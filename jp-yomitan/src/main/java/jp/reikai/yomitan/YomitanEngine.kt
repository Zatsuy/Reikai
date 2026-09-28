package jp.reikai.yomitan

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import logcat.logcat
import okhttp3.OkHttpClient
import java.io.Closeable
import java.lang.ref.WeakReference

/**
 * How the app sets up the engine (see the fork's DI graph in the app).
 *
 * @property lookupEnabled the lookup switch (D-025); while it is off the engine never starts.
 * @property isLookupEnabled its current value, read when a screen asks for the engine.
 * @property storage where Yomitan's `chrome.storage.local` (its settings) lives.
 * @property httpClient the client for Yomitan's cross-origin requests; it must send no cookies.
 * @property localServer answers `localhost:8765` (AnkiConnect 3.2, local audio 3.3).
 * @property pageOpener opens the pages Yomitan asks for (search 3.4, settings 3.5); by default only
 *   web links open, in the browser.
 * @property debug debug builds: DevTools, Yomitan's console in logcat.
 * @property onReady runs each time Yomitan's backend is ready; `newSettings` when it started without
 *   any settings stored (so Yomitan has just made its defaults), or a start before it did and never
 *   got as far as this.
 */
class YomitanConfig(
    val lookupEnabled: Flow<Boolean>,
    val isLookupEnabled: () -> Boolean,
    val storage: YomitanStorage,
    val httpClient: () -> OkHttpClient,
    val localServer: LocalServer = LocalServer(),
    val pageOpener: YomitanPageOpener? = null,
    val debug: Boolean = false,
    val idleGraceMillis: Long = 60_000,
    val onReady: suspend (engine: YomitanEngine, newSettings: Boolean) -> Unit = { _, _ -> },
)

/** The longest dictionary match at the start of a text ([YomitanEngine.findTerms]). */
data class TermMatch(
    /** How many characters of the text the longest match covers (Yomitan's `originalTextLength`). */
    val length: Int,
    /** The headwords of the best entries, best first: the dictionary form, its reading, and the text matched. */
    val headwords: List<Headword>,
)

data class Headword(val term: String, val reading: String, val matched: String)

/**
 * Yomitan's backend, running unmodified in a hidden WebView, and the host for every WebView that
 * shows one of Yomitan's pages. App-scoped; one per process.
 *
 * Lifecycle: nothing runs until a screen calls [acquire] (the reader of a Japanese novel, search,
 * settings, the lookup activity); the engine then starts and stays while any [Lease] is open, and
 * stops [YomitanConfig.idleGraceMillis] after the last one closes, at once when lookup is switched
 * off (D-025), and on memory pressure once idle. When WebView's renderer crashes it restarts, if
 * still in use; when the system killed it to free memory, or the engine failed, it starts again when
 * a screen next needs it ([ready]). The hidden WebView sits in the window of the newest lease's
 * activity as one transparent pixel (a WebView outside any window is treated as hidden and throttled).
 *
 * Main-thread only, except the suspend functions, which may be called from anywhere.
 */
class YomitanEngine(context: Context, private val config: YomitanConfig) {

    sealed interface State {
        /** Lookup is switched off. */
        data object Off : State

        data object Stopped : State

        data object Starting : State

        /** Yomitan's backend is prepared; [startMillis] from loading `background.html` to that. */
        data class Ready(val startMillis: Long) : State

        data class Failed(val reason: String) : State
    }

    /** One screen's use of the engine; close it when the screen goes. */
    inner class Lease internal constructor(host: Activity?) : Closeable {
        internal val host = host?.let(::WeakReference)
        private var open = true

        override fun close() {
            checkMainThread()
            if (!open) return
            open = false
            release(this)
        }
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hubHost = EngineHubHost()
    internal val hub = YomitanHub(hubHost)
    internal val server = EngineServer(app.assets, config.httpClient, config.localServer)
    private val network = NetworkBridge(config.httpClient, config.localServer, scope)
    private val scripts = YomitanScripts(app.assets, config.debug)
    private val saves = YomitanSaves(java.io.File(app.cacheDir, "jp-yomitan-saves"), scope)

    /** The screens hosting Yomitan's pages, by their hub view, for what only a screen can do (save a file). */
    private val listeners = HashMap<Int, YomitanPageListener>()

    /** Those pages' WebViews (not the engine's own), whose screens the pages they ask for open over. */
    private val pageViews = HashMap<Int, WebView>()

    private val stateFlow = MutableStateFlow<State>(if (config.isLookupEnabled()) State.Stopped else State.Off)
    val state: StateFlow<State> = stateFlow.asStateFlow()

    private val leases = LinkedHashSet<Lease>()
    private var wrapper: MutableContextWrapper? = null
    private var holder: EngineHolder? = null
    private var engineView: WebView? = null
    private var engineBinding: HubBinding? = null
    private var loadStartedAt = 0L

    /**
     * This start of the engine found no settings stored, or an earlier start that never got as far as
     * the backend being ready did (Yomitan may have stored its new defaults before the renderer died).
     */
    private var startedWithoutSettings = false
    private var graceJob: Job? = null
    private var watchdog: Job? = null
    private val restarts = ArrayDeque<Long>()

    /** Extension APIs Yomitan used that the stand-in lacks (`trip`) or only stubs (`called`). */
    val tripwire: StateFlow<Set<String>> get() = tripwireFlow
    private val tripwireFlow = MutableStateFlow<Set<String>>(emptySet())

    init {
        scope.launch {
            config.lookupEnabled.distinctUntilChanged().collect { enabled ->
                if (!enabled) {
                    stop(State.Off)
                } else if (stateFlow.value == State.Off) {
                    stateFlow.value = State.Stopped
                    if (leases.isNotEmpty()) start()
                }
            }
        }
        scope.launch {
            config.storage.replaced.collect {
                if (engineView == null) return@collect
                logcat(TAG, LogPriority.INFO) { "Yomitan's settings were replaced: restarting the engine" }
                stop(State.Stopped)
                if (leases.isNotEmpty()) start()
            }
        }
        app.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    @Suppress("DEPRECATION")
                    if (leases.isEmpty() && engineView != null && level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                        stop(State.Stopped)
                    }
                }

                override fun onConfigurationChanged(newConfig: Configuration) {}

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {}
            },
        )
        (app as? Application)?.registerActivityLifecycleCallbacks(HostWatcher())
    }

    /**
     * Starts the engine if needed and holds it until the lease is closed; [host] is the screen's
     * activity (the hidden WebView joins its window). Null while lookup is switched off.
     */
    fun acquire(host: Activity? = null): Lease? {
        checkMainThread()
        if (!config.isLookupEnabled()) return null
        val lease = Lease(host)
        leases += lease
        graceJob?.cancel()
        graceJob = null
        when (stateFlow.value) {
            State.Stopped, is State.Failed -> start()
            State.Off -> {
                stateFlow.value = State.Stopped
                start()
            }
            else -> placeEngineView()
        }
        return lease
    }

    /**
     * Waits for Yomitan's backend while the engine starts; true once it is ready. While a screen holds
     * the engine, an engine that stopped (WebView's renderer was killed to free memory) or failed is
     * started again first; otherwise this starts nothing.
     */
    suspend fun ready(): Boolean = withContext(Dispatchers.Main.immediate) {
        val now = stateFlow.value
        if ((now == State.Stopped || now is State.Failed) && leases.isNotEmpty()) start()
        stateFlow.first { it != State.Starting } is State.Ready
    }

    /**
     * Calls one of Yomitan's backend actions (`js/background/backend.js`'s API map), as its own pages
     * do, and returns its result. Throws [YomitanException] when the engine is not running or the
     * action fails.
     */
    suspend fun api(action: String, params: JsonElement = JsonNull): JsonElement {
        val payload = buildJsonObject {
            put("action", action)
            put("params", params)
        }
        val response =
            parseObject(callBackend("api", payload.toString())) ?: throw YomitanException("$action: no response")
        (response["error"] as? JsonObject)?.let {
            throw YomitanException(it["message"]?.jsonPrimitive?.contentOrNull ?: "$action failed")
        }
        return response["result"] ?: JsonNull
    }

    /**
     * The longest dictionary word at the start of [text] (the reader passes the text from a tapped
     * character on), with Yomitan's deinflection and the user's dictionaries; null when nothing
     * matches. Throws [YomitanException] when the engine is not running.
     */
    suspend fun findTerms(text: String, limit: Int = 5): TermMatch? {
        val payload = buildJsonObject {
            put("text", text)
            put("limit", limit)
        }
        val result = parseObject(callBackend("findTerms", payload.toString()))
            ?: throw YomitanException("findTerms: no response")
        val length = result["length"]?.jsonPrimitive?.int ?: 0
        val headwords = result["entries"]?.jsonArray.orEmpty().flatMap { entry ->
            entry.jsonObject["headwords"]?.jsonArray.orEmpty().map {
                val o = it.jsonObject
                Headword(
                    term = o["term"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    reading = o["reading"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    matched = o["matched"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }.distinct()
        return if (length <= 0 || headwords.isEmpty()) null else TermMatch(length, headwords)
    }

    /**
     * Hosts one of Yomitan's pages in [webView] (a screen's own): the stand-in and the hub join it,
     * the engine origin is served to it, and the engine is held until [YomitanPage.close]. Call before
     * the first load, then [YomitanPage.load]. Null while lookup is switched off.
     */
    fun attach(
        webView: WebView,
        kind: PageKind,
        host: Activity? = null,
        listener: YomitanPageListener = YomitanPageListener.None,
    ): YomitanPage? {
        require(kind != PageKind.ENGINE) { "the engine's own WebView is not attached" }
        val lease = acquire(host) ?: return null
        if (!YomitanWebViews.supported) {
            // The engine reports it as State.Failed; binding the page would throw.
            lease.close()
            return null
        }
        YomitanWebViews.configure(webView)
        var page: YomitanPage? = null
        val binding = bind(webView, kind, listener) { _ ->
            page?.close()
            destroyWebView(webView)
            listener.onRenderProcessGone()
        }
        return YomitanPage(webView, kind, binding, lease).also { page = it }
    }

    // --- starting and stopping ----------------------------------------------------------------------

    private fun start() {
        if (!YomitanWebViews.supported) {
            stateFlow.value = State.Failed("This device's WebView is too old for the lookup engine")
            return
        }
        stop(State.Starting)
        val wrapper = MutableContextWrapper(app).also { wrapper = it }
        val view = WebView(wrapper)
        if (config.debug) WebView.setWebContentsDebuggingEnabled(true)
        YomitanWebViews.configure(view)
        engineBinding = bind(view, PageKind.ENGINE, YomitanPageListener.None, ::onEngineRendererGone)
        engineView = view
        holder = EngineHolder(wrapper).apply {
            alpha = 0f
            addView(view, FrameLayout.LayoutParams(1, 1))
        }
        placeEngineView()
        loadStartedAt = SystemClock.elapsedRealtime()
        startedWithoutSettings = startedWithoutSettings || config.storage.get(listOf(OPTIONS_KEY)).isEmpty()
        view.loadUrl(YomitanOrigin.url("background.html"))
        watchdog = scope.launch {
            delay(START_TIMEOUT_MILLIS)
            if (stateFlow.value == State.Starting) {
                logcat(TAG, LogPriority.ERROR) {
                    "Yomitan's backend did not start within ${START_TIMEOUT_MILLIS / 1000} s"
                }
                stop(State.Failed("Yomitan's backend did not start"))
            }
        }
    }

    /** Destroys the engine's WebView (if any) and settles everything waiting on its backend. */
    private fun stop(next: State) {
        graceJob?.cancel()
        graceJob = null
        watchdog?.cancel()
        watchdog = null
        engineBinding?.detach()
        engineBinding = null
        holder?.let { (it.parent as? ViewGroup)?.removeView(it) }
        holder = null
        engineView?.destroy()
        engineView = null
        wrapper = null
        stateFlow.value = next
    }

    private fun release(lease: Lease) {
        leases -= lease
        if (leases.isNotEmpty()) {
            placeEngineView()
            return
        }
        if (engineView == null) return
        graceJob?.cancel()
        graceJob = scope.launch {
            delay(config.idleGraceMillis)
            if (leases.isEmpty()) stop(State.Stopped)
        }
    }

    private fun onEngineRendererGone(crashed: Boolean) {
        // WebView's renderer is one process for the whole app: every Yomitan document died with it.
        hub.forgetAll()
        val now = SystemClock.elapsedRealtime()
        if (crashed) restarts.addLast(now)
        while (restarts.isNotEmpty() && now - restarts.first() > RESTART_WINDOW_MILLIS) restarts.removeFirst()
        when (afterRendererGone(stateFlow.value == State.Off, leases.isNotEmpty(), crashed, restarts.size)) {
            RendererGone.STAY_OFF -> Unit
            RendererGone.STOP -> stop(State.Stopped)
            RendererGone.FAIL -> stop(State.Failed("WebView's renderer keeps dying"))
            RendererGone.RESTART -> start()
        }
    }

    /** Puts the hidden WebView into the window of the newest lease whose activity is alive. */
    private fun placeEngineView(gone: Activity? = null) {
        val holder = holder ?: return
        // Not leases.reversed(): that compiles to LinkedHashSet.reversed(), new in Android 15 (API 35).
        val activity = leases.toList().asReversed().firstNotNullOfOrNull { lease ->
            lease.host?.get()?.takeIf { it !== gone && !it.isFinishing && !it.isDestroyed }
        }
        val target = activity?.window?.decorView as? ViewGroup
        if (target != null && holder.parent === target) return
        (holder.parent as? ViewGroup)?.removeView(holder)
        wrapper?.baseContext = activity ?: app
        target?.addView(holder, FrameLayout.LayoutParams(1, 1))
    }

    private inner class HostWatcher : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(activity: Activity) {
            if (holder?.context?.let { (it as? MutableContextWrapper)?.baseContext } ===
                activity
            ) {
                placeEngineView(gone = activity)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    // --- the hub's side -----------------------------------------------------------------------------

    @SuppressLint("RequiresFeature")
    private fun bind(
        webView: WebView,
        kind: PageKind,
        listener: YomitanPageListener,
        onGone: (crashed: Boolean) -> Unit,
    ): HubBinding {
        val viewId = hub.registerView(kind)
        val origins = setOf(YomitanOrigin.ORIGIN)
        val script = WebViewCompat.addDocumentStartJavaScript(webView, scripts.standIn(kind), origins)
        WebViewCompat.addWebMessageListener(webView, YomitanOrigin.HUB_NAME, origins) {
                _,
                message,
                sourceOrigin,
                isMainFrame,
                proxy,
            ->
            val data = message.data ?: return@addWebMessageListener
            hub.onMessage(viewId, sourceOrigin.toString(), isMainFrame, proxy, ProxyPort(proxy), data)
        }
        val binding = HubBinding(this, webView, kind, viewId, script)
        listeners[viewId] = listener
        if (kind != PageKind.ENGINE) pageViews[viewId] = webView
        webView.webViewClient = YomitanWebViewClient(this, { binding }, kind, listener, onGone)
        webView.webChromeClient = YomitanChromeClient(config.debug, listener)
        return binding
    }

    /** A WebView left the hub ([HubBinding.detach]). */
    internal fun detached(viewId: Int) {
        listeners.remove(viewId)
        pageViews.remove(viewId)
        saves.dropView(viewId)
    }

    private suspend fun callBackend(op: String, payload: String): String = withContext(Dispatchers.Main.immediate) {
        if (!ready()) throw YomitanException("The lookup engine is not running")
        suspendCancellableCoroutine { continuation ->
            hub.callBackend(op, payload) { result -> if (continuation.isActive) continuation.resumeWith(result) }
        }
    }

    /** Opens a page Yomitan asked for; [from] is the WebView of the page that asked (null: the backend). */
    internal fun openPage(how: String, url: String?, from: WebView?): JsonElement? {
        val request = when {
            how == "options" -> YomitanPageRequest.Settings(YomitanOrigin.url("settings.html"))
            url == null -> null
            else -> YomitanPageRequest.of(url)
        } ?: return null
        val opened = config.pageOpener?.open(request, from?.context) ?: openInBrowser(request)
        logcat(TAG) { "open $how ${request.url}: ${if (opened) "opened" else "not handled"}" }
        return if (!opened) {
            null
        } else {
            buildJsonObject {
                put("id", -1)
                put("url", request.url)
            }
        }
    }

    private fun openInBrowser(request: YomitanPageRequest): Boolean {
        if (request !is YomitanPageRequest.External) return false
        return runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(request.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }

    private inner class EngineHubHost : HubHost {
        override val localStorage: YomitanStorage get() = config.storage

        override fun openPage(how: String, url: String?, viewId: Int) =
            this@YomitanEngine.openPage(how, url, pageViews[viewId])

        override fun fetch(request: FetchRequest, done: (FetchResult) -> Unit) = network.fetch(request, done)

        override fun save(viewId: Int, step: String, id: Int?, payload: String, done: (Result<String>) -> Unit) =
            saves.step(viewId, step, id, payload, { view, file -> listeners[view]?.onDownload(file) == true }, done)

        override fun onBackendReady() {
            if (stateFlow.value != State.Starting) return
            watchdog?.cancel()
            val ms = SystemClock.elapsedRealtime() - loadStartedAt
            logcat(TAG, LogPriority.INFO) { "Yomitan's backend is ready after $ms ms" }
            stateFlow.value = State.Ready(ms)
            val newSettings = startedWithoutSettings
            startedWithoutSettings = false
            scope.launch {
                runCatching { config.onReady(this@YomitanEngine, newSettings) }
                    .onFailure { logcat(TAG, LogPriority.WARN) { "After the engine started: $it" } }
            }
        }

        override fun onTripwire(path: String, called: Boolean, url: String) {
            val entry = if (called) "called $path" else path
            if (entry in tripwireFlow.value) return
            tripwireFlow.value += entry
            logcat(TAG, LogPriority.WARN) { "tripwire: $entry (${url.substringAfter(YomitanOrigin.ORIGIN)})" }
        }

        override fun log(level: Char, text: String) {
            val priority = when (level) {
                'E' -> LogPriority.ERROR
                'W' -> LogPriority.WARN
                else -> LogPriority.INFO
            }
            logcat(TAG, priority) { text }
        }
    }

    /** Holds the hidden WebView: one transparent pixel that never takes a touch or focus. */
    private class EngineHolder(context: Context) : FrameLayout(context) {
        init {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            isFocusable = false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
    }

    internal enum class RendererGone { STAY_OFF, STOP, FAIL, RESTART }

    companion object {
        const val TAG = "Yomitan"
        private const val START_TIMEOUT_MILLIS = 30_000L

        /** Where Yomitan keeps its settings in `chrome.storage.local` (`options-util.js`). */
        private const val OPTIONS_KEY = "options"
        private const val RESTART_WINDOW_MILLIS = 5 * 60_000L
        private const val MAX_RESTARTS = 3

        /**
         * What the engine does when WebView's renderer died with [crashes] crashes in the last five
         * minutes. A renderer the system killed to free memory ([crashed] false) is not started again at
         * once, which could only get it killed again: [ready] starts it when a screen next needs it.
         */
        internal fun afterRendererGone(off: Boolean, inUse: Boolean, crashed: Boolean, crashes: Int) = when {
            off -> RendererGone.STAY_OFF
            !inUse || !crashed -> RendererGone.STOP
            crashes > MAX_RESTARTS -> RendererGone.FAIL
            else -> RendererGone.RESTART
        }

        /** The backend's answer as a JSON object; null when it answered nothing (no listener responded). */
        private fun parseObject(text: String): JsonObject? =
            runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject

        private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) {
            "YomitanEngine is main-thread only"
        }
    }
}

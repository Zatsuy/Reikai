package jp.reikai.yomitan

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import logcat.logcat
import java.io.Closeable

/** WebView setup shared by the engine and every screen hosting a Yomitan page. */
object YomitanWebViews {

    /** Whether this device's WebView can host the engine at all. */
    val supported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /** JavaScript and DOM storage on; file and content access off (everything is served over https). */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // Yomitan plays a word's audio when asked, which is always a user's tap.
            mediaPlaybackRequiresUserGesture = false
        }
    }
}

/** Callbacks for a screen hosting a Yomitan page ([YomitanEngine.attach]), on the main thread except [intercept]. */
interface YomitanPageListener {
    /** A main-frame page finished loading. */
    fun onPageFinished(url: String) {}

    /**
     * The WebView's renderer died. The engine has already dropped the page's documents, closed its
     * [YomitanPage] and destroyed the WebView; the screen may attach a new one.
     */
    fun onRenderProcessGone() {}

    /**
     * A chance to answer a request before the engine does (the debug engine check serves test files).
     * Called on WebView's background thread.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? = null

    object None : YomitanPageListener
}

/**
 * A Yomitan page hosted in a screen's WebView: settings, search, or the popup's results. Holds a use
 * of the engine until [close]. Created by [YomitanEngine.attach].
 */
class YomitanPage internal constructor(
    val webView: WebView,
    val kind: PageKind,
    private val binding: HubBinding,
    private val lease: YomitanEngine.Lease,
) : Closeable {

    /** Loads one of Yomitan's pages, e.g. `load("search.html?query=猫")`. */
    fun load(path: String) = webView.loadUrl(YomitanOrigin.url(path))

    /**
     * Detaches the page from the engine and ends its use of it. The WebView stays the screen's to
     * destroy (after this, while it is still attached to its window, to avoid a blank flash).
     */
    override fun close() {
        binding.detach()
        lease.close()
    }
}

/** Builds the stand-in script for each kind of page, with Yomitan's manifest embedded. */
internal class YomitanScripts(private val assets: AssetManager, private val debug: Boolean) {

    private val standIn by lazy { assets.open("jp-reikai/stand-in.js").bufferedReader().use { it.readText() } }
    private val manifest by lazy {
        Json.parseToJsonElement(assets.open("yomitan/manifest.json").bufferedReader().use { it.readText() })
    }

    fun standIn(kind: PageKind): String {
        val config = buildJsonObject {
            put("origin", YomitanOrigin.ORIGIN)
            put("manifest", manifest)
            put("kind", kind.jsName)
            put("debug", debug)
        }
        // The file is a function expression; this calls it.
        return "$standIn\n($config);\n"
    }
}

/** One WebView joined to the hub: the injected stand-in and the message listener. */
internal class HubBinding(
    private val engine: YomitanEngine,
    val webView: WebView,
    val kind: PageKind,
    val viewId: Int,
    private val script: ScriptHandler,
) {
    private var attached = true

    fun detach() {
        if (!attached) return
        attached = false
        engine.hub.unregisterView(viewId)
        script.remove()
        WebViewCompat.removeWebMessageListener(webView, YomitanOrigin.HUB_NAME)
    }
}

/** [DocPort] over a document's web message reply proxy. */
internal class ProxyPort(private val proxy: JavaScriptReplyProxy) : DocPort {
    override val binary: Boolean get() = BINARY

    @SuppressLint("RequiresFeature")
    override fun post(text: String): Boolean = try {
        proxy.postMessage(text)
        true
    } catch (e: IllegalStateException) {
        false
    }

    @SuppressLint("RequiresFeature")
    override fun post(bytes: ByteArray): Boolean = try {
        proxy.postMessage(bytes)
        true
    } catch (e: IllegalStateException) {
        false
    }

    private companion object {
        val BINARY by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER) }
    }
}

/** Serves the engine origin to a WebView of Yomitan's and keeps the hub told about its documents. */
internal class YomitanWebViewClient(
    private val engine: YomitanEngine,
    private val binding: () -> HubBinding?,
    private val kind: PageKind,
    private val listener: YomitanPageListener,
    private val onGone: () -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        listener.intercept(request) ?: engine.server.intercept(request, kind)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        // The backend never navigates; Yomitan's own pages move between each other in place.
        if (kind == PageKind.ENGINE) return true
        val url = request.url.toString()
        // The popup stays on its page: Yomitan's other pages (a link to its settings) open where the
        // app opens them.
        if (YomitanOrigin.owns(url) && (kind != PageKind.POPUP || request.url.path == "/popup.html")) return false
        engine.openPage("tab", url)
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        // The page the WebView leaves has vanished; the new one (which may already have said hello) stays.
        binding()?.let { engine.hub.forgetView(it.viewId, keepUrl = url) }
    }

    override fun onPageFinished(view: WebView, url: String) = listener.onPageFinished(url)

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        logcat(LogPriority.WARN) { "Yomitan ${kind.jsName} WebView lost its renderer (crashed=${detail.didCrash()})" }
        binding()?.let { engine.hub.forgetView(it.viewId) }
        onGone()
        return true
    }
}

/** Yomitan's console output in logcat (errors always, the rest in debug builds). */
internal class YomitanChromeClient(private val debug: Boolean) : WebChromeClient() {
    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        val priority = when (message.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> LogPriority.ERROR
            ConsoleMessage.MessageLevel.WARNING -> LogPriority.WARN
            else -> LogPriority.DEBUG
        }
        if (debug || priority == LogPriority.ERROR) {
            logcat(YomitanEngine.TAG, priority) {
                "console ${message.sourceId()?.substringAfterLast('/')}:${message.lineNumber()} ${message.message()}"
            }
        }
        return true
    }
}

/** Removes [view] from its parent and destroys it. */
internal fun destroyWebView(view: WebView) {
    (view.parent as? ViewGroup)?.removeView(view)
    view.destroy()
}

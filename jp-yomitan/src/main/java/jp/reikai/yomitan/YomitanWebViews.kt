package jp.reikai.yomitan

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
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
import java.io.File

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

    /**
     * A file input of the page asks for files (Yomitan's settings: dictionary zips, a settings
     * backup). Return true and answer [callback] exactly once (null when the user cancels), or false
     * to refuse, as WebView's own `onShowFileChooser`.
     */
    fun onShowFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean =
        false

    /**
     * The page saved a file (Yomitan's settings export, only from a settings page). The screen moves
     * [YomitanDownload.file] where the user wants it and deletes it; return false to refuse it (the
     * engine then deletes it and the page's save fails).
     */
    fun onDownload(download: YomitanDownload): Boolean = false

    /**
     * The picture for Anki's `{screenshot}` of the lookup on the page (the book's cover), or null
     * when there is none: Yomitan then adds the card with that field empty.
     */
    fun picture(): LookupPicture? = null

    object None : YomitanPageListener
}

/**
 * A lookup's picture for Anki's `{screenshot}`, which in a browser is a screenshot of the page: in
 * Reikai JP, the cover of the book the word was looked up in.
 */
fun interface LookupPicture {
    /** The picture as JPEG, at most [maxSize] pixels on its longer side; null when there is none. Off the main thread. */
    suspend fun jpeg(maxSize: Int): ByteArray?
}

/** A file a Yomitan page saved: [file] is a temporary copy in the app's cache, named [name] by the page. */
class YomitanDownload(val name: String, val mimeType: String, val file: File)

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

/**
 * A reader's chapter pages joined to the hub ([YomitanEngine.attachContent]): the stand-in runs in
 * content mode in each of their documents. The WebView stays the reader's, and so does its
 * WebViewClient, which answers the engine origin's files through [serve].
 */
class YomitanContent internal constructor(
    private val binding: HubBinding,
    private val server: EngineServer,
) : Closeable {

    /** The engine origin's files for the chapter page (Yomitan's modules), or null for another request. */
    fun serve(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.scheme != "https" || url.host != YomitanOrigin.HOST || url.port != -1) return null
        if (request.method != "GET") return null
        return server.serveAsset(url.path.orEmpty())
    }

    /** Leaves the hub; call before the WebView is destroyed. */
    override fun close() = binding.detach()
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
        engine.detached(viewId)
        script.remove()
        WebViewCompat.removeWebMessageListener(webView, YomitanOrigin.HUB_NAME)
    }

    internal companion object {
        /** The listener the stand-in of documents of [origins] in [webView] talks to, routed to the hub. */
        @SuppressLint("RequiresFeature")
        fun listen(engine: YomitanEngine, webView: WebView, viewId: Int, origins: Set<String>) {
            WebViewCompat.addWebMessageListener(webView, YomitanOrigin.HUB_NAME, origins) {
                    _,
                    message,
                    sourceOrigin,
                    isMainFrame,
                    proxy,
                ->
                val data = message.data ?: return@addWebMessageListener
                engine.hub.onMessage(viewId, sourceOrigin.toString(), isMainFrame, proxy, ProxyPort(proxy), data)
            }
        }
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
    private val onGone: (crashed: Boolean) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        listener.intercept(request) ?: engine.server.intercept(request, kind)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        // The backend never navigates; Yomitan's own pages move between each other in place.
        if (kind == PageKind.ENGINE) return true
        val url = request.url.toString()
        // The popup stays on its page, and Yomitan's settings open only in the settings screen: a link
        // to them ("Go to Dictionaries settings") opens where the app opens them.
        val path = request.url.path
        val staysHere = when (kind) {
            PageKind.POPUP -> path == "/popup.html"
            PageKind.SETTINGS -> true
            else -> path != "/settings.html"
        }
        if (YomitanOrigin.owns(url) && staysHere) return false
        engine.openPage("tab", url, view)
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
        onGone(detail.didCrash())
        return true
    }
}

/** Yomitan's console output in logcat (errors always, the rest in debug builds), and its file inputs. */
internal class YomitanChromeClient(
    private val debug: Boolean,
    private val listener: YomitanPageListener,
) : WebChromeClient() {

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = listener.onShowFileChooser(filePathCallback, fileChooserParams)

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

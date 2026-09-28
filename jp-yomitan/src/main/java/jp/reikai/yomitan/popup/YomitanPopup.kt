package jp.reikai.yomitan.popup

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.os.SystemClock
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import jp.reikai.yomitan.PageKind
import jp.reikai.yomitan.YomitanEngine
import jp.reikai.yomitan.YomitanOrigin
import jp.reikai.yomitan.YomitanPage
import jp.reikai.yomitan.YomitanPageListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import logcat.logcat
import java.io.Closeable

/**
 * Yomitan's own results page (`popup.html`) in a WebView the app places: the one lookup popup of
 * every surface (the reader's "Look up", "Look up in Reikai JP" from any app, Phase 4's tap). Yomitan
 * draws the results, plays audio, adds cards and navigates between words as in a browser; the app
 * only chooses where the page sits (a sheet) and what it looks up ([PopupLookup]).
 *
 * Kept ready between lookups: [prepare] loads the page (with an empty lookup, which makes Yomitan
 * load its settings) so the next [show] only swaps the lookup in (`assets/jp-reikai/popup-host.js`). The WebView lives in a [MutableContextWrapper], so
 * the same prepared page moves between activities ([moveTo]). Main thread only.
 */
class YomitanPopup private constructor(
    private val wrapper: MutableContextWrapper,
    val webView: WebView,
) : Closeable {

    /** What the app hears from the page, on the main thread. */
    interface Listener {
        /**
         * A lookup is on screen (its first result, or "no results"): [token] is [show]'s, [pageMillis]
         * the page's own time from receiving it, [shownAt] the uptime of the frame after it painted.
         */
        fun onShown(token: Int, pageMillis: Double, shownAt: Long) {}

        /** Yomitan's close button. */
        fun onCloseRequested() {}

        /** WebView's renderer died: this popup is closed; get a new one. */
        fun onGone() {}
    }

    var listener: Listener? = null

    /** Yomitan's display is prepared, so a lookup can be swapped in without loading the page. */
    var ready = false
        private set

    var closed = false
        private set

    private lateinit var page: YomitanPage
    private var nextToken = 0
    private var loading = false

    /** The page theme the page was prepared with ([PopupLookup.dark]); Yomitan sets it once per load. */
    private var theme: Boolean? = null

    /** A lookup asked for before the page was ready, and its token. */
    private var pending: Pair<PopupLookup, Int>? = null

    /**
     * Loads the page with nothing looked up, in the page theme [dark] (see [PopupLookup.dark]), so the
     * first lookup is as quick as the next.
     */
    fun prepare(dark: Boolean?) {
        if (closed || ((loading || ready) && dark == theme)) return
        ready = false
        loading = true
        theme = dark
        page.load(PopupUrls.empty(dark))
    }

    /** Looks [lookup] up; returns the token [Listener.onShown] reports it with. */
    fun show(lookup: PopupLookup): Int {
        val token = ++nextToken
        if (closed) return token
        if (ready && (lookup.dark == null || lookup.dark == theme)) {
            swapIn(lookup, token)
        } else {
            // First, or in the other theme: the page loads (again) first.
            pending = lookup to token
            prepare(lookup.dark ?: theme)
        }
        return token
    }

    private fun swapIn(lookup: PopupLookup, token: Int) {
        val url = JsonPrimitive(PopupUrls.page(lookup))
        webView.evaluateJavascript("__reikaiPopup.show($url, ${PopupUrls.state(lookup)}, $token)", null)
    }

    /** Empties the page (after the sheet closes), so the next lookup never opens on the last one. */
    fun clear() {
        if (ready && !closed) webView.evaluateJavascript("__reikaiPopup.clear()", null)
    }

    /** Moves the WebView to [context]'s activity (take it out of its old parent first). */
    fun moveTo(context: Context) {
        wrapper.baseContext = context
    }

    override fun close() {
        if (closed) return
        closed = true
        ready = false
        page.close()
        webView.destroy()
    }

    private fun onMessage(text: String) {
        val message = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
        when (message["t"]?.jsonPrimitive?.contentOrNull) {
            "ready" -> {
                ready = true
                loading = false
                pending?.let { (lookup, token) -> swapIn(lookup, token) }
                pending = null
            }
            "shown" -> {
                val token = message["token"]?.jsonPrimitive?.intOrNull ?: return
                val ms = message["ms"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val shownAt = SystemClock.uptimeMillis()
                listener?.onShown(token, ms, shownAt)
            }
            "close" -> listener?.onCloseRequested()
        }
    }

    companion object {
        private const val CHANNEL = "reikaiPopup"

        /**
         * A new popup for [host]'s window, attached to the engine (which it starts, and holds until
         * [close]); null while lookup is switched off.
         */
        @SuppressLint("RequiresFeature")
        fun create(engine: YomitanEngine, host: Activity): YomitanPopup? {
            val wrapper = MutableContextWrapper(host)
            val webView = WebView(wrapper)
            val popup = YomitanPopup(wrapper, webView)
            val page = engine.attach(
                webView,
                PageKind.POPUP,
                host,
                object : YomitanPageListener {
                    override fun onRenderProcessGone() {
                        popup.closed = true
                        popup.ready = false
                        popup.listener?.onGone()
                    }
                },
            )
            if (page == null) {
                webView.destroy()
                return null
            }
            popup.page = page
            val origins = setOf(YomitanOrigin.ORIGIN)
            WebViewCompat.addDocumentStartJavaScript(webView, "${hostScript(host)}\n();\n", origins)
            WebViewCompat.addWebMessageListener(webView, CHANNEL, origins) { _, message, sourceOrigin, isMainFrame, _ ->
                if (isMainFrame && sourceOrigin.toString().trimEnd('/') == YomitanOrigin.ORIGIN) {
                    message.data?.let(popup::onMessage)
                }
            }
            logcat(LogPriority.DEBUG) { "Yomitan popup created" }
            return popup
        }

        @Volatile private var script: String? = null

        private fun hostScript(context: Context): String = script ?: context.assets.open("jp-reikai/popup-host.js")
            .bufferedReader().use { it.readText() }.also { script = it }
    }
}

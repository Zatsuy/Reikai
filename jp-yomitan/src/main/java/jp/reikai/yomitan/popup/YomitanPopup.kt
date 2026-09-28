package jp.reikai.yomitan.popup

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
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
import kotlinx.serialization.json.booleanOrNull
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
 * load its settings) so the next [show] only swaps the lookup in (`assets/jp-reikai/popup-host.js`).
 * Yomitan reads its settings once per load, so when they or the dictionaries change the page loads
 * again: shortly after the last change while no lookup is on it, else with the next lookup or [clear]. The WebView lives in
 * a [MutableContextWrapper], so the same prepared page moves between activities ([moveTo]). Main
 * thread only.
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

    /** Yomitan's history holds a lookup before the one on screen (a word tapped in the results). */
    var canGoBack = false
        private set

    private lateinit var page: YomitanPage
    private var nextToken = 0
    private var loading = false

    /** Which load of the page is current: an answer from an earlier one is ignored. */
    private var load = 0

    /** The page theme the page was prepared with ([PopupLookup.dark]); Yomitan sets it once per load. */
    private var theme: Boolean? = null

    /** A lookup asked for before the page was ready, and its token. */
    private var pending: Pair<PopupLookup, Int>? = null

    /** The token of the lookup on the page (or waiting for it), -1 for none. */
    private var current = -1

    /** Yomitan's settings or dictionaries changed since the page loaded them. */
    private var stale = false

    /**
     * Loads a stale page again while nothing is on it: once the changes have stopped, since Yomitan's
     * settings page reports every control it changes, and a load costs the renderer each time.
     */
    private val handler = Handler(Looper.getMainLooper())
    private val reloadIdle = Runnable { if (!closed && stale && current < 0) reload(theme) }

    /**
     * Loads the page with nothing looked up, in the page theme [dark] (see [PopupLookup.dark]), so the
     * first lookup is as quick as the next.
     */
    fun prepare(dark: Boolean?) {
        if (closed || ((loading || ready) && dark == theme && !stale)) return
        reload(dark)
    }

    private fun reload(dark: Boolean?) {
        handler.removeCallbacks(reloadIdle)
        ready = false
        loading = true
        stale = false
        canGoBack = false
        theme = dark
        page.load(PopupUrls.empty(dark, ++load))
    }

    /** Looks [lookup] up; returns the token [Listener.onShown] reports it with. */
    fun show(lookup: PopupLookup): Int {
        val token = ++nextToken
        if (closed) return token
        current = token
        if (ready && !stale && (lookup.dark == null || lookup.dark == theme)) {
            swapIn(lookup, token)
        } else {
            // First, in the other theme, or with changed settings: the page loads (again) first.
            pending = lookup to token
            prepare(lookup.dark ?: theme)
        }
        return token
    }

    /** Whether the page still has the lookup [show] returned [token] for (no other lookup or load since). */
    fun holds(token: Int): Boolean = !closed && token == current

    private fun swapIn(lookup: PopupLookup, token: Int) {
        val url = JsonPrimitive(PopupUrls.page(lookup))
        webView.evaluateJavascript("__reikaiPopup.show($url, ${PopupUrls.state(lookup)}, $token)", null)
    }

    /** Empties the page (after the sheet closes), so the next lookup never opens on the last one. */
    fun clear() {
        current = -1
        pending = null
        canGoBack = false
        if (closed) return
        if (stale) {
            reload(theme)
        } else if (ready) {
            webView.evaluateJavascript("__reikaiPopup.clear()", null)
        }
    }

    /** Goes back to the lookup before the one on screen, as Yomitan's own back button does. */
    fun goBack() {
        if (ready && !closed && canGoBack) webView.evaluateJavascript("__reikaiPopup.back()", null)
    }

    /** Moves the WebView to [context]'s activity (take it out of its old parent first). */
    fun moveTo(context: Context) {
        wrapper.baseContext = context
    }

    override fun close() {
        if (closed) return
        closed = true
        ready = false
        handler.removeCallbacks(reloadIdle)
        page.close()
        webView.destroy()
    }

    private fun onMessage(text: String) {
        val message = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
        val fromLoad = message["load"]?.jsonPrimitive?.intOrNull
        when (message["t"]?.jsonPrimitive?.contentOrNull) {
            "ready" -> {
                if (fromLoad != load) return
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
            "stale" -> {
                if (fromLoad != load || closed) return
                logcat(LogPriority.DEBUG) { "Yomitan popup: settings or dictionaries changed" }
                stale = true
                // Nothing on the page: load it again soon, so the next lookup is quick.
                handler.removeCallbacks(reloadIdle)
                if (current < 0) handler.postDelayed(reloadIdle, STALE_RELOAD_MILLIS)
            }
            "nav" -> {
                // An earlier load's word on its way out never gives the new page a "back".
                if (fromLoad != load) return
                canGoBack = message["back"]?.jsonPrimitive?.booleanOrNull == true
            }
            "close" -> listener?.onCloseRequested()
        }
    }

    companion object {
        private const val CHANNEL = "reikaiPopup"

        /** How long after the last change of settings or dictionaries an idle page loads again. */
        private const val STALE_RELOAD_MILLIS = 1_500L

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

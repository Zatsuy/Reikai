package jp.reikai.reader.page

import android.annotation.SuppressLint
import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import jp.reikai.yomitan.YomitanContent
import jp.reikai.yomitan.YomitanEngine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import logcat.logcat

/**
 * Lookup by tap in the Japanese reader's page (4.3). While it is bound, the page's documents load
 * Yomitan's own scanner (`jp-yomitan/src/main/assets/jp-reikai/reader-scan.js`, from Yomitan's origin,
 * which the viewport serves through [YomitanEngine.attachContent]) with the stand-in in content mode:
 * a tap on a character is searched there, and its word, sentence and place on screen come here
 * through the `reikaiReader` listener (the chapter origin's main frame only), for the lookup sheet.
 * Main thread only.
 */
internal class JpPageLookup(
    private val viewport: JpPageViewport,
    private val webView: WebView,
    private val engine: () -> YomitanEngine,
    private val listener: Listener,
) : JpPageViewport.Listener {

    interface Listener {
        /** A tap found a word; [word] is where it is on the screen (its top to bottom, screen pixels). */
        fun onFound(found: JpScanMessage.Found, word: IntRange?, askedAt: Long)

        /** A tap on text came before the engine was ready: it is looked up once the engine is. */
        fun onWait()

        /** The page's address and title for Yomitan (Anki's `{url}`, profile conditions). */
        fun context(): Pair<String?, String?>
    }

    private var content: YomitanContent? = null

    val bound: Boolean get() = content != null

    /** Joins the page to the engine; the next document loads the scanner. */
    @SuppressLint("RequiresFeature")
    fun bind() {
        if (content != null) return
        val joined = engine().attachContent(webView, JpPageDocument.ORIGIN) ?: return
        content = joined
        viewport.requestInterceptor = joined::serve
        WebViewCompat.addWebMessageListener(webView, CHANNEL, setOf(JpPageDocument.ORIGIN)) {
                _,
                message,
                sourceOrigin,
                isMainFrame,
                _,
            ->
            if (!isMainFrame || !sourceOrigin.isChapterOrigin()) return@addWebMessageListener
            val document = viewport.documentId ?: return@addWebMessageListener
            message.data?.let { JpScanMessage.parse(it, document) }?.let(::onMessage)
        }
        viewport.lookup = true
    }

    /**
     * Leaves the engine (lookup switched off, or the page going); the next document has no scanner. The
     * page on screen stops handing taps on text to its scanner, which is joined to nothing now, so they
     * open the menu again as without lookup.
     */
    @SuppressLint("RequiresFeature")
    fun unbind() {
        val joined = content ?: return
        content = null
        viewport.lookup = false
        viewport.requestInterceptor = null
        runCatching { WebViewCompat.removeWebMessageListener(webView, CHANNEL) }
        runCatching { joined.close() }
    }

    /** The word's highlight goes (the sheet closed); a selection the reader made since stays. */
    fun clear() {
        if (content != null) viewport.runInPage("window.__reikaiReader && __reikaiReader.clear()")
    }

    override fun onDestroy(webView: WebView) = unbind()

    private fun onMessage(message: JpScanMessage) {
        if (content == null) return
        when (message) {
            JpScanMessage.Ready -> setContext()
            JpScanMessage.Wait -> listener.onWait()
            is JpScanMessage.Found -> {
                logcat(LogPriority.DEBUG) { "Japanese reader: a tap found a word in ${message.millis} ms" }
                listener.onFound(message, wordOnScreen(message.rects), SystemClock.uptimeMillis() - message.millis)
            }
            JpScanMessage.Empty -> Unit
            is JpScanMessage.Failed -> logcat(LogPriority.WARN) { "Japanese reader lookup failed: ${message.message}" }
        }
    }

    private fun setContext() {
        val (url, title) = listener.context()
        val context = buildJsonObject {
            url?.let { put("url", it) }
            title?.let { put("title", it) }
        }
        viewport.runInPage("window.__reikaiReader && __reikaiReader.setContext($context)")
    }

    /** The word's boxes (CSS pixels of the page, one per device-independent pixel) on the screen. */
    private fun wordOnScreen(boxes: List<JpScanMessage.Box>): IntRange? {
        if (boxes.isEmpty()) return null
        val at = IntArray(2)
        webView.getLocationOnScreen(at)
        val scale = webView.resources.displayMetrics.density
        val top = at[1] + (boxes.minOf { it.top } * scale).toInt()
        val bottom = at[1] + (boxes.maxOf { it.bottom } * scale).toInt()
        return top..bottom
    }

    private fun Uri.isChapterOrigin(): Boolean = scheme == "https" && host == JpPageDocument.HOST && port == -1

    private companion object {
        /** The page's `window.reikaiReader`. */
        const val CHANNEL = "reikaiReader"
    }
}

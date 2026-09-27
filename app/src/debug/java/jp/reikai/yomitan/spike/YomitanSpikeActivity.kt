package jp.reikai.yomitan.spike

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.json.JSONObject

/**
 * Roadmap 2.1, the Yomitan spike: can Yomitan's own, unmodified code run inside the app? A debug-only
 * screen started over adb by scripts/fork/yomitan_spike.py, which reads its results from logcat
 * (tag [SpikeHub.TAG]). Throwaway: Phase 3 builds the real engine from what this proves.
 *
 * - An invisible engine WebView runs Yomitan's backend (`background.html`, as in its Firefox build).
 * - The spike page (`/__reikai/spike.html`) drives imports and lookups through Yomitan's own API.
 * - All documents share one private origin, [SpikeFiles.ORIGIN], and a stand-in for the extension
 *   APIs (`stand-in.js`, injected at document start) whose messages [SpikeHub] routes.
 */
class YomitanSpikeActivity : ComponentActivity() {

    private lateinit var files: SpikeFiles
    private lateinit var hub: SpikeHub
    private lateinit var root: FrameLayout
    private lateinit var engine: WebView
    private lateinit var spike: WebView
    private lateinit var reader: WebView
    private lateinit var anki: SpikeAnki
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var engineStart = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)
        files = SpikeFiles(this)
        anki = SpikeAnki(this, files)
        hub = SpikeHub(standInScript())
        hub.onOther = ::onPageMessage
        root = FrameLayout(this)
        setContentView(root)

        spike = newWebView(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // The engine never shows anything, but a WebView outside the window or with no size may be
        // throttled, so it stays attached as one transparent pixel.
        engine = newWebView(1, 1).apply { alpha = 0f }
        engineStart = SystemClock.elapsedRealtime()
        Log.i(SpikeHub.TAG, "MARK engine_load 0")
        engine.loadUrl("${SpikeFiles.ORIGIN}/background.html")
        spike.loadUrl("${SpikeFiles.ORIGIN}/__reikai/spike.html")
        handleCommand(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommand(intent)
    }

    private fun handleCommand(intent: Intent?) {
        val command = intent?.getStringExtra("cmd") ?: return
        Log.i(SpikeHub.TAG, "command $command")
        val json = JSONObject(command)
        when (json.getString("do")) {
            "reader" -> openReader()
            "anki-setup" -> background { anki.setUp(json.getString("deck")) }
            "anki-find" -> background { anki.find(json.getString("query")) }
            "anki-cleanup" -> background {
                val ids = json.getJSONArray("noteIds").let { a -> (0 until a.length()).map { a.getLong(it) } }
                anki.cleanUp(json.getString("deck"), ids)
            }
            else -> hub.postToPath("/__reikai/spike", JSONObject().put("t", "cmd"), command)
        }
    }

    /** Runs a slow AnkiDroid call off the main thread and logs its outcome as the command's result. */
    private fun background(work: () -> String) {
        io.execute {
            val outcome = runCatching(work)
            Log.i(
                SpikeHub.TAG,
                outcome.fold({
                    "RESULT anki $it"
                }, { "RESULT error ${JSONObject.quote(it.toString())}" }),
            )
            if (outcome.isSuccess) Log.i(SpikeHub.TAG, "RESULT done \"anki\"")
        }
    }

    /** A visible WebView with vertical Japanese text, where Yomitan's own content script scans taps. */
    private fun openReader() {
        if (::reader.isInitialized) {
            reader.reload()
            return
        }
        // The spike page has done its part; one pixel, like the engine, so memory reads as engine plus reader.
        spike.layoutParams = FrameLayout.LayoutParams(1, 1)
        reader = newWebView(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        reader.loadUrl("${SpikeFiles.ORIGIN}/__reikai/reader.html")
    }

    private fun onPageMessage(header: JSONObject, payload: String, reply: (String) -> Unit) {
        when (header.getString("t")) {
            // A timing mark, in milliseconds since the engine WebView started loading.
            "mark" -> Log.i(
                SpikeHub.TAG,
                "MARK ${header.getString("name")} ${SystemClock.elapsedRealtime() - engineStart}",
            )
            "result" -> Log.i(SpikeHub.TAG, "RESULT ${header.getString("name")} $payload")
            "tripwire" -> reply(JSONObject().put("paths", org.json.JSONArray(hub.tripwire.toList())).toString())
            "anki" -> io.execute {
                val response = anki.handle(payload)
                runOnUiThread { reply(response) }
            }
            // Places in the reader, in its CSS pixels, turned into screen pixels adb can tap.
            "rects" -> {
                val origin = IntArray(2).also { reader.getLocationOnScreen(it) }
                val scale = resources.displayMetrics.density
                val rects = JSONObject(payload)
                val onScreen = JSONObject()
                rects.keys().forEach { key ->
                    val r = rects.getJSONObject(key)
                    onScreen.put(
                        key,
                        org.json.JSONArray()
                            .put((origin[0] + r.getDouble("x") * scale).toInt())
                            .put((origin[1] + r.getDouble("y") * scale).toInt()),
                    )
                }
                Log.i(SpikeHub.TAG, "RESULT ${header.optString("name", "rects")} $onScreen")
                if (header.optBoolean("done")) Log.i(SpikeHub.TAG, "RESULT done \"reader\"")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(width: Int, height: Int): WebView = WebView(this).apply {
        layoutParams = FrameLayout.LayoutParams(width, height)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if ("${url.scheme}://${url.host}" != SpikeFiles.ORIGIN) return null
                return files.open(url.path ?: "/")
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val priority = when (message.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                    ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                    else -> Log.INFO
                }
                Log.println(
                    priority,
                    SpikeHub.TAG,
                    "console ${message.sourceId()?.substringAfterLast(
                        '/',
                    )}:${message.lineNumber()} ${message.message()}",
                )
                return true
            }
        }
        hub.attach(this)
        root.addView(this)
    }

    /** A pushed copy wins over the packaged one, so the stand-in can change without a rebuild. */
    private fun standInScript(): String {
        val pushed = java.io.File(files.root, "reikai/stand-in.js")
        if (pushed.isFile) return pushed.readText()
        return assets.open("jp-reikai/yomitan-spike/stand-in.js").bufferedReader().use { it.readText() }
    }

    override fun onDestroy() {
        root.removeAllViews()
        engine.destroy()
        spike.destroy()
        if (::reader.isInitialized) reader.destroy()
        io.shutdown()
        super.onDestroy()
    }
}

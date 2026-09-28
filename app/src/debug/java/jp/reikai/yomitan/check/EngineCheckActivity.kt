package jp.reikai.yomitan.check

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import jp.reikai.di.jpGraph
import jp.reikai.yomitan.PageKind
import jp.reikai.yomitan.YomitanEngine
import jp.reikai.yomitan.YomitanOrigin
import jp.reikai.yomitan.YomitanPage
import jp.reikai.yomitan.YomitanPageListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * The Yomitan engine check (debug builds only): drives the production engine ([YomitanEngine]) the
 * way later screens will, so an agent can prove it on a device. Started over adb by
 * scripts/fork/yomitan_check.py, which reads its `RESULT` lines from logcat (tag [TAG]).
 *
 * Commands arrive as `--es cmd <JSON>`: `{"do":"state"}`, `{"do":"lookup","text":".."}`,
 * `{"do":"api","action":"..","params":{..}}`, `{"do":"settings"}` then
 * `{"do":"import","url":".."}` (Yomitan's own settings page imports from a URL, through its Worker
 * path), `{"do":"search","query":".."}` (Yomitan's search page, pictures included), `{"do":"purge"}`
 * (the settings page's "delete all dictionaries"), `{"do":"close"}` (close the pages),
 * `{"do":"release"}` / `{"do":"acquire"}` (end or take this screen's use of the engine),
 * `{"do":"switch","on":false}` (the lookup switch, D-025), `{"do":"crash"}` (kill WebView's renderer).
 * Test files pushed to `<external files>/yomitan-check/` are served at `/__reikai/check/<name>`.
 */
class EngineCheckActivity : ComponentActivity() {

    private val engine: YomitanEngine by lazy { jpGraph.yomitanEngine }
    private lateinit var status: TextView
    private lateinit var pages: FrameLayout
    private var lease: YomitanEngine.Lease? = null
    private var page: YomitanPage? = null
    private var queue: Job? = null
    private val checkFiles by lazy { File(getExternalFilesDir(null), "yomitan-check") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { setPadding(24, 24, 24, 24) }
        pages = FrameLayout(this)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(status, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
        acquire()
        lifecycleScope.launch {
            engine.state.collect { state ->
                status.text = "Yomitan engine: $state"
                result("state", JSONObject().put("state", state.toString()))
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun acquire() {
        if (lease == null) lease = engine.acquire(this)
        Log.i(TAG, "MARK acquired ${lease != null}")
    }

    private fun handle(intent: Intent?) {
        val command = intent?.getStringExtra("cmd") ?: return
        val json = JSONObject(command)
        val previous = queue
        queue = lifecycleScope.launch {
            previous?.join()
            runCatching { run(json) }
                .onSuccess { result("done", JSONObject().put("do", json.getString("do"))) }
                .onFailure {
                    result("error", JSONObject().put("do", json.getString("do")).put("message", it.toString()))
                }
        }
    }

    private suspend fun run(command: JSONObject) {
        when (command.getString("do")) {
            "state" -> {
                val ready = engine.ready()
                result(
                    "state",
                    JSONObject().put("state", engine.state.value.toString()).put("ready", ready)
                        .put("tripwire", org.json.JSONArray(engine.tripwire.value.toList())),
                )
            }
            "acquire" -> acquire()
            "release" -> {
                lease?.close()
                lease = null
            }
            "lookup" -> {
                val started = System.nanoTime()
                val match = engine.findTerms(command.getString("text"))
                val ms = (System.nanoTime() - started) / 1_000_000.0
                val headwords = match?.headwords.orEmpty().map { "${it.term}【${it.reading}】(${it.matched})" }
                val found = JSONObject().put("ms", ms).put("length", match?.length ?: 0)
                result("lookup", found.put("headwords", org.json.JSONArray(headwords)))
            }
            "bench" -> bench(command.optInt("count", 200))
            "api" -> {
                val params = command.optJSONObject("params")?.toString()
                val element = engine.api(
                    command.getString("action"),
                    params?.let(Json::parseToJsonElement) ?: JsonNull,
                )
                result("api", JSONObject().put("result", element.toString().take(4000)))
            }
            "settings" -> open(PageKind.SETTINGS, "settings.html")
            "search" -> {
                val query = URLEncoder.encode(command.getString("query"), "UTF-8")
                open(PageKind.SEARCH, "search.html?query=$query")
            }
            "import" -> import(command.getString("url"))
            "purge" -> purge()
            "close" -> closePage()
            "switch" -> jpGraph.jpPreferences.lookupEnabled().set(command.getBoolean("on"))
            "crash" -> crashRenderer()
            else -> error("unknown command")
        }
    }

    private suspend fun open(kind: PageKind, path: String) {
        closePage()
        val webView = WebView(this)
        pages.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()
        val attached = engine.attach(
            webView,
            kind,
            this,
            object : YomitanPageListener {
                override fun onPageFinished(url: String) {
                    loaded.complete(Unit)
                }

                override fun intercept(request: WebResourceRequest): WebResourceResponse? = serveCheckFile(request)
            },
        ) ?: error("lookup is switched off")
        page = attached
        val started = System.nanoTime()
        attached.load(path)
        loaded.await()
        result("page", JSONObject().put("kind", kind.name).put("loadMs", (System.nanoTime() - started) / 1_000_000))
    }

    /** Kills WebView's renderer (shared by every WebView of the app) to check the engine restarts. */
    private fun crashRenderer() {
        val victim = WebView(this)
        victim.webViewClient = object : android.webkit.WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                view.destroy()
                return true
            }
        }
        victim.loadUrl("chrome://crash")
    }

    private fun closePage() {
        val current = page ?: return
        page = null
        current.close()
        pages.removeView(current.webView)
        current.webView.destroy()
    }

    /** Imports through Yomitan's settings page, as a user would with "Import from URL". */
    private suspend fun import(url: String) {
        val settings = page?.takeIf { it.kind == PageKind.SETTINGS } ?: error("open the settings page first")
        val before = dictionaryTitles()
        val started = System.nanoTime()
        settings.webView.evaluate(
            """(() => {
                const text = document.querySelector('#dictionary-import-url-text');
                text.value = ${JSONObject.quote(url)};
                document.querySelector('#dictionary-import-url-button').click();
                return 'clicked';
            })()""",
        )
        var progress = ""
        while (true) {
            delay(1000)
            val titles = dictionaryTitles()
            val added = titles - before
            val errors = settings.webView.evaluate(ERRORS_JS)
            if (errors.isNotBlank() && errors != "\"\"") error("Yomitan reported: $errors")
            if (added.isNotEmpty() && enabledInProfile(added.first())) {
                val ms = (System.nanoTime() - started) / 1_000_000
                result("import", JSONObject().put("title", added.first()).put("ms", ms))
                return
            }
            val now = settings.webView.evaluate(PROGRESS_JS)
            if (now != progress) {
                progress = now
                Log.i(TAG, "import progress $now")
            }
        }
    }

    private suspend fun purge() {
        val settings = page?.takeIf { it.kind == PageKind.SETTINGS } ?: error("open the settings page first")
        settings.webView.evaluate("document.querySelector('#dictionary-confirm-delete-all-button').click()")
        repeat(60) {
            delay(1000)
            val errors = settings.webView.evaluate(ERRORS_JS)
            if (errors.isNotBlank() && errors != "\"\"") error("Yomitan reported: $errors")
            if (dictionaryTitles().isEmpty()) {
                result("purge", JSONObject().put("dictionaries", 0))
                return
            }
        }
        error("dictionaries still there after 60 s")
    }

    private suspend fun bench(count: Int) {
        val sample = "その日の夕方、彼女は駅前の古い喫茶店で友達を待っていた。窓の外では雨が降り続いていて、通りを歩く人々は皆傘をさしていた。"
        val times = ArrayList<Double>()
        repeat(count) { n ->
            val start = n % (sample.length - 1)
            val started = System.nanoTime()
            engine.findTerms(sample.substring(start, minOf(sample.length, start + 16)))
            times += (System.nanoTime() - started) / 1_000_000.0
        }
        times.sort()
        fun pct(p: Double) = times[minOf(times.size - 1, (p * times.size).toInt())]
        val summary = JSONObject().put("count", count).put("p50", pct(0.5)).put("p95", pct(0.95))
        result("bench", summary.put("max", times.last()))
    }

    private suspend fun dictionaryTitles(): Set<String> =
        (engine.api("getDictionaryInfo") as JsonArray).map { it.jsonObject["title"]!!.jsonPrimitive.content }.toSet()

    private suspend fun enabledInProfile(title: String): Boolean {
        val options = engine.api("optionsGetFull").jsonObject
        val profile = options["profiles"]!!.jsonArray[0].jsonObject["options"]!!.jsonObject
        val dictionaries = profile["dictionaries"]!!.jsonArray
        return dictionaries.any { it.jsonObject["name"]?.jsonPrimitive?.content == title }
    }

    private fun serveCheckFile(request: WebResourceRequest): WebResourceResponse? {
        val path = request.url.path ?: return null
        if (request.url.host != YomitanOrigin.HOST || !path.startsWith(CHECK_PREFIX)) return null
        val file = File(checkFiles, path.removePrefix(CHECK_PREFIX)).canonicalFile
        if (!file.path.startsWith(checkFiles.canonicalPath + File.separator) || !file.isFile) return null
        val headers = mapOf("Cache-Control" to "no-store")
        return WebResourceResponse("application/zip", null, 200, "OK", headers, FileInputStream(file))
    }

    private fun result(name: String, value: JSONObject) = Log.i(TAG, "RESULT $name $value")

    override fun onDestroy() {
        closePage()
        lease?.close()
        lease = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "YomitanCheck"
        const val CHECK_PREFIX = "/__reikai/check/"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val ERRORS_JS = "document.querySelector('#dictionary-error')?.textContent?.trim() ?? ''"
        const val PROGRESS_JS = "[...document.querySelectorAll('.dictionary-import-progress .progress-info, " +
            ".progress-status')].map(e => e.textContent.trim()).filter(Boolean).join(' | ')"
    }
}

private suspend fun WebView.evaluate(script: String): String = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(script) { continuation.resume(it ?: "") }
}

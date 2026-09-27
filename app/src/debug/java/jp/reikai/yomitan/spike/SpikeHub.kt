package jp.reikai.yomitan.spike

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import org.json.JSONObject

/**
 * The browser's half of the browser-extension stand-in: routes `chrome.runtime.sendMessage` and
 * `chrome.tabs.sendMessage` between Yomitan's documents, which live in separate WebViews (one
 * WebView is one "tab"). Each message is a string `<header JSON>\n<payload>`; only the small header
 * is parsed here, the payload is passed through untouched.
 *
 * Everything runs on the main thread, where androidx.webkit delivers messages.
 */
class SpikeHub(private val standInScript: String) {

    private class Doc(
        val proxy: JavaScriptReplyProxy,
        val tabId: Int,
        val frameId: Int,
        val role: String,
        val url: String,
    )

    private class Pending(val from: Doc, val mid: Int, var remaining: Int, var done: Boolean = false)

    /** A `chrome.runtime.Port` between two documents (the backend opens them with `tabs.connect`). */
    private class PortEnds(val opener: Doc, val target: Doc) {
        fun other(proxy: JavaScriptReplyProxy) = if (proxy === opener.proxy) target else opener
    }

    private val docs = LinkedHashMap<JavaScriptReplyProxy, Doc>()
    private val pending = HashMap<Int, Pending>()
    private val ports = HashMap<String, PortEnds>()
    private val nextFrameId = HashMap<Int, Int>()
    private var nextTabId = 1
    private var nextRid = 1

    /** Called for every message the hub does not route itself (logs, Anki, spike results). */
    var onOther: (header: JSONObject, payload: String, reply: (String) -> Unit) -> Unit = { _, _, _ -> }

    val tripwire = linkedSetOf<String>()

    @SuppressLint("RequiresFeature")
    fun attach(webView: WebView): Int {
        val tabId = nextTabId++
        WebViewCompat.addDocumentStartJavaScript(webView, standInScript, setOf(SpikeFiles.ORIGIN))
        WebViewCompat.addWebMessageListener(webView, NAME, setOf(SpikeFiles.ORIGIN)) {
                _,
                message,
                _,
                isMainFrame,
                proxy,
            ->
            onMessage(tabId, isMainFrame, proxy, message)
        }
        return tabId
    }

    private fun onMessage(tabId: Int, isMainFrame: Boolean, proxy: JavaScriptReplyProxy, message: WebMessageCompat) {
        val data = message.data ?: return
        val cut = data.indexOf('\n').let { if (it < 0) data.length else it }
        val header = JSONObject(data.substring(0, cut))
        val payload = if (cut < data.length) data.substring(cut + 1) else ""
        when (header.getString("t")) {
            "hello" -> {
                val frameId = if (isMainFrame) 0 else nextFrameId.merge(tabId, 1, Int::plus)!!
                // A new main-frame document replaces the whole tab: its old frames are gone.
                if (isMainFrame) docs.values.removeAll { it.tabId == tabId }
                val doc = Doc(proxy, tabId, frameId, header.getString("role"), header.getString("url"))
                docs[proxy] = doc
                post(doc, JSONObject().put("t", "welcome").put("tabId", tabId).put("frameId", frameId), "")
            }
            "send" -> {
                val from = docs[proxy] ?: return
                // chrome.runtime.sendMessage reaches every extension page except the sender, never a content script.
                route(from, header.getInt("mid"), payload) { it !== from && it.role != "content" }
            }
            "tabsend" -> {
                val from = docs[proxy] ?: return
                val tab = header.getInt("tabId")
                val frame = header.optInt("frameId", -1)
                // Every frame of the tab hears it: content scripts and extension frames (Yomitan's popup).
                route(from, header.getInt("mid"), payload) {
                    it.tabId == tab && it.role != "backend" && (frame < 0 || it.frameId == frame)
                }
            }
            "connect" -> {
                val from = docs[proxy] ?: return
                val pid = header.getString("pid")
                val tab = header.getInt("tabId")
                val frame = header.optInt("frameId", -1)
                val target = docs.values.firstOrNull {
                    it.tabId == tab && it.role != "backend" &&
                        (frame < 0 || it.frameId == frame)
                }
                if (target == null) {
                    post(
                        from,
                        JSONObject().put(
                            "t",
                            "pdisc",
                        ).put("pid", pid).put("err", "Could not establish connection. Receiving end does not exist."),
                        "",
                    )
                } else {
                    ports[pid] = PortEnds(from, target)
                    post(
                        target,
                        JSONObject().put(
                            "t",
                            "onconnect",
                        ).put("pid", pid).put("name", header.optString("name")).put("sender", senderOf(from)),
                        "",
                    )
                }
            }
            "pmsg" -> {
                val ends = ports[header.getString("pid")] ?: return
                post(ends.other(proxy), JSONObject().put("t", "pmsg").put("pid", header.getString("pid")), payload)
            }
            "pdisc" -> {
                val pid = header.getString("pid")
                val ends = ports.remove(pid) ?: return
                post(ends.other(proxy), JSONObject().put("t", "pdisc").put("pid", pid), "")
            }
            "tabs" -> {
                // chrome.tabs.query: one tab per WebView showing a page (the backend is not a tab).
                val from = docs[proxy] ?: return
                val tabs = org.json.JSONArray()
                docs.values.filter { it.frameId == 0 && it.role != "backend" }.forEach {
                    tabs.put(JSONObject().put("id", it.tabId).put("url", it.url))
                }
                post(from, JSONObject().put("t", "other").put("mid", header.getInt("mid")), tabs.toString())
            }
            "resp" -> {
                val p = pending[header.getInt("rid")] ?: return
                if (p.done) return
                if (header.getBoolean("has")) {
                    p.done = true
                    pending.remove(header.getInt("rid"))
                    post(p.from, JSONObject().put("t", "reply").put("mid", p.mid), payload)
                } else if (--p.remaining <= 0) {
                    pending.remove(header.getInt("rid"))
                    replyError(p.from, p.mid, "The message port closed before a response was received.")
                }
            }
            "trip" -> {
                val path = header.getString("path")
                if (tripwire.add(path)) Log.w(TAG, "tripwire: $path (${docs[proxy]?.url})")
            }
            "log" -> Log.println(
                when (header.optString("level")) {
                    "error" -> Log.ERROR
                    "warn" -> Log.WARN
                    else -> Log.INFO
                },
                TAG,
                "[${docs[proxy]?.url?.let { Uri.parse(it).path } ?: "?"}] $payload",
            )
            else -> {
                val doc = docs[proxy]
                onOther(header, payload) { reply ->
                    if (doc !=
                        null
                    ) {
                        post(doc, JSONObject().put("t", "other").put("mid", header.optInt("mid")), reply)
                    }
                }
            }
        }
    }

    private fun route(from: Doc, mid: Int, payload: String, accept: (Doc) -> Boolean) {
        val targets = docs.values.filter(accept)
        if (targets.isEmpty()) {
            replyError(from, mid, "Could not establish connection. Receiving end does not exist.")
            return
        }
        val rid = nextRid++
        pending[rid] = Pending(from, mid, targets.size)
        val header = JSONObject().put("t", "msg").put("rid", rid).put("sender", senderOf(from))
        targets.forEach { post(it, header, payload) }
    }

    private fun senderOf(from: Doc) = JSONObject()
        .put("tabId", if (from.role == "backend") -1 else from.tabId)
        .put("frameId", from.frameId)
        .put("url", from.url)

    private fun replyError(to: Doc, mid: Int, error: String) =
        post(to, JSONObject().put("t", "reply").put("mid", mid).put("err", error), "")

    private fun post(to: Doc, header: JSONObject, payload: String) {
        try {
            to.proxy.postMessage("$header\n$payload")
        } catch (e: IllegalStateException) {
            // The document went away (navigation or a closed frame).
            docs.remove(to.proxy)
        }
    }

    /** Posts to every document whose path starts with [pathPrefix] (commands from adb to the spike page). */
    fun postToPath(pathPrefix: String, header: JSONObject, payload: String) {
        docs.values.filter {
            Uri.parse(it.url).path.orEmpty().startsWith(pathPrefix)
        }.forEach { post(it, header, payload) }
    }

    companion object {
        const val NAME = "reikaiHub"
        const val TAG = "YomitanSpike"
    }
}

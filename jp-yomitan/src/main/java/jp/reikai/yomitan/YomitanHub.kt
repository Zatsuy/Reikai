package jp.reikai.yomitan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/** One Yomitan document's end of the hub: its web message reply proxy on the device, a fake in tests. */
internal interface DocPort {
    /** Whether [post] of bytes arrives as an ArrayBuffer (WebView's WEB_MESSAGE_ARRAY_BUFFER). */
    val binary: Boolean

    /** Posts a message; false when the document is gone. */
    fun post(text: String): Boolean

    fun post(bytes: ByteArray): Boolean
}

/** What a document may do, decided by the hub from facts the page cannot fake. */
internal enum class DocRole {
    /** The main frame of the engine's WebView: Yomitan's backend. */
    BACKEND,

    /** An engine-origin main frame of a settings, search or popup WebView. */
    PAGE,

    /** An engine-origin frame inside another page (Yomitan's popup inside a chapter). */
    FRAME,

    /** A page of any other origin (a chapter with Yomitan's content script, Phase 4). */
    CONTENT,
}

internal class HubDoc(
    val key: Any,
    val port: DocPort,
    val viewId: Int,
    val kind: PageKind,
    val role: DocRole,
    val tabId: Int,
    val frameId: Int,
    val url: String,
) {
    /** Storage, network (AnkiConnect included) and downloads: the backend and the settings and search pages. */
    val trusted: Boolean
        get() = role == DocRole.BACKEND ||
            (role == DocRole.PAGE && (kind == PageKind.SETTINGS || kind == PageKind.SEARCH))

    override fun toString() = "$role#$tabId.$frameId $url"
}

/** A request the stand-in hands to the app's network (see NetworkBridge). */
internal class FetchRequest(val url: String, val method: String, val headers: Map<String, String>, val body: ByteArray?)

internal sealed interface FetchResult {
    class Ok(
        val status: Int,
        val statusText: String,
        val headers: Map<String, String>,
        val url: String,
        val body: ByteArray,
    ) : FetchResult

    class Failed(val message: String) : FetchResult
}

/** What the hub needs from the engine. Everything is called on the hub's (main) thread. */
internal interface HubHost {
    val localStorage: YomitanStorage

    /**
     * `how` is tab, window, update or options, asked by a document of the WebView [viewId]; returns
     * the tab to report back, if any.
     */
    fun openPage(how: String, url: String?, viewId: Int): JsonElement?

    /** Starts a request; [done] runs on the hub's thread. Returns a function that cancels it. */
    fun fetch(request: FetchRequest, done: (FetchResult) -> Unit): () -> Unit

    /**
     * One step of a file a settings page of the WebView [viewId] saves: `start` (payload
     * `{name, type, size}`, answered `{"id":..}`), `data` (a base64 slice of save [id]) or `end`.
     * [done] runs on the hub's thread with the answer's payload, or a failure.
     */
    fun save(viewId: Int, step: String, id: Int?, payload: String, done: (Result<String>) -> Unit)

    /**
     * Anki's `{screenshot}`: the picture of the lookup shown in the WebView [viewId] (the book's
     * cover), as a data URL; [done] runs on the hub's thread, with a failure when there is none.
     */
    fun capture(viewId: Int, done: (Result<String>) -> Unit)

    /** The backend finished preparing (it announced `applicationBackendReady`). */
    fun onBackendReady()

    /** Yomitan read an extension API the stand-in lacks (`trip`) or used a stub (`called`). */
    fun onTripwire(path: String, called: Boolean, url: String)

    fun log(level: Char, text: String)
}

/**
 * The browser's half of the extension stand-in: routes `chrome.runtime` and `chrome.tabs` messages
 * and ports between Yomitan's documents, which live in separate WebViews (one WebView is one tab),
 * answers storage, tab and network requests, and settles every request whose document went away.
 *
 * A document's rights come from its [DocRole]: the WebView it lives in ([PageKind]), whether it is
 * the main frame and its origin, all reported by WebView itself, never by the page. Chapter pages
 * (Phase 4) get only content-script messaging within their own tab.
 *
 * Wire format: `<header JSON>\n<payload>`, see the stand-in (`assets/jp-reikai/stand-in.js`). The hub
 * parses headers only; payloads pass through, except a chapter page's messages, whose action is
 * checked against [CONTENT_ACTIONS]. Not thread-safe: the engine calls it on the main thread.
 */
internal class YomitanHub(private val host: HubHost) {

    private class Pending(val from: HubDoc, val mid: Int, val targets: MutableSet<HubDoc>)

    /** A `chrome.runtime.Port` between two documents. */
    private class PortEnds(val opener: HubDoc, val target: HubDoc) {
        fun other(doc: HubDoc) = if (doc === opener) {
            target
        } else if (doc === target) {
            opener
        } else {
            null
        }
    }

    private class NativeCall(val doc: HubDoc, val done: (Result<String>) -> Unit)

    private class View(val kind: PageKind) {
        var nextFrameId = 1
    }

    private val views = HashMap<Int, View>()
    private val docs = LinkedHashMap<Any, HubDoc>()
    private val pending = HashMap<Int, Pending>()
    private val ports = HashMap<String, PortEnds>()
    private val fetches = HashMap<Pair<HubDoc, Int>, () -> Unit>()
    private val natives = HashMap<Int, NativeCall>()
    private val dead = ArrayDeque<HubDoc>()
    private var nextViewId = 1
    private var nextRid = 1
    private var nextNid = 1

    /** `chrome.storage.session`: lives as long as the app process, like a browser session. */
    private val session = LinkedHashMap<String, String>()

    /** Registers a WebView; its documents report through [onMessage] with the returned id. */
    fun registerView(kind: PageKind): Int {
        val id = nextViewId++
        views[id] = View(kind)
        return id
    }

    /**
     * The WebView is gone, navigating away, or lost its renderer: its documents vanished. A main
     * frame at [keepUrl] stays: a navigation's new document may say hello before WebView reports the
     * navigation's start.
     */
    fun forgetView(viewId: Int, keepUrl: String? = null) {
        docs.values.filter { it.viewId == viewId && !(it.frameId == 0 && it.url == keepUrl) }.forEach(::forget)
        flushDead()
    }

    fun unregisterView(viewId: Int) {
        forgetView(viewId)
        views.remove(viewId)
    }

    /** Every document vanished (the shared renderer process died). */
    fun forgetAll() {
        docs.values.toList().forEach(::forget)
        flushDead()
    }

    val hasBackend: Boolean get() = docs.values.any { it.role == DocRole.BACKEND }

    /**
     * Asks the backend document to run [op] (`api` or `findTerms`, see the stand-in) with [payload];
     * [done] gets the response's JSON text, or a failure when there is no backend or it went away.
     */
    fun callBackend(op: String, payload: String, done: (Result<String>) -> Unit) {
        val backend = docs.values.firstOrNull { it.role == DocRole.BACKEND }
        if (backend == null) {
            done(Result.failure(YomitanException("The engine is not running")))
            return
        }
        val nid = nextNid++
        natives[nid] = NativeCall(backend, done)
        post(
            backend,
            header("nreq") {
                put("nid", nid)
                put("op", op)
            },
            payload,
        )
        flushDead()
    }

    /**
     * A message from a document of the WebView [viewId]. [sourceOrigin] and [isMainFrame] come from
     * WebView; [key] identifies the document (its reply proxy).
     */
    fun onMessage(viewId: Int, sourceOrigin: String, isMainFrame: Boolean, key: Any, port: DocPort, data: String) {
        val cut = data.indexOf('\n').let { if (it < 0) data.length else it }
        val header = runCatching { Json.parseToJsonElement(data.substring(0, cut)).jsonObject }.getOrNull() ?: return
        val payload = if (cut < data.length) data.substring(cut + 1) else ""
        val type = header.str("t") ?: return
        if (type == "hello") {
            hello(viewId, sourceOrigin, isMainFrame, key, port, header)
        } else {
            val from = docs[key] ?: return
            try {
                handle(from, type, header, payload)
            } catch (e: Exception) {
                // A malformed message fails only itself: this runs on the main thread, where an
                // exception would take the whole app down.
                host.log('E', "hub: $type from $from failed: $e")
                header.int("mid")?.let { replyError(from, it, e.message ?: e.javaClass.simpleName) }
            }
        }
        flushDead()
    }

    private fun hello(
        viewId: Int,
        sourceOrigin: String,
        isMainFrame: Boolean,
        key: Any,
        port: DocPort,
        header: JsonObject,
    ) {
        val view = views[viewId] ?: return
        docs[key]?.let(::forget)
        // A new main-frame document replaces the whole tab: its old frames are gone.
        if (isMainFrame) docs.values.filter { it.viewId == viewId }.forEach(::forget)
        val role = when {
            sourceOrigin.trimEnd('/') != YomitanOrigin.ORIGIN -> DocRole.CONTENT
            !isMainFrame -> DocRole.FRAME
            view.kind == PageKind.ENGINE -> DocRole.BACKEND
            else -> DocRole.PAGE
        }
        val frameId = if (isMainFrame) 0 else view.nextFrameId++
        val tabId = if (role == DocRole.BACKEND) -1 else viewId
        val doc = HubDoc(key, port, viewId, view.kind, role, tabId, frameId, header.str("url").orEmpty())
        docs[key] = doc
        val welcome = header("welcome") {
            put("tabId", tabId)
            put("frameId", frameId)
            put("role", role.name.lowercase())
        }
        post(doc, welcome)
    }

    private fun handle(from: HubDoc, type: String, header: JsonObject, payload: String) {
        when (type) {
            "bye" -> forget(from)
            "send" -> send(from, header, payload)
            "tabsend" -> tabSend(from, header, payload)
            "resp" -> respond(from, header, payload)
            "connect" -> connect(from, header)
            "pmsg", "pdisc" -> portMessage(from, type, header, payload)
            "req" -> request(from, header, payload)
            "fabort" -> header.int("fid")?.let { fetches.remove(from to it)?.invoke() }
            "nresp" -> nativeResponse(from, header, payload)
            "trip", "called" -> header.str("path")?.let { host.onTripwire(it, type == "called", from.url) }
            "log" -> host.log(
                when (header.str("level")) {
                    "error" -> 'E'
                    "warn" -> 'W'
                    else -> 'I'
                },
                "[${from.url.substringAfter(YomitanOrigin.ORIGIN)}] $payload",
            )
        }
    }

    // --- chrome.runtime.sendMessage and chrome.tabs.sendMessage ------------------------------------

    private fun send(from: HubDoc, header: JsonObject, payload: String) {
        val mid = header.int("mid") ?: return
        if (from.role == DocRole.CONTENT && actionOf(payload) !in CONTENT_ACTIONS) {
            replyError(from, mid, "Reikai JP does not let a web page send this message")
            return
        }
        if (from.role == DocRole.BACKEND && header.str("action") == "applicationBackendReady") host.onBackendReady()
        // Reaches every extension document except the sender, never a content script (as in a browser).
        route(from, mid, payload) { it !== from && it.role != DocRole.CONTENT }
    }

    private fun tabSend(from: HubDoc, header: JsonObject, payload: String) {
        val mid = header.int("mid") ?: return
        val tab = header.int("tabId") ?: return
        if (from.role == DocRole.CONTENT && tab != from.tabId) {
            replyError(from, mid, "Reikai JP does not let a web page message another tab")
            return
        }
        val frame = header.int("frameId")
        // Every frame of the tab hears it: content scripts and extension frames (Yomitan's popup).
        route(from, mid, payload) {
            it !== from && it.tabId == tab && it.role != DocRole.BACKEND && (frame == null || it.frameId == frame)
        }
    }

    private fun route(from: HubDoc, mid: Int, payload: String, accept: (HubDoc) -> Boolean) {
        val targets = docs.values.filter(accept)
        if (targets.isEmpty()) {
            replyError(from, mid, NO_RECEIVER)
            return
        }
        val rid = nextRid++
        pending[rid] = Pending(from, mid, targets.toMutableSet())
        val msg = header("msg") {
            put("rid", rid)
            put("sender", senderOf(from))
        }
        targets.forEach { post(it, msg, payload) }
    }

    private fun respond(from: HubDoc, header: JsonObject, payload: String) {
        val rid = header.int("rid") ?: return
        val p = pending[rid] ?: return
        if (from !in p.targets) return
        if (header.bool("has") == true) {
            pending.remove(rid)
            post(p.from, header("reply") { put("mid", p.mid) }, payload)
        } else {
            p.targets.remove(from)
            if (p.targets.isEmpty()) {
                pending.remove(rid)
                replyError(p.from, p.mid, PORT_CLOSED)
            }
        }
    }

    // --- ports (chrome.tabs.connect) ----------------------------------------------------------------

    private fun connect(from: HubDoc, header: JsonObject) {
        val pid = header.str("pid") ?: return
        val tab = header.int("tabId") ?: return
        val frame = header.int("frameId")
        val target = docs.values.firstOrNull {
            it !== from && it.tabId == tab && it.role != DocRole.BACKEND && (frame == null || it.frameId == frame)
        }
        if (target == null || (from.role == DocRole.CONTENT && tab != from.tabId) || pid in ports) {
            post(
                from,
                header("pdisc") {
                    put("pid", pid)
                    put("err", NO_RECEIVER)
                },
            )
            return
        }
        ports[pid] = PortEnds(from, target)
        val onConnect = header("onconnect") {
            put("pid", pid)
            put("name", header.str("name").orEmpty())
            put("sender", senderOf(from))
        }
        post(target, onConnect)
    }

    private fun portMessage(from: HubDoc, type: String, header: JsonObject, payload: String) {
        val pid = header.str("pid") ?: return
        val ends = ports[pid] ?: return
        val other = ends.other(from) ?: return
        if (type == "pdisc") ports.remove(pid)
        post(other, header(type) { put("pid", pid) }, if (type == "pmsg") payload else "")
    }

    // --- requests the app answers -------------------------------------------------------------------

    private fun request(from: HubDoc, header: JsonObject, payload: String) {
        val mid = header.int("mid") ?: return
        val op = header.str("op")
        val needsTrust = op != "tabs" && op != "open"
        if (from.role == DocRole.CONTENT || (needsTrust && !from.trusted)) {
            replyError(from, mid, "Reikai JP does not let this page use $op")
            return
        }
        when (op) {
            "tabs" -> {
                // One tab per WebView showing a page; the backend is not a tab.
                val tabs = buildJsonArray {
                    docs.values.filter { it.frameId == 0 && it.role != DocRole.BACKEND }.forEach {
                        add(
                            buildJsonObject {
                                put("id", it.tabId)
                                put("url", it.url)
                            },
                        )
                    }
                }
                reply(from, mid, tabs.toString())
            }
            "sget", "sset", "sremove", "sclear" -> storage(from, mid, op, header.str("area"), payload)
            "open" -> {
                val body = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
                val tab = host.openPage(body?.str("how") ?: "tab", body?.str("url"), from.viewId)
                reply(from, mid, tab?.toString().orEmpty())
            }
            "fetch" -> fetch(from, mid, payload)
            "save" -> save(from, mid, header, payload)
            "capture" -> capture(from, mid, payload)
            else -> replyError(from, mid, "unknown request $op")
        }
    }

    /**
     * Anki's `{screenshot}`, which Yomitan's backend takes of the tab a lookup came from after telling
     * it to hide its popups (the stand-in names that tab): the picture of the lookup that tab shows.
     * Only the backend may ask.
     */
    private fun capture(from: HubDoc, mid: Int, payload: String) {
        if (from.role != DocRole.BACKEND) return replyError(from, mid, "Reikai JP does not let this page use capture")
        val tab = runCatching { Json.parseToJsonElement(payload).jsonObject.int("tabId") }.getOrNull()
        val view = docs.values.firstOrNull { it.tabId == tab && it.frameId == 0 && it.role != DocRole.BACKEND }?.viewId
            ?: return replyError(from, mid, NO_PICTURE)
        host.capture(view) { result ->
            if (docs[from.key] !== from) return@capture
            result.fold(
                onSuccess = { reply(from, mid, it) },
                onFailure = { replyError(from, mid, it.message ?: NO_PICTURE) },
            )
            flushDead()
        }
    }

    /** A file Yomitan's settings page saves (its settings export), in slices; only settings pages may. */
    private fun save(from: HubDoc, mid: Int, header: JsonObject, payload: String) {
        val step = header.str("step")
        if (from.role != DocRole.PAGE || from.kind != PageKind.SETTINGS || step == null) {
            return replyError(from, mid, "Reikai JP does not let this page save files")
        }
        host.save(from.viewId, step, header.int("id"), payload) { result ->
            if (docs[from.key] !== from) return@save
            result.fold(
                onSuccess = { reply(from, mid, it) },
                onFailure = { replyError(from, mid, it.message ?: "The file could not be saved") },
            )
            flushDead()
        }
    }

    private fun storage(from: HubDoc, mid: Int, op: String, area: String?, payload: String) {
        val body = runCatching { if (payload.isEmpty()) JsonNull else Json.parseToJsonElement(payload) }.getOrNull()
        val local = host.localStorage
        fun keys(): List<String> = (body as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val isLocal = when (area) {
            "local" -> true
            "session" -> false
            else -> return replyError(from, mid, "unknown storage area $area")
        }
        when (op) {
            "sget" -> {
                val wanted = if (body is JsonArray) keys() else null
                val values = when {
                    isLocal -> local.get(wanted)
                    wanted == null -> session.toMap()
                    else -> session.filterKeys { it in wanted }
                }
                reply(from, mid, JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString())
                return
            }
            "sset" -> {
                val items = (body as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                if (isLocal) local.set(items) else session.putAll(items)
            }
            "sremove" -> if (isLocal) local.remove(keys()) else keys().forEach { session.remove(it) }
            "sclear" -> if (isLocal) local.clear() else session.clear()
        }
        reply(from, mid, "")
    }

    private fun fetch(from: HubDoc, mid: Int, payload: String) {
        val body = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
        val url = body?.str("url")
        if (body == null || url == null) return replyError(from, mid, "bad fetch request")
        val request = FetchRequest(
            url = url,
            method = body.str("method") ?: "GET",
            headers = (body["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
            body = body.str("body")?.toByteArray() ?: body.str("bodyBase64")?.let { Base64.getDecoder().decode(it) },
        )
        val id = from to mid
        // Registered before the request starts: a request may fail at once, inside host.fetch.
        var cancel: () -> Unit = {}
        fetches[id] = { cancel() }
        cancel = host.fetch(request) { result ->
            if (fetches.remove(id) == null || docs[from.key] !== from) return@fetch
            when (result) {
                is FetchResult.Failed -> replyError(from, mid, result.message)
                is FetchResult.Ok -> {
                    val head = header("reply") {
                        put("mid", mid)
                        put("status", result.status)
                        put("statusText", result.statusText)
                        put("url", result.url)
                        put("headers", JsonObject(result.headers.mapValues { JsonPrimitive(it.value) }))
                        if (from.port.binary) put("bin", true)
                    }
                    if (from.port.binary) {
                        if (post(from, head)) post(from, result.body)
                    } else {
                        post(from, head, Base64.getEncoder().encodeToString(result.body))
                    }
                }
            }
            flushDead()
        }
    }

    private fun nativeResponse(from: HubDoc, header: JsonObject, payload: String) {
        val call = natives[header.int("nid") ?: return] ?: return
        if (call.doc !== from) return
        natives.remove(header.int("nid"))
        val err = header.str("err")
        call.done(if (err != null) Result.failure(YomitanException(err)) else Result.success(payload))
    }

    // --- documents that went away --------------------------------------------------------------------

    /**
     * Drops [doc]: requests waiting on it are answered as a browser would ("The message port closed
     * before a response was received."), its ports' other ends are disconnected, and its own pending
     * requests, fetches and native calls are dropped or failed. No request waits on a timer: Yomitan's
     * imports legitimately take minutes.
     */
    private fun forget(doc: HubDoc) {
        if (docs[doc.key] !== doc) return
        docs.remove(doc.key)
        pending.entries.removeAll { it.value.from === doc }
        pending.entries.toList().forEach { (rid, p) ->
            if (p.targets.remove(doc) && p.targets.isEmpty()) {
                pending.remove(rid)
                replyError(p.from, p.mid, PORT_CLOSED)
            }
        }
        ports.entries.toList().forEach { (pid, ends) ->
            val other = ends.other(doc) ?: return@forEach
            ports.remove(pid)
            post(other, header("pdisc") { put("pid", pid) })
        }
        fetches.keys.filter { it.first === doc }.forEach { fetches.remove(it)?.invoke() }
        natives.entries.toList().forEach { (nid, call) ->
            if (call.doc !== doc) return@forEach
            natives.remove(nid)
            call.done(Result.failure(YomitanException("The engine stopped before it answered")))
        }
    }

    private fun flushDead() {
        while (dead.isNotEmpty()) forget(dead.removeFirst())
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private fun senderOf(from: HubDoc) = buildJsonObject {
        put("tabId", from.tabId)
        put("frameId", from.frameId)
        put("url", from.url)
    }

    private fun reply(to: HubDoc, mid: Int, payload: String) = post(to, header("reply") { put("mid", mid) }, payload)

    private fun replyError(to: HubDoc, mid: Int, error: String) {
        post(
            to,
            header("reply") {
                put("mid", mid)
                put("err", error)
            },
        )
    }

    private fun post(to: HubDoc, header: JsonObject, payload: String = ""): Boolean {
        if (docs[to.key] !== to) return false
        val ok = runCatching { to.port.post("$header\n$payload") }.getOrDefault(false)
        if (!ok) dead.addLast(to)
        return ok
    }

    private fun post(to: HubDoc, bytes: ByteArray): Boolean {
        if (docs[to.key] !== to) return false
        val ok = runCatching { to.port.post(bytes) }.getOrDefault(false)
        if (!ok) dead.addLast(to)
        return ok
    }

    private inline fun header(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject {
            put("t", type)
            build()
        }

    private fun actionOf(payload: String): String? =
        runCatching { Json.parseToJsonElement(payload).jsonObject.str("action") }.getOrNull()

    internal companion object {
        const val NO_RECEIVER = "Could not establish connection. Receiving end does not exist."
        const val PORT_CLOSED = "The message port closed before a response was received."

        /** Anki's `{screenshot}` with no picture for the lookup; `popup-host.js` knows this text. */
        const val NO_PICTURE = "Reikai JP has no picture for this lookup"

        /**
         * What Yomitan's content script sends from a chapter page (the modules reachable from
         * `js/app/content-script-main.js` in 26.9.8.0). Left out on purpose: `optionsGetFull` and
         * `modifySettings` (profile switching by hotkey), which would let any web page read or
         * rewrite every setting, the Anki server address included. Phase 4 revisits this list.
         */
        val CONTENT_ACTIONS = setOf(
            "applicationReady", "requestBackendReadySignal", "heartbeat", "frameInformationGet",
            "optionsGet", "termsFind", "kanjiFind", "isTextLookupWorthy", "getZoom", "getEnvironmentInfo",
            "getStylesheetContent", "injectStylesheet", "logGenericErrorBackend", "broadcastTab",
            "sendMessageToFrame", "openCrossFramePort", "getOrCreateSearchPopup", "isTabSearchPopup",
        )
    }
}

/** A failure reported by the engine or by Yomitan's backend. */
class YomitanException(message: String) : Exception(message)

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

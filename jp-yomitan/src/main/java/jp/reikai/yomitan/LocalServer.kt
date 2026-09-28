package jp.reikai.yomitan

/**
 * A request Yomitan sent to `http://localhost:8765` or `http://127.0.0.1:8765`, the address desktop
 * users give AnkiConnect and local audio servers. Nothing listens there on a phone: the engine hands
 * these to the app's [LocalServer] instead, in process.
 *
 * @property path the URL's path, e.g. `/` (AnkiConnect) or `/localaudio/get/`.
 * @property query the decoded query parameters (first value of each).
 */
class LocalRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray?,
) {
    val bodyText: String get() = body?.toString(Charsets.UTF_8).orEmpty()

    /** True for an AnkiConnect call: a POST to the server's root with a JSON body. */
    val isAnkiConnect: Boolean get() = method == "POST" && (path == "/" || path.isEmpty())
}

class LocalResponse(
    val status: Int = 200,
    val contentType: String = "application/json",
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun json(text: String) = LocalResponse(body = text.toByteArray())
    }
}

/**
 * One handler of the local server: answers the requests it knows and returns null for the rest.
 * Anki (3.2) adds an AnkiConnect route, audio (3.3) `GET /localaudio/...` and `/tts/...` routes.
 * Called off the main thread.
 */
fun interface LocalRoute {
    suspend fun handle(request: LocalRequest): LocalResponse?

    companion object {
        /** AnkiConnect: [answer] gets the request's JSON text and returns the response's JSON text. */
        fun ankiConnect(answer: suspend (String) -> String) = LocalRoute { request ->
            if (request.isAnkiConnect) LocalResponse.json(answer(request.bodyText)) else null
        }

        /** GET requests whose path starts with [prefix]. */
        fun get(prefix: String, answer: suspend (LocalRequest) -> LocalResponse?) = LocalRoute { request ->
            if (request.method == "GET" && request.path.startsWith(prefix)) answer(request) else null
        }
    }
}

/**
 * The in-process stand-in for `localhost:8765`: the first of [routes] that answers wins. With no
 * answer the request fails as a refused connection would, which is what Yomitan expects when Anki is
 * not running.
 */
class LocalServer(private val routes: List<LocalRoute> = emptyList()) {

    suspend fun handle(request: LocalRequest): LocalResponse? = routes.firstNotNullOfOrNull { it.handle(request) }

    companion object {
        const val PORT = 8765

        /** Whether [host] and [port] name the local server. */
        fun matches(host: String?, port: Int): Boolean = port == PORT && (host == "localhost" || host == "127.0.0.1")
    }
}

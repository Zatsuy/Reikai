package jp.reikai.yomitan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException

/**
 * Runs the network requests Yomitan's pages cannot run themselves in a WebView: the backend's
 * cross-origin `fetch` (audio sources without CORS, jisho) on the app's OkHttp without cookies, and
 * anything for `localhost:8765` on the in-process [LocalServer]. Results come back on [scope]'s
 * (main) thread.
 */
internal class NetworkBridge(
    private val client: () -> OkHttpClient,
    private val localServer: LocalServer,
    private val scope: CoroutineScope,
) {

    fun fetch(request: FetchRequest, done: (FetchResult) -> Unit): () -> Unit {
        val url = request.url.toHttpUrlOrNull()
        if (url == null) {
            done(FetchResult.Failed("not an http(s) URL"))
            return {}
        }
        if (LocalServer.matches(url.host, url.port)) return local(url, request, done)
        // OkHttp refuses what a browser's fetch refuses too, e.g. a GET with a body.
        val okRequest = try {
            toOkHttp(url, request)
        } catch (e: IllegalArgumentException) {
            done(FetchResult.Failed(e.message ?: "invalid request"))
            return {}
        }
        val call = client().newCall(okRequest)
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) finish(done, FetchResult.Failed(e.message ?: e.javaClass.simpleName))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { it.toResult() } }
                        .getOrElse { FetchResult.Failed(it.message ?: it.javaClass.simpleName) }
                    finish(done, result)
                }
            },
        )
        return { call.cancel() }
    }

    private fun local(url: HttpUrl, request: FetchRequest, done: (FetchResult) -> Unit): () -> Unit {
        val job = scope.launch {
            val local = LocalRequest(
                method = request.method,
                path = url.encodedPath,
                query = url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() },
                headers = request.headers,
                body = request.body,
            )
            val response = withContext(Dispatchers.IO) { runCatching { localServer.handle(local) } }
            done(
                response.fold(
                    onSuccess = { answer ->
                        answer?.let {
                            FetchResult.Ok(
                                it.status,
                                "OK",
                                it.headers + ("content-type" to it.contentType),
                                url.toString(),
                                it.body,
                            )
                        } ?: FetchResult.Failed("connection refused (nothing in Reikai JP answers ${url.encodedPath})")
                    },
                    onFailure = {
                        logcat(LogPriority.WARN) { "local server ${url.encodedPath}: $it" }
                        FetchResult.Failed(it.message ?: it.javaClass.simpleName)
                    },
                ),
            )
        }
        return { job.cancel() }
    }

    private fun finish(done: (FetchResult) -> Unit, result: FetchResult) {
        scope.launch { done(result) }
    }

    private fun toOkHttp(url: HttpUrl, request: FetchRequest): Request {
        val headers = Headers.Builder()
        request.headers.forEach { (name, value) ->
            if (name.lowercase() !in DROPPED_REQUEST_HEADERS) headers.addUnsafeNonAscii(name, value)
        }
        val body = request.body?.toRequestBody(
            request.headers.entries.firstOrNull {
                it.key.equals("content-type", true)
            }?.value?.toMediaTypeOrNull(),
        )
        val method = request.method.uppercase()
        return Request.Builder()
            .url(url)
            .headers(headers.build())
            .method(
                method,
                body
                    ?: if (method == "POST" || method == "PUT" ||
                        method == "PATCH"
                    ) {
                        ByteArray(0).toRequestBody()
                    } else {
                        null
                    },
            )
            .build()
    }

    private fun Response.toResult(): FetchResult.Ok {
        val bytes = readCapped(body, MAX_BODY_BYTES)
        val headers = LinkedHashMap<String, String>()
        this.headers.names().forEach { name ->
            if (!name.equals("set-cookie", true)) {
                headers[name.lowercase()] =
                    this.headers.values(name).joinToString(", ")
            }
        }
        return FetchResult.Ok(code, message.ifEmpty { "OK" }, headers, request.url.toString(), bytes)
    }

    internal companion object {
        /** Cookies stay out (Yomitan's own request rules strip them); the rest OkHttp sets itself. */
        val DROPPED_REQUEST_HEADERS = setOf("cookie", "host", "content-length", "connection", "origin", "referer")

        /**
         * The largest answer handed to a page (a word's recording is tens of KB, a jisho page a few
         * hundred): the body is held in memory, and again as base64 on WebViews without binary messages.
         */
        const val MAX_BODY_BYTES = 32L * 1024 * 1024

        /** [body]'s bytes; an [IOException] when there are more than [max]. */
        fun readCapped(body: ResponseBody, max: Long): ByteArray {
            val length = body.contentLength()
            if (length > max) throw IOException("the answer is too large ($length bytes)")
            val source = body.source()
            if (source.request(max + 1)) throw IOException("the answer is larger than $max bytes")
            return source.readByteArray()
        }
    }
}

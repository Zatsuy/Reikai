package jp.reikai.yomitan

import android.content.res.AssetManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import logcat.logcat
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Answers the requests of the WebViews hosting Yomitan, from `shouldInterceptRequest` (a background
 * thread):
 * - the engine origin: Yomitan's vendored release at the root (`assets/yomitan/`, its pages use root
 *   paths), Reikai JP's files under [YomitanOrigin.FORK_PREFIX] (`assets/jp-reikai/`);
 * - `localhost:8765` GETs (a local audio file an `<audio>` element plays) from the [LocalServer];
 * - cross-origin GETs of the settings and search pages (dictionary import from a URL, the update
 *   check), which a WebView would refuse without CORS: run on OkHttp without cookies and answered
 *   with `Access-Control-Allow-Origin` for the engine origin. Navigations are not proxied.
 */
internal class EngineServer(
    private val assets: AssetManager,
    private val client: () -> OkHttpClient,
    private val localServer: LocalServer,
) {

    fun intercept(request: WebResourceRequest, kind: PageKind): WebResourceResponse? {
        val url = request.url
        val scheme = url.scheme ?: return null
        return when {
            scheme == "https" && url.host == YomitanOrigin.HOST -> serveAsset(url.path.orEmpty())
            scheme == "http" && LocalServer.matches(url.host, url.port) -> local(request)
            (scheme == "http" || scheme == "https") && proxies(kind, request) -> proxy(request)
            else -> null
        }
    }

    /** One of the engine origin's files, or 404. */
    fun serveAsset(path: String): WebResourceResponse {
        val clean = path.ifEmpty { "/" }
        val asset = when {
            clean.contains("..") || clean.endsWith("/") -> null
            clean.startsWith(YomitanOrigin.FORK_PREFIX) -> "jp-reikai/" + clean.removePrefix(YomitanOrigin.FORK_PREFIX)
            else -> "yomitan/" + clean.removePrefix("/")
        }
        val stream = asset?.let { runCatching { assets.open(it) }.getOrNull() } ?: return notFound()
        return WebResourceResponse(mimeType(clean), "utf-8", 200, "OK", assetHeaders(clean), stream)
    }

    private fun proxies(kind: PageKind, request: WebResourceRequest): Boolean =
        !request.isForMainFrame && (kind == PageKind.ENGINE || kind == PageKind.SETTINGS || kind == PageKind.SEARCH)

    private fun local(request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET") return null
        val url = request.url
        val answer = runCatching {
            runBlocking {
                localServer.handle(
                    LocalRequest(
                        method = "GET",
                        path = url.encodedPath.orEmpty(),
                        query = url.queryParameterNames.associateWith { url.getQueryParameter(it).orEmpty() },
                        headers = request.requestHeaders.orEmpty(),
                        body = null,
                    ),
                )
            }
        }.onFailure { logcat(LogPriority.WARN) { "local server ${url.encodedPath}: $it" } }.getOrNull()
            ?: return failure(404, "Not Found")
        return WebResourceResponse(
            answer.contentType.substringBefore(';').trim(),
            charsetOf(answer.contentType),
            answer.status,
            reasonOf(answer.status),
            answer.headers + CORS_HEADERS,
            ByteArrayInputStream(answer.body),
        )
    }

    private fun proxy(request: WebResourceRequest): WebResourceResponse? {
        if (request.method == "OPTIONS") {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                204,
                "No Content",
                CORS_HEADERS + mapOf(
                    "Access-Control-Allow-Methods" to "GET, HEAD",
                    "Access-Control-Allow-Headers" to "*",
                ),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        if (request.method != "GET" && request.method != "HEAD") return null
        val builder = Request.Builder().url(
            request.url.toString(),
        ).method(request.method, null).cacheControl(CacheControl.FORCE_NETWORK)
        request.requestHeaders.orEmpty().forEach { (name, value) ->
            if (name.lowercase() in FORWARDED_HEADERS) builder.header(name, value)
        }
        return try {
            val response = client().newCall(builder.build()).execute()
            // WebView refuses a redirect status here; OkHttp has followed any real redirect already.
            if (response.code in 300..399) {
                response.close()
                return failure(502, "Bad Gateway")
            }
            val headers = LinkedHashMap<String, String>()
            response.headers.names().forEach { name ->
                if (!name.equals("set-cookie", true) && !name.startsWith("access-control-", true)) {
                    headers[name] = response.headers.values(name).joinToString(", ")
                }
            }
            val contentType = response.header("Content-Type") ?: "application/octet-stream"
            WebResourceResponse(
                contentType.substringBefore(';').trim(),
                charsetOf(contentType),
                response.code,
                response.message.ifEmpty { reasonOf(response.code) },
                headers + CORS_HEADERS,
                ClosingStream(response.body.byteStream(), response),
            )
        } catch (e: IOException) {
            logcat(LogPriority.WARN) { "proxy ${request.url.host}: $e" }
            failure(502, "Bad Gateway")
        }
    }

    /** Closes the OkHttp response with the stream WebView reads, so its connection is released. */
    private class ClosingStream(stream: InputStream, private val response: okhttp3.Response) :
        java.io.FilterInputStream(stream) {
        override fun close() {
            try {
                super.close()
            } finally {
                response.close()
            }
        }
    }

    private fun notFound() = failure(404, "Not Found")

    private fun failure(status: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", status, reason, CORS_HEADERS, ByteArrayInputStream(ByteArray(0)))

    internal companion object {
        /** Engine files are public and fixed for an app version; chapter pages may import them (Phase 4). */
        val ASSET_HEADERS = mapOf("Cache-Control" to "no-cache", "Access-Control-Allow-Origin" to "*")

        /**
         * Only Yomitan's popup may sit in a frame of another site (a chapter page, Phase 4), as in a
         * browser, where it is the one page its manifest makes web-accessible. Its other pages
         * (settings, search, the backend) show only in the app's own WebViews or in frames of each
         * other, so a web page cannot frame them and trick a tap into changing a setting.
         */
        val SAME_ORIGIN_FRAMES = mapOf("Content-Security-Policy" to "frame-ancestors 'self'")

        fun assetHeaders(path: String): Map<String, String> =
            if (path.endsWith(".html") && path != "/popup.html") ASSET_HEADERS + SAME_ORIGIN_FRAMES else ASSET_HEADERS
        val CORS_HEADERS = mapOf("Access-Control-Allow-Origin" to YomitanOrigin.ORIGIN)
        val FORWARDED_HEADERS = setOf("accept", "accept-language", "range", "user-agent")

        fun charsetOf(contentType: String): String? =
            contentType.substringAfter("charset=", "").substringBefore(';').trim().trim('"').ifEmpty { null }

        fun reasonOf(status: Int) = when (status) {
            200 -> "OK"
            204 -> "No Content"
            206 -> "Partial Content"
            404 -> "Not Found"
            else -> "Status $status"
        }

        fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "wasm" -> "application/wasm"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ttf" -> "font/ttf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "mp3" -> "audio/mpeg"
            "handlebars" -> "text/x-handlebars-template"
            "map" -> "application/json"
            else -> "application/octet-stream"
        }
    }
}

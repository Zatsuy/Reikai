package jp.reikai.reader.page

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import reikai.novel.font.NovelFontManager
import reikai.presentation.reader.NovelChapterNavigationClient
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI

/**
 * Serves the Japanese reader's origin (`https://chapter.reikai.invalid`): the chapter document, the
 * page script and stylesheet from `assets/jp-reader/`, and the fonts the reader added. Pictures and
 * links go through upstream's [NovelChapterNavigationClient] (pictures fetched with the source's own
 * client, a tapped link opened in the browser, every other navigation refused), except a link into
 * this origin, which is never opened outside it. Anything else is refused here rather than fetched:
 * the page never reaches the network itself.
 */
internal class JpPageClient(
    private val context: Context,
    private val upstream: NovelChapterNavigationClient,
    /** The document for a path's id, or null for one this viewport no longer serves. */
    private val document: (documentId: String) -> String?,
    /** The address of the document on screen, whose own fragments are the only links kept in the page. */
    private val documentUrl: () -> String?,
    private val fontManager: NovelFontManager,
    /** Font files the current document declares; only those are served. */
    private val fonts: () -> Set<String>,
    /** A later hook (4.3: Yomitan's origin in this page) answers first when it knows the request. */
    private val extra: () -> ((WebResourceRequest) -> WebResourceResponse?)?,
) : WebViewClient() {

    /** On a WebView worker thread, which may block. */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        extra()?.invoke(request)?.let { return it }
        val url = request.url
        if (url.scheme == "https" && url.host == JpPageDocument.HOST) {
            val path = url.encodedPath.orEmpty()
            return when {
                path.startsWith(
                    JpPageDocument.CHAPTER_PATH,
                ) -> serveDocument(path.removePrefix(JpPageDocument.CHAPTER_PATH))
                path.startsWith(JpPageDocument.ASSET_PATH) -> serveAsset(path.removePrefix(JpPageDocument.ASSET_PATH))
                path.startsWith(JpPageDocument.FONT_PATH) -> serveFont(path.removePrefix(JpPageDocument.FONT_PATH))
                else -> refused(NOT_FOUND)
            }
        }
        // A picture of the chapter, routed by NovelWebImages; null for anything that is not one.
        upstream.shouldInterceptRequest(view, request)?.let { return it }
        return refused(FORBIDDEN)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (isOwnOrigin(url)) return !staysInDocument(url, documentUrl())
        return upstream.shouldOverrideUrlLoading(view, request)
    }

    private fun serveDocument(id: String): WebResourceResponse {
        val html = document(id) ?: return refused(NOT_FOUND)
        return WebResourceResponse(
            "text/html",
            "utf-8",
            OK,
            "OK",
            mapOf(
                "Content-Security-Policy" to JpPageDocument.CONTENT_SECURITY_POLICY,
                "Cache-Control" to "no-store",
                "X-Content-Type-Options" to "nosniff",
            ),
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun serveAsset(name: String): WebResourceResponse {
        if (!ASSET_NAME.matches(name)) return refused(NOT_FOUND)
        val type = mimeType(name) ?: return refused(NOT_FOUND)
        val stream = runCatching { context.assets.open("jp-reader/$name") }.getOrNull() ?: return refused(NOT_FOUND)
        return ok(type, stream)
    }

    private fun serveFont(segment: String): WebResourceResponse {
        val name = JpPageDocument.decodePathSegment(segment) ?: return refused(NOT_FOUND)
        if (name !in fonts()) return refused(NOT_FOUND)
        // The font manager's own copy in app storage, made on first use (a picked folder has no path).
        val file = runCatching { runBlocking { fontManager.localFile(name) } }
            .onFailure { logcat(LogPriority.WARN, it) { "Could not open a reader font" } }
            .getOrNull() ?: return refused(NOT_FOUND)
        val type = if (name.endsWith(".otf", ignoreCase = true)) "font/otf" else "font/ttf"
        return ok(type, FileInputStream(file))
    }

    private fun ok(type: String, stream: InputStream) = WebResourceResponse(
        type,
        if (type.startsWith("text/")) "utf-8" else null,
        OK,
        "OK",
        mapOf("X-Content-Type-Options" to "nosniff"),
        stream,
    )

    private fun refused(code: Int) =
        WebResourceResponse(
            null,
            null,
            code,
            if (code ==
                NOT_FOUND
            ) {
                "Not Found"
            } else {
                "Forbidden"
            },
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

    private fun mimeType(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "woff2" -> "font/woff2"
        else -> null
    }

    internal companion object {
        /**
         * Whether [url] is this reader's private origin, which is no address anywhere else: a link there (a
         * relative one the chapter was built without a web address for) is never opened in the browser.
         */
        fun isOwnOrigin(url: String): Boolean =
            runCatching { URI(url).let { it.scheme == "https" && it.host == JpPageDocument.HOST } }.getOrDefault(false)

        /** A jump within the document on screen ([documentUrl] plus a fragment), the only link kept in the page. */
        fun staysInDocument(url: String, documentUrl: String?): Boolean =
            NovelChapterNavigationClient.decide(url, documentUrl, hasGesture = true) ==
                NovelChapterNavigationClient.Decision.ALLOW

        const val OK = 200
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404

        /** One file directly under `assets/jp-reader/`, no directories or dot-dot. */
        val ASSET_NAME = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]*$")
    }
}

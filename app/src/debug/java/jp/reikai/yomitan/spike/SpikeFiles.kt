package jp.reikai.yomitan.spike

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Serves the spike's private origin. Yomitan's files use root-absolute paths (`/js/...`, workers,
 * `/lib/resvg.wasm`), so the unpacked release is served at the origin root; the spike's own pages
 * live under `/__reikai/` and the dictionaries to import under `/__dicts/`.
 *
 * Everything comes from `<external files>/yomitan-spike/`, which scripts/fork/yomitan_spike.py fills
 * over adb, so a changed script needs a push and a reload rather than a rebuild. The spike's pages
 * fall back to the copies packaged in the debug assets.
 */
class SpikeFiles(private val context: Context) {

    val root: File = File(context.getExternalFilesDir(null), "yomitan-spike")

    fun open(path: String): WebResourceResponse? {
        val clean = path.substringBefore('?').substringBefore('#').ifEmpty { "/" }
        val (dir, relative) = when {
            clean.startsWith(REIKAI_PREFIX) -> File(root, "reikai") to clean.removePrefix(REIKAI_PREFIX)
            clean.startsWith(DICTS_PREFIX) -> File(root, "dicts") to clean.removePrefix(DICTS_PREFIX)
            else -> File(root, "ext") to clean.removePrefix("/")
        }
        val stream = openUnder(dir, relative)
            ?: (if (clean.startsWith(REIKAI_PREFIX)) openAsset(relative) else null)
            ?: return notFound()
        return WebResourceResponse(mimeType(clean), "utf-8", 200, "OK", HEADERS, stream)
    }

    private fun openUnder(dir: File, relative: String): InputStream? {
        val file = File(dir, relative).canonicalFile
        if (!file.path.startsWith(dir.canonicalPath + File.separator) || !file.isFile) return null
        return FileInputStream(file)
    }

    private fun openAsset(relative: String): InputStream? =
        runCatching { context.assets.open("jp-reikai/yomitan-spike/$relative") }.getOrNull()

    private fun notFound() =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", HEADERS, "".byteInputStream())

    private fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "wasm" -> "application/wasm"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "ttf" -> "font/ttf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "zip" -> "application/zip"
        else -> "text/plain"
    }

    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val REIKAI_PREFIX = "/__reikai/"
        const val DICTS_PREFIX = "/__dicts/"
        private val HEADERS = mapOf("Cache-Control" to "no-store")
    }
}

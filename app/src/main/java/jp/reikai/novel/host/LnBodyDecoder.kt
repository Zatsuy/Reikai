package jp.reikai.novel.host

import java.nio.charset.Charset
import java.util.Base64

/**
 * Turns a plugin fetch's response bytes into text the way a browser would, so pages in Shift_JIS,
 * EUC-JP or GBK read correctly instead of as mojibake. The charset comes from, in order: a byte
 * order mark, the plugin's own label (lnreader's `fetchText(url, init, encoding)`), the
 * Content-Type header, a `<meta charset>` in the first 1024 bytes of an HTML page, and else UTF-8.
 * Aozora Bunko is the case that needs the `<meta>` step: it sends `text/html` with no charset.
 *
 * Labels follow the WHATWG Encoding Standard, which browsers and lnreader's WebView share: every
 * Shift_JIS label means Microsoft's superset (windows-31j, which has the circled digits web novels
 * use, where a strict Shift_JIS decoder fails) and the GB labels mean GB18030.
 */
object LnBodyDecoder {

    class Decoded(
        val text: String,
        /** The raw bytes as base64 when the page is not UTF-8, so `arrayBuffer()` returns them
         *  rather than the UTF-8 re-encoding of [text]; null for UTF-8, where the two agree. */
        val rawBase64: String?,
    )

    fun decode(bytes: ByteArray, label: String?, contentType: String?): Decoded {
        bomCharset(bytes)?.let { (charset, bomSize) ->
            return result(bytes, String(bytes, bomSize, bytes.size - bomSize, charset), charset)
        }
        val charset = charsetFor(label)
            ?: charsetFor(headerCharset(contentType))
            ?: sniffMeta(bytes, contentType)
            ?: Charsets.UTF_8
        return result(bytes, String(bytes, charset), charset)
    }

    /** `new TextDecoder(label).decode(bytes)` for labels the JS shim cannot decode itself. */
    fun decodeBase64(label: String, base64: String): String {
        val bytes = Base64.getDecoder().decode(base64)
        bomCharset(bytes)?.let { (charset, bomSize) -> return String(bytes, bomSize, bytes.size - bomSize, charset) }
        return String(bytes, charsetFor(label) ?: Charsets.UTF_8)
    }

    fun charsetFor(label: String?): Charset? {
        val key = label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val name = ALIASES[key] ?: key
        return runCatching { Charset.forName(name) }.getOrNull()
            ?: FALLBACKS[name]?.let { fallback -> runCatching { Charset.forName(fallback) }.getOrNull() }
    }

    private fun result(bytes: ByteArray, text: String, charset: Charset): Decoded =
        Decoded(text, if (charset == Charsets.UTF_8) null else Base64.getEncoder().encodeToString(bytes))

    private fun bomCharset(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
        bytes.startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
        bytes.startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
        else -> null
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }

    private fun headerCharset(contentType: String?): String? =
        contentType?.let { HEADER_CHARSET.find(it)?.groupValues?.get(1) }

    // The WHATWG prescan, simplified: HTML (or an untyped body) only, ASCII-compatible bytes only.
    // A page that claims UTF-16 in a <meta> cannot be, since the tag itself was readable as ASCII.
    private fun sniffMeta(bytes: ByteArray, contentType: String?): Charset? {
        if (contentType != null && contentType.isNotBlank() && !contentType.contains("html", ignoreCase = true)) {
            return null
        }
        val head = String(bytes, 0, minOf(bytes.size, PRESCAN_BYTES), Charsets.ISO_8859_1)
        val label = META_CHARSET.find(head)?.groupValues?.get(1) ?: return null
        if (label.lowercase().startsWith("utf-16")) return Charsets.UTF_8
        return charsetFor(label)
    }

    private const val PRESCAN_BYTES = 1024

    private val HEADER_CHARSET = Regex("""charset\s*=\s*["']?([^"';\s]+)""", RegexOption.IGNORE_CASE)

    // Matches both <meta charset="x"> and <meta http-equiv="Content-Type" content="text/html; charset=x">.
    private val META_CHARSET =
        Regex("""<meta\b[^>]*?charset\s*=\s*["']?\s*([A-Za-z0-9_.:\-]+)""", RegexOption.IGNORE_CASE)

    private val ALIASES: Map<String, String> = buildMap {
        listOf("utf8", "unicode-1-1-utf-8", "unicode11utf8", "unicode20utf8", "x-unicode20utf8")
            .forEach { put(it, "UTF-8") }
        listOf(
            "shift_jis", "shift-jis", "sjis", "x-sjis", "ms_kanji", "csshiftjis", "ms932", "cp932",
            "windows-31j",
        ).forEach { put(it, "windows-31j") }
        listOf("euc-jp", "x-euc-jp", "cseucpkdfmtjapanese").forEach { put(it, "EUC-JP") }
        listOf(
            "gbk", "x-gbk", "gb2312", "csgb2312", "chinese", "csiso58gb231280", "gb_2312", "gb_2312-80",
            "iso-ir-58", "gb18030",
        ).forEach { put(it, "GB18030") }
        listOf("big5", "big5-hkscs", "cn-big5", "csbig5", "x-x-big5").forEach { put(it, "Big5-HKSCS") }
        listOf(
            "iso-8859-1", "iso8859-1", "latin1", "l1", "ascii", "us-ascii", "cp1252", "windows-1252",
            "x-cp1252", "iso_8859-1", "cp819", "csisolatin1", "ibm819", "iso-ir-100",
        ).forEach { put(it, "windows-1252") }
    }

    // A runtime without the superset still decodes the common characters with the base charset.
    private val FALLBACKS = mapOf("windows-31j" to "Shift_JIS", "Big5-HKSCS" to "Big5")
}

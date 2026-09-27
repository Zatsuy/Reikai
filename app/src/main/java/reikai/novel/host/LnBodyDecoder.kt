package reikai.novel.host

import java.nio.charset.Charset
import java.util.Base64

/**
 * Turns a plugin fetch's response bytes into text the way a browser would, so pages in Shift_JIS,
 * EUC-JP or GBK read correctly instead of as mojibake. The charset comes from, in order: a byte
 * order mark, the plugin's own label (lnreader's `fetchText(url, init, encoding)`), the
 * Content-Type header, a `<meta charset>` (or XML `encoding=`) in the page's head, and else UTF-8.
 * Aozora Bunko is the case that needs the `<meta>` step: it sends `text/html` with no charset.
 *
 * Labels follow the WHATWG Encoding Standard, which browsers and lnreader's WebView share: every
 * Shift_JIS label means Microsoft's superset (windows-31j, which has the circled digits web novels
 * use, where a strict Shift_JIS decoder fails), EUC-JP includes the same NEC row, and the GB labels
 * mean GB18030.
 */
object LnBodyDecoder {

    class Decoded(
        val text: String,
        /** The raw bytes as base64 when the page is not UTF-8, so `arrayBuffer()` returns them
         *  rather than the UTF-8 re-encoding of [text]; null for UTF-8, where the two agree. */
        val rawBase64: String?,
    )

    /** [keepRaw] false skips the raw copy for callers that only read text (`fetchText`). */
    fun decode(bytes: ByteArray, label: String?, contentType: String?, keepRaw: Boolean = true): Decoded {
        bomCharset(bytes)?.let { (charset, bomSize) ->
            return result(bytes, String(bytes, bomSize, bytes.size - bomSize, charset), charset, keepRaw)
        }
        val charset = charsetFor(label)
            ?: charsetFor(headerCharset(contentType))
            ?: sniff(bytes, contentType)
            ?: Charsets.UTF_8
        return result(bytes, String(bytes, charset), charset, keepRaw)
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

    private fun result(bytes: ByteArray, text: String, charset: Charset, keepRaw: Boolean): Decoded =
        Decoded(text, if (!keepRaw || charset == Charsets.UTF_8) null else Base64.getEncoder().encodeToString(bytes))

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

    // The WHATWG prescan, simplified: HTML, XML or an untyped body, ASCII-compatible bytes only. Like
    // Chromium it reads the whole head (up to <body>), not only the spec's first 1024 bytes, since
    // long heads of scripts and og: tags push the <meta> further. A page that claims UTF-16 there
    // cannot be, since the tag itself was readable as ASCII.
    private fun sniff(bytes: ByteArray, contentType: String?): Charset? {
        if (!contentType.isNullOrBlank() &&
            !contentType.contains("html", ignoreCase = true) &&
            !contentType.contains("xml", ignoreCase = true)
        ) {
            return null
        }
        val prefix = String(bytes, 0, minOf(bytes.size, PRESCAN_BYTES), Charsets.ISO_8859_1)
        val head = prefix.indexOf("<body", ignoreCase = true).let { if (it >= 0) prefix.substring(0, it) else prefix }
        val label = (META_CHARSET.find(head) ?: XML_ENCODING.find(head))?.groupValues?.get(1) ?: return null
        if (label.lowercase().startsWith("utf-16")) return Charsets.UTF_8
        return charsetFor(label)
    }

    private const val PRESCAN_BYTES = 16 * 1024

    private val HEADER_CHARSET = Regex("""charset\s*=\s*["']?([^"';\s]+)""", RegexOption.IGNORE_CASE)

    // Matches both <meta charset="x"> and <meta http-equiv="Content-Type" content="text/html; charset=x">.
    private val META_CHARSET =
        Regex("""<meta\b[^>]*?charset\s*=\s*["']?\s*([A-Za-z0-9_.:\-]+)""", RegexOption.IGNORE_CASE)

    private val XML_ENCODING =
        Regex("""^\s*<\?xml\b[^>]*?encoding\s*=\s*["']([A-Za-z0-9_.:\-]+)""", RegexOption.IGNORE_CASE)

    private val ALIASES: Map<String, String> = buildMap {
        listOf("utf8", "unicode-1-1-utf-8", "unicode11utf8", "unicode20utf8", "x-unicode20utf8")
            .forEach { put(it, "UTF-8") }
        listOf(
            "shift_jis", "shift-jis", "sjis", "x-sjis", "ms_kanji", "csshiftjis", "ms932", "cp932",
            "windows-31j",
        ).forEach { put(it, "windows-31j") }
        listOf("euc-jp", "x-euc-jp", "cseucpkdfmtjapanese").forEach { put(it, "x-eucJP-Open") }
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
    private val FALLBACKS = mapOf("windows-31j" to "Shift_JIS", "x-eucJP-Open" to "EUC-JP", "Big5-HKSCS" to "Big5")
}

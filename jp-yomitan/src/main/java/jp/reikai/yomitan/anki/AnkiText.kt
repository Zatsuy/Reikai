package jp.reikai.yomitan.anki

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Anki's rules for a note's first field, as its duplicate check applies them (Anki's
 * `rslib/src/text.rs` and `notes/mod.rs`): media tags become their file names, other HTML is dropped,
 * entities are decoded, and the checksum is the first 32 bits of the SHA-1 of what is left.
 */
internal object AnkiText {

    /** Anki normalises note text to NFC when a note is saved, so the checksum is taken over NFC text. */
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    /** Anki's `strip_html_preserving_media_filenames`. */
    fun stripHtmlPreservingMediaFilenames(html: String): String {
        val withNames = MEDIA_TAG.replace(html) { match ->
            val (double, single, bare) = match.destructured
            " $double$single$bare "
        }
        return decodeEntities(HTML.replace(withNames, ""))
    }

    /** Anki's `field_checksum`: an unsigned 32-bit number, as stored in the notes' `csum` column. */
    fun checksum(field: String): Long {
        val digest = MessageDigest.getInstance("SHA-1").digest(stripHtmlPreservingMediaFilenames(field).toByteArray())
        return (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (digest[i].toLong() and 0xff) }
    }

    private fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { match ->
            val name = match.groupValues[1]
            val code = when {
                name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)
                name.startsWith("#") -> name.substring(1).toIntOrNull()
                else -> NAMED[name]
            }
            if (code == null || !Character.isValidCodePoint(code)) match.value else String(Character.toChars(code))
        }.replace(' ', ' ')
    }

    private val HTML = Regex("(?si)(<!--.*?-->)|(<style.*?>.*?</style>)|(<script.*?>.*?</script>)|(<.*?>)")

    private val MEDIA_TAG = Regex(
        "(?si)<\\b(?:img|audio|video|object)\\b(?:[^>]|\"[^\"]+?\"|'[^']+?')+?\\b(?:src|data)\\b=" +
            "(?:\"([^\"]+?)\"|'([^']+?)'|([^ >]+?)).*?>",
    )

    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")

    private val NAMED = mapOf(
        "amp" to '&'.code,
        "lt" to '<'.code,
        "gt" to '>'.code,
        "quot" to '"'.code,
        "apos" to '\''.code,
        "nbsp" to 0xa0,
        "ensp" to 0x2002,
        "emsp" to 0x2003,
        "thinsp" to 0x2009,
        "zwnj" to 0x200c,
        "zwj" to 0x200d,
        "hellip" to 0x2026,
        "middot" to 0xb7,
        "mdash" to 0x2014,
        "ndash" to 0x2013,
    )
}

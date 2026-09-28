package jp.reikai.reader.page

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A message of Yomitan's scanner in the chapter page (4.3, `reikaiReader.postMessage` from
 * `jp-yomitan/src/main/assets/jp-reikai/reader-scan.js`).
 */
sealed interface JpScanMessage {

    /** Yomitan answered and its scanning settings are applied. */
    data object Ready : JpScanMessage

    /** A tap on text came before that; the scanner searches it once the engine is ready. */
    data object Wait : JpScanMessage

    /**
     * A tap found [query] (Yomitan's longest word there, or only its first character as a [kanji])
     * in [sentence], where it starts at [offset]; [rects] are the word's boxes in CSS pixels of the
     * page's viewport, [millis] the time since the tap.
     */
    data class Found(
        val query: String,
        val sentence: String,
        val offset: Int,
        val kanji: Boolean,
        val rects: List<Box>,
        val millis: Long,
    ) : JpScanMessage

    /** A tap on text where Yomitan found nothing. */
    data object Empty : JpScanMessage

    /** The search failed (the engine went away during it). */
    data class Failed(val message: String) : JpScanMessage

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** A word longer than this is no word; a sentence is cut to [MAX_SENTENCE] around it. */
        private const val MAX_QUERY = 64
        private const val MAX_SENTENCE = 1000
        private const val MAX_RECTS = 32

        /**
         * [text] read as a message, or null for anything that is not one. The script is the fork's own,
         * but it shares its document with the chapter, so every field is checked, not trusted.
         */
        fun parse(text: String): JpScanMessage? {
            val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            return when (message.string("t")) {
                "ready" -> Ready
                "wait" -> Wait
                "empty" -> Empty
                "error" -> Failed(message.string("message").orEmpty().take(200))
                "found" -> found(message)
                else -> null
            }
        }

        private fun found(message: JsonObject): Found? {
            val query = message.string("query")?.takeIf { it.isNotBlank() && it.length <= MAX_QUERY } ?: return null
            val sentence = message["sentence"] as? JsonObject
            var text = sentence?.string("text").orEmpty()
            var offset = sentence?.int("offset") ?: 0
            if (!text.regionMatches(offset.coerceIn(0, text.length), query, 0, query.length)) {
                // A sentence that does not hold the word where it says is no help to a card.
                text = query
                offset = 0
            } else if (text.length > MAX_SENTENCE) {
                val from = (offset - MAX_SENTENCE / 2).coerceIn(0, text.length - MAX_SENTENCE)
                text = text.substring(from, from + MAX_SENTENCE)
                offset -= from
            }
            val rects = (message["rects"] as? JsonArray).orEmpty().take(MAX_RECTS).mapNotNull { box ->
                val o = box as? JsonObject ?: return@mapNotNull null
                Box(
                    left = o.float("left") ?: return@mapNotNull null,
                    top = o.float("top") ?: return@mapNotNull null,
                    right = o.float("right") ?: return@mapNotNull null,
                    bottom = o.float("bottom") ?: return@mapNotNull null,
                ).takeIf { it.right >= it.left && it.bottom >= it.top }
            }
            return Found(
                query = query,
                sentence = text,
                offset = offset,
                kanji = message.string("type") == "kanji",
                rects = rects,
                millis = message.double("ms")?.toLong()?.coerceIn(0L, 60_000L) ?: 0L,
            )
        }

        private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

        private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.content

        private fun JsonObject.int(key: String): Int? = primitive(key)?.takeIf { !it.isString }?.intOrNull

        private fun JsonObject.double(key: String): Double? =
            primitive(key)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

        private fun JsonObject.float(key: String): Float? = double(key)?.toFloat()
    }
}

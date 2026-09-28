package jp.reikai.reader.page

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToInt

/**
 * Where the page is in its chapter (the contract's `pos`): [charOffset] characters before the first one
 * on screen, of [chars] (ttu's count), their ratio [fraction], the page (or screen) and page count,
 * whether the chapter fits on one page, and whether its end is on screen. [anchor] is the character the
 * reader means to be at, the first on screen after their own last move: the place to keep, since a page
 * laid out another way (a rotation, a new document) lands on the page holding it, where [charOffset]
 * would slip back a page each time. A page that sends none has it equal to [charOffset].
 */
data class JpPagePosition(
    val charOffset: Int,
    val chars: Int,
    val fraction: Double,
    val page: Int,
    val pages: Int,
    val fits: Boolean,
    val endSeen: Boolean,
    val anchor: Int = charOffset,
) {
    /**
     * The whole percent upstream is told, in its scroll reader's terms: 0 for a chapter that fits on one
     * page, as its scroll saturates at 0 there; 100 once the chapter's end is on screen, as its scroll
     * reaches 100 at the bottom (which is what marks a chapter read); below that, the share of
     * characters before the page, never 100 while the end is still ahead.
     */
    val percent: Int
        get() = when {
            fits -> 0
            endSeen -> 100
            else -> (fraction * 100).roundToInt().coerceIn(0, 99)
        }
}

/** A message the chapter page posts (`jpReader.postMessage`), as the contract types it. */
sealed interface JpPageMessage {

    /** Laid out and landed, fonts loaded. */
    data class Ready(val pos: JpPagePosition, val chapterId: Long?) : JpPageMessage

    /** After a page turn, a settled scroll or a re-layout. */
    data class Position(val pos: JpPagePosition, val chapterId: Long?) : JpPageMessage

    /** A tap that was not a lookup or a furigana reveal, at fractions of the page. */
    data class Tap(val x: Float, val y: Float, val action: String?) : JpPageMessage

    /** A page turn asked past the first ([forward] false) or last page. */
    data class Edge(val forward: Boolean) : JpPageMessage

    /** A touch began. */
    data object Touch : JpPageMessage

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * [text] read as a message, or null for anything that is not one. The page is the fork's own
         * script, but the chapter shares its document, so every field is checked, not trusted.
         * A `chapterId` field, when the page sends one, names the document a position is about.
         */
        fun parse(text: String): JpPageMessage? {
            val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            return when (message.string("t")) {
                "ready" -> message.position()?.let { Ready(it, message.long("chapterId")) }
                "pos" -> message.position()?.let { Position(it, message.long("chapterId")) }
                "tap" -> Tap(
                    x = message.double("x")?.toFloat()?.coerceIn(0f, 1f) ?: return null,
                    y = message.double("y")?.toFloat()?.coerceIn(0f, 1f) ?: return null,
                    action = message.string("action")?.takeIf { it in TAP_ACTIONS },
                )
                "edge" -> Edge(message.boolean("forward") ?: return null)
                "touch" -> Touch
                else -> null
            }
        }

        private val TAP_ACTIONS = setOf("menu", "back", "forward", "none")

        private fun JsonObject.position(): JpPagePosition? {
            val pos = (this["pos"] as? JsonObject) ?: return null
            val chars = pos.int("chars")?.coerceAtLeast(0) ?: return null
            val offset = pos.int("charOffset")?.coerceIn(0, chars) ?: return null
            val fraction = pos.double("fraction")?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
                ?: if (chars == 0) 0.0 else offset.toDouble() / chars
            return JpPagePosition(
                charOffset = offset,
                chars = chars,
                fraction = fraction,
                page = pos.int("page")?.coerceAtLeast(1) ?: 1,
                pages = pos.int("pages")?.coerceAtLeast(1) ?: 1,
                fits = pos.boolean("fits") ?: false,
                endSeen = pos.boolean("endSeen") ?: false,
                anchor = pos.int("anchor")?.coerceIn(0, chars) ?: offset,
            )
        }

        private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

        private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.content

        private fun JsonObject.int(key: String): Int? =
            primitive(key)?.takeIf { !it.isString }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

        private fun JsonObject.long(key: String): Long? = primitive(key)?.takeIf { !it.isString }?.longOrNull

        private fun JsonObject.double(key: String): Double? = primitive(key)?.takeIf { !it.isString }?.doubleOrNull

        private fun JsonObject.boolean(key: String): Boolean? = primitive(key)?.takeIf { !it.isString }?.booleanOrNull
    }
}

package jp.reikai.reader.page

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import reikai.novel.font.isSupportedFontFile
import reikai.presentation.reader.NovelTapAction
import reikai.presentation.reader.readerColorOrNull
import reikai.presentation.reader.readerDarkPreset
import reikai.presentation.reader.web.cssFontName
import java.util.Locale

/** The Japanese reader's own settings (`jp_reader_*`), each read into the values the page knows. */
data class JpPageOptions(
    val vertical: Boolean = true,
    val paged: Boolean = true,
    /** ttu's furigana modes: `show`, `partial`, `full`, `toggle`, `hide`. */
    val furigana: String = "show",
    /** `mincho`, `gothic`, or an added font's file name. */
    val font: String = FONT_MINCHO,
    /** A tap on text looks it up (D-029); false: taps follow the reader's tap zones. */
    val tapLooksUp: Boolean = true,
) {
    val writing: String get() = if (vertical) "vertical" else "horizontal"
    val layout: String get() = if (paged) "paged" else "scroll"

    companion object {
        const val FONT_MINCHO = "mincho"
        const val FONT_GOTHIC = "gothic"
        val FURIGANA_MODES = listOf("show", "partial", "full", "toggle", "hide")

        /** From the stored values, each unknown one read as its default (a restored backup can hold anything). */
        fun from(writing: String, layout: String, furigana: String, font: String, tap: String) = JpPageOptions(
            vertical = writing != "horizontal",
            paged = layout != "scroll",
            furigana = furigana.takeIf { it in FURIGANA_MODES } ?: "show",
            font = font.takeIf { it == FONT_MINCHO || it == FONT_GOTHIC || isSupportedFontFile(it) } ?: FONT_MINCHO,
            tapLooksUp = tap != "zones",
        )
    }
}

/** Upstream's reader settings the Japanese reader shares (ruling 5): theme, size, spacing, margins. */
data class JpPageLook(
    val fontSize: Int,
    val lineHeight: Float,
    val marginTop: Int,
    val marginRight: Int,
    val marginBottom: Int,
    val marginLeft: Int,
    val background: String,
    val text: String,
    /** First-line indent in em. */
    val textIndent: Float,
    val justify: Boolean,
)

/** A tap zone as fractions of the page, and what a tap in it does. */
data class JpTapZone(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val action: NovelTapAction,
) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/**
 * Upstream's tap zones (Settings -> Reader -> Tap zones) as the Japanese reader reads them. Vertical
 * text reads from right to left, so a zone that turns forward on the right in horizontal text turns
 * forward on the left in vertical text: the zones are mirrored left to right.
 */
data class JpTapLayout(
    val zones: List<JpTapZone>,
    /** A layout whose zones only open the menu (centre, bottom): a tap outside them does nothing. */
    val zoneOnly: Boolean,
) {
    fun mirrored(): JpTapLayout = copy(zones = zones.map { it.copy(left = 1f - it.right, right = 1f - it.left) })

    fun forWriting(vertical: Boolean): JpTapLayout = if (vertical) mirrored() else this

    /** Upstream's rule: the first zone holding the tap, else the menu, or nothing for a zone-only layout. */
    fun actionAt(x: Float, y: Float): NovelTapAction {
        zones.firstOrNull { it.contains(x, y) }?.let {
            return if (zoneOnly) NovelTapAction.MENU else it.action
        }
        return if (zoneOnly) NovelTapAction.NONE else NovelTapAction.MENU
    }
}

/** The contract's `settings` object for `jp-init` and `JpReader.applySettings` (phase 4 design). */
object JpPageSettings {

    /** Families Android maps to its Noto CJK faces for `lang="ja"`; a device without the serif one falls
     *  back to the sans-serif one (ruling 6). */
    const val MINCHO_FAMILY = "\"Noto Serif CJK JP\", \"Noto Serif JP\", \"Source Han Serif JP\", serif"
    const val GOTHIC_FAMILY = "\"Noto Sans CJK JP\", \"Noto Sans JP\", \"Source Han Sans JP\", sans-serif"

    /** [font] as a CSS `font-family` list. An added font's name is filtered to characters CSS can hold. */
    fun fontFamily(font: String): String = when {
        font == JpPageOptions.FONT_GOTHIC -> GOTHIC_FAMILY
        isSupportedFontFile(font) -> cssFontName(font).takeIf { it.isNotEmpty() }?.let { "'$it', $MINCHO_FAMILY" }
            ?: MINCHO_FAMILY
        else -> MINCHO_FAMILY
    }

    fun json(
        options: JpPageOptions,
        look: JpPageLook,
        tapLayout: JpTapLayout,
        insetTop: Int,
        insetBottom: Int,
    ): JsonObject = buildJsonObject {
        put("writing", options.writing)
        put("layout", options.layout)
        put("furigana", options.furigana)
        put("tapMode", if (options.tapLooksUp) "lookup" else "zones")
        put("tapZones", zonesJson(tapLayout.forWriting(options.vertical)))
        put("fontFamily", fontFamily(options.font))
        put("fontSize", look.fontSize)
        put("lineHeight", look.lineHeight)
        putJsonObject("margins") {
            put("top", look.marginTop)
            put("right", look.marginRight)
            put("bottom", look.marginBottom)
            put("left", look.marginLeft)
        }
        putJsonObject("insets") {
            put("top", insetTop)
            put("bottom", insetBottom)
        }
        val text = cssColor(look.text, readerDarkPreset.textColor)
        putJsonObject("colors") {
            put("background", cssColor(look.background, readerDarkPreset.background))
            put("text", text)
            put("hint", hintColor(text))
        }
        put("textIndent", look.textIndent)
        put("justify", look.justify)
        put("invertSwipe", false)
    }

    private fun zonesJson(layout: JpTapLayout): JsonArray = buildJsonArray {
        layout.zones.forEach { zone ->
            add(
                buildJsonArray {
                    add(zone.left)
                    add(zone.top)
                    add(zone.right)
                    add(zone.bottom)
                    add(
                        when {
                            layout.zoneOnly -> "menu"
                            zone.action == NovelTapAction.BACK -> "back"
                            zone.action == NovelTapAction.FORWARD -> "forward"
                            else -> "menu"
                        },
                    )
                },
            )
        }
    }

    /** A stored colour as `#rrggbb` or `#rrggbbaa`, or [fallback] when it is not one (a restored backup). */
    fun cssColor(value: String, fallback: String): String {
        val argb = readerColorOrNull(value) ?: readerColorOrNull(fallback) ?: 0
        return hex(argb)
    }

    /** The text colour at half strength, for dimmed furigana and the like. */
    fun hintColor(textHex: String): String {
        val argb = readerColorOrNull(textHex) ?: return textHex
        val alpha = (argb ushr 24) and 0xFF
        return hex((argb and 0xFFFFFF) or ((alpha / 2) shl 24))
    }

    private fun hex(argb: Int): String {
        val alpha = (argb ushr 24) and 0xFF
        val rgb = "%06x".format(Locale.ROOT, argb and 0xFFFFFF)
        return if (alpha == 0xFF) "#$rgb" else "#$rgb" + "%02x".format(Locale.ROOT, alpha)
    }
}

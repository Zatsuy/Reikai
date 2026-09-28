package jp.reikai.reader.page

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.presentation.reader.NovelTapAction

class JpPageSettingsTest {

    /** Upstream's "Thirds" layout in horizontal text: left third back, right third forward, middle menu. */
    private val thirds = JpTapLayout(
        zones = listOf(
            JpTapZone(0f, 0f, 0.33f, 1f, NovelTapAction.BACK),
            JpTapZone(0.66f, 0f, 1f, 1f, NovelTapAction.FORWARD),
        ),
        zoneOnly = false,
    )

    private val centreOnly = JpTapLayout(
        zones = listOf(JpTapZone(0.33f, 0.33f, 0.66f, 0.66f, NovelTapAction.MENU)),
        zoneOnly = true,
    )

    private val look = JpPageLook(
        fontSize = 20,
        lineHeight = 1.8f,
        marginTop = 8,
        marginRight = 12,
        marginBottom = 16,
        marginLeft = 24,
        background = "#101010",
        text = "#eeeeee",
        textIndent = 1f,
        justify = true,
    )

    @ParameterizedTest(name = "tap at {0},{1} in vertical {2} -> {3}")
    @CsvSource(
        // Horizontal text: upstream's own zones.
        "0.1, 0.5, false, BACK",
        "0.9, 0.5, false, FORWARD",
        "0.5, 0.5, false, MENU",
        // Vertical text reads right to left: the left side turns forward, the right side back.
        "0.1, 0.5, true, FORWARD",
        "0.9, 0.5, true, BACK",
        "0.5, 0.5, true, MENU",
    )
    fun `tap zones are mirrored for vertical text`(x: Float, y: Float, vertical: Boolean, action: NovelTapAction) {
        thirds.forWriting(vertical).actionAt(x, y) shouldBe action
    }

    @ParameterizedTest(name = "tap at {0},{1} -> {2}")
    @CsvSource("0.5, 0.5, MENU", "0.1, 0.1, NONE", "0.9, 0.9, NONE")
    fun `a zone-only layout opens the menu in its zone and does nothing elsewhere`(
        x: Float,
        y: Float,
        action: NovelTapAction,
    ) {
        centreOnly.forWriting(vertical = true).actionAt(x, y) shouldBe action
    }

    @Test
    fun `the page gets the zones already mirrored for vertical text`() {
        val json = JpPageSettings.json(JpPageOptions(vertical = true), look, thirds, insetTop = 0, insetBottom = 0)
        val zones = json["tapZones"]!!.jsonArray.map { it.jsonArray }
        zones.map { it[4].jsonPrimitive.content } shouldBe listOf("back", "forward")
        // The back zone moved to the right third, the forward zone to the left third.
        zones[0][0].jsonPrimitive.float shouldBe (1f - 0.33f)
        zones[1][2].jsonPrimitive.float shouldBe (1f - 0.66f)
    }

    @Test
    fun `the settings object carries the contract's fields from both settings`() {
        val options =
            JpPageOptions(vertical = false, paged = false, furigana = "partial", font = "gothic", tapLooksUp = false)
        val json = JpPageSettings.json(options, look, thirds, insetTop = 24, insetBottom = 0)
        json.string("writing") shouldBe "horizontal"
        json.string("layout") shouldBe "scroll"
        json.string("furigana") shouldBe "partial"
        json.string("tapMode") shouldBe "zones"
        json.string("fontFamily") shouldBe JpPageSettings.GOTHIC_FAMILY
        json.string("fontSize") shouldBe "20"
        json.string("lineHeight") shouldBe "1.8"
        (json["margins"] as JsonObject).let { margins ->
            listOf("top", "right", "bottom", "left").map { margins.string(it) } shouldBe listOf("8", "12", "16", "24")
        }
        (json["insets"] as JsonObject).string("top") shouldBe "24"
        (json["colors"] as JsonObject).let { colors ->
            colors.string("background") shouldBe "#101010"
            colors.string("text") shouldBe "#eeeeee"
            // The text at half strength.
            colors.string("hint") shouldBe "#eeeeee7f"
        }
        json.string("textIndent") shouldBe "1.0"
        json.string("justify") shouldBe "true"
        (json["tapZones"] as JsonArray).size shouldBe 2
    }

    @ParameterizedTest(name = "stored {0} -> {1}")
    @CsvSource(
        "mincho, mincho",
        "gothic, gothic",
        "MyFont.otf, MyFont.otf",
        // A restored backup can hold anything: it reads as the default.
        "'</style><script>', mincho",
        "comic-sans, mincho",
    )
    fun `the font setting keeps only values the reader knows`(stored: String, font: String) {
        JpPageOptions.from("vertical", "paged", "show", stored, "lookup").font shouldBe font
    }

    @Test
    fun `an added font's name reaches the page filtered, ahead of the Mincho fallbacks`() {
        JpPageSettings.fontFamily("Klee_One-Regular.ttf") shouldBe "'Klee One Regular', ${JpPageSettings.MINCHO_FAMILY}"
        JpPageSettings.fontFamily("mincho") shouldBe JpPageSettings.MINCHO_FAMILY
    }

    @ParameterizedTest(name = "{0} / {1} / {2} / {3}")
    @CsvSource(
        "vertical, paged, show, lookup, true, true, show, true",
        "horizontal, scroll, hide, zones, false, false, hide, false",
        // Unknown values read as the defaults.
        "sideways, flipped, loud, poke, true, true, show, true",
    )
    fun `stored reader settings are read into the page's values`(
        writing: String,
        layout: String,
        furigana: String,
        tap: String,
        vertical: Boolean,
        paged: Boolean,
        mode: String,
        looksUp: Boolean,
    ) {
        JpPageOptions.from(writing, layout, furigana, "mincho", tap) shouldBe
            JpPageOptions(vertical = vertical, paged = paged, furigana = mode, font = "mincho", tapLooksUp = looksUp)
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource("#abc, #aabbcc", "#11223344, #11223344", "red, #121212", "'', #121212")
    fun `colours reach the page as checked hex, a bad one as the dark default`(stored: String, css: String) {
        JpPageSettings.cssColor(stored, fallback = "#121212") shouldBe css
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
}

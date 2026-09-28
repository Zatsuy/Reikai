package jp.reikai.reader.page

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class JpPageMessageTest {

    @Test
    fun `a ready message carries the page's position`() {
        val message = JpPageMessage.parse(
            """{"t":"ready","pos":{"charOffset":120,"chars":4000,"fraction":0.03,"page":2,"pages":40,"fits":false,"endSeen":false}}""",
        )
        message shouldBe JpPageMessage.Ready(
            JpPagePosition(
                charOffset = 120,
                chars = 4000,
                fraction = 0.03,
                page = 2,
                pages = 40,
                fits = false,
                endSeen = false,
            ),
            chapterId = null,
        )
    }

    @Test
    fun `a position names its chapter when the page sends it`() {
        val message = JpPageMessage.parse("""{"t":"pos","chapterId":77,"pos":{"charOffset":0,"chars":10}}""")
        message.shouldBeInstanceOf<JpPageMessage.Position>()
        message.chapterId shouldBe 77L
        // Missing fields read as a first page that is not the end.
        message.pos shouldBe JpPagePosition(0, 10, 0.0, 1, 1, fits = false, endSeen = false)
    }

    @Test
    fun `a position out of range is held to the chapter`() {
        val message = JpPageMessage.parse("""{"t":"pos","pos":{"charOffset":900,"chars":500,"fraction":7,"anchor":-3}}""")
        (message as JpPageMessage.Position).pos.let {
            it.charOffset shouldBe 500
            it.fraction shouldBe 1.0
            it.anchor shouldBe 0
        }
    }

    @Test
    fun `the anchor is the place the reader means, the page's first character when it sends none`() {
        val anchored = JpPageMessage.parse("""{"t":"pos","pos":{"charOffset":586,"anchor":786,"chars":2169}}""")
        (anchored as JpPageMessage.Position).pos.let {
            it.charOffset shouldBe 586
            it.anchor shouldBe 786
        }
        val plain = JpPageMessage.parse("""{"t":"pos","pos":{"charOffset":586,"chars":2169}}""")
        (plain as JpPageMessage.Position).pos.anchor shouldBe 586
    }

    @Test
    fun `taps, edges and touches are read as the contract types them`() {
        JpPageMessage.parse("""{"t":"tap","x":0.25,"y":1.5,"action":"forward"}""") shouldBe
            JpPageMessage.Tap(0.25f, 1f, "forward")
        JpPageMessage.parse("""{"t":"tap","x":0.5,"y":0.5,"action":"explode"}""") shouldBe
            JpPageMessage.Tap(0.5f, 0.5f, null)
        JpPageMessage.parse("""{"t":"edge","forward":false}""") shouldBe JpPageMessage.Edge(forward = false)
        JpPageMessage.parse("""{"t":"touch"}""") shouldBe JpPageMessage.Touch
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "not json",
            "[1,2]",
            """{"t":"launch"}""",
            """{"t":"pos"}""",
            """{"t":"pos","pos":{"chars":"10","charOffset":0}}""",
            """{"t":"edge"}""",
            """{"t":"tap","x":"0.5","y":0.5}""",
        ],
    )
    fun `anything else is no message`(text: String) {
        JpPageMessage.parse(text) shouldBe null
    }

    @ParameterizedTest(name = "fraction {0}, fits {1}, end seen {2} -> {3}%")
    @CsvSource(
        "0.0, false, false, 0",
        "0.424, false, false, 42",
        // Not finished while the end is still ahead, however close.
        "0.998, false, false, 99",
        // The last page is on screen: finished, as upstream's scroll reaches 100 at the bottom.
        "0.97, false, true, 100",
        // A chapter on one page reads 0, as upstream's scroll does there.
        "0.0, true, true, 0",
    )
    fun `upstream hears the position as its scroll reader's percent`(
        fraction: Double,
        fits: Boolean,
        endSeen: Boolean,
        percent: Int,
    ) {
        JpPagePosition(0, 100, fraction, 1, 1, fits, endSeen).percent shouldBe percent
    }
}

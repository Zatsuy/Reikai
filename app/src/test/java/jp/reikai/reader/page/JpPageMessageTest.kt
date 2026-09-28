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
        val message = parse(
            """{"doc":"7-1","t":"ready","pos":{"charOffset":120,"chars":4000,"fraction":0.03,"page":2,"pages":40,"fits":false,"endSeen":false}}""",
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
        )
    }

    @Test
    fun `a position with only its place reads as a first page that is not the end`() {
        val message = parse("""{"doc":"7-1","t":"pos","pos":{"charOffset":0,"chars":10}}""")
        message.shouldBeInstanceOf<JpPageMessage.Position>()
        message.pos shouldBe JpPagePosition(0, 10, 0.0, 1, 1, fits = false, endSeen = false)
    }

    @Test
    fun `an auto-scroll report is a live place, any other a settled one`() {
        val live = parse("""{"doc":"7-1","t":"pos","live":true,"pos":{"charOffset":0,"chars":10}}""")
        (live as JpPageMessage.Position).live shouldBe true
        val settled = parse("""{"doc":"7-1","t":"pos","pos":{"charOffset":0,"chars":10}}""")
        (settled as JpPageMessage.Position).live shouldBe false
    }

    @Test
    fun `a position out of range is held to the chapter`() {
        val message = parse(
            """{"doc":"7-1","t":"pos","pos":{"charOffset":900,"chars":500,"fraction":7,"anchor":-3}}""",
        )
        (message as JpPageMessage.Position).pos.let {
            it.charOffset shouldBe 500
            it.fraction shouldBe 1.0
            it.anchor shouldBe 0
        }
    }

    @Test
    fun `the anchor is the place the reader means, the page's first character when it sends none`() {
        val anchored = parse("""{"doc":"7-1","t":"pos","pos":{"charOffset":586,"anchor":786,"chars":2169}}""")
        (anchored as JpPageMessage.Position).pos.let {
            it.charOffset shouldBe 586
            it.anchor shouldBe 786
        }
        val plain = parse("""{"doc":"7-1","t":"pos","pos":{"charOffset":586,"chars":2169}}""")
        (plain as JpPageMessage.Position).pos.anchor shouldBe 586
    }

    @Test
    fun `taps, edges and touches are read as the contract types them`() {
        parse("""{"doc":"7-1","t":"tap","x":0.25,"y":1.5,"action":"forward"}""") shouldBe
            JpPageMessage.Tap(0.25f, 1f, "forward")
        parse("""{"doc":"7-1","t":"tap","x":0.5,"y":0.5,"action":"explode"}""") shouldBe
            JpPageMessage.Tap(0.5f, 0.5f, null)
        parse("""{"doc":"7-1","t":"edge","forward":false}""") shouldBe JpPageMessage.Edge(forward = false)
        parse("""{"doc":"7-1","t":"touch"}""") shouldBe JpPageMessage.Touch
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "not json",
            "[1,2]",
            """{"doc":"7-1","t":"launch"}""",
            """{"doc":"7-1","t":"pos"}""",
            """{"doc":"7-1","t":"pos","pos":{"chars":"10","charOffset":0}}""",
            """{"doc":"7-1","t":"edge"}""",
            """{"doc":"7-1","t":"tap","x":"0.5","y":0.5}""",
        ],
    )
    fun `anything else is no message`(text: String) {
        parse(text) shouldBe null
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // A late message from the document this one replaced.
            """{"doc":"7-1","t":"ready","pos":{"charOffset":0,"chars":10}}""",
            // One that names no document, or not as the contract does.
            """{"t":"ready","pos":{"charOffset":0,"chars":10}}""",
            """{"doc":8,"t":"edge","forward":true}""",
        ],
    )
    fun `a message that does not name the document on screen is dropped`(text: String) {
        JpPageMessage.parse(text, document = "8-2") shouldBe null
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

    private fun parse(text: String) = JpPageMessage.parse(text, document = "7-1")
}

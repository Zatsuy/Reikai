package jp.reikai.reader.page

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JpScanMessageTest {

    @Test
    fun `a word found carries its sentence, its boxes and the time since the tap`() {
        val text =
            """{"doc":"7-1","t":"found","type":"terms","query":"打ち込んだ","sentence":{"text":"彼は古い画像を見て、打ち込んだ。",""" +
                """"offset":10},"rects":[{"left":300.5,"top":80,"right":322,"bottom":190}],"writingMode":"vertical-rl","ms":42}"""

        parse(text) shouldBe JpScanMessage.Found(
            query = "打ち込んだ",
            sentence = "彼は古い画像を見て、打ち込んだ。",
            offset = 10,
            kanji = false,
            rects = listOf(JpScanMessage.Box(300.5f, 80f, 322f, 190f)),
            millis = 42,
        )
    }

    @Test
    fun `a kanji found only as a kanji says so`() {
        val found = parse(
            """{"doc":"7-1","t":"found","type":"kanji","query":"込","sentence":{"text":"込","offset":0}}""",
        )
        (found as JpScanMessage.Found).kanji shouldBe true
    }

    @Test
    fun `a sentence that does not hold the word where it says gives way to the word`() {
        val found = parse("""{"doc":"7-1","t":"found","query":"猫","sentence":{"text":"犬がいる。","offset":1}}""")
        found as JpScanMessage.Found
        (found.sentence to found.offset) shouldBe ("猫" to 0)
    }

    @Test
    fun `an offset outside the sentence gives way to the word`() {
        val before = parse("""{"doc":"7-1","t":"found","query":"猫","sentence":{"text":"猫がいる。","offset":-3}}""")
        before as JpScanMessage.Found
        (before.sentence to before.offset) shouldBe ("猫" to 0)
        val after = parse("""{"doc":"7-1","t":"found","query":"猫","sentence":{"text":"猫がいる。","offset":9}}""")
        after as JpScanMessage.Found
        (after.sentence to after.offset) shouldBe ("猫" to 0)
    }

    @Test
    fun `a very long sentence is cut around the word`() {
        val sentence = "あ".repeat(3000) + "猫" + "い".repeat(3000)
        val found = parse("""{"doc":"7-1","t":"found","query":"猫","sentence":{"text":"$sentence","offset":3000}}""")
        found as JpScanMessage.Found
        found.sentence.length shouldBe 1000
        found.sentence[found.offset] shouldBe '猫'
    }

    @Test
    fun `the other messages, and what is not one`() {
        parse("""{"doc":"7-1","t":"ready"}""") shouldBe JpScanMessage.Ready
        parse("""{"doc":"7-1","t":"wait"}""") shouldBe JpScanMessage.Wait
        parse("""{"doc":"7-1","t":"empty"}""") shouldBe JpScanMessage.Empty
        parse("""{"doc":"7-1","t":"error","message":"gone"}""") shouldBe JpScanMessage.Failed("gone")
        parse("""{"doc":"7-1","t":"found","query":""}""") shouldBe null
        parse("""{"doc":"7-1","t":"found","query":"${"長".repeat(65)}"}""") shouldBe null
        parse("""{"doc":"7-1","t":"tap"}""") shouldBe null
        parse("not json") shouldBe null
    }

    @Test
    fun `a message of another document, or of none, is dropped`() {
        JpScanMessage.parse("""{"doc":"7-1","t":"empty"}""", document = "8-2") shouldBe null
        JpScanMessage.parse("""{"t":"empty"}""", document = "7-1") shouldBe null
    }

    private fun parse(text: String) = JpScanMessage.parse(text, document = "7-1")
}

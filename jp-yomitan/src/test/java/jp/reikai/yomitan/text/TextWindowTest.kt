package jp.reikai.yomitan.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TextWindowTest {

    private val source = "報告ではこうだ。\n昨夜、レストは島流しの刑を恐れて、伯爵家から脱走した。\nさらに、狼面衆とも繋がりがあった。"

    /** The window Android would hand over: [from] characters cut off the front. */
    private fun window(from: Int, to: Int = source.length) = source.substring(from, to)

    @Test
    fun `the pressed character is found in a window cut from the front`() {
        val pressed = source.indexOf("り")
        val window = window(10)
        // Android selected the "が" after it.
        val start = window.indexOf("りが") + 1
        TextWindow.locate(window, start, start + 1, source, pressed) shouldBe pressed - 10
    }

    @Test
    fun `the pressed character is found when the window is the whole text`() {
        val pressed = source.indexOf("島")
        TextWindow.locate(source, pressed, pressed + 2, source, pressed) shouldBe pressed
    }

    @Test
    fun `a press far from the selection is not placed`() {
        val window = window(10)
        val start = window.indexOf("島")
        TextWindow.locate(window, start, start + 1, source, source.indexOf("狼")) shouldBe null
    }

    @Test
    fun `a window from another text is not matched`() {
        val other = "全く別の文章です。島流しの話ではない。"
        val start = other.indexOf("島")
        TextWindow.locate(other, start, start + 1, source, source.indexOf("島")) shouldBe null
    }
}

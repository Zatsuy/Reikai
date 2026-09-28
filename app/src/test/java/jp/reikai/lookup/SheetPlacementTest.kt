package jp.reikai.lookup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class SheetPlacementTest {

    @ParameterizedTest(name = "word {0}-{1}, sheet {2} of 1000 -> top {3}")
    @CsvSource(
        // In the top half, a half-height sheet at the bottom leaves it in view.
        "100, 140, 500, false",
        // Just above a bottom sheet's edge.
        "460, 500, 500, false",
        // Low on the page, but above a short sheet: it stays at the bottom.
        "600, 640, 300, false",
        // In the lower half: the sheet moves to the top, clear of it.
        "700, 740, 500, true",
        "560, 600, 450, true",
        // A tall sheet covers it either way: on the side farther from the word.
        "300, 340, 800, false",
        "700, 740, 800, true",
    )
    fun `the sheet opens where it leaves the word in view`(wordTop: Int, wordBottom: Int, sheet: Int, top: Boolean) {
        SheetPlacement.atTop(wordTop, wordBottom, window = 1000, sheet = sheet) shouldBe top
    }
}

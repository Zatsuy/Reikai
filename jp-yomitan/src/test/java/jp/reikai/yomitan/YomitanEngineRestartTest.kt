package jp.reikai.yomitan

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class YomitanEngineRestartTest {

    @ParameterizedTest(name = "off {0}, in use {1}, crashed {2}, crashes {3}: {4}")
    @CsvSource(
        "true, true, true, 1, STAY_OFF",
        "false, false, true, 1, STOP",
        "false, true, false, 0, STOP",
        "false, true, false, 9, STOP",
        "false, true, true, 1, RESTART",
        "false, true, true, 3, RESTART",
        "false, true, true, 4, FAIL",
    )
    fun `a renderer killed to free memory waits for the next use, a crashed one restarts until it keeps crashing`(
        off: Boolean,
        inUse: Boolean,
        crashed: Boolean,
        crashes: Int,
        expected: String,
    ) {
        YomitanEngine.afterRendererGone(off, inUse, crashed, crashes).name shouldBe expected
    }
}

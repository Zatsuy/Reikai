package jp.reikai.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class JpReaderSessionTest {

    @ParameterizedTest(name = "asked 1000, touched {1}, now {0} -> wait {2}")
    @CsvSource(
        // Two quiet seconds: now.
        "3000, 1000, 0",
        // Touched half a second ago: the rest of the two seconds.
        "5000, 4500, 1500",
        // Still scrolling (the reader's auto-scroll) nine seconds on: only until ten seconds.
        "10000, 10000, 1000",
        // Still scrolling ten seconds on: now all the same.
        "11000, 11000, 0",
    )
    fun `the warm-up waits for a pause in reading, never more than ten seconds`(
        now: Long,
        touchedAt: Long,
        wait: Long,
    ) {
        JpReaderSession.warmUpWait(now, touchedAt, askedAt = 1000).coerceAtLeast(0) shouldBe wait
    }
}

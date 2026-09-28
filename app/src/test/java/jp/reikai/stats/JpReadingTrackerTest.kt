package jp.reikai.stats

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class JpReadingTrackerTest {

    private val zone: ZoneId = ZoneOffset.ofHours(9)
    private var clock = at(2026, 9, 28, 20, 0, 0)
    private val flushed = mutableListOf<List<TtuStatistic>>()
    private val tracker = JpReadingTracker(now = { clock }, zone = { zone }, onFlush = { flushed += it })

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute, second).atZone(ZoneOffset.ofHours(9)).toInstant().toEpochMilli()

    /** Resumed, begun on an empty title, landed on chapter 1 at [offset]. */
    private fun reading(offset: Int = 0, chars: Int = 20_000) {
        tracker.resume()
        tracker.begin("吾輩は猫である", emptyList())
        tracker.position(1L, offset, chars, endSeen = false)
    }

    /** [seconds] one-second ticks. */
    private fun ticks(seconds: Int) = repeat(seconds) {
        clock += 1000
        tracker.tick()
    }

    private fun today() = tracker.day("2026-09-28")!!

    @Test
    fun `time counts a second a tick while the reader is on screen`() {
        reading()
        ticks(9)
        today().readingTime shouldBe 9
        today().charactersRead shouldBe 0
        flushed shouldBe emptyList()
        ticks(1)
        // Handed over every ten seconds.
        flushed.single().single().readingTime shouldBe 10
    }

    @Test
    fun `late ticks lose nothing and a tick before a whole second counts it later`() {
        reading()
        clock += 1500
        tracker.tick()
        clock += 1600
        tracker.tick()
        clock += 400
        tracker.tick()
        today().readingTime shouldBe 3
    }

    @Test
    fun `page turns count forward reading once`() {
        reading()
        tracker.position(1L, 500, 20_000, endSeen = false)
        tracker.position(1L, 1100, 20_000, endSeen = false)
        ticks(1)
        today().charactersRead shouldBe 1100
        // Back a page and the same page again: read once.
        tracker.position(1L, 500, 20_000, endSeen = false)
        tracker.position(1L, 1100, 20_000, endSeen = false)
        tracker.position(1L, 1700, 20_000, endSeen = false)
        ticks(1)
        today().charactersRead shouldBe 1700
        today().lastReadingSpeed shouldBe TtuStatistic.speed(1700, 2)
    }

    @Test
    fun `a jump of 2700 characters or more is not reading, and reading goes on from where it landed`() {
        reading(offset = 1000)
        tracker.position(1L, 3700, 20_000, endSeen = false)
        tracker.position(1L, 4100, 20_000, endSeen = false)
        ticks(1)
        today().charactersRead shouldBe 400
        // Back by a jump, then reading the same part again counts: the reader chose to reread it.
        tracker.position(1L, 1000, 20_000, endSeen = false)
        tracker.position(1L, 1600, 20_000, endSeen = false)
        ticks(1)
        today().charactersRead shouldBe 1000
        // Just under the threshold is a page.
        tracker.position(1L, 1600 + 2699, 20_000, endSeen = false)
        ticks(1)
        today().charactersRead shouldBe 3699
    }

    @Test
    fun `five idle minutes are taken back and time waits for the reader`() {
        reading()
        ticks(100)
        tracker.position(1L, 600, 20_000, endSeen = false)
        // Nothing for five minutes after that page turn: the time since it is not reading.
        ticks(300)
        today().readingTime shouldBe 100
        today().charactersRead shouldBe 600
        ticks(60)
        today().readingTime shouldBe 100
        // A touch: reading again.
        tracker.activity()
        ticks(10)
        today().readingTime shouldBe 110
        tracker.session.readingTime shouldBe 110
    }

    @Test
    fun `a tick reaching back past midnight gives the day before its seconds`() {
        clock = at(2026, 9, 28, 23, 59, 50)
        reading()
        // The ticks stalled: one tick covers 23:59:50 to 00:00:05.
        tracker.position(1L, 300, 20_000, endSeen = false)
        clock = at(2026, 9, 29, 0, 0, 5)
        tracker.tick()
        tracker.day("2026-09-28")!!.readingTime shouldBe 10
        tracker.day("2026-09-29")!!.readingTime shouldBe 5
        // The characters go with the later day.
        tracker.day("2026-09-28")!!.charactersRead shouldBe 0
        tracker.day("2026-09-29")!!.charactersRead shouldBe 300
        ticks(2)
        tracker.day("2026-09-29")!!.readingTime shouldBe 7
    }

    @Test
    fun `an idle stretch across midnight is taken back from both days`() {
        clock = at(2026, 9, 28, 23, 58, 0)
        reading()
        ticks(420)
        // Idle from 23:58:00 to 00:03:00: the whole stretch taken back, from both days.
        tracker.day("2026-09-28")!!.readingTime shouldBe 0
        tracker.day("2026-09-29")!!.readingTime shouldBe 0
    }

    @Test
    fun `leaving a finished chapter counts its last page, and the next counts from its landing`() {
        reading(offset = 2800, chars = 3400)
        tracker.position(1L, 3000, 3400, endSeen = true)
        ticks(1)
        today().charactersRead shouldBe 200
        // The next chapter opened at its start: the last page (400 characters) was read.
        tracker.position(2L, 0, 5000, endSeen = false)
        ticks(1)
        today().charactersRead shouldBe 600
        tracker.position(2L, 700, 5000, endSeen = false)
        ticks(1)
        today().charactersRead shouldBe 1300
    }

    @Test
    fun `stepping back or leaving an unfinished chapter counts nothing for it`() {
        reading(offset = 0, chars = 5000)
        tracker.position(1L, 600, 5000, endSeen = false)
        // Back a chapter, landing on its last page.
        tracker.position(0L, 4500, 4800, endSeen = true, openedAtEnd = true)
        ticks(1)
        today().charactersRead shouldBe 600
        // Forward again to chapter 1 from the previous chapter's end, which was seen: its last page counts.
        tracker.position(1L, 0, 5000, endSeen = false)
        ticks(1)
        today().charactersRead shouldBe 900
        // Chapter 3 picked from the list while chapter 1's end was not on screen: nothing more.
        tracker.position(3L, 0, 5000, endSeen = false)
        ticks(1)
        today().charactersRead shouldBe 900
    }

    @Test
    fun `a chapter that fits on one page is read when the reader moves on`() {
        reading(offset = 0, chars = 800)
        tracker.position(1L, 0, 800, endSeen = true)
        tracker.position(2L, 0, 5000, endSeen = false)
        ticks(1)
        today().charactersRead shouldBe 800
    }

    @Test
    fun `incognito records nothing`() {
        tracker.resume()
        tracker.begin("吾輩は猫である", emptyList())
        tracker.position(1L, 0, 5000, endSeen = false, incognito = true)
        tracker.position(1L, 900, 5000, endSeen = false, incognito = true)
        ticks(20)
        tracker.pause()
        tracker.day("2026-09-28") shouldBe null
        flushed shouldBe emptyList()
        tracker.session.readingTime shouldBe 0
    }

    @Test
    fun `pausing counts up to now and hands the days over, and paused time is not counted`() {
        reading()
        ticks(3)
        clock += 700
        tracker.position(1L, 400, 20_000, endSeen = false)
        tracker.pause()
        flushed.single().single().let {
            it.readingTime shouldBe 3
            it.charactersRead shouldBe 400
        }
        clock += 60_000
        tracker.tick()
        tracker.resume()
        ticks(2)
        today().readingTime shouldBe 5
    }

    @Test
    fun `counting continues the title's stored day and the session carries over a rebuild`() {
        val stored = TtuStatistic("吾輩は猫である", "2026-09-28", charactersRead = 1000, readingTime = 100)
            .updated(0, 0, 1L)
        tracker.resume()
        tracker.begin("吾輩は猫である", listOf(stored, TtuStatistic("other", "2026-09-28", readingTime = 5)))
        tracker.position(1L, 0, 20_000, endSeen = false)
        tracker.position(1L, 300, 20_000, endSeen = false)
        ticks(10)
        today().charactersRead shouldBe 1300
        today().readingTime shouldBe 110
        tracker.session.charactersRead shouldBe 300

        val rebuilt = JpReadingTracker(now = { clock }, zone = { zone }, session = tracker.session, onFlush = {})
        rebuilt.session.readingTime shouldBe 10
    }

    @Test
    fun `nothing counts before the title's days are known or before the page reports`() {
        tracker.resume()
        ticks(5)
        tracker.begin("吾輩は猫である", emptyList())
        ticks(5)
        tracker.day("2026-09-28") shouldBe null
        tracker.position(1L, 0, 5000, endSeen = false)
        ticks(2)
        today().readingTime shouldBe 2
    }
}

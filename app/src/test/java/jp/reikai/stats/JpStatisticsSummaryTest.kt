package jp.reikai.stats

import io.kotest.matchers.shouldBe
import jp.reikai.data.JpReaderDatabase.StatisticRow
import jp.reikai.stats.JpStatisticsSummary.Totals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class JpStatisticsSummaryTest {

    private val today = LocalDate.of(2026, 9, 28)

    private fun row(novelId: Long, title: String, date: String, characters: Long, seconds: Long) =
        StatisticRow(novelId, TtuStatistic(title, date, charactersRead = characters, readingTime = seconds))

    private val rows = listOf(
        row(1L, "猫", "2026-09-28", 3000, 600),
        row(2L, "犬", "2026-09-28", 1000, 400),
        row(1L, "猫", "2026-09-22", 500, 100),
        // Eight days ago: all time only.
        row(2L, "犬", "2026-09-20", 7000, 1800),
    )
    private val summary = JpStatisticsSummary.of(rows, today)

    @Test
    fun `today, the last seven days and all time add up every novel`() {
        summary.today shouldBe Totals(4000, 1000)
        summary.week shouldBe Totals(4500, 1100)
        summary.allTime shouldBe Totals(11500, 2900)
        summary.today.speed shouldBe 14400
        summary.allTime.speed shouldBe TtuStatistic.speed(11500, 2900)
    }

    @Test
    fun `the recent days are listed newest first, empty days included`() {
        summary.days.size shouldBe JpStatisticsSummary.DAYS
        summary.days.first() shouldBe JpStatisticsSummary.Day(today, Totals(4000, 1000))
        summary.days[1] shouldBe JpStatisticsSummary.Day(today.minusDays(1), Totals())
        summary.days[6] shouldBe JpStatisticsSummary.Day(LocalDate.of(2026, 9, 22), Totals(500, 100))
        summary.days[8] shouldBe JpStatisticsSummary.Day(LocalDate.of(2026, 9, 20), Totals(7000, 1800))
    }

    @Test
    fun `each novel has its totals, most recently read first`() {
        summary.novels shouldBe listOf(
            JpStatisticsSummary.Novel("犬", 2L, Totals(8000, 2200), today),
            JpStatisticsSummary.Novel("猫", 1L, Totals(3500, 700), today),
        )
    }

    @Test
    fun `nothing read is an empty summary`() {
        JpStatisticsSummary.of(emptyList(), today).isEmpty shouldBe true
        summary.isEmpty shouldBe false
    }
}

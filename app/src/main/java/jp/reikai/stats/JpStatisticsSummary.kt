package jp.reikai.stats

import jp.reikai.data.JpReaderDatabase.StatisticRow
import java.time.LocalDate

/**
 * What the statistics screen shows (roadmap 4.4): today, the last seven days (today and the six before)
 * and all time, the recent days one by one, and each novel's totals, newest read first. Speeds are ttu's
 * (characters per hour over the time counted, rounded up).
 */
data class JpStatisticsSummary(
    val today: Totals,
    val week: Totals,
    val allTime: Totals,
    /** The last [DAYS] days, newest first, days without reading included. */
    val days: List<Day>,
    val novels: List<Novel>,
) {
    data class Totals(val characters: Long = 0, val seconds: Long = 0) {
        val speed: Long get() = TtuStatistic.speed(characters, seconds)

        operator fun plus(statistic: TtuStatistic) =
            Totals(characters + statistic.charactersRead, seconds + statistic.readingTime)
    }

    data class Day(val date: LocalDate, val totals: Totals)

    data class Novel(val title: String, val novelId: Long, val totals: Totals, val lastRead: LocalDate)

    val isEmpty: Boolean get() = novels.isEmpty()

    companion object {
        const val DAYS = 14

        fun of(rows: List<StatisticRow>, today: LocalDate): JpStatisticsSummary {
            val todayKey = JpReadingTracker.dateKey(today)
            val weekStart = JpReadingTracker.dateKey(today.minusDays(6))
            var todayTotals = Totals()
            var week = Totals()
            var allTime = Totals()
            val byDay = HashMap<String, Totals>()
            rows.forEach { row ->
                val statistic = row.statistic
                allTime += statistic
                if (statistic.dateKey == todayKey) todayTotals += statistic
                if (statistic.dateKey in weekStart..todayKey) week += statistic
                byDay[statistic.dateKey] = (byDay[statistic.dateKey] ?: Totals()) + statistic
            }
            val days = (0 until DAYS).map { back ->
                val date = today.minusDays(back.toLong())
                Day(date, byDay[JpReadingTracker.dateKey(date)] ?: Totals())
            }
            val novels = rows.groupBy { it.statistic.title }.map { (title, days) ->
                val latest = days.maxBy { it.statistic.dateKey }
                Novel(
                    title = title,
                    novelId = latest.novelId,
                    totals = days.fold(Totals()) { sum, row -> sum + row.statistic },
                    lastRead = runCatching { LocalDate.parse(latest.statistic.dateKey) }.getOrDefault(today),
                )
            }.sortedWith(compareByDescending<Novel> { it.lastRead }.thenBy { it.title })
            return JpStatisticsSummary(todayTotals, week, allTime, days, novels)
        }
    }
}

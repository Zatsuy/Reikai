/*
 * Reikai JP: ttu's reading statistics model (roadmap 4.4, phase 4 ruling 12). GPL-3.0-or-later,
 * except the logic ported from ッツ Ebook Reader (https://github.com/ttu-ttu/ebook-reader):
 *
 * @license BSD-3-Clause
 * Copyright (c) 2026, ッツ Reader Authors
 * All rights reserved.
 * (Licence text: LICENSES/BSD-3-Clause.txt.)
 *
 * Ported: the statistic row (books-db-v6.ts, BooksDbV6Statistic), its update rule
 * (book-reading-tracker.svelte, updateStatistic), the export file name and the title's folder name
 * (base-handler.ts, getStatisticsFileName and sanitizeForFilename). The JVM test TtuStatisticsTest
 * holds them to ttu's own functions (scripts/fork/ttu-statistics/make-fixture.mjs).
 */
package jp.reikai.stats

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * One title's reading on one day, field for field ttu's `BooksDbStatistic`: [readingTime] in seconds,
 * the speeds in characters per hour, [lastStatisticModified] in epoch milliseconds, [dateKey] as
 * `yyyy-MM-dd` in local time. [completedBook] and [completedData] (ttu's own JSON object) are carried
 * through for ttu; this app never sets them.
 */
data class TtuStatistic(
    val title: String,
    val dateKey: String,
    val charactersRead: Long = 0,
    val readingTime: Long = 0,
    val minReadingSpeed: Long = 0,
    val altMinReadingSpeed: Long = 0,
    val lastReadingSpeed: Long = 0,
    val maxReadingSpeed: Long = 0,
    val lastStatisticModified: Long = 0,
    val completedBook: Long? = null,
    val completedData: JsonObject? = null,
) {

    /**
     * ttu's `updateStatistic`: [timeDiff] seconds and [characterDiff] characters more (either may be
     * negative; neither total goes below 0), the speeds moved as ttu moves them on every tick.
     */
    fun updated(timeDiff: Long, characterDiff: Long, lastStatisticModified: Long): TtuStatistic {
        val readingTime = max(0, readingTime + timeDiff)
        val charactersRead = max(0, charactersRead + characterDiff)
        val lastReadingSpeed = speed(charactersRead, readingTime)
        return copy(
            readingTime = readingTime,
            charactersRead = charactersRead,
            lastReadingSpeed = lastReadingSpeed,
            minReadingSpeed = if (minReadingSpeed != 0L) min(minReadingSpeed, lastReadingSpeed) else lastReadingSpeed,
            maxReadingSpeed = max(maxReadingSpeed, lastReadingSpeed),
            lastStatisticModified = lastStatisticModified,
            altMinReadingSpeed = when {
                characterDiff == 0L -> altMinReadingSpeed
                altMinReadingSpeed != 0L -> min(altMinReadingSpeed, lastReadingSpeed)
                else -> lastReadingSpeed
            },
        )
    }

    /** The row as ttu stores and exports it, in its field order. */
    fun toJson(): JsonObject = buildJsonObject {
        put("title", title)
        put("dateKey", dateKey)
        put("charactersRead", charactersRead)
        put("readingTime", readingTime)
        put("minReadingSpeed", minReadingSpeed)
        put("altMinReadingSpeed", altMinReadingSpeed)
        put("lastReadingSpeed", lastReadingSpeed)
        put("maxReadingSpeed", maxReadingSpeed)
        put("lastStatisticModified", lastStatisticModified)
        completedBook?.let { put("completedBook", it) }
        completedData?.let { put("completedData", it as JsonElement) }
    }

    companion object {
        /** ttu's `exporterVersion` and `currentDbVersion`, the first two parts of its file names. */
        const val EXPORTER_VERSION = 1
        const val DB_VERSION = 6

        /** Characters per hour, rounded up, 0 without time: ttu's rule everywhere it shows a speed. */
        fun speed(characters: Long, seconds: Long): Long = if (seconds > 0) ceilDiv(3600 * characters, seconds) else 0

        /**
         * ttu's `getStatisticsFileName`: the statistics file of one title, whose name carries the
         * title's totals and averages so ttu can compare copies without opening them. [statistics] in
         * the order ttu's database returns them (by day).
         */
        fun fileName(statistics: List<TtuStatistic>, lastStatisticModified: Long): String {
            var readingTime = 0L
            var charactersRead = 0L
            var minReadingSpeed = 0L
            var altMinReadingSpeed = 0L
            var maxReadingSpeed = 0L
            var weightedSum = 0L
            var validReadingDays = 0L
            var finishDate = "na"
            for (statistic in statistics) {
                readingTime += statistic.readingTime
                charactersRead += statistic.charactersRead
                minReadingSpeed = if (minReadingSpeed != 0L) {
                    min(minReadingSpeed, statistic.minReadingSpeed)
                } else {
                    statistic.minReadingSpeed
                }
                altMinReadingSpeed = if (altMinReadingSpeed != 0L) {
                    min(altMinReadingSpeed, statistic.altMinReadingSpeed)
                } else {
                    statistic.altMinReadingSpeed
                }
                maxReadingSpeed = max(maxReadingSpeed, statistic.lastReadingSpeed)
                weightedSum += statistic.readingTime * statistic.charactersRead
                if (statistic.readingTime != 0L) validReadingDays += 1
                val completed = statistic.completedData
                if (completed != null) {
                    finishDate = if (finishDate == "na") {
                        statistic.dateKey
                    } else {
                        // ttu compares the completion's own day here, and took the row's day above.
                        val completedDay = (completed["dateKey"] as? JsonPrimitive)
                            ?.takeIf { it.isString }?.content
                        if (completedDay != null && completedDay > finishDate) completedDay else finishDate
                    }
                }
            }
            val averageReadingTime = if (validReadingDays != 0L) ceilDiv(readingTime, validReadingDays) else 0
            val averageWeightedReadingTime = if (charactersRead != 0L) ceilDiv(weightedSum, charactersRead) else 0
            val averageCharactersRead = if (validReadingDays != 0L) ceilDiv(charactersRead, validReadingDays) else 0
            val averageWeightedCharactersRead = if (readingTime != 0L) ceilDiv(weightedSum, readingTime) else 0
            val lastReadingSpeed = speed(charactersRead, readingTime)
            val averageReadingSpeed = speed(averageCharactersRead, averageReadingTime)
            val averageWeightedReadingSpeed = speed(averageWeightedCharactersRead, averageWeightedReadingTime)
            return "statistics_${EXPORTER_VERSION}_${DB_VERSION}_${lastStatisticModified}_${charactersRead}_" +
                "${readingTime}_${minReadingSpeed}_${altMinReadingSpeed}_${lastReadingSpeed}_${maxReadingSpeed}_" +
                "${averageReadingTime}_${averageWeightedReadingTime}_${averageCharactersRead}_" +
                "${averageWeightedCharactersRead}_${averageReadingSpeed}_${averageWeightedReadingSpeed}_$finishDate.json"
        }

        /**
         * ttu's `sanitizeForFilename`: a title as its folder name in ttu's exports. Only a last space or
         * dot is marked (ttu's patterns have no global flag); each of `/?<>\:|%"` is percent-encoded.
         */
        fun sanitizeForFilename(title: String): String {
            var name = title
            if (name.endsWith(" ")) name = name.dropLast(1) + "~ttu-spc~"
            if (name.endsWith(".")) name = name.dropLast(1) + "~ttu-dend~"
            name = name.replace("*", "~ttu-star~")
            return buildString {
                name.forEach { char ->
                    // encodeURIComponent's form of these ASCII characters.
                    if (char in ENCODED) append('%').append("%02X".format(Locale.ROOT, char.code)) else append(char)
                }
            }
        }

        private const val ENCODED = "/?<>\\:*|%\""

        /** `Math.ceil(a / b)` for the non-negative whole numbers ttu divides here. */
        private fun ceilDiv(a: Long, b: Long): Long = if (a <= 0) a / b else (a + b - 1) / b
    }
}

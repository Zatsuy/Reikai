/*
 * Reikai JP: reading statistics exported in ttu's backup format (roadmap 4.4, ruling 12).
 * GPL-3.0-or-later. The layout follows ッツ Ebook Reader's backup-handler.ts (saveStatistics):
 * BSD-3-Clause, Copyright (c) 2026, ッツ Reader Authors. All rights reserved.
 */
package jp.reikai.stats

import kotlinx.serialization.json.JsonArray
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The statistics as a ttu backup zip, which ttu's "Import" takes: a folder per title (its name made
 * safe by ttu's rule) holding one `statistics_…json` file, named by ttu's rule from the title's totals,
 * with the title's days sorted by day as ttu writes them.
 */
object TtuExport {

    /** One title's file: its path in the zip and its JSON. */
    data class Entry(val path: String, val json: String)

    fun entries(statistics: List<TtuStatistic>): List<Entry> = statistics
        .groupBy { it.title }
        .toSortedMap()
        .map { (title, days) ->
            // By day, as ttu's database returns them; ttu names the file before it sorts, so the same order.
            val sorted = days.sortedBy { it.dateKey }
            val lastModified = sorted.maxOf { it.lastStatisticModified }
            val name = TtuStatistic.fileName(sorted, lastModified)
            Entry(
                path = "${TtuStatistic.sanitizeForFilename(title)}/$name",
                json = JsonArray(sorted.map(TtuStatistic::toJson)).toString(),
            )
        }

    /** Writes the zip of [statistics] to [output], which it closes. Returns the number of titles. */
    fun write(statistics: List<TtuStatistic>, output: OutputStream): Int {
        val entries = entries(statistics)
        // A zip needs at least one entry; the screen offers no export without statistics.
        if (entries.isEmpty()) {
            output.close()
            return 0
        }
        ZipOutputStream(output.buffered()).use { zip ->
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.path))
                zip.write(entry.json.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return entries.size
    }
}

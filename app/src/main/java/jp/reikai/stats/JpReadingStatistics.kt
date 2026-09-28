package jp.reikai.stats

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import jp.reikai.data.JpReaderDatabase
import jp.reikai.data.JpReaderDatabase.StatisticRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * The reading statistics (roadmap 4.4, ruling 12) in front of [JpReaderDatabase]: the Japanese reader's
 * tracker hands its changed days here every few seconds and on pause, and they are written off the main
 * thread. Kept for the process, so a reader rebuilt before a write lands (a rotation) and the statistics
 * screen both see the newest numbers, written or not.
 */
@Inject
@SingleIn(AppScope::class)
class JpReadingStatistics(private val database: () -> JpReaderDatabase) {

    private val unwritten = ConcurrentHashMap<Pair<String, String>, StatisticRow>()
    private val writeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Every day read of [title], by day, the unwritten ones included. */
    suspend fun forTitle(title: String): List<StatisticRow> {
        val stored = withContext(Dispatchers.IO) {
            runCatching { database().statistics(title) }
                .onFailure { logcat(LogPriority.WARN, it) { "Could not read reading statistics" } }
                .getOrDefault(emptyList())
        }
        return overlay(stored.filter { it.statistic.title == title }, unwritten.values.filter { it.statistic.title == title })
    }

    /** Every day read of every novel, the unwritten ones included. */
    suspend fun all(): List<StatisticRow> {
        val stored = withContext(Dispatchers.IO) {
            runCatching { database().allStatistics() }
                .onFailure { logcat(LogPriority.WARN, it) { "Could not read reading statistics" } }
                .getOrDefault(emptyList())
        }
        return overlay(stored, unwritten.values.toList())
    }

    /** Remembers [rows] now and writes them at once, off the main thread; the latest per title and day wins. */
    fun put(rows: Collection<StatisticRow>) {
        if (rows.isEmpty()) return
        rows.forEach { unwritten[it.statistic.title to it.statistic.dateKey] = it }
        scope.launch { flush() }
    }

    /** Writes every row not written yet; survives the caller's cancellation (a reader closing). */
    suspend fun flush() = withContext(NonCancellable + Dispatchers.IO) {
        writeLock.withLock {
            val batch = unwritten.values.toList()
            if (batch.isEmpty()) return@withLock
            runCatching { database().saveStatistics(batch) }
                .onSuccess { batch.forEach { unwritten.remove(it.statistic.title to it.statistic.dateKey, it) } }
                .onFailure { logcat(LogPriority.WARN, it) { "Could not save reading statistics" } }
        }
    }

    private fun overlay(stored: List<StatisticRow>, newer: Collection<StatisticRow>): List<StatisticRow> {
        if (newer.isEmpty()) return stored
        val byKey = LinkedHashMap<Pair<String, String>, StatisticRow>()
        stored.forEach { byKey[it.statistic.title to it.statistic.dateKey] = it }
        newer.forEach { byKey[it.statistic.title to it.statistic.dateKey] = it }
        return byKey.values.sortedWith(compareBy({ it.statistic.title }, { it.statistic.dateKey }))
    }
}

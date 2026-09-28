package jp.reikai.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * The Japanese reader's place in each chapter by character (phase 4 ruling 7), in front of
 * [JpReaderDatabase]: a page turn only updates memory, and the newest place per chapter is written a
 * moment later, off the main thread. Kept for the process rather than the reader, so a rebuilt reader
 * (rotation, a switch of reader) finds the place the last page turn left, written or not.
 */
@Inject
@SingleIn(AppScope::class)
class JpChapterPositions(private val database: () -> JpReaderDatabase) {

    private val recent = object : LinkedHashMap<Long, JpReaderDatabase.ChapterPosition>(RECENT + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, JpReaderDatabase.ChapterPosition>) =
            size > RECENT
    }
    private val unwritten = ConcurrentHashMap<Long, JpReaderDatabase.ChapterPosition>()
    private val writeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Conflated, so a place put while a write runs is written by the next round rather than lost. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in wake) {
                delay(WRITE_DELAY_MS)
                flush()
            }
        }
    }

    /** The place stored for [chapterId], or null for a chapter never read in the Japanese reader. */
    suspend fun get(chapterId: Long): JpReaderDatabase.ChapterPosition? {
        synchronized(recent) { recent[chapterId] }?.let { return it }
        val stored = withContext(Dispatchers.IO) {
            runCatching { database().position(chapterId) }
                .onFailure { logcat(LogPriority.WARN, it) { "Could not read a chapter position" } }
                .getOrNull()
        } ?: return null
        synchronized(recent) { recent.getOrPut(chapterId) { stored } }
        return stored
    }

    /**
     * Remembers [position] now and writes it shortly after; the latest per chapter wins. Without [write]
     * (a place passed on the way, auto-scroll's once a second) it is only kept, and written with the next
     * place that is.
     */
    fun put(position: JpReaderDatabase.ChapterPosition, write: Boolean = true) {
        synchronized(recent) { recent[position.chapterId] = position }
        unwritten[position.chapterId] = position
        if (write) wake.trySend(Unit)
    }

    /** Writes every place not written yet. */
    suspend fun flush() = writeLock.withLock {
        val batch = unwritten.values.toList()
        batch.forEach { position ->
            runCatching { database().savePosition(position) }
                .onSuccess { unwritten.remove(position.chapterId, position) }
                .onFailure { logcat(LogPriority.WARN, it) { "Could not save a chapter position" } }
        }
    }

    private companion object {
        /** Places kept in memory: a session's chapters and their neighbours. */
        const val RECENT = 64
        const val WRITE_DELAY_MS = 500L
    }
}

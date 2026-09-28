/*
 * Reikai JP: the Japanese reader's reading tracker (roadmap 4.4, phase 4 ruling 12). GPL-3.0-or-later.
 *
 * After ッツ Ebook Reader's book-reading-tracker.svelte (https://github.com/ttu-ttu/ebook-reader,
 * BSD-3-Clause, Copyright (c) 2026, ッツ Reader Authors. All rights reserved. Licence text:
 * LICENSES/BSD-3-Clause.txt): one-second ticks, the idle time taken back, jumps of 2700 characters or
 * more not counted, a tick split at midnight, and the statistic rows updated by ttu's own rule
 * ([TtuStatistic.updated]).
 */
package jp.reikai.stats

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Counts one reader session's reading into ttu's rows for the novel's title, one per day ([TtuStatistic]).
 *
 * - **Time** comes from [tick], called about once a second while the reader is resumed and on screen
 *   ([resume] to [pause]). It counts while the reader was active within the last [IDLE_MS] (a touch,
 *   a lookup, the page moving); past that the whole idle stretch is taken back and time stops until the
 *   reader is active again, as ttu does with "adjust statistics after idle time". Whole seconds are
 *   counted and the rest carried, so late ticks lose nothing.
 * - **Characters** come from the page's positions ([position], ttu's count of the characters before the
 *   first one on screen). Only reading forward counts: a move back and the same page again count once
 *   (the furthest place reached is remembered). A move of [JUMP] characters or more either way is a jump
 *   (ttu's skip thresholds), not reading: it counts nothing and reading goes on from where it landed.
 *   Leaving a chapter whose end was on screen for another (not by stepping back) counts the rest of
 *   its last page; the new chapter counts from where it opened.
 * - **Days**: a tick is the day it ends on; one reaching back past midnight gives the seconds before
 *   midnight to the day before (ttu's split; its characters stay with the later day, where ttu adds
 *   them to both).
 * - Changed days go to [onFlush] every [FLUSH_MS] and on [pause]. Nothing counts before [begin] (the
 *   title's stored days) or while the chapter's source is in incognito.
 */
class JpReadingTracker(
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** This session's totals so far, when the reader was rebuilt (a rotation) mid-session. */
    session: TtuStatistic? = null,
    private val onFlush: (List<TtuStatistic>) -> Unit,
) {

    private var title: String? = null
    private val days = HashMap<String, TtuStatistic>()
    private val dirty = LinkedHashSet<String>()

    /** This session's reading (ttu's `sessionStatistics`), whose speed the status bar shows. */
    var session: TtuStatistic = session ?: TtuStatistic(title = "", dateKey = "")
        private set

    private var running = false
    private var idle = false
    private var lastTick = 0L
    private var carryMs = 0L
    private var lastActivity = 0L
    private var lastFlush = 0L
    private var pendingChars = 0L
    private var recording = true
    private var last: Sample? = null

    /** The furthest character reached in the chapter being read: reading counts only past it. */
    private var mark = 0

    private class Sample(val chapterId: Long, val charOffset: Int, val chars: Int, val endSeen: Boolean)

    /** The novel's title (ttu's key) and its days stored so far; counting starts here. */
    fun begin(title: String, stored: List<TtuStatistic>) {
        this.title = title
        days.clear()
        stored.filter { it.title == title }.forEach { days[it.dateKey] = it }
        val at = now()
        lastTick = at
        lastActivity = at
        lastFlush = at
        carryMs = 0
        idle = false
    }

    /** The reader is resumed and on screen: time counts from now. */
    fun resume() {
        val at = now()
        running = true
        idle = false
        lastTick = at
        lastActivity = at
        lastFlush = at
        carryMs = 0
    }

    /** The reader left the screen: counts up to now and hands over what changed. */
    fun pause() {
        if (!running) return
        tick()
        running = false
        flush()
    }

    /** A touch, a lookup, or a page turn: the reader is reading. */
    fun activity() {
        val at = now()
        lastActivity = at
        if (idle) {
            idle = false
            lastTick = at
            carryMs = 0
        }
    }

    /**
     * Where the page is: [charOffset] characters before the first on screen, of the chapter's [chars],
     * [endSeen] when its end is on screen. [openedAtEnd]: the document was opened at its end by a step
     * back. [incognito]: the chapter's source keeps no history, or the page is the chapter translated
     * (4.5), so nothing is counted, and the next counted page starts afresh from where it lands.
     */
    fun position(
        chapterId: Long,
        charOffset: Int,
        chars: Int,
        endSeen: Boolean,
        openedAtEnd: Boolean = false,
        incognito: Boolean = false,
    ) {
        activity()
        recording = !incognito
        val previous = last
        last = Sample(chapterId, charOffset, chars, endSeen).takeUnless { incognito }
        if (previous == null || incognito) {
            mark = charOffset
            return
        }
        if (previous.chapterId != chapterId) {
            if (previous.endSeen && !openedAtEnd) {
                // The last page of the chapter left: read, as ttu counts it once the reader moves past.
                val rest = previous.chars - max(mark, previous.charOffset)
                if (rest in 1 until JUMP) pendingChars += rest
            }
            mark = charOffset
            return
        }
        if (abs(charOffset - previous.charOffset) >= JUMP) {
            mark = charOffset
            return
        }
        if (charOffset > mark) {
            pendingChars += charOffset - mark
            mark = charOffset
        }
    }

    /** About once a second while resumed. True when it handed days over (every [FLUSH_MS]). */
    fun tick(): Boolean {
        val at = now()
        if (!running || idle || title == null || last == null || !recording) {
            lastTick = at
            carryMs = 0
            if (!recording) pendingChars = 0
            return false
        }
        val deadline = lastActivity + IDLE_MS
        val until = min(at, deadline)
        carryMs += max(0, until - lastTick)
        lastTick = until
        val seconds = carryMs / 1000
        carryMs -= seconds * 1000
        if (at >= deadline) {
            // ttu: processStatistics(0, elapsed - idleTime): the idle stretch was counted, now taken back.
            add(seconds - IDLE_MS / 1000, pendingChars, until)
            pendingChars = 0
            idle = true
            carryMs = 0
            flush()
            return true
        }
        if (seconds != 0L || pendingChars != 0L) {
            add(seconds, pendingChars, until)
            pendingChars = 0
        }
        if (at - lastFlush >= FLUSH_MS) {
            flush()
            return true
        }
        return false
    }

    /** Hands the days changed since the last flush to [onFlush]. */
    fun flush() {
        lastFlush = now()
        if (dirty.isEmpty()) return
        val rows = dirty.mapNotNull(days::get)
        dirty.clear()
        onFlush(rows)
    }

    /** The day [dateKey]'s row so far, for tests and the status bar. */
    fun day(dateKey: String): TtuStatistic? = days[dateKey]

    private fun add(seconds: Long, characters: Long, at: Long) {
        val title = title ?: return
        val zone = zone()
        val time = Instant.ofEpochMilli(at).atZone(zone)
        val day = time.toLocalDate()
        // ttu's `getSecondsToDate(...) || 1`.
        val intoDay = max(1, Duration.between(day.atStartOfDay(zone), time).seconds)
        if (abs(seconds) > intoDay) {
            val sign = if (seconds < 0) -1 else 1
            update(title, day.minusDays(1), sign * (abs(seconds) - intoDay), 0, at)
            update(title, day, sign * intoDay, characters, at)
        } else {
            update(title, day, seconds, characters, at)
        }
        session = session.updated(seconds, characters, at)
    }

    private fun update(title: String, day: LocalDate, seconds: Long, characters: Long, at: Long) {
        val key = dateKey(day)
        val current = days[key] ?: TtuStatistic(title = title, dateKey = key, lastStatisticModified = at)
        days[key] = current.updated(seconds, characters, at)
        dirty += key
    }

    companion object {
        /** ttu's default forward and backward skip thresholds. */
        const val JUMP = 2700

        /** Without a touch, a lookup or a page moving for this long, the reader is taken to be away. */
        const val IDLE_MS = 5 * 60 * 1000L

        /** How often counted time is handed over while reading (ttu's flush interval). */
        const val FLUSH_MS = 10_000L

        /** ttu's day key: the local date as `yyyy-MM-dd` (its day starts at midnight by default). */
        fun dateKey(day: LocalDate): String = day.toString()
    }
}

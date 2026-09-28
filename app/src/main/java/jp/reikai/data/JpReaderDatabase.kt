package jp.reikai.data

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import jp.reikai.stats.TtuStatistic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Reikai JP's own reading data, in its own database file (`jp_reader.db`), never in upstream's schema
 * (its migrations are one numbered sequence a fork migration would collide with). Plain SQL over a
 * SQLDelight driver: the app's bundled SQLite on the device, an in-memory JDBC one in JVM tests.
 *
 * Version 1 holds the Japanese reader's place in each chapter by character (phase 4 ruling 7); version 2
 * adds the reading statistics (4.4, ruling 12): ttu's rows, one per novel title and day.
 */
class JpReaderDatabase(private val driver: SqlDriver) {

    /**
     * Where the Japanese reader was in a chapter: [charOffset] characters before the first one on
     * screen, of [chars] (ttu's count), and the whole [percent] reported to upstream with it, which
     * tells a stored place apart from one the standard reader or a mark-as-read moved since.
     */
    data class ChapterPosition(
        val chapterId: Long,
        val charOffset: Int,
        val chars: Int,
        val percent: Int,
        val updatedAt: Long,
    )

    /** Call off the main thread. */
    suspend fun position(chapterId: Long): ChapterPosition? = driver.executeQuery(
        identifier = null,
        sql = "SELECT chapter_id, char_offset, chars, percent, updated_at FROM chapter_position WHERE chapter_id = ?",
        mapper = { cursor ->
            QueryResult.Value(
                if (cursor.next().value) {
                    ChapterPosition(
                        chapterId = cursor.getLong(0)!!,
                        charOffset = cursor.getLong(1)!!.toInt(),
                        chars = cursor.getLong(2)!!.toInt(),
                        percent = cursor.getLong(3)!!.toInt(),
                        updatedAt = cursor.getLong(4)!!,
                    )
                } else {
                    null
                },
            )
        },
        parameters = 1,
    ) { bindLong(0, chapterId) }.await()

    /** Call off the main thread. */
    suspend fun savePosition(position: ChapterPosition) {
        driver.execute(
            identifier = null,
            sql = "INSERT OR REPLACE INTO chapter_position(chapter_id, char_offset, chars, percent, updated_at) " +
                "VALUES (?, ?, ?, ?, ?)",
            parameters = 5,
        ) {
            bindLong(0, position.chapterId)
            bindLong(1, position.charOffset.toLong())
            bindLong(2, position.chars.toLong())
            bindLong(3, position.percent.toLong())
            bindLong(4, position.updatedAt)
        }.await()
    }

    /**
     * A day of reading one novel: ttu's row ([statistic], keyed by its title and day as in ttu) and the
     * novel it was last written for, for this app's own lists.
     */
    data class StatisticRow(val novelId: Long, val statistic: TtuStatistic)

    /** Every day read of [title], by day. Call off the main thread. */
    suspend fun statistics(title: String): List<StatisticRow> = driver.executeQuery(
        identifier = null,
        sql = "SELECT $STATISTIC_COLUMNS FROM reading_statistic WHERE title = ? ORDER BY date_key",
        mapper = { cursor -> QueryResult.Value(statisticRows(cursor)) },
        parameters = 1,
    ) { bindString(0, title) }.await()

    /** Every day read of every novel, by title and day. Call off the main thread. */
    suspend fun allStatistics(): List<StatisticRow> = driver.executeQuery(
        identifier = null,
        sql = "SELECT $STATISTIC_COLUMNS FROM reading_statistic ORDER BY title, date_key",
        mapper = { cursor -> QueryResult.Value(statisticRows(cursor)) },
        parameters = 0,
    ).await()

    /**
     * Writes [rows], each replacing the stored row of its title and day. A few rows every few seconds,
     * so one statement each (a transaction would have to hold one of the driver's pooled connections).
     */
    suspend fun saveStatistics(rows: Collection<StatisticRow>) {
        rows.forEach { row ->
            val s = row.statistic
            driver.execute(
                identifier = null,
                sql = "INSERT OR REPLACE INTO reading_statistic($STATISTIC_COLUMNS) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                parameters = 12,
            ) {
                bindString(0, s.title)
                bindString(1, s.dateKey)
                bindLong(2, row.novelId)
                bindLong(3, s.charactersRead)
                bindLong(4, s.readingTime)
                bindLong(5, s.minReadingSpeed)
                bindLong(6, s.altMinReadingSpeed)
                bindLong(7, s.lastReadingSpeed)
                bindLong(8, s.maxReadingSpeed)
                bindLong(9, s.lastStatisticModified)
                bindLong(10, s.completedBook)
                bindString(11, s.completedData?.toString())
            }.await()
        }
    }

    private fun statisticRows(cursor: SqlCursor): List<StatisticRow> {
        val rows = ArrayList<StatisticRow>()
        while (cursor.next().value) {
            rows += StatisticRow(
                novelId = cursor.getLong(2)!!,
                statistic = TtuStatistic(
                    title = cursor.getString(0)!!,
                    dateKey = cursor.getString(1)!!,
                    charactersRead = cursor.getLong(3)!!,
                    readingTime = cursor.getLong(4)!!,
                    minReadingSpeed = cursor.getLong(5)!!,
                    altMinReadingSpeed = cursor.getLong(6)!!,
                    lastReadingSpeed = cursor.getLong(7)!!,
                    maxReadingSpeed = cursor.getLong(8)!!,
                    lastStatisticModified = cursor.getLong(9)!!,
                    completedBook = cursor.getLong(10),
                    completedData = cursor.getString(11)?.let { text ->
                        runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                    },
                ),
            )
        }
        return rows
    }

    private companion object {
        const val STATISTIC_COLUMNS = "title, date_key, novel_id, characters_read, reading_time, " +
            "min_reading_speed, alt_min_reading_speed, last_reading_speed, max_reading_speed, " +
            "last_statistic_modified, completed_book, completed_data"
    }

    /**
     * The tables, version by version. A new version adds a step to [steps] and raises [version]; a
     * step never changes once released, so a database at any version reaches the newest one.
     */
    object Schema : SqlSchema<QueryResult.AsyncValue<Unit>> {

        private val steps: List<List<String>> = listOf(
            // Version 1 (phase 4): the character position of each chapter read in the Japanese reader.
            listOf(
                """
                CREATE TABLE chapter_position(
                    chapter_id INTEGER NOT NULL PRIMARY KEY,
                    char_offset INTEGER NOT NULL,
                    chars INTEGER NOT NULL,
                    percent INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            ),
            // Version 2 (4.4): ttu's reading statistics, one row per novel title and day (ttu's key), with
            // the novel last read under that title.
            listOf(
                """
                CREATE TABLE reading_statistic(
                    title TEXT NOT NULL,
                    date_key TEXT NOT NULL,
                    novel_id INTEGER NOT NULL,
                    characters_read INTEGER NOT NULL,
                    reading_time INTEGER NOT NULL,
                    min_reading_speed INTEGER NOT NULL,
                    alt_min_reading_speed INTEGER NOT NULL,
                    last_reading_speed INTEGER NOT NULL,
                    max_reading_speed INTEGER NOT NULL,
                    last_statistic_modified INTEGER NOT NULL,
                    completed_book INTEGER,
                    completed_data TEXT,
                    PRIMARY KEY(title, date_key)
                )
                """.trimIndent(),
                "CREATE INDEX reading_statistic_date ON reading_statistic(date_key)",
            ),
        )

        override val version: Long = steps.size.toLong()

        override fun create(driver: SqlDriver) = QueryResult.AsyncValue {
            steps.flatten().forEach { driver.execute(null, it, 0).await() }
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ) = QueryResult.AsyncValue {
            for (from in oldVersion until newVersion) {
                steps[from.toInt()].forEach { driver.execute(null, it, 0).await() }
                callbacks.filter { it.afterVersion == from }.forEach { it.block(driver) }
            }
        }
    }
}

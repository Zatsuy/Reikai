package jp.reikai.data

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * Reikai JP's own reading data, in its own database file (`jp_reader.db`), never in upstream's schema
 * (its migrations are one numbered sequence a fork migration would collide with). Plain SQL over a
 * SQLDelight driver: the app's bundled SQLite on the device, an in-memory JDBC one in JVM tests.
 *
 * Version 1 holds the Japanese reader's place in each chapter by character (phase 4 ruling 7). Reading
 * statistics (4.4) add their tables as version 2, as one more step in [Schema.migrate].
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

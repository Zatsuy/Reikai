package jp.reikai.data

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import jp.reikai.stats.TtuStatistic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class JpReaderDatabaseTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = JpReaderDatabase(driver)

    @AfterEach
    fun close() = driver.close()

    @Test
    fun `a chapter's place is kept and the newest one replaces it`() = runTest {
        JpReaderDatabase.Schema.create(driver).await()
        database.position(5L) shouldBe null
        val first = JpReaderDatabase.ChapterPosition(5L, charOffset = 1200, chars = 8000, percent = 15, updatedAt = 10L)
        database.savePosition(first)
        database.position(5L) shouldBe first
        val later = first.copy(charOffset = 7990, percent = 100, updatedAt = 20L)
        database.savePosition(later)
        database.position(5L) shouldBe later
        database.position(6L) shouldBe null
    }

    @Test
    fun `a migration runs each version's step and the callbacks after it`() = runTest {
        var calledAt: Long? = null
        // From an empty database at version 0 to the newest: the same tables as a fresh one.
        JpReaderDatabase.Schema.migrate(
            driver,
            0L,
            JpReaderDatabase.Schema.version,
            AfterVersion(0L) { calledAt = 0L },
        ).await()
        calledAt shouldBe 0L
        val position = JpReaderDatabase.ChapterPosition(1L, 0, 10, 0, 1L)
        database.savePosition(position)
        database.position(1L) shouldBe position
    }

    @Test
    fun `the schema is at version 2 with the reading statistics`() {
        JpReaderDatabase.Schema.version shouldBe 2L
    }

    @Test
    fun `a database made before statistics gains them and keeps its places`() = runTest {
        JpReaderDatabase.Schema.migrate(driver, 0L, 1L).await()
        val position = JpReaderDatabase.ChapterPosition(3L, 40, 900, 4, 1L)
        database.savePosition(position)
        JpReaderDatabase.Schema.migrate(driver, 1L, 2L).await()
        database.position(3L) shouldBe position
        database.allStatistics() shouldBe emptyList()
        database.saveStatistics(listOf(row(9L, "猫", "2026-09-28", 100)))
        database.statistics("猫") shouldBe listOf(row(9L, "猫", "2026-09-28", 100))
    }

    @Test
    fun `a day of a title is kept whole and the newest replaces it`() = runTest {
        JpReaderDatabase.Schema.create(driver).await()
        val completed = Json.parseToJsonElement("""{"dateKey":"2026-09-27","charactersRead":5}""").jsonObject
        val full = JpReaderDatabase.StatisticRow(
            novelId = 9L,
            statistic = TtuStatistic(
                title = "吾輩は猫である",
                dateKey = "2026-09-27",
                charactersRead = 1200,
                readingTime = 300,
                minReadingSpeed = 9000,
                altMinReadingSpeed = 9500,
                lastReadingSpeed = 14400,
                maxReadingSpeed = 20000,
                lastStatisticModified = 1_790_000_000_000,
                completedBook = 1,
                completedData = completed,
            ),
        )
        database.saveStatistics(listOf(full, row(9L, "吾輩は猫である", "2026-09-28", 50), row(4L, "other", "2026-09-28", 7)))
        database.statistics("吾輩は猫である") shouldBe listOf(full, row(9L, "吾輩は猫である", "2026-09-28", 50))

        database.saveStatistics(listOf(row(9L, "吾輩は猫である", "2026-09-28", 80)))
        database.allStatistics() shouldBe listOf(
            row(4L, "other", "2026-09-28", 7),
            full,
            row(9L, "吾輩は猫である", "2026-09-28", 80),
        )
    }

    private fun row(novelId: Long, title: String, dateKey: String, characters: Long) = JpReaderDatabase.StatisticRow(
        novelId,
        TtuStatistic(title, dateKey, charactersRead = characters, readingTime = 60, lastStatisticModified = 5L),
    )
}

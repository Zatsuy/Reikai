package jp.reikai.data

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
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
    fun `the schema is at version 1 until statistics arrive`() {
        JpReaderDatabase.Schema.version shouldBe 1L
    }
}

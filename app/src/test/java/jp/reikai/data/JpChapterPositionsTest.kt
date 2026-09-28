package jp.reikai.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class JpChapterPositionsTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = JpReaderDatabase(driver)
    private val positions = JpChapterPositions { database }

    @AfterEach
    fun close() = driver.close()

    @Test
    fun `a place passed on the way is kept at once and written only with the next place that is`() = runTest {
        JpReaderDatabase.Schema.create(driver).await()
        val passed = JpReaderDatabase.ChapterPosition(5L, charOffset = 1200, chars = 8000, percent = 15, updatedAt = 1L)
        positions.put(passed, write = false)
        positions.get(5L) shouldBe passed
        // Longer than the writer's own delay: nothing woke it.
        withContext(Dispatchers.IO) { delay(WRITER_WAIT_MS) }
        database.position(5L) shouldBe null
        val settled = passed.copy(charOffset = 1300, percent = 16, updatedAt = 2L)
        positions.put(settled)
        withContext(Dispatchers.IO) { delay(WRITER_WAIT_MS) }
        database.position(5L) shouldBe settled
    }

    private companion object {
        const val WRITER_WAIT_MS = 1_000L
    }
}

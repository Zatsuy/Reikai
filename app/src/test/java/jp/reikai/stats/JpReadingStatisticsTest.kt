package jp.reikai.stats

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import jp.reikai.data.JpReaderDatabase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class JpReadingStatisticsTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = JpReaderDatabase(driver)

    @AfterEach
    fun close() = driver.close()

    @Test
    fun `a title's stored days are read`() = runTest {
        JpReaderDatabase.Schema.create(driver).await()
        val day = JpReaderDatabase.StatisticRow(1L, TtuStatistic("本", "2026-09-28", charactersRead = 900, readingTime = 60))
        database.saveStatistics(listOf(day))
        JpReadingStatistics { database }.forTitle("本") shouldBe listOf(day)
    }

    @Test
    fun `stored days that cannot be read are no days at all`() = runTest {
        JpReadingStatistics { error("the disk is gone") }.forTitle("本") shouldBe null
    }
}

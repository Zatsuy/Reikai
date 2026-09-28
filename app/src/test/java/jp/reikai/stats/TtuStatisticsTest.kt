package jp.reikai.stats

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.ZipInputStream

/** The Kotlin port against what ttu's own functions compute (TtuFixture, made by make-fixture.mjs). */
class TtuStatisticsTest {

    @ParameterizedTest(name = "step {index}")
    @MethodSource("updates")
    fun `each tick updates a row as ttu's updateStatistic does`(before: TtuStatistic, step: JsonObject, after: TtuStatistic) {
        before.updated(
            timeDiff = step.long("timeDiff"),
            characterDiff = step.long("characterDiff"),
            lastStatisticModified = step.long("at"),
        ) shouldBe after
    }

    @ParameterizedTest(name = "set {index}")
    @MethodSource("fileNameCases")
    fun `a title's file is named and filled as ttu names and fills it`(case: JsonObject) {
        val statistics = case.statistics()
        TtuStatistic.fileName(statistics, case.long("lastStatisticModified")) shouldBe case.string("fileName")
        // The content: the days sorted, every field as ttu stores it.
        json(JsonArray(statistics.sortedBy { it.dateKey }.map(TtuStatistic::toJson)).toString()) shouldBe
            json(case.string("content"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("titles")
    fun `titles become folder names by ttu's rule`(title: String, sanitized: String) {
        TtuStatistic.sanitizeForFilename(title) shouldBe sanitized
    }

    @Test
    fun `the export holds one file per title, named as ttu names that title's days in order`() {
        val sets = fixture.getValue("fileNames").jsonArray.map { it.jsonObject }.take(2)
        val rows = sets.flatMap { it.statistics() }
        val bytes = ByteArrayOutputStream()

        TtuExport.write(rows.shuffled(Random(7)), bytes) shouldBe 2

        val files = ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.associate { it.name to json(zip.readBytes().decodeToString()) }
        }
        // ttu's database returns a title's days in order, so its export names the sorted days.
        files shouldBe mapOf(
            "吾輩は猫である/${sets[0].string("sortedFileName")}" to json(sets[0].string("content")),
            "Re%3Aゼロから始める異世界生活/${sets[1].string("sortedFileName")}" to json(sets[1].string("content")),
        )
    }

    @Test
    fun `an export without statistics writes nothing and says so`() {
        val bytes = ByteArrayOutputStream()
        TtuExport.write(emptyList(), bytes) shouldBe 0
        bytes.size() shouldBe 0
    }

    @Test
    fun `speeds are characters per hour rounded up`() {
        TtuStatistic.speed(777, 61) shouldBe 45856
        TtuStatistic.speed(10, 0) shouldBe 0
        TtuStatistic.speed(0, 30) shouldBe 0
    }

    companion object {
        private val fixture = Json.parseToJsonElement(TtuFixture.JSON).jsonObject

        private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

        private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long

        private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

        private fun JsonObject.statistics() = getValue("statistics").jsonArray.map { it.jsonObject.toStatistic() }

        private fun JsonObject.toStatistic() = TtuStatistic(
            title = string("title"),
            dateKey = string("dateKey"),
            charactersRead = long("charactersRead"),
            readingTime = long("readingTime"),
            minReadingSpeed = long("minReadingSpeed"),
            altMinReadingSpeed = long("altMinReadingSpeed"),
            lastReadingSpeed = long("lastReadingSpeed"),
            maxReadingSpeed = long("maxReadingSpeed"),
            lastStatisticModified = long("lastStatisticModified"),
            completedBook = get("completedBook")?.jsonPrimitive?.longOrNull,
            completedData = get("completedData") as? JsonObject,
        )

        /** Each step of ttu's sequence with the row before it (the step before's result) and after it. */
        @JvmStatic
        fun updates(): List<Arguments> {
            val steps = fixture.getValue("updates").jsonArray.map { it.jsonObject }
            val afters = steps.map { it.getValue("after").jsonObject.toStatistic() }
            val first = TtuStatistic(title = "吾輩は猫である", dateKey = "2026-09-20")
            return steps.mapIndexed { index, step ->
                Arguments.of(if (index == 0) first else afters[index - 1], step, afters[index])
            }
        }

        @JvmStatic
        fun fileNameCases(): List<JsonObject> = fixture.getValue("fileNames").jsonArray.map { it.jsonObject }

        @JvmStatic
        fun titles(): List<Arguments> = fixture.getValue("sanitized").jsonArray.map {
            Arguments.of(it.jsonObject.string("title"), it.jsonObject.string("sanitized"))
        }
    }
}

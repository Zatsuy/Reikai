package jp.reikai.yomitan.audio

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class YomitanAudioSourcesTest {

    private fun source(type: String, url: String = "") = buildJsonObject {
        put("type", type)
        put("url", url)
        put("voice", "")
    }

    private val local = source("custom-json", LocalAudio.SOURCE_URL)
    private val tts = source("custom", TtsAudio.SOURCE_URL)

    private fun List<JsonObject>.types() = map { it["type"]!!.jsonPrimitive.content }

    @Test
    fun `local audio goes first and text-to-speech after Yomitan's Japanese defaults`() {
        YomitanAudioSources.sources(
            listOf(source("jpod101")),
            localAudio = true,
            textToSpeech = true,
            japaneseDefaults = true,
        )
            .shouldContainExactly(local, source("jpod101"), source("language-pod-101"), source("jisho"), tts)
    }

    @Test
    fun `without Yomitan's defaults text-to-speech simply goes last`() {
        YomitanAudioSources.sources(
            listOf(source("jisho")),
            localAudio = false,
            textToSpeech = true,
            japaneseDefaults = false,
        )
            .shouldContainExactly(source("jisho"), tts)
    }

    @Test
    fun `sources already listed stay where they are and are not added twice`() {
        val current = listOf(source("jpod101"), local, tts, source("wiktionary"))
        YomitanAudioSources.sources(current, localAudio = true, textToSpeech = true, japaneseDefaults = true)
            .shouldContainExactly(current)
    }

    @Test
    fun `switched-off sources are removed and the user's own are kept`() {
        val custom = source("custom", "https://example.com/{term}.mp3")
        YomitanAudioSources.sources(
            listOf(local, custom, tts),
            localAudio = false,
            textToSpeech = false,
            japaneseDefaults = true,
        )
            .types().shouldContainExactly("custom")
    }

    @Test
    fun `the current profile's listed sources are read`() {
        val options = kotlinx.serialization.json.Json.parseToJsonElement(
            """
            {"profileCurrent": 1, "profiles": [
              {"options": {"audio": {"sources": []}}},
              {"options": {"audio": {"sources": [$tts, ${source("jpod101")}]}}}
            ]}
            """.trimIndent(),
        ) as JsonObject

        YomitanAudioSources.listed(options) shouldBe (false to true)
    }
}

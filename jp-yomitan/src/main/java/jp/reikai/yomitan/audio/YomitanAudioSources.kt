package jp.reikai.yomitan.audio

import jp.reikai.yomitan.YomitanEngine
import jp.reikai.yomitan.settings.YomitanSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Puts Reikai JP's audio sources into Yomitan's own settings, in every profile, through Yomitan's
 * `modifySettings` (the settings screen, roadmap 3.5, calls [apply] when the user switches them):
 * local audio ([LocalAudio.SOURCE_URL], custom-json) first, as the offline source users curate, and
 * text-to-speech ([TtsAudio.SOURCE_URL], custom) last, as the fallback. An entry with the same type
 * and URL is never added twice (a desktop backup may already list local audio under this URL), and a
 * switched-off source is removed.
 *
 * Yomitan tries its Japanese defaults (jpod101, language-pod-101, jisho) after the listed sources
 * when "enableDefaultAudioSources" is on, so text-to-speech is added after them explicitly to stay
 * the last resort. Switching text-to-speech off removes those again, when they are exactly what
 * Yomitan would add by itself at the end, so its switch controls them again and nothing plays in
 * another order.
 */
object YomitanAudioSources {

    /** Makes every profile list local audio and text-to-speech as asked; returns how many profiles changed. */
    suspend fun apply(engine: YomitanEngine, localAudio: Boolean, textToSpeech: Boolean): Int {
        val profiles = engine.api("optionsGetFull").jsonObject["profiles"]?.jsonArray.orEmpty()
        val targets = profiles.mapIndexedNotNull { index, profile ->
            val options = profile.jsonObject["options"]?.jsonObject ?: return@mapIndexedNotNull null
            val audio = options["audio"]?.jsonObject ?: return@mapIndexedNotNull null
            val current = audio["sources"]?.jsonArray.orEmpty().map { it.jsonObject }
            val language = options["general"]?.jsonObject?.get("language")?.let {
                (it as? JsonPrimitive)?.contentOrNull
            }
            val defaults =
                (audio["enableDefaultAudioSources"] as? JsonPrimitive)?.booleanOrNull != false && language == "ja"
            val wanted = sources(current, localAudio, textToSpeech, defaults)
            if (wanted == current) {
                null
            } else {
                buildJsonObject {
                    put("action", "set")
                    put("path", "audio.sources")
                    put("value", JsonArray(wanted))
                    put("scope", "profile")
                    put("optionsContext", buildJsonObject { put("index", index) })
                }
            }
        }
        if (targets.isEmpty()) return 0
        YomitanSettings.modify(engine, JsonArray(targets))
        return targets.size
    }

    /** Which of Reikai JP's sources the current profile lists: (local audio, text-to-speech). */
    suspend fun listed(engine: YomitanEngine): Pair<Boolean, Boolean> = listed(engine.api("optionsGetFull").jsonObject)

    internal fun listed(options: JsonObject): Pair<Boolean, Boolean> {
        val index = (options["profileCurrent"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val sources = options["profiles"]?.jsonArray?.getOrNull(index)?.jsonObject?.get("options")?.jsonObject
            ?.get("audio")?.jsonObject?.get("sources")?.jsonArray.orEmpty().map { it.jsonObject }
        val local = source("custom-json", LocalAudio.SOURCE_URL)
        val tts = source("custom", TtsAudio.SOURCE_URL)
        return sources.any { it.sameAs(local) } to sources.any { it.sameAs(tts) }
    }

    /** One profile's source list with Reikai JP's sources added or removed. */
    internal fun sources(
        current: List<JsonObject>,
        localAudio: Boolean,
        textToSpeech: Boolean,
        japaneseDefaults: Boolean,
    ): List<JsonObject> {
        val local = source("custom-json", LocalAudio.SOURCE_URL)
        val tts = source("custom", TtsAudio.SOURCE_URL)
        val list = current.toMutableList()
        if (!localAudio) list.removeAll { it.sameAs(local) }
        if (!textToSpeech) {
            val wasLast = list.lastOrNull()?.sameAs(tts) == true
            list.removeAll { it.sameAs(tts) }
            // The Japanese defaults added before it (below) go with it.
            if (wasLast && japaneseDefaults) dropImpliedDefaults(list)
        }
        if (localAudio && list.none { it.sameAs(local) }) list.add(0, local)
        if (textToSpeech && list.none { it.sameAs(tts) }) {
            if (japaneseDefaults) {
                val listed = list.mapNotNull { it.type() }.toSet()
                JAPANESE_DEFAULTS.filter { it !in listed }.forEach { list += source(it, "") }
            }
            list += tts
        }
        return list
    }

    /**
     * Drops the Japanese defaults at the end of [list] when they are the very ones, in the same order,
     * that Yomitan appends by itself (those not listed earlier): the ones added with text-to-speech.
     */
    private fun dropImpliedDefaults(list: MutableList<JsonObject>) {
        val run = list.takeLastWhile { it.type() in JAPANESE_DEFAULTS && it.url().isNullOrEmpty() }.size
        val drop = (run downTo 1).firstOrNull { n ->
            val kept = list.subList(0, list.size - n).mapNotNull { it.type() }.toSet()
            list.takeLast(n).map { it.type() } == JAPANESE_DEFAULTS.filter { it !in kept }
        } ?: return
        repeat(drop) { list.removeAt(list.lastIndex) }
    }

    private fun source(type: String, url: String) = buildJsonObject {
        put("type", type)
        put("url", url)
        put("voice", "")
    }

    private fun JsonObject.type(): String? = (this["type"] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.url(): String? = (this["url"] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.sameAs(other: JsonObject): Boolean = type() == other.type() && url() == other.url()

    /** Yomitan's required Japanese sources (`getRequiredAudioSourceList` in `audio-downloader.js`). */
    private val JAPANESE_DEFAULTS = listOf("jpod101", "language-pod-101", "jisho")
}

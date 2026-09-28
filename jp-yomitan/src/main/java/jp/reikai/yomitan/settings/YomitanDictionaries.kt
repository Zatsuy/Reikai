package jp.reikai.yomitan.settings

import jp.reikai.yomitan.YomitanEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** A dictionary installed in Yomitan (one entry of its backend's `getDictionaryInfo`). */
data class InstalledDictionary(
    val title: String,
    /** Its words (0 for a frequency, pitch or kanji-only dictionary). */
    val terms: Int,
    val kanji: Int,
    /** False when its import stopped part way (Yomitan then offers to delete it). */
    val complete: Boolean,
)

/** The dictionaries installed in Yomitan, for the Japanese settings (roadmap 3.5). */
object YomitanDictionaries {

    /** Every installed dictionary, in Yomitan's order. Throws when the engine is not running. */
    suspend fun installed(engine: YomitanEngine): List<InstalledDictionary> = parse(engine.api("getDictionaryInfo"))

    /** `getDictionaryInfo`'s answer: a list of summaries, `{title, counts: {terms: {total}, ...}, importSuccess}`. */
    internal fun parse(info: JsonElement): List<InstalledDictionary> = (info as? JsonArray).orEmpty().mapNotNull {
        val summary = it as? JsonObject ?: return@mapNotNull null
        val title = (summary["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val counts = summary["counts"] as? JsonObject
        InstalledDictionary(
            title = title,
            terms = counts.total("terms"),
            kanji = counts.total("kanji"),
            complete = (summary["importSuccess"] as? JsonPrimitive)?.booleanOrNull != false,
        )
    }

    private fun JsonObject?.total(key: String): Int =
        ((this?.get(key) as? JsonObject)?.get("total") as? JsonPrimitive)?.intOrNull ?: 0
}

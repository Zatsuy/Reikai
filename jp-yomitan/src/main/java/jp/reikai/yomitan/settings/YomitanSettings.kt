package jp.reikai.yomitan.settings

import jp.reikai.yomitan.YomitanEngine
import jp.reikai.yomitan.YomitanException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Changes to Yomitan's settings the app makes itself, as Yomitan's own settings page makes them. */
object YomitanSettings {

    /** The `source` Yomitan passes on with its "options updated" message, so a change can be told apart. */
    const val SOURCE = "reikai-jp"

    /**
     * Applies [targets] (`modifySettings` changes: `{action, path, value, scope, optionsContext}`)
     * through Yomitan's backend, which saves them and tells its pages. Yomitan answers each change
     * with `{result}` or `{error}` rather than failing the call, so any error is thrown here as a
     * [YomitanException] (the changes before it are applied).
     */
    suspend fun modify(engine: YomitanEngine, targets: JsonArray) {
        val results = engine.api(
            "modifySettings",
            buildJsonObject {
                put("targets", targets)
                put("source", SOURCE)
            },
        )
        errors(results).firstOrNull()?.let { throw YomitanException(it) }
    }

    /** The messages of the changes `modifySettings` answered with an error. */
    internal fun errors(results: JsonElement): List<String> = (results as? JsonArray).orEmpty().mapNotNull { result ->
        val error = (result as? JsonObject)?.get("error") ?: return@mapNotNull null
        ((error as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Yomitan refused a setting"
    }
}

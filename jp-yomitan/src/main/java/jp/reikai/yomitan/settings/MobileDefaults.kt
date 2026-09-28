package jp.reikai.yomitan.settings

import jp.reikai.yomitan.YomitanEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Yomitan's settings made for a phone or tablet screen, set once on a fresh profile (the app decides
 * when: never over settings a user made or imported). Yomitan's defaults are a desktop browser's:
 * - compact definitions and tags (`general.glossaryLayoutMode`, `general.compactTags`), so a sheet
 *   shows more of an entry without scrolling;
 * - a word tapped in the results opens in the same sheet, with Yomitan's back and forward buttons
 *   (and the back gesture), rather than a popup nested inside the sheet's popup, which is cramped on
 *   a phone (`scanning.enablePopupSearch` on, `scanning.popupNestingMaxDepth` 0).
 */
object MobileDefaults {

    internal val VALUES: Map<String, JsonElement> = mapOf(
        "general.glossaryLayoutMode" to JsonPrimitive("compact"),
        "general.compactTags" to JsonPrimitive(true),
        "scanning.enablePopupSearch" to JsonPrimitive(true),
        "scanning.popupNestingMaxDepth" to JsonPrimitive(0),
    )

    /** `modifySettings` targets setting [VALUES] in the current profile. */
    internal fun targets(): JsonArray = buildJsonArray {
        VALUES.forEach { (path, value) ->
            add(
                buildJsonObject {
                    put("action", "set")
                    put("path", path)
                    put("value", value)
                    put("scope", "profile")
                    put("optionsContext", buildJsonObject { put("current", true) })
                },
            )
        }
    }

    suspend fun apply(engine: YomitanEngine) = YomitanSettings.modify(engine, targets())
}

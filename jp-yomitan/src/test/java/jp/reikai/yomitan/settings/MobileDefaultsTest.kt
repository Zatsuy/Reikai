package jp.reikai.yomitan.settings

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File

class MobileDefaultsTest {

    @Test
    fun `the current profile gets compact results and lookups inside the sheet`() {
        val targets = MobileDefaults.targets().map { it.jsonObject }

        targets.map { it["path"]!!.jsonPrimitive.content to it["value"].toString() }.shouldContainExactlyInAnyOrder(
            "general.glossaryLayoutMode" to "\"compact\"",
            "general.compactTags" to "true",
            "scanning.enablePopupSearch" to "true",
            "scanning.popupNestingMaxDepth" to "0",
        )
        targets.map { it["optionsContext"].toString() }.toSet() shouldBe setOf("""{"current":true}""")
    }

    /** A Yomitan update that renames one of these settings, or its values, fails here. */
    @Test
    fun `every value is one the vendored Yomitan's settings schema accepts`() {
        val schema = Json.parseToJsonElement(
            File("src/main/assets/yomitan/data/schemas/options-schema.json").readText(),
        )
        val options = schema.at("properties", "profiles", "items", "properties", "options", "properties")
        val checked = MobileDefaults.VALUES.map { (path, value) ->
            val (section, key) = path.split('.')
            val property = options.at(section, "properties", key)
            val inEnum = (property["enum"] as? JsonArray)?.contains(value) ?: true
            path to (property["type"]!!.jsonPrimitive.content == typeOf(value.jsonPrimitive) && inEnum)
        }
        checked.filterNot { it.second } shouldBe emptyList()
    }

    private fun JsonElement.at(vararg keys: String): JsonObject = keys.fold(jsonObject) { o, key ->
        o[key]!!.jsonObject
    }

    private fun typeOf(value: JsonPrimitive) = when {
        value.isString -> "string"
        value.content == "true" || value.content == "false" -> "boolean"
        else -> "integer"
    }
}

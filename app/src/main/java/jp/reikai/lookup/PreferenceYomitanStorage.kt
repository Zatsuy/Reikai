package jp.reikai.lookup

import jp.reikai.yomitan.YomitanStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.core.common.preference.Preference

/**
 * Yomitan's `chrome.storage.local` in one string preference (see [jp.reikai.JpPreferences.yomitanStorage]).
 * Decoded once and kept in memory; every change writes the whole object back, which the preference
 * store saves off the main thread.
 */
class PreferenceYomitanStorage(private val preference: Preference<String>) : YomitanStorage {

    private val values: MutableMap<String, String> by lazy {
        runCatching {
            Json.parseToJsonElement(preference.get()).jsonObject.mapValuesTo(LinkedHashMap()) {
                it.value.jsonPrimitive.content
            }
        }.getOrElse { LinkedHashMap() }
    }

    override fun get(keys: Collection<String>?): Map<String, String> =
        if (keys == null) values.toMap() else values.filterKeys { it in keys }

    override fun set(items: Map<String, String>) {
        values.putAll(items)
        save()
    }

    override fun remove(keys: Collection<String>) {
        keys.forEach { values.remove(it) }
        save()
    }

    override fun clear() {
        values.clear()
        save()
    }

    private fun save() = preference.set(JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString())
}

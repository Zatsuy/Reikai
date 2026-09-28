package jp.reikai.lookup

import jp.reikai.yomitan.YomitanStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.core.common.preference.Preference

/**
 * Yomitan's `chrome.storage.local` in one string preference (see [jp.reikai.JpPreferences.yomitanStorage]).
 * Decoded once and kept in memory; every change writes the whole object back, which the preference
 * store saves off the main thread. A stored text this class did not write (a restored backup) is
 * read again, never overwritten by the old values, and reported through [replaced].
 */
class PreferenceYomitanStorage(private val preference: Preference<String>) : YomitanStorage {

    /** The stored text [values] holds, as read or last written; null until first read. */
    private var raw: String? = null
    private var values = LinkedHashMap<String, String>()

    override val replaced: Flow<Unit> = preference.changes().filter { raw != null && it != raw }.map { }

    override fun get(keys: Collection<String>?): Map<String, String> =
        current().let { if (keys == null) it.toMap() else it.filterKeys { key -> key in keys } }

    override fun set(items: Map<String, String>) {
        current().putAll(items)
        save()
    }

    override fun remove(keys: Collection<String>) {
        current().let { values -> keys.forEach { values.remove(it) } }
        save()
    }

    override fun clear() {
        current().clear()
        save()
    }

    /** The values, read again when the stored text is not the one they came from. */
    private fun current(): MutableMap<String, String> {
        // The preference store hands back the very string last written, so this is cheap.
        val stored = preference.get()
        if (stored != raw) {
            values = decode(stored)
            raw = stored
        }
        return values
    }

    private fun save() {
        val text = JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()
        raw = text
        preference.set(text)
    }

    private fun decode(text: String): LinkedHashMap<String, String> = runCatching {
        Json.parseToJsonElement(text).jsonObject.mapValuesTo(LinkedHashMap()) { it.value.jsonPrimitive.content }
    }.getOrElse { LinkedHashMap() }
}

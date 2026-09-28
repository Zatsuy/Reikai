package jp.reikai.lookup

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

@OptIn(ExperimentalCoroutinesApi::class)
class PreferenceYomitanStorageTest {

    /** A string preference whose changes flow emits every new value, as the app's preference store does. */
    private class FakePreference(initial: String) : Preference<String> {
        val value = MutableStateFlow(initial)
        override fun key() = "jp_yomitan_storage_local"
        override fun get() = value.value
        override fun set(value: String) {
            this.value.value = value
        }
        override fun isSet() = true
        override fun delete() {}
        override fun defaultValue() = "{}"
        override fun changes() = value
        override fun stateIn(scope: CoroutineScope): StateFlow<String> = value
    }

    private val preference = FakePreference("""{"options":"{\"v\":1}"}""")
    private val storage = PreferenceYomitanStorage(preference)

    @Test
    fun `a restored backup is read again, never overwritten with the values read before it`() {
        storage.get(null)
        preference.set("""{"options":"{\"v\":2}"}""")

        storage.set(mapOf("dictionaryImportQueue" to "0"))

        preference.get() shouldBe """{"options":"{\"v\":2}","dictionaryImportQueue":"0"}"""
    }

    @Test
    fun `a restore is reported, the storage's own writes are not`() = runTest {
        val reports = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { storage.replaced.collect { reports += it } }
        storage.get(null)

        storage.set(mapOf("options" to """{"v":3}"""))
        preference.set("""{"options":"{\"v\":4}"}""")

        reports.size shouldBe 1
    }
}

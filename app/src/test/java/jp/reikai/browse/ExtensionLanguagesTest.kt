package jp.reikai.browse

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.NovelPreferences
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.registry.LnRepoRegistries
import reikai.novel.registry.LnRepoResult
import reikai.novel.source.NovelExtensionFormat
import reikai.presentation.browse.extension.BrowseExtensionRow
import reikai.presentation.browse.extension.ExtensionKey
import reikai.presentation.browse.extension.ExtensionSection
import reikai.presentation.recents.EmittingPreferenceStore

/** Novel extensions answer to the language filter manga extensions already had. */
class ExtensionLanguagesTest {

    private val store = EmittingPreferenceStore()
    private val sourcePreferences = SourcePreferences(store)
    private val novelPreferences = NovelPreferences(store)

    @Test
    fun `a plugin on offer in a switched-off language is hidden, installed ones stay`() {
        val rows = listOf(
            row("ja-offered", "ja", ExtensionSection.Available("ja")),
            row("en-offered", "en", ExtensionSection.Available("en")),
            row("en-installed", "en", ExtensionSection.Installed),
            row("en-update", "en", ExtensionSection.Updates),
            row("en-broken", "en", ExtensionSection.NotLoaded),
        )

        languages().offered(rows, setOf("ja")).map { it.name } shouldBe
            listOf("ja-offered", "en-installed", "en-update", "en-broken")
    }

    @Test
    fun `an IReader extension listed as jp shows when Japanese is on`() {
        val rows = listOf(
            row(
                "ireader-jp",
                "jp",
                ExtensionSection.Available("jp"),
                ExtensionKey.NovelApk("ireader-jp", NovelExtensionFormat.IREADER),
            ),
            row(
                "ireader-tu",
                "tu",
                ExtensionSection.Available("tu"),
                ExtensionKey.NovelApk("ireader-tu", NovelExtensionFormat.IREADER),
            ),
        )

        languages().offered(rows, setOf("ja")).map { it.name } shouldBe listOf("ireader-jp")
    }

    @Test
    fun `seeding repeats while a first store fetch could still overwrite it`() = runTest {
        novelPreferences.installedPluginMetadata().set(
            mapOf("https://a/kakuyomu.js" to LnInstalledPluginMetadata(pluginId = "kakuyomu", lang = "日本語")),
        )

        languages().enabled.first() shouldContain "ja"
        // Upstream's first-run step replaces the set with its defaults later in that session.
        sourcePreferences.enabledLanguages.set(setOf("en"))

        languages().enabled.first() shouldBe setOf("en", "ja")
    }

    @Test
    fun `the languages of plugins installed before the filter are switched on once`() = runTest {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        novelPreferences.installedPluginMetadata().set(
            mapOf("https://a/kakuyomu.js" to LnInstalledPluginMetadata(pluginId = "kakuyomu", lang = "日本語")),
        )

        languages().enabled.first() shouldBe setOf("en", "ja")

        // The user then switches Japanese off; seeding does not undo that.
        sourcePreferences.enabledLanguages.set(setOf("en"))
        languages().enabled.first() shouldBe setOf("en")
    }

    @Test
    fun `the filter lists plugin languages as codes beside the manga ones`() = runTest {
        sourcePreferences.enabledLanguages.set(setOf("en"))
        val registries = mockk<LnRepoRegistries> {
            every { results } returns flowOf(
                mapOf(
                    "repo" to LnRepoResult.Reached(listOf(entry("kakuyomu", "日本語"), entry("novelbin", "English"))),
                    "down" to LnRepoResult.Unreachable("offline"),
                ),
            )
        }

        languages(registries).withNovelLanguages(flowOf(listOf("en", "fr"))).first()
            .toSet() shouldBe setOf("en", "fr", "ja")
    }

    private fun languages(registries: LnRepoRegistries = mockk()) = ExtensionLanguages(
        sourcePreferences = sourcePreferences,
        novelPreferences = novelPreferences,
        preferenceStore = store,
        extensionManager = mockk<ExtensionManager> {
            every { availableNovelExtensionsFlow } returns MutableStateFlow(emptyList())
            coEvery { getLoadedNovelExtensions() } returns emptyList()
        },
        registries = registries,
    )

    private fun row(
        name: String,
        lang: String,
        section: ExtensionSection,
        key: ExtensionKey = ExtensionKey.Novel(name),
    ) = BrowseExtensionRow(
        key = key,
        name = name,
        lang = lang,
        section = section,
        needsAttention = false,
        searchTerms = listOf(name),
        searchIds = listOf(name),
        payload = Unit,
    )

    private fun entry(id: String, lang: String) =
        LnRegistryEntry(id = id, name = id, version = "1", site = "", lang = lang, url = "https://a/$id.js")
}

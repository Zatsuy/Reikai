package jp.reikai.browse

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import reikai.domain.novel.NovelPreferences
import reikai.novel.registry.LnRepoRegistries
import reikai.novel.registry.LnRepoResult
import reikai.novel.source.toLangCode
import reikai.presentation.browse.extension.BrowseExtensionRow
import reikai.presentation.browse.extension.ExtensionSection
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getAndSet

/**
 * The Extensions language filter, extended from manga to novels: novel extensions and LNReader
 * plugins answer to Mihon's enabled languages too, one setting for both content types (decision
 * D-020). Installed novel sources keep Reikai's own per-language switches in the Sources filter.
 */
@Inject
@SingleIn(AppScope::class)
class ExtensionLanguages(
    private val sourcePreferences: SourcePreferences,
    private val novelPreferences: NovelPreferences,
    private val preferenceStore: PreferenceStore,
    private val extensionManager: ExtensionManager,
    private val registries: LnRepoRegistries,
) {

    /** Mihon's enabled languages, first seeded with the languages of the plugins already installed. */
    val enabled: Flow<Set<String>> = sourcePreferences.enabledLanguages.changes()
        .onStart { seedFromInstalledPlugins() }

    /**
     * The filter screen's languages with the novel ones added, ordered as upstream orders them:
     * enabled first, then by name. Registry languages arrive as names and are turned into codes.
     */
    fun withNovelLanguages(mangaLanguages: Flow<List<String>>): Flow<List<String>> = combine(
        mangaLanguages,
        extensionManager.availableNovelExtensionsFlow,
        registries.results,
        sourcePreferences.enabledLanguages.changes(),
    ) { manga, novelApks, repos, enabled ->
        val plugins = repos.values.flatMap { (it as? LnRepoResult.Reached)?.entries.orEmpty() }
        (manga + novelApks.flatMap { ext -> ext.sources.map { it.lang } } + plugins.map { it.lang.toLangCode() })
            .distinct()
            .sortedWith(compareBy<String> { it !in enabled }.then(LocaleHelper.comparator))
    }

    /**
     * [rows] without those on offer in a language that is switched off. Installed, updating and failed
     * extensions always stay, as in Mihon. Manga rows arrive already filtered, so only novel rows change.
     */
    fun offered(rows: List<BrowseExtensionRow>, enabled: Set<String>): List<BrowseExtensionRow> =
        rows.filter { it.section !is ExtensionSection.Available || it.lang in enabled }

    // Before this filter existed nothing hid a novel plugin, so plugins may be installed in a language
    // that is switched off. Turning those languages on once keeps what their repos offer in view. Later
    // installs need no such step: a plugin can only be installed from a row its language already shows.
    private fun seedFromInstalledPlugins() {
        val seeded = preferenceStore.getBoolean(SEEDED_KEY, false)
        if (seeded.get()) return
        val installed = novelPreferences.installedPluginMetadata().get().values
            .mapNotNullTo(HashSet()) { it.lang?.toLangCode() }
        if (!sourcePreferences.enabledLanguages.get().containsAll(installed)) {
            sourcePreferences.enabledLanguages.getAndSet { it + installed }
        }
        seeded.set(true)
    }

    private companion object {
        const val SEEDED_KEY = "jp_extension_languages_seeded"
    }
}

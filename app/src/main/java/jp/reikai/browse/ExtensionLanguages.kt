package jp.reikai.browse

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import reikai.domain.library.ContentType
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

    // Read before this class seeds anything: while the set was never written, ExtensionManager's first
    // manga store fetch replaces it with the defaults (enableAdditionalSubLanguages), erasing a seed.
    private val languagesSetAtStart = sourcePreferences.enabledLanguages.isSet()

    /** Mihon's enabled languages, first seeded with the languages of the novel extensions installed. */
    val enabled: Flow<Set<String>> = sourcePreferences.enabledLanguages.changes()
        .onStart { seedFromInstalled() }

    /**
     * The filter screen's languages with the novel ones added, ordered as upstream orders them:
     * enabled first, then by name.
     */
    fun withNovelLanguages(mangaLanguages: Flow<List<String>>): Flow<List<String>> = combine(
        mangaLanguages,
        extensionManager.availableNovelExtensionsFlow,
        registries.results,
        sourcePreferences.enabledLanguages.changes(),
    ) { manga, novelApks, repos, enabled ->
        val plugins = repos.values.flatMap { (it as? LnRepoResult.Reached)?.entries.orEmpty() }
        (
            manga + novelApks.flatMap { ext -> ext.sources.map { novelLangCode(it.lang) } } +
                plugins.map { novelLangCode(it.lang) }
            )
            .distinct()
            .sortedWith(compareBy<String> { it !in enabled }.then(LocaleHelper.comparator))
    }
        // Every registry entry is re-read on each toggle, so keep that off the main thread.
        .flowOn(Dispatchers.Default)

    /**
     * [rows] without those on offer in a language that is switched off. Installed, updating and failed
     * extensions always stay, as in Mihon. Manga rows arrive already filtered, so only novel rows change.
     */
    fun offered(rows: List<BrowseExtensionRow>, enabled: Set<String>): List<BrowseExtensionRow> = rows.filter {
        it.section !is ExtensionSection.Available ||
            (if (it.key.contentType == ContentType.NOVELS) novelLangCode(it.lang) else it.lang) in enabled
    }

    // Before this filter existed nothing hid a novel extension, so some may be installed in a language
    // that is switched off. Turning those languages on keeps what their repos offer in view. It counts
    // as done only once no store fetch can overwrite it; until then it runs again on the next visit.
    private suspend fun seedFromInstalled() {
        val seeded = preferenceStore.getBoolean(SEEDED_KEY, false)
        if (seeded.get()) return
        val installed = buildSet {
            novelPreferences.installedPluginMetadata().get().values.forEach { meta ->
                meta.lang?.let { add(novelLangCode(it)) }
            }
            extensionManager.getLoadedNovelExtensions().forEach { ext ->
                ext.sources.forEach { add(novelLangCode(it.lang)) }
            }
        }
        if (!sourcePreferences.enabledLanguages.get().containsAll(installed)) {
            sourcePreferences.enabledLanguages.getAndSet { it + installed }
        }
        if (languagesSetAtStart) seeded.set(true)
    }

    private companion object {
        const val SEEDED_KEY = "jp_extension_languages_seeded"
    }
}

/**
 * A novel extension's language as the ISO code the filter speaks. Plugins name theirs ("日本語", see
 * [toLangCode]); IReader's store uses codes of its own for a few.
 */
internal fun novelLangCode(lang: String): String = IREADER_CODES[lang] ?: lang.toLangCode()

// IReader-extensions' index (repov2/index.min.json) as of 2026-09; everything else there is ISO already.
private val IREADER_CODES = mapOf("jp" to "ja", "cn" to "zh", "tu" to "tr", "in" to "id", "multi" to "all")

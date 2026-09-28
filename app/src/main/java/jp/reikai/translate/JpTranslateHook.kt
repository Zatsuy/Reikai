package jp.reikai.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Reikai JP's hook into the novel reader's chapter loading for translation (4.5, phase 4 ruling 15):
 * `NovelReaderViewModel.loadChapterHtml` hands every chapter it prepares (an open, a reload, a
 * neighbour fetched ahead) through [load], so the standard reader in either mode and the Japanese
 * reader all get a chapter shown translated as its translation. A reading session no translation was
 * asked in has no [TranslationSession] and loads exactly as upstream's.
 */
object JpTranslateHook {

    /** By reader model: a session's translations live as long as its model (the reading session). */
    private val sessions = WeakHashMap<Any, TranslationSession>()

    /**
     * `NovelReaderViewModel.loadChapterHtml`: [loader] is upstream's (the pipeline output and the base
     * address); a chapter [viewModel]'s session shows translated comes back as its translation.
     */
    @JvmStatic
    suspend fun load(
        viewModel: Any,
        chapterId: Long,
        fromSource: Boolean,
        loader: suspend () -> Pair<String, String?>,
    ): Pair<String, String?> {
        val session = synchronized(sessions) { sessions[viewModel] } ?: return loader()
        return session.load(chapterId, fromSource, loader)
    }

    internal fun session(viewModel: Any, translations: ChapterTranslations): TranslationSession =
        synchronized(sessions) { sessions.getOrPut(viewModel) { TranslationSession(translations::translated) } }
}

/**
 * One reading session's translations: which chapters are shown translated and into what ("shown
 * translated" lasts while the reader is open: an immersion reader peeks rather than stays), the chapter
 * being translated, and each shown chapter's original, so going back needs no second fetch.
 */
class TranslationSession internal constructor(
    /** The chapter's saved translation in place, or null when none is saved for its text. */
    private val translated: (chapterId: Long, key: TranslationKey, html: String) -> String?,
) {

    private val shownState = MutableStateFlow<Map<Long, TranslationKey>>(emptyMap())
    val shown: StateFlow<Map<Long, TranslationKey>> = shownState

    /** The chapter being translated, if one is. */
    val busy = MutableStateFlow<Long?>(null)

    private val originals = ConcurrentHashMap<Long, Pair<String, String?>>()

    /** What the next load of a chapter takes instead of running upstream's loader, once. */
    private val reuse = ConcurrentHashMap<Long, Pair<String, String?>>()

    internal suspend fun load(
        chapterId: Long,
        fromSource: Boolean,
        loader: suspend () -> Pair<String, String?>,
    ): Pair<String, String?> {
        val reused = reuse.remove(chapterId)?.takeUnless { fromSource }
        val original = reused ?: loader()
        val key = shownState.value[chapterId] ?: return original
        originals[chapterId] = original
        val translation = withContext(Dispatchers.Default) {
            runCatching { translated(chapterId, key, original.first) }
                .onFailure { logcat(LogPriority.WARN, it) { "Could not show chapter $chapterId translated" } }
                .getOrNull()
        }
        if (translation == null) {
            // Its text changed since it was translated (a replacement rule, a new copy): the original,
            // and the menu offers the translation again.
            hide(chapterId)
            return original
        }
        return translation to original.second
    }

    /** [chapterId] shown translated under [key] from its next load, which takes [original] as it is. */
    internal fun show(chapterId: Long, key: TranslationKey, original: Pair<String, String?>?) {
        if (original != null) {
            originals[chapterId] = original
            reuse[chapterId] = original
        }
        shownState.update { it + (chapterId to key) }
    }

    /** The next load of [chapterId] runs upstream's loader after all (the reopen it was kept for did not happen). */
    internal fun forgetOriginal(chapterId: Long) {
        reuse.remove(chapterId)
    }

    /** [chapterId] shown as its source has it from its next load, which takes the original kept for it. */
    internal fun hide(chapterId: Long, reuseOriginal: Boolean = false) {
        shownState.update { it - chapterId }
        val original = originals.remove(chapterId)
        if (reuseOriginal && original != null) reuse[chapterId] = original
    }
}

package jp.reikai.reader.page

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import jp.reikai.yomitan.text.JapaneseText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.novel.source.NovelSourceManager
import reikai.novel.source.langCode
import reikai.novel.source.toLangCode
import tachiyomi.core.common.util.system.logcat
import java.util.WeakHashMap

/**
 * Which reader each open novel is read in: the Japanese reader or upstream's standard one (phase 4
 * rulings 3 and 4). One answer per reading session, keyed by the novel the session opened (for a
 * merged series, the entry it was opened as, where upstream keeps the rotation too), so the viewport,
 * the chapter pipeline and the chapter window always agree:
 *
 * - the chapter pipeline decides it for the session's first chapter, with that chapter's text at hand
 *   for a source whose language is not one language ([decideForLoad]); a novel whose choice or source
 *   language settles it is decided sooner, from its row alone ([decideEarly]);
 * - the reader's viewport ([JpReaderSwitch]) and the model's window ([holdsOneChapter]) read it;
 * - the reader menu changes it ([choose]), which also rewrites the novel's `viewer_flags`.
 */
@Inject
@SingleIn(AppScope::class)
class JpReaderModes(
    private val novelRepository: NovelRepository,
    private val sourceManager: NovelSourceManager,
    private val novelPreferences: NovelPreferences,
    private val setChoice: SetJpReaderChoice,
) {

    /** Each reading session's chapter loader, with the novel the session opened. */
    private val sessions = WeakHashMap<Any, Long>()

    private val decided = MutableStateFlow<Map<Long, Boolean>>(emptyMap())

    /** Rises on every switch of reader, which makes the model prepare its chapters again. */
    val switches: StateFlow<Int>
        field = MutableStateFlow(0)

    /** A reading session began for [novelId] with [loader]; it decides afresh, so a restored backup or
     *  a newly installed source counts from the next time the novel opens. */
    fun bind(loader: Any, novelId: Long) {
        synchronized(sessions) { sessions[loader] = novelId }
        decided.update { it - novelId }
    }

    /** The reader decided for [novelId]'s session, or null while it is not known yet. */
    fun known(novelId: Long): Boolean? = decided.value[novelId]

    fun decisions(novelId: Long) = decided.map { it[novelId] }

    /** Whether the session of [novelId] reads one chapter per page, which the Japanese reader does. */
    fun holdsOneChapter(novelId: Long): Boolean = known(novelId) == true

    suspend fun await(novelId: Long): Boolean = decisions(novelId).filterNotNull().first()

    /** Decides from the novel's row and its source's language alone, or null when that takes a chapter. */
    suspend fun decideEarly(novelId: Long): Boolean? {
        known(novelId)?.let { return it }
        val decision = decide(novelId, sample = null) ?: return null
        return publish(novelId, decision)
    }

    /**
     * The decision for the session whose [loader] is loading a chapter of [chapterNovelId] with [raw]
     * text, made now when nothing made it yet. The first answer stands for the session.
     */
    suspend fun decideForLoad(loader: Any, chapterNovelId: Long, raw: String): Boolean {
        val novelId = synchronized(sessions) { sessions[loader] } ?: chapterNovelId
        known(novelId)?.let { return it }
        // A novel that cannot be read (its row gone mid-load) keeps upstream's reader rather than
        // failing the chapter.
        val decision = try {
            decide(novelId, sample = raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not decide the novel's reader" }
            null
        }
        return publish(novelId, decision ?: false)
    }

    /**
     * The reader chose [japanese] for [novelId] in the menu: the session follows at once and the novel
     * remembers it. Rewrites the novel's flags off the caller's thread; [persist] runs that write.
     */
    fun choose(novelId: Long, japanese: Boolean, persist: (suspend () -> Unit) -> Unit) {
        decided.update { it + (novelId to japanese) }
        switches.update { it + 1 }
        val choice = if (japanese) JpReaderChoice.JAPANESE else JpReaderChoice.STANDARD
        persist { setChoice.await(novelId, choice) }
    }

    private fun publish(novelId: Long, decision: Boolean): Boolean {
        decided.update { if (novelId in it) it else it + (novelId to decision) }
        return decided.value[novelId] ?: decision
    }

    private suspend fun decide(novelId: Long, sample: String?): Boolean? {
        val novel = novelRepository.getById(novelId) ?: return false
        return JpReaderDefault.decide(JpReaderChoice.of(novel.viewerFlags), languageOf(novel.source)) {
            sample?.let(JapaneseText::looksJapanese)
        }
    }

    /**
     * The source's language without loading plugins, which a downloaded chapter never needs: the one
     * recorded when the plugin was last seen installed, else an app source's. Null when neither knows.
     */
    private suspend fun languageOf(sourceId: String): String? =
        novelPreferences.seenNovelSources().get()[sourceId]?.lang?.toLangCode()?.takeIf { it.isNotBlank() }
            ?: sourceManager.getWithoutPlugins(sourceId)?.langCode()
}

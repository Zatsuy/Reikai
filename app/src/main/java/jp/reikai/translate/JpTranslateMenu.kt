package jp.reikai.translate

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewModelScope
import eu.kanade.presentation.components.AppBar
import jp.reikai.di.jpGraph
import jp.reikai.reader.JpReaderHook
import jp.reikai.yomitan.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import reikai.presentation.reader.NovelReaderViewModel
import tachiyomi.core.common.util.system.logcat

/**
 * The reader menu's "Translate chapter", or "Show original" while the chapter is shown translated
 * (4.5, phase 4 ruling 15), for the novel open in this Activity; null in a manga reader. Nothing goes
 * to a translation service before the tap.
 */
@Composable
fun jpTranslateMenuAction(): AppBar.OverflowAction? {
    val activity = LocalActivity.current ?: return null
    val control = remember(activity) { ChapterTranslationControl.of(activity) } ?: return null
    val chapter by control.viewModel.chapter.collectAsState()
    val shown by control.session.shown.collectAsState()
    val busy by control.session.busy.collectAsState()
    val id = chapter?.chapterId ?: return null
    return when {
        busy == id -> AppBar.OverflowAction(title = stringResource(R.string.jp_translate_busy), onClick = {})
        id in shown -> AppBar.OverflowAction(
            title = stringResource(R.string.jp_translate_original),
            onClick = control::showOriginal,
        )
        else -> AppBar.OverflowAction(
            title = stringResource(R.string.jp_translate_chapter),
            onClick = control::translate,
        )
    }
}

/**
 * The menu's actions on the reading session of [viewModel]. A translation runs in the session's scope,
 * so turning the tablet does not stop it; the chapter then opens again where the reader is, by percent
 * (the line at the top of the original is somewhere else in its translation).
 */
internal class ChapterTranslationControl(
    private val context: Context,
    val viewModel: NovelReaderViewModel,
    val session: TranslationSession,
    private val translations: ChapterTranslations,
) {

    /** "Translating the chapter…", gone once the translation shows or fails, so an error is not queued behind it. */
    private var progress: Toast? = null

    fun translate() {
        val chapter = viewModel.chapter.value ?: return
        val id = chapter.chapterId
        if (!session.busy.compareAndSet(null, id)) return
        progress = toast(context.getString(R.string.jp_translate_started), Toast.LENGTH_SHORT)
        viewModel.viewModelScope.launch {
            try {
                val key = translations.translate(id, chapter.html)
                val stillOpen = viewModel.chapter.value?.chapterId == id
                session.show(id, key, (chapter.html to chapter.baseUrl).takeIf { stillOpen })
                if (stillOpen) reopen(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Could not translate chapter $id" }
                progress?.cancel()
                toast(message(e), Toast.LENGTH_LONG)
            } finally {
                progress?.cancel()
                progress = null
                session.busy.value = null
            }
        }
    }

    fun showOriginal() {
        val id = viewModel.chapter.value?.chapterId ?: return
        session.hide(id, reuseOriginal = true)
        reopen(id)
    }

    private fun reopen(id: Long) {
        viewModel.reportTopLine(id, null)
        viewModel.reloadChapter(fromSource = false)
    }

    private fun message(e: Throwable): String = when (e) {
        is TranslationSetupMissing -> when (e.what) {
            TranslationSetupMissing.What.KEY -> context.getString(R.string.jp_translate_missing_key)
            else -> context.getString(R.string.jp_translate_missing_address)
        }
        is NothingToTranslate -> context.getString(R.string.jp_translate_nothing)
        else -> context.getString(R.string.jp_translate_failed, e.message ?: e.javaClass.simpleName)
    }

    private fun toast(text: String, length: Int): Toast = Toast.makeText(context, text, length).also { it.show() }

    companion object {
        fun of(activity: Activity): ChapterTranslationControl? {
            val viewModel = JpReaderHook.viewModelOf(activity) ?: return null
            val translations = activity.jpGraph.chapterTranslations
            return ChapterTranslationControl(
                context = activity.applicationContext,
                viewModel = viewModel,
                session = JpTranslateHook.session(viewModel, translations),
                translations = translations,
            )
        }
    }
}

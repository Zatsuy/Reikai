package jp.reikai.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.view.View
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.webkit.WebView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import jp.reikai.reader.page.JpPageViewport
import jp.reikai.translate.ChapterTranslator
import jp.reikai.yomitan.text.JapaneseText
import kotlinx.coroutines.flow.StateFlow
import reikai.presentation.reader.NovelReaderViewModel
import java.io.File
import java.util.WeakHashMap

/**
 * Reikai JP's hook into upstream's novel reader: one-line seams call it (the provider's attach, the
 * native renderer's layout manager and each of its text chunks, the WebView, the WebView document's
 * opening tag, the model's report of a chapter that fits on one screen). Everything it does lives in
 * the [JpReaderSession] of the reader's activity.
 */
object JpReaderHook {

    private val sessions = WeakHashMap<Activity, JpReaderSession>()

    /** A novel opened in [host] (`NovelReaderProvider.attach`); the session ends with the activity. */
    @JvmStatic
    fun attach(host: ReaderActivity, viewModel: NovelReaderViewModel) {
        val session = JpReaderSession(host, viewModel, classifierMode(host))
        synchronized(sessions) { sessions.put(host, session) }?.close()
        session.start()
    }

    /**
     * The native renderer's layout manager (`NovelTextViewport`): upstream's, except that an item
     * taking focus never scrolls the page. A chapter's text is one selectable item, which the first
     * long-press focuses; RecyclerView then scrolls the focused item's top to the top of the page
     * (87 px on the tablet, with the chapter heading above it) while the finger is still down, and
     * the press, now over other text, selects from one line into the next. Scrolling for a selection
     * handle dragged to the edge (a rectangle request, not focus) is unchanged.
     */
    @JvmStatic
    fun layoutManager(context: Context): LinearLayoutManager = object : LinearLayoutManager(context) {
        override fun onRequestChildFocus(
            parent: RecyclerView,
            state: RecyclerView.State,
            child: View,
            focused: View?,
        ): Boolean = true
    }

    /** A text chunk of the native renderer (`NovelTextViewport.createChunkView`). */
    @JvmStatic
    fun decorate(view: TextView) {
        sessionOf(view.context)?.decorate(view)
    }

    /** The WebView renderer's WebView (`NovelWebViewport`). */
    @JvmStatic
    fun decorate(view: WebView) {
        sessionOf(view.context)?.decorate(view)
    }

    /** The Japanese reader's page viewport, built for [host] (4.1); the session hears its documents. */
    internal fun pageViewport(host: Activity, viewport: JpPageViewport) {
        synchronized(sessions) { sessions[host] }?.onPageViewport(viewport)
    }

    /**
     * Which reader [host]'s novel reads in, the Japanese one or the standard one (null until known), for
     * the status bar (4.5).
     */
    internal fun readerSwitch(host: Activity, isJapanese: StateFlow<Boolean?>) {
        synchronized(sessions) { sessions[host] }?.onReaderKnown(isJapanese)
    }

    /**
     * `NovelReaderViewModel.reportFitsOnScreen`: a chapter of [viewModel]'s reader fits on one screen or
     * page, or no longer does. [finish] marks a chapter read through upstream's own finishing (the one a
     * forward step from a chapter that fits takes), for "mark chapters that fit on one screen as read"
     * (4.5, ruling 14). Any thread.
     */
    @JvmStatic
    fun chapterFits(viewModel: NovelReaderViewModel, chapterId: Long, fits: Boolean, finish: (Long) -> Unit) {
        val session = synchronized(sessions) { sessions.values.firstOrNull { it.readsFor(viewModel) } } ?: return
        session.onChapterFits(chapterId, fits, finish)
    }

    /**
     * The WebView document's opening tag (`NovelWebDocument.build`, off the main thread):
     * `lang="ja"` for Japanese, so a device in another language never draws Chinese glyph forms; a
     * chapter shown translated (4.5) carries the language it was translated into.
     */
    @JvmStatic
    fun htmlTag(context: Context, chapterHtml: String): String {
        ChapterTranslator.languageOf(chapterHtml)?.let { return "<html lang=\"$it\">" }
        val japanese = sessionOf(context)?.japanese == true || JapaneseText.looksJapanese(chapterHtml)
        return if (japanese) "<html lang=\"ja\">" else "<html>"
    }

    /** The reader model of [activity]'s novel, or null in a manga reader or another screen (4.5, translation). */
    internal fun viewModelOf(activity: Activity): NovelReaderViewModel? =
        synchronized(sessions) { sessions[activity] }?.viewModel

    internal fun ended(session: JpReaderSession, host: Activity) {
        synchronized(sessions) { if (sessions[host] === session) sessions.remove(host) }
    }

    private fun sessionOf(context: Context): JpReaderSession? {
        val activity = context.activity() ?: return null
        return synchronized(sessions) { sessions[activity] }
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

    /**
     * Debug builds only: `<external files>/jp-selection` holding `system` or `noop` puts the system's
     * classifier or none back, to compare the selection with and without the fork's (read when a
     * reader opens; `adb shell` can write it).
     */
    private fun classifierMode(context: Context): ClassifierMode {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return ClassifierMode.FORK
        val mode = runCatching { File(context.getExternalFilesDir(null), "jp-selection").readText().trim() }.getOrNull()
        return when (mode) {
            "system" -> ClassifierMode.SYSTEM
            "noop" -> ClassifierMode.NONE
            else -> ClassifierMode.FORK
        }
    }

    internal enum class ClassifierMode { FORK, SYSTEM, NONE }

    /** The classifier a reader view gets, or null to leave the view's own. */
    internal fun classifier(
        context: Context,
        mode: ClassifierMode,
        hooks: JpTextClassifier.Hooks,
        pressed: (CharSequence, Int, Int) -> Int? = { _, _, _ -> null },
        onlyGrow: Boolean = false,
    ): TextClassifier? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return when (mode) {
            ClassifierMode.SYSTEM -> null
            ClassifierMode.NONE -> TextClassifier.NO_OP
            ClassifierMode.FORK -> {
                val system = context.getSystemService(TextClassificationManager::class.java)?.textClassifier
                    ?: TextClassifier.NO_OP
                JpTextClassifier(system, hooks, pressed, onlyGrow)
            }
        }
    }
}

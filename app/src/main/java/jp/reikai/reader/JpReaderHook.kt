package jp.reikai.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.webkit.WebView
import android.widget.TextView
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import jp.reikai.yomitan.text.JapaneseText
import reikai.presentation.reader.NovelReaderViewModel
import java.io.File
import java.util.WeakHashMap

/**
 * Reikai JP's hook into upstream's novel reader: four one-line seams call it (the provider's
 * attach, each native text chunk, the WebView, the WebView document's opening tag). Everything it
 * does lives in the [JpReaderSession] of the reader's activity.
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

    /**
     * The WebView document's opening tag (`NovelWebDocument.build`, off the main thread):
     * `lang="ja"` for Japanese, so a device in another language never draws Chinese glyph forms.
     */
    @JvmStatic
    fun htmlTag(context: Context, chapterHtml: String): String {
        val japanese = sessionOf(context)?.japanese == true || JapaneseText.looksJapanese(chapterHtml)
        return if (japanese) "<html lang=\"ja\">" else "<html>"
    }

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

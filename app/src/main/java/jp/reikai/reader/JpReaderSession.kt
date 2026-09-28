package jp.reikai.reader

import android.annotation.SuppressLint
import android.app.RemoteAction
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import jp.reikai.di.jpGraph
import jp.reikai.yomitan.YomitanEngine
import jp.reikai.yomitan.text.JapaneseText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import reikai.novel.source.langCode
import reikai.presentation.reader.NovelReaderViewModel
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reikai JP's part of one novel reader (one [ReaderActivity]): Japanese text is drawn with Japanese
 * glyphs and long-presses select whole Japanese words ([JpTextClassifier]). Ends with the activity.
 */
internal class JpReaderSession(
    private val host: ReaderActivity,
    private val viewModel: NovelReaderViewModel,
    private val classifierMode: JpReaderHook.ClassifierMode,
) : DefaultLifecycleObserver,
    JpTextClassifier.Hooks {

    private val graph = host.jpGraph

    /** Whether the novel is Japanese; null until its first chapter is known. */
    @Volatile
    var japanese: Boolean? = null
        private set

    /** Text views made before the language was known, to be given it. */
    private val waiting = ArrayList<WeakReference<TextView>>()
    private var job: Job? = null

    fun start() {
        host.lifecycle.addObserver(this)
        job = host.lifecycleScope.launch {
            val chapter = viewModel.chapter.filterNotNull().first()
            val lang = chapter.sourceId?.let { graph.novelSourceManager.get(it)?.langCode() }
            onLanguage(lang == "ja" || JapaneseText.looksJapanese(chapter.html))
        }
    }

    fun decorate(view: TextView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val presses = PressTracker(view)
            JpReaderHook.classifier(view.context, classifierMode, this, presses::pressedIn)?.let {
                view.setTextClassifier(it)
                view.setOnTouchListener(presses)
            }
        }
        when (japanese) {
            true -> view.textLocale = Locale.JAPANESE
            false -> Unit
            null -> waiting += WeakReference(view)
        }
    }

    fun decorate(view: WebView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val presses = WebPressTracker(view)
            JpReaderHook.classifier(view.context, classifierMode, this, presses::pressedIn, onlyGrow = true)?.let {
                view.setTextClassifier(it)
                view.setOnTouchListener(presses)
            }
        }
    }

    private fun onLanguage(japanese: Boolean) {
        this.japanese = japanese
        if (japanese) waiting.forEach { it.get()?.textLocale = Locale.JAPANESE }
        waiting.clear()
    }

    // --- JpTextClassifier.Hooks, on the classifier's thread ----------------------------------------

    override suspend fun longestMatch(text: String): Int? {
        if (!graph.jpPreferences.lookupEnabled().get()) return null
        val engine = graph.yomitanEngine
        if (engine.state.value !is YomitanEngine.State.Ready) return null
        return engine.findTerms(text, limit = 1)?.length
    }

    override fun lookupAction(query: String, sentence: JapaneseText.Sentence): RemoteAction? = null

    fun close() {
        job?.cancel()
        host.lifecycle.removeObserver(this)
        JpReaderHook.ended(this, host)
    }

    override fun onDestroy(owner: LifecycleOwner) = close()

    /**
     * The character under the finger at the last press on a text chunk. Android selects from the
     * character boundary nearest the finger, which on the right half of a character is the one after
     * it; the classifier gets a window of the chunk's text, found here by matching it against the
     * chunk's (reader chunks never change their text).
     */
    private class PressTracker(private val view: TextView) : View.OnTouchListener {
        @Volatile private var text: CharSequence? = null

        @Volatile private var offset = -1

        @Volatile private var at = 0L

        /** Where the pressed character is in [window], a piece of the chunk's text around [start]..[end]. */
        fun pressedIn(window: CharSequence, start: Int, end: Int): Int? {
            val source = text ?: return null
            val pressed = offset
            if (pressed < 0 || SystemClock.uptimeMillis() - at > PRESS_MEMORY_MILLIS) return null
            return (start - 1..end).firstOrNull { candidate ->
                val shift = pressed - candidate
                candidate in window.indices && shift >= 0 && shift + window.length <= source.length &&
                    (maxOf(0, candidate - MATCH_REACH) until minOf(window.length, candidate + MATCH_REACH))
                        .all { window[it] == source[shift + it] }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
            offset = -1
            val layout = view.layout ?: return false
            val boundary = view.getOffsetForPosition(event.x, event.y)
            if (boundary < 0) return false
            val line = layout.getLineForVertical((event.y - view.totalPaddingTop + view.scrollY).toInt())
            val x = event.x - view.totalPaddingLeft + view.scrollX
            val before = boundary > layout.getLineStart(line) && x < layout.getPrimaryHorizontal(boundary)
            text = view.text
            at = event.eventTime
            offset = if (before) boundary - 1 else boundary
            // Only watching: the text view handles the press as before.
            return false
        }
    }

    /**
     * The same for the WebView renderer, whose page Kotlin cannot measure: Chromium also selects the
     * one character after the boundary nearest the finger, so the page is asked (on the main thread,
     * while the classifier waits on its own) whether the finger was left of that character.
     */
    private class WebPressTracker(private val view: WebView) : View.OnTouchListener {
        @Volatile private var x = 0f

        @Volatile private var y = 0f

        @Volatile private var at = 0L

        fun pressedIn(window: CharSequence, start: Int, end: Int): Int? {
            if (start <= 0 || start >= window.length || Looper.myLooper() == Looper.getMainLooper()) return null
            if (end - start != Character.charCount(Character.codePointAt(window, start))) return null
            if (SystemClock.uptimeMillis() - at > PRESS_MEMORY_MILLIS) return null
            val answer = CountDownLatch(1)
            var before = false
            val script = "($BEFORE_SELECTION)(${x.toDouble()}, ${y.toDouble()})"
            view.post {
                view.evaluateJavascript(script) {
                    before = it == "true"
                    answer.countDown()
                }
            }
            if (!answer.await(PAGE_ANSWER_MILLIS, TimeUnit.MILLISECONDS)) return null
            return if (before) Character.offsetByCodePoints(window, start, -1) else null
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                x = event.x
                y = event.y
                at = event.eventTime
            }
            return false
        }
    }

    private companion object {
        /** Whether a point (view pixels) is left of the page's selection: the finger was on the character before it. */
        const val BEFORE_SELECTION =
            "function (x, y) { const s = getSelection(); if (!s || s.rangeCount === 0) { return false; } " +
                "const r = s.getRangeAt(0).getBoundingClientRect(); return x / (devicePixelRatio || 1) < r.left; }"

        /** How long a long-press waits for the page to say where the finger was. */
        const val PAGE_ANSWER_MILLIS = 80L

        /** A long-press asks its question well within this of the finger going down. */
        const val PRESS_MEMORY_MILLIS = 3_000L

        /** How much text on each side of the pressed character must match to place it. */
        const val MATCH_REACH = 16
    }
}

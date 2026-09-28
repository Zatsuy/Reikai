package jp.reikai.reader

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.isNightMode
import jp.reikai.di.jpGraph
import jp.reikai.lookup.LookupSheet
import jp.reikai.yomitan.R
import jp.reikai.yomitan.YomitanEngine
import jp.reikai.yomitan.popup.PopupLookup
import jp.reikai.yomitan.text.JapaneseText
import jp.reikai.yomitan.text.TextWindow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.logcat
import reikai.novel.source.langCode
import reikai.presentation.reader.NovelReaderViewModel
import reikai.presentation.reader.readerBackgroundColorInt
import reikai.presentation.reader.resolvedForSystemTheme
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reikai JP's part of one novel reader (one [ReaderActivity]): Japanese text is drawn with Japanese
 * glyphs and long-presses select whole Japanese words ([JpTextClassifier]); with lookup on (D-025),
 * the lookup engine warms up once the first chapter is on screen, "Look up" leads the selection
 * toolbar and opens the lookup sheet over the reader. Ends with the activity.
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
    private var lookupJob: Job? = null

    /** This reader's use of the engine; read by the classifier's thread too. */
    @Volatile
    private var lease: YomitanEngine.Lease? = null

    /**
     * The engine, once this reader has had it built on the main thread (its warm-up or a lookup), for
     * the classifier's thread, which never builds it.
     */
    @Volatile
    private var seenEngine: YomitanEngine? = null
    private var sheet: LookupSheet? = null
    private var chapterUrl: String? = null
    private var closed = false

    /** Tells this reader's "Look up" presses from another reader's. */
    private val id = nextId.incrementAndGet()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_SESSION, -1) != id) return
            val query = intent.getStringExtra(EXTRA_QUERY) ?: return
            // Already looked up when the press was reported.
            if (query == pressed && SystemClock.uptimeMillis() - pressedAt < PRESS_MEMORY_MILLIS) return
            onLookUp(query, intent.getStringExtra(EXTRA_SENTENCE), intent.getIntExtra(EXTRA_OFFSET, 0))
        }
    }
    private var pressed: String? = null
    private var pressedAt = 0L

    /** Where the last long-press was on the screen (the line's top to bottom), for the sheet's place. */
    private var pressedLine: IntRange? = null

    /**
     * When the page was last touched or scrolled (uptime): the warm-up waits for a pause in reading.
     * Touches are seen on the text; a scroll through the window's views, since a press that stops a
     * fling goes to the scrolling list, never to the text under it. The reader's own scrolling (auto-
     * scroll, read-aloud following the text) is heard too, so the wait has a limit ([warmUpWait]).
     */
    private var touchedAt = 0L
    private val onTouched = { touchedAt = SystemClock.uptimeMillis() }
    private val onScrolled = ViewTreeObserver.OnScrollChangedListener { touchedAt = SystemClock.uptimeMillis() }

    fun start() {
        host.lifecycle.addObserver(this)
        // Opening the chapter counts as reading too: its first scroll often follows at once.
        touchedAt = SystemClock.uptimeMillis()
        host.window.decorView.viewTreeObserver.addOnScrollChangedListener(onScrolled)
        // Keeps "Look up in Reikai JP" in step with the lookup switch (it follows the switch while alive).
        graph.jpLookup
        ContextCompat.registerReceiver(
            host,
            receiver,
            IntentFilter(ACTION_LOOK_UP),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        job = host.lifecycleScope.launch {
            val chapter = viewModel.chapter.filterNotNull().first()
            val lang = chapter.sourceId?.let { graph.novelSourceManager.get(it)?.langCode() }
            onLanguage(lang == "ja" || JapaneseText.looksJapanese(chapter.html))
            // The chapter's web address, for Anki's {url}.
            viewModel.chapter.collect { loaded -> chapterUrl = loaded?.let { viewModel.webUrlFor(it) } }
        }
    }

    fun decorate(view: TextView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val presses = PressTracker(view, onTouched) { pressedLine = it }
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
            val presses = WebPressTracker(view, onTouched) { pressedLine = it }
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
        if (japanese) {
            lookupJob = host.lifecycleScope.launch {
                graph.jpPreferences.lookupEnabled().changes().distinctUntilChanged().collect { on ->
                    if (on) warmUp() else coolDown()
                }
            }
        }
    }

    /**
     * Starts the engine and readies the popup for a Japanese novel, each once the main thread is idle
     * and the page has not been touched for a moment, so neither delays the chapter nor a scroll: the
     * engine's WebView costs the main thread 85 ms and the popup 40 ms (tablet, native mode), and an
     * idle moment comes between two frames of a fling too.
     */
    private fun warmUp() {
        whenIdle {
            if (closed || lease != null || !graph.jpLookup.isEnabled) return@whenIdle
            val engine = graph.yomitanEngine.also { seenEngine = it }
            val began = SystemClock.uptimeMillis()
            lease = engine.acquire(host) ?: return@whenIdle
            logcat(LogPriority.DEBUG) {
                "warm-up: engine started on the main thread in ${SystemClock.uptimeMillis() - began} ms"
            }
            host.lifecycleScope.launch {
                if (engine.ready() &&
                    lease != null
                ) {
                    whenIdle {
                        if (!closed && lease != null) {
                            val at = SystemClock.uptimeMillis()
                            sheet().prewarm(isDarkPage())
                            logcat(LogPriority.DEBUG) {
                                "warm-up: popup readied in ${SystemClock.uptimeMillis() - at} ms"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun coolDown() {
        sheet?.close()
        lease?.close()
        lease = null
    }

    private fun whenIdle(askedAt: Long = SystemClock.uptimeMillis(), block: () -> Unit) {
        Looper.myQueue().addIdleHandler {
            val wait = warmUpWait(SystemClock.uptimeMillis(), touchedAt, askedAt)
            if (wait <= 0) {
                block()
            } else if (!closed) {
                host.window.decorView.postDelayed({ whenIdle(askedAt, block) }, wait)
            }
            false
        }
    }

    private fun sheet(): LookupSheet = sheet ?: LookupSheet(host, graph.jpLookup).also { sheet = it }

    private fun onLookUp(query: String, sentence: String?, offset: Int, askedAt: Long = SystemClock.uptimeMillis()) {
        if (!graph.jpLookup.isEnabled) return
        sheet().show(popupLookup(query, sentence, offset), askedAt, word = pressedLine)
        // Built by the sheet by now: the next selection may use it as soon as it is ready.
        seenEngine = graph.yomitanEngine
    }

    private fun popupLookup(query: String, sentence: String?, offset: Int): PopupLookup {
        val novel = viewModel.entryTitle.value
        val chapter = viewModel.chapter.value?.title
        return PopupLookup(
            query = query,
            sentence = sentence,
            offset = offset,
            documentTitle = listOfNotNull(chapter, novel).joinToString(" - ").ifEmpty { null },
            url = chapterUrl,
            dark = isDarkPage(),
        )
    }

    /** Whether the reader's page is dark, for Yomitan's "match the page" theme. */
    private fun isDarkPage(): Boolean {
        val settings = viewModel.settings.value.resolvedForSystemTheme(host.isNightMode())
        return ColorUtils.calculateLuminance(readerBackgroundColorInt(settings.backgroundColor)) < DARK_LUMINANCE
    }

    // --- JpTextClassifier.Hooks, on the classifier's thread ----------------------------------------

    override suspend fun longestMatch(text: String): Int? {
        // Any running engine (this reader's warm-up, the sheet's, another screen's) that this reader
        // has seen built: this never builds or starts it.
        val engine = seenEngine ?: return null
        if (!graph.jpPreferences.lookupEnabled().get()) return null
        if (engine.state.value !is YomitanEngine.State.Ready) return null
        return engine.findTerms(text, limit = 1)?.length
    }

    override fun lookupAction(query: String, sentence: JapaneseText.Sentence): RemoteAction? {
        if (closed || !graph.jpPreferences.lookupEnabled().get()) return null
        // The foreground queue: a background broadcast took 70 ms to arrive on the tablet.
        val intent = Intent(ACTION_LOOK_UP)
            .setPackage(host.packageName)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(EXTRA_SESSION, id)
            .putExtra(EXTRA_QUERY, query)
            .putExtra(EXTRA_SENTENCE, sentence.text)
            .putExtra(EXTRA_OFFSET, sentence.offset)
        val pending = PendingIntent.getBroadcast(
            host,
            id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Looked up now, while the toolbar shows, so a press on "Look up" only opens the sheet.
        host.runOnUiThread { if (!closed) sheet?.preload(popupLookup(query, sentence.text, sentence.offset)) }
        val title = host.getString(R.string.jp_lookup_action)
        return RemoteAction(Icon.createWithResource(host, R.drawable.jp_ic_lookup), title, title, pending)
            .apply { setShouldShowIcon(false) }
    }

    override fun onLookUpPressed(query: String, sentence: JapaneseText.Sentence) {
        val at = SystemClock.uptimeMillis()
        host.runOnUiThread {
            if (closed) return@runOnUiThread
            pressed = query
            pressedAt = at
            onLookUp(query, sentence.text, sentence.offset, at)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        job?.cancel()
        lookupJob?.cancel()
        runCatching { host.unregisterReceiver(receiver) }
        lease?.close()
        lease = null
        host.window.decorView.viewTreeObserver.let { if (it.isAlive) it.removeOnScrollChangedListener(onScrolled) }
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
    private class PressTracker(
        private val view: TextView,
        private val onTouched: () -> Unit,
        private val onLine: (IntRange) -> Unit,
    ) : View.OnTouchListener {
        @Volatile private var text: CharSequence? = null

        @Volatile private var offset = -1

        @Volatile private var at = 0L

        /** Where the pressed character is in [window], a piece of the chunk's text around [start]..[end]. */
        fun pressedIn(window: CharSequence, start: Int, end: Int): Int? {
            val source = text ?: return null
            val pressed = offset
            if (pressed < 0 || SystemClock.uptimeMillis() - at > PRESS_MEMORY_MILLIS) return null
            return TextWindow.locate(window, start, end, source, pressed)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            onTouched()
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
            // The pressed line on the screen: the view's top there, plus the line's place in the view.
            val lineTop = (event.rawY - event.y).toInt() + view.totalPaddingTop - view.scrollY
            onLine(lineTop + layout.getLineTop(line)..lineTop + layout.getLineBottom(line))
            // Only watching: the text view handles the press as before.
            return false
        }
    }

    /**
     * The same for the WebView renderer, whose page Kotlin cannot measure: Chromium also selects the
     * one character after the boundary nearest the finger, so the page is asked (on the main thread,
     * while the classifier waits on its own) whether the finger was left of that character.
     */
    private class WebPressTracker(
        private val view: WebView,
        private val onTouched: () -> Unit,
        private val onLine: (IntRange) -> Unit,
    ) : View.OnTouchListener {
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
            onTouched()
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                x = event.x
                y = event.y
                at = event.eventTime
                // The page's lines cannot be measured from here: about a line around the finger.
                val half = (LINE_DP / 2 * view.resources.displayMetrics.density).toInt()
                onLine(event.rawY.toInt() - half..event.rawY.toInt() + half)
            }
            return false
        }
    }

    internal companion object {
        private const val ACTION_LOOK_UP = "jp.reikai.action.READER_LOOK_UP"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_QUERY = "query"
        private const val EXTRA_SENTENCE = "sentence"
        private const val EXTRA_OFFSET = "offset"
        private const val DARK_LUMINANCE = 0.4

        private val nextId = AtomicInteger()

        /**
         * Whether a point (view pixels) is well left of the page's one-character selection: the finger
         * was on the character before it. A press within a quarter character of the boundary counts
         * as on the selected character: there Chromium's choice is as likely the word meant, and it
         * needs no widening (a suggestion must contain Chromium's character, so the finger's word
         * would be selected with that character added).
         */
        private const val BEFORE_SELECTION =
            "function (x, y) { const s = getSelection(); if (!s || s.rangeCount === 0) { return false; } " +
                "const r = s.getRangeAt(0).getBoundingClientRect(); " +
                "return x / (devicePixelRatio || 1) < r.left - r.width / 4; }"

        /** About one line of the WebView page's text, for where the sheet opens. */
        private const val LINE_DP = 40f

        /** How long a long-press waits for the page to say where the finger was. */
        private const val PAGE_ANSWER_MILLIS = 80L

        /** How long after the last touch the warm-up waits, so it never lands in a fling. */
        private const val QUIET_MILLIS = 2_000L

        /**
         * The longest the warm-up waits for that pause: the reader's own auto-scroll or read-aloud
         * never pauses, and a long-press after it should find the dictionary ready.
         */
        private const val MAX_WAIT_MILLIS = 10_000L

        /** A long-press asks its question well within this of the finger going down. */
        private const val PRESS_MEMORY_MILLIS = 3_000L

        /**
         * How much longer the warm-up asked for at [askedAt] waits at [now] (uptimes), the page last
         * touched or scrolled at [touchedAt]; 0 or less: now.
         */
        internal fun warmUpWait(now: Long, touchedAt: Long, askedAt: Long): Long =
            minOf(QUIET_MILLIS - (now - touchedAt), MAX_WAIT_MILLIS - (now - askedAt))
    }
}

package jp.reikai.reader.page

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import coil3.imageLoader
import jp.reikai.JpPreferences
import jp.reikai.data.JpChapterPositions
import jp.reikai.data.JpReaderDatabase
import jp.reikai.translate.ChapterTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import logcat.LogPriority
import reikai.data.coil.fetchNovelImage
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.fraction
import reikai.novel.font.NovelFontManager
import reikai.novel.font.isSupportedFontFile
import reikai.novel.network.NovelImageRequests
import reikai.presentation.reader.ChapterWindow
import reikai.presentation.reader.NovelChapterNavigationClient
import reikai.presentation.reader.NovelReaderSettings
import reikai.presentation.reader.NovelReaderViewModel
import reikai.presentation.reader.NovelTapAction
import reikai.presentation.reader.ReadAloudPosition
import reikai.presentation.reader.ReadAloudSurface
import reikai.presentation.reader.ReaderViewport
import reikai.presentation.reader.TextViewport
import reikai.presentation.reader.readerBackgroundColorInt
import reikai.presentation.reader.web.NovelWebImages
import reikai.util.isDebugInspectorBuild
import reikai.util.webContentsDebugging
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.max

/**
 * The Japanese reader (phase 4, item 4.1): one chapter per document in a WebView, laid out by the page
 * script `assets/jp-reader/jp-reader.js` in vertical or horizontal text, by pages or scrolling, to the
 * page contract in `docs/fork/research/phase4-design-2026-09.md`. Upstream's reader model drives it
 * through [TextViewport] as it drives its own viewports; the page reports where it is by character,
 * which is kept per chapter ([JpChapterPositions]) and reported to upstream as a percent.
 *
 * Seamless chapters are off in this reader (ruling 1): the page turn past a chapter's last page steps
 * the chapter, and the model publishes one chapter at a time for it (`JpPageHook.holdsOneChapter`).
 */
@SuppressLint("SetJavaScriptEnabled")
class JpPageViewport internal constructor(
    private val context: Context,
    private val jpPreferences: JpPreferences,
    private val positions: JpChapterPositions,
    private val fontManager: NovelFontManager,
    imageRequests: NovelImageRequests,
    private val isIncognito: suspend (sourceId: String?) -> Boolean,
    devTools: Boolean,
    /** Whether a volume key is the reader's right now: the setting, and the menu being down. */
    private val volumeKeysActive: () -> Boolean,
    /** A place passed on the way (auto-scroll), which upstream saves debounced. */
    private val onProgressChanged: (chapterId: Long, percent: Int) -> Unit,
    /** A place the reader settled on (a page turn, a stopped scroll, an edge, the end), saved at once. */
    private val onProgressSettled: (chapterId: Long, percent: Int) -> Unit,
    /** Upstream's line at the top of the screen, which this reader has none of: told null, so a switch back
     *  to the standard reader lands at the percent rather than at a line that reader saw before. */
    private val onTopLine: (chapterId: Long, line: Int?) -> Unit,
    private val onToggleMenu: () -> Unit,
    /** Steps to the next or previous chapter; the chapter it steps to, or null when there is none that way. */
    private val onStepChapter: (forward: Boolean) -> Long?,
    /** A chapter the model was asked to open failed to load (the reader goes on in the one it had). */
    loadFailures: Flow<Unit>,
    /** The cutout inset in dp, which the page's CSS pixels are. */
    private val cutoutTopDp: () -> Int,
    private val onChapterFits: (chapterId: Long, fits: Boolean) -> Unit,
    private val onChapterEndSeen: (chapterId: Long) -> Unit,
    /** Upstream's "hide chapter title": the document then has no heading of its own. */
    private val hideTitle: () -> Boolean,
    /**
     * WebView's renderer died (a crash, or the system reclaiming it): this page can never draw again, so
     * the reader is rebuilt around its live session, which lands where the reader was.
     */
    private val onPageLost: () -> Unit,
) : ReaderViewport,
    TextViewport,
    ChapterWindow {

    /** Hears the page's documents, for the lookup that 4.3 attaches to them. */
    interface Listener {
        /** A document for [chapterId] is about to load in [webView]. */
        fun onDocumentStart(webView: WebView, chapterId: Long) = Unit

        /** The page laid out and landed on [chapterId]. */
        fun onReady(webView: WebView, chapterId: Long) = Unit

        /** A touch began on the page. */
        fun onTouch() = Unit

        /** The page said where it is (its landing, a page turn, a settled scroll, a re-layout). */
        fun onPosition(report: PageReport) = Unit

        /** The viewport ends: [webView] is destroyed right after. */
        fun onDestroy(webView: WebView) = Unit
    }

    val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * Where the page of [chapterId] is ([pos]). [openedAtEnd]: its document was opened on its last page by
     * a step back from the next chapter. [incognito]: the chapter's source keeps no reading history.
     */
    data class PageReport(
        val chapterId: Long,
        val pos: JpPagePosition,
        val openedAtEnd: Boolean,
        val incognito: Boolean,
        /** The document is the chapter translated (4.5): its characters are not Japanese read. */
        val translated: Boolean = false,
    )

    /**
     * Space at the bottom of the page kept free of text for the reader's status bar (4.5), in dp, which
     * the page's CSS pixels are. Changing it lays the page out again where it is.
     */
    var reservedBottomDp: Int = 0
        set(value) {
            if (field == value) return
            field = value
            documentSettings?.let(::applySettings)
        }

    /**
     * Whether the documents load Yomitan's scanner (4.3: lookup is on and joined to this page). The
     * scanner, and the stand-in it runs on, come only with a new document, so switched on while a chapter
     * is open, that chapter's document is opened again where the reader is; switched off, the scanner in
     * the page is no longer joined to anything.
     */
    var lookup = false
        set(value) {
            if (field == value) return
            field = value
            if (value) lookupJoins++
            if (!ready) return // the document on its way is checked when it is ready
            if (value) {
                if (scannerMismatch()) reopen(lastAnchor)
            } else if (documentJoin != 0) {
                // Its scanner is joined to nothing now: taps on text go back to opening the menu.
                runInPage("if (window.JpReader) { JpReader.onTextTap = null; }")
                documentJoin = 0
            }
        }

    /** Each time lookup is switched on; a document's scanner works only for the one it was built in. */
    private var lookupJoins = 0

    /** The [lookupJoins] the document on screen was built with a scanner for, 0 for none. */
    private var documentJoin = 0

    /**
     * Answers requests of the page before this viewport does (4.3: Yomitan's origin, for its content
     * scripts in this page). Called on a WebView worker thread.
     */
    @Volatile
    var requestInterceptor: ((WebResourceRequest) -> WebResourceResponse?)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val webImages = NovelWebImages()

    private var options: JpPageOptions = readOptions()

    /**
     * The language the document was translated into (4.5), or null for the chapter as its source has it.
     * A translation into anything but Japanese is laid out horizontally, whatever the reader's text
     * direction (Latin text in vertical lines lies on its side); it keeps no place by character and
     * counts no reading, and taps on it open the menu rather than looking words up.
     */
    private var translatedInto: String? = null

    /**
     * A translation opened over its chapter's place: where it first reported, and whether the reader has
     * moved since. "Show original" after a look that did not move lands on the original's own place by
     * character; a percent would land on the page holding it, up to a page earlier.
     */
    private class Peek(val chapterId: Long, var landed: Int? = null, var moved: Boolean = false)

    private var peek: Peek? = null

    /** The reader's options as the document on screen follows them. */
    private fun documentOptions(): JpPageOptions =
        if (translatedInto.let { it != null && !it.startsWith("ja") }) options.copy(vertical = false) else options

    /** The document being shown: its chapter, the settings it was last given, and what it has said. */
    private var chapterId: Long? = null

    /**
     * The document the page's messages must name (`<chapterId>-<load>`, jp-init's `doc`), set as its load
     * begins: a message from the one it replaces, still on its way, names another and is dropped.
     */
    internal var documentId: String? = null
        private set
    private var documentSettings: NovelReaderSettings? = null
    private var documents = 0
    private var ready = false
    private var incognito = false
    private var fitsReported: Boolean? = null
    private var endReported = false

    /** The percent of the place last stored for the document, so a live place is written only when it moves. */
    private var storedPercent: Int? = null

    /** The document on screen was opened on its last page by a step back. */
    private var openedAtEnd = false

    /** The chapter of the document on screen, and the place its page last named, for opening it again. */
    private var shown: NovelReaderViewModel.LoadedChapter? = null
    private var lastAnchor: Int? = null

    /**
     * An edge step asked for and not answered yet, so a second swipe steps no further. Cleared by the
     * next document, by the reader moving within this one, and by the step's chapter failing to load.
     */
    private var stepping = false

    /**
     * The chapter a step back is opening, which lands on its last page: the reader turned back past this
     * one's first. Only that chapter lands there, and only from this step: cleared the same way, and never
     * set when there was no chapter before (so a back swipe on the first chapter sends no later one to its
     * end).
     */
    private var landAtEnd: Long? = null

    @Volatile
    private var served: Pair<String, String>? = null

    @Volatile
    private var servedFonts: Set<String> = emptySet()
    private var installedFonts: List<String>? = null

    /** The cutout inset the page was last given, which is only known once the window has one. */
    private var documentInset = 0

    private var autoScrollRunning = false
    private var autoScrollSpeed = 0f
    private var obscuredTop = 0f
    private var obscuredBottom = 0f

    /** Calls the page was not up to receive yet, run in order once it says it is ready. */
    private val pending = mutableListOf<PendingCall>()

    /** Calls still owed a result: a page replaced or destroyed never answers, so they hear null then. */
    private val awaiting = mutableSetOf<PendingCall>()

    private class PendingCall(val js: String, val onResult: ((String?) -> Unit)?)

    /** The page's renderer is gone: WebView forbids any further use of this view but its destruction. */
    private var gone = false

    private val webView: WebView = WebView(context).apply {
        WebView.setWebContentsDebuggingEnabled(webContentsDebugging(devTools, context.isDebugInspectorBuild()))
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            textZoom = 100
        }
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        webViewClient = JpPageClient(
            context = context,
            upstream = NovelChapterNavigationClient(
                context,
                { served?.first?.let(JpPageDocument::url) },
                webImages,
                scope,
            ) { image ->
                // The text renderer's fetch and cache, so switching readers downloads nothing again.
                fetchNovelImage(
                    image,
                    imageRequests,
                    context.imageLoader.diskCache,
                    readCache = true,
                    writeCache = true,
                )
            },
            document = { id -> served?.takeIf { it.first == id }?.second },
            documentUrl = { served?.first?.let(JpPageDocument::url) },
            fontManager = fontManager,
            fonts = { servedFonts },
            extra = { requestInterceptor },
            onGone = ::onRendererGone,
        )
        // Every layout, because the inset is only known once the window has one and moves with the system
        // bars; comparing first keeps an unchanged one from re-laying the page out.
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val settings = documentSettings
            if (settings != null && cutoutTopDp() != documentInset) applySettings(settings)
        }
        // Only the chapter origin's main frame is heard; what WebView reports decides that, never the page.
        WebViewCompat.addWebMessageListener(this, MESSAGE_NAME, setOf(JpPageDocument.ORIGIN)) {
                _,
                message,
                sourceOrigin,
                isMainFrame,
                _,
            ->
            if (!isMainFrame || !sourceOrigin.isChapterOrigin()) return@addWebMessageListener
            val document = documentId ?: return@addWebMessageListener
            message.data?.let { JpPageMessage.parse(it, document) }?.let(::onMessage)
        }
    }

    init {
        // The Japanese reader's own settings reach an open page at once.
        combine(
            jpPreferences.readerWriting().changes(),
            jpPreferences.readerLayout().changes(),
            jpPreferences.readerFurigana().changes(),
            jpPreferences.readerFont().changes(),
            jpPreferences.readerTap().changes(),
        ) { writing, layout, furigana, font, tap -> JpPageOptions.from(writing, layout, furigana, font, tap) }
            .distinctUntilChanged()
            .onEach { changed ->
                if (changed == options) return@onEach
                val wasScrolling = !options.paged
                options = changed
                if (wasScrolling && changed.paged) runOrQueue("JpReader.autoScroll(0)")
                documentSettings?.let(::applySettings)
                pushAutoScroll()
            }
            .launchIn(scope)
        // The step's chapter did not open: the reader is still here, and a page turn past the edge asks again.
        loadFailures.onEach { endStep() }.launchIn(scope)
    }

    private fun endStep() {
        stepping = false
        landAtEnd = null
    }

    override val view: View get() = webView

    /** Vertical text reads from right to left, so the navigator and its chapter buttons point that way. */
    override val isRtl: Boolean get() = documentOptions().vertical

    override fun seekTo(progress: ChapterProgress) {
        if (progress !is ChapterProgress.Percent) return
        runOrQueue("JpReader.seekFraction(${progress.fraction.toDouble().coerceIn(0.0, 1.0)})")
    }

    // Each chapter is its own document, which starts at that chapter's own place.
    override fun onChapterStepped() = Unit

    override fun destroy() {
        listeners.forEach { it.onDestroy(webView) }
        listeners.clear()
        requestInterceptor = null
        dropPendingCalls()
        scope.cancel()
        chapterId = null
        documentId = null
        shown = null
        served = null
        if (!gone) {
            WebViewCompat.removeWebMessageListener(webView, MESSAGE_NAME)
            webView.stopLoading()
        }
        // destroy() on an attached WebView is undefined and pins the hierarchy, hence the detach.
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    /**
     * WebView's renderer is gone ([JpPageClient] answered that the app handles it, so the app lives on).
     * Nothing more goes to this page; calls still owed an answer hear null, and the host rebuilds the
     * reader with a new page.
     */
    private fun onRendererGone() {
        if (gone) return
        gone = true
        ready = false
        dropPendingCalls()
        (webView.parent as? ViewGroup)?.removeView(webView)
        onPageLost()
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolumeKey || !volumeKeysActive()) return false
        val settings = documentSettings ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val forward = (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) != settings.volumeButtonsInverted
            turn(forward)
        }
        // The key-up too, so the system's volume panel never shows during a press.
        return true
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false

    /**
     * Builds the chapter's document off the main thread (it is proportional to the chapter, and a
     * downloaded one carries its pictures inline) and loads it. It lands at the character stored for
     * the chapter when that place is the one upstream last heard of, else at upstream's percent.
     */
    override suspend fun load(chapter: NovelReaderViewModel.LoadedChapter, settings: NovelReaderSettings) =
        open(chapter, settings, reopenAt = null)

    /** The document on screen has a scanner lookup does not want, or lacks one it wants (or has a dead one). */
    private fun scannerMismatch() =
        translatedInto == null && shown != null && documentJoin != (if (lookup) lookupJoins else 0)

    /** Opens the document on screen again at [anchor], the same chapter as it was reported so far. */
    private fun reopen(anchor: Int?) {
        val chapter = shown ?: return
        val settings = documentSettings ?: return
        scope.launch { open(chapter, settings, reopenAt = anchor) }
    }

    /**
     * [reopenAt]: the chapter on screen opened again at that character (lookup switched on), which keeps
     * what its document has told upstream so far rather than telling it again.
     */
    private suspend fun open(
        chapter: NovelReaderViewModel.LoadedChapter,
        settings: NovelReaderSettings,
        reopenAt: Int?,
    ) {
        val again = reopenAt != null && chapter.chapterId == chapterId
        val atEnd = !again && landAtEnd == chapter.chapterId
        endStep()
        dropPendingCalls()
        ready = false
        if (!again) {
            fitsReported = null
            endReported = false
            storedPercent = null
        }
        shown = chapter
        lastAnchor = null
        chapterId = chapter.chapterId
        documentSettings = settings
        val number = ++documents
        val documentId = "${chapter.chapterId}-$number"
        this.documentId = documentId
        val translation = ChapterTranslator.languageOf(chapter.html)
        translatedInto = translation
        val backFromPeek =
            peek?.takeIf { translation == null && it.chapterId == chapter.chapterId && !it.moved } != null
        peek = if (translation != null) Peek(chapter.chapterId) else null
        val current = documentOptions()
        val withLookup = lookup && translation == null
        documentJoin = if (withLookup) lookupJoins else 0
        val insetTop = cutoutTopDp()
        documentInset = insetTop
        val baseUrl = chapter.baseUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val hidesTitle = hideTitle()
        val built = withContext(Dispatchers.IO) {
            // A place by character is the original's: a translation lands by percent.
            val stored = if (atEnd || again || translation != null) null else positions.get(chapter.chapterId)
            val hidden = isIncognito(chapter.sourceId)
            val fonts = fontFiles(current.font)
            withContext(Dispatchers.Default) {
                val html = webImages.rewrite(JpPageDocument.cleanChapter(chapter.html, baseUrl), baseUrl, chapter.sourceId)
                val init = JpPageDocument.init(
                    chapterId = chapter.chapterId,
                    documentId = documentId,
                    charOffset = if (again) {
                        reopenAt
                    } else {
                        stored?.takeIf { backFromPeek || it.percent == chapter.progressPercent }?.charOffset
                    },
                    fraction = if (atEnd) 1.0 else chapter.progressPercent / 100.0,
                    settings = settingsJson(settings, current, insetTop),
                )
                val document = JpPageDocument.build(
                    init,
                    current,
                    look(settings),
                    chapter.title.takeUnless { hidesTitle },
                    html,
                    fonts,
                    withLookup,
                    language = translation ?: "ja",
                )
                Built(document, fonts, hidden)
            }
        }
        // A later load or the viewport's end overtook this one while it was built, or the page is gone.
        if (number != documents || chapterId != chapter.chapterId || gone) return
        incognito = built.incognito
        if (!again) openedAtEnd = atEnd
        servedFonts = built.fonts.toSet()
        served = documentId to built.html
        webView.setBackgroundColor(readerBackgroundColorInt(settings.backgroundColor))
        listeners.forEach { it.onDocumentStart(webView, chapter.chapterId) }
        webView.loadUrl(JpPageDocument.url(documentId))
    }

    private class Built(val html: String, val fonts: List<String>, val incognito: Boolean)

    /** The reader's added fonts, listed once per viewport, and again when the chosen one is new. */
    private suspend fun fontFiles(chosen: String): List<String> {
        val known = installedFonts
        if (known != null && (chosen in known || !isSupportedFontFile(chosen))) return known
        return runCatching { fontManager.installed().map { it.fileName } }
            .onFailure { logcat(LogPriority.WARN, it) { "Could not list the reader's fonts" } }
            .getOrDefault(emptyList())
            .also { installedFonts = it }
    }

    override fun applySettings(settings: NovelReaderSettings) {
        documentSettings = settings
        documentInset = cutoutTopDp()
        webView.setBackgroundColor(readerBackgroundColorInt(settings.backgroundColor))
        runOrQueue("JpReader.applySettings(${settingsJson(settings, documentOptions(), documentInset)})")
    }

    private fun settingsJson(settings: NovelReaderSettings, options: JpPageOptions, insetTop: Int) =
        JpPageSettings.json(options, look(settings), settings.tapZones.toJpTapLayout(), insetTop, insetBottom(settings))

    /**
     * What covers the bottom of the page: the status bar, or else upstream's progress readout, which sits
     * over the page's last line while the menu is closed (seen on the tablet in scroll mode).
     */
    private fun insetBottom(settings: NovelReaderSettings): Int {
        val readout = if (settings.showProgressPercentage) {
            // Its text is bodySmall, a 16 sp line, with an outline around it.
            ceil(READOUT_LINE_SP * context.resources.configuration.fontScale).toInt() + READOUT_OUTLINE_DP
        } else {
            0
        }
        return max(reservedBottomDp, readout)
    }

    private fun look(settings: NovelReaderSettings) = JpPageLook(
        fontSize = settings.fontSize,
        lineHeight = settings.lineHeight,
        marginTop = settings.margins.top,
        marginRight = settings.margins.right,
        marginBottom = settings.margins.bottom,
        marginLeft = settings.margins.left,
        background = settings.backgroundColor,
        text = settings.textColor,
        textIndent = settings.paragraphIndent,
        justify = settings.textAlign == "justify",
    )

    /** Auto-scroll runs in scroll mode only, right to left in vertical text: the page moves itself. */
    override fun setAutoScroll(running: Boolean, pixelsPerFrame: Float) {
        autoScrollRunning = running
        autoScrollSpeed = pixelsPerFrame
        pushAutoScroll()
    }

    private fun pushAutoScroll() {
        if (!ready || options.paged) return
        val speed = if (autoScrollRunning) autoScrollSpeed.coerceAtLeast(0f) else 0f
        evaluate(PendingCall("JpReader.autoScroll($speed)", null))
    }

    override fun setObscured(top: Int, bottom: Int) {
        val density = context.resources.displayMetrics.density
        obscuredTop = top / density
        obscuredBottom = bottom / density
    }

    private fun turn(forward: Boolean) = runOrQueue("JpReader.turn(${if (forward) 1 else -1})")

    // --- Page messages, on the main thread --------------------------------------------------------

    private fun onMessage(message: JpPageMessage) {
        val id = chapterId ?: return
        when (message) {
            is JpPageMessage.Ready -> {
                ready = true
                pending.forEach(::evaluate)
                pending.clear()
                pushAutoScroll()
                onPosition(id, message.pos)
                // Lookup came on or went off while this document was on its way.
                if (scannerMismatch()) reopen(message.pos.anchor)
                listeners.forEach { it.onReady(webView, id) }
                JpReaderIntro.showOnce(context, jpPreferences, vertical = documentOptions().vertical)
            }
            is JpPageMessage.Position -> {
                if (!ready) return
                endStep()
                onPosition(id, message.pos, live = message.live)
            }
            is JpPageMessage.Tap -> if (ready) onTap(message)
            is JpPageMessage.Edge -> {
                if (!ready || stepping) return
                // No chapter that way (the first one's start, the last one's end): nothing asked, nothing kept.
                val target = onStepChapter(message.forward) ?: return
                stepping = true
                landAtEnd = if (message.forward) null else target
            }
            JpPageMessage.Touch -> listeners.forEach { it.onTouch() }
        }
    }

    private fun onPosition(id: Long, pos: JpPagePosition, live: Boolean = false) {
        val percent = pos.percent
        lastAnchor = pos.anchor
        onTopLine(id, null)
        // Auto-scroll reports once a second: upstream's debounced save, as its own scroll reader's is, rather
        // than a database write each time.
        if (live) onProgressChanged(id, percent) else onProgressSettled(id, percent)
        if (fitsReported != pos.fits) {
            fitsReported = pos.fits
            onChapterFits(id, pos.fits)
        }
        if (pos.endSeen && !endReported) {
            endReported = true
            onChapterEndSeen(id)
        }
        // Incognito keeps no reading place, upstream's or the fork's; a translation's characters are not
        // the chapter's. The anchor, not the page's first character: a document laid out another way lands
        // on the page holding it without slipping back.
        val translated = translatedInto != null
        peek?.takeIf { it.chapterId == id }?.let { look ->
            if (look.landed == null) {
                look.landed = percent
            } else if (look.landed != percent) {
                look.moved = true
            }
        }
        if (!incognito && !translated && pos.chars > 0) {
            // A live place is kept, and written only when its percent moves (upstream's rule), or with the
            // next settled one.
            positions.put(
                JpReaderDatabase.ChapterPosition(id, pos.anchor, pos.chars, percent, System.currentTimeMillis()),
                write = !live || percent != storedPercent,
            )
            storedPercent = percent
        }
        if (listeners.isNotEmpty()) {
            val report = PageReport(id, pos, openedAtEnd, incognito, translated)
            listeners.forEach { it.onPosition(report) }
        }
    }

    /**
     * A tap the page passed up (not a lookup, not a furigana reveal). With "Tap on text: Look up" (D-029)
     * it opens the menu, as a tap off the text does (ruling 9); with "Turn pages" the reader's tap zones
     * decide, mirrored for vertical text, by upstream's rule. The page's own reading of the zones is not
     * needed for that.
     */
    private fun onTap(tap: JpPageMessage.Tap) {
        val action = if (options.tapLooksUp) {
            NovelTapAction.MENU
        } else {
            val zones = documentSettings?.tapZones?.toJpTapLayout() ?: return onToggleMenu()
            zones.forWriting(documentOptions().vertical).actionAt(tap.x, tap.y)
        }
        when (action) {
            NovelTapAction.MENU -> onToggleMenu()
            NovelTapAction.BACK -> turn(forward = false)
            NovelTapAction.FORWARD -> turn(forward = true)
            NovelTapAction.NONE -> Unit
        }
    }

    // --- Calls into the page ----------------------------------------------------------------------

    private fun runOrQueue(js: String, onResult: ((String?) -> Unit)? = null) {
        val call = PendingCall(js, onResult)
        if (onResult != null) awaiting += call
        if (ready) evaluate(call) else pending += call
    }

    private fun evaluate(call: PendingCall) {
        if (gone) {
            if (awaiting.remove(call)) call.onResult?.invoke(null)
            return
        }
        val answer = call.onResult?.let { onResult ->
            ValueCallback<String> { if (awaiting.remove(call)) onResult(it) }
        }
        val script = if (call.onResult != null) {
            "(window.JpReader ? ${call.js} : null)"
        } else {
            "if (window.JpReader) { ${call.js}; }"
        }
        webView.evaluateJavascript(script, answer)
    }

    /** Runs [js] in the document on screen now, if there is a page to run it in; nothing is queued. */
    internal fun runInPage(js: String) {
        if (chapterId == null || gone) return
        webView.evaluateJavascript(js, null)
    }

    private fun dropPendingCalls() {
        pending.clear()
        val unanswered = awaiting.toList()
        awaiting.clear()
        unanswered.forEach { it.onResult?.invoke(null) }
    }

    /** What [js] evaluates to in the page as JSON, or null for a null, or a page replaced before it answered. */
    private suspend fun query(js: String): String? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            runOrQueue(js) { result -> if (continuation.isActive) continuation.resume(result?.takeIf { it != "null" }) }
        }
    }

    // --- The window: one chapter per document -----------------------------------------------------

    override val window: ChapterWindow get() = this

    // The model publishes one chapter at a time for this reader; a window it built for another reader,
    // still arriving after a switch, is let go of here rather than half rendered.
    override suspend fun append(chapter: NovelReaderViewModel.LoadedChapter) = Unit

    override suspend fun prepend(chapter: NovelReaderViewModel.LoadedChapter) = Unit

    override fun evict(chapterId: Long) = Unit

    // A chapter at an edge that failed to load ends a step toward it, so the next page turn asks again.
    override fun setBoundaryFailures(
        previous: NovelReaderViewModel.BoundaryFailure?,
        next: NovelReaderViewModel.BoundaryFailure?,
    ) {
        if (stepping && (previous != null || next != null)) endStep()
    }

    // --- Read aloud -------------------------------------------------------------------------------

    override val readAloud: ReadAloudSurface = object : ReadAloudSurface {
        override suspend fun paragraphs(chapterId: Long): List<String>? {
            if (chapterId != this@JpPageViewport.chapterId) return null
            val json = query("JpReader.paragraphs()") ?: return null
            val array = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return null
            return array.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return null }
        }

        override suspend fun firstVisibleParagraph(): ReadAloudPosition? {
            val id = chapterId ?: return null
            val json = query("JpReader.firstVisibleParagraph($obscuredTop, $obscuredBottom)") ?: return null
            val index = runCatching { (Json.parseToJsonElement(json) as? JsonPrimitive)?.intOrNull }.getOrNull()
                ?: return null
            // Asked of a document that was replaced while it answered.
            if (id != chapterId || index < 0) return null
            return ReadAloudPosition(id, index)
        }

        override fun highlight(position: ReadAloudPosition?, range: IntRange?) {
            if (position == null) {
                runOrQueue("JpReader.highlight(-1)")
                return
            }
            if (position.chapterId != chapterId) return
            val from = range?.first ?: -1
            val to = range?.let { it.last + 1 } ?: -1
            runOrQueue("JpReader.highlight(${position.paragraph}, $from, $to)")
        }
    }

    private fun readOptions() = JpPageOptions.from(
        writing = jpPreferences.readerWriting().get(),
        layout = jpPreferences.readerLayout().get(),
        furigana = jpPreferences.readerFurigana().get(),
        font = jpPreferences.readerFont().get(),
        tap = jpPreferences.readerTap().get(),
    )

    private fun Uri.isChapterOrigin(): Boolean = scheme == "https" && host == JpPageDocument.HOST && port == -1

    private companion object {
        /** The page's `window.jpReader`. */
        const val MESSAGE_NAME = "jpReader"

        /** Upstream's progress readout (`ReaderPageIndicator`): a bodySmall line and its outline. */
        const val READOUT_LINE_SP = 16f
        const val READOUT_OUTLINE_DP = 2
    }
}

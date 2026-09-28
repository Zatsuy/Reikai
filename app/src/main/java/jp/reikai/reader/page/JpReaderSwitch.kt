package jp.reikai.reader.page

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import reikai.domain.reader.ChapterProgress
import reikai.presentation.reader.ChapterWindow
import reikai.presentation.reader.NovelReaderSettings
import reikai.presentation.reader.NovelReaderViewModel
import reikai.presentation.reader.ReadAloudPosition
import reikai.presentation.reader.ReadAloudSurface
import reikai.presentation.reader.ReaderViewport
import reikai.presentation.reader.TextViewport
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat

/**
 * The novel reader's viewport, holding either the Japanese reader ([JpPageViewport]) or upstream's own
 * viewport, whichever this novel reads in ([JpReaderModes]).
 *
 * Why a holder rather than a choice in `NovelReaderProvider.createViewport`: the provider builds the
 * viewport synchronously in `onCreate`, before the novel's row or its source has been read, and reading
 * them there would block the main thread on the database. The holder goes on screen at once and builds
 * its viewport as soon as the reader is known: from the novel's row and its source's language a few
 * milliseconds later (off the main thread), or, for a source that is not in one language, from the
 * first chapter's text once the model has it. A reader rebuilt around a live session (rotation, a switch
 * of reader) knows at once and builds straight away. Until then nothing is rendered, which is what the
 * reader's loading spinner already covers.
 */
class JpReaderSwitch internal constructor(
    private val host: ReaderActivity,
    viewModel: NovelReaderViewModel,
    private val modes: JpReaderModes,
    private val standard: () -> ReaderViewport,
    private val japaneseViewport: () -> JpPageViewport,
) : ReaderViewport,
    TextViewport {

    private val novelId = viewModel.novelId

    /** Whether this novel reads in the Japanese reader; null until that is known. */
    val isJapanese: StateFlow<Boolean?> =
        modes.decisions(novelId).stateIn(host.lifecycleScope, SharingStarted.Eagerly, modes.known(novelId))

    /**
     * What the host puts in its viewer container before the viewport is known. The viewport's own view
     * goes into that container beside it rather than inside it: upstream's viewports lift the container's
     * focus block from their direct parent while attached (`SelectableWhileAttached`), which text
     * selection needs, so their parent has to stay the container.
     */
    private val frame = FrameLayout(host)
    private var inner: ReaderViewport? = null
    private var unplaced: View? = null
    private var destroyed = false

    // What the host asked before the viewport existed, for the one built after.
    private var autoScroll: Pair<Boolean, Float>? = null
    private var obscured: Pair<Int, Int>? = null

    init {
        frame.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                // Posted: a view added while the container is still telling its children they are
                // attached would be attached twice.
                override fun onViewAttachedToWindow(v: View) {
                    if (unplaced != null) v.post { unplaced?.let(::place) }
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            },
        )
        val known = modes.known(novelId)
        if (known != null) {
            build(known)
        } else {
            host.lifecycleScope.launch {
                val early = withContext(Dispatchers.IO) {
                    runCatching { modes.decideEarly(novelId) }
                        .onFailure { logcat(LogPriority.WARN, it) { "Could not decide the novel's reader early" } }
                        .getOrNull()
                }
                val decided = early ?: modes.await(novelId)
                build(decided)
            }
        }
    }

    /**
     * Switches this novel's reader (the reader menu, the settings sheet): the model prepares its chapter
     * again for the other reader and the Activity is rebuilt around the live session, as upstream does
     * when its rendering mode changes.
     */
    fun choose(japanese: Boolean) {
        if (destroyed || isJapanese.value == japanese) return
        modes.choose(novelId, japanese) { write -> host.lifecycleScope.launchNonCancellable { write() } }
        host.recreate()
    }

    private fun build(japanese: Boolean): ReaderViewport? {
        inner?.let { return it }
        if (destroyed) return null
        val viewport = if (japanese) japaneseViewport() else standard()
        inner = viewport
        place(viewport.view)
        val text = viewport as? TextViewport
        if (text == null) {
            logcat(LogPriority.ERROR) { "Novel viewport renders no text: ${viewport::class}" }
        } else {
            obscured?.let { (top, bottom) -> text.setObscured(top, bottom) }
            autoScroll?.let { (running, speed) -> text.setAutoScroll(running, speed) }
        }
        return viewport
    }

    /** Puts [view] in the host's container above the placeholder, or waits for the host to add it. */
    private fun place(view: View) {
        val container = frame.parent as? ViewGroup
        if (container == null) {
            unplaced = view
            return
        }
        unplaced = null
        if (view.parent != null) return
        container.addView(
            view,
            container.indexOfChild(frame) + 1,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private val text: TextViewport? get() = inner as? TextViewport

    override val view: View get() = frame

    override val isRtl: Boolean get() = inner?.isRtl ?: false

    override fun seekTo(progress: ChapterProgress) {
        inner?.seekTo(progress)
    }

    override fun onChapterStepped() {
        inner?.onChapterStepped()
    }

    override fun onChapterOpened() {
        inner?.onChapterOpened()
    }

    override fun destroy() {
        destroyed = true
        unplaced = null
        inner?.let { viewport ->
            viewport.destroy()
            (viewport.view.parent as? ViewGroup)?.removeView(viewport.view)
        }
        inner = null
        (frame.parent as? ViewGroup)?.removeView(frame)
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean = inner?.handleKeyEvent(event) ?: false

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = inner?.handleGenericMotionEvent(event) ?: false

    /** By the time the model hands over a chapter it has decided the reader, so this waits for nothing
     *  but the build itself. */
    override suspend fun load(chapter: NovelReaderViewModel.LoadedChapter, settings: NovelReaderSettings) {
        val viewport = inner ?: build(modes.known(novelId) ?: modes.await(novelId)) ?: return
        (viewport as? TextViewport)?.load(chapter, settings)
    }

    override fun applySettings(settings: NovelReaderSettings) {
        text?.applySettings(settings)
    }

    override fun setAutoScroll(running: Boolean, pixelsPerFrame: Float) {
        autoScroll = running to pixelsPerFrame
        text?.setAutoScroll(running, pixelsPerFrame)
    }

    override fun setObscured(top: Int, bottom: Int) {
        obscured = top to bottom
        text?.setObscured(top, bottom)
    }

    override val window: ChapterWindow = object : ChapterWindow {
        override suspend fun append(chapter: NovelReaderViewModel.LoadedChapter) {
            text?.window?.append(chapter)
        }

        override suspend fun prepend(chapter: NovelReaderViewModel.LoadedChapter) {
            text?.window?.prepend(chapter)
        }

        override fun evict(chapterId: Long) {
            text?.window?.evict(chapterId)
        }

        override fun setBoundaryFailures(
            previous: NovelReaderViewModel.BoundaryFailure?,
            next: NovelReaderViewModel.BoundaryFailure?,
        ) {
            text?.window?.setBoundaryFailures(previous, next)
        }
    }

    /** Answers every question, with null while no viewport is built, as the contract asks. */
    override val readAloud: ReadAloudSurface = object : ReadAloudSurface {
        override suspend fun paragraphs(chapterId: Long): List<String>? = text?.readAloud?.paragraphs(chapterId)

        override suspend fun firstVisibleParagraph(): ReadAloudPosition? = text?.readAloud?.firstVisibleParagraph()

        override fun highlight(position: ReadAloudPosition?, range: IntRange?) {
            text?.readAloud?.highlight(position, range)
        }
    }
}

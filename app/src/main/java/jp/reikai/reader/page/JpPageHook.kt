package jp.reikai.reader.page

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.toast
import jp.reikai.di.JpGraph
import jp.reikai.reader.JpReaderHook
import jp.reikai.yomitan.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import mihon.core.metro.GraphProvider
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.SourceKey
import reikai.novel.content.RenderTarget
import reikai.novel.font.NovelFontManager
import reikai.novel.network.NovelImageRequests
import reikai.presentation.reader.NovelReaderViewModel
import reikai.presentation.reader.ReaderLoadState
import reikai.presentation.reader.ReaderViewport
import java.util.WeakHashMap

/**
 * Reikai JP's hook into upstream's novel reader for the Japanese reader (phase 4, items 4.1 and 4.2):
 * each seam is one call here. Without the app's graph (upstream's JVM tests build the reader model
 * with a stand-in context) every call answers as if the fork were not there.
 */
object JpPageHook {

    /**
     * Each live reader's switch. A switch holds its Activity, so a weak key alone would never let go:
     * the entry goes when the Activity is destroyed.
     */
    private val switches = WeakHashMap<Activity, JpReaderSwitch>()

    private fun graph(context: Context): JpGraph? = (context.applicationContext as? GraphProvider<*>)?.graph as? JpGraph

    /** `NovelReaderViewModel`: [loader] prepares the chapters of a reading session of [novelId]. */
    @JvmStatic
    fun bindSession(context: Context, loader: Any, novelId: Long) {
        graph(context)?.jpReaderModes?.bind(loader, novelId)
    }

    /**
     * `NovelChapterTextLoader.load`: the WebView form of the chapter when its session reads in the
     * Japanese reader, whatever upstream's global rendering mode is; null leaves upstream's rule.
     */
    @JvmStatic
    suspend fun pageTarget(context: Context, loader: Any, chapter: NovelChapter, raw: String): RenderTarget? {
        val modes = graph(context)?.jpReaderModes ?: return null
        return if (modes.decideForLoad(loader, chapter.novelId, raw)) RenderTarget.WEB_VIEW else null
    }

    /** `NovelChapterTextLoader.settingsChanged`: a switch of reader prepares the open chapters again. */
    @JvmStatic
    fun readerSwitches(context: Context): Flow<Int> = graph(context)?.jpReaderModes?.switches ?: flowOf(0)

    @JvmStatic
    fun readerSwitchCount(context: Context): Int = graph(context)?.jpReaderModes?.switches?.value ?: 0

    /** `NovelReaderViewModel.windowedReading`: the Japanese reader holds one chapter per page (ruling 1). */
    @JvmStatic
    fun holdsOneChapter(context: Context, novelId: Long): Boolean =
        graph(context)?.jpReaderModes?.holdsOneChapter(novelId) == true

    /**
     * `NovelReaderProvider.attach`: the viewport for this novel, the Japanese reader or [standard]
     * (upstream's own choice), picked as soon as the novel's reader is known ([JpReaderSwitch]).
     */
    @JvmStatic
    fun viewport(
        host: ReaderActivity,
        viewModel: NovelReaderViewModel,
        novelPreferences: NovelPreferences,
        fontManager: NovelFontManager,
        imageRequests: NovelImageRequests,
        standard: () -> ReaderViewport,
    ): ReaderViewport {
        val graph = graph(host) ?: return standard()
        // The page talks to the app through a web message listener; a WebView too old for one keeps
        // upstream's reader, which is what the novel's reader is decided as then too.
        if (!graph.jpReaderModes.pageSupported()) {
            JpReaderHook.readerSwitch(host, MutableStateFlow(false))
            return standard()
        }
        val switch = JpReaderSwitch(
            host = host,
            viewModel = viewModel,
            modes = graph.jpReaderModes,
            standard = standard,
            japaneseViewport = {
                JpPageViewport(
                    context = host,
                    jpPreferences = graph.jpPreferences,
                    positions = graph.jpChapterPositions,
                    fontManager = fontManager,
                    imageRequests = imageRequests,
                    isIncognito = { sourceId -> graph.getIncognitoState.await(sourceId?.let(SourceKey::Novel)) },
                    devTools = novelPreferences.readerWebViewDevTools().get(),
                    volumeKeysActive = { viewModel.settings.value.useVolumeButtons && !host.isMenuVisible },
                    onProgressChanged = viewModel::reportProgress,
                    onProgressSettled = viewModel::saveProgress,
                    onTopLine = viewModel::reportTopLine,
                    onToggleMenu = host::toggleMenu,
                    onStepChapter = { forward ->
                        val neighbours = viewModel.chapterNeighbours.value
                        (if (forward) neighbours.next else neighbours.previous)?.also {
                            if (forward) host.engine.nextChapter() else host.engine.previousChapter()
                        }
                    },
                    loadFailures = viewModel.loadState.filterIsInstance<ReaderLoadState.Failed>().map { },
                    cutoutTopDp = host::displayCutoutTopDp,
                    onChapterFits = viewModel::reportFitsOnScreen,
                    onChapterEndSeen = viewModel::reportChapterEndSeen,
                    hideTitle = novelPreferences.readerHideChapterTitle()::get,
                    onPageLost = { pageLost(host) },
                ).also { JpReaderHook.pageViewport(host, it) }
            },
        )
        synchronized(switches) { switches[host] = switch }
        host.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    synchronized(switches) { if (switches[host] === switch) switches.remove(host) }
                }
            },
        )
        JpReaderHook.readerSwitch(host, switch.isJapanese)
        return switch
    }

    /** When a reader's page last lost its renderer (elapsed realtime), 0 for never. */
    private var lastPageLoss = 0L

    /**
     * [host]'s page lost WebView's renderer: the reader is rebuilt around its live session, which lands
     * where the reader was. A second loss soon after (a chapter that kills the renderer every time) closes
     * the reader instead of rebuilding it in a loop. Posted, out of WebView's own callback.
     */
    private fun pageLost(host: ReaderActivity) {
        val now = SystemClock.elapsedRealtime()
        val again = lastPageLoss != 0L && now - lastPageLoss < PAGE_LOSS_WINDOW_MS
        lastPageLoss = now
        Handler(Looper.getMainLooper()).post {
            if (host.isFinishing || host.isDestroyed) return@post
            if (again) {
                host.toast(host.getString(R.string.jp_reader_page_lost), Toast.LENGTH_LONG)
                host.finish()
            } else {
                host.recreate()
            }
        }
    }

    private const val PAGE_LOSS_WINDOW_MS = 60_000L

    /** The reader switch of [activity]'s novel, or null for a manga reader or another screen. */
    fun switchOf(activity: Activity): JpReaderSwitch? = synchronized(switches) { switches[activity] }
}

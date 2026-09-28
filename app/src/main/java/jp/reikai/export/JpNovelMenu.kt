package jp.reikai.export

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.util.system.toast
import jp.reikai.di.jpGraph
import jp.reikai.yomitan.R
import kotlinx.coroutines.launch
import reikai.presentation.novel.details.NovelScreen

/** Reikai JP's items in the details screen's overflow menu (4.5), added by one line in `EntryToolbar`. */
object JpNovelMenu {

    /**
     * "Export as EPUB" on a novel's screen; nothing on a manga's. A novel with nothing downloaded says
     * so instead of asking for a file; otherwise the owner picks where the book goes and it is written
     * in the background ([NovelEpubJob]).
     */
    @Composable
    fun overflowActions(): List<AppBar.OverflowAction> {
        val screen = LocalNavigator.current?.lastItem as? NovelScreen ?: return emptyList()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val export = remember { context.jpGraph.novelEpubExport }
        // Saved, so the picker's answer still finds its novel after the activity was recreated.
        var pendingId by rememberSaveable { mutableStateOf<Long?>(null) }
        var pendingTitle by rememberSaveable { mutableStateOf("") }
        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(EpubWriter.MEDIA_TYPE),
        ) { uri ->
            val novelId = pendingId
            pendingId = null
            if (uri == null || novelId == null) return@rememberLauncherForActivityResult
            // Kept across a restart of the app, as a backup's location is, so the job can still write; the
            // job lets it go again, except for the last book exported (NovelEpubJob.keep).
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            NovelEpubJob.start(context, novelId, pendingTitle, uri)
            context.toast(context.getString(R.string.jp_export_started))
        }
        val title = stringResource(R.string.jp_export_epub)
        return listOf(
            AppBar.OverflowAction(title = title) {
                scope.launch {
                    when (val readiness = export.prepare(screen.sourceId, screen.novelUrl)) {
                        NovelEpubExport.Readiness.Unknown ->
                            context.toast(context.getString(R.string.jp_export_not_found), Toast.LENGTH_LONG)
                        NovelEpubExport.Readiness.NothingDownloaded ->
                            context.toast(context.getString(R.string.jp_export_nothing_downloaded), Toast.LENGTH_LONG)
                        is NovelEpubExport.Readiness.Ready -> {
                            pendingId = readiness.novelId
                            pendingTitle = readiness.title
                            picker.launch(readiness.fileName)
                        }
                    }
                }
            },
        )
    }
}

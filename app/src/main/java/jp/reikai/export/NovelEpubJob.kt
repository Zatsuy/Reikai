package jp.reikai.export

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.PermissionChecker
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import jp.reikai.di.jpGraph
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.cancellation.CancellationException
import jp.reikai.yomitan.R as JpR

/**
 * Writes a novel's EPUB in the background (4.5, phase 4 ruling 16), so leaving the screen does not stop
 * it: a progress notification with Cancel on upstream's common channel, then one that says how many
 * chapters went in and how many were skipped as not downloaded, and opens the book when tapped (a toast
 * saying the same when notifications are off). A failed or empty export deletes the file it was given.
 */
class NovelEpubJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val title = inputData.getString(KEY_TITLE).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = progress(done = 0, total = 0).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ID_PROGRESS, notification)
        }
    }

    override suspend fun doWork(): Result {
        val novelId = inputData.getLong(KEY_NOVEL_ID, -1L)
        val uri = inputData.getString(KEY_URI)?.toUri() ?: return Result.failure()
        setForegroundSafely()
        var shownAt = 0L
        var kept = false
        return try {
            val counts = applicationContext.jpGraph.novelEpubExport.write(novelId, uri) { done, total ->
                val now = SystemClock.elapsedRealtime()
                if (done == total || now - shownAt >= PROGRESS_INTERVAL_MS) {
                    shownAt = now
                    applicationContext.notify(ID_PROGRESS, progress(done, total).build())
                }
            }
            if (counts.exported == 0) {
                delete(uri)
                result(
                    applicationContext.getString(JpR.string.jp_export_nothing_title, title),
                    applicationContext.getString(JpR.string.jp_export_nothing_downloaded),
                )
            } else {
                keep(uri)
                kept = true
                result(applicationContext.getString(JpR.string.jp_export_done_title, title), summary(counts), uri)
            }
            Result.success()
        } catch (e: CancellationException) {
            delete(uri)
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "EPUB export failed" }
            delete(uri)
            result(
                applicationContext.getString(JpR.string.jp_export_failed_title, title),
                e.message ?: e.javaClass.simpleName,
            )
            Result.failure()
        } finally {
            applicationContext.cancelNotification(ID_PROGRESS)
            if (!kept) release(uri)
        }
    }

    /**
     * The finished book keeps the right to open it that the menu took for this job (so its notification
     * opens it, even after the app was closed); the book exported before it lets go of its own, so the
     * app holds one such right at a time rather than one per export.
     */
    private fun keep(book: Uri) {
        val last = applicationContext.jpGraph.jpPreferences.exportedBook()
        val previous = last.get().takeIf { it.isNotEmpty() && it != book.toString() }
        last.set(book.toString())
        previous?.toUri()?.let(::release)
    }

    /** Lets go of the right to [book] kept across restarts; nothing when it was never taken. */
    private fun release(book: Uri) {
        runCatching {
            applicationContext.contentResolver.releasePersistableUriPermission(
                book,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun progress(done: Int, total: Int): NotificationCompat.Builder =
        applicationContext.notificationBuilder(Notifications.CHANNEL_COMMON) {
            setSmallIcon(R.drawable.ic_reikai)
            setContentTitle(applicationContext.getString(JpR.string.jp_export_progress_title, title))
            if (total > 0) {
                setContentText(applicationContext.getString(JpR.string.jp_export_progress, done, total))
                setProgress(total, done, false)
            } else {
                setProgress(0, 0, true)
            }
            setOngoing(true)
            setOnlyAlertOnce(true)
            addAction(
                R.drawable.ic_close_24dp,
                applicationContext.getString(JpR.string.jp_export_cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
        }

    /** "12 chapters exported, 3 not downloaded were skipped", the second half only when something was. */
    private fun summary(counts: ExportCounts): String {
        val resources = applicationContext.resources
        val exported = resources.getQuantityString(JpR.plurals.jp_export_exported, counts.exported, counts.exported)
        if (counts.skipped == 0) return exported
        val skipped = resources.getQuantityString(JpR.plurals.jp_export_skipped, counts.skipped, counts.skipped)
        return applicationContext.getString(JpR.string.jp_export_counts, exported, skipped)
    }

    /**
     * Says how the export ended: a notification, or, when this app may not post one (notifications off,
     * the permission refused, the channel blocked), a long toast, the only way the owner would learn it.
     */
    private suspend fun result(heading: String, text: String, book: Uri? = null) {
        if (!canNotify()) {
            withUIContext { applicationContext.toast(heading + "\n" + text, Toast.LENGTH_LONG) }
            return
        }
        applicationContext.notify(ID_RESULT, Notifications.CHANNEL_COMMON) {
            setSmallIcon(R.drawable.ic_reikai)
            setContentTitle(heading)
            val body = if (book == null) text else text + "\n" + applicationContext.getString(JpR.string.jp_export_open)
            setContentText(body)
            setStyle(NotificationCompat.BigTextStyle().bigText(body))
            setAutoCancel(true)
            if (book != null) {
                val open = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(book, EpubWriter.MEDIA_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                setContentIntent(
                    PendingIntent.getActivity(
                        applicationContext,
                        0,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }
    }

    private fun canNotify(): Boolean {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            PermissionChecker.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PermissionChecker.PERMISSION_GRANTED
        ) {
            return false
        }
        val channel = manager.getNotificationChannelCompat(Notifications.CHANNEL_COMMON) ?: return true
        return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /** An unfinished book is no book: the file picked for it goes. */
    private fun delete(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, uri) }
            .onFailure { logcat(LogPriority.WARN, it) { "Could not delete the unfinished EPUB" } }
    }

    companion object {
        private const val TAG = "jp_epub_export"
        private const val KEY_NOVEL_ID = "novel_id"
        private const val KEY_TITLE = "title"
        private const val KEY_URI = "uri"
        private const val PROGRESS_INTERVAL_MS = 300L

        // Reikai JP's own numbers on the common channel, far from upstream's.
        private const val ID_PROGRESS = -9_401
        private const val ID_RESULT = -9_402

        /** Queued behind any export still running, so two books are never written at once. */
        fun start(context: Context, novelId: Long, title: String, uri: Uri) {
            val request = OneTimeWorkRequestBuilder<NovelEpubJob>()
                .addTag(TAG)
                .setInputData(workDataOf(KEY_NOVEL_ID to novelId, KEY_TITLE to title, KEY_URI to uri.toString()))
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

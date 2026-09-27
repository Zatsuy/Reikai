package jp.reikai.perf

import android.app.Activity
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.view.doOnPreDraw

/**
 * Timing marks that scripts/fork/perf.py reads from logcat, so the performance baseline measures
 * when the app has something to show rather than when its first frame (a splash) appears. Each
 * costs one log line or one system call, so they stay on in every build type.
 */
object PerfMarks {
    const val TAG = "ReikaiPerf"

    @Volatile private var libraryReadyLogged = false

    /** The library has its data, in milliseconds since the process started. Once per process. */
    fun libraryReady() {
        if (libraryReadyLogged) return
        libraryReadyLogged = true
        val sinceStart = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        Log.i(TAG, "library_ready $sinceStart")
    }

    /**
     * A chapter was handed to the reader: once the frame showing it is drawn, the reader counts as fully
     * drawn, which the system logs as "Fully drawn ... +<ms>" from the tap that opened it. Later
     * calls in the same launch are ignored by the system.
     */
    fun chapterShown(activity: Activity) {
        val view = activity.window.decorView
        // Posted from the pre-draw so it runs after the frame that pre-draw belongs to.
        view.doOnPreDraw { view.post { if (!activity.isFinishing) activity.reportFullyDrawn() } }
    }
}

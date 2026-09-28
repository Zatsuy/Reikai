package jp.reikai.yomitan.anki

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Whether Reikai JP can reach AnkiDroid, for the screens that ask the user (the popup's first add,
 * roadmap 3.4; the Japanese settings, 3.5). The engine never asks: while access is missing its
 * AnkiConnect answers every call with an error Yomitan shows ("Anki error: ..."), and each refused
 * call is reported on [refusals] so a visible screen can request [PERMISSION] (a runtime permission,
 * requested with `ActivityResultContracts.RequestPermission`).
 */
class AnkiAccess(context: Context) {

    enum class Status {
        /** AnkiDroid is not installed (or too old to define its API permission). */
        MISSING_APP,

        /** AnkiDroid is installed; Reikai JP may not use its database yet. */
        NEEDS_PERMISSION,

        READY,
    }

    /** A call Yomitan made that was refused, e.g. `addNote` while [Status.NEEDS_PERMISSION]. */
    data class Refusal(val status: Status, val action: String)

    private val app = context.applicationContext
    private val refusalFlow =
        MutableSharedFlow<Refusal>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Refused calls, as they happen (nothing is replayed). */
    val refusals: SharedFlow<Refusal> = refusalFlow.asSharedFlow()

    /** The current state; cheap enough to call on every AnkiConnect request, from any thread. */
    fun status(): Status {
        val installed = try {
            app.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        return when {
            !installed -> Status.MISSING_APP
            app.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED -> Status.NEEDS_PERMISSION
            else -> Status.READY
        }
    }

    internal fun refused(status: Status, action: String) {
        refusalFlow.tryEmit(Refusal(status, action))
    }

    companion object {
        const val PACKAGE = "com.ichi2.anki"

        /** AnkiDroid's API permission, granted by the user at run time. */
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    }
}

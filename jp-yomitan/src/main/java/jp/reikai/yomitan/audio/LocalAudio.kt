package jp.reikai.yomitan.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_URI
import jp.reikai.yomitan.LocalResponse
import jp.reikai.yomitan.LocalRoute
import jp.reikai.yomitan.LocalServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import logcat.logcat
import java.io.File

/**
 * Local audio: the community `android.db` (SQLite: `entries(expression, reading, source, speaker,
 * display, file)` and `android(file, source, data)`, several GB), picked by the user through the
 * system file picker and served under AnkiConnect Android's URLs, so a desktop Yomitan backup that
 * lists `http://localhost:8765/localaudio/get/?term={term}&reading={reading}` works unchanged:
 * - `GET /localaudio/get/?term=&reading=` answers Yomitan's custom-json list of recordings;
 * - `GET /localaudio/<source>/<file>` answers one recording.
 *
 * The picked file stays where it is: SQLite opens it read-only through the picker's file descriptor
 * (`/proc/self/fd/N`). Where that is refused, the file is copied into the app's storage once, with
 * progress in [status]. Nothing is opened until the first lookup asks for audio.
 *
 * @param savedUri the picked file's URI, kept by the app ("" when none).
 * @param saveUri keeps it.
 */
class LocalAudio(
    context: Context,
    private val savedUri: () -> String,
    private val saveUri: (String) -> Unit,
) {

    sealed interface Status {
        /** No database was picked. */
        data object None : Status

        /** Picked but not opened yet (it opens on the first lookup that asks for audio). */
        data object Closed : Status

        data object Opening : Status

        /** The picker's file cannot be opened in place; it is being copied into the app's storage. */
        data class Copying(val copiedBytes: Long, val totalBytes: Long) : Status

        /** Open; [copied] when it is the app's own copy of the file. */
        data class Ready(val copied: Boolean) : Status

        data class Failed(val reason: String) : Status
    }

    private val app = context.applicationContext
    private val mutex = Mutex()
    private var db: Database? = null
    private val statusFlow = MutableStateFlow(if (savedUri().isEmpty()) Status.None else Status.Closed)

    val status: StateFlow<Status> = statusFlow.asStateFlow()

    /** The local server's route for `/localaudio/...`. */
    val route: LocalRoute = LocalRoute.get(PREFIX) { request ->
        val path = request.path.removePrefix(PREFIX)
        if (path == "get/" || path == "get") {
            sources(request.query["term"].orEmpty(), request.query["reading"].orEmpty())
        } else {
            recording(path)
        }
    }

    /**
     * Uses the database the user picked ([uri] from `ACTION_OPEN_DOCUMENT`): keeps access to it across
     * restarts, opens it (copying it when it cannot be opened in place) and checks it is an
     * `android.db`. Returns the resulting [status].
     */
    suspend fun setDatabase(uri: Uri): Status = mutex.withLock {
        withContext(Dispatchers.IO) {
            closeLocked()
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            copyFile().delete()
            saveUri(uri.toString())
            openLocked()
            statusFlow.value
        }
    }

    /** Forgets the database, and the app's copy of it if there is one. */
    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            closeLocked()
            val uri = savedUri()
            if (uri.isNotEmpty()) {
                runCatching {
                    app.contentResolver.releasePersistableUriPermission(
                        Uri.parse(uri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            copyFile().delete()
            saveUri("")
            statusFlow.value = Status.None
        }
    }

    private suspend fun sources(term: String, reading: String): LocalResponse {
        val started = SystemClock.elapsedRealtime()
        val entries = withDatabase { it.entries(term, reading) } ?: return EMPTY_LIST
        val recordings = LocalAudioRanking.rank(entries, term, reading)
        logcat(TAG) {
            "local audio $term【$reading】: ${recordings.size} in ${SystemClock.elapsedRealtime() - started} ms"
        }
        val json = buildJsonObject {
            put("type", "audioSourceList")
            put(
                "audioSources",
                buildJsonArray {
                    recordings.forEach { recording ->
                        add(
                            buildJsonObject {
                                put("name", recording.name)
                                put("url", "$BASE${encode(recording.source)}/${encode(recording.file)}")
                            },
                        )
                    }
                },
            )
        }
        return LocalResponse.json(json.toString())
    }

    private suspend fun recording(path: String): LocalResponse {
        val source = decode(path.substringBefore('/'))
        val file = decode(path.substringAfter('/', ""))
        val type = LocalAudioRanking.mediaType(file) ?: return NOT_FOUND
        val data = withDatabase { it.recording(source, file) } ?: return NOT_FOUND
        return LocalResponse(contentType = type, body = data)
    }

    /** Runs [block] on the open database, opening it first; null when there is none or it failed. */
    private suspend fun <T> withDatabase(block: (Database) -> T): T? {
        // While the file is being opened or copied (minutes for several GB), lookups go without it.
        if (statusFlow.value.let { it is Status.Copying || it == Status.Opening }) return null
        return mutex.withLock { useLocked(block) }
    }

    private fun <T> useLocked(block: (Database) -> T): T? {
        if (db == null && statusFlow.value == Status.Closed) openLocked(allowCopy = false)
        val open = db ?: return null
        return runCatching { block(open) }.onFailure {
            logcat(TAG, LogPriority.WARN) { "local audio: $it" }
        }.getOrNull()
    }

    /** Opens the saved database; copies it when it cannot be opened in place and [allowCopy]. */
    private fun openLocked(allowCopy: Boolean = true) {
        val uri = savedUri().takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: run {
            statusFlow.value = Status.None
            return
        }
        statusFlow.value = Status.Opening
        val started = SystemClock.elapsedRealtime()
        val copy = copyFile()
        db = try {
            if (copy.isFile) {
                Database.open(copy.path, null).also { statusFlow.value = Status.Ready(copied = true) }
            } else {
                openInPlace(uri)
                    ?: if (allowCopy) copyAndOpen(uri, copy) else error("the audio database cannot be opened")
            }
        } catch (e: Exception) {
            logcat(TAG, LogPriority.WARN) { "local audio: cannot open $uri: $e" }
            statusFlow.value = Status.Failed(e.message ?: e.javaClass.simpleName)
            null
        }
        logcat(TAG) { "local audio: ${statusFlow.value} after ${SystemClock.elapsedRealtime() - started} ms" }
    }

    /** SQLite on the picker's descriptor, read-only and without locks (`immutable`); null if refused. */
    private fun openInPlace(uri: Uri): Database? {
        val descriptor = app.contentResolver.openFileDescriptor(uri, "r") ?: error("the file cannot be read")
        return try {
            Database.open("file:/proc/self/fd/${descriptor.fd}?mode=ro&immutable=1", descriptor)
                .also { statusFlow.value = Status.Ready(copied = false) }
        } catch (e: Exception) {
            logcat(TAG, LogPriority.WARN) { "local audio: opening in place failed ($e), copying it" }
            descriptor.close()
            null
        }
    }

    private fun copyAndOpen(uri: Uri, copy: File): Database {
        val total = app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        copy.parentFile!!.mkdirs()
        if (total > 0 && StatFs(copy.parent).availableBytes < total + FREE_SPACE_MARGIN) {
            error("not enough free space to copy the audio database (${total / MB} MB)")
        }
        val partial = File(copy.path + ".part")
        statusFlow.value = Status.Copying(0, total)
        app.contentResolver.openInputStream(uri)!!.use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                var copied = 0L
                var reported = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    copied += n
                    if (copied - reported >= PROGRESS_STEP) {
                        reported = copied
                        statusFlow.value = Status.Copying(copied, total)
                    }
                }
            }
        }
        check(partial.renameTo(copy)) { "the copy could not be saved" }
        return Database.open(copy.path, null).also { statusFlow.value = Status.Ready(copied = true) }
    }

    private fun closeLocked() {
        db?.close()
        db = null
    }

    private fun copyFile() = File(app.noBackupFilesDir, "jp-local-audio/android.db")

    /** One open `android.db`; the descriptor (when opened in place) lives as long as the connection. */
    private class Database(private val connection: SQLiteConnection, private val descriptor: ParcelFileDescriptor?) {

        fun entries(term: String, reading: String): List<LocalAudioRanking.Entry> {
            val kana = LocalAudioRanking.katakanaToHiragana(reading)
            val sql = if (kana.isEmpty()) ENTRIES_BY_TERM else ENTRIES_BY_TERM_OR_READING
            return connection.prepare(sql).use { statement ->
                statement.bindText(1, term)
                if (kana.isNotEmpty()) statement.bindText(2, kana)
                buildList {
                    while (statement.step()) {
                        add(
                            LocalAudioRanking.Entry(
                                source = statement.getText(0),
                                display = if (statement.isNull(1)) null else statement.getText(1),
                                file = statement.getText(2),
                                expression = statement.getText(3),
                                reading = if (statement.isNull(4)) null else statement.getText(4),
                            ),
                        )
                    }
                }
            }
        }

        fun recording(source: String, file: String): ByteArray? = connection.prepare(RECORDING).use { statement ->
            statement.bindText(1, source)
            statement.bindText(2, file)
            if (statement.step()) statement.getBlob(0) else null
        }

        fun close() {
            connection.close()
            descriptor?.close()
        }

        companion object {
            fun open(name: String, descriptor: ParcelFileDescriptor?): Database {
                val connection = BundledSQLiteDriver().open(name, SQLITE_OPEN_READONLY or SQLITE_OPEN_URI)
                try {
                    // Fails here, not on the first lookup, when the file is not an android.db.
                    connection.prepare("SELECT 1 FROM entries LIMIT 1").use { it.step() }
                    connection.prepare("SELECT 1 FROM android LIMIT 1").use { it.step() }
                } catch (e: Exception) {
                    connection.close()
                    throw IllegalStateException("not a local audio database (android.db): ${e.message}", e)
                }
                return Database(connection, descriptor)
            }
        }
    }

    companion object {
        /** Yomitan's audio source for local audio (type custom-json), AnkiConnect Android's URL. */
        const val SOURCE_URL = "http://localhost:${LocalServer.PORT}/localaudio/get/?term={term}&reading={reading}"

        const val PREFIX = "/localaudio/"
        private const val BASE = "http://localhost:${LocalServer.PORT}$PREFIX"
        private const val TAG = "Yomitan"
        private const val MB = 1024 * 1024
        private const val PROGRESS_STEP = 16L * MB
        private const val FREE_SPACE_MARGIN = 256L * MB

        /** The candidate rows; [LocalAudioRanking] orders them. */
        internal const val ENTRIES_BY_TERM =
            "SELECT source, display, file, expression, reading FROM entries WHERE expression = ?1"
        internal const val ENTRIES_BY_TERM_OR_READING =
            "SELECT source, display, file, expression, reading FROM entries WHERE expression = ?1 OR reading = ?2"
        internal const val RECORDING = "SELECT data FROM android WHERE source = ?1 AND file = ?2 LIMIT 1"

        private val EMPTY_LIST = LocalResponse.json("""{"type":"audioSourceList","audioSources":[]}""")

        /** Yomitan skips a source that answers 404 ("no audio here"). */
        internal val NOT_FOUND = LocalResponse(status = 404, contentType = "text/plain")

        /** Percent-encodes a path segment (file names hold Japanese and spaces). */
        internal fun encode(segment: String): String = buildString {
            segment.toByteArray().forEach { byte ->
                val c = byte.toInt() and 0xff
                if ((c < 0x80 && c.toChar().isLetterOrDigit()) || c.toChar() in "-._~") {
                    append(c.toChar())
                } else {
                    append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
                }
            }
        }

        internal fun decode(segment: String): String {
            if ('%' !in segment) return segment
            val out = java.io.ByteArrayOutputStream(segment.length)
            var i = 0
            while (i < segment.length) {
                val hex = if (segment[i] == '%' &&
                    i + 3 <= segment.length
                ) {
                    segment.substring(i + 1, i + 3).toIntOrNull(16)
                } else {
                    null
                }
                if (hex != null) {
                    out.write(hex)
                    i += 3
                } else {
                    val end = i + Character.charCount(segment.codePointAt(i))
                    out.write(segment.substring(i, end).toByteArray())
                    i = end
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }

        private const val HEX = "0123456789ABCDEF"
    }
}

package jp.reikai.yomitan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.OutputStream
import java.util.Base64

/**
 * Files Yomitan's settings page saves (its settings export, roadmap 3.5). A browser downloads them;
 * in the app the stand-in sends each one in slices (`save` requests: start, data, end), written here
 * to a temporary file in the app's cache and then handed to the screen showing the page, which asks
 * the user where to keep it. Main thread, except the writing.
 */
internal class YomitanSaves(private val dir: File, private val scope: CoroutineScope) {

    private class Pending(
        val viewId: Int,
        val name: String,
        val mimeType: String,
        val file: File,
        val out: OutputStream,
    )

    private val pending = HashMap<Int, Pending>()
    private var nextId = 1

    /** One step from the hub; [deliver] hands the finished file to the page's screen (false: refused). */
    fun step(
        viewId: Int,
        step: String,
        id: Int?,
        payload: String,
        deliver: (Int, YomitanDownload) -> Boolean,
        done: (Result<String>) -> Unit,
    ) {
        when (step) {
            "start" -> done(runCatching { """{"id":${start(viewId, payload)}}""" })
            "data" -> {
                val save = pending[id]?.takeIf { it.viewId == viewId } ?: return done(unknown())
                scope.launch {
                    val written = withContext(Dispatchers.IO) {
                        runCatching { save.out.write(Base64.getDecoder().decode(payload)) }
                    }
                    if (written.isFailure) drop(id!!)
                    done(written.map { "" })
                }
            }
            "end" -> {
                val save = pending.remove(id)?.takeIf { it.viewId == viewId } ?: return done(unknown())
                scope.launch {
                    val closed = withContext(Dispatchers.IO) { runCatching { save.out.close() } }
                    val handed = closed.isSuccess &&
                        deliver(viewId, YomitanDownload(save.name, save.mimeType, save.file))
                    if (!handed) save.file.delete()
                    done(if (handed) Result.success("") else Result.failure(YomitanException(NOT_SAVED)))
                }
            }
            else -> done(Result.failure(YomitanException("unknown save step $step")))
        }
    }

    /** The page's WebView went away: its unfinished files are deleted. */
    fun dropView(viewId: Int) {
        pending.filterValues { it.viewId == viewId }.keys.forEach(::drop)
    }

    private fun start(viewId: Int, payload: String): Int {
        val info = Json.parseToJsonElement(payload).jsonObject
        val name = safeName((info["name"] as? JsonPrimitive)?.contentOrNull)
        val type = (info["type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: DEFAULT_TYPE
        dir.mkdirs()
        val id = nextId++
        val file = File(dir, "$id-$name")
        pending[id] = Pending(viewId, name, type, file, file.outputStream().buffered())
        return id
    }

    private fun drop(id: Int) {
        val save = pending.remove(id) ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { save.out.close() }
            save.file.delete()
        }
    }

    private fun unknown() = Result.failure<String>(YomitanException("unknown save"))

    companion object {
        private const val DEFAULT_TYPE = "application/octet-stream"
        private const val NOT_SAVED = "The app did not save the file"

        /** A page's file name without any path, never empty. */
        internal fun safeName(name: String?): String =
            name.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim().trimStart('.')
                .ifEmpty { "yomitan-file" }
    }
}

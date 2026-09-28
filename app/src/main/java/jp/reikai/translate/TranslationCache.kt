package jp.reikai.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Which translation: into [language] by the engine [engine] ([TranslationEngine.id]). */
data class TranslationKey(val language: String, val engine: String) {
    init {
        require(SAFE.matches(language) && SAFE.matches(engine)) { "Not a file-name part: $language, $engine" }
    }

    private companion object {
        val SAFE = Regex("[A-Za-z0-9-]{1,40}")
    }
}

/**
 * Translations kept on the device, one file per chapter, language, engine and chapter text:
 * `<root>/<chapterId>/<language>-<engine>-<hash of the paragraphs>.json`, the paragraphs' translations
 * as a JSON array. The paragraphs rather than the finished page, because each reader gets the chapter
 * in its own markup (the Japanese reader and the standard reader's WebView and native modes), and the
 * same paragraphs go back into whichever one opens it. A chapter whose text changed has a new hash,
 * and its old translation by the same engine and language is deleted when the new one is written.
 */
class TranslationCache(private val root: File) {

    /**
     * Translates the chapter [html] into [target] with [engine], unless that translation of this text is
     * saved already, and saves it; answers which translation it is.
     */
    suspend fun translate(chapterId: Long, html: String, engine: TranslationEngine, target: String): TranslationKey {
        val key = TranslationKey(target, engine.id)
        val prepared = ChapterTranslator.prepare(html)
        if (prepared.texts.isEmpty()) throw NothingToTranslate()
        if (read(chapterId, key, prepared.hash)?.size != prepared.texts.size) {
            write(chapterId, key, prepared.hash, ChapterTranslator.translate(prepared, engine, target))
        }
        return key
    }

    /** [html] with its saved translation under [key] in place, or null when none is saved for this text. */
    fun translated(chapterId: Long, key: TranslationKey, html: String): String? {
        val prepared = ChapterTranslator.prepare(html)
        val saved = read(chapterId, key, prepared.hash) ?: return null
        if (saved.size != prepared.texts.size) return null
        return ChapterTranslator.write(prepared, saved, key.language)
    }

    fun file(chapterId: Long, key: TranslationKey, hash: String): File =
        File(File(root, chapterId.toString()), "${prefix(key)}$hash.json")

    fun read(chapterId: Long, key: TranslationKey, hash: String): List<String>? {
        val file = file(chapterId, key, hash)
        if (!file.isFile) return null
        return runCatching {
            (Json.parseToJsonElement(file.readText()) as JsonArray).map { it.jsonPrimitive.content }
        }.getOrNull()
    }

    fun write(chapterId: Long, key: TranslationKey, hash: String, translations: List<String>) {
        val file = file(chapterId, key, hash)
        val folder = file.parentFile ?: return
        folder.mkdirs()
        folder.listFiles()?.forEach { if (it.name.startsWith(prefix(key)) && it.name != file.name) it.delete() }
        val partial = File(folder, file.name + ".part")
        partial.writeText(JsonArray(translations.map(::JsonPrimitive)).toString())
        if (!partial.renameTo(file)) {
            partial.delete()
            error("Could not save the translation")
        }
    }

    /** Every saved translation gone; the bytes freed. */
    fun clear(): Long {
        val size = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        root.deleteRecursively()
        return size
    }

    private fun prefix(key: TranslationKey) = "${key.language}-${key.engine}-"
}

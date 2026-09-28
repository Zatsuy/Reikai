package jp.reikai.translate

import eu.kanade.tachiyomi.network.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

/**
 * A translation service for a chapter (4.5, phase 4 ruling 15): texts in, as many translations out, in
 * the same order. A failure throws [TranslationFailure] with the service's own words; an engine never
 * hands back the source text as if it were a translation.
 */
interface TranslationEngine {

    /** Part of the cache key: another engine, or another model, is another translation. */
    val id: String

    /** The service's name, which starts its error messages. */
    val name: String

    /** At most this many texts in one [translate] call. */
    val maxTexts: Int

    /** At most about this many characters in one [translate] call (one text longer than it goes alone). */
    val maxChars: Int

    /**
     * [source] is a language code or `auto`; [target] a language code (`en`, `pt-BR`, `zh-TW`), which
     * the engine turns into its own form.
     */
    suspend fun translate(texts: List<String>, source: String, target: String): List<String>
}

/** A translation that did not happen; [message] says why in the service's words. */
open class TranslationFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The engine needs something only the owner can set: a key, an address or a model. */
class TranslationSetupMissing(val what: What, service: String) : TranslationFailure("$service: $what missing") {
    enum class What { KEY, ADDRESS, MODEL }
}

/**
 * One request's body. A refused request becomes a [TranslationFailure] with the message [detail] reads
 * from the answer, or the HTTP status; a failed connection one with the connection's message.
 */
internal suspend fun OkHttpClient.answer(request: Request, service: String, detail: (Int, String) -> String?): String {
    val response = try {
        newCall(request).await()
    } catch (e: IOException) {
        throw TranslationFailure("$service: ${e.message ?: e.javaClass.simpleName}", e)
    }
    val body = response.use { it.body.string() }
    if (!response.isSuccessful) {
        val said = runCatching { detail(response.code, body) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        throw TranslationFailure("$service: ${said ?: "HTTP ${response.code}"}")
    }
    return body
}

/** The first [length] hexadecimal digits of the SHA-256 of [text]. */
internal fun shortHash(text: String, length: Int = 16): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(length)

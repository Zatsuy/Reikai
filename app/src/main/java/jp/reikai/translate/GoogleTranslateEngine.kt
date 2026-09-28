/*
 * Reikai JP: chapter translation (roadmap 4.5, phase 4 ruling 15). GPL-3.0-or-later, except the
 * request and the reading of its answer, adapted from Tsundoku
 * (https://github.com/tsundoku-otaku/tsundoku),
 * app/src/main/java/eu/kanade/tachiyomi/data/translation/engine/GoogleTranslateScraperEngine.kt:
 *
 * Copyright 2015 Javier Tomás
 * Copyright 2024 Mihon Open Source Project
 * Copyright the Tsundoku contributors
 * Licensed under the Apache License, Version 2.0 (LICENSES/Apache-2.0.txt).
 *
 * Modified for Reikai JP: no TKK token and only the translation (`dt=t`) asked for; the texts of a
 * batch go as lines of one request; a refused request is an error instead of the source text passed
 * off as its translation.
 */
package jp.reikai.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Google Translate through the endpoint its web widgets use (`client=gtx`): no key, the default engine.
 * A batch goes as one text of lines; should Google join or split a line, each text of that batch goes
 * again on its own.
 */
class GoogleTranslateEngine(private val client: OkHttpClient) : TranslationEngine {

    override val id = "google"
    override val name = NAME
    override val maxTexts = 100
    override val maxChars = 3500

    override suspend fun translate(texts: List<String>, source: String, target: String): List<String> {
        if (texts.isEmpty()) return emptyList()
        val lines = request(texts.joinToString("\n"), source, target).split('\n').map(String::trim)
        if (lines.size == texts.size) return lines
        if (texts.size == 1) return listOf(lines.joinToString(" ").trim())
        return texts.map { request(it, source, target).replace('\n', ' ').trim() }
    }

    private suspend fun request(text: String, source: String, target: String): String {
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", source)
            .addQueryParameter("tl", code(target))
            .addQueryParameter("dt", "t")
            .build()
        val request = Request.Builder()
            .url(url)
            .post(FormBody.Builder().add("q", text).build())
            .build()
        return parse(client.answer(request, NAME) { _, _ -> null })
    }

    companion object {
        const val NAME = "Google Translate"
        const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

        private val json = Json { ignoreUnknownKeys = true }

        /** Google's code for [target]: Chinese by script (`zh-CN`, `zh-TW`), else the language. */
        fun code(target: String): String = when (target.lowercase()) {
            "zh", "zh-cn", "zh-hans" -> "zh-CN"
            "zh-tw", "zh-hant", "zh-hk" -> "zh-TW"
            else -> target.substringBefore('-')
        }

        /**
         * The translation in an answer: the first member of each sentence of its first array, joined.
         * Anything else is an error, never an empty translation.
         */
        fun parse(body: String): String {
            val sentences = runCatching { (json.parseToJsonElement(body) as JsonArray)[0] as JsonArray }
                .getOrElse { throw TranslationFailure("$NAME: unexpected answer") }
            return sentences.joinToString("") { sentence ->
                ((sentence as? JsonArray)?.getOrNull(0))?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        }
    }
}

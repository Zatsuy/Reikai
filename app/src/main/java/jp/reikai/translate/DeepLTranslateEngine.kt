/*
 * Reikai JP: chapter translation (roadmap 4.5, phase 4 ruling 15). GPL-3.0-or-later, except the
 * request and the reading of its answer, adapted from Tsundoku
 * (https://github.com/tsundoku-otaku/tsundoku),
 * app/src/main/java/eu/kanade/tachiyomi/data/translation/engine/DeepLTranslateEngine.kt:
 *
 * Copyright 2015 Javier Tomás
 * Copyright 2024 Mihon Open Source Project
 * Copyright the Tsundoku contributors
 * Licensed under the Apache License, Version 2.0 (LICENSES/Apache-2.0.txt).
 *
 * Modified for Reikai JP: the key is passed in; English and Portuguese go to a regional variant, as
 * DeepL asks of a target; an answer with a different number of texts is an error.
 */
package jp.reikai.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * DeepL's API, with the owner's key: a free key (ending `:fx`) on `api-free.deepl.com`, a paid one on
 * `api.deepl.com`. Up to 50 texts per request, as DeepL allows, each a repeated `text` field.
 */
class DeepLTranslateEngine(private val client: OkHttpClient, private val key: String) : TranslationEngine {

    override val id = "deepl"
    override val name = NAME
    override val maxTexts = 50

    // DeepL takes 128 KiB a request; a Japanese character is nine bytes form-encoded.
    override val maxChars = 10_000

    override suspend fun translate(texts: List<String>, source: String, target: String): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (key.isBlank()) throw TranslationSetupMissing(TranslationSetupMissing.What.KEY, NAME)
        val form = FormBody.Builder()
        texts.forEach { form.add("text", it) }
        form.add("target_lang", target(target))
        if (source != "auto") form.add("source_lang", source.substringBefore('-').uppercase())
        val request = Request.Builder()
            .url(endpoint(key))
            .header("Authorization", "DeepL-Auth-Key $key")
            .post(form.build())
            .build()
        val body = client.answer(request, NAME) { code, answer -> refusal(code, answer) }
        val translations = parse(body)
        if (translations.size != texts.size) {
            throw TranslationFailure("$NAME: ${translations.size} translations for ${texts.size} texts")
        }
        return translations
    }

    companion object {
        const val NAME = "DeepL"

        private val json = Json { ignoreUnknownKeys = true }

        /** Free keys end in `:fx` and only work on DeepL's free endpoint. */
        fun endpoint(key: String): String = if (key.trim().endsWith(":fx")) {
            "https://api-free.deepl.com/v2/translate"
        } else {
            "https://api.deepl.com/v2/translate"
        }

        /** DeepL's target code: English as `EN-US`, Portuguese as `PT-BR`, Chinese by script. */
        fun target(language: String): String {
            val lower = language.lowercase()
            return when {
                lower == "en" -> "EN-US"
                lower == "en-gb" || lower == "en-us" -> lower.uppercase()
                lower == "pt" -> "PT-BR"
                lower == "pt-pt" || lower == "pt-br" -> lower.uppercase()
                lower in setOf("zh-tw", "zh-hant", "zh-hk") -> "ZH-HANT"
                lower.startsWith("zh") -> "ZH-HANS"
                else -> lower.substringBefore('-').uppercase()
            }
        }

        fun parse(body: String): List<String> = runCatching {
            (json.parseToJsonElement(body).jsonObject["translations"] as JsonArray)
                .map { it.jsonObject.getValue("text").jsonPrimitive.content }
        }.getOrElse { throw TranslationFailure("$NAME: unexpected answer") }

        /** DeepL's own message, with what its status codes mean when it gives none. */
        private fun refusal(code: Int, body: String): String {
            val message = runCatching {
                json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
            }
                .getOrNull()
            val meaning = when (code) {
                401, 403 -> "the key was not accepted"
                429 -> "too many requests, try again later"
                456 -> "the character allowance is used up"
                else -> null
            }
            return listOfNotNull("HTTP $code", meaning, message?.takeIf { it.isNotBlank() }).joinToString(": ")
        }
    }
}

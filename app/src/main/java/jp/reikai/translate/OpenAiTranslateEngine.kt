/*
 * Reikai JP: chapter translation (roadmap 4.5, phase 4 ruling 15). GPL-3.0-or-later, except the
 * request, the reading of its answer and the instructions to the model, adapted from Tsundoku
 * (https://github.com/tsundoku-otaku/tsundoku),
 * app/src/main/java/eu/kanade/tachiyomi/data/translation/engine/OpenAITranslateEngine.kt and
 * domain/src/main/java/tachiyomi/domain/translation/service/TranslationPromptDefaults.kt:
 *
 * Copyright 2015 Javier Tomás
 * Copyright 2024 Mihon Open Source Project
 * Copyright the Tsundoku contributors
 * Licensed under the Apache License, Version 2.0 (LICENSES/Apache-2.0.txt).
 *
 * Modified for Reikai JP: one engine for every service with an OpenAI-compatible chat API (address,
 * model and key given, presets for common services); paragraphs go as a JSON array and come back as
 * one, checked for their number; no temperature or token limit is sent, which some models refuse.
 */
package jp.reikai.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

/** A service speaking OpenAI's chat API, with its address and a model to start from. */
enum class AiPreset(val key: String, val label: String, val address: String, val model: String) {
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1/", "gpt-4.1-mini"),
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/", "gemini-2.5-flash"),
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com/v1/", "deepseek-chat"),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/", "google/gemini-2.5-flash"),
    OLLAMA("ollama", "Ollama", "http://localhost:11434/v1/", "qwen2.5:7b"),
    CUSTOM("custom", "Other", "", ""),
    ;

    companion object {
        fun of(key: String): AiPreset = entries.firstOrNull { it.key == key } ?: OPENAI
    }
}

/**
 * Any service with an OpenAI-compatible `chat/completions` endpoint (OpenAI, Gemini, DeepSeek,
 * OpenRouter, Ollama). A batch of paragraphs goes as a JSON array; an answer that is not an array of
 * as many strings is asked again in halves, down to single paragraphs.
 */
class OpenAiTranslateEngine(
    private val client: OkHttpClient,
    private val address: String,
    private val model: String,
    private val key: String,
    override val name: String = "AI",
) : TranslationEngine {

    override val id = "ai-" + shortHash(address.trim().trimEnd('/') + "\n" + model.trim(), 8)
    override val maxTexts = 60
    override val maxChars = 3000

    override suspend fun translate(texts: List<String>, source: String, target: String): List<String> {
        if (texts.isEmpty()) return emptyList()
        val url = completions(address) ?: throw TranslationSetupMissing(TranslationSetupMissing.What.ADDRESS, name)
        if (model.isBlank()) throw TranslationSetupMissing(TranslationSetupMissing.What.MODEL, name)
        return inParts(url, texts, systemPrompt(source, target))
    }

    private suspend fun inParts(url: String, texts: List<String>, system: String): List<String> {
        val content = complete(url, system, texts)
        val parsed = parseArray(content)
        if (parsed != null && parsed.size == texts.size) return parsed
        if (texts.size == 1) {
            return listOf(
                parsed?.singleOrNull() ?: unfence(content).trim().takeIf { it.isNotEmpty() }
                    ?: throw TranslationFailure("$name: empty answer"),
            )
        }
        val half = texts.size / 2
        return inParts(url, texts.subList(0, half), system) + inParts(url, texts.subList(half, texts.size), system)
    }

    private suspend fun complete(url: String, system: String, texts: List<String>): String {
        val payload = buildJsonObject {
            put("model", model.trim())
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray { texts.forEach { add(it) } }.toString())
                }
            }
        }
        val request = Request.Builder()
            .url(url)
            .apply { if (key.isNotBlank()) header("Authorization", "Bearer ${key.trim()}") }
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()
        return reply(client.answer(request, name) { _, body -> errorMessage(body) }, name)
    }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /** Tsundoku's instructions for novel translation, for a JSON array of paragraphs. */
        private const val SYSTEM_PROMPT =
            """You are a professional translator specializing in novel/fiction translation. Translate the following text from {SOURCE_LANG} to {TARGET_LANG}.
Rules:
- The input is a JSON array of strings, each one paragraph of the novel, in order
- Reply with only a JSON array of exactly as many strings, each the translation of the string at the same position
- Do not summarize, merge, split, drop or reorder paragraphs
- Maintain the author's writing style and tone
- Keep character names consistent
- Do not add explanations or notes"""

        fun systemPrompt(source: String, target: String): String = SYSTEM_PROMPT
            .replace(
                "{SOURCE_LANG}",
                if (source ==
                    "auto"
                ) {
                    "the automatically-detected source language"
                } else {
                    languageName(source)
                },
            )
            .replace("{TARGET_LANG}", languageName(target))

        private fun languageName(code: String): String =
            Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH).ifBlank { code }

        /** `<address>/chat/completions`, or null when [address] is not a web address. */
        fun completions(address: String): String? {
            val base = address.trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: return null
            return "$base/chat/completions".toHttpUrlOrNull()?.toString()
        }

        /** The model's text in a chat answer. */
        fun reply(body: String, service: String): String = runCatching {
            val choice = json.parseToJsonElement(body).jsonObject.getValue("choices").jsonArray[0].jsonObject
            choice.getValue("message").jsonObject.getValue("content").jsonPrimitive.content
        }.getOrElse { throw TranslationFailure("$service: unexpected answer") }

        /** The texts of a JSON array somewhere in [content] (a model may fence it or talk around it). */
        fun parseArray(content: String): List<String>? {
            val text = unfence(content)
            val start = text.indexOf('[')
            val end = text.lastIndexOf(']')
            if (start < 0 || end <= start) return null
            val array = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as? JsonArray }
                .getOrNull() ?: return null
            return array.map { element -> element.textOrNull() ?: return null }
        }

        private fun JsonElement.textOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun unfence(content: String): String =
            content.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```")

        /** OpenAI's `{"error": {"message"}}`, or Gemini's list of those. */
        fun errorMessage(body: String): String? {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
            val error = when (root) {
                is JsonObject -> root["error"]
                is JsonArray -> (root.firstOrNull() as? JsonObject)?.get("error")
                else -> null
            }
            return when (error) {
                is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> error.contentOrNull
                else -> null
            }
        }
    }
}

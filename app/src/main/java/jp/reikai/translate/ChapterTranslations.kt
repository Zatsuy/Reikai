package jp.reikai.translate

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import jp.reikai.JpPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Chapter translation in the novel reader (4.5, phase 4 ruling 15): the engine the settings name, the
 * translations kept on the device, and the translation of a chapter the reader asked for. Nothing
 * goes to a service except from [translate], which only the reader menu's "Translate chapter" calls.
 */
@Inject
@SingleIn(AppScope::class)
class ChapterTranslations(
    private val context: Context,
    private val preferences: JpPreferences,
    private val network: () -> NetworkHelper,
) {

    // Anonymous, as Yomitan's requests are: no cookies, no cache, and never the Cloudflare
    // interceptor, which may open a challenge screen. A model can take its time over a batch.
    private val client: OkHttpClient by lazy {
        network().client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .apply { interceptors().removeAll { it is CloudflareInterceptor } }
            .readTimeout(2, TimeUnit.MINUTES)
            .callTimeout(3, TimeUnit.MINUTES)
            .build()
    }

    val cache: TranslationCache by lazy { TranslationCache(File(context.filesDir, "jp_translations")) }

    /** The engine the settings name now. */
    fun engine(): TranslationEngine = when (preferences.translateEngine().get()) {
        ENGINE_DEEPL -> DeepLTranslateEngine(client, preferences.translateDeepLKey().get())
        ENGINE_AI -> {
            val preset = AiPreset.of(preferences.translateAiPreset().get())
            OpenAiTranslateEngine(
                client = client,
                address = preferences.translateAiAddress().get(),
                model = preferences.translateAiModel().get(),
                key = preferences.translateAiKey(preset.key).get(),
                name = preset.label,
            )
        }
        else -> GoogleTranslateEngine(client)
    }

    /** The language to translate into now. */
    fun target(): String = TranslationLanguages.target(preferences.translateTarget().get(), Locale.getDefault())

    /**
     * Translates the chapter [html] (the reader's pipeline output) with the engine and language the
     * settings name, unless that translation of this text is already saved; answers which one it is.
     * Throws [TranslationFailure] with the service's message.
     */
    suspend fun translate(chapterId: Long, html: String): TranslationKey = withContext(Dispatchers.IO) {
        cache.translate(chapterId, html, engine(), target())
    }

    /** [html] with its saved translation under [key] in place, or null when none is saved for this text. */
    fun translated(chapterId: Long, key: TranslationKey, html: String): String? = cache.translated(chapterId, key, html)

    companion object {
        const val ENGINE_GOOGLE = "google"
        const val ENGINE_DEEPL = "deepl"
        const val ENGINE_AI = "ai"
    }
}

/** The chapter has no text: only pictures, or nothing. */
class NothingToTranslate : TranslationFailure("Nothing to translate")

/** The languages offered as a target, and the default one. */
object TranslationLanguages {

    val CODES = listOf(
        "en", "zh-CN", "zh-TW", "ko", "es", "fr", "de", "it", "pt", "ru", "uk", "pl", "nl", "sv", "tr", "ar",
        "id", "vi", "th",
    )

    /** [setting], or when it is empty the device's language, English for a device in Japanese. */
    fun target(setting: String, device: Locale): String = setting.ifBlank { fromDevice(device) }

    fun fromDevice(locale: Locale): String = when (val language = locale.language) {
        "", "ja" -> "en"
        "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
        // Android still answers the old ISO codes for these.
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> language
    }

    fun name(code: String, inLocale: Locale): String =
        Locale.forLanguageTag(code).getDisplayName(inLocale).replaceFirstChar { it.titlecase(inLocale) }
}

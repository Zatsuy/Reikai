package jp.reikai.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import jp.reikai.JpPreferences
import jp.reikai.lookup.JpLookup
import jp.reikai.lookup.JpPageOpener
import jp.reikai.lookup.PreferenceYomitanStorage
import jp.reikai.yomitan.LocalServer
import jp.reikai.yomitan.YomitanConfig
import jp.reikai.yomitan.YomitanEngine
import jp.reikai.yomitan.anki.AnkiAccess
import jp.reikai.yomitan.anki.AnkiConnect
import jp.reikai.yomitan.audio.LocalAudio
import jp.reikai.yomitan.audio.TtsAudio
import mihon.core.metro.IsDebugBuild
import mihon.core.metro.metroGraph
import okhttp3.CookieJar
import reikai.novel.source.NovelSourceManager

/**
 * Reikai JP's members of the app graph, read with `context.jpGraph` where there is no constructor
 * to inject into. AppGraph itself is upstream's and never lists fork members.
 */
@ContributesTo(AppScope::class)
interface JpGraph {
    val jpPreferences: JpPreferences
    val yomitanEngine: YomitanEngine

    /** The reader's source language, for its Japanese hook (3.4). */
    val novelSourceManager: NovelSourceManager

    /** The lookup switch, the search screen and the shared lookup popup (3.4, 3.5). */
    val jpLookup: JpLookup

    /** Whether AnkiDroid can be reached, and the calls refused while it cannot (3.4, 3.5). */
    val ankiAccess: AnkiAccess

    /** The local audio database: pick it, clear it, its state (3.5). */
    val localAudio: LocalAudio

    /** Text-to-speech as an audio source: whether a Japanese voice is there (3.5). */
    val ttsAudio: TtsAudio
}

val Context.jpGraph: JpGraph get() = metroGraph<JpGraph>()

/** How the fork's app-scoped objects that are not plain `@Inject` classes are made. */
@ContributesTo(AppScope::class)
@BindingContainer
object JpBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideAnkiAccess(context: Context): AnkiAccess = AnkiAccess(context)

    @Provides
    @SingleIn(AppScope::class)
    fun provideLocalAudio(context: Context, preferences: JpPreferences): LocalAudio {
        val uri = preferences.localAudioUri()
        return LocalAudio(context, uri::get, uri::set)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun provideTtsAudio(context: Context): TtsAudio = TtsAudio(context)

    /**
     * The Yomitan engine. Constructing it starts nothing: the engine waits for a screen to acquire it
     * (roadmap 3.1). Its local server answers AnkiConnect from AnkiDroid (3.2) and serves local audio
     * and text-to-speech (3.3); its page opener sends Yomitan's search page to the dictionary screen
     * (3.4) and will send its settings to the settings screen (3.5).
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideYomitanEngine(
        context: Context,
        preferences: JpPreferences,
        network: () -> NetworkHelper,
        ankiAccess: AnkiAccess,
        localAudio: LocalAudio,
        ttsAudio: TtsAudio,
        @IsDebugBuild isDebugBuild: Boolean,
    ): YomitanEngine {
        val lookup = preferences.lookupEnabled()
        // Yomitan's own requests go out anonymously (its request rules strip cookies), never through
        // the Cloudflare interceptor, which may open a challenge screen.
        val client by lazy {
            network().client.newBuilder()
                .cookieJar(CookieJar.NO_COOKIES)
                .cache(null)
                .apply { interceptors().removeAll { it is CloudflareInterceptor } }
                .build()
        }
        return YomitanEngine(
            context,
            YomitanConfig(
                lookupEnabled = lookup.changes(),
                isLookupEnabled = lookup::get,
                storage = PreferenceYomitanStorage(preferences.yomitanStorage()),
                httpClient = { client },
                localServer = LocalServer(
                    listOf(AnkiConnect.route(context, ankiAccess), localAudio.route, ttsAudio.route),
                ),
                pageOpener = JpPageOpener(context),
                debug = isDebugBuild,
            ),
        )
    }
}

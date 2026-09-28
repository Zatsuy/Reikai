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
import jp.reikai.lookup.PreferenceYomitanStorage
import jp.reikai.yomitan.YomitanConfig
import jp.reikai.yomitan.YomitanEngine
import mihon.core.metro.IsDebugBuild
import mihon.core.metro.metroGraph
import okhttp3.CookieJar

/**
 * Reikai JP's members of the app graph, read with `context.jpGraph` where there is no constructor
 * to inject into. AppGraph itself is upstream's and never lists fork members.
 */
@ContributesTo(AppScope::class)
interface JpGraph {
    val jpPreferences: JpPreferences
    val yomitanEngine: YomitanEngine
}

val Context.jpGraph: JpGraph get() = metroGraph<JpGraph>()

/** How the fork's app-scoped objects that are not plain `@Inject` classes are made. */
@ContributesTo(AppScope::class)
@BindingContainer
object JpBindings {

    /**
     * The Yomitan engine. Constructing it starts nothing: the engine waits for a screen to acquire it
     * (roadmap 3.1). Later slices plug in here: the AnkiConnect and audio routes of the local server
     * (3.2, 3.3) and the page opener for the search and settings screens (3.4, 3.5).
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideYomitanEngine(
        context: Context,
        preferences: JpPreferences,
        network: () -> NetworkHelper,
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
                debug = isDebugBuild,
            ),
        )
    }
}

package jp.reikai.settings

import android.webkit.CookieManager
import android.webkit.WebStorage
import jp.reikai.yomitan.YomitanOrigin

/**
 * Settings, Advanced, "Clear WebView data" without erasing the Yomitan dictionaries (roadmap 3.5).
 *
 * Upstream's version deletes every site's storage (`WebStorage.deleteAllData`) and WebView's whole
 * data directory (`app_webview/`). The dictionaries live there too, in the lookup engine's own
 * origin's IndexedDB (hundreds of MB, minutes to download and import again). This deletes each other
 * site's storage through WebView's own API, removes the cookies (which deleting the directory also
 * did), and keeps the directory, so nothing depends on how WebView lays out its files. Yomitan's
 * settings are not in WebView at all (they are the app's preferences).
 */
object WebViewData {

    /** Clears every site's storage but the lookup engine's, and every cookie. Main thread. */
    fun clearKeepingDictionaries() {
        val storage = WebStorage.getInstance()
        storage.getOrigins { origins ->
            val all = origins.orEmpty().values.mapNotNull { (it as? WebStorage.Origin)?.origin }
            originsToClear(all).forEach(storage::deleteOrigin)
        }
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
    }

    /** Every origin but the engine's, which WebView may report with or without a trailing slash. */
    internal fun originsToClear(origins: Collection<String>): List<String> =
        origins.filter { it.trimEnd('/') != YomitanOrigin.ORIGIN }
}

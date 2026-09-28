package jp.reikai.settings

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import jp.reikai.yomitan.YomitanOrigin
import java.io.File

/**
 * Settings, Advanced, "Clear WebView data" without erasing the Yomitan dictionaries (roadmap 3.5).
 *
 * Upstream's version deletes every site's storage (`WebStorage.deleteAllData`) and WebView's whole
 * data directory (`app_webview/`). The dictionaries live there too, in the lookup engine's own
 * origin's IndexedDB (hundreds of MB, minutes to download and import again). This deletes each other
 * site's storage through WebView's own API, removes the cookies (which deleting the directory also
 * did), and keeps the directory, so nothing depends on how WebView lays out the dictionaries.
 * `deleteOrigin` clears only quota-managed storage (IndexedDB, file systems, Web SQL) of the origins
 * WebView lists, so the storage it leaves out (every site's local and session storage, service
 * workers) goes by deleting those directories, as upstream's deletion of the whole directory did.
 * Yomitan's settings are not in WebView at all (they are the app's preferences); the engine loses
 * only what its search page remembers in local storage.
 */
object WebViewData {

    /** Clears every site's storage but the lookup engine's dictionaries, and every cookie. Main thread. */
    fun clearKeepingDictionaries(context: Context) {
        val storage = WebStorage.getInstance()
        storage.getOrigins { origins ->
            val all = origins.orEmpty().values.mapNotNull { (it as? WebStorage.Origin)?.origin }
            originsToClear(all).forEach(storage::deleteOrigin)
        }
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        storageDirectories(File(context.applicationInfo.dataDir, "app_webview")).forEach { it.deleteRecursively() }
    }

    /** Every origin but the engine's, which WebView may report with or without a trailing slash. */
    internal fun originsToClear(origins: Collection<String>): List<String> =
        origins.filter { it.trimEnd('/') != YomitanOrigin.ORIGIN }

    /**
     * The storage `deleteOrigin` leaves, in WebView's profile directory (`Default/`, or the data
     * directory itself in older WebViews). Never `IndexedDB/`, where the dictionaries are.
     */
    internal fun storageDirectories(webViewData: File): List<File> =
        listOf(File(webViewData, "Default"), webViewData).flatMap { profile ->
            UNLISTED_STORAGE.map { File(profile, it) }
        }.filter { it.exists() }

    private val UNLISTED_STORAGE = listOf("Local Storage", "Session Storage", "Service Worker")
}

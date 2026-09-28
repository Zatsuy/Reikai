package jp.reikai

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Reikai JP's own settings. Keys start with `jp_`; they are backed up with the app's other
 * preferences, and a key is never renamed without a migration.
 */
@Inject
@SingleIn(AppScope::class)
class JpPreferences(private val preferenceStore: PreferenceStore) {

    /** Yomitan lookup (popup, search, "Look up"); while off the engine never starts (D-025). */
    fun lookupEnabled() = preferenceStore.getBoolean("jp_lookup_enabled", true)

    /**
     * Yomitan's `chrome.storage.local` (its settings, under "options"), as one JSON object of JSON
     * texts. Kept here rather than in WebView's storage so the app's backups carry it and clearing
     * WebView data does not reset Yomitan. Upstream's backup carries only this preference file
     * (`PreferenceBackupCreator`), so it stays here rather than in a file of its own; one profile's
     * default settings are about 9 KB (16 KB as stored), a few more with custom card templates.
     */
    fun yomitanStorage() = preferenceStore.getString("jp_yomitan_storage_local", "{}")

    /** The local audio database (`android.db`) the user picked, as a document URI; "" when none. */
    fun localAudioUri() = preferenceStore.getString("jp_local_audio_uri", "")

    /**
     * Whether Yomitan's settings were ever given the app's phone and tablet defaults
     * ([jp.reikai.yomitan.settings.MobileDefaults]): once, on the first settings Yomitan made itself,
     * and never again, whatever the user changes or imports later.
     */
    fun yomitanMobileDefaultsDone() = preferenceStore.getBoolean("jp_yomitan_mobile_defaults_done", false)

    /** The lookup sheet's height as a share of the window's, as the reader last dragged it. */
    fun lookupSheetHeight() = preferenceStore.getFloat("jp_lookup_sheet_height", 0.5f)
}

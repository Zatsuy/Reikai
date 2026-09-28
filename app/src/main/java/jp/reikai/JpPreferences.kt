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
     * WebView data does not reset Yomitan.
     */
    fun yomitanStorage() = preferenceStore.getString("jp_yomitan_storage_local", "{}")

    /** The local audio database (`android.db`) the user picked, as a document URI; "" when none. */
    fun localAudioUri() = preferenceStore.getString("jp_local_audio_uri", "")
}

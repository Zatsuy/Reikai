package jp.reikai.lookup

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.ViewGroup
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import jp.reikai.JpPreferences
import jp.reikai.yomitan.YomitanEngine
import jp.reikai.yomitan.anki.AnkiAccess
import jp.reikai.yomitan.popup.YomitanPopup
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Dictionary lookup as the rest of the app sees it (roadmap 3.4; the Japanese settings screen, 3.5,
 * uses it too): the lookup switch (D-025), the search screen, and the one lookup popup every surface
 * shares, kept ready between lookups.
 */
@Inject
@SingleIn(AppScope::class)
class JpLookup(
    private val context: Context,
    private val preferences: JpPreferences,
    private val engineProvider: () -> YomitanEngine,
    val ankiAccess: AnkiAccess,
) {
    private val scope = MainScope()
    private var popup: YomitanPopup? = null
    private var popupExpiry: Job? = null

    /** "AnkiDroid is not installed" was said once this run; it is not said again. */
    internal var ankiMissingNoted = false

    val engine: YomitanEngine get() = engineProvider()

    init {
        // "Look up in Reikai JP" leaves every app's selection menu while lookup is off.
        scope.launch {
            preferences.lookupEnabled().changes().distinctUntilChanged().collect { on ->
                setComponentEnabled(LookupActivity::class.java, on)
                if (!on) closePopup()
            }
        }
    }

    val isEnabled: Boolean get() = preferences.lookupEnabled().get()

    /** Switches lookup on or off; off stops the engine and hides "Look up" everywhere (D-025). */
    fun setEnabled(on: Boolean) = preferences.lookupEnabled().set(on)

    /** The lookup sheet's height, as a share of the window's. */
    internal fun sheetHeight() = preferences.lookupSheetHeight()

    /** The dictionary search screen, looking up [query] if given. */
    fun searchIntent(context: Context, query: String? = null): Intent = DictionaryActivity.intent(context, query)

    /**
     * The shared lookup popup, moved into [host]'s window (the caller adds its WebView to a view);
     * created, and the engine started, if needed. Null while lookup is off. Main thread.
     */
    fun popup(host: Activity): YomitanPopup? {
        popupExpiry?.cancel()
        popupExpiry = null
        popup?.takeIf { !it.closed }?.let { existing ->
            (existing.webView.parent as? ViewGroup)?.removeView(existing.webView)
            existing.moveTo(host)
            return existing
        }
        if (!isEnabled) return null
        return YomitanPopup.create(engine, host)?.also { popup = it }
    }

    /** [host] is going away: the popup waits, ready, for the next lookup, then closes. */
    fun releasePopup(popup: YomitanPopup) {
        if (popup !== this.popup) return
        (popup.webView.parent as? ViewGroup)?.removeView(popup.webView)
        popup.moveTo(context)
        popupExpiry?.cancel()
        popupExpiry = scope.launch {
            delay(POPUP_KEPT_MILLIS)
            closePopup()
        }
    }

    private fun closePopup() {
        popupExpiry?.cancel()
        popupExpiry = null
        popup?.let { (it.webView.parent as? ViewGroup)?.removeView(it.webView) }
        popup?.close()
        popup = null
    }

    private fun setComponentEnabled(component: Class<*>, on: Boolean) {
        val name = ComponentName(context, component)
        val state = if (on) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val pm = context.packageManager
        if (pm.getComponentEnabledSetting(name) != state) {
            pm.setComponentEnabledSetting(name, state, PackageManager.DONT_KILL_APP)
        }
    }

    private companion object {
        /** As long as the engine itself stays after its last user. */
        const val POPUP_KEPT_MILLIS = 60_000L
    }
}

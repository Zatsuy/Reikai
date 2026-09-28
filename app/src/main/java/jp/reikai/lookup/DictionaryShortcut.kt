package jp.reikai.lookup

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import jp.reikai.yomitan.R
import logcat.LogPriority
import logcat.logcat

/**
 * The "Dictionary" shortcut to the search screen (roadmap 3.4), which the Japanese settings offer to
 * put on the home screen ([pin]). Not in the launcher's long-press list: launchers show about four
 * of an app's shortcuts and make room for a dynamic one by dropping the last of the manifest's (on
 * the tablet, upstream's Browse went for it), and a line in upstream's `shortcuts.xml` would do the
 * same from first place. The search screen is a task of its own when a launcher starts it, so it
 * never replaces an open reader.
 */
internal object DictionaryShortcut {

    /**
     * Not "jp_dictionary": that was a manifest shortcut in earlier builds, and one pinned then stays on
     * the home screen as a disabled manifest shortcut, which a pin request with its id may not touch.
     */
    private const val ID = "jp_dictionary_home"

    private fun info(context: Context): ShortcutInfoCompat = ShortcutInfoCompat.Builder(context, ID)
        .setShortLabel(context.getString(R.string.jp_dictionary))
        .setLongLabel(context.getString(R.string.jp_dictionary_long))
        .setIcon(IconCompat.createWithResource(context, R.drawable.jp_sc_dictionary_48dp))
        .setIntent(Intent(Intent.ACTION_VIEW, null, context, DictionaryActivity::class.java))
        .build()

    fun canPin(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * Asks the launcher to put the shortcut on the home screen (it asks the user to confirm); says so
     * when the launcher refuses or the request fails.
     */
    fun pin(context: Context) {
        val asked = runCatching { ShortcutManagerCompat.requestPinShortcut(context, info(context), null) }
            .onFailure { logcat(LogPriority.WARN) { "Dictionary shortcut: $it" } }
            .getOrDefault(false)
        if (!asked) Toast.makeText(context, R.string.jp_settings_pin_dictionary_failed, Toast.LENGTH_LONG).show()
    }
}

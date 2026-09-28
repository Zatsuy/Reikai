package jp.reikai.lookup

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import jp.reikai.di.jpGraph
import jp.reikai.yomitan.popup.PopupLookup

/**
 * "Look up in Reikai JP" in any app's text-selection menu (`ACTION_PROCESS_TEXT`): the lookup sheet
 * over the app the text came from, closing back to it. It ends as soon as it is out of sight (a link
 * in the results opened another screen, or the user left), so it never waits over the other app
 * with an old lookup and holding the popup. The component is switched off with lookup (D-025,
 * [JpLookup]); started anyway (before the switch reached it), it says lookup is off and offers to
 * turn it on.
 */
class LookupActivity : ComponentActivity() {

    private val lookup by lazy { jpGraph.jpLookup }
    private var sheet: LookupSheet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(FrameLayout(this))
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStop() {
        super.onStop()
        // Not for a rotation, which it handles itself, nor a dialog over it (Anki's permission).
        if (!isChangingConfigurations) finish()
    }

    private fun handle(intent: Intent) {
        val askedAt = SystemClock.uptimeMillis()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()?.take(MAX_LENGTH)
        if (text.isNullOrEmpty()) {
            finish()
            return
        }
        if (!lookup.isEnabled) {
            showOff(text)
            return
        }
        val sheet = sheet ?: LookupSheet(this, lookup, onClosed = ::finish).also { sheet = it }
        sheet.show(
            PopupLookup(
                query = text,
                dark =
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES,
                // More than a word: its words, parsed, above the results, each one tappable.
                showSentence = text.length > WORD_LENGTH,
            ),
            askedAt,
        )
    }

    private fun showOff(text: String) {
        val content = findViewById<FrameLayout>(android.R.id.content)
        content.removeAllViews()
        content.addView(
            lookupOffCard(this, onClose = ::finish) {
                lookup.setEnabled(true)
                content.removeAllViews()
                handle(Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
            },
            lookupOffCardParams(this),
        )
    }

    private companion object {
        /** Longer selections are cut: the popup shows a sentence, not a page. */
        const val MAX_LENGTH = 1000

        /** Up to this long, a selection is taken for one word. */
        const val WORD_LENGTH = 6
    }
}

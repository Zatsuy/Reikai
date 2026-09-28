package jp.reikai.lookup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import jp.reikai.settings.YomitanSettingsActivity
import jp.reikai.yomitan.YomitanPageOpener
import jp.reikai.yomitan.YomitanPageRequest

/**
 * Where the pages Yomitan opens go: its search page to the dictionary screen (3.4), its settings and
 * its other pages (info, legal) to the Yomitan settings screen (3.5), web links to the browser.
 *
 * Yomitan's own pages open over the screen whose page asked, in its task: over the reader in the
 * app's, within the home-screen dictionary's, over the app a "Look up in Reikai JP" came from, so
 * back returns there. Only a launcher's start makes the dictionary a task of its own (its manifest
 * affinity), and the browser always is one.
 */
internal class JpPageOpener(private val context: Context) : YomitanPageOpener {

    override fun open(request: YomitanPageRequest, from: Context?): Boolean = when (request) {
        is YomitanPageRequest.Search -> start(DictionaryActivity.intent(context, request.query), from)
        is YomitanPageRequest.External -> start(Intent(Intent.ACTION_VIEW, Uri.parse(request.url)), null)
        is YomitanPageRequest.Settings -> start(YomitanSettingsActivity.pageIntent(context, request.url), from)
        is YomitanPageRequest.EnginePage -> start(YomitanSettingsActivity.pageIntent(context, request.url), from)
    }

    private fun start(intent: Intent, from: Context?): Boolean = try {
        val screen = from?.activity()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (screen != null) {
            screen.startActivity(intent)
        } else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    /** The activity behind a view's context (the popup's is a wrapper that moves between screens). */
    private fun Context.activity(): Activity? =
        generateSequence(this) { (it as? ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? Activity }
}

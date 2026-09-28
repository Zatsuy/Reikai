package jp.reikai.lookup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import jp.reikai.settings.YomitanSettingsActivity
import jp.reikai.yomitan.YomitanPageOpener
import jp.reikai.yomitan.YomitanPageRequest

/**
 * Where the pages Yomitan opens go: its search page to the dictionary screen (3.4), its settings and
 * its other pages (info, legal) to the Yomitan settings screen (3.5), web links to the browser.
 */
internal class JpPageOpener(private val context: Context) : YomitanPageOpener {

    override fun open(request: YomitanPageRequest): Boolean = when (request) {
        is YomitanPageRequest.Search -> start(DictionaryActivity.intent(context, request.query))
        is YomitanPageRequest.External -> start(Intent(Intent.ACTION_VIEW, Uri.parse(request.url)))
        is YomitanPageRequest.Settings -> start(YomitanSettingsActivity.pageIntent(context, request.url))
        is YomitanPageRequest.EnginePage -> start(YomitanSettingsActivity.pageIntent(context, request.url))
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

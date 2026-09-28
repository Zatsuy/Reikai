package jp.reikai.lookup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import jp.reikai.yomitan.YomitanPageOpener
import jp.reikai.yomitan.YomitanPageRequest

/**
 * Where the pages Yomitan opens go (its search page, its settings, web links). Each case is one line
 * to fill in: Yomitan's settings (`settings.html`) wait for the Japanese settings screen (3.5), and
 * its other pages (info, legal) for an in-app viewer; until then they stay unopened.
 */
internal class JpPageOpener(private val context: Context) : YomitanPageOpener {

    override fun open(request: YomitanPageRequest): Boolean = when (request) {
        is YomitanPageRequest.Search -> start(DictionaryActivity.intent(context, request.query))
        is YomitanPageRequest.External -> start(Intent(Intent.ACTION_VIEW, Uri.parse(request.url)))
        is YomitanPageRequest.Settings -> false
        is YomitanPageRequest.EnginePage -> false
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

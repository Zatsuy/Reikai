package jp.reikai.yomitan.popup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder

/**
 * One lookup for the popup: the text looked up and where it came from, which Yomitan puts on Anki
 * cards (the Sentence field, and `{url}` / `{document-title}` for the source).
 *
 * @property query the text to look up; Yomitan finds the longest word at its start.
 * @property sentence the sentence around it, if known; [offset] is where [query] starts in it.
 * @property dark the page's theme under Yomitan's default "match the page" popup theme; null leaves it
 *   to Yomitan (the device's theme).
 * @property showSentence show [sentence] (or a long [query]) parsed above the results, so each of its
 *   words can be tapped: for text from another app, where the word itself was not picked out.
 */
data class PopupLookup(
    val query: String,
    val sentence: String? = null,
    val offset: Int = 0,
    val documentTitle: String? = null,
    val url: String? = null,
    val dark: Boolean? = null,
    val showSentence: Boolean = false,
)

/** The popup page's addresses and history state for a [PopupLookup] (see `popup-host.js`). */
internal object PopupUrls {

    /**
     * The page before its first lookup, in the page theme [dark] if known; [load] tells this load's
     * answers from an earlier one's. A new address each time, so loading it always loads the page
     * (never only a jump within the page the lookups left it at).
     */
    fun empty(dark: Boolean?, load: Int): String = "popup.html?reikai-load=$load" + when (dark) {
        null -> ""
        true -> "&reikai-theme=dark"
        false -> "&reikai-theme=light"
    }

    /**
     * The page's address for [lookup]: Yomitan's own parameters, as its in-page popup and search
     * page write them (`display.js` `_createSearchParams`): `full` and `offset` carry the sentence,
     * which Anki's Sentence field falls back to when the history state has none.
     */
    fun page(lookup: PopupLookup): String {
        val params = buildList {
            add("type" to "terms")
            add("query" to lookup.query)
            val full = lookup.sentence?.takeIf { it.length > lookup.query.length }
            if (full != null) {
                add("full" to full)
                add("offset" to lookup.offset.coerceIn(0, full.length - lookup.query.length).toString())
            }
            if (lookup.showSentence) add("full-visible" to "true")
            add("wildcards" to "off")
        }
        return "/popup.html?" + params.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    }

    /** Yomitan's history state for [lookup] (`display.js` `HistoryState`). */
    fun state(lookup: PopupLookup): JsonObject = buildJsonObject {
        put("focusEntry", 0)
        lookup.url?.let { put("url", it) }
        lookup.documentTitle?.let { put("documentTitle", it) }
        lookup.dark?.let { put("pageTheme", if (it) "dark" else "light") }
        val sentence = lookup.sentence
        putJsonObject("sentence") {
            put("text", sentence ?: lookup.query)
            put("offset", if (sentence == null) 0 else lookup.offset)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}

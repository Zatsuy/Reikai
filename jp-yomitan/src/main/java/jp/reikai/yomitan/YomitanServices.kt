package jp.reikai.yomitan

/**
 * `chrome.storage.local` for Yomitan (it keeps all its settings there, under "options"). The app
 * backs it with a preference so the settings are in the app's backups and survive a WebView data
 * wipe. Values are JSON texts; calls come on the main thread and must not block on disk.
 */
interface YomitanStorage {
    /** The stored JSON texts of [keys], or of every key when [keys] is null; missing keys are left out. */
    fun get(keys: Collection<String>?): Map<String, String>

    fun set(items: Map<String, String>)

    fun remove(keys: Collection<String>)

    fun clear()

    /** A storage that lives only as long as the process (tests, and a fallback). */
    class InMemory : YomitanStorage {
        private val values = LinkedHashMap<String, String>()
        override fun get(keys: Collection<String>?) = if (keys ==
            null
        ) {
            values.toMap()
        } else {
            values.filterKeys { it in keys }
        }
        override fun set(items: Map<String, String>) = values.putAll(items)
        override fun remove(keys: Collection<String>) = keys.forEach { values.remove(it) }
        override fun clear() = values.clear()
    }
}

/**
 * A page Yomitan wants opened (`chrome.tabs.create`, `chrome.windows.create`,
 * `chrome.runtime.openOptionsPage`). Welcome pages are dropped before they get here.
 */
sealed interface YomitanPageRequest {
    val url: String

    /** Yomitan's settings (`settings.html`, maybe with a `#section`): the "All Yomitan settings" screen (3.5). */
    data class Settings(override val url: String) : YomitanPageRequest

    /** Yomitan's search page, with the text to look up if any: the search screen (3.4). */
    data class Search(override val url: String, val query: String?) : YomitanPageRequest

    /** Another page of Yomitan's (info, legal, permissions, templates...): an in-app WebView. */
    data class EnginePage(override val url: String) : YomitanPageRequest

    /** A web page (a dictionary's home page, Yomitan's docs): the browser. */
    data class External(override val url: String) : YomitanPageRequest

    companion object {
        /** What [url] asks for, or null for pages the app never opens (welcome) and other schemes. */
        fun of(url: String): YomitanPageRequest? {
            if (!YomitanOrigin.owns(url)) {
                return if (url.startsWith("https://") || url.startsWith("http://")) External(url) else null
            }
            val path = url.removePrefix(
                YomitanOrigin.ORIGIN,
            ).substringBefore('#').substringBefore('?').removePrefix("/")
            return when (path) {
                "welcome.html" -> null
                "settings.html" -> Settings(url)
                "search.html" -> Search(url, queryParameter(url, "query"))
                else -> EnginePage(url)
            }
        }

        private fun queryParameter(url: String, name: String): String? = url.substringAfter('?', "")
            .substringBefore('#')
            .split('&')
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
    }
}

/**
 * Opens [YomitanPageRequest]s: the app plugs its screens in here (search 3.4, settings 3.5).
 * Called on the main thread; returns false when the request was not handled.
 */
fun interface YomitanPageOpener {
    fun open(request: YomitanPageRequest): Boolean
}

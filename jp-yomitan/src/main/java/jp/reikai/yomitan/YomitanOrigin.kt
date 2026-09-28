package jp.reikai.yomitan

/**
 * Where the engine lives. Yomitan's pages, IndexedDB (the dictionaries) and localStorage belong to
 * this origin, so it never changes once shipped. `.invalid` can never be a real site (RFC 6761), and
 * the host is Reikai JP's own, never upstream's `appassets.androidplatform.net`, which upstream's
 * WebView reader uses for chapter images.
 */
object YomitanOrigin {
    const val HOST = "yomitan.reikai.invalid"
    const val ORIGIN = "https://$HOST"

    /** Reikai JP's own files on the origin (the stand-in's workers); Yomitan's are at the root. */
    const val FORK_PREFIX = "/__reikai/"

    /** The name of the web message listener the stand-in talks to. */
    internal const val HUB_NAME = "reikaiHub"

    /** The URL of a Yomitan page, e.g. `url("search.html?query=猫")`. */
    fun url(path: String): String = "$ORIGIN/${path.removePrefix("/")}"

    /** Whether [url] is on the engine origin (scheme, host and default port). */
    fun owns(url: String): Boolean = url == ORIGIN || url.startsWith("$ORIGIN/")
}

/** What a WebView hosting Yomitan is for; the engine decides what its documents may do from it. */
enum class PageKind(internal val jsName: String) {
    /** The engine's own hidden WebView running Yomitan's backend (`background.html`). */
    ENGINE("engine"),

    /** Yomitan's settings (`settings.html`): full access, like the backend. */
    SETTINGS("settings"),

    /** Yomitan's search page (`search.html`): full access, like the backend. */
    SEARCH("search"),

    /** The app's lookup popup (roadmap 3.4) showing Yomitan's results page: messaging only. */
    POPUP("popup"),

    /** A reader's chapter pages with Yomitan's content script (Phase 4): content-script rights only. */
    READER("reader"),
}

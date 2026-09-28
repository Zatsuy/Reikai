package jp.reikai.reader.page

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import reikai.novel.font.isSupportedFontFile
import reikai.presentation.reader.web.cssFontName
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * The document the Japanese reader shows, to the page contract in
 * `docs/fork/research/phase4-design-2026-09.md`: one chapter, served by [JpPageViewport] at
 * `https://chapter.reikai.invalid/chapter/<documentId>` (RFC 6761, never a real site) with the page
 * script and stylesheet from `assets/jp-reader/`, and a Content-Security-Policy under which only that
 * script and Yomitan's modules run (ruling 2): the chapter's own markup never runs anything.
 */
object JpPageDocument {

    const val HOST = "chapter.reikai.invalid"
    const val ORIGIN = "https://$HOST"
    const val YOMITAN_ORIGIN = "https://yomitan.reikai.invalid"

    const val CHAPTER_PATH = "/chapter/"
    const val ASSET_PATH = "/jp-reader/"
    const val FONT_PATH = "/font/"

    /** Yomitan's scanner for the page (4.3), a module on Yomitan's origin, loaded while lookup is on. */
    const val LOOKUP_SCRIPT = "$YOMITAN_ORIGIN/__reikai/reader-scan.js"

    /** Where upstream's [reikai.presentation.reader.web.NovelWebImages] routes a chapter's online pictures. */
    private const val IMAGE_ORIGIN = "https://appassets.androidplatform.net"

    /**
     * Scripts only from this origin (the page script) and Yomitan's (its content scripts, 4.3); no inline
     * or evaluated script. Styles inline too, since a chapter's own markup may carry them. Pictures from
     * here, inline (downloaded chapters) and upstream's picture route; fonts from here.
     */
    val CONTENT_SECURITY_POLICY: String = listOf(
        "default-src 'none'",
        "script-src 'self' $YOMITAN_ORIGIN",
        "style-src 'self' 'unsafe-inline' $YOMITAN_ORIGIN",
        "img-src 'self' data: blob: $IMAGE_ORIGIN $YOMITAN_ORIGIN",
        "font-src 'self' data: $YOMITAN_ORIGIN",
        "connect-src 'self' $YOMITAN_ORIGIN",
        "media-src 'self' data: blob:",
        "base-uri 'none'",
        "form-action 'none'",
        "object-src 'none'",
        "frame-ancestors 'none'",
    ).joinToString("; ")

    fun url(documentId: String): String = "$ORIGIN$CHAPTER_PATH$documentId"

    /** The contract's `jp-init` object; [documentId] is the one every message of the page names. */
    fun init(
        chapterId: Long,
        documentId: String,
        charOffset: Int?,
        fraction: Double,
        settings: JsonObject,
    ): JsonObject = buildJsonObject {
        put("chapterId", chapterId)
        put("doc", documentId)
        if (charOffset != null) put("charOffset", charOffset) else put("charOffset", JsonNull)
        put("fraction", fraction.coerceIn(0.0, 1.0))
        put("settings", settings)
    }

    /**
     * The chapter's markup made safe to place between the heading and the page script: parsed and
     * written back balanced (a stray end tag or an unclosed element would otherwise swallow the page
     * script that follows it), with every script and scripting attribute gone whatever the reader's
     * "keep embedded scripts" setting says, since the Japanese reader never runs a chapter's code.
     * A link relative to the chapter's page is made absolute against [baseUrl] (its web address), as its
     * pictures are, rather than resolving against this reader's private origin, which is no address
     * anywhere; a jump within the chapter (`#…`) stays as it is.
     * Off the main thread: it is proportional to the chapter.
     */
    fun cleanChapter(html: String, baseUrl: String? = null): String {
        val document = runCatching { Jsoup.parseBodyFragment(html, baseUrl.orEmpty()) }.getOrNull()
            ?: return "<pre>" + escapeHtml(html) + "</pre>"
        document.outputSettings().prettyPrint(false)
        document.select(DROPPED).remove()
        document.select("*").forEach { element ->
            element.attributes().map { it.key }
                .filter { it.startsWith("on", ignoreCase = true) }
                .forEach(element::removeAttr)
        }
        if (baseUrl != null) {
            document.select("a[href]").forEach { link ->
                val href = link.attr("href").trim()
                if (href.isEmpty() || href.startsWith("#")) return@forEach
                val absolute = link.absUrl("href")
                if (absolute.startsWith("http://") || absolute.startsWith("https://")) link.attr("href", absolute)
            }
        }
        return document.body().html()
    }

    private const val DROPPED =
        "script, noscript, iframe, frame, frameset, object, embed, applet, base, meta, link, form, " +
            "noembed, noframes, template, portal"

    /**
     * The whole document. [look] and [options] give the first paint its colours, font and classes
     * before the page script runs; the script reads everything from [init]. With [lookup], Yomitan's
     * scanner follows the page script ([LOOKUP_SCRIPT]). A null [title] (upstream's "hide chapter title")
     * leaves the heading out.
     */
    fun build(
        init: JsonObject,
        options: JpPageOptions,
        look: JpPageLook,
        title: String?,
        chapterHtml: String,
        fontFiles: List<String>,
        lookup: Boolean = false,
        /** The chapter's language: Japanese, or the one a translation (4.5) is in. */
        language: String = "ja",
    ): String = buildString(chapterHtml.length + 4096) {
        val classes = listOf(
            if (options.vertical) "jp-vertical" else "jp-horizontal",
            if (options.paged) "jp-paged" else "jp-scroll",
            "jp-furi-${options.furigana}",
        ).joinToString(" ")
        appendLine("<!DOCTYPE html>")
        append("<html lang=\"").append(escapeHtml(language)).append("\" class=\"").append(classes).append("\" style=\"")
            .append(escapeHtml(firstPaintStyle(options, look))).appendLine("\">")
        appendLine("<head>")
        appendLine("<meta charset=\"utf-8\">")
        appendLine(
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=1, " +
                "maximum-scale=1, user-scalable=no\">",
        )
        append("<link rel=\"stylesheet\" href=\"").append(ASSET_PATH).appendLine("jp-reader.css\">")
        append("<style id=\"jp-font-face\">").append(fontFaces(fontFiles)).appendLine("</style>")
        append("<script type=\"application/json\" id=\"jp-init\">").append(scriptSafe(init.toString()))
            .appendLine("</script>")
        appendLine("</head>")
        appendLine("<body>")
        append("<main id=\"jp-chapter\">")
        if (title != null) append("<h1 class=\"jp-title\">").append(escapeHtml(title)).append("</h1>")
        append(chapterHtml)
        appendLine("</main>")
        append("<script src=\"").append(ASSET_PATH).appendLine("jp-reader.js\"></script>")
        if (lookup) append("<script type=\"module\" src=\"").append(LOOKUP_SCRIPT).appendLine("\"></script>")
        appendLine("</body>")
        append("</html>")
    }

    /** Custom properties and the page colours for the first paint. Every value is a checked colour, a
     *  number or a font list built from constants and filtered names. */
    private fun firstPaintStyle(options: JpPageOptions, look: JpPageLook): String {
        val settings = JpPageSettings
        val background = settings.cssColor(look.background, reikai.presentation.reader.readerDarkPreset.background)
        val text = settings.cssColor(look.text, reikai.presentation.reader.readerDarkPreset.textColor)
        return buildString {
            append("background-color:").append(background).append(';')
            append("color:").append(text).append(';')
            append("--jp-background:").append(background).append(';')
            append("--jp-text:").append(text).append(';')
            append("--jp-hint:").append(settings.hintColor(text)).append(';')
            append("--jp-font-family:").append(settings.fontFamily(options.font)).append(';')
            append("--jp-font-size:").append(look.fontSize).append("px;")
            append("--jp-line-height:").append(String.format(Locale.ROOT, "%.2f", look.lineHeight)).append(';')
        }
    }

    /** A face for each font added through the reader's font manager, served from this origin by name
     *  rather than inlined: a face is fetched only when the page uses it. */
    fun fontFaces(fontFiles: List<String>): String = fontFiles
        .filter(::isSupportedFontFile)
        .mapNotNull { file ->
            val family = cssFontName(file).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            "@font-face { font-family: '$family'; src: url('$FONT_PATH${encodePathSegment(
                file,
            )}'); font-display: block; }"
        }
        .joinToString("\n")

    /** A file name as one URL path segment: only characters that need no quoting in CSS or HTML remain. */
    fun encodePathSegment(name: String): String = URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")

    fun decodePathSegment(segment: String): String? = runCatching {
        URLDecoder.decode(segment, Charsets.UTF_8)
    }.getOrNull()

    /** JSON placed in a `<script>` data block: no `<` may appear, or `</script>` would end the block. */
    fun scriptSafe(json: String): String = json.replace("<", "\\u003c")

    fun escapeHtml(text: String): String = buildString(text.length + 16) {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}

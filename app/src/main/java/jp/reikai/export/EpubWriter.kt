/*
 * Reikai JP: EPUB export (roadmap 4.5, phase 4 ruling 16). GPL-3.0-or-later, except the layout of the
 * book (the stored `mimetype` first, the container, the package document, the navigation document and
 * NCX, the picture types by their first bytes), adapted from Tsundoku
 * (https://github.com/tsundoku-otaku/tsundoku),
 * core/archive/src/main/kotlin/mihon/core/archive/EpubWriter.kt:
 *
 * Copyright 2015 Javier Tomás
 * Copyright 2024 Mihon Open Source Project
 * Copyright the Tsundoku contributors
 * Licensed under the Apache License, Version 2.0 (LICENSES/Apache-2.0.txt).
 *
 * Modified for Reikai JP: written as a stream, a chapter at a time; chapters as well-formed XHTML (void
 * elements closed, no named entities, names XML cannot hold dropped); the book's own language on every
 * page instead of `en`; a cover page; an identifier given by the caller, the same on every export of a
 * novel; `data:` pictures written out as files, each once; for Japanese, vertical writing and a
 * right-to-left page order; no custom CSS or scripts.
 */
package jp.reikai.export

import eu.kanade.tachiyomi.util.lang.Hash
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.select.NodeVisitor
import java.io.Closeable
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes an EPUB 3 book (with an NCX for EPUB 2 readers) to [output] as it goes: [cover], then each
 * [chapter] in reading order, then [finish] for the package and navigation documents. Only what a
 * chapter needs is held between calls, so a long novel never sits in memory whole.
 *
 * [convertImage] turns a picture in a format EPUB readers need not show (AVIF, BMP) into JPEG or PNG
 * bytes, or gives null to leave it out.
 */
class EpubWriter(
    output: OutputStream,
    private val book: Book,
    private val convertImage: (ByteArray) -> ByteArray? = { null },
) : Closeable {

    data class Book(
        /** The same on every export of one novel, so a reader app keeps its place ([identifierFor]). */
        val identifier: String,
        val title: String,
        /** BCP 47, as `dc:language` and every page's `lang`. */
        val language: String,
        val authors: List<String> = emptyList(),
        val description: String? = null,
        val subjects: List<String> = emptyList(),
        val modified: Instant = Instant.now(),
    ) {
        /** Japanese is set vertically, its pages turned right to left, as printed. */
        val vertical: Boolean get() = language.substringBefore('-').equals("ja", ignoreCase = true)
    }

    private class Item(val id: String, val href: String, val mediaType: String, val title: String = "")

    private val zip = ZipOutputStream(output)
    private val language = xmlEscape(book.language)
    private val images = LinkedHashMap<String, Item>()
    private val chapters = mutableListOf<Item>()
    private var coverImage: Item? = null
    private var finished = false

    /** Chapters written so far. */
    val chapterCount: Int get() = chapters.size

    init {
        writeMimetype()
        writeEntry(
            "META-INF/container.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """.trimIndent(),
        )
        writeEntry("OEBPS/$STYLESHEET", stylesheet(book.vertical))
    }

    /** The cover picture and a page showing it, first in the book. False when the bytes are no picture. */
    fun cover(bytes: ByteArray): Boolean {
        check(coverImage == null && chapters.isEmpty()) { "The cover goes first, once" }
        val (data, type) = pictureOf(bytes) ?: return false
        val item = Item("cover-image", "images/cover.${type.extension}", type.mediaType)
        writeEntry("OEBPS/${item.href}", data)
        coverImage = item
        writeEntry(
            "OEBPS/$COVER_PAGE",
            page(
                title = book.title,
                htmlClass = "cover-page",
                body = """<section class="cover" epub:type="cover"><img src="${item.href}" alt="${xmlEscape(
                    book.title,
                )}"/></section>""",
            ),
        )
        return true
    }

    /**
     * A chapter from HTML: made well-formed XHTML, its `data:` pictures written out as files, headed by
     * [title] unless it opens with a heading of its own (Kakuyomu's part and episode titles), which
     * would otherwise show the title twice.
     */
    fun chapter(title: String, html: String) {
        check(!finished) { "The book is finished" }
        val number = chapters.size + 1
        val item = Item("c$number", "chapter${number.toString().padStart(4, '0')}.xhtml", XHTML, title)
        val body = xhtmlBody(html)
        val heading = if (opensWithHeading(body)) "" else """<h1 class="chapter-title">${xmlEscape(title)}</h1>"""
        writeEntry(
            "OEBPS/${item.href}",
            page(title, """<section class="chapter" epub:type="chapter">$heading${body.html()}</section>"""),
        )
        chapters += item
    }

    /** The navigation documents and the package document. The book is complete after this. */
    fun finish() {
        check(!finished) { "The book is finished" }
        check(chapters.isNotEmpty()) { "A book needs a chapter" }
        finished = true
        writeEntry("OEBPS/nav.xhtml", navigation())
        writeEntry("OEBPS/toc.ncx", ncx())
        writeEntry("OEBPS/content.opf", packageDocument())
        zip.finish()
    }

    override fun close() = zip.close()

    // Chapters

    /**
     * The body of [html] as XHTML: parsed as HTML, what a book cannot hold dropped (scripts, styles,
     * embedded frames and players, pictures that are not in the file), names XML cannot hold dropped,
     * links kept only to the web or within the page, and serialised as XML (void elements closed, no
     * named entities, characters XML forbids left out).
     */
    private fun xhtmlBody(html: String): Element {
        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
            .prettyPrint(false)
        val body = document.body()
        body.select(DROPPED).remove()
        body.select("picture").forEach { picture ->
            picture.select("source").remove()
            picture.unwrap()
        }
        body.select("img").forEach(::placePicture)
        body.allElements.filter { it !== body }.forEach(::keepXmlNames)
        removeComments(body)
        return body
    }

    /** Whether the first text of [body] is a heading's. */
    private fun opensWithHeading(body: Element): Boolean {
        val heading = body.selectFirst(HEADINGS)?.text()?.takeIf { it.isNotBlank() } ?: return false
        return body.text().startsWith(heading)
    }

    private fun placePicture(img: Element) {
        val href = img.attr("src").takeIf { it.startsWith("data:", ignoreCase = true) }?.let(::dataPicture)
        if (href == null) {
            // A picture on the web would need the network while reading, and a relative one points nowhere.
            img.remove()
            return
        }
        img.removeAttr("srcset").removeAttr("sizes").attr("src", href)
        if (!img.hasAttr("alt")) img.attr("alt", "")
    }

    /** The file a `data:` URI's picture is written to, written once however often it appears. */
    private fun dataPicture(uri: String): String? {
        val comma = uri.indexOf(',')
        if (comma < 0 || !uri.substring(0, comma).endsWith(";base64", ignoreCase = true)) return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(uri.substring(comma + 1)) }.getOrNull()
            ?: return null
        val hash = Hash.sha256(bytes).take(HASH_LENGTH)
        images[hash]?.let { return it.href }
        val (data, type) = pictureOf(bytes) ?: return null
        val item = Item("img-$hash", "images/$hash.${type.extension}", type.mediaType)
        writeEntry("OEBPS/${item.href}", data)
        images[hash] = item
        return item.href
    }

    private fun pictureOf(bytes: ByteArray): Pair<ByteArray, PictureType>? =
        PictureType.of(bytes)?.let { bytes to it }
            ?: convertImage(bytes)?.let { converted -> PictureType.of(converted)?.let { converted to it } }

    private fun keepXmlNames(element: Element) {
        if (':' in element.tagName()) {
            // Word's `o:p` and the like: a prefix no namespace declares.
            element.unwrap()
            return
        }
        val names = element.attributes().asList().map { it.key }
        names.filter { name ->
            name == "xmlns" || name.startsWith("xmlns:") || (':' in name && name !in PREFIXED_KEPT) ||
                name.startsWith("on", ignoreCase = true) || name in RESOURCE_ATTRIBUTES
        }.forEach(element::removeAttr)
        if (element.hasAttr("href") && !keepsLink(element.attr("href"))) element.removeAttr("href")
        if (element.attr("style").contains("url(", ignoreCase = true)) element.removeAttr("style")
    }

    private fun keepsLink(href: String): Boolean {
        val link = href.trim()
        return link.startsWith("#") || LINK_SCHEMES.any { link.startsWith(it, ignoreCase = true) }
    }

    private fun removeComments(root: Element) {
        val comments = mutableListOf<Comment>()
        root.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    if (node is Comment) comments += node
                }

                override fun tail(node: Node, depth: Int) = Unit
            },
        )
        comments.forEach(Comment::remove)
    }

    // Documents

    // Built line by line rather than from an indented template: a title holding a line break would stop
    // trimIndent() trimming, and an XML declaration after any whitespace is not one.
    private fun page(title: String, body: String, htmlClass: String? = null): String {
        val classAttribute = htmlClass?.let { " class=\"$it\"" }.orEmpty()
        return XML_DECLARATION +
            "<!DOCTYPE html>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" " +
            "lang=\"$language\" xml:lang=\"$language\"$classAttribute>\n" +
            "<head>\n" +
            "<meta charset=\"UTF-8\"/>\n" +
            "<title>${xmlEscape(title)}</title>\n" +
            "<link rel=\"stylesheet\" type=\"text/css\" href=\"$STYLESHEET\"/>\n" +
            "</head>\n" +
            "<body>\n" + body + "\n</body>\n</html>\n"
    }

    private fun navigation(): String {
        val words = if (book.vertical) JAPANESE_WORDS else ENGLISH_WORDS
        val toc = chapters.joinToString("\n") { "<li><a href=\"${it.href}\">${xmlEscape(it.title)}</a></li>" }
        val landmarks = buildString {
            if (coverImage != null) append("<li><a epub:type=\"cover\" href=\"$COVER_PAGE\">${words.cover}</a></li>")
            append("<li><a epub:type=\"bodymatter\" href=\"${chapters.first().href}\">${words.start}</a></li>")
        }
        return page(
            title = words.contents,
            body = "<nav epub:type=\"toc\" id=\"toc\"><h1>${words.contents}</h1><ol>\n$toc\n</ol></nav>\n" +
                "<nav epub:type=\"landmarks\" hidden=\"hidden\"><ol>$landmarks</ol></nav>",
        )
    }

    private fun ncx(): String {
        val points = chapters.withIndex().joinToString("\n") { (index, item) ->
            "<navPoint id=\"p${index + 1}\" playOrder=\"${index + 1}\"><navLabel><text>${xmlEscape(item.title)}" +
                "</text></navLabel><content src=\"${item.href}\"/></navPoint>"
        }
        return XML_DECLARATION +
            "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\" xml:lang=\"$language\">\n" +
            "<head>\n" +
            "<meta name=\"dtb:uid\" content=\"${xmlEscape(book.identifier)}\"/>\n" +
            "<meta name=\"dtb:depth\" content=\"1\"/>\n" +
            "<meta name=\"dtb:totalPageCount\" content=\"0\"/>\n" +
            "<meta name=\"dtb:maxPageNumber\" content=\"0\"/>\n" +
            "</head>\n" +
            "<docTitle><text>${xmlEscape(book.title)}</text></docTitle>\n" +
            "<navMap>\n" + points + "\n</navMap>\n</ncx>\n"
    }

    private fun packageDocument(): String {
        val cover = coverImage
        val metadata = buildList {
            add("<dc:identifier id=\"$BOOK_ID\">${xmlEscape(book.identifier)}</dc:identifier>")
            add("<dc:title>${xmlEscape(book.title)}</dc:title>")
            add("<dc:language>$language</dc:language>")
            book.authors.filter { it.isNotBlank() }.forEach { add("<dc:creator>${xmlEscape(it)}</dc:creator>") }
            book.description?.takeIf {
                it.isNotBlank()
            }?.let { add("<dc:description>${xmlEscape(it)}</dc:description>") }
            book.subjects.filter { it.isNotBlank() }.forEach { add("<dc:subject>${xmlEscape(it)}</dc:subject>") }
            val modified = DateTimeFormatter.ISO_INSTANT.format(book.modified.truncatedTo(ChronoUnit.SECONDS))
            add("<meta property=\"dcterms:modified\">$modified</meta>")
            if (cover != null) add("<meta name=\"cover\" content=\"${cover.id}\"/>")
            if (book.vertical) add("<meta name=\"primary-writing-mode\" content=\"vertical-rl\"/>")
        }
        val manifest = buildList {
            add(manifestItem("nav", "nav.xhtml", XHTML, "nav"))
            add(manifestItem("ncx", "toc.ncx", "application/x-dtbncx+xml"))
            add(manifestItem("style", STYLESHEET, "text/css"))
            if (cover != null) {
                add(manifestItem(cover.id, cover.href, cover.mediaType, "cover-image"))
                add(manifestItem("cover", COVER_PAGE, XHTML))
            }
            chapters.forEach { add(manifestItem(it.id, it.href, it.mediaType)) }
            images.values.forEach { add(manifestItem(it.id, it.href, it.mediaType)) }
        }
        val spine = buildList {
            if (cover != null) add("<itemref idref=\"cover\"/>")
            chapters.forEach { add("<itemref idref=\"${it.id}\"/>") }
        }
        val direction = if (book.vertical) " page-progression-direction=\"rtl\"" else ""
        val guide = if (cover !=
            null
        ) {
            "<guide><reference type=\"cover\" title=\"Cover\" href=\"$COVER_PAGE\"/></guide>\n"
        } else {
            ""
        }
        return XML_DECLARATION +
            """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="$BOOK_ID" xml:lang="$language">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
${metadata.joinToString("\n")}
</metadata>
<manifest>
${manifest.joinToString("\n")}
</manifest>
<spine toc="ncx"$direction>
${spine.joinToString("\n")}
</spine>
$guide</package>
"""
    }

    private fun manifestItem(id: String, href: String, mediaType: String, properties: String? = null): String =
        "<item id=\"$id\" href=\"$href\" media-type=\"$mediaType\"" +
            properties?.let { " properties=\"$it\"" }.orEmpty() + "/>"

    // Zip

    /** First and stored, so the file announces itself as an EPUB at a fixed offset. */
    private fun writeMimetype() {
        val content = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = content.size.toLong()
            compressedSize = content.size.toLong()
            crc = CRC32().apply { update(content) }.value
        }
        zip.putNextEntry(entry)
        zip.write(content)
        zip.closeEntry()
    }

    private fun writeEntry(path: String, content: String) = writeEntry(path, content.toByteArray(Charsets.UTF_8))

    private fun writeEntry(path: String, content: ByteArray) {
        zip.putNextEntry(ZipEntry(path).apply { method = ZipEntry.DEFLATED })
        zip.write(content)
        zip.closeEntry()
    }

    internal enum class PictureType(val mediaType: String, val extension: String) {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        GIF("image/gif", "gif"),
        WEBP("image/webp", "webp"),
        ;

        companion object {
            /** By the first bytes, not what a server or a `data:` URI claimed. */
            fun of(bytes: ByteArray): PictureType? {
                fun at(index: Int, vararg expected: Int) =
                    bytes.size >= index + expected.size &&
                        expected.withIndex().all { (i, b) -> bytes[index + i] == b.toByte() }
                return when {
                    at(0, 0xFF, 0xD8, 0xFF) -> JPEG
                    at(0, 0x89, 0x50, 0x4E, 0x47) -> PNG
                    at(0, 0x47, 0x49, 0x46, 0x38) -> GIF
                    at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> WEBP
                    else -> null
                }
            }
        }
    }

    private class Words(val contents: String, val cover: String, val start: String)

    companion object {
        const val MEDIA_TYPE = "application/epub+zip"

        private const val XHTML = "application/xhtml+xml"
        private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        private const val STYLESHEET = "styles/book.css"
        private const val COVER_PAGE = "cover.xhtml"
        private const val BOOK_ID = "book-id"
        private const val HEADINGS = "h1, h2, h3, h4, h5, h6"
        private const val HASH_LENGTH = 16

        private val JAPANESE_WORDS = Words(contents = "目次", cover = "表紙", start = "本文")
        private val ENGLISH_WORDS = Words(contents = "Contents", cover = "Cover", start = "Start")

        /** What a book cannot hold: code, styles it would apply over the book's, players, frames, foreign markup. */
        private const val DROPPED =
            "script, noscript, style, link, meta, base, template, iframe, frame, frameset, object, embed, " +
                "applet, video, audio, track, canvas, svg, math, form"

        /** Prefixed names whose prefix every page declares. */
        private val PREFIXED_KEPT = setOf("xml:lang", "xml:space", "epub:type")

        /** Attributes that fetch something, which a book has no network for. */
        private val RESOURCE_ATTRIBUTES =
            setOf("srcset", "sizes", "poster", "background", "data", "formaction", "action")

        private val LINK_SCHEMES = listOf("http://", "https://", "mailto:")

        /** Characters XML 1.0 cannot hold at all. */
        private val NOT_XML = Regex("[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD\\x{10000}-\\x{10FFFF}]")

        /** A `urn:uuid` from the novel's source and address, the same on every export of it. */
        fun identifierFor(source: String, url: String): String =
            "urn:uuid:" + UUID.nameUUIDFromBytes("reikai-jp:epub:$source:$url".toByteArray(Charsets.UTF_8))

        internal fun xmlEscape(text: String): String = text.replace(NOT_XML, "")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        /**
         * Vertical for Japanese: `writing-mode` with the `-epub-` and `-webkit-` names older reading
         * systems know it by. Paragraph spacing stays at zero there, since Japanese web novels space
         * their text with blank lines of their own.
         */
        private fun stylesheet(vertical: Boolean): String = buildString {
            appendLine("@charset \"UTF-8\";")
            appendLine("html, body { margin: 0; padding: 0; }")
            appendLine("body { font-family: serif; line-height: 1.8; }")
            appendLine("img { max-width: 100%; max-height: 100%; }")
            appendLine("rt { font-size: 0.5em; }")
            appendLine("h1.chapter-title { font-size: 1.3em; line-height: 1.5; }")
            if (vertical) {
                appendLine(
                    "html { -epub-writing-mode: vertical-rl; -webkit-writing-mode: vertical-rl; " +
                        "writing-mode: vertical-rl; }",
                )
                appendLine("p { margin: 0; }")
                appendLine("h1.chapter-title { margin: 0 0 0 2em; }")
                // Emphasis as Japanese print sets it (Kakuyomu's emphasis dots), as the Japanese reader does.
                appendLine(
                    "em { font-style: normal; -epub-text-emphasis-style: filled sesame; " +
                        "-webkit-text-emphasis-style: filled sesame; text-emphasis-style: filled sesame; }",
                )
            } else {
                appendLine("p { margin: 0 0 0.8em 0; }")
                appendLine("h1.chapter-title { margin: 0 0 2em 0; }")
            }
            appendLine(
                "html.cover-page { -epub-writing-mode: horizontal-tb; -webkit-writing-mode: horizontal-tb; " +
                    "writing-mode: horizontal-tb; }",
            )
            appendLine(".cover { margin: 0; padding: 0; text-align: center; }")
            appendLine(".cover img { max-width: 100%; max-height: 100vh; }")
        }
    }
}

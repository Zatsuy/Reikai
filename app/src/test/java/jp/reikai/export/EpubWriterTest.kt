package jp.reikai.export

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import reikai.domain.novel.model.NovelChapter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class EpubWriterTest {

    @Test
    fun `mimetype is the first entry, stored uncompressed with no extra field`() {
        val bytes = epubBytes()
        // The OCF local header: the signature, the method at 8, the name length at 26, the extra length at 28.
        bytes.copyOfRange(0, 4) shouldBe byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        (bytes[8].toInt() or (bytes[9].toInt() shl 8)) shouldBe ZipEntry.STORED
        (bytes[28].toInt() or (bytes[29].toInt() shl 8)) shouldBe 0
        String(bytes.copyOfRange(30, 38), Charsets.US_ASCII) shouldBe "mimetype"
        String(bytes.copyOfRange(38, 58), Charsets.US_ASCII) shouldBe "application/epub+zip"
    }

    @Test
    fun `the container points at the package document`() {
        val container = xml(epub().getValue("META-INF/container.xml"))
        container.first("rootfile").getAttribute("full-path") shouldBe "OEBPS/content.opf"
        epub().keys shouldContain "OEBPS/content.opf"
    }

    @Test
    fun `the package holds the novel's details and language`() {
        val opf = xml(epub().getValue("OEBPS/content.opf"))
        opf.first("dc:title").textContent shouldBe "テスト小説"
        opf.first("dc:language").textContent shouldBe "ja"
        opf.first("dc:creator").textContent shouldBe "作者"
        opf.first("dc:description").textContent shouldBe "あらすじ & <b>"
        opf.first("dc:subject").textContent shouldBe "ファンタジー"
        opf.all("meta").map { it.getAttribute("property") } shouldContain "dcterms:modified"
    }

    @Test
    fun `a Japanese book turns its pages right to left and is written vertically`() {
        val book = epub()
        xml(book.getValue("OEBPS/content.opf")).first("spine").getAttribute("page-progression-direction") shouldBe "rtl"
        String(book.getValue("OEBPS/styles/book.css")) shouldContain "writing-mode: vertical-rl"
    }

    @Test
    fun `a book in another language keeps the default direction and horizontal text`() {
        val book = epub(book = book(language = "en"))
        xml(book.getValue("OEBPS/content.opf")).first("spine").hasAttribute("page-progression-direction") shouldBe false
        String(book.getValue("OEBPS/styles/book.css")) shouldNotContain "vertical-rl"
        xml(book.getValue("OEBPS/chapter0001.xhtml")).documentElement.getAttribute("lang") shouldBe "en"
    }

    @Test
    fun `the cover is the cover image and the first page`() {
        val book = epub()
        val opf = xml(book.getValue("OEBPS/content.opf"))
        val coverItem = opf.all("item").single { it.getAttribute("properties") == "cover-image" }
        book.getValue("OEBPS/" + coverItem.getAttribute("href")) shouldBe JPEG
        opf.all("itemref").map { it.getAttribute("idref") } shouldContainExactly listOf("cover", "c1", "c2")
        opf.all("item").single { it.getAttribute("id") == "cover" }.getAttribute("href") shouldBe "cover.xhtml"
        xml(book.getValue("OEBPS/cover.xhtml")).first("img").getAttribute("src") shouldBe coverItem.getAttribute("href")
    }

    @Test
    fun `a book without a cover starts at its first chapter`() {
        val opf = xml(epub(cover = null).getValue("OEBPS/content.opf"))
        opf.all("itemref").map { it.getAttribute("idref") } shouldContainExactly listOf("c1", "c2")
        opf.all("item").filter { it.getAttribute("properties") == "cover-image" } shouldHaveSize 0
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "OEBPS/chapter0001.xhtml", "OEBPS/chapter0002.xhtml", "OEBPS/cover.xhtml", "OEBPS/nav.xhtml",
            "OEBPS/toc.ncx", "OEBPS/content.opf", "META-INF/container.xml",
        ],
    )
    fun `every document parses as strict namespace-aware XML`(name: String) {
        xml(epub().getValue(name)).documentElement.shouldNotBeNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["OEBPS/chapter0001.xhtml", "OEBPS/cover.xhtml", "OEBPS/nav.xhtml"])
    fun `every page carries the book's language as lang and xml lang`(name: String) {
        val root = xml(epub().getValue(name)).documentElement
        root.getAttribute("lang") shouldBe "ja"
        root.getAttributeNS(XML_NS, "lang") shouldBe "ja"
    }

    @Test
    fun `a chapter keeps its text and readings and loses what XML or a book cannot hold`() {
        val page = String(epub().getValue("OEBPS/chapter0001.xhtml"))
        val chapter = xml(page.toByteArray())
        chapter.first("h1").textContent shouldBe "第一話"
        chapter.first("rt").textContent shouldBe "はし"
        chapter.documentElement.textContent shouldContain "彼は走"
        chapter.documentElement.textContent shouldContain "ワード"
        chapter.documentElement.textContent shouldContain " ©"
        page shouldNotContain "&nbsp;"
        page shouldNotContain "o:p"
        page shouldNotContain "foo:bar"
        page shouldNotContain "onclick"
        page shouldNotContain "\u0001"
        chapter.all("a").map { it.getAttribute("href") } shouldContainExactly listOf("", "https://kakuyomu.jp/")
    }

    @Test
    fun `a title with markup and a line break is escaped, and the page still starts with its declaration`() {
        val page = String(epub().getValue("OEBPS/chapter0002.xhtml"))
        page shouldStartWith "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        xml(page.toByteArray()).first("h1").textContent shouldBe "第二話 <前編>\n& 後編"
    }

    @Test
    fun `a chapter that opens with its own heading gets no second title`() {
        val out = ByteArrayOutputStream()
        EpubWriter(out, book()).use { writer ->
            writer.chapter("第一部 - 第1話：始まり", "<div><h1>第一部</h1><h2>第1話：始まり</h2></div><p>本文</p>")
            writer.chapter("第2話", "<p>本文<h2>小見出し</h2></p>")
            writer.finish()
        }
        val book = entries(out.toByteArray())
        xml(book.getValue("OEBPS/chapter0001.xhtml")).all("h1").map { it.textContent } shouldContainExactly listOf("第一部")
        xml(book.getValue("OEBPS/chapter0002.xhtml")).first("h1").textContent shouldBe "第2話"
    }

    @Test
    fun `data pictures become files, each written once, and remote pictures are left out`() {
        val book = epub()
        val sources = xml(book.getValue("OEBPS/chapter0001.xhtml")).all("img").map { it.getAttribute("src") }
        sources shouldHaveSize 3
        sources[0] shouldBe sources[1]
        sources[0] shouldStartWith "images/"
        book.getValue("OEBPS/" + sources[0]) shouldBe PNG
        book.getValue("OEBPS/" + sources[2]) shouldBe JPEG
        val opf = xml(book.getValue("OEBPS/content.opf"))
        opf.all("item").single { it.getAttribute("href") == sources[0] }.getAttribute("media-type") shouldBe "image/png"
    }

    @Test
    fun `a picture in a format a book cannot hold goes through the converter`() {
        val book = epub(convert = { JPEG })
        val source = xml(book.getValue("OEBPS/chapter0002.xhtml")).first("img").getAttribute("src")
        book.getValue("OEBPS/$source") shouldBe JPEG
    }

    @Test
    fun `a picture the converter cannot read is left out`() {
        xml(epub(convert = { null }).getValue("OEBPS/chapter0002.xhtml")).all("img") shouldHaveSize 0
    }

    @Test
    fun `navigation lists the chapters in order, in the NCX too`() {
        val book = epub()
        val nav = xml(book.getValue("OEBPS/nav.xhtml"))
        nav.all("nav").first().getAttributeNS(EPUB_NS, "type") shouldBe "toc"
        nav.all("a").take(2).map { it.getAttribute("href") } shouldContainExactly
            listOf("chapter0001.xhtml", "chapter0002.xhtml")
        xml(book.getValue("OEBPS/toc.ncx")).all("content").map { it.getAttribute("src") } shouldContainExactly
            listOf("chapter0001.xhtml", "chapter0002.xhtml")
        xml(book.getValue("OEBPS/content.opf")).first("item").getAttribute("properties") shouldBe "nav"
    }

    @Test
    fun `the identifier is the same on every export of a novel and differs between novels`() {
        val first = EpubWriter.identifierFor("kakuyomu", "/works/1")
        first shouldBe EpubWriter.identifierFor("kakuyomu", "/works/1")
        first shouldNotBe EpubWriter.identifierFor("kakuyomu", "/works/2")
        first shouldNotBe EpubWriter.identifierFor("syosetu", "/works/1")
        first shouldStartWith "urn:uuid:"
        val opf = xml(epub(book = book(identifier = first)).getValue("OEBPS/content.opf"))
        val unique = opf.documentElement.getAttribute("unique-identifier")
        opf.all("dc:identifier").single { it.getAttribute("id") == unique }.textContent shouldBe first
        xml(epub().getValue("OEBPS/toc.ncx")).all("meta").first().getAttribute("content") shouldBe first
    }

    @Test
    fun `chapters not downloaded are skipped and counted, the rest written in order`() = runTest {
        val chapters = listOf(chapter(1, "一話"), chapter(2, "二話"), chapter(3, "三話"), chapter(4, "四話"))
        val downloaded = mapOf(1L to "<p>一</p>", 3L to "<p>三</p>")
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Pair<Int, Int>>()
        val counts = EpubWriter(out, book()).use { writer ->
            writeChapters(
                writer = writer,
                chapters = chapters,
                read = { downloaded[it.id] },
                prepare = { chapter, raw -> "$raw<p>${chapter.url}</p>" },
                onProgress = { done, total -> progress += done to total },
            ).also { writer.finish() }
        }
        counts shouldBe ExportCounts(exported = 2, skipped = 2)
        progress shouldContainExactly listOf(1 to 4, 2 to 4, 3 to 4, 4 to 4)
        val book = entries(out.toByteArray())
        xml(book.getValue("OEBPS/chapter0001.xhtml")).first("h1").textContent shouldBe "一話"
        xml(book.getValue("OEBPS/chapter0002.xhtml")).first("h1").textContent shouldBe "三話"
        xml(book.getValue("OEBPS/chapter0002.xhtml")).documentElement.textContent shouldContain "/c/3"
        book["OEBPS/chapter0003.xhtml"].shouldBeNull()
    }

    private fun book(language: String = "ja", identifier: String = EpubWriter.identifierFor("kakuyomu", "/works/1")) =
        EpubWriter.Book(
            identifier = identifier,
            title = "テスト小説",
            language = language,
            authors = listOf("作者"),
            description = "あらすじ & <b>",
            subjects = listOf("ファンタジー"),
            modified = Instant.parse("2026-09-28T10:00:00Z"),
        )

    private fun epubBytes(
        book: EpubWriter.Book = book(),
        cover: ByteArray? = JPEG,
        convert: (ByteArray) -> ByteArray? = { null },
    ): ByteArray {
        val out = ByteArrayOutputStream()
        EpubWriter(out, book, convert).use { writer ->
            cover?.let(writer::cover)
            writer.chapter("第一話", CHAPTER_ONE)
            writer.chapter("第二話 <前編>\n& 後編", CHAPTER_TWO)
            writer.finish()
        }
        return out.toByteArray()
    }

    private fun epub(
        book: EpubWriter.Book = book(),
        cover: ByteArray? = JPEG,
        convert: (ByteArray) -> ByteArray? = { null },
    ): Map<String, ByteArray> = entries(epubBytes(book, cover, convert))

    /** The entries in the order they were written. */
    private fun entries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { put(it.name, zip.readBytes()) }
        }
    }

    private fun xml(bytes: ByteArray): Document {
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        builder.setErrorHandler(
            object : ErrorHandler {
                override fun warning(exception: SAXParseException) = throw exception
                override fun error(exception: SAXParseException) = throw exception
                override fun fatalError(exception: SAXParseException) = throw exception
            },
        )
        return builder.parse(ByteArrayInputStream(bytes))
    }

    private fun Document.all(name: String): List<Element> {
        val nodes = getElementsByTagName(name)
        return List(nodes.length) { nodes.item(it) as Element }
    }

    private fun Document.first(name: String): Element = all(name).first()

    private fun chapter(id: Long, name: String) = NovelChapter(
        id = id,
        novelId = 1L,
        url = "/c/$id",
        name = name,
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = id.toDouble(),
        sourceOrder = id,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )

    private companion object {
        const val XML_NS = "http://www.w3.org/XML/1998/namespace"
        const val EPUB_NS = "http://www.idpf.org/2007/ops"

        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 4, 5, 6)
        val BMP = byteArrayOf(0x42, 0x4D, 7, 8, 9)

        fun dataUri(type: String, bytes: ByteArray) = "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)

        /** What a downloaded Japanese web novel chapter can hold once the reader's pipeline has run. */
        val CHAPTER_ONE = """
            <p>　彼は<ruby>走<rp>(</rp><rt>はし</rt><rp>)</rp></ruby>った。&nbsp;&copy;</p>
            <p><br></p>
            <p>絵<img src="${dataUri("image/png", PNG)}"><img src="https://example.com/remote.jpg">
            <img src="${dataUri("image/jpeg", PNG)}" srcset="https://example.com/a.jpg 2x"></p>
            <picture><source srcset="https://example.com/b.webp"><img src="${dataUri("image/jpeg", JPEG)}"></picture>
            <o:p>ワード</o:p><span foo:bar="1" xmlns:foo="urn:x" onclick="x()">スパン</span>
            <a href="/relative/link">相対</a> <a href="https://kakuyomu.jp/">外部</a>
            <hr>閉じない段落<p>もう一つ${"\u0001"}
        """.trimIndent()

        val CHAPTER_TWO = """<p>二話<img src="${dataUri("image/bmp", BMP)}" alt="bmp"></p>"""
    }
}

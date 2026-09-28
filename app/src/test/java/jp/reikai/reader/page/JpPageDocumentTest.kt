package jp.reikai.reader.page

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class JpPageDocumentTest {

    private val look = JpPageLook(
        fontSize = 18,
        lineHeight = 1.75f,
        marginTop = 0,
        marginRight = 0,
        marginBottom = 0,
        marginLeft = 0,
        background = "#ffffff",
        text = "#000000",
        textIndent = 1f,
        justify = false,
    )

    private fun build(
        title: String = "第一話",
        chapterHtml: String = "<p>吾輩は猫である。</p>",
        charOffset: Int? = 42,
        lookup: Boolean = false,
    ) =
        JpPageDocument.build(
            init = JpPageDocument.init(7L, charOffset, 0.5, JsonObject(emptyMap())),
            options = JpPageOptions(vertical = true, paged = true, furigana = "toggle"),
            look = look,
            title = title,
            chapterHtml = chapterHtml,
            fontFiles = listOf("Klee One.ttf", "notes.txt"),
            lookup = lookup,
        )

    @Test
    fun `the document has the contract's shape`() {
        val document = Jsoup.parse(build())
        val html = document.selectFirst("html")!!
        html.attr("lang") shouldBe "ja"
        html.classNames() shouldBe setOf("jp-vertical", "jp-paged", "jp-furi-toggle")
        document.selectFirst("head link[rel=stylesheet]")!!.attr("href") shouldBe "/jp-reader/jp-reader.css"
        document.selectFirst("head style#jp-font-face")!!.data() shouldContain "font-family: 'Klee One'"
        document.selectFirst("main#jp-chapter > h1.jp-title")!!.text() shouldBe "第一話"
        document.selectFirst("main#jp-chapter > p")!!.text() shouldBe "吾輩は猫である。"
        // The page script is the body's last element, after the chapter.
        document.body().children().last()!!.attr("src") shouldBe "/jp-reader/jp-reader.js"
        // No inline script: the policy would refuse it.
        document.select("script:not([src]):not([type=\"application/json\"])").size shouldBe 0
    }

    @Test
    fun `with lookup on, Yomitan's scanner follows the page script as a module`() {
        val scripts = Jsoup.parse(build(lookup = true)).body().children().takeLast(2)
        scripts.map { it.attr("src") } shouldBe
            listOf("/jp-reader/jp-reader.js", "https://yomitan.reikai.invalid/__reikai/reader-scan.js")
        scripts.last().attr("type") shouldBe "module"
        Jsoup.parse(build()).select("script[src*=reader-scan]").size shouldBe 0
    }

    @Test
    fun `jp-init carries the chapter and where it lands`() {
        val init = Json.parseToJsonElement(Jsoup.parse(build()).selectFirst("script#jp-init")!!.data()).jsonObject
        init["chapterId"]!!.jsonPrimitive.content shouldBe "7"
        init["charOffset"]!!.jsonPrimitive.content shouldBe "42"
        init["fraction"]!!.jsonPrimitive.content shouldBe "0.5"
        init["settings"] shouldBe JsonObject(emptyMap())
        val unknown = Json.parseToJsonElement(
            Jsoup.parse(build(charOffset = null)).selectFirst("script#jp-init")!!.data(),
        )
        unknown.jsonObject["charOffset"] shouldBe JsonNull
    }

    @Test
    fun `a title or init value cannot end its element`() {
        val document = build(title = "</h1><script>alert(1)</script>")
        document shouldNotContain "<script>alert(1)"
        JpPageDocument.scriptSafe("""{"title":"</script><b>"}""") shouldNotContain "<"
    }

    @Test
    fun `the chapter's own scripts and handlers are dropped, and its markup is balanced`() {
        val cleaned = JpPageDocument.cleanChapter(
            """<p onclick="steal()">本文<script>steal()</script></p><iframe src="https://x"></iframe></main><div>""",
        )
        cleaned shouldNotContain "script"
        cleaned shouldNotContain "onclick"
        cleaned shouldNotContain "iframe"
        cleaned shouldNotContain "</main>"
        cleaned shouldBe "<p>本文</p><div></div>"
    }

    @Test
    fun `ruby survives cleaning`() {
        JpPageDocument.cleanChapter("<p><ruby>漢字<rt>かんじ</rt></ruby></p>") shouldBe "<p><ruby>漢字<rt>かんじ</rt></ruby></p>"
    }

    @Test
    fun `only the page script and Yomitan's modules may run`() {
        val policy = JpPageDocument.CONTENT_SECURITY_POLICY
        policy shouldContain "default-src 'none'"
        policy shouldContain "script-src 'self' https://yomitan.reikai.invalid;"
        policy shouldNotContain "unsafe-eval"
        policy.substringAfter("script-src").substringBefore(";") shouldNotContain "unsafe-inline"
    }

    @Test
    fun `an added font is served by name from the chapter origin`() {
        JpPageDocument.fontFaces(listOf("My Font's.ttf")) shouldBe
            "@font-face { font-family: 'My Fonts'; src: url('/font/My%20Font%27s.ttf'); font-display: block; }"
        JpPageDocument.decodePathSegment("My%20Font%27s.ttf") shouldBe "My Font's.ttf"
    }
}

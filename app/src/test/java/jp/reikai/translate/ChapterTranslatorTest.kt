package jp.reikai.translate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class ChapterTranslatorTest {

    private fun texts(html: String) = ChapterTranslator.prepare(html).texts

    /** Each text as `T(<text>)`, so a test can see which paragraph went where. */
    private fun translateAll(html: String, language: String = "en"): String {
        val prepared = ChapterTranslator.prepare(html)
        return ChapterTranslator.write(prepared, prepared.texts.map { "T($it)" }, language)
    }

    @Test
    fun `paragraphs come from blocks, headings, list items, quotes and text in divs and spans`() {
        texts(
            """
            <h1>第一話</h1>
            <p>彼は<b>走った</b>。</p>
            <p>　</p>
            <ul><li>一つ目</li><li>二つ目</li></ul>
            <blockquote><p>引用の文</p></blockquote>
            <div>地の文<span>続き</span></div>
            <div><span>スパンだけ</span></div>
            """.trimIndent(),
        ) shouldContainExactly listOf("第一話", "彼は走った。", "一つ目", "二つ目", "引用の文", "地の文続き", "スパンだけ")
    }

    @Test
    fun `readings are dropped and a line break cuts a paragraph`() {
        texts("<div><ruby>漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby>を書く<br>次の行<br><br>その次</div>") shouldContainExactly
            listOf("漢字を書く", "次の行", "その次")
    }

    @Test
    fun `scripts, styles and preformatted text are not translated`() {
        texts("<style>p { color: red }</style><p>本文</p><script>var a = 1</script><pre>code</pre>") shouldContainExactly
            listOf("本文")
    }

    @Test
    fun `the translation goes back where each paragraph was, and pictures stay`() {
        val out = translateAll(
            """<p><img src="a.png">絵の後の文</p><p>文の後の絵<img src="b.png"></p><div>一行目<br>二行目</div>""",
        )
        val body = Jsoup.parseBodyFragment(out).apply { outputSettings().prettyPrint(false) }.body()
        body.select("p")[0].html() shouldBe """<img src="a.png">T(絵の後の文)"""
        body.select("p")[1].html() shouldBe """T(文の後の絵)<img src="b.png">"""
        body.select("div").first()!!.html() shouldBe "T(一行目)<br>T(二行目)"
    }

    @Test
    fun `a picture element keeps its own img, and text straight in the body carries the language`() {
        val out =
            translateAll("""<p>前<a href="x"><picture><source srcset="d.webp"><img src="d.png"></picture></a>後</p>本文""")
        out shouldContain """<picture><source srcset="d.webp"><img src="d.png"></picture>"""
        out shouldContain """<span lang="en">T(本文)</span>"""
    }

    @Test
    fun `a picture inside a link inside the paragraph stays`() {
        val out = translateAll("""<p>前<a href="x"><img src="c.png"></a>後</p>""")
        out shouldContain """<img src="c.png">"""
        out shouldContain "T(前後)"
    }

    @Test
    fun `readings are gone from the translation and translated elements carry the language`() {
        val out = translateAll("<p><ruby>漢字<rt>かんじ</rt></ruby>です</p><p><img src=\"x.png\"></p>", language = "de")
        out shouldNotContain "かんじ"
        out shouldNotContain "<rt>"
        out shouldContain """<p lang="de">T(漢字です)</p>"""
        // A paragraph that is only a picture is left alone.
        out shouldContain """<p><img src="x.png"></p>"""
    }

    @Test
    fun `the translation is marked with its language and a source chapter is not`() {
        val out = translateAll("<p>本文</p>", language = "pt-BR")
        out shouldStartWith "<!--reikai-translation:pt-BR-->"
        ChapterTranslator.languageOf(out) shouldBe "pt-BR"
        ChapterTranslator.languageOf("<p>本文</p>").shouldBeNull()
        ChapterTranslator.languageOf("<!--reikai-translation:\"><script>-->").shouldBeNull()
    }

    @Test
    fun `a blank translation leaves its paragraph as it was`() {
        val prepared = ChapterTranslator.prepare("<p>一</p><p>二</p>")
        ChapterTranslator.write(prepared, listOf("One", " "), "en") shouldContain "<p>二</p>"
    }

    @Test
    fun `a different number of translations is an error`() {
        val prepared = ChapterTranslator.prepare("<p>一</p><p>二</p>")
        shouldThrow<TranslationFailure> { ChapterTranslator.write(prepared, listOf("One"), "en") }
    }

    @Test
    fun `an engine answering a batch with a different number of texts fails the translation`() = runTest {
        val prepared = ChapterTranslator.prepare("<p>一</p><p>二</p><p>三</p>")
        val engine = FakeEngine(maxTexts = 2) { batch -> batch.drop(1) }
        val failure = shouldThrow<TranslationFailure> { ChapterTranslator.translate(prepared, engine, "en") }
        failure.message shouldContain "1 translations for 2 paragraphs"
    }

    @Test
    fun `paragraphs go in batches of the engine's size, in order`() = runTest {
        val prepared = ChapterTranslator.prepare((1..5).joinToString("") { "<p>文です$it</p>" })
        val engine = FakeEngine(maxTexts = 2) { batch -> batch.map { "T($it)" } }
        ChapterTranslator.translate(prepared, engine, "en") shouldContainExactly
            (1..5).map { "T(文です$it)" }
        engine.batches shouldContainExactly
            listOf(listOf("文です1", "文です2"), listOf("文です3", "文です4"), listOf("文です5"))
        prepared.source shouldBe "ja"
    }

    @Test
    fun `batches keep to the character limit, and a long text goes alone`() {
        ChapterTranslator.batches(
            listOf("aaaa", "bb", "cccccccccc", "d"),
            maxTexts = 10,
            maxChars = 6,
        ) shouldContainExactly
            listOf(0..1, 2..2, 3..3)
    }

    @Test
    fun `the same paragraphs in other markup have the same hash, other text another`() {
        val web = ChapterTranslator.prepare("""<div class="rk"><p id="L1">一行</p><p id="L2">二行</p></div>""")
        val native = ChapterTranslator.prepare("<p>一行</p>\n<p>二行</p>")
        web.hash shouldBe native.hash
        ChapterTranslator.prepare("<p>一行</p><p>三行</p>").hash shouldNotBe web.hash
    }

    class FakeEngine(
        override val maxTexts: Int = 100,
        override val maxChars: Int = 10_000,
        override val id: String = "fake",
        private val answer: (List<String>) -> List<String>,
    ) : TranslationEngine {
        override val name = "Fake"
        val batches = ArrayList<List<String>>()
        var calls = 0

        override suspend fun translate(texts: List<String>, source: String, target: String): List<String> {
            calls++
            batches += texts
            return answer(texts)
        }
    }
}

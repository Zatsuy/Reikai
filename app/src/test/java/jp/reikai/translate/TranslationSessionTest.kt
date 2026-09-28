package jp.reikai.translate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Locale

/** The cache on disk and the swap in the reader's chapter loading. */
class TranslationSessionTest {

    @TempDir
    lateinit var dir: File

    private val cache by lazy { TranslationCache(File(dir, "jp_translations")) }
    private val chapter = "<p>彼は<ruby>走<rt>はし</rt></ruby>った。</p><p><img src=\"a.png\"></p><p>雨だ。</p>"
    private val engine = ChapterTranslatorTest.FakeEngine { batch -> batch.map { "T($it)" } }

    private fun session() = TranslationSession(cache::translated)

    @Test
    fun `a translation is saved per chapter, language, engine and text, and used again`() = runTest {
        val key = cache.translate(7L, chapter, engine, "en")
        key shouldBe TranslationKey("en", "fake")
        val hash = ChapterTranslator.prepare(chapter).hash
        val file = File(dir, "jp_translations/7/en-fake-$hash.json")
        file.isFile shouldBe true
        cache.read(7L, key, hash) shouldContainExactly listOf("T(彼は走った。)", "T(雨だ。)")
        cache.translate(7L, chapter, engine, "en")
        engine.calls shouldBe 1
    }

    @Test
    fun `a new text's translation replaces the old one by the same engine and language`() = runTest {
        cache.translate(7L, chapter, engine, "en")
        cache.translate(7L, "<p>新しい文</p>", engine, "en")
        cache.translate(7L, "<p>新しい文</p>", engine, "de")
        File(dir, "jp_translations/7").list()!!.toList() shouldHaveSize 2
        cache.clear()
        File(dir, "jp_translations").exists() shouldBe false
    }

    @Test
    fun `a failed translation saves nothing`() = runTest {
        val failing = ChapterTranslatorTest.FakeEngine { throw TranslationFailure("Fake: HTTP 500") }
        shouldThrow<TranslationFailure> { cache.translate(7L, chapter, failing, "en") }
        File(dir, "jp_translations/7").list().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `a chapter with only pictures has nothing to translate`() = runTest {
        shouldThrow<NothingToTranslate> { cache.translate(7L, "<p><img src=\"a.png\"></p>", engine, "en") }
    }

    @Test
    fun `without a session the reader's loader runs as upstream's`() = runTest {
        var loads = 0
        val out = JpTranslateHook.load(Any(), 7L, false) {
            loads++
            chapter to "https://site"
        }
        out shouldBe (chapter to "https://site")
        loads shouldBe 1
    }

    @Test
    fun `a chapter shown translated loads as its translation, from the kept original, with its base`() = runTest {
        val session = session()
        val key = cache.translate(7L, chapter, engine, "en")
        session.show(7L, key, chapter to "https://site")
        var loads = 0
        val (html, base) = session.load(7L, false) {
            loads++
            chapter to "https://site"
        }
        loads shouldBe 0
        base shouldBe "https://site"
        ChapterTranslator.languageOf(html) shouldBe "en"
        html shouldContain "T(彼は走った。)"
        html shouldContain "<img src=\"a.png\">"
        // Later loads (a reload, a setting) run the loader and still show it translated.
        val again = session.load(7L, false) {
            loads++
            chapter to "https://site"
        }
        loads shouldBe 1
        ChapterTranslator.languageOf(again.first) shouldBe "en"
        // Other chapters are untouched.
        session.load(8L, false) { "<p>別</p>" to null }.first shouldBe "<p>別</p>"
    }

    @Test
    fun `the same text in another reader's markup is shown translated too`() = runTest {
        val session = session()
        session.show(7L, cache.translate(7L, chapter, engine, "en"), null)
        val webForm = "<div class=\"x\">$chapter</div>"
        ChapterTranslator.languageOf(session.load(7L, false) { webForm to null }.first) shouldBe "en"
    }

    @Test
    fun `show original goes back to the kept original without loading it again`() = runTest {
        val session = session()
        session.show(7L, cache.translate(7L, chapter, engine, "en"), chapter to null)
        session.load(7L, false) { error("not loaded") }
        session.hide(7L, reuseOriginal = true)
        session.shown.value shouldBe emptyMap()
        session.load(7L, false) { error("not loaded") }.first shouldBe chapter
    }

    @Test
    fun `reload from source runs the loader even with an original kept`() = runTest {
        val session = session()
        session.show(7L, cache.translate(7L, chapter, engine, "en"), "<p>古い</p>" to null)
        var loads = 0
        val out = session.load(7L, true) {
            loads++
            chapter to null
        }
        loads shouldBe 1
        ChapterTranslator.languageOf(out.first) shouldBe "en"
    }

    @Test
    fun `a chapter whose text changed shows the original and is no longer shown translated`() = runTest {
        val session = session()
        session.show(7L, cache.translate(7L, chapter, engine, "en"), null)
        val changed = "<p>書き直された文</p>"
        session.load(7L, false) { changed to null }.first shouldBe changed
        session.shown.value[7L].shouldBeNull()
    }

    @Test
    fun `the target is the device's language, English on a Japanese device`() {
        TranslationLanguages.target("", Locale.JAPAN) shouldBe "en"
        TranslationLanguages.target("", Locale.GERMANY) shouldBe "de"
        TranslationLanguages.target("", Locale.TRADITIONAL_CHINESE) shouldBe "zh-TW"
        TranslationLanguages.target("", Locale.forLanguageTag("in-ID")) shouldBe "id"
        TranslationLanguages.target("fr", Locale.JAPAN) shouldBe "fr"
        TranslationLanguages.name("zh-TW", Locale.ENGLISH).shouldNotBeNull() shouldContain "Chinese"
    }
}

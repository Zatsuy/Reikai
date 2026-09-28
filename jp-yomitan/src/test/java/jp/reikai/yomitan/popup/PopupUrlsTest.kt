package jp.reikai.yomitan.popup

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.net.URLDecoder

class PopupUrlsTest {

    private fun params(url: String): Map<String, String> = URI("https://x$url").rawQuery.split('&').associate {
        val (key, value) = it.split('=', limit = 2)
        key to URLDecoder.decode(value, Charsets.UTF_8)
    }

    @Test
    fun `a word in its sentence carries the sentence and where the word starts in it`() {
        val lookup = PopupLookup(query = "繋がり", sentence = "さらに、狼面衆とも繋がりがあった。", offset = 9)
        params(PopupUrls.page(lookup)) shouldBe mapOf(
            "type" to "terms",
            "query" to "繋がり",
            "full" to "さらに、狼面衆とも繋がりがあった。",
            "offset" to "9",
            "wildcards" to "off",
        )
    }

    @Test
    fun `a word on its own asks only for the word`() {
        params(PopupUrls.page(PopupLookup(query = "猫"))) shouldBe
            mapOf("type" to "terms", "query" to "猫", "wildcards" to "off")
    }

    @Test
    fun `text from another app is shown parsed so each word can be tapped`() {
        params(PopupUrls.page(PopupLookup(query = "猫が好きです", showSentence = true)))["full-visible"] shouldBe "true"
    }

    @Test
    fun `spaces and symbols survive the address`() {
        val query = "a b&c=d+e"
        params(PopupUrls.page(PopupLookup(query = query)))["query"] shouldBe query
    }

    @Test
    fun `an offset past the sentence is kept inside it`() {
        params(PopupUrls.page(PopupLookup(query = "猫", sentence = "猫が好き", offset = 40)))["offset"] shouldBe "3"
    }

    @Test
    fun `the history state gives Anki the sentence, the source and the page's theme`() {
        val state = PopupUrls.state(
            PopupLookup(
                query = "繋がり",
                sentence = "狼面衆とも繋がりがあった。",
                offset = 5,
                documentTitle = "第30話",
                url = "https://kakuyomu.jp/works/1",
                dark = true,
            ),
        )
        state["documentTitle"]!!.jsonPrimitive.content shouldBe "第30話"
        state["url"]!!.jsonPrimitive.content shouldBe "https://kakuyomu.jp/works/1"
        state["pageTheme"]!!.jsonPrimitive.content shouldBe "dark"
        val sentence = state["sentence"]!!.jsonObject
        sentence["text"]!!.jsonPrimitive.content shouldBe "狼面衆とも繋がりがあった。"
        sentence["offset"]!!.jsonPrimitive.int shouldBe 5
    }

    @Test
    fun `without a sentence the looked-up text is the sentence`() {
        val sentence = PopupUrls.state(PopupLookup(query = "猫が好き"))["sentence"]!!.jsonObject
        sentence["text"]!!.jsonPrimitive.content shouldBe "猫が好き"
        sentence["offset"]!!.jsonPrimitive.int shouldBe 0
    }

    @ParameterizedTest
    @CsvSource("true, popup.html#theme=dark", "false, popup.html#theme=light", ", popup.html")
    fun `the empty page is prepared in the page's theme`(dark: Boolean?, expected: String) {
        PopupUrls.empty(dark) shouldBe expected
    }
}

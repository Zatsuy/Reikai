package jp.reikai.translate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Test
import java.io.IOException

/** Each engine's request and the reading of its answer, against a stand-in server. */
class TranslationEnginesTest {

    private val requests = ArrayList<Request>()
    private val answers = ArrayDeque<Pair<Int, String>>()

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requests += chain.request()
            val (code, body) = answers.removeFirst()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun answer(code: Int, body: String) = answers.addLast(code to body)

    private fun Request.form(): List<Pair<String, String>> {
        val form = body as FormBody
        return (0 until form.size).map { form.name(it) to form.value(it) }
    }

    private fun Request.text(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

    // --- Google -------------------------------------------------------------------------------------

    @Test
    fun `google sends a batch as lines of one text, with no key or token`() = runTest {
        answer(200, """[[["He wrote.\n","彼は書いた。\n",null,null,3],["She said.","彼女は言った。",null,null,3]],null,"ja"]""")
        val out = GoogleTranslateEngine(client).translate(listOf("彼は書いた。", "彼女は言った。"), "ja", "en")
        out shouldContainExactly listOf("He wrote.", "She said.")
        val request = requests.single()
        request.method shouldBe "POST"
        request.url.host shouldBe "translate.googleapis.com"
        request.url.queryParameter("client") shouldBe "gtx"
        request.url.queryParameter("sl") shouldBe "ja"
        request.url.queryParameter("tl") shouldBe "en"
        request.url.queryParameterValues("dt") shouldContainExactly listOf("t")
        request.url.queryParameter("tk").shouldBeNull()
        request.form() shouldContainExactly listOf("q" to "彼は書いた。\n彼女は言った。")
    }

    @Test
    fun `google joining two lines sends each text alone`() = runTest {
        answer(200, """[[["He wrote. She said.","彼は書いた。\n彼女は言った。"]]]""")
        answer(200, """[[["He wrote.","彼は書いた。"]]]""")
        answer(200, """[[["She said.","彼女は言った。"]]]""")
        val out = GoogleTranslateEngine(client).translate(listOf("彼は書いた。", "彼女は言った。"), "auto", "zh-TW")
        out shouldContainExactly listOf("He wrote.", "She said.")
        requests.size shouldBe 3
        requests[0].url.queryParameter("tl") shouldBe "zh-TW"
    }

    @Test
    fun `google answering three lines as two asks again in halves`() = runTest {
        answer(200, """[[["One. Two.\n","一。二。\n"],["Three.","三。"]]]""")
        answer(200, """[[["One.","一。"]]]""")
        answer(200, """[[["Two.\n","二。\n"],["Three.","三。"]]]""")
        GoogleTranslateEngine(client).translate(listOf("一。", "二。", "三。"), "ja", "en") shouldContainExactly
            listOf("One.", "Two.", "Three.")
        requests.size shouldBe 3
    }

    @Test
    fun `google refusing is an error, never the source text`() = runTest {
        answer(429, "<html>Too Many Requests</html>")
        val failure = shouldThrow<TranslationFailure> {
            GoogleTranslateEngine(client).translate(listOf("彼は書いた。"), "ja", "en")
        }
        failure.message shouldBe "Google Translate: HTTP 429"
    }

    @Test
    fun `google answering something else is an error`() = runTest {
        answer(200, "<html>captcha</html>")
        shouldThrow<TranslationFailure> { GoogleTranslateEngine(client).translate(listOf("文"), "ja", "en") }
    }

    @Test
    fun `no connection is an error with the connection's message`() = runTest {
        val offline = OkHttpClient.Builder().addInterceptor { throw IOException("Unable to resolve host") }.build()
        val failure = shouldThrow<TranslationFailure> {
            GoogleTranslateEngine(offline).translate(listOf("文"), "ja", "en")
        }
        failure.message shouldBe "Google Translate: Unable to resolve host"
    }

    // --- DeepL --------------------------------------------------------------------------------------

    @Test
    fun `deepl sends each text as a repeated field, English as EN-US, a free key to the free endpoint`() = runTest {
        answer(200, """{"translations":[{"detected_source_language":"JA","text":"One"},{"text":"Two"}]}""")
        val out = DeepLTranslateEngine(client, "abc:fx").translate(listOf("一", "二"), "ja", "en")
        out shouldContainExactly listOf("One", "Two")
        val request = requests.single()
        request.url.toString() shouldBe "https://api-free.deepl.com/v2/translate"
        request.header("Authorization") shouldBe "DeepL-Auth-Key abc:fx"
        request.form() shouldContainExactly
            listOf("text" to "一", "text" to "二", "target_lang" to "EN-US", "source_lang" to "JA")
    }

    @Test
    fun `deepl's paid endpoint, automatic source and regional targets`() = runTest {
        DeepLTranslateEngine.endpoint("abc") shouldBe "https://api.deepl.com/v2/translate"
        DeepLTranslateEngine.target("pt") shouldBe "PT-BR"
        DeepLTranslateEngine.target("zh-TW") shouldBe "ZH-HANT"
        DeepLTranslateEngine.target("zh-CN") shouldBe "ZH-HANS"
        DeepLTranslateEngine.target("de") shouldBe "DE"
        answer(200, """{"translations":[{"text":"Eins"}]}""")
        DeepLTranslateEngine(client, "abc").translate(listOf("一"), "auto", "de")
        requests.single().form().map { it.first } shouldContainExactly listOf("text", "target_lang")
    }

    @Test
    fun `deepl refusing the key says so with deepl's message`() = runTest {
        answer(403, """{"message":"Wrong endpoint. Use https://api.deepl.com"}""")
        val failure = shouldThrow<TranslationFailure> {
            DeepLTranslateEngine(client, "bad").translate(listOf("一"), "ja", "en")
        }
        failure.message shouldBe "DeepL: HTTP 403: the key was not accepted: Wrong endpoint. Use https://api.deepl.com"
    }

    @Test
    fun `deepl without a key asks for one and sends nothing`() = runTest {
        val failure = shouldThrow<TranslationSetupMissing> {
            DeepLTranslateEngine(client, " ").translate(listOf("一"), "ja", "en")
        }
        failure.what shouldBe TranslationSetupMissing.What.KEY
        requests.size shouldBe 0
    }

    @Test
    fun `deepl answering with fewer texts is an error`() = runTest {
        answer(200, """{"translations":[{"text":"One"}]}""")
        shouldThrow<TranslationFailure> { DeepLTranslateEngine(client, "k").translate(listOf("一", "二"), "ja", "en") }
    }

    // --- OpenAI-compatible --------------------------------------------------------------------------

    private fun chat(content: String) =
        """{"choices":[{"index":0,"message":{"role":"assistant","content":${Json.encodeToString(content)}}}]}"""

    private fun ai(address: String = AiPreset.GEMINI.address, model: String = "gemini-2.5-flash", key: String = "k") =
        OpenAiTranslateEngine(client, address, model, key, name = "Gemini")

    @Test
    fun `an ai service gets the paragraphs as a JSON array and answers one`() = runTest {
        answer(200, chat("```json\n[\"He wrote.\", \"She said.\"]\n```"))
        val out = ai().translate(listOf("彼は書いた。", "彼女は言った。"), "ja", "en")
        out shouldContainExactly listOf("He wrote.", "She said.")
        val request = requests.single()
        request.url.toString() shouldBe "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        request.header("Authorization") shouldBe "Bearer k"
        val body = Json.parseToJsonElement(request.text()).jsonObject
        body["model"]!!.jsonPrimitive.content shouldBe "gemini-2.5-flash"
        body.keys shouldBe setOf("model", "messages")
        val messages = body["messages"]!!.jsonArray
        messages[0].jsonObject["content"]!!.jsonPrimitive.content shouldContain "from Japanese to English"
        Json.parseToJsonElement(messages[1].jsonObject["content"]!!.jsonPrimitive.content).jsonArray
            .map { it.jsonPrimitive.content } shouldContainExactly listOf("彼は書いた。", "彼女は言った。")
    }

    @Test
    fun `an ai answer with the wrong number of paragraphs is asked again in halves`() = runTest {
        answer(200, chat("""["He wrote. She said."]"""))
        answer(200, chat("""["He wrote."]"""))
        answer(200, chat("She said."))
        val out = ai().translate(listOf("彼は書いた。", "彼女は言った。"), "ja", "en")
        out shouldContainExactly listOf("He wrote.", "She said.")
        requests.size shouldBe 3
    }

    @Test
    fun `an ai answer with no text is an error, and one paragraph answered in two parts is joined`() = runTest {
        answer(
            200,
            """{"choices":[{"message":{"role":"assistant","content":null},"finish_reason":"content_filter"}]}""",
        )
        shouldThrow<TranslationFailure> { ai().translate(listOf("文"), "ja", "en") }.message shouldBe
            "Gemini: empty answer"
        answer(200, chat("""["He ran.", "It rained."]"""))
        ai().translate(listOf("彼は走った。雨だった。"), "ja", "en") shouldContainExactly listOf("He ran. It rained.")
    }

    @Test
    fun `an ai service's error message is shown, in OpenAI's form or Gemini's`() = runTest {
        answer(401, """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}""")
        shouldThrow<TranslationFailure> { ai().translate(listOf("文"), "ja", "en") }
            .message shouldBe "Gemini: Incorrect API key provided"
        answer(400, """[{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}]""")
        shouldThrow<TranslationFailure> { ai().translate(listOf("文"), "ja", "en") }
            .message shouldBe "Gemini: API key not valid."
    }

    @Test
    fun `ollama needs no key, and a service without an address or model asks for them`() = runTest {
        answer(200, chat("""["One"]"""))
        ai(address = AiPreset.OLLAMA.address, key = "").translate(listOf("一"), "ja", "en")
        requests.single().header("Authorization").shouldBeNull()
        requests.single().url.toString() shouldBe "http://localhost:11434/v1/chat/completions"
        shouldThrow<TranslationSetupMissing> { ai(address = "").translate(listOf("一"), "ja", "en") }
            .what shouldBe TranslationSetupMissing.What.ADDRESS
        shouldThrow<TranslationSetupMissing> { ai(model = " ").translate(listOf("一"), "ja", "en") }
            .what shouldBe TranslationSetupMissing.What.MODEL
        requests.size shouldBe 1
    }

    @Test
    fun `a model is part of the engine's id, so another model is another translation`() {
        (ai(model = "a").id == ai(model = "b").id) shouldBe false
        ai(model = "a").id shouldBe ai(model = "a").id
        OpenAiTranslateEngine.systemPrompt("auto", "de") shouldContain
            "automatically-detected source language to German"
        OpenAiTranslateEngine.systemPrompt("auto", "de") shouldNotContain "{"
    }
}

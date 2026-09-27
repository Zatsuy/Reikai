package reikai.novel.host

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

/** The bridge hands plugins text decoded by [LnBodyDecoder]: `LnHostBridge.runFetch`. */
class LnHostBridgeCharsetTest {

    private val novel = "吾輩は猫である①"

    private fun bridgeServing(bytes: ByteArray, contentType: String): LnHostBridge {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", contentType)
                    .body(bytes.toResponseBody(contentType.toMediaType()))
                    .build()
            }
            .build()
        return LnHostBridge(preferenceStore = mockk(relaxed = true), client = client)
    }

    private fun bodyOf(json: String): String =
        LnHostBridge.JSON.parseToJsonElement(json).jsonObject.getValue("body").jsonPrimitive.content

    @Test
    fun `a shift_jis page named only in its meta tag reaches the plugin as text`() {
        val page = """<meta charset="Shift_JIS"><p>$novel</p>""".toByteArray(Charset.forName("windows-31j"))

        bodyOf(bridgeServing(page, "text/html").runFetch("https://example.jp/", "{}") {}) shouldBe
            """<meta charset="Shift_JIS"><p>$novel</p>"""
    }

    @Test
    fun `fetchText's encoding argument decodes an unlabelled page`() {
        val page = "吾輩は猫である".toByteArray(Charset.forName("EUC-JP"))

        bodyOf(
            bridgeServing(page, "text/plain").runFetch("https://example.jp/", """{"encoding":"euc-jp"}""") {
            },
        ) shouldBe
            "吾輩は猫である"
    }
}

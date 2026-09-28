package jp.reikai.yomitan

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkBridgeTest {

    @Test
    fun `a GET with a body fails as a request instead of throwing`() {
        val bridge = NetworkBridge({ OkHttpClient() }, LocalServer(), CoroutineScope(UnconfinedTestDispatcher()))
        var result: FetchResult? = null

        bridge.fetch(FetchRequest("https://example.com/", "GET", emptyMap(), ByteArray(0))) { result = it }

        result.shouldBeInstanceOf<FetchResult.Failed>()
    }

    @Test
    fun `an answer within the limit is read whole`() {
        NetworkBridge.readCapped("12345678".toResponseBody(), max = 8).decodeToString() shouldBe "12345678"
    }

    @Test
    fun `an answer that says it is too large is refused`() {
        shouldThrow<IOException> { NetworkBridge.readCapped("123456789".toResponseBody(), max = 8) }
    }

    @Test
    fun `an answer of unknown length is refused once it passes the limit`() {
        val body = Buffer().writeUtf8("123456789").asResponseBody(contentLength = -1)
        shouldThrow<IOException> { NetworkBridge.readCapped(body, max = 8) }
    }
}

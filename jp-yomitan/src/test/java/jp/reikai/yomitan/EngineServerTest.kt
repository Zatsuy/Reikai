package jp.reikai.yomitan

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class EngineServerTest {

    @ParameterizedTest(name = "{0} may be framed by another site: {1}")
    @CsvSource(
        "/popup.html, true",
        "/settings.html, false",
        "/search.html, false",
        "/background.html, false",
        "/popup-preview.html, false",
        "/js/app/popup.js, true",
    )
    fun `only the popup may sit in a frame of another site`(path: String, framable: Boolean) {
        ("Content-Security-Policy" !in EngineServer.assetHeaders(path)) shouldBe framable
    }
}

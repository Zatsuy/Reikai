package jp.reikai.settings

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

class WebViewDataTest {

    @Test
    fun `every site's storage is cleared except the lookup engine's`() {
        WebViewData.originsToClear(
            listOf(
                "https://kakuyomu.jp/",
                "https://yomitan.reikai.invalid/",
                "https://yomitan.reikai.invalid",
                "https://appassets.androidplatform.net",
                "https://sub.yomitan.reikai.invalid/",
            ),
        ).shouldContainExactly(
            "https://kakuyomu.jp/",
            "https://appassets.androidplatform.net",
            "https://sub.yomitan.reikai.invalid/",
        )
    }
}

package jp.reikai.settings

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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

    @Test
    fun `local and session storage and service workers go, the dictionaries' IndexedDB stays`(@TempDir dir: File) {
        val names = listOf(
            "Default/Local Storage",
            "Default/Session Storage",
            "Default/Service Worker",
            "Default/IndexedDB",
            "Default/WebStorage",
            "Default/Cookies",
            "Local Storage",
        )
        names.forEach { File(dir, it).mkdirs() }

        WebViewData.storageDirectories(dir).map { it.relativeTo(dir).path }.shouldContainExactlyInAnyOrder(
            "Default/Local Storage",
            "Default/Session Storage",
            "Default/Service Worker",
            "Local Storage",
        )
    }
}

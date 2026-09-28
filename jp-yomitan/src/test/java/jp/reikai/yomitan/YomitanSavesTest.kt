package jp.reikai.yomitan

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class YomitanSavesTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `another view cannot end a save, which stays its own view's to finish`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val saves = YomitanSaves(dir, CoroutineScope(dispatcher), dispatcher)
        val delivered = mutableListOf<String>()
        fun step(view: Int, step: String, id: Int?, payload: String = ""): Result<String> {
            var answer: Result<String>? = null
            saves.step(view, step, id, payload, { _, download -> delivered.add(download.file.readText()) }) {
                answer = it
            }
            return answer!!
        }

        step(1, "start", null, """{"name":"settings.json","type":"application/json"}""")
        step(1, "data", 1, Base64.getEncoder().encodeToString("{}".toByteArray()))
        val stranger = step(2, "end", 1)
        val owner = step(1, "end", 1)

        (stranger.isFailure to owner.isSuccess to delivered) shouldBe (true to true to listOf("{}"))
    }
}

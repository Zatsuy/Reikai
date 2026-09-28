package jp.reikai.yomitan.audio

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class LocalAudioCopyTest {

    @TempDir
    lateinit var dir: File

    private val target get() = File(dir, "android.db")

    /** What is on disk afterwards: (the copy, the partial file). */
    private fun files() = target.exists() to LocalAudio.partOf(target).exists()

    @Test
    fun `a whole copy replaces the target and leaves no partial file`() = runTest {
        LocalAudio.copyInto(ByteArrayInputStream(ByteArray(10) { it.toByte() }), target, total = 10) {}

        (target.readBytes().size to files()) shouldBe (10 to (true to false))
    }

    @Test
    fun `a copy that ends before the file's size fails and leaves nothing behind`() = runTest {
        shouldThrow<IOException> {
            LocalAudio.copyInto(ByteArrayInputStream(ByteArray(10)), target, total = 20) {}
        }

        files() shouldBe (false to false)
    }

    @Test
    fun `a cancelled copy stops at once and leaves nothing behind`() = runTest {
        var reads = 0
        lateinit var job: Job
        // An endless file (a hundred reads) whose third read comes after the user removed it.
        val input = object : InputStream() {
            override fun read(): Int = error("the copy reads blocks")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                if (reads == 3) job.cancel()
                return if (reads > 100) -1 else minOf(len, 4)
            }
        }
        job = launch { LocalAudio.copyInto(input, target, total = -1) {} }

        job.join()

        (reads to files()) shouldBe (3 to (false to false))
    }
}

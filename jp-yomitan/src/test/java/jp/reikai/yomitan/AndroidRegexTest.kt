package jp.reikai.yomitan

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests run on the JVM, whose regex engine accepts things Android's (ICU) refuses at run time:
 * `(?U)` (UNICODE_CHARACTER_CLASS) failed "Set up cards for Lapis" on the tablet with "Syntax error
 * in regexp pattern near index 3" while every JVM test passed. Fork code spells character classes
 * out instead (`\p{Z}`, `\p{L}`, which both engines know); this keeps it that way.
 */
class AndroidRegexTest {

    private val jvmOnly = listOf(
        Regex("""\(\?[a-zA-Z]*U[a-zA-Z]*[):]"""),
        Regex("""UNICODE_CHARACTER_CLASS"""),
        Regex("""\\p\{java"""),
    )

    @Test
    fun `fork code uses no regex construct only the JVM knows`() {
        val roots = listOf(File("src/main/java"), File("../app/src/main/java/jp"))
        roots.map { it.isDirectory } shouldBe listOf(true, true)
        val found = roots.flatMap { root ->
            root.walk().filter { it.extension == "kt" }.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val code = line.trim()
                    val isComment = code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")
                    if (!isComment && jvmOnly.any { it.containsMatchIn(code) }) "${file.path}:${i + 1}: $code" else null
                }
            }
        }
        found.shouldBeEmpty()
    }
}

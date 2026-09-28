package jp.reikai.reader.page

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class JpReaderChoiceTest {

    @ParameterizedTest(name = "flags {0} -> {1}")
    @CsvSource(
        "0, DEFAULT",
        // Upstream's orientation bits (0x38) and anything else outside 0x300 are not the choice.
        "56, DEFAULT",
        "256, JAPANESE",
        "512, STANDARD",
        // Orientation 0x18 with the Japanese reader.
        "280, JAPANESE",
        // The unused value 3 reads as the default.
        "768, DEFAULT",
    )
    fun `the choice is read from viewer flag bits 0x300 alone`(flags: Long, choice: JpReaderChoice) {
        JpReaderChoice.of(flags) shouldBe choice
    }

    @Test
    fun `writing a choice keeps the orientation and every other bit`() {
        val orientation = 0x28L
        val withJapanese = JpReaderChoice.withChoice(orientation or 0x1000L, JpReaderChoice.JAPANESE)
        withJapanese shouldBe (orientation or 0x1000L or 0x100L)
        JpReaderChoice.withChoice(withJapanese, JpReaderChoice.STANDARD) shouldBe (orientation or 0x1000L or 0x200L)
        JpReaderChoice.withChoice(withJapanese, JpReaderChoice.DEFAULT) shouldBe (orientation or 0x1000L)
    }

    @ParameterizedTest(name = "{0}, source {1}, text Japanese {2} -> {3}")
    @CsvSource(
        nullValues = ["null"],
        value = [
            // The novel's own choice wins over everything.
            "JAPANESE, en, false, true",
            "STANDARD, ja, true, false",
            // A Japanese source reads in the Japanese reader without looking at the text.
            "DEFAULT, ja, null, true",
            "DEFAULT, ja-JP, null, true",
            // A source in another language keeps the standard reader, whatever the text.
            "DEFAULT, en, true, false",
            "DEFAULT, zh, true, false",
            "DEFAULT, pt-BR, true, false",
            // Multi-language, unknown and local sources go by the first chapter's text.
            "DEFAULT, all, true, true",
            "DEFAULT, all, false, false",
            "DEFAULT, null, true, true",
            "DEFAULT, '', false, false",
            "DEFAULT, other, true, true",
            "DEFAULT, Unknown language, true, true",
            // Not known yet: no chapter read so far.
            "DEFAULT, all, null, null",
        ],
    )
    fun `a novel reads in the Japanese reader by its choice, its source's language, or its text`(
        choice: JpReaderChoice,
        language: String?,
        looksJapanese: Boolean?,
        expected: Boolean?,
    ) {
        JpReaderDefault.decide(choice, language) { looksJapanese } shouldBe expected
    }
}

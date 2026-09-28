package jp.reikai.yomitan.text

import io.kotest.matchers.shouldBe
import jp.reikai.yomitan.text.JapaneseText.Sentence
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

class JapaneseTextTest {

    @ParameterizedTest
    @CsvSource(
        "猫, true",
        "ね, true",
        "ネ, true",
        "ｱ, true",
        "ー, true",
        "々, true",
        "ヶ, true",
        "。, false",
        "、, false",
        "「, false",
        "・, false",
        "a, false",
        "１, false",
        "가, false",
    )
    fun `word characters are kanji, kana and the marks inside words`(char: Char, expected: Boolean) {
        JapaneseText.isWordChar(char.code) shouldBe expected
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "昨夜、レストは島流しの刑を恐れて、伯爵家から脱走した。さらに、狼面衆とも繋がりがあったことが判明。 | true",
            "<p>報告では<ruby>狼面衆<rt>ろうめんしゅう</rt></ruby>とも。</p> | true",
            "我们今天去学校，老师说明天考试。他很高兴，因为他已经准备好了。 | false",
            "The report said so. Last night, Rest escaped from the count's house. | false",
            "Chapter one: the cat called タマ. | false",
        ],
    )
    fun `a text is Japanese when kana run through it`(text: String, expected: Boolean) {
        JapaneseText.looksJapanese(text) shouldBe expected
    }

    /** A segmenter over fixed words, standing in for ICU. */
    private fun segmenter(vararg words: String): (Int) -> IntRange? {
        val ranges = buildList {
            var at = 0
            words.forEach {
                add(at until at + it.length)
                at += it.length
            }
        }
        return { offset -> ranges.firstOrNull { offset in it } }
    }

    private fun dictionary(text: String, vararg words: String): (Int) -> Int? = { offset ->
        words.filter { text.startsWith(it, offset) }.maxOfOrNull { it.length }
    }

    private fun select(words: List<String>, tapped: Int, dictionary: List<String>?): String? {
        val text = words.joinToString("")
        val range = JapaneseText.selectWord(
            text,
            tapped,
            segmenter(*words.toTypedArray()),
            dictionary?.let { dictionary(text, *it.toTypedArray()) },
        )
        return range?.let { text.substring(it.first, it.last + 1) }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("selections")
    fun `a long-press selects the Japanese word`(
        @Suppress("UNUSED_PARAMETER") case: String,
        words: List<String>,
        tapped: Int,
        dictionary: List<String>?,
        expected: String?,
    ) {
        select(words, tapped, dictionary) shouldBe expected
    }

    @Test
    fun `a long-press on text that is not Japanese is left to the system`() {
        select(listOf("Rest", "は", "島流し"), 1, listOf("Rest")) shouldBe null
    }

    @Test
    fun `without an ICU word the tapped character is selected`() {
        JapaneseText.selectWord("猫が好き", 1, { null }) shouldBe 1..1
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sentences")
    fun `the sentence around a word is cut as Yomitan cuts it`(
        @Suppress("UNUSED_PARAMETER") case: String,
        text: String,
        word: String,
        expected: Sentence,
    ) {
        val start = text.indexOf(word)
        JapaneseText.sentenceAround(text, start, start + word.length) shouldBe expected
    }

    @Test
    fun `a sentence looks only so far each way`() {
        val text = "あ".repeat(50) + "猫" + "い".repeat(50)
        JapaneseText.sentenceAround(text, 50, 51, extent = 10) shouldBe
            Sentence("あ".repeat(10) + "猫" + "い".repeat(10), 10)
    }

    companion object {
        @JvmStatic
        fun selections() = listOf(
            Arguments.of("without the dictionary, ICU's word", listOf("昨夜", "、", "島流", "し", "の"), 3, null, "島流"),
            Arguments.of(
                "the dictionary adds the okurigana",
                listOf("昨夜", "、", "島流", "し", "の"),
                3,
                listOf("島流し"),
                "島流し",
            ),
            Arguments.of("a lone kanji grows to its word", listOf("も", "繋", "がり", "が"), 1, listOf("繋がり", "繋がる"), "繋がり"),
            Arguments.of(
                "an inflected verb, tapped on its ending",
                listOf("を", "恐れ", "て", "、"),
                3,
                listOf("恐れて", "を"),
                "恐れて",
            ),
            Arguments.of("kana after kana is not joined back", listOf("し", "た", "の", "も"), 2, listOf("たのも", "の"), "の"),
            Arguments.of("the dictionary never shrinks ICU's word", listOf("東京大学", "に"), 1, listOf("東京"), "東京大学"),
            Arguments.of("no dictionary match keeps ICU's word", listOf("伯爵", "家"), 0, emptyList<String>(), "伯爵"),
            Arguments.of(
                "a lone kanji ICU split off a word joins it back",
                listOf("、", "伯", "爵", "家", "から"),
                2,
                listOf("伯爵", "爵", "家"),
                "伯爵",
            ),
            Arguments.of("the word's first kanji grows to it", listOf("、", "伯", "爵", "家"), 1, listOf("伯爵"), "伯爵"),
            Arguments.of(
                "a word ICU knows is not joined to the one before",
                listOf("東京", "大学", "に"),
                2,
                listOf("東京大学", "大学"),
                "大学",
            ),
            Arguments.of("a match stops at punctuation", listOf("話", "だ", "。", "次"), 0, listOf("話だ。次"), "話だ"),
        )

        @JvmStatic
        fun sentences() = listOf(
            Arguments.of(
                "a line of its own",
                "報告ではこうだ──。\n昨夜、レストは島流しの刑を恐れて、伯爵家から脱走した。\nさらに",
                "島流し",
                Sentence("昨夜、レストは島流しの刑を恐れて、伯爵家から脱走した。", 7),
            ),
            Arguments.of(
                "two sentences on a line",
                "巡回兵が明朝、死体を発見。そしてその周辺に横たわる者たちも。",
                "周辺",
                Sentence("そしてその周辺に横たわる者たちも。", 5),
            ),
            Arguments.of(
                "speech inside quotes",
                "彼は言った。「郊外の森で謎の魔物にまとめて殺されたって話だろ？」と。",
                "魔物",
                Sentence("郊外の森で謎の魔物にまとめて殺されたって話だろ？", 7),
            ),
            Arguments.of(
                "a quote closed inside the sentence",
                "「『猫』が好き」",
                "好き",
                Sentence("『猫』が好き", 4),
            ),
            Arguments.of(
                "a terminator inside quotes does not end the sentence",
                "前の文。彼は「本当？嘘！」と言った。",
                "言っ",
                Sentence("彼は「本当？嘘！」と言った。", 10),
            ),
            Arguments.of("terminators run together", "え！？本当に", "え", Sentence("え！？", 0)),
            Arguments.of("an ideographic space is trimmed", "　猫が好き。", "猫", Sentence("猫が好き。", 0)),
        )
    }
}

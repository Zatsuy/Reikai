package jp.reikai.yomitan.text

/**
 * Plain-text rules for Japanese that the reader's selection and the lookup popup share: which
 * characters make up a word, whether a text is Japanese, the sentence around a word and which word
 * a long-press should select. Pure Kotlin (no Android), so it is tested on the JVM.
 */
object JapaneseText {

    /** Kanji, kana and the marks that live inside words (々, ー, 〆, ヶ); not punctuation or the middle dot. */
    fun isWordChar(codePoint: Int): Boolean = when (codePoint) {
        0x3005, 0x3006, 0x3007, 0x30FC, 0xFF70, 0x30F5, 0x30F6 -> true
        0x30FB, 0xFF65 -> false
        else -> when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            -> true
            else -> false
        }
    }

    fun isKana(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> codePoint != 0x30FB && codePoint != 0xFF65
        else -> false
    }

    /**
     * Whether [text] reads as Japanese rather than Chinese or anything else: kana, which Chinese never
     * uses, among its first [limit] characters. Markup in between does not matter.
     */
    fun looksJapanese(text: CharSequence, limit: Int = 20_000): Boolean {
        var kana = 0
        var han = 0
        var i = 0
        val end = minOf(text.length, limit)
        while (i < end) {
            val cp = Character.codePointAt(text, i)
            if (isKana(cp)) {
                kana++
            } else if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                han++
            }
            if (kana >= KANA_ENOUGH) return true
            i += Character.charCount(cp)
        }
        return kana >= KANA_FEW && kana * 5 >= han
    }

    /** The run of word characters around [index], at most [reach] characters each way. */
    fun wordRun(text: CharSequence, index: Int, reach: Int = RUN_REACH): IntRange {
        var start = index
        while (start > 0 && index - start < reach) {
            val cp = Character.codePointBefore(text, start)
            if (!isWordChar(cp)) break
            start -= Character.charCount(cp)
        }
        var end = index
        while (end < text.length && end - index < reach) {
            val cp = Character.codePointAt(text, end)
            if (!isWordChar(cp)) break
            end += Character.charCount(cp)
        }
        return start until end
    }

    /**
     * The word a long-press at [start] should select, or null when the text there is not Japanese
     * (the system's own rules then apply).
     *
     * [segment] is ICU's Japanese word break around an offset (the word Firefox and Android select
     * themselves); [longestMatch], when the dictionary is at hand, is the length of the longest
     * dictionary word (with deinflection) starting at an offset, or null. The dictionary only ever
     * extends ICU's word: to its okurigana and endings ("恐れ" + "て"), or back over a word ICU split
     * ("恐れ|て" tapped on "て"; "伯|爵", a word ICU does not know, tapped on "爵"). It never shrinks
     * it, so the result is never shorter than what Firefox selects.
     */
    fun selectWord(
        text: CharSequence,
        start: Int,
        segment: (Int) -> IntRange?,
        longestMatch: ((Int) -> Int?)? = null,
    ): IntRange? {
        if (start !in text.indices || !isWordChar(Character.codePointAt(text, start))) return null
        val run = wordRun(text, start)
        val icu = segment(start)?.clip(run)?.takeIf { start in it }
            ?: (start until start + Character.charCount(Character.codePointAt(text, start)))
        if (longestMatch == null) return icu
        val own = longestMatch(icu.first)?.let { (icu.first until icu.first + it).clip(run) }
        // Only a word ICU split after a stem with a kanji in it: okurigana (kana tapped right after
        // it), or a lone kanji, which ICU leaves when it does not know the word ("伯|爵"). Kana after
        // kana ("した|の") is left alone, where a match across would be a guess, and so is a word ICU
        // knows ("東京|大学" tapped on "大" stays 大学).
        val split =
            text.allKana(icu) || (Character.codePointCount(text, icu.first, icu.last + 1) == 1 && text.hasKanji(icu))
        val stem = if (icu.first > run.first && split) {
            segment(icu.first - 1)?.clip(run)?.takeIf { text.hasKanji(it) }
        } else {
            null
        }
        val previous = stem?.let { from ->
            longestMatch(from.first)?.let {
                (from.first until from.first + it).clip(run)
            }?.takeIf { it.last >= icu.last }
        }
        return previous ?: own?.takeIf { it.last > icu.last } ?: icu
    }

    private fun CharSequence.allKana(range: IntRange): Boolean = range.all { isKana(this[it].code) }

    private fun CharSequence.hasKanji(range: IntRange): Boolean =
        range.any { Character.UnicodeScript.of(this[it].code) == Character.UnicodeScript.HAN }

    private fun IntRange.clip(run: IntRange): IntRange? {
        val from = maxOf(first, run.first)
        val to = minOf(last, run.last)
        return if (from > to) null else from..to
    }

    /** A sentence and where the word starts in it (Yomitan's `{text, offset}` for Anki's Sentence field). */
    data class Sentence(val text: String, val offset: Int)

    /**
     * The sentence around the word at [start] until [end], the way Yomitan cuts one from a page
     * (`dom/text-source-generator.js` `extractSentence`, GPL-3.0, with its default Japanese
     * terminators): it ends after 。！？.!?．…, stops at a line break and at a quote opened or closed
     * outside it (「」『』), and looks at most [extent] characters each way.
     */
    fun sentenceAround(text: CharSequence, start: Int, end: Int, extent: Int = SENTENCE_EXTENT): Sentence {
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        val lower = maxOf(0, from - extent)
        val upper = minOf(text.length, to + extent)

        var cursorStart = from
        val backQuotes = ArrayDeque<Char>()
        while (cursorStart > lower) {
            val c = text[cursorStart - 1]
            if (c == '\n' || c == '\r') break
            if (backQuotes.isEmpty() && c in TERMINATORS) break
            if (c in OPENING_QUOTES) {
                if (backQuotes.isEmpty()) break
                if (backQuotes.first() == c) {
                    backQuotes.removeFirst()
                    cursorStart--
                    continue
                }
            }
            CLOSING_QUOTES[c]?.let { backQuotes.addFirst(it) }
            cursorStart--
        }

        var cursorEnd = to
        val forwardQuotes = ArrayDeque<Char>()
        while (cursorEnd < upper) {
            val c = text[cursorEnd]
            if (c == '\n' || c == '\r') break
            if (forwardQuotes.isEmpty() && c in TERMINATORS) {
                while (cursorEnd < upper && text[cursorEnd] in TERMINATORS) cursorEnd++
                break
            }
            if (c in CLOSING_QUOTES) {
                if (forwardQuotes.isEmpty()) break
                if (forwardQuotes.first() == c) {
                    forwardQuotes.removeFirst()
                    cursorEnd++
                    continue
                }
            }
            OPENING_QUOTES[c]?.let { forwardQuotes.addFirst(it) }
            cursorEnd++
        }

        while (cursorStart < from && text[cursorStart].isWhitespace()) cursorStart++
        while (cursorEnd > to && text[cursorEnd - 1].isWhitespace()) cursorEnd--
        return Sentence(text.substring(cursorStart, cursorEnd), from - cursorStart)
    }

    private const val KANA_ENOUGH = 30
    private const val KANA_FEW = 3
    private const val RUN_REACH = 32
    private const val SENTENCE_EXTENT = 200
    private const val TERMINATORS = "。！？.!?．…"

    /** Opening quote to its closing one, and the other way round. */
    private val OPENING_QUOTES = mapOf('「' to '」', '『' to '』')
    private val CLOSING_QUOTES = mapOf('」' to '「', '』' to '『')
}

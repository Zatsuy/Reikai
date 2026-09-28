package jp.reikai.reader

import android.app.RemoteAction
import android.icu.text.BreakIterator
import android.icu.util.ULocale
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.textclassifier.SelectionEvent
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import android.view.textclassifier.TextSelection
import androidx.annotation.RequiresApi
import jp.reikai.yomitan.text.JapaneseText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import logcat.logcat

/**
 * The reader's text classifier: what Android's selection asks when a word is long-pressed (which
 * word to select) and when a selection's toolbar is built (its actions).
 *
 * Android's own long-press selects a single character whenever the finger's nearest character
 * boundary is a word boundary (the start of a word's first character or the end of its last), which
 * in Japanese, with no spaces between words, is half of all presses; and ICU's words split kanji from
 * their okurigana ("手|助け"). The system classifier (Samsung's on the owner's tablet) keeps both. This
 * one answers Japanese text itself (see [JapaneseText.selectWord]: ICU's Japanese word around the
 * pressed character, extended by the dictionary when the lookup engine is already running) and hands
 * everything else to the system. When lookup is on, "Look up" leads the toolbar.
 *
 * [pressed] finds the character under the finger in a request's text (Android's selection starts
 * from a boundary, which cannot tell a press on the right half of "り" from one on the left of the
 * "が" after it); null when unknown. [onlyGrow] is for Chromium, which drops a suggestion that does
 * not contain its own selection: the word is then widened to take that character in too.
 */
@RequiresApi(Build.VERSION_CODES.P)
internal class JpTextClassifier(
    private val system: TextClassifier,
    private val hooks: Hooks,
    private val pressed: (text: CharSequence, start: Int, end: Int) -> Int? = { _, _, _ -> null },
    private val onlyGrow: Boolean = false,
) : TextClassifier {

    /** What the classifier needs from the reader; called on the classifier's (background) thread. */
    interface Hooks {
        /**
         * The length of the longest dictionary word at the start of [text], or null. Only while the
         * engine is already running; never starts it.
         */
        suspend fun longestMatch(text: String): Int?

        /** The "Look up" action for [query] in [sentence], or null while lookup is off. */
        fun lookupAction(query: String, sentence: JapaneseText.Sentence): RemoteAction?

        /**
         * "Look up" was pressed: told as the press happens, from the selection's own event, before
         * its action's broadcast (which takes 60-70 ms more to arrive) is even sent.
         */
        fun onLookUpPressed(query: String, sentence: JapaneseText.Sentence)
    }

    /** The lookup the toolbar's first action offers now, if it is "Look up". */
    @Volatile
    private var offered: Pair<String, JapaneseText.Sentence>? = null

    override fun suggestSelection(request: TextSelection.Request): TextSelection {
        val text = request.text
        val deadline = SystemClock.uptimeMillis() + DICTIONARY_BUDGET_MILLIS
        // The engine answers on the main thread, so a call made there could never wait for it.
        val offMain = Looper.myLooper() != Looper.getMainLooper()
        val start = request.startIndex
        val finger = pressed(text, start, request.endIndex) ?: start
        val segment = { offset: Int -> icuWordAt(text, offset) }
        val longestMatch = if (offMain) { offset: Int -> dictionaryMatch(text, offset, deadline) } else null
        val word = JapaneseText.selectWord(text, finger, segment, longestMatch)
            ?: JapaneseText.selectWord(text, start, segment, longestMatch).takeIf { finger != start }
            ?: return system.suggestSelection(request)
        if (onlyGrow) {
            return TextSelection.Builder(
                minOf(word.first, start),
                maxOf(word.last + 1, request.endIndex),
            ).build()
        }
        return TextSelection.Builder(word.first, word.last + 1).build()
    }

    override fun classifyText(request: TextClassification.Request): TextClassification {
        val base = system.classifyText(request)
        val text = request.text
        val start = request.startIndex
        val end = request.endIndex
        val selected = text.subSequence(start, end)
        offered = null
        if (selected.none { JapaneseText.isWordChar(it.code) }) return base
        val sentence = JapaneseText.sentenceAround(text, start, end)
        val action = hooks.lookupAction(selected.toString(), sentence) ?: return base
        offered = selected.toString() to sentence
        return TextClassification.Builder()
            .setText(base.text ?: selected.toString())
            .addAction(action)
            .apply {
                base.actions.forEach(::addAction)
                repeat(base.entityCount) { i ->
                    val entity = base.getEntity(i)
                    setEntityType(entity, base.getConfidenceScore(entity))
                }
                base.id?.let(::setId)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setExtras(base.extras)
            }
            .build()
    }

    override fun generateLinks(request: TextLinks.Request): TextLinks = system.generateLinks(request)

    override fun getMaxGenerateLinksTextLength(): Int = system.maxGenerateLinksTextLength

    override fun onSelectionEvent(event: SelectionEvent) {
        // The toolbar's first action was pressed, which is "Look up" whenever one is offered.
        val lookUp = offered.takeIf { event.eventType == SelectionEvent.ACTION_SMART_SHARE }
        if (lookUp != null) hooks.onLookUpPressed(lookUp.first, lookUp.second)
        system.onSelectionEvent(event)
    }

    /** The dictionary's longest word at [offset], or null past the time budget or without the engine. */
    private fun dictionaryMatch(text: CharSequence, offset: Int, deadline: Long): Int? {
        val left = deadline - SystemClock.uptimeMillis()
        if (left <= 0) return null
        val run = JapaneseText.wordRun(text, offset)
        val query = text.substring(offset, minOf(run.last + 1, offset + SCAN_LENGTH))
        if (query.isEmpty()) return null
        return runCatching { runBlocking { withTimeoutOrNull(left) { hooks.longestMatch(query) } } }
            .onFailure { logcat(LogPriority.WARN) { "Japanese word lookup failed: $it" } }
            .getOrNull()
    }

    private fun icuWordAt(text: CharSequence, offset: Int): IntRange? {
        val words = BreakIterator.getWordInstance(ULocale.JAPANESE)
        words.setText(text.toString())
        val end = words.following(offset)
        if (end == BreakIterator.DONE) return null
        val start = words.previous()
        return if (start == BreakIterator.DONE || start > offset) null else start until end
    }

    private companion object {
        /** How long a long-press waits for the dictionary before it settles for ICU's word. */
        const val DICTIONARY_BUDGET_MILLIS = 150L

        /** Yomitan's default scan length. */
        const val SCAN_LENGTH = 16
    }
}

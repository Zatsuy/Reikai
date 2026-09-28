package jp.reikai.lookup

/** Where the lookup sheet opens so that it leaves the word looked up in view (window pixels). */
internal object SheetPlacement {

    /**
     * Whether a sheet [sheet] pixels tall goes at the top of a window [window] pixels tall, for a word
     * from [wordTop] to [wordBottom]: at the bottom unless it would cover the word there, then on the
     * side farther from the word (which, when the top leaves the word in view, is always the top).
     */
    fun atTop(wordTop: Int, wordBottom: Int, window: Int, sheet: Int): Boolean =
        wordBottom > window - sheet && wordTop + wordBottom > window
}

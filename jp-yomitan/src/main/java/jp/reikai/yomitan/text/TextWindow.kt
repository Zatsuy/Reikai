package jp.reikai.yomitan.text

/**
 * Android's selection hands a text classifier only a window of a view's text (about 120 characters
 * each side of the selection). This finds where a position in the view's own text falls in that window.
 */
object TextWindow {

    /**
     * Where [sourceOffset] of [source] is in [window], a piece of [source] around its selection
     * [start]..[end] (window coordinates), looking only next to that selection; null if the texts do
     * not match there. [reach] characters each side must match.
     */
    fun locate(
        window: CharSequence,
        start: Int,
        end: Int,
        source: CharSequence,
        sourceOffset: Int,
        reach: Int = 16,
    ): Int? =
        (start - 1..end).firstOrNull { candidate ->
            val shift = sourceOffset - candidate
            candidate in window.indices &&
                shift >= 0 &&
                shift + window.length <= source.length &&
                (maxOf(0, candidate - reach) until minOf(window.length, candidate + reach)).all {
                    window[it] ==
                        source[shift + it]
                }
        }
}

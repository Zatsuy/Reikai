package jp.reikai.reader.page

/**
 * Which novel reader a series opens in, kept per novel in `novels.viewer_flags` bits `0x300`
 * (phase 4 ruling 3), beside upstream's orientation bits (`0x38`): 0 follows the default, 1 is the
 * Japanese reader, 2 upstream's standard reader. Backups and migrations already carry the column.
 */
enum class JpReaderChoice(internal val bits: Long) {
    DEFAULT(0L),
    JAPANESE(1L),
    STANDARD(2L),
    ;

    companion object {
        const val MASK = 0x300L
        private const val SHIFT = 8

        /** The choice stored in [viewerFlags]; an unused value (3) reads as the default. */
        fun of(viewerFlags: Long): JpReaderChoice {
            val bits = (viewerFlags and MASK) shr SHIFT
            return entries.firstOrNull { it.bits == bits } ?: DEFAULT
        }

        /** [viewerFlags] with [choice] in place of whatever the bits held, every other bit kept. */
        fun withChoice(viewerFlags: Long, choice: JpReaderChoice): Long =
            (viewerFlags and MASK.inv()) or (choice.bits shl SHIFT)
    }
}

/** How a novel's reader is decided (phase 4 ruling 4, decision D-003). */
object JpReaderDefault {

    /** Language codes that name no one language: upstream's "Multi" ("all") and the like. */
    private val UNSPECIFIC = setOf("all", "multi", "mul", "und", "zxx", "other", "local", "localsourcelang")

    private val LANGUAGE_CODE = Regex("^[a-z]{2,3}([-_][A-Za-z0-9]{2,8})*$")

    /**
     * Whether the Japanese reader opens: the novel's own choice when it made one; otherwise yes for a
     * source whose language is Japanese, no for a source in another language, and for a source whose
     * language is not one language (multi, unknown, local books) whatever [looksJapanese] says of the
     * first chapter. Null when that answer is needed and not known yet (no chapter read so far).
     */
    fun decide(choice: JpReaderChoice, sourceLanguage: String?, looksJapanese: () -> Boolean?): Boolean? =
        when (choice) {
            JpReaderChoice.JAPANESE -> true
            JpReaderChoice.STANDARD -> false
            JpReaderChoice.DEFAULT -> when {
                sourceLanguage.isJapanese() -> true
                isSpecificLanguage(sourceLanguage) -> false
                else -> looksJapanese()
            }
        }

    /** Whether [code] names one language (`en`, `pt-BR`), rather than several, none or an unknown one. */
    fun isSpecificLanguage(code: String?): Boolean {
        val trimmed = code?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.lowercase() in UNSPECIFIC) return false
        return LANGUAGE_CODE.matches(trimmed)
    }

    private fun String?.isJapanese(): Boolean {
        val code = this?.trim()?.lowercase() ?: return false
        return code == "ja" || code.startsWith("ja-") || code.startsWith("ja_")
    }
}

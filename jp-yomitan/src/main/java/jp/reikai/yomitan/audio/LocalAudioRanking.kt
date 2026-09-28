package jp.reikai.yomitan.audio

/**
 * Orders the recordings of a word found in a local audio database (the community `android.db`) the
 * way AnkiConnect Android and Hoshi-Reader do (Hoshi-Reader's `Core/LocalFileServer.swift`,
 * GPL-3.0, github.com/Manhhao/Hoshi-Reader, itself after AnkiConnect Android's
 * `LocalAudioAPIRouting.java`): the word with its reading first, then other words read the same way
 * (named after the word), then the word read another way (named after that reading); within a rank
 * the dictionaries' usual order ([SOURCES]), then by reading. Unlike Hoshi-Reader (iOS plays only
 * mp3), every format a WebView plays is kept.
 */
internal object LocalAudioRanking {

    /** A row of the database's `entries` table. */
    data class Entry(
        val source: String,
        val display: String?,
        val file: String,
        val expression: String,
        val reading: String?,
    )

    /** One recording for Yomitan's custom-json list: its name in the audio menu and its file. */
    data class Recording(val name: String, val source: String, val file: String)

    /** The recordings of [term] read [reading] (katakana is read as hiragana), best first, without repeats. */
    fun rank(entries: List<Entry>, term: String, reading: String): List<Recording> {
        val kana = katakanaToHiragana(reading)
        return entries
            .mapNotNull { entry -> rankOf(entry, term, kana)?.let { entry to it } }
            .sortedWith(
                compareBy<Pair<Entry, Int>> { it.second }
                    .thenBy { SOURCES.indexOf(it.first.source).takeIf { i -> i >= 0 } ?: OTHER_SOURCE }
                    .thenBy(nullsFirst()) { it.first.reading },
            )
            .distinctBy { (entry, _) -> entry.source to entry.file }
            .filter { (entry, _) -> playable(entry.file) }
            .map { (entry, rank) ->
                val matched = when (rank) {
                    1 -> " (${entry.expression})"
                    2 -> " (${entry.reading.orEmpty()})"
                    else -> ""
                }
                Recording(displayName(entry.source, entry.display.orEmpty()) + matched, entry.source, entry.file)
            }
    }

    /** 0: this word and reading; 1: another word with this reading; 2: this word, another reading. */
    private fun rankOf(entry: Entry, term: String, reading: String): Int? = when {
        reading.isEmpty() -> if (entry.expression == term) 0 else null
        entry.expression == term && (entry.reading == null || entry.reading == reading) -> 0
        entry.reading == reading -> 1
        entry.expression == term -> 2
        else -> null
    }

    fun displayName(source: String, display: String): String =
        (DISPLAY_NAMES[source] ?: "$source %s").replace("%s", display).trim()

    fun katakanaToHiragana(text: String): String = buildString(text.length) {
        text.forEach { c -> append(if (c in 'ァ'..'ヶ') c - 0x60 else c) }
    }

    /** The media type of a recording, from its file name; null for formats a WebView cannot play. */
    fun mediaType(file: String): String? = when (file.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "ogg", "oga", "opus" -> "audio/ogg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "webm" -> "audio/webm"
        else -> null
    }

    private fun playable(file: String) = mediaType(file) != null

    /** The dictionaries' order in AnkiConnect Android's default configuration. */
    val SOURCES = listOf(
        "nhk16", "daijisen", "shinmeikai8", "jpod", "jpod_alternate",
        "taas", "ozk5", "forvo", "forvo_ext", "forvo_ext2",
    )
    private const val OTHER_SOURCE = 999

    private val DISPLAY_NAMES = mapOf(
        "nhk16" to "NHK16 %s",
        "daijisen" to "Daijisen %s",
        "shinmeikai8" to "SMK8 %s",
        "jpod" to "JPod101",
        "jpod_alternate" to "JPod101 Alt",
        "taas" to "TAAS",
        "ozk5" to "OZK5 %s",
        "forvo" to "Forvo (%s)",
        "forvo_ext" to "Forvo Ext",
        "forvo_ext2" to "Forvo Ext2",
    )
}

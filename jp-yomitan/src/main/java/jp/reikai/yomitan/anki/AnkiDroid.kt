package jp.reikai.yomitan.anki

/**
 * What [AnkiConnect] needs from AnkiDroid: the calls its content provider
 * (`content://com.ichi2.anki.flashcards`, AnkiDroid 2.24) answers, one method per provider call so
 * each costs one round trip. Blocking; called off the main thread. Failures are thrown as the provider
 * throws them ([SecurityException] when the permission is missing).
 */
internal interface AnkiDroid {

    fun decks(): List<Deck>

    /** Every note type with its field names, in one call. */
    fun models(): List<Model>

    /** Notes whose first-field checksum is one of [checksums] (`notes_v2`, `csum IN (...)`). */
    fun notesByChecksum(checksums: Collection<Long>): List<NoteRow>

    /** Which of [noteIds] have a card in one of [deckIds] (home deck included, for filtered decks). */
    fun notesWithCardsIn(noteIds: Collection<Long>, deckIds: Collection<Long>): Set<Long>

    fun notes(ids: Collection<Long>): List<NoteRow>

    /** Notes matching an Anki search (the provider runs it as AnkiDroid's browser would). */
    fun findNotes(query: String): List<Long>

    /** Cards matching an Anki search. */
    fun findCards(query: String): List<Long>

    fun card(id: Long): CardRow?

    /** The cards of note [noteId]: their `ord` and deck. */
    fun cardsOf(noteId: Long): List<CardRow>

    /** Adds a note of [modelId] with [fields] in the note type's order; returns its id. */
    fun addNote(modelId: Long, fields: List<String>, tags: String): Long

    fun moveCard(noteId: Long, ord: Int, deckId: Long)

    fun updateNote(id: Long, fields: List<String>)

    /** Copies a media file into AnkiDroid's collection; returns the name AnkiDroid stored it under. */
    fun storeMedia(fileName: String, data: ByteArray): String

    /** Opens AnkiDroid's card browser on an Anki search. */
    fun browse(query: String)

    /** Asks AnkiDroid to sync with AnkiWeb. */
    fun sync()

    data class Deck(val id: Long, val name: String)

    data class Model(val id: Long, val name: String, val fields: List<String>)

    data class NoteRow(
        val id: Long,
        val modelId: Long,
        val fields: List<String>,
        val tags: String = "",
        val mod: Long = 0,
    )

    data class CardRow(
        val id: Long,
        val noteId: Long,
        val ord: Int,
        val deckId: Long,
        val queue: Int = 0,
        val type: Int = 0,
        val interval: Long = 0,
        val due: Long = 0,
        val reps: Int = 0,
        val lapses: Int = 0,
    )

    companion object {
        const val FIELD_SEPARATOR = "\u001f"
    }
}

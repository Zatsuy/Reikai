package jp.reikai.yomitan.anki

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.core.content.FileProvider
import jp.reikai.yomitan.anki.AnkiDroid.CardRow
import jp.reikai.yomitan.anki.AnkiDroid.Companion.FIELD_SEPARATOR
import jp.reikai.yomitan.anki.AnkiDroid.Deck
import jp.reikai.yomitan.anki.AnkiDroid.Model
import jp.reikai.yomitan.anki.AnkiDroid.NoteRow
import java.io.File

/**
 * [AnkiDroid] over AnkiDroid's content provider (`FlashCardsContract`, AnkiDroid 2.24), without
 * AnkiDroid's LGPL API library, which lacks searches and deck-scoped duplicate checks. The insert
 * path follows the Phase 3 spike's `SpikeAnki.kt`; the media path is the one chimahon's
 * `AnkiDroidBridge.kt` uses (GPL-3.0, github.com/Chimahon/chimahon): a file shared through a
 * FileProvider with AnkiDroid, which copies it into its collection.
 */
internal class ContentAnkiDroid(context: Context) : AnkiDroid {

    private val app = context.applicationContext
    private val resolver get() = app.contentResolver

    override fun decks(): List<Deck> = query(DECKS, arrayOf("deck_id", "deck_name")) { Deck(getLong(0), getString(1)) }

    override fun models(): List<Model> = query(MODELS, arrayOf("_id", "name", "field_names")) {
        Model(getLong(0), getString(1), getString(2).split(FIELD_SEPARATOR))
    }

    override fun notesByChecksum(checksums: Collection<Long>): List<NoteRow> = checksums.chunked(
        CHUNK,
    ).flatMap { chunk ->
        query(
            NOTES_V2,
            arrayOf("_id", "mid", "flds"),
            "csum IN (${chunk.joinToString(",") { "?" }})",
            chunk.map(Long::toString).toTypedArray(),
        ) { NoteRow(getLong(0), getLong(1), getString(2).split(FIELD_SEPARATOR)) }
    }

    override fun notesWithCardsIn(noteIds: Collection<Long>, deckIds: Collection<Long>): Set<Long> {
        if (deckIds.isEmpty()) return emptySet()
        val decks = deckIds.joinToString(",")
        // notes_v2 runs its selection as SQL on the collection: one query answers for every candidate.
        return noteIds.chunked(CHUNK).flatMapTo(HashSet()) { chunk ->
            query(
                NOTES_V2,
                arrayOf("_id"),
                "id IN (${chunk.joinToString(
                    ",",
                )}) AND id IN (SELECT nid FROM cards WHERE did IN ($decks) OR odid IN ($decks))",
            ) { getLong(0) }
        }
    }

    override fun notes(ids: Collection<Long>): List<NoteRow> = ids.chunked(CHUNK).flatMap { chunk ->
        query(NOTES_V2, arrayOf("_id", "mid", "flds", "tags", "mod"), "id IN (${chunk.joinToString(",")})") {
            NoteRow(getLong(0), getLong(1), getString(2).split(FIELD_SEPARATOR), getString(3).orEmpty(), getLong(4))
        }
    }

    override fun findNotes(query: String): List<Long> = query(NOTES, arrayOf("_id"), query) { getLong(0) }

    override fun findCards(query: String): List<Long> = query(CARDS, arrayOf("_id"), query) { getLong(0) }

    override fun card(id: Long): CardRow? = query(Uri.withAppendedPath(CARDS, id.toString()), CARD_COLUMNS) {
        CardRow(
            id = getLong(0),
            noteId = getLong(1),
            ord = getInt(2),
            deckId = getLong(3),
            queue = getInt(4),
            type = getInt(5),
            interval = getLong(6),
            due = getLong(7),
            reps = getInt(8),
            lapses = getInt(9),
        )
    }.firstOrNull()

    override fun cardsOf(noteId: Long): List<CardRow> =
        query(Uri.withAppendedPath(NOTES, "$noteId/cards"), arrayOf("_id", "note_id", "ord", "deck_id")) {
            CardRow(getLong(0), getLong(1), getInt(2), getLong(3))
        }

    override fun addNote(modelId: Long, fields: List<String>, tags: String): Long {
        val values = ContentValues().apply {
            put("mid", modelId)
            put("flds", fields.joinToString(FIELD_SEPARATOR))
            put("tags", tags)
        }
        val uri = resolver.insert(NOTES, values) ?: error("AnkiDroid did not add the note")
        return uri.lastPathSegment!!.toLong()
    }

    override fun moveCard(noteId: Long, ord: Int, deckId: Long) {
        resolver.update(
            Uri.withAppendedPath(NOTES, "$noteId/cards/$ord"),
            ContentValues().apply { put("deck_id", deckId) },
            null,
            null,
        )
    }

    override fun updateNote(id: Long, fields: List<String>) {
        resolver.update(
            Uri.withAppendedPath(NOTES, id.toString()),
            ContentValues().apply { put("flds", fields.joinToString(FIELD_SEPARATOR)) },
            null,
            null,
        )
    }

    override fun storeMedia(fileName: String, data: ByteArray): String {
        val dir = File(app.cacheDir, AnkiMediaProvider.DIRECTORY).apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(data)
        val uri = FileProvider.getUriForFile(app, AnkiMediaProvider.authority(app), file)
        app.grantUriPermission(AnkiAccess.PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val values = ContentValues().apply {
                put("file_uri", uri.toString())
                // AnkiDroid adds a suffix and the extension of the file's media type itself.
                put("preferred_name", fileName.substringBeforeLast('.'))
            }
            val stored = resolver.insert(MEDIA, values) ?: error("AnkiDroid could not store $fileName")
            return stored.lastPathSegment ?: error("AnkiDroid could not store $fileName")
        } finally {
            app.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            file.delete()
        }
    }

    override fun browse(query: String) {
        val uri = Uri.parse("anki://x-callback-url/browser?search=${Uri.encode(query)}")
        app.startActivity(
            Intent(Intent.ACTION_VIEW, uri).setPackage(AnkiAccess.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun sync() {
        app.startActivity(
            Intent("com.ichi2.anki.DO_SYNC").setPackage(AnkiAccess.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun <T> query(
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        args: Array<String>? = null,
        read: Cursor.() -> T,
    ): List<T> = resolver.query(uri, projection, selection, args, null)?.use { cursor ->
        buildList(cursor.count) { while (cursor.moveToNext()) add(cursor.read()) }
    }.orEmpty()

    private companion object {
        val BASE: Uri = Uri.parse("content://com.ichi2.anki.flashcards")
        val NOTES: Uri = Uri.withAppendedPath(BASE, "notes")
        val NOTES_V2: Uri = Uri.withAppendedPath(BASE, "notes_v2")
        val MODELS: Uri = Uri.withAppendedPath(BASE, "models")
        val DECKS: Uri = Uri.withAppendedPath(BASE, "decks")
        val CARDS: Uri = Uri.withAppendedPath(BASE, "cards")
        val MEDIA: Uri = Uri.withAppendedPath(BASE, "media")

        val CARD_COLUMNS =
            arrayOf("_id", "note_id", "ord", "deck_id", "queue", "type", "interval", "due", "reps", "lapses")

        /** Below SQLite's oldest limit on bound parameters (999). */
        const val CHUNK = 500
    }
}

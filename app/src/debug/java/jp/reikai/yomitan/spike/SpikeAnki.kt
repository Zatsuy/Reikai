package jp.reikai.yomitan.spike

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The spike's AnkiConnect stand-in: Yomitan's backend speaks the AnkiConnect protocol (JSON over
 * POST, version 2, raw results, `{"error": ...}` on failure) and this answers it from AnkiDroid's
 * content provider, the same calls AnkiDroid's own AddContentApi makes. Only what adding a card needs
 * is here; any other action is logged as unsupported so the spike shows what Phase 3.2 must add.
 *
 * Runs off the main thread (content provider calls block).
 */
class SpikeAnki(private val context: Context, private val files: SpikeFiles) {

    private val resolver get() = context.contentResolver

    fun handle(requestJson: String): String = try {
        val request = JSONObject(requestJson)
        val action = request.getString("action")
        val params = request.optJSONObject("params") ?: JSONObject()
        val result = when (action) {
            "version" -> 6
            "deckNames" -> JSONArray(decks().keys.toList())
            "modelNames" -> JSONArray(models().keys.toList())
            "modelFieldNames" -> JSONArray(fieldNames(modelId(params.getString("modelName"))))
            "canAddNotes" -> params.getJSONArray("notes").map {
                JSONObject(it.toString()).let(::canAdd)
            }.let(::JSONArray)
            "canAddNotesWithErrorDetail" -> params.getJSONArray("notes").map {
                if (canAdd(JSONObject(it.toString()))) {
                    JSONObject().put("canAdd", true)
                } else {
                    JSONObject().put("canAdd", false).put("error", "cannot create note because it is a duplicate")
                }
            }.let(::JSONArray)
            "addNote" -> addNote(params.getJSONObject("note"))
            "findNotes" -> JSONArray()
            "notesInfo", "cardsInfo" -> JSONArray()
            "storeMediaFile" -> params.getString("filename").also {
                Log.i(SpikeHub.TAG, "anki: storeMediaFile $it not stored (spike)")
            }
            else -> {
                Log.w(SpikeHub.TAG, "anki: unsupported action $action")
                return JSONObject().put("error", "unsupported action $action").toString()
            }
        }
        Log.i(SpikeHub.TAG, "anki: $action ok")
        when (result) {
            is String -> JSONObject.quote(result)
            else -> result.toString()
        }
    } catch (e: Exception) {
        Log.e(SpikeHub.TAG, "anki: failed", e)
        JSONObject().put("error", e.message ?: e.toString()).toString()
    }

    // --- set-up and clean-up for the test deck (commands from adb) -------------------------------

    /** Creates the test deck and the Lapis note type (from its official templates) when missing. */
    fun setUp(deckName: String): String {
        val deckId = decks()[deckName] ?: createDeck(deckName)
        val modelId = models()[LAPIS] ?: createLapis(deckId)
        return JSONObject().put(
            "deckId",
            deckId,
        ).put("modelId", modelId).put("fields", JSONArray(fieldNames(modelId))).toString()
    }

    /** Deletes the notes the spike added and then its deck (AnkiDroid removes an empty deck's cards with it). */
    fun cleanUp(deckName: String, noteIds: List<Long>): String {
        val out = JSONObject()
        noteIds.forEach { id ->
            out.put(
                "note $id",
                runCatching {
                    resolver.delete(Uri.withAppendedPath(NOTES, id.toString()), null, null)
                }.fold({ "deleted $it" }, { "failed: $it" }),
            )
        }
        val deckId = decks()[deckName]
        if (deckId != null) {
            out.put(
                "deck",
                runCatching {
                    resolver.delete(Uri.withAppendedPath(DECKS, deckId.toString()), null, null)
                }.fold({ "deleted $it" }, { "failed: $it" }),
            )
        }
        return out.toString()
    }

    /** Notes matching an Anki search, with their fields and the decks of their cards (to check a card landed). */
    fun find(query: String): String {
        val deckNames = decks().entries.associate { (name, id) -> id to name }
        val out = JSONArray()
        resolver.query(NOTES, arrayOf("_id", "mid", "flds", "tags"), query, null, null)?.use { c ->
            while (c.moveToNext()) {
                val noteId = c.getLong(0)
                val fields = c.getString(2).split(FIELD_SEPARATOR)
                val names = fieldNames(c.getLong(1))
                val cardDecks = JSONArray()
                resolver.query(
                    Uri.withAppendedPath(NOTES, "$noteId/cards"),
                    arrayOf("deck_id"),
                    null,
                    null,
                    null,
                )?.use { k ->
                    while (k.moveToNext()) cardDecks.put(deckNames[k.getLong(0)] ?: k.getLong(0).toString())
                }
                val shown = JSONObject()
                names.zip(fields).forEach { (name, value) -> if (value.isNotEmpty()) shown.put(name, value.take(160)) }
                out.put(
                    JSONObject().put(
                        "noteId",
                        noteId,
                    ).put("decks", cardDecks).put("tags", c.getString(3)).put("fields", shown),
                )
            }
        }
        return out.toString()
    }

    // --- AnkiConnect actions ---------------------------------------------------------------------

    private fun canAdd(note: JSONObject): Boolean {
        if (note.optJSONObject("options")?.optBoolean("allowDuplicate") == true) return true
        val modelId = modelId(note.getString("modelName"))
        val first = note.getJSONObject("fields").optString(fieldNames(modelId).first())
        return duplicates(modelId, first).isEmpty()
    }

    private fun addNote(note: JSONObject): Long {
        if (!canAdd(note)) throw IllegalStateException("cannot create note because it is a duplicate")
        val modelId = modelId(note.getString("modelName"))
        val deckId =
            decks()[note.getString("deckName")]
                ?: throw IllegalStateException("deck was not found: ${note.getString("deckName")}")
        val given = note.getJSONObject("fields")
        val values = ContentValues().apply {
            put("mid", modelId)
            put("flds", fieldNames(modelId).joinToString(FIELD_SEPARATOR) { given.optString(it) })
            put("tags", note.optJSONArray("tags")?.map { it.toString() }?.joinToString(" ").orEmpty())
        }
        val uri = resolver.insert(NOTES, values) ?: throw IllegalStateException("AnkiDroid refused the note")
        val noteId = uri.lastPathSegment!!.toLong()
        // New cards go to the note type's deck; move them where Yomitan asked, as AddContentApi does.
        val cards = Uri.withAppendedPath(uri, "cards")
        resolver.query(cards, arrayOf("ord", "deck_id"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getLong(1) != deckId) {
                    resolver.update(
                        Uri.withAppendedPath(cards, c.getInt(0).toString()),
                        ContentValues().apply {
                            put("deck_id", deckId)
                        },
                        null,
                        null,
                    )
                }
            }
        }
        Log.i(SpikeHub.TAG, "RESULT anki_note {\"noteId\":$noteId}")
        return noteId
    }

    // --- AnkiDroid content provider ---------------------------------------------------------------

    private fun decks(): Map<String, Long> = buildMap {
        resolver.query(DECKS, arrayOf("deck_id", "deck_name"), null, null, null)?.use { c ->
            while (c.moveToNext()) put(c.getString(1), c.getLong(0))
        }
    }

    private fun models(): Map<String, Long> = buildMap {
        resolver.query(MODELS, arrayOf("_id", "name"), null, null, null)?.use { c ->
            while (c.moveToNext()) put(c.getString(1), c.getLong(0))
        }
    }

    private fun modelId(name: String) = models()[name] ?: throw IllegalStateException("model was not found: $name")

    private fun fieldNames(modelId: Long): List<String> =
        resolver.query(
            Uri.withAppendedPath(MODELS, modelId.toString()),
            arrayOf("field_names"),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0).split(FIELD_SEPARATOR) else null
        } ?: throw IllegalStateException("no fields for model $modelId")

    /** Notes of this type whose first field matches, found through Anki's first-field checksum. */
    private fun duplicates(modelId: Long, firstField: String): List<Long> = buildList {
        resolver.query(
            NOTES_V2,
            arrayOf("_id", "mid", "flds"),
            "csum=?",
            arrayOf(checksum(firstField).toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getLong(1) == modelId &&
                    stripHtml(c.getString(2).substringBefore(FIELD_SEPARATOR)) == stripHtml(firstField)
                ) {
                    add(c.getLong(0))
                }
            }
        }
    }

    private fun createDeck(name: String): Long {
        val uri =
            resolver.insert(DECKS, ContentValues().apply { put("deck_name", name) })
                ?: throw IllegalStateException("could not create deck $name")
        return uri.lastPathSegment!!.toLong()
    }

    /** Lapis 1.7.0 (GPL-3.0, github.com/donkuri/lapis), its templates as the release ships them. */
    private fun createLapis(deckId: Long): Long {
        val dir = File(files.root, "lapis")
        val uri = resolver.insert(
            MODELS,
            ContentValues().apply {
                put("name", LAPIS)
                put("field_names", LAPIS_FIELDS.joinToString(FIELD_SEPARATOR))
                put("num_cards", 1)
                put("css", File(dir, "styling.css").readText())
                put("sort_field_index", 0)
                put("deck_id", deckId)
            },
        ) ?: throw IllegalStateException("could not create the Lapis note type")
        resolver.update(
            Uri.withAppendedPath(uri, "templates/0"),
            ContentValues().apply {
                put("card_template_name", "Mining")
                put("question_format", File(dir, "front.html").readText())
                put("answer_format", File(dir, "back.html").readText())
            },
            null,
            null,
        )
        return uri.lastPathSegment!!.toLong()
    }

    private fun stripHtml(text: String): String = text
        .replace(Regex("(?is)<(style|script)\\b.*?</\\1>"), "")
        .replace(Regex("(?i)<img[^>]+src=[\"']?([^\"'>]+)[\"']?[^>]*>"), " $1 ")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    /** Anki's first-field checksum: the first 8 hex digits of the SHA-1 of the field without HTML. */
    private fun checksum(field: String): Long {
        val digest = MessageDigest.getInstance("SHA-1").digest(stripHtml(field).toByteArray())
        return digest.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
    }

    private fun JSONArray.map(transform: (Any) -> Any): List<Any> = (0 until length()).map { transform(get(it)) }

    companion object {
        private val BASE = Uri.parse("content://com.ichi2.anki.flashcards")
        private val NOTES = Uri.withAppendedPath(BASE, "notes")
        private val NOTES_V2 = Uri.withAppendedPath(BASE, "notes_v2")
        private val MODELS = Uri.withAppendedPath(BASE, "models")
        private val DECKS = Uri.withAppendedPath(BASE, "decks")
        private const val FIELD_SEPARATOR = "\u001f"
        const val LAPIS = "Lapis"
        private val LAPIS_FIELDS = listOf(
            "Expression", "ExpressionFurigana", "ExpressionReading", "ExpressionAudio", "SelectionText",
            "MainDefinition", "DefinitionPicture", "Sentence", "SentenceFurigana", "SentenceAudio", "Picture",
            "Glossary", "Hint", "IsWordAndSentenceCard", "IsClickCard", "IsSentenceCard", "IsAudioCard",
            "PitchPosition", "PitchCategories", "Frequency", "FreqSort", "MiscInfo",
        )
    }
}

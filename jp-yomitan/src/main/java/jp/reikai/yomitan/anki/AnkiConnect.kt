package jp.reikai.yomitan.anki

import android.content.Context
import android.os.SystemClock
import jp.reikai.yomitan.LocalRoute
import jp.reikai.yomitan.anki.AnkiDroid.Deck
import jp.reikai.yomitan.anki.AnkiDroid.Model
import jp.reikai.yomitan.anki.AnkiDroid.NoteRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import logcat.LogPriority
import logcat.logcat
import kotlin.io.encoding.Base64

/**
 * The AnkiConnect server Yomitan talks to (`POST http://127.0.0.1:8765`), answered from AnkiDroid's
 * content provider instead of desktop Anki. Speaks AnkiConnect's protocol as Yomitan uses it
 * (`comm/anki-connect.js`): version 6, raw results for request versions up to 4, and
 * `{"result": null, "error": "..."}` on failure. Actions AnkiDroid cannot do answer exactly
 * `unsupported action`, the text Yomitan falls back on (`guiEditNote` falls back to `guiBrowse`).
 *
 * Deck and note type lists are cached ([CACHE_MILLIS]; refreshed when a name is not found, and by
 * the settings page's `deckNames`, `modelNames` and `modelFieldNames`), since the duplicate check
 * runs on every popup: it costs one provider query for all notes, plus one when a note is scoped to
 * a deck and has a candidate.
 *
 * Blocking; runs on the local server's IO thread.
 */
class AnkiConnect internal constructor(
    private val anki: AnkiDroid,
    private val access: () -> AnkiAccess.Status,
    private val onRefused: (AnkiAccess.Status, String) -> Unit = { _, _ -> },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    private class Cached<T>(val value: T, val at: Long)

    @Volatile private var deckCache: Cached<List<Deck>>? = null

    @Volatile private var modelCache: Cached<List<Model>>? = null

    @Volatile private var lastSync = Long.MIN_VALUE

    /** Answers one AnkiConnect request (its JSON text) with the response's JSON text. */
    fun answer(requestText: String): String {
        val request = runCatching { Json.parseToJsonElement(requestText).jsonObject }.getOrNull()
            ?: return error("invalid request").toString()
        val action = request.str("action") ?: return error("missing action").toString()
        val status = access()
        if (status != AnkiAccess.Status.READY) {
            deckCache = null
            modelCache = null
            onRefused(status, action)
            return error(refusalMessage(status)).toString()
        }
        val started = clock()
        return reply(request).toString().also {
            logcat(TAG) { "anki $action: ${clock() - started} ms" }
        }
    }

    /** One request's reply: the result as AnkiConnect shapes it for the request's version, or an error. */
    private fun reply(request: JsonObject): JsonElement {
        val action = request.str("action") ?: return error("missing action")
        val version = request["version"]?.jsonPrimitive?.intOrNull ?: 4
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            val result = run(action, params)
            if (version <= 4) {
                result
            } else {
                buildJsonObject {
                    put("result", result)
                    put("error", JsonNull)
                }
            }
        } catch (e: SecurityException) {
            logcat(TAG, LogPriority.WARN) { "anki $action: $e" }
            onRefused(AnkiAccess.Status.NEEDS_PERMISSION, action)
            error(refusalMessage(AnkiAccess.Status.NEEDS_PERMISSION))
        } catch (e: AnkiError) {
            error(e.message.orEmpty())
        } catch (e: Exception) {
            logcat(TAG, LogPriority.WARN) { "anki $action failed: $e" }
            error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun run(action: String, params: JsonObject): JsonElement = when (action) {
        "version" -> JsonPrimitive(VERSION)
        "deckNames" -> strings(decks(refresh = true).map { it.name })
        "modelNames" -> strings(models(refresh = true).map { it.name })
        "modelFieldNames" -> {
            val name = params.str("modelName") ?: throw AnkiError("missing modelName")
            val model =
                models(refresh = true).firstOrNull { it.name == name } ?: throw AnkiError("model was not found: $name")
            strings(model.fields)
        }
        "canAddNotes" -> buildJsonArray { checkNotes(params.notes()).forEach { add(JsonPrimitive(it == null)) } }
        "canAddNotesWithErrorDetail" -> buildJsonArray {
            checkNotes(params.notes()).forEach { error ->
                add(
                    buildJsonObject {
                        put("canAdd", error == null)
                        if (error != null) put("error", error)
                    },
                )
            }
        }
        "addNote" -> JsonPrimitive(addNote(params["note"] as? JsonObject ?: throw AnkiError("missing note")))
        "addNotes" -> buildJsonArray {
            params.notes().forEach { note ->
                add(runCatching { addNote(note) }.fold({ JsonPrimitive(it) }, { JsonNull }))
            }
        }
        "updateNoteFields" -> {
            updateNoteFields(params["note"] as? JsonObject ?: throw AnkiError("missing note"))
            JsonNull
        }
        "findNotes" -> longs(anki.findNotes(params.str("query").orEmpty()))
        "findCards" -> longs(anki.findCards(params.str("query").orEmpty()))
        "notesInfo" -> notesInfo(
            params.ids("notes") ?: params.str("query")?.let(anki::findNotes) ?: throw AnkiError("missing notes"),
        )
        "cardsInfo" -> cardsInfo(params.ids("cards") ?: throw AnkiError("missing cards"))
        "storeMediaFile" -> JsonPrimitive(storeMediaFile(params))
        "guiBrowse" -> {
            val query = params.str("query").orEmpty()
            anki.browse(query)
            longs(runCatching { anki.findCards(query) }.getOrDefault(emptyList()))
        }
        "sync" -> {
            val now = clock()
            if (lastSync == Long.MIN_VALUE || now - lastSync >= SYNC_COOLDOWN_MILLIS) {
                lastSync = now
                anki.sync()
            }
            JsonNull
        }
        "multi" -> buildJsonArray {
            params["actions"]?.jsonArray.orEmpty().forEach { add(reply(it as? JsonObject ?: JsonObject(emptyMap()))) }
        }
        else -> throw AnkiError(UNSUPPORTED)
    }

    // --- duplicates ---------------------------------------------------------------------------------

    /** A note Yomitan wants checked or added, resolved against the collection. */
    private class Prepared(
        val model: Model,
        val deck: Deck,
        /** The first field as Anki compares it (NFC, HTML stripped, media names kept). */
        val first: String,
        val allowDuplicate: Boolean,
        val checkAllModels: Boolean,
        /** The decks a duplicate must have a card in; null for the whole collection. */
        val scopeDecks: Set<Long>?,
    )

    /** Resolves [note], or throws [AnkiError] with the text desktop AnkiConnect uses. */
    private fun prepare(note: JsonObject): Prepared {
        val modelName = note.str("modelName").orEmpty()
        val deckName = note.str("deckName").orEmpty()
        val model = model(modelName) ?: throw AnkiError("model was not found: $modelName")
        val deck = deck(deckName) ?: throw AnkiError("deck was not found: $deckName")
        val fields = note["fields"] as? JsonObject ?: JsonObject(emptyMap())
        val first = AnkiText.stripHtmlPreservingMediaFilenames(AnkiText.normalize(fields.field(model.fields.first())))
        if (first.isBlank()) throw AnkiError(EMPTY)
        val options = note["options"] as? JsonObject
        val scopeOptions = options?.get("duplicateScopeOptions") as? JsonObject
        val scopeDecks = if (options?.str("duplicateScope") == "deck") {
            val name = scopeOptions?.str("deckName") ?: deckName
            val scopeDeck = deck(name)
            when {
                // AnkiConnect: a scope deck that does not exist holds no duplicates.
                scopeDeck == null -> emptySet()
                scopeOptions?.bool("checkChildren") == true -> childrenOf(scopeDeck)
                else -> setOf(scopeDeck.id)
            }
        } else {
            null
        }
        return Prepared(
            model = model,
            deck = deck,
            first = first,
            allowDuplicate = options?.bool("allowDuplicate") == true,
            checkAllModels = scopeOptions?.bool("checkAllModels") == true,
            scopeDecks = scopeDecks,
        )
    }

    /** For each note, null when it can be added, else why not (AnkiConnect's error texts). */
    private fun checkNotes(notes: List<JsonObject>): List<String?> {
        val prepared = notes.map { note ->
            try {
                Result.success(prepare(note))
            } catch (e: AnkiError) {
                Result.failure(e)
            }
        }
        val checked = prepared.mapNotNull { it.getOrNull()?.takeUnless(Prepared::allowDuplicate) }
        val duplicates = duplicates(checked)
        return prepared.map { result ->
            result.fold(
                onSuccess = { if (it in duplicates) DUPLICATE else null },
                onFailure = { it.message },
            )
        }
    }

    /** Which of [notes] are duplicates: one checksum query for all, one deck query per distinct scope. */
    private fun duplicates(notes: List<Prepared>): Set<Prepared> {
        if (notes.isEmpty()) return emptySet()
        val rows = anki.notesByChecksum(notes.map { AnkiText.checksum(it.first) }.distinct())
        if (rows.isEmpty()) return emptySet()
        val firstOf = rows.associate {
            it.id to
                AnkiText.stripHtmlPreservingMediaFilenames(it.fields.firstOrNull().orEmpty())
        }
        val matches = notes.associateWith { note ->
            rows.filter { row ->
                firstOf[row.id] == note.first && (note.checkAllModels || row.modelId == note.model.id)
            }
        }
        val found = HashSet<Prepared>()
        notes.filter { it.scopeDecks == null && matches[it].orEmpty().isNotEmpty() }.forEach { found += it }
        notes.filter { it.scopeDecks != null && it.scopeDecks.isNotEmpty() && matches[it].orEmpty().isNotEmpty() }
            .groupBy { it.scopeDecks!! }
            .forEach { (decks, group) ->
                val inDecks = anki.notesWithCardsIn(
                    group.flatMap {
                        matches[it].orEmpty()
                    }.map { it.id }.distinct(),
                    decks,
                )
                group.filter { note -> matches[note].orEmpty().any { it.id in inDecks } }.forEach { found += it }
            }
        return found
    }

    // --- notes --------------------------------------------------------------------------------------

    private fun addNote(note: JsonObject): Long {
        var prepared = prepare(note)
        if (!prepared.allowDuplicate && prepared in duplicates(listOf(prepared))) throw AnkiError(DUPLICATE)
        val given = note["fields"] as? JsonObject ?: JsonObject(emptyMap())
        val tags = (note["tags"] as? JsonArray).orEmpty().mapNotNull {
            it.jsonPrimitive.contentOrNull
        }.joinToString(" ")
        val id = try {
            anki.addNote(prepared.model.id, prepared.model.fields.map { given.field(it) }, tags)
        } catch (e: IllegalArgumentException) {
            // The note type's fields changed since they were cached: reload them and try once more.
            modelCache = null
            prepared = prepare(note)
            anki.addNote(prepared.model.id, prepared.model.fields.map { given.field(it) }, tags)
        }
        // New cards go to the note type's last deck; move them where Yomitan asked, as AddContentApi does.
        anki.cardsOf(id).filter {
            it.deckId != prepared.deck.id
        }.forEach { anki.moveCard(id, it.ord, prepared.deck.id) }
        return id
    }

    private fun updateNoteFields(note: JsonObject) {
        val id = note["id"]?.jsonPrimitive?.longOrNull ?: throw AnkiError("missing note id")
        val row = anki.notes(listOf(id)).firstOrNull() ?: throw AnkiError("note was not found: $id")
        val model = modelOf(row) ?: throw AnkiError("model was not found: ${row.modelId}")
        val given = note["fields"] as? JsonObject ?: JsonObject(emptyMap())
        val fields = model.fields.mapIndexed { i, name ->
            if (given.keys.any {
                    it.equals(name, ignoreCase = true)
                }
            ) {
                given.field(name)
            } else {
                row.fields.getOrElse(i) { "" }
            }
        }
        anki.updateNote(id, fields)
    }

    private fun notesInfo(ids: List<Long>): JsonArray {
        val rows = anki.notes(ids).associateBy { it.id }
        return buildJsonArray {
            ids.forEach { id ->
                val row = rows[id]
                val model = row?.let(::modelOf)
                if (row == null || model == null) {
                    add(JsonObject(emptyMap()))
                    return@forEach
                }
                add(
                    buildJsonObject {
                        put("noteId", id)
                        put("modelName", model.name)
                        put("tags", strings(row.tags.split(' ').filter { it.isNotBlank() }))
                        put(
                            "fields",
                            buildJsonObject {
                                model.fields.forEachIndexed { i, name ->
                                    put(
                                        name,
                                        buildJsonObject {
                                            put("value", row.fields.getOrElse(i) { "" })
                                            put("order", i)
                                        },
                                    )
                                }
                            },
                        )
                        put("cards", longs(anki.findCards("nid:$id")))
                        put("mod", row.mod)
                    },
                )
            }
        }
    }

    private fun cardsInfo(ids: List<Long>): JsonArray {
        val deckNames by lazy { decks().associate { it.id to it.name } }
        return buildJsonArray {
            ids.forEach { id ->
                val card = runCatching { anki.card(id) }.getOrNull()
                if (card == null) {
                    add(JsonObject(emptyMap()))
                    return@forEach
                }
                add(
                    buildJsonObject {
                        put("cardId", card.id)
                        put("note", card.noteId)
                        put("deckName", deckNames[card.deckId].orEmpty())
                        put("ord", card.ord)
                        put("queue", card.queue)
                        put("type", card.type)
                        put("interval", card.interval)
                        put("due", card.due)
                        put("reps", card.reps)
                        put("lapses", card.lapses)
                        // AnkiDroid's provider does not expose card flags.
                        put("flags", 0)
                    },
                )
            }
        }
    }

    private fun storeMediaFile(params: JsonObject): String {
        val name =
            params.str("filename")?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it != "." && it != ".." }
                ?: throw AnkiError("missing filename")
        val data = params.str("data") ?: throw AnkiError("Reikai JP can only store media sent as data")
        val bytes = runCatching { Base64.decode(data) }.getOrElse { throw AnkiError("invalid media data") }
        return anki.storeMedia(name, bytes)
    }

    // --- decks and note types -----------------------------------------------------------------------

    private fun decks(refresh: Boolean = false): List<Deck> {
        val cached = deckCache
        if (!refresh && cached != null && clock() - cached.at < CACHE_MILLIS) return cached.value
        return anki.decks().also { deckCache = Cached(it, clock()) }
    }

    private fun models(refresh: Boolean = false): List<Model> {
        val cached = modelCache
        if (!refresh && cached != null && clock() - cached.at < CACHE_MILLIS) return cached.value
        return anki.models().also { modelCache = Cached(it, clock()) }
    }

    /** Anki's deck names are case-insensitive; a name not in the cache reloads it once. */
    private fun deck(name: String): Deck? {
        fun find(decks: List<Deck>) =
            decks.firstOrNull { it.name == name } ?: decks.firstOrNull { it.name.equals(name, true) }
        return find(decks()) ?: find(decks(refresh = true))
    }

    private fun model(name: String): Model? = models().firstOrNull { it.name == name }
        ?: models(refresh = true).firstOrNull { it.name == name }

    /** The note type of [row], reloaded when the cached one's field count no longer matches. */
    private fun modelOf(row: NoteRow): Model? =
        models().firstOrNull { it.id == row.modelId && it.fields.size == row.fields.size }
            ?: models(refresh = true).firstOrNull { it.id == row.modelId }

    private fun childrenOf(deck: Deck): Set<Long> {
        val prefix = deck.name + "::"
        return decks().filter {
            it.id == deck.id || it.name.startsWith(prefix, ignoreCase = true)
        }.mapTo(HashSet()) { it.id }
    }

    private class AnkiError(message: String) : Exception(message)

    companion object {
        /** AnkiConnect's API version as AnkiConnect Android and desktop report it. */
        const val VERSION = 6

        /** Yomitan recognises an unsupported action by exactly this text (`anki-connect.js`). */
        const val UNSUPPORTED = "unsupported action"

        /** Yomitan marks a note as a duplicate when the error contains this text (`backend.js`). */
        const val DUPLICATE = "cannot create note because it is a duplicate"
        const val EMPTY = "cannot create note because it is empty"

        const val CACHE_MILLIS = 60_000L
        const val SYNC_COOLDOWN_MILLIS = 120_000L

        private const val TAG = "Yomitan"

        /** The route that answers Yomitan's AnkiConnect calls from AnkiDroid. */
        fun route(context: Context, access: AnkiAccess): LocalRoute {
            val connect = AnkiConnect(ContentAnkiDroid(context), access::status, access::refused)
            return LocalRoute.ankiConnect { connect.answer(it) }
        }

        fun refusalMessage(status: AnkiAccess.Status): String = when (status) {
            AnkiAccess.Status.MISSING_APP -> "AnkiDroid is not installed: install it to add cards from Reikai JP"
            AnkiAccess.Status.NEEDS_PERMISSION ->
                "Reikai JP may not use AnkiDroid yet: allow it in Reikai JP's Japanese settings or when adding a card"
            AnkiAccess.Status.READY -> "AnkiDroid is ready"
        }

        private fun error(message: String): JsonObject = buildJsonObject {
            put("result", JsonNull)
            put("error", message)
        }

        private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

        private fun longs(values: List<Long>) = JsonArray(values.map(::JsonPrimitive))

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

        private fun JsonObject.notes(): List<JsonObject> = (this["notes"] as? JsonArray).orEmpty().mapNotNull {
            it as? JsonObject
        }

        private fun JsonObject.ids(key: String): List<Long>? =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }

        /** A note field by name; AnkiConnect matches field names without regard to case. */
        private fun JsonObject.field(name: String): String {
            val value = this[name] ?: entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            return (value as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
    }
}

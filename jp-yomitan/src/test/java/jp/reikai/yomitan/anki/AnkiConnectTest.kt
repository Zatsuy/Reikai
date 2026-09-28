package jp.reikai.yomitan.anki

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import jp.reikai.yomitan.anki.AnkiDroid.CardRow
import jp.reikai.yomitan.anki.AnkiDroid.Deck
import jp.reikai.yomitan.anki.AnkiDroid.Model
import jp.reikai.yomitan.anki.AnkiDroid.NoteRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class AnkiConnectTest {

    /** AnkiDroid's collection in memory, with the provider's semantics the dispatcher relies on. */
    private class FakeAnkiDroid : AnkiDroid {
        val decks = mutableListOf(Deck(1, "Default"), Deck(10, "Mining"), Deck(11, "Mining::Novels"), Deck(20, "Other"))
        val models = mutableListOf(
            Model(100, "Lapis", listOf("Expression", "ExpressionReading", "ExpressionAudio")),
            Model(200, "Basic", listOf("Front", "Back")),
        )
        val notes = mutableListOf<NoteRow>()
        val cards = mutableListOf<CardRow>()

        /** A card's home deck while it sits in a filtered deck (`odid`). */
        val homeDecks = HashMap<Long, Long>()
        var checksumQueries = 0
        var deckQueries = 0
        var deckListLoads = 0
        var failWith: Exception? = null
        val stored = mutableListOf<Pair<String, ByteArray>>()

        fun note(id: Long, modelId: Long, vararg fields: String, deckId: Long = 10) {
            notes += NoteRow(id, modelId, fields.toList())
            cards += CardRow(id * 10, id, 0, deckId)
        }

        override fun decks(): List<Deck> {
            failWith?.let { throw it }
            deckListLoads++
            return decks.toList()
        }

        override fun models() = models.toList()

        override fun notesByChecksum(checksums: Collection<Long>): List<NoteRow> {
            checksumQueries++
            return notes.filter { AnkiText.checksum(it.fields.first()) in checksums }
        }

        override fun notesWithCardsIn(noteIds: Collection<Long>, deckIds: Collection<Long>): Set<Long> {
            deckQueries++
            return cards.filter { it.noteId in noteIds && (it.deckId in deckIds || homeDecks[it.id] in deckIds) }
                .map { it.noteId }.toSet()
        }

        override fun notes(ids: Collection<Long>) = notes.filter { it.id in ids }
        override fun findNotes(query: String) = notes.map { it.id }
        override fun findCards(query: String) = cards.filter { "nid:${it.noteId}" == query }.map { it.id }
        override fun card(id: Long) = cards.firstOrNull { it.id == id }
        override fun cardsOf(noteId: Long) = cards.filter { it.noteId == noteId }

        override fun addNote(modelId: Long, fields: List<String>, tags: String): Long {
            val id = 1000L + notes.size
            notes += NoteRow(id, modelId, fields, tags)
            cards += CardRow(id * 10, id, 0, 1)
            return id
        }

        override fun moveCard(noteId: Long, ord: Int, deckId: Long) {
            val i = cards.indexOfFirst { it.noteId == noteId && it.ord == ord }
            cards[i] = cards[i].copy(deckId = deckId)
        }

        override fun updateNote(id: Long, fields: List<String>) {
            val i = notes.indexOfFirst { it.id == id }
            notes[i] = notes[i].copy(fields = fields)
        }

        override fun storeMedia(fileName: String, data: ByteArray): String {
            stored += fileName to data
            return "stored_$fileName"
        }

        override fun browse(query: String) {}
        override fun sync() {}
    }

    private val anki = FakeAnkiDroid()
    private var status = AnkiAccess.Status.READY
    private val refused = mutableListOf<Pair<AnkiAccess.Status, String>>()
    private var now = 0L
    private val connect = AnkiConnect(anki, { status }, { s, a -> refused += s to a }, { now })

    private fun call(action: String, params: String = "{}", version: Int = 2): JsonElement =
        Json.parseToJsonElement(connect.answer("""{"action":"$action","version":$version,"params":$params}"""))

    private fun note(
        expression: String,
        deck: String = "Mining",
        model: String = "Lapis",
        options: String = """{"allowDuplicate":false}""",
    ) = """{"deckName":"$deck","modelName":"$model","fields":{"Expression":"$expression"},""" +
        """"options":$options,"tags":["yomitan"]}"""

    private fun canAdd(vararg notes: String): List<String?> =
        call("canAddNotesWithErrorDetail", """{"notes":[${notes.joinToString(",")}]}""").jsonArray.map {
            it.jsonObject["error"]?.jsonPrimitive?.content
        }

    @Test
    fun `version is answered raw for Yomitan's request version`() {
        call("version").toString() shouldBe "6"
    }

    @Test
    fun `a request of version 5 or later gets the result and error envelope`() {
        call("version", version = 6).toString() shouldBe """{"result":6,"error":null}"""
    }

    @ParameterizedTest
    @ValueSource(strings = ["guiEditNote", "suspend", "createDeck", "apiReflect"])
    fun `actions AnkiDroid cannot do answer exactly unsupported action`(action: String) {
        call(action).toString() shouldBe """{"result":null,"error":"unsupported action"}"""
    }

    @Test
    fun `multi answers each action with its raw result or its error`() {
        call("multi", """{"actions":[{"action":"version"},{"action":"guiEditNote","params":{"note":1}}]}""")
            .toString() shouldBe """[6,{"result":null,"error":"unsupported action"}]"""
    }

    @Test
    fun `a note whose first field matches a note of the same type is a duplicate`() {
        anki.note(1, 100, "食べる")
        canAdd(note("食べる"), note("飲む")) shouldContainExactly listOf(AnkiConnect.DUPLICATE, null)
    }

    @Test
    fun `the duplicate check compares the first field without HTML, as Anki does`() {
        anki.note(1, 100, "<b>食べる</b>")
        canAdd(note("食べる")) shouldContainExactly listOf(AnkiConnect.DUPLICATE)
    }

    @Test
    fun `a note of another type is a duplicate only when checkAllModels is set`() {
        anki.note(1, 200, "食べる")
        val all = """{"allowDuplicate":false,"duplicateScope":"collection",""" +
            """"duplicateScopeOptions":{"checkAllModels":true}}"""
        canAdd(note("食べる"), note("食べる", options = all)) shouldContainExactly listOf(null, AnkiConnect.DUPLICATE)
    }

    @ParameterizedTest(name = "a card in deck {0}, scope deck {1}, children {2}: {3}")
    @CsvSource(
        "20, Mining, false, ",
        "10, Mining, false, cannot create note because it is a duplicate",
        "11, Mining, false, ",
        "11, Mining, true, cannot create note because it is a duplicate",
        "20, Other, false, cannot create note because it is a duplicate",
        "20, Missing, false, ",
    )
    fun `deck scope counts only cards in the scope deck and, when asked, its children`(
        cardDeck: Long,
        scopeDeck: String,
        children: Boolean,
        error: String?,
    ) {
        anki.note(1, 100, "食べる", deckId = cardDeck)
        val options = """{"allowDuplicate":false,"duplicateScope":"deck",""" +
            """"duplicateScopeOptions":{"deckName":"$scopeDeck","checkChildren":$children,"checkAllModels":false}}"""
        canAdd(note("食べる", options = options)) shouldContainExactly listOf(error)
    }

    @Test
    fun `deck scope counts a card sitting in a filtered deck by its home deck`() {
        anki.note(1, 100, "食べる", deckId = 20)
        anki.homeDecks[10] = 10
        val options = """{"allowDuplicate":false,"duplicateScope":"deck","duplicateScopeOptions":{"deckName":null}}"""
        canAdd(note("食べる", options = options)) shouldContainExactly listOf(AnkiConnect.DUPLICATE)
    }

    @Test
    fun `one checksum query and one deck query answer a whole popup`() {
        anki.note(1, 100, "食べる")
        anki.note(2, 100, "飲む")
        val scoped = """{"allowDuplicate":false,"duplicateScope":"deck"}"""
        canAdd(note("食べる", options = scoped), note("飲む", options = scoped), note("見る", options = scoped), note("食べる"))
        (anki.checksumQueries to anki.deckQueries) shouldBe (1 to 1)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "a missing note type, Mining, Nope, 食べる, model was not found: Nope",
        "a missing deck, Nope, Lapis, 食べる, deck was not found: Nope",
        "an empty first field, Mining, Lapis, <br>, cannot create note because it is empty",
    )
    fun `notes that cannot be created get AnkiConnect's error text`(
        case: String,
        deck: String,
        model: String,
        expression: String,
        error: String,
    ) {
        canAdd(note(expression, deck, model)) shouldContainExactly listOf(error)
    }

    @Test
    fun `canAddNotes answers booleans and allowDuplicate skips the check`() {
        anki.note(1, 100, "食べる")
        call("canAddNotes", """{"notes":[${note("食べる")},${note("食べる", options = """{"allowDuplicate":true}""")}]}""")
            .toString() shouldBe "[false,true]"
    }

    @Test
    fun `addNote fills fields in the note type's order and moves the card to the deck`() {
        val id = call(
            "addNote",
            """{"note":{"deckName":"Mining","modelName":"Lapis",""" +
                """"fields":{"expressionreading":"たべる","Expression":"食べる"},""" +
                """"options":{"allowDuplicate":true},"tags":["yomitan","jp"]}}""",
        ).jsonPrimitive.long
        (anki.notes.single { it.id == id } to anki.cards.single { it.noteId == id }.deckId) shouldBe
            (NoteRow(id, 100, listOf("食べる", "たべる", ""), "yomitan jp") to 10L)
    }

    @Test
    fun `addNote refuses a duplicate unless duplicates are allowed`() {
        anki.note(1, 100, "食べる")
        call("addNote", """{"note":${note("食べる")}}""").toString() shouldBe
            """{"result":null,"error":"cannot create note because it is a duplicate"}"""
    }

    @Test
    fun `updateNoteFields changes only the fields given`() {
        anki.note(1, 100, "食べる", "たべる", "[sound:old.mp3]")
        call("updateNoteFields", """{"note":{"id":1,"fields":{"ExpressionAudio":"[sound:new.mp3]"}}}""")
        anki.notes.single().fields shouldContainExactly listOf("食べる", "たべる", "[sound:new.mp3]")
    }

    @Test
    fun `notesInfo gives fields with their order, tags, the note type and card ids`() {
        anki.notes += NoteRow(1, 200, listOf("front", "back"), " yomitan  jp ")
        anki.cards += CardRow(77, 1, 0, 10)
        call("notesInfo", """{"notes":[1,5]}""").toString() shouldBe
            """[{"noteId":1,"modelName":"Basic","tags":["yomitan","jp"],""" +
            """"fields":{"Front":{"value":"front","order":0},"Back":{"value":"back","order":1}},""" +
            """"cards":[77],"mod":0},{}]"""
    }

    @Test
    fun `storeMediaFile decodes the data and returns the name AnkiDroid stored it under`() {
        call("storeMediaFile", """{"filename":"yomitan_audio.mp3","data":"aGk="}""").toString() shouldBe
            "\"stored_yomitan_audio.mp3\""
    }

    @ParameterizedTest
    @CsvSource(
        "MISSING_APP, AnkiDroid is not installed: install it to add cards from Reikai JP",
        "NEEDS_PERMISSION, Reikai JP may not use AnkiDroid yet: allow it in Reikai JP's Japanese settings " +
            "or when adding a card",
    )
    fun `without AnkiDroid or its permission every call gets an error Yomitan shows and is reported`(
        missing: AnkiAccess.Status,
        message: String,
    ) {
        status = missing
        (call("deckNames").toString() to refused.toList()) shouldBe
            ("""{"result":null,"error":"$message"}""" to listOf(missing to "deckNames"))
    }

    @Test
    fun `a permission revoked while the app runs is reported as a missing permission`() {
        anki.failWith = SecurityException("Permission not granted")
        val needs = AnkiAccess.Status.NEEDS_PERMISSION
        (call("deckNames").jsonObject["error"]?.jsonPrimitive?.content to refused.toList()) shouldBe
            (AnkiConnect.refusalMessage(needs) to listOf(needs to "deckNames"))
    }

    @Test
    fun `decks are cached between popups and reloaded when a name is missing or the cache is old`() {
        canAdd(note("食べる"))
        canAdd(note("食べる"))
        anki.decks += Deck(30, "New")
        canAdd(note("食べる", deck = "New"))
        now += AnkiConnect.CACHE_MILLIS
        canAdd(note("食べる"))
        anki.deckListLoads shouldBe 3
    }
}

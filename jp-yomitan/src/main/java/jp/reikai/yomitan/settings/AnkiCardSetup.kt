package jp.reikai.yomitan.settings

import jp.reikai.yomitan.YomitanEngine
import jp.reikai.yomitan.anki.AnkiAccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Yomitan's Anki card format as the Japanese settings show it (roadmap 3.5), and "Set up cards for
 * Lapis": the Lapis note type's fields filled with the markers Lapis's own README gives
 * (github.com/donkuri/lapis, "Yomitan field setup"), written into Yomitan's settings through its
 * backend's `modifySettings`, as Yomitan's own settings page would when the user picks the note type.
 * Only the current profile's card formats change, and only those without a note type (which add
 * nothing); everything else stays the user's.
 */
object AnkiCardSetup {

    /** The note type Lapis's README tells users to pick. */
    const val LAPIS = "Lapis"

    /**
     * What the current profile adds to Anki: [model] into [deck] (its first card format with a note
     * type), or nothing yet. A note type set while Anki is switched off (or in imported settings)
     * still counts as the user's choice.
     */
    data class Current(val enabled: Boolean, val model: String, val deck: String) {
        val configured: Boolean get() = model.isNotEmpty()
    }

    /** The current profile's first word card format; reads Yomitan's settings through the engine. */
    suspend fun current(engine: YomitanEngine): Current = current(engine.api("optionsGetFull"))

    /** Fills Lapis's fields as its README says, cards into [deck]; the note type is AnkiDroid's [lapis]. */
    suspend fun setUpLapis(engine: YomitanEngine, deck: String, lapis: AnkiAccess.NoteType) {
        val options = engine.api("optionsGetFull")
        val dictionaries = YomitanDictionaries.installed(engine)
        YomitanSettings.modify(engine, lapisTargets(options, deck, lapis, dictionaries))
    }

    internal fun current(options: JsonElement): Current {
        val anki = currentProfile(options)?.obj("anki") ?: return Current(false, "", "")
        val formats = anki.array("cardFormats")
        val format = formats.firstOrNull { !it.str("model").isNullOrEmpty() }
            ?: formats.firstOrNull { it.str("type") == "term" }
        return Current(
            enabled = (anki["enable"] as? JsonPrimitive)?.booleanOrNull == true,
            model = format?.str("model").orEmpty(),
            deck = format?.str("deck").orEmpty(),
        )
    }

    /**
     * `modifySettings` targets for the current profile: Anki on, and its first word card format (a
     * new one if it has none) set to [lapis] into [deck] with Lapis's markers. Its other card formats
     * without a note type go (Yomitan's defaults "Reading" and "Kanji" would each put an Add button on
     * every entry that only fails); those with one stay, and the keyboard's add and view note hotkeys
     * follow them ([cardFormatHotkeys]). MainDefinition holds one dictionary's
     * definitions (Lapis's `{single-glossary-...}`), preferring Jitendex, then JMdict, then the first
     * enabled word dictionary; with none it falls back to Yomitan's first definition.
     */
    internal fun lapisTargets(
        options: JsonElement,
        deck: String,
        lapis: AnkiAccess.NoteType,
        dictionaries: List<InstalledDictionary>,
    ): JsonArray {
        val index = (options as? JsonObject)?.get("profileCurrent")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val profile = currentProfile(options)
        val anki = profile?.obj("anki")
        val formats = anki?.array("cardFormats").orEmpty()
        val at = formats.indexOfFirst { it.str("type") == "term" }
        val main = mainDictionary(profile?.array("dictionaries").orEmpty(), dictionaries)
        val fields = buildJsonObject {
            lapisFields(lapis.fields, main).forEach { (name, value) ->
                put(
                    name,
                    buildJsonObject {
                        put("value", value)
                        put("overwriteMode", "coalesce")
                    },
                )
            }
        }
        fun format(base: JsonObject?) = JsonObject(
            (base ?: NEW_FORMAT) + mapOf(
                "deck" to JsonPrimitive(deck),
                "model" to JsonPrimitive(lapis.name),
                "fields" to fields,
            ),
        )
        val keptAt = formats.indices.filter { i -> i == at || !formats[i].str("model").isNullOrEmpty() }
        val kept = keptAt.map { formats[it] }
        val newFormats = if (at >= 0) {
            JsonArray(kept.map { if (it === formats[at]) format(it) else it })
        } else {
            JsonArray(kept + format(null))
        }
        val hotkeys = profile?.obj("inputs")?.get("hotkeys") as? JsonArray
        fun target(path: String, value: JsonElement) = buildJsonObject {
            put("action", "set")
            put("path", path)
            put("value", value)
            put("scope", "profile")
            put("optionsContext", buildJsonObject { put("index", index) })
        }
        val newHotkeys = hotkeys?.let { cardFormatHotkeys(it, keptAt) }
        return buildJsonArray {
            add(target("anki.enable", JsonPrimitive(true)))
            add(target("anki.cardFormats", newFormats))
            if (newHotkeys != null && newHotkeys != hotkeys) add(target("inputs.hotkeys", newHotkeys))
        }
    }

    /**
     * [hotkeys] (`inputs.hotkeys`) once only the card formats at [keptAt] (old indices, in order) are
     * left: Yomitan's add and view note hotkeys name a card format by its index (`argument`), so each
     * follows its format to its new index, and one whose format went is dropped (it would otherwise
     * act on whichever format takes that index later). Every other hotkey stays as it is.
     */
    internal fun cardFormatHotkeys(hotkeys: JsonArray, keptAt: List<Int>): JsonArray = JsonArray(
        hotkeys.mapNotNull { hotkey ->
            val o = hotkey as? JsonObject ?: return@mapNotNull hotkey
            if (o.str("action") !in CARD_FORMAT_ACTIONS) return@mapNotNull hotkey
            val old = o.str("argument")?.toIntOrNull() ?: return@mapNotNull hotkey
            val new = keptAt.indexOf(old).takeIf { it >= 0 } ?: return@mapNotNull null
            JsonObject(o + ("argument" to JsonPrimitive(new.toString())))
        },
    )

    /**
     * Each of the note type's [fields] with its marker from Lapis's README, and the book's cover as its
     * Picture (`{screenshot}`, which Reikai JP answers with the cover of the book a word came from); a
     * field the README leaves empty (sentence furigana and audio, the card-type switches) or does not
     * know stays empty.
     */
    internal fun lapisFields(fields: List<String>, mainDictionary: String?): Map<String, String> {
        val markers =
            LAPIS_MARKERS +
                (
                    "MainDefinition" to
                        (mainDictionary?.let { "{single-glossary-${kebabCase(it)}}" } ?: "{glossary-first}")
                    )
        return fields.associateWith { markers[it].orEmpty() }
    }

    /** The dictionary for MainDefinition among the profile's enabled word dictionaries, or null. */
    internal fun mainDictionary(profileDictionaries: List<JsonObject>, installed: List<InstalledDictionary>): String? {
        val withWords = installed.filter { it.terms > 0 && it.complete }.map { it.title }.toSet()
        val enabled = profileDictionaries
            .filter { (it["enabled"] as? JsonPrimitive)?.booleanOrNull != false }
            .mapNotNull { it.str("name") }
            .filter { it in withWords }
        return enabled.firstOrNull { it.contains("jitendex", ignoreCase = true) }
            ?: enabled.firstOrNull { it.contains("jmdict", ignoreCase = true) }
            ?: enabled.firstOrNull()
    }

    /**
     * Yomitan's `getKebabCase` (`js/data/anki-template-util.js`), which names `{single-glossary-...}`.
     * JavaScript's `\s` is Unicode's white space: spelled out, since Android's regex engine (ICU)
     * refuses the JVM's `(?U)` flag, and the JVM's plain `\s` is ASCII only.
     */
    internal fun kebabCase(text: String): String = text
        .replace(Regex("[\\s\\p{Z}\\uFEFF_]"), "-")
        .replace(Regex("[^\\p{L}\\p{N}-]"), "")
        .replace(Regex("--+"), "-")
        .replace(Regex("^-|-$"), "")
        .lowercase()

    private fun currentProfile(options: JsonElement): JsonObject? {
        val root = options as? JsonObject ?: return null
        val index = (root["profileCurrent"] as? JsonPrimitive)?.intOrNull ?: 0
        return (root["profiles"] as? JsonArray)?.getOrNull(index)?.let { it as? JsonObject }?.obj("options")
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun JsonObject.array(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull {
        it as? JsonObject
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Yomitan's hotkey actions whose argument is a card format's index (`display-anki.js`). */
    private val CARD_FORMAT_ACTIONS = setOf("addNote", "viewNotes")

    /** Yomitan's default word card format (`options-schema.json`), for a profile that has none. */
    private val NEW_FORMAT = buildJsonObject {
        put("name", "Expression")
        put("icon", "big-circle")
        put("type", "term")
    }

    /**
     * Lapis's README, "Yomitan field setup", and Picture; MainDefinition depends on the dictionaries
     * installed.
     */
    private val LAPIS_MARKERS = mapOf(
        "Expression" to "{expression}",
        "ExpressionFurigana" to "{furigana-plain}",
        "ExpressionReading" to "{reading}",
        "ExpressionAudio" to "{audio}",
        "SelectionText" to "{popup-selection-text}",
        "Sentence" to "{cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}",
        "Glossary" to "{glossary}",
        "PitchPosition" to "{pitch-accent-positions}",
        "PitchCategories" to "{pitch-accent-categories}",
        "Frequency" to "{frequencies}",
        "FreqSort" to "{frequency-harmonic-rank}",
        "MiscInfo" to "{document-title}",
        "Picture" to "{screenshot}",
    )
}

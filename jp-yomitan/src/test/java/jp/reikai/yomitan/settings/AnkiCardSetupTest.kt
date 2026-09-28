package jp.reikai.yomitan.settings

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import jp.reikai.yomitan.anki.AnkiAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AnkiCardSetupTest {

    /** Lapis 1.7.0's fields, in its note type's order. */
    private val lapis = AnkiAccess.NoteType(
        "Lapis",
        listOf(
            "Expression", "ExpressionFurigana", "ExpressionReading", "ExpressionAudio", "SelectionText",
            "MainDefinition", "DefinitionPicture", "Sentence", "SentenceFurigana", "SentenceAudio", "Picture",
            "Glossary", "Hint", "IsWordAndSentenceCard", "IsClickCard", "IsSentenceCard", "IsAudioCard",
            "PitchPosition", "PitchCategories", "Frequency", "FreqSort", "MiscInfo",
        ),
    )

    private val jitendex = InstalledDictionary("Jitendex.org [2026-08-11]", terms = 250_000, kanji = 0, complete = true)
    private val jpdb = InstalledDictionary("JPDBv2㋕", terms = 0, kanji = 0, complete = true)

    /** Yomitan's fresh settings, as `optionsGetFull` gives them, trimmed to what the setup reads. */
    private fun options(
        profileCurrent: Int = 0,
        dictionaries: String = "[]",
        formats: String = DEFAULT_FORMATS,
        enable: Boolean = false,
        hotkeys: String = "[]",
    ) =
        Json.parseToJsonElement(
            """
            {"profileCurrent": $profileCurrent, "profiles": [
              {"options": {"anki": {"enable": false, "cardFormats": []}, "dictionaries": []}},
              {"options": {"anki": {"enable": $enable, "cardFormats": $formats}, "dictionaries": $dictionaries,
                "inputs": {"hotkeys": $hotkeys}}}
            ]}
            """.trimIndent(),
        )

    private fun JsonArray.value(path: String) = first { it.jsonObject["path"]!!.jsonPrimitive.content == path }
        .jsonObject["value"]!!

    @Test
    fun `lapis fields get the markers from Lapis's README`() {
        val fields = AnkiCardSetup.lapisFields(lapis.fields, "Jitendex.org [2026-08-11]")

        fields["Expression"] shouldBe "{expression}"
        fields["ExpressionFurigana"] shouldBe "{furigana-plain}"
        fields["ExpressionReading"] shouldBe "{reading}"
        fields["ExpressionAudio"] shouldBe "{audio}"
        fields["SelectionText"] shouldBe "{popup-selection-text}"
        fields["MainDefinition"] shouldBe "{single-glossary-jitendexorg-2026-08-11}"
        fields["Sentence"] shouldBe "{cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}"
        fields["Glossary"] shouldBe "{glossary}"
        fields["PitchPosition"] shouldBe "{pitch-accent-positions}"
        fields["PitchCategories"] shouldBe "{pitch-accent-categories}"
        fields["Frequency"] shouldBe "{frequencies}"
        fields["FreqSort"] shouldBe "{frequency-harmonic-rank}"
        fields["MiscInfo"] shouldBe "{document-title}"
        // The book's cover (Reikai JP answers Yomitan's screenshot with it).
        fields["Picture"] shouldBe "{screenshot}"
        fields.filterValues { it.isEmpty() }.keys.toList().shouldContainExactly(
            "DefinitionPicture",
            "SentenceFurigana",
            "SentenceAudio",
            "Hint",
            "IsWordAndSentenceCard",
            "IsClickCard",
            "IsSentenceCard",
            "IsAudioCard",
        )
    }

    @Test
    fun `without a word dictionary MainDefinition takes Yomitan's first definition`() {
        AnkiCardSetup.lapisFields(listOf("MainDefinition", "Unknown"), null) shouldBe
            mapOf("MainDefinition" to "{glossary-first}", "Unknown" to "")
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "'Jitendex.org [2026-08-11]', jitendexorg-2026-08-11",
        "'JMdict (English)', jmdict-english",
        "'大辞林　第四版', 大辞林-第四版",
        "'  a__b  ', a-b",
        "'JMdict\u00a0Extra\u2003(v2)', jmdict-extra-v2",
    )
    fun `dictionary names become Yomitan's kebab case`(title: String, kebab: String) {
        AnkiCardSetup.kebabCase(title) shouldBe kebab
    }

    @Test
    fun `the current profile's first word format gets Lapis in the chosen deck`() {
        val targets = AnkiCardSetup.lapisTargets(
            options(
                profileCurrent = 1,
                dictionaries = """[{"name":"JPDBv2㋕","enabled":true},{"name":"${jitendex.title}","enabled":true}]""",
            ),
            deck = "Mining",
            lapis = lapis,
            dictionaries = listOf(jpdb, jitendex),
        )

        targets.map { it.jsonObject["optionsContext"]!!.jsonObject["index"]!!.jsonPrimitive.content }
            .shouldContainExactly("1", "1")
        targets.value("anki.enable").jsonPrimitive.content shouldBe "true"
        val formats = targets.value("anki.cardFormats").jsonArray.map { it.jsonObject }
        // Yomitan's "Reading" and "Kanji" have no note type: each would add an Add button that only fails.
        formats.map { it["name"]!!.jsonPrimitive.content }.shouldContainExactly("Expression")
        formats[0]["deck"]!!.jsonPrimitive.content shouldBe "Mining"
        formats[0]["model"]!!.jsonPrimitive.content shouldBe "Lapis"
        formats[0]["fields"]!!.jsonObject["MainDefinition"]!!.jsonObject["value"]!!.jsonPrimitive.content shouldBe
            "{single-glossary-jitendexorg-2026-08-11}"
        formats[0]["fields"]!!.jsonObject["Expression"]!!.jsonObject["overwriteMode"]!!.jsonPrimitive.content shouldBe
            "coalesce"
    }

    @Test
    fun `card formats with a note type are kept`() {
        val targets = AnkiCardSetup.lapisTargets(
            options(
                profileCurrent = 1,
                formats = """[
                    {"name":"Expression","type":"term","deck":"","model":"","fields":{}},
                    {"name":"Kanji","type":"kanji","deck":"Kanji","model":"Kanji note","fields":{}},
                    {"name":"Reading","type":"term","deck":"","model":"","fields":{}}
                ]""",
            ),
            "Mining",
            lapis,
            emptyList(),
        )

        val formats = targets.value("anki.cardFormats").jsonArray.map { it.jsonObject }
        formats.map { it["model"]!!.jsonPrimitive.content }.shouldContainExactly("Lapis", "Kanji note")
    }

    @Test
    fun `the add and view note hotkeys follow their card formats, and those of dropped formats go`() {
        val targets = AnkiCardSetup.lapisTargets(
            options(
                profileCurrent = 1,
                formats = """[
                    {"name":"Kanji","type":"kanji","deck":"","model":"","fields":{}},
                    {"name":"Kanji note","type":"kanji","deck":"Kanji","model":"Kanji note","fields":{}},
                    {"name":"Expression","type":"term","deck":"","model":"","fields":{}}
                ]""",
                hotkeys = """[
                    {"action":"addNote","argument":"0","key":"KeyE"},
                    {"action":"addNote","argument":"1","key":"KeyR"},
                    {"action":"addNote","argument":"2","key":"KeyK"},
                    {"action":"viewNotes","argument":"2","key":"KeyV"},
                    {"action":"nextEntry","argument":"2","key":"PageDown"}
                ]""",
            ),
            "Mining",
            lapis,
            emptyList(),
        )

        targets.value("anki.cardFormats").jsonArray.map { it.jsonObject["model"]!!.jsonPrimitive.content }
            .shouldContainExactly("Kanji note", "Lapis")
        targets.value("inputs.hotkeys").jsonArray.map {
            "${it.jsonObject["action"]!!.jsonPrimitive.content} ${it.jsonObject["argument"]!!.jsonPrimitive.content}"
        }.shouldContainExactly("addNote 0", "addNote 1", "viewNotes 1", "nextEntry 2")
    }

    @Test
    fun `hotkeys that still point at their card formats are left alone`() {
        val targets = AnkiCardSetup.lapisTargets(
            options(profileCurrent = 1, hotkeys = """[{"action":"addNote","argument":"0","key":"KeyE"}]"""),
            "Mining",
            lapis,
            emptyList(),
        )

        targets.map { it.jsonObject["path"]!!.jsonPrimitive.content }
            .shouldContainExactly("anki.enable", "anki.cardFormats")
    }

    @Test
    fun `a profile without a word format gets a new one`() {
        val targets = AnkiCardSetup.lapisTargets(
            options(profileCurrent = 1, formats = "[]"),
            "Mining",
            lapis,
            emptyList(),
        )

        val formats = targets.value("anki.cardFormats").jsonArray.map { it.jsonObject }
        formats.map { it["type"]!!.jsonPrimitive.content }.shouldContainExactly("term")
        formats[0]["model"]!!.jsonPrimitive.content shouldBe "Lapis"
    }

    @Test
    fun `MainDefinition prefers Jitendex, then JMdict, among enabled word dictionaries`() {
        val profile = listOf(
            dictionary("JMdict (English)"),
            dictionary("Jitendex.org [2026-08-11]", enabled = false),
            dictionary("JPDBv2㋕"),
        )
        val installed = listOf(
            jitendex,
            jpdb,
            InstalledDictionary("JMdict (English)", terms = 200_000, kanji = 0, complete = true),
        )

        AnkiCardSetup.mainDictionary(profile, installed) shouldBe "JMdict (English)"
    }

    @Test
    fun `Jitendex is the main definition even below JMdict`() {
        val jmdict = InstalledDictionary("JMdict (English)", terms = 200_000, kanji = 0, complete = true)

        AnkiCardSetup.mainDictionary(
            listOf(dictionary(jmdict.title), dictionary(jitendex.title)),
            listOf(jmdict, jitendex),
        ) shouldBe jitendex.title
    }

    @Test
    fun `a dictionary without words is never the main definition`() {
        AnkiCardSetup.mainDictionary(listOf(dictionary("JPDBv2㋕")), listOf(jpdb)) shouldBe null
    }

    @Test
    fun `the current format reads the current profile`() {
        val configured = options(
            profileCurrent = 1,
            enable = true,
            formats = """[{"name":"Expression","type":"term","deck":"Mining","model":"Lapis","fields":{}}]""",
        )

        AnkiCardSetup.current(configured) shouldBe
            AnkiCardSetup.Current(enabled = true, model = "Lapis", deck = "Mining")
        AnkiCardSetup.current(options()).configured shouldBe false
    }

    @Test
    fun `a note type chosen while Anki is off, in any card format, counts as set up`() {
        val imported = options(
            profileCurrent = 1,
            enable = false,
            formats = """[
                {"name":"Expression","type":"term","deck":"","model":"","fields":{}},
                {"name":"Reading","type":"term","deck":"Mining","model":"Basic","fields":{}}
            ]""",
        )

        AnkiCardSetup.current(imported) shouldBe
            AnkiCardSetup.Current(enabled = false, model = "Basic", deck = "Mining")
        AnkiCardSetup.current(imported).configured shouldBe true
    }

    private fun dictionary(name: String, enabled: Boolean = true) =
        Json.parseToJsonElement("""{"name":"$name","enabled":$enabled}""").jsonObject

    private companion object {
        /** Yomitan's three default card formats (`options-schema.json`). */
        const val DEFAULT_FORMATS = """[
            {"name":"Expression","icon":"big-circle","deck":"","model":"","fields":{},"type":"term"},
            {"name":"Reading","icon":"small-circle","deck":"","model":"","fields":{},"type":"term"},
            {"name":"Kanji","icon":"big-circle","deck":"","model":"","fields":{},"type":"kanji"}
        ]"""
    }
}

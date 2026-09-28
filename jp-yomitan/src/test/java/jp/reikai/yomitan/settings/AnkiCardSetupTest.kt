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
    ) =
        Json.parseToJsonElement(
            """
            {"profileCurrent": $profileCurrent, "profiles": [
              {"options": {"anki": {"enable": false, "cardFormats": []}, "dictionaries": []}},
              {"options": {"anki": {"enable": $enable, "cardFormats": $formats}, "dictionaries": $dictionaries}}
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
        fields.filterValues { it.isEmpty() }.keys.toList().shouldContainExactly(
            "DefinitionPicture", "SentenceFurigana", "SentenceAudio", "Picture", "Hint",
            "IsWordAndSentenceCard", "IsClickCard", "IsSentenceCard", "IsAudioCard",
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
        formats.map { it["name"]!!.jsonPrimitive.content }.shouldContainExactly("Expression", "Reading", "Kanji")
        formats[0]["deck"]!!.jsonPrimitive.content shouldBe "Mining"
        formats[0]["model"]!!.jsonPrimitive.content shouldBe "Lapis"
        formats[0]["fields"]!!.jsonObject["MainDefinition"]!!.jsonObject["value"]!!.jsonPrimitive.content shouldBe
            "{single-glossary-jitendexorg-2026-08-11}"
        formats[0]["fields"]!!.jsonObject["Expression"]!!.jsonObject["overwriteMode"]!!.jsonPrimitive.content shouldBe
            "coalesce"
        formats[1]["model"]!!.jsonPrimitive.content shouldBe ""
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

package jp.reikai.yomitan.settings

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class YomitanDictionariesTest {

    @Test
    fun `getDictionaryInfo's summaries become titles and counts`() {
        val info = Json.parseToJsonElement(
            """
            [
              {"title":"Jitendex.org [2026-08-11]","revision":"2026.08.11","importSuccess":true,
               "counts":{"terms":{"total":298110},"kanji":{"total":0},"media":{"total":12}}},
              {"title":"KANJIDIC","revision":"1","counts":{"terms":{"total":0},"kanji":{"total":13108}}},
              {"title":"half imported","importSuccess":false},
              {"revision":"no title"}
            ]
            """.trimIndent(),
        )

        YomitanDictionaries.parse(info).shouldContainExactly(
            InstalledDictionary("Jitendex.org [2026-08-11]", terms = 298110, kanji = 0, complete = true),
            InstalledDictionary("KANJIDIC", terms = 0, kanji = 13108, complete = true),
            InstalledDictionary("half imported", terms = 0, kanji = 0, complete = false),
        )
    }

    @Test
    fun `no dictionaries is an empty list`() {
        YomitanDictionaries.parse(Json.parseToJsonElement("[]")).shouldBeEmpty()
        YomitanDictionaries.parse(Json.parseToJsonElement("null")).shouldBeEmpty()
    }
}

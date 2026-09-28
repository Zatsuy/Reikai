package jp.reikai.yomitan.settings

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class YomitanSettingsTest {

    @Test
    fun `modifySettings answers are errors only where Yomitan says so`() {
        val answer = Json.parseToJsonElement(
            """[{"result": true}, {"error": {"message": "Invalid path: anki.cardFormats[9]"}}, {"error": {}}]""",
        )

        YomitanSettings.errors(answer).shouldContainExactly(
            "Invalid path: anki.cardFormats[9]",
            "Yomitan refused a setting",
        )
    }

    @Test
    fun `an answer of only results has no errors`() {
        YomitanSettings.errors(Json.parseToJsonElement("""[{"result": null}, {"result": 3}]""")).shouldBeEmpty()
    }
}

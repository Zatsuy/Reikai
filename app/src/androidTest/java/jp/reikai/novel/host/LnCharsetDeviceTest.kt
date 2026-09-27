package jp.reikai.novel.host

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import mihon.app.di.appGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import reikai.novel.host.LnPluginHost

/**
 * Pages that are not UTF-8 reach plugins as readable text in the real QuickJS host: the JS half of
 * [LnBodyDecoder] (fetchText's encoding argument, TextDecoder labels) only runs on a device. The
 * Aozora Bunko cases need the network; the page sends `text/html` with no charset and names
 * Shift_JIS only in its `<meta>`.
 */
@RunWith(AndroidJUnit4::class)
class LnCharsetDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun host() = LnPluginHost(context, context.appGraph.networkHelper, context.appGraph.preferenceStore)

    private suspend fun LnPluginHost.run(id: String, body: String): String {
        loadPlugin(
            id,
            """
            var fetchApi = require('@libs/fetch').fetchApi;
            var fetchText = require('@libs/fetch').fetchText;
            module.exports.default = {
              id: '$id', name: 'T', site: 'https://www.aozora.gr.jp', version: '1.0.0',
              parseChapter: async function () { $body },
            };
            """.trimIndent(),
        )
        return parseChapter(id, "/c")
    }

    @Test
    fun textDecoderDecodesAShiftJisLabel() = runBlocking {
        // 吾輩は猫である① in Shift_JIS (cp932: ① is outside the strict table).
        val bytes = "140,225,148,121,130,205,148,76,130,197,130,160,130,233,135,64"

        assertEquals(
            "吾輩は猫である①",
            host().run("td-sjis", "return new TextDecoder('shift_jis').decode(new Uint8Array([$bytes]));"),
        )
    }

    // EUC-JP's NEC row (①, Roman numerals): Android's ICU table must have it, as browsers do.
    @Test
    fun textDecoderDecodesTheEucJpNecRow() = runBlocking {
        assertEquals("①", host().run("td-eucjp", "return new TextDecoder('euc-jp').decode(new Uint8Array([173,161]));"))
    }

    @Test
    fun textDecoderStillDecodesUtf8WithoutALabel() = runBlocking {
        assertEquals(
            "猫",
            host().run("td-utf8", "return new TextDecoder().decode(new Uint8Array([231,140,171]));"),
        )
    }

    @Test
    fun fetchTextReadsAShiftJisPageNamedOnlyInItsMeta() = runBlocking {
        assertEquals(
            "夏目漱石 こころ",
            host().run("aozora-meta", "var h = await fetchText('$AOZORA'); return h.match(/<title>([^<]*)/)[1];"),
        )
    }

    // The page itself says Shift_JIS, so only a label that wins over it can garble the title.
    @Test
    fun fetchTextsEncodingArgumentWinsOverThePage() = runBlocking {
        assertNotEquals(
            "夏目漱石 こころ",
            host().run(
                "aozora-arg",
                "var h = await fetchText('$AOZORA', {}, 'EUC-JP'); return h.match(/<title>([^<]*)/)[1];",
            ),
        )
    }

    @Test
    fun arrayBufferKeepsTheRawShiftJisBytes() = runBlocking {
        assertEquals(
            "夏目漱石 こころ",
            host().run(
                "aozora-bytes",
                "var r = await fetchApi('$AOZORA'); var h = new TextDecoder('sjis').decode(await r.arrayBuffer());" +
                    " return h.match(/<title>([^<]*)/)[1];",
            ),
        )
    }

    private companion object {
        const val AOZORA = "https://www.aozora.gr.jp/cards/000148/files/773_14560.html"
    }
}

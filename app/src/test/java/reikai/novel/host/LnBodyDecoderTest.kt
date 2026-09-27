package reikai.novel.host

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.charset.Charset
import java.util.Base64

class LnBodyDecoderTest {

    private val sjis = Charset.forName("windows-31j")
    private val eucJp = Charset.forName("EUC-JP")
    private val gbk = Charset.forName("GBK")

    // ① is outside strict Shift_JIS: web novels use it, and browsers decode it.
    private val novel = "吾輩は猫である①"

    // EUC-JP as Java encodes it has no ①.
    private val plain = "吾輩は猫である"

    @Test
    fun `a page that names its charset only in a meta tag decodes with that charset`() {
        val page = """<html><head><meta charset="Shift_JIS"></head><body>$novel</body></html>"""

        LnBodyDecoder.decode(page.toByteArray(sjis), null, "text/html").text shouldContain novel
    }

    @Test
    fun `the http-equiv form of the meta tag is read too`() {
        val page = """<meta http-equiv="Content-Type" content="text/html; charset=EUC-JP"><p>$plain</p>"""

        LnBodyDecoder.decode(page.toByteArray(eucJp), null, null).text shouldContain plain
    }

    @Test
    fun `the header charset wins over the meta tag`() {
        val page = """<meta charset="Shift_JIS"><p>$plain</p>"""

        LnBodyDecoder.decode(page.toByteArray(eucJp), null, "text/html; charset=EUC-JP").text shouldContain plain
    }

    @Test
    fun `the plugin's label wins over the header`() {
        LnBodyDecoder.decode("你好".toByteArray(gbk), "gbk", "text/html; charset=utf-8").text shouldBe "你好"
    }

    @Test
    fun `an unknown label falls back to the header`() {
        LnBodyDecoder.decode(plain.toByteArray(eucJp), "no-such-charset", "text/html; charset=euc-jp").text shouldBe
            plain
    }

    @Test
    fun `a byte order mark wins over everything and is dropped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + novel.toByteArray()

        LnBodyDecoder.decode(bytes, "shift_jis", "text/html; charset=Shift_JIS").text shouldBe novel
    }

    @Test
    fun `a body that is not html is never sniffed`() {
        val json = """{"html":"<meta charset=Shift_JIS>","title":"$novel"}"""

        LnBodyDecoder.decode(json.toByteArray(), null, "application/json").text shouldBe json
    }

    @Test
    fun `a utf-8 page keeps no raw copy`() {
        LnBodyDecoder.decode(novel.toByteArray(), null, "text/html").rawBase64.shouldBeNull()
    }

    @Test
    fun `a page in another charset keeps its raw bytes for arrayBuffer`() {
        val bytes = """<meta charset="Shift_JIS">$novel""".toByteArray(sjis)

        Base64.getDecoder().decode(LnBodyDecoder.decode(bytes, null, "text/html").rawBase64)
            .toList() shouldBe bytes.toList()
    }

    @Test
    fun `text decoder labels decode through the same table`() {
        val base64 = Base64.getEncoder().encodeToString(novel.toByteArray(sjis))

        LnBodyDecoder.decodeBase64("shift_jis", base64) shouldBe novel
    }

    @ParameterizedTest
    @CsvSource(
        "Shift_JIS, windows-31j",
        "sjis, windows-31j",
        "x-sjis, windows-31j",
        "MS932, windows-31j",
        "euc-jp, x-eucJP-Open",
        "gb2312, GB18030",
        "gbk, GB18030",
        "UTF8, UTF-8",
        "latin1, windows-1252",
    )
    fun `labels resolve as browsers resolve them`(label: String, charset: String) {
        LnBodyDecoder.charsetFor(label)?.name() shouldBe charset
    }

    @Test
    fun `euc-jp decodes the circled digits of the NEC row`() {
        LnBodyDecoder.decode(byteArrayOf(0xAD.toByte(), 0xA1.toByte()), "euc-jp", null).text shouldBe "①"
    }

    @Test
    fun `a meta tag after a long head is still found`() {
        val page = """<head><script>${"x".repeat(4000)}</script><meta charset="Shift_JIS"></head><p>$novel</p>"""

        LnBodyDecoder.decode(page.toByteArray(sjis), null, "text/html").text shouldContain novel
    }

    @Test
    fun `an unknown header charset falls through to the meta tag`() {
        val page = """<meta charset="Shift_JIS"><p>$novel</p>"""

        LnBodyDecoder.decode(page.toByteArray(sjis), null, "text/html; charset=bogus").text shouldContain novel
    }

    @Test
    fun `a meta tag claiming utf-16 means utf-8`() {
        val page = """<meta charset="utf-16"><p>$novel</p>"""

        LnBodyDecoder.decode(page.toByteArray(), null, "text/html").text shouldContain novel
    }

    @Test
    fun `an xml feed's encoding declaration is read`() {
        val feed = """<?xml version="1.0" encoding="Shift_JIS"?><rss><title>$novel</title></rss>"""

        LnBodyDecoder.decode(feed.toByteArray(sjis), null, "application/rss+xml").text shouldContain novel
    }

    @Test
    fun `a text-only caller gets no raw copy`() {
        val bytes = """<meta charset="Shift_JIS">$novel""".toByteArray(sjis)

        LnBodyDecoder.decode(bytes, null, "text/html", keepRaw = false).rawBase64.shouldBeNull()
    }

    @Test
    fun `an unknown label resolves to nothing`() {
        LnBodyDecoder.charsetFor("no-such-charset").shouldBeNull()
    }
}

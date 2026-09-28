package jp.reikai.yomitan.audio

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import jp.reikai.yomitan.audio.LocalAudioRanking.Entry
import jp.reikai.yomitan.audio.LocalAudioRanking.Recording
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class LocalAudioRankingTest {

    private val entries = listOf(
        Entry("daijisen", "橋", "daijisen/kyou.mp3", "橋", "きょう"),
        Entry("zzz", "x", "zzz/hashi.mp3", "橋", "はし"),
        Entry("jpod", null, "jpod/hashi-chopsticks.mp3", "箸", "はし"),
        Entry("forvo", "user1", "forvo/hashi.opus", "橋", "はし"),
        Entry("nhk16", "橋 [はし]", "nhk16/hashi.mp3", "橋", "はし"),
        Entry("nhk16", "橋 [はし]", "nhk16/hashi.mp3", "橋", "はし"),
        Entry("taas", null, "taas/hashi.txt", "橋", "はし"),
        Entry("ozk5", "箸", "ozk5/other.mp3", "端", "はた"),
    )

    @Test
    fun `the word with its reading comes first, then homophones, then other readings, each in source order`() {
        LocalAudioRanking.rank(entries, "橋", "はし") shouldContainExactly listOf(
            Recording("NHK16 橋 [はし]", "nhk16", "nhk16/hashi.mp3"),
            Recording("Forvo (user1)", "forvo", "forvo/hashi.opus"),
            Recording("zzz x", "zzz", "zzz/hashi.mp3"),
            Recording("JPod101 (箸)", "jpod", "jpod/hashi-chopsticks.mp3"),
            Recording("Daijisen 橋 (きょう)", "daijisen", "daijisen/kyou.mp3"),
        )
    }

    @Test
    fun `a katakana reading matches the database's hiragana`() {
        LocalAudioRanking.rank(entries, "箸", "ハシ").map { it.file } shouldContainExactly listOf(
            "jpod/hashi-chopsticks.mp3",
            "nhk16/hashi.mp3",
            "forvo/hashi.opus",
            "zzz/hashi.mp3",
        )
    }

    @Test
    fun `without a reading only the word itself matches`() {
        LocalAudioRanking.rank(entries, "端", "").map { it.file } shouldContainExactly listOf("ozk5/other.mp3")
    }

    @ParameterizedTest
    @CsvSource("食べる 1.mp3", "a/b%c.opus", "ｶﾀｶﾅ〜.m4a", "plain.mp3")
    fun `file names survive the recording URL`(file: String) {
        LocalAudio.decode(LocalAudio.encode(file)) shouldBe file
    }

    @Test
    fun `a recording URL keeps only unreserved characters`() {
        LocalAudio.encode("食 1.mp3") shouldBe "%E9%A3%9F%201.mp3"
    }
}

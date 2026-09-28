package jp.reikai.reader.page

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

class JpReaderModesTest {

    private val japaneseText = "吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。" +
        "何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。"
    private val englishText = "It was a bright cold day in April, and the clocks were striking thirteen."

    // The library database and the plugin host are the boundaries; the rest is the real thing.
    private val novels = mapOf(
        KAKUYOMU_NOVEL to novel(KAKUYOMU_NOVEL, "kakuyomu", viewerFlags = ORIENTATION),
        ROYAL_ROAD_NOVEL to novel(ROYAL_ROAD_NOVEL, "royalroad"),
        MULTI_NOVEL to novel(MULTI_NOVEL, "multi"),
    )
    private val repository = mockk<NovelRepository> {
        coEvery { getById(any()) } answers { novels[firstArg()] }
        coEvery { update(any<NovelUpdate>()) } returns true
    }
    private val sources = mockk<NovelSourceManager> { coEvery { getWithoutPlugins(any()) } returns null }
    private val preferences = NovelPreferences(
        InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreference(
                    "ln_seen_novel_sources",
                    mapOf(
                        "kakuyomu" to LnSourceIdentity(name = "Kakuyomu", lang = "日本語"),
                        "royalroad" to LnSourceIdentity(name = "Royal Road", lang = "English"),
                        "multi" to LnSourceIdentity(name = "Many", lang = "Multi"),
                    ),
                    emptyMap<String, LnSourceIdentity>(),
                ),
            ),
        ),
    )
    private val modes = JpReaderModes(repository, sources, preferences, SetJpReaderChoice(repository))
    private val loader = Any()

    @Test
    fun `a source's language decides before any chapter is read`() = runTest {
        modes.decideEarly(KAKUYOMU_NOVEL) shouldBe true
        modes.decideEarly(ROYAL_ROAD_NOVEL) shouldBe false
        modes.holdsOneChapter(KAKUYOMU_NOVEL) shouldBe true
        modes.holdsOneChapter(ROYAL_ROAD_NOVEL) shouldBe false
    }

    @Test
    fun `a multi-language source waits for its first chapter and keeps that answer for the session`() = runTest {
        modes.bind(loader, MULTI_NOVEL)
        modes.decideEarly(MULTI_NOVEL) shouldBe null
        modes.known(MULTI_NOVEL) shouldBe null
        modes.decideForLoad(loader, MULTI_NOVEL, japaneseText) shouldBe true
        // A later chapter in English (an afterword) does not switch readers mid-session.
        modes.decideForLoad(loader, MULTI_NOVEL, englishText) shouldBe true
        modes.known(MULTI_NOVEL) shouldBe true
    }

    @Test
    fun `a chapter of a merged member is decided for the session it belongs to`() = runTest {
        modes.bind(loader, KAKUYOMU_NOVEL)
        modes.decideForLoad(loader, chapterNovelId = MULTI_NOVEL, raw = englishText) shouldBe true
        modes.known(KAKUYOMU_NOVEL) shouldBe true
        modes.known(MULTI_NOVEL) shouldBe null
    }

    @Test
    fun `a new session decides afresh`() = runTest {
        modes.decideEarly(KAKUYOMU_NOVEL) shouldBe true
        modes.bind(Any(), KAKUYOMU_NOVEL)
        modes.known(KAKUYOMU_NOVEL) shouldBe null
    }

    @Test
    fun `choosing a reader moves the session at once and is remembered in the novel's flags`() = runTest {
        modes.decideEarly(KAKUYOMU_NOVEL) shouldBe true
        var write: (suspend () -> Unit)? = null
        modes.choose(KAKUYOMU_NOVEL, japanese = false) { write = it }
        modes.known(KAKUYOMU_NOVEL) shouldBe false
        modes.switches.value shouldBe 1
        write!!.invoke()
        coVerify { repository.update(NovelUpdate(id = KAKUYOMU_NOVEL, viewerFlags = ORIENTATION or 0x200L)) }
    }

    private fun novel(id: Long, source: String, viewerFlags: Long = 0L) =
        Novel.create().copy(id = id, source = source, viewerFlags = viewerFlags)

    private companion object {
        const val KAKUYOMU_NOVEL = 1L
        const val ROYAL_ROAD_NOVEL = 2L
        const val MULTI_NOVEL = 3L

        /** Upstream's orientation bits, which a reader choice must leave alone. */
        const val ORIENTATION = 0x18L
    }
}

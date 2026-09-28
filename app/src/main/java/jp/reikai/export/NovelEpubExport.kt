package jp.reikai.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.util.storage.DiskUtil
import jp.reikai.reader.NovelCoverPicture
import jp.reikai.yomitan.text.JapaneseText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import reikai.data.coil.asNovelCover
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.hiddenKey
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.withCustomInfo
import reikai.novel.content.NovelContentConfig
import reikai.novel.content.NovelContentPipeline
import reikai.novel.content.NovelHtmlUtils
import reikai.novel.content.RenderTarget
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSourceManager
import reikai.novel.source.langCode
import reikai.novel.source.toLangCode
import tachiyomi.core.common.util.lang.withIOContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream

/**
 * "Export as EPUB" (roadmap 4.5, phase 4 ruling 16): a novel's downloaded chapters, cover and details
 * as an EPUB. The chapters are the ones its screen lists, in reading order (a merged novel's whole
 * group, as its stitch orders them; hidden chapters left out), each through the reader's own content
 * pipeline so the owner's replacement rules apply. A chapter that is not downloaded is skipped and
 * counted.
 */
@Inject
@SingleIn(AppScope::class)
class NovelEpubExport(
    private val context: Context,
    private val novelRepository: NovelRepository,
    private val chapterRepository: NovelChapterRepository,
    private val mergeManager: NovelMergeManager,
    private val mergedChapters: NovelMergedChapterProvider,
    private val getCustomNovelInfo: GetCustomNovelInfo,
    private val novelPreferences: NovelPreferences,
    private val sourceManager: NovelSourceManager,
    private val coverCache: CoverCache,
    // Building the manager restores the download queue, so it is made on first use, as the reader does.
    private val downloadManager: () -> NovelDownloadManager,
) {

    private val pipeline = NovelContentPipeline(novelPreferences)

    sealed interface Readiness {
        /** The novel is not in the database (a listing never opened). */
        data object Unknown : Readiness

        data object NothingDownloaded : Readiness

        data class Ready(val novelId: Long, val title: String, val fileName: String) : Readiness
    }

    /** Whether the novel of a details screen has anything to export, and the file name to offer. */
    suspend fun prepare(sourceId: String, novelUrl: String): Readiness = withIOContext {
        val stored = novelRepository.getByUrlAndSource(novelUrl, sourceId) ?: return@withIOContext Readiness.Unknown
        val novel = withCustomInfo(stored)
        val chapters = chaptersOf(novel)
        val owners = ownersOf(novel, chapters)
        val downloads = downloadManager()
        val any = chapters.any { chapter ->
            owners[chapter.novelId]?.let { downloads.isChapterDownloaded(it, chapter) } ==
                true
        }
        if (!any) return@withIOContext Readiness.NothingDownloaded
        val name = DiskUtil.buildValidFilename(novel.title, maxBytes = FILE_NAME_BYTES).ifBlank { "novel" }
        Readiness.Ready(novel.id, novel.title, "$name.epub")
    }

    /**
     * Writes the book to [uri]. [onProgress] hears each chapter looked at, written or skipped. Throws
     * when the file cannot be written; zero chapters exported leaves the file unfinished.
     */
    suspend fun write(novelId: Long, uri: Uri, onProgress: (done: Int, total: Int) -> Unit): ExportCounts =
        withIOContext {
            val novel = novelRepository.getById(novelId)?.let { withCustomInfo(it) } ?: error("The novel is gone")
            val chapters = chaptersOf(novel)
            val owners = ownersOf(novel, chapters)
            val downloads = downloadManager()
            val read: (NovelChapter) -> String? = { chapter ->
                owners[chapter.novelId]?.let { downloads.getChapterText(it, chapter) }
            }
            val book = EpubWriter.Book(
                identifier = EpubWriter.identifierFor(novel.source, novel.url),
                title = novel.title,
                language = languageOf(novel) { chapters.firstNotNullOfOrNull(read) },
                authors = listOfNotNull(novel.author, novel.artist).map(String::trim).distinct(),
                description = novel.description,
                subjects = novel.genre.orEmpty(),
            )
            // Truncated: a larger file picked to be overwritten would otherwise keep its old tail.
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("The file could not be opened")
            BufferedOutputStream(output).use { stream ->
                EpubWriter(stream, book, ::asJpeg).use { writer ->
                    coverOf(novel)?.let(writer::cover)
                    val counts = writeChapters(writer, chapters, read, ::prepareChapter, onProgress)
                    if (counts.exported > 0) writer.finish()
                    counts
                }
            }
        }

    private suspend fun withCustomInfo(novel: Novel): Novel =
        novel.withCustomInfo(getCustomNovelInfo.subscribe(novel.id).first())

    /** The chapters the novel's screen lists, in reading order: a merged novel's as its stitch orders them. */
    private suspend fun chaptersOf(novel: Novel): List<NovelChapter> {
        val ids = mergeManager.relatedIdsList(novel.id)
        val listed = if (ids.size <= 1) {
            chapterRepository.getByNovelId(novel.id).sortedBy { it.sourceOrder }
        } else {
            mergedChapters.merged(ids.flatMap { chapterRepository.getByNovelId(it) }, mergedChapters.stitchOf(novel.id))
        }
        val hidden = novelPreferences.hiddenChapters().get()
        if (hidden.isEmpty()) return listed
        val sources = listed.map { it.novelId }.distinct().associateWith { novelRepository.getById(it)?.source }
        return listed.filter { it.hiddenKey(sources) !in hidden }
    }

    /** Each chapter's own novel, which a merged group's chapters differ in. */
    private suspend fun ownersOf(novel: Novel, chapters: List<NovelChapter>): Map<Long, Novel> =
        chapters.map { it.novelId }.distinct()
            .mapNotNull { id -> (if (id == novel.id) novel else novelRepository.getById(id))?.let { id to it } }
            .toMap()

    /** Through the reader's pipeline for its WebView page, without the page's own CSS and scripts. */
    private fun prepareChapter(chapter: NovelChapter, raw: String): String {
        val config = NovelContentConfig.from(
            preferences = novelPreferences,
            target = RenderTarget.WEB_VIEW,
            chapterUrl = chapter.url,
            chapterName = chapter.name,
        ).copy(keepEmbeddedCss = false, keepEmbeddedJs = false)
        val processed = pipeline.process(raw, config)
        return if (processed.isPlainText) NovelHtmlUtils.plainTextToHtml(processed.text) else processed.text
    }

    /**
     * The source's language, as the reader decides it (the one recorded for the plugin, else an app
     * source's, else the loaded plugin's); a source of many languages or none by the first chapter's text.
     */
    private suspend fun languageOf(novel: Novel, sample: () -> String?): String {
        val declared = novelPreferences.seenNovelSources().get()[novel.source]?.lang?.toLangCode()
            ?: sourceManager.getWithoutPlugins(novel.source)?.langCode()
            ?: runCatching { sourceManager.get(novel.source)?.langCode() }.getOrNull()
        return declared?.takeIf(::isLanguageTag)
            ?: if (sample()?.let(JapaneseText::looksJapanese) == true) "ja" else "und"
    }

    /** The user's own cover, else the library's cached one, else through the image loader (maybe the network). */
    private suspend fun coverOf(novel: Novel): ByteArray? {
        coverCache.getCustomCoverFile(EntryId.Novel(novel.id)).takeIf { it.exists() }?.let { return it.readBytes() }
        // The plugins' "no cover" picture is no cover.
        if (novel.thumbnailUrl in NovelCoverPicture.PLACEHOLDERS) return null
        coverCache.getCoverFile(novel.thumbnailUrl)?.takeIf { it.exists() }?.let { return it.readBytes() }
        return runCatching { NovelCoverPicture(context, novel.asNovelCover(), coverCache).jpeg(COVER_SIZE) }.getOrNull()
    }

    /** A picture EPUB readers need not show (AVIF, BMP, HEIF) as a JPEG, when Android can read it. */
    private fun asJpeg(bytes: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private companion object {
        const val COVER_SIZE = 1600
        const val FILE_NAME_BYTES = 200
        const val JPEG_QUALITY = 90

        /** Codes a source gives for many languages or none, which are no language of a book. */
        val NOT_A_LANGUAGE = setOf("all", "multi", "mul", "other", "und", "zxx")
        val LANGUAGE_TAG = Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")

        fun isLanguageTag(code: String) = code.lowercase() !in NOT_A_LANGUAGE && LANGUAGE_TAG.matches(code)
    }
}

/** How many chapters went into a book, and how many were left out because they are not downloaded. */
data class ExportCounts(val exported: Int, val skipped: Int)

/**
 * Writes [chapters] in order: each one [read] gives through [prepare] into [writer], each one it has
 * no text for (not downloaded) counted as skipped.
 */
internal suspend fun writeChapters(
    writer: EpubWriter,
    chapters: List<NovelChapter>,
    read: (NovelChapter) -> String?,
    prepare: (NovelChapter, String) -> String,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): ExportCounts {
    var skipped = 0
    chapters.forEachIndexed { index, chapter ->
        currentCoroutineContext().ensureActive()
        val raw = read(chapter)
        if (raw == null) skipped++ else writer.chapter(chapter.name, prepare(chapter, raw))
        onProgress(index + 1, chapters.size)
    }
    return ExportCounts(exported = chapters.size - skipped, skipped = skipped)
}

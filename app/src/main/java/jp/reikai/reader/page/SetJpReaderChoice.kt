package jp.reikai.reader.page

import dev.zacsweers.metro.Inject
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelUpdate

/**
 * Writes a novel's reader choice ([JpReaderChoice], `viewer_flags` bits `0x300`), the way upstream's
 * `SetNovelViewerFlags` writes the orientation bits: read the flags, replace the masked bits, write
 * only the column.
 */
@Inject
class SetJpReaderChoice(private val novelRepository: NovelRepository) {

    suspend fun await(novelId: Long, choice: JpReaderChoice) {
        val novel = novelRepository.getById(novelId) ?: return
        novelRepository.update(
            NovelUpdate(id = novelId, viewerFlags = JpReaderChoice.withChoice(novel.viewerFlags, choice)),
        )
    }
}

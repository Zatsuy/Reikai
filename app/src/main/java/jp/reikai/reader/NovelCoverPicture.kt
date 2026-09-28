package jp.reikai.reader

import android.content.Context
import android.graphics.Bitmap
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import eu.kanade.tachiyomi.data.cache.CoverCache
import jp.reikai.yomitan.LookupPicture
import reikai.data.coil.NovelCover
import reikai.domain.entry.EntryId
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * The novel's cover as the picture of a word looked up in it (Anki's `{screenshot}`, 4.3), through
 * the app's image loader: the user's own cover if set, else the library's cover cache, else the
 * source. A novel whose source gave only the plugins' "no cover" picture has none. A data class, so
 * two lookups in the same novel carry equal pictures (the sheet compares a lookup made ahead with the
 * one asked for).
 */
internal data class NovelCoverPicture(
    private val context: Context,
    private val cover: NovelCover,
    private val coverCache: CoverCache,
) : LookupPicture {

    override suspend fun jpeg(maxSize: Int): ByteArray? {
        if (cover.url in PLACEHOLDERS && !coverCache.getCustomCoverFile(EntryId.Novel(cover.novelId)).exists()) {
            return null
        }
        val request = ImageRequest.Builder(context)
            .data(cover)
            // Read as pixels, never as a hardware bitmap.
            .allowHardware(false)
            .size(maxSize)
            .build()
        val image = context.imageLoader.execute(request).image ?: return null
        if (image.width <= 0 || image.height <= 0) return null
        val scale = minOf(1f, maxSize.toFloat() / maxOf(image.width, image.height))
        val bitmap = image.toBitmap(
            (image.width * scale).roundToInt().coerceAtLeast(1),
            (image.height * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        return ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
            out.toByteArray()
        }
    }

    private companion object {
        const val JPEG_QUALITY = 85

        /**
         * What LNReader plugins name as the cover of a novel without one: the plugin host's
         * `defaultCover` (`assets/lnhost/headless.js`), and LNReader's own.
         */
        val PLACEHOLDERS = setOf(
            "https://github.com/LNReader/lnreader-sources/blob/main/icons/no-cover.jpg?raw=true",
            "https://github.com/LNReader/lnreader-plugins/blob/main/icons/src/coverNotAvailable.jpg?raw=true",
        )
    }
}

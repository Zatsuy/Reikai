package jp.reikai.yomitan.anki

import android.content.Context
import androidx.core.content.FileProvider

/**
 * Shares the media files Yomitan stores on a card (audio, pictures) with AnkiDroid, which copies them
 * into its collection; the file is deleted once AnkiDroid has it. Reikai JP's own provider, so the
 * engine does not depend on the paths upstream's FileProvider happens to expose, and it exposes one
 * cache directory only (`res/xml/jp_anki_media_paths.xml`).
 */
class AnkiMediaProvider : FileProvider() {
    internal companion object {
        const val DIRECTORY = "jp-anki-media"

        fun authority(context: Context) = "${context.packageName}.jp-anki-media"
    }
}

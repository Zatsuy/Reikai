package jp.reikai.reader.page

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import jp.reikai.JpPreferences
import jp.reikai.yomitan.R

/**
 * The first time the Japanese reader opens (D-026): it reads vertically, like a printed book, and
 * horizontal text is one tap away. Once, whatever the answer.
 */
internal object JpReaderIntro {

    fun showOnce(context: Context, preferences: JpPreferences, vertical: Boolean) {
        val shown = preferences.readerIntroShown()
        if (shown.get()) return
        shown.set(true)
        if (!vertical) return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.jp_reader_intro_title)
            .setMessage(R.string.jp_reader_intro_body)
            .setPositiveButton(R.string.jp_reader_intro_keep, null)
            .setNegativeButton(R.string.jp_reader_intro_horizontal) { _, _ ->
                preferences.readerWriting().set("horizontal")
            }
            .show()
    }
}

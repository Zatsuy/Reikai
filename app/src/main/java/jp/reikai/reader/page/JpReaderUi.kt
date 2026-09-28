package jp.reikai.reader.page

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import jp.reikai.di.jpGraph
import jp.reikai.translate.jpTranslateMenuAction
import jp.reikai.yomitan.R
import reikai.novel.font.NovelFont
import tachiyomi.core.common.preference.Preference
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.util.collectAsState

/**
 * The fork's items in the reader's top bar overflow menu: "Translate chapter" or "Show original" (4.5)
 * and the switch between the readers; none outside a novel reader.
 */
@Composable
fun jpReaderMenuActions(): List<AppBar.OverflowAction> = listOfNotNull(jpTranslateMenuAction(), jpReaderMenuAction())

/**
 * The reader menu's switch between the Japanese reader and the standard one (D-003: clearly labelled,
 * one tap back); null outside a novel reader.
 */
@Composable
private fun jpReaderMenuAction(): AppBar.OverflowAction? {
    val activity = LocalActivity.current ?: return null
    val switch = remember(activity) { JpPageHook.switchOf(activity) } ?: return null
    val japanese by switch.isJapanese.collectAsState()
    val current = japanese ?: return null
    val title =
        stringResource(if (current) R.string.jp_reader_switch_to_standard else R.string.jp_reader_switch_to_japanese)
    return AppBar.OverflowAction(title = title, onClick = { switch.choose(!current) })
}

/**
 * The reader settings sheet's rows for the Japanese reader, under "For this series": which reader this
 * novel opens in, and, while it is the Japanese one, its own settings, pages or scrolling first. Both
 * readers end with the status bar and "mark chapters that fit on one screen as read" (4.5). Everything
 * after the reader choice applies to every novel, under a heading that says so.
 */
@Composable
fun JpReaderSettingsRows(installedFonts: suspend () -> List<NovelFont>) {
    val activity = LocalActivity.current ?: return
    val switch = remember(activity) { JpPageHook.switchOf(activity) } ?: return
    val japanese by switch.isJapanese.collectAsState()
    val current = japanese ?: return
    ChipRow(R.string.jp_reader_reader) {
        FilterChip(
            selected = current,
            onClick = { switch.choose(true) },
            label = { Text(stringResource(R.string.jp_reader_japanese)) },
        )
        FilterChip(
            selected = !current,
            onClick = { switch.choose(false) },
            label = { Text(stringResource(R.string.jp_reader_standard)) },
        )
    }
    val preferences = remember(activity) { activity.jpGraph.jpPreferences }
    if (!current) {
        // The standard reader's own: its status bar (off by default) and short chapters (shared). Both apply
        // to every novel, which their heading says, since they sit under upstream's "For this series".
        Column {
            HeadingItem(stringResource(R.string.jp_reader_all_novels))
            CheckboxItem(stringResource(R.string.jp_reader_status_bar), preferences.standardStatusBar())
            CheckboxItem(stringResource(R.string.jp_reader_short_chapters), preferences.shortChaptersRead())
        }
        return
    }
    Column {
        HeadingItem(stringResource(R.string.jp_reader_settings))
        ChoiceRow(
            R.string.jp_reader_layout,
            preferences.readerLayout(),
            listOf("paged" to R.string.jp_reader_layout_paged, "scroll" to R.string.jp_reader_layout_scroll),
        )
        ChoiceRow(
            R.string.jp_reader_writing,
            preferences.readerWriting(),
            listOf(
                "vertical" to R.string.jp_reader_writing_vertical,
                "horizontal" to R.string.jp_reader_writing_horizontal,
            ),
        )
        ChoiceRow(
            R.string.jp_reader_furigana,
            preferences.readerFurigana(),
            listOf(
                "show" to R.string.jp_reader_furigana_show,
                "partial" to R.string.jp_reader_furigana_partial,
                "full" to R.string.jp_reader_furigana_full,
                "toggle" to R.string.jp_reader_furigana_toggle,
                "hide" to R.string.jp_reader_furigana_hide,
            ),
        )
        FontRow(preferences.readerFont(), installedFonts)
        ChoiceRow(
            R.string.jp_reader_tap,
            preferences.readerTap(),
            listOf("lookup" to R.string.jp_reader_tap_lookup, "zones" to R.string.jp_reader_tap_zones),
        )
        CheckboxItem(stringResource(R.string.jp_reader_status_bar), preferences.readerStatusBar())
        CheckboxItem(stringResource(R.string.jp_reader_short_chapters), preferences.shortChaptersRead())
    }
}

@Composable
private fun ChoiceRow(@StringRes label: Int, preference: Preference<String>, choices: List<Pair<String, Int>>) {
    val value by preference.collectAsState()
    ChipRow(label) {
        choices.forEach { (key, name) ->
            FilterChip(
                selected = value == key,
                onClick = { preference.set(key) },
                label = { Text(stringResource(name)) },
            )
        }
    }
}

@Composable
private fun FontRow(preference: Preference<String>, installedFonts: suspend () -> List<NovelFont>) {
    val chosen by preference.collectAsState()
    val installed by produceState(emptyList<NovelFont>()) { value = installedFonts() }
    ChipRow(R.string.jp_reader_font) {
        FilterChip(
            selected = chosen == JpPageOptions.FONT_MINCHO,
            onClick = { preference.set(JpPageOptions.FONT_MINCHO) },
            label = { Text(stringResource(R.string.jp_reader_font_mincho)) },
        )
        FilterChip(
            selected = chosen == JpPageOptions.FONT_GOTHIC,
            onClick = { preference.set(JpPageOptions.FONT_GOTHIC) },
            label = { Text(stringResource(R.string.jp_reader_font_gothic)) },
        )
        installed.forEach { font ->
            FilterChip(
                selected = chosen == font.fileName,
                onClick = { preference.set(font.fileName) },
                label = { Text(font.displayName) },
            )
        }
    }
}

/** Upstream's `SettingsChipRow`, for a label in the fork's own strings. */
@Composable
private fun ChipRow(@StringRes label: Int, content: @Composable () -> Unit) {
    Column {
        HeadingItem(stringResource(label))
        FlowRow(
            modifier = Modifier.padding(
                start = SettingsItemsPaddings.Horizontal,
                end = SettingsItemsPaddings.Horizontal,
                bottom = SettingsItemsPaddings.Vertical,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

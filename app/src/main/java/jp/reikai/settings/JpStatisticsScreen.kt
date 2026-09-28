package jp.reikai.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import jp.reikai.stats.JpStatisticsSummary
import jp.reikai.stats.JpStatisticsViewModel
import jp.reikai.yomitan.R
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.material.Scaffold
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Settings, Japanese, Reading statistics (roadmap 4.4): what the Japanese reader counted, today, over the
 * last seven days and in all, the last two weeks day by day, each novel's totals, and the export for ttu.
 * Plain Compose: a row per day with a bar as long as its share of the busiest day.
 */
class JpStatisticsScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = metroViewModel<JpStatisticsViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        LifecycleStartEffect(model) {
            model.refresh()
            onStopOrDispose {}
        }
        LaunchedEffect(model) {
            model.events.collect { event ->
                val text = when (event) {
                    is JpStatisticsViewModel.Event.Exported -> context.resources.getQuantityString(
                        R.plurals.jp_stats_export_done,
                        event.novels,
                        event.novels,
                    )
                    is JpStatisticsViewModel.Event.Failed -> context.getString(
                        R.string.jp_stats_export_failed,
                        event.reason,
                    )
                }
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
        val exportTo =
            rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                uri?.let(model::export)
            }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(R.string.jp_stats_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            val summary = state.summary
            if (summary == null) {
                Box(Modifier.fillMaxWidth().padding(contentPadding).padding(32.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }
            val numbers = remember { NumberFormat.getIntegerInstance() }
            LazyColumn(contentPadding = contentPadding) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Totals(context, numbers, stringResource(R.string.jp_stats_today), summary.today)
                        Totals(context, numbers, stringResource(R.string.jp_stats_week), summary.week)
                        Totals(context, numbers, stringResource(R.string.jp_stats_all_time), summary.allTime)
                    }
                }
                if (summary.isEmpty) {
                    item { Note(stringResource(R.string.jp_stats_empty)) }
                } else {
                    item { HeadingItem(stringResource(R.string.jp_stats_days)) }
                    val busiest = summary.days.maxOf { it.totals.characters }.coerceAtLeast(1)
                    items(summary.days, key = { it.date.toEpochDay() }) { day ->
                        DayRow(context, numbers, day, busiest)
                    }
                    item { HeadingItem(stringResource(R.string.jp_stats_novels)) }
                    items(summary.novels, key = { it.title }) { novel -> NovelRow(context, numbers, novel) }
                }
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                        Button(
                            onClick = {
                                try {
                                    exportTo.launch("reikai-jp-statistics-${LocalDate.now()}.zip")
                                } catch (_: ActivityNotFoundException) {
                                    context.toast(MR.strings.file_picker_error)
                                }
                            },
                            enabled = !summary.isEmpty && !state.exporting,
                        ) { Text(stringResource(R.string.jp_stats_export)) }
                        Text(
                            text = stringResource(R.string.jp_stats_export_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                item { Note(stringResource(R.string.jp_stats_how)) }
            }
        }
    }

    @Composable
    private fun Totals(context: Context, numbers: NumberFormat, label: String, totals: JpStatisticsSummary.Totals) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(120.dp))
            Column {
                Text(
                    text = stringResource(R.string.jp_stats_characters, numbers.format(totals.characters)) +
                        "  ·  " + duration(context, totals.seconds),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.jp_stats_speed, numbers.format(totals.speed)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun DayRow(context: Context, numbers: NumberFormat, day: JpStatisticsSummary.Day, busiest: Long) {
        val pattern = remember { android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM") }
        val format = remember(pattern) { DateTimeFormatter.ofPattern(pattern) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(day.date.format(format), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
            Box(Modifier.weight(1f).height(12.dp)) {
                val share = day.totals.characters.toFloat() / busiest
                if (share > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(share)
                            .height(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Text(
                text = if (day.totals.seconds == 0L && day.totals.characters == 0L) {
                    "–"
                } else {
                    numbers.format(day.totals.characters) + " · " + duration(context, day.totals.seconds)
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(128.dp),
            )
        }
    }

    @Composable
    private fun NovelRow(context: Context, numbers: NumberFormat, novel: JpStatisticsSummary.Novel) {
        val format = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                novel.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.jp_stats_novel_line,
                    numbers.format(novel.totals.characters),
                    duration(context, novel.totals.seconds),
                    numbers.format(novel.totals.speed),
                    novel.lastRead.format(format),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun Note(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    private fun duration(context: Context, seconds: Long): String {
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        val rest = seconds % 60
        return when {
            hours > 0 -> context.getString(R.string.jp_stats_hours_minutes, hours.toInt(), minutes.toInt())
            minutes > 0 -> context.getString(R.string.jp_stats_minutes_seconds, minutes.toInt(), rest.toInt())
            else -> context.getString(R.string.jp_stats_seconds, rest.toInt())
        }
    }
}

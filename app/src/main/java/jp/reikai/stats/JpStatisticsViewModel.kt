package jp.reikai.stats

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.time.LocalDate

/**
 * The reading statistics screen (roadmap 4.4): the summary of every day read, read again each time the
 * screen shows (reading goes on in the reader meanwhile), and the export for ttu.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class JpStatisticsViewModel(
    private val statistics: JpReadingStatistics,
    private val context: Context,
) : ViewModel() {

    data class State(
        /** Null while reading. */
        val summary: JpStatisticsSummary? = null,
        val exporting: Boolean = false,
    )

    sealed interface Event {
        data class Exported(val novels: Int) : Event

        data class Failed(val reason: String) : Event
    }

    val state: StateFlow<State>
        field = MutableStateFlow(State())

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    fun refresh() {
        viewModelScope.launch {
            val rows = statistics.all()
            val summary = withContext(Dispatchers.Default) { JpStatisticsSummary.of(rows, LocalDate.now()) }
            state.update { it.copy(summary = summary) }
        }
    }

    /** Writes every novel's statistics to [uri] as ttu's backup zip. */
    fun export(uri: Uri) {
        if (state.value.exporting) return
        state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val event = runCatching {
                // The newest counts too, written or not.
                statistics.flush()
                val rows = statistics.all().map { it.statistic }
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("The file could not be opened")
                    TtuExport.write(rows, output)
                }
            }.fold(
                onSuccess = { Event.Exported(it) },
                onFailure = {
                    logcat(LogPriority.WARN, it) { "Could not export reading statistics" }
                    Event.Failed(it.message ?: it::class.java.simpleName)
                },
            )
            state.update { it.copy(exporting = false) }
            eventChannel.send(event)
        }
    }
}

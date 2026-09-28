package jp.reikai.settings

import android.app.Activity
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import jp.reikai.lookup.JpLookup
import jp.reikai.yomitan.YomitanEngine
import jp.reikai.yomitan.anki.AnkiAccess
import jp.reikai.yomitan.audio.LocalAudio
import jp.reikai.yomitan.audio.TtsAudio
import jp.reikai.yomitan.audio.YomitanAudioSources
import jp.reikai.yomitan.settings.AnkiCardSetup
import jp.reikai.yomitan.settings.InstalledDictionary
import jp.reikai.yomitan.settings.YomitanDictionaries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * The Japanese settings (roadmap 3.5): what the screen shows about lookup, dictionaries, AnkiDroid
 * and word audio, read from Yomitan's own settings through the engine while the screen is visible
 * ([onStart] to [onStop]), and the changes it makes there. The engine starts only while lookup is on
 * (D-025).
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class JapaneseSettingsViewModel(
    private val lookup: JpLookup,
    private val ankiAccess: AnkiAccess,
    private val localAudio: LocalAudio,
    private val ttsAudio: TtsAudio,
) : ViewModel() {

    /** What the Anki card row offers. */
    sealed interface Cards {
        /** Not known yet (AnkiDroid not connected, or still reading). */
        data object Unknown : Cards

        /** Yomitan adds [model] cards into [deck]. */
        data class Configured(val model: String, val deck: String) : Cards

        /** No card format yet, and AnkiDroid has the Lapis note type: one tap sets it up. */
        data class OfferLapis(val lapis: AnkiAccess.NoteType, val decks: List<String>) : Cards

        /** No card format yet and no Lapis: the choice is made in Yomitan's Anki settings. */
        data object ChooseInYomitan : Cards
    }

    data class State(
        val lookupOn: Boolean,
        /** Lookup was switched off while the app ran: its memory is freed only by a restart. */
        val switchedOff: Boolean = false,
        val engineFailed: String? = null,
        /** Null while unknown. */
        val dictionaries: List<InstalledDictionary>? = null,
        val anki: AnkiAccess.Status? = null,
        val cards: Cards = Cards.Unknown,
        val localAudio: LocalAudio.Status = LocalAudio.Status.None,
        val localAudioBytes: Long? = null,
        /** Whether Yomitan's current profile lists text-to-speech; null while unknown. */
        val ttsListed: Boolean? = null,
        val tts: TtsAudio.Status? = null,
    )

    /** One-off results for a message (a toast). */
    sealed interface Event {
        data class LapisReady(val deck: String) : Event

        data class Failed(val reason: String) : Event
    }

    val state: StateFlow<State>
        field = MutableStateFlow(State(lookupOn = lookup.isEnabled))

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    private val engine: YomitanEngine get() = lookup.engine
    private var host: Activity? = null
    private var lease: YomitanEngine.Lease? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            lookup.lookupChanges().distinctUntilChanged().drop(1).collect { on ->
                state.update { it.copy(lookupOn = on, switchedOff = it.switchedOff || !on) }
                if (on) host?.let(::onStart) else release()
            }
        }
        viewModelScope.launch {
            localAudio.status.collect { status ->
                state.update { it.copy(localAudio = status) }
                if (status is LocalAudio.Status.Ready || status == LocalAudio.Status.Closed) {
                    val bytes = localAudio.size()
                    state.update { it.copy(localAudioBytes = bytes) }
                }
            }
        }
        viewModelScope.launch {
            engine.state.collect { engineState ->
                val failed = (engineState as? YomitanEngine.State.Failed)?.reason
                state.update { it.copy(engineFailed = failed) }
            }
        }
    }

    /** The screen is visible in [activity]: hold the engine (lookup on) and read everything afresh. */
    fun onStart(activity: Activity) {
        host = activity
        if (lease == null) lease = engine.acquire(activity)
        refresh()
    }

    /** The screen is no longer visible: the engine may stop once nothing else uses it. */
    fun onStop() {
        host = null
        release()
    }

    private fun release() {
        refreshJob?.cancel()
        lease?.close()
        lease = null
    }

    /** Reads AnkiDroid's state, and with the engine the dictionaries and Yomitan's card and audio settings. */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val anki = withContext(Dispatchers.IO) { ankiAccess.status() }
            state.update { it.copy(anki = anki) }
            // Asking for the voice starts a text-to-speech engine: only while its row can be used.
            if (lease == null) return@launch
            launch {
                val tts = ttsAudio.status()
                state.update { it.copy(tts = tts) }
            }
            if (!engine.ready()) return@launch
            runCatching { YomitanDictionaries.installed(engine) }
                .onSuccess { list -> state.update { it.copy(dictionaries = list) } }
                .onFailure { logcat(LogPriority.WARN, it) { "Japanese settings: dictionaries" } }
            runCatching { YomitanAudioSources.listed(engine) }
                .onSuccess { (_, tts) -> state.update { it.copy(ttsListed = tts) } }
            val cards = if (anki == AnkiAccess.Status.READY) cards() else Cards.Unknown
            state.update { it.copy(cards = cards) }
        }
    }

    private suspend fun cards(): Cards = try {
        val current = AnkiCardSetup.current(engine)
        if (current.configured) {
            Cards.Configured(current.model, current.deck)
        } else {
            val (types, decks) = withContext(Dispatchers.IO) { ankiAccess.noteTypes() to ankiAccess.deckNames() }
            types.firstOrNull { it.name == AnkiCardSetup.LAPIS }
                ?.let { Cards.OfferLapis(it, decks) }
                ?: Cards.ChooseInYomitan
        }
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "Japanese settings: cards" }
        Cards.Unknown
    }

    /** "Set up cards for Lapis" into [deck]. */
    fun setUpLapis(lapis: AnkiAccess.NoteType, deck: String) {
        viewModelScope.launch {
            runCatching { AnkiCardSetup.setUpLapis(engine, deck, lapis) }
                .onSuccess { eventChannel.send(Event.LapisReady(deck)) }
                .onFailure { eventChannel.send(Event.Failed(it.message ?: it.javaClass.simpleName)) }
            refresh()
        }
    }

    /** The user picked a local audio file: Yomitan plays it first, and the app copies it in the background. */
    fun pickLocalAudio(uri: Uri) {
        localAudio.pick(uri)
        applyAudio(local = true, tts = state.value.ttsListed == true)
    }

    fun removeLocalAudio() {
        viewModelScope.launch {
            localAudio.clear()
            applyAudio(local = false, tts = state.value.ttsListed == true)
        }
    }

    fun setTextToSpeech(on: Boolean) {
        state.update { it.copy(ttsListed = on) }
        applyAudio(local = state.value.localAudio != LocalAudio.Status.None, tts = on)
    }

    private fun applyAudio(local: Boolean, tts: Boolean) {
        viewModelScope.launch {
            if (lease == null || !engine.ready()) return@launch
            runCatching { YomitanAudioSources.apply(engine, localAudio = local, textToSpeech = tts) }
                .onFailure { eventChannel.send(Event.Failed(it.message ?: it.javaClass.simpleName)) }
            refresh()
        }
    }

    override fun onCleared() {
        release()
    }
}

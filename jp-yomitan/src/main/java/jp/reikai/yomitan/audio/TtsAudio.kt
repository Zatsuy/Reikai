package jp.reikai.yomitan.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.LruCache
import jp.reikai.yomitan.LocalResponse
import jp.reikai.yomitan.LocalRoute
import jp.reikai.yomitan.LocalServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import logcat.logcat
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Text-to-speech as a Yomitan audio source of type custom, [SOURCE_URL], last in the list: a word no
 * recording has is spoken by the device's Japanese voice, and because it is an ordinary audio file
 * (WAV from `TextToSpeech.synthesizeToFile`) it also reaches Anki cards, which Yomitan's own
 * text-to-speech source (the browser's `speechSynthesis`) never does.
 *
 * Without a Japanese voice (or when speaking fails) the route answers 404, which Yomitan treats as
 * "no audio from this source" and moves on; with no other source it plays its fallback sound. The engine is bound on the first request and released after [IDLE_MILLIS].
 */
class TtsAudio(context: Context) {

    sealed interface Status {
        /** A Japanese voice speaks (engine package, voice name). */
        data class Ready(val engine: String, val voice: String?) : Status

        /** The TTS engine has no Japanese voice installed (or its data is missing). */
        data class NoJapaneseVoice(val engine: String) : Status

        /** No TTS engine on the device, or it would not start. */
        data object NoEngine : Status
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var tts: TextToSpeech? = null
    private var ready: Status? = null
    private var idle: Job? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val cache = LruCache<String, ByteArray>(CACHE_ENTRIES)
    private var next = 0

    /** The local server's route for `/tts/get/?term=&reading=`. */
    val route: LocalRoute = LocalRoute.get(PREFIX) { request ->
        val text = request.query["reading"]?.takeIf { it.isNotBlank() } ?: request.query["term"].orEmpty()
        val audio = if (text.isBlank()) null else speak(text.trim())
        audio?.let { LocalResponse(contentType = "audio/wav", body = it) } ?: LocalAudio.NOT_FOUND
    }

    /** Whether a Japanese voice is there (binds the engine for a moment if it is not bound). */
    suspend fun status(): Status = mutex.withLock { engineLocked().second }

    private suspend fun speak(text: String): ByteArray? {
        cache.get(text)?.let { return it }
        return mutex.withLock {
            val (engine, status) = engineLocked()
            if (engine == null || status !is Status.Ready) return@withLock null
            val file = File(app.cacheDir, "jp-tts/${next++}.wav").apply { parentFile?.mkdirs() }
            val id = file.name
            val done = CompletableDeferred<Boolean>().also { pending[id] = it }
            try {
                val queued = withContext(Dispatchers.IO) { engine.synthesizeToFile(text, Bundle(), file, id) }
                val ok =
                    queued == TextToSpeech.SUCCESS && withTimeoutOrNull(SPEAK_TIMEOUT_MILLIS) { done.await() } == true
                if (!ok) logcat(TAG, LogPriority.WARN) { "tts: could not speak $text" }
                file.takeIf { ok && it.length() > WAV_HEADER }?.readBytes()?.also { cache.put(text, it) }
            } finally {
                pending.remove(id)
                file.delete()
            }
        }
    }

    /**
     * The bound engine and whether it speaks Japanese; binds it when needed. Holds [mutex]. Only the
     * binding runs on the main thread (its callback arrives there); the engine's calls are IPC to the
     * TTS app and run on the IO dispatcher.
     */
    private suspend fun engineLocked(): Pair<TextToSpeech?, Status> {
        idle?.cancel()
        idle = scope.launch {
            delay(IDLE_MILLIS)
            mutex.withLock { shutdownLocked() }
        }
        tts?.let { engine -> ready?.let { return engine to it } }
        val started = CompletableDeferred<Int>()
        val engine = withContext(Dispatchers.Main) { TextToSpeech(app) { started.complete(it) } }
        val init = withTimeoutOrNull(START_TIMEOUT_MILLIS) { started.await() }
        if (init != TextToSpeech.SUCCESS) {
            withContext(Dispatchers.IO) { engine.shutdown() }
            logcat(TAG, LogPriority.WARN) { "tts: no engine (init $init)" }
            return null to Status.NoEngine
        }
        val status = withContext(Dispatchers.IO) {
            engine.setOnUtteranceProgressListener(Listener())
            val name = engine.defaultEngine.orEmpty()
            when (engine.setLanguage(Locale.JAPAN)) {
                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> Status.NoJapaneseVoice(name)
                else -> Status.Ready(name, runCatching { engine.voice?.name }.getOrNull())
            }
        }
        logcat(TAG) { "tts: $status" }
        tts = engine
        ready = status
        return engine to status
    }

    private suspend fun shutdownLocked() {
        val engine = tts ?: return
        tts = null
        ready = null
        withContext(Dispatchers.IO) { engine.shutdown() }
    }

    private inner class Listener : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            pending[utteranceId]?.complete(true)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            pending[utteranceId]?.complete(false)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            pending[utteranceId]?.complete(false)
        }
    }

    companion object {
        /** Yomitan's audio source for text-to-speech (type custom). */
        const val SOURCE_URL = "http://localhost:${LocalServer.PORT}/tts/get/?term={term}&reading={reading}"

        const val PREFIX = "/tts/"
        private const val TAG = "Yomitan"
        private const val IDLE_MILLIS = 60_000L
        private const val START_TIMEOUT_MILLIS = 5_000L
        private const val SPEAK_TIMEOUT_MILLIS = 10_000L
        private const val CACHE_ENTRIES = 8

        /** A WAV file with no samples is only its header. */
        private const val WAV_HEADER = 44L
    }
}

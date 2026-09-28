package jp.reikai.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.LocalSettingsIndexing
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import jp.reikai.di.jpGraph
import jp.reikai.lookup.DictionaryShortcut
import jp.reikai.settings.JapaneseSettingsViewModel.Cards
import jp.reikai.yomitan.R
import jp.reikai.yomitan.anki.AnkiAccess
import jp.reikai.yomitan.audio.LocalAudio
import jp.reikai.yomitan.audio.TtsAudio

/**
 * Settings, Japanese (roadmap 3.5): the lookup switch (D-025), dictionaries, AnkiDroid and card
 * set-up, word audio, Yomitan's own settings, the reading statistics (4.4) and chapter translation (4.5). Everything a Japanese learner sets up once, in the
 * order they need it: get dictionaries, connect AnkiDroid, set up cards, then optional audio and a
 * desktop Yomitan backup. Yomitan's own pages (its settings) open in [YomitanSettingsActivity].
 *
 * While lookup is off every Yomitan row is greyed out with the reason; settings search indexes the
 * rows without starting anything.
 */
object SettingsJapaneseScreen : SearchableSettings {

    /** For the Settings list's entry. */
    val TITLE = StringResource(R.string.jp_settings_title)
    val SUMMARY = StringResource(R.string.jp_settings_summary)

    /** Material's "translate" icon (Apache-2.0), drawn here so upstream's icon set stays untouched. */
    val ICON: ImageVector = ImageVector.Builder("jp.Translate", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = addPathNodes(
                "M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53H17V4h-7V2H8v2H1v1.99h11.17" +
                    "C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.3,9.19 6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56" +
                    "l-5.09,5.02L4,19l5,-5 3.11,3.11 0.76,-2.04zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2l-4.5,-12z" +
                    "M15.88,17l1.62,-4.33L19.12,17h-3.24z",
            ),
            fill = SolidColor(Color.Black),
        )
        .build()

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = TITLE

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val lookupPreference = remember { context.jpGraph.jpPreferences.lookupEnabled() }
        // Settings search composes every screen for each query: rows only, nothing started.
        if (LocalSettingsIndexing.current) {
            return rows(context, JapaneseSettingsViewModel.State(lookupOn = true), lookupPreference, Actions())
        }

        val model = metroViewModel<JapaneseSettingsViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        val activity = remember(context) { context.findActivity() }
        LifecycleStartEffect(model, activity) {
            activity?.let(model::onStart)
            onStopOrDispose { model.onStop() }
        }
        LaunchedEffect(model) {
            model.events.collect { event ->
                val text = when (event) {
                    is JapaneseSettingsViewModel.Event.LapisReady ->
                        context.getString(R.string.jp_settings_cards_done, event.deck)
                    is JapaneseSettingsViewModel.Event.Failed ->
                        context.getString(R.string.jp_settings_cards_failed, event.reason)
                }
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }

        var deniedForGood by remember { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            deniedForGood = !granted && activity?.shouldShowRequestPermissionRationale(AnkiAccess.PERMISSION) == false
            model.refresh()
        }
        val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(model::pickLocalAudio)
        }
        var deckChoice by remember { mutableStateOf<Cards.OfferLapis?>(null) }
        var confirmRemove by remember { mutableStateOf(false) }

        deckChoice?.let { offer ->
            DeckDialog(
                decks = offer.decks,
                onDismiss = { deckChoice = null },
                onPick = { deck ->
                    deckChoice = null
                    model.setUpLapis(offer.lapis, deck)
                },
            )
        }
        if (confirmRemove) {
            val size = state.localAudioBytes?.let { Formatter.formatShortFileSize(context, it) }.orEmpty()
            // While it is still copying, the removal stops the copy.
            val copying = state.localAudio is LocalAudio.Status.Copying
            AlertDialog(
                onDismissRequest = { confirmRemove = false },
                title = {
                    val title = if (copying) {
                        R.string.jp_settings_local_audio_stop_title
                    } else {
                        R.string.jp_settings_local_audio_remove_title
                    }
                    Text(stringResource(title))
                },
                text = {
                    Text(
                        if (copying) {
                            stringResource(R.string.jp_settings_local_audio_stop_body)
                        } else {
                            stringResource(R.string.jp_settings_local_audio_remove_body, size)
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmRemove = false
                            model.removeLocalAudio()
                        },
                    ) { Text(stringResource(R.string.jp_settings_local_audio_remove)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRemove = false }) {
                        Text(stringResource(R.string.jp_settings_cancel))
                    }
                },
            )
        }

        val navigator = LocalNavigator.current
        val actions = Actions(
            openStatistics = { navigator?.push(JpStatisticsScreen()) },
            allowAnki = {
                if (deniedForGood) context.open(appDetails(context)) else permission.launch(AnkiAccess.PERMISSION)
            },
            deniedForGood = deniedForGood,
            setUpLapis = { deckChoice = it },
            pickLocalAudio = { audioPicker.launch(arrayOf("*/*")) },
            removeLocalAudio = { confirmRemove = true },
            setTextToSpeech = model::setTextToSpeech,
        )
        return rows(context, state, lookupPreference, actions)
    }

    /** What the rows do; the defaults (settings search) do nothing. */
    private class Actions(
        val openStatistics: () -> Unit = {},
        val allowAnki: () -> Unit = {},
        val deniedForGood: Boolean = false,
        val setUpLapis: (Cards.OfferLapis) -> Unit = {},
        val pickLocalAudio: () -> Unit = {},
        val removeLocalAudio: () -> Unit = {},
        val setTextToSpeech: (Boolean) -> Unit = {},
    )

    @Composable
    private fun rows(
        context: Context,
        state: JapaneseSettingsViewModel.State,
        lookupPreference: tachiyomi.core.common.preference.Preference<Boolean>,
        actions: Actions,
    ): List<Preference> {
        val on = state.lookupOn
        fun yomitan(section: String? = null, recommended: Boolean = false) =
            context.open(YomitanSettingsActivity.intent(context, section, recommended))

        val lookupGroup = Preference.PreferenceGroup(
            title = stringResource(R.string.jp_settings_lookup_group),
            preferenceItems = listOfNotNull(
                Preference.PreferenceItem.SwitchPreference(
                    preference = lookupPreference,
                    title = stringResource(R.string.jp_settings_lookup),
                    subtitle = stringResource(
                        when {
                            on -> R.string.jp_settings_lookup_on
                            state.switchedOff -> R.string.jp_settings_lookup_off_restart
                            else -> R.string.jp_settings_lookup_off
                        },
                    ),
                ),
                row(
                    title = stringResource(R.string.jp_settings_open_dictionary),
                    subtitle = stringResource(R.string.jp_settings_open_dictionary_summary),
                    enabled = on,
                ) { context.open(context.jpGraph.jpLookup.searchIntent(context)) },
                if (remember { DictionaryShortcut.canPin(context) }) {
                    row(
                        title = stringResource(R.string.jp_settings_pin_dictionary),
                        subtitle = stringResource(R.string.jp_settings_pin_dictionary_summary),
                        enabled = on,
                    ) { DictionaryShortcut.pin(context) }
                } else {
                    null
                },
                state.engineFailed?.takeIf { on }?.let {
                    Preference.PreferenceItem.InfoPreference(stringResource(R.string.jp_settings_engine_failed, it))
                },
                if (on) {
                    null
                } else {
                    Preference.PreferenceItem.InfoPreference(stringResource(R.string.jp_settings_lookup_needed))
                },
            ),
        )

        val dictionaries = state.dictionaries
        val dictionaryGroup = Preference.PreferenceGroup(
            title = stringResource(R.string.jp_settings_dictionaries_group),
            preferenceItems = listOf(
                row(
                    title = stringResource(R.string.jp_settings_dictionaries),
                    subtitle = when {
                        !on -> null
                        dictionaries == null -> stringResource(R.string.jp_settings_dictionaries_checking)
                        dictionaries.isEmpty() -> stringResource(R.string.jp_settings_dictionaries_none)
                        else -> pluralStringResource(
                            R.plurals.jp_settings_dictionaries_count,
                            dictionaries.size,
                            dictionaries.size,
                            dictionaries.joinToString { it.title },
                        ) + "\n" + stringResource(R.string.jp_settings_dictionaries_manage)
                    },
                    enabled = on,
                ) { yomitan(YomitanSettingsActivity.DICTIONARIES) },
                row(
                    title = stringResource(R.string.jp_settings_get_dictionaries),
                    subtitle = stringResource(R.string.jp_settings_get_dictionaries_summary),
                    enabled = on,
                ) { yomitan(YomitanSettingsActivity.DICTIONARIES, recommended = true) },
            ),
        )

        val ankiGroup = Preference.PreferenceGroup(
            title = stringResource(R.string.jp_settings_anki_group),
            preferenceItems = listOf(
                ankiRow(context, state, actions),
                cardRow(state, actions) { yomitan(YomitanSettingsActivity.ANKI) },
            ),
        )

        val audioGroup = Preference.PreferenceGroup(
            title = stringResource(R.string.jp_settings_audio_group),
            preferenceItems = listOf(localAudioRow(context, state, actions), ttsRow(context, state, actions)),
        )

        val yomitanGroup = Preference.PreferenceGroup(
            title = stringResource(R.string.jp_settings_yomitan_group),
            preferenceItems = listOf(
                row(
                    title = stringResource(R.string.jp_settings_import),
                    subtitle = stringResource(R.string.jp_settings_import_summary),
                    enabled = on,
                ) { yomitan(YomitanSettingsActivity.BACKUP) },
                row(
                    title = stringResource(R.string.jp_settings_all),
                    subtitle = stringResource(R.string.jp_settings_all_summary),
                    enabled = on,
                ) { yomitan() },
            ),
        )
        // Not a lookup setting: open whether lookup is on or not.
        val readingGroup = Preference.PreferenceGroup(
            title = stringResource(R.string.jp_settings_reading_group),
            preferenceItems = listOf(
                row(
                    title = stringResource(R.string.jp_settings_statistics),
                    subtitle = stringResource(R.string.jp_settings_statistics_summary),
                    onClick = actions.openStatistics,
                ),
            ),
        )
        return listOf(
            lookupGroup,
            dictionaryGroup,
            ankiGroup,
            audioGroup,
            yomitanGroup,
            readingGroup,
            translationGroup(context),
        )
    }

    @Composable
    private fun ankiRow(
        context: Context,
        state: JapaneseSettingsViewModel.State,
        actions: Actions,
    ) = when (state.anki) {
        AnkiAccess.Status.MISSING_APP -> row(
            title = stringResource(R.string.jp_settings_ankidroid),
            subtitle = stringResource(R.string.jp_settings_anki_missing),
            enabled = state.lookupOn,
        ) { context.open(Intent(Intent.ACTION_VIEW, Uri.parse(ANKIDROID_STORE)), ANKIDROID_PAGE) }
        AnkiAccess.Status.NEEDS_PERMISSION -> row(
            title = stringResource(R.string.jp_settings_ankidroid),
            subtitle = stringResource(
                if (actions.deniedForGood) {
                    R.string.jp_settings_anki_denied
                } else {
                    R.string.jp_settings_anki_needs_permission
                },
            ),
            enabled = state.lookupOn,
            widget = {
                Button(onClick = actions.allowAnki) {
                    val label = if (actions.deniedForGood) {
                        R.string.jp_settings_anki_open_settings
                    } else {
                        R.string.jp_settings_anki_allow
                    }
                    Text(stringResource(label))
                }
            },
            onClick = actions.allowAnki,
        )
        AnkiAccess.Status.READY -> row(
            title = stringResource(R.string.jp_settings_ankidroid),
            subtitle = stringResource(R.string.jp_settings_anki_ready),
            enabled = state.lookupOn,
        )
        null -> row(title = stringResource(R.string.jp_settings_ankidroid), subtitle = null, enabled = state.lookupOn)
    }

    @Composable
    private fun cardRow(
        state: JapaneseSettingsViewModel.State,
        actions: Actions,
        openAnkiSettings: () -> Unit,
    ): Preference.PreferenceItem.CustomPreference {
        val ready = state.lookupOn && state.anki == AnkiAccess.Status.READY
        val title = stringResource(R.string.jp_settings_cards)
        if (!ready) return row(title, stringResource(R.string.jp_settings_cards_needs_anki), enabled = false)
        return when (val cards = state.cards) {
            Cards.Unknown -> row(title, stringResource(R.string.jp_settings_cards_checking))
            is Cards.Configured -> row(
                title,
                stringResource(R.string.jp_settings_cards_configured, cards.model, cards.deck),
                onClick = openAnkiSettings,
            )
            is Cards.OfferLapis -> row(
                stringResource(R.string.jp_settings_cards_lapis),
                stringResource(R.string.jp_settings_cards_lapis_summary),
            ) { actions.setUpLapis(cards) }
            Cards.ChooseInYomitan -> row(
                title,
                stringResource(R.string.jp_settings_cards_other),
                onClick = openAnkiSettings,
            )
        }
    }

    @Composable
    private fun localAudioRow(
        context: Context,
        state: JapaneseSettingsViewModel.State,
        actions: Actions,
    ): Preference.PreferenceItem.CustomPreference {
        fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
        val title = stringResource(R.string.jp_settings_local_audio)
        return when (val audio = state.localAudio) {
            LocalAudio.Status.None -> row(
                title,
                stringResource(R.string.jp_settings_local_audio_none),
                state.lookupOn,
                onClick = actions.pickLocalAudio,
            )
            LocalAudio.Status.Opening -> row(
                title,
                stringResource(R.string.jp_settings_local_audio_opening),
                state.lookupOn,
            )
            is LocalAudio.Status.Copying -> row(
                title,
                stringResource(
                    R.string.jp_settings_local_audio_copying,
                    size(audio.copiedBytes),
                    size(audio.totalBytes),
                ),
                state.lookupOn,
                onClick = actions.removeLocalAudio,
            )
            is LocalAudio.Status.Ready, LocalAudio.Status.Closed -> row(
                title,
                stringResource(
                    R.string.jp_settings_local_audio_ready,
                    "android.db" + state.localAudioBytes?.let { ", ${size(it)}" }.orEmpty(),
                ),
                state.lookupOn,
                onClick = actions.removeLocalAudio,
            )
            is LocalAudio.Status.Failed -> row(
                title,
                stringResource(R.string.jp_settings_local_audio_failed, audio.reason),
                state.lookupOn,
                onClick = actions.pickLocalAudio,
            )
        }
    }

    @Composable
    private fun ttsRow(
        context: Context,
        state: JapaneseSettingsViewModel.State,
        actions: Actions,
    ): Preference.PreferenceItem.CustomPreference {
        val listed = state.ttsListed == true
        val subtitle = when (state.tts) {
            is TtsAudio.Status.Ready -> R.string.jp_settings_tts_ready
            is TtsAudio.Status.NoJapaneseVoice -> R.string.jp_settings_tts_no_voice
            TtsAudio.Status.NoEngine -> R.string.jp_settings_tts_no_engine
            null -> R.string.jp_settings_tts_checking
        }
        val needsVoice = state.tts is TtsAudio.Status.NoJapaneseVoice
        val enabled = state.lookupOn && state.ttsListed != null
        return row(
            title = stringResource(R.string.jp_settings_tts),
            subtitle = stringResource(subtitle),
            enabled = enabled,
            widget = { Switch(checked = listed, onCheckedChange = actions.setTextToSpeech) },
        ) {
            if (needsVoice) context.open(Intent(TTS_SETTINGS)) else actions.setTextToSpeech(!listed)
        }
    }

    /** A row that greys out, and does nothing, when not [enabled] (upstream's rows hide instead). */
    private fun row(
        title: String,
        subtitle: String?,
        enabled: Boolean = true,
        widget: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null,
    ) = Preference.PreferenceItem.CustomPreference(title) {
        TextPreferenceWidget(
            modifier = if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA),
            title = title,
            subtitle = subtitle,
            widget = widget?.takeIf { enabled },
            onPreferenceClick = onClick?.takeIf { enabled },
        )
    }

    @Composable
    private fun DeckDialog(decks: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.jp_settings_deck_title)) },
            text = {
                if (decks.isEmpty()) {
                    Text(stringResource(R.string.jp_settings_deck_none))
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        decks.forEach { deck ->
                            Text(
                                text = deck,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(deck) }
                                    .padding(vertical = 14.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.jp_settings_cancel)) } },
        )
    }

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /** Starts [intent], or [fallback] (a web page) when nothing handles it. */
    private fun Context.open(intent: Intent, fallback: String? = null) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            fallback?.let { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private const val DISABLED_ALPHA = 0.38f
    private const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
    private const val ANKIDROID_STORE = "market://details?id=${AnkiAccess.PACKAGE}"
    private const val ANKIDROID_PAGE = "https://play.google.com/store/apps/details?id=${AnkiAccess.PACKAGE}"
}

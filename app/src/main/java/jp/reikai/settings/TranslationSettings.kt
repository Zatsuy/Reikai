package jp.reikai.settings

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import jp.reikai.di.jpGraph
import jp.reikai.translate.AiPreset
import jp.reikai.translate.ChapterTranslations
import jp.reikai.translate.TranslationLanguages
import jp.reikai.yomitan.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale
import tachiyomi.core.common.preference.Preference as PreferenceData

/**
 * Settings, Japanese, Translation (4.5, phase 4 ruling 15): the service, the language, and what the
 * chosen service needs (a DeepL key; an AI service's address, model and key). Keys are private
 * preferences, left out of backups, and never shown once set.
 */
@Composable
internal fun translationGroup(context: Context): Preference.PreferenceGroup {
    val preferences = remember { context.jpGraph.jpPreferences }
    val engine by preferences.translateEngine().collectAsState()
    val presetKey by preferences.translateAiPreset().collectAsState()
    val preset = AiPreset.of(presetKey)
    val aiKeyPreference = remember(preset) { preferences.translateAiKey(preset.key) }
    val aiKey by aiKeyPreference.collectAsState()
    val deepLKey by preferences.translateDeepLKey().collectAsState()
    val scope = rememberCoroutineScope()
    val locale = Locale.getDefault()
    val keySet = stringResource(R.string.jp_settings_translate_key_set)

    val targets = buildMap {
        val device = TranslationLanguages.name(TranslationLanguages.fromDevice(locale), locale)
        put("", stringResource(R.string.jp_settings_translate_target_device, device))
        TranslationLanguages.CODES.forEach { put(it, TranslationLanguages.name(it, locale)) }
    }
    val items = buildList<Preference.PreferenceItem<out Any, out Any>> {
        add(Preference.PreferenceItem.InfoPreference(stringResource(R.string.jp_settings_translate_note)))
        add(
            Preference.PreferenceItem.ListPreference(
                preference = preferences.translateEngine(),
                entries = mapOf(
                    ChapterTranslations.ENGINE_GOOGLE to stringResource(R.string.jp_settings_translate_engine_google),
                    ChapterTranslations.ENGINE_DEEPL to stringResource(R.string.jp_settings_translate_engine_deepl),
                    ChapterTranslations.ENGINE_AI to stringResource(R.string.jp_settings_translate_engine_ai),
                ),
                title = stringResource(R.string.jp_settings_translate_engine),
            ),
        )
        add(
            Preference.PreferenceItem.ListPreference(
                preference = preferences.translateTarget(),
                entries = targets,
                title = stringResource(R.string.jp_settings_translate_target),
            ),
        )
        when (engine) {
            ChapterTranslations.ENGINE_DEEPL -> add(
                keyRow(
                    preference = preferences.translateDeepLKey(),
                    title = stringResource(R.string.jp_settings_translate_deepl_key),
                    subtitle = if (deepLKey.isBlank()) {
                        stringResource(R.string.jp_settings_translate_deepl_key_summary)
                    } else {
                        keySet
                    },
                ),
            )
            ChapterTranslations.ENGINE_AI -> {
                add(
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.translateAiPreset(),
                        entries = AiPreset.entries.associate { it.key to it.label },
                        title = stringResource(R.string.jp_settings_translate_ai_preset),
                        subtitle = stringResource(R.string.jp_settings_translate_ai_preset_summary, "%s"),
                        onValueChanged = { key ->
                            val chosen = AiPreset.of(key)
                            if (chosen != AiPreset.CUSTOM) {
                                preferences.translateAiAddress().set(chosen.address)
                                preferences.translateAiModel().set(chosen.model)
                            }
                            true
                        },
                    ),
                )
                add(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.translateAiAddress(),
                        title = stringResource(R.string.jp_settings_translate_ai_address),
                    ),
                )
                add(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.translateAiModel(),
                        title = stringResource(R.string.jp_settings_translate_ai_model),
                    ),
                )
                add(
                    keyRow(
                        preference = aiKeyPreference,
                        title = stringResource(R.string.jp_settings_translate_ai_key, preset.label),
                        subtitle = if (aiKey.isBlank()) {
                            stringResource(R.string.jp_settings_translate_ai_key_summary)
                        } else {
                            keySet
                        },
                    ),
                )
            }
        }
        add(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(R.string.jp_settings_translate_clear),
                subtitle = stringResource(R.string.jp_settings_translate_clear_summary),
                onClick = {
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) { context.jpGraph.chapterTranslations.cache.clear() }
                        val text = context.getString(
                            R.string.jp_settings_translate_cleared,
                            Formatter.formatShortFileSize(context, freed),
                        )
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                    }
                },
            ),
        )
    }
    return Preference.PreferenceGroup(
        title = stringResource(R.string.jp_settings_translate_group),
        preferenceItems = items,
    )
}

/**
 * A key's row: never shows the key; its dialog hides what is typed and can remove the key (upstream's
 * text row refuses an empty value).
 */
private fun keyRow(preference: PreferenceData<String>, title: String, subtitle: String) =
    Preference.PreferenceItem.CustomPreference(title) {
        var editing by remember { mutableStateOf(false) }
        TextPreferenceWidget(title = title, subtitle = subtitle, onPreferenceClick = { editing = true })
        if (editing) {
            KeyDialog(title, preference.get(), onDismiss = { editing = false }) {
                preference.set(it.trim())
                editing = false
            }
        }
    }

@Composable
private fun KeyDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val text = rememberTextFieldState(initial)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedSecureTextField(state = text) },
        confirmButton = {
            TextButton(onClick = { onSave(text.text.toString()) }) {
                Text(stringResource(R.string.jp_settings_translate_key_save))
            }
        },
        dismissButton = {
            Row {
                if (initial.isNotBlank()) {
                    TextButton(onClick = { onSave("") }) {
                        Text(stringResource(R.string.jp_settings_translate_key_remove))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.jp_settings_cancel)) }
            }
        },
    )
}

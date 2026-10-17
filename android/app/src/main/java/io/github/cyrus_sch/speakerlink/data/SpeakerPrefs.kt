package io.github.cyrus_sch.speakerlink.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User choices that survive restarts. Small enough for SharedPreferences. */
data class Preferences(
    /** Speakers the user removed from the party selection (everything else is selected). */
    val excludedFromParty: Set<String> = emptySet(),
    /** Address -> model id picked by hand, for renamed speakers. */
    val modelOverrides: Map<String, String> = emptyMap(),
    /** Speaker the phone should stream to (the PartyBoost/Auracast host). */
    val preferredHost: String? = null,
    /** Also list Bluetooth audio devices that are not JBL speakers. */
    val showAllAudioDevices: Boolean = false,
)

class SpeakerPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("speakerlink", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Preferences> = _state.asStateFlow()

    fun setInParty(address: String, included: Boolean) = update {
        it.copy(excludedFromParty = if (included) it.excludedFromParty - address else it.excludedFromParty + address)
    }

    fun setModelOverride(address: String, modelId: String?) = update {
        it.copy(modelOverrides = if (modelId == null) it.modelOverrides - address else it.modelOverrides + (address to modelId))
    }

    fun setPreferredHost(address: String?) = update { it.copy(preferredHost = address) }

    fun setShowAllAudioDevices(show: Boolean) = update { it.copy(showAllAudioDevices = show) }

    private fun update(change: (Preferences) -> Preferences) {
        val next = change(_state.value)
        _state.value = next
        prefs.edit {
            putStringSet(KEY_EXCLUDED, next.excludedFromParty)
            putStringSet(KEY_MODELS, next.modelOverrides.map { (address, id) -> "$address=$id" }.toSet())
            putString(KEY_HOST, next.preferredHost)
            putBoolean(KEY_SHOW_ALL, next.showAllAudioDevices)
        }
    }

    private fun read() = Preferences(
        excludedFromParty = prefs.getStringSet(KEY_EXCLUDED, null)?.toSet().orEmpty(),
        modelOverrides = prefs.getStringSet(KEY_MODELS, null).orEmpty().mapNotNull { entry ->
            val separator = entry.lastIndexOf('=')
            if (separator <= 0) null else entry.substring(0, separator) to entry.substring(separator + 1)
        }.toMap(),
        preferredHost = prefs.getString(KEY_HOST, null),
        showAllAudioDevices = prefs.getBoolean(KEY_SHOW_ALL, false),
    )

    private companion object {
        const val KEY_EXCLUDED = "excluded_from_party"
        const val KEY_MODELS = "model_overrides"
        const val KEY_HOST = "preferred_host"
        const val KEY_SHOW_ALL = "show_all_audio_devices"
    }
}

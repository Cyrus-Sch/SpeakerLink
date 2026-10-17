package io.github.cyrus_sch.speakerlink.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User choices that survive restarts. Small enough for SharedPreferences. */
data class Preferences(
    /** Devices the user took out of the "use together" selection (everything else is selected). */
    val deselected: Set<String> = emptySet(),
    /** Address -> JBL model id picked by hand, for renamed speakers. */
    val modelOverrides: Map<String, String> = emptyMap(),
    /** JBL speaker the phone should stream to (the PartyBoost/Auracast host). */
    val preferredHost: String? = null,
)

class SpeakerPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("speakerlink", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Preferences> = _state.asStateFlow()

    fun setSelected(address: String, selected: Boolean) = update {
        it.copy(deselected = if (selected) it.deselected - address else it.deselected + address)
    }

    fun setModelOverride(address: String, modelId: String?) = update {
        it.copy(modelOverrides = if (modelId == null) it.modelOverrides - address else it.modelOverrides + (address to modelId))
    }

    fun setPreferredHost(address: String?) = update { it.copy(preferredHost = address) }

    private fun update(change: (Preferences) -> Preferences) {
        val next = change(_state.value)
        _state.value = next
        prefs.edit {
            putStringSet(KEY_DESELECTED, next.deselected)
            putStringSet(KEY_MODELS, next.modelOverrides.map { (address, id) -> "$address=$id" }.toSet())
            putString(KEY_HOST, next.preferredHost)
        }
    }

    private fun read() = Preferences(
        deselected = prefs.getStringSet(KEY_DESELECTED, null)?.toSet().orEmpty(),
        modelOverrides = prefs.getStringSet(KEY_MODELS, null).orEmpty().mapNotNull { entry ->
            val separator = entry.lastIndexOf('=')
            if (separator <= 0) null else entry.substring(0, separator) to entry.substring(separator + 1)
        }.toMap(),
        preferredHost = prefs.getString(KEY_HOST, null),
    )

    private companion object {
        /** Stored name kept from when only JBL speakers could be selected (for a "party"). */
        const val KEY_DESELECTED = "excluded_from_party"
        const val KEY_MODELS = "model_overrides"
        const val KEY_HOST = "preferred_host"
    }
}

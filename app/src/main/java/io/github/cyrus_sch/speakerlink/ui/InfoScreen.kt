package io.github.cyrus_sch.speakerlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.cyrus_sch.speakerlink.BuildConfig
import io.github.cyrus_sch.speakerlink.R
import io.github.cyrus_sch.speakerlink.core.LinkFamily
import io.github.cyrus_sch.speakerlink.core.ModelCatalog

const val SOURCE_URL = "https://github.com/Cyrus-Sch/PuzzleGenerator/tree/master/SpeakerLink"

class InfoActions(
    val openDeveloperOptions: () -> Unit,
    val openSource: () -> Unit,
)

@Composable
fun InfoScreen(state: UiState, actions: InfoActions, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("info_list"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            InfoCard(stringResource(R.string.info_about_title), R.drawable.ic_info) {
                BodyText(stringResource(R.string.info_about_body))
                BodyText(stringResource(R.string.info_version, BuildConfig.VERSION_NAME))
                BodyText(stringResource(R.string.info_license))
                OutlinedButton(onClick = actions.openSource) { Text(stringResource(R.string.info_source)) }
            }
        }
        item {
            InfoCard(stringResource(R.string.info_privacy_title), R.drawable.ic_check_circle) {
                BodyText(stringResource(R.string.info_privacy_body))
            }
        }
        item {
            val caps = state.capabilities
            InfoCard(stringResource(R.string.info_phone_title), R.drawable.ic_settings) {
                if (caps != null) {
                    BodyText("${caps.manufacturer} ${caps.model}")
                    BodyText(stringResource(R.string.info_android, caps.androidRelease, caps.sdkInt))
                    BodyText(stringResource(R.string.info_le_audio, yesNo(caps.leAudio)))
                    BodyText(stringResource(R.string.info_le_broadcast, yesNo(caps.leBroadcastSource)))
                }
                BodyText(stringResource(R.string.info_direct_connect, yesNo(state.canConnectDirectly)))
                val max = state.maxConnected
                BodyText(
                    if (max != null) stringResource(R.string.info_max_devices, max)
                    else stringResource(R.string.info_max_devices_unknown),
                )
                if (max == null || max < 2) {
                    BodyText(stringResource(R.string.info_max_devices_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = actions.openDeveloperOptions) { Text(stringResource(R.string.action_developer_options)) }
                }
            }
        }
        item {
            SectionHeader(stringResource(R.string.info_compat_title))
            BodyText(stringResource(R.string.info_compat_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(LinkFamily.entries.sortedByDescending { it.generation }, key = { it.name }) { family ->
            val models = ModelCatalog.inFamily(family)
            InfoCard(stringResource(family.labelRes()), R.drawable.ic_link) {
                val limit = family.maxSpeakers
                BodyText(models.map { it.titleWithConfidence() }.joinToString(", "))
                if (limit != null) {
                    Text(
                        stringResource(R.string.family_max, limit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            val noLinking = ModelCatalog.all.filter { it.families.isEmpty() }
            InfoCard(stringResource(R.string.family_none), R.drawable.ic_link_off) {
                BodyText(noLinking.map { it.titleWithConfidence() }.joinToString(", "))
            }
        }
        item {
            Text(
                stringResource(R.string.info_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("disclaimer"),
            )
        }
    }
}

@Composable
private fun yesNo(value: Boolean?): String = when (value) {
    true -> stringResource(R.string.yes)
    false -> stringResource(R.string.no)
    null -> stringResource(R.string.unknown)
}

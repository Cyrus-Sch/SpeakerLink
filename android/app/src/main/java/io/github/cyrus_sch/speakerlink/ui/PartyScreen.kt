package io.github.cyrus_sch.speakerlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.cyrus_sch.speakerlink.R
import io.github.cyrus_sch.speakerlink.core.Alternative
import io.github.cyrus_sch.speakerlink.core.LinkGroup

class PartyActions(
    val setInParty: (String, Boolean) -> Unit,
    val makeHost: (String) -> Unit,
    val connectHost: (String) -> Unit,
    val testSound: () -> Unit,
    val openOutputSwitcher: () -> Unit,
    val openAudioSharing: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyScreen(state: UiState, actions: PartyActions, contentPadding: PaddingValues) {
    val plan = state.plan
    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("party_list"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            InfoCard(stringResource(R.string.party_intro_title), R.drawable.ic_celebration, tonal = true) {
                BodyText(stringResource(R.string.party_intro_body))
            }
        }

        item { SectionHeader(stringResource(R.string.party_select_title)) }
        val jblSpeakers = state.speakers.filter { it.isJbl }
        if (jblSpeakers.isEmpty()) {
            item { BodyText(stringResource(R.string.party_select_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    jblSpeakers.forEach { speaker ->
                        FilterChip(
                            selected = speaker.inParty,
                            onClick = { actions.setInParty(speaker.address, !speaker.inParty) },
                            label = { Text(speaker.name) },
                            leadingIcon = if (speaker.inParty) {
                                { AppIcon(R.drawable.ic_check_circle, modifier = Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        if (plan.mixesFamilies) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_warning, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    BodyText(stringResource(R.string.party_mixed_warning))
                }
            }
        }

        items(plan.groups, key = { "group-" + it.family.name + it.host.key }) { group ->
            GroupCard(group, state, actions)
        }

        if (plan.excluded.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.party_excluded_title)) }
            items(plan.excluded, key = { "excluded-" + it.speaker.key }) { excluded ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(R.drawable.ic_link_off, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(excluded.speaker.name, style = MaterialTheme.typography.titleSmall)
                            BodyText(excluded.text(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (plan.alternatives.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.alternatives_title)) }
            items(plan.alternatives, key = { "alt-" + it.name }) { alternative ->
                AlternativeCard(alternative, actions)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(group: LinkGroup, state: UiState, actions: PartyActions) {
    val family = stringResource(group.family.labelRes())
    val hostConnected = state.speakers.firstOrNull { it.address == group.host.key }?.connected == true
    Card(
        modifier = Modifier.fillMaxWidth().testTag("group_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_speaker_group, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.party_group_title, family), style = MaterialTheme.typography.titleLarge)
                    Text(
                        pluralStringResource(R.plurals.party_group_size, group.size, group.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(
                stringResource(R.string.party_host, group.host.name),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (group.members.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    group.members.forEach { member ->
                        TextButton(onClick = { actions.makeHost(member.key) }) {
                            Text(stringResource(R.string.party_make_host, member.name))
                        }
                    }
                }
            }

            Text(stringResource(R.string.party_steps_title), style = MaterialTheme.typography.titleSmall)
            group.steps.forEachIndexed { index, step ->
                Row {
                    Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(24.dp))
                    BodyText(step.text(), modifier = Modifier.weight(1f))
                }
            }
            group.notes.forEach { note ->
                Row(verticalAlignment = Alignment.Top) {
                    AppIcon(R.drawable.ic_info, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(note.textRes()), style = MaterialTheme.typography.bodySmall)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.connectHost(group.host.key) }, enabled = !hostConnected || !group.host.playing) {
                    AppIcon(R.drawable.ic_bluetooth_connected, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (hostConnected && group.host.playing) R.string.host_connected else R.string.action_connect_host))
                }
                OutlinedButton(onClick = actions.testSound) {
                    AppIcon(R.drawable.ic_music_note, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_test_sound))
                }
            }
            Text(stringResource(R.string.party_test_sound_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AlternativeCard(alternative: Alternative, actions: PartyActions) {
    when (alternative) {
        Alternative.SAMSUNG_DUAL_AUDIO -> InfoCard(stringResource(R.string.alt_samsung_title), R.drawable.ic_devices) {
            BodyText(stringResource(R.string.alt_samsung_body))
            OutlinedButton(onClick = actions.openOutputSwitcher) { Text(stringResource(R.string.action_open_output_switcher)) }
        }
        Alternative.LE_AUDIO_SHARING -> InfoCard(stringResource(R.string.alt_le_title), R.drawable.ic_campaign) {
            BodyText(stringResource(R.string.alt_le_body))
            OutlinedButton(onClick = actions.openAudioSharing) { Text(stringResource(R.string.action_open_audio_sharing)) }
        }
        Alternative.SWITCH_OUTPUT -> InfoCard(stringResource(R.string.alt_switch_title), R.drawable.ic_swap_horiz) {
            BodyText(stringResource(R.string.alt_switch_body))
            OutlinedButton(onClick = actions.openOutputSwitcher) { Text(stringResource(R.string.action_open_output_switcher)) }
        }
    }
}

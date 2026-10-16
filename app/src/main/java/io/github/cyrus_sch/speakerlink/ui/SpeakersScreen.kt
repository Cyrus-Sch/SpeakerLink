package io.github.cyrus_sch.speakerlink.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cyrus_sch.speakerlink.R
import io.github.cyrus_sch.speakerlink.bluetooth.AdapterState
import io.github.cyrus_sch.speakerlink.bluetooth.LinkState
import io.github.cyrus_sch.speakerlink.core.AudioChannel
import io.github.cyrus_sch.speakerlink.core.IdentificationSource
import io.github.cyrus_sch.speakerlink.status.SpeakerStatus

/** Everything the Speakers screen can ask for. */
class SpeakerActions(
    val requestPermissions: () -> Unit,
    val openAppSettings: () -> Unit,
    val enableBluetooth: () -> Unit,
    val scan: () -> Unit,
    val stopScan: () -> Unit,
    val pair: (String) -> Unit,
    val connect: (String) -> Unit,
    val disconnect: (String) -> Unit,
    val playOn: (String) -> Unit,
    val connectAll: () -> Unit,
    val setInParty: (String, Boolean) -> Unit,
    val setModel: (String, String?) -> Unit,
    val readStatus: (String) -> Unit,
    val readAllStatuses: () -> Unit,
    val locate: (String) -> Unit,
    val testSound: () -> Unit,
    val openOutputSwitcher: () -> Unit,
    val openBluetoothSettings: () -> Unit,
    val setShowAll: (Boolean) -> Unit,
)

@Composable
fun SpeakersScreen(state: UiState, actions: SpeakerActions, contentPadding: PaddingValues) {
    var modelDialogFor by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("speakers_list"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!state.bluetoothSupported) {
            item { InfoCard(stringResource(R.string.bluetooth_unavailable), R.drawable.ic_bluetooth_disabled) {} }
            return@LazyColumn
        }
        if (!state.permissionsGranted) {
            item { PermissionCard(actions) }
        } else if (state.adapter != AdapterState.ON) {
            item { BluetoothOffCard(state.adapter, actions) }
        }

        item { StatusCard(state, actions) }

        item {
            SectionHeader(stringResource(R.string.section_my_speakers)) {
                val disconnected = state.partySpeakers.count { !it.connected }
                if (state.partySpeakers.size >= 2 && disconnected > 0 && state.adapter == AdapterState.ON) {
                    TextButton(onClick = actions.connectAll) {
                        AppIcon(R.drawable.ic_link, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_connect_all))
                    }
                }
            }
        }
        if (state.speakers.isEmpty()) {
            item { BodyText(stringResource(R.string.no_speakers), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.speakers, key = { it.address }) { speaker ->
            SpeakerCard(speaker, state.adapter == AdapterState.ON, actions, onSetModel = { modelDialogFor = speaker.address })
        }
        if (state.speakers.any { it.canReadStatus } && state.permissionsGranted) {
            item {
                Column {
                    TextButton(onClick = actions.readAllStatuses) {
                        AppIcon(R.drawable.ic_battery_full, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_read_all))
                    }
                    Text(
                        stringResource(R.string.status_beta_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionHeader(stringResource(R.string.section_add_speakers)) {
                if (state.scanning) {
                    TextButton(onClick = actions.stopScan) { Text(stringResource(R.string.action_stop)) }
                } else {
                    FilledTonalButton(
                        onClick = actions.scan,
                        enabled = state.permissionsGranted && state.adapter == AdapterState.ON,
                        modifier = Modifier.testTag("scan_button"),
                    ) {
                        AppIcon(R.drawable.ic_bluetooth_searching, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_scan))
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    BodyText(stringResource(R.string.scanning))
                } else {
                    BodyText(stringResource(R.string.scan_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(state.discovered, key = { "found-" + it.address }) { speaker ->
            DiscoveredCard(speaker, onPair = { actions.pair(speaker.address) })
        }
        if (!state.scanning && state.discovered.isEmpty() && state.nearbyNotInPairingMode.isEmpty()) {
            // Nothing found (yet): the hint above already says what to do.
        }
        if (state.nearbyNotInPairingMode.isNotEmpty()) {
            item {
                BodyText(
                    stringResource(R.string.nearby_not_pairable, state.nearbyNotInPairingMode.joinToString(", ")),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.show_all_devices), modifier = Modifier.weight(1f))
                Switch(checked = state.showAllAudioDevices, onCheckedChange = actions.setShowAll)
            }
        }
    }

    val dialogSpeaker = state.speakers.firstOrNull { it.address == modelDialogFor }
    if (dialogSpeaker != null) {
        ModelPickerDialog(
            current = dialogSpeaker.model.takeIf { dialogSpeaker.modelSource == IdentificationSource.USER },
            onPick = { model ->
                actions.setModel(dialogSpeaker.address, model?.id)
                modelDialogFor = null
            },
            onDismiss = { modelDialogFor = null },
        )
    }
}

@Composable
private fun PermissionCard(actions: SpeakerActions) {
    var asked by rememberSaveable { mutableStateOf(false) }
    InfoCard(stringResource(R.string.permission_title), R.drawable.ic_bluetooth, tonal = true) {
        val body = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.permission_body else R.string.permission_body_legacy
        BodyText(stringResource(body))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    asked = true
                    actions.requestPermissions()
                },
                modifier = Modifier.testTag("grant_permissions"),
            ) { Text(stringResource(R.string.permission_grant)) }
            if (asked) {
                TextButton(onClick = actions.openAppSettings) { Text(stringResource(R.string.permission_open_settings)) }
            }
        }
    }
}

@Composable
private fun BluetoothOffCard(adapter: AdapterState, actions: SpeakerActions) {
    InfoCard(stringResource(R.string.bluetooth_off_title), R.drawable.ic_bluetooth_disabled, tonal = true) {
        BodyText(stringResource(R.string.bluetooth_off_body))
        Button(onClick = actions.enableBluetooth, enabled = adapter == AdapterState.OFF) {
            Text(stringResource(R.string.bluetooth_turn_on))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(state: UiState, actions: SpeakerActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_speaker_group, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        pluralStringResource(R.plurals.status_connected, state.connectedCount, state.connectedCount),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        state.playing?.let { stringResource(R.string.status_playing_on, it.name) }
                            ?: stringResource(R.string.status_playing_none),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            state.maxConnected?.let {
                Text(stringResource(R.string.status_connected_limit, it), style = MaterialTheme.typography.bodySmall)
            }
            if (state.connectedCount > 1) {
                Text(stringResource(R.string.status_one_output), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = actions.testSound) {
                    AppIcon(R.drawable.ic_music_note, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_test_sound))
                }
                OutlinedButton(onClick = actions.openOutputSwitcher) {
                    AppIcon(R.drawable.ic_swap_horiz, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_switch_output))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakerCard(speaker: SpeakerUi, bluetoothOn: Boolean, actions: SpeakerActions, onSetModel: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val highlighted = speaker.playing
    Card(
        modifier = Modifier.fillMaxWidth().testTag("speaker_card"),
        colors = if (highlighted) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(
                    if (speaker.playing) R.drawable.ic_graphic_eq else R.drawable.ic_speaker,
                    modifier = Modifier.size(28.dp),
                    tint = if (speaker.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        speaker.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        modelLine(speaker),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stateLine(speaker),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (speaker.playing) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (speaker.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    speaker.status?.let { StatusLine(it) }
                }
                if (speaker.isJbl) {
                    Checkbox(
                        checked = speaker.inParty,
                        onCheckedChange = { actions.setInParty(speaker.address, it) },
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        AppIcon(R.drawable.ic_more_vert, stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_set_model)) },
                            onClick = { menuOpen = false; onSetModel() },
                        )
                        if (speaker.canReadStatus) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_read_status)) },
                                onClick = { menuOpen = false; actions.readStatus(speaker.address) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_locate)) },
                                onClick = { menuOpen = false; actions.locate(speaker.address) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_bluetooth_settings)) },
                            onClick = { menuOpen = false; actions.openBluetoothSettings() },
                        )
                    }
                }
            }
            FlowRow(
                modifier = Modifier.padding(start = 40.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (speaker.link) {
                    LinkState.CONNECTED -> {
                        if (!speaker.playing) {
                            FilledTonalButton(onClick = { actions.playOn(speaker.address) }, enabled = bluetoothOn) {
                                AppIcon(R.drawable.ic_play_arrow, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.action_play_here))
                            }
                        }
                        TextButton(onClick = { actions.disconnect(speaker.address) }, enabled = bluetoothOn) {
                            Text(stringResource(R.string.action_disconnect))
                        }
                    }
                    LinkState.CONNECTING, LinkState.DISCONNECTING -> {
                        TextButton(onClick = {}, enabled = false) { Text(stateLine(speaker)) }
                    }
                    LinkState.DISCONNECTED -> {
                        FilledTonalButton(onClick = { actions.connect(speaker.address) }, enabled = bluetoothOn) {
                            AppIcon(R.drawable.ic_bluetooth_connected, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_connect))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun modelLine(speaker: SpeakerUi): String {
    val model = speaker.model ?: return stringResource(R.string.model_unknown)
    val base = "${model.titleWithConfidence()} · ${model.familyLabel()}"
    return if (speaker.modelSource == IdentificationSource.USER) "$base (${stringResource(R.string.model_set_by_you)})" else base
}

@Composable
private fun stateLine(speaker: SpeakerUi): String {
    val base = when {
        speaker.playing -> stringResource(R.string.state_playing)
        speaker.link == LinkState.CONNECTED -> stringResource(R.string.state_connected)
        speaker.link == LinkState.CONNECTING -> stringResource(R.string.state_connecting)
        speaker.link == LinkState.DISCONNECTING -> stringResource(R.string.state_disconnecting)
        else -> stringResource(R.string.state_disconnected)
    }
    return if (!speaker.connected && speaker.nearby) "$base · ${stringResource(R.string.state_nearby)}" else base
}

@Composable
private fun StatusLine(status: SpeakerStatus) {
    val text = when (status) {
        SpeakerStatus.Reading -> stringResource(R.string.status_reading)
        is SpeakerStatus.Failed -> stringResource(status.reason.textRes())
        is SpeakerStatus.Ready -> buildList {
            val info = status.info
            info.batteryPercent?.let {
                add(
                    if (info.charging == true) stringResource(R.string.battery_charging, it)
                    else stringResource(R.string.battery_level, it),
                )
            }
            when (info.channel) {
                AudioChannel.LEFT -> add(stringResource(R.string.status_channel_left))
                AudioChannel.RIGHT -> add(stringResource(R.string.status_channel_right))
                AudioChannel.STEREO, null -> Unit
            }
            info.firmware?.let { add(stringResource(R.string.status_firmware, it)) }
            if (isEmpty()) add(stringResource(R.string.status_no_details))
        }.joinToString(" · ")
    }
    if (text.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            val icon = when {
                status is SpeakerStatus.Ready && status.info.charging == true -> R.drawable.ic_battery_charging_full
                status is SpeakerStatus.Ready -> batteryIcon(status.info.batteryPercent)
                status is SpeakerStatus.Failed -> R.drawable.ic_warning
                else -> R.drawable.ic_refresh
            }
            AppIcon(icon, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun batteryIcon(percent: Int?): Int = when {
    percent == null -> R.drawable.ic_battery_full
    percent >= 90 -> R.drawable.ic_battery_full
    percent >= 60 -> R.drawable.ic_battery_5_bar
    percent >= 30 -> R.drawable.ic_battery_3_bar
    else -> R.drawable.ic_battery_1_bar
}

@Composable
private fun DiscoveredCard(speaker: SpeakerUi, onPair: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("discovered_card")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_speaker, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(speaker.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(modelLine(speaker), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (speaker.bonding) {
                Text(stringResource(R.string.pairing), style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(onClick = onPair) { Text(stringResource(R.string.action_pair)) }
            }
        }
    }
}

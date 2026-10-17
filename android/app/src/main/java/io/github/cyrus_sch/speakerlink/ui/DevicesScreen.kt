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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.cyrus_sch.speakerlink.R
import io.github.cyrus_sch.speakerlink.bluetooth.AdapterState
import io.github.cyrus_sch.speakerlink.bluetooth.LinkState
import io.github.cyrus_sch.speakerlink.core.AudioChannel
import io.github.cyrus_sch.speakerlink.core.IdentificationSource
import io.github.cyrus_sch.speakerlink.status.SpeakerStatus

/** Everything the Devices screen can ask for. */
class DeviceActions(
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
    val setSelected: (String, Boolean) -> Unit,
    val setModel: (String, String?) -> Unit,
    val readStatus: (String) -> Unit,
    val readAllStatuses: () -> Unit,
    val locate: (String) -> Unit,
    val testSound: () -> Unit,
    val openOutputSwitcher: () -> Unit,
    val openBluetoothSettings: () -> Unit,
)

@Composable
fun DevicesScreen(state: UiState, actions: DeviceActions, contentPadding: PaddingValues) {
    var modelDialogFor by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("devices_list"),
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
                val disconnected = state.selected.count { !it.connected }
                if (state.selected.size >= 2 && disconnected > 0 && state.adapter == AdapterState.ON) {
                    TextButton(onClick = actions.connectAll) {
                        AppIcon(R.drawable.ic_link, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_connect_all))
                    }
                }
            }
        }
        if (state.devices.isEmpty()) {
            item { BodyText(stringResource(R.string.no_speakers), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.devices, key = { it.address }) { device ->
            DeviceCard(device, state.adapter == AdapterState.ON, actions, onSetModel = { modelDialogFor = device.address })
        }
        // JBL extra: battery and firmware over JBL's control protocol.
        if (state.devices.any { it.canReadStatus } && state.permissionsGranted) {
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
        items(state.discovered, key = { "found-" + it.address }) { device ->
            DiscoveredCard(device, onPair = { actions.pair(device.address) })
        }
        if (state.nearbyNotInPairingMode.isNotEmpty()) {
            item {
                BodyText(
                    stringResource(R.string.nearby_not_pairable, state.nearbyNotInPairingMode.joinToString(", ")),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // The model picker is a JBL extra.
    val dialogDevice = state.devices.firstOrNull { it.address == modelDialogFor && it.isJbl }
    if (dialogDevice != null) {
        ModelPickerDialog(
            current = dialogDevice.model.takeIf { dialogDevice.modelSource == IdentificationSource.USER },
            onPick = { model ->
                actions.setModel(dialogDevice.address, model?.id)
                modelDialogFor = null
            },
            onDismiss = { modelDialogFor = null },
        )
    }
}

@Composable
private fun PermissionCard(actions: DeviceActions) {
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
private fun BluetoothOffCard(adapter: AdapterState, actions: DeviceActions) {
    InfoCard(stringResource(R.string.bluetooth_off_title), R.drawable.ic_bluetooth_disabled, tonal = true) {
        BodyText(stringResource(R.string.bluetooth_off_body))
        Button(onClick = actions.enableBluetooth, enabled = adapter == AdapterState.OFF) {
            Text(stringResource(R.string.bluetooth_turn_on))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(state: UiState, actions: DeviceActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_graphic_eq, modifier = Modifier.size(28.dp))
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
private fun DeviceCard(device: DeviceUi, bluetoothOn: Boolean, actions: DeviceActions, onSetModel: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val highlighted = device.playing
    val stateColor = if (device.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth().testTag("device_card"),
        colors = if (highlighted) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(device.kind.iconRes(), modifier = Modifier.size(28.dp), tint = stateColor)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        kindLine(device),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (device.playing) {
                            AppIcon(R.drawable.ic_graphic_eq, modifier = Modifier.size(16.dp), tint = stateColor)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            stateLine(device),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (device.playing) FontWeight.SemiBold else FontWeight.Normal,
                            color = stateColor,
                        )
                    }
                    device.status?.let { StatusLine(it) }
                }
                val selectLabel = stringResource(R.string.in_party)
                Checkbox(
                    checked = device.selected,
                    onCheckedChange = { actions.setSelected(device.address, it) },
                    modifier = Modifier.semantics { contentDescription = selectLabel },
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        AppIcon(R.drawable.ic_more_vert, stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // JBL extras: model picker, battery and info, locate sound.
                        if (device.isJbl) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_set_model)) },
                                onClick = { menuOpen = false; onSetModel() },
                            )
                        }
                        if (device.canReadStatus) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_read_status)) },
                                onClick = { menuOpen = false; actions.readStatus(device.address) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_locate)) },
                                onClick = { menuOpen = false; actions.locate(device.address) },
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
                when (device.link) {
                    LinkState.CONNECTED -> {
                        if (!device.playing) {
                            FilledTonalButton(onClick = { actions.playOn(device.address) }, enabled = bluetoothOn) {
                                AppIcon(R.drawable.ic_play_arrow, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.action_play_here))
                            }
                        }
                        TextButton(onClick = { actions.disconnect(device.address) }, enabled = bluetoothOn) {
                            Text(stringResource(R.string.action_disconnect))
                        }
                    }
                    LinkState.CONNECTING, LinkState.DISCONNECTING -> {
                        TextButton(onClick = {}, enabled = false) { Text(stateLine(device)) }
                    }
                    LinkState.DISCONNECTED -> {
                        FilledTonalButton(onClick = { actions.connect(device.address) }, enabled = bluetoothOn) {
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

/** "Headphones", "Speaker", or for a JBL speaker "Speaker · JBL Flip 6 · PartyBoost". */
@Composable
private fun kindLine(device: DeviceUi): String {
    val kind = stringResource(device.kind.labelRes())
    return if (device.isJbl) "$kind · ${modelLine(device)}" else kind
}

@Composable
private fun modelLine(device: DeviceUi): String {
    val model = device.model ?: return stringResource(R.string.model_unknown)
    val base = "${model.titleWithConfidence()} · ${model.familyLabel()}"
    return if (device.modelSource == IdentificationSource.USER) "$base (${stringResource(R.string.model_set_by_you)})" else base
}

@Composable
private fun stateLine(device: DeviceUi): String {
    val base = when {
        device.playing -> stringResource(R.string.state_playing)
        device.link == LinkState.CONNECTED -> stringResource(R.string.state_connected)
        device.link == LinkState.CONNECTING -> stringResource(R.string.state_connecting)
        device.link == LinkState.DISCONNECTING -> stringResource(R.string.state_disconnecting)
        else -> stringResource(R.string.state_disconnected)
    }
    return if (!device.connected && device.nearby) "$base · ${stringResource(R.string.state_nearby)}" else base
}

@Composable
private fun StatusLine(status: SpeakerStatus) {
    // While a refresh runs, the last good reading stays visible.
    val shown = (status as? SpeakerStatus.Reading)?.previous ?: status
    val text = when (shown) {
        is SpeakerStatus.Reading -> stringResource(R.string.status_reading)
        is SpeakerStatus.Failed -> stringResource(shown.reason.textRes())
        is SpeakerStatus.Ready -> buildList {
            val info = shown.info
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
            if (status is SpeakerStatus.Reading) add(stringResource(R.string.status_reading))
            else if (isEmpty()) add(stringResource(R.string.status_no_details))
        }.joinToString(" · ")
    }
    if (text.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            val icon = when {
                status is SpeakerStatus.Reading -> R.drawable.ic_refresh
                shown is SpeakerStatus.Ready && shown.info.charging == true -> R.drawable.ic_battery_charging_full
                shown is SpeakerStatus.Ready -> batteryIcon(shown.info.batteryPercent)
                else -> R.drawable.ic_warning
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
private fun DiscoveredCard(device: DeviceUi, onPair: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("discovered_card")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(device.kind.iconRes(), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(kindLine(device), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (device.bonding) {
                Text(stringResource(R.string.pairing), style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(onClick = onPair) { Text(stringResource(R.string.action_pair)) }
            }
        }
    }
}

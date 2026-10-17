package io.github.cyrus_sch.speakerlink.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.cyrus_sch.speakerlink.R
import io.github.cyrus_sch.speakerlink.bluetooth.BluetoothEvent
import io.github.cyrus_sch.speakerlink.system.SystemScreens
import kotlinx.coroutines.flow.Flow

enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int, val tag: String) {
    DEVICES(R.string.tab_speakers, R.drawable.ic_bluetooth, "tab_devices"),
    TOGETHER(R.string.tab_party, R.drawable.ic_groups, "tab_together"),
    INFO(R.string.tab_info, R.drawable.ic_info, "tab_info"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    state: UiState,
    events: Flow<BluetoothEvent>,
    deviceActions: DeviceActions,
    togetherActions: TogetherActions,
    infoActions: InfoActions,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.DEVICES) }
    val snackbars = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(events) {
        events.collect { event ->
            val message = event.message()
            val text = if (message.argument != null) {
                resources.getString(message.text, message.argument)
            } else {
                resources.getString(message.text)
            }
            val actionLabel = message.action?.let {
                resources.getString(if (it == EventAction.OUTPUT_SWITCHER) R.string.action_open else R.string.action_settings)
            }
            val result = snackbars.showSnackbar(
                message = text,
                actionLabel = actionLabel,
                withDismissAction = actionLabel == null,
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                when (message.action) {
                    EventAction.BLUETOOTH_SETTINGS -> SystemScreens.openBluetoothSettings(context)
                    EventAction.OUTPUT_SWITCHER -> SystemScreens.openOutputSwitcher(context)
                    EventAction.LOCATION_SETTINGS -> SystemScreens.openLocationSettings(context)
                    null -> Unit
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { AppIcon(item.icon) },
                        label = { Text(stringResource(item.label)) },
                        modifier = Modifier.testTag(item.tag),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        when (tab) {
            Tab.DEVICES -> DevicesScreen(state, deviceActions, padding)
            Tab.TOGETHER -> TogetherScreen(state, togetherActions, padding)
            Tab.INFO -> InfoScreen(state, infoActions, padding)
        }
    }
}

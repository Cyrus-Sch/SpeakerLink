package io.github.cyrus_sch.speakerlink.ui

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.cyrus_sch.speakerlink.bluetooth.BtPermissions
import io.github.cyrus_sch.speakerlink.system.SystemScreens
import io.github.cyrus_sch.speakerlink.ui.theme.SpeakerLinkTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onPermissionsResult()
    }

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val deviceActions = DeviceActions(
            requestPermissions = { permissionLauncher.launch(BtPermissions.runtime) },
            openAppSettings = { SystemScreens.openAppSettings(this) },
            enableBluetooth = ::requestEnableBluetooth,
            scan = viewModel::scan,
            stopScan = viewModel::stopScan,
            pair = viewModel::pair,
            connect = viewModel::connect,
            disconnect = viewModel::disconnect,
            playOn = viewModel::playOn,
            connectAll = viewModel::connectAll,
            setSelected = viewModel::setSelected,
            setModel = viewModel::setModel,
            readStatus = viewModel::readStatus,
            readAllStatuses = viewModel::readAllStatuses,
            locate = viewModel::playLocateSound,
            testSound = viewModel::playTestSound,
            openOutputSwitcher = { SystemScreens.openOutputSwitcher(this) },
            openBluetoothSettings = { SystemScreens.openBluetoothSettings(this) },
        )
        val togetherActions = TogetherActions(
            setSelected = viewModel::setSelected,
            makeHost = viewModel::setPreferredHost,
            connectHost = viewModel::connectHost,
            testSound = viewModel::playTestSound,
            openOutputSwitcher = { SystemScreens.openOutputSwitcher(this) },
            openAudioSharing = { SystemScreens.openAudioSharing(this) },
        )
        val infoActions = InfoActions(
            openDeveloperOptions = { SystemScreens.openDeveloperOptions(this) },
            openSource = { SystemScreens.openUrl(this, SOURCE_URL) },
        )

        setContent {
            SpeakerLinkTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                AppRoot(state, viewModel.events, deviceActions, togetherActions, infoActions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    private fun requestEnableBluetooth() {
        if (!BtPermissions.canConnect(this)) {
            permissionLauncher.launch(BtPermissions.runtime)
            return
        }
        try {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: ActivityNotFoundException) {
            SystemScreens.openBluetoothSettings(this)
        } catch (e: SecurityException) {
            SystemScreens.openBluetoothSettings(this)
        }
    }
}

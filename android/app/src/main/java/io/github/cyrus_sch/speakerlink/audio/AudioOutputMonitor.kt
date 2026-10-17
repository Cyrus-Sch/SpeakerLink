package io.github.cyrus_sch.speakerlink.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.cyrus_sch.speakerlink.core.BluetoothAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A Bluetooth audio output the audio framework currently routes to. */
data class BluetoothOutput(
    /** Classic address of the device (Android 9+), or null when the framework does not say. */
    val address: String?,
    val name: String,
    val type: Int,
)

/**
 * Tracks which Bluetooth outputs the audio framework knows about. On stock Android only the
 * active A2DP speaker is listed, so this is the public way to tell which speaker is playing.
 */
class AudioOutputMonitor(context: Context) {
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)

    private val bluetoothTypes: Set<Int> = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
        }
    }

    private val _outputs = MutableStateFlow(read())
    val outputs: StateFlow<List<BluetoothOutput>> = _outputs.asStateFlow()

    init {
        audioManager?.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    _outputs.value = read()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    _outputs.value = read()
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    /** Re-reads the outputs, e.g. after the Bluetooth permission was granted (addresses become readable). */
    fun refresh() {
        _outputs.value = read()
    }

    fun bluetoothDevices(): List<AudioDeviceInfo> =
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().filter { it.type in bluetoothTypes }

    private fun read(): List<BluetoothOutput> = bluetoothDevices().map { device ->
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.address.takeIf { BluetoothAddress.isValid(it) }?.let(BluetoothAddress::normalize)
        } else {
            null
        }
        BluetoothOutput(address = address, name = device.productName?.toString().orEmpty(), type = device.type)
    }
}

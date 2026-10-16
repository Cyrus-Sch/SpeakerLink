package io.github.cyrus_sch.speakerlink.bluetooth

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import io.github.cyrus_sch.speakerlink.core.HarmanAdvertisement

/** Bluetooth adapter state as the UI needs it. */
enum class AdapterState { UNAVAILABLE, OFF, TURNING_ON, ON, TURNING_OFF }

/** A2DP connection state of one speaker. */
enum class LinkState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ;

    companion object {
        fun fromProfileState(state: Int): LinkState = when (state) {
            BluetoothProfile.STATE_CONNECTING -> CONNECTING
            BluetoothProfile.STATE_CONNECTED -> CONNECTED
            BluetoothProfile.STATE_DISCONNECTING -> DISCONNECTING
            else -> DISCONNECTED
        }
    }
}

/** Everything the app knows about one Bluetooth device, keyed by its classic address. */
data class DeviceRecord(
    val address: String,
    val name: String?,
    val bondState: Int = BluetoothDevice.BOND_NONE,
    val link: LinkState = LinkState.DISCONNECTED,
    /** The speaker reports that A2DP audio is streaming to it. */
    val a2dpPlaying: Boolean = false,
    val majorClass: Int? = null,
    val deviceClass: Int? = null,
    /** Advertises the A2DP sink service (it can play phone audio). */
    val audioSink: Boolean = false,
    /** Found by classic discovery during this app session (it is in pairing mode). */
    val discovered: Boolean = false,
    val rssi: Int? = null,
    /** SystemClock.elapsedRealtime() of the last discovery result, advertisement or link event. */
    val lastSeen: Long? = null,
    val advertisement: HarmanAdvertisement? = null,
    /** Bluetooth LE address the speaker last advertised from (often a rotating random address). */
    val bleAddress: String? = null,
    /** SystemClock.elapsedRealtime() of the last LE advertisement. */
    val bleSeenAt: Long? = null,
    /** SystemClock.elapsedRealtime() of a connect request the app is still waiting for. */
    val connectRequestedAt: Long? = null,
    /** The app asked to pair with this device and will connect it once bonding succeeds. */
    val pairRequested: Boolean = false,
) {
    val bonded: Boolean get() = bondState == BluetoothDevice.BOND_BONDED
    val bonding: Boolean get() = bondState == BluetoothDevice.BOND_BONDING

    /** Headphones and headsets are audio devices too, but never part of a speaker party. */
    val isHeadphones: Boolean
        get() = deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE

    val isAudioDevice: Boolean
        get() = audioSink || majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO

    fun seenWithin(nowElapsed: Long, windowMillis: Long): Boolean =
        lastSeen != null && nowElapsed - lastSeen <= windowMillis
}

/** A Harman advertisement that could not be tied to a known device (speaker not in pairing mode). */
data class NearbyAdvert(
    val bleAddress: String,
    val name: String?,
    val advertisement: HarmanAdvertisement,
    val lastSeen: Long,
)

/** One-off messages for the UI (shown as snackbars). */
sealed interface BluetoothEvent {
    val address: String?

    data class Paired(override val address: String, val name: String) : BluetoothEvent
    data class PairingFailed(override val address: String, val name: String) : BluetoothEvent
    data class Connected(override val address: String, val name: String) : BluetoothEvent
    data class ConnectFailed(override val address: String, val name: String, val limitReached: Boolean) : BluetoothEvent

    /** The device cannot connect speakers itself; the user has to use the system screen. */
    data class NeedsSystemSettings(override val address: String, val name: String) : BluetoothEvent

    /** Choosing the playing speaker has to go through the system output switcher. */
    data class NeedsOutputSwitcher(override val address: String, val name: String) : BluetoothEvent
    data class ScanFailed(val locationOff: Boolean) : BluetoothEvent {
        override val address: String? get() = null
    }
    data object BluetoothOff : BluetoothEvent {
        override val address: String? get() = null
    }
}

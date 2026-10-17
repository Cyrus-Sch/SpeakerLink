package io.github.cyrus_sch.speakerlink.bluetooth

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import io.github.cyrus_sch.speakerlink.core.HarmanAdvertisement

/** Bluetooth adapter state as the UI needs it. */
enum class AdapterState { UNAVAILABLE, OFF, TURNING_ON, ON, TURNING_OFF }

/** A2DP connection state of one device. */
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

/** What kind of Bluetooth audio device something is, from its Bluetooth device class. */
enum class AudioKind {
    /** Headphones, earbuds and headsets. */
    HEADPHONES,

    /** Loudspeakers, portable speakers and hi-fi systems. */
    SPEAKER,

    /** Any other audio device (car audio, TVs, uncategorised or unknown class). */
    OTHER,
}

/** Everything the app knows about one Bluetooth device, keyed by its classic address. */
data class DeviceRecord(
    val address: String,
    /** Display name: the phone-local alias if the user renamed the device, else its own name. */
    val name: String?,
    /** The name the device itself reports; used to recognise the model after a local rename. */
    val remoteName: String? = null,
    val bondState: Int = BluetoothDevice.BOND_NONE,
    val link: LinkState = LinkState.DISCONNECTED,
    /** The device reports that A2DP audio is streaming to it. */
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
    /** Bluetooth LE address a JBL speaker last advertised from (often a rotating random address). */
    val bleAddress: String? = null,
    /** SystemClock.elapsedRealtime() of the last LE advertisement. */
    val bleSeenAt: Long? = null,
    /** The LE address was tied to this device by address, not by name or model. */
    val bleMatchExact: Boolean = false,
    /** SystemClock.elapsedRealtime() of a connect request the app is still waiting for. */
    val connectRequestedAt: Long? = null,
    /** The app asked to pair with this device and will connect it once bonding succeeds. */
    val pairRequested: Boolean = false,
) {
    val bonded: Boolean get() = bondState == BluetoothDevice.BOND_BONDED
    val bonding: Boolean get() = bondState == BluetoothDevice.BOND_BONDING

    /** Headphones, earbuds and headsets (some speakers with a speakerphone report this class too). */
    val isHeadphones: Boolean
        get() = deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE

    val isSpeaker: Boolean
        get() = deviceClass == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER ||
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO ||
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO

    /** Kind shown to the user; hands-free devices (often car kits) count as other audio devices. */
    val audioKind: AudioKind
        get() = when {
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
                deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> AudioKind.HEADPHONES
            isSpeaker -> AudioKind.SPEAKER
            else -> AudioKind.OTHER
        }

    /**
     * Speakers, headphones and other devices that play audio. Devices of another major class
     * (phones, computers, watches, keyboards...) are left out even if they offer an audio sink.
     */
    val isAudioDevice: Boolean
        get() = when (majorClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> true
            null, BluetoothClass.Device.Major.UNCATEGORIZED, BluetoothClass.Device.Major.MISC -> audioSink
            else -> false
        }

    fun seenWithin(nowElapsed: Long, windowMillis: Long): Boolean =
        lastSeen != null && nowElapsed - lastSeen <= windowMillis
}

/** A Harman (JBL) advertisement that could not be tied to a known device (speaker not in pairing mode). */
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

    /** The phone does not let the app connect devices; the user has to use the system screen. */
    data class NeedsSystemSettings(override val address: String, val name: String) : BluetoothEvent

    /** Choosing the playing device has to go through the system output switcher. */
    data class NeedsOutputSwitcher(override val address: String, val name: String) : BluetoothEvent
    data class ScanFailed(val locationOff: Boolean) : BluetoothEvent {
        override val address: String? get() = null
    }
    data object BluetoothOff : BluetoothEvent {
        override val address: String? get() = null
    }
}

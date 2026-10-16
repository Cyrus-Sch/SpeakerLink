package io.github.cyrus_sch.speakerlink.status

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.cyrus_sch.speakerlink.bluetooth.BtPermissions
import io.github.cyrus_sch.speakerlink.core.FrameDecoder
import io.github.cyrus_sch.speakerlink.core.JblProtocol
import io.github.cyrus_sch.speakerlink.core.SpeakerInfo
import io.github.cyrus_sch.speakerlink.core.SpeakerInfoParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Result of asking a speaker for its status over its vendor control service. */
sealed interface SpeakerStatus {
    data object Reading : SpeakerStatus

    data class Ready(val info: SpeakerInfo, val readAt: Long) : SpeakerStatus

    data class Failed(val reason: Reason) : SpeakerStatus

    enum class Reason {
        /** The speaker could not be reached over Bluetooth LE (off, out of range, or busy). */
        UNREACHABLE,

        /** Connected, but the speaker has no JBL control service. */
        NO_CONTROL_SERVICE,

        /** Connected, but the speaker did not answer. Another phone may hold its control link. */
        NO_RESPONSE,

        NO_PERMISSION,
    }
}

/** One way to reach a speaker's control service. */
data class GattTarget(val address: String, val transport: Int)

/**
 * Talks to the JBL control service (see [JblProtocol]) to read battery, name and firmware, or to
 * play the speaker's locate sound. Speakers accept a single control connection at a time, so the
 * reader connects, asks, and disconnects straight away; sessions run one after another.
 */
@SuppressLint("MissingPermission") // every entry point checks BtPermissions.canConnect first
class SpeakerStatusReader(private val context: Context, private val scope: CoroutineScope) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mutex = Mutex()

    private val _statuses = MutableStateFlow<Map<String, SpeakerStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, SpeakerStatus>> = _statuses.asStateFlow()

    /** Reads the status of the speaker identified by [key], trying [targets] in order. */
    fun read(key: String, targets: List<GattTarget>) {
        if (_statuses.value[key] == SpeakerStatus.Reading) return
        _statuses.update { it + (key to SpeakerStatus.Reading) }
        scope.launch {
            val status = mutex.withLock {
                execute(targets, listOf(JblProtocol.requestSpeakerInfo(), JblProtocol.requestFirmware()), INFO_COLLECT_MILLIS)
            }
            _statuses.update { it + (key to status) }
        }
    }

    /** Plays the speaker's "find me" sound. Returns through [statuses] like [read]. */
    fun playLocateSound(key: String, targets: List<GattTarget>) {
        scope.launch {
            val status = mutex.withLock { execute(targets, listOf(JblProtocol.playSound()), 400) }
            if (status is SpeakerStatus.Failed) _statuses.update { it + (key to status) }
        }
    }

    private suspend fun execute(targets: List<GattTarget>, commands: List<ByteArray>, collectMillis: Long): SpeakerStatus {
        if (!BtPermissions.canConnect(context)) return SpeakerStatus.Failed(SpeakerStatus.Reason.NO_PERMISSION)
        var failure = SpeakerStatus.Reason.UNREACHABLE
        for (target in targets.distinct()) {
            // A fresh connection often fails once with status 133; one retry is usually enough.
            repeat(2) { attempt ->
                when (val outcome = session(target, commands, collectMillis)) {
                    is Outcome.Success -> return SpeakerStatus.Ready(outcome.info, SystemClock.elapsedRealtime())
                    Outcome.NoService -> failure = SpeakerStatus.Reason.NO_CONTROL_SERVICE
                    Outcome.NoResponse -> failure = SpeakerStatus.Reason.NO_RESPONSE
                    Outcome.ConnectFailed -> if (attempt == 0) delay(600)
                }
                if (failure != SpeakerStatus.Reason.UNREACHABLE) return SpeakerStatus.Failed(failure)
            }
        }
        return SpeakerStatus.Failed(failure)
    }

    private sealed interface Outcome {
        data class Success(val info: SpeakerInfo) : Outcome
        data object ConnectFailed : Outcome
        data object NoService : Outcome
        data object NoResponse : Outcome
    }

    private sealed interface Event {
        data class Connection(val status: Int, val state: Int) : Event
        data object MtuChanged : Event
        data class ServicesDiscovered(val status: Int) : Event
        data object DescriptorWritten : Event
        data object CharacteristicWritten : Event
        class Notification(val value: ByteArray) : Event
    }

    private suspend fun session(target: GattTarget, commands: List<ByteArray>, collectMillis: Long): Outcome {
        val adapter = adapter ?: return Outcome.ConnectFailed
        if (!BluetoothAdapter.checkBluetoothAddress(target.address)) return Outcome.ConnectFailed
        val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(target.address) }.getOrNull()
            ?: return Outcome.ConnectFailed
        val events = Channel<Event>(Channel.UNLIMITED)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                events.trySend(Event.Connection(status, newState))
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                events.trySend(Event.MtuChanged)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                events.trySend(Event.ServicesDiscovered(status))
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                events.trySend(Event.DescriptorWritten)
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                events.trySend(Event.CharacteristicWritten)
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                events.trySend(Event.Notification(value.copyOf()))
            }

            @Deprecated("Called on Android 12 and lower")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                // Android 13+ calls both overloads; only use this one where the other does not exist.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    characteristic.value?.let { events.trySend(Event.Notification(it.copyOf())) }
                }
            }
        }

        val gatt = withContext(Dispatchers.Main) {
            runCatching { device.connectGatt(context, false, callback, target.transport) }.getOrNull()
        } ?: return Outcome.ConnectFailed

        try {
            val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
                var result: Boolean? = null
                while (result == null) {
                    val event = events.receive()
                    if (event is Event.Connection) {
                        result = event.status == BluetoothGatt.GATT_SUCCESS && event.state == BluetoothProfile.STATE_CONNECTED
                    }
                }
                result
            } ?: false
            if (!connected) return Outcome.ConnectFailed

            if (gatt.requestMtu(MTU)) awaitEvent(events, 2_000) { it is Event.MtuChanged }
            if (!gatt.discoverServices()) return Outcome.NoService
            val discovered = awaitEvent(events, 10_000) { it is Event.ServicesDiscovered } as? Event.ServicesDiscovered
            if (discovered?.status != BluetoothGatt.GATT_SUCCESS) return Outcome.NoService

            val service = gatt.getService(JblProtocol.SERVICE) ?: return Outcome.NoService
            val notify = service.getCharacteristic(JblProtocol.NOTIFY_CHARACTERISTIC) ?: return Outcome.NoService
            val write = service.getCharacteristic(JblProtocol.WRITE_CHARACTERISTIC) ?: return Outcome.NoService

            gatt.setCharacteristicNotification(notify, true)
            notify.getDescriptor(JblProtocol.CCCD)?.let { descriptor ->
                if (writeDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    awaitEvent(events, 1_500) { it is Event.DescriptorWritten }
                }
            }

            val withResponse = (write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            val writeType = if (withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

            val decoder = FrameDecoder()
            var info = SpeakerInfo()
            var answered = false
            for (command in commands) {
                if (!writeCharacteristic(gatt, write, command, writeType)) continue
                if (withResponse) awaitEvent(events, 1_500) { it is Event.CharacteristicWritten }
                // Collect the burst of answers; stop early once the speaker goes quiet.
                val deadline = SystemClock.elapsedRealtime() + collectMillis
                var quietUntil = deadline
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    val until = minOf(deadline, quietUntil)
                    if (now >= until) break
                    val event = withTimeoutOrNull(until - now) { events.receive() } ?: break
                    when (event) {
                        is Event.Notification -> {
                            answered = true
                            for (frame in decoder.feed(event.value)) {
                                SpeakerInfoParser.parse(frame)?.let { info = info.merge(it) }
                            }
                            quietUntil = SystemClock.elapsedRealtime() + QUIET_MILLIS
                        }
                        is Event.Connection -> if (event.state != BluetoothProfile.STATE_CONNECTED) break
                        else -> Unit
                    }
                }
            }
            return when {
                !info.isEmpty -> Outcome.Success(info)
                answered || commands.none { it[1].toInt() == JblProtocol.CMD_REQUEST_INFO } -> Outcome.Success(info)
                else -> Outcome.NoResponse
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission lost during GATT session", e)
            return Outcome.ConnectFailed
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
            events.close()
        }
    }

    private suspend fun awaitEvent(events: Channel<Event>, timeoutMillis: Long, match: (Event) -> Boolean): Event? =
        withTimeoutOrNull(timeoutMillis) {
            var found: Event? = null
            while (found == null) {
                val event = events.receive()
                if (match(event)) found = event
            }
            found
        }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
    } else {
        characteristic.writeType = writeType
        characteristic.value = value
        gatt.writeCharacteristic(characteristic)
    }

    private companion object {
        const val TAG = "SpeakerStatusReader"
        const val MTU = 517
        const val CONNECT_TIMEOUT_MILLIS = 12_000L
        const val INFO_COLLECT_MILLIS = 2_500L
        const val QUIET_MILLIS = 700L
    }
}

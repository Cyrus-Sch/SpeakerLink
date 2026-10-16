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
import io.github.cyrus_sch.speakerlink.core.SpeakerIdentifier
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

    /** Ordered from least to most specific; the most specific failure seen is reported. */
    enum class Reason {
        /** The speaker could not be reached over Bluetooth (off, out of range, or busy). */
        UNREACHABLE,

        /** Connected, but the speaker has no JBL control service the app understands. */
        NO_CONTROL_SERVICE,

        /** Connected, but the speaker did not answer. Another phone may hold its control link. */
        NO_RESPONSE,

        NO_PERMISSION,
    }
}

/**
 * One way to reach a speaker's control service.
 *
 * @property randomAddress the address is a (rotating) random LE address, not the public one.
 */
data class GattTarget(val address: String, val transport: Int, val randomAddress: Boolean = false)

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

    /** Marks [key] as being read, so the UI shows progress while the caller prepares targets. */
    fun markReading(key: String): Boolean {
        if (_statuses.value[key] == SpeakerStatus.Reading) return false
        _statuses.update { it + (key to SpeakerStatus.Reading) }
        return true
    }

    /**
     * Reads the status of the speaker identified by [key], trying [targets] in order. A reply that
     * names a different speaker than [expectedName] is ignored, because LE addresses are matched
     * to paired speakers partly by heuristics.
     */
    fun read(key: String, targets: List<GattTarget>, expectedName: String?) {
        _statuses.update { it + (key to SpeakerStatus.Reading) }
        scope.launch {
            val status = mutex.withLock {
                execute(
                    targets,
                    listOf(JblProtocol.requestSpeakerInfo(), JblProtocol.requestFirmware()),
                    INFO_COLLECT_MILLIS,
                    expectedName,
                    expectInfo = true,
                )
            }
            _statuses.update { current ->
                // A failed refresh keeps showing the last good reading.
                val previous = current[key]
                if (status is SpeakerStatus.Failed && previous is SpeakerStatus.Ready && previous.info.batteryPercent != null) {
                    current + (key to previous)
                } else {
                    current + (key to status)
                }
            }
        }
    }

    /** Plays the speaker's "find me" sound. Only failures are reported through [statuses]. */
    fun playLocateSound(key: String, targets: List<GattTarget>) {
        scope.launch {
            val status = mutex.withLock {
                execute(targets, listOf(JblProtocol.playSound()), 400, expectedName = null, expectInfo = false)
            }
            if (status is SpeakerStatus.Failed) _statuses.update { it + (key to status) }
            else if (_statuses.value[key] == SpeakerStatus.Reading) _statuses.update { it - key }
        }
    }

    private suspend fun execute(
        targets: List<GattTarget>,
        commands: List<ByteArray>,
        collectMillis: Long,
        expectedName: String?,
        expectInfo: Boolean,
    ): SpeakerStatus {
        if (!BtPermissions.canConnect(context)) return SpeakerStatus.Failed(SpeakerStatus.Reason.NO_PERMISSION)
        var failure = SpeakerStatus.Reason.UNREACHABLE
        fun note(reason: SpeakerStatus.Reason) {
            if (reason > failure) failure = reason
        }
        for (target in targets.distinct()) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                when (val outcome = session(target, commands, collectMillis, expectInfo)) {
                    is Outcome.Success -> {
                        val name = outcome.info.name
                        if (expectedName != null && name != null && !SpeakerIdentifier.sameSpeakerName(expectedName, name)) {
                            Log.i(TAG, "Ignoring reply from \"$name\" while looking for \"$expectedName\"")
                            break // someone else's speaker: try the next target
                        }
                        return SpeakerStatus.Ready(outcome.info, SystemClock.elapsedRealtime())
                    }
                    // A fresh connection often fails at once with status 133; one retry usually helps.
                    Outcome.FastFailure, Outcome.NotSent -> delay(RETRY_DELAY_MILLIS)
                    Outcome.Timeout -> break
                    Outcome.NoService -> {
                        note(SpeakerStatus.Reason.NO_CONTROL_SERVICE)
                        break
                    }
                    Outcome.NoResponse -> {
                        note(SpeakerStatus.Reason.NO_RESPONSE)
                        break
                    }
                }
            }
        }
        return SpeakerStatus.Failed(failure)
    }

    private sealed interface Outcome {
        data class Success(val info: SpeakerInfo) : Outcome

        /** The stack reported a connection error quickly (worth one retry). */
        data object FastFailure : Outcome

        /** Nothing happened before the connect timeout (speaker off or out of range). */
        data object Timeout : Outcome
        data object NoService : Outcome
        data object NoResponse : Outcome

        /** Connected, but no command could be written. */
        data object NotSent : Outcome
    }

    private sealed interface Event {
        data class Connection(val status: Int, val state: Int) : Event
        data object MtuChanged : Event
        data class ServicesDiscovered(val status: Int) : Event
        data object DescriptorWritten : Event
        data object CharacteristicWritten : Event
        class Notification(val value: ByteArray) : Event
    }

    /** Thrown inside a session when the link drops. */
    private class Disconnected : Exception()

    private suspend fun session(target: GattTarget, commands: List<ByteArray>, collectMillis: Long, expectInfo: Boolean): Outcome {
        val device = remoteDevice(target) ?: return Outcome.FastFailure
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

        @Suppress("DEPRECATION") // the replacement needs API 37
        val gatt = withContext(Dispatchers.Main) {
            runCatching { device.connectGatt(context, false, callback, target.transport) }.getOrNull()
        } ?: return Outcome.FastFailure

        val decoder = FrameDecoder()
        var info = SpeakerInfo()
        var framesSeen = 0
        var unsupported = false
        fun consume(event: Event) {
            if (event !is Event.Notification) return
            for (frame in decoder.feed(event.value)) {
                framesSeen++
                if (frame.command == JblProtocol.CMD_UNSUPPORTED) unsupported = true
                SpeakerInfoParser.parse(frame)?.let { info = info.merge(it) }
            }
        }

        /** Waits for an event matching [match]; notifications are consumed, a dropped link throws. */
        suspend fun await(timeoutMillis: Long, match: (Event) -> Boolean): Event? = withTimeoutOrNull(timeoutMillis) {
            var found: Event? = null
            while (found == null) {
                val event = events.receive()
                when {
                    match(event) -> found = event
                    event is Event.Connection && event.state != BluetoothProfile.STATE_CONNECTED -> throw Disconnected()
                    else -> consume(event)
                }
            }
            found
        }

        try {
            val connection = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
                var result: Event.Connection? = null
                while (result == null) {
                    val event = events.receive()
                    if (event is Event.Connection) result = event
                }
                result
            } ?: return Outcome.Timeout
            if (connection.status != BluetoothGatt.GATT_SUCCESS || connection.state != BluetoothProfile.STATE_CONNECTED) {
                return Outcome.FastFailure
            }

            if (gatt.requestMtu(MTU)) await(2_000) { it is Event.MtuChanged }
            if (!gatt.discoverServices()) return Outcome.FastFailure
            val discovered = await(10_000) { it is Event.ServicesDiscovered } as? Event.ServicesDiscovered
                ?: return Outcome.FastFailure
            if (discovered.status != BluetoothGatt.GATT_SUCCESS) return Outcome.FastFailure

            val service = gatt.getService(JblProtocol.SERVICE) ?: return Outcome.NoService
            val notify = service.getCharacteristic(JblProtocol.NOTIFY_CHARACTERISTIC) ?: return Outcome.NoService
            val write = service.getCharacteristic(JblProtocol.WRITE_CHARACTERISTIC) ?: return Outcome.NoService

            gatt.setCharacteristicNotification(notify, true)
            // Some speakers have no CCCD and notify anyway; a failed write is not fatal.
            notify.getDescriptor(JblProtocol.CCCD)?.let { descriptor ->
                if (writeDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    await(1_500) { it is Event.DescriptorWritten }
                }
            }

            val withResponse = (write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            val writeType = if (withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

            var written = 0
            for (command in commands) {
                // A partial frame left over from the previous answer must not swallow this one.
                if (decoder.hasPartialFrame) decoder.reset()
                var sent = writeCharacteristic(gatt, write, command, writeType)
                if (!sent) {
                    delay(300) // the stack may still be busy with the previous operation
                    sent = writeCharacteristic(gatt, write, command, writeType)
                }
                if (!sent) continue
                written++
                if (withResponse) await(1_500) { it is Event.CharacteristicWritten }
                // Collect the answers; stop early once the speaker goes quiet.
                val deadline = SystemClock.elapsedRealtime() + collectMillis
                var quietUntil = deadline
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    val until = minOf(deadline, quietUntil)
                    if (now >= until) break
                    val event = withTimeoutOrNull(until - now) { events.receive() } ?: break
                    if (event is Event.Connection && event.state != BluetoothProfile.STATE_CONNECTED) throw Disconnected()
                    if (event is Event.Notification) {
                        consume(event)
                        quietUntil = SystemClock.elapsedRealtime() + QUIET_MILLIS
                    }
                }
            }
            return when {
                written == 0 -> Outcome.NotSent
                !expectInfo -> Outcome.Success(info)
                !info.isEmpty -> Outcome.Success(info)
                unsupported -> Outcome.NoService
                else -> Outcome.NoResponse
            }
        } catch (e: Disconnected) {
            return if (framesSeen > 0 && !info.isEmpty) Outcome.Success(info) else Outcome.FastFailure
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission lost during GATT session", e)
            return Outcome.FastFailure
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
            events.close()
        }
    }

    private fun remoteDevice(target: GattTarget): BluetoothDevice? {
        val adapter = adapter ?: return null
        if (!BluetoothAdapter.checkBluetoothAddress(target.address)) return null
        return runCatching {
            if (target.randomAddress && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Keeps the address type, which getRemoteDevice(String) would reset to public.
                adapter.getRemoteLeDevice(target.address, BluetoothDevice.ADDRESS_TYPE_RANDOM)
            } else {
                adapter.getRemoteDevice(target.address)
            }
        }.getOrNull()
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
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
        const val RETRY_DELAY_MILLIS = 600L
        const val INFO_COLLECT_MILLIS = 2_500L
        const val QUIET_MILLIS = 700L
    }
}

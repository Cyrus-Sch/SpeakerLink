package io.github.cyrus_sch.speakerlink.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.cyrus_sch.speakerlink.SpeakerLinkApp
import io.github.cyrus_sch.speakerlink.audio.BluetoothOutput
import io.github.cyrus_sch.speakerlink.bluetooth.AdapterState
import io.github.cyrus_sch.speakerlink.bluetooth.BluetoothEvent
import io.github.cyrus_sch.speakerlink.bluetooth.BtPermissions
import io.github.cyrus_sch.speakerlink.bluetooth.DeviceRecord
import io.github.cyrus_sch.speakerlink.bluetooth.HiddenBluetooth
import io.github.cyrus_sch.speakerlink.bluetooth.LinkState
import io.github.cyrus_sch.speakerlink.bluetooth.NearbyAdvert
import io.github.cyrus_sch.speakerlink.core.ControlProtocol
import io.github.cyrus_sch.speakerlink.core.IdentificationSource
import io.github.cyrus_sch.speakerlink.core.PartyPlan
import io.github.cyrus_sch.speakerlink.core.PartyPlanner
import io.github.cyrus_sch.speakerlink.core.PlannerSpeaker
import io.github.cyrus_sch.speakerlink.core.SpeakerIdentifier
import io.github.cyrus_sch.speakerlink.core.SpeakerModel
import io.github.cyrus_sch.speakerlink.data.Preferences
import io.github.cyrus_sch.speakerlink.status.GattTarget
import io.github.cyrus_sch.speakerlink.status.SpeakerStatus
import io.github.cyrus_sch.speakerlink.system.DeviceCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One speaker as the screens show it. */
data class SpeakerUi(
    val address: String,
    val name: String,
    val model: SpeakerModel?,
    val modelSource: IdentificationSource,
    val isJbl: Boolean,
    val bonded: Boolean,
    val bonding: Boolean,
    val link: LinkState,
    /** The phone's media audio currently goes to this speaker. */
    val playing: Boolean,
    /** Seen by discovery, an advertisement or a connection in the last two minutes. */
    val nearby: Boolean,
    val inParty: Boolean,
    val status: SpeakerStatus?,
    /** The speaker speaks the JBL control protocol, so battery etc. can be read. */
    val canReadStatus: Boolean,
) {
    val connected: Boolean get() = link == LinkState.CONNECTED
}

data class UiState(
    val bluetoothSupported: Boolean = true,
    val permissionsGranted: Boolean = false,
    val adapter: AdapterState = AdapterState.OFF,
    /** Paired speakers (JBL only, unless the user asked to see every audio device). */
    val speakers: List<SpeakerUi> = emptyList(),
    /** Unpaired speakers found by a scan (in pairing mode). */
    val discovered: List<SpeakerUi> = emptyList(),
    /** JBL models advertising nearby that a scan could not pair with (not in pairing mode). */
    val nearbyNotInPairingMode: List<String> = emptyList(),
    val scanning: Boolean = false,
    val connectedCount: Int = 0,
    val maxConnected: Int? = null,
    val playing: SpeakerUi? = null,
    val canConnectDirectly: Boolean = true,
    val partySpeakers: List<SpeakerUi> = emptyList(),
    val plan: PartyPlan = PartyPlan(emptyList(), emptyList(), emptyList()),
    val preferredHost: String? = null,
    val showAllAudioDevices: Boolean = false,
    val capabilities: DeviceCapabilities? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as SpeakerLinkApp).container
    private val bluetooth = container.bluetooth
    private val prefs = container.prefs
    private val statusReader = container.statusReader

    private val permissions = MutableStateFlow(BtPermissions.allGranted(application))
    private val capabilities = MutableStateFlow(DeviceCapabilities.read(application))
    private val maxConnected = MutableStateFlow<Int?>(null)

    /** One-off messages for snackbars. */
    val events: Flow<BluetoothEvent> = bluetooth.events

    private data class BluetoothSnapshot(
        val adapter: AdapterState,
        val devices: Map<String, DeviceRecord>,
        val discovering: Boolean,
        val activeAddress: String?,
        val nearby: Map<String, NearbyAdvert>,
    )

    private data class Environment(
        val outputs: List<BluetoothOutput>,
        val prefs: Preferences,
        val statuses: Map<String, SpeakerStatus>,
        val permissions: Boolean,
        val now: Long,
    )

    /** Re-evaluates "nearby" badges as time passes. */
    private val clock = flow {
        while (true) {
            emit(SystemClock.elapsedRealtime())
            delay(15_000)
        }
    }

    private val bluetoothSnapshot = combine(
        bluetooth.adapterState,
        bluetooth.devices,
        bluetooth.discovering,
        bluetooth.activeAddress,
        bluetooth.nearbyAdverts,
    ) { adapter, devices, discovering, active, nearby -> BluetoothSnapshot(adapter, devices, discovering, active, nearby) }

    private val environment = combine(
        container.audioOutputs.outputs,
        prefs.state,
        statusReader.statuses,
        permissions,
        clock,
    ) { outputs, prefs, statuses, permissions, now -> Environment(outputs, prefs, statuses, permissions, now) }

    val state: StateFlow<UiState> = combine(bluetoothSnapshot, environment, capabilities, maxConnected) { bt, env, caps, max ->
        build(bt, env, caps, max)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(capabilities = capabilities.value))

    init {
        bluetooth.start()
    }

    // ------------------------------------------------------------------ lifecycle

    fun onResume() {
        val app = getApplication<Application>()
        permissions.value = BtPermissions.allGranted(app)
        capabilities.value = DeviceCapabilities.read(app)
        bluetooth.start()
        maxConnected.value = bluetooth.maxConnectedAudioDevices()
    }

    fun onPermissionsResult() = onResume()

    // ------------------------------------------------------------------ actions

    fun scan() = bluetooth.startScan()

    fun stopScan() = bluetooth.stopScan()

    fun pair(address: String) = bluetooth.pair(address)

    fun connect(address: String) {
        bluetooth.connect(address)
    }

    fun disconnect(address: String) = bluetooth.disconnect(address)

    /** Sends the phone's audio to this (connected) speaker. */
    fun playOn(address: String) {
        bluetooth.setActive(address)
    }

    /** Connects every speaker in the party selection; the preferred host ends up playing. */
    fun connectAll() {
        val current = state.value
        val targets = current.partySpeakers.filter { !it.connected }.map { it.address }
        val playOn = current.preferredHost?.takeIf { host -> current.partySpeakers.any { it.address == host } }
            ?: current.plan.best?.host?.key
        bluetooth.connectAll(targets, playOn)
    }

    /** Connects the phone to a group's host and makes it the playing speaker. */
    fun connectHost(address: String) {
        prefs.setPreferredHost(address)
        bluetooth.connectAll(listOf(address), playOn = address)
    }

    fun setInParty(address: String, included: Boolean) = prefs.setInParty(address, included)

    fun setPreferredHost(address: String?) = prefs.setPreferredHost(address)

    fun setModel(address: String, modelId: String?) = prefs.setModelOverride(address, modelId)

    fun setShowAllAudioDevices(show: Boolean) = prefs.setShowAllAudioDevices(show)

    fun playTestSound() = container.tonePlayer.play()

    fun readStatus(address: String) = withGattTargets(address) { targets -> statusReader.read(address, targets) }

    fun playLocateSound(address: String) = withGattTargets(address) { targets -> statusReader.playLocateSound(address, targets) }

    fun readAllStatuses() {
        state.value.speakers.filter { it.canReadStatus }.forEach { readStatus(it.address) }
    }

    /**
     * The control service is reached through the speaker's current LE address, which often rotates.
     * If none was seen recently, listen to advertisements for a few seconds first.
     */
    private fun withGattTargets(address: String, action: (List<GattTarget>) -> Unit) {
        viewModelScope.launch {
            var record = bluetooth.devices.value[address] ?: return@launch
            if (!record.hasFreshLeAddress()) {
                bluetooth.scanAdvertisements(LE_SCAN_MILLIS)
                record = withTimeoutOrNull(LE_SCAN_MILLIS) {
                    bluetooth.devices.first { it[address]?.hasFreshLeAddress() == true }[address]
                } ?: bluetooth.devices.value[address] ?: record
                bluetooth.stopScan()
            }
            action(gattTargets(record))
        }
    }

    private fun gattTargets(record: DeviceRecord): List<GattTarget> {
        val model = identify(record, prefs.state.value).model
        val le = GattTarget(record.address, BluetoothDevice.TRANSPORT_LE)
        val classic = GattTarget(record.address, BluetoothDevice.TRANSPORT_BREDR)
        val fresh = record.bleAddress?.takeIf { record.hasFreshLeAddress() }
            ?.let { GattTarget(it, BluetoothDevice.TRANSPORT_LE) }
        return listOfNotNull(fresh) + if (model?.control == ControlProtocol.AA_BR_EDR) listOf(classic, le) else listOf(le, classic)
    }

    private fun DeviceRecord.hasFreshLeAddress(): Boolean =
        bleAddress != null && bleSeenAt != null && SystemClock.elapsedRealtime() - bleSeenAt < LE_ADDRESS_MAX_AGE_MILLIS

    // ------------------------------------------------------------------ state

    private fun identify(record: DeviceRecord, prefs: Preferences) =
        SpeakerIdentifier.identify(record.name, record.advertisement?.pid, prefs.modelOverrides[record.address])

    private fun build(bt: BluetoothSnapshot, env: Environment, caps: DeviceCapabilities, max: Int?): UiState {
        val outputs = env.outputs
        val outputAddresses = outputs.mapNotNull { it.address }.toSet()
        fun isPlaying(record: DeviceRecord): Boolean {
            if (record.link != LinkState.CONNECTED) return false
            if (outputAddresses.isNotEmpty()) return record.address in outputAddresses
            // Android 8 does not expose output addresses: fall back to names and the A2DP state.
            return outputs.any { it.name == record.name } || bt.activeAddress == record.address
        }

        fun toUi(record: DeviceRecord): SpeakerUi {
            val identification = identify(record, env.prefs)
            val model = identification.model
            return SpeakerUi(
                address = record.address,
                name = record.name ?: record.address,
                model = model,
                modelSource = identification.source,
                isJbl = identification.isJbl && !record.isHeadphones,
                bonded = record.bonded,
                bonding = record.bonding,
                link = record.link,
                playing = isPlaying(record),
                nearby = record.link == LinkState.CONNECTED || record.seenWithin(env.now, NEARBY_WINDOW_MILLIS),
                inParty = record.address !in env.prefs.excludedFromParty,
                status = env.statuses[record.address],
                canReadStatus = model?.control?.let { it in READABLE } ?: identification.isJbl,
            )
        }

        val showAll = env.prefs.showAllAudioDevices
        val all = bt.devices.values.map { it to toUi(it) }
        val speakers = all
            .filter { (record, ui) -> record.bonded && (ui.isJbl || (showAll && record.isAudioDevice)) }
            .map { it.second }
            .sortedWith(
                compareByDescending<SpeakerUi> { it.playing }
                    .thenByDescending { it.connected }
                    .thenByDescending { it.link == LinkState.CONNECTING }
                    .thenByDescending { it.nearby }
                    .thenBy { it.name.lowercase() },
            )
        val discovered = all
            .filter { (record, ui) -> !record.bonded && record.discovered && (ui.isJbl || (showAll && record.isAudioDevice)) }
            .sortedByDescending { it.first.rssi ?: Int.MIN_VALUE }
            .map { it.second }
        val pairable = discovered.map { it.name }.toSet()
        val nearbyNotInPairingMode = bt.nearby.values
            .filter { env.now - it.lastSeen <= NEARBY_WINDOW_MILLIS }
            .mapNotNull { advert -> advert.advertisement.model?.let { "JBL ${it.displayName}" } }
            .filter { it !in pairable }
            .distinct()
            .sorted()

        val partySpeakers = speakers.filter { it.isJbl && it.inParty }
        val plan = PartyPlanner.plan(
            partySpeakers.map { PlannerSpeaker(it.address, it.name, it.model, connectedToPhone = it.connected, playing = it.playing) },
            caps.forPlanner(),
            env.prefs.preferredHost,
        )
        return UiState(
            bluetoothSupported = bluetooth.isSupported,
            permissionsGranted = env.permissions,
            adapter = bt.adapter,
            speakers = speakers,
            discovered = discovered,
            nearbyNotInPairingMode = nearbyNotInPairingMode,
            scanning = bt.discovering,
            connectedCount = bt.devices.values.count { it.link == LinkState.CONNECTED },
            maxConnected = max,
            playing = speakers.firstOrNull { it.playing },
            canConnectDirectly = HiddenBluetooth.canConnect,
            partySpeakers = partySpeakers,
            plan = plan,
            preferredHost = env.prefs.preferredHost,
            showAllAudioDevices = showAll,
            capabilities = caps,
        )
    }

    private companion object {
        const val NEARBY_WINDOW_MILLIS = 120_000L
        const val LE_ADDRESS_MAX_AGE_MILLIS = 180_000L
        const val LE_SCAN_MILLIS = 7_000L
        val READABLE = setOf(ControlProtocol.AA_BLE, ControlProtocol.AA_BR_EDR)
    }
}

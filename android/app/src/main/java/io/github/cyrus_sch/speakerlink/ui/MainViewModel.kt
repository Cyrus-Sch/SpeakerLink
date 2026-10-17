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
import io.github.cyrus_sch.speakerlink.core.Identification
import io.github.cyrus_sch.speakerlink.core.IdentificationSource
import io.github.cyrus_sch.speakerlink.core.LinkGroup
import io.github.cyrus_sch.speakerlink.core.PartyPlan
import io.github.cyrus_sch.speakerlink.core.PartyPlanner
import io.github.cyrus_sch.speakerlink.core.PlannerSpeaker
import io.github.cyrus_sch.speakerlink.core.SpeakerIdentifier
import io.github.cyrus_sch.speakerlink.core.SpeakerModel
import io.github.cyrus_sch.speakerlink.data.Preferences
import io.github.cyrus_sch.speakerlink.status.GattTarget
import io.github.cyrus_sch.speakerlink.status.SpeakerStatus
import io.github.cyrus_sch.speakerlink.system.DeviceCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

    /** The last plan, so pairs stay together while speakers connect and switch output. Declared before [state]. */
    @Volatile
    private var lastPlan: PartyPlan? = null

    val state: StateFlow<UiState> = combine(bluetoothSnapshot, environment, capabilities, maxConnected) { bt, env, caps, max ->
        build(bt, env, caps, max)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

    init {
        bluetooth.start()
        // Capabilities and limits can only be read while Bluetooth is on (e.g. turned on from Quick Settings).
        viewModelScope.launch {
            bluetooth.adapterState.collect { adapter ->
                if (adapter == AdapterState.ON) {
                    capabilities.value = DeviceCapabilities.read(getApplication<Application>())
                    maxConnected.value = bluetooth.maxConnectedAudioDevices()
                }
            }
        }
    }

    /** The first frame already shows real data instead of flashing the permission card. */
    private fun initialState(): UiState = build(
        BluetoothSnapshot(
            bluetooth.adapterState.value,
            bluetooth.devices.value,
            bluetooth.discovering.value,
            bluetooth.activeAddress.value,
            bluetooth.nearbyAdverts.value,
        ),
        Environment(
            container.audioOutputs.outputs.value,
            prefs.state.value,
            statusReader.statuses.value,
            permissions.value,
            SystemClock.elapsedRealtime(),
        ),
        capabilities.value,
        maxConnected.value,
    )

    // ------------------------------------------------------------------ lifecycle

    fun onResume() {
        val app = getApplication<Application>()
        permissions.value = BtPermissions.allGranted(app)
        capabilities.value = DeviceCapabilities.read(app)
        bluetooth.start()
        container.audioOutputs.refresh()
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
        // Speakers that are around first, so absent ones do not take the connection slots.
        val targets = current.partySpeakers.filter { !it.connected }.sortedByDescending { it.nearby }.map { it.address }
        // The planner already picked the hosts (the user's choice only if that speaker is around).
        // Play on the host of the user's group, else of the largest group, if that host is around.
        fun reachable(group: LinkGroup) = group.host.present || group.host.connectedToPhone
        val groups = current.plan.groups
        val group = groups.firstOrNull { g -> reachable(g) && g.speakers.any { it.key == current.preferredHost } }
            ?: groups.firstOrNull(::reachable)
            ?: current.plan.best
        bluetooth.connectAll(targets, playOn = group?.host?.key)
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

    fun readStatus(address: String) = readStatuses(listOf(address))

    fun readAllStatuses() = readStatuses(state.value.speakers.filter { it.canReadStatus }.map { it.address })

    fun playLocateSound(address: String) {
        viewModelScope.launch {
            val record = withFreshLeAddresses(listOf(address)).firstOrNull() ?: return@launch
            statusReader.playLocateSound(address, gattTargets(record), expectedNames(record))
        }
    }

    private fun readStatuses(addresses: List<String>) {
        val wanted = addresses.filter { statusReader.markReading(it) }
        if (wanted.isEmpty()) return
        // The app's scope, and every mark undone if no read starts, so no speaker stays "Reading".
        container.scope.launch {
            val pending = wanted.toMutableSet()
            try {
                for (record in withFreshLeAddresses(wanted)) {
                    statusReader.read(record.address, gattTargets(record), expectedNames(record))
                    pending -= record.address
                }
            } finally {
                pending.forEach(statusReader::cancelReading)
            }
        }
    }

    /** The speaker's own name and the name the user gave it on the phone. */
    private fun expectedNames(record: DeviceRecord): List<String> = listOfNotNull(record.remoteName, record.name).distinct()

    /**
     * The control service of most models is reached through the speaker's current LE address,
     * which rotates. Listens to advertisements (one shared scan) until every speaker that needs
     * one has a fresh LE address, or a few seconds have passed.
     */
    private suspend fun withFreshLeAddresses(addresses: List<String>): List<DeviceRecord> {
        fun needsLe(record: DeviceRecord?) =
            record != null && !record.hasFreshLeAddress() && identify(record, prefs.state.value).model?.control != ControlProtocol.AA_BR_EDR
        if (addresses.any { needsLe(bluetooth.devices.value[it]) } && bluetooth.beginAdvertLookup()) {
            try {
                withTimeoutOrNull(LE_SCAN_MILLIS) {
                    bluetooth.devices.first { devices -> addresses.none { needsLe(devices[it]) } }
                }
            } finally {
                bluetooth.endAdvertLookup()
            }
        }
        return addresses.mapNotNull { bluetooth.devices.value[it] }
    }

    /**
     * Ways to reach the speaker's control service, best first. Models that run it over the classic
     * link start there; the others start with the LE address they advertise from. That address is
     * checked against the speaker's name unless the advertisement named the speaker's address.
     */
    private fun gattTargets(record: DeviceRecord): List<GattTarget> {
        val model = identify(record, prefs.state.value).model
        val le = GattTarget(record.address, BluetoothDevice.TRANSPORT_LE)
        val classic = GattTarget(record.address, BluetoothDevice.TRANSPORT_BREDR)
        val fresh = record.bleAddress?.takeIf { record.hasFreshLeAddress() && it != record.address }
            ?.let { GattTarget(it, BluetoothDevice.TRANSPORT_LE, randomAddress = true, verifyName = !record.bleMatchExact) }
        return if (model?.control == ControlProtocol.AA_BR_EDR) {
            listOfNotNull(classic, fresh, le)
        } else {
            listOfNotNull(fresh, le, classic)
        }
    }

    private fun DeviceRecord.hasFreshLeAddress(): Boolean =
        bleAddress != null && bleSeenAt != null && SystemClock.elapsedRealtime() - bleSeenAt < LE_ADDRESS_MAX_AGE_MILLIS

    // ------------------------------------------------------------------ state

    /** Tries the device's own name first (it survives a local rename), then the display name. */
    private fun identify(record: DeviceRecord, prefs: Preferences): Identification {
        val pid = record.advertisement?.pid
        val override = prefs.modelOverrides[record.address]
        val byRemote = SpeakerIdentifier.identify(record.remoteName, pid, override)
        if (byRemote.model != null) return byRemote
        val byName = SpeakerIdentifier.identify(record.name, pid, override)
        if (byName.model != null) return byName
        return byRemote.copy(isJbl = byRemote.isJbl || byName.isJbl)
    }

    private fun build(bt: BluetoothSnapshot, env: Environment, caps: DeviceCapabilities, max: Int?): UiState {
        val outputs = env.outputs
        val outputAddresses = outputs.mapNotNull { it.address }.toSet()
        val connectedNames = bt.devices.values.filter { it.link == LinkState.CONNECTED }.groupingBy { it.name }.eachCount()
        fun isPlaying(record: DeviceRecord): Boolean {
            if (record.link != LinkState.CONNECTED) return false
            if (outputAddresses.isNotEmpty()) return record.address in outputAddresses
            // No output addresses (Android 8, or not readable yet): use the active A2DP device,
            // then a name match that is unique among connected devices.
            if (bt.activeAddress != null) return bt.activeAddress == record.address
            return connectedNames[record.name] == 1 && outputs.any { it.name == record.name }
        }

        fun toUi(record: DeviceRecord): SpeakerUi {
            val identification = identify(record, env.prefs)
            val model = identification.model
            return SpeakerUi(
                address = record.address,
                name = record.name ?: record.address,
                model = model,
                modelSource = identification.source,
                // Some speakers with a speakerphone report a headset device class; a known model wins.
                isJbl = identification.isJbl && (model != null || !record.isHeadphones),
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
            .filter { (record, ui) ->
                !record.bonded && record.discovered && record.seenWithin(env.now, DISCOVERY_WINDOW_MILLIS) &&
                    (ui.isJbl || (showAll && record.isAudioDevice))
            }
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
        // Speakers keep their default names ("JBL Flip 6" twice), so add the end of the address.
        val duplicateNames = partySpeakers.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        val plan = PartyPlanner.plan(
            partySpeakers.map {
                PlannerSpeaker(
                    key = it.address,
                    name = if (it.name in duplicateNames) "${it.name} (${it.address.takeLast(5)})" else it.name,
                    model = it.model,
                    connectedToPhone = it.connected,
                    playing = it.playing,
                    present = it.nearby,
                )
            },
            caps.forPlanner(),
            env.prefs.preferredHost,
            previous = lastPlan,
        ).also { lastPlan = it }
        return UiState(
            bluetoothSupported = bluetooth.isSupported,
            permissionsGranted = env.permissions,
            adapter = bt.adapter,
            speakers = speakers,
            discovered = discovered,
            nearbyNotInPairingMode = nearbyNotInPairingMode,
            scanning = bt.discovering,
            connectedCount = speakers.count { it.connected },
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
        const val DISCOVERY_WINDOW_MILLIS = 300_000L
        const val LE_ADDRESS_MAX_AGE_MILLIS = 180_000L
        const val LE_SCAN_MILLIS = 7_000L
        val READABLE = setOf(ControlProtocol.AA_BLE, ControlProtocol.AA_BR_EDR)
    }
}

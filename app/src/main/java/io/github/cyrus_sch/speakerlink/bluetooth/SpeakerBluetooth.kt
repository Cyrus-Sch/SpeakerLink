package io.github.cyrus_sch.speakerlink.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.cyrus_sch.speakerlink.core.HarmanAdvertisement
import io.github.cyrus_sch.speakerlink.core.SpeakerIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns all classic Bluetooth work: adapter state, bonded and discovered speakers, A2DP
 * connections (several at once), choosing the playing speaker, and Harman LE advertisements.
 *
 * All callbacks arrive on the main thread; state is exposed as [StateFlow]s.
 * Every method checks its permission first and degrades to a no-op without it, hence the
 * class-wide MissingPermission suppression.
 */
@SuppressLint("MissingPermission")
class SpeakerBluetooth(private val context: Context, private val scope: CoroutineScope) {

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val bleScanner = HarmanBleScanner(context, adapter, ::onAdvertisement)

    val isSupported: Boolean get() = adapter != null

    private val _adapterState = MutableStateFlow(readAdapterState())
    val adapterState: StateFlow<AdapterState> = _adapterState.asStateFlow()

    private val _devices = MutableStateFlow<Map<String, DeviceRecord>>(emptyMap())
    val devices: StateFlow<Map<String, DeviceRecord>> = _devices.asStateFlow()

    private val _nearbyAdverts = MutableStateFlow<Map<String, NearbyAdvert>>(emptyMap())
    val nearbyAdverts: StateFlow<Map<String, NearbyAdvert>> = _nearbyAdverts.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    /** Address of the A2DP device the phone currently sends media to, if known. */
    private val _activeAddress = MutableStateFlow<String?>(null)
    val activeAddress: StateFlow<String?> = _activeAddress.asStateFlow()

    private val _a2dpReady = MutableStateFlow(false)
    val a2dpReady: StateFlow<Boolean> = _a2dpReady.asStateFlow()

    private val _events = MutableSharedFlow<BluetoothEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<BluetoothEvent> = _events.asSharedFlow()

    private var a2dp: BluetoothA2dp? = null
    private var started = false
    private var scanJob: Job? = null
    private var groupJob: Job? = null

    /** Registers receivers and binds the A2DP proxy. Safe to call repeatedly. */
    fun start() {
        val adapter = adapter ?: return
        if (!started) {
            started = true
            ContextCompat.registerReceiver(context, receiver, receiverFilter(), ContextCompat.RECEIVER_EXPORTED)
            adapter.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
        }
        refresh()
    }

    /** Re-reads bonded devices and connection states. */
    fun refresh() {
        val adapter = adapter ?: return
        _adapterState.value = readAdapterState()
        if (!BtPermissions.canConnect(context) || !adapter.isEnabled) return
        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        val proxy = a2dp
        val now = SystemClock.elapsedRealtime()
        _devices.update { current ->
            val next = current.toMutableMap()
            val bondedAddresses = bonded.mapTo(HashSet()) { it.address }
            for ((address, record) in current) {
                if (record.bonded && address !in bondedAddresses) {
                    next[address] = record.copy(bondState = BluetoothDevice.BOND_NONE, link = LinkState.DISCONNECTED)
                }
            }
            for (device in bonded) {
                val old = next[device.address]
                val link = proxy?.let { p -> runCatching { LinkState.fromProfileState(p.getConnectionState(device)) }.getOrNull() }
                    ?: old?.link ?: LinkState.DISCONNECTED
                val playing = proxy?.let { p -> runCatching { p.isA2dpPlaying(device) }.getOrNull() } ?: false
                next[device.address] = describe(device, old).copy(
                    bondState = BluetoothDevice.BOND_BONDED,
                    link = link,
                    a2dpPlaying = playing,
                    lastSeen = if (link == LinkState.CONNECTED) now else old?.lastSeen,
                )
            }
            next
        }
        refreshActive()
    }

    // ---------------------------------------------------------------- discovery

    /** Classic discovery (speakers in pairing mode) plus an LE scan for Harman advertisements. */
    fun startScan(durationMillis: Long = 15_000) {
        val adapter = adapter ?: return
        if (!adapter.isEnabled) {
            _events.tryEmit(BluetoothEvent.BluetoothOff)
            return
        }
        if (!BtPermissions.canScan(context)) return
        if (BtPermissions.scanNeedsLocationServices(context)) {
            _events.tryEmit(BluetoothEvent.ScanFailed(locationOff = true))
            return
        }
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        if (!started) {
            _events.tryEmit(BluetoothEvent.ScanFailed(locationOff = false))
        }
        bleScanner.start()
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(durationMillis)
            stopScan()
        }
    }

    /** Listens for LE advertisements only (no classic discovery), e.g. to find a speaker's LE address. */
    fun scanAdvertisements(durationMillis: Long = 8_000) {
        if (adapter?.isEnabled != true || !BtPermissions.canScan(context)) return
        bleScanner.start()
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(durationMillis)
            stopScan()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        bleScanner.stop()
        val adapter = adapter ?: return
        if (BtPermissions.canScan(context) && adapter.isDiscovering) runCatching { adapter.cancelDiscovery() }
    }

    // ---------------------------------------------------------------- pairing and connections

    fun pair(address: String) {
        val device = device(address) ?: return
        stopScan()
        val record = _devices.value[address]
        if (record?.bonded == true) {
            connect(address)
            return
        }
        upsert(address) { it.copy(pairRequested = true) }
        val accepted = runCatching { device.createBond() }.getOrDefault(false)
        if (!accepted) {
            upsert(address) { it.copy(pairRequested = false) }
            _events.tryEmit(BluetoothEvent.PairingFailed(address, displayName(address)))
        }
    }

    /** Connects A2DP to one speaker without disconnecting the others. */
    fun connect(address: String): Boolean {
        val device = device(address) ?: return false
        val proxy = a2dp ?: return false
        // Discovery slows connections down considerably.
        adapter?.takeIf { BtPermissions.canScan(context) && it.isDiscovering }?.let { runCatching { it.cancelDiscovery() } }
        return when (val result = HiddenBluetooth.connect(proxy, device)) {
            is HiddenCall.Done -> {
                if (result.value) {
                    val requestedAt = SystemClock.elapsedRealtime()
                    upsert(address) { it.copy(connectRequestedAt = requestedAt, link = LinkState.CONNECTING) }
                    watchConnect(address, requestedAt)
                } else {
                    val limit = connectedCount() >= (maxConnectedAudioDevices() ?: Int.MAX_VALUE)
                    _events.tryEmit(BluetoothEvent.ConnectFailed(address, displayName(address), limitReached = limit))
                }
                result.value
            }
            HiddenCall.Unavailable, is HiddenCall.Failed -> {
                _events.tryEmit(BluetoothEvent.NeedsSystemSettings(address, displayName(address)))
                false
            }
        }
    }

    fun disconnect(address: String) {
        val device = device(address) ?: return
        val proxy = a2dp ?: return
        when (HiddenBluetooth.disconnect(proxy, device)) {
            is HiddenCall.Done -> upsert(address) { it.copy(connectRequestedAt = null) }
            else -> _events.tryEmit(BluetoothEvent.NeedsSystemSettings(address, displayName(address)))
        }
    }

    /**
     * Connects several speakers one after another. The phone plays on the most recently connected
     * speaker, so [playOn] is connected last and then made the active output explicitly.
     */
    fun connectAll(addresses: List<String>, playOn: String?) {
        val ordered = addresses.distinct().sortedBy { it == playOn }
        groupJob?.cancel()
        groupJob = scope.launch {
            for (address in ordered) {
                val record = _devices.value[address] ?: continue
                if (record.link == LinkState.CONNECTED) continue
                if (!connect(address)) continue
                // Give the stack time; connections are serialized internally anyway.
                val deadline = SystemClock.elapsedRealtime() + 8_000
                while (SystemClock.elapsedRealtime() < deadline &&
                    _devices.value[address]?.link == LinkState.CONNECTING
                ) {
                    delay(250)
                }
                delay(500)
            }
            if (playOn != null && _devices.value[playOn]?.link == LinkState.CONNECTED) setActive(playOn)
        }
    }

    /** Makes a connected speaker the one the phone plays on. */
    fun setActive(address: String): Boolean {
        val device = device(address) ?: return false
        val proxy = a2dp ?: return false
        val result = HiddenBluetooth.setActiveDevice(proxy, device)
        if (result is HiddenCall.Done && result.value) {
            scope.launch {
                delay(600)
                refreshActive()
            }
            return true
        }
        _events.tryEmit(BluetoothEvent.NeedsOutputSwitcher(address, displayName(address)))
        return false
    }

    fun maxConnectedAudioDevices(): Int? {
        val adapter = adapter ?: return null
        if (!BtPermissions.canConnect(context)) return null
        return HiddenBluetooth.maxConnectedAudioDevices(adapter)
    }

    fun connectedCount(): Int = _devices.value.values.count { it.link == LinkState.CONNECTED }

    // ---------------------------------------------------------------- internals

    private fun watchConnect(address: String, requestedAt: Long) {
        scope.launch {
            delay(CONNECT_TIMEOUT_MILLIS)
            val record = _devices.value[address] ?: return@launch
            if (record.connectRequestedAt == requestedAt && record.link != LinkState.CONNECTED) {
                upsert(address) { it.copy(connectRequestedAt = null, link = refreshedLink(address)) }
                _events.tryEmit(BluetoothEvent.ConnectFailed(address, displayName(address), limitReached = false))
            }
        }
    }

    private fun refreshedLink(address: String): LinkState {
        val proxy = a2dp ?: return LinkState.DISCONNECTED
        val device = device(address) ?: return LinkState.DISCONNECTED
        return runCatching { LinkState.fromProfileState(proxy.getConnectionState(device)) }.getOrDefault(LinkState.DISCONNECTED)
    }

    private fun refreshActive() {
        val proxy = a2dp ?: return
        if (!BtPermissions.canConnect(context)) return
        _activeAddress.value = HiddenBluetooth.activeDevice(proxy)?.address
            ?: _devices.value.values.firstOrNull { it.a2dpPlaying }?.address
    }

    private fun device(address: String): BluetoothDevice? {
        val adapter = adapter ?: return null
        if (!BtPermissions.canConnect(context) || !BluetoothAdapter.checkBluetoothAddress(address)) return null
        return runCatching { adapter.getRemoteDevice(address) }.getOrNull()
    }

    private fun displayName(address: String): String = _devices.value[address]?.name ?: address

    private fun upsert(address: String, change: (DeviceRecord) -> DeviceRecord) {
        _devices.update { current ->
            val old = current[address] ?: DeviceRecord(address = address, name = null)
            current + (address to change(old))
        }
    }

    /** Copies name, class and sink capability from the framework object into a record. */
    private fun describe(device: BluetoothDevice, old: DeviceRecord?, nameHint: String? = null, classHint: BluetoothClass? = null): DeviceRecord {
        val name = runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null }.getOrNull()
            ?: runCatching { device.name }.getOrNull()
            ?: nameHint
            ?: old?.name
        val btClass = classHint ?: runCatching { device.bluetoothClass }.getOrNull()
        val uuids = runCatching { device.uuids }.getOrNull()
        val rendersAudio = btClass != null &&
            btClass.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO &&
            btClass.hasService(BluetoothClass.Service.RENDER)
        val sink = uuids?.any { it == A2DP_SINK_UUID } == true || rendersAudio
        val base = old ?: DeviceRecord(address = device.address, name = name)
        return base.copy(
            name = name,
            majorClass = btClass?.majorDeviceClass ?: base.majorClass,
            deviceClass = btClass?.deviceClass ?: base.deviceClass,
            audioSink = sink || base.audioSink,
        )
    }

    private fun readAdapterState(): AdapterState {
        val adapter = adapter ?: return AdapterState.UNAVAILABLE
        return when (runCatching { adapter.state }.getOrDefault(BluetoothAdapter.STATE_OFF)) {
            BluetoothAdapter.STATE_ON -> AdapterState.ON
            BluetoothAdapter.STATE_TURNING_ON -> AdapterState.TURNING_ON
            BluetoothAdapter.STATE_TURNING_OFF -> AdapterState.TURNING_OFF
            else -> AdapterState.OFF
        }
    }

    private fun onAdvertisement(advert: HarmanAdvertisement, bleAddress: String, bleName: String?, rssi: Int) {
        val now = SystemClock.elapsedRealtime()
        val devices = _devices.value
        val target = when {
            devices.containsKey(bleAddress) -> bleAddress
            else -> advert.classicAddressCandidates.firstOrNull { devices.containsKey(it) }
                ?: matchByName(devices.values, bleName)
                ?: matchByModel(devices.values, advert)
        }
        if (target != null) {
            upsert(target) {
                it.copy(advertisement = advert, lastSeen = now, rssi = rssi, bleAddress = bleAddress, bleSeenAt = now)
            }
            _nearbyAdverts.update { it - bleAddress }
        } else {
            _nearbyAdverts.update { it + (bleAddress to NearbyAdvert(bleAddress, bleName, advert, now)) }
        }
    }

    /** LE names are often truncated ("JBL Fli"), so a prefix of the classic name counts. */
    private fun matchByName(devices: Collection<DeviceRecord>, bleName: String?): String? {
        if (bleName == null || bleName.length < 6) return null
        return devices.singleOrNull { record ->
            val name = record.name ?: return@singleOrNull false
            name.equals(bleName, ignoreCase = true) || name.startsWith(bleName, ignoreCase = true)
        }?.address
    }

    /** Last resort: exactly one bonded speaker of the advertised model. */
    private fun matchByModel(devices: Collection<DeviceRecord>, advert: HarmanAdvertisement): String? {
        val model = advert.model ?: return null
        return devices.singleOrNull { record ->
            record.bonded && SpeakerIdentifier.modelFromName(record.name) == model
        }?.address
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.A2DP) return
            a2dp = proxy as BluetoothA2dp
            _a2dpReady.value = true
            refresh()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.A2DP) return
            a2dp = null
            _a2dpReady.value = false
        }
    }

    private fun receiverFilter() = IntentFilter().apply {
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) addAction(BluetoothDevice.ACTION_ALIAS_CHANGED)
        addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
        addAction(ACTION_A2DP_ACTIVE_DEVICE_CHANGED)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                handle(intent)
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission revoked while handling ${intent.action}", e)
            }
        }
    }

    private fun handle(intent: Intent) {
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        val now = SystemClock.elapsedRealtime()
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                _adapterState.value = readAdapterState()
                if (_adapterState.value == AdapterState.ON) {
                    refresh()
                } else {
                    _discovering.value = false
                    _activeAddress.value = null
                    _devices.update { map -> map.mapValues { (_, r) -> r.copy(link = LinkState.DISCONNECTED, a2dpPlaying = false) } }
                }
            }
            BluetoothAdapter.ACTION_DISCOVERY_STARTED -> _discovering.value = true
            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _discovering.value = false
            BluetoothDevice.ACTION_FOUND -> {
                device ?: return
                val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                val btClass = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
                val old = _devices.value[device.address]
                val record = describe(device, old, nameHint = name, classHint = btClass).copy(
                    discovered = true,
                    lastSeen = now,
                    rssi = rssi.takeIf { it != Short.MIN_VALUE.toInt() } ?: old?.rssi,
                    bondState = runCatching { device.bondState }.getOrDefault(old?.bondState ?: BluetoothDevice.BOND_NONE),
                )
                _devices.update { it + (device.address to record) }
                // An advertisement seen earlier may belong to this device.
                _nearbyAdverts.value.values.firstOrNull { advert ->
                    device.address in advert.advertisement.classicAddressCandidates ||
                        (advert.name != null && record.name?.startsWith(advert.name, ignoreCase = true) == true)
                }?.let { advert ->
                    upsert(device.address) {
                        it.copy(advertisement = advert.advertisement, bleAddress = advert.bleAddress, bleSeenAt = advert.lastSeen)
                    }
                    _nearbyAdverts.update { it - advert.bleAddress }
                }
            }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                device ?: return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                val old = _devices.value[device.address]
                upsert(device.address) { describe(device, it).copy(bondState = state) }
                when {
                    state == BluetoothDevice.BOND_BONDED -> {
                        _events.tryEmit(BluetoothEvent.Paired(device.address, displayName(device.address)))
                        if (old?.pairRequested == true) {
                            upsert(device.address) { it.copy(pairRequested = false) }
                            // Most speakers connect on their own after pairing; make sure.
                            scope.launch {
                                delay(1_500)
                                if (_devices.value[device.address]?.link == LinkState.DISCONNECTED) connect(device.address)
                            }
                        }
                    }
                    state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING -> {
                        upsert(device.address) { it.copy(pairRequested = false) }
                        _events.tryEmit(BluetoothEvent.PairingFailed(device.address, displayName(device.address)))
                    }
                }
            }
            BluetoothDevice.ACTION_NAME_CHANGED -> {
                device ?: return
                val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                if (_devices.value.containsKey(device.address)) upsert(device.address) { describe(device, it, nameHint = name) }
            }
            BluetoothDevice.ACTION_ALIAS_CHANGED -> {
                device ?: return
                if (_devices.value.containsKey(device.address)) upsert(device.address) { describe(device, it) }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                device ?: return
                if (_devices.value.containsKey(device.address)) upsert(device.address) { it.copy(lastSeen = now) }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                device ?: return
                if (_devices.value.containsKey(device.address)) upsert(device.address) { it.copy(a2dpPlaying = false) }
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                device ?: return
                val state = LinkState.fromProfileState(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED))
                val old = _devices.value[device.address]
                upsert(device.address) { record ->
                    describe(device, record).copy(
                        link = state,
                        bondState = runCatching { device.bondState }.getOrDefault(record.bondState),
                        connectRequestedAt = if (state == LinkState.CONNECTING) record.connectRequestedAt else null,
                        a2dpPlaying = if (state == LinkState.CONNECTED) record.a2dpPlaying else false,
                        lastSeen = if (state == LinkState.CONNECTED) now else record.lastSeen,
                    )
                }
                if (state == LinkState.CONNECTED && old?.connectRequestedAt != null) {
                    _events.tryEmit(BluetoothEvent.Connected(device.address, displayName(device.address)))
                } else if (state == LinkState.DISCONNECTED && old?.connectRequestedAt != null && old.link == LinkState.CONNECTING) {
                    val limit = connectedCount() >= (maxConnectedAudioDevices() ?: Int.MAX_VALUE)
                    _events.tryEmit(BluetoothEvent.ConnectFailed(device.address, displayName(device.address), limitReached = limit))
                }
                scope.launch {
                    delay(400)
                    refreshActive()
                }
            }
            BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> {
                device ?: return
                val playing = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothA2dp.STATE_NOT_PLAYING) == BluetoothA2dp.STATE_PLAYING
                upsert(device.address) { it.copy(a2dpPlaying = playing) }
                refreshActive()
            }
            ACTION_A2DP_ACTIVE_DEVICE_CHANGED -> {
                _activeAddress.value = device?.address
            }
        }
    }

    companion object {
        private const val TAG = "SpeakerBluetooth"
        private const val CONNECT_TIMEOUT_MILLIS = 20_000L

        /** Hidden but long-standing protected broadcast (BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED). */
        const val ACTION_A2DP_ACTIVE_DEVICE_CHANGED = "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"

        val A2DP_SINK_UUID: ParcelUuid = ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB")
    }
}

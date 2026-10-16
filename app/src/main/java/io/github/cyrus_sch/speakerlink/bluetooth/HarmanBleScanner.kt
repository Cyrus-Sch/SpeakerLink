package io.github.cyrus_sch.speakerlink.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import io.github.cyrus_sch.speakerlink.core.BluetoothUuids
import io.github.cyrus_sch.speakerlink.core.HarmanAdvertisement

/**
 * Listens for JBL/Harman Bluetooth LE advertisements. They reveal the exact model (product ID)
 * of speakers that are switched on, even when they are not in pairing mode.
 */
@SuppressLint("MissingPermission")
class HarmanBleScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val onAdvertisement: (advert: HarmanAdvertisement, bleAddress: String, bleName: String?, rssi: Int) -> Unit,
) {
    private var scanning = false

    fun start() {
        if (scanning || !BtPermissions.canScan(context)) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val filters = listOf(
            ScanFilter.Builder().setManufacturerData(HarmanAdvertisement.HARMAN_COMPANY_ID, ByteArray(0)).build(),
            ScanFilter.Builder().setManufacturerData(HarmanAdvertisement.HARMAN_VENDOR_ID_ALT, ByteArray(0)).build(),
            ScanFilter.Builder().setServiceData(HARMAN_SERVICE_UUID, ByteArray(0)).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure { Log.w(TAG, "LE scan failed to start", it) }
            .isSuccess
    }

    fun stop() {
        if (!scanning) return
        scanning = false
        if (!BtPermissions.canScan(context)) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    /** Forgets the scan without calling the stack, e.g. after Bluetooth was turned off. */
    fun reset() {
        scanning = false
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "LE scan failed: $errorCode")
            scanning = false
        }
    }

    private fun handle(result: ScanResult) {
        val record = result.scanRecord ?: return
        val manufacturer = buildMap {
            record.manufacturerSpecificData?.let { data ->
                for (i in 0 until data.size()) put(data.keyAt(i), data.valueAt(i))
            }
        }
        val service = buildMap {
            for ((uuid, bytes) in record.serviceData.orEmpty()) {
                val short = BluetoothUuids.shortFormOrNull(uuid.uuid) ?: continue
                put(short, bytes)
            }
        }
        val advert = HarmanAdvertisement.parse(manufacturer, service) ?: return
        onAdvertisement(advert, result.device.address, record.deviceName, result.rssi)
    }

    private companion object {
        const val TAG = "HarmanBleScanner"
        val HARMAN_SERVICE_UUID = ParcelUuid(BluetoothUuids.fromShortForm(HarmanAdvertisement.HARMAN_SERVICE_UUID_16))
    }
}

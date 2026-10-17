package io.github.cyrus_sch.speakerlink.core

import java.util.Locale

/**
 * Information decoded from a JBL/Harman Bluetooth LE advertisement.
 *
 * JBL speakers advertise manufacturer-specific data under Harman's Bluetooth SIG company ID
 * (0x0057) and/or service data under Harman's 16-bit service UUID 0xFDDF. Both start with the
 * product ID (PID) in little-endian order.
 */
data class HarmanAdvertisement(
    val pid: Int?,
    val colorCode: Int?,
    /** Classic (BR/EDR) address found in FDDF service data, as "AA:BB:CC:DD:EE:FF", if present. */
    val classicAddressCandidates: List<String>,
) {
    val model: SpeakerModel? get() = ModelCatalog.byPid(pid)

    companion object {
        /** Harman International Industries, Inc. (Bluetooth SIG company identifier). */
        const val HARMAN_COMPANY_ID = 0x0057

        /** Vendor ID used by JBL's PartyBox software. */
        const val HARMAN_VENDOR_ID_ALT = 0x0ECB

        /** Harman's 16-bit member service UUID used for service data. */
        const val HARMAN_SERVICE_UUID_16 = 0xFDDF

        private const val ADDRESS_OFFSET = 11
        private const val ADDRESS_LENGTH = 6

        /**
         * Parses an advertisement.
         *
         * @param manufacturerData company ID -> payload (without the 2 company ID bytes), as
         *   Android's `ScanRecord.getManufacturerSpecificData()` returns it.
         * @param serviceData 16-bit service UUID -> payload.
         * @return null when the advertisement carries no Harman data.
         */
        fun parse(manufacturerData: Map<Int, ByteArray>, serviceData: Map<Int, ByteArray>): HarmanAdvertisement? {
            val harman = manufacturerData[HARMAN_COMPANY_ID]
            val alt = manufacturerData[HARMAN_VENDOR_ID_ALT]
            val fddf = serviceData[HARMAN_SERVICE_UUID_16]
            if (harman == null && alt == null && fddf == null) return null

            val pidCandidates = listOfNotNull(
                harman?.let(::littleEndianPid),
                fddf?.let(::littleEndianPid),
                alt?.let(::littleEndianPid),
            )
            // Prefer a product ID we know; otherwise keep the first one so callers can log it.
            val pid = pidCandidates.firstOrNull { ModelCatalog.byPid(it) != null } ?: pidCandidates.firstOrNull()
            val color = harman?.takeIf { it.size >= 3 }?.let { it[2].toInt() and 0xFF }

            val addresses = fddf?.takeIf { it.size >= ADDRESS_OFFSET + ADDRESS_LENGTH }?.let { data ->
                val bytes = data.copyOfRange(ADDRESS_OFFSET, ADDRESS_OFFSET + ADDRESS_LENGTH)
                // The byte order is not documented for every model, so offer both and let the
                // caller accept whichever matches a device it already knows.
                listOf(BluetoothAddress.format(bytes), BluetoothAddress.format(bytes.reversedArray())).distinct()
            }.orEmpty()

            return HarmanAdvertisement(pid = pid, colorCode = color, classicAddressCandidates = addresses)
        }

        private fun littleEndianPid(data: ByteArray): Int? =
            if (data.size >= 2) (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8) else null
    }
}

object BluetoothAddress {
    private val pattern = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

    fun format(bytes: ByteArray): String {
        require(bytes.size == 6) { "A Bluetooth address has 6 bytes" }
        return bytes.joinToString(":") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }
    }

    fun normalize(address: String): String = address.trim().uppercase(Locale.ROOT).replace('-', ':')

    fun isValid(address: String): Boolean = pattern.matches(normalize(address))
}

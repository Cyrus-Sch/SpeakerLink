package io.github.cyrus_sch.speakerlink.core

import java.util.UUID

/** Conversions between 16-bit Bluetooth SIG UUIDs and full 128-bit UUIDs. */
object BluetoothUuids {
    /** The Bluetooth base UUID, 00000000-0000-1000-8000-00805F9B34FB. */
    val BASE: UUID = UUID.fromString("00000000-0000-1000-8000-00805F9B34FB")

    private const val SHORT_MASK = 0xFFFF_0000_0000_0000uL

    fun fromShortForm(short: Int): UUID {
        require(short in 0..0xFFFF) { "Not a 16-bit UUID: $short" }
        return UUID(BASE.mostSignificantBits or (short.toLong() shl 32), BASE.leastSignificantBits)
    }

    /** The 16-bit form of [uuid], or null when it is not derived from the base UUID. */
    fun shortFormOrNull(uuid: UUID): Int? {
        if (uuid.leastSignificantBits != BASE.leastSignificantBits) return null
        val msb = uuid.mostSignificantBits
        if ((msb.toULong() and SHORT_MASK) != 0uL) return null
        if ((msb and 0xFFFF_FFFFL) != (BASE.mostSignificantBits and 0xFFFF_FFFFL)) return null
        return ((msb ushr 32) and 0xFFFF).toInt()
    }
}

package io.github.cyrus_sch.speakerlink.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BluetoothUuidsTest {

    @Test
    fun roundTrip() {
        val fddf = UUID.fromString("0000FDDF-0000-1000-8000-00805F9B34FB")
        assertEquals(fddf, BluetoothUuids.fromShortForm(0xFDDF))
        assertEquals(0xFDDF, BluetoothUuids.shortFormOrNull(fddf))
        assertEquals(0x110B, BluetoothUuids.shortFormOrNull(UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")))
    }

    @Test
    fun customUuidsHaveNoShortForm() {
        assertNull(BluetoothUuids.shortFormOrNull(UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0000")))
        assertNull(BluetoothUuids.shortFormOrNull(UUID.fromString("1234FDDF-0000-1000-8000-00805F9B34FB")))
        assertNull(BluetoothUuids.shortFormOrNull(UUID.fromString("0000FDDF-0000-1001-8000-00805F9B34FB")))
    }
}

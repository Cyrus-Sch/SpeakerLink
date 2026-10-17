package io.github.cyrus_sch.speakerlink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarmanAdvertisementTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun manufacturerDataGivesPidAndColor() {
        // Flip 7: "57 00 07 21 ..." -> company 0x0057, PID 0x2107.
        val ad = HarmanAdvertisement.parse(mapOf(0x0057 to bytes(0x07, 0x21, 0x05, 0x00)), emptyMap())!!
        assertEquals(0x2107, ad.pid)
        assertEquals(5, ad.colorCode)
        assertEquals("flip7", ad.model?.id)
        assertTrue(ad.classicAddressCandidates.isEmpty())
    }

    @Test
    fun serviceDataGivesPidAndClassicAddress() {
        val data = bytes(0x40, 0x20, 0, 0, 0x55, 0, 0, 0, 0, 0, 0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0)
        val ad = HarmanAdvertisement.parse(emptyMap(), mapOf(0xFDDF to data))!!
        assertEquals("charge5", ad.model?.id)
        assertEquals(listOf("11:22:33:44:55:66", "66:55:44:33:22:11"), ad.classicAddressCandidates)
    }

    @Test
    fun knownPidWinsOverUnknownOne() {
        val ad = HarmanAdvertisement.parse(
            mapOf(0x0057 to bytes(0x34, 0x12)),
            mapOf(0xFDDF to bytes(0x4F, 0x20)),
        )!!
        assertEquals(0x204F, ad.pid)
    }

    @Test
    fun otherVendorsAreIgnored() {
        assertNull(HarmanAdvertisement.parse(mapOf(0x004C to bytes(1, 2, 3)), emptyMap()))
    }

    @Test
    fun shortPayloadHasNoPid() {
        val ad = HarmanAdvertisement.parse(mapOf(0x0057 to bytes(0x07)), emptyMap())!!
        assertNull(ad.pid)
        assertNull(ad.colorCode)
    }

    @Test
    fun addressFormatting() {
        assertEquals("0A:0B:0C:0D:0E:FF", BluetoothAddress.format(bytes(10, 11, 12, 13, 14, 255)))
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddress.normalize(" aa-bb-cc-dd-ee-ff "))
        assertTrue(BluetoothAddress.isValid("aa:bb:cc:dd:ee:ff"))
    }
}

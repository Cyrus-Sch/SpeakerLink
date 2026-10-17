package io.github.cyrus_sch.speakerlink.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JblProtocolTest {

    private fun hex(text: String): ByteArray =
        text.split(' ', ':').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun parseAll(vararg notifications: String): SpeakerInfo {
        val decoder = FrameDecoder()
        return notifications.flatMap { decoder.feed(hex(it)) }
            .mapNotNull { SpeakerInfoParser.parse(it) }
            .fold(SpeakerInfo()) { acc, info -> acc.merge(info) }
    }

    @Test
    fun commandsHaveTheDocumentedBytes() {
        assertContentEquals(hex("AA 11 00"), JblProtocol.requestSpeakerInfo())
        assertContentEquals(hex("AA 41 00"), JblProtocol.requestFirmware())
        assertContentEquals(hex("AA 31 00"), JblProtocol.playSound())
        assertContentEquals(hex("AA 15 03 00 46 01"), JblProtocol.frame(0x15, hex("00 46 01")))
        assertContentEquals(hex("AA 98 00 00"), JblProtocol.frame(0x98))
    }

    @Test
    fun charge5InfoBurst() {
        // Captured from a Charge 5 (jbl-charge5 docs), one token per frame.
        val info = parseAll(
            "aa 12 0f 00 c1 0c 4a 42 4c 20 43 68 61 72 67 65 20 35",
            "aa 12 04 00 42 20 40",
            "aa 12 03 00 43 01",
            "aa 12 03 00 44 64",
            "aa 12 03 00 46 00",
            "aa 12 03 00 47 01",
            "aa 12 08 00 48 aa bb cc dd ee ff",
        )
        assertEquals("JBL Charge 5", info.name)
        assertEquals(0x2040, info.pid)
        assertEquals("charge5", info.model?.id)
        assertEquals(1, info.colorCode)
        assertEquals(100, info.batteryPercent)
        assertEquals(false, info.charging)
        assertEquals(AudioChannel.STEREO, info.channel)
        assertEquals(true, info.sourceConnected)
    }

    @Test
    fun flip7InfoAndFirmware() {
        val info = parseAll(
            "AA 12 11 00 C1 0E 46 46 52 20 4A 42 4C 20 46 6C 69 70 20 37",
            "AA 12 04 00 42 21 07",
            "AA 12 03 00 44 1E",
            "AA 12 06 00 4D 00 07 02 00",
        )
        assertEquals("FFR JBL Flip 7", info.name)
        assertEquals("flip7", info.model?.id)
        assertEquals(30, info.batteryPercent)
        assertEquals("0.7.2.0", info.firmware)
    }

    @Test
    fun chargingFlagIsBit7() {
        val info = parseAll("AA 12 03 00 44 8A")
        assertEquals(10, info.batteryPercent)
        assertEquals(true, info.charging)
        assertEquals(20, parseAll("AA 12 03 00 44 94").batteryPercent)
    }

    @Test
    fun firmwareResponse() {
        assertEquals("3.0.7.1", parseAll("AA 42 04 03 00 07 01").firmware)
    }

    @Test
    fun severalTokensInOneFrame() {
        // Older firmware packs the whole burst into one frame.
        val info = parseAll("AA 12 0C 00 42 1E D1 43 02 44 55 46 01 47 00")
        assertEquals("flip4", info.model?.id)
        assertEquals(2, info.colorCode)
        assertEquals(85, info.batteryPercent)
        assertEquals(AudioChannel.LEFT, info.channel)
        assertEquals(false, info.sourceConnected)
    }

    @Test
    fun framesSplitAndJoinedAcrossNotifications() {
        val decoder = FrameDecoder()
        // One EQ frame split in two notifications (captured from a Charge 5)...
        assertTrue(decoder.feed(hex("AA 6D 12 02 01 01 03 01 00 02 00")).isEmpty())
        val split = decoder.feed(hex("03 00 C1 03 01 00 02 00 03 00"))
        assertEquals(1, split.size)
        assertEquals(0x6D, split[0].command)
        assertEquals(0x12, split[0].payload.size)
        // ...and two frames in one notification, with garbage in front.
        val joined = decoder.feed(hex("FF 00 AA 12 03 00 44 50 AA EE 01 61"))
        assertEquals(listOf(0x12, 0xEE), joined.map { it.command })
    }

    @Test
    fun incompleteFrameWaitsForMoreBytes() {
        val decoder = FrameDecoder()
        assertTrue(decoder.feed(hex("AA 12")).isEmpty())
        assertTrue(decoder.feed(hex("03 00 44")).isEmpty())
        assertEquals(1, decoder.feed(hex("64")).size)
    }

    @Test
    fun implausibleLengthIsTreatedAsNoise() {
        val decoder = FrameDecoder()
        val frames = decoder.feed(hex("AA 86 FF FF AA 12 03 00 44 50"))
        assertEquals(listOf(0x12), frames.map { it.command })
        assertTrue(!decoder.hasPartialFrame)
    }

    @Test
    fun protocol4NotificationsAreIgnored() {
        val decoder = FrameDecoder()
        assertTrue(decoder.feed(hex("00 DD 01 00 01 00 04 00 AA 12 03 00")).isEmpty())
    }

    @Test
    fun unknownTokenAloneIsSkippedAndBrokenFramesDoNotCrash() {
        assertTrue(parseAll("AA 12 04 00 4A 20 31").isEmpty)
        assertTrue(parseAll("AA 12 01 00").isEmpty)
        assertNull(SpeakerInfoParser.parse(Frame(0x12, hex("00"))))
        // Truncated name length is clamped.
        assertEquals("JB", parseAll("AA 12 05 00 C1 09 4A 42").name)
        // Unknown token after known ones stops parsing but keeps what was read.
        assertEquals(40, parseAll("AA 12 06 00 44 28 7E 01 02").batteryPercent)
    }
}

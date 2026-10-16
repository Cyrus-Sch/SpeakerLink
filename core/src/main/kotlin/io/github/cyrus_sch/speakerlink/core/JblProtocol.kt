package io.github.cyrus_sch.speakerlink.core

import java.util.UUID

/**
 * The vendor control protocol of JBL portable speakers (Connect+, PartyBoost and first-generation
 * Auracast models), spoken over a GATT service named after its chip vendor ("excelpoint.com").
 *
 * Frames are `AA | command | length | payload` with no checksum; a few commands use a 2-byte
 * big-endian length. Re-implemented from the protocol notes of the open-source projects
 * connect-plus, JBLControl, openjbl and jbl-charge5 (see README, "Sources"). Not affiliated with
 * or endorsed by JBL/Harman; it may not work on every model or firmware.
 */
object JblProtocol {
    val SERVICE: UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0000")

    /** Speaker to phone (notifications). */
    val NOTIFY_CHARACTERISTIC: UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0001")

    /** Phone to speaker. */
    val WRITE_CHARACTERISTIC: UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0002")

    /** Client Characteristic Configuration descriptor. */
    val CCCD: UUID = BluetoothUuids.fromShortForm(0x2902)

    const val START: Int = 0xAA

    const val CMD_ACK = 0x00
    const val CMD_REQUEST_INFO = 0x11
    const val CMD_INFO = 0x12
    const val CMD_PLAY_SOUND = 0x31
    const val CMD_REQUEST_FIRMWARE = 0x41
    const val CMD_FIRMWARE = 0x42
    const val CMD_UNSUPPORTED = 0xEE

    /** Commands whose length field is two bytes (advanced EQ and LED canvas frames). */
    val LONG_LENGTH_COMMANDS: Set<Int> = setOf(0x86, 0x92, 0x96, 0x97, 0x98, 0x99)

    fun frame(command: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(command in 0..0xFF)
        return if (command in LONG_LENGTH_COMMANDS) {
            byteArrayOf(START.toByte(), command.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload
        } else {
            require(payload.size <= 0xFF) { "Payload too long for a short frame" }
            byteArrayOf(START.toByte(), command.toByte(), payload.size.toByte()) + payload
        }
    }

    /** Asks for name, model, color, battery, channel... answered by a burst of [CMD_INFO] frames. */
    fun requestSpeakerInfo(): ByteArray = frame(CMD_REQUEST_INFO)

    fun requestFirmware(): ByteArray = frame(CMD_REQUEST_FIRMWARE)

    /** Plays the speaker's "find me" sound. */
    fun playSound(): ByteArray = frame(CMD_PLAY_SOUND)
}

/** One decoded protocol frame. */
class Frame(val command: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Frame && other.command == command && other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * command + payload.contentHashCode()

    override fun toString(): String =
        "Frame(%02X: %s)".format(command, payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
}

/**
 * Reassembles frames from notifications: one notification can hold part of a frame or several
 * frames. Bytes before a start marker are dropped, as are notifications in the newer
 * "protocol 4" format (`00 DD ...` / `01 DD ...`), which this app does not speak.
 */
class FrameDecoder {
    private var buffer = ByteArray(0)

    fun feed(chunk: ByteArray): List<Frame> {
        if (buffer.isEmpty() && isProtocol4(chunk)) return emptyList()
        buffer += chunk
        val frames = mutableListOf<Frame>()
        while (true) {
            val start = buffer.indexOfFirst { (it.toInt() and 0xFF) == JblProtocol.START }
            if (start < 0) {
                buffer = ByteArray(0)
                break
            }
            if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
            if (buffer.size < 3) break
            val command = buffer[1].toInt() and 0xFF
            val long = command in JblProtocol.LONG_LENGTH_COMMANDS
            val header = if (long) 4 else 3
            if (buffer.size < header) break
            val length = if (long) {
                ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
            } else {
                buffer[2].toInt() and 0xFF
            }
            if (buffer.size < header + length) break
            frames += Frame(command, buffer.copyOfRange(header, header + length))
            buffer = buffer.copyOfRange(header + length, buffer.size)
        }
        return frames
    }

    fun reset() {
        buffer = ByteArray(0)
    }

    private fun isProtocol4(chunk: ByteArray): Boolean =
        chunk.size >= 8 && (chunk[1].toInt() and 0xFF) == 0xDD && (chunk[0].toInt() and 0xFF) <= 0x01
}

enum class AudioChannel { STEREO, LEFT, RIGHT }

/** Status reported by a speaker. Every field is optional: models report different subsets. */
data class SpeakerInfo(
    val name: String? = null,
    /** Product ID (from token 0x42, big-endian on the wire). */
    val pid: Int? = null,
    val colorCode: Int? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val channel: AudioChannel? = null,
    /** A Bluetooth audio source (a phone) is connected to the speaker. */
    val sourceConnected: Boolean? = null,
    val linkedDevices: Int? = null,
    val firmware: String? = null,
    val serial: String? = null,
) {
    val model: SpeakerModel? get() = ModelCatalog.byPid(pid)

    /** Values from [newer] win when present. */
    fun merge(newer: SpeakerInfo) = SpeakerInfo(
        name = newer.name ?: name,
        pid = newer.pid ?: pid,
        colorCode = newer.colorCode ?: colorCode,
        batteryPercent = newer.batteryPercent ?: batteryPercent,
        charging = newer.charging ?: charging,
        channel = newer.channel ?: channel,
        sourceConnected = newer.sourceConnected ?: sourceConnected,
        linkedDevices = newer.linkedDevices ?: linkedDevices,
        firmware = newer.firmware ?: firmware,
        serial = newer.serial ?: serial,
    )

    val isEmpty: Boolean get() = this == SpeakerInfo()
}

object SpeakerInfoParser {

    /** Value lengths of the fixed-size tokens. Tokens with bit 7 set are length-prefixed. */
    private val fixedLengths = mapOf(
        0x42 to 2, 0x43 to 1, 0x44 to 1, 0x45 to 1, 0x46 to 1, 0x47 to 1, 0x48 to 6,
        0x49 to 1, 0x4A to 2, 0x4B to 1, 0x4C to 1, 0x4D to 4, 0x4F to 1, 0x53 to 1,
    )

    /**
     * Parses a [JblProtocol.CMD_INFO] or [JblProtocol.CMD_FIRMWARE] frame; returns null for other
     * frames. The info payload is `index | token value [token value ...]`: newer firmware sends
     * one token per frame, older firmware packs several.
     */
    fun parse(frame: Frame): SpeakerInfo? = when (frame.command) {
        JblProtocol.CMD_INFO -> parseInfo(frame.payload)
        JblProtocol.CMD_FIRMWARE -> firmware(frame.payload)?.let { SpeakerInfo(firmware = it) }
        else -> null
    }

    private fun parseInfo(payload: ByteArray): SpeakerInfo? {
        if (payload.size < 2) return null
        var info = SpeakerInfo()
        var pos = 1 // skip the speaker index byte
        while (pos < payload.size) {
            val token = payload[pos].toInt() and 0xFF
            val valueStart = pos + 1
            val value: ByteArray
            if ((token and 0x80) != 0) {
                if (valueStart >= payload.size) break
                val length = payload[valueStart].toInt() and 0xFF
                val end = minOf(payload.size, valueStart + 1 + length)
                value = payload.copyOfRange(valueStart + 1, end)
                pos = end
            } else {
                val length = fixedLengths[token]
                if (length == null) {
                    // Unknown fixed-size token: only safe to read when it is the frame's only token.
                    if (pos == 1) apply(info, token, payload.copyOfRange(valueStart, payload.size))?.let { info = it }
                    break
                }
                if (valueStart + length > payload.size) break
                value = payload.copyOfRange(valueStart, valueStart + length)
                pos = valueStart + length
            }
            apply(info, token, value)?.let { info = it }
        }
        return info
    }

    private fun apply(info: SpeakerInfo, token: Int, value: ByteArray): SpeakerInfo? {
        fun u8(i: Int = 0) = value[i].toInt() and 0xFF
        if (value.isEmpty()) return null
        return when (token) {
            0x42 -> if (value.size >= 2) info.copy(pid = (u8(0) shl 8) or u8(1)) else null
            0x43 -> info.copy(colorCode = u8())
            0x44 -> info.copy(batteryPercent = (u8() and 0x7F).coerceAtMost(100), charging = (u8() and 0x80) != 0)
            0x45 -> info.copy(linkedDevices = u8())
            0x46 -> when (u8()) {
                0 -> info.copy(channel = AudioChannel.STEREO)
                1 -> info.copy(channel = AudioChannel.LEFT)
                2 -> info.copy(channel = AudioChannel.RIGHT)
                else -> null
            }
            0x47 -> info.copy(sourceConnected = u8() == 1)
            0x4D -> firmware(value)?.let { info.copy(firmware = it) }
            0xC1 -> text(value)?.let { info.copy(name = it) }
            0xC2 -> text(value)?.let { info.copy(serial = it) }
            else -> null
        }
    }

    private fun firmware(value: ByteArray): String? =
        if (value.size == 4) value.joinToString(".") { (it.toInt() and 0xFF).toString() } else null

    private fun text(value: ByteArray): String? =
        value.toString(Charsets.UTF_8).trim { it <= ' ' }.takeIf { it.isNotEmpty() }
}

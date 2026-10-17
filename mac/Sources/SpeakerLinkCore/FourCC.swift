// SPDX-License-Identifier: GPL-3.0-or-later

/// Helpers for the four-character codes Core Audio uses for selectors, transport types and
/// error codes, for example `'blue'` for Bluetooth or `'who?'` for "unknown property".
///
/// SpeakerLinkCore does not import CoreAudio, so it spells these codes out itself. The
/// SpeakerLinkAudio tests check every code used here against the real SDK constants.
public enum FourCC {
    /// The 32-bit code for a four-character ASCII string, most significant byte first.
    ///
    /// `FourCC.code("blue")` is `0x626C_7565`, the value of `kAudioDeviceTransportTypeBluetooth`.
    /// Strings shorter than four bytes are padded with spaces, longer ones are cut off.
    public static func code(_ string: String) -> UInt32 {
        var bytes = Array(string.utf8.prefix(4))
        while bytes.count < 4 { bytes.append(0x20) }
        return bytes.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }

    /// The same code as a signed `OSStatus`-style value.
    public static func status(_ string: String) -> Int32 {
        Int32(bitPattern: code(string))
    }

    /// The four printable characters of a code, or `nil` when any byte is not printable ASCII
    /// (ordinary negative error numbers such as -50 are not four-character codes).
    public static func string(from code: UInt32) -> String? {
        let bytes: [UInt8] = [
            UInt8(truncatingIfNeeded: code >> 24),
            UInt8(truncatingIfNeeded: code >> 16),
            UInt8(truncatingIfNeeded: code >> 8),
            UInt8(truncatingIfNeeded: code),
        ]
        guard bytes.allSatisfy({ $0 >= 0x20 && $0 < 0x7F }) else { return nil }
        return String(decoding: bytes, as: UTF8.self)
    }

    /// The four printable characters of a status code, if it is one.
    public static func string(fromStatus status: Int32) -> String? {
        string(from: UInt32(bitPattern: status))
    }
}

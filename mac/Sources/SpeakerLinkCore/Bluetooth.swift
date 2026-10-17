// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// A Bluetooth device address in one canonical form: six uppercase hex pairs separated by
/// dashes, for example `"F3-A2-14-A9-1D-F8"`.
///
/// Core Audio names a Bluetooth output `"F3-A2-14-A9-1D-F8:output"` (and its hands-free
/// microphone `"…:input"`), while IOBluetooth's `addressString` is usually lowercase. Both
/// normalise to the same value, so the two worlds can be matched.
public struct BluetoothAddress: Sendable, Hashable, Comparable, CustomStringConvertible {
    /// Canonical form, for example `"F3-A2-14-A9-1D-F8"`.
    public let rawValue: String

    /// Parses `"f3-a2-14-a9-1d-f8"`, `"F3:A2:14:A9:1D:F8"` or `"F3A214A91DF8"`, ignoring case and
    /// surrounding white space. Returns `nil` for anything else.
    public init?(_ string: String) {
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        var pairs: [String]
        if trimmed.contains("-") || trimmed.contains(":") {
            pairs = trimmed
                .split(omittingEmptySubsequences: false) { $0 == "-" || $0 == ":" }
                .map(String.init)
        } else {
            guard trimmed.unicodeScalars.count == 12 else { return nil }
            let scalars = Array(trimmed.unicodeScalars)
            pairs = stride(from: 0, to: 12, by: 2).map { index in
                var pair = String.UnicodeScalarView()
                pair.append(scalars[index])
                pair.append(scalars[index + 1])
                return String(pair)
            }
        }
        guard pairs.count == 6 else { return nil }
        for (index, pair) in pairs.enumerated() {
            guard pair.unicodeScalars.count == 2,
                  pair.unicodeScalars.allSatisfy(Self.isASCIIHexDigit) else { return nil }
            pairs[index] = pair.uppercased()
        }
        rawValue = pairs.joined(separator: "-")
    }

    /// Extracts the address from a Core Audio device UID such as `"F3-A2-14-A9-1D-F8:output"`.
    /// Returns `nil` when the UID does not start with a Bluetooth address.
    public init?(audioDeviceUID uid: String) {
        let base: Substring
        if let colon = uid.lastIndex(of: ":"), Self.uidSuffixes.contains(uid[uid.index(after: colon)...].lowercased()) {
            base = uid[..<colon]
        } else {
            base = Substring(uid)
        }
        self.init(String(base))
    }

    /// The UID Core Audio gives this device's audio output.
    public var outputUID: String { rawValue + ":output" }

    /// The UID Core Audio gives this device's hands-free microphone.
    public var inputUID: String { rawValue + ":input" }

    public var description: String { rawValue }

    public static func < (lhs: BluetoothAddress, rhs: BluetoothAddress) -> Bool {
        lhs.rawValue < rhs.rawValue
    }

    private static let uidSuffixes: Set<String> = ["output", "input"]

    private static func isASCIIHexDigit(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x30...0x39, 0x41...0x46, 0x61...0x66:
            return true
        default:
            return false
        }
    }
}

/// The Bluetooth "class of device" fields that say what kind of audio device something is.
public struct BluetoothDeviceClass: Sendable, Hashable {
    /// Major device class, for example ``audioMajor`` (0x04) for audio and video devices.
    public let major: Int
    /// Minor device class; its meaning depends on ``major``.
    public let minor: Int

    public init(major: Int, minor: Int) {
        self.major = major
        self.minor = minor
    }

    /// Decodes the 24-bit class-of-device value, as the `BluetoothGetDeviceClassMajor` and
    /// `BluetoothGetDeviceClassMinor` macros do (Swift cannot import those macros).
    public init(rawValue: UInt32) {
        major = Int((rawValue & 0x1F00) >> 8)
        minor = Int((rawValue & 0xFC) >> 2)
    }

    /// `kBluetoothDeviceClassMajorAudio`.
    public static let audioMajor = 0x04

    /// Audio minor classes from BluetoothAssignedNumbers.h.
    public enum AudioMinor {
        public static let headset = 0x01
        public static let handsFree = 0x02
        public static let microphone = 0x04
        public static let loudspeaker = 0x05
        public static let headphones = 0x06
        public static let portable = 0x07
        public static let car = 0x08
        public static let setTopBox = 0x09
        public static let hiFi = 0x0A
        public static let videoDisplayAndLoudspeaker = 0x0F
    }

    /// Whether the major class is audio/video.
    public var isAudio: Bool { major == Self.audioMajor }

    /// The kind of output this class describes, or `nil` when the class does not say
    /// (not an audio device, or an audio minor class without a clear meaning).
    public var outputKind: DeviceKind? {
        guard isAudio else { return nil }
        switch minor {
        case AudioMinor.headset, AudioMinor.handsFree, AudioMinor.headphones:
            return .headphones
        case AudioMinor.loudspeaker, AudioMinor.portable, AudioMinor.hiFi:
            return .speaker
        case AudioMinor.car:
            return .car
        case AudioMinor.videoDisplayAndLoudspeaker:
            return .display
        default:
            return nil
        }
    }
}

/// A paired Bluetooth audio device as IOBluetooth reports it.
public struct PairedAudioDevice: Sendable, Hashable, Identifiable {
    public var id: BluetoothAddress { address }
    public let address: BluetoothAddress
    public let name: String
    public let bluetoothClass: BluetoothDeviceClass
    /// Whether IOBluetooth reports a baseband link. Some devices report this wrongly, so
    /// SpeakerLink treats the presence of the Core Audio output as the real "connected" state.
    public let isLinkUp: Bool

    public init(address: BluetoothAddress, name: String, bluetoothClass: BluetoothDeviceClass, isLinkUp: Bool) {
        self.address = address
        self.name = name
        self.bluetoothClass = bluetoothClass
        self.isLinkUp = isLinkUp
    }

    /// Kind for the icon; Bluetooth audio devices without a clear class get a speaker icon.
    public var kind: DeviceKind {
        bluetoothClass.outputKind ?? DeviceKind.guess(fromName: name) ?? .speaker
    }
}

/// Whether this app may use Bluetooth (IOBluetooth), as macOS's privacy settings say.
public enum BluetoothAuthorization: Sendable, Hashable {
    case allowed
    case notDetermined
    case denied
    case restricted
    /// The running binary has no `NSBluetoothAlwaysUsageDescription` (for example a bare
    /// `swift run` build outside the app bundle). Using IOBluetooth would make macOS kill it.
    case unavailable

    /// Whether IOBluetooth calls will return real data.
    public var isAllowed: Bool { self == .allowed }

    /// Whether the person turned access off (or a profile did) and must change it in
    /// System Settings > Privacy & Security > Bluetooth.
    public var isBlocked: Bool { self == .denied || self == .restricted }
}

/// Rules for the "Paired, not connected" list.
public enum PairedDeviceRules {
    /// Paired audio devices whose Core Audio output is not present, sorted by name.
    public static func notConnected(_ paired: [PairedAudioDevice], presentOutputUIDs: Set<String>) -> [PairedAudioDevice] {
        let presentAddresses = Set(presentOutputUIDs.compactMap { BluetoothAddress(audioDeviceUID: $0) })
        return paired
            .filter { !presentAddresses.contains($0.address) }
            .sorted { lhs, rhs in
                let order = lhs.name.localizedStandardCompare(rhs.name)
                return order == .orderedSame ? lhs.address < rhs.address : order == .orderedAscending
            }
    }

    /// Class-of-device by address, for picking row icons.
    public static func classesByAddress(_ paired: [PairedAudioDevice]) -> [BluetoothAddress: BluetoothDeviceClass] {
        var result: [BluetoothAddress: BluetoothDeviceClass] = [:]
        for device in paired { result[device.address] = device.bluetoothClass }
        return result
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// What an output device is, for its icon.
public enum DeviceKind: String, Sendable, Hashable, CaseIterable, Codable {
    case headphones
    case speaker
    /// The Mac's own speakers or headphone jack.
    case builtIn
    /// A display or TV (HDMI, DisplayPort).
    case display
    case car
    case airPlay
    case other

    /// Guesses headphones from a device name, for Bluetooth devices whose class is unknown
    /// (for example when Bluetooth access was denied). Returns `nil` when the name says nothing.
    public static func guess(fromName name: String) -> DeviceKind? {
        let lowered = name.lowercased()
        let headphoneWords = ["airpods", "headphone", "headset", "earbud", "buds", "earphone", "beats"]
        if headphoneWords.contains(where: { lowered.contains($0) }) { return .headphones }
        let speakerWords = ["speaker", "soundbar", "homepod", "boombox", "soundlink"]
        if speakerWords.contains(where: { lowered.contains($0) }) { return .speaker }
        return nil
    }

    /// Picks the kind for an output: the Bluetooth class of a matching paired device when there
    /// is one, otherwise the transport (and, for Bluetooth, the name).
    public static func resolve(for device: OutputDevice, bluetoothClass: BluetoothDeviceClass?) -> DeviceKind {
        if device.transport.isBluetooth, let kind = bluetoothClass?.outputKind {
            return kind
        }
        switch device.transport {
        case .bluetooth, .bluetoothLE:
            return guess(fromName: device.name) ?? .speaker
        case .builtIn:
            return device.name.lowercased().contains("headphone") ? .headphones : .builtIn
        case .hdmi, .displayPort:
            return .display
        case .airPlay:
            return .airPlay
        case .usb, .pci, .fireWire, .thunderbolt, .avb, .virtual, .aggregate, .continuityCapture, .unknown:
            return guess(fromName: device.name) ?? .other
        }
    }
}

/// An audio output device as Core Audio describes it, captured at one moment.
///
/// SpeakerLinkAudio reads these from the HAL; everything that decides what to show or how to
/// build a group works on this plain value type, so it can be unit-tested without hardware.
public struct OutputDevice: Sendable, Hashable, Identifiable {
    public var id: String { uid }

    /// The `AudioObjectID`. It is only valid while the device exists and may be reused.
    public let objectID: UInt32
    /// `kAudioDevicePropertyDeviceUID`, stable across reconnects and restarts.
    public let uid: String
    public let name: String
    public let transport: AudioTransport
    /// Nominal sample rate in Hz (0 when unknown).
    public let sampleRate: Double
    /// Number of output channels across all output streams.
    public let outputChannels: Int
    public let isHidden: Bool
    /// `kAudioDevicePropertyDeviceCanBeDefaultDevice` in output scope.
    public let canBeDefault: Bool
    /// Current output volume 0...1, or `nil` when the device has no settable volume.
    public let volume: Double?

    public init(
        objectID: UInt32,
        uid: String,
        name: String,
        transport: AudioTransport,
        sampleRate: Double,
        outputChannels: Int = 2,
        isHidden: Bool = false,
        canBeDefault: Bool = true,
        volume: Double? = nil
    ) {
        self.objectID = objectID
        self.uid = uid
        self.name = name
        self.transport = transport
        self.sampleRate = sampleRate
        self.outputChannels = outputChannels
        self.isHidden = isHidden
        self.canBeDefault = canBeDefault
        self.volume = volume
    }

    /// The Bluetooth address, for Bluetooth outputs whose UID has the usual
    /// `"AA-BB-CC-DD-EE-FF:output"` form.
    public var bluetoothAddress: BluetoothAddress? {
        transport.isBluetooth ? BluetoothAddress(audioDeviceUID: uid) : nil
    }

    /// Whether the device can be part of a multi-output group. AirPlay outputs cannot.
    public var canJoinGroup: Bool {
        transport != .airPlay && transport != .aggregate
    }

    /// The same device with a different volume (after SpeakerLink changed it).
    public func withVolume(_ newVolume: Double?) -> OutputDevice {
        OutputDevice(
            objectID: objectID, uid: uid, name: name, transport: transport, sampleRate: sampleRate,
            outputChannels: outputChannels, isHidden: isHidden, canBeDefault: canBeDefault, volume: newVolume
        )
    }
}

/// A device with input channels, for the default-input (microphone) advice.
public struct InputDevice: Sendable, Hashable, Identifiable {
    public var id: String { uid }
    public let objectID: UInt32
    public let uid: String
    public let name: String
    public let transport: AudioTransport

    public init(objectID: UInt32, uid: String, name: String, transport: AudioTransport) {
        self.objectID = objectID
        self.uid = uid
        self.name = name
        self.transport = transport
    }
}

/// Which outputs the menu shows and in what order.
public enum DeviceListRules {
    /// Outputs a person can pick: devices with output channels that are not hidden, not
    /// aggregate devices (multi-output devices made in Audio MIDI Setup or by other apps), and
    /// not one of SpeakerLink's own groups.
    public static func visibleOutputs(_ devices: [OutputDevice], groupUIDs: GroupUIDs = .production) -> [OutputDevice] {
        let visible = devices.filter { device in
            device.outputChannels > 0
                && !device.isHidden
                && device.transport != .aggregate
                && !groupUIDs.isOurs(device.uid)
        }
        return sorted(visible)
    }

    /// Bluetooth first, then the Mac's own outputs, other wired outputs, virtual devices and
    /// finally AirPlay (which cannot join). Within a section, by name.
    public static func sorted(_ devices: [OutputDevice]) -> [OutputDevice] {
        devices.sorted { lhs, rhs in
            let left = rank(lhs.transport), right = rank(rhs.transport)
            if left != right { return left < right }
            let order = lhs.name.localizedStandardCompare(rhs.name)
            if order != .orderedSame { return order == .orderedAscending }
            return lhs.uid < rhs.uid
        }
    }

    static func rank(_ transport: AudioTransport) -> Int {
        switch transport {
        case .bluetooth, .bluetoothLE: return 0
        case .builtIn: return 1
        case .usb, .hdmi, .displayPort, .pci, .fireWire, .thunderbolt, .avb: return 2
        case .virtual, .continuityCapture, .unknown, .aggregate: return 3
        case .airPlay: return 4
        }
    }
}

/// Text helpers for device rows.
public enum DeviceFormatting {
    /// "48 kHz", "44.1 kHz", or an empty string for an unknown rate.
    public static func sampleRate(_ hertz: Double) -> String {
        guard hertz.isFinite, hertz > 0 else { return "" }
        let kilohertz = hertz / 1000
        if kilohertz.rounded() == kilohertz {
            return "\(Int(kilohertz)) kHz"
        }
        return String(format: "%.1f kHz", locale: Locale(identifier: "en_US_POSIX"), kilohertz)
    }

    /// "Bluetooth · 48 kHz" style subtitle.
    public static func subtitle(for device: OutputDevice) -> String {
        let rate = sampleRate(device.sampleRate)
        return rate.isEmpty ? device.transport.displayName : "\(device.transport.displayName) · \(rate)"
    }
}

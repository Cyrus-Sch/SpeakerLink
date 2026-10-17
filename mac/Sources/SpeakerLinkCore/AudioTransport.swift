// SPDX-License-Identifier: GPL-3.0-or-later

/// How an output device is attached to the Mac, from `kAudioDevicePropertyTransportType`.
public enum AudioTransport: String, Sendable, Hashable, CaseIterable, Codable {
    case builtIn
    case bluetooth
    case bluetoothLE
    case usb
    case hdmi
    case displayPort
    case airPlay
    case aggregate
    case virtual
    case pci
    case fireWire
    case thunderbolt
    case avb
    case continuityCapture
    case unknown

    /// Maps a Core Audio transport code (for example `kAudioDeviceTransportTypeBluetooth`).
    /// Codes this type does not know, including 0, become ``unknown``.
    public init(code: UInt32) {
        self = Self.byCode[code] ?? .unknown
    }

    /// The current Core Audio code for this transport, or 0 for ``unknown``.
    public var code: UInt32 {
        switch self {
        case .builtIn: return FourCC.code("bltn")
        case .bluetooth: return FourCC.code("blue")
        case .bluetoothLE: return FourCC.code("blea")
        case .usb: return FourCC.code("usb ")
        case .hdmi: return FourCC.code("hdmi")
        case .displayPort: return FourCC.code("dprt")
        case .airPlay: return FourCC.code("airp")
        case .aggregate: return FourCC.code("grup")
        case .virtual: return FourCC.code("virt")
        case .pci: return FourCC.code("pci ")
        case .fireWire: return FourCC.code("1394")
        case .thunderbolt: return FourCC.code("thun")
        case .avb: return FourCC.code("eavb")
        case .continuityCapture: return FourCC.code("ccwd")
        case .unknown: return 0
        }
    }

    /// Classic Bluetooth or Bluetooth LE audio.
    public var isBluetooth: Bool {
        self == .bluetooth || self == .bluetoothLE
    }

    /// A physical, wired connection (built-in, USB, HDMI and so on). These have steady clocks
    /// and make the best main (clock) device for a group.
    public var isWired: Bool {
        switch self {
        case .builtIn, .usb, .hdmi, .displayPort, .pci, .fireWire, .thunderbolt, .avb:
            return true
        case .bluetooth, .bluetoothLE, .airPlay, .aggregate, .virtual, .continuityCapture, .unknown:
            return false
        }
    }

    /// A short, human-readable name for subtitles, for example "Bluetooth" or "USB".
    public var displayName: String {
        switch self {
        case .builtIn: return "Built-in"
        case .bluetooth: return "Bluetooth"
        case .bluetoothLE: return "Bluetooth LE"
        case .usb: return "USB"
        case .hdmi: return "HDMI"
        case .displayPort: return "DisplayPort"
        case .airPlay: return "AirPlay"
        case .aggregate: return "Aggregate"
        case .virtual: return "Virtual"
        case .pci: return "PCI"
        case .fireWire: return "FireWire"
        case .thunderbolt: return "Thunderbolt"
        case .avb: return "AVB"
        case .continuityCapture: return "Continuity"
        case .unknown: return "Other"
        }
    }

    /// Transport codes from AudioHardwareBase.h. `'fgrp'` (the deprecated auto-aggregate) is
    /// treated as an aggregate and `'ccap'` (deprecated) as Continuity.
    static let byCode: [UInt32: AudioTransport] = [
        FourCC.code("bltn"): .builtIn,
        FourCC.code("blue"): .bluetooth,
        FourCC.code("blea"): .bluetoothLE,
        FourCC.code("usb "): .usb,
        FourCC.code("hdmi"): .hdmi,
        FourCC.code("dprt"): .displayPort,
        FourCC.code("airp"): .airPlay,
        FourCC.code("grup"): .aggregate,
        FourCC.code("fgrp"): .aggregate,
        FourCC.code("virt"): .virtual,
        FourCC.code("pci "): .pci,
        FourCC.code("1394"): .fireWire,
        FourCC.code("thun"): .thunderbolt,
        FourCC.code("eavb"): .avb,
        FourCC.code("ccwd"): .continuityCapture,
        FourCC.code("ccwl"): .continuityCapture,
        FourCC.code("ccap"): .continuityCapture,
    ]
}

// SPDX-License-Identifier: GPL-3.0-or-later

/// Volume arithmetic for a group.
///
/// A multi-output device has no volume control of its own, so macOS's volume slider and keys
/// do nothing while it is the output. SpeakerLink therefore keeps a master volume (0...1) and
/// a level per member (0...1), and sets each member's own volume to `master × level`.
public enum VolumeMath {
    /// One press of a volume key, like macOS's own 16 steps.
    public static let keyStep = 1.0 / 16.0

    /// Clamps to 0...1; non-finite values become 0.
    public static func clamp(_ value: Double) -> Double {
        guard value.isFinite else { return 0 }
        return min(max(value, 0), 1)
    }

    /// The volume a member actually gets.
    public static func effective(master: Double, level: Double) -> Double {
        clamp(clamp(master) * clamp(level))
    }

    /// The master volume after one volume-key press, snapped to the 1/16 grid like macOS.
    public static func step(_ master: Double, up: Bool) -> Double {
        let position = clamp(master) * 16
        let tolerance = 1e-9
        let next = up ? (position + tolerance).rounded(.down) + 1 : (position - tolerance).rounded(.up) - 1
        return clamp(next / 16)
    }

    /// The level that keeps a member at its current hardware volume under `master`.
    /// With a master of 0 every level is silent, so the level becomes 1.
    public static func level(forHardwareVolume volume: Double, master: Double) -> Double {
        let master = clamp(master)
        guard master > 0 else { return 1 }
        return clamp(clamp(volume) / master)
    }

    /// A master volume and per-member levels.
    public struct Mix: Sendable, Hashable {
        public let master: Double
        public let levels: [String: Double]

        public init(master: Double, levels: [String: Double]) {
            self.master = master
            self.levels = levels
        }
    }

    /// The mix to start a group with so that starting changes nobody's volume: the master
    /// becomes the loudest member's volume and every level is relative to it. Without any
    /// readable volume the `fallbackMaster` is kept and no levels are set.
    public static func adopt(hardwareVolumes: [String: Double], fallbackMaster: Double) -> Mix {
        guard let loudest = hardwareVolumes.values.map(clamp).max() else {
            return Mix(master: clamp(fallbackMaster), levels: [:])
        }
        guard loudest > 0 else {
            return Mix(master: 0, levels: hardwareVolumes.mapValues { _ in 1 })
        }
        return Mix(master: loudest, levels: hardwareVolumes.mapValues { level(forHardwareVolume: $0, master: loudest) })
    }
}

/// A media key that changes the volume.
public enum VolumeKey: Sendable, Hashable {
    case up
    case down
    case mute
}

/// A volume-key press decoded from an AppKit "system defined" event.
public struct VolumeKeyEvent: Sendable, Hashable {
    public let key: VolumeKey
    /// Whether this is an auto-repeat of a held key.
    public let isRepeat: Bool

    public init(key: VolumeKey, isRepeat: Bool) {
        self.key = key
        self.isRepeat = isRepeat
    }

    /// The `NSEvent` subtype of auxiliary control buttons (media keys).
    public static let auxControlButtonsSubtype = 8

    /// Decodes `NSEvent.subtype` and `NSEvent.data1` of a `.systemDefined` event.
    ///
    /// `data1` carries the NX key type in bits 16-31 (`NX_KEYTYPE_SOUND_UP` 0,
    /// `NX_KEYTYPE_SOUND_DOWN` 1, `NX_KEYTYPE_MUTE` 7), the key state in bits 8-15 (0xA down,
    /// 0xB up) and a repeat flag in bit 0. Only key-down events of those three keys decode.
    public static func decode(subtype: Int, data1: Int) -> VolumeKeyEvent? {
        guard subtype == auxControlButtonsSubtype else { return nil }
        let keyCode = (data1 & 0xFFFF_0000) >> 16
        let keyState = (data1 & 0xFF00) >> 8
        guard keyState == 0xA else { return nil }
        let key: VolumeKey
        switch keyCode {
        case 0: key = .up
        case 1: key = .down
        case 7: key = .mute
        default: return nil
        }
        return VolumeKeyEvent(key: key, isRepeat: data1 & 0x1 != 0)
    }
}

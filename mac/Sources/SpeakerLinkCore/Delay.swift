// SPDX-License-Identifier: GPL-3.0-or-later

/// Per-device delay arithmetic. A delay holds a faster output back so it lines up with a
/// slower one (typically wired or built-in speakers waiting for Bluetooth headphones).
public enum DelayMath {
    /// The delays the menu offers, in milliseconds.
    public static let range: ClosedRange<Int> = 0...500
    /// Slider step in milliseconds.
    public static let step = 10

    /// Clamps to ``range``.
    public static func clamp(_ milliseconds: Int) -> Int {
        min(max(milliseconds, range.lowerBound), range.upperBound)
    }

    /// Rounds to the nearest ``step`` and clamps. Non-finite input becomes 0.
    public static func snap(_ milliseconds: Double) -> Int {
        guard milliseconds.isFinite else { return 0 }
        let steps = (milliseconds / Double(step)).rounded()
        let clamped = min(max(steps * Double(step), Double(range.lowerBound)), Double(range.upperBound))
        return Int(clamped)
    }

    /// Converts a delay to sample frames at `sampleRate` (the unit of "latency-out"), rounding
    /// to the nearest frame: 100 ms at 48 kHz is 4800 frames, at 44.1 kHz 4410.
    public static func frames(milliseconds: Int, sampleRate: Double) -> Int {
        guard sampleRate.isFinite, sampleRate > 0 else { return 0 }
        return Int((Double(clamp(milliseconds)) * sampleRate / 1000).rounded())
    }
}

/// Output latency as Core Audio reports it for one device, in that device's frames.
public struct DeviceLatency: Sendable, Hashable {
    /// `kAudioDevicePropertyLatency` (output scope).
    public let deviceFrames: Int
    /// `kAudioDevicePropertySafetyOffset` (output scope).
    public let safetyOffsetFrames: Int
    /// `kAudioStreamPropertyLatency` of the first output stream.
    public let streamFrames: Int
    /// `kAudioDevicePropertyNominalSampleRate`.
    public let sampleRate: Double

    public init(deviceFrames: Int, safetyOffsetFrames: Int, streamFrames: Int, sampleRate: Double) {
        self.deviceFrames = max(0, deviceFrames)
        self.safetyOffsetFrames = max(0, safetyOffsetFrames)
        self.streamFrames = max(0, streamFrames)
        self.sampleRate = sampleRate
    }

    public var totalFrames: Int { deviceFrames + safetyOffsetFrames + streamFrames }

    /// Total latency in seconds, or `nil` when the sample rate is unknown.
    public var seconds: Double? {
        guard sampleRate.isFinite, sampleRate > 0 else { return nil }
        return Double(totalFrames) / sampleRate
    }
}

/// Suggests delays from reported latencies.
///
/// Bluetooth devices often report less latency than they really have, so this is only a
/// starting point; the menu says so and offers test clicks for tuning by ear.
public enum DelayEstimator {
    /// For each device, the delay (ms, snapped and clamped like the slider) that would make it
    /// line up with the slowest device: `delay_i = max(latency) - latency_i`.
    /// Non-finite or negative latencies are ignored.
    public static func delays(forLatencies latencies: [String: Double]) -> [String: Int] {
        let valid = latencies.filter { $0.value.isFinite && $0.value >= 0 }
        guard let slowest = valid.values.max() else { return [:] }
        return valid.mapValues { DelayMath.snap((slowest - $0) * 1000) }
    }

    /// Same, from ``DeviceLatency`` values; devices with an unknown rate are left out.
    public static func delays(for latencies: [String: DeviceLatency]) -> [String: Int] {
        delays(forLatencies: latencies.compactMapValues(\.seconds))
    }
}

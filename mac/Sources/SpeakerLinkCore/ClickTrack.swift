// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// A short track of clicks for tuning delays by ear: when every output clicks at the same
/// moment, the delays are right.
public struct ClickTrack: Sendable, Hashable {
    public var clickCount: Int
    /// Seconds between clicks.
    public var interval: Double
    /// Silence before the first click, so the outputs have time to start.
    public var leadIn: Double
    /// Silence after the last click.
    public var tail: Double
    /// Length of one click in seconds.
    public var clickLength: Double
    /// Pitch of the click's tone in Hz.
    public var frequency: Double
    /// Peak amplitude 0...1.
    public var amplitude: Float

    public init(
        clickCount: Int = 8,
        interval: Double = 1,
        leadIn: Double = 0.3,
        tail: Double = 0.3,
        clickLength: Double = 0.015,
        frequency: Double = 2_000,
        amplitude: Float = 0.6
    ) {
        self.clickCount = max(1, clickCount)
        self.interval = max(0.05, interval)
        self.leadIn = max(0, leadIn)
        self.tail = max(0, tail)
        self.clickLength = max(0.001, clickLength)
        self.frequency = frequency
        self.amplitude = min(max(amplitude, 0), 1)
    }

    /// Total length in seconds.
    public var duration: Double {
        leadIn + Double(clickCount - 1) * interval + clickLength + tail
    }

    /// The frame index at which each click starts.
    public func clickStartFrames(sampleRate: Double) -> [Int] {
        (0..<clickCount).map { Int(((leadIn + Double($0) * interval) * sampleRate).rounded()) }
    }

    /// Mono samples at `sampleRate`: each click is a short tone burst with a fast decay, and
    /// everything between clicks is silence.
    public func samples(sampleRate: Double) -> [Float] {
        guard sampleRate.isFinite, sampleRate > 0 else { return [] }
        let total = Int((duration * sampleRate).rounded(.up))
        var output = [Float](repeating: 0, count: total)
        let clickFrames = max(1, Int((clickLength * sampleRate).rounded()))
        let decay = Double(clickFrames) / 5
        for start in clickStartFrames(sampleRate: sampleRate) {
            for offset in 0..<clickFrames {
                let index = start + offset
                guard index < total else { break }
                let time = Double(offset) / sampleRate
                let envelope = exp(-Double(offset) / decay)
                output[index] = amplitude * Float(sin(2 * Double.pi * frequency * time) * envelope)
            }
        }
        return output
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

import AVFoundation
import Foundation
import SpeakerLinkCore

/// Plays a ``ClickTrack`` on the current default output (the group, while listening
/// together), so people can hear whether all outputs click at the same moment.
@MainActor
public final class ClickPlayer {
    /// Why the clicks could not play.
    public enum PlaybackError: Error, Sendable, Hashable {
        case formatUnavailable
        case engineFailed(String)

        public var message: String {
            switch self {
            case .formatUnavailable:
                return "The test clicks couldn't be prepared for this output."
            case .engineFailed(let reason):
                return "The test clicks couldn't play: \(reason)"
            }
        }
    }

    public private(set) var isPlaying = false

    /// Called on the main actor when playback ends by itself or is stopped.
    public var onFinish: (@MainActor () -> Void)?

    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var finishTask: Task<Void, Never>?

    public init() {}

    /// Starts the clicks, replacing any that are playing.
    public func play(_ track: ClickTrack = ClickTrack()) throws {
        stop(notify: false)

        // A fresh engine binds to whatever the default output is right now.
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        engine.attach(player)

        let outputRate = engine.outputNode.outputFormat(forBus: 0).sampleRate
        let sampleRate = outputRate > 0 ? outputRate : 48_000
        guard let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 2) else {
            throw PlaybackError.formatUnavailable
        }
        let samples = track.samples(sampleRate: sampleRate)
        guard !samples.isEmpty,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)),
              let channels = buffer.floatChannelData else {
            throw PlaybackError.formatUnavailable
        }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        for channel in 0..<Int(format.channelCount) {
            let destination = channels[channel]
            for index in samples.indices {
                destination[index] = samples[index]
            }
        }

        engine.connect(player, to: engine.mainMixerNode, format: format)
        do {
            try engine.start()
        } catch {
            throw PlaybackError.engineFailed(error.localizedDescription)
        }
        player.scheduleBuffer(buffer, completionHandler: nil)
        player.play()

        self.engine = engine
        self.player = player
        isPlaying = true

        let duration = Double(samples.count) / sampleRate
        finishTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64((duration + 0.2) * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.stop()
        }
    }

    /// Stops the clicks.
    public func stop() {
        stop(notify: true)
    }

    private func stop(notify: Bool) {
        finishTask?.cancel()
        finishTask = nil
        player?.stop()
        engine?.stop()
        player = nil
        engine = nil
        let wasPlaying = isPlaying
        isPlaying = false
        if notify, wasPlaying { onFinish?() }
    }
}

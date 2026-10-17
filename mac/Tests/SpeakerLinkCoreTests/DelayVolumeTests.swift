// SPDX-License-Identifier: GPL-3.0-or-later

import Testing
@testable import SpeakerLinkCore

@Suite("Delays")
struct DelayTests {
    @Test func convertsMillisecondsToFramesAtEachRate() {
        #expect(DelayMath.frames(milliseconds: 100, sampleRate: 48_000) == 4800)
        #expect(DelayMath.frames(milliseconds: 100, sampleRate: 44_100) == 4410)
        #expect(DelayMath.frames(milliseconds: 250, sampleRate: 96_000) == 24_000)
        #expect(DelayMath.frames(milliseconds: 10, sampleRate: 22_050) == 221)  // 220.5 rounds up
        #expect(DelayMath.frames(milliseconds: 0, sampleRate: 48_000) == 0)
        #expect(DelayMath.frames(milliseconds: 100, sampleRate: 0) == 0)
        #expect(DelayMath.frames(milliseconds: 100, sampleRate: .nan) == 0)
        #expect(DelayMath.frames(milliseconds: 900, sampleRate: 48_000) == 24_000)  // clamped to 500 ms
    }

    @Test func snapsAndClampsLikeTheSlider() {
        #expect(DelayMath.snap(0) == 0)
        #expect(DelayMath.snap(14.9) == 10)
        #expect(DelayMath.snap(15) == 20)
        #expect(DelayMath.snap(499) == 500)
        #expect(DelayMath.snap(720) == 500)
        #expect(DelayMath.snap(-30) == 0)
        #expect(DelayMath.snap(.infinity) == 0)
        #expect(DelayMath.clamp(-1) == 0)
        #expect(DelayMath.clamp(501) == 500)
    }

    @Test func totalsReportedLatency() throws {
        let latency = DeviceLatency(deviceFrames: 4000, safetyOffsetFrames: 500, streamFrames: 300, sampleRate: 48_000)
        #expect(latency.totalFrames == 4800)
        let seconds = try #require(latency.seconds)
        #expect(abs(seconds - 0.1) < 1e-12)
        #expect(DeviceLatency(deviceFrames: 10, safetyOffsetFrames: 0, streamFrames: 0, sampleRate: 0).seconds == nil)
        #expect(DeviceLatency(deviceFrames: -10, safetyOffsetFrames: 0, streamFrames: 0, sampleRate: 1).totalFrames == 0)
    }

    @Test func estimatesDelaysAgainstTheSlowestDevice() {
        let delays = DelayEstimator.delays(forLatencies: ["wired": 0.012, "airpods": 0.180, "speaker": 0.250])
        #expect(delays == ["wired": 240, "airpods": 70, "speaker": 0])
    }

    @Test func clampsAndIgnoresInvalidLatencies() {
        #expect(DelayEstimator.delays(forLatencies: ["fast": 0, "slow": 0.9]) == ["fast": 500, "slow": 0])
        #expect(DelayEstimator.delays(forLatencies: ["a": .nan, "b": -1, "c": 0.05]) == ["c": 0])
        #expect(DelayEstimator.delays(forLatencies: [:]).isEmpty)
    }

    @Test func estimatesFromDeviceLatencyInEachRate() {
        let latencies = [
            "mac": DeviceLatency(deviceFrames: 480, safetyOffsetFrames: 0, streamFrames: 0, sampleRate: 48_000),       // 10 ms
            "bt": DeviceLatency(deviceFrames: 6615, safetyOffsetFrames: 0, streamFrames: 0, sampleRate: 44_100),       // 150 ms
            "unknown": DeviceLatency(deviceFrames: 100, safetyOffsetFrames: 0, streamFrames: 0, sampleRate: 0),
        ]
        #expect(DelayEstimator.delays(for: latencies) == ["mac": 140, "bt": 0])
    }
}

@Suite("Volume")
struct VolumeTests {
    @Test func effectiveVolumeIsMasterTimesLevel() {
        #expect(VolumeMath.effective(master: 0.5, level: 0.5) == 0.25)
        #expect(VolumeMath.effective(master: 1, level: 0.3) == 0.3)
        #expect(VolumeMath.effective(master: 2, level: 0.5) == 0.5)
        #expect(VolumeMath.effective(master: 0.8, level: -1) == 0)
        #expect(VolumeMath.clamp(.nan) == 0)
    }

    @Test func stepsOnASixteenthGrid() {
        #expect(VolumeMath.step(0.5, up: true) == 9.0 / 16)
        #expect(VolumeMath.step(0.5, up: false) == 7.0 / 16)
        #expect(VolumeMath.step(0.53, up: true) == 9.0 / 16)
        #expect(VolumeMath.step(0.53, up: false) == 8.0 / 16)
        #expect(VolumeMath.step(1, up: true) == 1)
        #expect(VolumeMath.step(0, up: false) == 0)
        #expect(VolumeMath.step(1.0 / 16, up: false) == 0)
    }

    @Test func levelKeepsHardwareVolume() {
        #expect(VolumeMath.level(forHardwareVolume: 0.3, master: 0.6) == 0.5)
        #expect(VolumeMath.level(forHardwareVolume: 0.9, master: 0.6) == 1)
        #expect(VolumeMath.level(forHardwareVolume: 0.4, master: 0) == 1)
    }

    @Test func adoptingVolumesChangesNothingAudible() {
        let hardware = ["a": 0.8, "b": 0.4, "c": 0.2]
        let mix = VolumeMath.adopt(hardwareVolumes: hardware, fallbackMaster: 0.3)
        #expect(mix.master == 0.8)
        for (uid, volume) in hardware {
            let level = mix.levels[uid] ?? -1
            #expect(abs(VolumeMath.effective(master: mix.master, level: level) - volume) < 1e-9)
        }
        #expect(VolumeMath.adopt(hardwareVolumes: [:], fallbackMaster: 0.3) == VolumeMath.Mix(master: 0.3, levels: [:]))
        #expect(VolumeMath.adopt(hardwareVolumes: ["a": 0, "b": 0], fallbackMaster: 0.3) == VolumeMath.Mix(master: 0, levels: ["a": 1, "b": 1]))
    }
}

@Suite("Volume keys")
struct VolumeKeyTests {
    /// Builds `data1` as AppKit does: key code in bits 16-31, state in 8-15, repeat in bit 0.
    func data1(key: Int, state: Int, isRepeat: Bool = false) -> Int {
        (key << 16) | (state << 8) | (isRepeat ? 1 : 0)
    }

    @Test func decodesKeyDowns() {
        #expect(VolumeKeyEvent.decode(subtype: 8, data1: data1(key: 0, state: 0xA)) == VolumeKeyEvent(key: .up, isRepeat: false))
        #expect(VolumeKeyEvent.decode(subtype: 8, data1: data1(key: 1, state: 0xA)) == VolumeKeyEvent(key: .down, isRepeat: false))
        #expect(VolumeKeyEvent.decode(subtype: 8, data1: data1(key: 7, state: 0xA)) == VolumeKeyEvent(key: .mute, isRepeat: false))
        #expect(VolumeKeyEvent.decode(subtype: 8, data1: data1(key: 0, state: 0xA, isRepeat: true)) == VolumeKeyEvent(key: .up, isRepeat: true))
        // Raw values as AppKit delivers them: key code in the high half, 0x0A00 = key down.
        #expect(VolumeKeyEvent.decode(subtype: 8, data1: 0x0000_0A00)?.key == .up)
        #expect(VolumeKeyEvent.decode(subtype: 8, data1: 0x0001_0A00)?.key == .down)
    }

    @Test func ignoresKeyUpsOtherKeysAndSubtypes() {
        #expect(VolumeKeyEvent.decode(subtype: 8, data1: data1(key: 0, state: 0xB)) == nil)    // key up
        #expect(VolumeKeyEvent.decode(subtype: 8, data1: data1(key: 16, state: 0xA)) == nil)   // play/pause
        #expect(VolumeKeyEvent.decode(subtype: 8, data1: data1(key: 2, state: 0xA)) == nil)    // brightness up
        #expect(VolumeKeyEvent.decode(subtype: 7, data1: data1(key: 0, state: 0xA)) == nil)    // not a media key event
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import SpeakerLinkCore

@Suite("Messages")
struct MessageTests {
    @Test func explainsCoreAudioCodesInPlainLanguage() {
        let message = AudioStatusText.describe(FourCC.status("who?"))
        #expect(message.hasPrefix("The device doesn't support this setting."))
        #expect(message.hasSuffix("(Core Audio code 'who?')"))
        #expect(AudioStatusText.describe(FourCC.status("!obj")).contains("no longer available"))
        #expect(AudioStatusText.describe(-50) == "macOS rejected the request as invalid. (Core Audio error -50)")
        #expect(AudioStatusText.describe(12345).hasPrefix("macOS reported an unexpected audio error."))
    }

    @Test("Messages never consist of a bare code", arguments: ["who?", "!obj", "!dev", "nope", "unop", "!hog", "stop", "!siz", "!str", "what", "nrdy", "!dat", "zzzz"])
    func neverOnlyACode(code: String) {
        let message = AudioStatusText.describe(FourCC.status(code))
        let withoutCode = message.replacingOccurrences(of: AudioStatusText.code(FourCC.status(code)), with: "")
        #expect(withoutCode.count > 20)
    }

    @Test func groupErrorsReadWell() {
        #expect(GroupError.creationFailed(status: FourCC.status("nope")).message.hasPrefix("macOS couldn't combine the outputs."))
        #expect(GroupError.notEnoughDevices(available: 1).message.contains("Only one"))
        #expect(GroupError.notEnoughDevices(available: 0).message.contains("at least two"))
        #expect(GroupError.didNotAppear.message.contains("Try again"))
        #expect(GroupError.notAcceptedAsDefault.message.contains("System Settings"))
    }

    @Test func statusLines() {
        #expect(StatusText.playing(memberCount: 3) == "Playing on 3 devices")
        #expect(StatusText.idle(selectedAvailable: 0) == "Choose two or more outputs below.")
        #expect(StatusText.idle(selectedAvailable: 1) == "Choose one more output.")
        #expect(StatusText.idle(selectedAvailable: 2) == "Ready to play on 2 outputs.")
        #expect(StatusText.stopped(.turnedOff, outputName: nil) == nil)
        #expect(StatusText.stopped(.outputChangedElsewhere(uid: "x"), outputName: "MacBook Pro Speakers")
            == "Stopped because the sound output was changed to MacBook Pro Speakers.")
        #expect(StatusText.stopped(.tooFewDevices(available: 1), outputName: nil)?.contains("fewer than two") == true)
    }

    @Test func warnsAboutAGroupMembersMicrophone() {
        let members = ["F3-A2-14-A9-1D-F8:output", "BuiltInSpeakerDevice"]
        #expect(MicrophoneAdvice.degradesGroup(defaultInputUID: "F3-A2-14-A9-1D-F8:input", memberUIDs: members))
        #expect(!MicrophoneAdvice.degradesGroup(defaultInputUID: "AA-BB-CC-DD-EE-FF:input", memberUIDs: members))
        #expect(!MicrophoneAdvice.degradesGroup(defaultInputUID: "BuiltInMicrophoneDevice", memberUIDs: members))
        #expect(!MicrophoneAdvice.degradesGroup(defaultInputUID: nil, memberUIDs: members))
    }

    @Test func suggestsTheBuiltInMicrophone() {
        let inputs = [
            InputDevice(objectID: 1, uid: "F3-A2-14-A9-1D-F8:input", name: "AirPods", transport: .bluetooth),
            InputDevice(objectID: 2, uid: "BuiltInMicrophoneDevice", name: "MacBook Pro Microphone", transport: .builtIn),
        ]
        #expect(MicrophoneAdvice.suggestedInput(from: inputs)?.objectID == 2)
        #expect(MicrophoneAdvice.suggestedInput(from: [inputs[0]]) == nil)
    }
}

@Suite("Click track")
struct ClickTrackTests {
    @Test func hasEightClicksOneSecondApart() {
        let track = ClickTrack()
        #expect(track.clickCount == 8)
        #expect(track.clickStartFrames(sampleRate: 48_000) == (0..<8).map { 14_400 + $0 * 48_000 })
        let samples = track.samples(sampleRate: 48_000)
        #expect(samples.count == Int((track.duration * 48_000).rounded(.up)))
        #expect(abs(track.duration - 7.615) < 1e-9)
    }

    @Test func clicksAreShortBurstsWithSilenceBetween() {
        let track = ClickTrack()
        let rate = 48_000.0
        let samples = track.samples(sampleRate: rate)
        let clickFrames = Int(track.clickLength * rate)
        for start in track.clickStartFrames(sampleRate: rate) {
            let burst = samples[start..<(start + clickFrames)]
            #expect(burst.map { abs($0) }.max() ?? 0 > 0.1)
            let gap = samples[(start + clickFrames + 10)..<min(samples.count, start + 40_000)]
            #expect(gap.allSatisfy { $0 == 0 })
        }
        #expect(samples.allSatisfy { abs($0) <= track.amplitude })
        #expect(samples[0..<track.clickStartFrames(sampleRate: rate)[0]].allSatisfy { $0 == 0 })
    }

    @Test func invalidRateGivesNoSamples() {
        #expect(ClickTrack().samples(sampleRate: 0).isEmpty)
    }
}

@Suite("Menu rows")
struct DeviceRowTests {
    let airPods = device("F3-A2-14-A9-1D-F8:output", "AirPods Pro", .bluetooth, rate: 48_000, volume: 0.6)
    let speaker = device("11-22-33-44-55-66:output", "Party Speaker", .bluetooth, rate: 44_100, volume: nil)
    let mac = device("BuiltInSpeakerDevice", "MacBook Pro Speakers", .builtIn, volume: 0.3)
    let airPlay = device("airplay-tv", "Living Room TV", .airPlay, volume: 0.5)

    @Test func buildsRowsWhileIdle() throws {
        let settings = AppSettings(selectedUIDs: [airPods.uid, mac.uid], delays: [mac.uid: 200], levels: [airPods.uid: 0.9])
        let rows = DeviceRowBuilder.rows(devices: [mac, airPlay, speaker, airPods], settings: settings, activePlan: nil)
        #expect(rows.map(\.uid) == [airPods.uid, speaker.uid, mac.uid, airPlay.uid])

        let airPodsRow = try #require(rows.first { $0.uid == airPods.uid })
        #expect(airPodsRow.isSelected && !airPodsRow.isInGroup && !airPodsRow.isMain)
        #expect(airPodsRow.volume == 0.6)  // the device's own volume while idle
        #expect(airPodsRow.kind == .headphones)
        #expect(airPodsRow.subtitle == "Bluetooth · 48 kHz")
        #expect(airPodsRow.bluetoothAddress?.rawValue == "F3-A2-14-A9-1D-F8")

        let macRow = try #require(rows.first { $0.uid == mac.uid })
        #expect(macRow.isMain)  // the would-be clock device of the selection
        #expect(macRow.delayMs == 200)
        #expect(macRow.kind == .builtIn)

        let speakerRow = try #require(rows.first { $0.uid == speaker.uid })
        #expect(speakerRow.volume == nil)
        #expect(!speakerRow.isSelected)

        let airPlayRow = try #require(rows.first { $0.uid == airPlay.uid })
        #expect(!airPlayRow.canJoin)
        #expect(airPlayRow.note == DeviceRowBuilder.airPlayNote)
    }

    @Test func showsLevelsAndMainWhileGrouped() throws {
        let settings = AppSettings(selectedUIDs: [airPods.uid, speaker.uid], levels: [airPods.uid: 0.9, speaker.uid: 0.4])
        let plan = GroupPlan(
            members: [
                GroupMember(uid: airPods.uid, name: airPods.name, sampleRate: 48_000, delayMs: 0),
                GroupMember(uid: speaker.uid, name: speaker.name, sampleRate: 44_100, delayMs: 0),
            ],
            mainUID: speaker.uid
        )
        let loudspeaker = BluetoothDeviceClass(major: 0x04, minor: 0x05)
        let speakerAddress = try #require(speaker.bluetoothAddress)
        let rows = DeviceRowBuilder.rows(devices: [airPods, speaker, mac], settings: settings, activePlan: plan, bluetoothClasses: [speakerAddress: loudspeaker])
        let airPodsRow = try #require(rows.first { $0.uid == airPods.uid })
        #expect(airPodsRow.isInGroup)
        #expect(airPodsRow.volume == 0.9)  // the level, not the device volume
        #expect(!airPodsRow.isMain)
        let speakerRow = try #require(rows.first { $0.uid == speaker.uid })
        #expect(speakerRow.isMain)
        #expect(speakerRow.kind == .speaker)
        let macRow = try #require(rows.first { $0.uid == mac.uid })
        #expect(!macRow.isInGroup && !macRow.isMain)
        #expect(macRow.volume == 0.3)
    }

    @Test func countsSelectedAvailableOutputs() {
        let settings = AppSettings(selectedUIDs: [airPods.uid, airPlay.uid, "gone", mac.uid])
        #expect(DeviceRowBuilder.selectedAvailableCount(devices: [airPods, airPlay, mac], settings: settings) == 2)
    }
}

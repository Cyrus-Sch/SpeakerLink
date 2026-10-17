// SPDX-License-Identifier: GPL-3.0-or-later

import CoreAudio
import Foundation
import Testing
import SpeakerLinkAudio
import SpeakerLinkCore

/// Settings and helpers for the Core Audio integration tests.
///
/// They run only with `SPEAKERLINK_AUDIO_TESTS=1` and need the virtual devices of BlackHole
/// (`brew install --cask blackhole-2ch blackhole-16ch`, then `sudo killall -9 coreaudiod`).
/// When the variable is set but a device is missing, the tests fail instead of skipping, so
/// a CI run without working devices cannot pass by accident.
enum IntegrationSupport {
    static let isEnabled = ProcessInfo.processInfo.environment["SPEAKERLINK_AUDIO_TESTS"] == "1"

    static let blackHole2UID = "BlackHole2ch_UID"
    static let blackHole16UID = "BlackHole16ch_UID"

    /// Prefix of every aggregate the tests create, never one of the app's own UIDs.
    static let testUIDPrefix = "io.github.cyrus-sch.speakerlink.test."

    /// A fresh UID for one test aggregate.
    static func makeTestUID(_ label: String) -> String {
        testUIDPrefix + label + "." + UUID().uuidString
    }

    /// The BlackHole output with `uid`, or a failure that says how to install it.
    static func requireOutput(_ uid: String, name: String) throws -> OutputDevice {
        let found = CoreAudioHAL.outputDevices().first { $0.uid == uid }
        let available = CoreAudioHAL.outputDevices().map { "\($0.name) (\($0.uid))" }.joined(separator: ", ")
        return try #require(
            found,
            "\(name) (UID \(uid)) is missing, but SPEAKERLINK_AUDIO_TESTS=1 requires it. Install it with 'brew install --cask blackhole-2ch blackhole-16ch' and restart Core Audio with 'sudo killall -9 coreaudiod'. Outputs present: \(available.isEmpty ? "none" : available)"
        )
    }

    /// Destroys the device with `uid` if present and waits (blocking) until it is gone.
    /// Used from `defer`, which cannot await.
    @MainActor
    @discardableResult
    static func destroyAndWait(uid: String, timeout: Double = 3) -> Bool {
        if let id = CoreAudioHAL.deviceID(forUID: uid) {
            CoreAudioHAL.destroyAggregateDevice(id)
        }
        return Polling.untilBlocking(timeout: timeout) { CoreAudioHAL.deviceID(forUID: uid) == nil }
    }

    /// Restores the default output (blocking), for `defer`.
    @MainActor
    static func restoreDefaultOutput(_ id: AudioObjectID?) {
        guard let id, CoreAudioHAL.deviceIDs().contains(id) else { return }
        _ = DefaultOutput.setBlocking(id)
    }

    /// Creates an aggregate of BlackHole 2ch (main) and 16ch and waits for it to appear.
    @MainActor
    static func makeBlackHoleAggregate(uid: String, stacked: Bool, latencyFramesOn16: Int) async throws -> AudioObjectID {
        let description = AggregateDescription.make(
            uid: uid,
            name: "SpeakerLink Test",
            members: [
                AggregateDescription.Member(uid: blackHole2UID, extraOutputLatencyFrames: 0, driftCompensation: false),
                AggregateDescription.Member(uid: blackHole16UID, extraOutputLatencyFrames: latencyFramesOn16, driftCompensation: true),
            ],
            mainUID: blackHole2UID,
            stacked: stacked,
            isPrivate: false
        )
        let id = try CoreAudioHAL.createAggregateDevice(description)
        let appeared = await Polling.until(timeout: 3) { CoreAudioHAL.deviceID(forUID: uid) != nil }
        try #require(appeared, "The aggregate \(uid) did not appear in the device list within 3 s")
        return CoreAudioHAL.deviceID(forUID: uid) ?? id
    }

    /// The sub-device entry for `uid` in an aggregate's composition.
    static func compositionEntry(of aggregate: AudioObjectID, for uid: String) -> [String: Any]? {
        guard let composition = CoreAudioHAL.composition(of: aggregate),
              let subDevices = composition[kAudioAggregateDeviceSubDeviceListKey] as? [[String: Any]] else { return nil }
        return subDevices.first { ($0[kAudioSubDeviceUIDKey] as? String) == uid }
    }
}

@Suite(
    "Core Audio integration (BlackHole)",
    .serialized,
    .enabled(if: IntegrationSupport.isEnabled, "Set SPEAKERLINK_AUDIO_TESTS=1 to run the Core Audio tests (needs BlackHole 2ch and 16ch)")
)
struct BlackHoleIntegrationTests {
    @Test func listsBothBlackHoleOutputsAsVirtual() throws {
        let two = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole2UID, name: "BlackHole 2ch")
        let sixteen = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole16UID, name: "BlackHole 16ch")
        #expect(two.name == "BlackHole 2ch")
        #expect(sixteen.name == "BlackHole 16ch")
        #expect(two.transport == .virtual)
        #expect(sixteen.transport == .virtual)
        #expect(two.outputChannels == 2)
        #expect(sixteen.outputChannels == 16)
        #expect(two.sampleRate > 0)
        let visible = DeviceListRules.visibleOutputs(CoreAudioHAL.outputDevices())
        #expect(visible.contains { $0.uid == two.uid })
        #expect(visible.contains { $0.uid == sixteen.uid })
        print("Outputs: " + visible.map { "\($0.name) [\($0.transport.displayName), \(Int($0.sampleRate)) Hz]" }.joined(separator: ", "))
    }

    @Test @MainActor func createsStackedAggregateWithExtraOutputLatency() async throws {
        _ = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole2UID, name: "BlackHole 2ch")
        _ = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole16UID, name: "BlackHole 16ch")
        let uid = IntegrationSupport.makeTestUID("latency")
        defer { IntegrationSupport.destroyAndWait(uid: uid) }

        let id = try await IntegrationSupport.makeBlackHoleAggregate(uid: uid, stacked: true, latencyFramesOn16: 4800)
        #expect(CoreAudioHAL.transport(of: id) == .aggregate)
        #expect(CoreAudioHAL.uid(of: id) == uid)
        #expect(Set(CoreAudioHAL.subDeviceUIDs(of: id)) == [IntegrationSupport.blackHole2UID, IntegrationSupport.blackHole16UID])

        let composition = try #require(CoreAudioHAL.composition(of: id), "The aggregate has no readable composition")
        print("Composition: \(composition)")
        let entry = try #require(
            IntegrationSupport.compositionEntry(of: id, for: IntegrationSupport.blackHole16UID),
            "The composition has no entry for BlackHole 16ch"
        )
        #expect(entry[kAudioSubDeviceExtraOutputLatencyKey] as? Int == 4800)
    }

    @Test @MainActor func stackedAggregateHasFewerOutputChannelsThanNonStacked() async throws {
        _ = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole2UID, name: "BlackHole 2ch")
        _ = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole16UID, name: "BlackHole 16ch")
        let stackedUID = IntegrationSupport.makeTestUID("stacked")
        let plainUID = IntegrationSupport.makeTestUID("plain")
        defer {
            IntegrationSupport.destroyAndWait(uid: stackedUID)
            IntegrationSupport.destroyAndWait(uid: plainUID)
        }

        let stacked = try await IntegrationSupport.makeBlackHoleAggregate(uid: stackedUID, stacked: true, latencyFramesOn16: 0)
        let plain = try await IntegrationSupport.makeBlackHoleAggregate(uid: plainUID, stacked: false, latencyFramesOn16: 0)
        let stackedChannels = CoreAudioHAL.channelCount(of: stacked, scope: kAudioObjectPropertyScopeOutput)
        let plainChannels = CoreAudioHAL.channelCount(of: plain, scope: kAudioObjectPropertyScopeOutput)
        print("Output channels: stacked=1 -> \(stackedChannels), stacked=0 -> \(plainChannels)")
        #expect(stackedChannels > 0)
        #expect(stackedChannels < plainChannels, "\"stacked\" = 1 should mirror one stream to every member (fewer channels than 2 + 16)")
    }

    @Test @MainActor func becomesDefaultOutputAndDisappearsAfterDestroy() async throws {
        _ = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole2UID, name: "BlackHole 2ch")
        _ = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole16UID, name: "BlackHole 16ch")
        let previous = CoreAudioHAL.defaultOutputDevice()
        let uid = IntegrationSupport.makeTestUID("default")
        defer {
            IntegrationSupport.restoreDefaultOutput(previous)
            IntegrationSupport.destroyAndWait(uid: uid)
        }

        let id = try await IntegrationSupport.makeBlackHoleAggregate(uid: uid, stacked: true, latencyFramesOn16: 0)
        let accepted = await DefaultOutput.set(id)
        #expect(accepted, "The aggregate was not accepted as the default output after 20 tries")
        #expect(CoreAudioHAL.defaultOutputDevice() == id)
        #expect(CoreAudioHAL.defaultOutputUID() == uid)

        if let previous {
            let restored = await DefaultOutput.set(previous)
            #expect(restored)
            #expect(CoreAudioHAL.defaultOutputDevice() == previous)
        }
        CoreAudioHAL.destroyAggregateDevice(id)
        let gone = await Polling.until(timeout: 3) { CoreAudioHAL.deviceID(forUID: uid) == nil }
        #expect(gone, "The destroyed aggregate was still listed after 3 s")
    }

    @Test @MainActor func groupControllerStartsRebuildsAndStops() async throws {
        let two = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole2UID, name: "BlackHole 2ch")
        let sixteen = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole16UID, name: "BlackHole 16ch")
        let previousID = CoreAudioHAL.defaultOutputDevice()
        let previousUID = CoreAudioHAL.defaultOutputUID()
        let uids = GroupUIDs(a: IntegrationSupport.makeTestUID("group.a"), b: IntegrationSupport.makeTestUID("group.b"))
        let controller = GroupController(uids: uids)
        defer {
            controller.shutdownNow()
            IntegrationSupport.restoreDefaultOutput(previousID)
            IntegrationSupport.destroyAndWait(uid: uids.a)
            IntegrationSupport.destroyAndWait(uid: uids.b)
        }

        let selection = [two.uid, sixteen.uid]
        guard case .ready(let plan) = GroupPlanner.plan(selectedUIDs: selection, devices: [two, sixteen], delays: [:]) else {
            Issue.record("Could not plan a group of the two BlackHole devices")
            return
        }
        #expect(plan.mainUID == two.uid)

        // Start.
        let first = try await controller.start(plan)
        #expect(first.uid == uids.a)
        #expect(CoreAudioHAL.defaultOutputUID() == uids.a)
        #expect(controller.restoreUID == previousUID)
        #expect(CoreAudioHAL.transport(of: first.objectID) == .aggregate)

        // Rebuild with a delay: built under the other UID, the old group goes away.
        guard case .ready(let delayed) = GroupPlanner.plan(selectedUIDs: selection, devices: [two, sixteen], delays: [sixteen.uid: 100], currentMainUID: plan.mainUID) else {
            Issue.record("Could not plan the delayed group")
            return
        }
        let second = try await controller.rebuild(delayed)
        #expect(second.uid == uids.b)
        #expect(CoreAudioHAL.defaultOutputUID() == uids.b)
        #expect(CoreAudioHAL.deviceID(forUID: uids.a) == nil)
        let entry = IntegrationSupport.compositionEntry(of: second.objectID, for: sixteen.uid)
        #expect(entry?[kAudioSubDeviceExtraOutputLatencyKey] as? Int == DelayMath.frames(milliseconds: 100, sampleRate: sixteen.sampleRate))

        // Stop: the previous output comes back and both UIDs are gone.
        await controller.stop(restoreDefault: true)
        #expect(controller.active == nil)
        if let previousUID, !uids.isOurs(previousUID) {
            #expect(CoreAudioHAL.defaultOutputUID() == previousUID)
        }
        #expect(CoreAudioHAL.deviceID(forUID: uids.a) == nil)
        #expect(CoreAudioHAL.deviceID(forUID: uids.b) == nil)
    }

    @Test @MainActor func removesLeftoverGroups() async throws {
        _ = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole2UID, name: "BlackHole 2ch")
        _ = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole16UID, name: "BlackHole 16ch")
        let previous = CoreAudioHAL.defaultOutputDevice()
        let uids = GroupUIDs(a: IntegrationSupport.makeTestUID("leftover.a"), b: IntegrationSupport.makeTestUID("leftover.b"))
        defer {
            IntegrationSupport.restoreDefaultOutput(previous)
            IntegrationSupport.destroyAndWait(uid: uids.a)
        }

        _ = try await IntegrationSupport.makeBlackHoleAggregate(uid: uids.a, stacked: true, latencyFramesOn16: 0)
        let controller = GroupController(uids: uids)
        await controller.removeLeftovers(restoringTo: CoreAudioHAL.defaultOutputUID())
        #expect(CoreAudioHAL.deviceID(forUID: uids.a) == nil)
    }

    @Test @MainActor func observerReportsDeviceListChanges() async throws {
        _ = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole2UID, name: "BlackHole 2ch")
        _ = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole16UID, name: "BlackHole 16ch")
        let uid = IntegrationSupport.makeTestUID("observer")
        let observer = SystemAudioObserver()
        let recorder = ChangeRecorder()
        observer.start { change in recorder.changes.append(change) }
        defer {
            observer.stop()
            IntegrationSupport.destroyAndWait(uid: uid)
        }
        #expect(observer.isRunning)

        _ = try await IntegrationSupport.makeBlackHoleAggregate(uid: uid, stacked: true, latencyFramesOn16: 0)
        let notified = await Polling.until(timeout: 3) { recorder.changes.contains(.devices) }
        #expect(notified, "No device-list notification arrived within 3 s of creating an aggregate")
    }

    @Test @MainActor func setsAndReadsBackVolume() throws {
        let two = try IntegrationSupport.requireOutput(IntegrationSupport.blackHole2UID, name: "BlackHole 2ch")
        #expect(CoreAudioHAL.hasVolumeControl(two.objectID))
        let original = try #require(CoreAudioHAL.volume(of: two.objectID), "BlackHole 2ch has no readable output volume")
        defer { CoreAudioHAL.setVolume(original, of: two.objectID) }

        for target in [0.25, 0.75] {
            #expect(CoreAudioHAL.setVolume(target, of: two.objectID))
            let readBack = try #require(CoreAudioHAL.volume(of: two.objectID))
            #expect(abs(readBack - target) < 0.01, "Set \(target), read back \(readBack)")
        }
    }
}

/// Collects observer notifications on the main actor.
@MainActor
final class ChangeRecorder {
    var changes: [SystemAudioObserver.Change] = []
}

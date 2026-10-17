// SPDX-License-Identifier: GPL-3.0-or-later

import Testing
@testable import SpeakerLinkCore

@Suite("Group UIDs, main device and planning")
struct GroupPlanningTests {
    @Test func groupUIDsAlternate() {
        let uids = GroupUIDs.production
        #expect(uids.a == "io.github.cyrus-sch.speakerlink.group.a")
        #expect(uids.b == "io.github.cyrus-sch.speakerlink.group.b")
        #expect(uids.other(than: uids.a) == uids.b)
        #expect(uids.other(than: uids.b) == uids.a)
        #expect(uids.isOurs(uids.a) && uids.isOurs(uids.b))
        #expect(!uids.isOurs("io.github.cyrus-sch.speakerlink.group.c"))
        #expect(uids.all == [uids.a, uids.b])
    }

    @Test func namesTheGroup() {
        #expect(GroupNaming.name(memberCount: 3) == "SpeakerLink (3 devices)")
        #expect(GroupNaming.name(memberCount: 1) == "SpeakerLink (1 device)")
    }

    @Test func prefersBuiltInThenWiredThenVirtual() {
        let airPods = device("bt", "AirPods", .bluetooth)
        let usb = device("usb", "Interface", .usb)
        let mac = device("mac", "Speakers", .builtIn)
        let virtual = device("virt", "BlackHole", .virtual)
        #expect(MainDeviceChooser.choose(from: [airPods, usb, mac])?.uid == "mac")
        #expect(MainDeviceChooser.choose(from: [airPods, virtual, usb])?.uid == "usb")
        #expect(MainDeviceChooser.choose(from: [airPods, virtual])?.uid == "virt")
        #expect(MainDeviceChooser.choose(from: []) == nil)
    }

    @Test func picksFirstDeviceWithMostCommonRate() {
        let speaker = device("speaker", "Speaker", .bluetooth, rate: 44_100)
        let first = device("first", "AirPods", .bluetooth, rate: 48_000)
        let second = device("second", "Headphones", .bluetooth, rate: 48_000)
        #expect(MainDeviceChooser.choose(from: [speaker, first, second])?.uid == "first")
        // A tie goes to the higher rate.
        #expect(MainDeviceChooser.choose(from: [speaker, first])?.uid == "first")
        #expect(MainDeviceChooser.choose(from: [speaker, device("other", "Other", .bluetooth, rate: 44_100)])?.uid == "speaker")
    }

    @Test func keepsTheCurrentMainWithinItsCategory() {
        let a = device("a", "AirPods", .bluetooth, rate: 48_000)
        let b = device("b", "Speaker", .bluetooth, rate: 44_100)
        let mac = device("mac", "Speakers", .builtIn)
        #expect(MainDeviceChooser.choose(from: [a, b], keeping: "b")?.uid == "b")
        // A built-in device still takes over from a Bluetooth main.
        #expect(MainDeviceChooser.choose(from: [a, b, mac], keeping: "b")?.uid == "mac")
        #expect(MainDeviceChooser.choose(from: [a, b], keeping: "gone")?.uid == "a")
    }

    @Test func plansInSelectionOrderSkippingAbsentAndAirPlay() throws {
        let devices = [
            device("a", "AirPods", .bluetooth, rate: 48_000),
            device("b", "Speaker", .bluetooth, rate: 44_100),
            device("tv", "TV", .airPlay),
        ]
        let outcome = GroupPlanner.plan(selectedUIDs: ["b", "missing", "tv", "a", "b"], devices: devices, delays: ["b": 120, "a": 999])
        guard case .ready(let plan) = outcome else {
            Issue.record("Expected a plan, got \(outcome)")
            return
        }
        #expect(plan.memberUIDs == ["b", "a"])
        #expect(plan.mainUID == "a")
        #expect(plan.members.map(\.delayMs) == [120, 500])
        #expect(plan.members[0].delayFrames == 5292)  // 120 ms at 44.1 kHz
        #expect(plan.members[1].delayFrames == 24_000)  // 500 ms at 48 kHz
    }

    @Test func needsTwoAvailableOutputs() {
        let devices = [device("a", "AirPods", .bluetooth), device("tv", "TV", .airPlay)]
        #expect(GroupPlanner.plan(selectedUIDs: ["a", "tv", "gone"], devices: devices, delays: [:]) == .notEnough(available: 1))
        #expect(GroupPlanner.plan(selectedUIDs: [], devices: devices, delays: [:]) == .notEnough(available: 0))
    }

    @Test func signatureIgnoresNamesAndRates() {
        let one = GroupPlan(members: [GroupMember(uid: "a", name: "A", sampleRate: 44_100, delayMs: 10), GroupMember(uid: "b", name: "B", sampleRate: 48_000, delayMs: 0)], mainUID: "b")
        let renamed = GroupPlan(members: [GroupMember(uid: "a", name: "A2", sampleRate: 48_000, delayMs: 10), GroupMember(uid: "b", name: "B", sampleRate: 48_000, delayMs: 0)], mainUID: "b")
        let delayed = GroupPlan(members: [GroupMember(uid: "a", name: "A", sampleRate: 44_100, delayMs: 20), GroupMember(uid: "b", name: "B", sampleRate: 48_000, delayMs: 0)], mainUID: "b")
        #expect(one.signature == renamed.signature)
        #expect(one.signature != delayed.signature)
    }
}

@Suite("Aggregate description")
struct AggregateDescriptionTests {
    @Test func literalKeysMatchAudioHardwareHeader() {
        #expect(AggregateKeys.uid == "uid")
        #expect(AggregateKeys.name == "name")
        #expect(AggregateKeys.subDevices == "subdevices")
        #expect(AggregateKeys.mainSubDevice == "master")
        #expect(AggregateKeys.isStacked == "stacked")
        #expect(AggregateKeys.isPrivate == "private")
        #expect(AggregateKeys.subDeviceUID == "uid")
        #expect(AggregateKeys.extraOutputLatency == "latency-out")
        #expect(AggregateKeys.driftCompensation == "drift")
        #expect(AggregateKeys.driftCompensationQuality == "drift quality")
        #expect(AggregateKeys.driftQualityHigh == 0x60)
    }

    @Test func buildsPublicStackedGroup() throws {
        let plan = GroupPlan(
            members: [
                GroupMember(uid: "speaker", name: "Speaker", sampleRate: 44_100, delayMs: 100),
                GroupMember(uid: "mac", name: "Mac", sampleRate: 48_000, delayMs: 0),
            ],
            mainUID: "mac"
        )
        let description = AggregateDescription.forGroup(uid: GroupUIDs.production.a, plan: plan)
        #expect(Set(description.keys) == ["uid", "name", "subdevices", "master", "stacked", "private"])
        #expect(description["uid"] as? String == "io.github.cyrus-sch.speakerlink.group.a")
        #expect(description["name"] as? String == "SpeakerLink (2 devices)")
        #expect(description["master"] as? String == "mac")
        #expect(description["stacked"] as? Int == 1)
        #expect(description["private"] as? Int == 0)

        let subDevices = try #require(description["subdevices"] as? [[String: Any]])
        #expect(subDevices.count == 2)
        let speaker = subDevices[0]
        #expect(Set(speaker.keys) == ["uid", "latency-out", "drift", "drift quality"])
        #expect(speaker["uid"] as? String == "speaker")
        #expect(speaker["latency-out"] as? Int == 4410)  // 100 ms at the speaker's own 44.1 kHz
        #expect(speaker["drift"] as? Int == 1)
        #expect(speaker["drift quality"] as? Int == 0x60)
        let mac = subDevices[1]
        #expect(Set(mac.keys) == ["uid", "latency-out", "drift"])
        #expect(mac["uid"] as? String == "mac")
        #expect(mac["latency-out"] as? Int == 0)
        #expect(mac["drift"] as? Int == 0)
    }

    @Test func buildsNonStackedPrivateVariantForTests() {
        let description = AggregateDescription.make(
            uid: "test",
            name: "Test",
            members: [AggregateDescription.Member(uid: "x", extraOutputLatencyFrames: -5, driftCompensation: false)],
            mainUID: "x",
            stacked: false,
            isPrivate: true
        )
        #expect(description["stacked"] as? Int == 0)
        #expect(description["private"] as? Int == 1)
        let subDevices = description["subdevices"] as? [[String: Any]]
        #expect(subDevices?.first?["latency-out"] as? Int == 0)
    }
}

@Suite("Reconciling wanted and actual state")
struct ReconcilerTests {
    let uids = GroupUIDs.production
    let planAB = GroupPlan(members: [GroupMember(uid: "a", name: "A", sampleRate: 48_000, delayMs: 0), GroupMember(uid: "b", name: "B", sampleRate: 48_000, delayMs: 0)], mainUID: "a")
    let planABDelayed = GroupPlan(members: [GroupMember(uid: "a", name: "A", sampleRate: 48_000, delayMs: 0), GroupMember(uid: "b", name: "B", sampleRate: 48_000, delayMs: 50)], mainUID: "a")
    let planABC = GroupPlan(members: [GroupMember(uid: "a", name: "A", sampleRate: 48_000, delayMs: 0), GroupMember(uid: "b", name: "B", sampleRate: 48_000, delayMs: 0), GroupMember(uid: "c", name: "C", sampleRate: 48_000, delayMs: 0)], mainUID: "a")

    func running(_ plan: GroupPlan, defaultOutput: String?) -> GroupSnapshot {
        GroupSnapshot(activeUID: uids.a, activePlan: plan, defaultOutputUID: defaultOutput)
    }

    @Test func startsWhenWantedAndPossible() {
        #expect(GroupReconciler.decide(wantsOn: true, outcome: .ready(planAB), snapshot: GroupSnapshot(defaultOutputUID: "mac")) == .start(planAB))
        #expect(GroupReconciler.decide(wantsOn: true, outcome: .notEnough(available: 1), snapshot: GroupSnapshot()) == .refuse(available: 1))
    }

    @Test func doesNothingWhenStateMatches() {
        // Includes the notifications caused by SpeakerLink's own create/destroy calls.
        #expect(GroupReconciler.decide(wantsOn: true, outcome: .ready(planAB), snapshot: running(planAB, defaultOutput: uids.a)) == .none)
        #expect(GroupReconciler.decide(wantsOn: false, outcome: .ready(planAB), snapshot: GroupSnapshot(defaultOutputUID: "mac")) == .none)
        // The default momentarily on our other UID during a rebuild is not a user change.
        #expect(GroupReconciler.decide(wantsOn: true, outcome: .ready(planAB), snapshot: running(planAB, defaultOutput: uids.b)) == .none)
        // An unreadable default output changes nothing.
        #expect(GroupReconciler.decide(wantsOn: true, outcome: .ready(planAB), snapshot: running(planAB, defaultOutput: nil)) == .none)
    }

    @Test func rebuildsWhenMembersOrDelaysChange() {
        #expect(GroupReconciler.decide(wantsOn: true, outcome: .ready(planABC), snapshot: running(planAB, defaultOutput: uids.a)) == .rebuild(planABC))
        #expect(GroupReconciler.decide(wantsOn: true, outcome: .ready(planABDelayed), snapshot: running(planAB, defaultOutput: uids.a)) == .rebuild(planABDelayed))
        // A member that vanished (macOS may also have moved the default) leads to a rebuild,
        // not to "the person picked another output".
        #expect(GroupReconciler.decide(wantsOn: true, outcome: .ready(planAB), snapshot: running(planABC, defaultOutput: "mac")) == .rebuild(planAB))
    }

    @Test func stopsAndRestoresWhenTurnedOffOrTooFew() {
        #expect(GroupReconciler.decide(wantsOn: false, outcome: .ready(planAB), snapshot: running(planAB, defaultOutput: uids.a))
            == .stop(restoreDefault: true, reason: .turnedOff))
        #expect(GroupReconciler.decide(wantsOn: true, outcome: .notEnough(available: 1), snapshot: running(planAB, defaultOutput: uids.a))
            == .stop(restoreDefault: true, reason: .tooFewDevices(available: 1)))
    }

    @Test func respectsAnOutputPickedInMacOS() {
        #expect(GroupReconciler.decide(wantsOn: true, outcome: .ready(planAB), snapshot: running(planAB, defaultOutput: "mac"))
            == .stop(restoreDefault: false, reason: .outputChangedElsewhere(uid: "mac")))
    }

    @Test func findsJoiningMembers() {
        #expect(GroupReconciler.joiningMembers(of: planABC, previous: planAB) == ["c"])
        #expect(GroupReconciler.joiningMembers(of: planAB, previous: nil) == ["a", "b"])
        #expect(GroupReconciler.joiningMembers(of: planAB, previous: planABC).isEmpty)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

/// The two fixed UIDs SpeakerLink gives its multi-output device.
///
/// A group is rebuilt (to change members or delays) by creating the replacement under the
/// other UID, switching the system output to it and only then destroying the old one, so
/// sound never falls back to the Mac's speakers in between. Fixed UIDs also let the app find
/// and remove a group left behind by a crash.
public struct GroupUIDs: Sendable, Hashable {
    public let a: String
    public let b: String

    public init(a: String, b: String) {
        precondition(a != b, "The two group UIDs must differ")
        self.a = a
        self.b = b
    }

    /// The UIDs the app uses.
    public static let production = GroupUIDs(
        a: "io.github.cyrus-sch.speakerlink.group.a",
        b: "io.github.cyrus-sch.speakerlink.group.b"
    )

    /// Both UIDs, a first.
    public var all: [String] { [a, b] }

    /// Whether `uid` is one of these two.
    public func isOurs(_ uid: String) -> Bool {
        uid == a || uid == b
    }

    /// The UID to build the next group under while `uid` is in use.
    public func other(than uid: String) -> String {
        uid == a ? b : a
    }
}

/// Names the multi-output device as macOS shows it, for example in Sound settings.
public enum GroupNaming {
    /// "SpeakerLink (3 devices)".
    public static func name(memberCount: Int) -> String {
        "SpeakerLink (\(memberCount) \(memberCount == 1 ? "device" : "devices"))"
    }
}

/// One output in a planned group.
public struct GroupMember: Sendable, Hashable {
    public let uid: String
    public let name: String
    /// The member's own nominal sample rate, which "latency-out" frames are counted in.
    public let sampleRate: Double
    /// Extra delay for this member, 0...500 ms.
    public let delayMs: Int

    public init(uid: String, name: String, sampleRate: Double, delayMs: Int) {
        self.uid = uid
        self.name = name
        self.sampleRate = sampleRate
        self.delayMs = DelayMath.clamp(delayMs)
    }

    /// The delay as sample frames at this member's own rate (the unit of "latency-out").
    public var delayFrames: Int {
        DelayMath.frames(milliseconds: delayMs, sampleRate: sampleRate)
    }
}

/// A complete description of a group SpeakerLink wants Core Audio to build.
public struct GroupPlan: Sendable, Hashable {
    /// Members in the order the person picked them.
    public let members: [GroupMember]
    /// The main (clock) device, whose clock the others follow with drift compensation.
    public let mainUID: String

    public init(members: [GroupMember], mainUID: String) {
        self.members = members
        self.mainUID = mainUID
    }

    public var memberUIDs: [String] { members.map(\.uid) }

    /// What decides whether a running group must be rebuilt: members, main device and delays.
    /// Names and sample rates are left out on purpose: joining an aggregate can change a
    /// member's rate, and rebuilding because of that would only cause another change.
    public struct Signature: Sendable, Hashable {
        public let memberUIDs: [String]
        public let mainUID: String
        public let delaysMs: [Int]
    }

    public var signature: Signature {
        Signature(memberUIDs: memberUIDs, mainUID: mainUID, delaysMs: members.map(\.delayMs))
    }
}

/// Chooses the main (clock) device of a group.
public enum MainDeviceChooser {
    /// The main device for `members` (in selection order):
    ///
    /// 1. the first built-in device (the Mac's speakers or headphone jack), else
    /// 2. the first other wired device (USB, HDMI, …), else
    /// 3. the first virtual device, else
    /// 4. the first device with the most common sample rate (ties go to the higher rate), so
    ///    as few members as possible need resampling.
    ///
    /// When `keeping` names a member that the rules would not move away from (it is in the
    /// same category as the winner), it stays main, so a running group does not change its
    /// clock just because a member's rate changed.
    public static func choose(from members: [OutputDevice], keeping current: String? = nil) -> OutputDevice? {
        guard !members.isEmpty else { return nil }
        let categories: [(OutputDevice) -> Bool] = [
            { $0.transport == .builtIn },
            { $0.transport.isWired && $0.transport != .builtIn },
            { $0.transport == .virtual },
        ]
        for belongs in categories {
            let candidates = members.filter(belongs)
            if let kept = candidates.first(where: { $0.uid == current }) { return kept }
            if let first = candidates.first { return first }
        }
        if let kept = members.first(where: { $0.uid == current }) { return kept }
        var counts: [Double: Int] = [:]
        for member in members { counts[member.sampleRate, default: 0] += 1 }
        let best = counts.max { lhs, rhs in
            lhs.value != rhs.value ? lhs.value < rhs.value : lhs.key < rhs.key
        }?.key
        return members.first { $0.sampleRate == best } ?? members.first
    }
}

/// The result of planning a group from the current selection.
public enum PlanOutcome: Sendable, Hashable {
    case ready(GroupPlan)
    /// Fewer than two of the selected outputs are present and able to join.
    case notEnough(available: Int)
}

/// Turns the person's selection into a group plan.
public enum GroupPlanner {
    /// Plans a group from the selected UIDs (in selection order) and the outputs present now.
    ///
    /// Selected devices that are absent or cannot join (AirPlay) are skipped; they join again
    /// when they come back. `delays` are milliseconds by UID; `currentMainUID` keeps the main
    /// device of a running group stable (see ``MainDeviceChooser``).
    public static func plan(
        selectedUIDs: [String],
        devices: [OutputDevice],
        delays: [String: Int],
        currentMainUID: String? = nil
    ) -> PlanOutcome {
        var seen = Set<String>()
        let members = selectedUIDs.compactMap { uid -> OutputDevice? in
            guard seen.insert(uid).inserted else { return nil }
            return devices.first { $0.uid == uid && $0.canJoinGroup && $0.outputChannels > 0 }
        }
        guard members.count >= 2, let main = MainDeviceChooser.choose(from: members, keeping: currentMainUID) else {
            return .notEnough(available: members.count)
        }
        let planned = members.map { device in
            GroupMember(uid: device.uid, name: device.name, sampleRate: device.sampleRate, delayMs: delays[device.uid] ?? 0)
        }
        return .ready(GroupPlan(members: planned, mainUID: main.uid))
    }
}

/// Why a running group was stopped.
public enum StopReason: Sendable, Hashable {
    /// The person switched "Listen together" off (or quit).
    case turnedOff
    /// Fewer than two of the chosen outputs are still available.
    case tooFewDevices(available: Int)
    /// Something else became the system output (the person picked another output in macOS).
    case outputChangedElsewhere(uid: String)
}

/// What the app should do to bring Core Audio in line with what the person wants.
public enum GroupAction: Sendable, Hashable {
    case none
    case start(GroupPlan)
    case rebuild(GroupPlan)
    case stop(restoreDefault: Bool, reason: StopReason)
    /// "Listen together" was switched on, but fewer than two outputs can play.
    case refuse(available: Int)
}

/// The facts the reconciler needs about Core Audio right now.
public struct GroupSnapshot: Sendable, Hashable {
    /// UID of SpeakerLink's running group, if any.
    public var activeUID: String?
    /// The plan the running group was built from.
    public var activePlan: GroupPlan?
    /// UID of the current system default output, if it could be read.
    public var defaultOutputUID: String?

    public init(activeUID: String? = nil, activePlan: GroupPlan? = nil, defaultOutputUID: String? = nil) {
        self.activeUID = activeUID
        self.activePlan = activePlan
        self.defaultOutputUID = defaultOutputUID
    }
}

/// Decides what to do after any change (selection, delays, devices coming and going, the
/// system output changing).
///
/// The decision depends only on state, never on which notification arrived, so the
/// notifications that SpeakerLink's own create/destroy calls cause end in ``GroupAction/none``
/// instead of rebuild loops.
public enum GroupReconciler {
    public static func decide(
        wantsOn: Bool,
        outcome: PlanOutcome,
        snapshot: GroupSnapshot,
        groupUIDs: GroupUIDs = .production
    ) -> GroupAction {
        let isActive = snapshot.activeUID != nil
        guard wantsOn else {
            return isActive ? .stop(restoreDefault: true, reason: .turnedOff) : .none
        }
        switch outcome {
        case .notEnough(let available):
            return isActive
                ? .stop(restoreDefault: true, reason: .tooFewDevices(available: available))
                : .refuse(available: available)
        case .ready(let plan):
            guard isActive, let activePlan = snapshot.activePlan else { return .start(plan) }
            if plan.signature != activePlan.signature { return .rebuild(plan) }
            if let current = snapshot.defaultOutputUID, current != snapshot.activeUID, !groupUIDs.isOurs(current) {
                return .stop(restoreDefault: false, reason: .outputChangedElsewhere(uid: current))
            }
            return .none
        }
    }

    /// Members of `plan` that were not in `previous` (they need their volume adopted).
    public static func joiningMembers(of plan: GroupPlan, previous: GroupPlan?) -> [String] {
        let before = Set(previous?.memberUIDs ?? [])
        return plan.memberUIDs.filter { !before.contains($0) }
    }
}

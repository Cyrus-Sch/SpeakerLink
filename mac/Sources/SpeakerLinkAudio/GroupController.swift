// SPDX-License-Identifier: GPL-3.0-or-later

import CoreAudio
import Foundation
import SpeakerLinkCore

/// A group SpeakerLink has built and made the system output.
public struct ActiveGroup: Sendable, Hashable {
    /// The aggregate device's UID (one of ``GroupUIDs``).
    public let uid: String
    /// The aggregate device's `AudioObjectID`.
    public let objectID: AudioObjectID
    /// The plan it was built from.
    public let plan: GroupPlan

    public init(uid: String, objectID: AudioObjectID, plan: GroupPlan) {
        self.uid = uid
        self.objectID = objectID
        self.plan = plan
    }
}

/// How long the controller waits for coreaudiod.
public struct GroupTiming: Sendable, Hashable {
    /// How long a new aggregate may take to appear in the device list.
    public var appearTimeout: Double
    /// How often to try making a new aggregate the default output.
    public var defaultAttempts: Int
    /// Pause between those attempts.
    public var defaultRetryInterval: Double
    /// How long a destroyed aggregate may take to leave the device list.
    public var removalTimeout: Double
    /// Polling interval for the waits above.
    public var pollInterval: Double

    public init(appearTimeout: Double, defaultAttempts: Int, defaultRetryInterval: Double, removalTimeout: Double, pollInterval: Double) {
        self.appearTimeout = appearTimeout
        self.defaultAttempts = defaultAttempts
        self.defaultRetryInterval = defaultRetryInterval
        self.removalTimeout = removalTimeout
        self.pollInterval = pollInterval
    }

    /// About 2 s to appear, 20 × 50 ms to become the default, 3 s to disappear.
    public static let standard = GroupTiming(
        appearTimeout: 2, defaultAttempts: 20, defaultRetryInterval: 0.05, removalTimeout: 3, pollInterval: 0.05
    )
}

/// Builds, rebuilds and removes SpeakerLink's multi-output device (a public, stacked
/// aggregate) and makes it the system output.
///
/// The controller does not watch for changes itself; the app decides what to do with
/// ``GroupReconciler`` and calls one method at a time (each call awaits coreaudiod, so callers
/// must not start a second call before the first returns).
///
/// Ideas for the create/retry/restore sequence follow 2geik/AudioShare (MIT): retry making a
/// new aggregate the default, restore the previous output before destroying, and remove
/// leftovers by fixed UID after a crash.
@MainActor
public final class GroupController {
    public let uids: GroupUIDs
    public let timing: GroupTiming

    /// The running group, if any.
    public private(set) var active: ActiveGroup?

    /// UID of the output that was the default before the group started; restored on stop.
    public private(set) var restoreUID: String?

    public init(uids: GroupUIDs = .production, timing: GroupTiming = .standard) {
        self.uids = uids
        self.timing = timing
    }

    // MARK: - Start, rebuild, stop

    /// Builds the group under the first UID and makes it the system output, remembering the
    /// current output for ``stop(restoreDefault:)``. When a group is already running this
    /// rebuilds it instead.
    @discardableResult
    public func start(_ plan: GroupPlan) async throws -> ActiveGroup {
        if active != nil { return try await rebuild(plan) }
        guard plan.members.count >= 2 else { throw GroupError.notEnoughDevices(available: plan.members.count) }
        if let current = CoreAudioHAL.defaultOutputUID(), !uids.isOurs(current) {
            restoreUID = current
        }
        await removeDevices(withUIDs: uids.all)
        let group = try await build(plan, uid: uids.a)
        active = group
        return group
    }

    /// Replaces the running group with one built from `plan` without the sound falling back
    /// to another output: the replacement is built under the other UID and made the default
    /// first, and only then is the old group destroyed. If building the replacement fails,
    /// the old group keeps playing and the error is thrown.
    @discardableResult
    public func rebuild(_ plan: GroupPlan) async throws -> ActiveGroup {
        guard let old = active else { return try await start(plan) }
        guard plan.members.count >= 2 else { throw GroupError.notEnoughDevices(available: plan.members.count) }
        let newUID = uids.other(than: old.uid)
        await removeDevices(withUIDs: [newUID])
        let group = try await build(plan, uid: newUID)
        active = group
        CoreAudioHAL.destroyAggregateDevice(old.objectID)
        _ = await Polling.until(timeout: timing.removalTimeout, interval: timing.pollInterval) {
            CoreAudioHAL.deviceID(forUID: old.uid) == nil
        }
        return group
    }

    /// Stops the group. With `restoreDefault`, and while the group is still the system
    /// output, the remembered output (or else the built-in output) becomes the default
    /// again first; then the group is destroyed and the controller waits until it is gone.
    ///
    /// Pass `restoreDefault: false` when the person picked another output in macOS, so their
    /// choice stays.
    public func stop(restoreDefault: Bool) async {
        guard let old = active else { return }
        active = nil
        if restoreDefault, shouldRestore(from: [old.objectID]), let target = restoreTarget() {
            _ = await DefaultOutput.set(target, attempts: timing.defaultAttempts, interval: timing.defaultRetryInterval)
        }
        CoreAudioHAL.destroyAggregateDevice(old.objectID)
        _ = await Polling.until(timeout: timing.removalTimeout, interval: timing.pollInterval) {
            CoreAudioHAL.deviceID(forUID: old.uid) == nil
        }
        restoreUID = nil
    }

    /// Stops at once, blocking the main thread for at most about two seconds. For app
    /// termination, when there is no time to suspend. Removes every device with one of our
    /// UIDs, not only the active one.
    public func shutdownNow() {
        let ours = ourDeviceIDs()
        active = nil
        guard !ours.isEmpty else {
            restoreUID = nil
            return
        }
        if shouldRestore(from: ours), let target = restoreTarget() {
            _ = DefaultOutput.setBlocking(target)
        }
        for id in ours { CoreAudioHAL.destroyAggregateDevice(id) }
        _ = Polling.untilBlocking(timeout: 1.5, interval: timing.pollInterval) {
            self.ourDeviceIDs().isEmpty
        }
        restoreUID = nil
    }

    /// Removes groups left behind by an earlier run that crashed. If one of them is the
    /// system output, `savedRestoreUID` (the output remembered by that run) or the built-in
    /// output becomes the default first. Does nothing while a group is running.
    public func removeLeftovers(restoringTo savedRestoreUID: String?) async {
        guard active == nil else { return }
        let leftovers = ourDeviceIDs()
        guard !leftovers.isEmpty else { return }
        if shouldRestore(from: leftovers) {
            restoreUID = savedRestoreUID
            if let target = restoreTarget() {
                _ = await DefaultOutput.set(target, attempts: timing.defaultAttempts, interval: timing.defaultRetryInterval)
            }
            restoreUID = nil
        }
        for id in leftovers { CoreAudioHAL.destroyAggregateDevice(id) }
        _ = await Polling.until(timeout: timing.removalTimeout, interval: timing.pollInterval) {
            self.ourDeviceIDs().isEmpty
        }
    }

    // MARK: - Helpers

    /// Creates the aggregate for `plan` under `uid`, waits for it to appear and makes it the
    /// default output. Destroys it again if any step fails.
    private func build(_ plan: GroupPlan, uid: String) async throws -> ActiveGroup {
        let description = AggregateDescription.forGroup(uid: uid, plan: plan)
        let createdID = try CoreAudioHAL.createAggregateDevice(description)
        let appeared = await Polling.until(timeout: timing.appearTimeout, interval: timing.pollInterval) {
            CoreAudioHAL.deviceID(forUID: uid) != nil
        }
        guard appeared else {
            CoreAudioHAL.destroyAggregateDevice(createdID)
            throw GroupError.didNotAppear
        }
        let id = CoreAudioHAL.deviceID(forUID: uid) ?? createdID
        let accepted = await DefaultOutput.set(id, attempts: timing.defaultAttempts, interval: timing.defaultRetryInterval)
        guard accepted else {
            CoreAudioHAL.destroyAggregateDevice(id)
            _ = await Polling.until(timeout: timing.removalTimeout, interval: timing.pollInterval) {
                CoreAudioHAL.deviceID(forUID: uid) == nil
            }
            throw GroupError.notAcceptedAsDefault
        }
        return ActiveGroup(uid: uid, objectID: id, plan: plan)
    }

    /// Destroys any device with one of `uids` and waits until they are gone, so the UID can
    /// be used again (destruction is asynchronous).
    private func removeDevices(withUIDs uidsToRemove: [String]) async {
        let ids = CoreAudioHAL.deviceIDs().filter { id in
            CoreAudioHAL.uid(of: id).map { uidsToRemove.contains($0) } ?? false
        }
        guard !ids.isEmpty else { return }
        for id in ids { CoreAudioHAL.destroyAggregateDevice(id) }
        _ = await Polling.until(timeout: timing.removalTimeout, interval: timing.pollInterval) {
            uidsToRemove.allSatisfy { CoreAudioHAL.deviceID(forUID: $0) == nil }
        }
    }

    /// IDs of all devices that carry one of our UIDs.
    private func ourDeviceIDs() -> [AudioObjectID] {
        CoreAudioHAL.deviceIDs().filter { id in
            CoreAudioHAL.uid(of: id).map { uids.isOurs($0) } ?? false
        }
    }

    /// Whether the default output is one of `ids` (or cannot be read), so it must be moved
    /// before they are destroyed.
    private func shouldRestore(from ids: [AudioObjectID]) -> Bool {
        guard let current = CoreAudioHAL.defaultOutputDevice() else { return true }
        if ids.contains(current) { return true }
        return CoreAudioHAL.uid(of: current).map { uids.isOurs($0) } ?? false
    }

    /// The remembered output if it still exists, otherwise the built-in output.
    private func restoreTarget() -> AudioObjectID? {
        if let restoreUID, !uids.isOurs(restoreUID), let id = CoreAudioHAL.deviceID(forUID: restoreUID),
           CoreAudioHAL.channelCount(of: id, scope: kAudioObjectPropertyScopeOutput) > 0 {
            return id
        }
        let builtIn = CoreAudioHAL.outputDevices().first(where: { device in
            device.transport == .builtIn && !device.isHidden && device.canBeDefault
        })
        return builtIn?.objectID
    }
}

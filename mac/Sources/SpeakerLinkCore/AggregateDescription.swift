// SPDX-License-Identifier: GPL-3.0-or-later

/// The dictionary keys `AudioHardwareCreateAggregateDevice` understands, spelled out as the
/// literal strings from AudioHardware.h (SpeakerLinkCore cannot import CoreAudio).
///
/// SpeakerLinkAudio's tests compare each one with the SDK constant of the same meaning.
public enum AggregateKeys {
    /// `kAudioAggregateDeviceUIDKey`.
    public static let uid = "uid"
    /// `kAudioAggregateDeviceNameKey`.
    public static let name = "name"
    /// `kAudioAggregateDeviceSubDeviceListKey`: an array of sub-device dictionaries.
    public static let subDevices = "subdevices"
    /// `kAudioAggregateDeviceMainSubDeviceKey` (formerly "Master"): UID of the clock device.
    public static let mainSubDevice = "master"
    /// `kAudioAggregateDeviceIsStackedKey`: 1 makes a multi-output device, where every member
    /// plays the same stream. (The header's comment says 0; every working implementation and
    /// SpeakerLink's own CI test show 1 is right.)
    public static let isStacked = "stacked"
    /// `kAudioAggregateDeviceIsPrivateKey`: 0 publishes the device to all apps, which is
    /// needed for it to become the system output.
    public static let isPrivate = "private"

    /// `kAudioSubDeviceUIDKey`.
    public static let subDeviceUID = "uid"
    /// `kAudioSubDeviceExtraOutputLatencyKey`: extra output latency in frames at the
    /// sub-device's own sample rate. SpeakerLink uses it as the per-device delay.
    public static let extraOutputLatency = "latency-out"
    /// `kAudioSubDeviceDriftCompensationKey`: non-zero resamples this member to follow the
    /// main device's clock.
    public static let driftCompensation = "drift"
    /// `kAudioSubDeviceDriftCompensationQualityKey`.
    public static let driftCompensationQuality = "drift quality"

    /// `kAudioAggregateDriftCompensationHighQuality` (0x60).
    public static let driftQualityHigh = 0x60
}

/// Builds the description dictionary for `AudioHardwareCreateAggregateDevice`.
public enum AggregateDescription {
    /// One sub-device entry.
    public struct Member: Sendable, Hashable {
        public let uid: String
        /// Extra output latency ("latency-out") in frames at this device's rate.
        public let extraOutputLatencyFrames: Int
        /// Whether this member is resampled to follow the main device's clock.
        public let driftCompensation: Bool

        public init(uid: String, extraOutputLatencyFrames: Int, driftCompensation: Bool) {
            self.uid = uid
            self.extraOutputLatencyFrames = max(0, extraOutputLatencyFrames)
            self.driftCompensation = driftCompensation
        }
    }

    /// The full description. Numbers are `Int` (bridged to CFNumber); every member gets
    /// "latency-out", and members with drift compensation also get "drift quality" = high.
    public static func make(
        uid: String,
        name: String,
        members: [Member],
        mainUID: String,
        stacked: Bool = true,
        isPrivate: Bool = false
    ) -> [String: Any] {
        let subDevices: [[String: Any]] = members.map { member in
            var entry: [String: Any] = [
                AggregateKeys.subDeviceUID: member.uid,
                AggregateKeys.extraOutputLatency: member.extraOutputLatencyFrames,
                AggregateKeys.driftCompensation: member.driftCompensation ? 1 : 0,
            ]
            if member.driftCompensation {
                entry[AggregateKeys.driftCompensationQuality] = AggregateKeys.driftQualityHigh
            }
            return entry
        }
        return [
            AggregateKeys.uid: uid,
            AggregateKeys.name: name,
            AggregateKeys.subDevices: subDevices,
            AggregateKeys.mainSubDevice: mainUID,
            AggregateKeys.isStacked: stacked ? 1 : 0,
            AggregateKeys.isPrivate: isPrivate ? 1 : 0,
        ]
    }

    /// The members of `plan` as sub-device entries: drift compensation on for every member
    /// except the main device, and each delay converted to frames at that member's own rate.
    public static func members(of plan: GroupPlan) -> [Member] {
        plan.members.map { member in
            Member(uid: member.uid, extraOutputLatencyFrames: member.delayFrames, driftCompensation: member.uid != plan.mainUID)
        }
    }

    /// The public, stacked multi-output device for `plan`, named "SpeakerLink (N devices)".
    public static func forGroup(uid: String, plan: GroupPlan) -> [String: Any] {
        make(
            uid: uid,
            name: GroupNaming.name(memberCount: plan.members.count),
            members: members(of: plan),
            mainUID: plan.mainUID,
            stacked: true,
            isPrivate: false
        )
    }
}

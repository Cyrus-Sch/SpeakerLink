// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// Everything SpeakerLink remembers between launches, stored as one JSON value.
///
/// Decoding is forgiving: missing keys (data from an older version), values of the wrong type
/// and unknown keys (data from a newer version) fall back to defaults instead of losing the
/// whole configuration, and out-of-range numbers are clamped.
public struct AppSettings: Codable, Sendable, Hashable {
    /// Format version written with the data.
    public static let currentVersion = 1

    /// Outputs chosen for the group, in the order they were chosen.
    public var selectedUIDs: [String]
    /// Extra delay per output UID, in milliseconds (0...500, multiples of 10).
    public var delays: [String: Int]
    /// Level per output UID (0...1), relative to ``master``.
    public var levels: [String: Double]
    /// Master volume of the group (0...1).
    public var master: Double
    /// Whether the keyboard's volume keys change the master volume while listening together.
    public var useVolumeKeys: Bool
    /// UID of the output that was in use before the group started, so it can be restored even
    /// after a crash.
    public var restoreOutputUID: String?

    public init(
        selectedUIDs: [String] = [],
        delays: [String: Int] = [:],
        levels: [String: Double] = [:],
        master: Double = 1,
        useVolumeKeys: Bool = true,
        restoreOutputUID: String? = nil
    ) {
        self.selectedUIDs = selectedUIDs
        self.delays = delays
        self.levels = levels
        self.master = master
        self.useVolumeKeys = useVolumeKeys
        self.restoreOutputUID = restoreOutputUID
    }

    enum CodingKeys: String, CodingKey {
        case version
        case selectedUIDs
        case delays
        case levels
        case master
        case useVolumeKeys
        case restoreOutputUID
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let defaults = AppSettings()
        selectedUIDs = (try? container.decodeIfPresent([String].self, forKey: .selectedUIDs)) ?? defaults.selectedUIDs
        delays = (try? container.decodeIfPresent([String: Int].self, forKey: .delays)) ?? defaults.delays
        levels = (try? container.decodeIfPresent([String: Double].self, forKey: .levels)) ?? defaults.levels
        master = (try? container.decodeIfPresent(Double.self, forKey: .master)) ?? defaults.master
        useVolumeKeys = (try? container.decodeIfPresent(Bool.self, forKey: .useVolumeKeys)) ?? defaults.useVolumeKeys
        restoreOutputUID = try? container.decodeIfPresent(String.self, forKey: .restoreOutputUID)
        self = normalized()
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(Self.currentVersion, forKey: .version)
        try container.encode(selectedUIDs, forKey: .selectedUIDs)
        try container.encode(delays, forKey: .delays)
        try container.encode(levels, forKey: .levels)
        try container.encode(master, forKey: .master)
        try container.encode(useVolumeKeys, forKey: .useVolumeKeys)
        try container.encodeIfPresent(restoreOutputUID, forKey: .restoreOutputUID)
    }

    /// A copy with duplicate or empty UIDs removed and every number in range.
    public func normalized() -> AppSettings {
        var seen = Set<String>()
        let uids = selectedUIDs.filter { !$0.isEmpty && seen.insert($0).inserted }
        return AppSettings(
            selectedUIDs: uids,
            delays: delays.filter { !$0.key.isEmpty }.mapValues { DelayMath.snap(Double($0)) },
            levels: levels.filter { !$0.key.isEmpty }.mapValues(VolumeMath.clamp),
            master: VolumeMath.clamp(master),
            useVolumeKeys: useVolumeKeys,
            restoreOutputUID: restoreOutputUID.flatMap { $0.isEmpty ? nil : $0 }
        )
    }

    /// Adds or removes an output from the selection, keeping the order of the others.
    public mutating func setSelected(_ uid: String, _ selected: Bool) {
        if selected {
            if !selectedUIDs.contains(uid) { selectedUIDs.append(uid) }
        } else {
            selectedUIDs.removeAll { $0 == uid }
        }
    }

    /// The delay for an output, 0 when none is stored.
    public func delay(for uid: String) -> Int {
        delays[uid] ?? 0
    }

    /// The level for an output, 1 when none is stored.
    public func level(for uid: String) -> Double {
        levels[uid] ?? 1
    }
}

/// Loads and saves ``AppSettings`` in `UserDefaults`.
public struct SettingsStore {
    /// The defaults key the JSON is stored under.
    public static let defaultKey = "SpeakerLinkSettings"

    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = SettingsStore.defaultKey) {
        self.defaults = defaults
        self.key = key
    }

    /// The stored settings, or the defaults when nothing (readable) is stored.
    public func load() -> AppSettings {
        guard let data = defaults.data(forKey: key),
              let settings = try? JSONDecoder().decode(AppSettings.self, from: data) else {
            return AppSettings()
        }
        return settings
    }

    public func save(_ settings: AppSettings) {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(settings.normalized()) else { return }
        defaults.set(data, forKey: key)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import SpeakerLinkCore

@Suite("Settings")
struct SettingsTests {
    func decode(_ json: String) throws -> AppSettings {
        try JSONDecoder().decode(AppSettings.self, from: Data(json.utf8))
    }

    @Test func roundTrips() throws {
        let settings = AppSettings(
            selectedUIDs: ["F3-A2-14-A9-1D-F8:output", "BuiltInSpeakerDevice"],
            delays: ["BuiltInSpeakerDevice": 180],
            levels: ["F3-A2-14-A9-1D-F8:output": 0.75],
            master: 0.5,
            useVolumeKeys: false,
            restoreOutputUID: "BuiltInSpeakerDevice"
        )
        let data = try JSONEncoder().encode(settings)
        #expect(try JSONDecoder().decode(AppSettings.self, from: data) == settings)
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        #expect(object?["version"] as? Int == AppSettings.currentVersion)
    }

    @Test func emptyDataGivesDefaults() throws {
        let settings = try decode("{}")
        #expect(settings == AppSettings())
        #expect(settings.master == 1)
        #expect(settings.useVolumeKeys)
        #expect(settings.selectedUIDs.isEmpty)
    }

    @Test func olderDataWithoutNewerKeysKeepsWhatItHas() throws {
        let settings = try decode(#"{"version": 0, "selectedUIDs": ["a", "b"], "delays": {"a": 40}}"#)
        #expect(settings.selectedUIDs == ["a", "b"])
        #expect(settings.delays == ["a": 40])
        #expect(settings.levels.isEmpty)
        #expect(settings.master == 1)
        #expect(settings.useVolumeKeys)
        #expect(settings.restoreOutputUID == nil)
    }

    @Test func wrongTypesAndUnknownKeysFallBackPerField() throws {
        let settings = try decode(#"{"selectedUIDs": "a", "master": "loud", "useVolumeKeys": false, "levels": {"a": 0.5}, "futureKey": [1, 2]}"#)
        #expect(settings.selectedUIDs.isEmpty)
        #expect(settings.master == 1)
        #expect(settings.useVolumeKeys == false)
        #expect(settings.levels == ["a": 0.5])
    }

    @Test func normalisesOutOfRangeValues() throws {
        let settings = try decode(#"{"selectedUIDs": ["a", "", "a", "b"], "delays": {"a": 733, "b": -20, "c": 44}, "levels": {"a": 1.7, "b": -0.2}, "master": 3, "restoreOutputUID": ""}"#)
        #expect(settings.selectedUIDs == ["a", "b"])
        #expect(settings.delays == ["a": 500, "b": 0, "c": 40])
        #expect(settings.levels == ["a": 1, "b": 0])
        #expect(settings.master == 1)
        #expect(settings.restoreOutputUID == nil)
    }

    @Test func togglesSelectionKeepingOrder() {
        var settings = AppSettings(selectedUIDs: ["a", "b"])
        settings.setSelected("c", true)
        settings.setSelected("a", true)
        #expect(settings.selectedUIDs == ["a", "b", "c"])
        settings.setSelected("b", false)
        #expect(settings.selectedUIDs == ["a", "c"])
        #expect(settings.delay(for: "x") == 0)
        #expect(settings.level(for: "x") == 1)
    }

    @Test func storeSavesAndLoads() throws {
        let suite = "SpeakerLinkTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = SettingsStore(defaults: defaults)
        #expect(store.load() == AppSettings())
        let settings = AppSettings(selectedUIDs: ["a", "b"], delays: ["b": 30], master: 0.4)
        store.save(settings)
        #expect(store.load() == settings)
        defaults.set(Data("not json".utf8), forKey: SettingsStore.defaultKey)
        #expect(store.load() == AppSettings())
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

import Testing
@testable import SpeakerLinkCore

@Suite("Bluetooth addresses and device classes")
struct BluetoothTests {
    @Test("Addresses normalise to uppercase with dashes", arguments: [
        ("f3-a2-14-a9-1d-f8", "F3-A2-14-A9-1D-F8"),
        ("F3:A2:14:A9:1D:F8", "F3-A2-14-A9-1D-F8"),
        ("f3a214a91df8", "F3-A2-14-A9-1D-F8"),
        ("  F3-A2-14-A9-1D-F8\n", "F3-A2-14-A9-1D-F8"),
        ("00-11-22-33-44-55", "00-11-22-33-44-55"),
    ])
    func normalises(_ example: (String, String)) {
        let (input, expected) = example
        #expect(BluetoothAddress(input)?.rawValue == expected)
    }

    @Test("Malformed addresses are rejected", arguments: [
        "", "F3-A2-14-A9-1D", "F3-A2-14-A9-1D-F8-00", "G3-A2-14-A9-1D-F8", "F3-A2-14-A9-1D-F", "F3--A2-14-A9-1D-F8",
        "F3A214A91DF", "BuiltInSpeakerDevice", "F3-A2-14-A9-1D-Ｆ8",
    ])
    func rejects(input: String) {
        #expect(BluetoothAddress(input) == nil)
    }

    @Test func parsesCoreAudioUIDs() {
        #expect(BluetoothAddress(audioDeviceUID: "F3-A2-14-A9-1D-F8:output")?.rawValue == "F3-A2-14-A9-1D-F8")
        #expect(BluetoothAddress(audioDeviceUID: "f3-a2-14-a9-1d-f8:input")?.rawValue == "F3-A2-14-A9-1D-F8")
        #expect(BluetoothAddress(audioDeviceUID: "F3-A2-14-A9-1D-F8:OUTPUT")?.rawValue == "F3-A2-14-A9-1D-F8")
        #expect(BluetoothAddress(audioDeviceUID: "BlackHole2ch_UID") == nil)
        #expect(BluetoothAddress(audioDeviceUID: "AppleHDAEngineOutput:1B,0,1,1:0") == nil)
        #expect(BluetoothAddress(audioDeviceUID: "F3-A2-14-A9-1D-F8:other") == nil)
    }

    @Test func buildsOutputAndInputUIDs() throws {
        let address = try #require(BluetoothAddress("f3:a2:14:a9:1d:f8"))
        #expect(address.outputUID == "F3-A2-14-A9-1D-F8:output")
        #expect(address.inputUID == "F3-A2-14-A9-1D-F8:input")
        #expect(BluetoothAddress(audioDeviceUID: address.outputUID) == address)
    }

    @Test("Audio minor classes map to kinds", arguments: [
        (0x01, DeviceKind.headphones),  // headset
        (0x02, DeviceKind.headphones),  // hands-free
        (0x05, DeviceKind.speaker),     // loudspeaker
        (0x06, DeviceKind.headphones),  // headphones
        (0x07, DeviceKind.speaker),     // portable audio
        (0x08, DeviceKind.car),         // car audio
        (0x0A, DeviceKind.speaker),     // hi-fi audio
        (0x0F, DeviceKind.display),     // video display and loudspeaker
    ])
    func mapsAudioMinor(_ example: (Int, DeviceKind)) {
        let (minor, kind) = example
        #expect(BluetoothDeviceClass(major: 0x04, minor: minor).outputKind == kind)
    }

    @Test func otherClassesHaveNoKind() {
        #expect(BluetoothDeviceClass(major: 0x04, minor: 0x04).outputKind == nil)  // microphone
        #expect(BluetoothDeviceClass(major: 0x04, minor: 0x00).outputKind == nil)  // uncategorised
        #expect(BluetoothDeviceClass(major: 0x05, minor: 0x06).outputKind == nil)  // peripheral
        #expect(BluetoothDeviceClass(major: 0x01, minor: 0x03).outputKind == nil)  // computer
    }

    @Test func decodesRawClassOfDevice() {
        // 0x240418: rendering + audio service bits, major audio (0x04), minor headphones (0x06).
        let headphones = BluetoothDeviceClass(rawValue: 0x24_0418)
        #expect(headphones.major == 0x04)
        #expect(headphones.minor == 0x06)
        #expect(headphones.isAudio)
        // 0x240414: loudspeaker.
        #expect(BluetoothDeviceClass(rawValue: 0x24_0414).outputKind == .speaker)
        // 0x5A020C: a phone (major 0x02).
        #expect(BluetoothDeviceClass(rawValue: 0x5A_020C).isAudio == false)
    }

    @Test func listsPairedDevicesWithoutAnOutput() throws {
        let connected = try #require(BluetoothAddress("11-11-11-11-11-11"))
        let offline = try #require(BluetoothAddress("22-22-22-22-22-22"))
        let other = try #require(BluetoothAddress("33-33-33-33-33-33"))
        let paired = [
            PairedAudioDevice(address: offline, name: "Zed Speaker", bluetoothClass: BluetoothDeviceClass(major: 4, minor: 5), isLinkUp: false),
            PairedAudioDevice(address: connected, name: "AirPods", bluetoothClass: BluetoothDeviceClass(major: 4, minor: 6), isLinkUp: true),
            PairedAudioDevice(address: other, name: "Alpha Headphones", bluetoothClass: BluetoothDeviceClass(major: 4, minor: 6), isLinkUp: false),
        ]
        let result = PairedDeviceRules.notConnected(paired, presentOutputUIDs: [connected.outputUID, "BuiltInSpeakerDevice"])
        #expect(result.map(\.name) == ["Alpha Headphones", "Zed Speaker"])
        #expect(PairedDeviceRules.classesByAddress(paired)[connected] == BluetoothDeviceClass(major: 4, minor: 6))
    }

    @Test func pairedDeviceKindFallsBack() throws {
        let address = try #require(BluetoothAddress("44-44-44-44-44-44"))
        let unclassified = BluetoothDeviceClass(major: 0x07, minor: 0x01)  // wearable
        #expect(PairedAudioDevice(address: address, name: "Jo's Buds", bluetoothClass: unclassified, isLinkUp: false).kind == .headphones)
        #expect(PairedAudioDevice(address: address, name: "Thing", bluetoothClass: unclassified, isLinkUp: false).kind == .speaker)
    }

    @Test func authorizationFlags() {
        #expect(BluetoothAuthorization.allowed.isAllowed)
        #expect(!BluetoothAuthorization.notDetermined.isAllowed)
        #expect(BluetoothAuthorization.denied.isBlocked)
        #expect(BluetoothAuthorization.restricted.isBlocked)
        #expect(!BluetoothAuthorization.unavailable.isBlocked)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later

import Testing
@testable import SpeakerLinkCore

/// Shorthand for test devices.
func device(
    _ uid: String,
    _ name: String,
    _ transport: AudioTransport,
    rate: Double = 48_000,
    channels: Int = 2,
    hidden: Bool = false,
    volume: Double? = 0.5
) -> OutputDevice {
    OutputDevice(
        objectID: UInt32(abs(uid.hashValue % 10_000)), uid: uid, name: name, transport: transport,
        sampleRate: rate, outputChannels: channels, isHidden: hidden, canBeDefault: true, volume: volume
    )
}

@Suite("Four-character codes and transports")
struct TransportTests {
    @Test func encodesAndDecodesFourCharacterCodes() {
        #expect(FourCC.code("blue") == 0x626C_7565)
        #expect(FourCC.code("bltn") == 0x626C_746E)
        #expect(FourCC.code("usb") == FourCC.code("usb "))
        #expect(FourCC.string(from: 0x7768_6F3F) == "who?")
        #expect(FourCC.string(fromStatus: FourCC.status("!obj")) == "!obj")
        #expect(FourCC.string(fromStatus: -50) == nil)
        #expect(FourCC.string(from: 0) == nil)
    }

    @Test("Transport codes", arguments: [
        ("bltn", AudioTransport.builtIn), ("blue", .bluetooth), ("blea", .bluetoothLE), ("usb ", .usb),
        ("hdmi", .hdmi), ("dprt", .displayPort), ("airp", .airPlay), ("grup", .aggregate), ("fgrp", .aggregate),
        ("virt", .virtual), ("pci ", .pci), ("1394", .fireWire), ("thun", .thunderbolt), ("eavb", .avb),
        ("ccwd", .continuityCapture), ("ccwl", .continuityCapture),
    ])
    func mapsTransportCodes(_ example: (String, AudioTransport)) {
        let (code, transport) = example
        #expect(AudioTransport(code: FourCC.code(code)) == transport)
    }

    @Test func unknownAndRoundTrip() {
        #expect(AudioTransport(code: 0) == .unknown)
        #expect(AudioTransport(code: FourCC.code("zzzz")) == .unknown)
        for transport in AudioTransport.allCases where transport != .unknown {
            #expect(AudioTransport(code: transport.code) == transport)
        }
    }

    @Test func classifiesTransports() {
        #expect(AudioTransport.bluetooth.isBluetooth && AudioTransport.bluetoothLE.isBluetooth)
        #expect(!AudioTransport.usb.isBluetooth)
        #expect(AudioTransport.builtIn.isWired && AudioTransport.usb.isWired && AudioTransport.hdmi.isWired)
        #expect(!AudioTransport.bluetooth.isWired && !AudioTransport.airPlay.isWired && !AudioTransport.virtual.isWired)
    }
}

@Suite("Device kinds, lists and formatting")
struct DeviceListTests {
    @Test func bluetoothClassWinsOverTransport() {
        let speakerNamedLikeHeadphones = device("11-22-33-44-55-66:output", "Headphones Box", .bluetooth)
        let loudspeaker = BluetoothDeviceClass(major: 0x04, minor: 0x05)
        #expect(DeviceKind.resolve(for: speakerNamedLikeHeadphones, bluetoothClass: loudspeaker) == .speaker)
        #expect(DeviceKind.resolve(for: speakerNamedLikeHeadphones, bluetoothClass: nil) == .headphones)
    }

    @Test func transportBasedKinds() {
        #expect(DeviceKind.resolve(for: device("a", "MacBook Pro Speakers", .builtIn), bluetoothClass: nil) == .builtIn)
        #expect(DeviceKind.resolve(for: device("b", "External Headphones", .builtIn), bluetoothClass: nil) == .headphones)
        #expect(DeviceKind.resolve(for: device("c", "LG UltraFine", .displayPort), bluetoothClass: nil) == .display)
        #expect(DeviceKind.resolve(for: device("d", "Living Room", .airPlay), bluetoothClass: nil) == .airPlay)
        #expect(DeviceKind.resolve(for: device("e", "USB Audio", .usb), bluetoothClass: nil) == .other)
        #expect(DeviceKind.resolve(for: device("11-22-33-44-55-66:output", "Party Box", .bluetooth), bluetoothClass: nil) == .speaker)
        #expect(DeviceKind.resolve(for: device("11-22-33-44-55-67:output", "Jo's AirPods", .bluetooth), bluetoothClass: nil) == .headphones)
    }

    @Test func bluetoothAddressOnlyForBluetoothOutputs() {
        #expect(device("F3-A2-14-A9-1D-F8:output", "AirPods", .bluetooth).bluetoothAddress?.rawValue == "F3-A2-14-A9-1D-F8")
        #expect(device("F3-A2-14-A9-1D-F8:output", "Odd", .usb).bluetoothAddress == nil)
        #expect(device("BuiltInSpeakerDevice", "Speakers", .builtIn).bluetoothAddress == nil)
    }

    @Test func filtersOutputs() {
        let devices = [
            device("speakers", "MacBook Pro Speakers", .builtIn),
            device("mic", "MacBook Pro Microphone", .builtIn, channels: 0),
            device("hidden", "BlackHole 2ch 2", .virtual, hidden: true),
            device("user-multi", "Multi-Output Device", .aggregate),
            device(GroupUIDs.production.a, "SpeakerLink (2 devices)", .aggregate),
            device(GroupUIDs.production.b, "SpeakerLink (2 devices)", .virtual),
            device("11-11-11-11-11-11:output", "AirPods", .bluetooth),
            device("tv", "Living Room", .airPlay),
        ]
        let visible = DeviceListRules.visibleOutputs(devices)
        #expect(visible.map(\.uid) == ["11-11-11-11-11-11:output", "speakers", "tv"])
    }

    @Test func sortsBluetoothFirstThenBuiltInWiredVirtualAirPlay() {
        let devices = [
            device("air", "Kitchen", .airPlay),
            device("virt", "BlackHole 2ch", .virtual),
            device("usb", "Audio Interface", .usb),
            device("bt2", "Zed Speaker", .bluetooth),
            device("mac", "MacBook Pro Speakers", .builtIn),
            device("bt1", "AirPods", .bluetoothLE),
            device("hdmi", "Display", .hdmi),
        ]
        #expect(DeviceListRules.sorted(devices).map(\.uid) == ["bt1", "bt2", "mac", "usb", "hdmi", "virt", "air"])
    }

    @Test func sortsNamesNaturally() {
        let devices = [device("b", "Speaker 10", .bluetooth), device("a", "Speaker 2", .bluetooth)]
        #expect(DeviceListRules.sorted(devices).map(\.name) == ["Speaker 2", "Speaker 10"])
    }

    @Test func airPlayAndAggregatesCannotJoin() {
        #expect(!device("a", "TV", .airPlay).canJoinGroup)
        #expect(!device("b", "Multi", .aggregate).canJoinGroup)
        #expect(device("c", "AirPods", .bluetooth).canJoinGroup)
        #expect(device("d", "BlackHole", .virtual).canJoinGroup)
    }

    @Test func formatsSampleRatesAndSubtitles() {
        #expect(DeviceFormatting.sampleRate(48_000) == "48 kHz")
        #expect(DeviceFormatting.sampleRate(44_100) == "44.1 kHz")
        #expect(DeviceFormatting.sampleRate(96_000) == "96 kHz")
        #expect(DeviceFormatting.sampleRate(0) == "")
        #expect(DeviceFormatting.subtitle(for: device("a", "AirPods", .bluetooth, rate: 48_000)) == "Bluetooth · 48 kHz")
        #expect(DeviceFormatting.subtitle(for: device("b", "Speakers", .builtIn, rate: 0)) == "Built-in")
    }

    @Test func withVolumeKeepsEverythingElse() {
        let original = device("a", "AirPods", .bluetooth, volume: 0.2)
        let changed = original.withVolume(0.7)
        #expect(changed.volume == 0.7)
        #expect(changed.uid == original.uid && changed.objectID == original.objectID && changed.sampleRate == original.sampleRate)
    }
}

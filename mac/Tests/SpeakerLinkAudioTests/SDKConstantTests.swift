// SPDX-License-Identifier: GPL-3.0-or-later

import CoreAudio
import Testing
import SpeakerLinkAudio
import SpeakerLinkCore

/// SpeakerLinkCore spells Core Audio's keys and codes out as literals (it cannot import
/// CoreAudio). These tests pin every literal to the SDK constant it stands for. They need no
/// audio hardware and always run.
@Suite("Core literals match the macOS SDK")
struct SDKConstantTests {
    @Test func aggregateDescriptionKeys() {
        #expect(AggregateKeys.uid == kAudioAggregateDeviceUIDKey)
        #expect(AggregateKeys.name == kAudioAggregateDeviceNameKey)
        #expect(AggregateKeys.subDevices == kAudioAggregateDeviceSubDeviceListKey)
        #expect(AggregateKeys.mainSubDevice == kAudioAggregateDeviceMainSubDeviceKey)
        #expect(AggregateKeys.isStacked == kAudioAggregateDeviceIsStackedKey)
        #expect(AggregateKeys.isPrivate == kAudioAggregateDeviceIsPrivateKey)
        #expect(AggregateKeys.subDeviceUID == kAudioSubDeviceUIDKey)
        #expect(AggregateKeys.extraOutputLatency == kAudioSubDeviceExtraOutputLatencyKey)
        #expect(AggregateKeys.driftCompensation == kAudioSubDeviceDriftCompensationKey)
        #expect(AggregateKeys.driftCompensationQuality == kAudioSubDeviceDriftCompensationQualityKey)
        #expect(AggregateKeys.driftQualityHigh == Int(kAudioAggregateDriftCompensationHighQuality))
    }

    @Test func transportCodes() {
        #expect(AudioTransport.builtIn.code == kAudioDeviceTransportTypeBuiltIn)
        #expect(AudioTransport.bluetooth.code == kAudioDeviceTransportTypeBluetooth)
        #expect(AudioTransport.bluetoothLE.code == kAudioDeviceTransportTypeBluetoothLE)
        #expect(AudioTransport.usb.code == kAudioDeviceTransportTypeUSB)
        #expect(AudioTransport.hdmi.code == kAudioDeviceTransportTypeHDMI)
        #expect(AudioTransport.displayPort.code == kAudioDeviceTransportTypeDisplayPort)
        #expect(AudioTransport.airPlay.code == kAudioDeviceTransportTypeAirPlay)
        #expect(AudioTransport.aggregate.code == kAudioDeviceTransportTypeAggregate)
        #expect(AudioTransport.virtual.code == kAudioDeviceTransportTypeVirtual)
        #expect(AudioTransport.pci.code == kAudioDeviceTransportTypePCI)
        #expect(AudioTransport.fireWire.code == kAudioDeviceTransportTypeFireWire)
        #expect(AudioTransport.thunderbolt.code == kAudioDeviceTransportTypeThunderbolt)
        #expect(AudioTransport.avb.code == kAudioDeviceTransportTypeAVB)
        #expect(AudioTransport.continuityCapture.code == kAudioDeviceTransportTypeContinuityCaptureWired)
        #expect(AudioTransport(code: kAudioDeviceTransportTypeContinuityCaptureWireless) == .continuityCapture)
        #expect(AudioTransport(code: kAudioDeviceTransportTypeUnknown) == .unknown)
    }

    @Test func statusCodes() {
        #expect(FourCC.status("who?") == kAudioHardwareUnknownPropertyError)
        #expect(FourCC.status("!obj") == kAudioHardwareBadObjectError)
        #expect(FourCC.status("!dev") == kAudioHardwareBadDeviceError)
        #expect(FourCC.status("nope") == kAudioHardwareIllegalOperationError)
        #expect(FourCC.status("unop") == kAudioHardwareUnsupportedOperationError)
        #expect(FourCC.status("!hog") == kAudioDevicePermissionsError)
        #expect(FourCC.status("stop") == kAudioHardwareNotRunningError)
        #expect(FourCC.status("!siz") == kAudioHardwareBadPropertySizeError)
        #expect(FourCC.status("!str") == kAudioHardwareBadStreamError)
        #expect(FourCC.status("what") == kAudioHardwareUnspecifiedError)
        #expect(FourCC.status("nrdy") == kAudioHardwareNotReadyError)
        #expect(FourCC.status("!dat") == kAudioDeviceUnsupportedFormatError)
    }

    @Test func groupUIDsAreDistinctFromTestUIDs() {
        #expect(!GroupUIDs.production.isOurs(IntegrationSupport.testUIDPrefix + "x"))
    }
}

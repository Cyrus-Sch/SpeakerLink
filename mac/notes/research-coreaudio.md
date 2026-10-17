# SUMMARY
I checked the facts below in primary sources where I could. Most come from the real macOS 26.1 SDK (sha256 matched the mirror's published checksum), which I unpacked and grepped. I compared it with the 15.5 SDK and with pyobjc's metadata generated from SDK 26.5. I also read five open-source Swift multi-output apps line by line, plus cubeb, jack2 and mpv. The macOS 27 SDK headers were not available to me. support.apple.com, the JUCE forum and MacRumors were unreachable from this sandbox.

KEYS for AudioHardwareCreateAggregateDevice (AudioHardware.h, identical in 15.5, 26.1 and 26.5):
- Swift imports each key as `var <name>: String { get }`.
- kAudioAggregateDeviceUIDKey = "uid"
- kAudioAggregateDeviceNameKey = "name"
- kAudioAggregateDeviceSubDeviceListKey = "subdevices" (a CFArray of dictionaries)
- kAudioAggregateDeviceMainSubDeviceKey = "master". The old name kAudioAggregateDeviceMasterSubDeviceKey ("master") still exists as a #define in AudioHardwareDeprecated.h, so it gives no deprecation warning.
- kAudioAggregateDeviceClockDeviceKey = "clock". This takes the UID of an AudioClockDevice, not of an ordinary device. If both are set, the clock device wins.
- kAudioAggregateDeviceIsPrivateKey = "private" (CFNumber; 0 or absent means public, 1 means private)
- kAudioAggregateDeviceIsStackedKey = "stacked". Use 1 for a Multi-Output Device. The header comment wrongly says 0. All real code uses 1: macos-audio-devices' --multi-output flag, AudioShare, PairPods and BluetoothTool.
- Tap keys: kAudioAggregateDeviceTapListKey = "taps", kAudioAggregateDeviceTapAutoStartKey = "tapautostart".

Per-sub-device keys:
- kAudioSubDeviceUIDKey = "uid"
- kAudioSubDeviceNameKey = "name"
- kAudioSubDeviceInputChannelsKey = "channels-in"
- kAudioSubDeviceOutputChannelsKey = "channels-out"
- kAudioSubDeviceExtraInputLatencyKey = "latency-in" (CFNumber, frames)
- kAudioSubDeviceExtraOutputLatencyKey = "latency-out" (CFNumber, frames)
- kAudioSubDeviceDriftCompensationKey = "drift" (non-zero turns it on)
- kAudioSubDeviceDriftCompensationQualityKey = "drift quality"
- Quality values: kAudioAggregateDriftCompensation{Min=0, Low=0x20, Medium=0x40, High=0x60, Max=0x7F}Quality (UInt32, macOS 13+ names). The old kAudioSubDeviceDriftCompensation*Quality names are marked API_DEPRECATED_WITH_REPLACEMENT(macos 13, API_TO_BE_DEPRECATED).

Functions:
- AudioHardwareCreateAggregateDevice(CFDictionaryRef, AudioObjectID*), macOS 10.9+.
- AudioHardwareDestroyAggregateDevice(AudioObjectID), macOS 10.9+. Per the header, destruction is asynchronous.

Aggregate properties:
- FullSubDeviceList = 'grup' (CFArray of UIDs)
- ActiveSubDeviceList = 'agrp'
- Composition = 'acom' (CFDictionary, caller releases)
- MainSubDevice = 'amst'. The old name kAudioAggregateDevicePropertyMasterSubDevice was deprecated in macOS 12.
- ClockDevice = 'apcd'
- TapList = 'tap#', SubTapList = 'atap'

AudioSubDevice class 'asub' properties:
- ExtraLatency = 'xltc' (Float64, sample frames)
- DriftCompensation = 'drft' (UInt32 0 or 1)
- DriftCompensationQuality = 'drfq'

Other renames: kAudioObjectPropertyElementMaster became kAudioObjectPropertyElementMain (= 0) in macOS 12, and kAudioHardwareServiceDeviceProperty_VirtualMasterVolume became VirtualMainVolume ('vmvc').

System object selectors:
- Devices = 'dev#', DefaultInputDevice = 'dIn ', DefaultOutputDevice = 'dOut', DefaultSystemOutputDevice = 'sOut' (alerts), TranslateUIDToDevice = 'uidd'.

Device selectors:
- DeviceUID = 'uid ', ModelUID = 'muid', TransportType = 'tran', ClockDomain = 'clkd', DeviceIsAlive = 'livn'
- DeviceCanBeDefaultDevice = 'dflt', DeviceCanBeDefaultSystemDevice = 'sflt'
- Latency = 'ltnc' (UInt32 frames; use output scope), SafetyOffset = 'saft', BufferFrameSize = 'fsiz'
- Streams = 'stm#', NominalSampleRate = 'nsrt' (Float64), AvailableNominalSampleRates = 'nsr#', IsHidden = 'hidn'
- VolumeScalar = 'volm' (Float32 0–1), Mute = 'mute'
- kAudioStreamPropertyLatency is the same code as kAudioDevicePropertyLatency.
- kAudioObjectPropertyOwnedObjects = 'ownd'
- kAudioObjectSystemObject = 1. It is CF_ENUM(int), so Swift needs AudioObjectID(kAudioObjectSystemObject).

Transport types (UInt32): Unknown = 0, BuiltIn 'bltn', Aggregate 'grup', Virtual 'virt', PCI 'pci ', USB 'usb ', FireWire '1394', Bluetooth 'blue', BluetoothLE 'blea', HDMI 'hdmi', DisplayPort 'dprt', AirPlay 'airp', AVB 'eavb', Thunderbolt 'thun', ContinuityCaptureWired 'ccwd', ContinuityCaptureWireless 'ccwl'. AutoAggregate 'fgrp' is deprecated.

Error codes: kAudioHardwareUnknownPropertyError = 'who?', IllegalOperation = 'nope', BadObject = '!obj', UnsupportedOperation = 'unop', Unspecified = 'what'.

Listeners: AudioObjectAddPropertyListenerBlock(objID, &addr, queue, block), macOS 10.7+. The queue is retained and the block is copied until AudioObjectRemovePropertyListenerBlock is called with the same block object.

Native Swift API: the SDK also has one, available macOS 15.0+. It was already in the 15.5 SDK, and 26.1 adds @unchecked Sendable. It includes:
- AudioHardwareSystem.shared, with makeAggregateDevice(description: [String: Any]) -> AudioHardwareAggregateDevice?, destroyAggregateDevice, setDefaultOutputDevice, setDefaultSoundEffectsDevice and device(forUID:)
- AudioHardwareDevice/AudioHardwareClock, with outputLatency, outputSafetyOffset, bufferFrameSize, nominalSampleRate and canBeDefaultSoundEffectsDevice
- AudioHardwareAggregateDevice, with composition/setComposition, setSubdevices and setClockSource
There is no volume helper, so a macOS 13 or 14 deployment target needs the C API.

BEHAVIOUR:
- Private vs public: a private aggregate belongs to the creating process and does not survive relaunch (header). It is hidden from other apps and cannot be the system default. So "listen together" for Safari, QuickTime or Netflix needs a public aggregate (private = 0) set as kAudioHardwarePropertyDefaultOutputDevice.
- Public aggregates outlive a crash of the app that made them. AudioShare, PairPods and BluetoothTool all delete leftovers by their fixed UID at launch.
- A freshly created aggregate may not take as the default straight away. Retry the set: AudioShare does 20 tries 50 ms apart, BluetoothTool 8 tries 150 ms apart. jack2 runs the run loop for 0.1 s after creating ("new aggregate device seems to disappear briefly after it is created").
- When stopping, set the default output back to the previous device first, then destroy the aggregate. Destruction is asynchronous, so wait until the UID is gone before recreating it under the same UID.
- Drift: use a wired or built-in device as the main ("master") device when there is one. Turn drift on for every other member, and for every Bluetooth member.
- Sample rate: forcing rate changes on Bluetooth devices made AirPods 4 cut out (PairPods 0.5.1). PairPods now leaves rates alone, picks the main device by highest or majority rate, and lets drift compensation handle mismatches. AudioShare instead sets the aggregate's nominal rate to one every member supports (48k, 44.1k, 96k or 88.2k). Don't mix input into the aggregate: Bluetooth output devices have their own UID ("AA-BB-CC-DD-EE-FF:output"), and using a shared AirPods microphone drops the link to a low-quality mode.
- Latency: a stacked aggregate does not line up its members by itself. Bluetooth plays about 150–250 ms behind wired outputs.
- Per-device delay: BluetoothTool reports that "latency-out" (frames at that device's sample rate) is accepted when the device is created. It also reports that kAudioSubDevicePropertyExtraLatency returns 'who?' on Bluetooth sub-devices, so you cannot change it while running. Changing a delay means destroy and recreate, then read Composition back to confirm. This is one source, not tested by me. Tutti, which is closed source, ships per-device delay on macOS 13+ with no driver.
- Latency figures for auto-sync: a device's latency in frames is Latency + SafetyOffset + output stream latency, plus BufferFrameSize (mpv) when timing your own IO. Divide by that device's NominalSampleRate. Use only the differences between devices. Bluetooth reported values do not match measured delay and drift over time (Apple forum thread 679274, BluetoothTool). Offer a manual slider.
- Volume: a multi-output device has no volume or mute control, so the menu-bar slider and volume keys do nothing for it. Set kAudioDevicePropertyVolumeScalar or Mute on each member in output scope. Try element 0 (main) first; AirPods expose only elements 1 and 2. Check AudioObjectIsPropertySettable before setting. To handle the volume keys, an NSEvent global/local monitor for .systemDefined, subtype 8, catches NX keys 0 (up), 1 (down) and 7 (mute). AudioShare says this needs no permission.
- Change listeners to register: on the system object, kAudioHardwarePropertyDevices and kAudioHardwarePropertyDefaultOutputDevice. On each member, VolumeScalar and DeviceIsAlive. Leave out your own aggregate (transport 'grup' or your UID) and hidden devices.
- Process-tap fallback: if a delay engine or a real master volume is needed, use AudioHardwareCreateProcessTap (macOS 14.2+), a private aggregate per output, and NSAudioCaptureUsageDescription (needs user permission).

# FACTS
- [verified] Aggregate composition keys and literal values in AudioHardware.h, macOS 26.1 SDK (lines 1566–1645): kAudioAggregateDeviceUIDKey="uid", NameKey="name", SubDeviceListKey="subdevices", MainSubDeviceKey="master", ClockDeviceKey="clock", IsPrivateKey="private", IsStackedKey="stacked", TapListKey="taps", TapAutoStartKey="tapautostart". The aggregate-constants section is byte-identical in the 15.5 SDK. pyobjc's 26.5 metadata adds no new aggregate or sub-device names.  (MacOSX26.1.sdk/System/Library/Frameworks/CoreAudio.framework/Headers/AudioHardware.h (from https://github.com/joseluisq/macosx-sdks/releases/download/26.1/MacOSX26.1.sdk.tar.xz, sha256 beee7212...784c); MacOSX15.5.sdk AudioHardware.h (same mirror, sha256 c15cf0f3...1cbd); https://github.com/ronaldoussoren/pyobjc/blob/main/pyobjc-framework-CoreAudio/metadata/raw/arm64-26.5.fwinfo)
- [verified] Per-sub-device keys: kAudioSubDeviceUIDKey="uid", kAudioSubDeviceNameKey="name", InputChannelsKey="channels-in", OutputChannelsKey="channels-out", ExtraInputLatencyKey="latency-in", ExtraOutputLatencyKey="latency-out" (CFNumber, frames of extra latency), DriftCompensationKey="drift" (non-zero = on), DriftCompensationQualityKey="drift quality". Sub-tap keys reuse the same strings: kAudioSubTapUIDKey, ExtraInput/OutputLatencyKey, DriftCompensation(Quality)Key.  (MacOSX26.1.sdk AudioHardware.h lines 1756–1900)
- [verified] Master to Main renames. kAudioAggregateDeviceMasterSubDeviceKey ("master") is now only a #define in AudioHardwareDeprecated.h, marked 'deprecated synonym', with no availability macro, so Swift gives no warning. kAudioAggregateDevicePropertyMasterSubDevice: API_DEPRECATED_WITH_REPLACEMENT(kAudioAggregateDevicePropertyMainSubDevice, macos(10.0,12.0)). kAudioObjectPropertyElementMaster became kAudioObjectPropertyElementMain (=0) in macOS 12. kAudioHardwarePropertyProcessIsMaster ('mast') became ProcessIsMain. kAudioHardwareServiceDeviceProperty_VirtualMasterVolume/Balance became VirtualMainVolume ('vmvc') / VirtualMainBalance ('vmbc'). kAudioSubDeviceDriftCompensation{Min,Low,Medium,High,Max}Quality became kAudioAggregateDriftCompensation{...}Quality (0, 0x20, 0x40, 0x60, 0x7F), deprecated macos(13.0, API_TO_BE_DEPRECATED).  (MacOSX26.1.sdk AudioHardwareDeprecated.h lines 213–221, 918–940; MacOSX26.1.sdk AudioHardwareBase.h lines 195–208; MacOSX26.1.sdk AudioHardware.h drift-compensation enums)
- [verified] Swift import types. The #define string keys import as `var kAudioAggregateDeviceIsStackedKey: String { get }` (and likewise for all the others), so they can be used directly as [String: Any] keys. Property selectors import as AudioObjectPropertySelector, transport types as UInt32, error codes as OSStatus, and kAudioObjectUnknown as AudioObjectID. kAudioObjectSystemObject is CF_ENUM(int) and needs AudioObjectID(kAudioObjectSystemObject).  (https://github.com/Averyy/apple-dev-docs/blob/main/documentation/CoreAudio/kaudioaggregatedeviceisstackedkey.md; https://github.com/DougGregor/swift-concurrency-objc/blob/main/macOS/CoreAudio/AudioHardware.swift; MacOSX26.1.sdk AudioHardware.h line 108 (CF_ENUM(int) kAudioObjectSystemObject = 1))
- [likely] The header's text for kAudioAggregateDeviceIsStackedKey says 'a value of 0 means ... the output streams are all fed the same data'. This is misleading. Every working multi-output implementation passes 1, and macos-audio-devices maps its --multi-output flag to 1 (0 otherwise). cubeb and AudioCap pass 0 for ordinary (non-stacked) private aggregates.  (MacOSX26.1.sdk AudioHardware.h line 1616–1623; https://github.com/karaggeorge/macos-audio-devices/blob/master/Sources/audio-devices/AudioDevices.swift (createAggregate, shouldStack ? 1 : 0); https://github.com/2geik/AudioShare/blob/main/Sources/AudioShare/Audio/MultiOutputDevice.swift)
- [verified] AudioHardwareCreateAggregateDevice(CFDictionaryRef inDescription, AudioObjectID* outDeviceID) and AudioHardwareDestroyAggregateDevice(AudioObjectID) are both __OSX_AVAILABLE_STARTING(__MAC_10_9). Destruction 'is asynchronous and may take place after the call to this routine has returned'. The older kAudioPlugInCreateAggregateDevice route via kAudioHardwarePropertyPlugInForBundleID 'com.apple.audio.CoreAudio' (used by cubeb and jack2) is not needed on 10.9+.  (MacOSX26.1.sdk AudioHardware.h lines 656–680; https://developer.apple.com/forums/thread/54242)
- [likely] Private aggregates ("private"=1) belong to the creating process and do not persist across launches (header text). They are hidden from other apps and cannot be the system default. To have Safari, QuickTime or Netflix play to the multi-output device, it must be public ("private"=0 or key absent) and set as kAudioHardwarePropertyDefaultOutputDevice.  (MacOSX26.1.sdk AudioHardware.h kAudioAggregateDeviceIsPrivateKey discussion (verified part: private to the process, not persistent); https://github.com/alileza/bypass (README: 'a private aggregate can't be the system default and is hidden from apps like Ableton'); https://github.com/insidegui/AudioCap (README: private so 'it doesn't show up globally'))
- [likely] Public aggregates created with the API are not cleaned up when the creating app crashes. AudioShare, PairPods and BluetoothTool all look up their fixed aggregate UID at launch and destroy or adopt any leftover. Whether a public aggregate also survives a reboot was not confirmed.  (https://github.com/2geik/AudioShare/blob/main/README.md ('After a crash, the next launch cleans up the leftover device'); https://github.com/wozniakpawel/PairPods/blob/main/PairPods/AudioDeviceManager.swift (removeMultiOutputDevice in init); https://github.com/gregparent21/BluetoothTool/blob/main/Sources/BluetoothTool/MultiOutputDevice.swift (adoptOrphanedDevice))
- [likely] Default output is set with AudioObjectSetPropertyData(kAudioObjectSystemObject, {kAudioHardwarePropertyDefaultOutputDevice 'dOut', global, main}, AudioDeviceID). A just-created multi-output device may not be accepted straight away, so set it, read it back and retry (AudioShare: 20 tries at 50 ms; BluetoothTool: 8 tries at 150 ms). jack2 runs CFRunLoopRunInMode(0.1 s) after creating because 'a new aggregate device seems to disappear briefly after it is created'.  (https://github.com/2geik/AudioShare/blob/main/Sources/AudioShare/AudioShareController.swift (route(to:)); https://github.com/gregparent21/BluetoothTool (AudioDevices.swift setDefaultOutputVerified); https://github.com/jackaudio/jack2/blob/develop/macosx/coreaudio/JackCoreAudioDriver.mm line 1121)
- [likely] Restore order on stop: set the default output back to the previous device (saved by UID) before destroying the aggregate, 'so macOS doesn't pick one at random'. Recreating under the same UID before coreaudiod has published the removal 'fails or hands back the dying device', so poll until the UID is gone (about 1 s at most).  (https://github.com/2geik/AudioShare/blob/main/Sources/AudioShare/AudioShareController.swift (stopSharing); https://github.com/gregparent21/BluetoothTool/blob/main/Sources/BluetoothTool/MultiOutputDevice.swift (waitForRemoval))
- [uncertain] System (alert) output is kAudioHardwarePropertyDefaultSystemOutputDevice = 'sOut'. A device can be chosen for it only if kAudioDevicePropertyDeviceCanBeDefaultSystemDevice ('sflt') = 1 (macOS 15+ Swift: canBeDefaultSoundEffectsDevice / setDefaultSoundEffectsDevice). I found no source saying whether a stacked aggregate reports sflt = 1, so read it at runtime and leave 'sOut' alone unless it does.  (MacOSX26.1.sdk AudioHardware.h line 478–480, 611; MacOSX26.1.sdk AudioHardwareBase.h lines 683–688, 741–742; MacOSX26.1.sdk usr/lib/swift/CoreAudio.swiftmodule/arm64e-apple-macos.swiftinterface)
- [verified] Sub-device properties: kAudioSubDevicePropertyExtraLatency='xltc' (Float64 'number of sample frames to add to or subtract from the latency compensation'), DriftCompensation='drft' (UInt32 0/1), DriftCompensationQuality='drfq' (0..128 in the doc; enum max is 0x7F). They live on AudioSubDevice objects (class 'asub'), found with kAudioObjectPropertyOwnedObjects ('ownd') on the aggregate and qualifier kAudioSubDeviceClassID. This is how cubeb and jack2 turn on drift after creating the aggregate.  (MacOSX26.1.sdk AudioHardware.h lines 1816–1846; https://github.com/mozilla/cubeb/blob/master/src/cubeb_audiounit.cpp (audiounit_activate_clock_drift_compensation); https://github.com/jackaudio/jack2/blob/develop/macosx/coreaudio/JackCoreAudioDriver.mm lines 1162–1200)
- [uncertain] In a stacked aggregate, "latency-out" (kAudioSubDeviceExtraOutputLatencyKey, in frames at that device's nominal rate) is accepted when the device is created and works as a per-device delay. kAudioSubDevicePropertyExtraLatency on Bluetooth sub-devices returns 'who?' (kAudioHardwareUnknownPropertyError), so the delay can't be changed while running. Changing it means destroy and recreate (about a 1 s gap), then read kAudioAggregateDevicePropertyComposition back to confirm. One third-party, unlicensed source; Tutti (closed source, macOS 13+, no driver) also ships a per-device delay.  (https://github.com/gregparent21/BluetoothTool/blob/main/README.md; https://github.com/gregparent21/BluetoothTool/blob/main/Sources/BluetoothTool/MultiOutputDevice.swift; https://github.com/BarryBarrywu/tutti (README))
- [likely] A stacked aggregate does not line up its members by reported latency. Bluetooth headphones play about 150–250 ms behind wired or built-in outputs, and adding delay can only hold the faster outputs back. Two Bluetooth headphones of similar type stay roughly in step.  (https://github.com/2geik/AudioShare/blob/main/README.md (Known limitations); https://github.com/gregparent21/BluetoothTool/blob/main/README.md; https://github.com/BarryBarrywu/tutti (Bluetooth synchronization limits))
- [uncertain] Composition can be read (CFDictionary, caller releases). The macOS 15+ Swift API exposes AudioHardwareAggregateDevice.setComposition(_:), setSubdevices(_:) and setClockSource(_:), so the property can be set there. Whether setting a composition with new "latency-out" values takes effect live is untested.  (MacOSX26.1.sdk usr/lib/swift/CoreAudio.swiftmodule/arm64e-apple-macos.swiftinterface (AudioHardwareAggregateDevice); MacOSX26.1.sdk AudioHardware.h kAudioAggregateDevicePropertyComposition 'acom')
- [likely] Drift: choose a wired or built-in member as the main ('master') device when there is one, and set "drift"=1 on every other member, including all Bluetooth devices. kAudioAggregateDeviceClockDeviceKey/'apcd' is for AudioClockDevice objects; setting ClockDevice 'will enable drift correction for all subdevices'. Apple's AMS guide: drift correction (resampling) is needed for devices not synced in hardware. kAudioDevicePropertyClockDomain ('clkd') 0 means an unknown/separate clock domain (jack2 then turns drift compensation on).  (MacOSX26.1.sdk AudioHardware.h lines 1594–1602, 1670–1676; MacOSX26.1.sdk AudioHardwareBase.h kAudioDevicePropertyClockDomain; https://github.com/ExistentialAudio/BlackHole/wiki/Multi-Output-Device ('enable Drift Correction for all devices except the Clock Source device'))
- [likely] Sample-rate mismatch. PairPods 0.5.1 (2026-03-02) fixed 'audio cutting out after a few seconds on some Bluetooth devices (e.g. AirPods 4) when sharing with a device at a different sample rate' by no longer forcing rate changes on Bluetooth devices ('CoreAudio drift compensation handles rate differences more reliably'). It picks the main device by highest or majority sample rate. AudioShare instead sets the aggregate's nominal rate to the first of 48000/44100/96000/88200 that all members support.  (https://github.com/wozniakpawel/PairPods/blob/main/CHANGELOG.md; https://github.com/2geik/AudioShare/blob/main/Sources/AudioShare/Audio/MultiOutputDevice.swift (commonSampleRate))
- [likely] Bluetooth audio appears as separate Core Audio devices. Output UIDs look like "AA-BB-CC-DD-EE-FF:output", and the prefix is the Bluetooth address for matching IOBluetooth. Use only the output device. If a shared AirPods microphone is opened (a call, for example), the link drops to a lower-quality Bluetooth mode. cubeb has an AirPods duplex workaround that sets the aggregate rate to the input rate.  (https://github.com/2geik/AudioShare/blob/main/Sources/AudioShare/Audio/AudioSystem.swift (bluetoothAddress); https://github.com/2geik/AudioShare/blob/main/README.md; https://github.com/mozilla/cubeb/blob/master/src/cubeb_audiounit.cpp (audiounit_workaround_for_airpod))
- [verified] Latency properties. kAudioDevicePropertyLatency 'ltnc' is a UInt32 frame count and differs by scope (use output scope). kAudioStreamPropertyLatency (same 'ltnc', on each AudioStream from kAudioDevicePropertyStreams 'stm#') is added to it. kAudioDevicePropertySafetyOffset 'saft' (UInt32 frames). kAudioDevicePropertyBufferFrameSize 'fsiz' (UInt32 frames). mpv sums Latency + BufferFrameSize + SafetyOffset (output scope) and divides by NominalSampleRate. jack2 adds buffer + latency + safety + stream latency. Players that time against callback timestamps leave out safety and buffer to avoid counting them twice.  (MacOSX26.1.sdk AudioHardwareBase.h lines 689–694, 709–711, 1091–1096; MacOSX26.1.sdk AudioHardware.h line 945–946; https://github.com/mpv-player/mpv/blob/master/audio/out/ao_coreaudio_utils.c (ca_get_device_latency_ns))
- [likely] Bluetooth reported latency is not reliable for auto-sync. AirPods measured about 215–220 ms falling over time while the system reported about 160 ms (iOS). BluetoothTool says macOS 'does not report a speaker's true output latency'. A process tap behind AirPods saw 300–400 ms, and an Apple engineer said that is expected when the Bluetooth device is in the same aggregate. I found no published per-property frame values for AirPods on macOS. Use the reported values only as a starting estimate and offer a manual slider.  (https://developer.apple.com/forums/thread/679274; https://developer.apple.com/forums/thread/770218; https://github.com/gregparent21/BluetoothTool/blob/main/README.md)
- [likely] macOS 27.0 with AirPods Pro 3 delivered 256-frame IO slices when 240 were requested (kAudioUnitErr_TooManyFramesToProcess, silent output in cubeb C++). Don't assume the buffer size you requested is the one in use.  (https://github.com/mozilla/cubeb-coreaudio-rs/issues/290)
- [likely] Volume. kAudioDevicePropertyVolumeScalar 'volm' (Float32 0–1) and kAudioDevicePropertyMute 'mute' (UInt32) are set per member in kAudioObjectPropertyScopeOutput. Built-in speakers expose element 0 (main); AirPods expose no main element but elements 1 and 2. Check with AudioObjectHasProperty/AudioObjectIsPropertySettable. On Bluetooth the value is sent as AVRCP absolute volume. kAudioHardwareServiceDeviceProperty_VirtualMainVolume 'vmvc' works only through AudioHardwareService*PropertyData, which has been API_DEPRECATED since macOS 10.11.  (MacOSX26.1.sdk AudioHardware.h lines 1027–1035, 1064–1067, 1300–1307; MacOSX26.1.sdk AudioToolbox AudioHardwareService.h; https://github.com/2geik/AudioShare/blob/main/Sources/AudioShare/Audio/AudioSystem.swift (outputElements))
- [likely] System volume does not work on multi-output devices: 'macOS does not support changing the volume of a Multi-Output device'. The Control Center/menu-bar slider is unavailable and the volume keys show a disabled HUD, because the aggregate has no volume control. Workaround: watch the volume keys with NSEvent.addGlobalMonitorForEvents(matching: .systemDefined) plus a local monitor (subtype 8; key code (data1 & 0xFFFF0000) >> 16: 0 = up, 1 = down, 7 = mute; key-down when (data1 & 0xFF00) >> 8 == 0xA) and step each member's volume. AudioShare says this needs no permission and does not suppress the system HUD.  (https://github.com/ExistentialAudio/BlackHole/wiki/Multi-Output-Device; https://github.com/2geik/AudioShare/blob/main/README.md; https://github.com/2geik/AudioShare/blob/main/Sources/AudioShare/Support/VolumeKeyMonitor.swift)
- [verified] Listener API: AudioObjectAddPropertyListenerBlock(AudioObjectID, const AudioObjectPropertyAddress*, dispatch_queue_t, AudioObjectPropertyListenerBlock), macOS 10.7+. The queue is retained and the block is Block_copy'd until a matching AudioObjectRemovePropertyListenerBlock with the same block. Blocks run asynchronously on the queue, except IO-context ones (DeviceIsRunning, ProcessorOverload). Store the closure to remove it later; PairPods fixed listener build-up and a use-after-free in exactly this code.  (MacOSX26.1.sdk AudioHardware.h lines 374–421; https://github.com/wozniakpawel/PairPods/blob/main/CHANGELOG.md (0.4.1))
- [verified] Transport type four-char codes (CF_ENUM(UInt32)): Unknown=0, BuiltIn='bltn', Aggregate='grup', Virtual='virt', PCI='pci ', USB='usb ', FireWire='1394', Bluetooth='blue', BluetoothLE='blea', HDMI='hdmi', DisplayPort='dprt', AirPlay='airp', AVB='eavb', Thunderbolt='thun', ContinuityCaptureWired='ccwd', ContinuityCaptureWireless='ccwl'. ContinuityCapture='ccap' is deprecated (13.0). AutoAggregate='fgrp' is in AudioHardwareDeprecated.h.  (MacOSX26.1.sdk AudioHardwareBase.h lines 607–626; MacOSX26.1.sdk AudioHardwareDeprecated.h line 471)
- [verified] System and device selector codes: Devices='dev#', DefaultInputDevice='dIn ', DefaultOutputDevice='dOut', DefaultSystemOutputDevice='sOut', TranslateUIDToDevice='uidd' (the old DeviceForUID='duid' is in the deprecated header), DeviceUID='uid ', ModelUID='muid', TransportType='tran', ClockDomain='clkd', DeviceIsAlive='livn', DeviceIsRunning='goin', DeviceIsRunningSomewhere='gone', DeviceCanBeDefaultDevice='dflt', DeviceCanBeDefaultSystemDevice='sflt', NominalSampleRate='nsrt', AvailableNominalSampleRates='nsr#', IsHidden='hidn', Name='lnam', OwnedObjects='ownd', StreamConfiguration='slay'. Errors: UnknownProperty='who?', IllegalOperation='nope', BadObject='!obj', UnsupportedOperation='unop', Unspecified='what', NotRunning='stop', Permissions='!hog'.  (MacOSX26.1.sdk AudioHardware.h lines 608–612, 1000–1008; MacOSX26.1.sdk AudioHardwareBase.h lines 152–166, 334–340, 732–752)
- [verified] A native Swift Core Audio API is available on macOS 15.0+. It was in the 15.5 SDK, and the 26.1 SDK adds @unchecked Sendable. Classes: AudioHardwareSystem.shared (makeAggregateDevice(description: [String: Any]) throws -> AudioHardwareAggregateDevice?, destroyAggregateDevice(_:), defaultOutputDevice, setDefaultOutputDevice(_:), setDefaultSoundEffectsDevice(_:), device(forUID:), makeProcessTap(description:)), AudioHardwareDevice (outputSafetyOffset, bufferFrameSize, canBeDefaultSoundEffectsDevice), AudioHardwareClock (uid, transportType, outputLatency, nominalSampleRate, setNominalSampleRate), and AudioHardwareAggregateDevice (subdevices, activeSubdevices, clockSource, composition, setSubdevices, setClockSource, setComposition). There are no volume helpers, so a macOS 13 or 14 target must use the C API.  (MacOSX26.1.sdk usr/lib/swift/CoreAudio.swiftmodule/arm64e-apple-macos.swiftinterface (swift-compiler-version 6.2.1); MacOSX15.5.sdk usr/lib/swift/CoreAudio.swiftmodule/arm64e-apple-macos.swiftinterface (swift 6.1.2))
- [verified] Process-tap fallback (macOS 14.2+): AudioHardwareCreateProcessTap/AudioHardwareDestroyProcessTap are API_AVAILABLE(macos(14.2)). CATapDescription has initStereoGlobalTapButExcludeProcesses, muteBehavior CATapUnmuted=0, CATapMuted=1 and CATapMutedWhenTapped=2, and a privateTap property. It needs the NSAudioCaptureUsageDescription Info.plist key and user permission; there is no public API to check or request that permission. Use this only if the multi-output path can't do per-device delay or a master volume. AudioShare and BluetoothTool avoid it on purpose.  (MacOSX26.1.sdk CoreAudio.framework/Headers/AudioHardwareTapping.h, CATapDescription.h; https://github.com/insidegui/AudioCap (README))
- [likely] AudioHardwareCreateAggregateDevice works in an App Sandbox. PairPods ships sandboxed (com.apple.security.app-sandbox + com.apple.security.device.bluetooth) and creates and sets the public stacked aggregate. A non-sandboxed GitHub-distributed app has no extra Core Audio entitlement needs.  (https://github.com/wozniakpawel/PairPods/blob/main/PairPods/PairPods.entitlements; https://github.com/wozniakpawel/PairPods/blob/main/PairPods/CoreAudioSystem.swift)
- [verified] Open-source references:
- 2geik/AudioShare (MIT, SwiftPM, swift-tools 6.2, macOS 14+): Sources/AudioShare/Audio/MultiOutputDevice.swift, Audio/AudioSystem.swift, AudioShareController.swift, Support/VolumeKeyMonitor.swift. The closest match to SpeakerLink.
- wozniakpawel/PairPods (MIT, Xcode project, macOS 13.5+): PairPods/CoreAudioSystem.swift, AudioDeviceManager.swift, AudioVolumeManager.swift, CHANGELOG.md.
- gregparent21/BluetoothTool (SwiftPM, macOS 14+, NO LICENSE file, read only): MultiOutputDevice.swift with "latency-out" delay.
- karaggeorge/macos-audio-devices (Swift CLI): AudioDevices.swift createAggregate.
- sbooth/CAAudioHardware (MIT): AudioAggregateDevice.swift.
- orchetect/swift-core-audio (MIT, pre-1.0): aggregate composition model with latency keys.
- mozilla/cubeb src/cubeb_audiounit.cpp and jackaudio/jack2 macosx/coreaudio/JackCoreAudioDriver.mm: C/C++ aggregate creation, drift and HAL workarounds.
- ronitsingh10/FineTune (GPL-3.0, macOS 15+): multi-device routing, implementation not checked.  (https://github.com/2geik/AudioShare; https://github.com/wozniakpawel/PairPods; https://github.com/gregparent21/BluetoothTool)
- [likely] Device-list hygiene: build the output list from kAudioHardwarePropertyDevices. Keep devices that have output streams (kAudioDevicePropertyStreams in output scope, size > 0) and IsHidden != 1, and drop TransportType == kAudioDeviceTransportTypeAggregate (our own and the user's). Bluetooth devices exist only while connected, so rebuild the aggregate when members come or go. If the default output changes to something else (user choice, or macOS auto-switching to newly connected headphones), stop sharing or re-route.  (https://github.com/2geik/AudioShare/blob/main/Sources/AudioShare/Audio/AudioSystem.swift (outputs()); https://github.com/2geik/AudioShare/blob/main/Sources/AudioShare/AudioShareController.swift; https://github.com/gregparent21/BluetoothTool/blob/main/README.md)
- [uncertain] Practical limits. Two or three Bluetooth outputs is the realistic ceiling, because they share one radio and macOS may drop to a lower-bandwidth codec. Bluetooth devices can drift apart after pause/resume, a track change or a reconnect, even with the same delay setting. Tutti says AirPlay receivers can't join its multi-output groups (closed source, reason unknown).  (https://github.com/gregparent21/BluetoothTool/blob/main/README.md; https://github.com/BarryBarrywu/tutti (README))

# SNIPPETS
// Create a public stacked aggregate (Multi-Output Device). Keys and literals from MacOSX26.1.sdk AudioHardware.h; same pattern as 2geik/AudioShare MultiOutputDevice.swift (MIT)
import CoreAudio
import Foundation

func makeMultiOutput(uid: String, name: String, mainUID: String,
                     members: [(uid: String, delayFrames: Int)]) throws -> AudioDeviceID {
    let subs: [[String: Any]] = members.map { m in
        var d: [String: Any] = [
            kAudioSubDeviceUIDKey: m.uid,                                   // "uid"
            kAudioSubDeviceDriftCompensationKey: m.uid == mainUID ? 0 : 1,  // "drift"
            kAudioSubDeviceDriftCompensationQualityKey: Int(kAudioAggregateDriftCompensationHighQuality), // "drift quality" = 0x60
        ]
        if m.delayFrames > 0 { d[kAudioSubDeviceExtraOutputLatencyKey] = m.delayFrames } // "latency-out", sample frames
        return d
    }
    let desc: [String: Any] = [
        kAudioAggregateDeviceUIDKey: uid,                 // "uid"   (fixed, so leftovers can be found after a crash)
        kAudioAggregateDeviceNameKey: name,               // "name"
        kAudioAggregateDeviceSubDeviceListKey: subs,      // "subdevices"
        kAudioAggregateDeviceMainSubDeviceKey: mainUID,   // "master" (clock source; prefer wired/built-in)
        kAudioAggregateDeviceIsStackedKey: 1,             // "stacked" -> every member gets the same stream
        kAudioAggregateDeviceIsPrivateKey: 0,             // "private" -> 0 so other apps / the system default can use it
    ]
    var id = AudioDeviceID(kAudioObjectUnknown)
    let st = AudioHardwareCreateAggregateDevice(desc as CFDictionary, &id)
    guard st == noErr, id != kAudioObjectUnknown else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(st)) }
    return id
}

---
// Get/set the default output and translate a UID. Selectors from AudioHardware.h; UID lookup pattern from wozniakpawel/PairPods CoreAudioSystem.swift (MIT)
let systemObject = AudioObjectID(kAudioObjectSystemObject)   // CF_ENUM(int) needs the cast

func addr(_ s: AudioObjectPropertySelector, _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal,
          _ el: AudioObjectPropertyElement = kAudioObjectPropertyElementMain) -> AudioObjectPropertyAddress {
    AudioObjectPropertyAddress(mSelector: s, mScope: scope, mElement: el)
}

func defaultOutput() -> AudioDeviceID? {
    var a = addr(kAudioHardwarePropertyDefaultOutputDevice)
    var id = AudioDeviceID(0); var size = UInt32(MemoryLayout<AudioDeviceID>.size)
    return AudioObjectGetPropertyData(systemObject, &a, 0, nil, &size, &id) == noErr && id != kAudioObjectUnknown ? id : nil
}

@discardableResult func setDefaultOutput(_ id: AudioDeviceID) -> Bool {
    var a = addr(kAudioHardwarePropertyDefaultOutputDevice); var v = id
    return AudioObjectSetPropertyData(systemObject, &a, 0, nil, UInt32(MemoryLayout<AudioDeviceID>.size), &v) == noErr
}
// A new aggregate may not be accepted at first: set, read back with defaultOutput(), retry ~20x every 50 ms.

func deviceID(forUID uid: String) -> AudioDeviceID? {
    var a = addr(kAudioHardwarePropertyTranslateUIDToDevice)
    var cfUID = uid as CFString
    var id = AudioDeviceID(kAudioObjectUnknown); var size = UInt32(MemoryLayout<AudioDeviceID>.size)
    let st = withUnsafeMutablePointer(to: &cfUID) {
        AudioObjectGetPropertyData(systemObject, &a, UInt32(MemoryLayout<CFString>.size), $0, &size, &id)
    }
    return st == noErr && id != kAudioObjectUnknown ? id : nil
}

---
// Estimated output latency in seconds, as a starting point for auto-sync (use only the differences between devices; Bluetooth values under-report). Formula: AudioHardwareBase.h docs plus mpv ao_coreaudio_utils.c
func u32(_ id: AudioObjectID, _ s: AudioObjectPropertySelector, _ scope: AudioObjectPropertyScope) -> UInt32? {
    var a = AudioObjectPropertyAddress(mSelector: s, mScope: scope, mElement: kAudioObjectPropertyElementMain)
    var v: UInt32 = 0; var size = UInt32(MemoryLayout<UInt32>.size)
    return AudioObjectGetPropertyData(id, &a, 0, nil, &size, &v) == noErr ? v : nil
}

func outputLatencySeconds(_ dev: AudioDeviceID) -> Double {
    let out = kAudioObjectPropertyScopeOutput
    var frames = Double(u32(dev, kAudioDevicePropertyLatency, out) ?? 0)
               + Double(u32(dev, kAudioDevicePropertySafetyOffset, out) ?? 0)
    var sa = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreams, mScope: out, mElement: kAudioObjectPropertyElementMain)
    var size: UInt32 = 0
    if AudioObjectGetPropertyDataSize(dev, &sa, 0, nil, &size) == noErr, size >= UInt32(MemoryLayout<AudioStreamID>.size) {
        var streams = [AudioStreamID](repeating: 0, count: Int(size) / MemoryLayout<AudioStreamID>.size)
        if AudioObjectGetPropertyData(dev, &sa, 0, nil, &size, &streams) == noErr {
            frames += Double(u32(streams[0], kAudioStreamPropertyLatency, kAudioObjectPropertyScopeGlobal) ?? 0)
        }
    }
    var ra = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyNominalSampleRate, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var rate: Float64 = 48_000; var rs = UInt32(MemoryLayout<Float64>.size)
    _ = AudioObjectGetPropertyData(dev, &ra, 0, nil, &rs, &rate)
    return frames / rate   // add kAudioDevicePropertyBufferFrameSize only if timing your own IO (mpv does)
}
// delay in frames for "latency-out": Int((ms / 1000) * nominalSampleRateOfThatDevice)

---
// Per-member volume: main element first, else channels 1 and 2 (AirPods). Pattern from 2geik/AudioShare AudioSystem.swift (MIT) and gregparent21/BluetoothTool
func volumeElements(_ dev: AudioDeviceID, _ sel: AudioObjectPropertySelector) -> [AudioObjectPropertyElement] {
    func settable(_ el: AudioObjectPropertyElement) -> Bool {
        var a = AudioObjectPropertyAddress(mSelector: sel, mScope: kAudioObjectPropertyScopeOutput, mElement: el)
        guard AudioObjectHasProperty(dev, &a) else { return false }
        var s: DarwinBoolean = false
        return AudioObjectIsPropertySettable(dev, &a, &s) == noErr && s.boolValue
    }
    return settable(kAudioObjectPropertyElementMain) ? [kAudioObjectPropertyElementMain] : [1, 2].filter(settable)
}

func setVolume(_ dev: AudioDeviceID, _ v: Float32) {
    var value = min(max(v, 0), 1)
    for el in volumeElements(dev, kAudioDevicePropertyVolumeScalar) {   // 'volm', Float32
        var a = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyVolumeScalar, mScope: kAudioObjectPropertyScopeOutput, mElement: el)
        AudioObjectSetPropertyData(dev, &a, 0, nil, UInt32(MemoryLayout<Float32>.size), &value)
    }
}
// Mute: same with kAudioDevicePropertyMute ('mute', UInt32 0/1). The stacked aggregate itself has no volume control.

---
// Change listeners on the main queue. Signature from AudioHardware.h; MainActor.assumeIsolated pattern from 2geik/AudioShare. Keep the block: remove needs the same block object.
final class HALWatcher {
    private var block: AudioObjectPropertyListenerBlock?
    private let selectors: [AudioObjectPropertySelector] = [kAudioHardwarePropertyDevices, kAudioHardwarePropertyDefaultOutputDevice]

    func start(_ onChange: @escaping @MainActor () -> Void) {
        let b: AudioObjectPropertyListenerBlock = { _, _ in MainActor.assumeIsolated { onChange() } }
        block = b
        for s in selectors {
            var a = AudioObjectPropertyAddress(mSelector: s, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
            AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &a, DispatchQueue.main, b)
        }
    }

    func stop() {
        guard let b = block else { return }
        for s in selectors {
            var a = AudioObjectPropertyAddress(mSelector: s, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
            AudioObjectRemovePropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &a, DispatchQueue.main, b)
        }
        block = nil
    }
}
// Creating or destroying our own aggregate also fires kAudioHardwarePropertyDevices: guard against rebuild loops.

---
// Read the composition back (e.g. to confirm "latency-out" was stored) and list owned AudioSubDevice objects. Header says the caller releases the dictionary; owned-objects qualifier pattern from mozilla/cubeb and jack2
func composition(of agg: AudioDeviceID) -> [String: Any]? {
    var a = AudioObjectPropertyAddress(mSelector: kAudioAggregateDevicePropertyComposition, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var dict: Unmanaged<CFDictionary>?
    var size = UInt32(MemoryLayout<Unmanaged<CFDictionary>?>.size)
    guard AudioObjectGetPropertyData(agg, &a, 0, nil, &size, &dict) == noErr, let dict else { return nil }
    return dict.takeRetainedValue() as? [String: Any]   // [kAudioAggregateDeviceSubDeviceListKey] -> [[String: Any]]
}

func ownedSubDevices(of agg: AudioDeviceID) -> [AudioObjectID] {
    var a = AudioObjectPropertyAddress(mSelector: kAudioObjectPropertyOwnedObjects, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var cls: AudioClassID = kAudioSubDeviceClassID          // 'asub'
    let q = UInt32(MemoryLayout<AudioClassID>.size)
    var size: UInt32 = 0
    guard AudioObjectGetPropertyDataSize(agg, &a, q, &cls, &size) == noErr else { return [] }
    var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
    guard AudioObjectGetPropertyData(agg, &a, q, &cls, &size, &ids) == noErr else { return [] }
    return ids   // then kAudioSubDevicePropertyDriftCompensation 'drft' (UInt32) / ExtraLatency 'xltc' (Float64): check AudioObjectHasProperty first; BluetoothTool reports 'who?' for xltc
}

---
// macOS 15+ native Swift API (signatures from MacOSX26.1.sdk usr/lib/swift/CoreAudio.swiftmodule/arm64e-apple-macos.swiftinterface)
if #available(macOS 15.0, *) {
    let sys = AudioHardwareSystem.shared
    if let agg = try sys.makeAggregateDevice(description: desc) {   // desc: [String: Any] as above
        try sys.setDefaultOutputDevice(agg)
        let members = try agg.activeSubdevices                      // [AudioHardwareClock]
        _ = try members.map { try $0.outputLatency }                // Int frames
        // later: try sys.destroyAggregateDevice(agg)
    }
}

---
// Package.swift for a SwiftPM menu bar app linking the needed frameworks; from 2geik/AudioShare Package.swift (MIT)
// swift-tools-version: 6.2
import PackageDescription
let package = Package(
    name: "SpeakerLink",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "SpeakerLink",
            path: "Sources/SpeakerLink",
            swiftSettings: [.defaultIsolation(MainActor.self)],
            linkerSettings: [
                .linkedFramework("CoreAudio"),
                .linkedFramework("IOBluetooth"),
                .linkedFramework("ServiceManagement"),
            ]
        ),
    ]
)

---

# RISKS
- Nobody has confirmed on real hardware that per-device delay works in a stacked aggregate. The only claim that "latency-out" works and kAudioSubDevicePropertyExtraLatency returns 'who?' comes from one unlicensed repo (gregparent21/BluetoothTool). Plan a manual hardware test on macOS 14, 15, 26 and 27. If it doesn't hold up, the fallback is a process-tap delay engine (macOS 14.2+, needs the audio capture permission and NSAudioCaptureUsageDescription).
- Changing a delay probably means destroying and recreating the aggregate, which causes a short audible gap. Coalesce slider changes before rebuilding. Wait for the old UID to disappear before recreating, because destruction is asynchronous.
- Reported Bluetooth latency is not reliable: it under-reports and changes over time and after pause/resume or reconnect. Treat 'auto-sync' as a first estimate only and always offer manual adjustment. Adding delay can only hold faster outputs back; it can't make a Bluetooth device play sooner.
- Sample-rate handling conflicts between the references. AudioShare sets the aggregate's nominal rate to one all members share. PairPods found that forcing Bluetooth rates caused AirPods 4 dropouts and now relies on drift compensation instead. Test both with mixed 44.1k and 48k devices.
- AirPods microphone or HFP: if any app opens the input of a shared AirPods, the link drops to low quality and the aggregate may reconfigure or glitch. Use only ':output' devices, and consider keeping the default input on the built-in mic while sharing.
- A public aggregate stays behind after a crash. Use a fixed UID, destroy leftovers at launch, destroy on quit (applicationWillTerminate), and restore the previous default output, stored by UID, before destroying.
- When a member connects, macOS may switch the default output to it, or the user may pick another output. Listen to kAudioHardwarePropertyDefaultOutputDevice and kAudioHardwarePropertyDevices, and keep rebuilds from feeding back into these listeners.
- Whether an aggregate can be the system alert output is unconfirmed. Read kAudioDevicePropertyDeviceCanBeDefaultSystemDevice ('sflt') at runtime instead of assuming.
- The multi-output device has no master volume, so the system slider and volume keys are dead while sharing. Per-member volume through VolumeScalar needs AVRCP absolute volume; devices without it can't be controlled without a virtual driver or a tap.
- I could not get the macOS 27 SDK headers; the latest I checked were 26.1 directly and 26.5 through pyobjc metadata. A report on macOS 27 with AirPods Pro 3 shows IO slice sizes larger than requested. AudioShare says that in the macOS 27 SDK '@State' is a macro whose plugin ships only with Xcode, so Command Line Tools builds fail unless you write State(initialValue:). It also says SwiftPM's Swift Build engine records the deployment target as the binary's SDK version, which disables Liquid Glass unless it is patched with vtool.
- Licenses: AudioShare, PairPods, CAAudioHardware and swift-core-audio are MIT (GPL-3.0 compatible). BluetoothTool has no LICENSE, so don't copy its code; use it for reference only.
- The kAudioAggregateDeviceIsStackedKey header comment literally says 0 means 'all fed the same data'. Don't follow it; pass 1.
- Several Apple support pages, the JUCE forum and MacRumors were unreachable from this sandbox. The Audio MIDI Setup drift and clock guidance is cited only from search snippets and the BlackHole wiki.

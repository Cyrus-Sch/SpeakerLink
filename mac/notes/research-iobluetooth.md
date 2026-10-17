# SUMMARY
IOBluetooth on macOS 13-27 for headphones and speakers. Sources were Apple's 2026 docs, IOBluetooth SDK headers from the MacOSX11.3 mirror (these constants have not changed in years), blueutil v2.14.0 (2026-09-10), Chromium main, PairPods and Apple forums. Many news and support sites could not be reached from the sandbox, so some points come from search summaries and are marked as such.

LISTING DEVICES
- `IOBluetoothDevice.pairedDevices() -> [Any]!`: returns nil when nothing is paired. Cast each element to IOBluetoothDevice. Chromium notes the list sometimes includes devices that are not paired, so filter with `isPaired()`.
- Useful members: `addressString: String!` (format "00-11-22-33-44-55"), `name: String!`, `nameOrAddress: String!`, `isConnected() -> Bool` (baseband/ACL link only), `classOfDevice`, `deviceClassMajor` / `deviceClassMinor` / `serviceClassMajor` (all UInt32 typealiases), `services: [Any]!` (cached SDP records), `getServiceRecord(for: IOBluetoothSDPUUID!)`, `isHandsFreeDevice`, `isIncoming()`, `rawRSSI()`.
- Class-of-device constants (Swift type Int): `kBluetoothDeviceClassMajorAudio` = 0x04. Audio minor classes: Headset 0x01, HandsFree 0x02, Microphone 0x04, Loudspeaker 0x05, Headphones 0x06, Portable 0x07, Car 0x08, SetTopBox 0x09, HiFi 0x0a, VideoDisplayAndLoudspeaker 0x0f, GamingToy 0x12. Service-class bits: `kBluetoothServiceClassMajorAudio` = 0x100 (CoD bit 21), `kBluetoothServiceClassMajorRendering` = 0x020 (bit 18).
- C macros: `BluetoothGetDeviceClassMajor(cod)` = (cod & 0x1F00) >> 8, Minor = (cod & 0xFC) >> 2, ServiceMajor = (cod & 0xFFE000) >> 13. Function-like macros are not imported into Swift; use the properties instead.
- SDP service UUIDs: `kBluetoothSDPUUID16ServiceClassAudioSink` = 0x110B (Swift type SDPServiceClasses, so use `.rawValue`), AudioSource 0x110A, AdvancedAudioDistribution 0x110D, AVRemoteControl 0x110E, Headset 0x1108.

CONNECTING AND DISCONNECTING
- `openConnection() -> IOReturn` is synchronous. It returns only once the connection is up or has failed or timed out. The default page timeout is `kDefaultPageTimeout` = 0x2710 slots × 0.625 ms = 6.25 s, and authentication can add to that. Since OS X 10.7 it no longer masks "connection exists" as success, so check `isConnected()` first (HCI error `kBluetoothHCIErrorACLConnectionAlreadyExists` = 0x0B).
- Asynchronous variant: `openConnection(_ target:, withPageTimeout:, authenticationRequired:)` calls `connectionComplete:status:` (protocol IOBluetoothDeviceAsyncCallbacks) on the target.
- `closeConnection()` is documented as synchronous, but has been observed returning success while the link stays up.
- blueutil:
  - `--connect` is just `[device openConnection] == kIOReturnSuccess`.
  - `--disconnect` checks `isConnected`, calls `registerForDisconnectNotification:selector:` and `closeConnection`, then runs CFRunLoopRun until the notification arrives (added in v2.9.1).
  - Issue #58: disconnect is unreliable on Monterey through macOS 15.0.1 and is still open.
  - Issue #87: `isConnected` is false for a connected Bose QC Ultra, so v2.13 added a system_profiler fallback (`SPBluetoothDataType`; keys `device_connected`, `device_not_connected`, `device_address`).
  - Issue #107 (maintainer): there is no public API to stop a device auto-connecting.
- Does `openConnection` bring up A2DP? Apple documents only the baseband link. In practice the system brings up the audio profiles afterwards, and the Core Audio output appears a few seconds later. Evidence: AppleScript using `openConnection()` reported working on Sequoia with a 2 s delay; BluetoothConnector; the live-trans app polls Core Audio 30 × 500 ms (about 15 s) after `openConnection` before giving up. Treat it as likely, not guaranteed: AirPods in their case, or devices held by another host, fail.

PRIVACY
- An Apple engineer stated that starting with Sonoma (14), IOBluetooth access is gated by TCC, and that TCC is not available to root daemons.
- Service: `kTCCServiceBluetoothAlways`. Info.plist key: `NSBluetoothAlwaysUsageDescription` (macOS 11+).
- Key missing: tccd logs "Refusing authorization request ... without NSBluetoothAlwaysUsageDescription key" and kills the process with SIGABRT (termination namespace TCC; `__TCC_CRASHING_DUE_TO_PRIVACY_VIOLATION__`). This is why blueutil installs a SIGABRT handler (exit 134).
- Access denied: IOBluetooth returns an empty array or nil with no error.
- Chromium calls `[IOBluetoothDevice pairedDevices]` on a background thread on purpose to trigger the prompt, because it "might block". It reads the status with `CBCentralManager.authorization` (CBManagerAuthorization: notDetermined / restricted / denied / allowedAlways), which never prompts.
- The grant goes to the responsible process: during development, Terminal or ssh gets it, not your binary.
- `com.apple.security.device.bluetooth` is the App Sandbox Bluetooth entitlement and only matters for sandboxed apps. PairPods ships sandboxed with it.
- The Hardened Runtime has no Bluetooth entitlement; its resource-access entitlements are audio-input, camera, location, addressbook, calendars, photos-library and apple-events.
- Ad-hoc-signed builds pin TCC grants to the cdhash, so users must grant again after every update. Sign with a stable identity.

MATCHING TO CORE AUDIO
- A Bluetooth output's `kAudioDevicePropertyDeviceUID` is "F3-A2-14-A9-1D-F8:output" and the hands-free microphone is "...:input". Hex is uppercase, separated by dashes. Chromium's audio_manager_mac.cc documents this format, and PairPods and live-trans rely on it.
- Transport types: `kAudioDeviceTransportTypeBluetooth` = 'blue', `kAudioDeviceTransportTypeBluetoothLE` = 'blea'.
- Compare with `addressString` case-insensitively (PairPods uses `caseInsensitiveCompare`).
- Look the device up with `kAudioHardwarePropertyTranslateUIDToDevice` ('uidd'). It returns `kAudioObjectUnknown` rather than an error when nothing matches.

SWIFT AND SWIFT 6
- Apple's 2026 docs list IOBluetoothDevice's conformances without Sendable. IOBluetoothSDPUUID (an NSData subclass) is Sendable.
- APIs use implicitly unwrapped types (`[Any]!`, `String!`), so guard against nil.
- Pass address Strings across actors, and make the IOBluetoothDevice inside the task with `IOBluetoothDevice(addressString:)`. The header says there is a single instance per address within an app.
- `@preconcurrency import IOBluetooth` silences the warnings for legacy types.
- Never call `openConnection`, `closeConnection` or the first `pairedDevices` on the main actor.
- Notifications: `IOBluetoothDevice.register(forConnectNotifications:selector:)` (class method) and `device.register(forDisconnectNotification:selector:)` return an IOBluetoothUserNotification; call `unregister()` when done. Chromium bounces these callbacks back to its UI thread, which suggests they can arrive off the main thread.
- Old C APIs: `IOBluetoothRegisterForDeviceConnectNotifications` crashed with a missing symbol on the Monterey betas. Use the Objective-C API.
- IOBluetooth is Classic-centric. blueutil #110 (2026-10) notes its inquiry does not find BLE devices.

LE AUDIO, AURACAST, SHARE AUDIO
- No source shows LE Audio (LC3/BAP), Auracast, or iOS-style "Share Audio" on macOS 26 Tahoe or macOS 27. macOS 27 is reportedly named "Golden Gate" and shipped in September 2026.
- Macs do support Made-for-iPhone hearing devices, which use Apple's own Bluetooth LE protocol, on select M1 and all M2/M3 Macs.
- Prior art: PairPods (MIT licence, macOS 13.5+, Homebrew cask `pairpods`) already ships a multi-output menu bar app. Its known limitation is pitch shift when devices run at different sample rates (44.1 vs 48 kHz).

PRACTICAL LIMITS
- Apple support text (seen via search): the Bluetooth spec allows 7 connected devices; 3-4 is the practical limit depending on device type.
- Reports: 2 speakers worked "fairly well", 3 was less reliable, 4 would not stay connected (iOS Hacker). Stutter appeared once a third Bluetooth device (a mouse) was connected (Apple Community).
- An M3 Max (BCM4388) on macOS 15.6.1 streaming SBC 44.1 kHz joint stereo, bitpool 52, 322 kbps showed about 57% retransmissions, against about 8% for AirPods on AAC.
- Codecs: SBC and AAC are what work reliably; aptX on Sonoma and later is disputed.
- Latency (secondary sources): SBC about 150-270 ms, AAC about 80-200 ms; it varies per device, hence per-device delay.
- Bluetooth is always 2.4 GHz; Apple advises putting Wi-Fi on 5 GHz and keeping USB 3 devices away from the hinge and antennas.
- If a Bluetooth headset becomes the active input (its mic), it drops from A2DP to HFP/SCO (about 16 kHz). Never include `:input` UIDs, and watch the default input.

# FACTS
- [verified] IOBluetoothDevice API (Swift): class func pairedDevices() -> [Any]!; func openConnection() -> IOReturn; func openConnection(_ target: Any!, withPageTimeout: BluetoothHCIPageTimeout, authenticationRequired: Bool) -> IOReturn; func closeConnection() -> IOReturn; func isConnected() -> Bool; func isPaired() -> Bool; var addressString: String!; var name: String!; var nameOrAddress: String!; var classOfDevice; var deviceClassMajor/deviceClassMinor (UInt32); var services: [Any]!; func getServiceRecord(for: IOBluetoothSDPUUID!); class func register(forConnectNotifications:selector:); func register(forDisconnectNotification:selector:) -> IOBluetoothUserNotification!. No deprecation shown.  (https://developer.apple.com/documentation/iobluetooth/iobluetoothdevice; https://developer.apple.com/documentation/iobluetooth)
- [verified] openConnection() is synchronous: 'will not return until either a connection has been established or the create connection has failed (perhaps timed out)'. Since OS X 10.7 it no longer masks 'Connection Exists' as success. The async variant with a target calls -connectionComplete:status: (IOBluetoothDeviceAsyncCallbacks).  (https://developer.apple.com/documentation/iobluetooth/iobluetoothdevice/openconnection(); https://developer.apple.com/documentation/iobluetooth/iobluetoothdevice/openconnection(_:withpagetimeout:authenticationrequired:); https://github.com/phracker/MacOSX-SDKs/blob/master/MacOSX11.3.sdk/System/Library/Frameworks/IOBluetooth.framework/Versions/A/Headers/objc/IOBluetoothDevice.h)
- [verified] Default page timeout kDefaultPageTimeout = 0x2710 slots (10000 × 0.625 ms = 6.25 s). kBluetoothHCIErrorPageTimeout = 0x04, kBluetoothHCIErrorACLConnectionAlreadyExists = 0x0B.  (https://github.com/phracker/MacOSX-SDKs/blob/master/MacOSX11.3.sdk/System/Library/Frameworks/IOBluetooth.framework/Versions/A/Headers/Bluetooth.h)
- [verified] pairedDevices: per the header, returns nil when nothing is paired and is not per-user (all users' pairings). Chromium filters with isPaired() because 'pairedDevices sometimes includes unknown devices that are not paired'.  (https://github.com/phracker/MacOSX-SDKs/blob/master/MacOSX11.3.sdk/System/Library/Frameworks/IOBluetooth.framework/Versions/A/Headers/objc/IOBluetoothDevice.h; https://github.com/chromium/chromium/blob/main/device/bluetooth/bluetooth_adapter_mac.mm)
- [verified] Class-of-device constants: kBluetoothDeviceClassMajorAudio=0x04 (Swift: var ... : Int); audio minors Headset 0x01, HandsFree 0x02, Microphone 0x04, Loudspeaker 0x05, Headphones 0x06, Portable 0x07, Car 0x08, SetTopBox 0x09, HiFi 0x0a, VideoDisplayAndLoudspeaker 0x0f, GamingToy 0x12; kBluetoothServiceClassMajorAudio=0x100, Rendering=0x020; macros: major=(cod&0x1F00)>>8, minor=(cod&0xFC)>>2, service=(cod&0xFFE000)>>13.  (https://github.com/phracker/MacOSX-SDKs/blob/master/MacOSX11.3.sdk/System/Library/Frameworks/IOBluetooth.framework/Versions/A/Headers/BluetoothAssignedNumbers.h; https://developer.apple.com/documentation/iobluetooth/kbluetoothdeviceclassmajoraudio)
- [verified] SDP UUIDs: AudioSink 0x110B, AudioSource 0x110A, AdvancedAudioDistribution 0x110D, AVRemoteControl 0x110E, Headset 0x1108. In Swift these are 'var kBluetoothSDPUUID16ServiceClassAudioSink: SDPServiceClasses' (needs .rawValue). IOBluetoothSDPUUID has init!(uuid16: BluetoothSDPUUID16) and is Sendable.  (https://developer.apple.com/documentation/iobluetooth/kbluetoothsdpuuid16serviceclassaudiosink; https://developer.apple.com/documentation/iobluetooth/iobluetoothsdpuuid; https://github.com/phracker/MacOSX-SDKs/blob/master/MacOSX11.3.sdk/System/Library/Frameworks/IOBluetooth.framework/Versions/A/Headers/BluetoothAssignedNumbers.h)
- [verified] IOBluetoothDevice's documented conformances (2026 docs) do not include Sendable; it is a non-Sendable ObjC class in Swift 6. Header: 'Within a single application, there will be only one instance of IOBluetoothDevice for a given remote device address'. The deviceWithAddressString: doc says form xx:xx:xx:xx:xx:xx, and blueutil/live-trans pass dashed forms.  (https://developer.apple.com/documentation/iobluetooth/iobluetoothdevice; https://github.com/phracker/MacOSX-SDKs/blob/master/MacOSX11.3.sdk/System/Library/Frameworks/IOBluetooth.framework/Versions/A/Headers/objc/IOBluetoothDevice.h)
- [likely] addressString / IOBluetoothNSStringFromDeviceAddress format is '00-11-22-33-44-55' (dashes). Case is likely lowercase in practice, so compare case-insensitively.  (https://developer.apple.com/documentation/iobluetooth/iobluetoothnsstringfromdeviceaddress(_:); https://github.com/wozniakpawel/PairPods/blob/main/PairPods/AudioDevice.swift; https://github.com/lapfelix/BluetoothConnector/blob/master/Sources/BluetoothConnector/main.swift)
- [verified] Core Audio UID for a Bluetooth device is '<ADDR uppercase dashed>:output' for output and ':input' for the HFP mic (e.g. 'F3-A2-14-A9-1D-F8:output'). Chromium strips ':input'/':output' to pair them when the transport is kAudioDeviceTransportTypeBluetooth. PairPods matches this UID to IOBluetoothDevice.addressString with caseInsensitiveCompare.  (https://github.com/chromium/chromium/blob/main/media/audio/mac/audio_manager_mac.cc; https://github.com/wozniakpawel/PairPods/blob/main/PairPods/AudioDevice.swift; https://github.com/graywzc/live-trans/pull/34)
- [verified] Transport constants: kAudioDeviceTransportTypeBluetooth='blue', kAudioDeviceTransportTypeBluetoothLE='blea'. kAudioHardwarePropertyTranslateUIDToDevice='uidd' maps a UID (qualifier CFString) to an AudioObjectID and returns kAudioObjectUnknown (not an error) if absent.  (https://github.com/phracker/MacOSX-SDKs/blob/master/MacOSX11.3.sdk/System/Library/Frameworks/CoreAudio.framework/Versions/A/Headers/AudioHardwareBase.h; https://github.com/phracker/MacOSX-SDKs/blob/master/MacOSX11.3.sdk/System/Library/Frameworks/CoreAudio.framework/Versions/A/Headers/AudioHardware.h)
- [verified] blueutil (latest v2.14.0, 2026-09-10) connects with only [device openConnection] != kIOReturnSuccess → fail. Disconnect: if !isConnected → return; registerForDisconnectNotification; closeConnection; CFRunLoopRun until notified (v2.9.1 added the wait because closeConnection returned success without disconnecting). v2.14.0 checks connected state before disconnecting to avoid a hang on unknown addresses.  (https://github.com/toy/blueutil/blob/main/blueutil.m; https://github.com/toy/blueutil/blob/main/CHANGELOG.md; https://github.com/toy/blueutil/issues/70)
- [verified] Disconnect via blueutil/closeConnection is unreliable on Monterey and later (issue #58, open; reports through macOS 14.6.1 and 15.0.1, Nov 2024; maintainer: 'unresolved'). BluetoothConnector retries closeConnection up to 10 times, 500 ms apart.  (https://github.com/toy/blueutil/issues/58; https://github.com/lapfelix/BluetoothConnector/blob/master/Sources/BluetoothConnector/main.swift)
- [verified] IOBluetooth isConnected can be wrong for some (multipoint) audio devices, e.g. Bose QC Ultra or Bose Soundbar 300 show not connected while connected. Maintainer: IOBluetooth is not fully maintained. blueutil v2.13 added BLUEUTIL_USE_SYSTEM_PROFILER=1 (system_profiler SPBluetoothDataType -xml; keys _items, device_connected, device_not_connected, device_address, device_rssi).  (https://github.com/toy/blueutil/issues/87; https://github.com/toy/blueutil/blob/main/README.md; https://github.com/toy/blueutil/blob/main/blueutil.m)
- [likely] openConnection on a paired audio device normally also brings up the audio profile, and the Core Audio output appears after a delay. Evidence: AppleScript using openConnection reported working on Sequoia (Dec 2024) with a 2 s delay; the live-trans app calls openConnection then polls Core Audio 30 × 500 ms (about 15 s) for '<ADDR>:output'. No Apple doc guarantees this, and AirPods 4 'failed immediately' in one live test (case closed or held by another device).  (https://gist.github.com/ieatfood/814b065964492f71f728da59a47413bc; https://github.com/graywzc/live-trans/pull/34; https://developer.apple.com/documentation/iobluetooth/iobluetoothdevice/openconnection())
- [verified] No public API exists to disable auto-connect per device (blueutil maintainer, #107); headphones or speakers may reconnect on their own after closeConnection.  (https://github.com/toy/blueutil/issues/107)
- [verified] Apple engineer (Oct 2023): 'Starting with Sonoma, access to IOBluetooth framework is gated by TCC ... prompts. TCC is not available for root daemons in macOS.' Launch agents and user apps work; denied access returns an empty array with no error.  (https://developer.apple.com/forums/thread/738748)
- [verified] Without NSBluetoothAlwaysUsageDescription, tccd logs 'Refusing authorization request for service kTCCServiceBluetoothAlways ... without NSBluetoothAlwaysUsageDescription key' and the process is killed with SIGABRT, termination namespace TCC, frame __TCC_CRASHING_DUE_TO_PRIVACY_VIOLATION__ (shell exit 134). blueutil installs a SIGABRT handler telling users to grant access in System Settings > Privacy & Security > Bluetooth.  (https://github.com/anthropics/claude-code/issues/76517; https://github.com/toy/blueutil/blob/main/blueutil.m; https://github.com/toy/blueutil/issues/95)
- [verified] NSBluetoothAlwaysUsageDescription is available on macOS 11.0+ and is 'required if your app uses the device's Bluetooth interface'.  (https://developer.apple.com/documentation/bundleresources/information-property-list/nsbluetoothalwaysusagedescription)
- [verified] Chromium triggers the Bluetooth TCC prompt by calling [IOBluetoothDevice pairedDevices] on a background ThreadPool task ('might block on user interaction with the prompt'). It reads status via CBCentralManager.authorization (NotDetermined→undetermined, Restricted/Denied→denied, AllowedAlways→allowed), which does not prompt.  (https://github.com/chromium/chromium/blob/main/device/bluetooth/bluetooth_adapter_mac.mm; https://github.com/chromium/chromium/blob/main/device/bluetooth/bluetooth_adapter_mac_permission.mm; https://developer.apple.com/documentation/corebluetooth/cbmanager/authorization-swift.type.property)
- [verified] TCC attribution follows the responsible process: run from Terminal, Terminal's grant applies; over ssh, sshd-keygen-wrapper got authValue=1 and pairedDevices returned nothing (macOS 26.4.1). Gating IOBluetooth on CBManager.authorization == .allowedAlways is a working pattern.  (https://github.com/charles8051/periphery/pull/316; https://developer.apple.com/forums/thread/675773)
- [likely] com.apple.security.device.bluetooth (macOS 10.7+) is the App Sandbox Hardware > Bluetooth entitlement. A non-sandboxed Developer ID app does not need it. PairPods ships sandboxed with app-sandbox + device.bluetooth + NSBluetoothAlwaysUsageDescription.  (https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.device.bluetooth; https://github.com/wozniakpawel/PairPods/blob/main/PairPods/PairPods.entitlements; https://github.com/wozniakpawel/PairPods/blob/main/PairPods/Info.plist)
- [verified] The Hardened Runtime has no Bluetooth entitlement. Resource Access entitlements are only device.audio-input, device.camera, personal-information.location/addressbook/calendars/photos-library and automation.apple-events. Hardened Runtime is required for notarization.  (https://developer.apple.com/documentation/security/hardened-runtime)
- [likely] Ad-hoc signatures pin the TCC designated requirement to the cdhash, so grants are lost on each update. A stable signing identity (Developer ID or a fixed self-signed certificate) avoids it.  (https://github.com/pathorsAI/parley/issues/75; https://github.com/shlgd/SuperDictate/issues/19; https://github.com/erickgnclvs/moomux/issues/304)
- [likely] Connect/disconnect notification callbacks may be delivered off the main thread. Chromium's bridge reposts them to its owning UI task runner and keeps the listener alive about 2 s after unregister for in-flight background dispatches. Notifications need a running run loop (blueutil uses CFRunLoopRun).  (https://github.com/chromium/chromium/blob/main/device/bluetooth/bluetooth_adapter_mac.mm; https://github.com/toy/blueutil/blob/main/blueutil.m)
- [likely] The C API IOBluetoothRegisterForDeviceConnectNotifications produced a 'missing symbol' crash on the Monterey betas; prefer the ObjC +registerForConnectNotifications:selector:.  (https://developer.apple.com/forums/thread/685545)
- [verified] Synchronous openConnection() on @MainActor blocks the UI for the whole page attempt (flagged in live-trans PR review). Run it off the main actor or use the async target variant.  (https://github.com/graywzc/live-trans/pull/34; https://developer.apple.com/documentation/iobluetooth/iobluetoothdevice/openconnection())
- [verified] PairPods (MIT, macOS 13.5+, brew install --cask pairpods) is an existing SwiftUI menu bar multi-output app with per-device toggles and volume, master-clock selection, and battery via undocumented KVC keys batteryPercentLeft/Right/Case/Single on IOBluetoothDevice. Known limitation: pitch shift when devices differ in sample rate (44.1 vs 48 kHz).  (https://github.com/wozniakpawel/PairPods; https://github.com/wozniakpawel/PairPods/blob/main/PairPods/AudioDevice.swift)
- [likely] No evidence that macOS 26 Tahoe or macOS 27 support standard LE Audio (LC3/BAP), Auracast, or iOS-style AirPods 'Share Audio' on Mac; guides still use an Audio MIDI Setup Multi-Output Device. Macs support Made-for-iPhone hearing devices (Apple's own BLE protocol) on select M1 and all M2/M3 Macs.  (https://support.apple.com/108780; https://github.com/toy/blueutil/issues/110; https://www.iot747.com/will-apple-support-auracast/)
- [uncertain] macOS 27 is reportedly codenamed 'Golden Gate' (WWDC26, June 2026), with 27.0 shipping around mid-September 2026.  (https://gigazine.net/gsc_news/en/20260609-apple-wwdc26-macos-27; https://en.wikipedia.org/wiki/MacBook_Neo)
- [likely] Apple support text (archived HT201171/101205, seen via search): the Bluetooth spec allows up to 7 connected devices, '3 to 4 devices is a practical limit, depending on the types of devices'.  (https://support.apple.com/HT201171; https://support.apple.com/101205)
- [likely] Field reports on multi-output with Bluetooth: 2 speakers 'fairly well', 3 less reliable, 4 would not stay connected (iOS Hacker). Stutter once a third Bluetooth device was connected, fixed by moving a mouse to USB (Apple Community). Hiccups when a second Bluetooth audio set joined. Drift correction reduces but does not remove offset.  (https://ioshacker.com/how-to/stream-to-multiple-bluetooth-speakers-on-mac; https://discussions.apple.com/thread/254533016; https://discussions.apple.com/thread/252654531)
- [verified] A Mac A2DP link example (MacBook Pro M3 Max, Broadcom BCM4388, macOS 15.6.1): 'SBC 44.1 kHz joint stereo, bitpool 52, 322 kbps, 5 frames x 117 B per packet every 14.51 ms, L2CAP MTU 672'. About 57% median retransmissions to an SBC speaker versus about 8.1% for AirPods (AAC); Wi-Fi on 5 GHz, CoexDenial 0. No Apple reply (FB25169767).  (https://developer.apple.com/forums/thread/850783)
- [uncertain] macOS A2DP codecs: SBC (mandatory) and AAC are reliable. aptX on Sonoma and later is disputed. Typical latency from secondary sources: SBC about 150-270 ms, AAC about 80-200 ms, varying per device.  (https://www.macrumors.com/how-to/enable-aptx-aac-bluetooth-audio-codecs-macos/; https://discussions.apple.com/thread/255528714; https://onlineaudiotest.com/devices/airpods-pro-2/)
- [likely] If a Bluetooth headset's mic becomes the active input, macOS switches it from A2DP to HFP/HSP (about 16 kHz, mono), degrading output. Keep the system input on the built-in mic and never add ':input' devices.  (https://mjtsai.com/blog/2023/12/13/why-is-bluetooth-sound-quality-bad-on-my-mac; https://github.com/david-ns/airpodssoundqualityfixer)
- [likely] Bluetooth always uses 2.4 GHz. Apple advises moving Wi-Fi to 5 GHz and keeping USB 3 devices or hubs away from the back/hinge antennas to reduce 2.4 GHz interference.  (https://support.apple.com/HT201542)

# SNIPPETS
// Swift 6 - list paired audio devices (API shapes from Apple's IOBluetooth docs and SDK headers; not compiled here)
@preconcurrency import IOBluetooth
struct BTAudioDevice: Sendable, Hashable { let address: String; let name: String; let connected: Bool; let minor: UInt32 }
func pairedAudioDevices() -> [BTAudioDevice] {   // call off the main actor: the first call may show the TCC prompt and block
    let sinkUUID = IOBluetoothSDPUUID(uuid16: BluetoothSDPUUID16(kBluetoothSDPUUID16ServiceClassAudioSink.rawValue))
    return (IOBluetoothDevice.pairedDevices() ?? [])            // nil when nothing is paired or access is denied
        .compactMap { $0 as? IOBluetoothDevice }
        .filter { $0.isPaired() }                               // Chromium: list can contain unpaired entries
        .compactMap { d in
            let audioClass = d.deviceClassMajor == BluetoothDeviceClassMajor(kBluetoothDeviceClassMajorAudio)
            guard audioClass || d.getServiceRecord(for: sinkUUID) != nil, let addr = d.addressString else { return nil }
            return BTAudioDevice(address: addr.uppercased(), name: d.nameOrAddress ?? addr, connected: d.isConnected(), minor: d.deviceClassMinor)
        }
}

---
// Swift 6 - connect / disconnect without blocking the UI. Pass only the Sendable address string across the actor hop.
func connect(address: String) async -> Bool {
    await Task.detached(priority: .userInitiated) {
        guard let d = IOBluetoothDevice(addressString: address) else { return false }
        if d.isConnected() { return true }                        // 10.7+: an existing link is reported as an error
        return d.openConnection() == kIOReturnSuccess              // synchronous; default page timeout 0x2710 slots = 6.25 s
    }.value
}
func disconnect(address: String) async -> Bool {                     // closeConnection can report success while still connected (blueutil #58/#70)
    await Task.detached {
        guard let d = IOBluetoothDevice(addressString: address) else { return false }
        for _ in 0..<10 where d.isConnected() { _ = d.closeConnection(); usleep(500_000) }   // BluetoothConnector pattern
        return !d.isConnected()
    }.value
}

---
// Swift - find the Core Audio output for a Bluetooth address (UID format from Chromium audio_manager_mac.cc / PairPods)
import CoreAudio
func bluetoothOutputDeviceID(address: String) -> AudioObjectID? {
    var uid = "\(address.uppercased()):output" as CFString         // e.g. "20-F4-D4-49-C9-47:output"
    var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyTranslateUIDToDevice,
                                          mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var id = AudioObjectID(kAudioObjectUnknown); var size = UInt32(MemoryLayout<AudioObjectID>.size)
    let st = withUnsafeMutablePointer(to: &uid) { q in
        AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, UInt32(MemoryLayout<CFString>.size), q, &size, &id) }
    return (st == noErr && id != AudioObjectID(kAudioObjectUnknown)) ? id : nil   // poll after openConnection (live-trans: 30 x 500 ms)
}

---
// Swift - permission check that never prompts (Chromium bluetooth_adapter_mac_permission.mm; CBManager.authorization is macOS 10.15+)
import CoreBluetooth
switch CBManager.authorization {
case .allowedAlways: break                    // safe to use IOBluetooth
case .notDetermined: break                    // the first IOBluetoothDevice.pairedDevices() call shows the prompt; run it off the main thread
case .denied, .restricted: break              // IOBluetooth silently returns empty or nil; send the user to Privacy & Security > Bluetooth
@unknown default: break
}

---
<!-- Info.plist (required, otherwise TCC kills the process with SIGABRT) - key per Apple BundleResources docs -->
<key>NSBluetoothAlwaysUsageDescription</key>
<string>SpeakerLink connects and disconnects your paired headphones and speakers so they can play together.</string>
<key>LSUIElement</key><true/>
<!-- .entitlements ONLY if App Sandbox is enabled (PairPods does this); a non-sandboxed Developer ID build needs neither -->
<key>com.apple.security.app-sandbox</key><true/>
<key>com.apple.security.device.bluetooth</key><true/>

---
# shell - debugging on a real Mac (service name BluetoothAlways is assumed from kTCCServiceBluetoothAlways)
tccutil reset BluetoothAlways com.example.SpeakerLink
log stream --info --predicate 'process == "tccd" AND eventMessage CONTAINS "BluetoothAlways"'
system_profiler SPBluetoothDataType -xml   # fallback used by blueutil: _items > device_connected / device_not_connected > device_address
codesign -d -r- SpeakerLink.app             # a cdhash-only requirement (ad-hoc signature) means grants are lost on every update

---

# RISKS
- openConnection() only guarantees a baseband (ACL) link. That A2DP comes up and a Core Audio '<ADDR>:output' appears is observed behaviour, not documented. Poll for it with a timeout (about 10-15 s) and show an actionable error (for AirPods: case open, not in use by an iPhone).
- closeConnection() can return kIOReturnSuccess while the link stays up (blueutil #58 still open through macOS 15). Headsets may also reconnect on their own, and there is no public API to stop auto-connect. Verify with isConnected() or a disconnect notification, and retry.
- IOBluetoothDevice.isConnected() is wrong for some multipoint devices (Bose QC Ultra, Bose Soundbar 300). Treat presence of the Core Audio '<ADDR>:output' device as the truth for 'audio connected'.
- TCC: a missing NSBluetoothAlwaysUsageDescription means an instant SIGABRT kill. Denial means silent empty or nil results. The first pairedDevices() call may block on the prompt. During development the grant belongs to Terminal or Xcode, not the app. Ad-hoc-signed GitHub releases lose the grant on every update. TCC is unavailable to root daemons.
- On macOS 13 (Ventura), IOBluetooth was not yet TCC-gated (Apple says gating started with Sonoma). Do not hard-block IOBluetooth on CBManager.authorization == .notDetermined there. This is inferred, so verify on a 13.x machine.
- Swift 6: IOBluetoothDevice is not Sendable and APIs return implicitly unwrapped optionals (nil crashes if force-used, e.g. pairedDevices().compactMap with no paired devices). C function-like macros (BluetoothGetDeviceClassMajor) are not imported. Constant types differ (Int for class-of-device constants vs SDPServiceClasses for SDP UUIDs). Complex IOKit error macros such as kIOReturnNotFound may not import, so compare against kIOReturnSuccess.
- Undocumented or private bits used by prior art (IOBluetoothPreference* power APIs in blueutil, KVC battery keys in PairPods, IOBluetoothPreferences setPoweredOn: in BluetoothConnector) can break in any release. Avoid them, or feature-detect with responds(to:).
- Practical stream count: 2 Bluetooth outputs are reasonable, 3 is flaky, 4 is often unstable. Other Bluetooth peripherals, 2.4 GHz Wi-Fi and USB 3 hubs make it worse. Some Apple Broadcom controllers show high A2DP retransmissions even with one SBC sink. Warn in the UI at 3 or more.
- Multi-output pitfalls: sample-rate mismatch (SBC speakers often 44.1 kHz vs AAC headphones 48 kHz) causes pitch shift or warping unless drift correction is on and the clock device is chosen well. If any headset becomes the input device it drops to HFP at about 16 kHz.
- Latency differs a lot per device (roughly 80-270 ms by codec and model), and the latency Core Audio reports for Bluetooth devices may not match real air latency. Per-device delay should be user-tunable, not purely computed. This is unverified and needs testing on hardware.
- No LE Audio, Auracast or Share Audio path exists on macOS 26/27 per available evidence. LE-only earbuds may not appear or connect via the Classic-centric IOBluetooth (blueutil #110).
- CI: GitHub macOS runners presumably have no Bluetooth hardware or TCC grants (unverified). Keep IOBluetooth calls out of unit tests; test address and UID parsing and class-of-device filtering as pure functions.
- Several sources (Apple support pages, MacRumors, iOS Hacker, latency sites) could only be read through search-result summaries, because the sandbox blocked those domains. Treat those facts as 'likely' or 'uncertain' as marked.

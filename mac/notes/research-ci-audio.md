# SUMMARY
Testing Core Audio code on GitHub-hosted macOS runners (checked 2026-10-17).

RUNNER IMAGES: macos-latest = macos-26 (arm64; image 20260907.0351.1, macOS 26.6.2 25G83, default Xcode 26.6 = Swift 6.3; Xcode 26.0.1 to 26.6 installed). macos-26-intel / macos-26-large = x64. macos-15 = arm64 (macOS 15.7.9, default Xcode 16.4; 26.0.1 to 26.3 also installed). Xcode 27 is a public preview under the label `xcode-27` (macOS 27.0.1, Xcode 27.0 default = Swift 6.4, plus 27.1 RC and 27.2 beta). macOS 14 images are deprecated and fully unsupported from Nov 2 2026. The image build no longer runs scripts/build/install-audiodevice.sh (switchaudio-osx + sox): no current template references it, so install switchaudio-osx yourself.

DEFAULT AUDIO: the runner normally has one virtual device, "Null Audio Device" (Manufacturer "Apple Inc.", Transport Virtual, 2 in / 2 out, default input/output/system output). This matches Apple's "Creating an Audio Server Driver Plug-in" sample: device name "Null Audio Device", UID "NullAudioDevice_UID", bundle "com.apple.audio.NullAudio". The device sometimes fails to come up: actions/runner-images#13668, open, about 30% of runs on macos-15-large, `system_profiler SPAudioDataType` shows no devices. Because of this, don't rely on the built-in device; install BlackHole.

BLACKHOLE: casks blackhole-2ch / blackhole-16ch / blackhole-64ch, version 0.7.1. The url is https://existential.audio/downloads/BlackHole{N}ch-0.7.1.pkg, pkgutil id audio.existential.BlackHole{N}ch. Installing runs `sudo installer` (passwordless on runners) into /Library/Audio/Plug-Ins/HAL. In March 2025 Homebrew commit 57ca4e7 removed the automatic `killall coreaudiod` and added a `reboot` caveat. Since then the devices do not appear until you run `sudo killall coreaudiod` (actions/runner-images#11746, closed with that workaround). The pkg postinstall only runs chown/chmod and does not restart coreaudiod.

Use `sudo killall -9 coreaudiod`; launchd restarts it. `sudo launchctl kickstart -k system/com.apple.audio.coreaudiod` fails with "Operation not permitted" under SIP since macOS 14.4. Then poll for up to 30 s, either with `SwitchAudioSource -a -t output | grep -qx "BlackHole 2ch"` (Cap's workflow does this) or with a fixed `sleep 10` (mozilla/cubeb).

BlackHole identifiers: names "BlackHole 2ch" / "BlackHole 16ch"; UIDs "BlackHole2ch_UID" / "BlackHole16ch_UID"; model UID "BlackHole{N}ch_ModelUID"; manufacturer "Existential Audio Inc."; transport kAudioDeviceTransportTypeVirtual. Each device has input and output, default rate 48000 Hz, supported rates 8000 to 768000, zero latency, and master output volume and mute controls. A hidden mirror device "BlackHole {N}ch 2" (UID "BlackHole{N}ch_2_UID") exists but is not listed.

Real workflows doing this: mozilla/cubeb (macos-15, macos-15-intel), mozilla/cubeb-coreaudio-rs, mozilla/audioipc, orchetect/swift-core-audio (macos-15, macos-26, `brew install blackhole-2ch && sudo killall -9 coreaudiod`), Wang-Yue/cdsp (installs 2ch and 16ch), CapSoftware/Cap, island-io/mila.

STACKED AGGREGATE OF TWO BLACKHOLES: wozniakpawel/PairPods (MIT) is a menu bar app built on the same idea (share audio to several Bluetooth headphones). Its CI installs `brew install --cask blackhole-2ch` and `blackhole-16ch` on macos-15 and macos-latest. Its Swift Testing suite (PairPodsTests/Integration/AudioRegressionTests.swift, `.serialized`, gated with `.enabled(if: BlackHoleHelper.isAvailable)`) does the following on the hosted runner:
- calls AudioHardwareCreateAggregateDevice with the 2ch and 16ch UIDs, kAudioAggregateDeviceIsStackedKey: 1, kAudioAggregateDeviceMasterSubDeviceKey, and kAudioSubDeviceDriftCompensationKey: 1 on the non-main sub-devices;
- sets kAudioHardwarePropertyDefaultOutputDevice;
- destroys the aggregate and restores the original default.

Its device layer sits behind protocols (AudioSystemQuerying, AudioSystemCommanding). A SimulatedAudioSystem reuses the real BlackHole IDs and UIDs but reports fake Bluetooth names, transport types and sample rates. Caveat: PairPods CI never restarts coreaudiod and its tests are skipped silently when BlackHole is missing, so green runs do not prove the tests ran (logs need sign-in).

No TCC permission is needed to create or destroy an aggregate or to set the default device. Starting IO is different: AudioDeviceStart / AudioDeviceCreateIOProcID can block (about 180 s) when the responsible process lacks kTCCServiceMicrophone (actions/runner-images#10902). Images since Jan 2025 grant Microphone, plus ScreenCapture and BluetoothAlways, to /opt/hca/hosted-compute-agent and /usr/local/opt/runner/provisioner/provisioner in both the system and user TCC.db, so processes started by `swift test` inherit it. An .app launched with `open` would not.

SIP status of hosted images is not documented. The image build writes the system TCC.db with sudo sqlite3, and a script comment says SIP is disabled for one step. The user TCC.db can always be written without SIP changes. Public aggregates (no "private" key, or 0) persist after the process exits and are visible to other apps; that is required for the system default output. Private (1) aggregates are process-local. Tests must use unique UIDs and destroy what they create.

Header ambiguity: AudioHardware.h (26.5 SDK) says stacked "value of 0 means ... output streams are all fed the same data", and mozilla/cubeb follows that wording. PairPods, Sapphire, karaggeorge/macos-audio-devices and Atoll use 1 for a multi-output device. This can be settled on CI: build 2ch+16ch with stacked=1 and stacked=0 and compare output channel totals from kAudioDevicePropertyStreamConfiguration (expect about 2 vs 18).

Other Core Audio API notes:
- Per-device delay is kAudioSubDeviceExtraOutputLatencyKey "latency-out", in frames.
- Drift compensation is "drift"; clock key is "clock"; main sub-device key kAudioAggregateDeviceMainSubDeviceKey = "master".
- Prefer AudioObjectAddPropertyListenerBlock on a private DispatchQueue. Alternatively set kAudioHardwarePropertyRunLoop (in AudioHardwareDeprecated.h, 'rnlp') to NULL so the HAL uses its own notification thread. cubeb waits up to 5 s for a new aggregate to appear in kAudioHardwarePropertyDevices.

ALTERNATIVES TO BLACKHOLE: soundflower cask is deprecated 2024-11-16, disabled 2025-11-16, x86_64-only kext, so not usable. background-music cask 0.5.0 installs "Background Music Device.driver" and restarts coreaudiod only on uninstall. loopback is a commercial GUI app, so no. A custom BlackHole build (kDriver_Name / kNumber_Of_Channels defines, copied to /Library/Audio/Plug-Ins/HAL, then killall coreaudiod) could imitate other devices; that is unverified.

SWIFTUI TO PNG: ImageRenderer is available from macOS 13.0 and declared `final class ImageRenderer<Content> where Content : View` (used @MainActor). Apple's docs say its output only includes views SwiftUI rasterizes itself (text, images, shapes, composites of these). It does not include views composited by Core Animation layers, such as more complex controls and containers, web views, media players, and most UIKit/AppKit views; those render as a placeholder. PairPods' ScreenshotGenerator is a separate SPM executable that calls `_ = NSApplication.shared` (its comment: "required for ImageRenderer to work"). It uses MainActor.assumeIsolated, ImageRenderer with scale 2.0, and nsImage converted through TIFF and NSBitmapImageRep to PNG. It redraws Toggle (switch and checkbox) and the volume Slider as custom shape views so they render. Its release.yml runs `swift build -c release` and the generator on macos-latest. For real AppKit controls, use NSHostingView plus bitmapImageRepForCachingDisplay(in:) / cacheDisplay(in:to:) (the approach swift-snapshot-testing uses for NSView), ideally inside a borderless window. Hosted displays are about 1x at 1024x768, so build a 2x NSBitmapImageRep by hand.

SWIFT TEST: SwiftPM runs XCTest (when the toolchain supports it, which needs Xcode, present on runners) and Swift Testing by default. Relevant flags: `--enable-xctest`/`--disable-xctest`, `--enable-swift-testing`/`--disable-swift-testing`, `--filter <regex>` and `--skip <regex>` (forwarded to Swift Testing too), `--xunit-output`, and `--attachments-path` (Swift Testing only). `--parallel` controls only XCTest process parallelism. Swift Testing runs in parallel in-process by default, so Core Audio suites that change global state need `.serialized`. To gate tests, read an environment variable with ProcessInfo in `.enabled(if:)` (Swift Testing) or `try XCTSkipUnless` (XCTest). Xcode 27 notes say XCTest/Swift Testing cross-framework assertion issues are now reported, including under the swift CLI.

# FACTS
- [verified] YAML labels: macos-latest, macos-26, macos-26-xlarge = macOS 26 arm64; macos-latest-large, macos-26-intel, macos-26-large = macOS 26 x64; macos-15, macos-15-xlarge = macOS 15 arm64; macos-15-intel, macos-15-large = x64; xcode-27 / xcode-27-xlarge = Xcode 27 preview (arm64). macOS 14 is deprecated and fully unsupported by Nov 2 (2026).  (https://github.com/actions/runner-images/blob/main/README.md)
- [verified] macos-26 arm64 image 20260907.0351.1: macOS 26.6.2 (25G83), default Xcode 26.6 (17F113); installed 26.0.1, 26.1.1, 26.2, 26.3, 26.4.1, 26.5, 26.6. The macos-15 arm64 default is Xcode 16.4. The xcode-27 image runs macOS 27.0.1 with Xcode 27.0 (27A266a) default, plus 27.1 RC and 27.2 beta 2.  (https://github.com/actions/runner-images/blob/main/images/macos/macos-26-arm64-Readme.md; https://github.com/actions/runner-images/blob/main/images/macos/macos-15-arm64-Readme.md; https://github.com/actions/runner-images/blob/main/images/macos/xcode-27-arm64-Readme.md)
- [verified] Xcode 26.6 includes Swift 6.3 and requires macOS Tahoe 26.2+. Xcode 27 includes Swift 6.4 and the macOS 27 SDK and requires macOS Tahoe 26.6+.  (https://developer.apple.com/documentation/xcode-release-notes/xcode-26_6-release-notes; https://developer.apple.com/documentation/xcode-release-notes/xcode-27-release-notes)
- [likely] The runner images no longer preinstall switchaudio-osx/sox. images/macos/scripts/build/install-audiodevice.sh still exists but is not referenced by the macOS 26 arm64 (or macOS 14) Packer templates.  (https://github.com/actions/runner-images/blob/main/images/macos/scripts/build/install-audiodevice.sh; https://github.com/actions/runner-images/blob/main/images/macos/templates/macOS-26.arm64.anka.pkr.hcl)
- [verified] Hosted macOS runners normally expose one device, 'Null Audio Device' (Manufacturer Apple Inc., Transport Virtual, 2 in/2 out, default input/output/system output). It is intermittently missing (about 30% of runs reported on macos-15-large), leaving an empty `system_profiler SPAudioDataType`. Issue #13668 is open (investigate).  (https://github.com/actions/runner-images/issues/13668)
- [likely] The runner's 'Null Audio Device' strings match Apple's AudioServerPlugIn sample 'Creating an Audio Server Driver Plug-in' (NullAudio.c: kPlugIn_BundleID com.apple.audio.NullAudio, kDevice_UID 'NullAudioDevice_UID', en.lproj DeviceName 'Null Audio Device', ManufacturerName 'Apple Inc.'). The UID on the runner is therefore probably 'NullAudioDevice_UID'.  (https://github.com/artemnovichkov/wwdc21-samplecode/blob/HEAD/Code/CreatingAnAudioServerDriverPlugIn/NullAudio.c)
- [verified] Cap's workflow uses 'Null Audio Device' as the output on GitHub-hosted macos-26, notes that hosted output devices give ScreenCaptureKit only silence, and installs BlackHole only on Blacksmith runners, where no audio device exists.  (https://github.com/CapSoftware/Cap/blob/HEAD/.github/workflows/verify-recording.yml)
- [verified] Homebrew casks blackhole-2ch/16ch/64ch are at version 0.7.1, url https://existential.audio/downloads/BlackHole{N}ch-#{version}.pkg, pkg install, uninstall pkgutil audio.existential.BlackHole{N}ch, with a `caveats do reboot end`. They no longer kill coreaudiod: commit 57ca4e7 removed the killall postflight and added the reboot caveat.  (https://github.com/Homebrew/homebrew-cask/blob/HEAD/Casks/b/blackhole-2ch.rb; https://github.com/Homebrew/homebrew-cask/commit/57ca4e7; https://github.com/actions/runner-images/issues/11746)
- [verified] After `brew install blackhole-2ch`, a runner shows 'The install requires restarting now' and SwitchAudioSource cannot find 'BlackHole 2ch'. Running `sudo killall coreaudiod` after install fixes it on hosted runners (issue #11746, closed).  (https://github.com/actions/runner-images/issues/11746)
- [verified] BlackHole's pkg postinstall only runs chown/chmod on /Library/Audio/Plug-Ins/HAL/BlackHole*ch.driver and does not restart coreaudiod. The README tells developers to restart CoreAudio with `sudo killall -9 coreaudiod`.  (https://github.com/ExistentialAudio/BlackHole/blob/HEAD/Installer/scripts/postinstall; https://github.com/ExistentialAudio/BlackHole/blob/HEAD/README.md)
- [likely] `sudo launchctl kickstart -k(p) system/com.apple.audio.coreaudiod` fails with 'Operation not permitted' when SIP is enabled, since macOS 14.4. `sudo killall coreaudiod` still works.  (https://developer.apple.com/forums/thread/748228)
- [verified] BlackHole identifiers: device name 'BlackHole %ich' (for example 'BlackHole 2ch', 'BlackHole 16ch'), UID 'BlackHole%ich_UID', box UID the same, model UID 'BlackHole%ich_ModelUID', hidden mirror device 'BlackHole %ich 2' / 'BlackHole%ich_2_UID' (kDevice2_IsHidden true). Manufacturer 'Existential Audio Inc.', transport kAudioDeviceTransportTypeVirtual, default sample rate 48000.0, rates 8000 to 768000, kLatency_Frame_Size 0. Each device has input and output plus master Volume and Mute controls for input and output scope; kCanBeDefaultDevice and kCanBeDefaultSystemDevice are true.  (https://github.com/ExistentialAudio/BlackHole/blob/HEAD/BlackHole/BlackHole.c)
- [verified] Real workflows install BlackHole and restart coreaudiod on hosted runners. mozilla/cubeb (macos-15, macos-15-intel): `brew install switchaudio-osx; brew install blackhole-2ch; sudo kill -9 $(pgrep coreaudiod); sleep 10; SwitchAudioSource -s 'BlackHole 2ch' -t input/output`. orchetect/swift-core-audio (macos-15, macos-26): `brew install blackhole-2ch && sudo killall -9 coreaudiod`. cubeb-coreaudio-rs and audioipc do the same. Wang-Yue/cdsp installs blackhole-2ch and blackhole-16ch, then runs `sudo killall coreaudiod || true; sleep 2`.  (https://github.com/mozilla/cubeb/blob/HEAD/.github/workflows/build.yml; https://github.com/orchetect/swift-core-audio/blob/HEAD/.github/workflows/build.yml; https://github.com/mozilla/cubeb-coreaudio-rs/blob/HEAD/.github/workflows/test.yml)
- [verified] PairPods (MIT, a menu bar app that shares audio to multiple Bluetooth devices) installs blackhole-2ch and blackhole-16ch casks on macos-15 and macos-latest. On the hosted runner, its Swift Testing integration suite creates a stacked aggregate (kAudioAggregateDeviceIsStackedKey: 1, master UID, drift: 1 on non-master) from the two real BlackHole devices, sets it as the default output, then destroys it and restores the previous default. The tests are gated with ConditionTrait.enabled(if: BlackHoleHelper.isAvailable) and use @Suite(.serialized).  (https://github.com/wozniakpawel/PairPods/blob/HEAD/.github/workflows/ci.yml; https://github.com/wozniakpawel/PairPods/blob/HEAD/PairPodsTests/Integration/AudioRegressionTests.swift; https://github.com/wozniakpawel/PairPods/blob/HEAD/PairPodsTests/Integration/BlackHoleHelper.swift)
- [likely] PairPods' green CI does not prove its BlackHole aggregate tests actually ran: the workflow never restarts coreaudiod, and the gating trait skips the tests silently when the devices are absent. Job logs need sign-in to check.  (https://github.com/wozniakpawel/PairPods/actions/workflows/ci.yml; https://github.com/Homebrew/homebrew-cask/commit/57ca4e7)
- [verified] Starting audio IO (AudioDeviceStart) took about 180 s on hosted runners when the responsible process lacked kTCCServiceMicrophone. This was fixed in images deployed 2025-01-30 by adding Microphone TCC rows. configure-tccdb-macos.sh now grants kTCCServiceMicrophone, kTCCServiceScreenCapture and kTCCServiceBluetoothAlways to /opt/hca/hosted-compute-agent and /usr/local/opt/runner/provisioner/provisioner, in both the system and user TCC.db.  (https://github.com/actions/runner-images/issues/10902; https://github.com/actions/runner-images/blob/main/images/macos/scripts/build/configure-tccdb-macos.sh; https://github.com/actions/runner-images/issues/9330)
- [verified] Without Microphone TCC, CoreAudio can block indefinitely in AudioDeviceCreateIOProcID waiting for coreaudiod; Cap observed this on SIP-disabled Blacksmith runners and worked around it with sqlite3 INSERTs into both TCC.db files plus `killall tccd`.  (https://github.com/CapSoftware/Cap/blob/HEAD/.github/workflows/verify-recording.yml)
- [uncertain] Whether SIP is disabled on GitHub-hosted macOS images is not documented. The image build writes the system TCC.db with `sudo sqlite3`, and configure-system.sh labels one step 'requires SIP to be disabled', which suggests SIP is off at build time. A step running `csrutil status` would confirm it.  (https://github.com/actions/runner-images/blob/main/images/macos/scripts/helpers/utils.sh; https://github.com/actions/runner-images/blob/main/images/macos/scripts/build/configure-system.sh)
- [likely] The runner image enables autologin (configure-autologin.sh), so a GUI/WindowServer session exists. Screenshots in runner issues are 1024x768, which suggests a 1x display.  (https://github.com/actions/runner-images/blob/main/images/macos/templates/macOS-26.arm64.anka.pkr.hcl; https://github.com/actions/runner-images/issues/14161)
- [verified] AudioHardware.h aggregate keys: uid 'uid', name 'name', subdevices 'subdevices', kAudioAggregateDeviceMainSubDeviceKey 'master', clock 'clock', private 'private' (1 = private to the creating process and not persistent; absent or 0 = published system-wide), stacked 'stacked'. Sub-device keys: 'uid', 'name', 'channels-in', 'channels-out', 'latency-in', 'latency-out' (extra latency in frames), 'drift', 'drift quality'. AudioHardwareCreateAggregateDevice and AudioHardwareDestroyAggregateDevice are available from macOS 10.9.  (https://github.com/alexey-lysiuk/macos-sdk/blob/main/MacOSX26.5.sdk/System/Library/Frameworks/CoreAudio.framework/Versions/A/Headers/AudioHardware.h)
- [uncertain] Meaning of the stacked value is contradictory. The header says 'a value of 0 means ... the output streams are all fed the same data', and cubeb-coreaudio-rs sets 0 for a 'stacked aggregate (i.e. multi-output device)'. PairPods, Sapphire, karaggeorge/macos-audio-devices (shouldStack ? 1 : 0) and Atoll use 1 for a multi-output device. A CI test can settle it empirically.  (https://github.com/alexey-lysiuk/macos-sdk/blob/main/MacOSX26.5.sdk/System/Library/Frameworks/CoreAudio.framework/Versions/A/Headers/AudioHardware.h; https://github.com/mozilla/cubeb-coreaudio-rs/blob/HEAD/src/backend/aggregate_device.rs; https://github.com/wozniakpawel/PairPods/blob/HEAD/PairPods/CoreAudioSystem.swift)
- [verified] cubeb waits up to 5 s for a newly created aggregate to appear in kAudioHardwarePropertyDevices, and for its sub-device list to update, using a property listener and a condvar.  (https://github.com/mozilla/cubeb-coreaudio-rs/blob/HEAD/src/backend/aggregate_device.rs)
- [verified] kAudioHardwarePropertyRunLoop ('rnlp', now in AudioHardwareDeprecated.h): since 10.6 the HAL attaches notifications to CFRunLoopGetMain(); setting it to NULL makes the HAL create its own notification thread.  (https://github.com/alexey-lysiuk/macos-sdk/blob/main/MacOSX26.5.sdk/System/Library/Frameworks/CoreAudio.framework/Versions/A/Headers/AudioHardwareDeprecated.h)
- [verified] Alternatives to BlackHole: soundflower cask is deprecated 2024-11-16 and disabled 2025-11-16, x86_64 only, and is a kext, so not usable. background-music cask 0.5.0 installs '/Library/Audio/Plug-Ins/HAL/Background Music Device.driver'. loopback cask (2.5.0) is a commercial GUI app requiring macOS Sonoma and is not suited to CI.  (https://github.com/Homebrew/homebrew-cask/blob/HEAD/Casks/s/soundflower.rb; https://github.com/Homebrew/homebrew-cask/blob/HEAD/Casks/b/background-music.rb; https://github.com/Homebrew/homebrew-cask/blob/HEAD/Casks/l/loopback.rb)
- [verified] switchaudio-osx 1.2.2 has bottles for arm64_tahoe and arm64_golden_gate (macOS 27). Usage: SwitchAudioSource [-a] [-c] [-f cli|human|json] [-t input|output|system] [-m mute|unmute|toggle] [-n] -s name | -i id | -u uid.  (https://github.com/Homebrew/homebrew-core/blob/HEAD/Formula/s/switchaudio-osx.rb; https://github.com/deweller/switchaudio-osx/blob/master/README.md)
- [verified] ImageRenderer is available from macOS 13.0 and declared 'final class ImageRenderer<Content> where Content : View'. Apple docs: output only includes views SwiftUI rasterizes directly (text, images, shapes and composites); views composited by Core Animation layers (more complex controls and containers, web views, media players, most UIKit/AppKit views) render as a placeholder.  (https://developer.apple.com/documentation/swiftui/imagerenderer)
- [verified] PairPods' ScreenshotGenerator is a standalone SPM executable (swift-tools 5.9, .macOS(.v13)). It calls `_ = NSApplication.shared`, uses MainActor.assumeIsolated, ImageRenderer with scale 2.0, and converts nsImage through tiffRepresentation and NSBitmapImageRep to .png. It replaces Toggle and Slider with custom shape-drawn views and runs on GitHub-hosted macos-latest in release.yml (`swift build -c release; .build/release/ScreenshotGenerator --output-dir /tmp/screenshots`).  (https://github.com/wozniakpawel/PairPods/blob/HEAD/ScreenshotGenerator/Sources/ScreenshotGenerator/main.swift; https://github.com/wozniakpawel/PairPods/blob/HEAD/ScreenshotGenerator/Sources/ScreenshotGenerator/ScreenshotViews.swift; https://github.com/wozniakpawel/PairPods/blob/HEAD/.github/workflows/release.yml)
- [verified] swift-snapshot-testing renders an NSView with view.bitmapImageRepForCachingDisplay(in: view.bounds) plus view.cacheDisplay(in:to:), then wraps it in an NSImage. Its SwiftUI View strategy is iOS/tvOS only, so on macOS you wrap the view in NSHostingView or NSHostingController.  (https://github.com/pointfreeco/swift-snapshot-testing/blob/main/Sources/SnapshotTesting/Snapshotting/NSView.swift; https://github.com/pointfreeco/swift-snapshot-testing/blob/main/Sources/SnapshotTesting/Snapshotting/SwiftUIView.swift)
- [verified] SwiftPM `swift test` enables XCTest (when the toolchain supports it; on Darwin this needs Xcode, not just CLT) and Swift Testing by default. Flags: --enable-xctest/--disable-xctest, --enable-swift-testing/--disable-swift-testing. --filter <regex> (e.g. <target>.<case>/<test>) and --skip <regex> are forwarded to Swift Testing. Also --xunit-output, --attachments-path (Swift Testing only) and --list-tests. --parallel applies to XCTest process-level parallelism.  (https://github.com/swiftlang/swift-package-manager/blob/main/Sources/Commands/SwiftTestCommand.swift; https://github.com/swiftlang/swift-package-manager/blob/main/Sources/CoreCommands/Options.swift)
- [likely] Swift Testing runs tests in parallel in-process by default, so suites that change global Core Audio state (default device, aggregates) should use the .serialized trait, as PairPods does.  (https://github.com/wozniakpawel/PairPods/blob/HEAD/PairPodsTests/Integration/AudioRegressionTests.swift)
- [verified] Xcode 27 reports an XCTest or Swift Testing assertion called from the other framework as a runtime warning, and fixes cross-framework issues not being surfaced under the swift CLI in packages.  (https://developer.apple.com/documentation/xcode-release-notes/xcode-27-release-notes)

# SNIPPETS
# GitHub Actions job (pattern from mozilla/cubeb, orchetect/swift-core-audio and Cap's wait loop; not run by me)
jobs:
  test:
    runs-on: macos-26            # arm64, Xcode 26.6 / Swift 6.3; add `xcode-27` to try Xcode 27 / macOS 27
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v5
      - run: sw_vers && xcodebuild -version && swift --version && (csrutil status || true)
      - name: Install virtual audio devices
        env: { HOMEBREW_NO_AUTO_UPDATE: "1", HOMEBREW_NO_INSTALL_CLEANUP: "1" }
        run: |
          brew install --cask blackhole-2ch blackhole-16ch
          brew install switchaudio-osx
          sudo killall -9 coreaudiod || true      # cask no longer does this; kickstart is SIP-blocked
          for _ in $(seq 1 30); do
            outs="$(SwitchAudioSource -a -t output)"
            echo "$outs" | grep -qx 'BlackHole 2ch' && echo "$outs" | grep -qx 'BlackHole 16ch' && break
            sleep 1
          done
          SwitchAudioSource -a -t output
          system_profiler SPAudioDataType
      - name: Unit tests
        run: swift test --skip IntegrationTests
      - name: Core Audio integration tests
        env: { SPEAKERLINK_AUDIO_IT: "1" }   # make missing BlackHole a FAILURE, not a silent skip
        run: swift test --filter IntegrationTests

---
// Swift Testing: gate tests on an env var; fail instead of skipping on CI (pattern from PairPods AudioRegressionTests)
import Testing
import Foundation
let audioIT = ProcessInfo.processInfo.environment["SPEAKERLINK_AUDIO_IT"] == "1"

@Suite("IntegrationTests", .serialized, .enabled(if: audioIT, "set SPEAKERLINK_AUDIO_IT=1"))
struct IntegrationTests {
  @Test func stackedAggregateOfTwoBlackHoles() async throws {
    let a = try #require(deviceID(forUID: "BlackHole2ch_UID"), "BlackHole 2ch missing")
    let b = try #require(deviceID(forUID: "BlackHole16ch_UID"), "BlackHole 16ch missing")
    _ = (a, b) // ... create aggregate, set default, assert, destroy
  }
}
// XCTest equivalent:
// try XCTSkipUnless(ProcessInfo.processInfo.environment["SPEAKERLINK_AUDIO_IT"] == "1", "integration tests disabled")

---
// Stacked (multi-output) aggregate from BlackHole UIDs. Keys from AudioHardware.h (macOS 26.5 SDK); stacked: 1 follows PairPods/CoreAudioSystem.swift
import CoreAudio
func makeStacked(uids: [String], mainUID: String, extraLatencyFrames: [String: Int] = [:]) throws -> AudioObjectID {
  let subs: [[String: Any]] = uids.map { uid in
    var d: [String: Any] = [kAudioSubDeviceUIDKey: uid]
    if uid != mainUID { d[kAudioSubDeviceDriftCompensationKey] = 1 }           // "drift"
    if let f = extraLatencyFrames[uid] { d[kAudioSubDeviceExtraOutputLatencyKey] = f } // "latency-out", in frames
    return d
  }
  let desc: [String: Any] = [
    kAudioAggregateDeviceNameKey: "SpeakerLink Test",
    kAudioAggregateDeviceUIDKey: "app.speakerlink.test.\(UUID().uuidString)", // unique per run; public aggregates persist
    kAudioAggregateDeviceSubDeviceListKey: subs,
    kAudioAggregateDeviceMainSubDeviceKey: mainUID,                             // "master"
    kAudioAggregateDeviceIsStackedKey: 1,                                       // "stacked" (header text is ambiguous; assert channel count below)
    // no kAudioAggregateDeviceIsPrivateKey => published system-wide (needed for default output)
  ]
  var id = AudioObjectID(kAudioObjectUnknown)
  let st = AudioHardwareCreateAggregateDevice(desc as CFDictionary, &id)
  guard st == noErr else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(st)) }
  return id // then poll kAudioHardwarePropertyDevices (cubeb waits up to 5 s) before use;
            // check output channels via kAudioDevicePropertyStreamConfiguration (stacked ~2, non-stacked 2+16=18)
}

---
# Diagnose/repair microphone TCC if AudioDeviceStart hangs (~180 s); row format from runner-images#10902; the user DB needs no SIP change
sqlite3 "$HOME/Library/Application Support/com.apple.TCC/TCC.db" "INSERT OR IGNORE INTO access VALUES ('kTCCServiceMicrophone','/opt/hca/hosted-compute-agent',1,2,4,1,NULL,NULL,0,'UNUSED',NULL,0,1687786159,NULL,NULL,'UNUSED',1687786159);"

---
// README screenshots: ImageRenderer route (pattern from PairPods ScreenshotGenerator/main.swift). Only shapes/text/images render; AppKit controls show placeholders
import AppKit; import SwiftUI
_ = NSApplication.shared   // "required for ImageRenderer to work" (PairPods)
try MainActor.assumeIsolated {
  let r = ImageRenderer(content: MenuContentPreview().environment(\.colorScheme, .dark))
  r.scale = 2.0
  guard let img = r.nsImage, let tiff = img.tiffRepresentation,
        let rep = NSBitmapImageRep(data: tiff), let png = rep.representation(using: .png, properties: [:]) else { exit(1) }
  try png.write(to: URL(fileURLWithPath: "docs/menu-dark.png"))
}

---
// Fallback for real Toggle/Slider: NSHostingView + cacheDisplay (approach from swift-snapshot-testing NSView.swift); manual 2x rep because CI display is ~1x. Not CI-verified
@MainActor func renderHosted<V: View>(_ v: V, size: CGSize, scale: CGFloat = 2, dark: Bool) -> Data? {
  _ = NSApplication.shared
  let host = NSHostingView(rootView: v.frame(width: size.width, height: size.height))
  host.frame = CGRect(origin: .zero, size: size)
  host.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
  let win = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false) // borderless: no title-bar height
  win.contentView = host
  host.layoutSubtreeIfNeeded()
  RunLoop.main.run(until: Date().addingTimeInterval(0.2))
  guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width * scale), pixelsHigh: Int(size.height * scale),
        bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0) else { return nil }
  rep.size = size
  host.cacheDisplay(in: host.bounds, to: rep)
  return rep.representation(using: .png, properties: [:])
}

---

# RISKS
- Integration tests gated with .enabled(if:) or XCTSkip pass green when BlackHole never appears, which probably happens to PairPods since it never restarts coreaudiod. On CI, set an env var that turns 'device missing' into a failure, and restart coreaudiod plus poll before running tests.
- The built-in 'Null Audio Device' goes missing on about 30% of runs (runner-images#13668, open). Don't depend on it. Don't assume the default output exists until BlackHole is installed and selected.
- The meaning of kAudioAggregateDeviceIsStackedKey (0 vs 1) differs between the header text and widespread practice. Add a CI assertion on the output channel count, or the composition read back with kAudioAggregateDevicePropertyComposition, so a wrong value is caught.
- BlackHole reports kAudioDeviceTransportTypeVirtual, not Bluetooth (kAudioDeviceTransportTypeBluetooth / BluetoothLE). Code that filters for Bluetooth outputs needs an injectable device-listing layer (like PairPods' AudioSystemQuerying + SimulatedAudioSystem) to be testable with BlackHole.
- Public aggregate devices persist after the test process exits. Use unique UIDs and always destroy them (defer), and restore the original default output. On ephemeral runners this matters less, but locally it leaves junk devices.
- Running IO, or launching the .app with `open` instead of via `swift test`, may block in AudioDeviceStart / AudioDeviceCreateIOProcID if the responsible process lacks Microphone TCC. BlackHole devices have input streams. Hosted images pre-grant only the runner agent binaries.
- SIP status on hosted runners is undocumented. Avoid `launchctl kickstart` (blocked under SIP since 14.4) and use `sudo killall -9 coreaudiod`. Add a `csrutil status` step if a test depends on it.
- IOBluetooth on runners: there is probably no Bluetooth controller in the VMs (unverified), although kTCCServiceBluetoothAlways is granted to the agent. Design tests so IOBluetooth paths are mocked or skipped. Don't expect pairedDevices() results on CI.
- ImageRenderer draws Toggle, Slider, Picker, List, ScrollView, materials and other AppKit-backed views as placeholders. Either use custom shape-based preview views (as PairPods does) or the NSHostingView + cacheDisplay route; the latter's fidelity on a headless 1x runner is not verified.
- The xcode-27 label is a public preview (macOS 27.0.1). The 27.1 RC and 27.2 beta are present; pin with maxim-lobanov/setup-xcode or `sudo xcode-select -s /Applications/Xcode_26.6.app`. macOS 14 images are going away, and PairPods' release.yml pins Xcode 16.2, which macos-latest (macos-26) no longer has.
- Hosted runner labels, image contents and Homebrew casks change frequently. The BlackHole cask could switch versions or re-add or remove coreaudiod handling; the restart-and-poll loop tolerates either.
- PairPods is MIT-licensed prior art with nearly the same feature set (per-device volume, master clock, auto-reconnect). Reusing its code in a GPL-3.0 app is license-compatible with attribution, but it is also a competing product worth noting in the README.
- Dynamic devices appear asynchronously after AudioHardwareCreateAggregateDevice and after a coreaudiod restart. Tests need explicit wait/poll with timeouts (cubeb uses 5 s), and listener blocks should run on a private DispatchQueue, since the test process may not service the main run loop.

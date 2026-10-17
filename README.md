# SpeakerLink

Use several Bluetooth speakers and headphones together. Open source, no ads, no tracking.

* **SpeakerLink for Mac** plays the same audio on several Bluetooth headphones and speakers at
  once, of any brand. Two people can watch a film with their own headphones. It has a volume for
  each device, a delay for each device to line them up, and volume keys that keep working.
* **SpeakerLink for Android** keeps several speakers and headphones connected and moves the
  music between them with one tap. It shows your phone's own ways to play on two at once
  (Samsung Dual Audio, Android Audio sharing), and has extras for JBL speakers: model,
  battery and a guide for linking them.

## What works where

| | Mac | Android |
| --- | --- | --- |
| Same audio on several Bluetooth devices at once | Yes, any brand. Two or three Bluetooth devices is the practical limit | Only with Samsung Dual Audio (two devices), Android Audio sharing (LE Audio devices, some Android 16 phones) or speakers that link to each other themselves |
| Several devices connected, switch the playing one | Yes | Yes, up to five connected |
| Volume per device | Yes | Each device's own volume |
| Delay per device (lip sync, headphones in step) | Yes, manual or estimated | No |

Android itself sends media to one Bluetooth device at a time; no app can change that. macOS can
send it to several through a *multi-output device*, which SpeakerLink for Mac sets up and
manages for you.

## Download

* **Mac** (macOS 14 or later): download `SpeakerLink-<version>.zip` from the
  [releases](https://github.com/Cyrus-Sch/SpeakerLink/releases) tagged `mac-v…`, unzip it and
  move SpeakerLink to Applications. See [mac/README.md](mac/README.md) for the first launch
  (until the app is notarized, macOS asks you to allow it once) and for how to use it.
* **Android** (Android 8 or later): download `SpeakerLink-<version>.apk` from the releases tagged
  `android-v…`. F-Droid is planned.

## SpeakerLink for Mac

A menu bar app. Tick the headphones and speakers that should play, switch on **Listen
together**, and everything the Mac plays (films, videos, music) comes out of all of them.

* Connect paired Bluetooth devices from the menu; pair new ones in System Settings.
* Master volume (also on the keyboard's volume keys) and a level for each device.
* A delay for each device, an estimate from what the devices report, and test clicks to tune
  by ear. Delay can only hold faster devices back.
* Turns itself off cleanly: when you quit, the Mac goes back to the output it used before.

How it works: SpeakerLink creates a Core Audio multi-output device (a "stacked" aggregate) from
the selected outputs and makes it the system output, with drift correction for every device
except the main one. Source code and details: [mac/](mac/).

## SpeakerLink for Android

* Lists every paired Bluetooth speaker and headphone, connects several at once and shows which
  one is playing; "Play here" moves the audio.
* **Together** tab: what your phone can do itself (Samsung Dual Audio, Android Audio sharing, or
  switching), and how speakers that link to each other fit in.
* **JBL extras:** model identification, battery and firmware of many JBL portables, the
  speaker's locate sound, and a planner for JBL's own linking (PartyBoost, Auracast, Connect+,
  PartyBox TWS) that picks a host and shows which buttons to press.

<details>
<summary>Which JBL speakers link together</summary>

| System | Models |
| --- | --- |
| Auracast | Flip 7, Charge 6, Go 4, Clip 5, Xtreme 4, Xtreme 5, Go 5, Boombox 4, PartyBox Club 120 / Stage 320 / 520 / 720 / Encore 2 / Encore Essential 2 (after firmware updates) |
| PartyBoost | Flip 5, Flip 6, Charge 5, Pulse 4, Pulse 5, Xtreme 3, Xtreme 4, Boombox 2, Boombox 3 |
| Connect+ | Flip 4, Charge 3 (after its update), Charge 4, Pulse 3, Xtreme 2, Boombox |
| JBL Connect (2 speakers) | Flip 3, Pulse 2, Xtreme, Boost TV, Charge 3 (original firmware) |
| PartyBox TWS (2 boxes) | PartyBox 110 / 310 / 710 / 1000 / Encore / Encore Essential, Club 120, Stage 320 (different models in JBL's PartyBox app); On-The-Go and On-The-Go Essential (only with each other) |
| PartyBox TWS, classic (2 boxes) | PartyBox 100 (only with another 100), PartyBox 200 / 300 (with each other) |

The Xtreme 4 joins both PartyBoost and Auracast groups (one at a time), and the Charge 3 links
with JBL Connect speakers on its original firmware and with Connect+ after its update. Go 3,
Clip 4 and older Go/Clip models cannot link. The catalog lives in
[`ModelCatalog.kt`](android/core/src/main/kotlin/io/github/cyrus_sch/speakerlink/core/ModelCatalog.kt).

</details>

### Android permissions

| Permission | Why |
| --- | --- |
| `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT` | Android 12+: find, pair and connect devices ("Nearby devices") |
| `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (all `maxSdkVersion=30`) | Android 8 to 11: Bluetooth discovery requires them |

The app has no internet permission. Connecting a device and choosing the playing one call
`BluetoothA2dp.connect`, `disconnect`, `setActiveDevice` and `getActiveDevice` through
reflection
([`HiddenBluetooth.kt`](android/app/src/main/java/io/github/cyrus_sch/speakerlink/bluetooth/HiddenBluetooth.kt)).
They are not in the public SDK, but they sit on Android's non-SDK "unsupported" list (allowed for
apps) on Android 12 to 17, and the Bluetooth service checks only `BLUETOOTH_CONNECT`. If a phone
blocks them, the app falls back to the system Bluetooth screens.

## Building

**Android** (JDK 17 or newer, Android SDK with platform 37):

```sh
cd android
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

`android/core` is pure Kotlin (JBL catalog, identification, linking planner, JBL control
protocol) and is unit-tested on the JVM; `android/app` is the app (Jetpack Compose,
Material 3). Toolchain: Android Gradle Plugin 9.3.3, Gradle 9.7.1, Kotlin 2.4.21, compileSdk 37,
targetSdk 36, minSdk 26.

**Mac** (Xcode 16 or newer, Swift 6):

```sh
cd mac
swift build
swift test
scripts/build-app.sh            # builds build/SpeakerLink.app and a zip
```

Continuous integration builds and tests both apps on every change
(`.github/workflows/android.yml`, `.github/workflows/mac.yml`): unit tests, lint, a check that two
Android release builds are byte-for-byte identical, emulator tests, Mac Core Audio tests against
virtual audio devices, and the store and README screenshots. Tags `android-v*` and `mac-v*`
publish releases; see [PUBLISHING.md](PUBLISHING.md).

## Sources and credits

* JBL compatibility data: JBL support and release-note pages, JBL press releases and independent
  reviews. The JBL Bluetooth LE control protocol was re-implemented from the protocol notes of
  [connect-plus](https://github.com/pembem22/connect-plus) (GPL-3.0),
  [JBLControl](https://github.com/ffrafat/JBLControl) (MIT),
  [openjbl](https://github.com/NiceDayZc/openjbl) (MIT) and
  [jbl-charge5](https://github.com/hudsonbrendon/jbl-charge5) (MIT), with no code copied.
* The Mac app's Core Audio approach follows Apple's headers and the open-source apps
  [AudioShare](https://github.com/2geik/AudioShare) (MIT) and
  [PairPods](https://github.com/wozniakpawel/PairPods) (MIT).
* Icons: [Material Symbols](https://github.com/google/material-design-icons) (Apache-2.0) and SF
  Symbols on the Mac.

## Trademarks

SpeakerLink is an independent project. It is not affiliated with, endorsed or sponsored by any
of the companies below. JBL, PartyBoost and Connect+ are trademarks of HARMAN International
Industries, Incorporated. Auracast and Bluetooth are trademarks of the Bluetooth SIG. Mac,
macOS and AirPods are trademarks of Apple Inc. Samsung and Galaxy are trademarks of Samsung
Electronics Co., Ltd. Android is a trademark of Google LLC.

## License

Copyright (C) 2026 Cyrus-Sch

SpeakerLink is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](LICENSE).

# SpeakerLink

An open-source Android app for using several JBL Bluetooth speakers together.

* Find, pair and connect several JBL speakers at the same time.
* See which speaker is playing and move the music to another one with one tap.
* **Party planner:** works out which of your speakers can play together (PartyBoost, Auracast,
  Connect+ or PartyBox TWS), picks a host and walks you through linking them.
* Warns about speakers that can't be linked, and offers alternatives such as Samsung Dual Audio
  or Android Audio sharing when the phone supports them.
* Test sound, output switcher and Bluetooth settings shortcuts.
* Battery level and firmware of many JBL portables (beta).
* No internet permission, no ads, no tracking.

SpeakerLink is not affiliated with, endorsed or sponsored by JBL or HARMAN International. JBL,
PartyBoost and Connect+ are trademarks of HARMAN International Industries, Incorporated.
Auracast is a trademark of the Bluetooth SIG.

## How "several speakers at once" works on Android

Stock Android keeps up to five Bluetooth audio devices *connected*, but it streams media to only
one of them, the *active* device. No app can change that. The working ways to hear the same
music on several speakers are:

| Way | What it needs | What SpeakerLink does |
| --- | --- | --- |
| JBL speaker-to-speaker linking (PartyBoost, Auracast, Connect+, PartyBox TWS) | Speakers of the **same** link system | Groups your speakers, picks and connects the host, shows the button presses |
| Samsung Dual Audio | A Galaxy phone; up to 2 speakers of any kind | Explains it and opens the media output panel |
| Android Audio sharing (LE Audio broadcast) | Android 16+ phone and LE Audio sinks (most JBL portables can't receive it) | Detects support and opens the setting |
| Switching | Any phone | Keeps several speakers connected and switches the playing one |

### Which JBL speakers link together

| System | Models |
| --- | --- |
| Auracast | Flip 7, Charge 6, Go 4, Clip 5, Xtreme 4, Xtreme 5, Go 5, Boombox 4, PartyBox Club 120 / Stage 320 / 520 / 720 / Encore 2 / Encore Essential 2 (after firmware updates) |
| PartyBoost | Flip 5, Flip 6, Charge 5, Pulse 4, Pulse 5, Xtreme 3, Xtreme 4, Boombox 2, Boombox 3 |
| Connect+ | Flip 4, Charge 3 (after its update), Charge 4, Pulse 3, Xtreme 2, Boombox |
| JBL Connect (2 speakers) | Flip 3, Pulse 2, Xtreme, Boost TV, Charge 3 (original firmware) |
| PartyBox TWS (2 boxes) | PartyBox 110 / 310 / 710 / 1000 / Encore / Encore Essential, Club 120, Stage 320 (different models in JBL's PartyBox app); On-The-Go and On-The-Go Essential (only with each other) |
| PartyBox TWS, classic (2 boxes) | PartyBox 100 (only with another 100), PartyBox 200 / 300 (with each other) |

The Xtreme 4 joins both PartyBoost and Auracast groups (one at a time), and the Charge 3 links
with JBL Connect speakers on its original firmware and with Connect+ after its update. Go 3, Clip 4 and older
Go/Clip models cannot link. The catalog lives in
[`android/core/src/main/kotlin/.../ModelCatalog.kt`](android/core/src/main/kotlin/io/github/cyrus_sch/speakerlink/core/ModelCatalog.kt).

## Permissions

| Permission | Why |
| --- | --- |
| `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT` | Android 12+: find, pair and connect speakers ("Nearby devices") |
| `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (all `maxSdkVersion=30`) | Android 8 to 11: Bluetooth discovery requires them |

Connecting a speaker and choosing the playing speaker call `BluetoothA2dp.connect`,
`disconnect`, `setActiveDevice` and `getActiveDevice` through reflection
([`HiddenBluetooth.kt`](android/app/src/main/java/io/github/cyrus_sch/speakerlink/bluetooth/HiddenBluetooth.kt)).
They are not in the public SDK, but they sit on Android's non-SDK "unsupported" list (allowed for
apps) on Android 12 to 17, and the Bluetooth service checks only `BLUETOOTH_CONNECT`. If a device
blocks them, the app falls back to the system Bluetooth screens.

## Building

Requirements: JDK 17 or newer (CI and F-Droid use JDK 21) and the Android SDK with platform 37.

```sh
cd android
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

* `core/`: pure Kotlin. The model catalog, name and advertisement identification, the party
  planner and the JBL control protocol codec, all unit-tested on the JVM.
* `app/`: the Android app (Jetpack Compose, Material 3).

Toolchain: Android Gradle Plugin 9.3.3 (built-in Kotlin), Gradle 9.7.1, Kotlin 2.4.21,
Compose BOM 2026.09.00, compileSdk 37, targetSdk 36, minSdk 26.

Continuous integration (`.github/workflows/android.yml`) builds, runs unit tests and lint,
checks that two release builds are byte-for-byte identical, runs instrumented tests on an
emulator (which also produces the store screenshots), and publishes signed releases for
`android-v*` tags. See [PUBLISHING.md](PUBLISHING.md).

## Sources

The JBL compatibility data comes from JBL support and release-note pages, JBL press releases and
independent reviews. The Bluetooth LE control protocol was re-implemented from the protocol notes
of these open-source projects, with no code copied:
[connect-plus](https://github.com/pembem22/connect-plus) (GPL-3.0),
[JBLControl](https://github.com/ffrafat/JBLControl) (MIT),
[openjbl](https://github.com/NiceDayZc/openjbl) (MIT) and
[jbl-charge5](https://github.com/hudsonbrendon/jbl-charge5) (MIT).
Icons: [Material Symbols](https://github.com/google/material-design-icons) (Apache-2.0).

## License

Copyright (C) 2026 Cyrus-Sch

SpeakerLink is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](LICENSE).

# SpeakerLink for Mac

A free, open-source menu bar app that plays the same audio on several Bluetooth headphones and
speakers at once. Two people can watch a film on one Mac, each with their own headphones, or you
can add a speaker in the next room. It works with any brand.

* Any mix of Bluetooth headphones, Bluetooth speakers and the Mac's own outputs.
* Volume for each device.
* Sync delay for each device, with test clicks to line them up by ear.
* Connects your paired Bluetooth headphones and speakers from the menu.
* No network access, no analytics.

## Requirements

macOS 14 Sonoma or later, on an Apple silicon or Intel Mac. Pair your headphones and speakers
in System Settings > Bluetooth first: SpeakerLink connects paired devices but does not pair new
ones.

## Install

1. Download `SpeakerLink-<version>.zip` from the
   [GitHub Releases](https://github.com/Cyrus-Sch/speakerlink/releases) (the "SpeakerLink for
   Mac" ones). You can check it against the `.sha256` file with `shasum -a 256 -c`.
2. Open the zip and move **SpeakerLink.app** to your Applications folder.
3. Open SpeakerLink. Its icon appears in the menu bar; there is no Dock icon.

The first time SpeakerLink lists your devices, macOS asks whether it may use Bluetooth. Allow it,
otherwise it can't show or connect your paired devices. You can change this later in System
Settings > Privacy & Security > Bluetooth.

### If macOS blocks the app

Releases whose notes say they are not notarized are only ad-hoc signed, and macOS blocks them the
first time. On macOS 15 Sequoia and later (Control-click > Open no longer works there):

1. Open SpeakerLink. macOS says it could not verify that "SpeakerLink" is free of malware.
   Click **Done**.
2. Open **System Settings > Privacy & Security** and scroll down to **Security**.
3. Next to the message about SpeakerLink, click **Open Anyway**. The button is shown for about an
   hour after you tried to open the app.
4. Confirm with **Open Anyway** and enter your login password (or use Touch ID) when asked.
   macOS remembers this, so later launches open normally.

On macOS 14 Sonoma you can instead Control-click the app in Finder and choose **Open**.

Alternatively, remove the quarantine flag in Terminal:

```sh
xattr -dr com.apple.quarantine /Applications/SpeakerLink.app
```

## Use

1. Connect your headphones and speakers. Paired devices that are not connected are listed in the
   SpeakerLink menu, and you can connect them from there.
2. Tick the devices you want to hear, then choose **Listen together**. They become the Mac's
   sound output, so every app (browser, video player, music) plays on all of them.
3. Set each device's volume with its slider in SpeakerLink.
4. If you hear an echo between devices, play the test clicks and change each device's sync delay
   until the clicks sound together. A delay holds a device back, so add it to the device that is
   ahead (usually a wired or built-in speaker, or the faster of two headphones).
5. Stop listening together, or quit SpeakerLink, to go back to your previous sound output.

## Limits

* **Two or three Bluetooth devices** is the practical maximum. They share the Mac's one
  Bluetooth radio with your keyboard, mouse and trackpad, and more devices can stutter or drop.
* **Sync is by ear.** Bluetooth devices play about 80 to 300 ms late, depending on the model and
  codec. SpeakerLink starts from the latency each device reports to macOS, but Bluetooth devices
  often report it wrong, so that is only a starting point. Devices can drift a little after a pause or a reconnect; check with the test
  clicks again.
* **Volume keys:** macOS's volume keys and the sound slider in Control Center can't control a
  multi-output device, so SpeakerLink sets each device's own volume instead. A few devices don't
  let the Mac change their volume; use the buttons on the device.
* **AirPlay** speakers and TVs can't join.
* **AirPods "Share Audio"** exists only on iPhone and iPad. On a Mac, connect each pair of AirPods
  (or other headphones) as its own device and tick them in SpeakerLink.
* **Microphone:** avoid using a headset's microphone (for example in a call) while listening
  together. It switches the headset to low-quality call audio.

## Privacy

SpeakerLink uses the Bluetooth permission only to list your paired devices and connect them. It
doesn't record audio, has no network access and collects no analytics. Its settings stay on your
Mac.

## Build from source

Requirements: Xcode 16 or later (Swift 6). Universal builds need the full Xcode, not only the
Command Line Tools.

```sh
cd mac
swift build                                # debug build
swift test --filter SpeakerLinkCoreTests   # unit tests
scripts/build-app.sh                       # build/SpeakerLink.app and build/SpeakerLink-<version>.zip
scripts/build-app.sh --universal           # the same for Apple silicon and Intel
open build/SpeakerLink.app
```

Run the app from the bundle: the Bluetooth permission belongs to the app bundle, so the bare
binary in `.build` can't get it. `scripts/build-app.sh` signs ad-hoc, with the bundle identifier
as the designated requirement so that macOS keeps the Bluetooth permission across rebuilds. Set
`SPEAKERLINK_SIGN_IDENTITY` to a Developer ID identity to sign for distribution instead. An app
you build yourself is not quarantined, so Gatekeeper doesn't block it.

The Core Audio integration tests need the [BlackHole](https://github.com/ExistentialAudio/BlackHole)
virtual devices "BlackHole 2ch" and "BlackHole 16ch". They create and remove multi-output devices
and briefly change the default output:

```sh
brew install --cask blackhole-2ch blackhole-16ch
sudo killall -9 coreaudiod    # makes the new devices appear
SPEAKERLINK_AUDIO_TESTS=1 swift test --filter SpeakerLinkAudioTests
```

`build/SpeakerLink.app/Contents/MacOS/SpeakerLink --screenshot <dir>` renders the menu with sample
devices to `menu-light.png` and `menu-dark.png`, without touching audio or Bluetooth.

Layout:

* `Sources/SpeakerLinkCore`: pure Swift logic with no Apple frameworks, unit-tested.
* `Sources/SpeakerLinkAudio`: Core Audio and IOBluetooth.
* `Sources/SpeakerLink`: the SwiftUI menu bar app.
* `App/`: Info.plist template and icon; `VERSION` and `BUILD` hold the version and build number.

Continuous integration ([`.github/workflows/mac.yml`](../.github/workflows/mac.yml)) builds with
warnings as errors, runs the unit tests and the integration tests against BlackHole, builds the
universal app and renders the screenshots (artifact `speakerlink-mac`). A `mac-v<version>` tag
publishes a GitHub release with the zip and its SHA-256, using the notes from
[CHANGELOG.md](CHANGELOG.md). When the secrets `MAC_DEVELOPER_ID_P12_BASE64`,
`MAC_DEVELOPER_ID_P12_PASSWORD`, `MAC_SIGN_IDENTITY`, `MAC_NOTARY_KEY_P8_BASE64`,
`MAC_NOTARY_KEY_ID` and `MAC_NOTARY_ISSUER_ID` are all set, the release is signed with the
Developer ID and notarized; otherwise it is ad-hoc signed and its notes explain how to open it.

## How it works

SpeakerLink builds a Core Audio **multi-output device**, the same kind of device that Audio MIDI
Setup makes: a public, stacked aggregate device whose sub-devices are the outputs you ticked, all
fed the same audio. It makes that device the system's default output, so every app plays through
it, and puts your previous output back when you stop.

* One device provides the clock; Core Audio's drift correction resamples the others so that they
  stay in step over a long film.
* A device's sync delay is its sub-device's extra output latency, so Core Audio applies it.
* The multi-output device has no volume of its own, so SpeakerLink sets the volume of each device.
* Bluetooth devices are listed and connected with IOBluetooth and matched to their Core Audio
  outputs by Bluetooth address.

## Credits

The Core Audio approach draws on ideas from the MIT-licensed projects
[AudioShare](https://github.com/2geik/AudioShare),
[PairPods](https://github.com/wozniakpawel/PairPods) and
[CAAudioHardware](https://github.com/sbooth/CAAudioHardware).

## License

Copyright (C) 2026 Cyrus-Sch

SpeakerLink is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](../LICENSE).

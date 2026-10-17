// swift-tools-version: 6.0
// SPDX-License-Identifier: GPL-3.0-or-later

import PackageDescription

let package = Package(
    name: "SpeakerLink",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "SpeakerLink", targets: ["SpeakerLink"]),
    ],
    targets: [
        // Pure logic (Foundation only): device rules, group plans, maths, settings.
        .target(
            name: "SpeakerLinkCore",
            path: "Sources/SpeakerLinkCore"
        ),
        // Core Audio HAL, IOBluetooth and the test-click player.
        .target(
            name: "SpeakerLinkAudio",
            dependencies: ["SpeakerLinkCore"],
            path: "Sources/SpeakerLinkAudio",
            linkerSettings: [
                .linkedFramework("CoreAudio"),
                .linkedFramework("IOBluetooth"),
                .linkedFramework("CoreBluetooth"),
                .linkedFramework("AVFoundation"),
            ]
        ),
        // The menu bar app (SwiftUI + AppKit).
        .executableTarget(
            name: "SpeakerLink",
            dependencies: ["SpeakerLinkCore", "SpeakerLinkAudio"],
            path: "Sources/SpeakerLink",
            linkerSettings: [
                .linkedFramework("ServiceManagement"),
            ]
        ),
        .testTarget(
            name: "SpeakerLinkCoreTests",
            dependencies: ["SpeakerLinkCore"],
            path: "Tests/SpeakerLinkCoreTests"
        ),
        .testTarget(
            name: "SpeakerLinkAudioTests",
            dependencies: ["SpeakerLinkAudio", "SpeakerLinkCore"],
            path: "Tests/SpeakerLinkAudioTests"
        ),
    ]
)

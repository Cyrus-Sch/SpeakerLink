// SPDX-License-Identifier: GPL-3.0-or-later

import AppKit
import Foundation
import SpeakerLinkCore
import SwiftUI

/// `SpeakerLink --screenshot <dir>`: writes `menu-light.png` and `menu-dark.png` of the menu
/// window, filled with ``SampleData``, at 2x scale.
///
/// The view is hosted in an `NSHostingView` inside an off-screen window and drawn with
/// `cacheDisplay(in:to:)`, because SwiftUI's `ImageRenderer` cannot draw AppKit-backed
/// controls such as toggles and sliders.
@MainActor
enum ScreenshotCommand {
    enum Request: Equatable {
        case runApp
        case render(URL)
        case usageError(String)
    }

    static let usage = "Usage: SpeakerLink --screenshot <output directory>"

    static func parse(_ arguments: [String]) -> Request {
        guard let flag = arguments.firstIndex(of: "--screenshot") else { return .runApp }
        let valueIndex = flag + 1
        guard valueIndex < arguments.count, !arguments[valueIndex].hasPrefix("-") else {
            return .usageError(usage)
        }
        return .render(URL(fileURLWithPath: arguments[valueIndex], isDirectory: true))
    }

    /// Renders both appearances. Returns the process exit code.
    static func run(outputDirectory: URL) -> Int32 {
        let application = NSApplication.shared
        application.setActivationPolicy(.accessory)
        do {
            try FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)
            for dark in [false, true] {
                let file = outputDirectory.appending(path: dark ? "menu-dark.png" : "menu-light.png")
                try MenuSnapshot.render(dark: dark, to: file)
                print("Wrote \(file.path(percentEncoded: false))")
            }
            return 0
        } catch {
            printError("Screenshot failed: \(error.localizedDescription)")
            return 1
        }
    }

    static func printError(_ message: String) {
        try? FileHandle.standardError.write(contentsOf: Data((message + "\n").utf8))
    }
}

/// Draws the menu window into a PNG.
@MainActor
enum MenuSnapshot {
    enum SnapshotError: LocalizedError {
        case bitmapUnavailable
        case encodingFailed

        var errorDescription: String? {
            switch self {
            case .bitmapUnavailable: return "Could not create the bitmap."
            case .encodingFailed: return "Could not encode the PNG."
            }
        }
    }

    static let scale: CGFloat = 2

    static func render(dark: Bool, to url: URL) throws {
        let appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
        let root = MenuContentView(model: SampleData.model())
            .background(Color(nsColor: .windowBackgroundColor))
            .environment(\.colorScheme, dark ? .dark : .light)
        let host = NSHostingView(rootView: root)
        host.appearance = appearance

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: MenuContentView.width, height: 800),
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        window.appearance = appearance
        window.contentView = host

        host.layoutSubtreeIfNeeded()
        let fitting = host.fittingSize
        let size = NSSize(width: ceil(max(fitting.width, MenuContentView.width)), height: ceil(fitting.height))
        window.setContentSize(size)
        host.frame = NSRect(origin: .zero, size: size)
        window.setFrameOrigin(NSPoint(x: -10_000, y: -10_000))
        window.orderFrontRegardless()

        // Give SwiftUI and AppKit a moment to lay out and draw the controls.
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        host.layoutSubtreeIfNeeded()
        host.display()

        // The CI display is 1x, so build the 2x bitmap explicitly.
        guard let bitmap = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: Int(size.width * scale),
            pixelsHigh: Int(size.height * scale),
            bitsPerSample: 8,
            samplesPerPixel: 4,
            hasAlpha: true,
            isPlanar: false,
            colorSpaceName: .deviceRGB,
            bytesPerRow: 0,
            bitsPerPixel: 0
        ) else {
            throw SnapshotError.bitmapUnavailable
        }
        bitmap.size = size
        host.cacheDisplay(in: host.bounds, to: bitmap)
        window.orderOut(nil)

        guard let png = bitmap.representation(using: .png, properties: [:]) else {
            throw SnapshotError.encodingFailed
        }
        try png.write(to: url, options: .atomic)
    }
}

/// Built-in sample data: two headphones and one speaker playing together, plus the Mac's
/// speakers. Names are made up.
@MainActor
enum SampleData {
    static func model() -> AppModel {
        let airPods = OutputDevice(
            objectID: 101, uid: "1C-57-DC-4A-10-01:output", name: "Alex's AirPods Pro",
            transport: .bluetooth, sampleRate: 48_000, volume: 0.6
        )
        let headphones = OutputDevice(
            objectID: 102, uid: "AC-80-0A-2B-33-02:output", name: "Sam's Headphones",
            transport: .bluetooth, sampleRate: 48_000, volume: 0.5
        )
        let speaker = OutputDevice(
            objectID: 103, uid: "F8-DF-15-6C-77-03:output", name: "Living Room Speaker",
            transport: .bluetooth, sampleRate: 44_100, volume: 0.4
        )
        let macSpeakers = OutputDevice(
            objectID: 104, uid: "BuiltInSpeakerDevice", name: "MacBook Pro Speakers",
            transport: .builtIn, sampleRate: 48_000, volume: 0.5
        )
        let devices = [airPods, headphones, speaker, macSpeakers]
        // Audio major class 0x04; minor 0x06 = headphones, 0x05 = loudspeaker.
        let classes: [(device: OutputDevice, minor: Int)] = [(airPods, 0x06), (headphones, 0x06), (speaker, 0x05)]
        let paired = classes.compactMap { entry -> PairedAudioDevice? in
            guard let address = entry.device.bluetoothAddress else { return nil }
            return PairedAudioDevice(
                address: address,
                name: entry.device.name,
                bluetoothClass: BluetoothDeviceClass(major: BluetoothDeviceClass.audioMajor, minor: entry.minor),
                isLinkUp: true
            )
        }
        let settings = AppSettings(
            selectedUIDs: [airPods.uid, headphones.uid, speaker.uid],
            delays: [airPods.uid: 0, headphones.uid: 30, speaker.uid: 120],
            levels: [airPods.uid: 1, headphones.uid: 0.8, speaker.uid: 0.65],
            master: 0.6,
            useVolumeKeys: true
        )
        var plan: GroupPlan?
        if case .ready(let ready) = GroupPlanner.plan(selectedUIDs: settings.selectedUIDs, devices: devices, delays: settings.delays) {
            plan = ready
        }
        return AppModel(preview: AppModel.PreviewState(
            devices: devices,
            paired: paired,
            settings: settings,
            activePlan: plan,
            isSyncExpanded: true,
            syncNote: nil
        ))
    }
}

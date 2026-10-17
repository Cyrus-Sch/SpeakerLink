// SPDX-License-Identifier: GPL-3.0-or-later

import AppKit
import Foundation
import SwiftUI

/// Entry point. `SpeakerLink --screenshot <dir>` renders the menu with sample data and exits
/// without touching audio or Bluetooth; anything else starts the menu bar app.
@main
enum SpeakerLinkMain {
    static func main() {
        MainActor.assumeIsolated {
            switch ScreenshotCommand.parse(ProcessInfo.processInfo.arguments) {
            case .runApp:
                SpeakerLinkApp.main()
            case .render(let directory):
                exit(ScreenshotCommand.run(outputDirectory: directory))
            case .usageError(let message):
                ScreenshotCommand.printError(message)
                exit(2)
            }
        }
    }
}

/// Owns the one model of the running app. It is created on first use, which only happens in
/// app mode, never for screenshots.
@MainActor
enum AppEnvironment {
    static let model = AppModel.live()
}

/// The menu bar app. `LSUIElement` in Info.plist keeps it out of the Dock.
@MainActor
struct SpeakerLinkApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @ObservedObject private var model = AppEnvironment.model

    var body: some Scene {
        MenuBarExtra {
            MenuContentView(model: model)
        } label: {
            Image(systemName: model.menuBarSymbol)
                .accessibilityLabel(model.menuBarAccessibilityLabel)
        }
        .menuBarExtraStyle(.window)
    }
}

/// Starts the model after launch and, on quit, restores the previous output and removes the
/// group before the process exits (a public aggregate would otherwise outlive the app).
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        AppEnvironment.model.launch()
    }

    func applicationWillTerminate(_ notification: Notification) {
        AppEnvironment.model.shutdown()
    }
}

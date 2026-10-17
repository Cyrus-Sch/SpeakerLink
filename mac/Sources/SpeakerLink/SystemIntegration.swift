// SPDX-License-Identifier: GPL-3.0-or-later

import AppKit
import ServiceManagement
import SpeakerLinkCore

/// "Open at login" through `SMAppService.mainApp` (macOS 13+).
@MainActor
final class LoginItemController {
    enum Status: Sendable, Hashable {
        case enabled
        case disabled
        /// Registered, but the person still has to allow it in Login Items settings.
        case requiresApproval
        /// macOS cannot register this copy (for example a bare binary outside an app bundle).
        case unavailable
    }

    var status: Status {
        switch SMAppService.mainApp.status {
        case .enabled:
            return .enabled
        case .requiresApproval:
            return .requiresApproval
        case .notRegistered:
            return .disabled
        case .notFound:
            return .unavailable
        @unknown default:
            return .unavailable
        }
    }

    func setEnabled(_ enabled: Bool) throws {
        if enabled {
            try SMAppService.mainApp.register()
        } else {
            try SMAppService.mainApp.unregister()
        }
    }

    func openSettings() {
        SMAppService.openSystemSettingsLoginItems()
    }
}

/// Watches the keyboard's volume keys (media keys arrive as "system defined" events).
///
/// The monitors only observe; they need no permission and macOS still handles the keys (it
/// shows its own "not available" HUD while a multi-output device is the output). The idea of
/// using a global plus a local monitor follows 2geik/AudioShare (MIT).
@MainActor
final class VolumeKeyMonitor {
    private var monitors: [Any] = []

    var isRunning: Bool { !monitors.isEmpty }

    /// Starts watching; `handler` gets every decoded key-down. Does nothing when running.
    func start(_ handler: @escaping @MainActor (VolumeKeyEvent) -> Void) {
        guard monitors.isEmpty else { return }
        // Global monitors see events sent to other apps; the local one covers SpeakerLink's
        // own menu window while it has focus.
        if let global = NSEvent.addGlobalMonitorForEvents(matching: .systemDefined, handler: { event in
            Self.forward(event, to: handler)
        }) {
            monitors.append(global)
        }
        if let local = NSEvent.addLocalMonitorForEvents(matching: .systemDefined, handler: { event in
            Self.forward(event, to: handler)
            return event
        }) {
            monitors.append(local)
        }
    }

    func stop() {
        for monitor in monitors {
            NSEvent.removeMonitor(monitor)
        }
        monitors.removeAll()
    }

    private static func forward(_ event: NSEvent, to handler: @MainActor (VolumeKeyEvent) -> Void) {
        guard event.type == .systemDefined,
              let decoded = VolumeKeyEvent.decode(subtype: Int(event.subtype.rawValue), data1: event.data1) else { return }
        handler(decoded)
    }
}

/// Deep links into System Settings.
enum SystemSettingsLink {
    case bluetooth
    case bluetoothPrivacy

    var url: URL? {
        switch self {
        case .bluetooth:
            return URL(string: "x-apple.systempreferences:com.apple.BluetoothSettings")
        case .bluetoothPrivacy:
            return URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Bluetooth")
        }
    }

    @MainActor
    func open() {
        guard let url = url else { return }
        NSWorkspace.shared.open(url)
    }
}

/// The standard About panel with a short description and the licence.
@MainActor
enum AboutPanel {
    static func show() {
        let text = """
        Play the same audio on several Bluetooth headphones and speakers at once.

        Free software under the GNU General Public License, version 3 or later.
        https://github.com/cyrus-sch/speakerlink
        """
        let credits = NSAttributedString(string: text, attributes: [
            .font: NSFont.systemFont(ofSize: NSFont.smallSystemFontSize),
            .foregroundColor: NSColor.secondaryLabelColor,
        ])
        NSApplication.shared.activate()
        NSApplication.shared.orderFrontStandardAboutPanel(options: [.credits: credits])
    }
}

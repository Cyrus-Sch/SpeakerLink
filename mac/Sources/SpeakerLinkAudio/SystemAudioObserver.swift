// SPDX-License-Identifier: GPL-3.0-or-later

import CoreAudio
import Dispatch
import Foundation

/// Watches the system object for changes to the device list and the default output and
/// input, using `AudioObjectAddPropertyListenerBlock` on the main queue.
///
/// The listener blocks are kept so that ``stop()`` can remove exactly the same block objects,
/// as Core Audio requires.
@MainActor
public final class SystemAudioObserver {
    /// What changed.
    public enum Change: Sendable, Hashable {
        case devices
        case defaultOutput
        case defaultInput
    }

    private var registrations: [(selector: AudioObjectPropertySelector, block: AudioObjectPropertyListenerBlock)] = []

    public init() {}

    /// Whether the listeners are installed.
    public var isRunning: Bool { !registrations.isEmpty }

    /// Installs the listeners; `handler` runs on the main actor for every notification.
    /// Calling it again while running does nothing.
    public func start(_ handler: @escaping @MainActor @Sendable (Change) -> Void) {
        guard registrations.isEmpty else { return }
        let watched: [(AudioObjectPropertySelector, Change)] = [
            (kAudioHardwarePropertyDevices, .devices),
            (kAudioHardwarePropertyDefaultOutputDevice, .defaultOutput),
            (kAudioHardwarePropertyDefaultInputDevice, .defaultInput),
        ]
        for (selector, change) in watched {
            let block = Self.makeListener { handler(change) }
            var address = CoreAudioHAL.address(selector)
            let status = AudioObjectAddPropertyListenerBlock(CoreAudioHAL.systemObject, &address, DispatchQueue.main, block)
            if status == noErr {
                registrations.append((selector: selector, block: block))
            }
        }
    }

    /// Removes the listeners.
    public func stop() {
        for registration in registrations {
            var address = CoreAudioHAL.address(registration.selector)
            AudioObjectRemovePropertyListenerBlock(CoreAudioHAL.systemObject, &address, DispatchQueue.main, registration.block)
        }
        registrations.removeAll()
    }

    /// Makes a listener block that is not isolated to the main actor (Core Audio calls it from
    /// its dispatch queue machinery) and hops onto the main actor itself. The block is only
    /// ever registered with `DispatchQueue.main`, so `assumeIsolated` holds.
    private nonisolated static func makeListener(_ body: @escaping @MainActor @Sendable () -> Void) -> AudioObjectPropertyListenerBlock {
        return { _, _ in
            MainActor.assumeIsolated {
                body()
            }
        }
    }
}

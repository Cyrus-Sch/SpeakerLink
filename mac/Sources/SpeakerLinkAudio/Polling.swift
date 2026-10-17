// SPDX-License-Identifier: GPL-3.0-or-later

import CoreAudio
import Foundation

/// Waiting for Core Audio to catch up. Device creation, destruction and default-device
/// changes are applied asynchronously by coreaudiod, so the app polls with a timeout.
@MainActor
public enum Polling {
    /// Suspends for `seconds` without blocking the main thread.
    public static func pause(_ seconds: Double) async {
        guard seconds > 0 else { return }
        try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    /// Checks `condition` every `interval` seconds until it holds or `timeout` passes.
    /// Returns whether it held.
    public static func until(timeout: Double, interval: Double = 0.05, _ condition: () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            if condition() { return true }
            if Date() >= deadline { return false }
            await pause(interval)
        }
    }

    /// The blocking variant, for app termination and test clean-up, where there is no time
    /// to suspend.
    public static func untilBlocking(timeout: Double, interval: Double = 0.05, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            if condition() { return true }
            if Date() >= deadline { return false }
            Thread.sleep(forTimeInterval: interval)
        }
    }
}

/// Setting the system default output reliably.
@MainActor
public enum DefaultOutput {
    /// Sets the default output and reads it back, retrying because a just-created aggregate
    /// is often not accepted at the first attempt (about 20 tries 50 ms apart works).
    /// Returns whether `id` is the default output afterwards.
    public static func set(_ id: AudioObjectID, attempts: Int = 20, interval: Double = 0.05) async -> Bool {
        for _ in 0..<max(1, attempts) {
            CoreAudioHAL.setDefaultOutputDevice(id)
            if CoreAudioHAL.defaultOutputDevice() == id { return true }
            await Polling.pause(interval)
        }
        return CoreAudioHAL.defaultOutputDevice() == id
    }

    /// The blocking variant of ``set(_:attempts:interval:)``.
    public static func setBlocking(_ id: AudioObjectID, attempts: Int = 10, interval: Double = 0.05) -> Bool {
        for _ in 0..<max(1, attempts) {
            CoreAudioHAL.setDefaultOutputDevice(id)
            if CoreAudioHAL.defaultOutputDevice() == id { return true }
            Thread.sleep(forTimeInterval: interval)
        }
        return CoreAudioHAL.defaultOutputDevice() == id
    }
}

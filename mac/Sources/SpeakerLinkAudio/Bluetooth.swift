// SPDX-License-Identifier: GPL-3.0-or-later

import CoreBluetooth
import Foundation
import IOBluetooth
import SpeakerLinkCore

/// Reads paired Bluetooth audio devices with IOBluetooth.
///
/// IOBluetooth is not thread-safe, so everything here runs on the main actor, except the
/// first call that may show the privacy prompt (see ``requestAccessIfNeeded()``).
/// Since macOS 14, IOBluetooth is gated by the Bluetooth privacy permission; without it,
/// IOBluetooth returns nothing.
@MainActor
public final class BluetoothDirectory {
    public init() {}

    /// Whether the running bundle declares `NSBluetoothAlwaysUsageDescription`. Without it
    /// macOS terminates a process that touches IOBluetooth, so SpeakerLink then skips all
    /// Bluetooth features (this happens when running the bare binary with `swift run`).
    public static var isUsageDescriptionPresent: Bool {
        Bundle.main.object(forInfoDictionaryKey: "NSBluetoothAlwaysUsageDescription") != nil
    }

    /// The current Bluetooth permission. Reading it never shows a prompt.
    public var authorization: BluetoothAuthorization {
        guard Self.isUsageDescriptionPresent else { return .unavailable }
        switch CBManager.authorization {
        case .allowedAlways:
            return .allowed
        case .notDetermined:
            return .notDetermined
        case .denied:
            return .denied
        case .restricted:
            return .restricted
        @unknown default:
            return .denied
        }
    }

    /// When the permission has not been asked for yet, touches IOBluetooth once so macOS shows
    /// the prompt. The call can block until the person answers, so it runs off the main
    /// thread and its result is discarded.
    public func requestAccessIfNeeded() async {
        guard authorization == .notDetermined else { return }
        await Task.detached(priority: .userInitiated) {
            _ = IOBluetoothDevice.pairedDevices()
        }.value
    }

    /// Paired devices that look like audio outputs: the "audio" major device class, the
    /// audio service-class bit, or a cached Audio Sink (A2DP) service record. Empty when
    /// Bluetooth access is not allowed.
    public func pairedAudioDevices() -> [PairedAudioDevice] {
        guard authorization == .allowed else { return [] }
        let audioSink = IOBluetoothSDPUUID(uuid16: Self.audioSinkServiceClass)
        var result: [PairedAudioDevice] = []
        for case let device as IOBluetoothDevice in IOBluetoothDevice.pairedDevices() ?? [] {
            guard device.isPaired(),
                  let rawAddress = device.addressString,
                  let address = BluetoothAddress(rawAddress) else { continue }
            let deviceClass = BluetoothDeviceClass(major: Int(device.deviceClassMajor), minor: Int(device.deviceClassMinor))
            let hasAudioService = (Int(device.serviceClassMajor) & Self.audioServiceClassBit) != 0
            var hasSinkRecord = false
            if let audioSink {
                hasSinkRecord = device.getServiceRecord(for: audioSink) != nil
            }
            guard deviceClass.isAudio || hasAudioService || hasSinkRecord else { continue }
            result.append(PairedAudioDevice(
                address: address,
                name: device.nameOrAddress ?? address.rawValue,
                bluetoothClass: deviceClass,
                isLinkUp: device.isConnected()
            ))
        }
        return result
    }

    /// `kBluetoothSDPUUID16ServiceClassAudioSink`.
    static let audioSinkServiceClass: BluetoothSDPUUID16 = 0x110B
    /// `kBluetoothServiceClassMajorAudio` (bit 21 of the class of device).
    static let audioServiceClassBit = 0x100
}

/// Connects and disconnects paired Bluetooth devices.
///
/// Connecting uses IOBluetooth's asynchronous `openConnection(_:)` with this object as the
/// target, so the main thread never blocks. A successful connection only means the radio
/// link is up: macOS then brings up the audio profile, and the Core Audio output appears a
/// few seconds later (the app waits for it separately).
@MainActor
public final class BluetoothConnector: NSObject, IOBluetoothDeviceAsyncCallbacks {
    /// The outcome of a connection attempt.
    public enum ConnectResult: Sendable, Hashable {
        /// The link is up (or already was).
        case linked
        /// IOBluetooth reported an error (an `IOReturn` or HCI status).
        case failed(status: Int32)
        /// The device did not answer in time.
        case timedOut
        /// IOBluetooth does not know the address.
        case unknownDevice
    }

    private enum Completion: Sendable {
        case finished(status: Int32)
        case timedOut
    }

    private var pending: [BluetoothAddress: CheckedContinuation<Completion, Never>] = [:]

    /// How long to wait for `connectionComplete` before giving up. Paging takes up to about
    /// 6 s, authentication can add more.
    public var connectTimeout: Double = 20

    /// Whether a connection attempt to `address` is running.
    public func isConnecting(_ address: BluetoothAddress) -> Bool {
        pending[address] != nil
    }

    /// Opens the Bluetooth link to a paired device.
    public func connect(_ address: BluetoothAddress) async -> ConnectResult {
        guard BluetoothDirectory.isUsageDescriptionPresent else { return .unknownDevice }
        guard let device = IOBluetoothDevice(addressString: address.rawValue) else { return .unknownDevice }
        if device.isConnected() { return .linked }
        guard pending[address] == nil else { return .failed(status: Self.busyStatus) }

        let timeout = connectTimeout
        let timer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.complete(address, with: .timedOut)
        }
        let completion: Completion = await withCheckedContinuation { continuation in
            pending[address] = continuation
            let started = device.openConnection(self)
            if started != kIOReturnSuccess {
                complete(address, with: .finished(status: started))
            }
        }
        timer.cancel()

        switch completion {
        case .timedOut:
            return device.isConnected() ? .linked : .timedOut
        case .finished(let status):
            // "Connection already exists" (HCI 0x0B) and similar still leave a usable link.
            if status == kIOReturnSuccess || device.isConnected() { return .linked }
            return .failed(status: status)
        }
    }

    /// Closes the Bluetooth link. `closeConnection()` blocks and sometimes reports success
    /// while the link stays up, so this runs off the main thread and retries a few times.
    /// Devices may reconnect by themselves later; macOS has no public API to stop that.
    /// Returns whether the link is down afterwards.
    public func disconnect(_ address: BluetoothAddress) async -> Bool {
        guard BluetoothDirectory.isUsageDescriptionPresent else { return false }
        let addressString = address.rawValue
        return await Task.detached(priority: .userInitiated) { () async -> Bool in
            guard let device = IOBluetoothDevice(addressString: addressString) else { return false }
            for _ in 0..<3 {
                if !device.isConnected() { return true }
                _ = device.closeConnection()
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
            return !device.isConnected()
        }.value
    }

    private func complete(_ address: BluetoothAddress, with completion: Completion) {
        guard let continuation = pending.removeValue(forKey: address) else { return }
        continuation.resume(returning: completion)
    }

    // MARK: IOBluetoothDeviceAsyncCallbacks

    /// IOBluetooth calls this on the run loop of the thread that started the connection (the
    /// main thread); it hops to the main actor explicitly anyway and passes only Sendable
    /// values across.
    public nonisolated func connectionComplete(_ device: IOBluetoothDevice!, status: IOReturn) {
        let rawAddress: String? = device?.addressString
        let address = rawAddress.flatMap { BluetoothAddress($0) }
        Task { @MainActor [weak self] in
            guard let self, let address else { return }
            self.complete(address, with: .finished(status: status))
        }
    }

    public nonisolated func remoteNameRequestComplete(_ device: IOBluetoothDevice!, status: IOReturn) {}

    public nonisolated func sdpQueryComplete(_ device: IOBluetoothDevice!, status: IOReturn) {}

    /// Reported when a second attempt to the same device starts while one is running.
    static let busyStatus: Int32 = FourCC.status("busy")
}

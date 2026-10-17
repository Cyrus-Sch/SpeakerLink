// SPDX-License-Identifier: GPL-3.0-or-later

import CoreAudio
import Foundation
import SpeakerLinkCore

/// Synchronous wrappers around the Core Audio HAL C API (`AudioObjectGetPropertyData` and
/// friends).
///
/// These functions keep no state and may be called from any thread. Failures become `nil`,
/// `false` or a returned `OSStatus`; nothing here force-unwraps a HAL result.
public enum CoreAudioHAL {
    /// `kAudioObjectSystemObject`, the object that owns all devices and the defaults.
    public static let systemObject = AudioObjectID(kAudioObjectSystemObject)

    /// `kAudioObjectUnknown`.
    public static let unknownObject = AudioObjectID(kAudioObjectUnknown)

    // MARK: - Devices

    /// IDs of every audio device (`kAudioHardwarePropertyDevices`).
    public static func deviceIDs() -> [AudioObjectID] {
        objectIDs(systemObject, address(kAudioHardwarePropertyDevices))
    }

    /// The device with the given UID, if it is present in the device list.
    public static func deviceID(forUID uid: String) -> AudioObjectID? {
        deviceIDs().first { Self.uid(of: $0) == uid }
    }

    /// `kAudioDevicePropertyDeviceUID`.
    public static func uid(of id: AudioObjectID) -> String? {
        string(id, address(kAudioDevicePropertyDeviceUID))
    }

    /// `kAudioObjectPropertyName`.
    public static func name(of id: AudioObjectID) -> String? {
        string(id, address(kAudioObjectPropertyName))
    }

    /// `kAudioDevicePropertyTransportType`, mapped to ``AudioTransport``.
    public static func transport(of id: AudioObjectID) -> AudioTransport {
        AudioTransport(code: uint32(id, address(kAudioDevicePropertyTransportType)) ?? 0)
    }

    /// `kAudioDevicePropertyNominalSampleRate`, or `nil` when it cannot be read.
    public static func nominalSampleRate(of id: AudioObjectID) -> Double? {
        float64(id, address(kAudioDevicePropertyNominalSampleRate))
    }

    /// Total channels in the device's stream configuration for `scope`
    /// (`kAudioDevicePropertyStreamConfiguration`, an `AudioBufferList`).
    public static func channelCount(of id: AudioObjectID, scope: AudioObjectPropertyScope) -> Int {
        var propertyAddress = address(kAudioDevicePropertyStreamConfiguration, scope: scope)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(id, &propertyAddress, 0, nil, &size) == noErr,
              Int(size) >= MemoryLayout<UInt32>.size else { return 0 }
        let byteCount = Int(size)
        let raw = UnsafeMutableRawPointer.allocate(byteCount: byteCount, alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { raw.deallocate() }
        guard AudioObjectGetPropertyData(id, &propertyAddress, 0, nil, &size, raw) == noErr else { return 0 }
        // AudioBufferList is variable-length: a UInt32 count followed by that many AudioBuffers.
        let bufferCount = Int(raw.load(as: UInt32.self))
        let firstBuffer = MemoryLayout<AudioBufferList>.offset(of: \AudioBufferList.mBuffers) ?? MemoryLayout<AudioBuffer>.alignment
        var channels = 0
        for index in 0..<bufferCount {
            let offset = firstBuffer + index * MemoryLayout<AudioBuffer>.stride
            guard offset + MemoryLayout<AudioBuffer>.size <= Int(size) else { break }
            let buffer = raw.load(fromByteOffset: offset, as: AudioBuffer.self)
            channels += Int(buffer.mNumberChannels)
        }
        return channels
    }

    /// Reads one output device into the Core model, or `nil` when it has no output channels
    /// or no UID.
    public static func outputDevice(_ id: AudioObjectID) -> OutputDevice? {
        let channels = channelCount(of: id, scope: kAudioObjectPropertyScopeOutput)
        guard channels > 0, let deviceUID = Self.uid(of: id) else { return nil }
        let hidden = (uint32(id, address(kAudioDevicePropertyIsHidden)) ?? 0) != 0
        let canBeDefault = (uint32(id, address(kAudioDevicePropertyDeviceCanBeDefaultDevice, scope: kAudioObjectPropertyScopeOutput)) ?? 1) != 0
        return OutputDevice(
            objectID: id,
            uid: deviceUID,
            name: Self.name(of: id) ?? deviceUID,
            transport: transport(of: id),
            sampleRate: nominalSampleRate(of: id) ?? 0,
            outputChannels: channels,
            isHidden: hidden,
            canBeDefault: canBeDefault,
            volume: volume(of: id)
        )
    }

    /// Every device with output channels, unfiltered (aggregates and hidden devices
    /// included); ``DeviceListRules`` decides what to show.
    public static func outputDevices() -> [OutputDevice] {
        deviceIDs().compactMap(outputDevice)
    }

    /// Every visible device with input channels.
    public static func inputDevices() -> [InputDevice] {
        deviceIDs().compactMap { id in
            guard channelCount(of: id, scope: kAudioObjectPropertyScopeInput) > 0,
                  (uint32(id, address(kAudioDevicePropertyIsHidden)) ?? 0) == 0,
                  let deviceUID = Self.uid(of: id) else { return nil }
            return InputDevice(objectID: id, uid: deviceUID, name: Self.name(of: id) ?? deviceUID, transport: Self.transport(of: id))
        }
    }

    // MARK: - Default devices

    /// The system default output (`kAudioHardwarePropertyDefaultOutputDevice`).
    public static func defaultOutputDevice() -> AudioObjectID? {
        guard let id = uint32(systemObject, address(kAudioHardwarePropertyDefaultOutputDevice)),
              id != unknownObject else { return nil }
        return id
    }

    /// UID of the system default output.
    public static func defaultOutputUID() -> String? {
        defaultOutputDevice().flatMap { Self.uid(of: $0) }
    }

    /// Sets the system default output. A freshly created device is sometimes not accepted at
    /// once; ``DefaultOutput/set(_:attempts:interval:)`` retries and reads it back.
    @discardableResult
    public static func setDefaultOutputDevice(_ id: AudioObjectID) -> OSStatus {
        setUInt32(systemObject, address(kAudioHardwarePropertyDefaultOutputDevice), id)
    }

    /// The system default input (`kAudioHardwarePropertyDefaultInputDevice`).
    public static func defaultInputDevice() -> AudioObjectID? {
        guard let id = uint32(systemObject, address(kAudioHardwarePropertyDefaultInputDevice)),
              id != unknownObject else { return nil }
        return id
    }

    /// UID of the system default input.
    public static func defaultInputUID() -> String? {
        defaultInputDevice().flatMap { Self.uid(of: $0) }
    }

    @discardableResult
    public static func setDefaultInputDevice(_ id: AudioObjectID) -> OSStatus {
        setUInt32(systemObject, address(kAudioHardwarePropertyDefaultInputDevice), id)
    }

    // MARK: - Volume and mute

    /// The elements whose output `selector` (volume or mute) can be set: the main element
    /// when it is settable, otherwise channels 1 and 2 (AirPods expose only those).
    public static func controlElements(of id: AudioObjectID, _ selector: AudioObjectPropertySelector) -> [AudioObjectPropertyElement] {
        if isSettable(id, address(selector, scope: kAudioObjectPropertyScopeOutput, element: kAudioObjectPropertyElementMain)) {
            return [kAudioObjectPropertyElementMain]
        }
        let channels: [AudioObjectPropertyElement] = [1, 2]
        return channels.filter { isSettable(id, address(selector, scope: kAudioObjectPropertyScopeOutput, element: $0)) }
    }

    /// Whether the device's output volume can be set.
    public static func hasVolumeControl(_ id: AudioObjectID) -> Bool {
        !controlElements(of: id, kAudioDevicePropertyVolumeScalar).isEmpty
    }

    /// Output volume 0...1 (the average of the controllable elements), or `nil` when the
    /// device has no settable volume.
    public static func volume(of id: AudioObjectID) -> Double? {
        let values = controlElements(of: id, kAudioDevicePropertyVolumeScalar).compactMap { element in
            float32(id, address(kAudioDevicePropertyVolumeScalar, scope: kAudioObjectPropertyScopeOutput, element: element))
        }
        guard !values.isEmpty else { return nil }
        return Double(values.reduce(0, +)) / Double(values.count)
    }

    /// Sets the output volume (0...1) on every controllable element. Returns whether at least
    /// one element accepted it.
    @discardableResult
    public static func setVolume(_ volume: Double, of id: AudioObjectID) -> Bool {
        let value = Float32(VolumeMath.clamp(volume))
        var accepted = false
        for element in controlElements(of: id, kAudioDevicePropertyVolumeScalar) {
            var propertyAddress = address(kAudioDevicePropertyVolumeScalar, scope: kAudioObjectPropertyScopeOutput, element: element)
            var data = value
            if AudioObjectSetPropertyData(id, &propertyAddress, 0, nil, UInt32(MemoryLayout<Float32>.size), &data) == noErr {
                accepted = true
            }
        }
        return accepted
    }

    /// Whether any controllable element is muted, or `nil` without a mute control.
    public static func isMuted(_ id: AudioObjectID) -> Bool? {
        let elements = controlElements(of: id, kAudioDevicePropertyMute)
        guard !elements.isEmpty else { return nil }
        return elements.contains { element in
            (uint32(id, address(kAudioDevicePropertyMute, scope: kAudioObjectPropertyScopeOutput, element: element)) ?? 0) != 0
        }
    }

    /// Mutes or unmutes every controllable element. Returns whether any accepted it.
    @discardableResult
    public static func setMuted(_ muted: Bool, of id: AudioObjectID) -> Bool {
        var accepted = false
        for element in controlElements(of: id, kAudioDevicePropertyMute) {
            let status = setUInt32(id, address(kAudioDevicePropertyMute, scope: kAudioObjectPropertyScopeOutput, element: element), muted ? 1 : 0)
            if status == noErr { accepted = true }
        }
        return accepted
    }

    // MARK: - Latency

    /// Reported output latency: device latency, safety offset and the first output stream's
    /// latency, in frames at the device's nominal rate.
    public static func latency(of id: AudioObjectID) -> DeviceLatency {
        let output = kAudioObjectPropertyScopeOutput
        let deviceFrames = uint32(id, address(kAudioDevicePropertyLatency, scope: output)) ?? 0
        let safetyFrames = uint32(id, address(kAudioDevicePropertySafetyOffset, scope: output)) ?? 0
        let streams = objectIDs(id, address(kAudioDevicePropertyStreams, scope: output))
        let streamFrames = streams.first.flatMap { uint32($0, address(kAudioStreamPropertyLatency)) } ?? 0
        return DeviceLatency(
            deviceFrames: Int(deviceFrames),
            safetyOffsetFrames: Int(safetyFrames),
            streamFrames: Int(streamFrames),
            sampleRate: nominalSampleRate(of: id) ?? 0
        )
    }

    // MARK: - Aggregate devices

    /// Creates an aggregate device from a description built by ``AggregateDescription``.
    /// - Throws: ``GroupError/creationFailed(status:)``.
    public static func createAggregateDevice(_ description: [String: Any]) throws -> AudioObjectID {
        var id = unknownObject
        let status = AudioHardwareCreateAggregateDevice(description as CFDictionary, &id)
        guard status == noErr, id != unknownObject else {
            throw GroupError.creationFailed(status: status == noErr ? FourCC.status("what") : status)
        }
        return id
    }

    /// Asks Core Audio to destroy an aggregate device. Destruction is asynchronous: the UID
    /// can stay in the device list for a moment after this returns.
    @discardableResult
    public static func destroyAggregateDevice(_ id: AudioObjectID) -> OSStatus {
        AudioHardwareDestroyAggregateDevice(id)
    }

    /// The aggregate's composition (`kAudioAggregateDevicePropertyComposition`): the same
    /// keys as the creation description, as Core Audio stored them.
    public static func composition(of id: AudioObjectID) -> [String: Any]? {
        var propertyAddress = address(kAudioAggregateDevicePropertyComposition)
        var value: Unmanaged<CFDictionary>?
        var size = UInt32(MemoryLayout<Unmanaged<CFDictionary>?>.size)
        let status = withUnsafeMutablePointer(to: &value) { pointer in
            AudioObjectGetPropertyData(id, &propertyAddress, 0, nil, &size, pointer)
        }
        guard status == noErr, let value else { return nil }
        let dictionary = value.takeRetainedValue() as NSDictionary
        return dictionary as? [String: Any]
    }

    /// UIDs of all sub-devices of an aggregate (`kAudioAggregateDevicePropertyFullSubDeviceList`).
    public static func subDeviceUIDs(of id: AudioObjectID) -> [String] {
        var propertyAddress = address(kAudioAggregateDevicePropertyFullSubDeviceList)
        var value: Unmanaged<CFArray>?
        var size = UInt32(MemoryLayout<Unmanaged<CFArray>?>.size)
        let status = withUnsafeMutablePointer(to: &value) { pointer in
            AudioObjectGetPropertyData(id, &propertyAddress, 0, nil, &size, pointer)
        }
        guard status == noErr, let value else { return [] }
        let array = value.takeRetainedValue() as NSArray
        return array.compactMap { $0 as? String }
    }

    // MARK: - Primitives

    /// A property address; scope global and element main unless given.
    public static func address(
        _ selector: AudioObjectPropertySelector,
        scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal,
        element: AudioObjectPropertyElement = kAudioObjectPropertyElementMain
    ) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: element)
    }

    static func has(_ id: AudioObjectID, _ propertyAddress: AudioObjectPropertyAddress) -> Bool {
        var propertyAddress = propertyAddress
        return AudioObjectHasProperty(id, &propertyAddress)
    }

    static func isSettable(_ id: AudioObjectID, _ propertyAddress: AudioObjectPropertyAddress) -> Bool {
        var propertyAddress = propertyAddress
        guard AudioObjectHasProperty(id, &propertyAddress) else { return false }
        var settable: DarwinBoolean = false
        return AudioObjectIsPropertySettable(id, &propertyAddress, &settable) == noErr && settable.boolValue
    }

    /// Reads a fixed-size value. `initial` only provides the type and storage.
    static func scalar<Value>(_ id: AudioObjectID, _ propertyAddress: AudioObjectPropertyAddress, initial: Value) -> Value? {
        var propertyAddress = propertyAddress
        var value = initial
        var size = UInt32(MemoryLayout<Value>.size)
        let status = withUnsafeMutablePointer(to: &value) { pointer in
            AudioObjectGetPropertyData(id, &propertyAddress, 0, nil, &size, pointer)
        }
        guard status == noErr, Int(size) == MemoryLayout<Value>.size else { return nil }
        return value
    }

    static func uint32(_ id: AudioObjectID, _ propertyAddress: AudioObjectPropertyAddress) -> UInt32? {
        scalar(id, propertyAddress, initial: UInt32(0))
    }

    static func float32(_ id: AudioObjectID, _ propertyAddress: AudioObjectPropertyAddress) -> Float32? {
        scalar(id, propertyAddress, initial: Float32(0))
    }

    static func float64(_ id: AudioObjectID, _ propertyAddress: AudioObjectPropertyAddress) -> Float64? {
        scalar(id, propertyAddress, initial: Float64(0))
    }

    static func string(_ id: AudioObjectID, _ propertyAddress: AudioObjectPropertyAddress) -> String? {
        var propertyAddress = propertyAddress
        var value: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        let status = withUnsafeMutablePointer(to: &value) { pointer in
            AudioObjectGetPropertyData(id, &propertyAddress, 0, nil, &size, pointer)
        }
        guard status == noErr, let value else { return nil }
        return value.takeRetainedValue() as String
    }

    static func objectIDs(_ id: AudioObjectID, _ propertyAddress: AudioObjectPropertyAddress) -> [AudioObjectID] {
        var propertyAddress = propertyAddress
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(id, &propertyAddress, 0, nil, &size) == noErr else { return [] }
        let stride = MemoryLayout<AudioObjectID>.stride
        let capacity = Int(size) / stride
        guard capacity > 0 else { return [] }
        var ids = [AudioObjectID](repeating: unknownObject, count: capacity)
        guard AudioObjectGetPropertyData(id, &propertyAddress, 0, nil, &size, &ids) == noErr else { return [] }
        // The list can shrink between the two calls; keep only what was written.
        return Array(ids.prefix(min(capacity, Int(size) / stride)))
    }

    @discardableResult
    static func setUInt32(_ id: AudioObjectID, _ propertyAddress: AudioObjectPropertyAddress, _ newValue: UInt32) -> OSStatus {
        var propertyAddress = propertyAddress
        var data = newValue
        return AudioObjectSetPropertyData(id, &propertyAddress, 0, nil, UInt32(MemoryLayout<UInt32>.size), &data)
    }
}

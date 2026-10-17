// SPDX-License-Identifier: GPL-3.0-or-later

/// Plain-language descriptions of Core Audio status codes.
///
/// Core Audio reports failures as `OSStatus` values that are often four-character codes such
/// as `'who?'`. People should never see such a code on its own, so every message starts with
/// an explanation and only ends with the code, for anyone reporting a bug.
public enum AudioStatusText {
    /// "The device doesn't support this setting. (Core Audio code 'who?')".
    public static func describe(_ status: Int32) -> String {
        "\(explanation(for: status)) (\(code(status)))"
    }

    /// The explanation alone.
    public static func explanation(for status: Int32) -> String {
        if status == 0 { return "No error." }
        if let text = explanations[status] { return text }
        return "macOS reported an unexpected audio error."
    }

    /// "Core Audio code 'who?'" or "Core Audio error -50".
    public static func code(_ status: Int32) -> String {
        if let chars = FourCC.string(fromStatus: status) {
            return "Core Audio code '\(chars)'"
        }
        return "Core Audio error \(status)"
    }

    static let explanations: [Int32: String] = [
        FourCC.status("who?"): "The device doesn't support this setting.",
        FourCC.status("!obj"): "The device is no longer available. It may have been switched off or disconnected.",
        FourCC.status("!dev"): "The device is no longer available. It may have been switched off or disconnected.",
        FourCC.status("nope"): "macOS didn't allow this change right now. Try again in a moment.",
        FourCC.status("unop"): "This device doesn't support that.",
        FourCC.status("!hog"): "Another app is using this device exclusively.",
        FourCC.status("stop"): "The audio system isn't running. Try again in a moment.",
        FourCC.status("!siz"): "macOS rejected a malformed request. Please report this.",
        FourCC.status("!str"): "The device's audio stream is no longer available.",
        FourCC.status("what"): "macOS reported an unspecified audio error.",
        FourCC.status("nrdy"): "The audio system isn't ready yet. Try again in a moment.",
        FourCC.status("!dat"): "The device doesn't support this audio format.",
        -50: "macOS rejected the request as invalid.",
    ]
}

/// Errors from starting, changing or stopping a group, with messages fit for people.
public enum GroupError: Error, Sendable, Hashable {
    /// Fewer than two outputs can join.
    case notEnoughDevices(available: Int)
    /// `AudioHardwareCreateAggregateDevice` failed.
    case creationFailed(status: Int32)
    /// The new device did not show up in the device list in time.
    case didNotAppear
    /// macOS did not accept the group as the system output.
    case notAcceptedAsDefault

    /// A sentence or two for the menu.
    public var message: String {
        switch self {
        case .notEnoughDevices(let available):
            return available == 1
                ? "Only one of the chosen outputs is available. Choose at least two."
                : "Choose at least two available outputs to listen together."
        case .creationFailed(let status):
            return "macOS couldn't combine the outputs. \(AudioStatusText.describe(status))"
        case .didNotAppear:
            return "macOS created the combined output, but it didn't show up in time. Try again."
        case .notAcceptedAsDefault:
            return "macOS didn't accept the combined output as the sound output. Try again, or choose it in System Settings > Sound."
        }
    }
}

/// Short status lines for the menu.
public enum StatusText {
    /// "Playing on 3 devices".
    public static func playing(memberCount: Int) -> String {
        "Playing on \(memberCount) \(memberCount == 1 ? "device" : "devices")"
    }

    /// What the menu says while "Listen together" is off.
    public static func idle(selectedAvailable: Int) -> String {
        switch selectedAvailable {
        case 0: return "Choose two or more outputs below."
        case 1: return "Choose one more output."
        default: return "Ready to play on \(selectedAvailable) outputs."
        }
    }

    /// Why a running group stopped by itself, or `nil` when the person stopped it.
    public static func stopped(_ reason: StopReason, outputName: String?) -> String? {
        switch reason {
        case .turnedOff:
            return nil
        case .tooFewDevices:
            return "Stopped: fewer than two of the chosen outputs are still available."
        case .outputChangedElsewhere:
            if let outputName {
                return "Stopped because the sound output was changed to \(outputName)."
            }
            return "Stopped because the sound output was changed in macOS."
        }
    }
}

/// Advice about the default input (microphone).
public enum MicrophoneAdvice {
    /// Whether the default input is the microphone of Bluetooth headphones that are in the
    /// group. Using that microphone switches the headphones to the low-quality hands-free
    /// profile, which also lowers their playback quality.
    public static func degradesGroup(defaultInputUID: String?, memberUIDs: [String]) -> Bool {
        guard let defaultInputUID, let address = BluetoothAddress(audioDeviceUID: defaultInputUID) else {
            return false
        }
        return memberUIDs.contains { BluetoothAddress(audioDeviceUID: $0) == address }
    }

    /// The input to suggest instead: the first built-in microphone.
    public static func suggestedInput(from inputs: [InputDevice]) -> InputDevice? {
        inputs.first { $0.transport == .builtIn }
    }
}

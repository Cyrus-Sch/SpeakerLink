// SPDX-License-Identifier: GPL-3.0-or-later

/// Everything the menu shows for one output.
public struct DeviceRow: Sendable, Hashable, Identifiable {
    public var id: String { uid }

    public let uid: String
    public let name: String
    public let kind: DeviceKind
    /// For example "Bluetooth · 48 kHz".
    public let subtitle: String
    public let isSelected: Bool
    /// Whether this is (or would be) the group's main (clock) device.
    public let isMain: Bool
    /// Whether this output is in the running group.
    public let isInGroup: Bool
    /// Whether the output can be added to a group at all (AirPlay cannot).
    public let canJoin: Bool
    /// An explanation shown under the row, for example why it is disabled.
    public let note: String?
    /// Slider value: the level while the output is in the running group, otherwise the
    /// device's own volume. `nil` when the device has no settable volume.
    public let volume: Double?
    /// The extra delay in milliseconds.
    public let delayMs: Int
    /// The Bluetooth address, for Bluetooth outputs (they can be disconnected from the menu).
    public let bluetoothAddress: BluetoothAddress?

    public init(
        uid: String, name: String, kind: DeviceKind, subtitle: String, isSelected: Bool, isMain: Bool,
        isInGroup: Bool, canJoin: Bool, note: String?, volume: Double?, delayMs: Int, bluetoothAddress: BluetoothAddress?
    ) {
        self.uid = uid
        self.name = name
        self.kind = kind
        self.subtitle = subtitle
        self.isSelected = isSelected
        self.isMain = isMain
        self.isInGroup = isInGroup
        self.canJoin = canJoin
        self.note = note
        self.volume = volume
        self.delayMs = delayMs
        self.bluetoothAddress = bluetoothAddress
    }
}

/// Builds the menu's output rows from plain state, so the menu logic is testable.
public enum DeviceRowBuilder {
    /// Note shown under AirPlay outputs.
    public static let airPlayNote = "AirPlay outputs can't join a group."

    /// Rows for the visible outputs (see ``DeviceListRules``), in display order.
    ///
    /// - Parameters:
    ///   - devices: Outputs as read from Core Audio (all of them; this filters and sorts).
    ///   - settings: Selection, delays and levels.
    ///   - activePlan: The running group's plan, if a group is running.
    ///   - bluetoothClasses: Class of device by address, from the paired-device list.
    public static func rows(
        devices: [OutputDevice],
        settings: AppSettings,
        activePlan: GroupPlan?,
        bluetoothClasses: [BluetoothAddress: BluetoothDeviceClass] = [:],
        groupUIDs: GroupUIDs = .production
    ) -> [DeviceRow] {
        let visible = DeviceListRules.visibleOutputs(devices, groupUIDs: groupUIDs)
        let members = Set(activePlan?.memberUIDs ?? [])
        let mainUID: String?
        if let activePlan {
            mainUID = activePlan.mainUID
        } else if case .ready(let plan) = GroupPlanner.plan(selectedUIDs: settings.selectedUIDs, devices: visible, delays: settings.delays) {
            mainUID = plan.mainUID
        } else {
            mainUID = nil
        }
        return visible.map { device in
            let address = device.bluetoothAddress
            let inGroup = members.contains(device.uid)
            let volume: Double?
            if device.volume == nil {
                volume = nil
            } else if inGroup {
                volume = settings.level(for: device.uid)
            } else {
                volume = device.volume
            }
            return DeviceRow(
                uid: device.uid,
                name: device.name,
                kind: DeviceKind.resolve(for: device, bluetoothClass: address.flatMap { bluetoothClasses[$0] }),
                subtitle: DeviceFormatting.subtitle(for: device),
                isSelected: settings.selectedUIDs.contains(device.uid),
                isMain: device.uid == mainUID,
                isInGroup: inGroup,
                canJoin: device.canJoinGroup,
                note: device.canJoinGroup ? nil : airPlayNote,
                volume: volume,
                delayMs: settings.delay(for: device.uid),
                bluetoothAddress: address
            )
        }
    }

    /// How many selected outputs are present and able to join.
    public static func selectedAvailableCount(devices: [OutputDevice], settings: AppSettings, groupUIDs: GroupUIDs = .production) -> Int {
        let visible = DeviceListRules.visibleOutputs(devices, groupUIDs: groupUIDs)
        let joinable = Set(visible.filter(\.canJoinGroup).map(\.uid))
        return Set(settings.selectedUIDs).intersection(joinable).count
    }
}

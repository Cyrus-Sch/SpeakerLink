// SPDX-License-Identifier: GPL-3.0-or-later

import AppKit
import Combine
import CoreAudio
import Foundation
import SpeakerLinkAudio
import SpeakerLinkCore

/// The services the running app uses. Screenshots and previews run without them.
@MainActor
struct LiveServices {
    let group: GroupController
    let observer: SystemAudioObserver
    let bluetooth: BluetoothDirectory
    let connector: BluetoothConnector
    let clicks: ClickPlayer
    let volumeKeys: VolumeKeyMonitor
    let loginItem: LoginItemController

    init() {
        group = GroupController()
        observer = SystemAudioObserver()
        bluetooth = BluetoothDirectory()
        connector = BluetoothConnector()
        clicks = ClickPlayer()
        volumeKeys = VolumeKeyMonitor()
        loginItem = LoginItemController()
    }
}

/// State and actions behind the menu window.
///
/// The model keeps what the person wants (the selection, delays, levels and whether
/// "Listen together" is on) and brings Core Audio in line with it in one place,
/// ``reconcileOnce()``, which runs after every change and every Core Audio notification.
/// Only one reconciliation runs at a time; a change that arrives meanwhile triggers one more
/// pass. Because each pass compares state rather than reacting to individual notifications,
/// the notifications caused by SpeakerLink's own create/destroy calls end in "nothing to do".
@MainActor
final class AppModel: ObservableObject {
    enum Phase: Sendable, Hashable {
        case off
        case starting
        case on
        case stopping
    }

    struct Notice: Sendable, Hashable {
        let text: String
        let isError: Bool
    }

    /// What the screenshot and preview show.
    struct PreviewState {
        var devices: [OutputDevice]
        var paired: [PairedAudioDevice]
        var settings: AppSettings
        var activePlan: GroupPlan?
        var isSyncExpanded: Bool
        var syncNote: String?
    }

    // MARK: Published state

    @Published private(set) var phase: Phase = .off
    @Published private(set) var wantsListening = false
    @Published private(set) var activePlan: GroupPlan? = nil
    @Published private(set) var rows: [DeviceRow] = []
    @Published private(set) var selectedAvailable = 0
    @Published private(set) var offlineDevices: [PairedAudioDevice] = []
    @Published private(set) var connecting: Set<BluetoothAddress> = []
    @Published private(set) var notice: Notice? = nil
    @Published private(set) var settings: AppSettings
    @Published private(set) var isMuted = false
    @Published private(set) var bluetoothAuthorization: BluetoothAuthorization = .allowed
    @Published private(set) var showsMicrophoneHint = false
    @Published private(set) var canUseBuiltInMicrophone = false
    @Published private(set) var loginItemStatus: LoginItemController.Status = .disabled
    @Published private(set) var isPlayingClicks = false
    @Published private(set) var syncNote: String? = nil
    @Published var isSyncExpanded = false

    // MARK: Private state

    private let live: LiveServices?
    private let store: SettingsStore?
    private var devices: [OutputDevice] = []
    private var pairedDevices: [PairedAudioDevice] = []
    private var disconnecting: Set<BluetoothAddress> = []
    private var reconcileRunning = false
    private var reconcileAgain = false
    private var reconcileDelay: Task<Void, Never>?
    private var delayDebounce: Task<Void, Never>?
    /// A rebuild that failed is not retried by notifications alone, only after the person
    /// changes something (otherwise the notifications of the failed attempt would retry it
    /// forever).
    private var failedSignature: GroupPlan.Signature?
    private var isShuttingDown = false

    // MARK: Creation

    /// The model of the running app.
    static func live() -> AppModel {
        AppModel(live: LiveServices(), store: SettingsStore())
    }

    private init(live: LiveServices, store: SettingsStore) {
        self.live = live
        self.store = store
        settings = store.load()
    }

    /// A model that shows `state` and never touches audio or Bluetooth.
    init(preview state: PreviewState) {
        live = nil
        store = nil
        settings = state.settings
        devices = state.devices
        pairedDevices = state.paired
        activePlan = state.activePlan
        wantsListening = state.activePlan != nil
        phase = state.activePlan == nil ? .off : .on
        isSyncExpanded = state.isSyncExpanded
        syncNote = state.syncNote
        bluetoothAuthorization = .allowed
        loginItemStatus = .disabled
        refreshRows()
    }

    // MARK: Derived values for the views

    var isBusy: Bool { phase == .starting || phase == .stopping }

    /// "Listen together" can always be switched off, and switched on with two outputs.
    var canToggleListening: Bool { wantsListening || selectedAvailable >= 2 }

    var statusLine: String {
        switch phase {
        case .starting: return "Starting…"
        case .stopping: return "Stopping…"
        case .on: return StatusText.playing(memberCount: activePlan?.members.count ?? 0)
        case .off: return StatusText.idle(selectedAvailable: selectedAvailable)
        }
    }

    /// The menu bar icon: filled while listening together.
    var menuBarSymbol: String {
        activePlan == nil ? "headphones" : "headphones.circle.fill"
    }

    var menuBarAccessibilityLabel: String {
        activePlan == nil ? "SpeakerLink" : "SpeakerLink, listening together"
    }

    var masterVolumeNote: String {
        settings.useVolumeKeys
            ? "macOS can't change the volume of a combined output, so this slider and the volume keys do it."
            : "macOS can't change the volume of a combined output, so use this slider."
    }

    var hasSelection: Bool { rows.contains { $0.isSelected } }

    // MARK: Life cycle

    /// Called once when the app has launched.
    func launch() {
        guard let live else { return }
        live.observer.start { [weak self] change in
            self?.handle(change)
        }
        live.clicks.onFinish = { [weak self] in
            self?.isPlayingClicks = false
        }
        updateVolumeKeyMonitor()
        loginItemStatus = live.loginItem.status
        refreshDevices()
        refreshBluetooth()
        refreshRows()
        // A group left behind by a crash would otherwise stay the system output. The clean-up
        // counts as a reconciliation pass, so a "Listen together" that arrives meanwhile waits.
        reconcileRunning = true
        Task {
            await live.group.removeLeftovers(restoringTo: self.settings.restoreOutputUID)
            if self.settings.restoreOutputUID != nil {
                self.settings.restoreOutputUID = nil
                self.save()
            }
            self.refreshDevices()
            self.refreshRows()
            self.reconcileRunning = false
            if self.reconcileAgain {
                self.reconcileAgain = false
                self.requestReconcile()
            }
        }
    }

    /// Called when the menu window opens.
    func menuDidAppear() {
        guard let live else { return }
        refreshDevices()
        refreshBluetooth()
        loginItemStatus = live.loginItem.status
        refreshRows()
        updateMicrophoneHint()
        if bluetoothAuthorization == .notDetermined {
            Task {
                await live.bluetooth.requestAccessIfNeeded()
                self.refreshBluetooth()
                self.refreshRows()
            }
        }
    }

    /// Called from `applicationWillTerminate`: puts the previous output back and removes the
    /// group before the process exits.
    func shutdown() {
        guard let live, !isShuttingDown else { return }
        isShuttingDown = true
        reconcileDelay?.cancel()
        delayDebounce?.cancel()
        live.clicks.stop()
        live.volumeKeys.stop()
        live.observer.stop()
        if isMuted { setMuted(false) }
        live.group.shutdownNow()
        settings.restoreOutputUID = nil
        save()
    }

    // MARK: Actions

    func setListening(_ on: Bool) {
        guard live != nil else { return }
        wantsListening = on
        notice = nil
        failedSignature = nil
        if on, activePlan == nil { phase = .starting }
        requestReconcile()
    }

    func setSelected(_ uid: String, _ selected: Bool) {
        settings.setSelected(uid, selected)
        save()
        refreshRows()
        failedSignature = nil
        if wantsListening || activePlan != nil { requestReconcile() }
    }

    /// Changes a delay. While a group runs, the group is rebuilt 0.6 s after the last change
    /// (a rebuild is needed because Core Audio only reads delays when a device is created).
    func setDelay(_ milliseconds: Double, for uid: String) {
        let snapped = DelayMath.snap(milliseconds)
        guard settings.delay(for: uid) != snapped else { return }
        settings.delays[uid] = snapped
        save()
        refreshRows()
        guard live != nil, activePlan != nil else { return }
        failedSignature = nil
        delayDebounce?.cancel()
        delayDebounce = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 600_000_000)
            guard !Task.isCancelled else { return }
            self?.requestReconcile()
        }
    }

    /// Moves a device's slider: its level while it is in the group, otherwise its own volume.
    func setVolume(_ value: Double, for uid: String) {
        let clamped = VolumeMath.clamp(value)
        if let plan = activePlan, plan.memberUIDs.contains(uid) {
            settings.levels[uid] = clamped
            applyVolume(VolumeMath.effective(master: settings.master, level: clamped), to: uid)
            save()
        } else {
            applyVolume(clamped, to: uid)
        }
        refreshRows()
    }

    func setMaster(_ value: Double) {
        settings.master = VolumeMath.clamp(value)
        applyGroupVolumes()
        save()
    }

    func setUseVolumeKeys(_ on: Bool) {
        settings.useVolumeKeys = on
        save()
        updateVolumeKeyMonitor()
    }

    /// Sets delays from the latencies macOS reports for the group's (or the selected) outputs.
    func estimateDelays() {
        guard live != nil else { return }
        refreshDevices()
        let uids = activePlan?.memberUIDs ?? rows.filter { $0.isSelected && $0.canJoin }.map(\.uid)
        var latencies: [String: DeviceLatency] = [:]
        for uid in uids {
            if let device = device(uid) {
                latencies[uid] = CoreAudioHAL.latency(of: device.objectID)
            }
        }
        let suggestions = DelayEstimator.delays(for: latencies)
        guard suggestions.count >= 2 else {
            syncNote = "Choose at least two outputs to estimate their delays."
            return
        }
        for (uid, delay) in suggestions {
            settings.delays[uid] = delay
        }
        save()
        refreshRows()
        syncNote = "Estimated from the latency macOS reports. Bluetooth devices often report less than they really have, so treat this as a starting point and check with the test clicks."
        failedSignature = nil
        if activePlan != nil { requestReconcile() }
    }

    /// Plays (or stops) the test clicks on the current output.
    func toggleTestClicks() {
        guard let live else { return }
        if live.clicks.isPlaying {
            live.clicks.stop()
            isPlayingClicks = false
            return
        }
        do {
            try live.clicks.play()
            isPlayingClicks = true
        } catch let error as ClickPlayer.PlaybackError {
            syncNote = error.message
        } catch {
            syncNote = "The test clicks couldn't play: \(error.localizedDescription)"
        }
    }

    /// Connects a paired device, waits for its Core Audio output and adds it to the selection.
    func connect(_ device: PairedAudioDevice) {
        guard let live, !connecting.contains(device.address) else { return }
        connecting.insert(device.address)
        notice = nil
        Task {
            let result = await live.connector.connect(device.address)
            await self.finishConnecting(device, result: result)
        }
    }

    /// Disconnects a Bluetooth output.
    func disconnect(_ row: DeviceRow) {
        guard let live, let address = row.bluetoothAddress, !disconnecting.contains(address) else { return }
        disconnecting.insert(address)
        notice = nil
        Task {
            let disconnected = await live.connector.disconnect(address)
            self.disconnecting.remove(address)
            if !disconnected {
                self.notice = Notice(text: "macOS didn't disconnect \(row.name). Try again, or use Bluetooth settings.", isError: true)
            }
            self.refreshDevices()
            self.refreshBluetooth()
            self.refreshRows()
        }
    }

    /// Makes the built-in microphone the default input.
    func useBuiltInMicrophone() {
        guard live != nil, let input = MicrophoneAdvice.suggestedInput(from: CoreAudioHAL.inputDevices()) else { return }
        CoreAudioHAL.setDefaultInputDevice(input.objectID)
        updateMicrophoneHint()
    }

    func setOpenAtLogin(_ on: Bool) {
        guard let live else { return }
        do {
            try live.loginItem.setEnabled(on)
        } catch {
            notice = Notice(text: "macOS didn't change \"Open at login\": \(error.localizedDescription)", isError: true)
        }
        loginItemStatus = live.loginItem.status
    }

    func openLoginItemsSettings() { live?.loginItem.openSettings() }
    func openBluetoothSettings() { SystemSettingsLink.bluetooth.open() }
    func openPrivacySettings() { SystemSettingsLink.bluetoothPrivacy.open() }
    func showAbout() { AboutPanel.show() }
    func quit() { NSApplication.shared.terminate(nil) }

    // MARK: Reconciliation

    private func handle(_ change: SystemAudioObserver.Change) {
        guard !isShuttingDown else { return }
        refreshDevices()
        if change == .devices { refreshBluetooth() }
        refreshRows()
        updateMicrophoneHint()
        if wantsListening || activePlan != nil {
            scheduleReconcile(after: 0.3)
        }
    }

    private func scheduleReconcile(after seconds: Double) {
        reconcileDelay?.cancel()
        reconcileDelay = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.requestReconcile()
        }
    }

    private func requestReconcile() {
        guard live != nil, !isShuttingDown else { return }
        if reconcileRunning {
            reconcileAgain = true
            return
        }
        reconcileRunning = true
        Task {
            repeat {
                self.reconcileAgain = false
                await self.reconcileOnce()
            } while self.reconcileAgain && !self.isShuttingDown
            self.reconcileRunning = false
        }
    }

    /// One pass: read Core Audio, decide, act.
    private func reconcileOnce() async {
        guard let live, !isShuttingDown else { return }
        let group = live.group
        refreshDevices()
        let outcome = GroupPlanner.plan(
            selectedUIDs: settings.selectedUIDs,
            devices: DeviceListRules.visibleOutputs(devices, groupUIDs: group.uids),
            delays: settings.delays,
            currentMainUID: group.active?.plan.mainUID
        )
        let snapshot = GroupSnapshot(
            activeUID: group.active?.uid,
            activePlan: group.active?.plan,
            defaultOutputUID: CoreAudioHAL.defaultOutputUID()
        )
        let action = GroupReconciler.decide(wantsOn: wantsListening, outcome: outcome, snapshot: snapshot, groupUIDs: group.uids)

        switch action {
        case .none:
            break
        case .start(let plan):
            phase = .starting
            do {
                try await group.start(plan)
                settings.restoreOutputUID = group.restoreUID
                adoptVolumes(previous: nil, plan: plan)
                notice = nil
            } catch {
                wantsListening = false
                notice = Notice(text: Self.message(for: error), isError: true)
            }
        case .rebuild(let plan):
            guard plan.signature != failedSignature else { break }
            let previous = group.active?.plan
            do {
                try await group.rebuild(plan)
                failedSignature = nil
                adoptVolumes(previous: previous, plan: plan)
                notice = nil
            } catch {
                failedSignature = plan.signature
                notice = Notice(text: "The group couldn't be updated and keeps its previous setup. \(Self.message(for: error))", isError: true)
            }
        case .stop(let restoreDefault, let reason):
            phase = .stopping
            if isMuted { setMuted(false) }
            var outputName: String?
            if case .outputChangedElsewhere(let uid) = reason {
                outputName = device(uid)?.name ?? CoreAudioHAL.deviceID(forUID: uid).flatMap { CoreAudioHAL.name(of: $0) }
            }
            await group.stop(restoreDefault: restoreDefault)
            wantsListening = false
            isMuted = false
            settings.restoreOutputUID = nil
            notice = StatusText.stopped(reason, outputName: outputName).map { Notice(text: $0, isError: false) }
        case .refuse(let available):
            wantsListening = false
            notice = Notice(text: GroupError.notEnoughDevices(available: available).message, isError: false)
        }

        save()
        activePlan = group.active?.plan
        phase = group.active == nil ? .off : .on
        refreshDevices()
        refreshRows()
        updateMicrophoneHint()
    }

    private static func message(for error: any Error) -> String {
        if let groupError = error as? GroupError { return groupError.message }
        return error.localizedDescription
    }

    // MARK: Volume

    /// Takes over the members' current volumes so that starting or growing the group changes
    /// nobody's volume (see ``VolumeMath/adopt(hardwareVolumes:fallbackMaster:)``).
    private func adoptVolumes(previous: GroupPlan?, plan: GroupPlan) {
        if previous == nil {
            var hardware: [String: Double] = [:]
            for uid in plan.memberUIDs {
                if let volume = device(uid)?.volume { hardware[uid] = volume }
            }
            let mix = VolumeMath.adopt(hardwareVolumes: hardware, fallbackMaster: settings.master)
            settings.master = mix.master
            for (uid, level) in mix.levels { settings.levels[uid] = level }
        } else {
            for uid in GroupReconciler.joiningMembers(of: plan, previous: previous) {
                if let volume = device(uid)?.volume {
                    settings.levels[uid] = VolumeMath.level(forHardwareVolume: volume, master: settings.master)
                }
            }
        }
    }

    private func applyGroupVolumes() {
        guard let plan = activePlan else { return }
        if isMuted { setMuted(false) }
        for uid in plan.memberUIDs {
            applyVolume(VolumeMath.effective(master: settings.master, level: settings.level(for: uid)), to: uid)
        }
        refreshRows()
    }

    private func applyVolume(_ volume: Double, to uid: String) {
        guard live != nil, let index = devices.firstIndex(where: { $0.uid == uid }), devices[index].volume != nil else { return }
        CoreAudioHAL.setVolume(volume, of: devices[index].objectID)
        devices[index] = devices[index].withVolume(volume)
    }

    private func setMuted(_ muted: Bool) {
        isMuted = muted
        for uid in activePlan?.memberUIDs ?? [] {
            if let device = device(uid) { CoreAudioHAL.setMuted(muted, of: device.objectID) }
        }
    }

    private func handleVolumeKey(_ event: VolumeKeyEvent) {
        guard activePlan != nil else { return }
        switch event.key {
        case .up, .down:
            setMaster(VolumeMath.step(settings.master, up: event.key == .up))
        case .mute:
            if !event.isRepeat { setMuted(!isMuted) }
        }
    }

    private func updateVolumeKeyMonitor() {
        guard let live else { return }
        if settings.useVolumeKeys {
            live.volumeKeys.start { [weak self] event in
                self?.handleVolumeKey(event)
            }
        } else {
            live.volumeKeys.stop()
        }
    }

    // MARK: Refreshing

    private func device(_ uid: String) -> OutputDevice? {
        devices.first { $0.uid == uid }
    }

    private func refreshDevices() {
        guard live != nil else { return }
        devices = CoreAudioHAL.outputDevices()
    }

    private func refreshBluetooth() {
        guard let live else { return }
        bluetoothAuthorization = live.bluetooth.authorization
        // IOBluetooth is not thread-safe; leave it alone while a disconnect runs.
        guard disconnecting.isEmpty else { return }
        pairedDevices = live.bluetooth.pairedAudioDevices()
    }

    private func refreshRows() {
        let groupUIDs = live?.group.uids ?? .production
        rows = DeviceRowBuilder.rows(
            devices: devices,
            settings: settings,
            activePlan: activePlan,
            bluetoothClasses: PairedDeviceRules.classesByAddress(pairedDevices),
            groupUIDs: groupUIDs
        )
        selectedAvailable = DeviceRowBuilder.selectedAvailableCount(devices: devices, settings: settings, groupUIDs: groupUIDs)
        offlineDevices = PairedDeviceRules.notConnected(pairedDevices, presentOutputUIDs: Set(devices.map(\.uid)))
    }

    private func updateMicrophoneHint() {
        guard live != nil, let plan = activePlan else {
            showsMicrophoneHint = false
            return
        }
        showsMicrophoneHint = MicrophoneAdvice.degradesGroup(defaultInputUID: CoreAudioHAL.defaultInputUID(), memberUIDs: plan.memberUIDs)
        canUseBuiltInMicrophone = showsMicrophoneHint && MicrophoneAdvice.suggestedInput(from: CoreAudioHAL.inputDevices()) != nil
    }

    private func finishConnecting(_ device: PairedAudioDevice, result: BluetoothConnector.ConnectResult) async {
        switch result {
        case .linked:
            // The radio link is up; macOS needs a few seconds to offer the audio output.
            let outputUID = device.address.outputUID
            let appeared = await Polling.until(timeout: 15, interval: 0.5) {
                CoreAudioHAL.deviceID(forUID: outputUID) != nil
            }
            if appeared {
                settings.setSelected(outputUID, true)
                failedSignature = nil
                save()
            } else {
                notice = Notice(text: "\(device.name) is connected, but macOS hasn't offered it as a sound output yet. Try again, or check Bluetooth settings.", isError: true)
            }
        case .failed(let status):
            notice = Notice(text: "Couldn't connect \(device.name). Make sure it's switched on, nearby and not in use by another device (for AirPods, open the case). (Bluetooth error \(String(format: "0x%08X", status)))", isError: true)
        case .timedOut:
            notice = Notice(text: "\(device.name) didn't answer. Make sure it's switched on and nearby, then try again.", isError: true)
        case .unknownDevice:
            notice = Notice(text: "macOS doesn't know \(device.name) any more. Pair it again in Bluetooth settings.", isError: true)
        }
        connecting.remove(device.address)
        refreshDevices()
        refreshBluetooth()
        refreshRows()
        if wantsListening || activePlan != nil { requestReconcile() }
    }

    private func save() {
        store?.save(settings)
    }
}

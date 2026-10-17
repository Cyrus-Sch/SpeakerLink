// SPDX-License-Identifier: GPL-3.0-or-later

import SpeakerLinkCore
import SwiftUI

/// The menu bar window.
struct MenuContentView: View {
    @ObservedObject var model: AppModel

    static let width: CGFloat = 340

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HeaderView(model: model)
            if model.activePlan != nil {
                MasterVolumeView(model: model)
            }
            Divider()
            OutputsView(model: model)
            if model.hasSelection {
                SyncView(model: model)
            }
            if !model.offlineDevices.isEmpty {
                PairedDevicesView(model: model)
            }
            HintsView(model: model)
            Divider()
            OptionsView(model: model)
            NotesView()
            Divider()
            FooterView(model: model)
        }
        .padding(14)
        .frame(width: Self.width, alignment: .leading)
        .onAppear { model.menuDidAppear() }
    }
}

// MARK: - Header

private struct HeaderView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .center, spacing: 10) {
                Image(systemName: model.menuBarSymbol)
                    .font(.title2)
                    .foregroundStyle(model.activePlan != nil ? Color.accentColor : Color.secondary)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Listen together")
                        .font(.headline)
                    Text(model.statusLine)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                if model.isBusy {
                    ProgressView()
                        .controlSize(.small)
                }
                Toggle("Listen together", isOn: Binding(
                    get: { model.wantsListening },
                    set: { model.setListening($0) }
                ))
                .toggleStyle(.switch)
                .labelsHidden()
                .disabled(!model.canToggleListening)
                .help("Play everything this Mac plays on all chosen outputs at once.")
            }
            if let notice = model.notice {
                NoticeView(notice: notice)
            }
        }
    }
}

private struct NoticeView: View {
    let notice: AppModel.Notice

    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: notice.isError ? "exclamationmark.triangle.fill" : "info.circle")
                .foregroundStyle(notice.isError ? Color.orange : Color.secondary)
            Text(notice.text)
                .font(.caption)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct MasterVolumeView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: model.isMuted ? "speaker.slash.fill" : "speaker.fill")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(width: 16)
                Slider(value: Binding(
                    get: { model.settings.master },
                    set: { model.setMaster($0) }
                ), in: 0...1)
                .accessibilityLabel("Volume")
                Image(systemName: "speaker.wave.3.fill")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(model.masterVolumeNote)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - Outputs

private struct OutputsView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle("Outputs")
            if model.rows.isEmpty {
                Text("No sound outputs found.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            ForEach(model.rows) { row in
                OutputRowView(model: model, row: row)
            }
        }
    }
}

private struct OutputRowView: View {
    @ObservedObject var model: AppModel
    let row: DeviceRow

    /// Indent of the slider and note under the name (checkbox + icon).
    private static let detailIndent: CGFloat = 54

    private var volumeHelp: String {
        row.isInGroup ? "Level of this output relative to the group volume." : "Volume of this output."
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Toggle("Include \(row.name)", isOn: Binding(
                    get: { row.isSelected },
                    set: { model.setSelected(row.uid, $0) }
                ))
                .toggleStyle(.checkbox)
                .labelsHidden()
                .disabled(!row.canJoin)
                Image(systemName: DeviceSymbols.name(for: row.kind))
                    .foregroundStyle(.secondary)
                    .frame(width: 20)
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 6) {
                        Text(row.name)
                            .lineLimit(1)
                            .truncationMode(.tail)
                        if row.isMain {
                            MainBadge()
                        }
                    }
                    Text(row.subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            if let volume = row.volume {
                HStack(spacing: 6) {
                    Image(systemName: "speaker.fill")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Slider(value: Binding(
                        get: { volume },
                        set: { model.setVolume($0, for: row.uid) }
                    ), in: 0...1)
                    .controlSize(.small)
                    .accessibilityLabel("Volume of \(row.name)")
                    .help(volumeHelp)
                    Image(systemName: "speaker.wave.3.fill")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .padding(.leading, Self.detailIndent)
            }
            if let note = row.note {
                Text(note)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.leading, Self.detailIndent)
            }
        }
        .opacity(row.canJoin ? 1 : 0.6)
        .contextMenu {
            if row.bluetoothAddress != nil {
                Button("Disconnect \(row.name)") {
                    model.disconnect(row)
                }
            }
        }
    }
}

private struct MainBadge: View {
    var body: some View {
        Text("Main")
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 5)
            .padding(.vertical, 1)
            .foregroundStyle(Color.accentColor)
            .background(Color.accentColor.opacity(0.15), in: Capsule())
            .help("The main device keeps time for the group; the others follow its clock.")
    }
}

// MARK: - Sync

private struct SyncView: View {
    @ObservedObject var model: AppModel

    private var clicksTitle: String {
        model.isPlayingClicks ? "Stop Clicks" : "Play Test Clicks"
    }

    var body: some View {
        DisclosureGroup(isExpanded: $model.isSyncExpanded) {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(model.rows.filter { $0.isSelected && $0.canJoin }) { row in
                    DelayRowView(model: model, row: row)
                }
                HStack(spacing: 8) {
                    Button("Estimate") { model.estimateDelays() }
                        .help("Set delays from the latency macOS reports for each output.")
                    Button(clicksTitle) { model.toggleTestClicks() }
                        .help("Plays 8 clicks, one per second. Adjust the delays until every output clicks at the same moment.")
                }
                .controlSize(.small)
                Text(model.syncNote ?? SyncCopy.explanation)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.top, 6)
        } label: {
            SectionTitle("Sync")
        }
    }
}

private enum SyncCopy {
    static let explanation = "Bluetooth adds its own delay. A delay holds a faster output back so it lines up with a slower one; it can't make a slow output play sooner."
}

private struct DelayRowView: View {
    @ObservedObject var model: AppModel
    let row: DeviceRow

    var body: some View {
        HStack(spacing: 8) {
            Text(row.name)
                .font(.caption)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(width: 104, alignment: .leading)
            Slider(value: Binding(
                get: { Double(row.delayMs) },
                set: { model.setDelay($0, for: row.uid) }
            ), in: 0...500, step: 10)
            .controlSize(.small)
            .accessibilityLabel("Delay of \(row.name)")
            Text("\(row.delayMs) ms")
                .font(.caption.monospacedDigit())
                .frame(width: 50, alignment: .trailing)
        }
    }
}

// MARK: - Paired devices

private struct PairedDevicesView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle("Paired, not connected")
            ForEach(model.offlineDevices) { device in
                HStack(spacing: 8) {
                    Image(systemName: DeviceSymbols.name(for: device.kind))
                        .foregroundStyle(.secondary)
                        .frame(width: 20)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(device.name)
                            .lineLimit(1)
                            .truncationMode(.tail)
                        if device.isLinkUp {
                            Text("Linked, waiting for audio")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Spacer(minLength: 4)
                    if model.connecting.contains(device.address) {
                        ProgressView()
                            .controlSize(.small)
                        Text("Connecting…")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        Button("Connect") { model.connect(device) }
                            .controlSize(.small)
                    }
                }
            }
        }
    }
}

// MARK: - Hints

private struct HintsView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if model.showsMicrophoneHint {
                HintView(
                    symbol: "mic.fill",
                    text: "Headphones switch to low quality while their microphone is in use.",
                    buttonTitle: model.canUseBuiltInMicrophone ? "Use Mac Microphone" : nil,
                    action: { model.useBuiltInMicrophone() }
                )
            }
            if model.bluetoothAuthorization.isBlocked {
                HintView(
                    symbol: "lock.fill",
                    text: "SpeakerLink isn't allowed to use Bluetooth, so it can't list or connect your paired devices. Outputs that are already connected still work.",
                    buttonTitle: "Open Privacy Settings…",
                    action: { model.openPrivacySettings() }
                )
            }
            HintView(
                symbol: "plus.circle",
                text: "To pair new headphones or speakers, use Bluetooth settings.",
                buttonTitle: "Pair a Device…",
                action: { model.openBluetoothSettings() }
            )
        }
    }
}

private struct HintView: View {
    let symbol: String
    let text: String
    let buttonTitle: String?
    let action: @MainActor () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: symbol)
                .foregroundStyle(.secondary)
                .frame(width: 20)
            VStack(alignment: .leading, spacing: 4) {
                Text(text)
                    .font(.caption)
                    .fixedSize(horizontal: false, vertical: true)
                if let buttonTitle {
                    Button(buttonTitle) { action() }
                        .controlSize(.small)
                }
            }
        }
    }
}

// MARK: - Options, notes and footer

private struct OptionsView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle("Use volume keys while listening together", isOn: Binding(
                get: { model.settings.useVolumeKeys },
                set: { model.setUseVolumeKeys($0) }
            ))
            .help("The keyboard's volume keys change SpeakerLink's volume, because macOS can't change the volume of a combined output.")
            Toggle("Open at login", isOn: Binding(
                get: { model.loginItemStatus == .enabled || model.loginItemStatus == .requiresApproval },
                set: { model.setOpenAtLogin($0) }
            ))
            .disabled(model.loginItemStatus == .unavailable)
            if model.loginItemStatus == .requiresApproval {
                HStack(spacing: 6) {
                    Text("Allow SpeakerLink in Login Items to finish.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button("Open Login Items…") { model.openLoginItemsSettings() }
                        .controlSize(.small)
                }
                .padding(.leading, 20)
            }
        }
        .toggleStyle(.checkbox)
    }
}

private struct NotesView: View {
    private static let notes = [
        "Works with any Bluetooth headphones and speakers, and with wired outputs.",
        "Two or three Bluetooth devices at once is the practical limit; they share one radio.",
        "AirPods' own Share Audio needs an iPhone or iPad; it isn't available on the Mac.",
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            ForEach(Self.notes, id: \.self) { note in
                Text(note)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct FooterView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        HStack(spacing: 8) {
            Button("Bluetooth Settings…") { model.openBluetoothSettings() }
            Spacer(minLength: 4)
            Button("About SpeakerLink") { model.showAbout() }
            Button("Quit") { model.quit() }
                .keyboardShortcut("q")
        }
        .controlSize(.small)
    }
}

private struct SectionTitle: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.secondary)
    }
}

/// SF Symbols for device kinds.
enum DeviceSymbols {
    static func name(for kind: DeviceKind) -> String {
        switch kind {
        case .headphones: return "headphones"
        case .speaker: return "hifispeaker"
        case .builtIn: return "laptopcomputer"
        case .display: return "display"
        case .car: return "car"
        case .airPlay: return "airplayaudio"
        case .other: return "speaker.wave.2"
        }
    }
}

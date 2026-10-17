package io.github.cyrus_sch.speakerlink.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.cyrus_sch.speakerlink.R
import io.github.cyrus_sch.speakerlink.bluetooth.BluetoothEvent
import io.github.cyrus_sch.speakerlink.core.Confidence
import io.github.cyrus_sch.speakerlink.core.ExcludedSpeaker
import io.github.cyrus_sch.speakerlink.core.ExclusionReason
import io.github.cyrus_sch.speakerlink.core.GroupNote
import io.github.cyrus_sch.speakerlink.core.LinkFamily
import io.github.cyrus_sch.speakerlink.core.LinkStep
import io.github.cyrus_sch.speakerlink.core.PlannerSpeaker
import io.github.cyrus_sch.speakerlink.core.SpeakerModel
import io.github.cyrus_sch.speakerlink.status.SpeakerStatus

@StringRes
fun LinkFamily.labelRes(): Int = when (this) {
    LinkFamily.CONNECT -> R.string.family_connect
    LinkFamily.CONNECT_PLUS -> R.string.family_connect_plus
    LinkFamily.PARTYBOOST -> R.string.family_partyboost
    LinkFamily.AURACAST -> R.string.family_auracast
    LinkFamily.PARTYBOX_TWS -> R.string.family_partybox_tws
    LinkFamily.PARTYBOX_TWS_CLASSIC -> R.string.family_partybox_tws_classic
}

/** "PartyBoost", "PartyBoost / Auracast", "No linking"... */
@Composable
fun SpeakerModel.familyLabel(): String = when {
    families.isNotEmpty() -> families.sortedBy { it.generation }.map { stringResource(it.labelRes()) }.joinToString(" / ")
    linkingKnown -> stringResource(R.string.family_none)
    else -> stringResource(R.string.family_unknown)
}

@Composable
fun SpeakerModel.titleWithConfidence(): String {
    val name = "JBL $displayName"
    return if (confidence == Confidence.UNVERIFIED) "$name (${stringResource(R.string.unverified)})" else name
}

fun names(speakers: List<PlannerSpeaker>): String = speakers.joinToString(", ") { it.name }

@Composable
fun LinkStep.text(): String = when (this) {
    LinkStep.PowerOnAndGather -> stringResource(R.string.step_power)
    is LinkStep.ConnectPhoneToHost ->
        if (host.connectedToPhone) stringResource(R.string.step_connect_host_done, host.name)
        else stringResource(R.string.step_connect_host, host.name)
    is LinkStep.PressOnHost ->
        if (family == LinkFamily.PARTYBOX_TWS_CLASSIC) stringResource(R.string.step_press_host_tws_classic, host.name)
        else stringResource(R.string.step_press_host, family.buttonName, host.name)
    is LinkStep.PressOnMembers ->
        if (family == LinkFamily.PARTYBOX_TWS_CLASSIC) stringResource(R.string.step_press_members_tws_classic, names(members))
        else stringResource(R.string.step_press_members, family.buttonName, names(members))
    is LinkStep.LinkInPartyBoxApp -> stringResource(R.string.step_link_in_partybox_app, host.name, names(members))
    is LinkStep.ChooseMode ->
        if (stereoPossible) stringResource(R.string.step_mode_stereo) else stringResource(R.string.step_mode_party)
    LinkStep.PlayMusic -> stringResource(R.string.step_play)
}

@StringRes
fun GroupNote.textRes(): Int = when (this) {
    GroupNote.CHARGE_3_NEEDS_FIRMWARE_UPDATE -> R.string.note_charge3
    GroupNote.MULTI_FAMILY_SPEAKER -> R.string.note_multi_family
    GroupNote.PARTYBOX_NEEDS_FIRMWARE_UPDATE -> R.string.note_partybox_firmware
    GroupNote.CONTAINS_UNVERIFIED_MODEL -> R.string.note_unverified
}

@Composable
fun ExcludedSpeaker.text(): String {
    val family = family?.let { stringResource(it.labelRes()) }.orEmpty()
    return when (reason) {
        ExclusionReason.UNKNOWN_MODEL -> stringResource(R.string.excluded_unknown_model)
        ExclusionReason.LINKING_UNKNOWN -> stringResource(R.string.excluded_linking_unknown)
        ExclusionReason.NO_LINKING -> stringResource(R.string.excluded_no_linking)
        ExclusionReason.NO_PARTNER -> stringResource(R.string.excluded_no_partner, family)
        ExclusionReason.INCOMPATIBLE_MODEL -> stringResource(R.string.excluded_incompatible_model, family)
        ExclusionReason.GROUP_FULL -> stringResource(R.string.excluded_group_full, family, this.family?.maxSpeakers ?: 0)
    }
}

@StringRes
fun SpeakerStatus.Reason.textRes(): Int = when (this) {
    SpeakerStatus.Reason.UNREACHABLE -> R.string.status_failed_unreachable
    SpeakerStatus.Reason.NO_CONTROL_SERVICE -> R.string.status_failed_no_service
    SpeakerStatus.Reason.NO_RESPONSE -> R.string.status_failed_no_response
    SpeakerStatus.Reason.NO_PERMISSION -> R.string.status_failed_no_permission
}

/** A snackbar message and an optional action for a Bluetooth event. */
data class EventMessage(@StringRes val text: Int, val argument: String?, val action: EventAction?)

enum class EventAction { BLUETOOTH_SETTINGS, OUTPUT_SWITCHER, LOCATION_SETTINGS }

fun BluetoothEvent.message(): EventMessage = when (this) {
    is BluetoothEvent.Paired -> EventMessage(R.string.event_paired, name, null)
    is BluetoothEvent.PairingFailed -> EventMessage(R.string.event_pairing_failed, name, null)
    is BluetoothEvent.Connected -> EventMessage(R.string.event_connected, name, null)
    is BluetoothEvent.ConnectFailed ->
        if (limitReached) EventMessage(R.string.event_connect_limit, name, EventAction.BLUETOOTH_SETTINGS)
        else EventMessage(R.string.event_connect_failed, name, null)
    is BluetoothEvent.NeedsSystemSettings -> EventMessage(R.string.event_needs_settings, name, EventAction.BLUETOOTH_SETTINGS)
    is BluetoothEvent.NeedsOutputSwitcher -> EventMessage(R.string.event_needs_output_switcher, name, EventAction.OUTPUT_SWITCHER)
    is BluetoothEvent.ScanFailed ->
        if (locationOff) EventMessage(R.string.event_scan_location_off, null, EventAction.LOCATION_SETTINGS)
        else EventMessage(R.string.event_scan_failed, null, null)
    BluetoothEvent.BluetoothOff -> EventMessage(R.string.event_bluetooth_off, null, null)
}

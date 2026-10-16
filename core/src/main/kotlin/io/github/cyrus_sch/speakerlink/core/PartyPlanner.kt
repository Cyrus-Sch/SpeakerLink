package io.github.cyrus_sch.speakerlink.core

/** A speaker the user wants to include in a multi-speaker setup. */
data class PlannerSpeaker(
    /** Stable key, normally the Bluetooth address. */
    val key: String,
    val name: String,
    val model: SpeakerModel?,
    /** The phone has an A2DP connection to this speaker. */
    val connectedToPhone: Boolean = false,
    /** The phone is currently sending its media audio to this speaker. */
    val playing: Boolean = false,
    /** The speaker was seen recently (connected, advertising or found by a scan). */
    val present: Boolean = true,
)

/** Why a speaker cannot be part of any group in the plan. */
enum class ExclusionReason {
    /** The model is unknown; the user can pick it by hand. */
    UNKNOWN_MODEL,

    /** Sources disagree on whether this model can link at all. */
    LINKING_UNKNOWN,

    /** The model has no speaker-to-speaker linking. */
    NO_LINKING,

    /** No other selected speaker uses a compatible linking family. */
    NO_PARTNER,

    /** Others use the same family, but this model links only with specific models (e.g. PartyBox 100). */
    INCOMPATIBLE_MODEL,

    /** The group for this family is already at its maximum size. */
    GROUP_FULL,
}

data class ExcludedSpeaker(
    val speaker: PlannerSpeaker,
    val reason: ExclusionReason,
    /** The family the speaker would have used, for [ExclusionReason.NO_PARTNER] and [ExclusionReason.GROUP_FULL]. */
    val family: LinkFamily? = null,
)

/** One instruction in the guided linking flow. */
sealed interface LinkStep {
    /** Switch every speaker on and bring them close together. */
    data object PowerOnAndGather : LinkStep

    /** Connect the phone to the host speaker (the only one that needs a phone connection). */
    data class ConnectPhoneToHost(val host: PlannerSpeaker) : LinkStep

    /** Press the family's button on the host. */
    data class PressOnHost(val family: LinkFamily, val host: PlannerSpeaker) : LinkStep

    /** Press the family's button on every other speaker, within about 30 seconds. */
    data class PressOnMembers(val family: LinkFamily, val members: List<PlannerSpeaker>) : LinkStep

    /** Different PartyBox models: link them in Party mode from JBL's PartyBox app. */
    data class LinkInPartyBoxApp(val host: PlannerSpeaker, val members: List<PlannerSpeaker>) : LinkStep

    /** Choose Party or Stereo mode (Stereo only when [stereoPossible]). */
    data class ChooseMode(val family: LinkFamily, val stereoPossible: Boolean) : LinkStep

    /** Start playback on the phone; every speaker in the group plays. */
    data object PlayMusic : LinkStep
}

/** Model-specific remarks that apply to a group. */
enum class GroupNote {
    CHARGE_3_NEEDS_FIRMWARE_UPDATE,
    /** A speaker that supports two families (Xtreme 4, PartyBox Club 120 / Stage 320) was put in this one. */
    MULTI_FAMILY_SPEAKER,
    PARTYBOX_NEEDS_FIRMWARE_UPDATE,
    CONTAINS_UNVERIFIED_MODEL,
}

data class LinkGroup(
    val family: LinkFamily,
    val host: PlannerSpeaker,
    /** The other speakers, without the host. */
    val members: List<PlannerSpeaker>,
    val steps: List<LinkStep>,
    /** Exactly two speakers that can form a left/right stereo pair (normally the same model). */
    val stereoPairPossible: Boolean,
    val notes: Set<GroupNote>,
) {
    val speakers: List<PlannerSpeaker> get() = listOf(host) + members
    val size: Int get() = 1 + members.size
}

/** Ways to reach several speakers that do not depend on the speakers' own linking. */
enum class Alternative {
    /** Samsung "Dual Audio": the phone streams to two Bluetooth audio devices at once. */
    SAMSUNG_DUAL_AUDIO,

    /** Android "Audio sharing" (LE Audio broadcast); works with LE Audio speakers only. */
    LE_AUDIO_SHARING,

    /** Use the system output switcher to move playback between connected speakers. */
    SWITCH_OUTPUT,
}

data class PhoneCapabilities(
    val isSamsung: Boolean = false,
    /** The phone can be an LE Audio broadcast source and assistant (Android "Audio sharing"). */
    val leAudioSharing: Boolean = false,
)

data class PartyPlan(
    /** Linkable groups, largest first. */
    val groups: List<LinkGroup>,
    val excluded: List<ExcludedSpeaker>,
    val alternatives: List<Alternative>,
) {
    val best: LinkGroup? get() = groups.firstOrNull()

    /** True when every selected speaker can play together in a single group. */
    val everyoneInOneGroup: Boolean get() = groups.size == 1 && excluded.isEmpty()

    /** True when the selection mixes speakers that cannot all be linked to each other. */
    val mixesFamilies: Boolean
        get() = excluded.any { it.reason == ExclusionReason.INCOMPATIBLE_MODEL } ||
            (groups.map { it.family } + excluded.mapNotNull { it.family }).distinct().size > 1
}

/**
 * Works out which of the selected speakers can play together and how to link them.
 *
 * The plan is deterministic: the same input always gives the same groups, hosts and order, so the
 * UI does not jump around while Bluetooth state updates arrive.
 */
object PartyPlanner {

    fun plan(
        speakers: List<PlannerSpeaker>,
        capabilities: PhoneCapabilities = PhoneCapabilities(),
        preferredHostKey: String? = null,
    ): PartyPlan {
        val unique = speakers.distinctBy { it.key }
        val excluded = mutableListOf<ExcludedSpeaker>()
        val linkable = mutableListOf<PlannerSpeaker>()
        for (speaker in unique) {
            val model = speaker.model
            when {
                model == null -> excluded += ExcludedSpeaker(speaker, ExclusionReason.UNKNOWN_MODEL)
                model.families.isEmpty() && !model.linkingKnown ->
                    excluded += ExcludedSpeaker(speaker, ExclusionReason.LINKING_UNKNOWN)
                model.families.isEmpty() -> excluded += ExcludedSpeaker(speaker, ExclusionReason.NO_LINKING)
                else -> linkable += speaker
            }
        }

        val assignment = assignFamilies(linkable)
        val buckets = linkable.groupBy { bucketKey(it, assignment.getValue(it.key)) }

        val groups = mutableListOf<LinkGroup>()
        for ((key, members) in buckets) {
            val family = key.family
            if (members.size < 2) {
                val sameFamilyElsewhere = buckets.keys.any { it != key && it.family == family }
                val reason = if (sameFamilyElsewhere) ExclusionReason.INCOMPATIBLE_MODEL else ExclusionReason.NO_PARTNER
                excluded += members.map { ExcludedSpeaker(it, reason, family) }
                continue
            }
            val ordered = orderForHost(members, preferredHostKey)
            // Families with a size limit (two speakers) can still form several separate groups.
            val chunks = ordered.chunked(family.maxSpeakers ?: ordered.size)
            for (chunk in chunks) {
                if (chunk.size < 2) {
                    excluded += chunk.map { ExcludedSpeaker(it, ExclusionReason.GROUP_FULL, family) }
                } else {
                    groups += buildGroup(family, key.subgroup, chunk)
                }
            }
        }

        groups.sortWith(
            compareByDescending<LinkGroup> { it.size }
                .thenByDescending { it.family.generation }
                .thenBy { it.host.name },
        )

        return PartyPlan(
            groups = groups,
            excluded = excluded.sortedBy { it.reason.ordinal },
            alternatives = alternatives(unique.size, groups, excluded, capabilities),
        )
    }

    /**
     * Gives each speaker one family. Speakers with a single family keep it. Speakers with several
     * (Xtreme 4, Charge 3, PartyBox Club 120 / Stage 320) join a family that still has room, then
     * the one with the most speakers, then the newest.
     */
    private fun assignFamilies(linkable: List<PlannerSpeaker>): Map<String, LinkFamily> {
        val result = LinkedHashMap<String, LinkFamily>()
        val counts = HashMap<LinkFamily, Int>()
        val (single, multi) = linkable.partition { it.model!!.families.size == 1 }
        for (speaker in single) {
            val family = speaker.model!!.families.single()
            result[speaker.key] = family
            counts[family] = (counts[family] ?: 0) + 1
        }
        for (speaker in multi) {
            val family = speaker.model!!.families.maxWith(
                compareBy<LinkFamily> { f -> f.maxSpeakers == null || (counts[f] ?: 0) < f.maxSpeakers }
                    .thenBy { counts[it] ?: 0 }
                    .thenBy { it.generation },
            )
            result[speaker.key] = family
            counts[family] = (counts[family] ?: 0) + 1
        }
        return result
    }

    private data class BucketKey(val family: LinkFamily, val subgroup: Subgroup)

    /** Models within a family that only link with each other. */
    private enum class Subgroup { ANY, PARTYBOX_100, PARTYBOX_200_300, PARTYBOX_ON_THE_GO, PARTYBOX_APP }

    private val onTheGo = setOf("partyboxonthego", "partyboxonthegoessential")

    private fun bucketKey(speaker: PlannerSpeaker, family: LinkFamily): BucketKey {
        val id = speaker.model!!.id
        val subgroup = when (family) {
            LinkFamily.PARTYBOX_TWS_CLASSIC -> if (id == "partybox100") Subgroup.PARTYBOX_100 else Subgroup.PARTYBOX_200_300
            LinkFamily.PARTYBOX_TWS -> if (id in onTheGo) Subgroup.PARTYBOX_ON_THE_GO else Subgroup.PARTYBOX_APP
            else -> Subgroup.ANY
        }
        return BucketKey(family, subgroup)
    }

    /**
     * Host first: the user's choice (if that speaker is around), then the speaker playing, then
     * one already connected, then one that is around.
     */
    private fun orderForHost(members: List<PlannerSpeaker>, preferredHostKey: String?): List<PlannerSpeaker> {
        val host = members.firstOrNull { it.key == preferredHostKey && (it.present || it.connectedToPhone) }
            ?: members.firstOrNull { it.playing }
            ?: members.firstOrNull { it.connectedToPhone }
            ?: members.firstOrNull { it.present }
            ?: members.first()
        return listOf(host) + members.filter { it.key != host.key }
    }

    /** Two PartyBox 200/300 or two On-The-Go models also form TWS stereo pairs. */
    private fun stereoCompatible(a: SpeakerModel, b: SpeakerModel): Boolean =
        a.id == b.id ||
            setOf(a.id, b.id) == setOf("partybox200", "partybox300") ||
            (a.id in onTheGo && b.id in onTheGo)

    private fun buildGroup(family: LinkFamily, subgroup: Subgroup, speakers: List<PlannerSpeaker>): LinkGroup {
        val host = speakers.first()
        val members = speakers.drop(1)
        val models = speakers.map { it.model!! }
        val stereo = family.supportsStereoPair && speakers.size == 2 && stereoCompatible(models[0], models[1])
        val mixedPartyBoxes = subgroup == Subgroup.PARTYBOX_APP && models.distinctBy { it.id }.size > 1

        val steps = buildList {
            add(LinkStep.PowerOnAndGather)
            add(LinkStep.ConnectPhoneToHost(host))
            if (mixedPartyBoxes) {
                add(LinkStep.LinkInPartyBoxApp(host, members))
            } else {
                add(LinkStep.PressOnHost(family, host))
                add(LinkStep.PressOnMembers(family, members))
            }
            if (family.supportsStereoPair) add(LinkStep.ChooseMode(family, stereo))
            add(LinkStep.PlayMusic)
        }

        val notes = buildSet {
            if (models.any { it.note == ModelNote.CONNECT_PLUS_AFTER_FIRMWARE_UPDATE }) add(GroupNote.CHARGE_3_NEEDS_FIRMWARE_UPDATE)
            if (models.any { it.families.size > 1 }) add(GroupNote.MULTI_FAMILY_SPEAKER)
            if (models.any { it.note == ModelNote.AURACAST_AFTER_FIRMWARE_UPDATE }) add(GroupNote.PARTYBOX_NEEDS_FIRMWARE_UPDATE)
            if (models.any { it.confidence == Confidence.UNVERIFIED }) add(GroupNote.CONTAINS_UNVERIFIED_MODEL)
        }
        return LinkGroup(family, host, members, steps, stereo, notes)
    }

    private fun alternatives(
        selected: Int,
        groups: List<LinkGroup>,
        excluded: List<ExcludedSpeaker>,
        capabilities: PhoneCapabilities,
    ): List<Alternative> = buildList {
        val everyoneLinked = groups.size == 1 && excluded.isEmpty()
        if (capabilities.isSamsung && selected >= 2 && !everyoneLinked) add(Alternative.SAMSUNG_DUAL_AUDIO)
        if (capabilities.leAudioSharing && selected >= 2 && !everyoneLinked) add(Alternative.LE_AUDIO_SHARING)
        if (selected >= 1) add(Alternative.SWITCH_OUTPUT)
    }
}

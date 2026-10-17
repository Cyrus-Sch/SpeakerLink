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

/**
 * How the selected speakers can link among themselves. Phone features such as Samsung Dual Audio
 * or Android Audio sharing work with any Bluetooth audio device and are not part of the plan.
 */
data class PartyPlan(
    /** Linkable groups, largest first. */
    val groups: List<LinkGroup>,
    val excluded: List<ExcludedSpeaker>,
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
 * The plan is deterministic: the same input always gives the same groups, hosts and order. Pass
 * the [previous] plan so that pairs the user may already be linking stay together while speakers
 * connect, start playing and switch output.
 */
object PartyPlanner {

    fun plan(
        speakers: List<PlannerSpeaker>,
        preferredHostKey: String? = null,
        previous: PartyPlan? = null,
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
        val previousPairs = previous?.groups.orEmpty().filter { it.size == 2 }.map { group -> group.speakers.map { it.key }.toSet() }.toSet()

        val groups = mutableListOf<LinkGroup>()
        for ((key, members) in buckets) {
            val family = key.family
            if (members.size < 2) {
                val sameFamilyElsewhere = buckets.keys.any { it != key && it.family == family }
                val reason = if (sameFamilyElsewhere) ExclusionReason.INCOMPATIBLE_MODEL else ExclusionReason.NO_PARTNER
                excluded += members.map { ExcludedSpeaker(it, reason, family) }
                continue
            }
            // Families with a size limit (two speakers) can still form several separate groups.
            for (chunk in split(members, family.maxSpeakers, preferredHostKey, previousPairs)) {
                if (chunk.size < 2) {
                    excluded += chunk.map { ExcludedSpeaker(it, ExclusionReason.GROUP_FULL, family) }
                } else {
                    groups += buildGroup(family, key.subgroup, orderForHost(chunk, preferredHostKey))
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
        )
    }

    /**
     * Gives each speaker one family. Speakers with a single family keep it. Speakers with several
     * (Xtreme 4, Charge 3, PartyBox Club 120 / Stage 320) prefer a family where they complete or
     * join a group (speakers they can actually link with, and room left), then an empty family
     * over one whose groups are all full, then the one with the most speakers, then the newest.
     * They are assigned in a fixed order, so the result does not change when another speaker plays.
     */
    private fun assignFamilies(linkable: List<PlannerSpeaker>): Map<String, LinkFamily> {
        val result = LinkedHashMap<String, LinkFamily>()
        val counts = HashMap<BucketKey, Int>()
        fun assign(speaker: PlannerSpeaker, family: LinkFamily) {
            result[speaker.key] = family
            val key = bucketKey(speaker, family)
            counts[key] = (counts[key] ?: 0) + 1
        }
        val (single, multi) = linkable.partition { it.model!!.families.size == 1 }
        for (speaker in single) assign(speaker, speaker.model!!.families.single())
        // Address order: neither playback nor sightings (which age on their own) change the result.
        for (speaker in multi.sortedBy { it.key }) {
            fun count(family: LinkFamily) = counts[bucketKey(speaker, family)] ?: 0
            fun fit(family: LinkFamily): Int {
                val count = count(family)
                val max = family.maxSpeakers
                // Someone to link with and room left (for a limited family: a group waiting for a partner).
                val joins = if (max == null) count >= 1 else count % max != 0
                return when {
                    joins -> 2
                    count == 0 -> 1
                    else -> 0 // every group of this family is already full
                }
            }
            val family = speaker.model!!.families.maxWith(
                compareBy<LinkFamily> { fit(it) }
                    .thenBy { count(it) }
                    .thenBy { it.generation },
            )
            assign(speaker, family)
        }
        return result
    }

    /** An order that does not depend on playback state: speakers around first, same models together. */
    private val stableOrder = compareByDescending<PlannerSpeaker> { it.present }
        .thenBy { it.model?.id }
        .thenBy { it.key }

    /**
     * Splits one bucket into groups of at most [max] speakers. Two-speaker families get the best
     * pairing (see [pairUp]); larger limits are only reached with very many speakers and are
     * filled in a stable order, protected speakers first.
     */
    private fun split(
        members: List<PlannerSpeaker>,
        max: Int?,
        preferredHostKey: String?,
        previousPairs: Set<Set<String>>,
    ): List<List<PlannerSpeaker>> {
        val stable = members.sortedWith(stableOrder)
        if (max == null || stable.size <= max) return listOf(stable)
        val isProtected = protection(stable, preferredHostKey)
        if (max == 2 && stable.size <= MAX_EXACT_PAIRING) return pairUp(stable, isProtected, previousPairs)
        return stable.sortedByDescending(isProtected).chunked(max)
    }

    /**
     * Speakers that must not be left out of a group: the one playing, and the user's chosen host
     * if it is around (or if no speaker is around at all).
     */
    private fun protection(members: List<PlannerSpeaker>, preferredHostKey: String?): (PlannerSpeaker) -> Boolean {
        val nobodyAround = members.none { it.around }
        return { it.playing || (it.key == preferredHostKey && (it.around || nobodyAround)) }
    }

    private val PlannerSpeaker.around: Boolean get() = present || connectedToPhone

    /**
     * Finds the best way to pair [stable] (at most one speaker is left over). Criteria, in order:
     * no protected speaker left over; as many pairs from the previous plan as possible (the user
     * may be linking them, and "around" changes on its own as sightings age); an absent speaker
     * left over rather than one that is around; as many pairs of speakers that are around as
     * possible; protected speakers with a partner that is around; as many same-model (stereo)
     * pairs as possible; then the speaker last in the stable order left over and the first pairing
     * in stable order. Connections are deliberately not a criterion: they change one by one while
     * "Connect all" runs.
     */
    private fun pairUp(
        stable: List<PlannerSpeaker>,
        isProtected: (PlannerSpeaker) -> Boolean,
        previousPairs: Set<Set<String>>,
    ): List<List<PlannerSpeaker>> {
        fun score(pairs: List<Pair<Int, Int>>, leftover: Int?): IntArray {
            val left = leftover?.let { stable[it] }
            val retained = pairs.count { (a, b) -> setOf(stable[a].key, stable[b].key) in previousPairs }
            return intArrayOf(
                if (left != null && isProtected(left)) 0 else 1,
                retained,
                if (left != null && left.around) 0 else 1,
                pairs.count { (a, b) -> stable[a].around && stable[b].around },
                pairs.sumOf { (a, b) ->
                    (if (isProtected(stable[a]) && stable[b].around) 1 else 0) +
                        (if (isProtected(stable[b]) && stable[a].around) 1 else 0)
                },
                pairs.count { (a, b) -> stereoCompatible(stable[a].model!!, stable[b].model!!) },
                leftover ?: 0,
            )
        }

        var best: List<Pair<Int, Int>> = emptyList()
        var bestLeftover: Int? = null
        var bestScore: IntArray? = null
        val all = stable.indices.toList()
        val leftovers: List<Int?> = if (stable.size % 2 == 0) listOf(null) else all
        for (leftover in leftovers) {
            forEachPerfectMatching(all.filter { it != leftover }) { pairs ->
                val score = score(pairs, leftover)
                if (bestScore.let { it == null || compareScores(score, it) > 0 }) {
                    best = pairs
                    bestLeftover = leftover
                    bestScore = score
                }
            }
        }
        return best.map { (a, b) -> listOf(stable[a], stable[b]) } + listOfNotNull(bestLeftover?.let { listOf(stable[it]) })
    }

    /** Calls [action] with every way to split [items] (an even number) into pairs, first pairing first. */
    private fun forEachPerfectMatching(items: List<Int>, action: (List<Pair<Int, Int>>) -> Unit) {
        fun recurse(rest: List<Int>, acc: List<Pair<Int, Int>>) {
            if (rest.isEmpty()) {
                action(acc)
                return
            }
            val first = rest[0]
            for (i in 1 until rest.size) {
                recurse(rest.filterIndexed { index, _ -> index != 0 && index != i }, acc + (first to rest[i]))
            }
        }
        recurse(items, emptyList())
    }

    private fun compareScores(a: IntArray, b: IntArray): Int {
        for (i in a.indices) {
            if (a[i] != b[i]) return a[i].compareTo(b[i])
        }
        return 0
    }

    /** Up to 12 speakers there are at most 10,395 pairings to compare; beyond that, pair in order. */
    private const val MAX_EXACT_PAIRING = 12

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
     * one already connected, then one that is around, then the user's choice anyway.
     */
    private fun orderForHost(members: List<PlannerSpeaker>, preferredHostKey: String?): List<PlannerSpeaker> {
        val host = members.firstOrNull { it.key == preferredHostKey && (it.present || it.connectedToPhone) }
            ?: members.firstOrNull { it.playing }
            ?: members.firstOrNull { it.connectedToPhone }
            ?: members.firstOrNull { it.present }
            ?: members.firstOrNull { it.key == preferredHostKey }
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
}

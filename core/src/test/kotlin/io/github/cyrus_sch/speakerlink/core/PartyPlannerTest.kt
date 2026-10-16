package io.github.cyrus_sch.speakerlink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PartyPlannerTest {

    private var counter = 0

    private fun speaker(modelId: String?, playing: Boolean = false, connected: Boolean = playing): PlannerSpeaker {
        counter++
        val model = modelId?.let { ModelCatalog.byId(it) ?: error("unknown model $it") }
        return PlannerSpeaker(
            key = "00:00:00:00:00:%02X".format(counter),
            name = model?.let { "JBL ${it.displayName} #$counter" } ?: "Speaker #$counter",
            model = model,
            connectedToPhone = connected,
            playing = playing,
        )
    }

    @Test
    fun partyBoostSpeakersFormOneGroupWithThePlayingSpeakerAsHost() {
        val flip6 = speaker("flip6")
        val charge5 = speaker("charge5", playing = true)
        val pulse5 = speaker("pulse5")
        val plan = PartyPlanner.plan(listOf(flip6, charge5, pulse5))

        assertTrue(plan.everyoneInOneGroup)
        val group = plan.best!!
        assertEquals(LinkFamily.PARTYBOOST, group.family)
        assertEquals(charge5, group.host)
        assertEquals(listOf(flip6, pulse5), group.members)
        assertFalse(group.stereoPairPossible)
        assertEquals(LinkStep.PressOnMembers(LinkFamily.PARTYBOOST, listOf(flip6, pulse5)), group.steps[3])
        assertEquals(listOf(Alternative.SWITCH_OUTPUT), plan.alternatives)
    }

    @Test
    fun differentFamiliesCannotLink() {
        val plan = PartyPlanner.plan(listOf(speaker("flip6"), speaker("charge6")))
        assertTrue(plan.groups.isEmpty())
        assertEquals(2, plan.excluded.size)
        assertTrue(plan.excluded.all { it.reason == ExclusionReason.NO_PARTNER })
        assertEquals(setOf(LinkFamily.PARTYBOOST, LinkFamily.AURACAST), plan.excluded.map { it.family }.toSet())
        assertTrue(plan.mixesFamilies)
    }

    @Test
    fun xtreme4JoinsTheFamilyThatIsAlreadyThere() {
        val withPartyBoost = PartyPlanner.plan(listOf(speaker("xtreme4"), speaker("flip6")))
        assertEquals(LinkFamily.PARTYBOOST, withPartyBoost.best!!.family)
        assertTrue(GroupNote.MULTI_FAMILY_SPEAKER in withPartyBoost.best!!.notes)

        val withAuracast = PartyPlanner.plan(listOf(speaker("flip7"), speaker("xtreme4")))
        assertEquals(LinkFamily.AURACAST, withAuracast.best!!.family)
    }

    @Test
    fun twoXtreme4sUseAuracastAndCanPlayStereo() {
        val plan = PartyPlanner.plan(listOf(speaker("xtreme4"), speaker("xtreme4")))
        val group = plan.best!!
        assertEquals(LinkFamily.AURACAST, group.family)
        assertTrue(group.stereoPairPossible)
        assertEquals(LinkStep.ChooseMode(LinkFamily.AURACAST, stereoPossible = true), group.steps[4])
    }

    @Test
    fun mixedFamiliesProduceSeparateGroupsLargestFirst() {
        val plan = PartyPlanner.plan(
            listOf(speaker("flip7"), speaker("flip6"), speaker("charge5"), speaker("pulse5"), speaker("charge6")),
        )
        assertEquals(listOf(LinkFamily.PARTYBOOST, LinkFamily.AURACAST), plan.groups.map { it.family })
        assertEquals(listOf(3, 2), plan.groups.map { it.size })
        assertTrue(plan.mixesFamilies)
    }

    @Test
    fun newPartyBoxesBridgeToOlderPartyBoxesOrAuracast() {
        val withOldBox = PartyPlanner.plan(listOf(speaker("partyboxclub120"), speaker("partybox310")))
        assertEquals(LinkFamily.PARTYBOX_TWS, withOldBox.best!!.family)

        val withPortable = PartyPlanner.plan(listOf(speaker("partyboxstage320"), speaker("go4")))
        assertEquals(LinkFamily.AURACAST, withPortable.best!!.family)
    }

    @Test
    fun onTheGoModelsOnlyPairWithEachOther() {
        val pair = PartyPlanner.plan(listOf(speaker("partyboxonthego"), speaker("partyboxonthegoessential")))
        assertTrue(pair.everyoneInOneGroup)
        assertTrue(pair.best!!.stereoPairPossible)

        val mixed = PartyPlanner.plan(listOf(speaker("partyboxonthego"), speaker("partybox310")))
        assertTrue(mixed.groups.isEmpty())
        assertTrue(mixed.excluded.all { it.reason == ExclusionReason.INCOMPATIBLE_MODEL })
    }

    @Test
    fun differentAppPartyBoxesLinkInThePartyBoxApp() {
        val box110 = speaker("partybox110")
        val box310 = speaker("partybox310")
        val group = PartyPlanner.plan(listOf(box110, box310)).best!!
        assertTrue(group.steps.contains(LinkStep.LinkInPartyBoxApp(box110, listOf(box310))))
        assertFalse(group.stereoPairPossible)
    }

    @Test
    fun multiFamilySpeakersAvoidFullFamilies() {
        val plan = PartyPlanner.plan(
            listOf(speaker("partybox110"), speaker("partybox110"), speaker("partyboxclub120"), speaker("go4")),
        )
        assertTrue(plan.excluded.isEmpty())
        assertEquals(setOf(LinkFamily.PARTYBOX_TWS, LinkFamily.AURACAST), plan.groups.map { it.family }.toSet())
    }

    @Test
    fun fullTwoSpeakerFamiliesFormSeveralGroups() {
        val plan = PartyPlanner.plan(List(4) { speaker("flip3") })
        assertEquals(listOf(2, 2), plan.groups.map { it.size })
        assertTrue(plan.excluded.isEmpty())
    }

    @Test
    fun charge3LinksWithLegacyConnectOrConnectPlus() {
        assertEquals(LinkFamily.CONNECT, PartyPlanner.plan(listOf(speaker("charge3"), speaker("flip3"))).best!!.family)
        assertEquals(LinkFamily.CONNECT_PLUS, PartyPlanner.plan(listOf(speaker("charge3"), speaker("flip4"))).best!!.family)
    }

    @Test
    fun rememberedHostThatIsNotAroundIsIgnored() {
        val absent = speaker("flip6").copy(present = false)
        val playing = speaker("charge5", playing = true)
        val plan = PartyPlanner.plan(listOf(absent, playing), preferredHostKey = absent.key)
        assertEquals(playing, plan.best!!.host)
    }

    @Test
    fun legacyConnectIsLimitedToTwoSpeakers() {
        val plan = PartyPlanner.plan(listOf(speaker("flip3"), speaker("flip3"), speaker("pulse2")))
        assertEquals(2, plan.best!!.size)
        val full = plan.excluded.single()
        assertEquals(ExclusionReason.GROUP_FULL, full.reason)
        assertEquals(LinkFamily.CONNECT, full.family)
    }

    @Test
    fun partyBox100OnlyLinksWithAnother100() {
        val plan = PartyPlanner.plan(listOf(speaker("partybox100"), speaker("partybox200")))
        assertTrue(plan.groups.isEmpty())

        assertEquals(setOf(ExclusionReason.INCOMPATIBLE_MODEL), plan.excluded.map { it.reason }.toSet())
        assertTrue(plan.mixesFamilies)

        val plan2 = PartyPlanner.plan(listOf(speaker("partybox200"), speaker("partybox300")))
        assertEquals(LinkFamily.PARTYBOX_TWS_CLASSIC, plan2.best!!.family)
        assertTrue(plan2.best!!.stereoPairPossible)
    }

    @Test
    fun speakersThatCannotLinkAreExplained() {
        val plan = PartyPlanner.plan(listOf(speaker("go3"), speaker(null), speaker("flipessential2")))
        assertEquals(
            listOf(ExclusionReason.UNKNOWN_MODEL, ExclusionReason.LINKING_UNKNOWN, ExclusionReason.NO_LINKING),
            plan.excluded.map { it.reason },
        )
        assertTrue(plan.groups.isEmpty())
    }

    @Test
    fun preferredHostIsRespected() {
        val a = speaker("flip5", playing = true)
        val b = speaker("flip5")
        val plan = PartyPlanner.plan(listOf(a, b), preferredHostKey = b.key)
        assertEquals(b, plan.best!!.host)
        assertTrue(plan.best!!.stereoPairPossible)
    }

    @Test
    fun phoneSideAlternativesOnlyWhenLinkingIsNotEnough() {
        val samsung = PhoneCapabilities(isSamsung = true, leAudioSharing = true)
        val incompatible = PartyPlanner.plan(listOf(speaker("flip6"), speaker("go3")), samsung)
        assertEquals(
            listOf(Alternative.SAMSUNG_DUAL_AUDIO, Alternative.LE_AUDIO_SHARING, Alternative.SWITCH_OUTPUT),
            incompatible.alternatives,
        )

        val compatible = PartyPlanner.plan(listOf(speaker("flip6"), speaker("flip5")), samsung)
        assertEquals(listOf(Alternative.SWITCH_OUTPUT), compatible.alternatives)
    }

    @Test
    fun duplicateKeysAreIgnoredAndNotesCollected() {
        val charge3 = speaker("charge3")
        val plan = PartyPlanner.plan(listOf(charge3, charge3, speaker("flip4")))
        val group = assertNotNull(plan.best)
        assertEquals(2, group.size)
        assertTrue(GroupNote.CHARGE_3_NEEDS_FIRMWARE_UPDATE in group.notes)
    }

    @Test
    fun emptySelection() {
        val plan = PartyPlanner.plan(emptyList())
        assertTrue(plan.groups.isEmpty())
        assertTrue(plan.alternatives.isEmpty())
        assertFalse(plan.everyoneInOneGroup)
    }
}

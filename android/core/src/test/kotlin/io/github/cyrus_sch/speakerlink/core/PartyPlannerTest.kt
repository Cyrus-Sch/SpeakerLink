package io.github.cyrus_sch.speakerlink.core

import kotlin.random.Random
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
    fun rememberedHostIsUsedWhenNoSpeakerIsAround() {
        val first = speaker("flip6").copy(present = false)
        val second = speaker("flip6").copy(present = false)
        val plan = PartyPlanner.plan(listOf(first, second), preferredHostKey = second.key)
        assertEquals(second, plan.best!!.host)
    }

    @Test
    fun multiFamilyPartyBoxCompletesAWaitingPair() {
        val onTheGo = speaker("partyboxonthego")
        val box110 = speaker("partybox110")
        val club120 = speaker("partyboxclub120")
        val plan = PartyPlanner.plan(listOf(onTheGo, box110, club120))
        val group = plan.groups.single()
        assertEquals(LinkFamily.PARTYBOX_TWS, group.family)
        assertEquals(setOf(box110, club120), group.speakers.toSet())
        assertEquals(onTheGo, plan.excluded.single().speaker)
        assertEquals(ExclusionReason.INCOMPATIBLE_MODEL, plan.excluded.single().reason)
    }

    @Test
    fun multiFamilyPartyBoxMakesTheSecondPair() {
        val plan = PartyPlanner.plan(List(3) { speaker("partybox110") } + speaker("partyboxclub120"))
        assertEquals(listOf(LinkFamily.PARTYBOX_TWS, LinkFamily.PARTYBOX_TWS), plan.groups.map { it.family })
        assertEquals(listOf(2, 2), plan.groups.map { it.size })
        assertTrue(plan.excluded.isEmpty())
    }

    @Test
    fun pairsStayTheSameWhenAnotherSpeakerPlays() {
        val boxes = List(4) { speaker("partybox310") }
        fun pairs(plan: PartyPlan) = plan.groups.map { group -> group.speakers.map { it.key }.toSet() }.toSet()
        val idle = PartyPlanner.plan(boxes)
        val playing = boxes.mapIndexed { i, box -> if (i == 2) box.copy(playing = true, connectedToPhone = true) else box }
        val busy = PartyPlanner.plan(playing)
        assertEquals(setOf(setOf(boxes[0].key, boxes[1].key), setOf(boxes[2].key, boxes[3].key)), pairs(idle))
        assertEquals(pairs(idle), pairs(busy))
        assertEquals(boxes[2].key, busy.groups.single { g -> g.speakers.any { it.key == boxes[2].key } }.host.key)
    }

    @Test
    fun thePlayingOrChosenSpeakerIsNeverTheOneLeftOver() {
        val first = speaker("partybox310")
        val second = speaker("partybox310")
        val playing = speaker("partybox310", playing = true)
        val plan = PartyPlanner.plan(listOf(first, second, playing))
        assertEquals(playing, plan.best!!.host)
        assertEquals(ExclusionReason.GROUP_FULL, plan.excluded.single().reason)

        val idle = playing.copy(playing = false, connectedToPhone = false)
        val chosen = PartyPlanner.plan(listOf(first, second, idle), preferredHostKey = idle.key)
        assertEquals(idle, chosen.best!!.host)
    }

    @Test
    fun multiFamilySpeakersDoNotBreakUpFullPairs() {
        val club = speaker("partyboxclub120")
        val box1 = speaker("partybox110")
        val box2 = speaker("partybox110")
        val plan = PartyPlanner.plan(listOf(club, box1, box2))
        val pair = plan.groups.single()
        assertEquals(setOf(box1, box2), pair.speakers.toSet())
        assertTrue(pair.stereoPairPossible)
        assertEquals(ExcludedSpeaker(club, ExclusionReason.NO_PARTNER, LinkFamily.AURACAST), plan.excluded.single())

        val twoClubs = PartyPlanner.plan(listOf(speaker("partyboxclub120"), speaker("partybox110"), speaker("partyboxclub120"), speaker("partybox110")))
        assertEquals(setOf(LinkFamily.AURACAST, LinkFamily.PARTYBOX_TWS), twoClubs.groups.map { it.family }.toSet())
        assertTrue(twoClubs.groups.all { it.stereoPairPossible })

        val charges = PartyPlanner.plan(List(3) { speaker("charge3") } + List(2) { speaker("flip3") })
        assertTrue(charges.excluded.isEmpty())
        assertEquals(listOf(LinkFamily.CONNECT_PLUS, LinkFamily.CONNECT), charges.groups.map { it.family })
    }

    @Test
    fun sameModelsArePairedForStereo() {
        val plan = PartyPlanner.plan(listOf(speaker("partybox110"), speaker("partybox310"), speaker("partybox110"), speaker("partybox310")))
        assertEquals(2, plan.groups.size)
        assertTrue(plan.groups.all { group -> group.stereoPairPossible && group.speakers.map { it.model }.distinct().size == 1 })
    }

    @Test
    fun multiFamilyChoiceDoesNotDependOnWhichSpeakerPlays() {
        val xtreme4 = speaker("xtreme4")
        val club = speaker("partyboxclub120")
        val flip6 = speaker("flip6")
        fun groups(plan: PartyPlan) = plan.groups.map { group -> group.family to group.speakers.map { it.key }.toSet() }.toSet()
        val idle = PartyPlanner.plan(listOf(club, xtreme4, flip6))
        val playing = PartyPlanner.plan(listOf(xtreme4.copy(playing = true, connectedToPhone = true), club, flip6))
        assertEquals(groups(idle), groups(playing))
    }

    @Test
    fun pairsSurviveSwitchingOutputInsideAGroup() {
        val (a, b, c) = List(3) { speaker("partybox310").copy(connectedToPhone = true) }
        // c plays, so it is protected and pairs with a; b is left over.
        val first = PartyPlanner.plan(listOf(a, b, c.copy(playing = true)))
        assertEquals(setOf(a.key, c.key), first.best!!.speakers.map { it.key }.toSet())
        // Output moves to a (same group): the pair stays, a becomes the host.
        val second = PartyPlanner.plan(listOf(a.copy(playing = true), b, c), previous = first)
        assertEquals(setOf(a.key, c.key), second.best!!.speakers.map { it.key }.toSet())
        assertEquals(a.key, second.best!!.host.key)
        // Without the previous plan the stable pairing would have been a + b.
        assertEquals(setOf(a.key, b.key), PartyPlanner.plan(listOf(a.copy(playing = true), b, c)).best!!.speakers.map { it.key }.toSet())
    }

    @Test
    fun speakersThatAreAroundArePairedFirst() {
        val here = speaker("partybox310")
        val away1 = speaker("partybox310").copy(present = false)
        val away2 = speaker("partybox310").copy(present = false)
        val plan = PartyPlanner.plan(listOf(here, away1, away2))
        assertTrue(here in plan.best!!.speakers)
        assertEquals(away2, plan.excluded.single().speaker)

        val flips = List(3) { speaker("flip3") } + List(2) { speaker("flip3").copy(present = false) }
        val five = PartyPlanner.plan(flips)
        assertEquals(flips[4], five.excluded.single().speaker)
        assertTrue(five.groups.any { g -> g.speakers.toSet() == setOf(flips[0], flips[1]) })
    }

    @Test
    fun protectedSpeakerDoesNotBreakAStereoPairNeedlessly() {
        val (k1, k2) = List(2) { speaker("partybox710") }
        val k3 = speaker("partybox110")
        val k4 = speaker("partybox310")
        val k5 = speaker("partyboxencore", playing = true)
        val plan = PartyPlanner.plan(listOf(k1, k2, k3, k4, k5))
        assertTrue(plan.groups.any { it.speakers.toSet() == setOf(k1, k2) && it.stereoPairPossible })
        assertTrue(plan.groups.any { k5 in it.speakers })

        // The playing speaker gets a partner that is around, even if that costs a stereo pair.
        val (p1, p2) = List(2) { speaker("partybox310") }
        val playing = speaker("partybox110", playing = true)
        val (a1, a2) = List(2) { speaker("partybox710").copy(present = false) }
        val mixed = PartyPlanner.plan(listOf(p1, p2, playing, a1, a2))
        val hostGroup = mixed.groups.single { playing in it.speakers }
        assertTrue(hostGroup.speakers.all { it.present })
    }

    @Test
    fun rememberedHostIsKeptWhenNoSpeakerIsAroundAndThereAreTooMany() {
        val flips = List(3) { speaker("flip3").copy(present = false) }
        val plan = PartyPlanner.plan(flips, preferredHostKey = flips[2].key)
        assertEquals(flips[2], plan.best!!.host)
        assertTrue(plan.excluded.none { it.speaker == flips[2] })
    }

    @Test
    fun randomSelectionsKeepTheirPairsAndNeverDropThePlayingSpeaker() {
        val random = Random(20261017)
        val models = listOf("partybox110", "partybox310", "partybox710", "partyboxclub120", "flip3", "charge3", "xtreme4", "flip6", "go4")
        fun pairs(plan: PartyPlan) = plan.groups.map { group -> group.speakers.map { it.key }.toSet() }.toSet()
        repeat(3_000) {
            val speakers = List(random.nextInt(2, 8)) {
                speaker(models[random.nextInt(models.size)]).copy(
                    present = random.nextInt(4) != 0,
                    connectedToPhone = random.nextBoolean(),
                )
            }
            val before = PartyPlanner.plan(speakers)
            // A speaker that already is in a group starts playing: the groups stay as they are.
            val grouped = before.groups.flatMap { it.speakers }
            if (grouped.isNotEmpty()) {
                val starts = grouped[random.nextInt(grouped.size)]
                val after = PartyPlanner.plan(
                    speakers.map { if (it.key == starts.key) it.copy(playing = true, connectedToPhone = true) else it },
                    previous = before,
                )
                assertEquals(pairs(before), pairs(after), "groups changed when ${starts.name} started playing in $speakers")
            }
            // A speaker's sighting ages out (or it is seen again): the groups stay as they are.
            val flips = speakers[random.nextInt(speakers.size)]
            val aged = PartyPlanner.plan(
                speakers.map { if (it.key == flips.key) it.copy(present = !it.present) else it },
                previous = before,
            )
            assertEquals(pairs(before), pairs(aged), "groups changed when ${flips.name} came or went in $speakers")
            // The playing speaker is only left out when it has no possible partner at all.
            val playing = speakers[random.nextInt(speakers.size)].copy(playing = true, connectedToPhone = true)
            val withPlaying = PartyPlanner.plan(speakers.map { if (it.key == playing.key) playing else it.copy(playing = false) })
            withPlaying.excluded.singleOrNull { it.speaker.key == playing.key }?.let {
                assertTrue(it.reason != ExclusionReason.GROUP_FULL, "playing ${playing.name} left over in $speakers")
            }
        }
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

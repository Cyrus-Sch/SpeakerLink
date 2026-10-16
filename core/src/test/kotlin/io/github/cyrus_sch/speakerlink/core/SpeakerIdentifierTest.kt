package io.github.cyrus_sch.speakerlink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeakerIdentifierTest {

    @Test
    fun normalizeSeparatesWordsAndDigits() {
        assertEquals("jbl flip 6", SpeakerIdentifier.normalize("JBL Flip6"))
        assertEquals("jbl flip 6", SpeakerIdentifier.normalize("  jbl-flip_6 "))
        assertEquals("jbl charge 5 wi fi", SpeakerIdentifier.normalize("JBL Charge 5 Wi-Fi"))
        assertEquals("jbl partybox on the go", SpeakerIdentifier.normalize("JBL PartyBox On-The-Go"))
        assertEquals("jbl partybox 110", SpeakerIdentifier.normalize("JBL PARTYBOX110"))
    }

    @Test
    fun namesMapToTheMostSpecificModel() {
        val cases = mapOf(
            "JBL Flip 6" to "flip6",
            "JBL FLIP 5" to "flip5",
            "JBL Flip 7" to "flip7",
            "JBL Charge 5" to "charge5",
            "JBL Charge 5 Wi-Fi" to "charge5wifi",
            "JBL Xtreme" to "xtreme",
            "JBL Xtreme 2" to "xtreme2",
            "JBL Xtreme 4" to "xtreme4",
            "JBL BOOMBOX" to "boombox",
            "JBL Boombox 3" to "boombox3",
            "JBL Boombox 3 Wi-Fi" to "boombox3wifi",
            "JBL GO 4" to "go4",
            "JBL Clip 5" to "clip5",
            "JBL PartyBox Encore" to "partyboxencore",
            "JBL PartyBox Encore 2" to "partyboxencore2",
            "JBL PartyBox Encore Essential" to "partyboxencoreessential",
            "JBL PartyBox Encore Essential 2" to "partyboxencoreessential2",
            "JBL Partybox On-The-Go" to "partyboxonthego",
            "JBL PartyBox On-The-Go Essential" to "partyboxonthegoessential",
            "JBL Boost TV" to "boosttv",
            "JBL PartyBox Club 120" to "partyboxclub120",
            "JBL Partybox 200" to "partybox200",
            "JBL Flip Essential" to "flipessential",
            "JBL Flip Essential 2" to "flipessential2",
            "JBL Charge Essential 2" to "chargeessential2",
            "Party JBL Flip6 garden" to "flip6",
        )
        for ((name, id) in cases) {
            assertEquals(id, SpeakerIdentifier.modelFromName(name)?.id, "name: $name")
        }
    }

    @Test
    fun unknownNamesDoNotMatch() {
        assertNull(SpeakerIdentifier.modelFromName("JBL TUNE510BT"))
        assertNull(SpeakerIdentifier.modelFromName("Kitchen"))
        assertNull(SpeakerIdentifier.modelFromName(""))
        assertNull(SpeakerIdentifier.modelFromName(null))
    }

    @Test
    fun everyCatalogModelIsFoundByItsOwnName() {
        for (model in ModelCatalog.all) {
            assertEquals(model.id, SpeakerIdentifier.modelFromName("JBL ${model.displayName}")?.id, model.displayName)
        }
    }

    @Test
    fun nameWithoutJblIsNotTreatedAsJbl() {
        val result = SpeakerIdentifier.identify("PlayStation Pulse 3D")
        assertNull(result.model)
        assertFalse(result.isJbl)
        assertEquals(IdentificationSource.NONE, result.source)
    }

    @Test
    fun identificationPrefersUserThenAdvertisementThenName() {
        assertEquals(IdentificationSource.NAME, SpeakerIdentifier.identify("JBL Pulse 3").source)

        val byPid = SpeakerIdentifier.identify("Garden speaker", advertisedPid = 0x204F)
        assertEquals("flip6", byPid.model?.id)
        assertEquals(IdentificationSource.ADVERTISEMENT, byPid.source)

        // A product ID from another speaker nearby must not override a name that names a model.
        val conflict = SpeakerIdentifier.identify("JBL Charge 4", advertisedPid = 0x20E3)
        assertEquals("charge4", conflict.model?.id)
        assertEquals(IdentificationSource.NAME, conflict.source)

        val byUser = SpeakerIdentifier.identify("JBL Flip 6", advertisedPid = 0x204F, userModelId = "charge5")
        assertEquals("charge5", byUser.model?.id)
        assertEquals(IdentificationSource.USER, byUser.source)
    }

    @Test
    fun unknownHarmanProductIsNotAssumedToBeJbl() {
        // e.g. a Harman Kardon Aura Studio 5 (PID 0x212D) shares Harman's company ID.
        val result = SpeakerIdentifier.identify("Aura Studio 5", advertisedPid = 0x212D)
        assertNull(result.model)
        assertFalse(result.isJbl)
        assertTrue(SpeakerIdentifier.identify("JBL Something New", advertisedPid = 0x7777).isJbl)
    }

    @Test
    fun sameSpeakerNameAcceptsTruncatedLeNames() {
        assertTrue(SpeakerIdentifier.sameSpeakerName("JBL Flip 6", "jbl flip6"))
        assertTrue(SpeakerIdentifier.sameSpeakerName("FFR JBL Fli", "FFR JBL Flip 7"))
        assertFalse(SpeakerIdentifier.sameSpeakerName("JBL", "JBL Flip 6"))
        assertFalse(SpeakerIdentifier.sameSpeakerName("JBL Flip 6", "JBL Flip 5"))
        assertFalse(SpeakerIdentifier.sameSpeakerName(null, "JBL Flip 6"))
    }

    @Test
    fun jblWordMustStandAlone() {
        assertTrue(SpeakerIdentifier.looksLikeJbl("JBL Flip 6"))
        assertTrue(SpeakerIdentifier.looksLikeJbl("my-jbl"))
        assertFalse(SpeakerIdentifier.looksLikeJbl("JBLX"))
        assertFalse(SpeakerIdentifier.looksLikeJbl(null))
    }
}

package io.github.cyrus_sch.speakerlink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCatalogTest {

    @Test
    fun idsAreUnique() {
        val duplicates = ModelCatalog.all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate ids: $duplicates")
    }

    @Test
    fun productIdsAreUnique() {
        val duplicates = ModelCatalog.all.flatMap { m -> m.pids.map { it to m.id } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty(), "duplicate PIDs: $duplicates")
    }

    @Test
    fun everyModelHasANamePattern() {
        assertTrue(ModelCatalog.all.all { it.namePatterns.isNotEmpty() })
    }

    @Test
    fun productIdLookup() {
        assertEquals("flip7", ModelCatalog.byPid(0x2107)?.id)
        assertEquals("charge5", ModelCatalog.byPid(0x2040)?.id)
        assertEquals("xtreme4", ModelCatalog.byPid(0x20DC)?.id)
        assertEquals(null, ModelCatalog.byPid(0x1234))
    }

    @Test
    fun familiesMatchJblDocumentation() {
        fun families(id: String) = ModelCatalog.byId(id)!!.families
        assertEquals(setOf(LinkFamily.CONNECT_PLUS), families("flip4"))
        assertEquals(setOf(LinkFamily.PARTYBOOST), families("flip6"))
        assertEquals(setOf(LinkFamily.AURACAST), families("flip7"))
        assertEquals(setOf(LinkFamily.PARTYBOOST, LinkFamily.AURACAST), families("xtreme4"))
        assertTrue(families("go3").isEmpty())
    }

    @Test
    fun compatibility() {
        val flip6 = ModelCatalog.byId("flip6")!!
        val charge5 = ModelCatalog.byId("charge5")!!
        val charge6 = ModelCatalog.byId("charge6")!!
        val xtreme4 = ModelCatalog.byId("xtreme4")!!
        assertTrue(flip6.canLinkWith(charge5))
        assertFalse(flip6.canLinkWith(charge6))
        assertTrue(xtreme4.canLinkWith(flip6))
        assertTrue(xtreme4.canLinkWith(charge6))
        assertTrue(charge5 in ModelCatalog.compatibleWith(flip6))
        assertFalse(flip6 in ModelCatalog.compatibleWith(flip6))
    }
}

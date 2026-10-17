package io.github.cyrus_sch.speakerlink.core

import io.github.cyrus_sch.speakerlink.core.Confidence.UNVERIFIED
import io.github.cyrus_sch.speakerlink.core.ControlProtocol.AA_BLE
import io.github.cyrus_sch.speakerlink.core.ControlProtocol.AA_BR_EDR
import io.github.cyrus_sch.speakerlink.core.ControlProtocol.OTHER
import io.github.cyrus_sch.speakerlink.core.ControlProtocol.PROTOCOL_4
import io.github.cyrus_sch.speakerlink.core.LinkFamily.AURACAST
import io.github.cyrus_sch.speakerlink.core.LinkFamily.CONNECT
import io.github.cyrus_sch.speakerlink.core.LinkFamily.CONNECT_PLUS
import io.github.cyrus_sch.speakerlink.core.LinkFamily.PARTYBOOST
import io.github.cyrus_sch.speakerlink.core.LinkFamily.PARTYBOX_TWS
import io.github.cyrus_sch.speakerlink.core.LinkFamily.PARTYBOX_TWS_CLASSIC
import io.github.cyrus_sch.speakerlink.core.ProductKind.PARTYBOX
import io.github.cyrus_sch.speakerlink.core.ProductKind.WIFI

/**
 * Known JBL speaker models and how they link to each other.
 *
 * Sources: JBL support and release-note pages, JBL press releases, independent reviews and
 * open-source projects that document the product IDs (see README, "Sources").
 * Patterns are matched against [SpeakerIdentifier.normalize]d names, e.g. "jbl flip 6".
 */
object ModelCatalog {

    private fun m(
        id: String,
        name: String,
        families: Set<LinkFamily>,
        pids: Set<Int> = emptySet(),
        vararg patterns: String,
        confidence: Confidence = Confidence.VERIFIED,
        linkingKnown: Boolean = true,
        control: ControlProtocol = ControlProtocol.NONE,
        kind: ProductKind = ProductKind.PORTABLE,
        note: ModelNote? = null,
    ) = SpeakerModel(
        id = id,
        displayName = name,
        families = families,
        pids = pids,
        namePatterns = patterns.map { Regex(it) },
        confidence = confidence,
        linkingKnown = linkingKnown,
        control = control,
        kind = kind,
        note = note,
    )

    // "(?!\s*\d)" stops "xtreme" from matching "xtreme 2" etc.
    private const val NO_NUMBER = """(?!\s*\d)"""
    private const val NO_WIFI = """(?!\s*wi ?fi)"""
    private const val NO_ESSENTIAL = """(?!\s*essential)"""

    val all: List<SpeakerModel> = listOf(
        // --- Legacy JBL Connect (two speakers) ---
        m("flip3", "Flip 3", setOf(CONNECT), setOf(0x0023), """\bflip 3\b""", control = OTHER),
        m("pulse2", "Pulse 2", setOf(CONNECT), setOf(0x0026), """\bpulse 2\b""", control = OTHER),
        m("xtreme", "Xtreme", setOf(CONNECT), setOf(0x0024), """\bxtreme\b$NO_NUMBER""", control = OTHER),
        m("boosttv", "Boost TV", setOf(CONNECT), emptySet(), """\bboost tv\b""", control = OTHER),

        // --- Connect+ ---
        m("flip4", "Flip 4", setOf(CONNECT_PLUS), setOf(0x1ED1, 0x1F24), """\bflip 4\b""", control = AA_BLE),
        m(
            "charge3", "Charge 3", setOf(CONNECT, CONNECT_PLUS), setOf(0x1EBC, 0x1F25), """\bcharge 3\b""",
            control = AA_BLE, note = ModelNote.CONNECT_PLUS_AFTER_FIRMWARE_UPDATE,
        ),
        m("charge4", "Charge 4", setOf(CONNECT_PLUS), setOf(0x1F17, 0x1F29), """\bcharge 4\b""", control = AA_BLE),
        m("pulse3", "Pulse 3", setOf(CONNECT_PLUS), setOf(0x1ED2, 0x1F28), """\bpulse 3\b""", control = AA_BLE),
        m("xtreme2", "Xtreme 2", setOf(CONNECT_PLUS), setOf(0x1EFC, 0x1F26, 0x2038), """\bxtreme 2\b""", control = AA_BLE),
        m("boombox", "Boombox", setOf(CONNECT_PLUS), setOf(0x1EE7, 0x1F27), """\bboom ?box\b$NO_NUMBER""", control = AA_BLE),

        // --- PartyBoost ---
        m("flip5", "Flip 5", setOf(PARTYBOOST), setOf(0x1F31), """\bflip 5\b""", control = AA_BLE),
        m("flip6", "Flip 6", setOf(PARTYBOOST), setOf(0x204F), """\bflip 6\b""", control = AA_BR_EDR),
        m("charge5", "Charge 5", setOf(PARTYBOOST), setOf(0x2040), """\bcharge 5\b$NO_WIFI""", control = AA_BLE),
        m("pulse4", "Pulse 4", setOf(PARTYBOOST), setOf(0x1F56), """\bpulse 4\b""", control = AA_BLE),
        m("pulse5", "Pulse 5", setOf(PARTYBOOST), setOf(0x2050), """\bpulse 5\b""", control = AA_BR_EDR),
        m("xtreme3", "Xtreme 3", setOf(PARTYBOOST), setOf(0x202F), """\bxtreme 3\b""", control = AA_BLE),
        m("boombox2", "Boombox 2", setOf(PARTYBOOST), setOf(0x1F53), """\bboom ?box 2\b""", control = AA_BR_EDR),
        m("boombox3", "Boombox 3", setOf(PARTYBOOST), setOf(0x206D), """\bboom ?box 3\b$NO_WIFI""", control = AA_BR_EDR),

        // --- PartyBoost and Auracast ---
        m(
            "xtreme4", "Xtreme 4", setOf(PARTYBOOST, AURACAST), setOf(0x20DC), """\bxtreme 4\b""",
            control = AA_BLE, note = ModelNote.PARTYBOOST_AND_AURACAST,
        ),

        // --- Auracast ---
        m("go4", "Go 4", setOf(AURACAST), setOf(0x20E4, 0x210A, 0x215C, 0x2181), """\bgo 4\b""", control = AA_BLE),
        m("clip5", "Clip 5", setOf(AURACAST), setOf(0x20F5), """\bclip 5\b""", control = AA_BLE),
        m("charge6", "Charge 6", setOf(AURACAST), setOf(0x20E3), """\bcharge 6\b""", control = AA_BLE),
        m("flip7", "Flip 7", setOf(AURACAST), setOf(0x2107), """\bflip 7\b""", control = AA_BLE),
        m("xtreme5", "Xtreme 5", setOf(AURACAST), setOf(0x2169), """\bxtreme 5\b""", control = PROTOCOL_4),
        m("go5", "Go 5", setOf(AURACAST), setOf(0x2168), """\bgo 5\b""", control = PROTOCOL_4),
        m("boombox4", "Boombox 4", setOf(AURACAST), setOf(0x214E), """\bboom ?box 4\b""", control = PROTOCOL_4),
        m("grip", "Grip", setOf(AURACAST), setOf(0x2132), """\bgrip\b""", confidence = UNVERIFIED, control = PROTOCOL_4),
        m("tuner3", "Tuner 3", setOf(AURACAST), setOf(0x214C), """\btuner 3\b""", confidence = UNVERIFIED, control = AA_BLE),
        m(
            "flipessential3", "Flip Essential 3 SE", setOf(AURACAST), setOf(0x2185), """\bflip essential 3\b""",
            confidence = UNVERIFIED, control = PROTOCOL_4,
        ),
        m(
            "chargeessential3", "Charge Essential 3 SE", setOf(AURACAST), setOf(0x218A), """\bcharge essential 3\b""",
            confidence = UNVERIFIED, control = PROTOCOL_4,
        ),

        // --- PartyBox, Auracast generation (2024+) ---
        m(
            "partyboxclub120", "PartyBox Club 120", setOf(AURACAST, PARTYBOX_TWS), emptySet(), """\bclub 120\b""",
            control = OTHER, kind = PARTYBOX, note = ModelNote.AURACAST_AFTER_FIRMWARE_UPDATE,
        ),
        m(
            "partyboxstage320", "PartyBox Stage 320", setOf(AURACAST, PARTYBOX_TWS), emptySet(), """\bstage 320\b""",
            control = OTHER, kind = PARTYBOX, note = ModelNote.AURACAST_AFTER_FIRMWARE_UPDATE,
        ),
        m(
            "partybox520", "PartyBox 520", setOf(AURACAST), setOf(0x2120), """\bparty ?box 520\b""",
            control = OTHER, kind = PARTYBOX, note = ModelNote.AURACAST_AFTER_FIRMWARE_UPDATE,
        ),
        m(
            "partybox720", "PartyBox 720", setOf(AURACAST), emptySet(), """\bparty ?box 720\b""",
            control = OTHER, kind = PARTYBOX, note = ModelNote.AURACAST_AFTER_FIRMWARE_UPDATE,
        ),
        m(
            "partyboxencore2", "PartyBox Encore 2", setOf(AURACAST), emptySet(), """\bparty ?box encore 2\b""",
            control = OTHER, kind = PARTYBOX, note = ModelNote.AURACAST_AFTER_FIRMWARE_UPDATE,
        ),
        m(
            "partyboxencoreessential2", "PartyBox Encore Essential 2", setOf(AURACAST), emptySet(),
            """\bparty ?box encore essential 2\b""",
            control = OTHER, kind = PARTYBOX, note = ModelNote.AURACAST_AFTER_FIRMWARE_UPDATE,
        ),
        m(
            "partyboxultimate", "PartyBox Ultimate", setOf(AURACAST), emptySet(), """\bparty ?box ultimate\b""",
            confidence = UNVERIFIED, control = OTHER, kind = PARTYBOX, note = ModelNote.AURACAST_AFTER_FIRMWARE_UPDATE,
        ),

        // --- PartyBox, TWS generation ---
        m("partybox110", "PartyBox 110", setOf(PARTYBOX_TWS), emptySet(), """\bparty ?box 110\b""", control = OTHER, kind = PARTYBOX),
        m("partybox310", "PartyBox 310", setOf(PARTYBOX_TWS), emptySet(), """\bparty ?box 310\b""", control = OTHER, kind = PARTYBOX),
        m("partybox710", "PartyBox 710", setOf(PARTYBOX_TWS), emptySet(), """\bparty ?box 710\b""", control = OTHER, kind = PARTYBOX),
        m(
            "partybox1000", "PartyBox 1000", setOf(PARTYBOX_TWS), emptySet(), """\bparty ?box 1000\b""",
            confidence = UNVERIFIED, control = OTHER, kind = PARTYBOX,
        ),
        m(
            "partyboxencore", "PartyBox Encore", setOf(PARTYBOX_TWS), emptySet(),
            """\bparty ?box encore\b(?!\s*(\d|essential))""", control = OTHER, kind = PARTYBOX,
        ),
        m(
            "partyboxencoreessential", "PartyBox Encore Essential", setOf(PARTYBOX_TWS), emptySet(),
            """\bparty ?box encore essential\b$NO_NUMBER""", control = OTHER, kind = PARTYBOX,
        ),
        m(
            "partyboxonthego", "PartyBox On-The-Go", setOf(PARTYBOX_TWS), emptySet(), """\bparty ?box on the go\b$NO_ESSENTIAL""",
            control = OTHER, kind = PARTYBOX,
        ),
        m(
            "partyboxonthegoessential", "PartyBox On-The-Go Essential", setOf(PARTYBOX_TWS), emptySet(),
            """\bparty ?box on the go essential\b""", control = OTHER, kind = PARTYBOX,
        ),
        m("partybox100", "PartyBox 100", setOf(PARTYBOX_TWS_CLASSIC), emptySet(), """\bparty ?box 100\b""", control = OTHER, kind = PARTYBOX),
        m(
            "partybox200", "PartyBox 200", setOf(PARTYBOX_TWS_CLASSIC), emptySet(), """\bparty ?box 200\b""",
            control = OTHER, kind = PARTYBOX, note = ModelNote.PARTYBOX_200_300_INTERLINK,
        ),
        m(
            "partybox300", "PartyBox 300", setOf(PARTYBOX_TWS_CLASSIC), emptySet(), """\bparty ?box 300\b""",
            control = OTHER, kind = PARTYBOX, note = ModelNote.PARTYBOX_200_300_INTERLINK,
        ),

        // --- No speaker-to-speaker linking ---
        m("go2", "Go 2", emptySet(), emptySet(), """\bgo 2\b"""),
        m("go3", "Go 3", emptySet(), emptySet(), """\bgo 3\b"""),
        m("clip3", "Clip 3", emptySet(), emptySet(), """\bclip 3\b"""),
        m("clip4", "Clip 4", emptySet(), emptySet(), """\bclip 4\b"""),
        m("flipessential", "Flip Essential", emptySet(), emptySet(), """\bflip essential\b$NO_NUMBER""", confidence = UNVERIFIED, linkingKnown = false),
        m("flipessential2", "Flip Essential 2", emptySet(), emptySet(), """\bflip essential 2\b""", confidence = UNVERIFIED, linkingKnown = false),
        m("chargeessential", "Charge Essential", emptySet(), emptySet(), """\bcharge essential\b$NO_NUMBER""", confidence = UNVERIFIED, linkingKnown = false),
        m("chargeessential2", "Charge Essential 2", emptySet(), emptySet(), """\bcharge essential 2\b""", confidence = UNVERIFIED, linkingKnown = false),

        // --- Wi-Fi editions (JBL One app) ---
        m(
            "charge5wifi", "Charge 5 Wi-Fi", emptySet(), setOf(0x208C), """\bcharge 5\s*wi ?fi\b""",
            linkingKnown = false, kind = WIFI, note = ModelNote.WIFI_EDITION,
        ),
        m(
            "boombox3wifi", "Boombox 3 Wi-Fi", emptySet(), setOf(0x2075), """\bboom ?box 3\s*wi ?fi\b""",
            linkingKnown = false, kind = WIFI, note = ModelNote.WIFI_EDITION,
        ),
    )

    private val byId: Map<String, SpeakerModel> = all.associateBy { it.id }

    private val byPid: Map<Int, SpeakerModel> = buildMap {
        for (model in all) for (pid in model.pids) putIfAbsent(pid, model)
    }

    fun byId(id: String?): SpeakerModel? = id?.let(byId::get)

    fun byPid(pid: Int?): SpeakerModel? = pid?.let(byPid::get)

    /** Models that can be in one group together with [model]. */
    fun compatibleWith(model: SpeakerModel): List<SpeakerModel> =
        all.filter { it != model && it.canLinkWith(model) }

    /** All models of a family, in catalog order. */
    fun inFamily(family: LinkFamily): List<SpeakerModel> = all.filter { family in it.families }
}

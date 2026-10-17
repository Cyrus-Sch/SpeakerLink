package io.github.cyrus_sch.speakerlink.core

/** How sure we are about a model's linking support. */
enum class Confidence {
    /** Confirmed by the manufacturer's documentation or several independent sources. */
    VERIFIED,

    /** Based on a single or conflicting source; shown to the user as "unverified". */
    UNVERIFIED,
}

/** The vendor control protocol a model speaks, used for the optional status (battery) reader. */
enum class ControlProtocol {
    /** "AA" framed protocol on the vendor GATT service, reachable over Bluetooth LE. */
    AA_BLE,

    /** Same "AA" protocol, but the GATT service is reached over the Classic (BR/EDR) link. */
    AA_BR_EDR,

    /** Newer "protocol 4" frames (2025+ models). Not supported by this app yet. */
    PROTOCOL_4,

    /** Legacy models that use Classic SPP, or PartyBox models with their own opcode set. */
    OTHER,

    /** No known control protocol. */
    NONE,
}

/** What kind of product a model is; only affects wording and icons. */
enum class ProductKind { PORTABLE, PARTYBOX, WIFI }

/** Extra information worth showing next to a model. */
enum class ModelNote {
    /** Charge 3: legacy JBL Connect on launch firmware, Connect+ after the firmware update. */
    CONNECT_PLUS_AFTER_FIRMWARE_UPDATE,

    /** Xtreme 4: joins PartyBoost groups and Auracast groups (one group at a time). */
    PARTYBOOST_AND_AURACAST,

    /** 2024+ PartyBox models: Auracast (and linking with older PartyBoxes) arrived with firmware updates. */
    AURACAST_AFTER_FIRMWARE_UPDATE,

    /** PartyBox 200 and 300 can be linked with each other. */
    PARTYBOX_200_300_INTERLINK,

    /** Wi-Fi editions are managed by the JBL One app and do not use PartyBoost. */
    WIFI_EDITION,
}

/**
 * A JBL speaker model.
 *
 * @property id stable identifier persisted in preferences (never rename).
 * @property families linking families this model supports; empty when the model cannot link.
 * @property linkingKnown false when the sources disagree about whether the model can link at all.
 * @property pids Harman product IDs found in the model's Bluetooth LE advertisements.
 * @property namePatterns regular expressions matched against [SpeakerIdentifier.normalize]d names.
 */
data class SpeakerModel(
    val id: String,
    val displayName: String,
    val families: Set<LinkFamily>,
    val pids: Set<Int> = emptySet(),
    val namePatterns: List<Regex>,
    val confidence: Confidence = Confidence.VERIFIED,
    val linkingKnown: Boolean = true,
    val control: ControlProtocol = ControlProtocol.NONE,
    val kind: ProductKind = ProductKind.PORTABLE,
    val note: ModelNote? = null,
) {
    val canLink: Boolean get() = families.isNotEmpty()

    /** True when this model and [other] can be in the same speaker-to-speaker group. */
    fun canLinkWith(other: SpeakerModel): Boolean = families.any { it in other.families }

    override fun toString(): String = "JBL $displayName"
}

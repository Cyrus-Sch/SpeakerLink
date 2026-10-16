package io.github.cyrus_sch.speakerlink.core

/**
 * The speaker-to-speaker linking technologies used by JBL speakers.
 *
 * In every family the phone streams to ONE "host" speaker over normal Bluetooth (A2DP) and the
 * other speakers receive the audio from that host. Families cannot be mixed, except for models
 * that support more than one family (the Xtreme 4 speaks both PartyBoost and Auracast).
 */
enum class LinkFamily(
    /** Name of the button the user presses on the speaker to start or join a group. */
    val buttonName: String,
    /** Maximum number of speakers in one group, or null when the manufacturer documents no limit. */
    val maxSpeakers: Int?,
    /** Whether two speakers of the same model can be used as a left/right stereo pair. */
    val supportsStereoPair: Boolean,
    /** Higher is newer; used to break ties when a speaker could join more than one group. */
    val generation: Int,
) {
    /** The original JBL Connect (2015-2016). Two speakers only. */
    CONNECT(buttonName = "Connect", maxSpeakers = 2, supportsStereoPair = false, generation = 1),

    /** JBL Connect+ (2016-2018). Up to 100 speakers. */
    CONNECT_PLUS(buttonName = "Connect+", maxSpeakers = 100, supportsStereoPair = true, generation = 2),

    /** JBL PartyBoost (2019-2023). Up to 100 speakers. */
    PARTYBOOST(buttonName = "PartyBoost", maxSpeakers = 100, supportsStereoPair = true, generation = 3),

    /** JBL Auracast multi-speaker linking (2024 onward). JBL documents no fixed limit. */
    AURACAST(buttonName = "Auracast", maxSpeakers = null, supportsStereoPair = true, generation = 4),

    /** PartyBox 100/200/300: True Wireless Stereo link between two PartyBoxes. */
    PARTYBOX_TWS_CLASSIC(buttonName = "TWS", maxSpeakers = 2, supportsStereoPair = true, generation = 1),

    /** PartyBox 110/310/710/1000/Encore/On-The-Go: True Wireless Stereo link between two PartyBoxes. */
    PARTYBOX_TWS(buttonName = "TWS", maxSpeakers = 2, supportsStereoPair = true, generation = 2),
}

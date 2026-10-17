package io.github.cyrus_sch.speakerlink.ui

/**
 * What the phone itself can do to play on several Bluetooth audio devices at once. Unlike the
 * speakers' own linking, these work with any kind of device: speakers, headphones or a mix.
 */
enum class PhoneOption {
    /** Samsung Dual Audio: two Bluetooth audio devices of any kind at once (media output panel). */
    DUAL_AUDIO,

    /** Android Audio sharing (LE Audio broadcast, Android 16+): LE Audio headphones and speakers. */
    AUDIO_SHARING,

    /** Neither is available: the phone plays on one Bluetooth device at a time; switching is the option. */
    ONE_AT_A_TIME,
}

/** The phone's own options for [selectedCount] selected devices; none until two are selected. */
fun phoneOptions(isSamsung: Boolean, audioSharing: Boolean, selectedCount: Int): List<PhoneOption> {
    if (selectedCount < 2) return emptyList()
    return buildList {
        if (isSamsung) add(PhoneOption.DUAL_AUDIO)
        if (audioSharing) add(PhoneOption.AUDIO_SHARING)
        if (isEmpty()) add(PhoneOption.ONE_AT_A_TIME)
    }
}

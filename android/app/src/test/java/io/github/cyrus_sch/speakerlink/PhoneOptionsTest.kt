package io.github.cyrus_sch.speakerlink

import io.github.cyrus_sch.speakerlink.ui.PhoneOption
import io.github.cyrus_sch.speakerlink.ui.phoneOptions
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneOptionsTest {

    @Test
    fun nothingToOfferForFewerThanTwoDevices() {
        assertEquals(emptyList<PhoneOption>(), phoneOptions(isSamsung = true, audioSharing = true, selectedCount = 1))
        assertEquals(emptyList<PhoneOption>(), phoneOptions(isSamsung = false, audioSharing = false, selectedCount = 0))
    }

    @Test
    fun phoneFeaturesWorkForAnyTwoDevices() {
        assertEquals(listOf(PhoneOption.DUAL_AUDIO), phoneOptions(isSamsung = true, audioSharing = false, selectedCount = 2))
        assertEquals(listOf(PhoneOption.AUDIO_SHARING), phoneOptions(isSamsung = false, audioSharing = true, selectedCount = 3))
        assertEquals(
            listOf(PhoneOption.DUAL_AUDIO, PhoneOption.AUDIO_SHARING),
            phoneOptions(isSamsung = true, audioSharing = true, selectedCount = 2),
        )
    }

    @Test
    fun otherwiseOneDeviceAtATime() {
        assertEquals(listOf(PhoneOption.ONE_AT_A_TIME), phoneOptions(isSamsung = false, audioSharing = false, selectedCount = 4))
    }
}

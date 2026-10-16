package io.github.cyrus_sch.speakerlink

import io.github.cyrus_sch.speakerlink.bluetooth.BluetoothEvent
import io.github.cyrus_sch.speakerlink.ui.EventAction
import io.github.cyrus_sch.speakerlink.ui.message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventMessageTest {

    @Test
    fun eventsThatNeedTheSystemOfferAnAction() {
        assertEquals(EventAction.BLUETOOTH_SETTINGS, BluetoothEvent.NeedsSystemSettings("A", "JBL Flip 6").message().action)
        assertEquals(EventAction.OUTPUT_SWITCHER, BluetoothEvent.NeedsOutputSwitcher("A", "JBL Flip 6").message().action)
        assertEquals(EventAction.LOCATION_SETTINGS, BluetoothEvent.ScanFailed(locationOff = true).message().action)
        assertEquals(EventAction.BLUETOOTH_SETTINGS, BluetoothEvent.ConnectFailed("A", "x", limitReached = true).message().action)
    }

    @Test
    fun informationalEventsCarryTheSpeakerName() {
        val paired = BluetoothEvent.Paired("A", "JBL Charge 5").message()
        assertEquals(R.string.event_paired, paired.text)
        assertEquals("JBL Charge 5", paired.argument)
        assertNull(paired.action)
        assertNull(BluetoothEvent.BluetoothOff.message().argument)
    }
}

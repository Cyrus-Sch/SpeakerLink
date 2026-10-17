package io.github.cyrus_sch.speakerlink

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.cyrus_sch.speakerlink.bluetooth.BtPermissions
import io.github.cyrus_sch.speakerlink.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the real app (with the real Bluetooth stack of the device or emulator). */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*BtPermissions.runtime)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun allTabsRender() {
        compose.onNodeWithTag("devices_list").assertIsDisplayed()
        compose.onNodeWithTag("devices_list").performScrollToNode(hasTestTag("scan_button"))
        compose.onNodeWithTag("scan_button").assertIsDisplayed()

        compose.onNodeWithTag("tab_together").performClick()
        compose.onNodeWithTag("together_list").assertIsDisplayed()

        compose.onNodeWithTag("tab_info").performClick()
        compose.onNodeWithTag("info_list").performScrollToNode(hasTestTag("disclaimer"))
        compose.onNodeWithTag("disclaimer").assertIsDisplayed()

        compose.onNodeWithTag("tab_devices").performClick()
        compose.onNodeWithTag("devices_list").assertIsDisplayed()
    }
}

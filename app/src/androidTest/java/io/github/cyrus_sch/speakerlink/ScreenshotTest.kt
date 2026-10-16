package io.github.cyrus_sch.speakerlink

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cyrus_sch.speakerlink.bluetooth.AdapterState
import io.github.cyrus_sch.speakerlink.bluetooth.LinkState
import io.github.cyrus_sch.speakerlink.core.IdentificationSource
import io.github.cyrus_sch.speakerlink.core.ModelCatalog
import io.github.cyrus_sch.speakerlink.core.PartyPlanner
import io.github.cyrus_sch.speakerlink.core.PlannerSpeaker
import io.github.cyrus_sch.speakerlink.core.SpeakerInfo
import io.github.cyrus_sch.speakerlink.status.SpeakerStatus
import io.github.cyrus_sch.speakerlink.system.DeviceCapabilities
import io.github.cyrus_sch.speakerlink.ui.AppRoot
import io.github.cyrus_sch.speakerlink.ui.InfoActions
import io.github.cyrus_sch.speakerlink.ui.PartyActions
import io.github.cyrus_sch.speakerlink.ui.SpeakerActions
import io.github.cyrus_sch.speakerlink.ui.SpeakerUi
import io.github.cyrus_sch.speakerlink.ui.UiState
import io.github.cyrus_sch.speakerlink.ui.theme.SpeakerLinkTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the screens with sample speakers and saves them as PNGs (store listing screenshots).
 * CI pulls them from the app's external files directory.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun speaker(
        address: String,
        name: String,
        modelId: String,
        link: LinkState = LinkState.DISCONNECTED,
        playing: Boolean = false,
        nearby: Boolean = false,
        battery: Int? = null,
    ) = SpeakerUi(
        address = address,
        name = name,
        model = ModelCatalog.byId(modelId),
        modelSource = IdentificationSource.NAME,
        isJbl = true,
        bonded = true,
        bonding = false,
        link = link,
        playing = playing,
        nearby = nearby || link == LinkState.CONNECTED,
        inParty = true,
        status = battery?.let { SpeakerStatus.Ready(SpeakerInfo(batteryPercent = it, charging = false), 0) },
        canReadStatus = true,
    )

    private fun sampleState(): UiState {
        val speakers = listOf(
            speaker("00:11:22:33:44:01", "JBL Flip 6", "flip6", LinkState.CONNECTED, playing = true, battery = 80),
            speaker("00:11:22:33:44:02", "JBL Charge 5", "charge5", LinkState.CONNECTED, battery = 55),
            speaker("00:11:22:33:44:03", "JBL Pulse 5", "pulse5", nearby = true),
            speaker("00:11:22:33:44:04", "JBL Flip 7", "flip7"),
        )
        val plan = PartyPlanner.plan(
            speakers.map { PlannerSpeaker(it.address, it.name, it.model, it.connected, it.playing) },
        )
        return UiState(
            bluetoothSupported = true,
            permissionsGranted = true,
            adapter = AdapterState.ON,
            speakers = speakers,
            discovered = emptyList(),
            scanning = false,
            connectedCount = 2,
            maxConnected = 5,
            playing = speakers.first(),
            canConnectDirectly = true,
            partySpeakers = speakers,
            plan = plan,
            capabilities = DeviceCapabilities(
                manufacturer = "Google",
                model = "Pixel",
                androidRelease = "16",
                sdkInt = 36,
                leAudio = true,
                leBroadcastSource = true,
                leBroadcastAssistant = true,
                outputSwitcher = true,
            ),
        )
    }

    @Test
    fun captureScreens() {
        val noop1: (String) -> Unit = {}
        val noop2: (String, Boolean) -> Unit = { _, _ -> }
        val speakerActions = SpeakerActions(
            requestPermissions = {}, openAppSettings = {}, enableBluetooth = {}, scan = {}, stopScan = {},
            pair = noop1, connect = noop1, disconnect = noop1, playOn = noop1, connectAll = {},
            setInParty = noop2, setModel = { _, _ -> }, readStatus = noop1, readAllStatuses = {}, locate = noop1,
            testSound = {}, openOutputSwitcher = {}, openBluetoothSettings = {}, setShowAll = {},
        )
        val partyActions = PartyActions(noop2, noop1, noop1, {}, {}, {})
        val infoActions = InfoActions({}, {})
        val state = sampleState()

        compose.setContent {
            SpeakerLinkTheme(darkTheme = false) {
                AppRoot(state, emptyFlow(), speakerActions, partyActions, infoActions)
            }
        }
        save("1_speakers")
        compose.onNodeWithTag("tab_party").performClick()
        save("2_party")
        compose.onNodeWithTag("tab_info").performClick()
        save("3_info")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

package io.github.cyrus_sch.speakerlink

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.cyrus_sch.speakerlink.bluetooth.AdapterState
import io.github.cyrus_sch.speakerlink.bluetooth.AudioKind
import io.github.cyrus_sch.speakerlink.bluetooth.LinkState
import io.github.cyrus_sch.speakerlink.core.IdentificationSource
import io.github.cyrus_sch.speakerlink.core.ModelCatalog
import io.github.cyrus_sch.speakerlink.core.PartyPlanner
import io.github.cyrus_sch.speakerlink.core.PlannerSpeaker
import io.github.cyrus_sch.speakerlink.core.SpeakerInfo
import io.github.cyrus_sch.speakerlink.status.SpeakerStatus
import io.github.cyrus_sch.speakerlink.system.DeviceCapabilities
import io.github.cyrus_sch.speakerlink.ui.AppRoot
import io.github.cyrus_sch.speakerlink.ui.DeviceActions
import io.github.cyrus_sch.speakerlink.ui.DeviceUi
import io.github.cyrus_sch.speakerlink.ui.InfoActions
import io.github.cyrus_sch.speakerlink.ui.TogetherActions
import io.github.cyrus_sch.speakerlink.ui.UiState
import io.github.cyrus_sch.speakerlink.ui.phoneOptions
import io.github.cyrus_sch.speakerlink.ui.theme.SpeakerLinkTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the screens with sample devices and saves them as PNGs (store listing screenshots).
 * CI pulls them from the app's external files directory.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** A device; with a [jblModelId] it is a JBL speaker with the JBL extras. */
    private fun device(
        address: String,
        name: String,
        kind: AudioKind,
        jblModelId: String? = null,
        link: LinkState = LinkState.DISCONNECTED,
        playing: Boolean = false,
        nearby: Boolean = false,
        battery: Int? = null,
    ) = DeviceUi(
        address = address,
        name = name,
        kind = kind,
        model = jblModelId?.let { ModelCatalog.byId(it) ?: error("unknown model $it") },
        modelSource = if (jblModelId != null) IdentificationSource.NAME else IdentificationSource.NONE,
        isJbl = jblModelId != null,
        bonded = true,
        bonding = false,
        link = link,
        playing = playing,
        nearby = nearby || link == LinkState.CONNECTED,
        selected = true,
        status = battery?.let { SpeakerStatus.Ready(SpeakerInfo(batteryPercent = it, charging = false), 0) },
        canReadStatus = jblModelId != null,
    )

    private fun sampleState(): UiState {
        val devices = listOf(
            device("00:11:22:33:44:01", "Anna's headphones", AudioKind.HEADPHONES, link = LinkState.CONNECTED, playing = true),
            device("00:11:22:33:44:02", "Earbuds", AudioKind.HEADPHONES, link = LinkState.CONNECTED),
            device("00:11:22:33:44:03", "JBL Flip 6", AudioKind.SPEAKER, jblModelId = "flip6", link = LinkState.CONNECTED, battery = 80),
            device("00:11:22:33:44:04", "Living room speaker", AudioKind.SPEAKER, nearby = true),
        )
        val capabilities = DeviceCapabilities(
            manufacturer = "Google",
            model = "Pixel",
            androidRelease = "16",
            sdkInt = 36,
            leAudio = true,
            leBroadcastSource = true,
            leBroadcastAssistant = true,
            outputSwitcher = true,
        )
        val plan = PartyPlanner.plan(
            devices.filter { it.isJbl }.map { PlannerSpeaker(it.address, it.name, it.model, it.connected, it.playing) },
        )
        return UiState(
            bluetoothSupported = true,
            permissionsGranted = true,
            adapter = AdapterState.ON,
            devices = devices,
            discovered = emptyList(),
            scanning = false,
            connectedCount = devices.count { it.connected },
            maxConnected = 5,
            playing = devices.first { it.playing },
            canConnectDirectly = true,
            selected = devices,
            phoneOptions = phoneOptions(capabilities.isSamsung, capabilities.audioSharing, devices.size),
            plan = plan,
            capabilities = capabilities,
        )
    }

    @Test
    fun captureScreens() {
        val noop1: (String) -> Unit = {}
        val noop2: (String, Boolean) -> Unit = { _, _ -> }
        val deviceActions = DeviceActions(
            requestPermissions = {}, openAppSettings = {}, enableBluetooth = {}, scan = {}, stopScan = {},
            pair = noop1, connect = noop1, disconnect = noop1, playOn = noop1, connectAll = {},
            setSelected = noop2, setModel = { _, _ -> }, readStatus = noop1, readAllStatuses = {}, locate = noop1,
            testSound = {}, openOutputSwitcher = {}, openBluetoothSettings = {},
        )
        val togetherActions = TogetherActions(noop2, noop1, noop1, {}, {}, {})
        val infoActions = InfoActions({}, {})
        val state = sampleState()

        compose.setContent {
            SpeakerLinkTheme(darkTheme = false, dynamicColor = false) {
                AppRoot(state, emptyFlow(), deviceActions, togetherActions, infoActions)
            }
        }
        // File names are kept for the store listing (2_party is the Together tab).
        save("1_speakers")
        compose.onNodeWithTag("tab_together").performClick()
        save("2_party")
        compose.onNodeWithTag("tab_info").performClick()
        save("3_info")
    }

    /**
     * Writes to the Android Gradle plugin's "additional test output" directory (copied to
     * build/outputs/connected_android_test_additional_output) and to the app's external files.
     */
    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directories = listOfNotNull(
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File),
            context.getExternalFilesDir(null)?.let { File(it, "screenshots") },
        )
        for (dir in directories) {
            runCatching {
                dir.mkdirs()
                File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}

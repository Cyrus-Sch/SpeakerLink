package io.github.cyrus_sch.speakerlink

import android.app.Application
import android.os.StrictMode
import io.github.cyrus_sch.speakerlink.audio.AudioOutputMonitor
import io.github.cyrus_sch.speakerlink.audio.TestTonePlayer
import io.github.cyrus_sch.speakerlink.bluetooth.SpeakerBluetooth
import io.github.cyrus_sch.speakerlink.data.SpeakerPrefs
import io.github.cyrus_sch.speakerlink.status.SpeakerStatusReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SpeakerLinkApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Make deliberate non-SDK Bluetooth calls visible in logcat during development.
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        }
        container = AppContainer(this)
    }
}

/** Process-wide singletons. The app is small enough not to need a DI framework. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val prefs = SpeakerPrefs(app)
    val bluetooth = SpeakerBluetooth(app, scope)
    val audioOutputs = AudioOutputMonitor(app)
    val tonePlayer = TestTonePlayer(app)
    val statusReader = SpeakerStatusReader(app, scope)
}

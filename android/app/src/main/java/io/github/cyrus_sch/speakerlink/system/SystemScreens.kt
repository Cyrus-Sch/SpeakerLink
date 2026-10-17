package io.github.cyrus_sch.speakerlink.system

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.MediaRouter2
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi

/** Ways out to system screens for the things an app is not allowed to do itself. */
object SystemScreens {
    private const val TAG = "SystemScreens"

    /** AOSP/Pixel Settings action for LE Audio "Audio sharing" (Android 16+). Not public SDK. */
    private const val ACTION_AUDIO_SHARING = "com.android.settings.BLUETOOTH_AUDIO_SHARING_SETTINGS"

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val ACTION_SYSTEMUI_MEDIA_OUTPUT = "com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG"
    private const val EXTRA_SYSTEMUI_PACKAGE_NAME = "package_name"
    private const val ACTION_SETTINGS_MEDIA_OUTPUT = "com.android.settings.panel.action.MEDIA_OUTPUT"
    private const val EXTRA_SETTINGS_PACKAGE_NAME = "com.android.settings.panel.extra.PACKAGE_NAME"

    fun openBluetoothSettings(context: Context): Boolean = start(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    /**
     * Opens the system "media output" switcher where the user picks the device that plays (and,
     * on Samsung phones, can turn on Dual Audio). Same fallback chain as AndroidX MediaRouter's
     * SystemOutputSwitcherDialogController, ending at the Bluetooth settings.
     */
    fun openOutputSwitcher(context: Context): Boolean {
        val shown = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> systemUiOutputDialog(context) || settingsMediaOutputPanel(context)
            Build.VERSION.SDK_INT == Build.VERSION_CODES.R -> settingsMediaOutputPanel(context)
            else -> false
        }
        return shown || openBluetoothSettings(context)
    }

    /** Android 12-13: SystemUI's (non-public) output switcher dialog. */
    private fun systemUiOutputDialog(context: Context): Boolean {
        val intent = Intent(ACTION_SYSTEMUI_MEDIA_OUTPUT)
            .setPackage(SYSTEM_UI_PACKAGE)
            .putExtra(EXTRA_SYSTEMUI_PACKAGE_NAME, context.packageName)
        val receiver = context.packageManager.queryBroadcastReceivers(intent, 0)
            .firstOrNull { it.activityInfo?.applicationInfo?.isSystemApp() == true } ?: return false
        return runCatching { context.sendBroadcast(intent.setPackage(receiver.activityInfo.packageName)) }.isSuccess
    }

    /** Android 11: the Settings media output panel (non-public). */
    private fun settingsMediaOutputPanel(context: Context): Boolean {
        val intent = Intent(ACTION_SETTINGS_MEDIA_OUTPUT).putExtra(EXTRA_SETTINGS_PACKAGE_NAME, context.packageName)
        val activity = context.packageManager.queryIntentActivities(intent, 0)
            .firstOrNull { it.activityInfo?.applicationInfo?.isSystemApp() == true } ?: return false
        return start(context, intent.setPackage(activity.activityInfo.packageName))
    }

    private fun ApplicationInfo.isSystemApp(): Boolean =
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    fun openAudioSharing(context: Context): Boolean =
        start(context, Intent(ACTION_AUDIO_SHARING)) || openBluetoothSettings(context)

    fun openAppSettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )

    fun openDeveloperOptions(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) ||
            start(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))

    fun openLocationSettings(context: Context): Boolean = start(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    fun openUrl(context: Context, url: String): Boolean = start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.i(TAG, "No activity for ${intent.action}")
        false
    } catch (e: SecurityException) {
        Log.i(TAG, "Not allowed to open ${intent.action}")
        false
    }
}

/** What this phone offers for playing on several Bluetooth audio devices (Together and Info tabs). */
data class DeviceCapabilities(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val leAudio: Boolean?,
    val leBroadcastSource: Boolean?,
    val leBroadcastAssistant: Boolean?,
    val outputSwitcher: Boolean,
) {
    val isSamsung: Boolean get() = manufacturer.equals("samsung", ignoreCase = true)

    /** Android "Audio sharing" needs Android 16 and both LE broadcast roles. */
    val audioSharing: Boolean
        get() = sdkInt >= Build.VERSION_CODES.BAKLAVA && leBroadcastSource == true && leBroadcastAssistant == true

    companion object {
        fun read(context: Context): DeviceCapabilities {
            val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
            val le = if (adapter != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                LeAudioSupport.read(adapter)
            } else {
                LeAudioSupport.UNKNOWN
            }
            return DeviceCapabilities(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
                leAudio = le.unicast,
                leBroadcastSource = le.broadcastSource,
                leBroadcastAssistant = le.broadcastAssistant,
                outputSwitcher = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            )
        }
    }
}

private data class LeAudioSupport(val unicast: Boolean?, val broadcastSource: Boolean?, val broadcastAssistant: Boolean?) {
    companion object {
        val UNKNOWN = LeAudioSupport(null, null, null)

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @SuppressLint("MissingPermission") // these feature queries need no permission
        fun read(adapter: BluetoothAdapter): LeAudioSupport {
            // Anything but a definite answer (e.g. ERROR_BLUETOOTH_NOT_ENABLED) means "unknown".
            fun supported(query: () -> Int): Boolean? = when (runCatching(query).getOrNull()) {
                BluetoothStatusCodes.FEATURE_SUPPORTED -> true
                BluetoothStatusCodes.FEATURE_NOT_SUPPORTED -> false
                else -> null
            }
            return LeAudioSupport(
                unicast = supported { adapter.isLeAudioSupported() },
                broadcastSource = supported { adapter.isLeAudioBroadcastSourceSupported() },
                broadcastAssistant = supported { adapter.isLeAudioBroadcastAssistantSupported() },
            )
        }
    }
}

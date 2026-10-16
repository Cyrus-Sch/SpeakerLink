package io.github.cyrus_sch.speakerlink.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Result of calling a method that is not part of the public Android SDK. */
sealed interface HiddenCall<out T> {
    data class Done<T>(val value: T) : HiddenCall<T>

    /** The method does not exist or is blocked on this device. */
    data object Unavailable : HiddenCall<Nothing>

    data class Failed(val error: Throwable) : HiddenCall<Nothing>
}

/**
 * The public SDK cannot connect an A2DP speaker or choose which connected speaker plays. These
 * methods exist in every Android version from 12 to 17, are on the non-SDK "unsupported" list
 * (reflection allowed for any targetSdk) and are only guarded by BLUETOOTH_CONNECT. Every call is
 * wrapped so a future block simply makes the app fall back to the system Bluetooth screens.
 */
@SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi", "PrivateApi")
object HiddenBluetooth {
    private const val TAG = "HiddenBluetooth"

    private val a2dpConnect by lazy { find(BluetoothA2dp::class.java, "connect", BluetoothDevice::class.java) }
    private val a2dpDisconnect by lazy { find(BluetoothA2dp::class.java, "disconnect", BluetoothDevice::class.java) }
    private val a2dpSetActive by lazy { find(BluetoothA2dp::class.java, "setActiveDevice", BluetoothDevice::class.java) }
    private val a2dpGetActive by lazy { find(BluetoothA2dp::class.java, "getActiveDevice") }
    private val maxAudioDevices by lazy { find(BluetoothAdapter::class.java, "getMaxConnectedAudioDevices") }

    val canConnect: Boolean get() = a2dpConnect != null
    val canSwitchActive: Boolean get() = a2dpSetActive != null

    fun connect(a2dp: BluetoothA2dp, device: BluetoothDevice): HiddenCall<Boolean> = invoke(a2dpConnect, a2dp, device)

    fun disconnect(a2dp: BluetoothA2dp, device: BluetoothDevice): HiddenCall<Boolean> = invoke(a2dpDisconnect, a2dp, device)

    fun setActiveDevice(a2dp: BluetoothA2dp, device: BluetoothDevice): HiddenCall<Boolean> =
        invoke(a2dpSetActive, a2dp, device)

    fun activeDevice(a2dp: BluetoothA2dp): BluetoothDevice? =
        (invoke<BluetoothDevice?>(a2dpGetActive, a2dp) as? HiddenCall.Done)?.value

    fun maxConnectedAudioDevices(adapter: BluetoothAdapter): Int? =
        (invoke<Int>(maxAudioDevices, adapter) as? HiddenCall.Done)?.value?.takeIf { it > 0 }

    private fun find(owner: Class<*>, name: String, vararg parameters: Class<*>): Method? =
        try {
            owner.getMethod(name, *parameters)
        } catch (e: ReflectiveOperationException) {
            Log.i(TAG, "$name not available: $e")
            null
        } catch (e: SecurityException) {
            Log.i(TAG, "$name blocked: $e")
            null
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invoke(method: Method?, target: Any, vararg args: Any?): HiddenCall<T> {
        if (method == null) return HiddenCall.Unavailable
        return try {
            HiddenCall.Done(method.invoke(target, *args) as T)
        } catch (e: InvocationTargetException) {
            Log.w(TAG, "${method.name} failed", e.targetException)
            HiddenCall.Failed(e.targetException ?: e)
        } catch (e: ReflectiveOperationException) {
            HiddenCall.Unavailable
        } catch (e: RuntimeException) {
            // ClassCastException, IllegalArgumentException, SecurityException...
            Log.w(TAG, "${method.name} failed", e)
            HiddenCall.Failed(e)
        }
    }
}

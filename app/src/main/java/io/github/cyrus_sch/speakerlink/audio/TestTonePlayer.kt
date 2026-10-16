package io.github.cyrus_sch.speakerlink.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Plays a short three-note chime. Played through the active speaker, it is the quickest way to
 * check that every speaker in a PartyBoost/Auracast group is actually playing.
 */
class TestTonePlayer(context: Context) {
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val pcm: ShortArray by lazy { chime() }

    /**
     * Plays the chime on the default media output, or on each of [devices] at the same time
     * (only possible when the system exposes several Bluetooth outputs, e.g. Samsung Dual Audio).
     */
    fun play(devices: List<AudioDeviceInfo> = emptyList()) {
        val tracks = (devices.ifEmpty { listOf(null) }).mapNotNull { device -> createTrack(device) }
            .filter { track ->
                runCatching { track.play() }.onFailure { track.release() }.isSuccess
            }
        if (tracks.isEmpty()) return
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        audioManager?.requestAudioFocus(focus)
        handler.postDelayed({
            tracks.forEach { track -> runCatching { track.stop() }; track.release() }
            audioManager?.abandonAudioFocusRequest(focus)
        }, durationMillis + 250)
    }

    private fun createTrack(device: AudioDeviceInfo?): AudioTrack? = try {
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        if (track.write(pcm, 0, pcm.size) != pcm.size) {
            track.release()
            return null
        }
        if (device != null) track.setPreferredDevice(device)
        track
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not create test tone track", e)
        null
    }

    private fun chime(): ShortArray {
        val notes = doubleArrayOf(523.25, 659.25, 783.99) // C5, E5, G5
        val noteSamples = SAMPLE_RATE * NOTE_MILLIS / 1000
        val total = noteSamples * notes.size + SAMPLE_RATE * TAIL_MILLIS / 1000
        val out = ShortArray(total)
        notes.forEachIndexed { index, frequency ->
            val start = index * noteSamples
            for (i in 0 until total - start) {
                val t = i.toDouble() / SAMPLE_RATE
                val attack = min(1.0, i / (SAMPLE_RATE * 0.008))
                val envelope = attack * exp(-t * 5.0)
                val sample = 0.28 * envelope * (sin(2 * PI * frequency * t) + 0.25 * sin(4 * PI * frequency * t))
                val mixed = out[start + i] + (sample * Short.MAX_VALUE).toInt()
                out[start + i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return out
    }

    private val durationMillis: Long get() = (NOTE_MILLIS * 3 + TAIL_MILLIS).toLong()

    private companion object {
        const val TAG = "TestTonePlayer"
        const val SAMPLE_RATE = 44_100
        const val NOTE_MILLIS = 180
        const val TAIL_MILLIS = 700
    }
}

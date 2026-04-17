package social.waddle.android.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import social.waddle.android.util.WaddleLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes call audio across the speaker / earpiece / wired headset / Bluetooth
 * headset. Encapsulates the API differences between pre-API-31
 * (`setSpeakerphoneOn` + SCO control) and API 31+ (`setCommunicationDevice`).
 *
 * Also owns the proximity wake-lock: during an audio-only call the phone's
 * earpiece is the active output, so we turn the screen off when the user
 * brings the device to their ear (same behaviour as the system dialer).
 */
@Singleton
class CallAudioRouter
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private var proximityLock: PowerManager.WakeLock? = null

        /** Call this when a call starts so we enter MODE_IN_COMMUNICATION. */
        fun onCallStart(audioOnly: Boolean) {
            context.getSystemService<AudioManager>()?.apply {
                mode = AudioManager.MODE_IN_COMMUNICATION
            }
            if (audioOnly) acquireProximityLock()
        }

        /** Call this when the call ends so we release all held audio routes. */
        fun onCallEnd() {
            releaseProximityLock()
            context.getSystemService<AudioManager>()?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    isSpeakerphoneOn = false
                }
                mode = AudioManager.MODE_NORMAL
            }
        }

        /**
         * Pick the first available output device in a preference-ordered list:
         *  speaker (when requested), bluetooth headset, wired headset, earpiece.
         * Mirrors what the Pixel dialer does on API 31+.
         */
        fun setSpeakerphone(enabled: Boolean) {
            val audio = context.getSystemService<AudioManager>() ?: return
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                routeOnR(audio, enabled)
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = enabled
            }
        }

        @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
        private fun routeOnR(
            audio: AudioManager,
            speaker: Boolean,
        ) {
            val preferred =
                if (speaker) {
                    listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                } else {
                    listOf(
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    )
                }
            val device =
                preferred.firstNotNullOfOrNull { type ->
                    audio.availableCommunicationDevices.firstOrNull { it.type == type }
                }
            if (device != null) {
                audio.setCommunicationDevice(device)
            } else {
                WaddleLog.info("No communication device matched preference=speaker=$speaker.")
                audio.clearCommunicationDevice()
            }
        }

        private fun acquireProximityLock() {
            val powerManager = context.getSystemService<PowerManager>() ?: return
            if (!powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            val lock =
                powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "waddle:call-proximity",
                )
            runCatching { lock.acquire(TEN_MINUTES_MS) }
            proximityLock = lock
        }

        private fun releaseProximityLock() {
            val lock = proximityLock ?: return
            runCatching { if (lock.isHeld) lock.release() }
            proximityLock = null
        }

        private companion object {
            const val TEN_MINUTES_MS = 10L * 60L * 1000L
        }
    }

package social.waddle.android.call

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import social.waddle.android.util.WaddleLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays the system ringtone and vibrates the device while an incoming call
 * is ringing. Started by [WaddleCallService] when [CallState.Incoming] is
 * observed and stopped on any other transition.
 *
 * The web client has no equivalent — it relies on browser notification
 * sounds. On Android we go the extra mile so locked-screen calls behave
 * like a proper telephony app.
 */
@Singleton
class Ringer
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private var ringtone: Ringtone? = null
        private var vibrator: Vibrator? = null

        fun start() {
            if (ringtone?.isPlaying == true) return
            playRingtone()
            vibrate()
        }

        fun stop() {
            runCatching { ringtone?.stop() }
            ringtone = null
            runCatching { vibrator?.cancel() }
            vibrator = null
        }

        private fun playRingtone() {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            val tone =
                RingtoneManager.getRingtone(context, uri) ?: run {
                    WaddleLog.info("No default ringtone available; skipping playback.")
                    return
                }
            tone.audioAttributes =
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tone.isLooping = true
            tone.play()
            ringtone = tone
        }

        private fun vibrate() {
            val vib = resolveVibrator() ?: return
            if (!vib.hasVibrator()) return
            // Pattern: 0 wait, 1s buzz, 2s pause, repeat from index 0.
            val pattern = longArrayOf(0, RING_ON_MS, RING_OFF_MS)
            val effect = VibrationEffect.createWaveform(pattern, REPEAT_FROM_INDEX)
            vib.vibrate(effect)
            vibrator = vib
        }

        private fun resolveVibrator(): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService<VibratorManager>()?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService<Vibrator>()
            }

        private companion object {
            const val RING_ON_MS = 1_000L
            const val RING_OFF_MS = 2_000L
            const val REPEAT_FROM_INDEX = 0
        }
    }

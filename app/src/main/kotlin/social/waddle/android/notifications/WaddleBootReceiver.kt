package social.waddle.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import social.waddle.android.util.WaddleLog
import java.io.File

/**
 * Re-starts the foreground messaging service after the device boots so the user
 * stays reachable without having to open the app first. Only fires if an
 * encrypted session blob exists on disk — no point starting a service that has
 * nobody to sign in as. Note: on Android 15+ the `camera` and `phoneCall`
 * foreground-service types cannot be started from BOOT_COMPLETED, but our
 * service uses the `dataSync` type which is still permitted.
 */
class WaddleBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val sessionFile = File(context.noBackupFilesDir, "secure/session.json.enc")
        if (!sessionFile.exists()) {
            WaddleLog.info("BOOT_COMPLETED: no stored session, skipping service start.")
            return
        }
        WaddleLog.info("BOOT_COMPLETED: restarting WaddleMessagingService for stored session.")
        runCatching { WaddleMessagingService.start(context) }
            .onFailure { throwable ->
                WaddleLog.error("BOOT_COMPLETED: failed to start WaddleMessagingService.", throwable)
            }
        // Even if the foreground service fails (e.g. OEM restrictions), the
        // periodic catch-up worker still runs every ~15 min and catches missed
        // messages.
        runCatching { CatchUpScheduler.enqueue(context) }
            .onFailure { throwable ->
                WaddleLog.error("BOOT_COMPLETED: failed to enqueue catch-up work.", throwable)
            }
    }
}

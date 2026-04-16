package social.waddle.android.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService
import androidx.core.net.toUri

object BatteryOptimizations {
    /**
     * Returns true if the app is already whitelisted from battery optimizations.
     * Whitelisted apps keep their foreground services alive reliably even under
     * aggressive OEM power management (Samsung/Xiaomi/Oppo/etc).
     */
    fun isIgnoring(context: Context): Boolean {
        val power = context.getSystemService<PowerManager>() ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Intent that takes the user straight to the system dialog for whitelisting
     * this app. Must be used with a foreground Activity (not a Service).
     *
     * Uses `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with `package:<id>` Uri,
     * which is the only approach that triggers the native "Allow" dialog. The
     * plain `IGNORE_BATTERY_OPTIMIZATION_SETTINGS` action opens the settings
     * list instead and requires the user to find the app manually.
     */
    @SuppressLint("BatteryLife")
    fun requestExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

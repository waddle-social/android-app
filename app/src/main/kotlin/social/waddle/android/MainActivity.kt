package social.waddle.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import social.waddle.android.notifications.DeepLink
import social.waddle.android.notifications.PendingDeepLink
import social.waddle.android.notifications.WaddleNotificationPoster
import social.waddle.android.ui.WaddleApp
import social.waddle.android.ui.lock.AppLockViewModel
import social.waddle.android.ui.lock.BiometricGate
import social.waddle.android.ui.theme.WaddleTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var pendingDeepLink: PendingDeepLink

    private val appLockViewModel: AppLockViewModel by viewModels()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* we can post even when denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        consumeDeepLinkFrom(intent)
        setContent {
            WaddleTheme(darkTheme = isSystemInDarkTheme()) {
                BiometricGate {
                    WaddleApp(pendingDeepLink = pendingDeepLink)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-arm the lock whenever the Activity is backgrounded so tapping the
        // app again re-prompts. Uses `isFinishing` to skip the explicit-exit case.
        if (!isFinishing && !isChangingConfigurations) {
            appLockViewModel.onMovedToBackground()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeDeepLinkFrom(intent)
    }

    private fun consumeDeepLinkFrom(intent: Intent?) {
        intent ?: return
        val kind = intent.getStringExtra(WaddleNotificationPoster.EXTRA_CONVERSATION_KIND) ?: return
        val id = intent.getStringExtra(WaddleNotificationPoster.EXTRA_CONVERSATION_ID) ?: return
        val link =
            when (kind) {
                WaddleNotificationPoster.CONV_KIND_DIRECT -> DeepLink.OpenDirectMessage(id)
                WaddleNotificationPoster.CONV_KIND_ROOM -> DeepLink.OpenRoom(id)
                else -> return
            }
        pendingDeepLink.push(link)
        // Strip so rotation doesn't re-trigger.
        intent.removeExtra(WaddleNotificationPoster.EXTRA_CONVERSATION_KIND)
        intent.removeExtra(WaddleNotificationPoster.EXTRA_CONVERSATION_ID)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

package social.waddle.android.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.waddle.android.util.WaddleLog

/**
 * Renders a blocking overlay when the app-lock is active. The overlay owns the
 * [BiometricPrompt] and calls [AppLockViewModel.onUnlocked] on success. If the
 * device has no enrolled biometrics we fall back to a pass-through warning so
 * the user can either opt out or enrol.
 */
@Composable
fun BiometricGate(
    viewModel: AppLockViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        content()
        if (locked) {
            LockOverlay(onAuthenticate = viewModel::onUnlocked)
        }
    }
}

@Composable
private fun LockOverlay(onAuthenticate: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    LaunchedEffect(Unit) {
        if (activity == null) {
            WaddleLog.error("Biometric gate hosted outside a FragmentActivity — skipping prompt.")
            onAuthenticate()
            return@LaunchedEffect
        }
        val manager = BiometricManager.from(context)
        val can =
            manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            WaddleLog.info("Biometric unavailable ($can); releasing gate.")
            onAuthenticate()
            return@LaunchedEffect
        }
        val executor = ContextCompat.getMainExecutor(context)
        val prompt =
            BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onAuthenticate()
                    }
                },
            )
        val info =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle("Unlock Waddle")
                .setSubtitle("Confirm it's you to read your messages.")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                ).build()
        prompt.authenticate(info)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Fingerprint,
                contentDescription = null,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Text(
                "Waddle is locked",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Authenticate to continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onAuthenticate,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text("Try again")
            }
        }
    }
}

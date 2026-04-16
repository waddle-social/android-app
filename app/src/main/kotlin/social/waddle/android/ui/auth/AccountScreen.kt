package social.waddle.android.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.waddle.android.data.model.AuthSession
import social.waddle.android.data.model.FeatureStatus
import social.waddle.android.notifications.BatteryOptimizations
import social.waddle.android.xmpp.XmppFeatureRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    session: AuthSession,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    biometricLockEnabled: Boolean = false,
    onBiometricLockChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var batteryWhitelisted by remember { mutableStateOf(BatteryOptimizations.isIgnoring(context)) }
    LaunchedEffect(Unit) {
        // Re-check when returning from Settings — isIgnoring is cheap and
        // recompositions re-run this effect keyed on Unit only once, but we
        // want a live value, so remember it and refresh on resume.
        batteryWhitelisted = BatteryOptimizations.isIgnoring(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                        Text("Log out")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                ) {
                    Text(session.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(session.jid, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(session.environment.displayName, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!batteryWhitelisted) {
                item {
                    BatteryOptimizationCard(
                        onRequest = {
                            context.startActivity(BatteryOptimizations.requestExemptionIntent(context))
                        },
                    )
                }
            }
            item {
                SecurityRow(
                    biometricLockEnabled = biometricLockEnabled,
                    onBiometricLockChange = onBiometricLockChange,
                )
            }
            item {
                Text(
                    text = "XMPP",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(XmppFeatureRegistry.supported) { feature ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = "${feature.xep} ${feature.name}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        Row {
                            AssistChip(
                                onClick = {},
                                label = { Text(feature.status.label()) },
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BatteryOptimizationCard(onRequest: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.BatteryFull, contentDescription = null)
                Text(
                    "Keep Waddle running reliably",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Exempt Waddle from battery optimization so it can keep the chat " +
                    "connection alive and deliver messages without delay.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRequest) {
                Text("Allow always-on connection")
            }
        }
    }
}

@Composable
private fun SecurityRow(
    biometricLockEnabled: Boolean,
    onBiometricLockChange: (Boolean) -> Unit,
) {
    ListItem(
        leadingContent = { Icon(Icons.Rounded.Fingerprint, contentDescription = null) },
        headlineContent = { Text("Biometric app lock") },
        supportingContent = { Text("Require fingerprint or face unlock to open Waddle.") },
        trailingContent = {
            Switch(checked = biometricLockEnabled, onCheckedChange = onBiometricLockChange)
        },
    )
}

private fun FeatureStatus.label(): String =
    when (this) {
        FeatureStatus.Active -> "active"
        FeatureStatus.AdapterReady -> "ready"
        FeatureStatus.Deferred -> "deferred"
    }

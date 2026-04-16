package social.waddle.android.ui.auth

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import social.waddle.android.auth.AuthUiState
import social.waddle.android.auth.OAuthConfig
import social.waddle.android.data.model.WaddleEnvironment
import social.waddle.android.ui.theme.LocalWaddleColors

@Composable
fun SignInScreen(
    state: AuthUiState,
    onEnvironmentSelected: (WaddleEnvironment) -> Unit,
    onProviderSelected: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    val selectedProvider = state.providers.firstOrNull { it.id == state.selectedProviderId }
    Scaffold { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandMark()
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Welcome to Waddle",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Your XMPP-native team chat.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(32.dp))
                SignInCard(
                    state = state,
                    onEnvironmentSelected = onEnvironmentSelected,
                    onProviderSelected = onProviderSelected,
                )
                Spacer(Modifier.height(20.dp))
                SignInButton(
                    state = state,
                    selectedProviderName = selectedProvider?.displayName,
                    onSignIn = onSignIn,
                )
            }
        }
    }
}

@Composable
private fun BrandMark() {
    val colors = LocalWaddleColors.current
    Surface(
        color = colors.sidebar,
        contentColor = colors.sidebarContent,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.size(64.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "W",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SignInCard(
    state: AuthUiState,
    onEnvironmentSelected: (WaddleEnvironment) -> Unit,
    onProviderSelected: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServerSelector(state = state, onEnvironmentSelected = onEnvironmentSelected)
            ProviderSelector(state = state, onProviderSelected = onProviderSelected)
        }
    }
}

@Composable
private fun ServerSelector(
    state: AuthUiState,
    onEnvironmentSelected: (WaddleEnvironment) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Server")
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WaddleEnvironment.entries.forEach { environment ->
                FilterChip(
                    selected = state.environment == environment,
                    onClick = { onEnvironmentSelected(environment) },
                    label = { Text(environment.displayName) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                )
            }
        }
        Text(
            text = OAuthConfig.wellKnownUri(state.environment).toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderSelector(
    state: AuthUiState,
    onProviderSelected: (String) -> Unit,
) {
    if (!state.providersLoading && state.providers.isEmpty()) {
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Provider")
        if (state.providersLoading) {
            CircularProgressIndicator(strokeWidth = 2.dp)
            return
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.providers.forEach { provider ->
                FilterChip(
                    selected = provider.id == state.selectedProviderId,
                    onClick = { onProviderSelected(provider.id) },
                    label = { Text(provider.displayName) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SignInButton(
    state: AuthUiState,
    selectedProviderName: String?,
    onSignIn: () -> Unit,
) {
    Button(
        onClick = onSignIn,
        enabled = !state.loading && !state.providersLoading,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
    ) {
        if (state.loading) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 12.dp).size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Login,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(
            text = "Continue with ${selectedProviderName ?: "OAuth"}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

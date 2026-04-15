package social.waddle.android.ui.auth

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import social.waddle.android.auth.AuthUiState
import social.waddle.android.auth.OAuthConfig
import social.waddle.android.data.model.WaddleEnvironment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    state: AuthUiState,
    onEnvironmentSelected: (WaddleEnvironment) -> Unit,
    onProviderSelected: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    val selectedProvider = state.providers.firstOrNull { it.id == state.selectedProviderId }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Waddle") })
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "Sign in",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Use your Waddle OAuth session for XMPP.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(28.dp))
            ServerSelector(state = state, onEnvironmentSelected = onEnvironmentSelected)
            ProviderSelector(state = state, onProviderSelected = onProviderSelected)
            Spacer(Modifier.height(28.dp))
            SignInButton(state = state, selectedProviderName = selectedProvider?.displayName, onSignIn = onSignIn)
        }
    }
}

@Composable
private fun ServerSelector(
    state: AuthUiState,
    onEnvironmentSelected: (WaddleEnvironment) -> Unit,
) {
    Text(
        text = "Server",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))
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
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = OAuthConfig.wellKnownUri(state.environment).toString(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProviderSelector(
    state: AuthUiState,
    onProviderSelected: (String) -> Unit,
) {
    if (!state.providersLoading && state.providers.isEmpty()) {
        return
    }

    Spacer(Modifier.height(24.dp))
    Text(
        text = "Provider",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))
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
            )
        }
    }
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
    ) {
        if (state.loading) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 12.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Login,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text("Continue with ${selectedProviderName ?: "OAuth"}")
    }
}

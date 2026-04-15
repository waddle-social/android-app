package social.waddle.android.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object SignIn : Route

    @Serializable
    data object Chat : Route

    @Serializable
    data object Account : Route
}

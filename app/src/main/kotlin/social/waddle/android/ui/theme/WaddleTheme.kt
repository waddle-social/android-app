package social.waddle.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF611F69),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFF3E9F4),
        onPrimaryContainer = Color(0xFF2C0C30),
        secondary = Color(0xFF1264A3),
        onSecondary = Color.White,
        tertiary = Color(0xFFE01E5A),
        onTertiary = Color.White,
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF1D1C1D),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1D1C1D),
        surfaceVariant = Color(0xFFF4EDE4),
        onSurfaceVariant = Color(0xFF616061),
        outline = Color(0xFFDDDDDD),
        outlineVariant = Color(0xFFEAEAEA),
        error = Color(0xFFE01E5A),
        onError = Color.White,
        errorContainer = Color(0xFFFCE4EC),
        onErrorContainer = Color(0xFF5C0F2A),
    )

private val DarkColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFFE7B1EF),
        onPrimary = Color(0xFF2C0C30),
        primaryContainer = Color(0xFF4A154B),
        onPrimaryContainer = Color(0xFFF3E9F4),
        secondary = Color(0xFF36C5F0),
        onSecondary = Color(0xFF001D2F),
        tertiary = Color(0xFFFF8EB0),
        onTertiary = Color(0xFF5C0F2A),
        background = Color(0xFF1A1D21),
        onBackground = Color(0xFFE8E8E8),
        surface = Color(0xFF222529),
        onSurface = Color(0xFFE8E8E8),
        surfaceVariant = Color(0xFF2C2F33),
        onSurfaceVariant = Color(0xFFABABAD),
        outline = Color(0xFF3A3D41),
        outlineVariant = Color(0xFF2C2F33),
        error = Color(0xFFFF8EB0),
        onError = Color(0xFF5C0F2A),
        errorContainer = Color(0xFF5C0F2A),
        onErrorContainer = Color(0xFFFCE4EC),
    )

@Immutable
data class WaddleColors(
    val sidebar: Color,
    val sidebarSurface: Color,
    val sidebarContent: Color,
    val sidebarMuted: Color,
    val sidebarSelected: Color,
    val sidebarSelectedContent: Color,
    val mention: Color,
    val onMention: Color,
    val presenceOnline: Color,
    val composerSurface: Color,
    val composerOutline: Color,
    val divider: Color,
)

private val LightExtended: WaddleColors =
    WaddleColors(
        sidebar = Color(0xFF3F0E40),
        sidebarSurface = Color(0xFF350D36),
        sidebarContent = Color(0xFFFFFFFF),
        sidebarMuted = Color(0xFFBCABBC),
        sidebarSelected = Color(0xFF1164A3),
        sidebarSelectedContent = Color(0xFFFFFFFF),
        mention = Color(0xFFFDE9C8),
        onMention = Color(0xFF1D1C1D),
        presenceOnline = Color(0xFF2BAC76),
        composerSurface = Color(0xFFFFFFFF),
        composerOutline = Color(0xFFBDBDBD),
        divider = Color(0xFFEAEAEA),
    )

private val DarkExtended: WaddleColors =
    WaddleColors(
        sidebar = Color(0xFF19171D),
        sidebarSurface = Color(0xFF121016),
        sidebarContent = Color(0xFFE8E8E8),
        sidebarMuted = Color(0xFF8E8B92),
        sidebarSelected = Color(0xFF1164A3),
        sidebarSelectedContent = Color(0xFFFFFFFF),
        mention = Color(0xFF5C4A24),
        onMention = Color(0xFFFDE9C8),
        presenceOnline = Color(0xFF34B27D),
        composerSurface = Color(0xFF222529),
        composerOutline = Color(0xFF3A3D41),
        divider = Color(0xFF2C2F33),
    )

val LocalWaddleColors = staticCompositionLocalOf { LightExtended }

@Composable
fun WaddleTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val extended = if (darkTheme) DarkExtended else LightExtended
    androidx.compose.runtime.CompositionLocalProvider(LocalWaddleColors provides extended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}

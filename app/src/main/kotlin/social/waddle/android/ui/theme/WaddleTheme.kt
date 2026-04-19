package social.waddle.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Teal accents — design palette "Waddle teal".
private val AccentDark = Color(0xFF00C4AB)
private val AccentLight = Color(0xFF00A890)

private val LightColors: ColorScheme =
    lightColorScheme(
        primary = AccentLight,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCFF0E9),
        onPrimaryContainer = Color(0xFF003A33),
        secondary = Color(0xFF4A5568),
        onSecondary = Color.White,
        tertiary = AccentLight,
        onTertiary = Color.White,
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF14161C),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF14161C),
        surfaceVariant = Color(0xFFF5F6F8),
        onSurfaceVariant = Color(0xFF6A6F7C),
        outline = Color(0xFFE2E4E9),
        outlineVariant = Color(0xFFEEF0F3),
        error = Color(0xFFEF4444),
        onError = Color.White,
        errorContainer = Color(0xFFFCE4EC),
        onErrorContainer = Color(0xFF5C0F2A),
    )

private val DarkColors: ColorScheme =
    darkColorScheme(
        primary = AccentDark,
        onPrimary = Color(0xFF00241F),
        primaryContainer = Color(0xFF00443B),
        onPrimaryContainer = Color(0xFFB8F3E7),
        secondary = Color(0xFFB0B4BF),
        onSecondary = Color(0xFF14161C),
        tertiary = AccentDark,
        onTertiary = Color(0xFF00241F),
        background = Color(0xFF0F1115),
        onBackground = Color(0xFFE6E8ED),
        surface = Color(0xFF0F1115),
        onSurface = Color(0xFFE6E8ED),
        surfaceVariant = Color(0xFF181B21),
        onSurfaceVariant = Color(0xFF8A8F9C),
        outline = Color(0xFF262A33),
        outlineVariant = Color(0xFF1F2229),
        error = Color(0xFFEF4444),
        onError = Color.White,
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
    val accentSoft: Color,
    val unreadBadge: Color,
    val fgMute: Color,
)

private val LightExtended: WaddleColors =
    WaddleColors(
        sidebar = Color(0xFFFFFFFF),
        sidebarSurface = Color(0xFFF5F6F8),
        sidebarContent = Color(0xFF14161C),
        sidebarMuted = Color(0xFF6A6F7C),
        sidebarSelected = Color(0x1A00A890),
        sidebarSelectedContent = AccentLight,
        mention = Color(0x1A00A890),
        onMention = Color(0xFF14161C),
        presenceOnline = Color(0xFF10B981),
        composerSurface = Color(0xFFF5F6F8),
        composerOutline = Color(0xFFE2E4E9),
        divider = Color(0xFFEEF0F3),
        accentSoft = Color(0x1A00A890),
        unreadBadge = Color(0xFFEF4444),
        fgMute = Color(0xFFA0A5B2),
    )

private val DarkExtended: WaddleColors =
    WaddleColors(
        sidebar = Color(0xFF0F1115),
        sidebarSurface = Color(0xFF181B21),
        sidebarContent = Color(0xFFE6E8ED),
        sidebarMuted = Color(0xFF8A8F9C),
        sidebarSelected = Color(0x2400C4AB),
        sidebarSelectedContent = AccentDark,
        mention = Color(0x2400C4AB),
        onMention = Color(0xFFE6E8ED),
        presenceOnline = Color(0xFF10B981),
        composerSurface = Color(0xFF181B21),
        composerOutline = Color(0xFF262A33),
        divider = Color(0xFF1F2229),
        accentSoft = Color(0x2400C4AB),
        unreadBadge = Color(0xFFEF4444),
        fgMute = Color(0xFF52576A),
    )

val LocalWaddleColors = staticCompositionLocalOf { LightExtended }

@Composable
fun WaddleTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkColors else LightColors
    val extended = if (darkTheme) DarkExtended else LightExtended
    CompositionLocalProvider(LocalWaddleColors provides extended) {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}

package social.waddle.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF0B6E4F),
        onPrimary = Color.White,
        secondary = Color(0xFF5B5F97),
        tertiary = Color(0xFFB5446E),
        background = Color(0xFFFAFBF7),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE2E7DE),
    )

private val DarkColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF67D9AA),
        onPrimary = Color(0xFF003828),
        secondary = Color(0xFFC5C7FF),
        tertiary = Color(0xFFFFB0CB),
        background = Color(0xFF101411),
        surface = Color(0xFF171D19),
        surfaceVariant = Color(0xFF3F4943),
    )

@Composable
fun WaddleTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

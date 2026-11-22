package pe.bovedasegura.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Teal = Color(0xFF0F6E56)
val TealDark = Color(0xFF085041)
val TealLight = Color(0xFFE1F5EE)
val Mint = Color(0xFF5DCAA5)
val Amber = Color(0xFFBA7517)
val AmberLight = Color(0xFFFAEEDA)
val Danger = Color(0xFFA32D2D)
val DangerLight = Color(0xFFFCEBEB)
val Violet = Color(0xFF534AB7)
val VioletLight = Color(0xFFEEEDFE)

private val Light = lightColorScheme(
    primary = Teal, onPrimary = Color.White,
    primaryContainer = TealLight, onPrimaryContainer = TealDark,
    secondary = Violet, secondaryContainer = VioletLight, onSecondaryContainer = Color(0xFF3C3489),
    tertiary = Amber, tertiaryContainer = AmberLight, onTertiaryContainer = Color(0xFF633806),
    error = Danger, errorContainer = DangerLight, onErrorContainer = Color(0xFF791F1F),
    background = Color(0xFFF8F8F6), surface = Color.White, surfaceVariant = Color(0xFFF1EFE8),
)

private val Dark = darkColorScheme(
    primary = Mint, onPrimary = Color(0xFF04342C),
    primaryContainer = TealDark, onPrimaryContainer = Color(0xFF9FE1CB),
    secondary = Color(0xFFAFA9EC), secondaryContainer = Color(0xFF3C3489), onSecondaryContainer = Color(0xFFCECBF6),
    tertiary = Color(0xFFEF9F27), tertiaryContainer = Color(0xFF633806), onTertiaryContainer = Color(0xFFFAC775),
    error = Color(0xFFF09595), errorContainer = Color(0xFF791F1F), onErrorContainer = Color(0xFFF7C1C1),
    background = Color(0xFF121212), surface = Color(0xFF1C1C1C), surfaceVariant = Color(0xFF2C2C2A),
)

@Composable
fun BovedaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

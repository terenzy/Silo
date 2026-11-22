package dev.silo.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val SiloEmerald = Color(0xFF10B981)
val SiloCyan = Color(0xFF06B6D4)
val SiloAmber = Color(0xFFF59E0B)
val SiloCrimson = Color(0xFFEF4444)
val SiloObsidian = Color(0xFF0B1117)
val SiloSurface = Color(0xFF121A23)
val SiloSurfaceVariant = Color(0xFF1B2634)

private val SiloDarkScheme = darkColorScheme(
    primary = SiloEmerald,
    onPrimary = Color(0xFF032318),
    primaryContainer = Color(0xFF064E3B),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = SiloCyan,
    onSecondary = Color(0xFF03222A),
    secondaryContainer = Color(0xFF0E3A47),
    onSecondaryContainer = Color(0xFFA5F3FC),
    tertiary = SiloAmber,
    error = SiloCrimson,
    background = SiloObsidian,
    onBackground = Color(0xFFE6EDF3),
    surface = SiloSurface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = SiloSurfaceVariant,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2B3A4C),
)

private val SiloLightScheme = lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF0891B2),
    tertiary = Color(0xFFD97706),
    error = Color(0xFFDC2626),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE2E8F0),
)

@Composable
fun SiloTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> SiloDarkScheme
        else -> SiloLightScheme
    }

    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}

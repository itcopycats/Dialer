package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Pure AMOLED Pitch Black Theme (#000000)
private val AmoledDarkColorScheme = darkColorScheme(
  primary = FlameOrange,
  onPrimary = Color.Black,
  primaryContainer = Color(0xFF261200),
  onPrimaryContainer = FlameOrangeLight,
  secondary = FlameGold,
  onSecondary = Color.Black,
  secondaryContainer = Color(0xFF261B00),
  onSecondaryContainer = FlameGold,
  tertiary = FlameCrimson,
  onTertiary = Color.White,
  background = AmoledBlack, // 100% True Black for OLED/AMOLED pixels
  onBackground = TextPrimary,
  surface = AmoledSurface,
  onSurface = TextPrimary,
  surfaceVariant = AmoledSurfaceVariant,
  onSurfaceVariant = TextSecondary,
  outline = AmoledBorder
)

// Tactical Light Theme (when system light mode is active)
private val LightColorScheme = lightColorScheme(
  primary = FlameOrange,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFFFE0B2),
  onPrimaryContainer = Color(0xFFBF360C),
  secondary = FlameGold,
  onSecondary = Color.Black,
  secondaryContainer = Color(0xFFFFECB3),
  onSecondaryContainer = Color(0xFFFF6F00),
  tertiary = FlameCrimson,
  onTertiary = Color.White,
  background = BackgroundLight,
  onBackground = TextPrimaryLight,
  surface = SurfaceLight,
  onSurface = TextPrimaryLight,
  surfaceVariant = SurfaceVariantLight,
  onSurfaceVariant = TextSecondaryLight,
  outline = SurfaceBorderLight
)

@Composable
fun MyApplicationTheme(
  themeMode: String = "AUTO", // "AUTO", "AMOLED", "LIGHT"
  useAmoledPureBlack: Boolean = true,
  systemDarkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val isDark = when (themeMode) {
    "AMOLED" -> true
    "LIGHT" -> false
    else -> systemDarkTheme // "AUTO" follows system dark mode automatically
  }

  val colorScheme = when {
    isDark && useAmoledPureBlack -> AmoledDarkColorScheme
    isDark -> AmoledDarkColorScheme.copy(
      background = Color(0xFF0D1117),
      surface = Color(0xFF161B22)
    )
    else -> LightColorScheme
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}

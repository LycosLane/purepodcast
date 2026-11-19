package com.lycoslane.purepodcast.ui.theme

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.lycoslane.purepodcast.R
import com.lycoslane.purepodcast.core.preferences.AppPreferences

enum class PureAccent(
    val id: String,
    @StringRes val labelRes: Int,
    val lightPrimary: Color,
    val darkPrimary: Color,
    val lightSecondary: Color,
    val darkSecondary: Color
) {
    TEAL(
        id = "teal",
        labelRes = R.string.accent_teal,
        lightPrimary = Color(0xFF006874),
        darkPrimary = Color(0xFF4FD8EB),
        lightSecondary = Color(0xFF4A6267),
        darkSecondary = Color(0xFFA6CAD1)
    ),
    INDIGO(
        id = "indigo",
        labelRes = R.string.accent_indigo,
        lightPrimary = Color(0xFF2B59A8),
        darkPrimary = Color(0xFF9BB9FF),
        lightSecondary = Color(0xFF50607B),
        darkSecondary = Color(0xFFB3C3DF)
    ),
    GREEN(
        id = "green",
        labelRes = R.string.accent_green,
        lightPrimary = Color(0xFF256B49),
        darkPrimary = Color(0xFF77D9A2),
        lightSecondary = Color(0xFF4B6556),
        darkSecondary = Color(0xFFA8CBB7)
    ),
    AMBER(
        id = "amber",
        labelRes = R.string.accent_amber,
        lightPrimary = Color(0xFF965114),
        darkPrimary = Color(0xFFFFB973),
        lightSecondary = Color(0xFF735A43),
        darkSecondary = Color(0xFFDFBE9E)
    ),
    CORAL(
        id = "coral",
        labelRes = R.string.accent_coral,
        lightPrimary = Color(0xFFA63648),
        darkPrimary = Color(0xFFFF94A4),
        lightSecondary = Color(0xFF76565B),
        darkSecondary = Color(0xFFDFB4BC)
    ),
    VIOLET(
        id = "violet",
        labelRes = R.string.accent_violet,
        lightPrimary = Color(0xFF6445A6),
        darkPrimary = Color(0xFFCBB2FF),
        lightSecondary = Color(0xFF625878),
        darkSecondary = Color(0xFFC4B8DF)
    );

    companion object {
        fun fromId(id: String): PureAccent = entries.firstOrNull { it.id == id } ?: TEAL
    }
}

private fun buildLightColorScheme(accent: PureAccent): ColorScheme {
    val p = accent.lightPrimary
    val s = accent.lightSecondary
    return lightColorScheme(
        primary = p,
        onPrimary = Color.White,
        primaryContainer = lerp(Color.White, p, 0.16f),
        onPrimaryContainer = lerp(p, Color.Black, 0.45f),
        secondary = s,
        onSecondary = Color.White,
        secondaryContainer = lerp(Color.White, s, 0.16f),
        onSecondaryContainer = lerp(s, Color.Black, 0.50f),
        tertiary = lerp(p, s, 0.50f),
        onTertiary = Color.White,
        background = lerp(Color(0xFFFAFBFB), p, 0.025f),
        onBackground = Color(0xFF161A1C),
        surface = lerp(Color.White, p, 0.012f),
        onSurface = Color(0xFF161A1C),
        surfaceVariant = lerp(Color(0xFFE3E7EA), p, 0.075f),
        onSurfaceVariant = lerp(Color(0xFF454B4F), s, 0.20f),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = lerp(Color(0xFFF6F7F8), p, 0.030f),
        surfaceContainer = lerp(Color(0xFFF0F2F4), p, 0.042f),
        surfaceContainerHigh = lerp(Color(0xFFE9ECEE), p, 0.055f),
        surfaceContainerHighest = lerp(Color(0xFFE1E5E8), p, 0.068f),
        outline = lerp(Color(0xFF747B80), s, 0.20f),
        outlineVariant = lerp(Color(0xFFC5CCD1), s, 0.22f)
    )
}

private fun buildDarkColorScheme(accent: PureAccent): ColorScheme {
    val p = accent.darkPrimary
    val s = accent.darkSecondary
    return darkColorScheme(
        primary = p,
        onPrimary = lerp(accent.lightPrimary, Color.Black, 0.65f),
        primaryContainer = lerp(Color(0xFF121518), p, 0.28f),
        onPrimaryContainer = lerp(p, Color.White, 0.42f),
        secondary = s,
        onSecondary = lerp(accent.lightSecondary, Color.Black, 0.65f),
        secondaryContainer = lerp(Color(0xFF15191C), s, 0.22f),
        onSecondaryContainer = lerp(s, Color.White, 0.45f),
        tertiary = lerp(p, s, 0.50f),
        onTertiary = lerp(accent.lightPrimary, Color.Black, 0.65f),
        background = lerp(Color(0xFF0E1113), p, 0.035f),
        onBackground = Color(0xFFE6EBEE),
        surface = lerp(Color(0xFF121619), p, 0.040f),
        onSurface = Color(0xFFE6EBEE),
        surfaceVariant = lerp(Color(0xFF252C32), p, 0.085f),
        onSurfaceVariant = lerp(Color(0xFFB3BCC2), s, 0.22f),
        surfaceContainerLowest = lerp(Color(0xFF0B0D0F), p, 0.025f),
        surfaceContainerLow = lerp(Color(0xFF171C20), p, 0.050f),
        surfaceContainer = lerp(Color(0xFF1C2227), p, 0.062f),
        surfaceContainerHigh = lerp(Color(0xFF232A30), p, 0.075f),
        surfaceContainerHighest = lerp(Color(0xFF2B333A), p, 0.090f),
        outline = lerp(Color(0xFF7E878E), s, 0.22f),
        outlineVariant = lerp(Color(0xFF394248), s, 0.24f)
    )
}

@Composable
fun isDarkThemeEffective(themeMode: String): Boolean = when (themeMode) {
    AppPreferences.THEME_DARK -> true
    AppPreferences.THEME_LIGHT -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun PurePodcastTheme(
    themeMode: String = AppPreferences.THEME_SYSTEM,
    lightAccentId: String = AppPreferences.DEFAULT_ACCENT,
    darkAccentId: String = AppPreferences.DEFAULT_ACCENT,
    content: @Composable () -> Unit
) {
    val darkTheme = isDarkThemeEffective(themeMode)
    val accent = PureAccent.fromId(if (darkTheme) darkAccentId else lightAccentId)
    val colorScheme = if (darkTheme) buildDarkColorScheme(accent) else buildLightColorScheme(accent)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

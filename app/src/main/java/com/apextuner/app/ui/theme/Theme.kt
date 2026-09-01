package com.apextuner.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ApexTuner brand palette: deep navy surfaces with restrained cyan / violet signal accents.
val ApexCyan = Color(0xFF13E7F4)
val ApexElectricBlue = Color(0xFF168EFF)
val ApexViolet = Color(0xFFA747FF)
val ApexGreen = Color(0xFF3EEE58)
val ApexNight = Color(0xFF010711)
val ApexPanel = Color(0xFF06111D)

private val ApexDarkColors = darkColorScheme(
    primary = ApexCyan,
    onPrimary = Color(0xFF001F23),
    primaryContainer = Color(0xFF063248),
    onPrimaryContainer = Color(0xFFD8FBFF),
    inversePrimary = Color(0xFF006A72),
    secondary = ApexViolet,
    onSecondary = Color(0xFF18002A),
    secondaryContainer = Color(0xFF281341),
    onSecondaryContainer = Color(0xFFF3E3FF),
    tertiary = ApexGreen,
    onTertiary = Color(0xFF002106),
    tertiaryContainer = Color(0xFF0B3515),
    onTertiaryContainer = Color(0xFFD8FFDA),
    background = ApexNight,
    onBackground = Color(0xFFF4F8FC),
    surface = Color(0xFF040D18),
    onSurface = Color(0xFFF4F8FC),
    surfaceVariant = Color(0xFF091827),
    onSurfaceVariant = Color(0xFFAFBBC8),
    surfaceDim = Color(0xFF010711),
    surfaceBright = Color(0xFF122535),
    surfaceContainerLowest = Color(0xFF020A14),
    surfaceContainerLow = Color(0xFF06121F),
    surfaceContainer = Color(0xFF081726),
    surfaceContainerHigh = Color(0xFF0B1C2D),
    surfaceContainerHighest = Color(0xFF102437),
    outline = Color(0xFF486174),
    outlineVariant = Color(0xFF173247),
    error = Color(0xFFFF737D),
    onError = Color(0xFF490008),
    errorContainer = Color(0xFF3D121A),
    onErrorContainer = Color(0xFFFFD9DC),
    surfaceTint = ApexCyan,
    scrim = Color.Black,
    inverseSurface = Color(0xFFE7EEF5),
    inverseOnSurface = Color(0xFF15202A),
)

private val ApexLightColors = lightColorScheme(
    primary = Color(0xFF006A73),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F7FC),
    onPrimaryContainer = Color(0xFF001F23),
    inversePrimary = ApexCyan,
    secondary = Color(0xFF7640A0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2DAFF),
    onSecondaryContainer = Color(0xFF2C0049),
    tertiary = Color(0xFF247A31),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBFF4C1),
    onTertiaryContainer = Color(0xFF002106),
    background = Color(0xFFF4F8FC),
    onBackground = Color(0xFF0A1722),
    surface = Color(0xFFFBFDFF),
    onSurface = Color(0xFF0A1722),
    surfaceVariant = Color(0xFFE5EEF5),
    onSurfaceVariant = Color(0xFF465A6A),
    surfaceDim = Color(0xFFD8E1E8),
    surfaceBright = Color(0xFFFBFDFF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F8FC),
    surfaceContainer = Color(0xFFEEF3F7),
    surfaceContainerHigh = Color(0xFFE8EDF2),
    surfaceContainerHighest = Color(0xFFE2E8EE),
    outline = Color(0xFF667C8D),
    outlineVariant = Color(0xFFB9CDD9),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceTint = Color(0xFF006A73),
    scrim = Color(0x99000000),
    inverseSurface = Color(0xFF22313C),
    inverseOnSurface = Color(0xFFF0F5F8),
)

private val ApexShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(13.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun ApexTunerTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Dynamic color remains supported exactly as before. When it is disabled, the app uses the
    // dedicated ApexTuner palette shown in the product artwork/reference UI.
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> ApexDarkColors
        else -> ApexLightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
    }

    MaterialTheme(
        colorScheme = colors,
        typography = ApexTunerTypography,
        shapes = ApexShapes,
        content = content,
    )
}

package com.apextuner.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Readability-focused visual tokens for data accents and status highlights.
 *
 * Brand accents are adjusted only when needed to maintain readable foreground contrast against
 * the current Material surface. This keeps the same visual identity in dark, light, and dynamic
 * color modes without relying on fixed colors that disappear on bright backgrounds.
 */
data class ApexAccentPalette(
    val cyan: Color,
    val violet: Color,
    val green: Color,
    val blue: Color,
    val warning: Color,
    val critical: Color,
    val muted: Color,
)

private val BrandCyan = Color(0xFF13E7F4)
private val BrandViolet = Color(0xFFA747FF)
private val BrandGreen = Color(0xFF3EEE58)
private val BrandBlue = Color(0xFF168EFF)
private val BrandWarning = Color(0xFFFFC65A)

const val APEX_MIN_TEXT_CONTRAST = 4.5f
private const val APEX_ACCENT_CONTRAST = 4.75f

fun contrastRatio(foreground: Color, background: Color): Float {
    val lighter = maxOf(foreground.luminance(), background.luminance())
    val darker = minOf(foreground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

fun ensureReadableColor(
    foreground: Color,
    background: Color,
    minimumContrast: Float = APEX_MIN_TEXT_CONTRAST,
): Color {
    require(minimumContrast in 1f..21f) { "minimumContrast must be between 1.0 and 21.0" }
    if (contrastRatio(foreground, background) >= minimumContrast) return foreground

    val target = if (background.luminance() >= 0.5f) Color.Black else Color.White
    var low = 0f
    var high = 1f
    repeat(16) {
        val midpoint = (low + high) / 2f
        val candidate = lerp(foreground, target, midpoint)
        if (contrastRatio(candidate, background) >= minimumContrast) {
            high = midpoint
        } else {
            low = midpoint
        }
    }
    return lerp(foreground, target, high)
}

fun accessibleAccentPalette(
    background: Color,
    error: Color,
    muted: Color,
): ApexAccentPalette = ApexAccentPalette(
    cyan = ensureReadableColor(BrandCyan, background, APEX_ACCENT_CONTRAST),
    violet = ensureReadableColor(BrandViolet, background, APEX_ACCENT_CONTRAST),
    green = ensureReadableColor(BrandGreen, background, APEX_ACCENT_CONTRAST),
    blue = ensureReadableColor(BrandBlue, background, APEX_ACCENT_CONTRAST),
    warning = ensureReadableColor(BrandWarning, background, APEX_ACCENT_CONTRAST),
    critical = ensureReadableColor(error, background, APEX_ACCENT_CONTRAST),
    muted = ensureReadableColor(muted, background, APEX_ACCENT_CONTRAST),
)

@Composable
fun apexAccentPalette(): ApexAccentPalette {
    val colors = MaterialTheme.colorScheme
    return accessibleAccentPalette(
        background = colors.surfaceContainerHighest,
        error = colors.error,
        muted = colors.onSurfaceVariant,
    )
}

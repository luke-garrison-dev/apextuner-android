package com.apextuner.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexAccessibilityTest {
    @Test
    fun brightBrandAccentIsAdjustedForLightSurface() {
        val original = Color(0xFF13E7F4)
        val background = Color(0xFFF4F8FC)

        val adjusted = ensureReadableColor(original, background)

        assertTrue(adjusted != original)
        assertTrue(contrastRatio(adjusted, background) >= APEX_MIN_TEXT_CONTRAST)
    }

    @Test
    fun readableBrandAccentIsPreservedOnDarkSurface() {
        val original = Color(0xFF13E7F4)
        val background = Color(0xFF06121F)

        val adjusted = ensureReadableColor(original, background)

        assertEquals(original, adjusted)
    }

    @Test
    fun generatedAccentPaletteMeetsTextContrastAcrossDarkSurfaces() {
        assertPaletteContrast(
            calibrationBackground = Color(0xFF102437),
            surfaces = listOf(
                Color(0xFF040D18),
                Color(0xFF06121F),
                Color(0xFF081726),
                Color(0xFF0B1C2D),
                Color(0xFF102437),
            ),
            error = Color(0xFFFF737D),
            muted = Color(0xFFAFBBC8),
        )
    }

    @Test
    fun generatedAccentPaletteMeetsTextContrastAcrossLightSurfaces() {
        assertPaletteContrast(
            calibrationBackground = Color(0xFFE2E8EE),
            surfaces = listOf(
                Color(0xFFFBFDFF),
                Color(0xFFF4F8FC),
                Color(0xFFEEF3F7),
                Color(0xFFE8EDF2),
                Color(0xFFE2E8EE),
            ),
            error = Color(0xFFBA1A1A),
            muted = Color(0xFF465A6A),
        )
    }

    @Test
    fun generatedAccentPaletteAdaptsToDynamicSurfaceLuminance() {
        val dynamicSurfaces = listOf(
            Color(0xFF2A2830),
            Color(0xFFF2EEF7),
        )

        dynamicSurfaces.forEach { surface ->
            val palette = accessibleAccentPalette(
                background = surface,
                error = Color(0xFFB3261E),
                muted = Color(0xFF625B71),
            )
            listOf(
                palette.cyan,
                palette.violet,
                palette.green,
                palette.blue,
                palette.warning,
                palette.critical,
                palette.muted,
            ).forEach { foreground ->
                assertTrue(contrastRatio(foreground, surface) >= APEX_MIN_TEXT_CONTRAST)
            }
        }
    }

    private fun assertPaletteContrast(
        calibrationBackground: Color,
        surfaces: List<Color>,
        error: Color,
        muted: Color,
    ) {
        val palette = accessibleAccentPalette(calibrationBackground, error, muted)
        val colors = listOf(
            palette.cyan,
            palette.violet,
            palette.green,
            palette.blue,
            palette.warning,
            palette.critical,
            palette.muted,
        )

        colors.forEach { foreground ->
            surfaces.forEach { background ->
                assertTrue(
                    "Expected contrast >= 4.5, was ${contrastRatio(foreground, background)}",
                    contrastRatio(foreground, background) >= APEX_MIN_TEXT_CONTRAST,
                )
            }
        }
    }
}

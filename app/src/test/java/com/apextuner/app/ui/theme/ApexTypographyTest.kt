package com.apextuner.app.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

class ApexTypographyTest {
    @Test
    fun denseUiTextDoesNotDropBelowThirteenSp() {
        assertTrue(ApexTunerTypography.bodySmall.fontSize.value >= 13f)
        assertTrue(ApexTunerTypography.labelMedium.fontSize.value >= 13f)
        assertTrue(ApexTunerTypography.labelSmall.fontSize.value >= 13f)
    }

    @Test
    fun recurringScreenHeadingsStayWithinDenseAppScale() {
        assertTrue(ApexTunerTypography.headlineMedium.fontSize.value <= 24f)
        assertTrue(ApexTunerTypography.headlineSmall.fontSize.value <= 21f)
        assertTrue(ApexTunerTypography.headlineMedium.fontSize.value > ApexTunerTypography.titleLarge.fontSize.value)
    }

    @Test
    fun paragraphLineHeightRemainsComfortable() {
        assertTrue(ApexTunerTypography.bodyMedium.lineHeight.value >= 22f)
        assertTrue(ApexTunerTypography.bodySmall.lineHeight.value >= 19f)
    }
}

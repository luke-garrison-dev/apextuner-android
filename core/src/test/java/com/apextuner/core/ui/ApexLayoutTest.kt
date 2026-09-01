package com.apextuner.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexLayoutTest {
    @Test fun portraitPhonesKeepBottomNavigation() {
        assertEquals(ApexNavigationPresentation.BottomBar, ApexLayout.navigationPresentationFor(360, 800))
        assertEquals(ApexNavigationPresentation.BottomBar, ApexLayout.navigationPresentationFor(430, 932))
    }

    @Test fun landscapePhonesUseCompactRail() {
        assertEquals(ApexNavigationPresentation.CompactRail, ApexLayout.navigationPresentationFor(800, 360))
        assertEquals(ApexNavigationPresentation.CompactRail, ApexLayout.navigationPresentationFor(568, 320))
        assertTrue(ApexLayout.isCompactLandscapeFor(800, 360))
    }

    @Test fun tabletsUseExpandedRailWhenHeightAllows() {
        assertEquals(ApexNavigationPresentation.ExpandedRail, ApexLayout.navigationPresentationFor(800, 1280))
        assertEquals(ApexNavigationPresentation.ExpandedRail, ApexLayout.navigationPresentationFor(1280, 800))
        assertFalse(ApexLayout.isCompactLandscapeFor(1280, 800))
    }

    @Test fun shortWideFreeformWindowsStayCompact() {
        assertEquals(ApexNavigationPresentation.CompactRail, ApexLayout.navigationPresentationFor(720, 520))
        assertEquals(ApexNavigationPresentation.CompactRail, ApexLayout.navigationPresentationFor(600, 500))
    }

    @Test fun bottomNavigationLabelsCollapseBeforeTheyBecomeCramped() {
        assertTrue(ApexLayout.showAllBottomNavigationLabels(400, 1.0f))
        assertFalse(ApexLayout.showAllBottomNavigationLabels(399, 1.0f))
        assertFalse(ApexLayout.showAllBottomNavigationLabels(400, 1.16f))
        assertTrue(ApexLayout.showAllBottomNavigationLabels(720, 1.15f))
    }

    @Test fun largeFontScalingUsesCompactRailAndStackedMetrics() {
        assertFalse(
            ApexLayout.shouldUseCompactNavigationRail(
                ApexNavigationPresentation.ExpandedRail,
                1.30f,
            ),
        )
        assertTrue(
            ApexLayout.shouldUseCompactNavigationRail(
                ApexNavigationPresentation.ExpandedRail,
                1.31f,
            ),
        )
        assertFalse(ApexLayout.shouldStackMetricRow(360, 1.30f))
        assertTrue(ApexLayout.shouldStackMetricRow(360, 1.31f))
        assertTrue(ApexLayout.shouldStackMetricRow(320, 1.0f))
    }
}

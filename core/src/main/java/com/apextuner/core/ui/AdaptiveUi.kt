package com.apextuner.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared responsive primitives for ApexTuner's Compose UI.
 *
 * These helpers intentionally depend on the current app window configuration rather than
 * physical-device assumptions, so split-screen, freeform windows, tablets and foldables can
 * reflow without touching feature/business logic.
 */
enum class ApexNavigationPresentation {
    BottomBar,
    CompactRail,
    ExpandedRail,
}

object ApexLayout {
    val MaxContentWidth: Dp = 1040.dp

    /**
     * Phone landscape needs a different treatment from a tablet even when both windows are wide.
     * A full rail (brand header + five text labels) can exceed the usable height on a phone and
     * make the whole app appear clipped. Keep a compact, icon-only rail in short landscape
     * windows and reserve the expanded rail for windows that have enough vertical space.
     */
    fun navigationPresentationFor(widthDp: Int, heightDp: Int): ApexNavigationPresentation {
        val width = widthDp.coerceAtLeast(0)
        val height = heightDp.coerceAtLeast(0)
        val landscape = width > height
        val canUseRail = width >= 600 || (landscape && width >= 480)
        if (!canUseRail) return ApexNavigationPresentation.BottomBar
        return if (height < 600) ApexNavigationPresentation.CompactRail
        else ApexNavigationPresentation.ExpandedRail
    }

    fun isCompactLandscapeFor(widthDp: Int, heightDp: Int): Boolean =
        widthDp > heightDp && heightDp < 600

    fun showAllBottomNavigationLabels(widthDp: Int, fontScale: Float): Boolean =
        widthDp >= 400 && fontScale <= 1.15f

    fun shouldUseCompactNavigationRail(
        presentation: ApexNavigationPresentation,
        fontScale: Float,
    ): Boolean = presentation == ApexNavigationPresentation.CompactRail ||
        (presentation == ApexNavigationPresentation.ExpandedRail && fontScale > 1.30f)

    fun shouldStackMetricRow(widthDp: Int, fontScale: Float): Boolean =
        widthDp < 340 || fontScale > 1.30f

    fun horizontalPaddingForSize(widthDp: Int, heightDp: Int): Dp {
        if (isCompactLandscapeFor(widthDp, heightDp)) return 14.dp
        return when {
            widthDp < 360 -> 13.dp
            widthDp < 600 -> 16.dp
            widthDp < 840 -> 24.dp
            else -> 32.dp
        }
    }


    @Composable
    fun horizontalPadding(): Dp {
        val configuration = LocalConfiguration.current
        return horizontalPaddingForSize(configuration.screenWidthDp, configuration.screenHeightDp)
    }

    @Composable
    fun isCompactLandscape(): Boolean {
        val configuration = LocalConfiguration.current
        return isCompactLandscapeFor(configuration.screenWidthDp, configuration.screenHeightDp)
    }
}

/**
 * A metric row that remains readable with large font scaling and narrow app windows.
 * It switches to a stacked label/value presentation before the two-column form becomes cramped.
 */
@Composable
fun ApexMetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        if (ApexLayout.shouldStackMetricRow(maxWidth.value.toInt(), fontScale)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(0.95f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    modifier = Modifier.weight(1.05f),
                    textAlign = TextAlign.End,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

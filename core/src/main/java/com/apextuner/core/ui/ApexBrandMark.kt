package com.apextuner.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.apextuner.core.R

/**
 * Canonical ApexTuner brand artwork used throughout the in-app UI.
 *
 * The bitmap lives in drawable-nodpi so Android does not apply density-based resampling; Compose
 * scales it with a high-quality fit at the call site. The adjacent product-name text remains the
 * accessible label, so this mark is intentionally decorative to avoid duplicate TalkBack output.
 */
@Composable
fun ApexBrandMark(
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.apextuner_logo),
        contentDescription = null,
        modifier = modifier.aspectRatio(1f),
        contentScale = ContentScale.Fit,
    )
}

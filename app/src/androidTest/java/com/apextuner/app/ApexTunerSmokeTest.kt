package com.apextuner.app

import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.apextuner.feature.billing.findActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApexTunerSmokeTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun topLevelNavigationRenders() {
        composeRule.onNodeWithText("Dashboard").assertExists()
        composeRule.onNodeWithText("Optimize").assertExists()
        composeRule.onNodeWithText("Apps").assertExists()
        composeRule.onNodeWithText("Tools").assertExists()
        composeRule.onNodeWithText("Settings").assertExists()
    }

    @Test fun toolsRouteExposesCoreFinalFeatures() {
        composeRule.onNodeWithText("Tools").performClick()
        composeRule.onNodeWithText("System Information").assertExists()
        composeRule.onNodeWithText("Game Session Booster", substring = true).assertExists()
        composeRule.onNodeWithText("Privacy & Security").assertExists()
    }

    @Test fun phoneLandscapeKeepsTopLevelNavigationReachable() {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Dashboard").assertExists()
        composeRule.onNodeWithContentDescription("Optimize").assertExists().performClick()
        composeRule.onNodeWithText("Optimize").assertExists()
    }


    @Test fun activityResolutionUnwrapsContextWrappers() {
        val wrapped = ContextWrapper(ContextWrapper(composeRule.activity))
        assertSame(composeRule.activity, wrapped.findActivity())
    }

    @Test fun defaultMultiPhotoPickerContractCreatesAnIntent() {
        val contract = ActivityResultContracts.PickMultipleVisualMedia()
        val request = PickVisualMediaRequest(
            mediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo,
        )
        assertNotNull(contract.createIntent(composeRule.activity, request))
    }
}

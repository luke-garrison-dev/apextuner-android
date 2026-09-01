package com.apextuner.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apextuner.app.ui.shell.ApexTunerApp
import com.apextuner.app.ui.theme.ApexTunerTheme
import com.apextuner.core.navigation.AppLaunchContract
import com.apextuner.core.model.ThemeMode
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()
    private var launchRequest by mutableStateOf<AppLaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchRequest = consumeLaunchRequest(intent)
        enableEdgeToEdge()
        setContent {
            val prefs by appViewModel.preferences.collectAsStateWithLifecycle()
            val entitlement by appViewModel.entitlement.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (prefs.themeMode) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.System -> systemDark
            }
            ApexTunerTheme(darkTheme = dark, dynamicColor = prefs.dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    ApexTunerApp(
                        entitlement = entitlement,
                        showAdvancedTools = prefs.showAdvancedTools,
                        launchRequest = launchRequest,
                        onLaunchRequestConsumed = { launchRequest = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchRequest = consumeLaunchRequest(intent)
        setIntent(intent)
        // A singleTop/deep-link delivery can reach an already-resumed activity without another
        // onResume callback. Refresh here as well so premium gating cannot remain stale.
        appViewModel.refreshEntitlement("new_intent")
    }

    override fun onResume() {
        super.onResume()
        appViewModel.refreshEntitlement()
    }

    private fun consumeLaunchRequest(intent: Intent?): AppLaunchRequest? {
        val request = parseLaunchRequest(intent) ?: return null
        // Launch extras are one-shot commands. Remove them from the Activity-retained Intent so
        // configuration changes cannot replay a Quick Scan or navigation request.
        intent?.removeExtra(AppLaunchContract.EXTRA_DESTINATION)
        intent?.removeExtra(AppLaunchContract.EXTRA_QUICK_SCAN)
        intent?.removeExtra(AppLaunchContract.EXTRA_REQUEST_TOKEN)
        return request
    }

    private fun parseLaunchRequest(intent: Intent?): AppLaunchRequest? {
        val launchIntent = intent ?: return null
        val destination = AppLaunchContract.sanitizeDestination(
            launchIntent.getStringExtra(AppLaunchContract.EXTRA_DESTINATION),
        ) ?: return null
        val quickScan = destination == AppLaunchContract.DESTINATION_OPTIMIZE &&
            launchIntent.getBooleanExtra(AppLaunchContract.EXTRA_QUICK_SCAN, false)
        val token = launchIntent.getLongExtra(AppLaunchContract.EXTRA_REQUEST_TOKEN, 0L)
            .takeIf { it > 0L }
            ?: System.currentTimeMillis()
        return AppLaunchRequest(destination, quickScan, token)
    }
}

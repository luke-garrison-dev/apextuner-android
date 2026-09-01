package com.apextuner.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apextuner.core.billing.EntitlementRepository
import com.apextuner.core.datastore.PreferencesRepository
import com.apextuner.core.model.AppPreferences
import com.apextuner.core.model.SystemProfile
import com.apextuner.core.model.EntitlementState
import com.apextuner.feature.settings.automation.AutomationScheduler
import com.apextuner.feature.settings.automation.SmartAutomationRepository
import com.apextuner.feature.settings.automation.SmartAutomationRecovery
import com.apextuner.feature.settings.widget.ApexStatusWidgetProvider
import com.apextuner.feature.battery.BatteryHealthTrendScheduler
import com.apextuner.feature.battery.ChargingSessionScheduler
import com.apextuner.feature.dashboard.HealthTimelineScheduler
import com.apextuner.feature.network.DataUsageAlertScheduler
import com.apextuner.feature.network.DataUsageCapPreferences
import com.apextuner.feature.tools.game.GameSessionController
import com.apextuner.core.tuning.SafeSystemTuningController
import com.apextuner.core.tuning.TemporaryProfileOverrideCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val entitlementRepository: EntitlementRepository,
    private val gameSessionController: GameSessionController,
    private val tuningController: SafeSystemTuningController,
    private val temporaryProfileOverride: TemporaryProfileOverrideCoordinator,
    private val batteryHealthTrendScheduler: BatteryHealthTrendScheduler,
    private val chargingSessionScheduler: ChargingSessionScheduler,
    private val healthTimelineScheduler: HealthTimelineScheduler,
    private val dataUsageAlertScheduler: DataUsageAlertScheduler,
    private val automationScheduler: AutomationScheduler,
    private val smartAutomationRepository: SmartAutomationRepository,
    private val smartAutomationRecovery: SmartAutomationRecovery,
    private val dataUsageCapPreferences: DataUsageCapPreferences,
) : ViewModel() {
    val preferences: StateFlow<AppPreferences> = preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())
    val entitlement: StateFlow<EntitlementState> = entitlementRepository.entitlement

    init {
        batteryHealthTrendScheduler.ensureScheduled()
        chargingSessionScheduler.ensureScheduled()
        healthTimelineScheduler.ensureScheduled()
        viewModelScope.launch {
            dataUsageCapPreferences.caps
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect { dataUsageAlertScheduler.sync(it) }
        }
        viewModelScope.launch {
            combine(
                preferencesRepository.preferences,
                entitlementRepository.entitlement.map { it.isPremium }.distinctUntilChanged(),
                temporaryProfileOverride.activeOwnerFlow,
            ) { prefs, premium, temporaryOwner -> Triple(prefs, premium, temporaryOwner) }
                .distinctUntilChanged()
                .collect { (prefs, premium, temporaryOwner) ->
                    val hasEnabledSmartRules = try { smartAutomationRepository.hasEnabledRules() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Throwable) { false }
                    automationScheduler.sync(prefs, premium, hasEnabledSmartRules)
                    try { smartAutomationRecovery.reconcileOwnedProfile(forceRestore = !premium) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Throwable) { Unit }
                    if ((!premium || !prefs.nightBatteryProfileEnabled) && prefs.nightBatteryProfileAppliedByAutomation &&
                        temporaryOwner == null
                    ) {
                        if (tuningController.activeProfile() == SystemProfile.Battery) {
                            when (tuningController.restoreBalanced()) {
                                is com.apextuner.core.tuning.ProfileApplyResult.Applied ->
                                    preferencesRepository.setNightBatteryProfileAppliedByAutomation(false)
                                else -> Unit
                            }
                        } else {
                            // A newer manual profile superseded the night automation. Release only
                            // ApexTuner's automation marker and leave the newer profile untouched.
                            preferencesRepository.setNightBatteryProfileAppliedByAutomation(false)
                        }
                    }
                }
        }
        // MainActivity.onResume() performs the initial and subsequent Play refreshes.
        // Avoid issuing a duplicate serialized Billing query during every cold start.
        viewModelScope.launch {
            try { tuningController.reconcileLegacyState() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { Unit }
            try { gameSessionController.recoverStaleSession() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { Unit }
        }
        viewModelScope.launch {
            entitlementRepository.entitlement
                .map { Triple(it.tier, it.verification, it.lastCheckedAtEpochMillis) }
                .distinctUntilChanged()
                .collect { ApexStatusWidgetProvider.requestUpdate(context) }
        }
    }
    fun refreshEntitlement(reason: String = "app_resume") {
        viewModelScope.launch {
            try { entitlementRepository.refresh(reason) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { Unit }
        }
    }

}

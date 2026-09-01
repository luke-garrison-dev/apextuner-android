package com.apextuner.feature.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apextuner.core.billing.EntitlementRepository
import com.apextuner.core.model.PremiumFeature
import com.apextuner.core.time.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NotificationHistoryUiState(
    val settings: NotificationHistorySettings = NotificationHistorySettings(),
    val items: List<NotificationHistoryItem> = emptyList(),
    val premium: Boolean = false,
    val accessGranted: Boolean = false,
    val availability: NotificationHistoryAvailability = NotificationHistoryAvailability(available = true),
) {
    val collectionActive: Boolean
        get() = availability.available &&
            NotificationHistoryPolicy.collectionAllowed(settings, premium, accessGranted)
}

@HiltViewModel
class NotificationHistoryViewModel @Inject constructor(
    private val preferences: NotificationHistoryPreferences,
    private val repository: NotificationHistoryRepository,
    private val entitlementRepository: EntitlementRepository,
    private val accessController: NotificationHistoryAccessController,
    private val scheduler: NotificationHistoryScheduler,
    private val timeProvider: TimeProvider,
) : ViewModel() {
    private val accessGranted = MutableStateFlow(accessController.isAccessGranted())
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<NotificationHistoryUiState> = combine(
        preferences.settings,
        repository.observeRecent(),
        entitlementRepository.entitlement,
        accessGranted,
    ) { settings, items, _, access ->
        NotificationHistoryUiState(
            settings = settings,
            items = items,
            premium = entitlementRepository.hasAccess(PremiumFeature.NotificationHistory),
            accessGranted = access,
            availability = accessController.availability(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NotificationHistoryUiState(
            premium = entitlementRepository.hasAccess(PremiumFeature.NotificationHistory),
            accessGranted = accessGranted.value,
            availability = accessController.availability(),
        ),
    )

    init {
        viewModelScope.launch {
            combine(
                preferences.settings,
                entitlementRepository.entitlement,
            ) { settings, entitlement ->
                settings to entitlementRepository.hasAccess(PremiumFeature.NotificationHistory)
            }
                .distinctUntilChanged()
                .collect { (settings, premium) ->
                    if (settings.enabled) scheduler.ensureScheduled()
                    accessController.setCollectionComponentEnabled(settings.enabled && premium)
                    accessGranted.value = accessController.isAccessGranted()
                }
        }
    }

    fun refreshAccess() {
        val current = state.value
        accessController.setCollectionComponentEnabled(current.settings.enabled && current.premium)
        accessGranted.value = accessController.isAccessGranted()
    }

    fun setRetentionDays(days: Int) = mutate {
        preferences.setRetentionDays(days)
        repository.prune(timeProvider.nowEpochMillis(), days)
        repository.enforceHardLimit()
        scheduler.ensureScheduled()
    }

    fun setMuted(packageName: String, muted: Boolean) = mutate {
        preferences.setMuted(packageName, muted)
        _message.value = if (muted) {
            "Muted in ApexTuner. Existing entries remain until cleared or expired."
        } else {
            "ApexTuner will record future notifications from this app while collection is active."
        }
    }

    fun clearAll() = mutate {
        repository.clearAll()
        _message.value = "Notification history cleared from this device."
    }

    fun clearPackage(packageName: String) = mutate {
        repository.clearPackage(packageName)
        _message.value = "Stored notification history for this app was cleared."
    }

    fun dismissMessage() {
        _message.value = null
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _message.value = "The notification-history operation could not be completed safely."
            }
        }
    }
}

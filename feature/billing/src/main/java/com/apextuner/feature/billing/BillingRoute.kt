package com.apextuner.feature.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apextuner.core.model.BillingOffering
import com.apextuner.core.model.EntitlementVerification
import com.apextuner.core.ui.ApexCard as Card
import com.apextuner.core.ui.ApexLayout
import com.apextuner.feature.billing.data.BillingCatalog

@Composable
fun BillingRoute(
    onBack: (() -> Unit)? = null,
    viewModel: BillingViewModel = hiltViewModel(),
) {
    val entitlement by viewModel.entitlement.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val checkoutOfferingKey by viewModel.checkoutOfferingKey.collectAsStateWithLifecycle()
    val purchaseErrors by viewModel.purchaseErrors.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    val lifetimeAvailable = catalog.offerings.any {
        it.productId == BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = ApexLayout.horizontalPadding(),
                vertical = 18.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (onBack != null) {
                        TextButton(onClick = onBack) { Text(stringResource(R.string.ui_back)) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.ui_apextuner_premium),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(
                                R.string.ui_unlock_the_advanced_optimization_toolset_through_google,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                EntitlementCard(
                    entitlement.tier.name,
                    entitlement.verification,
                    entitlement.message,
                )
            }
            item { FeatureSummaryCard() }

            if (catalog.loading) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(18.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.ui_loading_eligible_google_play_offers))
                        }
                    }
                }
            }

            if (!catalog.loading) {
                items(catalog.offerings, key = { it.key }) { offering ->
                    val purchasing = checkoutOfferingKey == offering.key
                    val blockedReason = when {
                        entitlement.hasPendingPurchase ->
                            stringResource(R.string.billing_pending_purchase_block)
                        checkoutOfferingKey != null && !purchasing ->
                            stringResource(R.string.billing_other_purchase_starting)
                        else -> null
                    }
                    OfferingCard(
                        offering = offering,
                        owned = entitlement.isPremium,
                        enabled = blockedReason == null,
                        purchasing = purchasing,
                        disabledReason = blockedReason,
                        errorMessage = purchaseErrors[offering.key],
                        onPurchase = {
                            val host = activity
                            if (host == null) {
                                viewModel.reportPurchaseError(
                                    offering.key,
                                    "A host Activity could not be resolved. Reopen Premium from the app and try again.",
                                )
                            } else {
                                viewModel.purchase(host, offering.key)
                            }
                        },
                    )
                }
            }

            if (!catalog.loading && !lifetimeAvailable) {
                item {
                    UnavailableOfferingCard(
                        title = stringResource(R.string.billing_lifetime_unavailable),
                        message = catalog.productErrors[BillingCatalog.PREMIUM_LIFETIME_PRODUCT_ID]
                            ?: "Google Play did not return the lifetime product or an eligible purchase option.",
                        onRetry = viewModel::refreshCatalog,
                    )
                }
            }

            if (!catalog.message.isNullOrBlank() &&
                (catalog.offerings.isNotEmpty() || catalog.productErrors.isEmpty())
            ) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(catalog.message.orEmpty(), Modifier.weight(1f))
                            TextButton(onClick = viewModel::refreshCatalog) {
                                Text(stringResource(R.string.ui_refresh))
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            stringResource(R.string.ui_restore_manage),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(
                                R.string.ui_restore_re_queries_active_google_play_purchases_pending,
                            ),
                        )
                        OutlinedButton(
                            onClick = viewModel::restorePurchases,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.ui_restore_purchases))
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Outlined.Shield, contentDescription = null)
                        Text(
                            stringResource(R.string.ui_billing_security_model),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(
                                R.string.ui_apextuner_grants_access_only_for_purchases_reported_as,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntitlementCard(
    tierName: String,
    verification: EntitlementVerification,
    message: String?,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.WorkspacePremium, contentDescription = null)
                Text(
                    stringResource(R.string.ui_current_entitlement),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                tierName.replace("PremiumLifetime", "Premium Lifetime"),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                when (verification) {
                    EntitlementVerification.VerifiedByPlayClient ->
                        "Verified by Google Play in this session"
                    EntitlementVerification.CachedOfflineGrace ->
                        "Recent encrypted local entitlement • Play re-verification pending"
                    EntitlementVerification.PlayUnavailable ->
                        "Google Play verification unavailable"
                    EntitlementVerification.NotChecked ->
                        "Verification pending"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun FeatureSummaryCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.ui_premium_unlocks),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            listOf(
                "Exact duplicates, large-file cleanup and advanced media tools",
                "Scheduled maintenance and smart automation",
                "Local per-app firewall and advanced access tools",
                "Floating real-time monitor, widget and Quick Settings action",
                "Capability-aware scheduled maintenance and reversible night profile",
            ).forEach { text ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Text(text, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun OfferingCard(
    offering: BillingOffering,
    owned: Boolean,
    enabled: Boolean,
    purchasing: Boolean,
    disabledReason: String?,
    errorMessage: String?,
    onPurchase: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                offering.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(offering.description)
            Text(
                offering.formattedPrice,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            offering.pricingSummary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onPurchase,
                enabled = enabled && !owned && !purchasing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (purchasing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        stringResource(R.string.billing_starting_google_play),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    Text(
                        if (owned) {
                            stringResource(R.string.billing_active)
                        } else {
                            stringResource(R.string.billing_buy_lifetime)
                        },
                    )
                }
            }
            if (!owned && !purchasing && disabledReason != null) {
                Text(
                    disabledReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            errorMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun UnavailableOfferingCard(
    title: String,
    message: String,
    onRetry: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Text(stringResource(R.string.ui_retry))
            }
        }
    }
}

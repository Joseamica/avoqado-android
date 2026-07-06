package com.avoqado.pos.pos.presentation.cart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.customers.presentation.CustomerCreditsViewModel
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

/**
 * "Membresías" card in the cart panel: when the attached customer has prepaid credits,
 * lets staff redeem one right at checkout (the "use a class/session" moment). Renders
 * nothing when the customer has no credits. Mirrors iOS CartCustomerCreditsCard.
 */
@Composable
fun CartCreditsCard(
    customerId: String,
    viewModel: CustomerCreditsViewModel = hiltViewModel(),
) {
    val balances by viewModel.balances.collectAsStateWithLifecycle()
    val busyId by viewModel.busyBalanceId.collectAsStateWithLifecycle()

    LaunchedEffect(customerId) { viewModel.load(customerId) }

    val items = balances.flatMap { it.itemBalances }
    if (items.isEmpty()) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(AvoqadoTheme.spacing.md)) {
            Text(
                text = "Membresías",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = AvoqadoTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.productName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${item.remainingQuantity} disponibles",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val enabled = item.remainingQuantity > 0 && busyId == null
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (enabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .clickable(enabled = enabled) { viewModel.redeem(item.id, customerId) }
                            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.xs),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busyId == item.id) {
                            CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = "Canjear",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

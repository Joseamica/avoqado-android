package com.avoqado.pos.customers.presentation

import androidx.compose.foundation.background
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.articles.data.model.CreditBalanceItem
import com.avoqado.pos.articles.data.model.CreditPack
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

@Composable
fun CustomerCreditsSection(
    customer: Customer,
    viewModel: CustomerCreditsViewModel = hiltViewModel(),
) {
    val balances by viewModel.balances.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val busyId by viewModel.busyBalanceId.collectAsStateWithLifecycle()
    var showSell by remember { mutableStateOf(false) }
    // Sell/redeem move paid credits — gate them (a WAITER could view a customer
    // and trigger a sell/redeem the backend would then 403).
    val canManage = viewModel.canManageCustomers
    var showRedeemConfirm by remember { mutableStateOf<CreditBalanceItem?>(null) }

    LaunchedEffect(customer.id) { viewModel.load(customer.id) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = AvoqadoTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Membresías",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (canManage) {
                TextButton(onClick = { showSell = true }) { Text("Vender paquete") }
            }
        }

        when {
            isLoading -> Box(
                modifier = Modifier.fillMaxWidth().padding(AvoqadoTheme.spacing.lg),
                contentAlignment = Alignment.Center,
            ) { AvoqadoBrandLoader(size = 48.dp) }

            balances.isEmpty() -> Text(
                text = "Sin paquetes activos",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.sm),
            )

            else -> balances.forEach { purchase ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = AvoqadoTheme.spacing.sm),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(modifier = Modifier.padding(AvoqadoTheme.spacing.md)) {
                        Text(
                            text = purchase.packName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        purchase.itemBalances.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = AvoqadoTheme.spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.productName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "${item.remainingQuantity} de ${item.originalQuantity} disponibles",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                RedeemChip(
                                    enabled = canManage && item.remainingQuantity > 0 && busyId == null,
                                    busy = busyId == item.id,
                                    onClick = { showRedeemConfirm = item },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    showRedeemConfirm?.let { item ->
        AvoqadoDialog(
            title = "Canjear crédito",
            description = "Se descontará 1 de ${item.productName}. Esta acción no se puede deshacer.",
            onDismiss = { showRedeemConfirm = null },
            actionButton = {
                PrimaryButton(text = "Canjear", onClick = {
                    viewModel.redeem(item.id, customer.id)
                    showRedeemConfirm = null
                })
            },
            content = {},
        )
    }

    if (showSell) {
        SellPackDialog(
            customer = customer,
            viewModel = viewModel,
            onDismiss = { showSell = false },
        )
    }
}

@Composable
private fun RedeemChip(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.height(16.dp).width(16.dp),
                strokeWidth = 2.dp,
            )
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

@Composable
private fun SellPackDialog(
    customer: Customer,
    viewModel: CustomerCreditsViewModel,
    onDismiss: () -> Unit,
) {
    val packs by viewModel.packs.collectAsStateWithLifecycle()
    val sellingId by viewModel.sellingPackId.collectAsStateWithLifecycle()
    var pendingPack by remember { mutableStateOf<CreditPack?>(null) }

    LaunchedEffect(Unit) { viewModel.loadPacks() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vender a ${customer.fullName}") },
        text = {
            Column {
                if (packs.isEmpty()) {
                    Text(
                        text = "No hay paquetes disponibles",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    packs.forEach { pack ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = sellingId == null) { pendingPack = pack }
                                .padding(vertical = AvoqadoTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pack.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "${pack.creditCount} créditos · ${pack.displayPrice}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (sellingId == pack.id) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(18.dp).width(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(
                                    text = pack.displayPrice,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )

    // Confirm before granting — a stray tap would otherwise sell a paid pack.
    pendingPack?.let { pack ->
        AlertDialog(
            onDismissRequest = { pendingPack = null },
            title = { Text("Vender paquete") },
            text = {
                Text("Se otorgarán ${pack.creditCount} créditos a ${customer.fullName}. Cobra el pago antes de confirmar.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingPack = null
                    viewModel.sell(pack.id, customer.id, onDone = onDismiss)
                }) { Text("Cobrar ${pack.displayPrice} y vender") }
            },
            dismissButton = { TextButton(onClick = { pendingPack = null }) { Text("Cancelar") } },
        )
    }
}

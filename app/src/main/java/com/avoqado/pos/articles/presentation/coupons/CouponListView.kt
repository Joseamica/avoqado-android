package com.avoqado.pos.articles.presentation.coupons

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avoqado.pos.articles.data.model.AdminCoupon
import com.avoqado.pos.articles.presentation.ArticlesViewModel
import com.avoqado.pos.designsystem.components.AvoqadoLoadingState
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.designsystem.theme.Error
import com.avoqado.pos.designsystem.theme.Success

private val SuccessSubtleBg = Success.copy(alpha = 0.15f)
private val ErrorSubtleBg = Error.copy(alpha = 0.15f)

@Composable
fun CouponListView(viewModel: ArticlesViewModel) {
    val coupons by viewModel.coupons.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var editingCoupon by remember { mutableStateOf<AdminCoupon?>(null) }
    var showCreateForm by remember { mutableStateOf(false) }
    var deletingCoupon by remember { mutableStateOf<AdminCoupon?>(null) }
    val configuration = LocalConfiguration.current
    val adaptive = AvoqadoTheme.adaptive
    val lowDensityTabletFallback = configuration.densityDpi in 1..220 &&
        adaptive.sizeClass != AvoqadoAdaptiveSizeClass.Compact &&
        (!adaptive.isPortrait || configuration.screenWidthDp <= 900)
    val denseListMode = adaptive.isAggressiveCompact || lowDensityTabletFallback
    val contentHorizontalPadding = if (denseListMode) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg
    val headerVerticalPadding = if (denseListMode) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.md
    val rowVerticalPadding = if (denseListMode) 9.dp else AvoqadoTheme.spacing.md

    // Show form sheet (create or edit)
    if (showCreateForm) {
        CouponFormSheet(
            coupon = null,
            viewModel = viewModel,
            onDismiss = { showCreateForm = false },
        )
    }

    editingCoupon?.let { coupon ->
        CouponFormSheet(
            coupon = coupon,
            viewModel = viewModel,
            onDismiss = { editingCoupon = null },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // MARK: - Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = contentHorizontalPadding,
                    vertical = headerVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cupones",
                style = if (denseListMode) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(if (denseListMode) 34.dp else 36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    )
                    .clickable { showCreateForm = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Nuevo cupon",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(if (denseListMode) 18.dp else 20.dp),
                )
            }
        }

        // MARK: - Content
        if (isLoading && coupons.isEmpty()) {
            AvoqadoLoadingState(
                message = "Cargando cupones...",
                compact = denseListMode,
            )
        } else if (coupons.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AvoqadoTheme.spacing.xxxl),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ConfirmationNumber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "No hay cupones",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Crea tu primer cupon",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(coupons, key = { it.id }) { coupon ->
                    CouponRow(
                        coupon = coupon,
                        denseMode = denseListMode,
                        horizontalPadding = contentHorizontalPadding,
                        rowVerticalPadding = rowVerticalPadding,
                        onTap = { editingCoupon = coupon },
                        onEdit = { editingCoupon = coupon },
                        onDelete = { deletingCoupon = coupon },
                    )
                }
            }
        }
    }

    // MARK: - Delete confirmation dialog
    deletingCoupon?.let { coupon ->
        AlertDialog(
            onDismissRequest = { deletingCoupon = null },
            title = {
                Text(text = "Eliminar cupon")
            },
            text = {
                Text(text = "Esta accion no se puede deshacer")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCoupon(coupon.id)
                    deletingCoupon = null
                }) {
                    Text(
                        text = "Eliminar",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingCoupon = null }) {
                    Text(text = "Cancelar")
                }
            },
        )
    }
}

// MARK: - Coupon Row

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CouponRow(
    coupon: AdminCoupon,
    denseMode: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    rowVerticalPadding: androidx.compose.ui.unit.Dp,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val isActive = coupon.active ?: false

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onTap() },
                onLongClick = { showMenu = true },
            )
            .padding(
                horizontal = horizontalPadding,
                vertical = rowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (denseMode) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md),
    ) {
        // MARK: - Ticket icon avatar (44x44)
        Box(
            modifier = Modifier
                .size(if (denseMode) 40.dp else 44.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.sm),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.ConfirmationNumber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (denseMode) 18.dp else 20.dp),
            )
        }

        // MARK: - Code + usage text
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs),
        ) {
            Text(
                text = coupon.code,
                style = if (denseMode) {
                    MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                } else {
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = coupon.usageText,
                style = if (denseMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // MARK: - Status capsules + dropdown anchor
        Box {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs),
            ) {
                // Active/Inactive capsule
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isActive) {
                        SuccessSubtleBg
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ) {
                    Text(
                        text = if (isActive) "Activo" else "Inactivo",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = AvoqadoTheme.spacing.sm,
                            vertical = if (denseMode) 1.dp else AvoqadoTheme.spacing.xxs,
                        ),
                    )
                }

                // Expired badge (only when expired)
                if (coupon.isExpired) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = ErrorSubtleBg,
                    ) {
                        Text(
                            text = "Expirado",
                            style = MaterialTheme.typography.labelSmall,
                            color = Error,
                            modifier = Modifier.padding(
                                horizontal = AvoqadoTheme.spacing.sm,
                                vertical = if (denseMode) 1.dp else AvoqadoTheme.spacing.xxs,
                            ),
                        )
                    }
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Editar") },
                    onClick = {
                        showMenu = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Eliminar",
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                )
            }
        }
    }

    // Divider
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .padding(horizontal = horizontalPadding)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

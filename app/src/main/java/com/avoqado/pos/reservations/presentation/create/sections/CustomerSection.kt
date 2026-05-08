package com.avoqado.pos.reservations.presentation.create.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.reservations.presentation.create.CreateReservationViewModel
import com.avoqado.pos.reservations.presentation.create.components.CustomerQuickCreateSheet

/**
 * CustomerSection — embedded customer picker for the create-reservation flow.
 *
 * Lives inside a [ModalBottomSheet] (PickerSheet) — owns its own scroll via
 * LazyColumn so the customer list stays performant for venues with hundreds of
 * customers. Three modes are visible at once:
 *   A) Search and tap an existing customer
 *   B) Toggle "Continuar como invitado" + fill inline guest fields
 *   C) Tap "+ Crear cliente" to open [CustomerQuickCreateSheet]
 *
 * Picking an existing customer fires [onCustomerPicked] so the caller can close
 * the sheet automatically. Other actions (guest toggle, quick-create) keep the
 * sheet open.
 */
@Composable
fun CustomerSection(
    viewModel: CreateReservationViewModel,
    onCustomerPicked: (() -> Unit)? = null,
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val customers by viewModel.customers.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingCustomers.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var showQuickCreate by remember { mutableStateOf(false) }

    val filtered = remember(customers, query) {
        if (query.isBlank()) {
            customers
        } else {
            val q = query.trim()
            customers.filter { c ->
                c.fullName.contains(q, ignoreCase = true) ||
                    (c.phone?.contains(q, ignoreCase = true) == true) ||
                    (c.email?.contains(q, ignoreCase = true) == true)
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item("search") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchPillField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Buscar cliente",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        when {
            isLoading && customers.isEmpty() -> {
                item("loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AvoqadoTheme.spacing.xxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            filtered.isEmpty() -> {
                item("empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AvoqadoTheme.spacing.xxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (query.isNotBlank()) {
                                "Sin resultados para \"$query\""
                            } else {
                                "No hay clientes registrados"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            else -> {
                items(filtered, key = { it.id }) { customer ->
                    CustomerRow(
                        customer = customer,
                        isSelected = !draft.isGuest && draft.customerId == customer.id,
                        onClick = {
                            viewModel.update {
                                it.copy(
                                    customerId = customer.id,
                                    customerName = customer.fullName,
                                    isGuest = false,
                                    guestName = null,
                                    guestPhone = null,
                                    guestEmail = null,
                                )
                            }
                            onCustomerPicked?.invoke()
                        },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 72.dp),
                    )
                }
            }
        }

        item("create-cta") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showQuickCreate = true }
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = "Crear cliente",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        item("or-divider") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
            ) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    text = "o",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }

        item("guest") {
            GuestSection(
                isGuest = draft.isGuest,
                guestName = draft.guestName.orEmpty(),
                guestPhone = draft.guestPhone.orEmpty(),
                guestEmail = draft.guestEmail.orEmpty(),
                onGuestToggle = { isOn ->
                    viewModel.update {
                        if (isOn) {
                            it.copy(
                                isGuest = true,
                                customerId = null,
                                customerName = null,
                            )
                        } else {
                            it.copy(
                                isGuest = false,
                                guestName = null,
                                guestPhone = null,
                                guestEmail = null,
                            )
                        }
                    }
                },
                onGuestNameChange = { value ->
                    viewModel.update { it.copy(guestName = value.ifBlank { null }) }
                },
                onGuestPhoneChange = { value ->
                    viewModel.update { it.copy(guestPhone = value.ifBlank { null }) }
                },
                onGuestEmailChange = { value ->
                    viewModel.update { it.copy(guestEmail = value.ifBlank { null }) }
                },
            )
        }

        item("bottom-spacer") {
            Spacer(Modifier.height(AvoqadoTheme.spacing.xxl))
        }
    }

    if (showQuickCreate) {
        CustomerQuickCreateSheet(
            viewModel = viewModel,
            onDismiss = { showQuickCreate = false },
        )
    }
}

@Composable
private fun CustomerRow(
    customer: Customer,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.md,
            )
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = customer.initials,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = customer.fullName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            customer.displayContact?.let { contact ->
                Text(
                    text = contact,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Seleccionado",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun GuestSection(
    isGuest: Boolean,
    guestName: String,
    guestPhone: String,
    guestEmail: String,
    onGuestToggle: (Boolean) -> Unit,
    onGuestNameChange: (String) -> Unit,
    onGuestPhoneChange: (String) -> Unit,
    onGuestEmailChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onGuestToggle(!isGuest) }
                .padding(vertical = AvoqadoTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
        ) {
            Icon(
                imageVector = Icons.Filled.PersonOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Continuar como invitado",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Sin guardar al cliente",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Switch(
                checked = isGuest,
                onCheckedChange = onGuestToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }

        if (isGuest) {
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxs))

            FieldLabel(text = "Nombre")
            AvoqadoPillTextField(
                value = guestName,
                onValueChange = onGuestNameChange,
                placeholder = "Nombre del invitado",
            )

            FieldLabel(text = "Teléfono (opcional)")
            AvoqadoPillTextField(
                value = guestPhone,
                onValueChange = onGuestPhoneChange,
                placeholder = "Teléfono",
                keyboardType = KeyboardType.Phone,
            )

            FieldLabel(text = "Correo (opcional)")
            AvoqadoPillTextField(
                value = guestEmail,
                onValueChange = onGuestEmailChange,
                placeholder = "correo@ejemplo.com",
                keyboardType = KeyboardType.Email,
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

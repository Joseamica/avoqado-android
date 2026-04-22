package com.avoqado.pos.customers.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.designsystem.components.CloseButton
import com.avoqado.pos.designsystem.components.SearchPillField
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

@Composable
fun CustomersView(
    viewModel: CustomersViewModel,
    onCustomerSelected: (Customer) -> Unit,
    onDismiss: () -> Unit,
    onCreateCustomer: (searchText: String) -> Unit,
    canCreateCustomer: Boolean = true,
) {
    val customers by viewModel.customers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var searchText by remember { mutableStateOf("") }

    val filteredCustomers = remember(searchText, customers) {
        if (searchText.isEmpty()) {
            customers
        } else {
            val query = searchText.lowercase()
            customers.filter { customer ->
                customer.fullName.lowercase().contains(query) ||
                    (customer.phone?.contains(query) == true) ||
                    (customer.email?.lowercase()?.contains(query) == true)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.fetchCustomers()
    }

    val configuration = LocalConfiguration.current
    val adaptive = AvoqadoTheme.adaptive
    val lowDensityTabletFallback = configuration.densityDpi in 1..220 &&
        adaptive.sizeClass != AvoqadoAdaptiveSizeClass.Compact &&
        (!adaptive.isPortrait || configuration.screenWidthDp <= 900)
    val denseMode = adaptive.isAggressiveCompact || lowDensityTabletFallback

    val horizontalPadding = if (denseMode) AvoqadoTheme.spacing.lg else AvoqadoTheme.spacing.xl
    val sectionPadding = if (denseMode) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md
    val actionSlotWidth = if (denseMode) 132.dp else 148.dp
    val headerActionContainer = MaterialTheme.colorScheme.onSurface
    val headerActionContent = MaterialTheme.colorScheme.surface

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Clientes",
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                style = if (denseMode) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CloseButton(onClick = onDismiss)
                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier = Modifier.width(actionSlotWidth),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (canCreateCustomer) {
                        Button(
                            onClick = { onCreateCustomer(searchText) },
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = AvoqadoTheme.spacing.lg),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = headerActionContainer,
                                contentColor = headerActionContent,
                                disabledContainerColor = headerActionContainer.copy(alpha = 0.35f),
                                disabledContentColor = headerActionContent.copy(alpha = 0.75f),
                            ),
                        ) {
                            Text(
                                text = "Crear",
                                style = if (denseMode) {
                                    MaterialTheme.typography.titleSmall
                                } else {
                                    MaterialTheme.typography.titleMedium
                                },
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Spacer(modifier = Modifier.height(if (denseMode) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md))

        SearchPillField(
            query = searchText,
            onQueryChange = { searchText = it },
            placeholder = "Buscar cliente",
            height = AvoqadoTheme.adaptive.listSearchFieldHeight,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
        )

        Spacer(modifier = Modifier.height(if (denseMode) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md))

        when {
            isLoading && customers.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                    Text(
                        text = "Cargando clientes...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            filteredCustomers.isEmpty() && searchText.isNotEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = horizontalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "No se encontraron clientes",
                        style = if (denseMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                    Text(
                        text = if (canCreateCustomer) {
                            "Crea un nuevo cliente con esta informacion"
                        } else {
                            "No se encontraron resultados"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (canCreateCustomer) {
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                        Button(
                            onClick = { onCreateCustomer(searchText) },
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = headerActionContainer,
                                contentColor = headerActionContent,
                            ),
                        ) {
                            Text(
                                text = "Crear cliente",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                }
            }

            else -> {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (filteredCustomers.isNotEmpty()) {
                        item {
                            Text(
                                text = "Creados recientemente",
                                style = if (denseMode) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(horizontal = horizontalPadding)
                                    .padding(vertical = sectionPadding),
                            )
                        }
                    }

                    items(filteredCustomers, key = { it.id }) { customer ->
                        CustomerRow(
                            customer = customer,
                            denseMode = denseMode,
                            onClick = { onCustomerSelected(customer) },
                            horizontalPadding = horizontalPadding,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Customer Row

@Composable
private fun CustomerRow(
    customer: Customer,
    denseMode: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val adaptive = AvoqadoTheme.adaptive
    val avatarSize = if (denseMode) adaptive.listLeadingVisualSize else 44.dp
    val rowPadding = if (denseMode) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = customer.initials,
                style = if (denseMode) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.width(if (denseMode) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md))

        Column {
            Text(
                text = customer.fullName,
                style = if (denseMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            customer.displayContact?.let { contact ->
                Text(
                    text = contact,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

package com.avoqado.pos.customers.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.designsystem.components.CircleBackButton
import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

// MARK: - Entry Point

@Composable
fun CustomersScreen(
    isTablet: Boolean,
    onDismiss: () -> Unit,
    viewModel: CustomersViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) {
        viewModel.fetchCustomers()
    }

    if (isTablet) {
        TabletCustomersLayout(
            viewModel = viewModel,
            onDismiss = onDismiss,
        )
    } else {
        PhoneCustomersLayout(
            viewModel = viewModel,
            onDismiss = onDismiss,
        )
    }
}

// MARK: - Tablet Layout (Split View)

@Composable
private fun TabletCustomersLayout(
    viewModel: CustomersViewModel,
    onDismiss: () -> Unit,
) {
    val customers by viewModel.customers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var selectedCustomer by remember { mutableStateOf<Customer?>(null) }
    var searchText by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showCreateCustomer by remember { mutableStateOf(false) }

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

    // Create Customer overlay
    if (showCreateCustomer) {
        CreateCustomerView(
            viewModel = viewModel,
            initialPhone = null,
            initialName = null,
            onCustomerCreated = { customer ->
                showCreateCustomer = false
                selectedCustomer = customer
            },
            onBack = { showCreateCustomer = false },
        )
        return
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Left Panel (~380dp) - Customer List
        Column(
            modifier = Modifier
                .width(380.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // Header: CircleBackButton + "Clientes" title + "..." menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBackButton(onClick = onDismiss)

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "Clientes",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.weight(1f))

                // "..." menu button
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Opciones",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        if (viewModel.canManageCustomers) {
                            DropdownMenuItem(
                                text = { Text("Crear cliente") },
                                onClick = {
                                    showMenu = false
                                    showCreateCustomer = true
                                },
                            )
                        }
                    }
                }
            }

            // Search bar (outlined style)
            TextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AvoqadoTheme.spacing.lg)
                    .padding(bottom = AvoqadoTheme.spacing.md)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(25.dp),
                    ),
                placeholder = { Text("Buscar") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Limpiar",
                            modifier = Modifier.clickable { searchText = "" },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(25.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Customer list
            when {
                isLoading && customers.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        AvoqadoBrandLoader(size = 72.dp)
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
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "No se encontraron clientes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filteredCustomers, key = { it.id }) { customer ->
                            CustomerListRow(
                                customer = customer,
                                isSelected = customer.id == selectedCustomer?.id,
                                onClick = { selectedCustomer = customer },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        // Vertical divider between panels
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Right Panel - Detail or Empty State
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (selectedCustomer != null) {
                CustomerDetailPanel(
                    customer = selectedCustomer!!,
                )
            } else {
                CustomerEmptyState()
            }
        }
    }
}

// MARK: - Phone Layout

@Composable
private fun PhoneCustomersLayout(
    viewModel: CustomersViewModel,
    onDismiss: () -> Unit,
) {
    val customers by viewModel.customers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var selectedCustomer by remember { mutableStateOf<Customer?>(null) }
    var searchText by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showCreateCustomer by remember { mutableStateOf(false) }

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

    // Create Customer overlay
    if (showCreateCustomer) {
        CreateCustomerView(
            viewModel = viewModel,
            initialPhone = null,
            initialName = null,
            onCustomerCreated = { customer ->
                showCreateCustomer = false
                selectedCustomer = customer
            },
            onBack = { showCreateCustomer = false },
        )
        return
    }

    // If a customer is selected, show detail
    if (selectedCustomer != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AvoqadoTheme.spacing.lg,
                        vertical = AvoqadoTheme.spacing.md,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleBackButton(onClick = { selectedCustomer = null })
                Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
                Text(
                    text = selectedCustomer!!.fullName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            CustomerDetailPanel(
                customer = selectedCustomer!!,
            )
        }
        return
    }

    // Customer list
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AvoqadoTheme.spacing.lg,
                    vertical = AvoqadoTheme.spacing.md,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleBackButton(onClick = onDismiss)

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Clientes",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.weight(1f))

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Opciones",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    if (viewModel.canManageCustomers) {
                        DropdownMenuItem(
                            text = { Text("Crear cliente") },
                            onClick = {
                                showMenu = false
                                showCreateCustomer = true
                            },
                        )
                    }
                }
            }
        }

        // Search bar
        TextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.lg)
                .padding(bottom = AvoqadoTheme.spacing.md)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(25.dp),
                ),
            placeholder = { Text("Buscar") },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (searchText.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Limpiar",
                        modifier = Modifier.clickable { searchText = "" },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(25.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Customer list content
        when {
            isLoading && customers.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AvoqadoBrandLoader(size = 72.dp)
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
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "No se encontraron clientes",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            else -> {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredCustomers, key = { it.id }) { customer ->
                        CustomerListRow(
                            customer = customer,
                            isSelected = false,
                            onClick = { selectedCustomer = customer },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

// MARK: - Customer List Row (Square style)

@Composable
private fun CustomerListRow(
    customer: Customer,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Circle avatar with initials (36dp, gray background)
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = customer.initials,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        // Name + contact info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = customer.fullName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val contactLine = buildContactLine(customer)
            if (contactLine != null) {
                Text(
                    text = contactLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// MARK: - Customer Detail Panel

@Composable
private fun CustomerDetailPanel(
    customer: Customer,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.xl),
    ) {
        // Header: Large avatar + name + edit button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            // Large avatar
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = customer.initials,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.lg))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = customer.fullName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (customer.createdAt != null) {
                    Text(
                        text = "Cliente desde ${formatDate(customer.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Contact section
        DetailSectionCard(title = "Contacto") {
            if (customer.phone != null) {
                DetailRow(
                    icon = Icons.Filled.Phone,
                    label = "Teléfono",
                    value = customer.phone,
                )
            }
            if (customer.phone != null && customer.email != null) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 48.dp),
                )
            }
            if (customer.email != null) {
                DetailRow(
                    icon = Icons.Filled.Email,
                    label = "Correo",
                    value = customer.email,
                )
            }
            if (customer.phone == null && customer.email == null) {
                Text(
                    text = "Sin información de contacto",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(AvoqadoTheme.spacing.lg),
                )
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        // Activity section
        DetailSectionCard(title = "Actividad") {
            DetailRow(
                icon = Icons.Filled.Person,
                label = "Visitas totales",
                value = "${customer.totalVisits ?: 0}",
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 48.dp),
            )
            DetailRow(
                icon = Icons.Filled.Person,
                label = "Gasto promedio",
                value = if (customer.averageSpend != null) {
                    "$${String.format("%.2f", customer.averageSpend)}"
                } else {
                    "$0.00"
                },
            )
            if (customer.lastVisit != null) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 48.dp),
                )
                DetailRow(
                    icon = Icons.Filled.Person,
                    label = "Última visita",
                    value = formatDate(customer.lastVisit),
                )
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        // Prepaid credit packs / memberships (sell in person + redeem)
        CustomerCreditsSection(customer = customer)
    }
}

// MARK: - Empty State

@Composable
private fun CustomerEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Person icon in a rounded square background
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        Text(
            text = "Selecciona un cliente",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// MARK: - Detail Section Card

@Composable
private fun DetailSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = AvoqadoTheme.spacing.sm),
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
    ) {
        content()
    }
}

// MARK: - Detail Row

@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// MARK: - Helpers

/**
 * Builds a contact line like "phone | email" for the list row
 */
private fun buildContactLine(customer: Customer): String? {
    val parts = mutableListOf<String>()
    customer.phone?.let { parts.add(it) }
    customer.email?.let { parts.add(it) }
    return if (parts.isNotEmpty()) parts.joinToString(" | ") else null
}

/**
 * Formats an ISO date string to a short display format in the active venue's timezone.
 */
private fun formatDate(isoDate: String?): String {
    if (isoDate.isNullOrBlank()) return ""
    val zoned = com.avoqado.pos.core.util.VenueDateTimeFormatter.parseIso(isoDate)
        ?.atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
    return zoned?.format(
        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"),
    ) ?: isoDate.take(10)
}

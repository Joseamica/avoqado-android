package com.avoqado.pos.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.addons.presentation.AddonsScreen
import com.avoqado.pos.articles.presentation.ArticlesScreen
import com.avoqado.pos.auth.presentation.VenueSwitcherSheet
import com.avoqado.pos.cashdrawer.presentation.CashDrawerScreen
import com.avoqado.pos.customers.presentation.CustomersScreen
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.estimates.presentation.EstimatesScreen
import com.avoqado.pos.kds.presentation.KDSScreen
import com.avoqado.pos.orders.presentation.OrdersScreen
import com.avoqado.pos.printing.presentation.PrinterSettingsSheet
import com.avoqado.pos.reports.presentation.ReportsScreen
import com.avoqado.pos.settings.presentation.ChangeModeSheet
import com.avoqado.pos.settings.presentation.CustomizeMenuSheet
import com.avoqado.pos.settings.presentation.SetupWizardScreen
import com.avoqado.pos.settings.presentation.SupportScreen
import com.avoqado.pos.timeclock.presentation.TimeClockSheet

@Composable
fun MoreMenuScreen(
    onLogout: () -> Unit,
    viewModel: MoreMenuViewModel = hiltViewModel(),
) {
    val venueName by viewModel.venueName.collectAsState()
    val isSwitching by viewModel.isSwitching.collectAsState()
    val currentMode by viewModel.posModeManager.currentMode.collectAsState()
    var showVenueSwitcher by remember { mutableStateOf(false) }
    var showTimeClock by remember { mutableStateOf(false) }
    var showPrinter by remember { mutableStateOf(false) }
    var showPermissions by remember { mutableStateOf(false) }
    var showPinSettings by remember { mutableStateOf(false) }
    var showArticles by remember { mutableStateOf(false) }
    var showCustomers by remember { mutableStateOf(false) }
    var showReports by remember { mutableStateOf(false) }
    var showOrders by remember { mutableStateOf(false) }
    var showCashDrawer by remember { mutableStateOf(false) }
    var showEstimates by remember { mutableStateOf(false) }
    var showSetupWizard by remember { mutableStateOf(false) }
    var showCustomizeMenu by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    var showChangeMode by remember { mutableStateOf(false) }
    var showAddons by remember { mutableStateOf(false) }
    var showKDS by remember { mutableStateOf(false) }

    // Get real version from PackageManager
    val context = LocalContext.current
    val versionName = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "Version ${pInfo.versionName} (${pInfo.longVersionCode})"
        } catch (_: PackageManager.NameNotFoundException) {
            "Avoqado v1.0.0"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AvoqadoTheme.spacing.lg),
    ) {
        // Header (matching iOS: "Más" title + venue subtitle)
        Text(
            text = "Mas",
            style = MaterialTheme.typography.displayMedium,
        )
        Text(
            text = venueName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        // Mode Selector Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showChangeMode = true }
                .padding(vertical = AvoqadoTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Modo: ${currentMode.displayName}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))

        // Venue Card (matching iOS: "Sucursal" + venue name + chevron)
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = viewModel.hasMultipleVenues && !isSwitching) {
                        showVenueSwitcher = true
                    }
                    .padding(AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Store,
                    contentDescription = null,
                    modifier = Modifier.size(AvoqadoTheme.dimensions.iconXLarge),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = AvoqadoTheme.spacing.md),
                ) {
                    Text(
                        text = "Sucursal",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = venueName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (viewModel.hasMultipleVenues) {
                        Text(
                            text = "Toca para cambiar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isSwitching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // General Section
        SectionHeader("General")
        MenuRow(
            icon = Icons.Filled.Schedule,
            label = "Reloj de entrada",
            onClick = { showTimeClock = true },
        )
        if (viewModel.canAccessReports) {
            MenuRow(
                icon = Icons.Filled.BarChart,
                label = "Informes",
                onClick = { showReports = true },
            )
        }
        MenuRow(
            icon = Icons.Filled.Description,
            label = "Pedidos",
            onClick = { showOrders = true },
        )
        MenuRow(
            icon = Icons.Filled.PointOfSale,
            label = "Caja",
            onClick = { showCashDrawer = true },
        )
        MenuRow(
            icon = Icons.Filled.Restaurant,
            label = "Pantalla de cocina",
            onClick = { showKDS = true },
        )
        if (viewModel.canCreateProducts) {
            MenuRow(
                icon = Icons.Filled.LocalOffer,
                label = "Articulos",
                onClick = { showArticles = true },
            )
        }
        MenuRow(
            icon = Icons.Filled.People,
            label = "Clientes",
            onClick = { showCustomers = true },
        )
        MenuRow(
            icon = Icons.Filled.Description,
            label = "Presupuestos",
            onClick = { showEstimates = true },
        )
        MenuRow(
            icon = Icons.Filled.Security,
            label = "Permisos",
            onClick = { showPermissions = true },
        )
        MenuRow(
            icon = Icons.Filled.Settings,
            label = "Configuracion PIN",
            onClick = { showPinSettings = true },
        )
        MenuRow(
            icon = Icons.AutoMirrored.Filled.PlaylistAddCheck,
            label = "Configuracion",
            onClick = { showSetupWizard = true },
        )
        MenuRow(
            icon = Icons.Filled.Dashboard,
            label = "Personalizar menu",
            onClick = { showCustomizeMenu = true },
        )
        MenuRow(
            icon = Icons.Filled.Extension,
            label = "Complementos",
            onClick = { showAddons = true },
        )
        MenuRow(
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            label = "Atencion al cliente",
            onClick = { showSupport = true },
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        // Hardware Section
        SectionHeader("Hardware")
        MenuRow(
            icon = Icons.Filled.Print,
            label = "Impresora",
            onClick = { showPrinter = true },
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxl))

        // Logout (matching iOS: underlined text, no icon, not red)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onLogout)
                .padding(vertical = AvoqadoTheme.spacing.lg),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Cerrar sesion $venueName",
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = TextDecoration.Underline,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        // Version (matching iOS: real version from bundle)
        Text(
            text = versionName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    // Articles Fullscreen Overlay (inside Box, stacks over the Column)
    if (showArticles) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            val isTablet = maxWidth >= 600.dp
            ArticlesScreen(
                isTablet = isTablet,
                onDismiss = { showArticles = false },
            )
        }
    }

    // Customers Fullscreen Overlay
    if (showCustomers) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            val isTablet = maxWidth >= 600.dp
            CustomersScreen(
                isTablet = isTablet,
                onDismiss = { showCustomers = false },
            )
        }
    }

    // Reports Fullscreen Overlay
    if (showReports) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            ReportsScreen(onDismiss = { showReports = false })
        }
    }

    // Orders Fullscreen Overlay
    if (showOrders) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            val isTablet = maxWidth >= 600.dp
            OrdersScreen(isTablet = isTablet, onDismiss = { showOrders = false })
        }
    }

    // Cash Drawer Fullscreen Overlay
    if (showCashDrawer) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            val isTablet = maxWidth >= 600.dp
            CashDrawerScreen(
                isTablet = isTablet,
                onDismiss = { showCashDrawer = false },
            )
        }
    }
    // Estimates Fullscreen Overlay
    if (showEstimates) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            val isTablet = maxWidth >= 600.dp
            EstimatesScreen(
                isTablet = isTablet,
                onDismiss = { showEstimates = false },
            )
        }
    }

    // Setup Wizard Fullscreen Overlay
    if (showSetupWizard) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            SetupWizardScreen(onDismiss = { showSetupWizard = false })
        }
    }

    // Support Fullscreen Overlay
    if (showSupport) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            val isTablet = maxWidth >= 600.dp
            SupportScreen(
                isTablet = isTablet,
                onDismiss = { showSupport = false },
            )
        }
    }

    // KDS Fullscreen Overlay
    if (showKDS) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            KDSScreen(onDismiss = { showKDS = false })
        }
    }

    // Addons Fullscreen Overlay
    if (showAddons) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            val isTablet = maxWidth >= 600.dp
            AddonsScreen(
                isTablet = isTablet,
                addonsManager = viewModel.addonsManager,
                onDismiss = { showAddons = false },
            )
        }
    }
    } // end Box

    // Customize Menu Sheet
    if (showCustomizeMenu) {
        CustomizeMenuSheet(onDismiss = { showCustomizeMenu = false })
    }

    // Venue Switcher Sheet
    if (showVenueSwitcher) {
        VenueSwitcherSheet(
            venues = viewModel.venuesList,
            currentVenueId = viewModel.currentVenueId,
            onVenueSelected = { venue ->
                viewModel.switchVenue(venue)
                showVenueSwitcher = false
            },
            onDismiss = { showVenueSwitcher = false },
        )
    }

    // Time Clock Sheet
    if (showTimeClock) {
        TimeClockSheet(
            repository = viewModel.timeEntryRepository,
            onDismiss = { showTimeClock = false },
        )
    }

    // Printer Settings Sheet
    if (showPrinter) {
        PrinterSettingsSheet(
            printerService = viewModel.printerService,
            onDismiss = { showPrinter = false },
        )
    }

    // Permissions Placeholder Sheet
    if (showPermissions) {
        PlaceholderSheet(
            title = "Permisos",
            message = "La gestion de permisos estara disponible proximamente.",
            onDismiss = { showPermissions = false },
        )
    }

    // PIN Settings Placeholder Sheet
    if (showPinSettings) {
        PlaceholderSheet(
            title = "Configuracion PIN",
            message = "La configuracion de PIN estara disponible proximamente.",
            onDismiss = { showPinSettings = false },
        )
    }

    // Change Mode Sheet
    if (showChangeMode) {
        ChangeModeSheet(
            posModeManager = viewModel.posModeManager,
            onDismiss = { showChangeMode = false },
        )
    }

}

// MARK: - Placeholder Sheet (for unimplemented features)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderSheet(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.xxxl))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = AvoqadoTheme.spacing.sm),
    )
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AvoqadoTheme.spacing.md),
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

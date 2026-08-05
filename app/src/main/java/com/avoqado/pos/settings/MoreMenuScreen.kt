package com.avoqado.pos.settings

import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.addons.presentation.AddonsScreen
import com.avoqado.pos.areatickets.presentation.AreaTicketDeliveryScreen
import com.avoqado.pos.articles.presentation.ArticlesScreen
import com.avoqado.pos.auth.presentation.VenueSwitcherSheet
import com.avoqado.pos.cashdrawer.presentation.CashDrawerScreen
import com.avoqado.pos.customers.presentation.CustomersScreen
import com.avoqado.pos.designsystem.components.TierBadge
import com.avoqado.pos.designsystem.theme.AvoqadoAdaptiveSizeClass
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.estimates.presentation.EstimatesScreen
import com.avoqado.pos.kds.presentation.KDSScreen
import com.avoqado.pos.orders.presentation.OrdersScreen
import com.avoqado.pos.printing.presentation.PrinterSettingsSheet
import com.avoqado.pos.reports.presentation.ReportsScreen
import com.avoqado.pos.reservations.domain.VenueMode
import com.avoqado.pos.settings.presentation.ChangeModeSheet
import com.avoqado.pos.settings.presentation.CustomerDisplaySheet
import com.avoqado.pos.settings.presentation.KioskSheet
import com.avoqado.pos.settings.presentation.CustomizeMenuSheet
import com.avoqado.pos.settings.presentation.SetupWizardScreen
import com.avoqado.pos.settings.presentation.SupportScreen
import com.avoqado.pos.timeclock.presentation.TimeClockSheet

@Composable
fun MoreMenuScreen(
    onLogout: () -> Unit,
    moreTabReselectionTick: Int = 0,
    onActivateReservations: () -> Unit = {},
    onOpenWaitlist: () -> Unit = {},
    onTabsShouldRefresh: () -> Unit = {},
    viewModel: MoreMenuViewModel = hiltViewModel(),
) {
    val venueName by viewModel.venueName.collectAsState()
    val isSwitching by viewModel.isSwitching.collectAsState()
    val sessionGuardMessage by viewModel.sessionGuardMessage.collectAsState()
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
    var showEndOfDay by remember { mutableStateOf(false) }
    var showEstimates by remember { mutableStateOf(false) }
    var showSetupWizard by remember { mutableStateOf(false) }
    var showCustomizeMenu by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    var showChangeMode by remember { mutableStateOf(false) }
    var showAddons by remember { mutableStateOf(false) }
    var showKDS by remember { mutableStateOf(false) }
    var showCustomerDisplay by remember { mutableStateOf(false) }
    var showAreaTicketDelivery by remember { mutableStateOf(false) }
    var showKiosk by remember { mutableStateOf(false) }
    val kioskEnabled by viewModel.kioskManager.enabled.collectAsState()
    val customerDisplayDetected by viewModel.customerDisplayState.isPresenting.collectAsState()
    val closeAllOverlays = {
        showVenueSwitcher = false
        showTimeClock = false
        showPrinter = false
        showPermissions = false
        showPinSettings = false
        showArticles = false
        showCustomers = false
        showReports = false
        showOrders = false
        showCashDrawer = false
        showEndOfDay = false
        showEstimates = false
        showSetupWizard = false
        showCustomizeMenu = false
        showSupport = false
        showChangeMode = false
        showAddons = false
        showAreaTicketDelivery = false
        showKDS = false
        showCustomerDisplay = false
        showKiosk = false
    }

    LaunchedEffect(moreTabReselectionTick) {
        if (moreTabReselectionTick > 0) {
            closeAllOverlays()
        }
    }

    // Get real version from PackageManager
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val versionName = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "Versión ${pInfo.versionName} (${PackageInfoCompat.getLongVersionCode(pInfo)})"
        } catch (_: PackageManager.NameNotFoundException) {
            "Avoqado v1.0.0"
        }
    }
    val adaptive = AvoqadoTheme.adaptive
    val lowDensityTabletFallback = configuration.densityDpi in 1..220 &&
        adaptive.sizeClass != AvoqadoAdaptiveSizeClass.Compact &&
        (!adaptive.isPortrait || configuration.screenWidthDp <= 900)
    val isSmallTablet = configuration.screenWidthDp >= 600 && lowDensityTabletFallback
    val denseMenu = adaptive.isAggressiveCompact
    val screenPadding = when {
        denseMenu -> AvoqadoTheme.spacing.sm
        isSmallTablet -> AvoqadoTheme.spacing.md
        else -> AvoqadoTheme.spacing.lg
    }
    val headerTitleStyle = when {
        denseMenu || isSmallTablet -> MaterialTheme.typography.headlineLarge
        else -> MaterialTheme.typography.displayMedium
    }
    val venueNameStyle = if (denseMenu) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    val modeStyle = when {
        denseMenu -> MaterialTheme.typography.bodyMedium
        isSmallTablet -> MaterialTheme.typography.bodyLarge
        else -> MaterialTheme.typography.bodyLarge
    }
    val modeRowPadding = if (denseMenu) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm
    val sectionGap = when {
        denseMenu -> AvoqadoTheme.spacing.lg
        isSmallTablet -> AvoqadoTheme.spacing.xl
        else -> AvoqadoTheme.spacing.xxl
    }
    val cardPadding = if (denseMenu) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg
    val venueTitleStyle = if (denseMenu) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(screenPadding),
    ) {
        // Header (matching iOS: "Más" title + venue subtitle)
        Text(
            text = "Más",
            style = headerTitleStyle,
        )
        Text(
            text = venueName,
            style = venueNameStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(if (denseMenu) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.lg))

        // El selector legacy Estándar/Reservas ya NO existe: "Reservas" es una
        // opción más del ÚNICO selector de modo (ChangeModeSheet) — antes había
        // dos "Modo" desincronizados en esta pantalla.

        if (isSmallTablet) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Card(
                    modifier = Modifier
                        .weight(0.35f)
                        .clickable { showChangeMode = true },
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs),
                    ) {
                        Text(
                            text = "Modo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = currentMode.displayName,
                                style = modeStyle,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(0.65f)
                        .clickable(enabled = viewModel.hasMultipleVenues && !isSwitching) {
                            showVenueSwitcher = true
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Store,
                            contentDescription = null,
                            modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = AvoqadoTheme.spacing.sm),
                        ) {
                            Text(
                                text = "Sucursal",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = venueName,
                                style = MaterialTheme.typography.titleSmall,
                            )
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
            }
        } else {
            // Mode Selector Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showChangeMode = true }
                    .padding(vertical = modeRowPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Modo: ${currentMode.displayName}",
                    style = modeStyle,
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

            Spacer(modifier = Modifier.height(if (denseMenu) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.md))

            // Venue Card (matching iOS: "Sucursal" + venue name + chevron)
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = viewModel.hasMultipleVenues && !isSwitching) {
                            showVenueSwitcher = true
                        }
                        .padding(cardPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Store,
                        contentDescription = null,
                        modifier = Modifier.size(if (denseMenu) AvoqadoTheme.dimensions.iconMedium else AvoqadoTheme.dimensions.iconXLarge),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = if (denseMenu) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md),
                    ) {
                        Text(
                            text = "Sucursal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = venueName,
                            style = venueTitleStyle,
                        )
                        if (viewModel.hasMultipleVenues) {
                            Text(
                                text = "Toca para cambiar",
                                style = if (denseMenu) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
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
        }

        Spacer(modifier = Modifier.height(sectionGap))

        // General Section
        SectionHeader("General", dense = denseMenu)
        if (!viewModel.reservationsEnabled) {
            // Visible teaser: the entry stays discoverable with a tier badge
            // when the plan lacks RESERVATIONS; tapping opens the Pro upsell.
            MenuRow(
                icon = Icons.Filled.CalendarMonth,
                label = "Activar reservas",
                subtitle = if (viewModel.reservationsRequireUpgrade) {
                    "Incluido en el Plan ${viewModel.reservationsTierLabel}"
                } else {
                    "Permite recibir citas. Gratis hoy."
                },
                tierBadgeLabel = if (viewModel.reservationsRequireUpgrade) {
                    viewModel.reservationsTierLabel
                } else {
                    null
                },
                onClick = onActivateReservations,
                dense = denseMenu,
            )
        }
        if (viewModel.reservationsEnabled) {
            MenuRow(
                icon = Icons.Filled.HourglassEmpty,
                label = "Lista de espera",
                onClick = onOpenWaitlist,
                dense = denseMenu,
            )
        }
        MenuRow(
            icon = Icons.Filled.Schedule,
            label = "Reloj checador",
            onClick = { showTimeClock = true },
            dense = denseMenu,
        )
        if (viewModel.canAccessReports) {
            MenuRow(
                icon = Icons.Filled.BarChart,
                label = "Informes",
                onClick = { showReports = true },
                dense = denseMenu,
            )
        }
        MenuRow(
            icon = Icons.Filled.Description,
            label = "Pedidos",
            onClick = { showOrders = true },
            dense = denseMenu,
        )
        MenuRow(
            icon = Icons.Filled.QrCodeScanner,
            label = "Entregas por área",
            subtitle = "Revisar papel o escanear comprobante pagado",
            onClick = { showAreaTicketDelivery = true },
            dense = denseMenu,
        )
        if (viewModel.canManageCashDrawer) {
            MenuRow(
                icon = Icons.Filled.PointOfSale,
                label = "Caja",
                onClick = { showCashDrawer = true },
                dense = denseMenu,
            )
            MenuRow(
                icon = Icons.Filled.Assignment,
                label = "Cierre del día",
                onClick = { showEndOfDay = true },
                dense = denseMenu,
            )
        }
        if (viewModel.canAccessKDS) {
            MenuRow(
                icon = Icons.Filled.Restaurant,
                label = "Pantalla de cocina",
                onClick = { showKDS = true },
                dense = denseMenu,
            )
        }
        if (viewModel.canCreateProducts) {
            MenuRow(
                icon = Icons.Filled.LocalOffer,
                label = "Artículos",
                onClick = { showArticles = true },
                dense = denseMenu,
            )
        }
        MenuRow(
            icon = Icons.Filled.People,
            label = "Clientes",
            onClick = { showCustomers = true },
            dense = denseMenu,
        )
        MenuRow(
            icon = Icons.Filled.Description,
            label = "Presupuestos",
            onClick = { showEstimates = true },
            dense = denseMenu,
        )
        MenuRow(
            icon = Icons.Filled.Security,
            label = "Permisos",
            onClick = { showPermissions = true },
            dense = denseMenu,
        )
        MenuRow(
            icon = Icons.Filled.Settings,
            label = "Configuración PIN",
            onClick = { showPinSettings = true },
            dense = denseMenu,
        )
        MenuRow(
            icon = Icons.AutoMirrored.Filled.PlaylistAddCheck,
            label = "Configuración",
            onClick = { showSetupWizard = true },
            dense = denseMenu,
        )
        MenuRow(
            icon = Icons.Filled.Dashboard,
            label = "Personalizar menú",
            onClick = { showCustomizeMenu = true },
            dense = denseMenu,
        )
        MenuRow(
            icon = Icons.Filled.Extension,
            label = "Complementos",
            onClick = { showAddons = true },
            dense = denseMenu,
        )
        MenuRow(
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            label = "Atención al cliente",
            onClick = { showSupport = true },
            dense = denseMenu,
        )

        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

        // Hardware Section
        SectionHeader("Hardware", dense = denseMenu)
        MenuRow(
            icon = Icons.Filled.Print,
            label = "Impresora",
            onClick = { showPrinter = true },
            dense = denseMenu,
        )
        MenuRow(
            icon = Icons.Filled.Lock,
            label = "Modo kiosco",
            subtitle = if (kioskEnabled) "Activado - la app está fijada" else "Salir de la app",
            onClick = { showKiosk = true },
            dense = denseMenu,
        )
        MenuRow(
            icon = Icons.Filled.Monitor,
            label = "Pantalla del cliente",
            subtitle = if (customerDisplayDetected) "Detectada" else "No detectada",
            onClick = { showCustomerDisplay = true },
            dense = denseMenu,
        )

        Spacer(modifier = Modifier.height(sectionGap))

        // Logout (matching iOS: underlined text, no icon, not red)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onLogout)
                .padding(vertical = AvoqadoTheme.spacing.lg),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Cerrar sesión $venueName",
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
        val overlayInteraction = remember { MutableInteractionSource() }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = overlayInteraction,
                    indication = null,
                    onClick = {},
                ),
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
        val overlayInteraction = remember { MutableInteractionSource() }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = overlayInteraction,
                    indication = null,
                    onClick = {},
                ),
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
        val overlayInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = overlayInteraction,
                    indication = null,
                    onClick = {},
                ),
        ) {
            ReportsScreen(onDismiss = { showReports = false })
        }
    }

    // Orders Fullscreen Overlay
    if (showOrders) {
        val overlayInteraction = remember { MutableInteractionSource() }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = overlayInteraction,
                    indication = null,
                    onClick = {},
                ),
        ) {
            val isTablet = maxWidth >= 600.dp
            OrdersScreen(isTablet = isTablet, onDismiss = { showOrders = false })
        }
    }

    // Cash Drawer Fullscreen Overlay
    if (showCashDrawer) {
        val overlayInteraction = remember { MutableInteractionSource() }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = overlayInteraction,
                    indication = null,
                    onClick = {},
                ),
        ) {
            val isTablet = maxWidth >= 600.dp
            CashDrawerScreen(
                isTablet = isTablet,
                onDismiss = { showCashDrawer = false },
            )
        }
    }
    // End of Day ("Cierre del día") Fullscreen Overlay
    if (showEndOfDay) {
        val eodInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = eodInteraction,
                    indication = null,
                    onClick = {},
                ),
        ) {
            com.avoqado.pos.cashdrawer.presentation.EndOfDayScreen(
                onDismiss = { showEndOfDay = false },
            )
        }
    }
    // Estimates Fullscreen Overlay
    if (showEstimates) {
        val overlayInteraction = remember { MutableInteractionSource() }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = overlayInteraction,
                    indication = null,
                    onClick = {},
                ),
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
        val overlayInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = overlayInteraction,
                    indication = null,
                    onClick = {},
                ),
        ) {
            SetupWizardScreen(onDismiss = { showSetupWizard = false })
        }
    }

    // Support Fullscreen Overlay
    if (showSupport) {
        val overlayInteraction = remember { MutableInteractionSource() }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = overlayInteraction,
                    indication = null,
                    onClick = {},
                ),
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
        val overlayInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = overlayInteraction,
                    indication = null,
                    onClick = {},
                ),
        ) {
            KDSScreen(onDismiss = { showKDS = false })
        }
    }

    // Addons Fullscreen Overlay
    if (showAddons) {
        val overlayInteraction = remember { MutableInteractionSource() }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = overlayInteraction,
                    indication = null,
                    onClick = {},
                ),
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

    if (showAreaTicketDelivery) {
        AreaTicketDeliveryScreen(onDismiss = { showAreaTicketDelivery = false })
    }

    sessionGuardMessage?.let { message ->
        com.avoqado.pos.designsystem.components.AvoqadoDialog(
            title = "Sincronización pendiente",
            description = message,
            onDismiss = viewModel::clearSessionGuard,
            dismissOnClickOutside = false,
            actionButton = {
                com.avoqado.pos.designsystem.components.PrimaryButton(
                    text = "Entendido",
                    onClick = viewModel::clearSessionGuard,
                    fullWidth = true,
                )
            },
        ) {}
    }

    // Venue Switcher Sheet
    if (showVenueSwitcher) {
        val cartItemCount by viewModel.activeCartState.itemCount.collectAsState()
        val cartTotal by viewModel.activeCartState.totalDisplay.collectAsState()

        VenueSwitcherSheet(
            venues = viewModel.venuesList,
            currentVenueId = viewModel.currentVenueId,
            hasCartItems = cartItemCount > 0,
            cartItemCount = cartItemCount,
            cartTotal = cartTotal,
            onVenueSelected = { venue ->
                viewModel.switchVenue(venue, onSwitched = onTabsShouldRefresh)
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
            message = "La gestión de permisos estará disponible próximamente.",
            onDismiss = { showPermissions = false },
        )
    }

    // PIN Settings Placeholder Sheet
    if (showPinSettings) {
        PlaceholderSheet(
            title = "Configuración PIN",
            message = "La configuración de PIN estará disponible próximamente.",
            onDismiss = { showPinSettings = false },
        )
    }

    // Kiosk Sheet (fijar la app + salida explícita)
    if (showKiosk) {
        KioskSheet(
            kiosk = viewModel.kioskManager,
            onDismiss = { showKiosk = false },
        )
    }

    // Customer Display Sheet (POS de doble pantalla)
    if (showCustomerDisplay) {
        CustomerDisplaySheet(
            prefs = viewModel.customerDisplayPrefs,
            displayState = viewModel.customerDisplayState,
            onDismiss = { showCustomerDisplay = false },
        )
    }

    // Change Mode Sheet
    if (showChangeMode) {
        ChangeModeSheet(
            posModeManager = viewModel.posModeManager,
            onDismiss = { showChangeMode = false },
            reservationsAvailable = viewModel.reservationsEnabled,
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
        com.avoqado.pos.designsystem.components.ImmersiveWindow()
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
    SectionHeader(title = title, dense = false)
}

@Composable
private fun SectionHeader(
    title: String,
    dense: Boolean,
) {
    Text(
        text = title,
            style = if (dense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = if (dense) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm),
        )
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    dense: Boolean = false,
    subtitle: String? = null,
    tierBadgeLabel: String? = null,
) {
    val verticalPadding = if (dense) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.md
    val rowTextStyle = if (dense) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    val iconSize = if (dense) AvoqadoTheme.dimensions.iconMedium else AvoqadoTheme.dimensions.iconLarge
    val chevronSize = if (dense) AvoqadoTheme.dimensions.iconMedium else AvoqadoTheme.dimensions.iconLarge

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = if (dense) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = rowTextStyle,
                )
                if (tierBadgeLabel != null) {
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                    TierBadge(tierLabel = tierBadgeLabel)
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(chevronSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

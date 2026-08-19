package com.avoqado.pos.settings

import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
// Set de CONTORNO en todo el menú: es el mismo peso que los SF Symbols de iOS,
// así que las dos apps dejan de sentirse distintas. Los `AutoMirrored` son los
// que el propio Material marca como tales — la variante `filled`/no-mirrored de
// ReceiptLong, FactCheck y HelpOutline está deprecada.
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.BarChart
// Edificio, no tienda: `Storefront` ya es el ícono del MODO Retail y en ese modo
// salían dos tiendas idénticas, una encima de la otra. Además empareja con el
// `building.2` que iOS ya usa para Sucursal.
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.addons.presentation.AddonsScreen
import com.avoqado.pos.areatickets.presentation.AreaTicketDeliveryScreen
import com.avoqado.pos.articles.presentation.ArticlesScreen
import com.avoqado.pos.auth.presentation.VenueSwitcherSheet
import com.avoqado.pos.cashdrawer.presentation.CashDrawerScreen
import com.avoqado.pos.core.util.findActivity
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
import com.avoqado.pos.settings.presentation.posModeIcon
import com.avoqado.pos.settings.presentation.CustomerDisplaySheet
import com.avoqado.pos.settings.presentation.ScreenPinningSheet
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
    // El rol es POR SUCURSAL: se colecta para que cambie solo al cambiar de
    // establecimiento, en vez de quedarse pegado al de la sucursal anterior.
    val roleLabel by viewModel.roleLabel.collectAsState()
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
    var showScreenPinning by remember { mutableStateOf(false) }
    val screenPinned by viewModel.screenPinningManager.enabled.collectAsState()
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
        showScreenPinning = false
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
    val sectionGap = when {
        denseMenu -> AvoqadoTheme.spacing.lg
        isSmallTablet -> AvoqadoTheme.spacing.xl
        else -> AvoqadoTheme.spacing.xxl
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
    // Columna de contenido acotada y centrada — espejo de iOS
    // (`MoreMenuView.maxContentWidth = 600`). Sin esto, en tablet horizontal
    // la fila se estira ~2000px y el chevron acaba a media pantalla del texto.
    Column(
        modifier = Modifier
            .widthIn(max = MoreMenuContentMaxWidth)
            .fillMaxWidth()
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

        // Identidad de la sesión: quién está adentro y con qué rol.
        //
        // Va PRIMERO, antes de Sucursal y Modo, porque es el contexto más grande
        // de los tres: sucursal y modo sólo significan algo una vez que sabes con
        // qué cuenta los estás mirando. Antes esta pantalla no lo decía en ningún
        // lado, así que una sesión de CASHIER olvidada de unas pruebas se veía
        // idéntica a la del dueño — y el menú recortado por rol parecía una app
        // rota, no un permiso.
        IdentityCard(
            name = viewModel.userDisplayName,
            email = viewModel.userEmail,
            roleLabel = roleLabel,
            dense = denseMenu,
        )

        Spacer(modifier = Modifier.height(if (denseMenu) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm))

        // El selector legacy Estándar/Reservas ya NO existe: "Reservas" es una
        // opción más del ÚNICO selector de modo (ChangeModeSheet) — antes había
        // dos "Modo" desincronizados en esta pantalla.

        if (isSmallTablet) {
            // Tablet chica de baja densidad: Sucursal y Modo comparten renglón
            // para no comerse el alto. Mismo lenguaje visual que la variante
            // apilada — sólo cambia el acomodo, no la piel.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Card(
                    modifier = Modifier
                        .weight(0.65f)
                        .clickable(enabled = viewModel.hasMultipleVenues && !isSwitching) {
                            showVenueSwitcher = true
                        },
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Business,
                                contentDescription = null,
                                modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
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
                                Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                                tint = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(0.35f)
                        .clickable { showChangeMode = true },
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                posModeIcon(currentMode),
                                contentDescription = null,
                                modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = AvoqadoTheme.spacing.sm),
                        ) {
                            Text(
                                text = "Modo",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = currentMode.displayName,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                            tint = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        } else {
            // Venue Card (matching iOS: "Sucursal" + venue name + chevron).
            // Va ARRIBA del modo: la sucursal es el contexto grande y el modo un
            // ajuste dentro de ella. Mismo orden en iOS.
            HeaderCard(
                icon = Icons.Outlined.Business,
                label = "Sucursal",
                value = venueName,
                hint = if (viewModel.hasMultipleVenues) "Toca para cambiar" else null,
                dense = denseMenu,
                enabled = viewModel.hasMultipleVenues && !isSwitching,
                onClick = { showVenueSwitcher = true },
                trailing = {
                    if (isSwitching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                            tint = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.height(if (denseMenu) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm))

            // El modo deja de ser texto suelto: misma tarjeta que Sucursal, para
            // que se lea como lo que es — un ajuste tocable, no un rótulo.
            HeaderCard(
                icon = posModeIcon(currentMode),
                label = "Modo",
                value = currentMode.displayName,
                hint = null,
                dense = denseMenu,
                enabled = true,
                onClick = { showChangeMode = true },
                trailing = {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                        tint = MaterialTheme.colorScheme.outlineVariant,
                    )
                },
            )
        }

        Spacer(modifier = Modifier.height(sectionGap))

        // Los grupos se arman como DATOS y no como Composables sueltos: así el
        // filtrado por rol/plan decide la MEMBRESÍA de cada tarjeta. Un grupo que
        // se queda sin filas no pinta encabezado ni tarjeta vacía, y cada fila
        // sabe si es la última — el divisor no se dibuja al ras del borde.
        // El orden y los nombres de grupo se espejan en iOS (`MainTabView.swift`).
        val operacion = buildList {
            if (viewModel.canAccessReports) {
                add(
                    MenuEntry(
                        icon = Icons.Outlined.BarChart,
                        label = "Informes",
                        onClick = { showReports = true },
                    ),
                )
            }
            add(
                MenuEntry(
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    label = "Pedidos",
                    onClick = { showOrders = true },
                ),
            )
            add(
                MenuEntry(
                    icon = Icons.Outlined.QrCodeScanner,
                    label = "Entregas por área",
                    subtitle = "Revisar papel o escanear comprobante pagado",
                    onClick = { showAreaTicketDelivery = true },
                ),
            )
            if (viewModel.canAccessKDS) {
                add(
                    MenuEntry(
                        icon = Icons.Outlined.LocalFireDepartment,
                        label = "Pantalla de cocina",
                        onClick = { showKDS = true },
                    ),
                )
            }
            if (viewModel.canManageCashDrawer) {
                add(
                    MenuEntry(
                        icon = Icons.Outlined.Payments,
                        label = "Caja",
                        onClick = { showCashDrawer = true },
                    ),
                )
                add(
                    MenuEntry(
                        icon = Icons.AutoMirrored.Outlined.FactCheck,
                        label = "Cierre del día",
                        onClick = { showEndOfDay = true },
                    ),
                )
            }
            if (viewModel.reservationsEnabled) {
                add(
                    MenuEntry(
                        icon = Icons.Outlined.HourglassEmpty,
                        label = "Lista de espera",
                        onClick = onOpenWaitlist,
                    ),
                )
            } else {
                // Visible teaser: the entry stays discoverable with a tier badge
                // when the plan lacks RESERVATIONS; tapping opens the Pro upsell.
                add(
                    MenuEntry(
                        icon = Icons.Outlined.CalendarMonth,
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
                    ),
                )
            }
            add(
                MenuEntry(
                    icon = Icons.Outlined.Schedule,
                    label = "Reloj checador",
                    onClick = { showTimeClock = true },
                ),
            )
        }

        val catalogo = buildList {
            if (viewModel.canCreateProducts) {
                add(
                    MenuEntry(
                        icon = Icons.Outlined.LocalOffer,
                        label = "Artículos",
                        onClick = { showArticles = true },
                    ),
                )
            }
            add(
                MenuEntry(
                    icon = Icons.Outlined.People,
                    label = "Clientes",
                    onClick = { showCustomers = true },
                ),
            )
            add(
                MenuEntry(
                    icon = Icons.Outlined.RequestQuote,
                    label = "Presupuestos",
                    onClick = { showEstimates = true },
                ),
            )
        }

        val ajustes = listOf(
            MenuEntry(
                icon = Icons.Outlined.AdminPanelSettings,
                label = "Permisos",
                onClick = { showPermissions = true },
            ),
            MenuEntry(
                icon = Icons.Outlined.Key,
                label = "Configuración PIN",
                onClick = { showPinSettings = true },
            ),
            MenuEntry(
                icon = Icons.Outlined.Checklist,
                label = "Configuración",
                onClick = { showSetupWizard = true },
            ),
            MenuEntry(
                icon = Icons.Outlined.GridView,
                label = "Personalizar menú",
                onClick = { showCustomizeMenu = true },
            ),
            MenuEntry(
                icon = Icons.Outlined.Extension,
                label = "Complementos",
                onClick = { showAddons = true },
            ),
            MenuEntry(
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                label = "Atención al cliente",
                onClick = { showSupport = true },
            ),
        )

        val hardware = listOf(
            MenuEntry(
                icon = Icons.Outlined.Print,
                label = "Impresora",
                onClick = { showPrinter = true },
            ),
            MenuEntry(
                icon = Icons.Outlined.Lock,
                label = "Esconder barras de Android",
                subtitle = if (screenPinned) "Activado - no se puede salir de la app" else "Salir de la app",
                onClick = { showScreenPinning = true },
            ),
            MenuEntry(
                icon = Icons.Outlined.Monitor,
                label = "Pantalla del cliente",
                subtitle = if (customerDisplayDetected) "Detectada" else "No detectada",
                onClick = { showCustomerDisplay = true },
            ),
        )

        MenuSection(title = "Operación", entries = operacion, dense = denseMenu)
        MenuSection(title = "Catálogo", entries = catalogo, dense = denseMenu)
        MenuSection(title = "Ajustes", entries = ajustes, dense = denseMenu)
        MenuSection(title = "Hardware", entries = hardware, dense = denseMenu)

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
    } // end columna de contenido acotada
    } // end columna con scroll

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

    // Esconder barras de Android (fijar la app + salida explícita)
    if (showScreenPinning) {
        ScreenPinningSheet(
            screenPinning = viewModel.screenPinningManager,
            onDismiss = { showScreenPinning = false },
        )
    }

    // Customer Display Sheet (POS de doble pantalla)
    if (showCustomerDisplay) {
        val carritoConItems by viewModel.activeCartState.itemCount.collectAsState()
        CustomerDisplaySheet(
            prefs = viewModel.customerDisplayPrefs,
            displayState = viewModel.customerDisplayState,
            displayModePrefs = viewModel.displayModePrefs,
            ventaEnCurso = carritoConItems > 0,
            onInvertedChange = { nuevo ->
                viewModel.cashierDisplayGuard.resetAttempts()
                viewModel.displayModePrefs.setInverted(nuevo)
                // findActivity() y no `context as? Activity`: el Context de
                // Compose viene envuelto, así que el cast daba null y el
                // interruptor se quedaba sin mover la caja — en silencio.
                val activity = context.findActivity()
                if (activity != null) {
                    viewModel.cashierDisplayGuard.enforce(activity)
                } else {
                    // No puede pasar (esta pantalla vive dentro de MainActivity),
                    // pero si pasara, el ajuste ya quedó guardado y la caja se
                    // colocaría sola en el próximo arranque: se deja rastro en
                    // vez de fallar mudo.
                    Log.w(
                        "🖥️CashierDisplay",
                        "Sin Activity detrás del Context: el modo se guardó pero la caja no se movió ahora",
                    )
                }
                showCustomerDisplay = false
                // Sin toast de éxito a propósito: enforce() relanza MainActivity
                // en la misma vuelta, así que la composición que lo mostraría
                // muere antes de pintarlo. La confirmación real es que TODO el
                // POS aparece en la otra pantalla — no hace falta adorno, y menos
                // uno que nunca se ve.
            },
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

/// Ancho máximo de la columna de contenido. Espejo exacto de iOS
/// (`MoreMenuView.maxContentWidth = 600`): en tablet horizontal el menú se lee
/// como una columna centrada, no como filas de 2000px con el chevron perdido.
private val MoreMenuContentMaxWidth = 600.dp

/// Tarjeta de identidad: con qué cuenta se inició sesión y con qué rol.
///
/// Comparte chrome exacto con [HeaderCard] (misma tarjeta, mismo chip, mismos
/// rótulos) para que se lea como parte del mismo sistema y no como un parche.
/// Se diferencia en dos cosas deliberadas: NO es tocable —hoy no hay pantalla de
/// cuenta a la que llevar, y una tarjeta que no responde al tacto enseña que no
/// hay nada detrás— y el rol viaja en una píldora propia, porque es el dato que
/// se viene a buscar y no puede quedar recortado detrás de un nombre largo.
///
/// 🔴 Nunca esconde un dato que falta: si no hay correo lo DICE. El modo de falla
/// que originó esta tarjeta fue justamente una app que callaba, y una identidad
/// a medias que se ve completa es peor que un hueco declarado.
@Composable
private fun IdentityCard(
    name: String?,
    email: String?,
    roleLabel: String?,
    dense: Boolean,
) {
    val padding = if (dense) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg
    // Mismo tamaño de chip que HeaderCard, vía tokens: las tres tarjetas del
    // encabezado tienen que alinear su columna de texto al pixel.
    val chipSize = if (dense) AvoqadoTheme.dimensions.buttonSmall else AvoqadoTheme.dimensions.touchTarget
    val initials = initialsFor(name, email)

    // El nombre manda; si no hay nombre, el correo sube a primario para no
    // repetirlo abajo. El correo faltante se declara, no se omite.
    val primary = name ?: email ?: "Cuenta sin nombre"
    val secondary = when {
        name != null -> email ?: "Correo no disponible"
        email != null -> null // ya está arriba
        else -> "Correo no disponible"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(chipSize)
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (initials != null) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Person,
                        contentDescription = null,
                        modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = if (dense) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md),
            ) {
                Text(
                    text = "Sesión iniciada",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // El nombre cede espacio y se recorta; la píldora del rol
                    // nunca — es el dato que se vino a buscar.
                    Text(
                        text = primary,
                        style = if (dense) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                    RolePill(roleLabel = roleLabel)
                }
                if (secondary != null) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/// El rol, en una píldora. Sin rol guardado lo dice con todas sus letras: el
/// server manda en permisos, y adivinar aquí un rol que la app no tiene sería
/// mentir sobre lo que la persona puede hacer.
@Composable
private fun RolePill(roleLabel: String?) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = AvoqadoTheme.spacing.sm, vertical = AvoqadoTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = roleLabel ?: "Rol no disponible",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (roleLabel != null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

/// Iniciales para el chip: del nombre si lo hay, si no la primera letra del
/// correo. Null → quien llama pinta el ícono genérico.
internal fun initialsFor(name: String?, email: String?): String? {
    val fromName = name.orEmpty()
        .split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull() }
        .joinToString("") { it.uppercaseChar().toString() }
    if (fromName.isNotBlank()) return fromName

    val fromEmail = email.orEmpty().trim().firstOrNull { it.isLetterOrDigit() }
    return fromEmail?.uppercaseChar()?.toString()
}

/// Tarjeta de encabezado (Sucursal / Modo): chip de ícono + rótulo + valor +
/// acción a la derecha. Las dos comparten forma a propósito — antes el modo era
/// texto suelto y no se leía como algo tocable.
@Composable
private fun HeaderCard(
    icon: ImageVector,
    label: String,
    value: String,
    hint: String?,
    dense: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val padding = if (dense) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg
    val chipSize = if (dense) 36.dp else 44.dp

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(padding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(chipSize)
                    .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.md))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = if (dense) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = if (dense) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                )
                if (hint != null) {
                    Text(
                        text = hint,
                        style = if (dense) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing()
        }
    }
}

/// Una entrada del menú. Se modela como dato para poder filtrar por rol/plan
/// ANTES de pintar: el grupo conoce su membresía final y por tanto sabe cuál es
/// su última fila (sin divisor) y si quedó vacío (no se pinta).
private data class MenuEntry(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val subtitle: String? = null,
    val tierBadgeLabel: String? = null,
)

@Composable
private fun MenuSection(
    title: String,
    entries: List<MenuEntry>,
    dense: Boolean,
) {
    // Un grupo sin filas no deja encabezado colgado ni tarjeta hueca.
    if (entries.isEmpty()) return

    SectionHeader(title = title, dense = dense)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.lg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, entry ->
                MenuRow(
                    entry = entry,
                    dense = dense,
                    showDivider = index != entries.lastIndex,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(if (dense) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg))
}

@Composable
private fun SectionHeader(
    title: String,
    dense: Boolean,
) {
    Text(
        text = title,
        style = if (dense) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(
            start = AvoqadoTheme.spacing.xxs,
            bottom = if (dense) AvoqadoTheme.spacing.xs else AvoqadoTheme.spacing.sm,
        ),
    )
}

@Composable
private fun MenuRow(
    entry: MenuEntry,
    dense: Boolean,
    showDivider: Boolean,
) {
    val verticalPadding = if (dense) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md
    val horizontalPadding = if (dense) AvoqadoTheme.spacing.md else AvoqadoTheme.spacing.lg
    val rowTextStyle = if (dense) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    val iconSize = if (dense) AvoqadoTheme.dimensions.iconMedium else AvoqadoTheme.dimensions.iconLarge
    val chevronSize = if (dense) AvoqadoTheme.dimensions.iconSmall else AvoqadoTheme.dimensions.iconMedium
    val gap = if (dense) AvoqadoTheme.spacing.sm else AvoqadoTheme.spacing.md
    // Sangría del divisor = padding + ancho fijo del ícono + hueco, para que
    // arranque bajo el texto y no de borde a borde (look de hoja de cálculo).
    val dividerInset = horizontalPadding + AvoqadoTheme.dimensions.iconLarge + gap

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = entry.onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Ancho fijo (no el tamaño del glifo) para que todas las etiquetas
        // arranquen en la misma columna, tengan el ícono que tengan.
        Box(
            modifier = Modifier.width(AvoqadoTheme.dimensions.iconLarge),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                entry.icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                // Jerarquía: el ícono acompaña al texto (espejo de iOS `.primary`),
                // el chevron se apaga. Antes los tres eran el mismo gris.
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = gap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.label,
                    style = rowTextStyle,
                )
                if (entry.tierBadgeLabel != null) {
                    Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
                    TierBadge(tierLabel = entry.tierBadgeLabel)
                }
            }
            if (entry.subtitle != null) {
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(chevronSize),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
    }
    if (showDivider) {
        HorizontalDivider(modifier = Modifier.padding(start = dividerInset))
    }
}

package com.avoqado.pos.navigation

import android.app.Activity
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.avoqado.pos.auth.presentation.AppState
import com.avoqado.pos.auth.presentation.LandingScreen
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.designsystem.components.ConnectivityBanner
import com.avoqado.pos.designsystem.components.AvoqadoBrandLoader
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.inventory.presentation.InventoryScreen
import com.avoqado.pos.notifications.presentation.NotificationsScreen
import com.avoqado.pos.pos.presentation.checkout.CheckoutScreen
import com.avoqado.pos.reservations.presentation.list.ReservationsListScreen
import com.avoqado.pos.settings.MoreMenuScreen
import com.avoqado.pos.timeclock.data.TimeEntryRepository
import com.avoqado.pos.timeclock.presentation.TimeClockSheet
import com.avoqado.pos.transactions.presentation.TransactionsScreen
import dagger.hilt.android.EntryPointAccessors
import java.time.format.DateTimeFormatter

// MARK: - Hilt entry point for singleton dependencies needed in NavGraph composables
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface FormatterEntryPoint {
    fun formatter(): VenueDateTimeFormatter
    fun errorNotifier(): com.avoqado.pos.core.data.network.ErrorNotifier
}

@Composable
fun AvoqadoNavGraph(
    windowSizeClass: WindowSizeClass,
    appState: AppState = hiltViewModel(),
) {
    val isLoggedIn by appState.isLoggedIn.collectAsState()
    val pendingPaymentCount by appState.pendingPaymentCount.collectAsState()
    val showOfflineBanner by appState.showOfflineBanner.collectAsState()
    val offlinePendingCount by appState.offlinePendingCount.collectAsState()
    val syncRejectedCount by appState.syncRejectedCount.collectAsState()
    val visibleTabs by appState.visibleTabs.collectAsState()
    val contentKey by appState.contentKey.collectAsState()
    val isSwitchingContext by appState.venueSwitchState.isSwitching.collectAsState()
    val switchMessage by appState.venueSwitchState.message.collectAsState()
    val isTablet = windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoggedIn) {
            // 🔴 Cambiar de sucursal o de modo RECREA todo (como Square): la
            // llave incluye venueId + modo, así que el NavHost y cada pantalla
            // montan de cero y recargan datos del contexto NUEVO. Antes las
            // pantallas ya montadas seguían con los datos del venue anterior.
            key(contentKey) {
                MainScaffold(
                    isTablet = isTablet,
                    onLogout = { appState.onLogout() },
                    timeEntryRepository = appState.timeEntryRepository,
                    visibleTabs = visibleTabs,
                    roleManager = appState.roleManager,
                    pendingPaymentCount = pendingPaymentCount,
                    showOfflineBanner = showOfflineBanner,
                    offlinePendingCount = offlinePendingCount,
                    syncRejectedCount = syncRejectedCount,
                    onTabsShouldRefresh = { appState.refreshTabs() },
                )
            }
        } else {
            LandingScreen(
                onLoginSuccess = { appState.onLoginSuccess() },
            )
        }

        // Loader global del cambio de contexto — vive ARRIBA del NavHost para
        // sobrevivir al rebuild que el propio cambio detona.
        if (isSwitchingContext) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(AvoqadoTheme.cornerRadius.xl),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(AvoqadoTheme.spacing.xxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AvoqadoBrandLoader(size = 72.dp)
                        Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))
                        Text(
                            text = switchMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScaffold(
    isTablet: Boolean,
    onLogout: () -> Unit,
    timeEntryRepository: TimeEntryRepository,
    visibleTabs: List<MainTab>,
    roleManager: RoleManager,
    pendingPaymentCount: Int = 0,
    showOfflineBanner: Boolean = false,
    offlinePendingCount: Int = 0,
    syncRejectedCount: Int = 0,
    onTabsShouldRefresh: () -> Unit = {},
) {
    // Status bar icons: follow theme (light icons on dark, dark icons on light)
    val view = LocalView.current
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    // El cobro se abre en un Dialog que NO cubre la pantalla completa: sin esto
    // el tab bar asomaba por debajo y era tocable a media transacción.
    val isPaying by com.avoqado.pos.payment.domain.PaymentOverlayState.isPaying.collectAsState()
    val startTab = visibleTabs.firstOrNull() ?: MainTab.NOTIFICATIONS

    // Cuarentena de sincronización: hoja de resolución de rechazos.
    var showQuarantineSheet by remember { mutableStateOf(false) }
    if (showQuarantineSheet) {
        com.avoqado.pos.sync.presentation.QuarantineSheet(onDismissSheet = { showQuarantineSheet = false })
    }

    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            FormatterEntryPoint::class.java,
        )
    }
    val formatter = remember { entryPoint.formatter() }
    // 403/RBAC denials used to be invisible (the button just no-op'd). Surface
    // them as a toast at the app root.
    val errorNotifier = remember { entryPoint.errorNotifier() }
    val forbiddenError by errorNotifier.forbiddenError.collectAsState()
    LaunchedEffect(forbiddenError) {
        forbiddenError?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            errorNotifier.clear()
        }
    }

    fun navigateToTab(tab: MainTab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    var showTimeClock by remember { mutableStateOf(false) }
    var moreTabReselectionTick by remember { mutableIntStateOf(0) }

    if (isTablet) {
        // iPad-style: custom capsule tab bar at bottom.
        // 🔴 DERIVADO de la navegación real (como ya hacía la barra de teléfono):
        // con un `remember` paralelo, cambiar de modo/sucursal navegaba al tab
        // nuevo pero la píldora se quedaba en el tab viejo ("Más" resaltado
        // sobre la pantalla de Mesas).
        val selectedTab = visibleTabs.firstOrNull { tab ->
            currentDestination?.hierarchy?.any { it.route == tab.route } == true
        } ?: startTab

        Scaffold(
            contentWindowInsets = WindowInsets.statusBars,
            topBar = {
                androidx.compose.foundation.layout.Column {
                    ConnectivityBanner(visible = showOfflineBanner, pendingSync = offlinePendingCount)
                    com.avoqado.pos.sync.presentation.QuarantineBanner(count = syncRejectedCount) { showQuarantineSheet = true }
                }
            },
            bottomBar = {
                // Durante el cobro el tab bar se esconde: el diálogo del pago no
                // cubre la pantalla completa y este quedaba asomando y tocable.
                if (!isPaying) TabletTabBar(
                    visibleTabs = visibleTabs,
                    selectedTab = selectedTab,
                    onTabSelected = { tab ->
                        if (tab == MainTab.MORE && selectedTab == MainTab.MORE) {
                            moreTabReselectionTick++
                        }
                        navigateToTab(tab)
                    },
                    onClockTap = { showTimeClock = true },
                    pendingPaymentCount = pendingPaymentCount,
                )
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = startTab.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                if (roleManager.canAccessPOS) {
                    composable(MainTab.CHECKOUT.route) { CheckoutScreen(isTablet = true, roleManager = roleManager) }
                    composable(MainTab.TABLES.route) {
                        com.avoqado.pos.tables.presentation.TablesScreen(
                            // Same save/restore discipline the bottom bar uses —
                            // a plain navigate() here corrupts the per-tab stacks.
                            onGoToCheckout = { navigateToTab(MainTab.CHECKOUT) },
                            onOpenTableOrder = { navController.navigate("table-order") },
                        )
                    }
                    composable("table-order") {
                        com.avoqado.pos.tables.presentation.TableOrderScreen(
                            isTablet = true,
                            onExit = { navController.popBackStack() },
                            onPagar = {
                                navController.popBackStack()
                                navigateToTab(MainTab.CHECKOUT)
                            },
                        )
                    }
                }
                if (roleManager.canAccessInventory) {
                    composable(MainTab.INVENTORY.route) { InventoryScreen(isTablet = true) }
                }
                if (roleManager.canAccessTransactions) {
                    composable(MainTab.TRANSACTIONS.route) { TransactionsScreen(isTablet = true) }
                }
                composable(MainTab.NOTIFICATIONS.route) { NotificationsScreen() }
                composable(MainTab.MORE.route) {
                    MoreMenuScreen(
                        onLogout = onLogout,
                        moreTabReselectionTick = moreTabReselectionTick,
                        onActivateReservations = { navController.navigate("activate-reservations") },
                        onOpenWaitlist = { navController.navigate("waitlist") },
                        onTabsShouldRefresh = onTabsShouldRefresh,
                    )
                }
                composable(MainTab.CALENDAR.route) {
                    com.avoqado.pos.reservations.presentation.calendar.CalendarTabHost(
                        onOpenReservation = { id -> navController.navigate("reservations/$id") },
                        onOpenClassSession = { id -> navController.navigate("class-sessions/$id") },
                        onOpenSettings = { navController.navigate("calendar/settings") },
                        onCreateReservation = { date, time, walkin ->
                            val timeStr = time.format(DateTimeFormatter.ofPattern("HH:mm"))
                            navController.navigate(
                                "reservations/create?date=$date&time=$timeStr&walkin=$walkin",
                            )
                        },
                        onCreateClassSession = { date, time ->
                            val timeStr = time.format(DateTimeFormatter.ofPattern("HH:mm"))
                            navController.navigate("class-sessions/create?date=$date&time=$timeStr")
                        },
                    )
                }
                composable("calendar/settings") {
                    val parentEntry = remember(it) {
                        navController.getBackStackEntry(MainTab.CALENDAR.route)
                    }
                    com.avoqado.pos.reservations.presentation.calendar.CalendarSettingsSheet(
                        onClose = { navController.popBackStack() },
                        viewModel = hiltViewModel(parentEntry),
                    )
                }
                composable("activate-reservations") {
                    com.avoqado.pos.reservations.presentation.onboarding.ActivateReservationsScreen(
                        onActivated = {
                            onTabsShouldRefresh()
                            navController.popBackStack()
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("waitlist") {
                    com.avoqado.pos.reservations.presentation.waitlist.WaitlistScreen(
                        onClose = { navController.popBackStack() },
                        onPromote = { entry ->
                            val params = buildString {
                                append("reservations/create?promoteWaitlistId=").append(entry.id)
                                if (entry.customerId != null) {
                                    append("&prefillCustomerId=").append(entry.customerId)
                                } else if (!entry.guestName.isNullOrBlank()) {
                                    append("&prefillGuestName=").append(
                                        encodeNavArg(entry.guestName),
                                    )
                                }
                                append("&prefillPartySize=").append(entry.partySize)
                                append("&prefillStart=").append(
                                    encodeNavArg(entry.desiredStartAt),
                                )
                            }
                            navController.navigate(params)
                        },
                    )
                }
                composable("reservations/list") {
                    ReservationsListScreen(
                        onOpenDetail = { id -> navController.navigate("reservations/$id") },
                        formatter = formatter,
                    )
                }
                composable(
                    route = "reservations/create?date={date}&time={time}&walkin={walkin}&promoteWaitlistId={promoteWaitlistId}&prefillCustomerId={prefillCustomerId}&prefillGuestName={prefillGuestName}&prefillPartySize={prefillPartySize}&prefillStart={prefillStart}",
                    arguments = listOf(
                        navArgument("date") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("time") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("walkin") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = "false"
                        },
                        navArgument("promoteWaitlistId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("prefillCustomerId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("prefillGuestName") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("prefillPartySize") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("prefillStart") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) {
                    com.avoqado.pos.reservations.presentation.create.CreateReservationScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(
                    route = "class-sessions/create?date={date}&time={time}",
                    arguments = listOf(
                        navArgument("date") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("time") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) {
                    com.avoqado.pos.reservations.presentation.classsessions.CreateClassSessionScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(
                    "class-sessions/{sessionId}",
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                    com.avoqado.pos.reservations.presentation.classsessions.ClassSessionDetailScreen(
                        onClose = { navController.popBackStack() },
                        onEdit = { navController.navigate("class-sessions/$id/edit") },
                        onChargeSeeded = { navigateToTab(MainTab.CHECKOUT) },
                    )
                }
                composable(
                    "class-sessions/{sessionId}/edit",
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                ) {
                    com.avoqado.pos.reservations.presentation.classsessions.ClassSessionEditScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(
                    "reservations/{reservationId}",
                    arguments = listOf(navArgument("reservationId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("reservationId") ?: return@composable
                    com.avoqado.pos.reservations.presentation.detail.ReservationDetailScreen(
                        onClose = { navController.popBackStack() },
                        onEdit = { navController.navigate("reservations/edit/$id") },
                        onReschedule = { navController.navigate("reservations/$id/reschedule") },
                        onCancel = { navController.navigate("reservations/$id/cancel") },
                        formatter = formatter,
                    )
                }
                composable(
                    route = "reservations/edit/{editingId}",
                    arguments = listOf(navArgument("editingId") { type = NavType.StringType }),
                ) {
                    com.avoqado.pos.reservations.presentation.create.CreateReservationScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(
                    "reservations/{reservationId}/cancel",
                    arguments = listOf(navArgument("reservationId") { type = NavType.StringType }),
                ) {
                    com.avoqado.pos.reservations.presentation.detail.CancelReservationSheet(
                        onDismiss = { navController.popBackStack() },
                    )
                }
                composable(
                    "reservations/{reservationId}/reschedule",
                    arguments = listOf(navArgument("reservationId") { type = NavType.StringType }),
                ) {
                    val tz = remember(formatter) { formatter.zoneId() }
                    com.avoqado.pos.reservations.presentation.detail.RescheduleSheet(
                        venueTimezone = tz,
                        onDismiss = { navController.popBackStack() },
                    )
                }
            }
        }
    } else {
        // iPhone-style: standard NavigationBar
        Scaffold(
            topBar = {
                androidx.compose.foundation.layout.Column {
                    ConnectivityBanner(visible = showOfflineBanner, pendingSync = offlinePendingCount)
                    com.avoqado.pos.sync.presentation.QuarantineBanner(count = syncRejectedCount) { showQuarantineSheet = true }
                }
            },
            bottomBar = {
                // Mismo criterio que el layout de tablet: durante el cobro no se
                // muestra, porque asoma por debajo del diálogo del pago.
                if (!isPaying) NavigationBar {
                    visibleTabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (tab == MainTab.MORE && selected) {
                                    moreTabReselectionTick++
                                }
                                navigateToTab(tab)
                            },
                            icon = {
                                val icon = if (selected) tab.selectedIcon else tab.unselectedIcon
                                when {
                                    tab == MainTab.CHECKOUT && pendingPaymentCount > 0 -> {
                                        BadgedBox(badge = { Badge { Text("$pendingPaymentCount") } }) {
                                            Icon(icon, contentDescription = tab.label)
                                        }
                                    }
                                    tab == MainTab.NOTIFICATIONS -> {
                                        Icon(icon, contentDescription = tab.label)
                                    }
                                    else -> {
                                        Icon(icon, contentDescription = tab.label)
                                    }
                                }
                            },
                            label = { Text(tab.shortLabel) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = startTab.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                if (roleManager.canAccessPOS) {
                    composable(MainTab.CHECKOUT.route) { CheckoutScreen(isTablet = false, roleManager = roleManager) }
                    composable(MainTab.TABLES.route) {
                        com.avoqado.pos.tables.presentation.TablesScreen(
                            // Same save/restore discipline the bottom bar uses —
                            // a plain navigate() here corrupts the per-tab stacks.
                            onGoToCheckout = { navigateToTab(MainTab.CHECKOUT) },
                            onOpenTableOrder = { navController.navigate("table-order") },
                        )
                    }
                    composable("table-order") {
                        com.avoqado.pos.tables.presentation.TableOrderScreen(
                            isTablet = false,
                            onExit = { navController.popBackStack() },
                            onPagar = {
                                navController.popBackStack()
                                navigateToTab(MainTab.CHECKOUT)
                            },
                        )
                    }
                }
                if (roleManager.canAccessInventory) {
                    composable(MainTab.INVENTORY.route) { InventoryScreen(isTablet = false) }
                }
                if (roleManager.canAccessTransactions) {
                    composable(MainTab.TRANSACTIONS.route) { TransactionsScreen() }
                }
                composable(MainTab.NOTIFICATIONS.route) { NotificationsScreen() }
                composable(MainTab.MORE.route) {
                    MoreMenuScreen(
                        onLogout = onLogout,
                        moreTabReselectionTick = moreTabReselectionTick,
                        onActivateReservations = { navController.navigate("activate-reservations") },
                        onOpenWaitlist = { navController.navigate("waitlist") },
                        onTabsShouldRefresh = onTabsShouldRefresh,
                    )
                }
                composable(MainTab.CALENDAR.route) {
                    com.avoqado.pos.reservations.presentation.calendar.CalendarTabHost(
                        onOpenReservation = { id -> navController.navigate("reservations/$id") },
                        onOpenClassSession = { id -> navController.navigate("class-sessions/$id") },
                        onOpenSettings = { navController.navigate("calendar/settings") },
                        onCreateReservation = { date, time, walkin ->
                            val timeStr = time.format(DateTimeFormatter.ofPattern("HH:mm"))
                            navController.navigate(
                                "reservations/create?date=$date&time=$timeStr&walkin=$walkin",
                            )
                        },
                        onCreateClassSession = { date, time ->
                            val timeStr = time.format(DateTimeFormatter.ofPattern("HH:mm"))
                            navController.navigate("class-sessions/create?date=$date&time=$timeStr")
                        },
                    )
                }
                composable("calendar/settings") {
                    val parentEntry = remember(it) {
                        navController.getBackStackEntry(MainTab.CALENDAR.route)
                    }
                    com.avoqado.pos.reservations.presentation.calendar.CalendarSettingsSheet(
                        onClose = { navController.popBackStack() },
                        viewModel = hiltViewModel(parentEntry),
                    )
                }
                composable("activate-reservations") {
                    com.avoqado.pos.reservations.presentation.onboarding.ActivateReservationsScreen(
                        onActivated = {
                            onTabsShouldRefresh()
                            navController.popBackStack()
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("waitlist") {
                    com.avoqado.pos.reservations.presentation.waitlist.WaitlistScreen(
                        onClose = { navController.popBackStack() },
                        onPromote = { entry ->
                            val params = buildString {
                                append("reservations/create?promoteWaitlistId=").append(entry.id)
                                if (entry.customerId != null) {
                                    append("&prefillCustomerId=").append(entry.customerId)
                                } else if (!entry.guestName.isNullOrBlank()) {
                                    append("&prefillGuestName=").append(
                                        encodeNavArg(entry.guestName),
                                    )
                                }
                                append("&prefillPartySize=").append(entry.partySize)
                                append("&prefillStart=").append(
                                    encodeNavArg(entry.desiredStartAt),
                                )
                            }
                            navController.navigate(params)
                        },
                    )
                }
                composable("reservations/list") {
                    ReservationsListScreen(
                        onOpenDetail = { id -> navController.navigate("reservations/$id") },
                        formatter = formatter,
                    )
                }
                composable(
                    route = "reservations/create?date={date}&time={time}&walkin={walkin}&promoteWaitlistId={promoteWaitlistId}&prefillCustomerId={prefillCustomerId}&prefillGuestName={prefillGuestName}&prefillPartySize={prefillPartySize}&prefillStart={prefillStart}",
                    arguments = listOf(
                        navArgument("date") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("time") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("walkin") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = "false"
                        },
                        navArgument("promoteWaitlistId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("prefillCustomerId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("prefillGuestName") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("prefillPartySize") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("prefillStart") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) {
                    com.avoqado.pos.reservations.presentation.create.CreateReservationScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(
                    route = "class-sessions/create?date={date}&time={time}",
                    arguments = listOf(
                        navArgument("date") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("time") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) {
                    com.avoqado.pos.reservations.presentation.classsessions.CreateClassSessionScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(
                    "class-sessions/{sessionId}",
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                    com.avoqado.pos.reservations.presentation.classsessions.ClassSessionDetailScreen(
                        onClose = { navController.popBackStack() },
                        onEdit = { navController.navigate("class-sessions/$id/edit") },
                        onChargeSeeded = { navigateToTab(MainTab.CHECKOUT) },
                    )
                }
                composable(
                    "class-sessions/{sessionId}/edit",
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                ) {
                    com.avoqado.pos.reservations.presentation.classsessions.ClassSessionEditScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(
                    "reservations/{reservationId}",
                    arguments = listOf(navArgument("reservationId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("reservationId") ?: return@composable
                    com.avoqado.pos.reservations.presentation.detail.ReservationDetailScreen(
                        onClose = { navController.popBackStack() },
                        onEdit = { navController.navigate("reservations/edit/$id") },
                        onReschedule = { navController.navigate("reservations/$id/reschedule") },
                        onCancel = { navController.navigate("reservations/$id/cancel") },
                        formatter = formatter,
                    )
                }
                composable(
                    route = "reservations/edit/{editingId}",
                    arguments = listOf(navArgument("editingId") { type = NavType.StringType }),
                ) {
                    com.avoqado.pos.reservations.presentation.create.CreateReservationScreen(
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(
                    "reservations/{reservationId}/cancel",
                    arguments = listOf(navArgument("reservationId") { type = NavType.StringType }),
                ) {
                    com.avoqado.pos.reservations.presentation.detail.CancelReservationSheet(
                        onDismiss = { navController.popBackStack() },
                    )
                }
                composable(
                    "reservations/{reservationId}/reschedule",
                    arguments = listOf(navArgument("reservationId") { type = NavType.StringType }),
                ) {
                    val tz = remember(formatter) { formatter.zoneId() }
                    com.avoqado.pos.reservations.presentation.detail.RescheduleSheet(
                        venueTimezone = tz,
                        onDismiss = { navController.popBackStack() },
                    )
                }
            }
        }
    }

    // TimeClockSheet overlay
    if (showTimeClock) {
        TimeClockSheet(
            repository = timeEntryRepository,
            onDismiss = { showTimeClock = false },
        )
    }
}

// MARK: - iPad Custom Tab Bar (matching iOS)

@Composable
private fun TabletTabBar(
    visibleTabs: List<MainTab>,
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    onClockTap: () -> Unit,
    pendingPaymentCount: Int = 0,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            // 🔴 SIN navigationBarsPadding a propósito. La app esconde el navbar
            // (modo inmersivo), pero el sistema lo muestra TRANSITORIO al pasar
            // el mouse por la orilla — y con ese padding la barra de pestañas
            // saltaba hacia arriba justo debajo del cursor: el cajero apunta a
            // un botón y le cambia de lugar al tocarlo. La barra transitoria se
            // superpone unos segundos y se va sola; mover la interfaz es peor.
            .padding(horizontal = AvoqadoTheme.spacing.lg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AvoqadoTheme.dimensions.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // El área táctil conserva 44dp, pero el círculo visible baja a 36dp
            // para mantener compacta la barra.
            Box(
                modifier = Modifier
                    .size(AvoqadoTheme.dimensions.touchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onClockTap),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(AvoqadoTheme.dimensions.buttonSmall)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = "Reloj checador",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                    )
                }
            }

            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
            Box(
                modifier = Modifier
                    .width(0.5.dp)
                    .height(AvoqadoTheme.dimensions.buttonSmall)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))

            // Tabs LIBRES sobre la barra, a todo el ancho restante y
            // distribuidos uniformemente — Square no encierra sus tabs en una
            // cápsula; solo el seleccionado lleva pastilla.
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visibleTabs.forEach { tab ->
                    val isSelected = selectedTab == tab
                    TabBarItem(
                        tab = tab,
                        isSelected = isSelected,
                        onClick = { onTabSelected(tab) },
                        badgeCount = if (tab == MainTab.CHECKOUT) pendingPaymentCount else 0,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabBarItem(
    tab: MainTab,
    isSelected: Boolean,
    onClick: () -> Unit,
    badgeCount: Int = 0,
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
        animationSpec = tween(durationMillis = 150),
        label = "tab_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 150),
        label = "tab_content",
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .height(AvoqadoTheme.dimensions.touchTarget)
            .widthIn(min = 72.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(AvoqadoTheme.dimensions.buttonSmall)
                .clip(RoundedCornerShape(50))
                .background(bgColor)
                .padding(horizontal = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (badgeCount > 0) {
                BadgedBox(badge = { Badge { Text("$badgeCount") } }) {
                    Icon(
                        imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.label,
                        tint = contentColor,
                        modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                    )
                }
            } else {
                Icon(
                    imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                    contentDescription = tab.label,
                    tint = contentColor,
                    modifier = Modifier.size(AvoqadoTheme.dimensions.iconMedium),
                )
            }

            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.xs))
            Text(
                text = tab.shortLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Encodes a value for use as a Compose Navigation query argument.
 *
 * Java's [java.net.URLEncoder] is form-urlencoded (`application/x-www-form-urlencoded`) and
 * encodes spaces as `+`, but Compose Navigation parses query strings per RFC 3986 — meaning a
 * literal `+` is preserved verbatim instead of being decoded back to a space. The result was
 * names like "Test+Waitlist+Fix" leaking into the create flow when promoting waitlist entries.
 *
 * Replacing `+` with `%20` after URLEncoder gives RFC 3986–compliant percent-encoding so the
 * receiver decodes spaces back to spaces.
 */
private fun encodeNavArg(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

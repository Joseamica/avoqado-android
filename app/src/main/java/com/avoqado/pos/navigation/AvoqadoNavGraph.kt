package com.avoqado.pos.navigation

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.defaultMinSize
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

// MARK: - Hilt entry point for singleton dependencies needed in NavGraph composables
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface FormatterEntryPoint {
    fun formatter(): VenueDateTimeFormatter
}

@Composable
fun AvoqadoNavGraph(
    windowSizeClass: WindowSizeClass,
    appState: AppState = hiltViewModel(),
) {
    val isLoggedIn by appState.isLoggedIn.collectAsState()
    val pendingPaymentCount by appState.pendingPaymentCount.collectAsState()
    val showOfflineBanner by appState.showOfflineBanner.collectAsState()
    val isTablet = windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium

    if (isLoggedIn) {
        MainScaffold(
            isTablet = isTablet,
            onLogout = { appState.onLogout() },
            timeEntryRepository = appState.timeEntryRepository,
            visibleTabs = appState.visibleTabs,
            roleManager = appState.roleManager,
            pendingPaymentCount = pendingPaymentCount,
            showOfflineBanner = showOfflineBanner,
        )
    } else {
        LandingScreen(
            onLoginSuccess = { appState.onLoginSuccess() },
        )
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
    val startTab = visibleTabs.firstOrNull() ?: MainTab.NOTIFICATIONS

    val context = LocalContext.current
    val formatter = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            FormatterEntryPoint::class.java,
        ).formatter()
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
        // iPad-style: custom capsule tab bar at bottom
        var selectedTab by remember { mutableStateOf(startTab) }

        Scaffold(
            contentWindowInsets = WindowInsets.statusBars,
            topBar = { ConnectivityBanner(visible = showOfflineBanner) },
            bottomBar = {
                TabletTabBar(
                    visibleTabs = visibleTabs,
                    selectedTab = selectedTab,
                    onTabSelected = { tab ->
                        if (tab == MainTab.MORE && selectedTab == MainTab.MORE) {
                            moreTabReselectionTick++
                        }
                        selectedTab = tab
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
                    )
                }
                composable(MainTab.CALENDAR.route) {
                    com.avoqado.pos.reservations.presentation.calendar.CalendarTabHost(
                        onOpenReservation = { id -> navController.navigate("reservations/$id") },
                        onOpenSettings = { navController.navigate("calendar/settings") },
                    )
                }
                composable("calendar/settings") {
                    com.avoqado.pos.reservations.presentation.calendar.CalendarSettingsSheet(
                        onClose = { navController.popBackStack() },
                    )
                }
                composable("activate-reservations") {
                    com.avoqado.pos.reservations.presentation.onboarding.ActivateReservationsScreen(
                        onActivated = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("reservations/list") {
                    ReservationsListScreen(
                        onOpenDetail = { id -> navController.navigate("reservations/$id") },
                        formatter = formatter,
                    )
                }
                composable(
                    "reservations/{reservationId}",
                    arguments = listOf(navArgument("reservationId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("reservationId") ?: return@composable
                    com.avoqado.pos.reservations.presentation.detail.ReservationDetailScreen(
                        onClose = { navController.popBackStack() },
                        onReschedule = { navController.navigate("reservations/$id/reschedule") },
                        onCancel = { navController.navigate("reservations/$id/cancel") },
                        formatter = formatter,
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
            topBar = { ConnectivityBanner(visible = showOfflineBanner) },
            bottomBar = {
                NavigationBar {
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
                    )
                }
                composable(MainTab.CALENDAR.route) {
                    com.avoqado.pos.reservations.presentation.calendar.CalendarTabHost(
                        onOpenReservation = { id -> navController.navigate("reservations/$id") },
                        onOpenSettings = { navController.navigate("calendar/settings") },
                    )
                }
                composable("calendar/settings") {
                    com.avoqado.pos.reservations.presentation.calendar.CalendarSettingsSheet(
                        onClose = { navController.popBackStack() },
                    )
                }
                composable("activate-reservations") {
                    com.avoqado.pos.reservations.presentation.onboarding.ActivateReservationsScreen(
                        onActivated = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("reservations/list") {
                    ReservationsListScreen(
                        onOpenDetail = { id -> navController.navigate("reservations/$id") },
                        formatter = formatter,
                    )
                }
                composable(
                    "reservations/{reservationId}",
                    arguments = listOf(navArgument("reservationId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("reservationId") ?: return@composable
                    com.avoqado.pos.reservations.presentation.detail.ReservationDetailScreen(
                        onClose = { navController.popBackStack() },
                        onReschedule = { navController.navigate("reservations/$id/reschedule") },
                        onCancel = { navController.navigate("reservations/$id/cancel") },
                        formatter = formatter,
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
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.sm),
    ) {
        // Top border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outline)
                .align(Alignment.TopCenter),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Clock button (left)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onClockTap),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = "Reloj checador",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(AvoqadoTheme.dimensions.iconLarge),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Center tabs capsule (iOS-style)
            Row(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        RoundedCornerShape(50),
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(50),
                    )
                    .padding(AvoqadoTheme.spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.xxs),
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

            Spacer(modifier = Modifier.weight(1f))

            // Balance spacer (right) - same size as clock button
            Spacer(modifier = Modifier.size(44.dp))
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
        targetValue = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
        label = "tab_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "tab_content",
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .defaultMinSize(minHeight = 44.dp)
            .widthIn(min = 72.dp)
            .then(
                if (isSelected) {
                    Modifier.shadow(1.dp, RoundedCornerShape(50))
                } else {
                    Modifier
                },
            )
            .background(bgColor, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(
                horizontal = AvoqadoTheme.spacing.md,
                vertical = AvoqadoTheme.spacing.sm,
            ),
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
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

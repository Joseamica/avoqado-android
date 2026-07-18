package com.avoqado.pos.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.TableRestaurant
import androidx.compose.ui.graphics.vector.ImageVector

enum class MainTab(
    val route: String,
    val label: String,
    val shortLabel: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    CHECKOUT(
        route = "checkout",
        label = "Cobrar",
        shortLabel = "Cobrar",
        selectedIcon = Icons.Filled.ShoppingCart,
        unselectedIcon = Icons.Outlined.ShoppingCart,
    ),
    INVENTORY(
        route = "inventory",
        label = "Inventario",
        shortLabel = "Inventario",
        selectedIcon = Icons.Filled.Inventory2,
        unselectedIcon = Icons.Outlined.Inventory2,
    ),
    TRANSACTIONS(
        route = "transactions",
        label = "Transacciones",
        shortLabel = "Ventas",
        selectedIcon = Icons.Filled.Receipt,
        unselectedIcon = Icons.Outlined.Receipt,
    ),
    NOTIFICATIONS(
        route = "notifications",
        label = "Notificaciones",
        shortLabel = "Alertas",
        selectedIcon = Icons.Filled.Notifications,
        unselectedIcon = Icons.Outlined.Notifications,
    ),
    MORE(
        route = "more",
        label = "Más",
        shortLabel = "Más",
        selectedIcon = Icons.Filled.Menu,
        unselectedIcon = Icons.Outlined.Menu,
    ),
    CALENDAR(
        route = "calendar",
        label = "Calendario",
        shortLabel = "Calendario",
        selectedIcon = Icons.Filled.CalendarMonth,
        unselectedIcon = Icons.Outlined.CalendarMonth,
    ),

    /** TABLE_SERVICE (PRO) — restaurant floor plan; visible only in Restaurante mode. */
    TABLES(
        route = "tables",
        label = "Mesas",
        shortLabel = "Mesas",
        selectedIcon = Icons.Filled.TableRestaurant,
        unselectedIcon = Icons.Outlined.TableRestaurant,
    ),
}

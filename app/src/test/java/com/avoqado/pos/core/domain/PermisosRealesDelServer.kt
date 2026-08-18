package com.avoqado.pos.core.domain

/**
 * Los permisos EFECTIVOS que el server manda de verdad a esta app, por rol.
 *
 * 🔴 No están escritos a mano: son la salida de `getEffectiveRolePermissions()`
 * (`avoqado-server/src/lib/permissions.ts`), que es exactamente la función con
 * la que `auth.mobile.service.ts` llena `venue.permissions` en el login — el
 * campo que esta app guarda en `SecureStorage.venuePermissions`.
 *
 * 🔴 POR QUÉ IMPORTA QUE SEA LA LISTA COMPLETA: el server EXPANDE dependencias
 * implícitas antes de mandarla. `orders:update` arrastra `inventory:read`, así
 * que el CAJERO, el MESERO y la COCINA reciben `inventory:read` aunque nadie se
 * los concedió a mano. Un fixture recortado "con los permisos importantes" omite
 * justo esas entradas implícitas y deja pasar en verde un gate que en el aparato
 * hace lo contrario.
 *
 * Para regenerarlo tras un cambio en el server:
 * ```
 * # en avoqado-server, resolver DEFAULT_PERMISSIONS + PERMISSION_DEPENDENCIES
 * # y volcar la lista por rol (ver el script del commit que creó este archivo)
 * ```
 */
object PermisosRealesDelServer {

    /** VIEWER — sólo lectura. NO trae `inventory:read`; SÍ trae `customers:read`. (17 permisos) */
    val VIEWER = listOf(
        "analytics:read",
        "catalog-venue:read",
        "coupons:read",
        "customers:read",
        "discounts:read",
        "features:read",
        "home:read",
        "loyalty:read",
        "menu:read",
        "orders:read",
        "payments:read",
        "products:read",
        "referral:read",
        "reviews:read",
        "shifts:read",
        "teams:read",
        "upsells:read",
    )

    /** HOST — recepción y reservas. NO trae `inventory:read`; SÍ trae `customers:read`. (18 permisos) */
    val HOST = listOf(
        "analytics:read",
        "calendar:connect_self",
        "customers:create",
        "customers:read",
        "home:read",
        "loyalty:read",
        "menu:read",
        "orders:read",
        "payments:read",
        "products:read",
        "referral:read",
        "reservations:cancel",
        "reservations:create",
        "reservations:read",
        "reservations:update",
        "tables:read",
        "tables:update",
        "teams:read",
    )

    /** KITCHEN — cocina. Trae `inventory:read` IMPLÍCITO (por `orders:update` y `area-tickets:issue`), y NO trae `customers:read`. (12 permisos) */
    val KITCHEN = listOf(
        "analytics:read",
        "area-tickets:deliver",
        "area-tickets:issue",
        "calendar:connect_self",
        "home:read",
        "inventory:read",
        "menu:read",
        "orders:read",
        "orders:update",
        "payments:read",
        "products:read",
        "scale:use",
    )

    /** WAITER — mesero. Trae `inventory:read` IMPLÍCITO (por `orders:create`/`orders:update`). NO trae `payments:refund`. (43 permisos) */
    val WAITER = listOf(
        "analytics:read",
        "area-tickets:deliver",
        "area-tickets:issue",
        "calendar:connect_self",
        "cash-out:view_own",
        "cash-out:withdraw",
        "commissions:view_own",
        "coupons:read",
        "coupons:redeem",
        "creditPacks:read",
        "creditPacks:redeem",
        "creditPacks:sell",
        "customers:create",
        "customers:read",
        "discounts:apply",
        "discounts:read",
        "home:read",
        "inventory:read",
        "loyalty:read",
        "menu:read",
        "orders:create",
        "orders:read",
        "orders:update",
        "payments:create",
        "payments:read",
        "products:read",
        "referral:read",
        "reviews:read",
        "scale:use",
        "serialized-inventory:create",
        "serialized-inventory:sell",
        "shifts:read",
        "tables:read",
        "tables:update",
        "teams:read",
        "tpv-payments:pay-later",
        "tpv-sim-custody:accept",
        "tpv-sim-custody:reject",
        "tpv-tables:assign",
        "tpv-time-entries:read",
        "tpv-time-entries:write",
        "tpv:read",
        "upsells:read",
    )

    /** CASHIER — cajero. Trae `payments:refund` EXPLÍCITO (el defecto medido en la D3) y `inventory:read` IMPLÍCITO (por `orders:update`). (40 permisos) */
    val CASHIER = listOf(
        "analytics:read",
        "area-tickets:checkout",
        "calendar:connect_self",
        "commissions:view_own",
        "coupons:read",
        "coupons:redeem",
        "creditPacks:read",
        "creditPacks:redeem",
        "creditPacks:sell",
        "customers:create",
        "customers:read",
        "discounts:apply",
        "discounts:read",
        "home:read",
        "inventory:read",
        "loyalty:read",
        "menu:read",
        "orders:create",
        "orders:read",
        "orders:update",
        "payments:create",
        "payments:read",
        "payments:refund",
        "products:read",
        "referral:read",
        "reviews:read",
        "serialized-inventory:create",
        "serialized-inventory:sell",
        "shifts:read",
        "tables:read",
        "tables:update",
        "teams:read",
        "tpv-payments:pay-later",
        "tpv-products:read",
        "tpv-sim-custody:accept",
        "tpv-sim-custody:reject",
        "tpv-tables:assign",
        "tpv-time-entries:read",
        "tpv-time-entries:write",
        "upsells:read",
    )

    /** SUPERADMIN — el server manda literalmente el comodín, nada más. (1 permisos) */
    val SUPERADMIN = listOf(
        "*:*",
    )
}


package com.avoqado.pos.core.domain

/**
 * Todo permiso que una ruta del server puede rechazarle a un POS.
 *
 * 🔴 ARCHIVO GENERADO — NO LO EDITES A MANO. Sale del mismo
 * `scripts/regenerar-permisos-reales.mjs` que `PermisosRealesDelServer`, leyendo
 * los `checkPermission(...)` de `mobile.routes.ts`, `tpv.routes.ts` y
 * `pos-sync.routes.ts`.
 *
 * 🔴 PARA QUÉ SIRVE: para que la cobertura de `PermissionLabels` se verifique
 * sola. Antes esa lista vivía escrita a mano dentro de un test, con un comentario
 * que ya admitía el problema ("si el server agrega un checkPermission nuevo, este
 * test NO se entera solo"). Y no se enteró: el server estrenó `estimates:create`
 * y `orders:cancel-unpaid`, el modal empezó a enseñar el código pelón —que es
 * justo el síntoma que el founder reportó en vivo— y la suite siguió verde.
 *
 * 🔴 LO QUE **NO** CUBRE, a propósito: los permisos que eximen de la propiedad de
 * mesa (`tables:manage-all`, `tables:pay-any`) no pasan por `checkPermission`
 * sino por `checkTableOwnership`, y su 403 trae `code: TABLE_OWNED_BY_OTHER` sin
 * nombre de permiso. No salen aquí porque el modal nunca los pide; sus etiquetas
 * existen para el resto de la app, no para ese modal.
 *
 * Derivado de avoqado-server · huella c974e359e6e4cd7b.
 */
object PermisosDeRutasDelServer {

    /** Los 48 permisos que un `checkPermission(...)` de `mobile.routes.ts` puede rechazar. */
    val MOBILE: List<String> = listOf(
        "area-tickets:cancel",
        "area-tickets:checkout",
        "area-tickets:confirm-external",
        "area-tickets:deliver",
        "area-tickets:issue",
        "class-sessions:read-assigned",
        "coupons:create",
        "coupons:delete",
        "coupons:read",
        "coupons:redeem",
        "coupons:update",
        "creditPacks:read",
        "creditPacks:redeem",
        "creditPacks:sell",
        "customers:create",
        "customers:read",
        "delivery-channels:snooze",
        "discounts:apply",
        "discounts:create",
        "discounts:delete",
        "discounts:read",
        "discounts:update",
        "estimates:create",
        "inventory:adjust",
        "inventory:create",
        "inventory:read",
        "inventory:update",
        "loyalty:read",
        "menu:create",
        "menu:delete",
        "menu:read",
        "menu:update",
        "orders:cancel-unpaid",
        "orders:create",
        "orders:merge",
        "orders:read",
        "orders:update",
        "payments:create",
        "payments:read",
        "payments:refund",
        "reports:read",
        "reservations:update",
        "scale:use",
        "tables:read",
        "tables:update",
        "teams:read",
        "tpv-products:write",
        "upsells:read",
    )

    /** Los 36 permisos que un `checkPermission(...)` de `tpv.routes.ts` puede rechazar. */
    val TPV: List<String> = listOf(
        "cash-out:view_own",
        "cash-out:withdraw",
        "customers:create",
        "customers:read",
        "discounts:apply",
        "home:read",
        "loyalty:read",
        "menu:read",
        "orders:cancel",
        "orders:cancel-unpaid",
        "orders:comp",
        "orders:create",
        "orders:merge",
        "orders:read",
        "orders:update",
        "orders:void",
        "payments:create",
        "payments:read",
        "payments:refund",
        "referral:override-existing-customer",
        "referral:read",
        "serialized-inventory:create",
        "serialized-inventory:sell",
        "shifts:read",
        "tables:update",
        "tpv-floor-elements:delete",
        "tpv-floor-elements:write",
        "tpv-products:write",
        "tpv-reports:read",
        "tpv-shifts:close",
        "tpv-shifts:create",
        "tpv-sim-custody:accept",
        "tpv-sim-custody:reject",
        "tpv-tables:delete",
        "tpv-tables:write",
        "tpv-time-entries:read",
    )

    /** Los 0 permisos que un `checkPermission(...)` de `pos-sync.routes.ts` puede rechazar. */
    val POS_SYNC: List<String> = emptyList()

    /** La unión, sin repetidos — lo que la app puede toparse por cualquier puerta. */
    val TODOS: List<String> = (MOBILE + TPV + POS_SYNC).distinct().sorted()
}

package com.avoqado.pos.core.domain

import com.avoqado.pos.core.data.local.SecureStorage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoleManager @Inject constructor(
    private val secureStorage: SecureStorage,
) {
    val role: String
        get() = secureStorage.userRole?.uppercase() ?: "VIEWER"

    // MARK: - Feature Access
    //
    // 🔴 La regla: un gate del cliente ESPEJA un permiso del server por nombre
    // EXACTO (`hasVenuePermission(..., fallbackRoles = ...)`). Los que siguen
    // decidiendo por LISTA DE ROLES están marcados abajo con "⚠️ DIVERGE": no es
    // olvido, es que elegir su permiso es una decisión de producto pendiente.
    // Ninguno se cambia a ciegas: el que se equivoque esconde trabajo que el
    // server sí permite, o promete permiso que el server niega.
    //
    // 🔴 Y ANTES DE ESPEJAR UNO NUEVO, DOS INVENTARIOS. Espejar no cambia un
    // valor: cambia la REGLA, y su radio es el conjunto de sus consumidores.
    //
    //   1. TODOS los consumidores del gate, buscados por FIRMA del símbolo
    //      (`grep -rn '\bcanX\b'`), no por substring. Un gate reusado por una
    //      pantalla ajena convierte un arreglo en una concesión silenciosa:
    //      `canIssueRefund` gobernaba también las 4 acciones de la CUARENTENA
    //      (ver `canResolveQuarantine`), y espejarlo se las regaló al cajero.
    //   2. La tabla rol × antes/después calculada con la lista EFECTIVA que el
    //      server manda de verdad — `getEffectiveRolePermissions()` EXPANDE
    //      dependencias implícitas antes de mandarla. `orders:update` arrastra
    //      `inventory:read`, así que espejar inventario le encendía una pestaña
    //      entera de la barra a COCINA, MESERO y CAJERO. El fixture de los tests
    //      vive en `PermisosRealesDelServer` justo para que esa tabla no se
    //      adivine.
    //
    // Cualquier celda que cambie y que gobierne NAVEGACIÓN o una acción
    // DESTRUCTIVA se declara como decisión de producto y no se toca de paso.

    /**
     * POS checkout: WAITER, CASHIER, MANAGER, ADMIN, OWNER, SUPERADMIN
     *
     * ⚠️ DIVERGE (decisión de producto pendiente): no hay UN permiso que
     * signifique "puede usar el POS". `orders:create` lo tiene el WAITER pero NO
     * el CASHIER (que sólo trae `orders:read|update` + `payments:create`), así
     * que espejarlo dejaría al cajero sin la pestaña de cobro. Falta decidir cuál
     * permiso —o qué combinación— gobierna esta pestaña.
     */
    val canAccessPOS: Boolean
        get() = role in setOf("WAITER", "CASHIER", "MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /**
     * Inventario: MANAGER, ADMIN, OWNER, SUPERADMIN
     *
     * ⚠️ DIVERGE (decisión de producto NO tomada) — y ojo, esto SÍ se espejó de
     * `inventory:read` el 2026-08-17 y se revirtió el mismo día, porque encendía
     * una pestaña entera de la barra para tres roles sin que nadie lo decidiera.
     *
     * El permiso existe y el server SÍ se los da, pero por DEPENDENCIA
     * IMPLÍCITA, no porque alguien se los concediera: `orders:create` y
     * `orders:update` arrastran `inventory:read`
     * (`avoqado-server/src/lib/permissions.ts`), para que el POS pueda
     * CONSULTAR existencias mientras se toma una orden. La tabla real, medida
     * contra `getEffectiveRolePermissions()`:
     *
     * ```
     *                        VIEWER  HOST  KITCHEN  WAITER  CASHIER  MANAGER+
     * lista de roles (hoy)     no     no     no       no      no       sí
     * `inventory:read` real    no     no     SÍ       SÍ      SÍ       sí
     * ```
     *
     * Este gate gobierna TRES cosas —la pestaña INVENTARIO de la barra
     * (`MainTabsPolicy`), las dos rutas `MainTab.INVENTORY` del `NavGraph`, y la
     * única puerta a Traslados entre sucursales, que vive dentro de
     * `InventoryScreen`—, así que espejarlo le abre el módulo de inventario
     * completo a la cocina y al piso. Puede que sea lo correcto; es una decisión
     * del founder, no la traducción mecánica de un permiso. Fijado en
     * `navigation/InventarioTabPermisoTest`.
     */
    val canAccessInventory: Boolean
        get() = role in MANAGER_UP

    /**
     * Transactions: CASHIER, MANAGER, ADMIN, OWNER, SUPERADMIN
     *
     * ⚠️ DIVERGE (E3): el server da `payments:read` también a WAITER y VIEWER, o
     * sea que el mesero NO ve sus ventas aunque el server se las serviría.
     * Espejarlo le daría la pestaña de Transacciones —con TODAS las ventas del
     * negocio, no sólo las suyas—, y eso es una decisión de producto: el permiso
     * del server no distingue "las mías" de "las de todos".
     */
    val canAccessTransactions: Boolean
        get() = role in setOf("CASHIER", "MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /**
     * Create products: MANAGER, ADMIN, OWNER, SUPERADMIN
     *
     * ⚠️ DIVERGE (decisión de producto pendiente): hay DOS permisos candidatos y
     * significan cosas distintas — `menu:create` (alta de catálogo, MANAGER+) y
     * `tpv-products:write` (crear al vuelo desde el POS, también MANAGER+ hoy).
     * Cuál gobierna el mosaico "Crear producto" del cobro es una decisión, no una
     * traducción.
     */
    val canCreateProducts: Boolean
        get() = role in setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /**
     * Manage customers (create/edit): MANAGER, ADMIN, OWNER, SUPERADMIN
     *
     * ⚠️ DIVERGE (E4): este gate MEZCLA dos permisos que el server ya separó.
     * Desde 2026-08-16 WAITER, CASHIER y HOST tienen `customers:create` a
     * propósito ("sin esto la venta queda anónima"), pero editar/borrar sigue en
     * MANAGER+. El arreglo correcto es PARTIRLO en `canCreateCustomers`
     * (`customers:create`) y `canEditCustomers` (`customers:update`) y repartir
     * los 4 sitios que hoy lo usan — cambio de significado, no mecánico.
     */
    val canManageCustomers: Boolean
        get() = role in setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /**
     * Ver clientes — espejo de `customers:read` (`GET /mobile/venues/:id/customers`).
     *
     * Cambia de tabla y se queda a propósito: el server le da `customers:read`
     * también a VIEWER y a HOST, que el respaldo `FLOOR` les negaba, y NO se lo
     * da a KITCHEN. **No cambia nada visible: este gate no tiene un solo
     * consumidor en la app** (la pantalla de Clientes cuelga del menú "Más" sin
     * gate). Queda espejado para que el día que se use ya diga la verdad.
     */
    val canViewCustomers: Boolean
        get() = hasVenuePermission("customers:read", fallbackRoles = FLOOR)

    /**
     * Reportes — espejo de `reports:read` (las rutas `/mobile/venues/:id/reports`).
     *
     * Espejo SIN cambio de tabla: `reports:read` lo tienen exactamente
     * MANAGER, ADMIN, OWNER y SUPERADMIN, los mismos del respaldo. Gobierna una
     * fila del menú "Más" ("Informes"), no navegación.
     */
    val canAccessReports: Boolean
        get() = hasVenuePermission("reports:read", fallbackRoles = MANAGER_UP)

    /**
     * Reembolsar — espejo EXACTO de `payments:refund`.
     *
     * 🔴 Medido en la D3 el 2026-08-17: esto decidía por una LISTA DE ROLES, así
     * que a un CAJERO se le pintaba el candado… y el reembolso pasaba sin PIN
     * (quedó un `Payment` de CASH -50.00). El server SÍ le da `payments:refund`
     * al CASHIER, o sea que la app prometía una autorización que el server no
     * exigía. Un gate por rol no es un espejo del permiso: es una SEGUNDA fuente
     * de verdad, y se desincroniza sola en cuanto el server mueve un permiso o
     * el negocio usa un Permission Set.
     *
     * 🔴 Este gate significa REEMBOLSAR y nada más. La pantalla de cuarentena
     * colgaba de él por reuso y se llevó de regalo al cajero: ya tiene el suyo
     * (`canResolveQuarantine`). No le vuelvas a colgar nada que no sea devolver
     * dinero al cliente.
     */
    val canIssueRefund: Boolean
        get() = hasVenuePermission("payments:refund", fallbackRoles = MANAGER_UP)

    /**
     * Liquidar el cheque de OTRO mesero — espejo EXACTO de `tables:pay-any`.
     *
     * 🔴 Es el gate que dejaba al CAJERO sin hacer su trabajo. Con la propiedad de
     * mesa encendida, la app decidía con `tables:manage-all` (que es EDITAR) y le
     * negaba también cobrar: tocaba "Pagar" y salía "Mesa de {mesero} — solo
     * lectura". El server ya no piensa eso: su ruta de cobro
     * (`POST /mobile/venues/:id/orders/:orderId/pay`) exime la propiedad con
     * `PAYMENT_OWNERSHIP_OVERRIDES = ['tables:manage-all', 'tables:pay-any']`, y el
     * CASHIER es justo el único rol que estrena `tables:pay-any`. El cliente era lo
     * ÚNICO que bloqueaba.
     *
     * 🔴 Y significa cobrar, NADA más. `tables:manage-all` habría "arreglado" el
     * síntoma regalándole editar, descontar, cortesiar, cancelar, mover y fusionar
     * CUALQUIER mesa — el permiso se elige por SIGNIFICADO, no por la tabla que
     * produzca. Toast y Square resuelven igual: hay dueño de mesa para EDITAR el
     * cheque, y la caja lo liquida.
     *
     * El respaldo es MANAGER_UP porque es lo que hacía la app cuando la lista
     * efectiva venía vacía: sólo quien tenía `tables:manage-all` se saltaba el
     * candado. Con lista, manda la lista.
     */
    val canSettleAnyTable: Boolean
        get() = hasVenuePermission("tables:pay-any", fallbackRoles = MANAGER_UP)

    /**
     * Resolver la CUARENTENA de sincronización: MANAGER, ADMIN, OWNER, SUPERADMIN
     *
     * ⚠️ DIVERGE, y **NO hay permiso que espejar — ni lo habrá mientras estas
     * acciones no toquen el server**. Las cuatro que gobierna —descartar una
     * operación rechazada, reintentar un cobro fallido, descartarlo, y descartar
     * una acción de reserva— terminan en un DELETE de la base LOCAL
     * (`SyncOutbox.dismissRejected` → `dao.dismiss`,
     * `PaymentSyncService.dismissFailedPayment` → `dao.deleteFailed`,
     * `ReservationRepository.dismissQuarantined` → `pendingDao.delete`). No hay
     * endpoint, así que no hay `checkPermission` que las juzgue.
     *
     * 🔴 CONSECUENCIA QUE HAY QUE TENER PRESENTE: **este gate del cliente es el
     * ÚNICO gate que existe.** En el resto de la app equivocarse cuesta una UI
     * inconsistente y el server corrige con un 403; aquí no hay red abajo. Por
     * eso se queda por ROL, que es lo conservador, y por eso no se espeja "el
     * permiso más parecido": ninguno significa esto. `payments:refund` es
     * devolver dinero (era el bug), `accounting:reconcile` es la conciliación
     * bancaria del módulo contable, `shifts:close` es cerrar un turno. Elegir
     * cualquiera sería INVENTARLE una regla al server, no espejarla.
     *
     * 📌 LO QUE EL SERVER DEBERÍA TENER, declarado para cuando se decida:
     * `pos-sync:resolve` — "descartar/reintentar operaciones en cuarentena de un
     * dispositivo" (namespace `pos-sync`, el del `PosSyncIntent` y del MCP
     * `pos_sync_status`). El día que exista, este gate se espeja de él con
     * `fallbackRoles = MANAGER_UP` y este comentario se borra.
     *
     * Paridad: iOS lo tiene igual de rol (`QuarantineView.swift` →
     * `canIssueRefund`, que allá sigue siendo lista de roles MANAGER+), así que
     * esto RESTAURA la paridad en vez de romperla.
     */
    val canResolveQuarantine: Boolean
        get() = role in MANAGER_UP

    /**
     * Cash-drawer ops (open/close/pay-in-out): CASHIER, MANAGER, ADMIN, OWNER, SUPERADMIN
     *
     * ⚠️ DIVERGE (E5): el server gobierna el cajón con `payments:create` (abrir,
     * ingreso, retiro, cerrar) y `payments:read` (ver) — y el WAITER tiene los
     * dos, o sea que el server SÍ le deja operar el cajón y Android lo esconde.
     * NO se espeja a ciegas: quién puede abrir y cerrar el cajón es justo la
     * pregunta abierta de "turno vs caja" (a quién se le cuadra el efectivo), y
     * es dinero. Decisión del founder.
     */
    val canManageCashDrawer: Boolean
        get() = role in setOf("CASHIER", "MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    /**
     * Kitchen display: WAITER, CASHIER, MANAGER, ADMIN, OWNER, SUPERADMIN
     *
     * ⚠️ DIVERGE, y NO hay permiso que espejar: las 4 rutas de `/mobile/venues/:id/kds`
     * llevan `authenticateTokenMiddleware + requireVenueMembership` y NINGÚN
     * `checkPermission`. O sea que el server se lo sirve a cualquier miembro del
     * venue —incluido KITCHEN, el rol que lleva el nombre de la pantalla, al que
     * esta lista se lo niega—. Elegir un permiso aquí sería INVENTARLE una regla
     * al server, no espejarla: o el server empieza a checar uno, o esto es una
     * decisión de producto declarada. Se deja como está a propósito.
     */
    val canAccessKDS: Boolean
        get() = role in setOf("WAITER", "CASHIER", "MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

    // MARK: - Effective venue permissions

    /**
     * ¿El permiso efectivo está concedido, con respaldo por rol si no se sabe?
     *
     * 🔴 Espejo EXACTO de `hasVenuePermission(_:fallbackRoles:)` de
     * `avoqado-ios/Services/RoleManager.swift`.
     *
     * La lista efectiva del venue es la autoridad. Pero puede llegar VACÍA —una
     * sesión anterior al campo, o un server que no la manda— y ahí no se sabe
     * nada: negar a ciegas escondería el reembolso hasta al dueño, así que se
     * cae al comportamiento de siempre (la lista de roles).
     */
    fun hasVenuePermission(requiredPermission: String, fallbackRoles: Set<String>): Boolean =
        if (secureStorage.venuePermissions.isEmpty()) {
            role in fallbackRoles
        } else {
            hasVenuePermission(requiredPermission)
        }

    /**
     * Permission checks for optional UI affordances must use the effective list
     * returned by the server for this venue, not a local role guess. That keeps
     * custom role permissions and Permission Sets authoritative.
     *
     * Sin respaldo: con la lista vacía devuelve `false`. Úsalo sólo donde negar
     * de más es inofensivo (un mosaico opcional). Para un gate que puede ESCONDER
     * trabajo, usa la sobrecarga con `fallbackRoles`.
     */
    fun hasVenuePermission(requiredPermission: String): Boolean {
        val (requiredResource, requiredAction) = requiredPermission.split(':', limit = 2)
            .let { parts -> parts.firstOrNull().orEmpty() to parts.getOrNull(1).orEmpty() }

        return secureStorage.venuePermissions.any { granted ->
            granted == "*:*" ||
                granted == requiredPermission ||
                granted == "$requiredResource:*" ||
                granted == "*:$requiredAction"
        }
    }

    /** Membership tiles are optional and must not trigger a forbidden request. */
    val canReadCreditPacks: Boolean
        get() = hasVenuePermission("creditPacks:read")

    // MARK: - Traslados entre sucursales (CEDIS)
    // Espejo de DEFAULT_PERMISSIONS del server (avoqado-server/src/lib/permissions.ts):
    // MANAGER tiene los 5 permisos inventory-transfers:* explícitos; ADMIN y OWNER
    // llevan el wildcard inventory-transfers:*. Espejo también de iOS, que ya los
    // leía por permiso (`canReadInterVenueTransfers` / `canApproveInterVenueTransfers`).
    //
    // Espejo SIN cambio de tabla: los dos permisos los tienen exactamente
    // MANAGER+, los mismos del respaldo. Y no gobiernan navegación: la única
    // puerta a esta pantalla es `canAccessInventory`, porque
    // `InterVenueTransfersView` vive dentro de `InventoryScreen`.

    /** Ver traslados — espejo de `inventory-transfers:read`. */
    val canViewInventoryTransfers: Boolean
        get() = hasVenuePermission("inventory-transfers:read", fallbackRoles = MANAGER_UP)

    /** Decidir traslados — aprobar/rechazar/despachar/recibir/cancelar
     *  — espejo de `inventory-transfers:approve`. */
    val canDecideInventoryTransfers: Boolean
        get() = hasVenuePermission("inventory-transfers:approve", fallbackRoles = MANAGER_UP)

    // MARK: - PIN de autorización de gerente

    /**
     * Cómo pintar una acción según si el rol la tiene y si el local activó el
     * PIN de autorización.
     *
     * 🔴 NO es lógica nueva de permisos: el juez sigue siendo el server. Esto
     * sólo decide si el control se ve. Con candado se toca igual, sale la
     * llamada, el server responde 403 `overridable` y el teclado aparece solo.
     */
    fun visibilityOf(allowed: Boolean, overrideEnabled: Boolean): ActionVisibility = when {
        allowed -> ActionVisibility.ALLOWED
        overrideEnabled -> ActionVisibility.LOCKED
        else -> ActionVisibility.HIDDEN
    }

    private companion object {
        /** El respaldo histórico de todo lo administrativo. */
        val MANAGER_UP = setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")

        /** El respaldo histórico de lo que se usa desde el piso. */
        val FLOOR = setOf("WAITER", "CASHIER", "MANAGER", "ADMIN", "OWNER", "SUPERADMIN")
    }
}

/**
 * Cómo se pinta una acción que el rol no tiene.
 *
 * 🔴 Espejo EXACTO de `ActionVisibility` en avoqado-ios/Services/RoleManager.swift.
 *
 * Esconder un botón parece limpio, pero deja al piso sin salida: sin botón no
 * hay 403, y sin 403 no hay a quién pedirle autorización. Con el PIN de gerente
 * encendido, la acción se VE con un candado y el "no" llega con una puerta.
 */
enum class ActionVisibility { ALLOWED, LOCKED, HIDDEN }

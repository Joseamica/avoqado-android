package com.avoqado.pos.core.domain

/**
 * Permiso técnico → cómo se lo decimos a un mesero.
 *
 * 🔴 Espejo EXACTO de `PermissionLabels.swift` en avoqado-ios. Si agregas uno
 * aquí, agrégalo allá en el MISMO trabajo, con el mismo texto en español.
 *
 * Nunca enseñes la string cruda ("orders:merge") en pantalla: el que está
 * enfrente no sabe qué es un permiso, sabe qué estaba tratando de hacer.
 *
 * 🔴 Por qué esto creció el 2026-08-16: hasta entonces sólo lo usaba el teclado
 * del PIN de gerente, así que tenía las 11 entradas que ESE teclado necesitaba.
 * El modal global de 403 no lo usaba y escupía el código pelado. Medido en
 * hardware: un CASHIER cobrando vio "Pídele a un administrador que te active
 * «tpv:read»" en la pantalla de propina —la app consulta sola qué terminales
 * PAX están en línea— y el código no le explicaba nada a nadie. Ahora el mapa
 * cubre todo permiso que una ruta de `/mobile` o `/tpv` puede rechazar.
 *
 * Las etiquetas son en español DE PISO: lo que la persona estaba haciendo, no
 * la traducción literal del recurso. Van en minúscula y sin el código, porque
 * se insertan dentro de una frase ("…que te active «cobrar con terminal»").
 */
object PermissionLabels {
    private val LABELS = mapOf(
        // MARK: - Terminales de cobro (PAX)
        //
        // 🔴 `tpv:read` NO es "cobrar". Desde 2026-08-16 (server c74f6593) la
        // ruta que el POS usa para saber qué terminal está en línea exige
        // `payments:create`, no esto. Lo que queda detrás de `tpv:read` son las
        // cuatro rutas de ADMINISTRACIÓN del dashboard: listar terminales, ver
        // una, y sus dos de salud.
        //
        // Nombrarlo "cobrar con terminal" fue correcto UN día — contra el server
        // viejo — y se vuelve mentira en cuanto ese fix se despliegue: mandaría
        // a pedir un permiso de administración a alguien que sólo quiere cobrar,
        // que es exactamente el bug que el fix eliminó.
        "tpv:read" to "ver las terminales del local",
        "tpv:create" to "dar de alta una terminal",
        "tpv:update" to "cambiar la configuración de una terminal",
        "tpv:delete" to "dar de baja una terminal",
        "tpv:command" to "mandar órdenes a una terminal",
        "tpv-terminal:settings" to "cambiar los ajustes de esta terminal",
        "tpv-settings:read" to "ver los ajustes de esta terminal",
        "tpv-settings:update" to "cambiar los ajustes de esta terminal",
        "tpv-devices:manage" to "conectar dispositivos de cobro",
        "tpv-kiosk:enable" to "prender el modo kiosco",
        "tpv-factory-reset:execute" to "restablecer la terminal de fábrica",

        // MARK: - Cobros
        "payments:read" to "ver los cobros",
        "payments:create" to "cobrar",
        "payments:refund" to "hacer un reembolso",
        "payments:routing-read" to "ver por dónde se procesa cada cobro",
        "payments:routing-manage" to "cambiar por dónde se procesa cada cobro",
        "payment:create-manual" to "registrar un cobro a mano",
        "tpv-payments:send-receipt" to "enviar el recibo",
        "tpv-payments:pay-later" to "dejar la cuenta para pagar después",
        "tender-types:read" to "ver los tipos de pago",
        "tender-types:manage" to "configurar los tipos de pago",

        // MARK: - Cuentas
        "orders:read" to "ver las cuentas",
        "orders:create" to "abrir una cuenta",
        "orders:update" to "modificar la cuenta",
        "orders:cancel" to "cancelar la cuenta",
        "orders:comp" to "dar una cortesía",
        "orders:void" to "anular artículos",
        "orders:merge" to "fusionar cuentas",
        "tpv-orders:comp" to "dar una cortesía",
        "tpv-orders:void" to "anular artículos",
        "tpv-orders:discount" to "aplicar un descuento",

        // MARK: - Mesas y plano del salón
        "tables:read" to "ver las mesas",
        "tables:update" to "cambiar el estado de una mesa",
        "tables:manage-all" to "modificar mesas de otro mesero",
        "tpv-tables:assign" to "asignar una mesa",
        "tpv-tables:write" to "crear o mover mesas",
        "tpv-tables:delete" to "borrar una mesa",
        "tpv-floor-elements:read" to "ver el plano del salón",
        "tpv-floor-elements:write" to "editar el plano del salón",
        "tpv-floor-elements:delete" to "borrar algo del plano del salón",

        // MARK: - Turnos y reloj checador
        "shifts:read" to "ver los turnos",
        "shifts:create" to "abrir un turno",
        "shifts:update" to "modificar un turno",
        "shifts:delete" to "borrar un turno",
        "shifts:close" to "cerrar el turno",
        "tpv-shifts:create" to "abrir un turno",
        "tpv-shifts:close" to "cerrar el turno",
        "tpv-time-entries:read" to "ver las marcas del reloj",
        "tpv-time-entries:write" to "marcar entrada y salida",

        // MARK: - Clientes y lealtad
        "customers:read" to "ver los clientes",
        "customers:create" to "dar de alta un cliente",
        "customers:update" to "editar un cliente",
        "customers:delete" to "borrar un cliente",
        "customers:settle-balance" to "saldar el adeudo de un cliente",
        "customer-groups:read" to "ver los grupos de clientes",
        "customer-groups:create" to "crear un grupo de clientes",
        "customer-groups:update" to "editar un grupo de clientes",
        "customer-groups:delete" to "borrar un grupo de clientes",
        "tpv-customers:read" to "ver los clientes",
        "tpv-customers:create" to "dar de alta un cliente",
        "loyalty:read" to "ver los puntos del cliente",
        "loyalty:create" to "crear un programa de lealtad",
        "loyalty:update" to "editar el programa de lealtad",
        "loyalty:delete" to "borrar el programa de lealtad",
        "loyalty:redeem" to "canjear puntos",
        "loyalty:adjust" to "ajustar los puntos de un cliente",
        "loyalty:expire" to "vencer puntos",
        "referral:read" to "ver el programa de referidos",
        "referral:override-existing-customer" to "referir a un cliente que ya existía",

        // MARK: - Menú y productos
        "menu:read" to "ver el menú",
        "menu:create" to "crear productos",
        "menu:update" to "editar el menú",
        "menu:delete" to "borrar productos",
        "menu:import" to "importar el menú",
        "products:read" to "ver los productos",
        "products:create" to "crear productos",
        "products:update" to "editar productos",
        "products:delete" to "borrar productos",
        "tpv-products:read" to "buscar productos por código de barras",
        "tpv-products:write" to "editar productos desde la terminal",
        "catalog-venue:read" to "ver el catálogo maestro",
        "catalog-venue:request-override" to "pedir un cambio al catálogo maestro",
        "upsells:read" to "ver las sugerencias de venta",
        "upsells:create" to "crear sugerencias de venta",
        "upsells:update" to "aprobar o descartar sugerencias de venta",
        "upsells:delete" to "borrar sugerencias de venta",

        // MARK: - Reservaciones
        "reservations:read" to "ver las reservaciones",
        "reservations:create" to "agendar una reservación",
        "reservations:update" to "modificar una reservación",
        "reservations:cancel" to "cancelar una reservación",

        // MARK: - Paquetes de crédito (clases, prepagos)
        "creditPacks:read" to "ver los paquetes de crédito",
        "creditPacks:create" to "crear un paquete de crédito",
        "creditPacks:update" to "editar un paquete de crédito",
        "creditPacks:delete" to "borrar un paquete de crédito",

        // MARK: - Descuentos y cupones
        "discounts:read" to "ver los descuentos",
        "discounts:create" to "crear un descuento",
        "discounts:update" to "editar un descuento",
        "discounts:delete" to "borrar un descuento",
        "discounts:apply" to "aplicar un descuento",
        "coupons:read" to "ver los cupones",
        "coupons:create" to "crear un cupón",
        "coupons:update" to "editar un cupón",
        "coupons:delete" to "borrar un cupón",
        "coupons:redeem" to "canjear un cupón",

        // MARK: - Inventario
        "inventory:read" to "ver el inventario",
        "inventory:create" to "dar de alta un insumo",
        "inventory:update" to "editar el inventario",
        "inventory:delete" to "borrar un insumo",
        "inventory:adjust" to "ajustar existencias",
        "inventory:org-manage" to "manejar el inventario de todas las sucursales",
        "inventory-transfers:read" to "ver los traspasos entre sucursales",
        "inventory-transfers:request" to "pedir un traspaso a otra sucursal",
        "inventory-transfers:approve" to "aprobar un traspaso",
        "inventory-transfers:dispatch" to "enviar un traspaso",
        "inventory-transfers:receive" to "recibir un traspaso",
        "serialized-inventory:sell" to "vender un artículo con número de serie",
        "serialized-inventory:create" to "dar de alta artículos con número de serie",
        "serialized-inventory:change-category" to "cambiar de categoría un artículo con número de serie",
        "tpv-sim-custody:accept" to "aceptar un traspaso de SIMs",
        "tpv-sim-custody:reject" to "rechazar un traspaso de SIMs",

        // MARK: - Reportes
        "reports:read" to "ver los reportes",
        "reports:export" to "exportar los reportes",
        "tpv-reports:read" to "ver los reportes de la terminal",
        "tpv-reports:export" to "exportar los reportes de la terminal",
        "tpv-reports:pay-later-aging" to "ver las cuentas pendientes de pago",
        "analytics:read" to "ver las estadísticas",
        "analytics:export" to "exportar las estadísticas",

        // MARK: - Vales de área (mostradores que cobran en otra caja)
        "area-tickets:issue" to "hacer un vale de área",
        "area-tickets:checkout" to "cobrar un vale de área",
        "area-tickets:cancel" to "cancelar un vale de área",
        "area-tickets:deliver" to "entregar un vale de área",
        "area-tickets:configure" to "configurar los vales de área",
        "area-tickets:confirm-external" to "confirmar un cobro hecho en otra caja",

        // MARK: - Comisiones del propio empleado
        "commissions:view_own" to "ver mis comisiones",
        "cash-out:view_own" to "ver mis comisiones",
        "cash-out:read" to "ver las comisiones del equipo",
        "cash-out:withdraw" to "retirar mis comisiones",
        "cash-out:manage" to "manejar los retiros de comisiones",
        "cash-out:report" to "ver el reporte de comisiones",

        // MARK: - Hardware e infraestructura del local
        "printers:read" to "ver las impresoras",
        "printers:manage" to "configurar las impresoras",
        "scale:use" to "usar la báscula",
        "scale:configure" to "configurar la báscula",

        // MARK: - Cuenta, equipo y ajustes
        "home:read" to "entrar a la pantalla de inicio",
        "teams:read" to "ver al equipo",
        "teams:create" to "dar de alta a alguien del equipo",
        "teams:update" to "editar a alguien del equipo",
        "teams:delete" to "dar de baja a alguien del equipo",
        "teams:invite" to "invitar a alguien al equipo",
        "settings:read" to "ver la configuración",
        "settings:manage" to "cambiar la configuración",
        "venues:read" to "ver los datos de la sucursal",
        "venues:update" to "editar los datos de la sucursal",
        "reviews:read" to "ver las reseñas",
        "reviews:respond" to "responder reseñas",
        "cfdi:issue" to "facturar",
        "cfdi:view" to "ver las facturas",
        "cfdi:configure" to "configurar la facturación",
    )

    const val FALLBACK = "esta acción"

    /**
     * La acción en humano, o [FALLBACK] si no la conocemos.
     *
     * Sirve donde el respaldo cabe en la frase ya hecha — el teclado del PIN
     * dice "Pídele a un gerente que autorice esta acción" y se lee bien. Donde
     * NO cabe, usa [labelOrNull] y arma otra frase.
     */
    fun of(permission: String): String = LABELS[permission] ?: FALLBACK

    /**
     * La acción en humano, o `null` si este permiso no tiene etiqueta.
     *
     * 🔴 Existe porque el respaldo, metido a fuerzas en la frase del modal,
     * deja "…que te active «esta acción»", que suena a error de la app. Quien
     * arma ese texto necesita SABER que no hubo etiqueta para decir otra cosa.
     */
    fun labelOrNull(permission: String): String? = LABELS[permission]

    /** Todo el mapa. Para tests y herramientas; la UI usa [of] / [labelOrNull]. */
    fun all(): Map<String, String> = LABELS
}

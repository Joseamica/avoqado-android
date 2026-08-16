package com.avoqado.pos.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionLabelsTest {

    @Test
    fun `traduce los permisos que el piso puede encontrarse`() {
        assertEquals("fusionar cuentas", PermissionLabels.of("orders:merge"))
        assertEquals("hacer un reembolso", PermissionLabels.of("payments:refund"))
        assertEquals("cancelar la cuenta", PermissionLabels.of("orders:cancel"))
        assertEquals("dar una cortesía", PermissionLabels.of("orders:comp"))
        assertEquals("anular artículos", PermissionLabels.of("orders:void"))
        assertEquals("modificar la cuenta", PermissionLabels.of("orders:update"))
        assertEquals("aplicar un descuento", PermissionLabels.of("discounts:apply"))
    }

    @Test
    fun `un permiso desconocido cae a un texto neutro, nunca a la string tecnica`() {
        assertEquals("esta acción", PermissionLabels.of("cosas:raras"))
        assertEquals("esta acción", PermissionLabels.of(""))
    }

    // MARK: - El caso que originó la ampliación (2026-08-16)

    @Test
    fun `tpv read tiene etiqueta — es el permiso del modal que vio el cajero`() {
        // Un CASHIER cobrando vio "te active «tpv:read»" en la pantalla de
        // propina: la app consulta sola qué terminales PAX están conectadas.
        // El código crudo no le dice a nadie qué estaba pasando.
        assertEquals("ver las terminales del local", PermissionLabels.of("tpv:read"))
    }

    // MARK: - labelOrNull: quien arma el mensaje necesita SABER si hubo etiqueta
    //
    // `of()` devuelve el respaldo, que sirve dentro de una frase ya hecha
    // ("Pídele el PIN para esta acción") pero deja coja la del modal
    // ("que te active «esta acción»"). Por eso hay una variante que lo dice.

    @Test
    fun `labelOrNull devuelve null cuando no hay etiqueta, y nunca el respaldo`() {
        assertNull(PermissionLabels.labelOrNull("cosas:raras"))
        assertNull(PermissionLabels.labelOrNull(""))
        assertEquals("ver las terminales del local", PermissionLabels.labelOrNull("tpv:read"))
    }

    // MARK: - Cobertura: todo permiso que una ruta de mobile/tpv puede rechazar
    //
    // Sacado de los `checkPermission(...)` de mobile.routes.ts, tpv.routes.ts y
    // pos-sync.routes.ts del server (2026-08-16). Si el server agrega un
    // `checkPermission` nuevo a una ruta que la app llama, este test NO se entera
    // solo — pero al menos deja escrito cuál era el contrato el día que se hizo.

    private val permisosQueLasRutasDeMobileRechazan = listOf(
        "area-tickets:cancel",
        "area-tickets:checkout",
        "area-tickets:confirm-external",
        "area-tickets:deliver",
        "area-tickets:issue",
        "cash-out:view_own",
        "cash-out:withdraw",
        "creditPacks:create",
        "creditPacks:read",
        "creditPacks:update",
        "customers:create",
        "customers:read",
        "discounts:apply",
        "home:read",
        "inventory:adjust",
        "inventory:create",
        "inventory:read",
        "inventory:update",
        "loyalty:read",
        "menu:create",
        "menu:delete",
        "menu:read",
        "menu:update",
        "orders:cancel",
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
        "reports:read",
        "scale:use",
        "serialized-inventory:create",
        "serialized-inventory:sell",
        "shifts:close",
        "shifts:create",
        "shifts:read",
        "tables:read",
        "teams:read",
        "tpv-floor-elements:delete",
        "tpv-floor-elements:write",
        "tpv-reports:read",
        "tpv-sim-custody:accept",
        "tpv-sim-custody:reject",
        "tpv-tables:delete",
        "tpv-tables:write",
        "tpv-time-entries:read",
        "tpv:read",
        "upsells:read",
    )

    @Test
    fun `ningun permiso que el POS puede toparse se queda sin etiqueta`() {
        val sinEtiqueta = permisosQueLasRutasDeMobileRechazan.filter { PermissionLabels.labelOrNull(it) == null }
        assertTrue(
            "Estos permisos los rechaza una ruta que la app llama y saldrían sin explicar: $sinEtiqueta",
            sinEtiqueta.isEmpty(),
        )
    }

    @Test
    fun `los recursos que el founder pidio cubrir estan completos`() {
        // Espejo de INDIVIDUAL_PERMISSIONS_BY_RESOURCE del server para los
        // recursos que de verdad aparecen en los modales del POS.
        val porRecurso = mapOf(
            "tpv" to listOf("tpv:read", "tpv:create", "tpv:update", "tpv:delete", "tpv:command"),
            "payments" to listOf(
                "payments:read", "payments:create", "payments:refund",
                "payments:routing-read", "payments:routing-manage",
            ),
            "orders" to listOf(
                "orders:read", "orders:create", "orders:update",
                "orders:cancel", "orders:comp", "orders:void", "orders:merge",
            ),
            "tables" to listOf("tables:read", "tables:update", "tables:manage-all"),
            "shifts" to listOf("shifts:read", "shifts:create", "shifts:update", "shifts:delete", "shifts:close"),
            "customers" to listOf(
                "customers:read", "customers:create", "customers:update",
                "customers:delete", "customers:settle-balance",
            ),
            "menu" to listOf("menu:read", "menu:create", "menu:update", "menu:delete", "menu:import"),
            "reservations" to listOf(
                "reservations:read", "reservations:create",
                "reservations:update", "reservations:cancel",
            ),
            "creditPacks" to listOf(
                "creditPacks:read", "creditPacks:create",
                "creditPacks:update", "creditPacks:delete",
            ),
            "discounts" to listOf(
                "discounts:read", "discounts:create", "discounts:update",
                "discounts:delete", "discounts:apply",
            ),
            "coupons" to listOf(
                "coupons:read", "coupons:create", "coupons:update",
                "coupons:delete", "coupons:redeem",
            ),
            "inventory" to listOf(
                "inventory:read", "inventory:create", "inventory:update",
                "inventory:delete", "inventory:adjust",
            ),
            "tpv-reports" to listOf("tpv-reports:read", "tpv-reports:export", "tpv-reports:pay-later-aging"),
        )
        val huecos = porRecurso.values.flatten().filter { PermissionLabels.labelOrNull(it) == null }
        assertTrue("Faltan etiquetas para: $huecos", huecos.isEmpty())
    }

    @Test
    fun `las etiquetas hablan de la accion, no repiten el codigo tecnico`() {
        PermissionLabels.all().forEach { (permiso, etiqueta) ->
            assertTrue("«$etiqueta» no puede traer el código de $permiso", !etiqueta.contains(":"))
            assertTrue("«$etiqueta» está vacía", etiqueta.isNotBlank())
            assertTrue("«$etiqueta» debe ir en minúscula, va dentro de una frase", etiqueta.first().isLowerCase())
        }
        assertNotNull(PermissionLabels.all()["orders:merge"])
    }
}

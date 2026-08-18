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
    // 🔴 Antes esto era una lista escrita a mano con un comentario que ya admitía
    // el hueco: "si el server agrega un checkPermission nuevo, este test NO se
    // entera solo". No se enteró. El server estrenó `estimates:create` y
    // `orders:cancel-unpaid`, el modal volvió a enseñar el código pelón —el
    // síntoma exacto que el founder reportó en vivo— y la suite siguió verde. Al
    // derivar la lista de los `checkPermission(...)` reales aparecieron CINCO
    // huecos, no los tres que veníamos contando: `creditPacks:sell` y
    // `creditPacks:redeem` llevaban tiempo rechazando sin traducción.
    //
    // Ahora la lista la genera `scripts/regenerar-permisos-reales.mjs`. Un
    // comentario que documenta un punto ciego no lo cierra: lo vuelve tolerado.

    @Test
    fun `ningun permiso que el POS puede toparse se queda sin etiqueta`() {
        val rechazables = PermisosDeRutasDelServer.TODOS
        // Sin esto, un fixture vacío pasaría el test sin comprobar nada.
        assertTrue("El fixture de rutas llegó vacío o truncado: ${rechazables.size}", rechazables.size >= 60)

        val sinEtiqueta = rechazables.filter { PermissionLabels.labelOrNull(it) == null }
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
                "orders:cancel", "orders:cancel-unpaid", "orders:comp", "orders:void", "orders:merge",
            ),
            "tables" to listOf("tables:read", "tables:update", "tables:manage-all", "tables:pay-any"),
            "shifts" to listOf("shifts:read", "shifts:create", "shifts:update", "shifts:delete", "shifts:close"),
            "tpv-shifts" to listOf("tpv-shifts:create", "tpv-shifts:close"),
            "estimates" to listOf("estimates:create"),
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
                "creditPacks:read", "creditPacks:create", "creditPacks:update",
                "creditPacks:delete", "creditPacks:sell", "creditPacks:redeem",
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

    // MARK: - Los 5 nombres que el server estrenó (2026-08-17/18)

    @Test
    fun `los 5 permisos nuevos del server dicen la accion, no el codigo`() {
        // Sin estas, el modal decía «tpv-shifts:create» — el founder lo reportó en
        // vivo ("no explica la causa real"). Van en minúscula porque se insertan
        // dentro de una frase ya hecha: "…que te active «abrir un turno»".
        assertEquals("hacer un presupuesto", PermissionLabels.of("estimates:create"))
        assertEquals("abrir un turno", PermissionLabels.of("tpv-shifts:create"))
        assertEquals("cerrar el turno", PermissionLabels.of("tpv-shifts:close"))
        assertEquals("cancelar una cuenta sin cobrar", PermissionLabels.of("orders:cancel-unpaid"))
        assertEquals("cobrar la mesa de otro mesero", PermissionLabels.of("tables:pay-any"))

        // Y no se confunden con los que ya existían y significan otra cosa.
        assertEquals("cancelar la cuenta", PermissionLabels.of("orders:cancel"))
        assertEquals("modificar mesas de otro mesero", PermissionLabels.of("tables:manage-all"))
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

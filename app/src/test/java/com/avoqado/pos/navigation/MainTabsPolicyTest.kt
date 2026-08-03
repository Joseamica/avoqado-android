package com.avoqado.pos.navigation

import com.avoqado.pos.settings.domain.PosMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué pestañas ve el usuario abajo.
 *
 * El defecto que motiva estos tests se vio en una tablet: al activar reservas
 * en un restaurante, **Mesas desaparecía de la barra**. El calendario exigía
 * además estar en modo Reservas, así que activar el interruptor cambiaba el
 * modo y el local perdía el plano de mesas por tomar citas. Decisión del
 * founder (2026-08-03): tienen que convivir.
 */
class MainTabsPolicyTest {

    private fun tabs(
        reservas: Boolean = false,
        planLoPermite: Boolean = true,
        modo: PosMode = PosMode.RETAIL,
        pos: Boolean = true,
        inventario: Boolean = true,
        transacciones: Boolean = true,
    ) = MainTabsPolicy.visibleTabs(
        reservationsEnabled = reservas,
        planAllowsReservations = planLoPermite,
        posMode = modo,
        canAccessPOS = pos,
        canAccessInventory = inventario,
        canAccessTransactions = transacciones,
    )

    // MARK: - Convivencia (el defecto)

    @Test
    fun `un restaurante que tambien reserva ve Mesas Y Calendario`() {
        val t = tabs(reservas = true, modo = PosMode.RESTAURANT)
        assertTrue("Mesas no puede perderse por activar reservas", t.contains(MainTab.TABLES))
        assertTrue("y el calendario tiene que estar", t.contains(MainTab.CALENDAR))
    }

    @Test
    fun `en un restaurante manda el plano de mesas, no la agenda`() {
        // Un mesero abre la app para ver el salón; la agenda es secundaria.
        val t = tabs(reservas = true, modo = PosMode.RESTAURANT)
        assertTrue(t.indexOf(MainTab.TABLES) < t.indexOf(MainTab.CALENDAR))
    }

    @Test
    fun `en un negocio de citas manda la agenda`() {
        val t = tabs(reservas = true, modo = PosMode.RESERVATIONS)
        assertEquals(MainTab.CALENDAR, t.first())
    }

    // MARK: - Cada pestaña con su propia condición

    @Test
    fun `sin reservas activas no hay Calendario`() {
        assertFalse(tabs(reservas = false, modo = PosMode.RESTAURANT).contains(MainTab.CALENDAR))
    }

    @Test
    fun `si el plan no incluye reservas el interruptor local no basta`() {
        // El gate del plan va ANDed: activar el toggle en un local sin la
        // función contratada no puede colar la pestaña.
        val t = tabs(reservas = true, planLoPermite = false, modo = PosMode.RESTAURANT)
        assertFalse(t.contains(MainTab.CALENDAR))
        assertTrue("y no puede llevarse Mesas por delante", t.contains(MainTab.TABLES))
    }

    @Test
    fun `un retail con citas ve Calendario pero no Mesas`() {
        // Una estética o un spa: agenda sí, plano de mesas no.
        val t = tabs(reservas = true, modo = PosMode.RETAIL)
        assertTrue(t.contains(MainTab.CALENDAR))
        assertFalse(t.contains(MainTab.TABLES))
    }

    @Test
    fun `un retail sin citas no ve ninguna de las dos`() {
        val t = tabs(reservas = false, modo = PosMode.RETAIL)
        assertFalse(t.contains(MainTab.CALENDAR))
        assertFalse(t.contains(MainTab.TABLES))
    }

    // MARK: - Permisos

    @Test
    fun `quien no puede vender tampoco ve Mesas`() {
        // Mesas es una puerta al cobro: dejarla sin el permiso de POS enseñaría
        // una pantalla desde la que no se puede hacer nada.
        val t = tabs(reservas = true, modo = PosMode.RESTAURANT, pos = false)
        assertFalse(t.contains(MainTab.TABLES))
        assertFalse(t.contains(MainTab.CHECKOUT))
        assertTrue("el calendario no depende de vender", t.contains(MainTab.CALENDAR))
    }

    @Test
    fun `sin permisos de inventario ni ventas esas pestanas no salen`() {
        val t = tabs(inventario = false, transacciones = false)
        assertFalse(t.contains(MainTab.INVENTORY))
        assertFalse(t.contains(MainTab.TRANSACTIONS))
    }

    // MARK: - Invariantes de la barra

    @Test
    fun `siempre queda una salida, aunque no haya permisos`() {
        // Sin "Más" alguien sin permisos se queda encerrado sin poder ni salir
        // de la sesión.
        val t = tabs(pos = false, inventario = false, transacciones = false)
        assertTrue(t.contains(MainTab.MORE))
    }

    @Test
    fun `la barra no se llena de pestanas aunque todo este activo`() {
        // Siete es el tope razonable en la tablet; pasar de ahí las aplasta.
        val t = tabs(reservas = true, modo = PosMode.RESTAURANT)
        assertTrue("demasiadas pestañas: ${t.size}", t.size <= 7)
    }

    @Test
    fun `no hay pestanas repetidas`() {
        val t = tabs(reservas = true, modo = PosMode.RESTAURANT)
        assertEquals(t.size, t.toSet().size)
    }
}

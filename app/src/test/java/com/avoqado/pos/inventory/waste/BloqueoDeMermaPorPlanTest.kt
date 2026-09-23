package com.avoqado.pos.inventory.waste

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.inventory.waste.data.BloqueoDeMermaPorPlan
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El bloqueo de plan de la merma: POR SUCURSAL, en disco, y sólo el servidor lo levanta.
 * Espejo exacto de `BloqueoDeMermaPorPlanTests.swift` de avoqado-ios.
 */
class BloqueoDeMermaPorPlanTest {

    private var enDisco: Set<String> = emptySet()
    private val almacen = mockk<SecureStorage> {
        every { venuesConMermaBloqueada } answers { enDisco }
        every { venuesConMermaBloqueada = any() } answers { enDisco = firstArg() }
    }
    private val conexion = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private val red = mockk<ConnectivityMonitor> {
        every { isConnected } returns conexion
        every { isServerReachable } returns servidor
    }
    private val catalogo = CatalogoDeMermaFalso()

    private fun bloqueo() = BloqueoDeMermaPorPlan(almacen, red, catalogo)

    /**
     * 🔴 Spec §5: un bloqueo pegado de OTRA sucursal escondería una función que sí está pagada
     * aquí.
     */
    @Test
    fun `el bloqueo es por sucursal`() {
        val bloqueo = bloqueo()
        bloqueo.bloquear("venue-a")
        assertTrue(bloqueo.estaBloqueado("venue-a"))
        assertFalse(bloqueo.estaBloqueado("venue-b"))
        assertFalse(bloqueo.estaBloqueado(null))
    }

    /** Uno que se pierde al reiniciar dejaría de avisar. */
    @Test
    fun `el bloqueo sobrevive a reiniciar la app`() {
        bloqueo().bloquear("venue-a")
        assertTrue(bloqueo().estaBloqueado("venue-a"))
    }

    @Test
    fun `con red, si el servidor dice que el plan la incluye, se levanta`() = runTest {
        val bloqueo = bloqueo()
        bloqueo.bloquear("venue-a")
        catalogo.plan = true

        bloqueo.revalidar("venue-a")

        assertFalse(bloqueo.estaBloqueado("venue-a"))
        assertEquals(emptySet<String>(), enDisco)
    }

    @Test
    fun `con red, si el servidor dice que no, queda bloqueado`() = runTest {
        val bloqueo = bloqueo()
        catalogo.plan = false

        bloqueo.revalidar("venue-a")

        assertTrue(bloqueo.estaBloqueado("venue-a"))
    }

    /** Un 5xx o un 403 que no es de plan no dicen nada del plan: nunca se levanta a ciegas. */
    @Test
    fun `sin una respuesta clara el bloqueo se conserva`() = runTest {
        val bloqueo = bloqueo()
        bloqueo.bloquear("venue-a")
        catalogo.plan = null

        bloqueo.revalidar("venue-a")

        assertTrue(bloqueo.estaBloqueado("venue-a"))
    }

    /** Sin red no se pregunta: se conserva lo último que dijo el servidor. */
    @Test
    fun `sin red no se pregunta y el bloqueo se conserva`() = runTest {
        val bloqueo = bloqueo()
        bloqueo.bloquear("venue-a")
        catalogo.plan = true
        conexion.value = false

        bloqueo.revalidar("venue-a")

        assertTrue(bloqueo.estaBloqueado("venue-a"))
        assertEquals(0, catalogo.consultasDePlan)
    }
}

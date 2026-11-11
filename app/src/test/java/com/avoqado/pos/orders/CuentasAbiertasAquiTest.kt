package com.avoqado.pos.orders

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.orders.data.CopiaDeCuentas
import com.avoqado.pos.orders.data.CuentasAbiertasAqui
import com.avoqado.pos.orders.data.Lectura
import com.avoqado.pos.orders.data.model.OrderSummary
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D12 (Tarea 6, Codex r1 #6): la marca «la abrió aquí», por sucursal. Es lo que prueba que ESTA caja abrió la cuenta; sin ella
 * la cuenta queda en gris. El «disco» de memoria es el de `CopiaDeCuentasTest` (también `loadDurable`).
 */
class CuentasAbiertasAquiTest {
    private val disco = mutableMapOf<String, PayloadCache.Cached>()
    private var falloAlLeer: Exception? = null
    private var falloAlGuardar = false
    private val cache = mockk<PayloadCache> {
        coEvery { load(any(), any()) } answers { if (falloAlLeer != null) null else disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { loadDurable(any(), any()) } answers { falloAlLeer?.let { throw it }; disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { saveDurable(any(), any(), any()) } answers {
            if (falloAlGuardar) throw IllegalStateException("disco lleno")
            disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0)
        }
    }
    private var reloj = 1_000_000_000_000L
    private val copia = CopiaDeCuentas(cache).also { it.ahora = { reloj } }
    private val marcas = CuentasAbiertasAqui(cache, copia).also { it.ahora = { reloj } }
    private fun c(id: String) = OrderSummary(id = id, status = "PENDING", paymentStatus = "PENDING", createdAt = "2026-11-10T18:00:00Z")
    private fun dias(n: Int) = n * 24 * 60 * 60 * 1000L

    @Test fun `marcar y leer por sucursal`() = runTest {
        marcas.marcar("v1", "A")
        assertTrue(marcas.laAbrioAqui("v1", "A"))
        assertFalse("la marca de la sucursal A nunca autoriza en la B", marcas.laAbrioAqui("v2", "A"))
        assertFalse(marcas.laAbrioAqui("v1", "B"))
    }

    @Test fun `no caduca mientras la cuenta siga en la copia`() = runTest {
        marcas.marcar("v1", "A")
        reloj += dias(30)
        copia.alLeer("v1", listOf(c("A")), Lectura.SUELTA) // sigue abierta: la «Pedidos» de hoy la trajo
        marcas.marcar("v1", "B")
        assertTrue(marcas.laAbrioAqui("v1", "A"))
        assertTrue(marcas.laAbrioAqui("v1", "B"))
    }

    @Test fun `se poda con mas de 7 dias y fuera de la copia`() = runTest {
        marcas.marcar("v1", "A")
        marcas.marcar("v1", "reciente")
        reloj += dias(8)
        marcas.marcar("v1", "reciente") // re-marcada hoy: la nueva hora manda
        marcas.marcar("v1", "B")
        assertFalse(marcas.laAbrioAqui("v1", "A"))
        assertTrue(marcas.laAbrioAqui("v1", "reciente"))
        assertTrue(marcas.laAbrioAqui("v1", "B"))
    }

    @Test fun `un disco que falla no tumba a quien marca`() = runTest {
        marcas.marcar("v1", "A")

        falloAlGuardar = true
        marcas.marcar("v1", "B") // no lanza
        falloAlGuardar = false
        assertFalse("sin disco no hay marca: gris, nunca de más", marcas.laAbrioAqui("v1", "B"))

        falloAlLeer = java.io.IOException("disco")
        marcas.marcar("v1", "C") // no lanza, y no escribe encima de lo que no pudo leer
        assertFalse(marcas.laAbrioAqui("v1", "A"))
        falloAlLeer = null
        assertTrue("las marcas de antes siguen", marcas.laAbrioAqui("v1", "A"))
        assertFalse(marcas.laAbrioAqui("v1", "C"))
    }
}

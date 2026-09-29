package com.avoqado.pos.kds.data

import com.avoqado.pos.core.data.lan.KdsComanda
import com.avoqado.pos.core.data.lan.KdsComandaItem
import com.avoqado.pos.kds.data.local.KdsTicketLocalEntity
import com.avoqado.pos.kds.data.local.KdsTicketsLocalesDao
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.KDSOrderItem
import com.avoqado.pos.kds.domain.KDSOrderStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La pantalla guarda ANTES de acusar y traduce entre el cable, la fila y el dominio (3.5, D8). El DAO es un mock: el SQL lo prueba `KdsLanSqlTest`. */
class KdsTicketsLocalesStoreTest {

    private val dao = mockk<KdsTicketsLocalesDao>(relaxed = true)
    private val store = KdsTicketsLocalesStore(dao)

    private val comanda = KdsComanda(
        venueId = "v1", deviceId = "tablet-1", sourceKey = "round:rk:st-barra", stationId = "st-barra", orderNumber = "77",
        orderType = "Mesa 8 · Aperitivos", createdAtMillis = 5, items = listOf(KdsComandaItem("a", "Café", 2, listOf("Sin azúcar"), null)),
    )

    @Test
    fun `unir escribe la fila con los renglones en JSON y devuelve true - el acuse sale solo entonces`() = runTest {
        val fila = slot<KdsTicketLocalEntity>()
        coEvery { dao.unir(capture(fila)) } returns Unit
        assertTrue(store.unir(comanda, ahora = 100))
        assertEquals("round:rk:st-barra", fila.captured.sourceKey)
        assertEquals("st-barra", fila.captured.stationId)
        assertEquals("Mesa 8 · Aperitivos", fila.captured.orderType)
        assertEquals(100L, fila.captured.recibidaEnMillis)
        assertNull(fila.captured.listaEnMillis)
        assertTrue(fila.captured.itemsJson, fila.captured.itemsJson.contains("\"productName\":\"Café\""))
    }

    @Test
    fun `P1 si el disco falla unir devuelve false y NO se acusa`() = runTest {
        coEvery { dao.unir(any()) } throws IllegalStateException("disco lleno")
        assertFalse(store.unir(comanda))
    }

    @Test
    fun `marcar lista de una comanda del servidor crea la fila con listaEnMillis (no la habia)`() = runTest {
        coEvery { dao.marcarLista("sale:x:st-barra", 50) } returns 0
        // «No la había»: explícito — un mock relajado de MockK devuelve un objeto, no `null`, para un retorno anulable.
        coEvery { dao.porFolio("sale:x:st-barra") } returns null
        val orden = KDSOrder(id = "k1", orderNumber = "1", orderType = "En tienda", items = listOf(KDSOrderItem("i", "Taco", 1)), createdAt = 1, status = KDSOrderStatus.NEW, sourceKey = "sale:x:st-barra", printStationId = "st-barra")
        val fila = slot<KdsTicketLocalEntity>()
        coEvery { dao.guardar(capture(fila)) } returns Unit

        store.marcarLista(orden, "v1", "st-barra", ahora = 50)

        assertEquals(50L, fila.captured.listaEnMillis)
        assertEquals("sale:x:st-barra", fila.captured.sourceKey)
    }

    @Test
    fun `marcar lista de una comanda que llego por WiFi solo marca (la fila ya existe)`() = runTest {
        coEvery { dao.marcarLista("round:rk:st-barra", 50) } returns 1
        val orden = KDSOrder(id = "lan:round:rk:st-barra", orderNumber = "77", orderType = "Mesa 8", items = emptyList(), createdAt = 1, status = KDSOrderStatus.NEW, sourceKey = "round:rk:st-barra", printStationId = "st-barra")
        store.marcarLista(orden, "v1", "st-barra", ahora = 50)
        coVerify(exactly = 0) { dao.guardar(any()) }
    }

    @Test
    fun `deLaEstacion traduce la fila al dominio y purgar usa la vigencia de 12 h`() = runTest {
        every { dao.deLaEstacion("v1", "st-barra") } returns flowOf(
            listOf(KdsTicketLocalEntity("round:rk:st-barra", "v1", "st-barra", "77", "Mesa 8", """[{"id":"a","productName":"Café","quantity":2,"modifiers":[],"notes":null}]""", 5, null)),
        )
        val locales = store.deLaEstacion("v1", "st-barra").first()
        assertEquals(listOf("Café"), locales.single().items.map { it.productName })
        assertEquals("a", locales.single().items.single().id)

        store.purgar("v1", ahora = 100_000_000)
        coVerify { dao.purgar("v1", 100_000_000 - KdsTicketsLocalesStore.VIGENCIA_MS) }
    }
}

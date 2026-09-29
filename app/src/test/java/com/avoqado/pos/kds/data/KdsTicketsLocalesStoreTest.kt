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
        coEvery { dao.porFolio(comanda.sourceKey) } returns null
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
        coEvery { dao.porFolio(comanda.sourceKey) } returns null
        coEvery { dao.unir(any()) } throws IllegalStateException("disco lleno")
        assertFalse(store.unir(comanda))
    }

    // MARK: - Task 8b: un curso nuevo sobre una fila YA LISTA no se guarda ni se acusa (hallazgo de pérdida)

    private fun filaLista(itemsJson: String, listaEnMillis: Long? = 20) = KdsTicketLocalEntity(
        sourceKey = comanda.sourceKey, venueId = "v1", stationId = "st-barra", orderNumber = "77", orderType = "Mesa 8 · Aperitivos",
        itemsJson = itemsJson, recibidaEnMillis = 10, listaEnMillis = listaEnMillis,
    )

    @Test
    fun `P1 un curso con un renglon NUEVO sobre una fila YA LISTA no se guarda ni se acusa`() = runTest {
        coEvery { dao.porFolio(comanda.sourceKey) } returns filaLista("""[{"id":"a","productName":"Café","quantity":2,"modifiers":["Sin azúcar"],"notes":null}]""")
        val conCursoNuevo = comanda.copy(items = comanda.items + KdsComandaItem("b", "Pan", 1, emptyList(), null))

        assertFalse(store.unir(conCursoNuevo, ahora = 100))
        coVerify(exactly = 0) { dao.unir(any()) }
    }

    @Test
    fun `reenviar los MISMOS renglones sobre una fila YA LISTA sigue guardando y acusando (sin cambios)`() = runTest {
        coEvery { dao.porFolio(comanda.sourceKey) } returns filaLista("""[{"id":"a","productName":"Café","quantity":2,"modifiers":["Sin azúcar"],"notes":null}]""")
        coEvery { dao.unir(any()) } returns Unit

        assertTrue(store.unir(comanda, ahora = 100))
        coVerify(exactly = 1) { dao.unir(any()) }
    }

    @Test
    fun `un curso nuevo sobre una fila PENDIENTE se sigue mezclando y acusando como hoy`() = runTest {
        coEvery { dao.porFolio(comanda.sourceKey) } returns filaLista(
            """[{"id":"a","productName":"Café","quantity":2,"modifiers":["Sin azúcar"],"notes":null}]""",
            listaEnMillis = null,
        )
        coEvery { dao.unir(any()) } returns Unit
        val conCursoNuevo = comanda.copy(items = comanda.items + KdsComandaItem("b", "Pan", 1, emptyList(), null))

        assertTrue(store.unir(conCursoNuevo, ahora = 100))
        coVerify(exactly = 1) { dao.unir(any()) }
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

    /**
     * I2 (revisión T8): la vigencia de 12 h cuenta desde que ESTE aparato guardó la marca, no desde que el servidor creó
     * la comanda. Con `createdAt`, marcar sin red una comanda de ayer creaba una fila ya vencida: la purga de cada minuto
     * la borraba y la comanda volvía al tablero, todavía sin red.
     */
    @Test
    fun `P1 la marca LISTO creada para una comanda del servidor cuenta su vigencia desde ahora, no desde su creacion`() = runTest {
        coEvery { dao.marcarLista("sale:vieja:st-barra", 90_000_000) } returns 0
        coEvery { dao.porFolio("sale:vieja:st-barra") } returns null
        val deAyer = KDSOrder(id = "k1", orderNumber = "1", orderType = "En tienda", items = emptyList(), createdAt = 1, status = KDSOrderStatus.NEW, sourceKey = "sale:vieja:st-barra", printStationId = "st-barra")
        val fila = slot<KdsTicketLocalEntity>()
        coEvery { dao.guardar(capture(fila)) } returns Unit

        store.marcarLista(deAyer, "v1", "st-barra", ahora = 90_000_000)

        assertEquals(90_000_000L, fila.captured.recibidaEnMillis)
        assertEquals(90_000_000L, fila.captured.listaEnMillis)
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

    // MARK: - Ronda 1: el servidor gana también en el disco, y «Deshacer» quita la marca

    @Test
    fun `retirar pendientes manda los folios que devolvio el servidor, y sin folios no toca el disco`() = runTest {
        store.retirarPendientes(listOf("sale:a:st-barra", "sale:b:st-barra"))
        coVerify(exactly = 1) { dao.retirarPendientes(listOf("sale:a:st-barra", "sale:b:st-barra")) }
        store.retirarPendientes(emptyList())
        coVerify(exactly = 1) { dao.retirarPendientes(any()) }
    }

    @Test
    fun `quitar lista manda el folio`() = runTest {
        store.quitarLista("sale:k9:st-barra")
        coVerify(exactly = 1) { dao.quitarLista("sale:k9:st-barra") }
    }

    @Test
    fun `P1 un disco que falla al retirar o al quitar la marca no tumba la pantalla`() = runTest {
        coEvery { dao.retirarPendientes(any()) } throws IllegalStateException("disco lleno")
        coEvery { dao.quitarLista(any()) } throws IllegalStateException("disco lleno")
        store.retirarPendientes(listOf("sale:a:st-barra"))
        store.quitarLista("sale:a:st-barra")
    }
}

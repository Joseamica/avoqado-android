package com.avoqado.pos.kds.data

import android.util.Log
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
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
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
        val ids = slot<Set<String>>()
        coEvery { dao.unir(capture(fila), capture(ids)) } returns true
        assertTrue(store.unir(comanda, ahora = 100))
        assertEquals("round:rk:st-barra", fila.captured.sourceKey)
        assertEquals("st-barra", fila.captured.stationId)
        assertEquals("Mesa 8 · Aperitivos", fila.captured.orderType)
        assertEquals(100L, fila.captured.recibidaEnMillis)
        assertNull(fila.captured.listaEnMillis)
        assertTrue(fila.captured.itemsJson, fila.captured.itemsJson.contains("\"productName\":\"Café\""))
        // Ronda 1 (M4): los ids entrantes salen de los renglones del dominio, sin releer el JSON recién codificado.
        assertEquals(setOf("a"), ids.captured)
    }

    @Test
    fun `P1 si el disco falla unir devuelve false y NO se acusa`() = runTest {
        coEvery { dao.unir(any(), any()) } throws IllegalStateException("disco lleno")
        assertFalse(store.unir(comanda))
    }

    // MARK: - Task 8b / ronda 1 (I1): la regla del curso nuevo sobre una fila LISTA vive en la transacción del DAO
    // (`KdsLanSqlTest` la ejecuta contra SQLite de verdad); aquí el store sólo la respeta: false del DAO ⇒ sin acuse.

    @Test
    fun `P1 si el DAO rechaza un curso con renglon NUEVO sobre una fila YA LISTA el store no acusa y lo deja en el log`() = runTest {
        val ids = slot<Set<String>>()
        coEvery { dao.unir(any(), capture(ids)) } returns false
        val conCursoNuevo = comanda.copy(items = comanda.items + KdsComandaItem("b", "Pan", 1, emptyList(), null))

        mockkStatic(Log::class)
        try {
            every { Log.i(any(), any()) } returns 0
            assertFalse(store.unir(conCursoNuevo, ahora = 100))
            verify(exactly = 1) { Log.i(any(), match { it.contains("round:rk:st-barra") && it.contains("LISTA") }) }
        } finally {
            unmockkStatic(Log::class)
        }
        assertEquals(setOf("a", "b"), ids.captured)
        // La decisión es del DAO, en UNA transacción: el store ya no lee la fila por su cuenta.
        coVerify(exactly = 0) { dao.porFolio(any()) }
    }

    /**
     * Revisión final (I2): el LISTO sin red es UNA transacción del DAO ([KdsTicketsLocalesDao.marcarListaOCrear]; su SQL
     * lo ejecuta `KdsLanSqlTest`). Leer y escribir desde el store en tres pasos dejaba colarse un `unir` entre la lectura y
     * el `REPLACE`, que pisaba el curso ya acusado con la sombra.
     */
    @Test
    fun `P1 marcarLista delega en UNA transaccion del DAO - el store nunca lee ni escribe por su cuenta`() = runTest {
        val orden = KDSOrder(id = "k1", orderNumber = "1", orderType = "En tienda", items = listOf(KDSOrderItem("i", "Taco", 1)), createdAt = 1, status = KDSOrderStatus.NEW, sourceKey = "sale:x:st-barra", printStationId = "st-barra")
        val sombra = slot<KdsTicketLocalEntity>()
        coEvery { dao.marcarListaOCrear(capture(sombra), 50) } returns Unit

        store.marcarLista(orden, "v1", "st-barra", ahora = 50)

        assertEquals(50L, sombra.captured.listaEnMillis)
        assertEquals("sale:x:st-barra", sombra.captured.sourceKey)
        assertEquals("st-barra", sombra.captured.stationId)
        assertTrue(sombra.captured.itemsJson, sombra.captured.itemsJson.contains("\"productName\":\"Taco\""))
        coVerify(exactly = 0) { dao.guardar(any()) }
        coVerify(exactly = 0) { dao.porFolio(any()) }
        coVerify(exactly = 0) { dao.marcarLista(any(), any()) }
    }

    /**
     * I2 (revisión T8): la vigencia de 12 h cuenta desde que ESTE aparato guardó la marca, no desde que el servidor creó
     * la comanda. Con `createdAt`, marcar sin red una comanda de ayer creaba una fila ya vencida: la purga de cada minuto
     * la borraba y la comanda volvía al tablero, todavía sin red.
     */
    @Test
    fun `P1 la marca LISTO creada para una comanda del servidor cuenta su vigencia desde ahora, no desde su creacion`() = runTest {
        val deAyer = KDSOrder(id = "k1", orderNumber = "1", orderType = "En tienda", items = emptyList(), createdAt = 1, status = KDSOrderStatus.NEW, sourceKey = "sale:vieja:st-barra", printStationId = "st-barra")
        val sombra = slot<KdsTicketLocalEntity>()
        coEvery { dao.marcarListaOCrear(capture(sombra), 90_000_000) } returns Unit

        store.marcarLista(deAyer, "v1", "st-barra", ahora = 90_000_000)

        assertEquals(90_000_000L, sombra.captured.recibidaEnMillis)
        assertEquals(90_000_000L, sombra.captured.listaEnMillis)
    }

    @Test
    fun `KDS 3_6 el tiempo de cada platillo se guarda en el disco y se lee de vuelta - fila vieja sin el campo queda en null`() = runTest {
        every { dao.deLaEstacion("v1", "st-barra") } returns flowOf(
            listOf(
                KdsTicketLocalEntity(
                    "round:rk:st-barra", "v1", "st-barra", "77", "Mesa 8",
                    """[{"id":"a","productName":"Guacamole","quantity":2,"modifiers":[],"notes":null,"course":"Aperitivos"},{"id":"b","productName":"Agua","quantity":1,"modifiers":[]}]""",
                    5, null,
                ),
            ),
        )
        assertEquals(listOf("Aperitivos", null), store.deLaEstacion("v1", "st-barra").first().single().items.map { it.course })

        val orden = KDSOrder(
            id = "k1", orderNumber = "1", orderType = "Mesa 8", items = listOf(KDSOrderItem("i", "Tacos", 1, course = "Principales")),
            createdAt = 1, status = KDSOrderStatus.NEW, sourceKey = "round:rk:st-barra", printStationId = "st-barra",
        )
        val sombra = slot<KdsTicketLocalEntity>()
        coEvery { dao.marcarListaOCrear(capture(sombra), 50) } returns Unit
        store.marcarLista(orden, "v1", "st-barra", ahora = 50)
        assertTrue(sombra.captured.itemsJson, sombra.captured.itemsJson.contains("\"course\":\"Principales\""))
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

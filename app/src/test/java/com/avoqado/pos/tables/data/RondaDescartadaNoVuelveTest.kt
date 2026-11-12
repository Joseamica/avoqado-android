package com.avoqado.pos.tables.data

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D29 (deuda A4 del D5, caso 9 del recorrido B3, 12-nov): la ronda que el servidor rechazó y el gerente marcó resuelta en «Operaciones
 * por revisar» volvía a la cola al reabrir la mesa, porque su borrador seguía en disco y `restoreDraft` encola de nuevo toda ronda
 * del borrador sin fila (la que la app no alcanzó a encolar). Marcarla resuelta ahora la saca del borrador ANTES de quitar su fila, y
 * una pantalla de la mesa con la ronda todavía en memoria no la vuelve a escribir. Borrador y cola REALES sobre la misma carpeta.
 */
class RondaDescartadaNoVuelveTest {
    private val root = java.nio.file.Files.createTempDirectory("d29").toFile()
    /** La carpeta que usa la cola (`filesDir/table-round-drafts`), la misma que la pantalla. */
    private val dir = File(root, "table-round-drafts")
    private fun store() = TableRoundDraftStore(dir)

    @After fun limpiar() { root.walkTopDown().forEach { it.setWritable(true) }; root.deleteRecursively() }

    private fun linea(id: String) = PendingLine(CartItem(id = id, type = CartItemType.ProductItem("p-$id"), name = "Cerveza Corona",
        unitPrice = 4500), null)

    private fun fila(id: String, tipo: String, payload: String) =
        SyncIntentEntity(id, "v", "staff", 1, tipo, payload, status = SyncIntentEntity.STATUS_REJECTED)

    private fun cola(dao: SyncIntentDao): SyncOutbox {
        val connectivity = mockk<ConnectivityMonitor>()
        every { connectivity.isConnected } returns MutableStateFlow(false)
        every { connectivity.isServerReachable } returns MutableStateFlow(false)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "d1"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.filesDir } returns root
        val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns "v" }
        return SyncOutbox(dao, mockk(relaxed = true), connectivity, storage, mockk(relaxed = true), context, mockk(relaxed = true))
    }

    // MARK: - El borrador

    @Test fun `una pantalla con la ronda descartada todavia en memoria no la revive al guardar`() {
        store().save("v", "o", emptyList(), mapOf("buena" to listOf(linea("a")), "rechazada" to listOf(linea("b"))), tomadaAqui = true)
        store().descartarRonda("v", "o", "rechazada")
        assertEquals(setOf("buena"), store().load("v", "o").rounds.keys)

        // La pantalla abierta guarda el borrador ENTERO (agregar un producto, leer la cuenta) con las rondas de su memoria.
        store().save("v", "o", listOf(linea("c")), mapOf("buena" to listOf(linea("a")), "rechazada" to listOf(linea("b")),
            "nueva" to listOf(linea("d"))))

        val frio = store().load("v", "o")
        assertEquals("la nueva sí, la descartada no", setOf("buena", "nueva"), frio.rounds.keys)
        assertFalse("rechazada" in frio.createdAtLocal)
        assertEquals("sus productos tampoco vuelven a «sin mandar»", listOf("c"), frio.pending.map { it.item.id })
        assertTrue(frio.tomadaAqui)
        assertEquals(setOf("rechazada"), frio.descartadas)
    }

    @Test fun `al promover el id local la ronda descartada sigue fuera y se puede descartar por cualquiera de los dos ids`() {
        store().save("v", "local", emptyList(), mapOf("r1" to listOf(linea("a")), "r2" to listOf(linea("b"))))
        store().descartarRonda("v", "local", "r2")
        store().promote("v", "local", "real")
        // Una pantalla vieja todavía con las dos rondas en memoria.
        store().save("v", "real", emptyList(), mapOf("r1" to listOf(linea("a")), "r2" to listOf(linea("b"))))
        assertEquals(setOf("r1"), store().load("v", "real").rounds.keys)

        store().descartarRonda("v", "local", "r1") // sigue la redirección del id local
        assertTrue(store().load("v", "real").rounds.isEmpty())
        assertEquals(setOf("r1", "r2"), store().load("v", "real").descartadas)
    }

    @Test fun `descartar una ronda que no esta en el borrador no escribe nada`() {
        store().descartarRonda("v", "sin-borrador", "x")
        assertTrue("no crea archivos", dir.listFiles().orEmpty().isEmpty())
        store().save("v", "o", emptyList(), mapOf("r1" to listOf(linea("a"))))
        store().descartarRonda("v", "o", "otra")
        assertEquals(setOf("r1"), store().load("v", "o").rounds.keys)
        assertTrue(store().load("v", "o").descartadas.isEmpty())
    }

    // MARK: - El descarte del gerente (la cola)

    @Test fun `marcar resuelta la ronda rechazada la saca del borrador ANTES de quitar su fila`() = runTest {
        store().save("v", "order-1", emptyList(), mapOf("buena" to listOf(linea("a")), "ronda-1" to listOf(linea("b"))), tomadaAqui = true)
        val dao = mockk<SyncIntentDao>(relaxed = true)
        coEvery { dao.findForVenue("v", "ronda-1") } returns fila("ronda-1", "ADD_ITEMS", """{"orderId":"order-1","items":[]}""")
        var seguiaEnElBorrador: Boolean? = null
        coEvery { dao.dismiss("ronda-1") } answers { seguiaEnElBorrador = "ronda-1" in store().load("v", "order-1").rounds }

        cola(dao).dismissRejected("v", "ronda-1")

        assertEquals("si la app muere al borrar la fila, el borrador ya no la tiene", false, seguiaEnElBorrador)
        val frio = store().load("v", "order-1")
        assertEquals("sólo sale la ronda descartada", setOf("buena"), frio.rounds.keys)
        assertTrue("la mesa sigue siendo de esta tablet", frio.tomadaAqui)
    }

    @Test fun `mesa abierta sin internet y ya promovida - la ronda se encuentra por su id local`() = runTest {
        store().save("v", "local-1", emptyList(), mapOf("ronda-1" to listOf(linea("b"))))
        store().promote("v", "local-1", "order-1")
        val dao = mockk<SyncIntentDao>(relaxed = true)
        coEvery { dao.findForVenue("v", "ronda-1") } returns fila("ronda-1", "ADD_ITEMS", """{"localOrderId":"local-1","items":[]}""")

        cola(dao).dismissRejected("v", "ronda-1")

        assertTrue(store().load("v", "order-1").rounds.isEmpty())
        coVerify(exactly = 1) { dao.dismiss("ronda-1") }
    }

    @Test fun `si el disco no deja sacarla del borrador, la fila rechazada se queda`() = runTest {
        store().save("v", "order-1", emptyList(), mapOf("ronda-1" to listOf(linea("b"))))
        val dao = mockk<SyncIntentDao>(relaxed = true)
        coEvery { dao.findForVenue("v", "ronda-1") } returns fila("ronda-1", "ADD_ITEMS", """{"orderId":"order-1","items":[]}""")
        assertTrue(dir.setWritable(false))

        val intento = runCatching { cola(dao).dismissRejected("v", "ronda-1") }

        assertTrue("el gerente ve «No se pudo marcar como resuelta»", intento.isFailure)
        coVerify(exactly = 0) { dao.dismiss(any()) }
        dir.setWritable(true)
        assertEquals(setOf("ronda-1"), store().load("v", "order-1").rounds.keys)
    }

    @Test fun `un rechazo que no es ronda no toca el borrador`() = runTest {
        store().save("v", "order-1", emptyList(), mapOf("ronda-1" to listOf(linea("b"))))
        val dao = mockk<SyncIntentDao>(relaxed = true)
        coEvery { dao.findForVenue("v", "liberar-1") } returns fila("liberar-1", "CLEAR_TABLE", """{"orderId":"order-1","tableId":"t1"}""")

        cola(dao).dismissRejected("v", "liberar-1")

        assertEquals(setOf("ronda-1"), store().load("v", "order-1").rounds.keys)
        coVerify(exactly = 1) { dao.dismiss("liberar-1") }
    }
}

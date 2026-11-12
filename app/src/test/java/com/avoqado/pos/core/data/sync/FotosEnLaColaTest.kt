package com.avoqado.pos.core.data.sync

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.tables.data.OrderDetail
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * D5 · B2b, punto de paso ② y R5-1, con la cola REAL (DAO de prueba) y el almacén de fotos REAL en disco: lo que entra a la cola y no
 * es ronda, cobro ni cocina borra la foto ANTES de escribirse; un rechazo o un descarte de algo de la mesa también.
 */
class FotosEnLaColaTest {
    private val dir = java.nio.file.Files.createTempDirectory("fotos-cola").toFile()
    private val fotos = FotosDeMesas(dir)
    private val dao = mockk<SyncIntentDao>(relaxed = true)
    /** Si la foto seguía en disco cuando la fila se escribió (o se resolvió). */
    private val fotoAlEscribir = mutableListOf<Boolean>()

    @After fun limpiar() { dir.setWritable(true); dir.deleteRecursively() }

    private fun enDisco(orderId: String) = FotosDeMesas(dir).foto("v1", orderId)

    private fun tomar(orderId: String) {
        val lectura = fotos.empezarLectura(setOf(orderId))
        assertTrue(fotos.crearSi(FotoDeMesa("v1", orderId, emptyList(), "mesa-$orderId", OrderDetail(orderId, total = 100.0), 1, emptyList(),
            System.currentTimeMillis()), lectura))
        fotos.terminarLectura(lectura)
    }

    private fun cola(): SyncOutbox {
        val connectivity = mockk<ConnectivityMonitor>()
        every { connectivity.isConnected } returns MutableStateFlow(false)
        every { connectivity.isServerReachable } returns MutableStateFlow(false)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "d1"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.filesDir } returns dir
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns "v1"
        coEvery { dao.insertWithNextSeq(any()) } answers { fotoAlEscribir += enDisco("order-F") != null; 1L }
        coEvery { dao.resolve(any(), any(), any(), any(), any()) } answers { fotoAlEscribir += enDisco("order-F") != null }
        coEvery { dao.dismiss(any()) } answers { fotoAlEscribir += enDisco("order-F") != null }
        return SyncOutbox(dao, mockk<ApiService>(relaxed = true), connectivity, storage, mockk<CashDrawerRepository>(relaxed = true), context,
            mockk(relaxed = true), fotos = fotos)
    }

    private fun payload(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun `un descuento sin red de la mesa borra su foto antes de escribirse`() = runTest {
        tomar("order-F")
        cola().enqueue("v1", "APPLY_DISCOUNT", payload("""{"orderId":"order-F","discountId":"d-1"}"""))
        assertEquals(listOf(false), fotoAlEscribir)
        assertNull(enDisco("order-F"))
    }

    @Test fun `juntar sin red borra las fotos de las dos ordenes`() = runTest {
        tomar("order-F"); tomar("order-G")
        cola().enqueue("v1", "MERGE_ORDERS", payload("""{"orderId":"order-G","sourceOrderId":"order-F"}"""))
        assertNull(enDisco("order-F")); assertNull(enDisco("order-G"))
    }

    @Test fun `la ronda el cobro y la cocina no borran la foto`() = runTest {
        tomar("order-F")
        val subject = cola()
        subject.enqueue("v1", SyncIntentTypes.ADD_ITEMS, payload("""{"orderId":"order-F","items":[]}"""))
        subject.enqueue("v1", SyncIntentTypes.PAY_CASH, payload("""{"orderId":"order-F","amountCents":100,"tipCents":0}"""), id = "cobro-1")
        subject.enqueue("v1", SyncIntentTypes.KDS_TICKET_MARK, payload("""{"orderId":"order-F","action":"FALLBACK_PRINTED"}"""))
        assertEquals(listOf(true, true, true), fotoAlEscribir)
        assertNotNull(enDisco("order-F"))
    }

    @Test fun `si borrar el disco falla no se encola`() = runTest {
        tomar("order-F")
        assertTrue(dir.setWritable(false))
        try { cola().enqueue("v1", "COMP_ORDER", payload("""{"orderId":"order-F"}""")); fail("debió fallar") } catch (_: IOException) {}
        dir.setWritable(true)
        coVerify(exactly = 0) { dao.insertWithNextSeq(any()) }
        assertNotNull(enDisco("order-F"))
    }

    // MARK: - R5-1: una ronda rechazada o descartada destruye la foto

    private val ronda = SyncIntentEntity("ronda-1", "v1", "staff", 9, SyncIntentTypes.ADD_ITEMS, """{"orderId":"order-F","items":[]}""")

    @Test fun `R5-1 el rechazo de una ronda de la mesa borra su foto antes de guardarse`() = runTest {
        tomar("order-F")
        cola().conservarAck(SyncAck("ronda-1", "REJECTED", "BUSINESS_RULE", "Producto agotado"), ronda, "v1", "d1", "d1")
        assertEquals("borrada ANTES de resolverse la fila", listOf(false), fotoAlEscribir)
        assertNull(enDisco("order-F"))
    }

    @Test fun `R5-1 el acuse de una ronda no borra la foto, y si el disco falla el rechazo se guarda igual y el descarte no procede`() = runTest {
        tomar("order-F")
        cola().conservarAck(SyncAck("ronda-1", "ACKED"), ronda, "v1", "d1", "d1")
        assertNotNull("una ronda aceptada la cuenta la fórmula", enDisco("order-F"))
        assertTrue(dir.setWritable(false))
        // Codex b2b-r1 #1: el rechazo es un hecho; se guarda aunque la foto no se pueda borrar (la fila rechazada pone gris).
        cola().conservarAck(SyncAck("ronda-1", "REJECTED", "BUSINESS_RULE", "x"), ronda, "v1", "d1", "d1")
        coVerify(exactly = 1) { dao.resolve("ronda-1", SyncIntentEntity.STATUS_REJECTED, any(), any(), any()) }
        // …y «Marcar resuelta» no quita la fila mientras la foto siga ahí.
        coEvery { dao.findForVenue("v1", "ronda-1") } returns ronda.copy(status = SyncIntentEntity.STATUS_REJECTED)
        try { cola().dismissRejected("v1", "ronda-1"); fail("debió fallar") } catch (_: IOException) {}
        coVerify(exactly = 0) { dao.dismiss(any()) }
        dir.setWritable(true)
    }

    @Test fun `con el piloto apagado la cola no lee ni borra fotos aunque hayan quedado`() = runTest {
        tomar("order-F")
        var prendido = true
        val apagable = FotosDeMesas(dir, { prendido })
        assertNotNull(apagable.foto("v1", "order-F"))
        prendido = false
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val storage = mockk<SecureStorage>(relaxed = true)
        assertTrue(dir.setWritable(false)) // ni siquiera un disco que falla frena la cola
        SyncOutbox(dao, mockk<ApiService>(relaxed = true), connectivity, storage, mockk<CashDrawerRepository>(relaxed = true), context,
            mockk(relaxed = true), fotos = apagable).enqueue("v1", "APPLY_DISCOUNT", payload("""{"orderId":"order-F"}"""))
        dir.setWritable(true)
        coVerify(exactly = 1) { dao.insertWithNextSeq(any()) }
    }

    @Test fun `R5-1 descartar la ronda en la cuarentena borra la foto antes de quitar la fila`() = runTest {
        tomar("order-F")
        coEvery { dao.findForVenue("v1", "ronda-1") } returns ronda.copy(status = SyncIntentEntity.STATUS_REJECTED)
        cola().dismissRejected("v1", "ronda-1")
        assertEquals(listOf(false), fotoAlEscribir)
        assertNull(enDisco("order-F"))
    }
}

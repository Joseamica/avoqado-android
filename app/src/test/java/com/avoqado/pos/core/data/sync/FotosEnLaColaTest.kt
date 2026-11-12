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

    @After fun limpiar() { dir.walkTopDown().forEach { it.setWritable(true) }; dir.deleteRecursively() }

    private fun enDisco(orderId: String) = FotosDeMesas(dir).foto("v1", orderId)

    /** Un error de E/S que impide BORRAR la foto (su carpeta de sucursal no deja quitar archivos). */
    private suspend fun <T> sinPoderBorrar(bloque: suspend () -> T): T {
        val carpetas = dir.listFiles { f -> f.isDirectory }.orEmpty()
        carpetas.forEach { assertTrue(it.setWritable(false)) }
        try { return bloque() } finally { carpetas.forEach { it.setWritable(true) } }
    }

    private fun tomar(orderId: String) {
        val lectura = fotos.empezarLectura(setOf(orderId))
        assertTrue(fotos.crearSi(FotoDeMesa("v1", orderId, emptyList(), "mesa-$orderId", OrderDetail(orderId, total = 100.0), 1, emptyList(),
            System.currentTimeMillis()), lectura))
        fotos.terminarLectura(lectura)
    }

    private fun cola(): SyncOutbox = colaCon(fotos)

    private fun colaCon(fotos: FotosDeMesas): SyncOutbox {
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

    @Test fun `F1 piloto prendido si no se puede borrar la foto no se encola`() = runTest {
        tomar("order-F")
        sinPoderBorrar { try { cola().enqueue("v1", "COMP_ORDER", payload("""{"orderId":"order-F"}""")); fail("debió fallar") } catch (_: IOException) {} }
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

    @Test fun `F1 R5-1 el acuse no borra la foto, si no se puede borrar al rechazar, el rechazo se guarda y la foto queda muerta`() = runTest {
        tomar("order-F")
        val subject = cola()
        subject.conservarAck(SyncAck("ronda-1", "ACKED"), ronda, "v1", "d1", "d1")
        assertNotNull("una ronda aceptada la cuenta la fórmula", enDisco("order-F"))
        // Codex b2b-r1 #1: el rechazo es un hecho; se guarda aunque la foto no se pueda borrar.
        sinPoderBorrar { subject.conservarAck(SyncAck("ronda-1", "REJECTED", "BUSINESS_RULE", "x"), ronda, "v1", "d1", "d1") }
        coVerify(exactly = 1) { dao.resolve("ronda-1", SyncIntentEntity.STATUS_REJECTED, any(), any(), any()) }
        assertNull("muerta en memoria: no se usa", fotos.foto("v1", "order-F"))
        // Piloto prendido: «Marcar resuelta» no quita la fila mientras el archivo siga ahí.
        coEvery { dao.findForVenue("v1", "ronda-1") } returns ronda.copy(status = SyncIntentEntity.STATUS_REJECTED)
        sinPoderBorrar { try { subject.dismissRejected("v1", "ronda-1"); fail("debió fallar") } catch (_: IOException) {} }
        coVerify(exactly = 0) { dao.dismiss(any()) }
        subject.dismissRejected("v1", "ronda-1")
        coVerify(exactly = 1) { dao.dismiss("ronda-1") }
        assertNull(enDisco("order-F"))
    }

    @Test fun `F1 con el piloto apagado un borrado que falla no frena ni el encolado ni el descarte`() = runTest {
        tomar("order-F")
        val apagado = colaCon(FotosDeMesas(dir, { false }))
        coEvery { dao.findForVenue("v1", "ronda-1") } returns ronda.copy(status = SyncIntentEntity.STATUS_REJECTED)
        sinPoderBorrar {
            apagado.enqueue("v1", "APPLY_DISCOUNT", payload("""{"orderId":"order-F"}"""))
            apagado.dismissRejected("v1", "ronda-1")
        }
        coVerify(exactly = 1) { dao.insertWithNextSeq(any()) }
        coVerify(exactly = 1) { dao.dismiss("ronda-1") }
    }

    @Test fun `R5-1 descartar la ronda en la cuarentena borra la foto antes de quitar la fila`() = runTest {
        tomar("order-F")
        coEvery { dao.findForVenue("v1", "ronda-1") } returns ronda.copy(status = SyncIntentEntity.STATUS_REJECTED)
        cola().dismissRejected("v1", "ronda-1")
        assertEquals(listOf(false), fotoAlEscribir)
        assertNull(enDisco("order-F"))
    }
}

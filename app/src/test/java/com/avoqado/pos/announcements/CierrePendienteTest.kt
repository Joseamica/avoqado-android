package com.avoqado.pos.announcements

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.announcements.data.AlmacenDeCierres
import com.avoqado.pos.announcements.data.AnnouncementsRepository
import com.avoqado.pos.announcements.data.AnnouncementsRepository.VentanaPendiente
import com.avoqado.pos.announcements.data.Cierre
import com.avoqado.pos.announcements.data.CierresDeAnuncios
import com.avoqado.pos.announcements.data.pendientesPorMandar
import com.avoqado.pos.announcements.data.respuestaVigente
import com.avoqado.pos.announcements.data.ventanaVisible
import com.avoqado.pos.announcements.presentation.AnnouncementGateViewModel
import com.avoqado.pos.announcements.presentation.AnnouncementViewModel
import com.avoqado.pos.announcements.data.model.AnnouncementDetail
import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * «Al cerrarla no vuelve a salir» (codex r1 #12): el cierre se guarda en el aparato ANTES de la red, por persona, y se
 * manda después con el `staffId` de quien cerró. Más las respuestas viejas de otra sesión (codex r4 #4, r5 #3, r6 #1).
 */
class CierrePendienteTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** El almacén en memoria: sobrevive a «reiniciar» porque se lo pasamos al ViewModel nuevo. */
    private class AlmacenEnMemoria : AlmacenDeCierres {
        val datos = mutableMapOf<String, List<Cierre>>()
        override fun todos(): Map<String, List<Cierre>> = datos.toMap()
        override fun escribir(staffId: String, cierres: List<Cierre>): Boolean { datos[staffId] = cierres; return true }
    }

    private val almacen = AlmacenEnMemoria()
    private val generacion = MutableStateFlow(1L)
    private var persona: String? = "ana"
    private val storage = mockk<SecureStorage>(relaxed = true) {
        every { generacion } answers { this@CierrePendienteTest.generacion.value }
        every { generacionDeSesion } returns this@CierrePendienteTest.generacion
        every { userId } answers { persona }
    }
    private val repo = mockk<AnnouncementsRepository>(relaxed = true) {
        coEvery { fetchVentanaPendiente() } returns null
    }
    private val ventanaA1 = VentanaPendiente("a1", "Terminal nueva", "Ya disponible", null)

    private fun gate() = AnnouncementGateViewModel(repo, storage, CierresDeAnuncios(almacen))

    // ── Funciones puras ──────────────────────────────────────────────────────────────────

    @Test
    fun `respuestaVigente solo con la misma generacion`() {
        assertTrue(respuestaVigente(pedida = 3, actual = 3))
        assertFalse(respuestaVigente(pedida = 3, actual = 4))
    }

    @Test
    fun `ventanaVisible esconde lo cerrado`() {
        assertTrue(ventanaVisible("a1", emptySet()))
        assertFalse(ventanaVisible("a1", setOf("a1")))
        assertFalse(ventanaVisible(null, emptySet()))
    }

    @Test
    fun `pendientesPorMandar solo de la persona y sin los sincronizados, en orden`() {
        val cierres = mapOf(
            "ana" to listOf(Cierre("a1", false), Cierre("a2", true), Cierre("a3", false)),
            "beatriz" to listOf(Cierre("b1", false)),
        )
        assertEquals(listOf("a1", "a3"), pendientesPorMandar("ana", cierres))
        assertEquals(emptyList<String>(), pendientesPorMandar("carlos", cierres))
    }

    @Test
    fun `el almacen guarda solo los ultimos 100 cierres`() {
        val cierres = CierresDeAnuncios(almacen)
        repeat(105) { cierres.marcar("ana", "a$it") }
        val guardados = almacen.datos.getValue("ana")
        assertEquals(100, guardados.size)
        assertEquals("a5", guardados.first().id)
        assertEquals("a104", guardados.last().id)
    }

    // ── El cierre durable ────────────────────────────────────────────────────────────────

    @Test
    fun `un cierre sin red no vuelve a ensenar la ventana tras reiniciar`() {
        coEvery { repo.fetchVentanaPendiente() } returns ventanaA1
        coEvery { repo.recordDismiss(any(), any()) } returns false   // sin red

        val antes = gate()
        assertEquals("a1", antes.ventanaPendiente.value?.id)
        antes.cerrar("a1")
        assertNull(antes.ventanaPendiente.value)
        assertEquals(listOf(Cierre("a1", false)), almacen.datos["ana"])

        // «Reiniciar»: ViewModel nuevo, mismo disco; el servidor todavía no sabe que se cerró.
        val despues = gate()
        assertNull(despues.ventanaPendiente.value)
    }

    @Test
    fun `el cierre se guarda antes de tocar la red`() {
        coEvery { repo.fetchVentanaPendiente() } returns ventanaA1
        var guardadoAlMandar: List<Cierre>? = null
        coEvery { repo.recordDismiss("a1", "ana") } answers { guardadoAlMandar = almacen.datos["ana"]; true }

        gate().cerrar("a1")

        assertEquals(listOf(Cierre("a1", false)), guardadoAlMandar)
        coVerify { repo.recordDismiss("a1", "ana") }
    }

    @Test
    fun `al volver la red se manda el cierre y queda marcado como sincronizado (no se borra)`() {
        almacen.datos["ana"] = listOf(Cierre("a1", false))
        coEvery { repo.fetchVentanaPendiente() } returns ventanaA1
        coEvery { repo.recordDismiss("a1", "ana") } returns true

        val vm = gate()

        coVerify(exactly = 1) { repo.recordDismiss("a1", "ana") }
        assertEquals(listOf(Cierre("a1", true)), almacen.datos["ana"])
        assertNull(vm.ventanaPendiente.value)
    }

    @Test
    fun `con 409 el cierre sigue pendiente`() {
        almacen.datos["ana"] = listOf(Cierre("a1", false))
        coEvery { repo.recordDismiss("a1", "ana") } returns false   // 409 CIERRE_DE_OTRA_PERSONA

        gate()

        assertEquals(listOf(Cierre("a1", false)), almacen.datos["ana"])
    }

    @Test
    fun `el cierre pendiente de otra persona no se manda con la sesion actual`() {
        almacen.datos["beatriz"] = listOf(Cierre("b1", false))
        almacen.datos["ana"] = listOf(Cierre("a1", false))
        coEvery { repo.recordDismiss(any(), any()) } returns true

        gate()

        coVerify(exactly = 0) { repo.recordDismiss("b1", any()) }
        coVerify(exactly = 1) { repo.recordDismiss("a1", "ana") }
        assertEquals(listOf(Cierre("b1", false)), almacen.datos["beatriz"])
    }

    @Test
    fun `los pendientes se mandan uno por uno en orden y paran al primer fallo`() {
        almacen.datos["ana"] = listOf(Cierre("a1", false), Cierre("a2", false), Cierre("a3", false))
        val mandados = mutableListOf<String>()
        coEvery { repo.recordDismiss(any(), "ana") } answers { mandados += firstArg<String>(); firstArg<String>() != "a2" }

        gate()

        assertEquals(listOf("a1", "a2"), mandados)
        assertEquals(listOf(Cierre("a1", true), Cierre("a2", false), Cierre("a3", false)), almacen.datos["ana"])
    }

    @Test
    fun `lo que otra persona cerro no le esconde la ventana a la actual`() {
        almacen.datos["beatriz"] = listOf(Cierre("a1", true))
        coEvery { repo.fetchVentanaPendiente() } returns ventanaA1

        assertEquals("a1", gate().ventanaPendiente.value?.id)
    }

    // ── Disparador y respuestas viejas ───────────────────────────────────────────────────

    @Test
    fun `al instalar una sesion despues de abrir sin ella, la ventana se consulta`() {
        generacion.value = 0
        persona = null
        coEvery { repo.fetchVentanaPendiente() } returns null   // sin sesión: no hay token
        val vm = gate()
        assertNull(vm.ventanaPendiente.value)

        coEvery { repo.fetchVentanaPendiente() } returns ventanaA1
        persona = "ana"
        generacion.value = 1   // entró alguien

        coVerify(exactly = 2) { repo.fetchVentanaPendiente() }
        assertEquals("a1", vm.ventanaPendiente.value?.id)
    }

    @Test
    fun `la respuesta de la sesion anterior que llega al ultimo no se ensena`() {
        val deAna = CompletableDeferred<VentanaPendiente?>()
        val deBeatriz = CompletableDeferred<VentanaPendiente?>()
        val respuestas = ArrayDeque(listOf(deAna, deBeatriz))
        coEvery { repo.fetchVentanaPendiente() } coAnswers { respuestas.removeFirst().await() }

        val vm = gate()   // consulta de Ana (generación 1)
        persona = "beatriz"
        generacion.value = 2   // entra Beatriz: la ventana se vacía de inmediato
        assertNull(vm.ventanaPendiente.value)

        deBeatriz.complete(VentanaPendiente("b1", "De Beatriz", "", null))
        deAna.complete(VentanaPendiente("a1", "De Ana", "", null))

        assertEquals("b1", vm.ventanaPendiente.value?.id)
    }

    @Test
    fun `la respuesta que llega con otra persona adentro no se ensena aunque nadie consulte de nuevo`() {
        val deAna = CompletableDeferred<VentanaPendiente?>()
        coEvery { repo.fetchVentanaPendiente() } coAnswers { deAna.await() }
        val vm = gate()

        storageCambiaSinAvisar(3)
        deAna.complete(ventanaA1)

        assertNull(vm.ventanaPendiente.value)
    }

    /** La generación cambia pero el flujo todavía no avisa (la respuesta gana la carrera al disparador). */
    private fun storageCambiaSinAvisar(nueva: Long) {
        every { storage.generacion } returns nueva
    }

    // ── El detalle de «Ver más» ──────────────────────────────────────────────────────────

    private fun detalle(id: String) = AnnouncementsRepository.Detalle(
        AnnouncementDetail(id = id, title = "T $id"),
        emptyList(),
    )

    @Test
    fun `el detalle de la sesion anterior que llega al ultimo no se ensena`() {
        val deAna = CompletableDeferred<AnnouncementsRepository.Detalle?>()
        val deBeatriz = CompletableDeferred<AnnouncementsRepository.Detalle?>()
        coEvery { repo.fetchDetail("a1") } coAnswers { deAna.await() }
        coEvery { repo.fetchDetail("b1") } coAnswers { deBeatriz.await() }
        val vm = AnnouncementViewModel(repo, storage)

        vm.abrir("a1")
        generacion.value = 2
        vm.abrir("b1")
        deBeatriz.complete(detalle("b1"))
        deAna.complete(detalle("a1"))

        assertEquals("b1", vm.detalle.value?.anuncio?.id)
        coVerify(exactly = 0) { repo.recordOpen("a1") }
        coVerify(exactly = 1) { repo.recordOpen("b1") }
    }

    @Test
    fun `el detalle que llega con otra persona adentro no se ensena ni cuenta como abierto`() {
        val deAna = CompletableDeferred<AnnouncementsRepository.Detalle?>()
        coEvery { repo.fetchDetail("a1") } coAnswers { deAna.await() }
        val vm = AnnouncementViewModel(repo, storage)

        vm.abrir("a1")
        generacion.value = 2
        deAna.complete(detalle("a1"))

        assertNull(vm.detalle.value)
        coVerify(exactly = 0) { repo.recordOpen(any()) }
    }
}

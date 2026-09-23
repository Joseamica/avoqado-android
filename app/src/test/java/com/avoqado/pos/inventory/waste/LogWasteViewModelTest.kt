package com.avoqado.pos.inventory.waste

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.inventory.data.RespuestaHttp
import com.avoqado.pos.inventory.waste.data.BloqueoDeMermaPorPlan
import com.avoqado.pos.inventory.waste.data.EstadoMerma
import com.avoqado.pos.inventory.waste.data.PendingWasteEntity
import com.avoqado.pos.inventory.waste.data.TransporteDeMerma
import com.avoqado.pos.inventory.waste.data.WasteCatalogEntity
import com.avoqado.pos.inventory.waste.data.WasteSyncCoordinator
import com.avoqado.pos.inventory.waste.domain.WasteReason
import com.avoqado.pos.inventory.waste.presentation.AvisoDeMerma
import com.avoqado.pos.inventory.waste.presentation.EntradaDeMerma
import com.avoqado.pos.inventory.waste.presentation.LogWasteViewModel
import com.avoqado.pos.inventory.waste.presentation.entradaDeMerma
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * La captura de una merma: lo que se puede confirmar, lo que se le dice al cajero, y que funcione
 * sin red. La cola corre el SQL real; el catálogo y el servidor son dobles.
 * Espejo exacto de `LogWasteViewModelTests.swift` de avoqado-ios.
 */
class LogWasteViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private companion object {
        const val VENUE = "venue-centro"
        const val YO = "yo"
        const val AHORA = 1_000_000_000_000L
        const val HORA = 3_600_000L

        fun articulo(nombre: String = "Aguacate", unidad: String = "KILOGRAM", actualizadoEn: Long = AHORA) =
            WasteCatalogEntity(VENUE, "RAW_MATERIAL", "rm-1", nombre, "AGU-01", unidad, actualizadoEn)
    }

    private val cola = ColaDeMermaSobreSqlite()
    private val conexion = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private var enDisco: Set<String> = emptySet()
    private val almacen = mockk<SecureStorage> {
        every { venueId } returns VENUE
        every { userId } returns YO
        every { venuesConMermaBloqueada } answers { enDisco }
        every { venuesConMermaBloqueada = any() } answers { enDisco = firstArg() }
    }
    private val red = mockk<ConnectivityMonitor> {
        every { isConnected } returns conexion
        every { isServerReachable } returns servidor
    }
    private val catalogo = CatalogoDeMermaFalso().apply { porVenue = mapOf(VENUE to listOf(articulo())) }

    /** Nadie arranca el drenado en estas pruebas: la fila se queda en disco, que es lo que se mira. */
    private val transporte = object : TransporteDeMerma {
        override suspend fun enviar(fila: PendingWasteEntity) = RespuestaHttp(201, "{}")
        override suspend fun anular(fila: PendingWasteEntity) = RespuestaHttp(0, "")
    }
    private val bloqueo = BloqueoDeMermaPorPlan(almacen, red, catalogo)
    private val motor = WasteSyncCoordinator(cola, transporte, almacen, red, bloqueo).apply { reloj = { AHORA } }

    private fun vm() = LogWasteViewModel(catalogo, motor, bloqueo, almacen, red).apply { reloj = { AHORA } }

    private fun LogWasteViewModel.capturar(cantidad: String = "3", motivo: WasteReason = WasteReason.SPOILED) {
        elegirArticulo(articulo())
        escribirCantidad(cantidad)
        elegirMotivo(motivo)
    }

    // MARK: - El formulario

    /** Sin artículo, sin una cantidad que el servidor acepte o sin motivo, no se puede confirmar. */
    @Test
    fun `el boton de confirmar solo se enciende con articulo, cantidad y motivo`() = runTest {
        val vm = vm()
        assertFalse(vm.estado.value.puedeConfirmar)
        vm.elegirArticulo(articulo())
        assertFalse(vm.estado.value.puedeConfirmar)
        vm.escribirCantidad("3")
        assertFalse(vm.estado.value.puedeConfirmar)
        vm.elegirMotivo(WasteReason.SPOILED)
        assertTrue(vm.estado.value.puedeConfirmar)
        vm.escribirCantidad("0")
        assertFalse(vm.estado.value.puedeConfirmar)
    }

    /** «Otro» exige nota — en la PANTALLA, no con un 422 permanente sobre una fila ya escrita. */
    @Test
    fun `con Otro, sin nota no se puede confirmar`() = runTest {
        val vm = vm()
        vm.capturar(motivo = WasteReason.OTHER)
        assertFalse(vm.estado.value.puedeConfirmar)
        vm.escribirNota("   ")
        assertFalse(vm.estado.value.puedeConfirmar)
        vm.escribirNota("se derramó al trasvasar")
        assertTrue(vm.estado.value.puedeConfirmar)
    }

    /**
     * 🔴 Spec §5: confirmación SIEMPRE, con el cuánto y el de qué. La cantidad se muestra como la
     * va a registrar el servidor: la coma del teclado ya convertida en punto (Review Focus 1).
     */
    @Test
    fun `la confirmacion dice cuanto y de que, como lo va a registrar el servidor`() = runTest {
        val vm = vm()
        vm.capturar(cantidad = "3,5")

        vm.pedirConfirmacion()

        assertEquals("Vas a registrar 3.5 kg de Aguacate como merma.", vm.estado.value.confirmacion)
    }

    /** La tecla que pasaría del tope del servidor no entra: la nota nunca se corta en silencio. */
    @Test
    fun `la nota que pasaria del tope del servidor no entra`() = runTest {
        val vm = vm()
        vm.escribirNota("x".repeat(280))
        vm.escribirNota("x".repeat(281))
        assertEquals(280, vm.estado.value.nota.length)
    }

    // MARK: - Registrar

    /**
     * 🔴 Un doble toque en «Confirmar» registra UNA merma. La escritura se detiene a propósito a
     * medio camino (como la de Room, que suspende): sin el candado, el segundo toque llegaría con
     * el formulario todavía lleno y escribiría otra fila.
     */
    @Test
    fun `un doble toque registra una sola merma`() = runTest {
        val vm = vm()
        vm.capturar()
        val puerta = CompletableDeferred<Unit>()
        cola.antesDeEncolar = { puerta.await() }

        vm.confirmar()
        vm.confirmar()
        puerta.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, cola.todas().size)
    }

    /** Con red y el plan en regla, el aviso es el del spec. */
    @Test
    fun `con red el aviso es el del spec`() = runTest {
        val vm = vm()
        vm.capturar()

        vm.confirmar()

        assertEquals(AvisoDeMerma("¡Merma registrada!"), vm.estado.value.aviso)
        assertEquals(EstadoMerma.PENDING, cola.todas().single().estado)
    }

    /** Sin red, la merma ya está en disco y la pantalla lo DICE — no pinta un error. */
    @Test
    fun `sin red el aviso es honesto, no un error`() = runTest {
        conexion.value = false
        val vm = vm()
        vm.capturar()

        vm.confirmar()

        assertEquals(AvisoDeMerma("Merma guardada", "Se subirá al recuperar la conexión."), vm.estado.value.aviso)
        assertNull(vm.estado.value.error)
        assertEquals(1, cola.todas().size)
    }

    /**
     * 🔴 Con el plan bloqueado se registra IGUAL: el bloqueo es informativo y la merma espera en
     * la cola; si el negocio activa el plan, sube sola. Apagar el botón trabaría la petición que
     * podría levantarlo (spec §5).
     */
    @Test
    fun `con el plan bloqueado se registra igual y lo dice`() = runTest {
        bloqueo.bloquear(VENUE)
        val vm = vm()
        vm.capturar()
        assertTrue(vm.estado.value.bloqueadaPorPlan)
        assertTrue(vm.estado.value.puedeConfirmar)

        vm.confirmar()

        assertEquals(
            AvisoDeMerma("Merma guardada", "Se subirá cuando tu negocio active el Plan Premium."),
            vm.estado.value.aviso,
        )
        assertEquals(1, cola.todas().size)
    }

    /** Al final del día se registran varias seguidas: tras una, el formulario queda limpio. */
    @Test
    fun `despues de registrar el formulario queda listo para la siguiente`() = runTest {
        val vm = vm()
        vm.capturar(motivo = WasteReason.OTHER)
        vm.escribirNota("se cayó")

        vm.confirmar()

        val e = vm.estado.value
        assertNull(e.articulo)
        assertEquals("", e.cantidad)
        assertNull(e.motivo)
        assertEquals("", e.nota)
        assertNull(e.confirmacion)
    }

    // MARK: - El catálogo

    /** Sin red, se busca en el catálogo guardado y se dice de cuándo es. */
    @Test
    fun `sin red el buscador usa el catalogo guardado y dice su antiguedad`() = runTest {
        catalogo.porVenue = mapOf(VENUE to listOf(articulo(actualizadoEn = AHORA - 5 * HORA)))
        conexion.value = false
        val vm = vm()

        vm.alAbrir()
        vm.buscar("agua")

        assertEquals(listOf("Aguacate"), vm.estado.value.resultados.map { it.name })
        assertEquals("Catálogo de hace 5 h", vm.estado.value.antiguedadDelCatalogo)
        assertEquals(0, catalogo.refrescos)
        assertEquals(0, catalogo.consultasDePlan)
    }

    @Test
    fun `sin catalogo guardado y sin red se dice que hace falta conectarse`() = runTest {
        catalogo.porVenue = emptyMap()
        conexion.value = false
        val vm = vm()

        vm.alAbrir()

        assertEquals(
            "Sin catálogo guardado. Conéctate a internet para descargarlo.",
            vm.estado.value.antiguedadDelCatalogo,
        )
    }

    /**
     * Con red, al abrir se le pregunta al servidor por el plan y se baja el catálogo: un bloqueo
     * viejo se levanta solo y lo que se ve ya está al día.
     */
    @Test
    fun `con red, al abrir se revalida el plan y se baja el catalogo`() = runTest {
        bloqueo.bloquear(VENUE)
        catalogo.plan = true
        val vm = vm()

        vm.alAbrir()

        assertFalse(vm.estado.value.bloqueadaPorPlan)
        assertEquals(1, catalogo.refrescos)
        assertNull(vm.estado.value.antiguedadDelCatalogo)
    }

    /** Con el plan bloqueado no se intenta bajar el catálogo: el servidor contestaría otro 403. */
    @Test
    fun `con red y sin plan no se intenta bajar el catalogo`() = runTest {
        catalogo.plan = false
        val vm = vm()

        vm.alAbrir()

        assertTrue(vm.estado.value.bloqueadaPorPlan)
        assertEquals(0, catalogo.refrescos)
    }

    // MARK: - La entrada en «Más»

    /** Sin el permiso, la entrada se ve APAGADA y dice a quién pedírselo: nunca desaparece. */
    @Test
    fun `sin permiso la entrada aparece apagada y dice a quien pedirselo`() {
        assertEquals(
            EntradaDeMerma(habilitada = false, subtitulo = "Pídele acceso a tu gerente", insignia = null),
            entradaDeMerma(puedeRegistrar = false, bloqueadaPorPlan = false),
        )
    }

    /** Con el plan bloqueado sigue ENCENDIDA y dice qué plan la incluye: informa, no traba. */
    @Test
    fun `con el plan bloqueado la entrada sigue encendida y dice que plan la incluye`() {
        assertEquals(
            EntradaDeMerma(habilitada = true, subtitulo = "Incluido en el Plan Premium", insignia = "Premium"),
            entradaDeMerma(puedeRegistrar = true, bloqueadaPorPlan = true),
        )
    }

    @Test
    fun `con permiso y plan la entrada esta encendida y sin avisos`() {
        assertEquals(
            EntradaDeMerma(habilitada = true, subtitulo = null, insignia = null),
            entradaDeMerma(puedeRegistrar = true, bloqueadaPorPlan = false),
        )
    }
}

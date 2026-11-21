package com.avoqado.pos.onlineorders

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.onlineorders.data.ImpresionDePedidoEnLinea
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.data.ResultadoImpresion
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderItem
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.domain.ReclamoDeImpresion
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.GuardaDelPrimerByte
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.PrinterInfo
import com.avoqado.pos.printing.routing.StationInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D661 · Ronda 3 · Familia 3 (Ruling 50): un reclamo es el permiso de imprimir AHORA. Si la comanda esperó su impresora (el turno de la
 * impresora, el permiso USB, la conexión) hasta que su reclamo tiene 75 s (90 s de vida − 15 s de margen), no sale ni un byte: se suelta
 * y la siguiente vuelta la reclama otra vez. Ya empezada, termina y se confirma como siempre. Ronda 4 (Ruling 50 enmendado): sólo la
 * comanda de tarjeta de «Nadie»; las demás salen tarde, como antes.
 */
class ReclamoVigenteTest {

    private val repo = mockk<OnlineOrdersRepository>()

    /** Reloj monotónico de la prueba (ms) y reloj de pared (para el descanso de 3 min). */
    private var monotono = 1_000_000L
    private var pared = 5_000_000L

    /**
     * El papel: espera [esperas] (una por pedazo que manda), y antes de cada pedazo pregunta el permiso del primer byte que viaja en el
     * contexto, como `PrinterService` justo antes de escribir. Sin permiso no manda nada de ese pedazo (como una estación que tronó).
     */
    private class Papel(var esperas: List<Long> = listOf(0L)) {
        var pedazos = 0
        var llamadas = 0
    }

    private fun armar(papel: Papel) = ImpresionDePedidoEnLinea(
        repositorio = repo,
        hayImpresoraDeCocina = { true },
        venueId = { "v1" },
        despachar = { _, _, _, _, _, _ ->
            papel.llamadas++
            var fallo = false
            for (espera in papel.esperas) {
                monotono += espera
                val guarda = currentCoroutineContext()[GuardaDelPrimerByte]
                if (guarda == null || guarda.puedeEscribir()) papel.pedazos++ else fallo = true
            }
            if (fallo) EstadoDeComanda.NoSalio(listOf("Cocina"), "La impresora no respondió.", "T-p1") else EstadoDeComanda.Salio
        },
        reloj = { pared },
        relojMonotono = { monotono },
    )

    /** Por omisión, la comanda de tarjeta de «Nadie» (D661: `CARD_ONLINE` sin aceptación): la única con tope (Ruling 50 enmendado). */
    private fun pedido(orderId: String = "p1", paymentMode: String = "CARD_ONLINE", requiresAcceptance: Boolean? = false) = OnlineOrder(
        orderId = orderId, onlineOrderId = "o-$orderId", orderNumber = "T-$orderId", status = OnlineOrderStatus.ACCEPTED,
        paymentMode = paymentMode, paymentStatus = "PAID", customerName = "Ana", customerPhone = null, note = null,
        createdAt = Instant.parse("2026-11-21T10:00:00Z"), acceptDeadlineAt = null, estimatedReadyAt = null,
        totalCents = 25050, remainingCents = 0, needsPrint = true, items = listOf(OnlineOrderItem("p", null, "Flautas", 2, null, emptyList())),
        requiresAcceptance = requiresAcceptance,
    )

    @Test
    fun `R3 Family 3 a ticket whose claim is 75 s old when its printer frees sends zero bytes and is claimed again by the next walk`() = runTest {
        coEvery { repo.reclamarComanda("p1", "dev-1") } returns ReclamoDeImpresion(true)
        coEvery { repo.confirmPrint("p1", "dev-1") } returns true
        // A tiene la impresora USB esperando un permiso: B espera su turno 80 s.
        val papel = Papel(esperas = listOf(80_000L))
        val impresion = armar(papel)
        assertEquals(ResultadoImpresion.ReclamoVencido, impresion.imprimirSiToca(pedido(), "dev-1"))
        assertEquals("ni un byte de B", 0, papel.pedazos)
        coVerify(exactly = 0) { repo.confirmPrint(any(), any()) }
        // Se soltó: sin marca de «en curso» y sin el descanso de 3 min; la siguiente vuelta la reclama otra vez (el SERVIDOR decide).
        assertTrue(impresion.puedeIntentar("p1"))
        papel.esperas = listOf(0L)
        assertEquals(ResultadoImpresion.Impresa, impresion.imprimirSiToca(pedido(), "dev-1"))
        coVerify(exactly = 2) { repo.reclamarComanda("p1", "dev-1") }
        coVerify(exactly = 1) { repo.confirmPrint("p1", "dev-1") }
        assertEquals(1, papel.pedazos)
    }

    @Test
    fun `R3 Family 3 a ticket younger than 75 s prints and confirms as today`() = runTest {
        coEvery { repo.reclamarComanda("p1", "dev-1") } returns ReclamoDeImpresion(true)
        coEvery { repo.confirmPrint("p1", "dev-1") } returns true
        val papel = Papel(esperas = listOf(74_000L))
        assertEquals(ResultadoImpresion.Impresa, armar(papel).imprimirSiToca(pedido(), "dev-1"))
        assertEquals(1, papel.pedazos)
        coVerify(exactly = 1) { repo.confirmPrint("p1", "dev-1") }
    }

    @Test
    fun `R3 Family 3 a ticket whose bytes started before 75 s finishes and confirms`() = runTest {
        coEvery { repo.reclamarComanda("p1", "dev-1") } returns ReclamoDeImpresion(true)
        coEvery { repo.confirmPrint("p1", "dev-1") } returns true
        // Cocina sale a los 10 s; la barra espera su impresora 80 s más: ya empezó, así que termina.
        val papel = Papel(esperas = listOf(10_000L, 80_000L))
        assertEquals(ResultadoImpresion.Impresa, armar(papel).imprimirSiToca(pedido(), "dev-1"))
        assertEquals(2, papel.pedazos)
        coVerify(exactly = 1) { repo.confirmPrint("p1", "dev-1") }
    }

    @Test
    fun `R3 Family 3 a person's reprint is not limited by any claim`() = runTest {
        val papel = Papel(esperas = listOf(500_000L))
        assertEquals(ResultadoImpresion.Impresa, armar(papel).reimprimir(pedido()))
        assertEquals(1, papel.pedazos)
        coVerify(exactly = 0) { repo.reclamarComanda(any(), any()) }
    }

    /**
     * Ronda 4 (Ruling 50 enmendado, el 66 de la TPV): el tope es SÓLO de la comanda de tarjeta de «Nadie». A «Mi equipo» (con tarjeta o
     * pagando al recoger), al folio que se cobra en caja y a lo de un SERVIDOR viejo sólo los vuelve a reclamar la bandeja a la vista:
     * negarlos los volvería «nunca». Salen tarde, como antes de la ronda 3.
     */
    @Test
    fun `R4 a Mi equipo ticket or a register folio that waited more than 75 s for its printer still prints and confirms`() = runTest {
        val sinTope = listOf(
            pedido("m1", "CARD_ONLINE", requiresAcceptance = true),
            pedido("m2", "PAY_AT_PICKUP", requiresAcceptance = true),
            pedido("f1", "PAY_AT_PICKUP", requiresAcceptance = false),
            pedido("v1", "CARD_ONLINE", requiresAcceptance = null),
        )
        for (p in sinTope) {
            coEvery { repo.reclamarComanda(p.orderId, "dev-1") } returns ReclamoDeImpresion(true)
            coEvery { repo.confirmPrint(p.orderId, "dev-1") } returns true
            val papel = Papel(esperas = listOf(80_000L))
            assertEquals(p.orderId, ResultadoImpresion.Impresa, armar(papel).imprimirSiToca(p, "dev-1"))
            assertEquals(p.orderId, 1, papel.pedazos)
            coVerify(exactly = 1) { repo.confirmPrint(p.orderId, "dev-1") }
        }
    }

    /**
     * Ronda 4 · menor 4: la guarda viaja por la cadena REAL (`ImpresionDePedidoEnLinea` → `ComandaDispatcher.dispatch` →
     * `ReintentoDeComanda.insistir` → `ComandaPrinter.printComandas` → `PrinterService.sendData`) hasta una ticketera de red falsa. Si una
     * capa saltara a otro ámbito (un `launch` en un scope suyo, otro hilo), la guarda se perdería y aquí llegarían bytes de una comanda
     * vencida. La edad cuenta desde ANTES de pedir el reclamo: aquí la vence una respuesta de 80 s.
     */
    @Test
    fun `R4 the first-byte guard travels the real chain to the printer - D661 sends nothing, Mi equipo prints, the next walk prints the D661`() =
        runBlocking {
            val ticketera = TicketeraDeRed()
            var reloj = 1_000_000L
            var respuestaDelReclamo = 80_000L
            mockkStatic(SystemClock::class)
            try {
                every { SystemClock.elapsedRealtime() } answers { reloj }
                coEvery { repo.reclamarComanda(any(), "dev-1") } answers { reloj += respuestaDelReclamo; ReclamoDeImpresion(true) }
                coEvery { repo.confirmPrint(any(), "dev-1") } returns true
                val impresion = cadenaReal(ticketera.puerto)

                // D661: con su turno y su conexión, la guarda dice que no: ni un byte, ni confirmación.
                assertEquals(ResultadoImpresion.ReclamoVencido, impresion.imprimirSiToca(pedido("p1"), "dev-1"))
                assertEquals("ni un byte del D661", emptyList<Byte>(), ticketera.siguienteTrabajo())
                // «Mi equipo» en la misma situación: sin tope, sale y se confirma.
                assertEquals(ResultadoImpresion.Impresa, impresion.imprimirSiToca(pedido("p2", requiresAcceptance = true), "dev-1"))
                assertTrue(ticketera.siguienteTrabajo().texto().contains("T-p2"))
                // La siguiente vuelta reclama el D661 otra vez, ya a tiempo: sale y se confirma.
                respuestaDelReclamo = 0L
                assertEquals(ResultadoImpresion.Impresa, impresion.imprimirSiToca(pedido("p1"), "dev-1"))
                assertTrue(ticketera.siguienteTrabajo().texto().contains("T-p1"))
                coVerify(exactly = 1) { repo.confirmPrint("p1", "dev-1") }
                coVerify(exactly = 1) { repo.confirmPrint("p2", "dev-1") }
            } finally {
                unmockkStatic(SystemClock::class)
                ticketera.cerrar()
            }
        }

    /** La cadena de verdad hasta el socket: sólo el SERVIDOR (reclamo y confirmación) y la configuración de impresión son de mentira. */
    private fun cadenaReal(puerto: Int): ImpresionDePedidoEnLinea {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns null
        val contexto = mockk<Context>(relaxed = true)
        every { contexto.getSharedPreferences(any(), any()) } returns prefs
        val impresoras = PrinterService(contexto, mockk(relaxed = true), mockk(relaxed = true))
        val reintento = ReintentoDeComanda(ComandaPrinter(impresoras), esperar = {}, reporteDeComandas = mockk(relaxed = true))
        val config = PrintConfig(
            printers = listOf(PrinterInfo("pr_cocina", "Cocina", "NETWORK", "127.0.0.1:$puerto")),
            stations = listOf(StationInfo("st_cocina", "Cocina", printerId = "pr_cocina", isDefault = true)),
            defaultStationId = "st_cocina",
        )
        val configuracion = mockk<PrintConfigRepository>(relaxed = true)
        every { configuracion.getCurrentConfig() } returns config
        coEvery { configuracion.savedConfig("v1") } returns config
        val almacen = mockk<SecureStorage>()
        every { almacen.venueId } returns "v1"
        return ImpresionDePedidoEnLinea(repo, ComandaDispatcher(configuracion, reintento, impresoras), impresoras, configuracion, almacen)
    }

    /** Una ticketera de red falsa en esta máquina: contesta «con papel» y guarda lo que llega en cada conexión (un trabajo = una conexión). */
    private class TicketeraDeRed {
        private val servidor = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val trabajos = LinkedBlockingQueue<List<Byte>>()
        val puerto: Int get() = servidor.localPort

        init {
            Thread {
                while (!servidor.isClosed) {
                    val s = runCatching { servidor.accept() }.getOrNull() ?: break
                    Thread {
                        val recibido = mutableListOf<Byte>()
                        // Si la caja cierra sin leer el estado (la guarda dijo que no), llega un reset: vale lo recibido hasta ahí.
                        runCatching {
                            s.use {
                                it.getOutputStream().apply { write(0x12); flush() } // estado del rollo: con papel
                                val entrada = it.getInputStream()
                                while (true) {
                                    val b = entrada.read()
                                    if (b < 0) break
                                    recibido += b.toByte()
                                }
                            }
                        }
                        trabajos.put(recibido)
                    }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
        }

        /** Lo que llegó en la siguiente conexión, ya cerrada por la caja. */
        fun siguienteTrabajo(): List<Byte> = trabajos.poll(5, TimeUnit.SECONDS) ?: error("la ticketera no recibió ninguna conexión")

        fun cerrar() = servidor.close()
    }

    private fun List<Byte>.texto() = String(toByteArray(), Charsets.ISO_8859_1)
}

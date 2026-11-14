package com.avoqado.pos.kds.data

import androidx.lifecycle.LifecycleOwner
import com.avoqado.pos.kds.domain.CanalReparto
import com.avoqado.pos.kds.domain.KDSOrder
import com.avoqado.pos.kds.domain.KDSOrderStatus
import com.avoqado.pos.printing.data.TrabajoPendiente
import com.avoqado.pos.printing.routing.PrintConfig
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El vigilante de reparto con reloj virtual y todo falso: nada de red, impresoras ni tiempo real. Cada prueba detiene su
 * ciclo con `onStop` (un `delay` vivo hace girar a `runTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VigilanteDeRepartoTest {

    private val owner = mockk<LifecycleOwner>(relaxed = true)

    private fun comanda(id: String, createdAt: Long = 1_000L, needsPrint: Boolean = true, needsAcceptance: Boolean = false) = KDSOrder(
        id = id, orderId = "o-$id", orderNumber = "N-$id", orderType = "Delivery", needsAcceptance = needsAcceptance,
        needsPrint = needsPrint, items = emptyList(), createdAt = createdAt, status = KDSOrderStatus.NEW,
    )

    private fun canal(proveedor: String = "RAPPI", estado: String = "ACTIVE") =
        CanalReparto(id = "l-$proveedor", proveedor = proveedor, pausado = estado == "PAUSED", pausadoHasta = null, estado = estado)

    private fun impresa(id: String, createdAt: Long = 1_000L, salio: Boolean = true, porReimprimir: List<TrabajoPendiente> = emptyList(), venue: String = "v1") =
        ComandaImpresa(kdsId = id, orderNumber = "N-$id", createdAt = createdAt, salioEnPapel = salio, porReimprimir = porReimprimir, venueId = venue)

    private val trabajo = TrabajoPendiente(
        planes = emptyList(), config = PrintConfig(), orderNumber = "N-k1", orderType = "Delivery", serverName = null,
        comboNames = emptyMap(), venueId = "v1", orderId = "o-k1",
    )

    private class Fixture(val pizarra: AvisosDeReparto = AvisosDeReparto()) {
        var puede = true
        var venue: String? = "v1"
        var canales: Result<List<CanalReparto>> = Result.success(emptyList())
        var comandas: Result<List<KDSOrder>> = Result.success(emptyList())
        val lecturasDeCanales = mutableListOf<Long>()
        var lecturasDeComandas = 0
        val impresas = mutableListOf<List<String>>()
        val reintentos = mutableListOf<String>()
        var alReimprimir: suspend (String) -> Unit = {}
        var sonidos = 0
        fun apuntar(c: ComandaImpresa) = pizarra.publicar(c, pizarra.epoca())
        lateinit var vigilante: VigilanteDeReparto
    }

    private fun TestScope.armar(pizarra: AvisosDeReparto = AvisosDeReparto(), configurar: Fixture.() -> Unit = {}): Fixture {
        val f = Fixture(pizarra).apply(configurar)
        f.vigilante = VigilanteDeReparto(
            puedeImprimir = { f.puede },
            leerCanales = { f.lecturasDeCanales += testScheduler.currentTime; f.canales },
            leerComandas = { f.lecturasDeComandas++; f.comandas },
            imprimir = { lista -> f.impresas += lista.map { it.id } },
            reimprimirLoQueFalto = { id -> f.reintentos += id; f.alReimprimir(id) },
            pizarra = f.pizarra,
            sonar = { f.sonidos++ },
            scope = backgroundScope,
            venueActual = { f.venue },
            ahora = { testScheduler.currentTime },
        )
        f.vigilante.onStart(owner)
        runCurrent()
        return f
    }

    @Test
    fun `sin permiso no lee nada`() = runTest {
        val f = armar { puede = false; canales = Result.success(listOf(canal())); comandas = Result.success(listOf(comanda("k1"))) }
        advanceTimeBy(60_000)
        assertTrue(f.lecturasDeCanales.isEmpty())
        assertEquals(0, f.lecturasDeComandas)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `canales sin ninguno conectado no lee comandas`() = runTest {
        val f = armar { canales = Result.success(listOf(canal(estado = "PENDING"), canal("UBER_EATS", "DISABLED"))) }
        advanceTimeBy(60_000)
        assertEquals(0, f.lecturasDeComandas)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `si los canales fallan la primera vez lee comandas igual`() = runTest {
        val f = armar { canales = Result.failure(IOException("403")); comandas = Result.success(listOf(comanda("k1"))) }
        assertEquals(listOf(listOf("k1")), f.impresas)
        // No reintenta los canales en cada vuelta: espera su compás de 5 min.
        advanceTimeBy(60_000)
        assertEquals(1, f.lecturasDeCanales.size)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `imprime lo pendiente y avisa con el nombre del proveedor`() = runTest {
        val f = armar {
            canales = Result.success(listOf(canal("RAPPI", "PAUSED")))
            comandas = Result.success(listOf(comanda("k1"), comanda("k2", needsPrint = false)))
        }
        assertEquals(listOf(listOf("k1")), f.impresas)
        f.apuntar(impresa("k1"))
        runCurrent()
        assertEquals(listOf("k1"), f.vigilante.avisos.value.map { it.kdsId })
        assertEquals("Rappi", f.vigilante.proveedor.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `los pedidos MANUAL no se imprimen aqui`() = runTest {
        val f = armar {
            canales = Result.success(listOf(canal()))
            comandas = Result.success(listOf(comanda("k1", needsAcceptance = true)))
        }
        assertTrue(f.impresas.isEmpty())
        f.vigilante.onStop(owner)
    }

    @Test
    fun `suena cada 20 segundos hasta Entendido`() = runTest {
        val f = armar { canales = Result.success(listOf(canal())); comandas = Result.success(listOf(comanda("k1"))) }
        f.apuntar(impresa("k1"))
        runCurrent()
        assertEquals(1, f.sonidos)
        advanceTimeBy(20_001)
        assertEquals(2, f.sonidos)
        f.vigilante.entendido()
        advanceTimeBy(60_000)
        assertEquals(2, f.sonidos)
        assertTrue(f.vigilante.avisos.value.isEmpty())
        f.vigilante.onStop(owner)
    }

    @Test
    fun `sin emision no hay aviso ni sonido`() = runTest {
        val f = armar { canales = Result.success(listOf(canal())); comandas = Result.success(listOf(comanda("k1"))) }
        advanceTimeBy(60_000)
        assertTrue(f.vigilante.avisos.value.isEmpty())
        assertEquals(0, f.sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `lo parcial no se reintenta solo y reimprimir lo manda una vez`() = runTest {
        val f = armar { canales = Result.success(listOf(canal())); comandas = Result.success(listOf(comanda("k1", needsPrint = false))) }
        f.alReimprimir = { id -> f.apuntar(impresa(id)) }
        f.apuntar(impresa("k1", salio = false, porReimprimir = listOf(trabajo)))
        advanceTimeBy(60_000)
        assertTrue(f.reintentos.isEmpty())

        f.vigilante.reimprimir("k1")
        runCurrent()
        assertEquals(listOf("k1"), f.reintentos)
        val aviso = f.vigilante.avisos.value.single()
        assertTrue(aviso.salioEnPapel)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `cambio de sucursal quita los avisos de la anterior y calla`() = runTest {
        val f = armar { canales = Result.success(listOf(canal())); comandas = Result.success(listOf(comanda("k1", needsPrint = false))) }
        f.apuntar(impresa("k1"))
        runCurrent()
        f.venue = "v2"
        advanceTimeBy(15_001)
        assertTrue(f.vigilante.avisos.value.isEmpty())
        val sonidos = f.sonidos
        // Un resultado de la sucursal anterior que llega tarde no se ve, no suena y no se puede reimprimir desde aquí.
        f.apuntar(impresa("k2", salio = false, porReimprimir = listOf(trabajo), venue = "v1"))
        advanceTimeBy(60_000)
        assertTrue(f.vigilante.avisos.value.isEmpty())
        assertEquals(sonidos, f.sonidos)
        f.vigilante.reimprimir("k2")
        runCurrent()
        assertTrue(f.reintentos.isEmpty())
        f.vigilante.onStop(owner)
    }

    @Test
    fun `el primer sondeo no invalida una impresion que ya estaba en curso`() = runTest {
        val pizarraCompartida = AvisosDeReparto()
        val epocaDelIntento = pizarraCompartida.epoca() // «Pantalla de cocina» empezó a imprimir antes de arrancar el vigilante
        val f = armar(pizarra = pizarraCompartida) { canales = Result.success(listOf(canal())); comandas = Result.success(listOf(comanda("k1", needsPrint = false))) }
        pizarraCompartida.publicar(impresa("k1"), epocaDelIntento)
        runCurrent()
        assertEquals(listOf("k1"), f.vigilante.avisos.value.map { it.kdsId })
        f.vigilante.onStop(owner)
    }

    @Test
    fun `sin red los avisos se quedan`() = runTest {
        val f = armar { canales = Result.success(listOf(canal())); comandas = Result.success(listOf(comanda("k1", needsPrint = false))) }
        f.apuntar(impresa("k1"))
        runCurrent()
        f.comandas = Result.failure(IOException("sin red"))
        advanceTimeBy(45_000)
        assertEquals(listOf("k1"), f.vigilante.avisos.value.map { it.kdsId })
        f.vigilante.onStop(owner)
    }

    @Test
    fun `nombre del proveedor`() {
        assertEquals("Rappi", nombreDelProveedor(listOf(canal("RAPPI"))))
        assertEquals("Uber Eats", nombreDelProveedor(listOf(canal("UBER_EATS"))))
        assertEquals("delivery", nombreDelProveedor(listOf(canal("RAPPI"), canal("UBER_EATS"))))
        assertEquals("delivery", nombreDelProveedor(listOf(canal("DELIVERECT"))))
        assertEquals("Rappi", nombreDelProveedor(listOf(canal("RAPPI"), canal("RAPPI"))))
    }

    @Test
    fun `nombre de un proveedor`() {
        assertEquals("Rappi", nombreDeProveedor("RAPPI"))
        assertEquals("Uber Eats", nombreDeProveedor("UBER_EATS"))
        assertEquals("DiDi Food", nombreDeProveedor("DIDI_FOOD"))
        assertEquals("delivery", nombreDeProveedor("DELIVERECT"))
        assertEquals("delivery", nombreDeProveedor(null))
    }

    @Test
    fun `lo apuntado mientras la app estaba en segundo plano se ve al volver`() = runTest {
        val f = armar { canales = Result.success(listOf(canal())); comandas = Result.success(listOf(comanda("k1", needsPrint = false))) }
        f.vigilante.onStop(owner)
        // «Pantalla de cocina» terminó de imprimir con la app en segundo plano.
        f.apuntar(impresa("k1", salio = false, porReimprimir = listOf(trabajo)))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals(listOf("k1"), f.vigilante.avisos.value.map { it.kdsId })
        f.vigilante.onStop(owner)
    }

    @Test
    fun `perder el permiso no deja que un resultado en vuelo vuelva a sonar ni se reimprima`() = runTest {
        val f = armar { canales = Result.success(listOf(canal())); comandas = Result.success(listOf(comanda("k1", needsPrint = false))) }
        f.apuntar(impresa("k0", salio = false, porReimprimir = listOf(trabajo)))
        runCurrent()
        val epocaDelIntento = f.pizarra.epoca() // «Pantalla de cocina» empieza a imprimir k1
        f.puede = false
        advanceTimeBy(15_001)
        assertTrue(f.vigilante.avisos.value.isEmpty())
        f.pizarra.publicar(impresa("k1", salio = false, porReimprimir = listOf(trabajo)), epocaDelIntento)
        runCurrent()
        assertTrue(f.vigilante.avisos.value.isEmpty())
        f.vigilante.reimprimir("k1")
        runCurrent()
        assertTrue(f.reintentos.isEmpty())
        f.vigilante.onStop(owner)
    }

    @Test
    fun `reimprimir sin permiso no manda nada aunque el aviso siga a la vista`() = runTest {
        val f = armar { canales = Result.success(listOf(canal())); comandas = Result.success(listOf(comanda("k1", needsPrint = false))) }
        f.apuntar(impresa("k1", salio = false, porReimprimir = listOf(trabajo)))
        runCurrent()
        f.puede = false // se perdió el permiso; el siguiente sondeo todavía no llega
        f.vigilante.reimprimir("k1")
        runCurrent()
        assertTrue(f.reintentos.isEmpty())
        f.vigilante.onStop(owner)
    }

    @Test
    fun `onStop detiene el sondeo`() = runTest {
        val f = armar { canales = Result.success(listOf(canal())); comandas = Result.success(listOf(comanda("k1", needsPrint = false))) }
        f.vigilante.onStop(owner)
        val antes = f.lecturasDeComandas
        advanceTimeBy(60_000)
        assertEquals(antes, f.lecturasDeComandas)
    }

    @Test
    fun `una emision de Pantalla de cocina tambien avisa`() = runTest {
        val f = armar { canales = Result.success(listOf(canal())) }
        // Nadie en este vigilante imprimió: la emisión viene de la pantalla de cocina del mismo aparato.
        f.apuntar(impresa("k9", createdAt = 5_000L))
        runCurrent()
        assertEquals(listOf("k9"), f.vigilante.avisos.value.map { it.kdsId })
        f.vigilante.onStop(owner)
    }

    @Test
    fun `aviso de un pedido cancelado se retira aunque su papel haya fallado`() = runTest {
        val f = armar {
            canales = Result.success(listOf(canal()))
            comandas = Result.success(listOf(comanda("k1", createdAt = 2_000L, needsPrint = false), comanda("k0", createdAt = 1_000L, needsPrint = false)))
        }
        f.apuntar(impresa("k1", createdAt = 2_000L, salio = false))
        // Uno MÁS VIEJO que toda la lista: su ausencia no prueba nada y se queda.
        f.apuntar(impresa("viejo", createdAt = 10L, salio = false))
        runCurrent()
        // Rappi canceló k1: el servidor borró su comanda.
        f.comandas = Result.success(listOf(comanda("k0", createdAt = 1_000L, needsPrint = false)))
        advanceTimeBy(15_001)
        assertEquals(listOf("viejo"), f.vigilante.avisos.value.map { it.kdsId })
        f.vigilante.onStop(owner)
    }
}

package com.avoqado.pos.core.data.lan

import android.content.Context
import android.content.SharedPreferences
import com.avoqado.pos.tables.data.TableSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que la caja declara al árbitro (spec §3.1–3.2): su mesa, sus aperturas con dueño y su número de vida.
 * Tiempo virtual: `relojMs` es el reloj del `testScheduler`.
 */
class MesasDeEstaCajaTest {

    private val mesa = MutableStateFlow<String?>(null)
    private val vidas = ContadorDeVidas(leer = { 0L }, guardar = { true })

    private fun TestScope.caja(mesaActual: () -> String? = { mesa.value }) =
        MesasDeEstaCaja(mesaActual, mesa, vidas) { testScheduler.currentTime }

    @Test
    fun `p_traspaso - la foto de aperturas va ANTES que la mesa actual, asi el exito no deja hueco`() = runTest {
        var sesion: String? = null
        lateinit var mesas: MesasDeEstaCaja
        lateinit var ticket: Apertura
        // Lo que hace el éxito en el TablesViewModel: arranca la sesión de m13 y después termina su ticket.
        val traspasar = { sesion = "m13"; mesas.terminarApertura(ticket) }
        mesas = caja(mesaActual = { val v = sesion; traspasar(); v })
        ticket = mesas.abrirApertura("m13", "s1", "Juan", "V1")

        val d = mesas.declaracion("V1")

        assertTrue("m13 se declara por su apertura", "m13" in d.mesas + d.aperturas.map { it.tableId })
    }

    @Test
    fun `p10b - dos aperturas de la misma mesa se terminan por id, nunca por mesa`() = runTest {
        val mesas = caja()
        val u1 = mesas.abrirApertura("m5", "s1", "Juan", "V1")
        val u2 = mesas.abrirApertura("m5", "s2", "Ana", "V1")

        mesas.terminarApertura(u1)
        assertEquals(listOf(u2), mesas.declaracion("V1").aperturas)

        mesas.terminarApertura(u1) // idempotente
        assertEquals(listOf(u2), mesas.declaracion("V1").aperturas)
    }

    @Test
    fun `p20a - si sigue da false, esperar da NoHub en 250 ms o menos`() = runTest {
        val mesas = caja()
        val a = mesas.abrirApertura("m5", "s1", "Juan", "V1")

        val r = mesas.esperar(a) { false }

        assertEquals(LeaseOutcome.NoHub, r)
        assertTrue("tardó ${testScheduler.currentTime} ms", testScheduler.currentTime <= 250)
    }

    @Test
    fun `p24a - el plazo de 15 s cuenta desde la apertura, no desde la llamada`() = runTest {
        val mesas = caja()
        val a = mesas.abrirApertura("m5", "s1", "Juan", "V1")
        advanceTimeBy(10_000)

        val r = mesas.esperar(a) { true }

        assertEquals(LeaseOutcome.NoHub, r)
        assertEquals(MesasDeEstaCaja.PLAZO_PEDIR_MESA_MS, testScheduler.currentTime)
    }

    @Test
    fun `p29 - la vida se guarda con commit antes del primer envio y nunca se repite`() = runTest {
        val disco = mutableMapOf<String, Long>()
        var commitOk = true
        var commits = 0
        val context = contextSobre(disco, { commitOk }) { commits++ }

        val primera = ContadorDeVidas(context)
        assertEquals(1L, primera.vida)
        assertEquals(1L, primera.vida) // una vez por proceso
        assertEquals(1, commits)
        assertEquals("guardada antes de mandar nada", 1L, disco["gen"])

        // La primera vida muere sin mandar nada; la siguiente no repite el 1.
        assertEquals(2L, ContadorDeVidas(context).vida)

        commitOk = false
        val isla = ContadorDeVidas(context)
        assertNull(isla.vida)
        assertNull(MesasDeEstaCaja(TableSession(), isla).gen)
        assertEquals("un commit fallido no avanza el disco", 2L, disco["gen"])
    }

    @Test
    fun `p34 - fijar otro venue suelta las aperturas del anterior y su espera da NoHub enseguida`() = runTest {
        val mesas = caja()
        val a = mesas.abrirApertura("m5", "s1", "Juan", "V1")
        val espera = async { mesas.esperar(a) { true } }
        runCurrent()

        mesas.fijarVenue("V2")

        assertEquals(LeaseOutcome.NoHub, espera.await())
        assertTrue("no esperó el plazo", testScheduler.currentTime < MesasDeEstaCaja.PLAZO_PEDIR_MESA_MS)
        assertTrue(mesas.declaracion("V2").aperturas.isEmpty())
        assertTrue(mesas.declaracion("V1").aperturas.isEmpty())
    }

    @Test
    fun `soltarAperturas resuelve NoHub y quita solo las de ese venue`() = runTest {
        val mesas = caja()
        val v1 = mesas.abrirApertura("m5", "s1", "Juan", "V1")
        val v2 = mesas.abrirApertura("m6", "s1", "Juan", "V2")
        val espera = async { mesas.esperar(v1) { true } }
        runCurrent()

        mesas.soltarAperturas("V1")

        assertEquals(LeaseOutcome.NoHub, espera.await())
        assertTrue(testScheduler.currentTime < MesasDeEstaCaja.PLAZO_PEDIR_MESA_MS)
        assertTrue(mesas.declaracion("V1").aperturas.isEmpty())
        assertEquals(listOf(v2), mesas.declaracion("V2").aperturas)
    }

    @Test
    fun `p_resolver - gana la primera resolucion, un id terminado no revive y alEsperar suena una vez`() = runTest {
        val mesas = caja()
        val u1 = mesas.abrirApertura("m5", "s1", "Juan", "V1")
        mesas.resolver(u1.id, LeaseOutcome.Taken("Ana"))
        mesas.resolver(u1.id, LeaseOutcome.Granted)
        assertEquals(LeaseOutcome.Taken("Ana"), mesas.esperar(u1) { true })

        val u2 = mesas.abrirApertura("m6", "s1", "Juan", "V1")
        mesas.terminarApertura(u2)
        mesas.resolver(u2.id, LeaseOutcome.Granted)
        assertTrue(mesas.declaracion("V1").aperturas.none { it.id == u2.id })
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(u2) { true })

        val u3 = mesas.abrirApertura("m7", "s1", "Juan", "V1")
        var avisos = 0
        val espera = async { mesas.esperar(u3, alEsperar = { avisos++ }) { true } }
        runCurrent()
        mesas.marcarEsperando(u3.id)
        runCurrent()
        mesas.marcarEsperando(u3.id)
        advanceTimeBy(1_000)
        mesas.resolver(u3.id, LeaseOutcome.Granted)
        assertEquals(LeaseOutcome.Granted, espera.await())
        assertEquals(1, avisos)
    }

    @Test
    fun `resolverPendientes no pisa lo ya resuelto ni toca otro venue`() = runTest {
        val mesas = caja()
        val tomada = mesas.abrirApertura("m5", "s1", "Juan", "V1")
        val pendiente = mesas.abrirApertura("m6", "s1", "Juan", "V1")
        val otra = mesas.abrirApertura("m7", "s1", "Juan", "V2")
        mesas.resolver(tomada.id, LeaseOutcome.Taken("Ana"))

        mesas.resolverPendientes("V1", LeaseOutcome.NoHub)
        mesas.resolver(otra.id, LeaseOutcome.Granted)

        assertEquals(LeaseOutcome.Taken("Ana"), mesas.esperar(tomada) { true })
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(pendiente) { true })
        assertEquals(LeaseOutcome.Granted, mesas.esperar(otra) { true })
        assertEquals("resolver no quita: sólo el dueño termina", 2, mesas.declaracion("V1").aperturas.size)
    }

    @Test
    fun `cambios suena con otra mesa o con aperturas, no con la misma mesa ni con una resolucion`() = runTest {
        val mesas = caja()
        var avisos = 0
        backgroundScope.launch { mesas.cambios.collect { avisos++ } }
        runCurrent()
        val inicial = avisos

        mesa.value = null; runCurrent()
        assertEquals(inicial, avisos)
        mesa.value = "m13"; runCurrent()
        assertEquals(inicial + 1, avisos)
        val a = mesas.abrirApertura("m14", "s1", "Juan", "V1"); runCurrent()
        assertEquals(inicial + 2, avisos)
        mesas.resolver(a.id, LeaseOutcome.Granted); mesas.marcarEsperando(a.id); runCurrent()
        assertEquals(inicial + 2, avisos)
        mesas.terminarApertura(a); runCurrent()
        assertEquals(inicial + 3, avisos)
    }

    @Test
    fun `seq empieza en 1 y la epoca sube`() = runTest {
        val mesas = caja()
        assertEquals(1L, mesas.siguienteSeq())
        assertEquals(2L, mesas.siguienteSeq())
        val antes = mesas.epoca()
        assertEquals(antes + 1, mesas.nuevaEpoca())
        assertEquals(antes + 1, mesas.epoca())
        assertEquals(1L, mesas.gen)
    }

    /** `Context` + `SharedPreferences` + `Editor` de mockk sobre un mapa (patrón `KdsPrefsTest`), con `commit()`. */
    private fun contextSobre(disco: MutableMap<String, Long>, commitOk: () -> Boolean, alCommit: () -> Unit): Context {
        val pendientes = mutableMapOf<String, Long>()
        val editor = mockk<SharedPreferences.Editor>()
        val prefs = mockk<SharedPreferences>()
        val context = mockk<Context>()
        every { context.getSharedPreferences("hub_presencia", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getLong(any(), any()) } answers { disco[firstArg<String>()] ?: secondArg<Long>() }
        every { prefs.edit() } returns editor
        every { editor.putLong(any(), any()) } answers { pendientes[firstArg<String>()] = secondArg<Long>(); editor }
        every { editor.commit() } answers {
            alCommit()
            commitOk().also { ok -> if (ok) disco.putAll(pendientes); pendientes.clear() }
        }
        return context
    }
}

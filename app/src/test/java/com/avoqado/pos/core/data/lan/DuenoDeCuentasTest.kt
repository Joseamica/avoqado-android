package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.orders.data.CopiaDeCuentas
import com.avoqado.pos.orders.data.CuentaCobradaAqui
import com.avoqado.pos.orders.data.CuentasAbiertasAqui
import com.avoqado.pos.orders.data.CuentasCobradasAqui
import com.avoqado.pos.orders.data.Lectura
import com.avoqado.pos.orders.data.model.OrderSummary
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D12 · Opción D, pieza 2: la caja DUEÑA (la que abrió la cuenta) guarda su registro en disco y contesta. H1 con tres cajas y la copia
 * vieja de la tercera, R2 (lo propio se reconcilia), R4 (el dinero durable manda), «No se cobró», reinicio y el aviso de la copia.
 */
class DuenoDeCuentasTest {
    private val v = "v1"
    private val disco = mutableMapOf<String, PayloadCache.Cached>()
    private var falloAlGuardar = false
    /** Sólo ese tipo de `PayloadCache` falla al guardar (la copia sí guarda). */
    private var fallaElTipo: String? = null
    private val cache = mockk<PayloadCache> {
        coEvery { load(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { loadDurable(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { saveDurable(any(), any(), any()) } answers {
            if (falloAlGuardar || firstArg<String>() == fallaElTipo) throw IllegalStateException("disco lleno")
            disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0)
        }
    }
    private val llaveDelRegistro = "${PayloadCache.TYPE_REGISTRO_DE_CUENTAS}|v1"
    private var mias = setOf("o1")
    private val abiertas = mockk<CuentasAbiertasAqui> { coEvery { todas(any()) } answers { mias } }
    private var dinero = false
    private var tarjeta = false
    private val filas = mutableMapOf<String, String>()
    private var arranque = "boot-1"
    private var ms = 1_000L
    private var hora = 1_700_000_000_000L
    private fun pasan(n: Long) { ms += n; hora += n }

    /** La caja 1, dueña de lo que esté en [mias]. Una instancia nueva = la app se reinició (mismo disco). */
    private fun cajaUno() = DuenoDeCuentas(cache, abiertas, { _, _ -> dinero }, { _, _ -> tarjeta }, { filas[it] }, { "caja-1" }, { "Caja 1" })
        .also { it.reloj = { Instante(arranque, ms) }; it.horaDePared = { hora } }

    private val ana = Solicitud("caja-2", "Caja 2", "Ana", "i-ana")
    private val beto = Solicitud("caja-3", "Caja 3", "Beto", "i-beto")
    private val betoEnLinea = beto.copy(enLinea = true)

    @Test fun `una cuenta que no abrio no la contesta`() = runTest {
        mias = emptySet()
        val a = cajaUno()
        assertNull(a.apartar(v, "o1", ana))
        assertNull(a.comprometer(v, "o1", ana))
        assertNull(a.soltar(v, "o1", "i-ana"))
        assertNull(a.cobrada(v, "o1", ana, subida = false))
        assertNull(a.comprometerPropio(v, "o1", "llave", "Yo", enLinea = false))
        assertNull(a.estadoDe(v, "o1"))
    }

    @Test fun `H1 - con tres cajas, la tercera con su copia vieja y sin red siempre recibe cerrada`() = runTest {
        val a = cajaUno()
        assertEquals(Respuesta.Concedida, a.apartar(v, "o1", ana))
        assertEquals(Respuesta.Ocupada("Ana", "Caja 2"), a.apartar(v, "o1", beto))
        assertEquals(Respuesta.Concedida, a.comprometer(v, "o1", ana))
        assertEquals(true, a.cobrada(v, "o1", ana, subida = false))
        // Beto todavía ve la cuenta abierta en su copia: sin red y en línea, «cobrada en Caja 2, falta subirla».
        assertEquals(Respuesta.Cerrada("Caja 2", true), a.apartar(v, "o1", beto))
        assertEquals(Respuesta.Cerrada("Caja 2", true), a.comprometer(v, "o1", betoEnLinea))
        // La Caja 2 subió el pago: sigue CERRADA para quien pregunta sin red; con internet manda el saldo del servidor.
        assertEquals(true, a.cobrada(v, "o1", ana, subida = true))
        assertEquals(Respuesta.Cerrada("Caja 2", false), a.apartar(v, "o1", beto))
        assertEquals(Respuesta.Concedida, a.apartar(v, "o1", betoEnLinea))
        // Pasan las 24 h de la copia y la app de la dueña se reinicia: nada la vuelve LIBRE.
        pasan(3 * 24 * 60 * 60 * 1000L)
        val reiniciada = cajaUno()
        assertEquals(Respuesta.Cerrada("Caja 2", false), reiniciada.apartar(v, "o1", beto))
        assertEquals(EstadoDeCuenta.CERRADA, reiniciada.estadoDe(v, "o1")?.estado)
    }

    @Test fun `R4 - con dinero durable de esta caja no concede aunque el registro diga LIBRE, y no lo escribe`() = runTest {
        dinero = true
        val a = cajaUno()
        assertEquals(Respuesta.Cerrada("Caja 1", true), a.apartar(v, "o1", ana))
        assertEquals(Respuesta.Cerrada("Caja 1", true), a.comprometer(v, "o1", ana))
        assertNull(a.estadoDe(v, "o1"))
        dinero = false
        assertEquals(Respuesta.Concedida, a.apartar(v, "o1", ana))
    }

    @Test fun `R2 - el compromiso propio vive en el proceso y al reiniciar se reconcilia con su fila`() = runTest {
        val a = cajaUno()
        assertEquals(Respuesta.Concedida, a.comprometerPropio(v, "o1", "llave-1", "Yo", enLinea = false))
        // Vivo: aunque no haya fila todavía, nadie más la obtiene.
        assertEquals(Respuesta.Ocupada("Yo", "Caja 1"), a.apartar(v, "o1", ana))
        // La app murió con el efectivo EN_VUELO: al volver, su fila manda (se dará por cobrado).
        filas["llave-1"] = "EN_VUELO"
        val tras = cajaUno()
        assertEquals(CuentaEnElRegistro(EstadoDeCuenta.CERRADA, "caja-1", "Caja 1", "Yo", "llave-1", Instante(arranque, ms), hora, faltaSubirla = true),
            tras.estadoDe(v, "o1"))
        assertEquals(Respuesta.Cerrada("Caja 1", true), tras.apartar(v, "o1", ana))
    }

    @Test fun `R2 - sin fila, una tarjeta en curso lo sostiene y sin nada se suelta`() = runTest {
        cajaUno().comprometerPropio(v, "o1", "llave-1", "Yo", enLinea = true)
        tarjeta = true
        assertEquals(EstadoDeCuenta.COMPROMETIDA, cajaUno().estadoDe(v, "o1")?.estado)
        tarjeta = false
        assertNull(cajaUno().estadoDe(v, "o1"))
        assertEquals(Respuesta.Concedida, cajaUno().apartar(v, "o1", ana))
    }

    @Test fun `terminar el flujo propio sin dinero la suelta, y cerrarlo con dinero la deja CERRADA`() = runTest {
        val a = cajaUno()
        a.comprometerPropio(v, "o1", "llave-1", "Yo", enLinea = false)
        a.terminarPropio(v, "o1", "llave-1")
        assertNull(a.estadoDe(v, "o1"))
        a.comprometerPropio(v, "o1", "llave-2", "Yo", enLinea = false)
        a.cerrarPropio(v, "o1", "llave-2", "Yo", subida = false)
        assertEquals(Respuesta.Cerrada("Caja 1", true), a.apartar(v, "o1", ana))
    }

    @Test fun `el cobro propio en cola deja de faltar al verse su fila SYNCED, y una fila borrada sin verla no lo quita`() = runTest {
        val a = cajaUno()
        a.comprometerPropio(v, "o1", "llave-1", "Yo", enLinea = false)
        a.cerrarPropio(v, "o1", "llave-1", "Yo", subida = false)
        assertTrue(a.estadoDe(v, "o1")!!.faltaSubirla)
        // La fila se borró sin verse SYNCED: sólo una lectura del servidor lo quita (lado conservador).
        assertTrue(cajaUno().estadoDe(v, "o1")!!.faltaSubirla)
        filas["llave-1"] = "SYNCED"
        assertFalse(cajaUno().estadoDe(v, "o1")!!.faltaSubirla)
        // Con internet ya no la bloquea a la dueña (manda el saldo del servidor); sin red sigue CERRADA.
        assertEquals(Respuesta.Concedida, a.comprometerPropio(v, "o1", "llave-2", "Yo", enLinea = true))
        assertEquals(Respuesta.Cerrada("Caja 1", false), a.apartar(v, "o1", ana))
    }

    @Test fun `la duena con su cuenta CERRADA - sin red siempre bloquea, en linea solo si falta subirla`() = runTest {
        val a = cajaUno()
        a.comprometerPropio(v, "o1", "llave-1", "Yo", enLinea = true)
        a.cerrarPropio(v, "o1", "llave-1", "Yo", subida = false)
        assertEquals(Respuesta.Cerrada("Caja 1", true), a.comprometerPropio(v, "o1", "llave-2", "Yo", enLinea = true))
        a.cobrada(v, "o1", Solicitud("caja-1", "Caja 1", "Yo", "llave-1"), subida = true)
        assertEquals(Respuesta.Cerrada("Caja 1", false), a.comprometerPropio(v, "o1", "llave-3", "Yo", enLinea = false))
        assertEquals(Respuesta.Concedida, a.comprometerPropio(v, "o1", "llave-4", "Yo", enLinea = true))
        assertEquals(EstadoDeCuenta.CERRADA, a.estadoDe(v, "o1")?.estado)
    }

    @Test fun `No se cobro - sólo a los 2 min y sobre otra caja, rechaza lo tardío y acepta su cobrada`() = runTest {
        val a = cajaUno()
        a.comprometer(v, "o1", ana)
        pasan(RegistroDeCuentas.NO_CONFIRMO_MS - 1)
        assertFalse(a.puedeDeclararNoSeCobro(a.estadoDe(v, "o1")))
        assertFalse(a.noSeCobro(v, "o1", "Gerente"))
        pasan(1)
        assertTrue(a.puedeDeclararNoSeCobro(a.estadoDe(v, "o1")))
        assertTrue(a.noSeCobro(v, "o1", "Gerente"))
        assertNull(a.estadoDe(v, "o1"))
        assertEquals(Respuesta.Rechazada, a.comprometer(v, "o1", ana))
        assertEquals(Respuesta.Rechazada, a.apartar(v, "o1", ana))
        assertEquals(true, a.cobrada(v, "o1", ana, subida = false))
        assertEquals(EstadoDeCuenta.CERRADA, a.estadoDe(v, "o1")?.estado)
    }

    @Test fun `el cobro propio nunca ofrece No se cobro`() = runTest {
        val a = cajaUno()
        a.comprometerPropio(v, "o1", "llave-1", "Yo", enLinea = false)
        pasan(RegistroDeCuentas.NO_CONFIRMO_MS)
        assertFalse(a.puedeDeclararNoSeCobro(a.estadoDe(v, "o1")))
    }

    @Test fun `registro ilegible - no contesta, no escribe encima y el cobro propio no pasa`() = runTest {
        disco[llaveDelRegistro] = PayloadCache.Cached("{roto", 0)
        val a = cajaUno()
        assertNull(a.apartar(v, "o1", ana))
        assertEquals("{roto", disco[llaveDelRegistro]?.json)
        assertTrue(runCatching { a.comprometerPropio(v, "o1", "llave", "Yo", enLinea = false) }.isFailure)
    }

    @Test fun `disco que no guarda - no se concede`() = runTest {
        falloAlGuardar = true
        val a = cajaUno()
        assertNull(a.apartar(v, "o1", ana))
        assertTrue(runCatching { a.comprometerPropio(v, "o1", "llave", "Yo", enLinea = false) }.isFailure)
        assertTrue(runCatching { a.cobrada(v, "o1", ana, subida = false) }.isFailure) // sin acuse: la otra caja reintenta
    }

    @Test fun `reinicio real del aparato - el apartado de otra caja cae, lo comprometido se queda`() = runTest {
        mias = setOf("o1", "o2")
        cajaUno().apartar(v, "o1", ana)
        cajaUno().comprometer(v, "o2", ana)
        arranque = "boot-2"; ms = 10
        val tras = cajaUno()
        assertNull(tras.estadoDe(v, "o1"))
        assertEquals(Respuesta.Concedida, tras.apartar(v, "o1", beto))
        assertEquals(Respuesta.Ocupada("Ana", "Caja 2"), tras.apartar(v, "o2", beto))
    }

    @Test fun `sin la marca abierta aqui, su renglon sale del registro`() = runTest {
        mias = setOf("o1", "o2")
        cajaUno().cobrada(v, "o1", ana, subida = false)
        mias = setOf("o2")
        cajaUno().estadoDe(v, "o2") // cualquier escritura poda
        mias = setOf("o1", "o2")
        assertNull(cajaUno().estadoDe(v, "o1"))
    }

    // MARK: - La copia de «Pedidos» le avisa a la dueña

    private var crudoDeMarcas: String? = null
    private val marcas = CuentasCobradasAqui(
        leerCrudo = { crudoDeMarcas },
        escribirCrudo = { f -> crudoDeMarcas = f(crudoDeMarcas); true },
        quitarTarjetaYAnotarCrudo = { _, f -> crudoDeMarcas = f(crudoDeMarcas); true },
    )

    private fun cuenta(id: String, pago: String = "PENDING") = OrderSummary(
        id = id, esDeMesa = false, source = "AVOQADO_ANDROID", status = "PENDING", paymentStatus = pago,
        createdAt = "2026-11-10T18:00:00Z", saldoPendienteCents = 6000, pagosCompletados = 0,
    )

    private fun copiaDe(a: DuenoDeCuentas) = CopiaDeCuentas(cache, marcas, javax.inject.Provider { a }).also {
        it.ahora = { hora }
        it.io = kotlinx.coroutines.Dispatchers.Unconfined
    }

    @Test fun `la lista del servidor que ya no la trae, o la trae pagada, la deja CERRADA`() = runTest {
        mias = setOf("o1", "o2")
        val a = cajaUno()
        val copia = copiaDe(a)
        copia.alLeer(v, listOf(cuenta("o1"), cuenta("o2")), Lectura.LISTA_COMPLETA)
        assertNull(a.estadoDe(v, "o1"))
        copia.alLeer(v, listOf(cuenta("o2", pago = "PAID")), Lectura.LISTA_COMPLETA)
        assertEquals(RegistroDeCuentas.SERVIDOR, a.estadoDe(v, "o1")?.por)
        assertEquals(RegistroDeCuentas.SERVIDOR, a.estadoDe(v, "o2")?.por)
        assertEquals(Respuesta.Cerrada("", false), a.apartar(v, "o1", beto))
    }

    @Test fun `una tarjeta de esta caja que ya consta la deja CERRADA, y la marca no se suelta si la duena no pudo guardar`() = runTest {
        val a = cajaUno()
        val copia = copiaDe(a)
        copia.alLeer(v, listOf(cuenta("o1")), Lectura.LISTA_COMPLETA)
        marcas.anotar(CuentaCobradaAqui(llave = "tarjeta:r1", venueId = v, orderId = "o1"))
        fallaElTipo = PayloadCache.TYPE_REGISTRO_DE_CUENTAS
        copia.cuentas(v)
        assertEquals(1, marcas.marcasDe(v).size)
        fallaElTipo = null
        copia.cuentas(v)
        assertEquals(0, marcas.marcasDe(v).size)
        assertEquals(Respuesta.Cerrada("Caja 1", false), a.apartar(v, "o1", beto))
    }
}

package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.orders.data.CopiaDeCuentas
import com.avoqado.pos.orders.data.CuentaCobradaAqui
import com.avoqado.pos.orders.data.CuentasAbiertasAqui
import com.avoqado.pos.orders.data.CuentasCobradasAqui
import com.avoqado.pos.orders.data.Lectura
import com.avoqado.pos.orders.data.model.OrderSummary
import io.mockk.coEvery
import io.mockk.every
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
    private var hiloDeTodas: String? = null
    private val abiertas = mockk<CuentasAbiertasAqui> { coEvery { todas(any()) } answers { hiloDeTodas = Thread.currentThread().name; mias } }
    private var dinero = false
    private var tarjeta = false
    private val filas = mutableMapOf<String, String>()
    private var arranque = "boot-1"
    private var ms = 1_000L
    private var hora = 1_700_000_000_000L
    private fun pasan(n: Long) { ms += n; hora += n }

    /** La caja 1, dueña de lo que esté en [mias]. Una instancia nueva = la app se reinició (mismo disco). */
    private fun cajaUno() = DuenoDeCuentas(cache, abiertas, { _, _, _ -> dinero }, { _, _ -> tarjeta }, { filas[it] }, { "caja-1" }, { "Caja 1" })
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
        // La app murió y su efectivo ya pasó a la cola: al volver, su fila manda.
        filas["llave-1"] = "PENDING"
        val tras = cajaUno()
        // Familia D: la reconciliación no sabe si ese cobro dejó saldo (pudo ser un abono): no la da por «Cobrada» (conAbono).
        assertEquals(CuentaEnElRegistro(EstadoDeCuenta.CERRADA, "caja-1", "Caja 1", "Yo", "llave-1", Instante(arranque, ms), hora, faltaSubirla = true,
            conAbono = true), tras.estadoDe(v, "o1"))
        assertEquals(Respuesta.Cerrada("Caja 1", true, conAbono = true), tras.apartar(v, "o1", ana))
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
        // Familia D: la marca de tarjeta no sabe si quedó saldo y no había nada antes: protegida, pero no «Cobrada» (conAbono).
        assertEquals(Respuesta.Cerrada("Caja 1", false, conAbono = true), a.apartar(v, "o1", beto))
    }

    /**
     * Revisión final M-1: un saldo de regalo de esta caja que no alcanzó (su marca no dice «pagada») la deja CERRADA CON ABONO: sigue
     * protegida, pero la lista no dice «Cobrada en Caja 1».
     */
    @Test fun `M-1 - una marca de cobro que no la pagó la deja CERRADA con abono`() = runTest {
        val a = cajaUno()
        val copia = copiaDe(a)
        copia.alLeer(v, listOf(cuenta("o1")), Lectura.LISTA_COMPLETA)
        marcas.anotar(CuentaCobradaAqui(llave = "saldo:r1", venueId = v, orderId = "o1", pagada = false, saldoCents = 2000, conSaldo = true))
        copia.cuentas(v)
        val r = a.estadoDe(v, "o1")
        assertEquals(EstadoDeCuenta.CERRADA, r?.estado)
        assertTrue("con abono: no es «Cobrada en Caja 1»", r!!.conAbono)
    }

    // MARK: - Ronda de arreglos 1

    /** C1 (dueña): una EN_VUELO es dinero EN DUDA: no la cierra; sigue COMPROMETIDA y, si la cajera la descarta, vuelve a LIBRE. */
    @Test fun `C1 - una EN_VUELO en duda no la cierra, sigue COMPROMETIDA y si se descarta vuelve a LIBRE`() = runTest {
        val a = cajaUno()
        a.comprometerPropio(v, "o1", "llave-1", "Yo", enLinea = false)
        filas["llave-1"] = "EN_VUELO"
        a.terminarPropio(v, "o1", "llave-1") // la pantalla se fue con el apartado vivo en su ViewModel
        assertEquals(EstadoDeCuenta.COMPROMETIDA, a.estadoDe(v, "o1")?.estado)
        assertEquals(Respuesta.Ocupada("Yo", "Caja 1"), a.apartar(v, "o1", ana))
        filas.remove("llave-1") // «Cancelar»
        assertNull(a.estadoDe(v, "o1"))
    }

    /** C1 + I4 (dueña): la cola ya tiene el efectivo y el SERVIDOR lo rechazó (FAILED): sí se cobró, y ya no «falta subirla». */
    @Test fun `C1 e I4 - una fila FAILED propia la cierra sin falta subirla`() = runTest {
        val a = cajaUno()
        a.comprometerPropio(v, "o1", "llave-1", "Yo", enLinea = false)
        filas["llave-1"] = "FAILED"
        a.terminarPropio(v, "o1", "llave-1")
        assertEquals(CuentaEnElRegistro(EstadoDeCuenta.CERRADA, "caja-1", "Caja 1", "Yo", "llave-1", Instante(arranque, ms), hora, faltaSubirla = false,
            conAbono = true), a.estadoDe(v, "o1")) // familia D: la reconciliación no sabe si quedó saldo
    }

    /** I4: con la parte 1 de una venta dividida rechazada por el SERVIDOR (FAILED), quien pregunta CON red no queda bloqueado; sin red, sí. */
    @Test fun `I4 - una fila FAILED no bloquea a quien pregunta con red, sin red sí`() = runTest {
        val dao = mockk<com.avoqado.pos.core.data.local.database.PendingPaymentDao> {
            coEvery { forVenue(any()) } returns listOf(com.avoqado.pos.core.data.local.database.PendingPaymentEntity(
                id = "parte-1", venueId = v, staffId = "s1", amountCents = 3000, tipCents = 0, method = "CASH",
                paymentType = "ORDER", orderId = "o1", syncStatus = "FAILED",
            ))
            coEvery { syncStatusOf(any()) } returns null
        }
        val storage = mockk<com.avoqado.pos.core.data.local.SecureStorage>(relaxed = true) { every { pendingCardChargesJson } returns null }
        val solicitudes = mockk<com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore> { every { delNegocio(any()) } returns emptyList() }
        val a = DuenoDeCuentas(
            cache, abiertas,
            com.avoqado.pos.giftcards.data.CobrosLocalesDeLaCuenta(dao, storage, solicitudes),
            mockk<com.avoqado.pos.payment.data.TerminalPaymentService> { every { cobrosConTarjetaDelNegocio(any()) } returns emptyList() },
            com.avoqado.pos.orders.data.CuentasCobradasAqui({ null }, { f -> f(null); true }, { _, _ -> true }),
            dao,
            mockk<com.avoqado.pos.core.data.sync.SyncOutbox> { every { deviceId } returns "caja-1" },
            mockk<com.avoqado.pos.tpvsettings.data.TpvSettingsRepository> {
                every { terminalNavigation } returns kotlinx.coroutines.flow.MutableStateFlow(
                    com.avoqado.pos.tpvsettings.data.TerminalNavigationSettings(terminalName = "Caja 1"))
            },
            com.avoqado.pos.onlineorders.data.FoliosCobradosConTarjeta({ null }, { f -> f(null); true }),
        ).also { it.reloj = { Instante(arranque, ms) }; it.horaDePared = { hora } }
        assertEquals(Respuesta.Concedida, a.apartar(v, "o1", ana.copy(enLinea = true)))
        a.soltar(v, "o1", "i-ana")
        assertEquals("la dueña también cobra en línea", Respuesta.Concedida, a.comprometerPropio(v, "o1", "llave-2", "Yo", enLinea = true))
        a.soltar(v, "o1", "llave-2")
        assertEquals("sin red no cambia", Respuesta.Cerrada("Caja 1", true), a.apartar(v, "o1", ana))
    }

    /** M6: el registro (disco y JSON) no se lee en el hilo de quien llama (la pantalla). */
    @Test fun `M6 - el registro se lee fuera del hilo de quien llama`() = runTest {
        val yo = Thread.currentThread().name
        cajaUno().estadoDe(v, "o1")
        assertTrue("leyó en $hiloDeTodas", hiloDeTodas != null && hiloDeTodas != yo)
    }

    // MARK: - Merge de develop (#199): la marca de tarjeta de la orden (D14)

    /**
     * Ruling 2 del merge: con #199, el positivo de una tarjeta de una cuenta de Pedidos deja la marca `FoliosCobradosConTarjeta` (D14) en
     * lugar de la de la copia. Es dinero de la cuenta: la dueña no contesta LIBRE mientras exista, y su propio compromiso con esa tarjeta
     * resuelta tarde pasa a CERRADA.
     */
    @Test fun `merge 199 - la marca de tarjeta de la orden (D14) frena a las demás y cierra lo propio`() = runTest {
        var crudo: String? = null
        var enDuda = false
        val folios = com.avoqado.pos.onlineorders.data.FoliosCobradosConTarjeta({ crudo }, { f -> crudo = f(crudo); true })
        val dao = mockk<com.avoqado.pos.core.data.local.database.PendingPaymentDao> {
            coEvery { forVenue(any()) } returns emptyList()
            coEvery { syncStatusOf(any()) } returns null
        }
        val storage = mockk<com.avoqado.pos.core.data.local.SecureStorage>(relaxed = true) { every { pendingCardChargesJson } returns null }
        val a = DuenoDeCuentas(
            cache, abiertas,
            com.avoqado.pos.giftcards.data.CobrosLocalesDeLaCuenta(dao, storage,
                mockk<com.avoqado.pos.giftcards.data.SolicitudesDeTarjetaStore> { every { delNegocio(any()) } returns emptyList() }),
            mockk<com.avoqado.pos.payment.data.TerminalPaymentService> {
                every { cobrosConTarjetaDelNegocio(any()) } answers {
                    if (enDuda) listOf(com.avoqado.pos.payment.data.ContextoDeCobro("r1", venueId = v, terminalId = null, orderId = "o1", cobrado = false))
                    else emptyList()
                }
            },
            com.avoqado.pos.orders.data.CuentasCobradasAqui({ null }, { f -> f(null); true }, { _, _ -> true }),
            dao,
            mockk<com.avoqado.pos.core.data.sync.SyncOutbox> { every { deviceId } returns "caja-1" },
            mockk<com.avoqado.pos.tpvsettings.data.TpvSettingsRepository> {
                every { terminalNavigation } returns kotlinx.coroutines.flow.MutableStateFlow(
                    com.avoqado.pos.tpvsettings.data.TerminalNavigationSettings(terminalName = "Caja 1"))
            },
            folios,
        ).also { it.reloj = { Instante(arranque, ms) }; it.horaDePared = { hora } }
        // La caja 1 cobra su cuenta con tarjeta; el cobro queda en duda (en la lista) y la pantalla se va: sigue COMPROMETIDA.
        assertEquals(Respuesta.Concedida, a.comprometerPropio(v, "o1", "llave-1", "Yo", enLinea = true))
        enDuda = true
        a.terminarPropio(v, "o1", "llave-1")
        assertEquals(EstadoDeCuenta.COMPROMETIDA, a.estadoDe(v, "o1")?.estado)
        // El positivo llega tarde (conciliación): sale de la lista y deja SÓLO la marca D14.
        enDuda = false
        folios.anotar(com.avoqado.pos.onlineorders.data.FolioCobradoConTarjeta(orderId = "o1", montoCents = 6000, saldoAntesCents = 6000,
            enMs = 1L, venueId = v, requestId = "r1", esFolio = false, fueraDeLaBandeja = true))
        assertEquals(EstadoDeCuenta.CERRADA, a.estadoDe(v, "o1")?.estado)
        // Y sin compromiso propio de por medio, la marca también frena a otra caja (R4).
        mias = setOf("o1", "o3")
        folios.anotar(com.avoqado.pos.onlineorders.data.FolioCobradoConTarjeta(orderId = "o3", montoCents = 6000, saldoAntesCents = 6000,
            enMs = 1L, venueId = v, requestId = "r3", esFolio = false, fueraDeLaBandeja = true))
        assertEquals(Respuesta.Cerrada("Caja 1", true), a.apartar(v, "o3", ana))
    }

    // MARK: - Familia D: quién escribe CERRADA y si sabe que quedó saldo

    /**
     * Familia D: una marca de cobro con otra llave («tarjeta:<orden>», la de tarjeta no sabe si quedó saldo) que llega DESPUÉS conserva lo
     * que el registro ya sabía (el abono del cobro, o que se cobró todo); sin nada antes no se sabe, y no es «Cobrada». La de saldo de regalo
     * que el servidor dio por pagada sí lo sabe.
     */
    @Test fun `familia D - una marca que llega despues conserva lo que se sabia, y sin nada antes no dice Cobrada`() = runTest {
        mias = setOf("o1", "o2", "o3", "o4")
        val a = cajaUno()
        a.comprometerPropio(v, "o1", "llave-1", "Yo", enLinea = true)
        a.cerrarPropio(v, "o1", "llave-1", "Yo", subida = true, conAbono = true) // el cobro dejó saldo
        a.comprometerPropio(v, "o2", "llave-2", "Yo", enLinea = true)
        a.cerrarPropio(v, "o2", "llave-2", "Yo", subida = true) // se cobró todo
        a.alSaberCerradas(v, emptyList(), listOf("o1", "o2", "o3"))
        a.alSaberCerradas(v, emptyList(), listOf("o4"), pagadas = listOf("o4"))

        assertTrue("el abono sigue", a.estadoDe(v, "o1")!!.conAbono)
        assertFalse("lo cobrado completo sigue «Cobrada»", a.estadoDe(v, "o2")!!.conAbono)
        assertTrue("sin nada antes no se sabe: no es «Cobrada»", a.estadoDe(v, "o3")!!.conAbono)
        assertFalse("el SERVIDOR la dio por pagada", a.estadoDe(v, "o4")!!.conAbono)
    }

    /** Familia D: la app de la dueña murió tras cobrar (quizá un abono): al reconciliar no se sabe si quedó saldo, y no la deja «Cobrada». */
    @Test fun `familia D - la reconciliacion tras morir la app no dice Cobrada sin saber si quedo saldo`() = runTest {
        cajaUno().comprometerPropio(v, "o1", "llave-1", "Yo", enLinea = false)
        filas["llave-1"] = "SYNCED" // su efectivo ya subió
        val r = cajaUno().estadoDe(v, "o1")
        assertEquals(EstadoDeCuenta.CERRADA, r?.estado)
        assertFalse(r!!.faltaSubirla)
        assertTrue(r.conAbono)
        assertEquals("sin red: «cóbrala con internet», nunca «Cobrada»", Respuesta.Cerrada("Caja 1", false, conAbono = true),
            cajaUno().comprometerPropio(v, "o1", "llave-2", "Yo", enLinea = false))
    }

    /**
     * La etiqueta se refresca sola: el registro dice cuándo cambió (su versión sube al guardar), y la lista de Pedidos la usa como llave; así
     * «Cobrada en <caja>» aparece aunque Pedidos ya estuviera abierto cuando llega el «cobrada». Sólo leer no la mueve.
     */
    @Test fun `la versión del registro sube cuando cambia, y sólo leer no la mueve`() = runTest {
        val a = cajaUno()
        val antes = a.version.value
        a.cobrada(v, "o1", ana, subida = true)
        val tras = a.version.value
        assertTrue("llegó el cobrada de otra caja", tras > antes)
        a.estadoDe(v, "o1")
        assertEquals("sólo leer", tras, a.version.value)
    }
}

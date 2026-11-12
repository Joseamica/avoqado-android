package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.orders.data.CuentasAbiertasAqui
import com.avoqado.pos.payment.domain.TextosDeCuentaSinRed
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D12 · Opción D, pieza 4: tres cajas en una red de mentira (cada una con su disco, su registro y su reloj). La caja 1 abrió la cuenta y es
 * la única que da permiso; las demás le preguntan, comprometen justo antes del cajón y le avisan «cobrada» o «suelta» hasta su acuse.
 */
class CuentasEntreCajasTest {
    private val v = "v1"
    private var hora = 1_700_000_000_000L
    private val cajas = mutableMapOf<String, Caja>()

    /** El cable de verdad (JSON por `CuentasPorLaRed.contestar`), sin sockets. */
    private val cliente = mockk<LeaseClient> {
        coEvery { preguntarCuenta(any(), any(), any(), any()) } coAnswers {
            val caja = cajas[firstArg<LanPeer>().deviceId]
            if (caja == null || !caja.contesta) null
            else LeaseProtocol.decodeResponse(CuentasPorLaRed.contestar(LeaseProtocol.encode(secondArg<LeaseRequest>()), v, caja.dueno))
                .takeUnless { caja.pierdeRespuestas }
        }
    }

    private inner class Caja(val id: String, val nombre: String, val mias: Set<String> = emptySet()) {
        val disco = mutableMapOf<String, PayloadCache.Cached>()
        val filas = mutableMapOf<String, String>()
        var ms = 1_000L
        var contesta = true
        var pierdeRespuestas = false
        val cache = mockk<PayloadCache> {
            coEvery { loadDurable(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
            coEvery { saveDurable(any(), any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0) }
        }
        val dueno = DuenoDeCuentas(cache, mockk<CuentasAbiertasAqui> { coEvery { todas(any()) } returns mias }, { _, _ -> false }, { _, _ -> false },
            { filas[it] }, { id }, { nombre }).also { it.reloj = { Instante("boot-$id", ms) }; it.horaDePared = { hora } }
        val peers = MutableStateFlow<List<LanPeer>>(emptyList())
        private val red = object : LanDiscoveryPort {
            override val peers = this@Caja.peers
            override fun conectarHub(respondTo: (String) -> LeaseResponse) {}
            override fun desconectarHub() {}
        }
        val entre = CuentasEntreCajas(red, dueno, cache) { filas[it] }.also {
            it.client = cliente
            it.reloj = { ms }
            it.horaDePared = { hora }
        }
        val peer get() = LanPeer(id, "10.0.0.${cajas.size}", 9000, venueExplicito = true, participaEnD = true, nombreCaja = nombre)
    }

    private fun red(vararg c: Caja) {
        c.forEach { cajas[it.id] = it }
        c.forEach { yo -> yo.peers.value = c.filter { it !== yo }.map { it.peer }; yo.entre.encender(v) }
    }

    @After fun apagar() = cajas.values.forEach { it.entre.apagar() }

    private val a = Caja("caja-1", "Caja 1", mias = setOf("o1"))
    private val b = Caja("caja-2", "Caja 2")
    private val c = Caja("caja-3", "Caja 3")

    private suspend fun CuentasEntreCajas.puertaSinRed(intentoPrevio: String? = null, llave: String = "llave-c") =
        puerta(v, "o1", llave, "Beto", enLinea = false, esTarjeta = false, esCuentaDePedidos = true, intentoPrevio = intentoPrevio)

    @Test fun `se pregunta a las cajas con D, contesta sólo la que la abrió y se guarda quién es`() = runTest {
        red(a, b, c)
        val r = c.entre.apartar(v, "o1", "i-c", "Beto", enLinea = false)
        assertEquals(Respuesta.Concedida, r.respuesta)
        assertEquals("caja-1", r.deviceId)
        assertEquals("Caja 1", r.caja)
        assertEquals("Caja 1", c.entre.cajaQueLaAbrio(v, "o1"))
        assertEquals(EstadoDeCuenta.APARTADA, a.dueno.estadoDe(v, "o1")?.estado)
    }

    @Test fun `si otra caja la tiene, se dice quién y dónde`() = runTest {
        red(a, b, c)
        assertEquals(Respuesta.Concedida, b.entre.apartar(v, "o1", "i-b", "Ana", enLinea = false).respuesta)
        val r = c.entre.apartar(v, "o1", "i-c", "Beto", enLinea = false)
        assertEquals(Respuesta.Ocupada("Ana", "Caja 2"), r.respuesta)
        assertEquals("Ana la está cobrando en Caja 2. Espera a que termine.", CuentasEntreCajas.textoDe(r.respuesta!!, r.caja))
    }

    @Test fun `la caja que la abrió ya no se ve - se nombra y no se cobra`() = runTest {
        red(a, c)
        c.entre.apartar(v, "o1", "i-c", "Beto", enLinea = false)
        a.contesta = false
        val r = c.entre.apartar(v, "o1", "i-c2", "Beto", enLinea = false)
        assertNull(r.respuesta)
        assertEquals("Caja 1", r.caja)
        assertEquals(CuentasEntreCajas.Puerta.NoPasa(TextosDeCuentaSinRed.sePerdioElContacto("Caja 1")), c.entre.puertaSinRed())
    }

    @Test fun `nadie la abrió en la red - el gris genérico`() = runTest {
        red(b, c)
        assertNull(c.entre.apartar(v, "o1", "i-c", "Beto", enLinea = false).respuesta)
        assertEquals(CuentasEntreCajas.Puerta.NoPasa(TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA), c.entre.puertaSinRed())
    }

    @Test fun `sin red concede, guarda el aviso antes del cajón y tras el cobro avisa cobrada hasta el acuse (H4)`() = runTest {
        red(a, c)
        c.entre.apartar(v, "o1", "i-c", "Beto", enLinea = false)
        val pasa = c.entre.puertaSinRed() as CuentasEntreCajas.Puerta.Pasa
        assertEquals("llave-c", pasa.intento)
        assertFalse(pasa.esDueno)
        assertEquals(EstadoDeCuenta.COMPROMETIDA, a.dueno.estadoDe(v, "o1")?.estado)
        // Mientras el cobro vive no se manda nada aunque ya exista la reserva.
        c.filas["llave-c"] = "EN_VUELO"
        c.entre.unaVuelta()
        assertEquals(EstadoDeCuenta.COMPROMETIDA, a.dueno.estadoDe(v, "o1")?.estado)
        c.entre.terminar(v, "o1", pasa, "Beto", cobrado = true, subida = false)
        c.entre.unaVuelta()
        assertEquals(Respuesta.Cerrada("Caja 3", true), a.dueno.apartar(v, "o1", Solicitud("caja-2", "Caja 2", "Ana", "i-b")))
        c.filas["llave-c"] = "SYNCED"
        c.entre.unaVuelta()
        assertFalse(a.dueno.estadoDe(v, "o1")!!.faltaSubirla)
    }

    @Test fun `H4 - con la fila ya limpia y la dueña fuera de la red, el aviso espera a su acuse`() = runTest {
        red(a, c)
        val pasa = c.entre.puertaSinRed() as CuentasEntreCajas.Puerta.Pasa
        c.filas["llave-c"] = "SYNCED"
        a.contesta = false
        c.entre.terminar(v, "o1", pasa, "Beto", cobrado = true, subida = true)
        c.entre.unaVuelta()
        c.filas.remove("llave-c") // la limpieza de pagos subidos
        c.entre.unaVuelta()
        assertEquals(EstadoDeCuenta.COMPROMETIDA, a.dueno.estadoDe(v, "o1")?.estado)
        a.contesta = true
        c.entre.unaVuelta()
        assertEquals(EstadoDeCuenta.CERRADA, a.dueno.estadoDe(v, "o1")?.estado)
        assertFalse(a.dueno.estadoDe(v, "o1")!!.faltaSubirla)
    }

    @Test fun `sin dinero, suelta hasta el acuse`() = runTest {
        red(a, c)
        val pasa = c.entre.puertaSinRed() as CuentasEntreCajas.Puerta.Pasa
        c.entre.terminar(v, "o1", pasa, "Beto", cobrado = false, subida = false)
        c.entre.unaVuelta()
        assertNull(a.dueno.estadoDe(v, "o1"))
    }

    @Test fun `la respuesta de comprometer se pierde - no recibas el efectivo, y la suelta llega cuando vuelve`() = runTest {
        red(a, c)
        c.entre.apartar(v, "o1", "i-c", "Beto", enLinea = false)
        a.pierdeRespuestas = true
        assertEquals(CuentasEntreCajas.Puerta.NoPasa(TextosDeCuentaSinRed.sePerdioElContacto("Caja 1")), c.entre.puertaSinRed())
        assertEquals(EstadoDeCuenta.COMPROMETIDA, a.dueno.estadoDe(v, "o1")?.estado) // la dueña sí lo anotó
        a.pierdeRespuestas = false
        c.entre.unaVuelta()
        assertNull(a.dueno.estadoDe(v, "o1"))
    }

    @Test fun `con internet - sin respuesta se cobra como hoy, una cuenta que no es de Pedidos no pregunta y la tarjeta ajena no entra`() = runTest {
        red(a, c)
        a.contesta = false
        assertEquals(CuentasEntreCajas.Puerta.Libre, c.entre.puerta(v, "o1", "l1", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
        assertEquals(CuentasEntreCajas.Puerta.Libre, c.entre.puerta(v, "o9", "l2", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = false))
        assertEquals(CuentasEntreCajas.Puerta.Libre, c.entre.puerta(v, "o1", "l3", "Beto", enLinea = false, esTarjeta = true, esCuentaDePedidos = true))
        coVerify(exactly = 0) { cliente.preguntarCuenta(any(), match { it.tableId == "cuenta:o9" }, any(), any()) }
    }

    @Test fun `con internet otra caja también le pregunta y respeta lo que diga (regla 8)`() = runTest {
        red(a, b, c)
        b.entre.apartar(v, "o1", "i-b", "Ana", enLinea = false)
        assertEquals(CuentasEntreCajas.Puerta.NoPasa("Ana la está cobrando en Caja 2. Espera a que termine."),
            c.entre.puerta(v, "o1", "l1", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
    }

    @Test fun `R1 - la autorización de la dueña vale 20 s desde el envío`() = runTest {
        red(a, c)
        val pasa = c.entre.puertaSinRed() as CuentasEntreCajas.Puerta.Pasa
        c.ms += RegistroDeCuentas.AUTORIZACION_MS - 1
        assertTrue(c.entre.autorizacionVigente(pasa))
        c.ms += 1
        assertFalse(c.entre.autorizacionVigente(pasa))
    }

    @Test fun `R1 - tras No se cobró en la dueña, el intento viejo ya no se compromete`() = runTest {
        red(a, c)
        val pasa = c.entre.puertaSinRed() as CuentasEntreCajas.Puerta.Pasa
        a.ms += RegistroDeCuentas.NO_CONFIRMO_MS
        assertTrue(a.dueno.puedeDeclararNoSeCobro(a.dueno.estadoDe(v, "o1")))
        assertTrue(a.dueno.noSeCobro(v, "o1", "Gerente"))
        c.ms += RegistroDeCuentas.AUTORIZACION_MS // C despierta con la autorización vencida y vuelve a comprometer
        assertFalse(c.entre.autorizacionVigente(pasa))
        assertEquals(CuentasEntreCajas.Puerta.NoPasa(TextosDeCuentaSinRed.sePerdioElContacto("Caja 1")), c.entre.puertaSinRed(intentoPrevio = pasa.intento))
    }

    @Test fun `la dueña compromete en local, en disco, y al terminar cierra o suelta`() = runTest {
        red(a, c)
        val pasa = a.entre.puerta(v, "o1", "llave-a", "Yo", enLinea = false, esTarjeta = false, esCuentaDePedidos = true) as CuentasEntreCajas.Puerta.Pasa
        assertTrue(pasa.esDueno)
        assertNull(pasa.autorizadoEn)
        assertEquals(Respuesta.Ocupada("Yo", "Caja 1"), c.entre.apartar(v, "o1", "i-c", "Beto", enLinea = false).respuesta)
        a.entre.terminar(v, "o1", pasa, "Yo", cobrado = false, subida = false)
        assertNull(a.dueno.estadoDe(v, "o1"))
        val otra = a.entre.puerta(v, "o1", "llave-a2", "Yo", enLinea = false, esTarjeta = true, esCuentaDePedidos = true) as CuentasEntreCajas.Puerta.Pasa
        a.entre.terminar(v, "o1", otra, "Yo", cobrado = true, subida = false)
        assertEquals(Respuesta.Cerrada("Caja 1", true), c.entre.apartar(v, "o1", "i-c2", "Beto", enLinea = false).respuesta)
    }

    @Test fun `CERRADA en las dos puntas - sin red nunca, en línea sólo si ya subió`() = runTest {
        red(a, c)
        val p = a.entre.puerta(v, "o1", "llave-a", "Yo", enLinea = true, esTarjeta = false, esCuentaDePedidos = true) as CuentasEntreCajas.Puerta.Pasa
        a.entre.terminar(v, "o1", p, "Yo", cobrado = true, subida = false)
        // Falta subirla: nadie la cobra, ni en línea.
        assertEquals(CuentasEntreCajas.Puerta.NoPasa("Cobrada en Caja 1 · falta subirla"),
            a.entre.puerta(v, "o1", "llave-a2", "Yo", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
        assertEquals(CuentasEntreCajas.Puerta.NoPasa("Cobrada en Caja 1 · falta subirla"),
            c.entre.puerta(v, "o1", "llave-c", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
        a.dueno.cobrada(v, "o1", Solicitud("caja-1", "Caja 1", "Yo", "llave-a"), subida = true)
        // La dueña: sin red, no; en línea, sí (manda el saldo del servidor).
        assertEquals(CuentasEntreCajas.Puerta.NoPasa("Cobrada en Caja 1"),
            a.entre.puerta(v, "o1", "llave-a3", "Yo", enLinea = false, esTarjeta = false, esCuentaDePedidos = true))
        assertTrue(a.entre.puerta(v, "o1", "llave-a4", "Yo", enLinea = true, esTarjeta = false, esCuentaDePedidos = true) is CuentasEntreCajas.Puerta.Pasa)
        // Otra caja: con la copia vieja y sin red, «cobrada» (H1); en línea, sí.
        assertEquals(CuentasEntreCajas.Puerta.NoPasa("Cobrada en Caja 1"), c.entre.puertaSinRed(llave = "llave-c2"))
        assertTrue(c.entre.puerta(v, "o1", "llave-c3", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = true) is CuentasEntreCajas.Puerta.Pasa)
    }

    @Test fun `renovar cada 10 s sostiene el apartado más allá de 30 s, y sin renovar vence`() = runTest {
        red(a, b, c)
        c.entre.apartar(v, "o1", "i-c", "Beto", enLinea = false)
        a.ms += 20_000
        c.entre.unaVuelta()
        a.ms += 25_000
        assertEquals(Respuesta.Ocupada("Beto", "Caja 3"), b.entre.apartar(v, "o1", "i-b", "Ana", enLinea = false).respuesta)
        c.entre.soltarApartado("o1")
        a.ms += 30_000
        assertEquals(Respuesta.Concedida, b.entre.apartar(v, "o1", "i-b2", "Ana", enLinea = false).respuesta)
    }

    @Test fun `la puerta compromete con la llave del cobro y reemplaza el apartado de la misma caja`() = runTest {
        red(a, b, c)
        c.entre.apartar(v, "o1", "i-tap", "Beto", enLinea = false)
        assertTrue(c.entre.tieneApartado("o1"))
        assertEquals("llave-c", (c.entre.puertaSinRed() as CuentasEntreCajas.Puerta.Pasa).intento)
        assertFalse(c.entre.tieneApartado("o1"))
        assertEquals(Respuesta.Ocupada("Beto", "Caja 3"), b.entre.apartar(v, "o1", "i-b", "Ana", enLinea = false).respuesta)
    }
}

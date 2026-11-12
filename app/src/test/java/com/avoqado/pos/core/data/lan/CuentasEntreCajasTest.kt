package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.orders.data.CuentasAbiertasAqui
import com.avoqado.pos.payment.domain.TextosDeCuentaSinRed
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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

    /** (op, llave) → (freno, llegó): esa pregunta espera el freno antes de llegar y avisa al llegar (M2, M4). */
    private val retenidas = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Pair<kotlinx.coroutines.CompletableDeferred<Unit>, kotlinx.coroutines.CompletableDeferred<Unit>>>()

    /** El cable de verdad (JSON por `CuentasPorLaRed.contestar`), sin sockets. */
    private val cliente = mockk<LeaseClient> {
        coEvery { preguntarCuenta(any(), any(), any(), any()) } coAnswers {
            val pedido = secondArg<LeaseRequest>()
            val retenida = retenidas[pedido.op to pedido.tableId]
            retenida?.first?.await()
            val caja = cajas[firstArg<LanPeer>().deviceId]
            val r = if (caja == null || !caja.contesta) null
            else LeaseProtocol.decodeResponse(CuentasPorLaRed.contestar(LeaseProtocol.encode(pedido), caja.sucursal, caja.dueno))
                .takeUnless { caja.pierdeRespuestas }
            retenida?.second?.complete(Unit)
            r
        }
    }

    private inner class Caja(val id: String, val nombre: String, val mias: Set<String> = emptySet()) {
        val disco = mutableMapOf<String, PayloadCache.Cached>()
        val filas = mutableMapOf<String, String>()
        var ms = 1_000L
        var contesta = true
        var pierdeRespuestas = false
        /** R4: esta caja tiene dinero de la cuenta que el SERVIDOR aún no tiene (cola, tarjeta en duda, marca). */
        var dinero = false
        /** La sucursal en que está HOY esta caja (contesta con ella). */
        var sucursal = v
        val cache = mockk<PayloadCache> {
            coEvery { loadDurable(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
            coEvery { saveDurable(any(), any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), 0) }
        }
        val dueno = DuenoDeCuentas(cache, mockk<CuentasAbiertasAqui> { coEvery { todas(any()) } returns mias }, { _, _, _ -> dinero }, { _, _ -> false },
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

    private val a = Caja("caja-1", "Caja 1", mias = setOf("o1", "o2"))
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

    // MARK: - Ronda de arreglos 1

    @Test fun `C1 - la EN_VUELO en duda de otra caja no avisa cobrada, y si se descarta suelta`() = runTest {
        red(a, c)
        val pasa = c.entre.puertaSinRed() as CuentasEntreCajas.Puerta.Pasa
        c.filas["llave-c"] = "EN_VUELO"
        c.entre.terminar(v, "o1", pasa, "Beto", cobrado = false, subida = false) // la pantalla se fue en Error con el apartado
        c.entre.unaVuelta()
        assertEquals("en duda: sigue comprometida, nadie la da por cobrada", EstadoDeCuenta.COMPROMETIDA, a.dueno.estadoDe(v, "o1")?.estado)
        c.filas.remove("llave-c") // «Cancelar»
        c.entre.unaVuelta()
        assertNull(a.dueno.estadoDe(v, "o1"))
    }

    @Test fun `C1 - Reintentar con otra llave reemplaza el intento propio en la dueña`() = runTest {
        red(a, c)
        val p = a.entre.puerta(v, "o1", "llave-a", "Yo", enLinea = false, esTarjeta = false, esCuentaDePedidos = true) as CuentasEntreCajas.Puerta.Pasa
        val p2 = a.entre.cambiarLlave(v, "o1", p, "llave-a2")
        a.filas["llave-a2"] = "PENDING" // el efectivo del reintento ya está en la cola
        a.entre.terminar(v, "o1", p2, "Yo", cobrado = false, subida = false)
        assertEquals(EstadoDeCuenta.CERRADA, a.dueno.estadoDe(v, "o1")?.estado)
    }

    @Test fun `C1 - Reintentar con otra llave cambia la llave del aviso en otra caja`() = runTest {
        red(a, c)
        val p = c.entre.puertaSinRed() as CuentasEntreCajas.Puerta.Pasa
        val p2 = c.entre.cambiarLlave(v, "o1", p, "llave-c2")
        c.filas["llave-c2"] = "PENDING"
        c.entre.terminar(v, "o1", p2, "Beto", cobrado = false, subida = false)
        c.entre.unaVuelta()
        assertEquals(EstadoDeCuenta.CERRADA, a.dueno.estadoDe(v, "o1")?.estado)
    }

    @Test fun `I2 - con internet la dueña ve el no confirmó a los 2 min`() = runTest {
        red(a, c)
        c.entre.puertaSinRed()
        a.ms += RegistroDeCuentas.NO_CONFIRMO_MS
        assertEquals(CuentasEntreCajas.Puerta.NoPasa(TextosDeCuentaSinRed.noConfirmo("Beto", "Caja 3")),
            a.entre.puerta(v, "o1", "llave-a", "Yo", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
    }

    @Test fun `I2 - si se pierde la respuesta de comprometer sin conocer a la dueña, la suelta igual se guarda`() = runTest {
        red(a, b, c)
        a.pierdeRespuestas = true
        c.entre.puertaSinRed()
        assertEquals(EstadoDeCuenta.COMPROMETIDA, a.dueno.estadoDe(v, "o1")?.estado) // la dueña sí lo anotó
        a.pierdeRespuestas = false
        c.entre.unaVuelta()
        assertNull(a.dueno.estadoDe(v, "o1"))
    }

    /**
     * Re-revisión r1 (Menor, dinero): el aviso con dueña DESCONOCIDA se borraba en una vuelta si la dueña no se veía y las demás cajas D
     * contestaban «no es mía». Con 3 cajas y la dueña a medias en el WiFi, la «cobrada» real se perdía y a los 2 min alguien podía
     * declarar «No se cobró» y cobrar otra vez. Con dueña vacía sólo lo borra el acuse de la dueña (o la poda de 7 días).
     */
    @Test fun `I2 - el aviso sin dueña conocida no se borra porque las demás digan no es mía y llega cuando vuelve la dueña`() = runTest {
        red(a, b, c)
        a.pierdeRespuestas = true
        assertEquals(CuentasEntreCajas.Puerta.Libre, c.entre.puerta(v, "o1", "llave-c", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
        assertEquals(EstadoDeCuenta.COMPROMETIDA, a.dueno.estadoDe(v, "o1")?.estado) // la dueña sí lo anotó
        a.pierdeRespuestas = false
        c.filas["llave-c"] = "SYNCED" // se cobró con internet
        c.peers.value = listOf(b.peer) // la dueña no se ve en esta vuelta; la tercera contesta «no es mía»
        c.entre.unaVuelta()
        c.peers.value = listOf(a.peer, b.peer) // vuelve la dueña
        c.entre.unaVuelta()
        assertEquals("la dueña recibió la «cobrada» y la cerró", EstadoDeCuenta.CERRADA, a.dueno.estadoDe(v, "o1")?.estado)
        assertFalse(a.dueno.estadoDe(v, "o1")!!.faltaSubirla)
    }

    /** Lo mismo, y cuando después se sabe quién es la dueña (otra pregunta la encontró), el aviso queda a su nombre. */
    @Test fun `I2 - al volver a comprometer el mismo intento con la dueña conocida, el aviso la anota`() = runTest {
        red(a, b, c)
        a.pierdeRespuestas = true
        c.entre.puertaSinRed()
        a.pierdeRespuestas = false
        // R1: el mismo intento vuelve a comprometer; ahora sí contesta la dueña (y ya se sabe cuál es).
        assertTrue(c.entre.puertaSinRed(intentoPrevio = "llave-c") is CuentasEntreCajas.Puerta.Pasa)
        val estado = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(CuentasEntreCajas.Estado.serializer(), c.disco["${PayloadCache.TYPE_CUENTAS_ENTRE_CAJAS}|v1"]!!.json)
        assertEquals("caja-1", estado.avisos.single { it.intento == "llave-c" }.dueno)
    }

    /**
     * Ronda 1 de la tubería, Menor 2 (dinero): la dueña, sostenida por TCP, se cambió de sucursal y conserva su IP y su puerto. Antes
     * contestaba «no es mía» con su sucursal nueva y la «cobrada» se borraba: al regresar podía ofrecer «No se cobró» sobre un cobro real.
     * Ahora el pedido lleva la sucursal; la de otra contesta error (como la red caída) y el aviso sigue hasta que vuelve y acusa.
     */
    @Test fun `la dueña que se cambió de sucursal no borra el aviso cobrada, y al volver lo acusa`() = runTest {
        red(a, c)
        val pasa = c.entre.puertaSinRed() as CuentasEntreCajas.Puerta.Pasa
        a.sucursal = "otra-sucursal"
        c.entre.terminar(v, "o1", pasa, "Beto", cobrado = true, subida = true)
        c.entre.unaVuelta()
        val estado = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(CuentasEntreCajas.Estado.serializer(), c.disco["${PayloadCache.TYPE_CUENTAS_ENTRE_CAJAS}|v1"]!!.json)
        assertTrue("el aviso «cobrada» sigue vivo", estado.avisos.single { it.intento == pasa.intento }.cobradaVista)
        assertEquals(EstadoDeCuenta.COMPROMETIDA, a.dueno.estadoDe(v, "o1")?.estado)
        a.sucursal = v // regresa
        c.entre.unaVuelta()
        assertEquals(EstadoDeCuenta.CERRADA, a.dueno.estadoDe(v, "o1")?.estado)
        assertFalse(a.dueno.estadoDe(v, "o1")!!.faltaSubirla)
    }

    @Test fun `M1 - lo que cerró el servidor dice Ya se cobró, nunca el nombre de una caja`() {
        assertEquals("Ya se cobró", CuentasEntreCajas.textoDe(Respuesta.Cerrada("", false), "Caja 1"))
    }

    @Test fun `M2 - una vuelta lenta no pisa el cobrada que el cobro anotó mientras tanto`() = runTest {
        red(a, c)
        val pasa = c.entre.puertaSinRed() as CuentasEntreCajas.Puerta.Pasa // o1: vivo
        val otra = c.entre.puerta(v, "o2", "llave-o2", "Beto", enLinea = false, esTarjeta = false, esCuentaDePedidos = true) as CuentasEntreCajas.Puerta.Pasa
        val soltarO2 = kotlinx.coroutines.CompletableDeferred<Unit>()
        retenidas[LeaseProtocol.OP_CUENTA_SOLTAR to "cuenta:o2"] = soltarO2 to kotlinx.coroutines.CompletableDeferred()
        // El «cobrada» de o1 se detiene hasta el final: lo que se mira es lo que quedó en el disco de esta caja.
        val cobradaO1 = kotlinx.coroutines.CompletableDeferred<Unit>()
        retenidas[LeaseProtocol.OP_CUENTA_COBRADA to "cuenta:o1"] = cobradaO1 to kotlinx.coroutines.CompletableDeferred()
        c.entre.terminar(v, "o2", otra, "Beto", cobrado = false, subida = false) // o2 sin dinero: su vuelta manda «suelta» (lenta)
        val lenta = launch { c.entre.unaVuelta() }
        runCurrent()
        val cobro = launch { c.entre.terminar(v, "o1", pasa, "Beto", cobrado = true, subida = false) } // o1 sí cobró, mientras tanto
        runCurrent()
        soltarO2.complete(Unit)
        cobro.join()
        cobradaO1.complete(Unit) // con las vueltas en fila, la que sigue manda el «cobrada»
        lenta.join()
        val estado = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(CuentasEntreCajas.Estado.serializer(), c.disco["${PayloadCache.TYPE_CUENTAS_ENTRE_CAJAS}|v1"]!!.json)
        assertTrue("el aviso de o1 sigue diciendo que se cobró", estado.avisos.single { it.orderId == "o1" }.cobradaVista)
    }

    @Test fun `M4 - salir mientras pregunta no deja la cuenta trabada`() = runTest {
        red(a, c)
        c.entre.apartar(v, "o1", "i-c", "Beto", enLinea = false)
        val freno = kotlinx.coroutines.CompletableDeferred<Unit>()
        val llego = kotlinx.coroutines.CompletableDeferred<Unit>()
        retenidas[LeaseProtocol.OP_CUENTA_COMPROMETER to "cuenta:o1"] = freno to llego
        val toque = launch { runCatching { c.entre.puertaSinRed() } }
        runCurrent()
        toque.cancel()
        freno.complete(Unit) // la dueña sí recibe el «comprometer»
        toque.join()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { while (a.dueno.estadoDe(v, "o1")?.estado != EstadoDeCuenta.COMPROMETIDA && !llego.isCompleted) kotlinx.coroutines.delay(10) }
        }
        retenidas.clear()
        c.entre.unaVuelta()
        assertNull(a.dueno.estadoDe(v, "o1"))
    }

    // MARK: - Ninguna renovación huérfana (ronda 1: en aparato, el permiso de MESA se renovaba para siempre)

    private fun sueltaPerdida(orden: String) {
        retenidas[LeaseProtocol.OP_CUENTA_SOLTAR to "cuenta:$orden"] = kotlinx.coroutines.CompletableDeferred<Unit>() to kotlinx.coroutines.CompletableDeferred()
    }

    /** Salir de la pantalla y `onCleared` llaman `soltarApartado`: aunque la suelta se pierda, nadie la renueva y vence a los 30 s. */
    @Test fun `ninguna renovación huérfana - salir sin cobrar con la suelta perdida, la dueña la ve LIBRE a los 30 s`() = runTest {
        red(a, c)
        c.entre.apartar(v, "o1", "i-c", "Beto", enLinea = false)
        sueltaPerdida("o1")
        c.entre.soltarApartado("o1")
        repeat(3) { a.ms += 10_000; c.ms += 10_000; c.entre.unaVuelta() }
        assertNull(a.dueno.estadoDe(v, "o1"))
    }

    /** Red de seguridad: un apartado que nadie compromete en 2 min deja de renovarse y se suelta, aunque su pantalla no avise. */
    @Test fun `ninguna renovación huérfana - un apartado sin comprometer en 2 min deja de renovarse`() = runTest {
        red(a, c)
        c.entre.apartar(v, "o1", "i-c", "Beto", enLinea = false)
        sueltaPerdida("o1")
        repeat(12) { a.ms += 10_000; c.ms += 10_000; c.entre.unaVuelta() } // 2 min tecleando: se renueva
        repeat(4) { a.ms += 10_000; c.ms += 10_000; c.entre.unaVuelta() }
        assertFalse(c.entre.tieneApartado("o1"))
        assertNull(a.dueno.estadoDe(v, "o1"))
    }

    /** La cuenta la cerró el SERVIDOR (u otra caja la tomó) mientras estaba apartada: la renovación recibe «no» y deja de renovar. */
    @Test fun `ninguna renovación huérfana - la cuenta cerrada mientras está apartada deja de renovarse`() = runTest {
        red(a, c)
        c.entre.apartar(v, "o1", "i-c", "Beto", enLinea = false)
        a.dueno.alSaberCerradas(v, listOf("o1"), emptyList())
        c.entre.unaVuelta()
        assertFalse(c.entre.tieneApartado("o1"))
    }

    // MARK: - Arreglo final (revisión final I-1, M-1, M-3)

    private fun avisosDe(caja: Caja) = caja.disco["${PayloadCache.TYPE_CUENTAS_ENTRE_CAJAS}|$v"]
        ?.let { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(CuentasEntreCajas.Estado.serializer(), it.json).avisos }
        .orEmpty()

    /**
     * I-1: con internet, una cuenta que ninguna caja con D abrió (app vieja, iOS, marca podada) se cobra como hoy y NO deja un aviso «sin
     * dueña»: nadie anotó nada. Antes cada uno duraba 7 días y se mandaba en serie a todas las cajas cada 10 s.
     */
    @Test fun `I-1 - con internet una cuenta que ninguna caja abrió se cobra como hoy y no deja aviso`() = runTest {
        red(b, c) // todas contestan «no es mía»
        assertEquals(CuentasEntreCajas.Puerta.Libre, c.entre.puerta(v, "o1", "l1", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
        c.peers.value = emptyList() // ni otra caja con D a quién preguntar
        assertEquals(CuentasEntreCajas.Puerta.Libre, c.entre.puerta(v, "o9", "l2", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
        assertTrue("ningún aviso sin dueña", avisosDe(c).isEmpty())
    }

    /**
     * Codex r3 #1: «en línea» ahora es la red VIVA, así que una pantalla abierta con internet puede preguntar «sin red». Una cuenta que no es
     * de Pedidos (la bandeja de pedidos en línea, un pedido web) sigue sin pasar por D: se cobra como hoy.
     */
    @Test fun `Codex r3 1 - una cuenta que no es de Pedidos no pasa por D aunque se vaya internet`() = runTest {
        red(b, c)
        assertEquals(CuentasEntreCajas.Puerta.Libre, c.entre.puerta(v, "o9", "l1", "Beto", enLinea = false, esTarjeta = false, esCuentaDePedidos = false))
        assertTrue(avisosDe(c).isEmpty())
    }

    /** I-1 sin romper I2: si alguna caja se queda callada (quizá la dueña, con su respuesta perdida), el aviso sí se guarda. */
    @Test fun `I-1 - si alguna caja se queda callada el aviso sin dueña sí se guarda`() = runTest {
        red(a, b, c)
        a.contesta = false
        assertEquals(CuentasEntreCajas.Puerta.Libre, c.entre.puerta(v, "o1", "l1", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
        assertEquals(listOf("" to "l1"), avisosDe(c).map { it.dueno to it.llave })
    }

    /**
     * M-3: el registro de la dueña no se puede leer (regresión de versión, disco dañado). Con red VIVA su propio cobro pasa (el SERVIDOR lo
     * valida en vivo); sin red no; y a otra caja nunca le concede. Lo ilegible no se escribe encima.
     */
    @Test fun `M-3 - con el registro ilegible la dueña cobra lo suyo sólo con red viva y a otras cajas nunca les concede`() = runTest {
        red(a, c)
        val llave = "${PayloadCache.TYPE_REGISTRO_DE_CUENTAS}|$v"
        a.disco[llave] = PayloadCache.Cached("{ilegible", 0)
        assertEquals(CuentasEntreCajas.Puerta.Libre, a.entre.puerta(v, "o1", "llave-a", "Yo", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
        assertEquals(CuentasEntreCajas.Puerta.NoPasa(CuentasEntreCajas.NO_SE_GUARDO),
            a.entre.puerta(v, "o1", "llave-a2", "Yo", enLinea = false, esTarjeta = false, esCuentaDePedidos = true))
        assertEquals(CuentasEntreCajas.Puerta.NoPasa(TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA), c.entre.puertaSinRed())
        assertEquals("{ilegible", a.disco[llave]!!.json)
    }

    /**
     * M-1: la dueña cobra una PARTE (otra parte de un dividido, un saldo de regalo que no alcanza). La cuenta sigue protegida (sin red nadie
     * cobra el resto: «cóbrala con internet»), pero no es «Cobrada en Caja 1»: alguien leería «Cobrada» y no cobraría lo que falta.
     */
    @Test fun `M-1 - un abono parcial de la dueña no dice Cobrada y sin red pide internet`() = runTest {
        red(a, c)
        val p = a.entre.puerta(v, "o1", "llave-a", "Yo", enLinea = true, esTarjeta = false, esCuentaDePedidos = true) as CuentasEntreCajas.Puerta.Pasa
        a.entre.terminar(v, "o1", p, "Yo", cobrado = true, subida = true, conAbono = true)
        val r = a.dueno.estadoDe(v, "o1")
        assertEquals("sigue protegida (H1)", EstadoDeCuenta.CERRADA, r?.estado)
        assertNull("la lista no dice «Cobrada en Caja 1»", com.avoqado.pos.payment.domain.textoDeLaListaDe(r, saldoPendienteCents = null, lecturaDelServidorEn = null))
        assertEquals(CuentasEntreCajas.Puerta.NoPasa(TextosDeCuentaSinRed.CON_ABONO),
            a.entre.puerta(v, "o1", "llave-a2", "Yo", enLinea = false, esTarjeta = false, esCuentaDePedidos = true))
        assertTrue("con internet, el resto", a.entre.puerta(v, "o1", "llave-a3", "Yo", enLinea = true, esTarjeta = false, esCuentaDePedidos = true)
            is CuentasEntreCajas.Puerta.Pasa)
    }

    /** M-1: un saldo de regalo de la dueña que no alcanzó (su marca de cobro no dice «pagada») deja la cuenta protegida, no «Cobrada». */
    @Test fun `M-1 - un saldo de regalo que no alcanzó deja la cuenta protegida pero no Cobrada`() = runTest {
        red(a, c)
        a.dueno.alSaberCerradas(v, emptyList(), listOf("o1"), conAbono = listOf("o1"))
        val r = a.dueno.estadoDe(v, "o1")
        assertEquals(EstadoDeCuenta.CERRADA, r?.estado)
        assertNull(com.avoqado.pos.payment.domain.textoDeLaListaDe(r, saldoPendienteCents = null, lecturaDelServidorEn = null))
    }

    /**
     * M-1 y familia D, en la lista: gana lo más nuevo (Codex r5 #2). Una lectura buena del SERVIDOR posterior a la CERRADA (y con el cobro ya
     * subido) dice con su saldo si queda algo: lo que todavía debe es un abono, venga de la caja que venga. Con la COPIA, o con una lectura
     * del SERVIDOR anterior a la CERRADA, ese saldo es de antes del cobro: manda el registro (`conAbono`).
     */
    @Test fun `M-1 - la lista dice Cobrada según el saldo sólo si la lectura del SERVIDOR es más nueva, y si no según el registro`() {
        val cerrada = CuentaEnElRegistro(EstadoDeCuenta.CERRADA, "caja-2", "Caja 2", "Ana", "i", Instante("b", 0), hora = 1_000)
        fun texto(c: CuentaEnElRegistro, saldo: Int?, lecturaEn: Long?) = com.avoqado.pos.payment.domain.textoDeLaListaDe(c, saldo, lecturaEn)
        assertEquals("Cobrada en Caja 2", texto(cerrada, 0, lecturaEn = 2_000))
        assertNull("ya subió y el SERVIDOR, después, dice que debe: un abono", texto(cerrada, 2000, lecturaEn = 2_000))
        assertEquals("la lectura es de ANTES de la cobrada: manda el registro", "Cobrada en Caja 2", texto(cerrada, 2000, lecturaEn = 500))
        assertEquals("con la copia el saldo es de antes del cobro: manda el registro", "Cobrada en Caja 2", texto(cerrada, 2000, lecturaEn = null))
        assertNull("un abono que el registro sabe, con la copia", texto(cerrada.copy(conAbono = true), 2000, lecturaEn = null))
        assertEquals("aún no sube: el saldo del SERVIDOR no lo sabe", "Cobrada en Caja 2 · falta subirla",
            texto(cerrada.copy(faltaSubirla = true), 2000, lecturaEn = 2_000))
        assertNull(texto(cerrada.copy(estado = EstadoDeCuenta.COMPROMETIDA), 0, lecturaEn = 2_000))
    }

    /** Cambio de sucursal: los apartados de la anterior se olvidan (y nada los renueva). */
    @Test fun `ninguna renovación huérfana - cambiar de sucursal olvida los apartados`() = runTest {
        red(a, c)
        c.entre.apartar(v, "o1", "i-c", "Beto", enLinea = false)
        c.entre.encender("otra-sucursal")
        assertFalse(c.entre.tieneApartado("o1"))
    }

    // MARK: - Pasada por familia (Codex r4 y re-revisión del arreglo final)

    /**
     * Familia D · caso (3) de `opcion-d.md`: A (la dueña) sin internet; Caja 2, con internet, la cobra con su permiso y la sube. Sin red la
     * lista de A viene de la copia, guardada ANTES de ese cobro (su saldo es viejo): manda el registro y dice «Cobrada en Caja 2».
     */
    @Test fun `familia D - caso 3 - sin red la lista de la dueña dice Cobrada en Caja 2`() = runTest {
        red(a, b)
        val p = b.entre.puerta(v, "o1", "llave-b", "Ana", enLinea = true, esTarjeta = false, esCuentaDePedidos = true) as CuentasEntreCajas.Puerta.Pasa
        b.entre.terminar(v, "o1", p, "Ana", cobrado = true, subida = true)
        b.entre.unaVuelta() // el «cobrada» (ya subida) llega a la dueña
        val r = a.dueno.estadoDe(v, "o1")
        assertEquals(EstadoDeCuenta.CERRADA, r?.estado)
        assertFalse("ya subió", r!!.faltaSubirla)
        assertEquals("Cobrada en Caja 2", com.avoqado.pos.payment.domain.textoDeLaListaDe(r, saldoPendienteCents = 2000, lecturaDelServidorEn = null))
    }

    /** M-3 (la prueba que faltaba): con el registro ilegible y dinero de esta caja que aún no sube (R4), ni con red viva se cobra otra vez. */
    @Test fun `M-3 - registro ilegible con dinero propio sin subir - ni con red viva se cobra`() = runTest {
        red(a, c)
        a.disco["${PayloadCache.TYPE_REGISTRO_DE_CUENTAS}|$v"] = PayloadCache.Cached("{ilegible", 0)
        a.dinero = true
        assertEquals(CuentasEntreCajas.Puerta.NoPasa("Cobrada en Caja 1 · falta subirla"),
            a.entre.puerta(v, "o1", "llave-a", "Yo", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
    }

    /**
     * Familia C: «en línea» se confirma en la puerta, y la salud del SERVIDOR sólo se pregunta cuando cambia la respuesta: una CERRADA (con
     * internet se cobra el resto con el saldo vivo). Una cuenta libre, la tarjeta, la copia y lo que no es de Pedidos no la preguntan.
     */
    @Test fun `familia C - la salud sólo se pregunta cuando cambia la respuesta`() = runTest {
        red(a, c)
        var preguntas = 0
        var contesta = true
        listOf(a, c).forEach { it.entre.servidorContesta = { preguntas++; contesta } }
        val p = c.entre.puerta(v, "o1", "llave-c", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = true) as CuentasEntreCajas.Puerta.Pasa
        c.entre.terminar(v, "o1", p, "Beto", cobrado = true, subida = true)
        c.entre.unaVuelta()
        assertEquals("una cuenta libre no pregunta", 0, preguntas)

        assertTrue("la dueña, con red de verdad, cobra el resto",
            a.entre.puerta(v, "o1", "llave-a", "Yo", enLinea = true, esTarjeta = false, esCuentaDePedidos = true) is CuentasEntreCajas.Puerta.Pasa)
        assertEquals(1, preguntas)
        contesta = false
        assertEquals("sin respuesta del SERVIDOR, otra caja no", CuentasEntreCajas.Puerta.NoPasa("Cobrada en Caja 3"),
            c.entre.puerta(v, "o1", "llave-c2", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
        assertEquals(2, preguntas)

        assertTrue("la tarjeta no se mide", a.entre.puerta(v, "o1", "llave-a2", "Yo", enLinea = true, esTarjeta = true, esCuentaDePedidos = true)
            is CuentasEntreCajas.Puerta.Pasa)
        assertEquals(CuentasEntreCajas.Puerta.NoPasa("Cobrada en Caja 3"), c.entre.puertaSinRed(llave = "llave-c3"))
        assertEquals(CuentasEntreCajas.Puerta.Libre,
            c.entre.puerta(v, "o9", "llave-c4", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = false))
        assertEquals("ni la tarjeta, ni la copia, ni lo que no es de Pedidos", 2, preguntas)
    }

    /** Familia C: una caja sin hub que no es la dueña (cuenta de Pedidos) y la dueña que no contesta (regla 8): ahí sí cambia la respuesta. */
    @Test fun `familia C - sin hub o sin respuesta de la dueña la salud decide`() = runTest {
        red(a, c)
        var contesta = false
        c.entre.servidorContesta = { contesta }
        a.contesta = false
        assertEquals(CuentasEntreCajas.Puerta.NoPasa(TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA),
            c.entre.puerta(v, "o1", "l1", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
        contesta = true
        assertEquals(CuentasEntreCajas.Puerta.Libre, c.entre.puerta(v, "o1", "l2", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
        c.entre.apagar() // sin hub
        contesta = false
        assertEquals(CuentasEntreCajas.Puerta.NoPasa(TextosDeCuentaSinRed.SE_ABRIO_EN_OTRA_CAJA),
            c.entre.puerta(v, "o1", "l3", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
        contesta = true
        assertEquals(CuentasEntreCajas.Puerta.Libre, c.entre.puerta(v, "o1", "l4", "Beto", enLinea = true, esTarjeta = false, esCuentaDePedidos = true))
    }

    // MARK: - W2 y la dueña (re-revisión de la pasada por familia)

    /** W2: un abono PARCIAL en Caja 2 con internet viaja con su «cobrada»: la dueña sin red no dice «Cobrada en Caja 2». */
    @Test fun `W2 - el abono parcial de otra caja viaja y la dueña sin red no dice Cobrada`() = runTest {
        red(a, b)
        val p = b.entre.puerta(v, "o1", "llave-b", "Ana", enLinea = true, esTarjeta = false, esCuentaDePedidos = true) as CuentasEntreCajas.Puerta.Pasa
        b.entre.terminar(v, "o1", p, "Ana", cobrado = true, subida = true, conAbono = true)
        b.entre.unaVuelta()
        val r = a.dueno.estadoDe(v, "o1")
        assertEquals(EstadoDeCuenta.CERRADA, r?.estado)
        assertTrue("con abono", r!!.conAbono)
        assertNull(com.avoqado.pos.payment.domain.textoDeLaListaDe(r, saldoPendienteCents = 2000, lecturaDelServidorEn = null))
        assertEquals(CuentasEntreCajas.Puerta.NoPasa(TextosDeCuentaSinRed.CON_ABONO),
            a.entre.puerta(v, "o1", "llave-a", "Yo", enLinea = false, esTarjeta = false, esCuentaDePedidos = true))
    }

    /** W2: el aviso que se rearma sin saber (el flujo se fue; su fila dice que sí hubo dinero) manda «no se sabe», que cuenta como abono. */
    @Test fun `W2 - un aviso rearmado desde la fila manda no se sabe y cuenta como abono`() = runTest {
        red(a, b)
        val p = b.entre.puerta(v, "o1", "llave-b", "Ana", enLinea = true, esTarjeta = false, esCuentaDePedidos = true) as CuentasEntreCajas.Puerta.Pasa
        b.filas["llave-b"] = "SYNCED"
        b.entre.terminar(v, "o1", p, "Ana", cobrado = false, subida = false)
        b.entre.unaVuelta()
        val r = a.dueno.estadoDe(v, "o1")
        assertEquals(EstadoDeCuenta.CERRADA, r?.estado)
        assertTrue("no se sabe si quedó saldo: no es «Cobrada»", r!!.conAbono)
    }

    /**
     * Regresión de la dueña (Menor 2 de la re-revisión): una cuenta «abierta aquí» nunca es de la bandeja ni de Rappi 2b. Con internet y
     * su cuenta fuera de la copia (más de 24 h, nunca listada, o la copia ilegible), la dueña cobra el resto de su cuenta ya cobrada y subida.
     */
    @Test fun `dueña - con internet y su cuenta fuera de la copia cobra el resto`() = runTest {
        red(a, c)
        a.dueno.cobrada(v, "o1", Solicitud("caja-1", "Caja 1", "Yo", "llave-0"), subida = true, conAbono = true)
        assertTrue(a.entre.puerta(v, "o1", "llave-a", "Yo", enLinea = true, esTarjeta = false, esCuentaDePedidos = false)
            is CuentasEntreCajas.Puerta.Pasa)
    }
}

package com.avoqado.pos.cashdrawer

import com.avoqado.pos.cashdrawer.data.AliasDeCaja
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.PendingCashSales
import com.avoqado.pos.cashdrawer.data.PendingDrawerOp
import com.avoqado.pos.cashdrawer.data.ESPACIO_ENTRE_PASES_MS
import com.avoqado.pos.cashdrawer.data.PASES_DEL_CIERRE
import com.avoqado.pos.cashdrawer.data.PasoDelCierre
import com.avoqado.pos.cashdrawer.data.pasoDelCierre
import com.avoqado.pos.cashdrawer.data.TOPE_DE_LA_BARRERA_MS
import com.avoqado.pos.cashdrawer.data.cajasEsperandoApertura
import com.avoqado.pos.cashdrawer.data.elCierreEspera
import com.avoqado.pos.cashdrawer.data.textoDelCierreQueEspera
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNull
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 UN MOVIMIENTO RECHAZADO TIENE QUE SEGUIR BLOQUEANDO EL CIERRE EN LA SIGUIENTE PASADA.
 *
 * El cierre es una BARRERA: si un retiro de esa caja no llegó al servidor, mandar el cierre encima
 * hace que el servidor firme un faltante falso — le faltan $50 que sí salieron del cajón.
 *
 * Un movimiento REINTENTABLE bloquea de forma natural: sigue en la cola y el bucle lo ve. Uno
 * RECHAZADO no, y ahí estuvo el defecto: para no reintentarlo se filtraba de la lista ANTES del
 * bucle, así que también dejaba de sembrar la barrera. En la MISMA corrida funcionaba (el rechazo
 * acababa de ocurrir); en la SEGUNDA —tras reabrir la app o volver a entrar a Caja— el cierre ya
 * no encontraba quién lo detuviera y se mandaba solo. El daño llega tarde y sin nadie mirando,
 * que es la peor forma. (Lo encontraron a la vez esta sesión y la 4ª auditoría de Codex.)
 *
 * Estas pruebas simulan la SEGUNDA pasada: la cola ya trae el rechazo guardado de antes.
 */
class CashDrawerBarreraDelCierreTest {

    /** Un `SecureStorage` que de verdad recuerda la cola entre llamadas (el de fixtures no guarda). */
    private fun almacenConCola(colaInicial: String) = mockk<SecureStorage>(relaxed = true).also { st ->
        var cola: String? = colaInicial
        every { st.venueId } returns VENUE_ID
        every { st.pendingDrawerOpsJson(any()) } answers { cola }
        every { st.setPendingDrawerOpsJson(any(), any()) } answers { cola = secondArg() }
    }

    private fun colaCon(vararg filas: String) = "[" + filas.joinToString(",") + "]"

    private fun retiroRechazado(sessionId: String, cents: Int, localId: String) =
        """{"kind":"PAY_OUT","sessionId":"$sessionId","amountCents":$cents,"localId":"$localId","at":1,"rechazadaEn":99,"motivoDelRechazo":"Monto inválido"}"""

    private fun cierre(sessionId: String, cents: Int) =
        """{"kind":"CLOSE","sessionId":"$sessionId","amountCents":$cents,"at":2}"""

    /** Ninguna ruta configurada ⇒ toda llamada revienta como "sin red", pero queda capturada. */
    private fun repoCon(cola: String, capturadas: MutableList<LlamadaCapturada>) =
        com.avoqado.pos.cashdrawer.data.CashDrawerRepository(
            dao = FakeCashDrawerDao(),
            secureStorage = almacenConCola(cola),
            client = cashDrawerClient(capturadas = capturadas),
            pendingCashSales = sinCobrosEnCola(),
            conectividad = conectividadDePrueba(),
        )

    @Test
    fun `el cierre NO se manda si esa caja tiene un retiro rechazado de una corrida anterior`() = runTest {
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repoCon(colaCon(retiroRechazado("srv-1", 5000, "loc-a"), cierre("srv-1", 25000)), llamadas)

        repo.reproducirPendientes()

        assertFalse(
            "el cierre se mandó pese al retiro rechazado — el server firmaría un faltante falso de \$50: $llamadas",
            llamadas.any { it.path.endsWith("/close") },
        )
    }

    /** Un rechazo de OTRA caja no puede detener el cierre de ésta. */
    @Test
    fun `un rechazo de otra caja no bloquea este cierre`() = runTest {
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repoCon(colaCon(retiroRechazado("srv-OTRA", 5000, "loc-b"), cierre("srv-1", 25000)), llamadas)

        repo.reproducirPendientes()

        assertTrue("el cierre de srv-1 debía intentarse: $llamadas", llamadas.any { it.path.endsWith("/close") })
    }

    /** Y el aviso tiene que seguir ahí para que alguien pueda resolverlo. */
    @Test
    fun `el rechazo guardado se sigue viendo`() {
        val repo = repoCon(colaCon(retiroRechazado("srv-1", 5000, "loc-a")), mutableListOf())
        val avisos = repo.operacionesRechazadas()

        assertEquals(1, avisos.size)
        assertEquals(5000, avisos[0].amountCents)
        assertEquals("PAY_OUT", avisos[0].kind)
        assertEquals("Monto inválido", avisos[0].motivo)
    }

    /**
     * 🔴 Dos retiros del MISMO monto y la MISMA caja son avisos DISTINTOS.
     *
     * Con la identidad por monto, tocar "Ya lo vi" en uno borraba los dos, y el segundo
     * desaparecía sin que nadie lo hubiera reconocido — dinero fuera del cajón que ya no
     * aparece por ningún lado.
     */
    @Test
    fun `dos retiros iguales de la misma caja son avisos distintos`() {
        val repo = repoCon(
            colaCon(retiroRechazado("srv-1", 5000, "loc-a"), retiroRechazado("srv-1", 5000, "loc-b")),
            mutableListOf(),
        )

        val avisos = repo.operacionesRechazadas()
        assertEquals("los dos retiros deben verse", 2, avisos.size)
        assertEquals("y con identidades distintas", 2, avisos.map { it.localKey }.toSet().size)

        repo.descartarRechazada(avisos[0].localKey)
        assertEquals("descartar uno no puede llevarse el otro", 1, repo.operacionesRechazadas().size)
    }

    // MARK: - Barrera 2 (efectivo sin red en el turno correcto, 21-oct-2026): el cierre espera a SUS cobros

    /** Un almacén que recuerda la cola y los alias entre corridas y entre instancias (la app que se reinicia). */
    private class AlmacenDeLaCaja(colaInicial: String, aliasInicial: List<AliasDeCaja> = emptyList()) {
        var cola: String? = colaInicial
        var alias: String? = Json.encodeToString(ListSerializer(AliasDeCaja.serializer()), aliasInicial)
        val st: SecureStorage = mockk<SecureStorage>(relaxed = true).also { st ->
            every { st.venueId } returns VENUE_ID
            every { st.pendingDrawerOpsJson(any()) } answers { cola }
            every { st.setPendingDrawerOpsJson(any(), any()) } answers { cola = secondArg() }
            every { st.drawerSessionAliasesJson(any()) } answers { alias }
            every { st.setDrawerSessionAliasesJson(any(), any()) } answers { alias = secondArg() }
        }
        fun cierre(): PendingDrawerOp? = cola
            ?.let { Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(PendingDrawerOp.serializer()), it) }
            ?.firstOrNull { it.kind == "CLOSE" }
    }

    private fun repoDeLaCaja(almacen: AlmacenDeLaCaja, llamadas: MutableList<LlamadaCapturada>, cobros: PendingCashSales, hayRed: Boolean = true) =
        CashDrawerRepository(
            dao = FakeCashDrawerDao(),
            secureStorage = almacen.st,
            client = cashDrawerClient(capturadas = llamadas),
            pendingCashSales = cobros,
            conectividad = conectividadDePrueba(hayRed),
        )

    private fun cobroDeLaCaja(id: String, caja: String?, estado: String = PaymentSyncStatus.PENDING.name) =
        cobroEncolado(id, 10_000, syncStatus = estado).copy(cashDrawerSessionId = caja)

    private fun seMandoElCierre(llamadas: List<LlamadaCapturada>) = llamadas.any { it.path.endsWith("/close") }

    @Test
    fun `P1 con red el cierre de la caja espera a que suban sus cobros y la banda lo dice`() = runTest {
        val almacen = AlmacenDeLaCaja(colaCon(cierre("srv-1", 25000)))
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repoDeLaCaja(almacen, llamadas, colaDeCobros(cobros = listOf(cobroDeLaCaja("p1", "srv-1"), cobroDeLaCaja("p2", "srv-1"))))

        repo.reproducirPendientes()

        assertFalse("el cierre salió antes que sus ventas: $llamadas", seMandoElCierre(llamadas))
        assertEquals("retenerlo no cuenta un pase: lo cuenta el sincronizador", 0, almacen.cierre()?.pasesConServidor)
        assertEquals("el sincronizador sabe a qué cierre contarle el pase", "srv-1", repo.cierreQueEspera()?.sessionId)
        assertEquals(2, repo.cierreEsperaVentas.value)
        assertEquals("El cierre de caja espera a que suban 2 ventas", textoDelCierreQueEspera(repo.cierreEsperaVentas.value))
    }

    @Test
    fun `P1 un cobro FAILED de la caja no detiene su cierre`() = runTest {
        val almacen = AlmacenDeLaCaja(colaCon(cierre("srv-1", 25000)))
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repoDeLaCaja(almacen, llamadas, colaDeCobros(cobros = listOf(cobroDeLaCaja("p1", "srv-1", PaymentSyncStatus.FAILED.name))))

        repo.reproducirPendientes()

        assertTrue("un rechazo definitivo no puede detener el cierre: $llamadas", seMandoElCierre(llamadas))
        assertEquals(0, repo.cierreEsperaVentas.value)
    }

    @Test
    fun `P1 un cobro sin caja o de otra caja no detiene el cierre`() = runTest {
        val almacen = AlmacenDeLaCaja(colaCon(cierre("srv-1", 25000)))
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repoDeLaCaja(almacen, llamadas, colaDeCobros(cobros = listOf(cobroDeLaCaja("viejo", null), cobroDeLaCaja("otra", "srv-OTRA"))))

        repo.reproducirPendientes()

        assertTrue("una fila vieja sin caja no bloquea ningún cierre: $llamadas", seMandoElCierre(llamadas))
    }

    @Test
    fun `P1 el cobro guardado con el id LOCAL de la caja tambien detiene su cierre, traducido por el alias`() = runTest {
        val almacen = AlmacenDeLaCaja(colaCon(cierre("srv-1", 25000)), listOf(AliasDeCaja("caja-local-1", "srv-1", en = 1)))
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repoDeLaCaja(almacen, llamadas, colaDeCobros(cobros = listOf(cobroDeLaCaja("p1", "caja-local-1"))))

        repo.reproducirPendientes()

        assertFalse(seMandoElCierre(llamadas))
        assertEquals(1, repo.cierreEsperaVentas.value)
    }

    /**
     * 🔴 Codex apps r1–r6 (24-oct): el tope ya no es tiempo de reloj. Ni horas de reloj ni cien revisiones lo vencen; tres peticiones
     * seguidas en el mismo minuto tampoco: el sincronizador PIDE el pase y lo cuenta la siguiente corrida que retiene el cierre, con 5 min
     * de reloj monotónico entre uno y otro (la primera arma).
     */
    @Test
    fun `P1 el reloj, las revisiones y los intentos seguidos no vencen el tope`() = runTest {
        val almacen = AlmacenDeLaCaja(colaCon(cierre("srv-1", 25000)))
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repoDeLaCaja(almacen, llamadas, colaDeCobros(cobros = listOf(cobroDeLaCaja("p1", "srv-1"))))
        var ahora = 1_000L
        var monotonico = 0L
        repo.reloj = { ahora }
        repo.relojMonotonico = { monotonico }
        suspend fun intento() { repo.solicitarPaseConServidor(listOfNotNull(repo.cierreQueEspera())); repo.reproducirPendientes() }

        for (i in 1..100) { ahora += 60 * 60_000L; repo.reproducirPendientes() }
        assertFalse("el tiempo venció el tope: $llamadas", seMandoElCierre(llamadas))

        intento()
        intento(); intento(); intento()
        assertEquals("la primera arma y las seguidas no cuentan", 0, almacen.cierre()?.pasesConServidor)

        for (k in 1..2) { monotonico += ESPACIO_ENTRE_PASES_MS; intento() }
        assertFalse("con 2 pases todavía espera: $llamadas", seMandoElCierre(llamadas))
        assertEquals(2, almacen.cierre()?.pasesConServidor)

        monotonico += ESPACIO_ENTRE_PASES_MS
        intento()
        assertTrue("al tercer pase con el servidor el cierre sale igual, en la misma corrida que lo cuenta: $llamadas", seMandoElCierre(llamadas))
        assertEquals(0, repo.cierreEsperaVentas.value)
        assertNull("ya no queda cierre retenido", repo.cierreQueEspera())
    }

    /** Los pases van en disco con la cola; tras un reinicio el primer intento vuelve a armar (un reinicio retrasa, nunca adelanta). */
    @Test
    fun `P1 los pases sobreviven al reinicio y el reinicio no adelanta el tope`() = runTest {
        val almacen = AlmacenDeLaCaja(colaCon(cierre("srv-1", 25000)))
        val cobros = colaDeCobros(cobros = listOf(cobroDeLaCaja("p1", "srv-1")))
        var monotonico = 0L
        val antes = repoDeLaCaja(almacen, mutableListOf(), cobros).apply { relojMonotonico = { monotonico } }
        antes.reproducirPendientes()
        for (k in 0..2) {
            antes.solicitarPaseConServidor(listOfNotNull(antes.cierreQueEspera())); antes.reproducirPendientes()
            monotonico += ESPACIO_ENTRE_PASES_MS
        }
        assertEquals(2, almacen.cierre()?.pasesConServidor)

        val llamadas = mutableListOf<LlamadaCapturada>()
        val reiniciada = repoDeLaCaja(almacen, llamadas, cobros).apply { relojMonotonico = { monotonico } }
        reiniciada.reproducirPendientes()
        reiniciada.solicitarPaseConServidor(listOfNotNull(reiniciada.cierreQueEspera()))
        reiniciada.reproducirPendientes()
        assertFalse("tras el reinicio el primer intento sólo arma: $llamadas", seMandoElCierre(llamadas))
        assertEquals(2, almacen.cierre()?.pasesConServidor)
        monotonico += ESPACIO_ENTRE_PASES_MS
        reiniciada.solicitarPaseConServidor(listOfNotNull(reiniciada.cierreQueEspera()))
        reiniciada.reproducirPendientes()
        assertTrue(seMandoElCierre(llamadas))
    }

    /** Un pase por corrida aunque el mismo cierre se pida repetido, y nunca a otro cierre: la petición de uno ajeno se descarta. */
    @Test
    fun `P1 pedir un pase suma uno sólo al cierre que se le da`() = runTest {
        val almacen = AlmacenDeLaCaja(colaCon(cierre("srv-1", 25000)))
        val repo = repoDeLaCaja(almacen, mutableListOf(), colaDeCobros(cobros = listOf(cobroDeLaCaja("p1", "srv-1"))))
        var monotonico = 0L
        repo.relojMonotonico = { monotonico }
        repo.reproducirPendientes()
        val retenido = requireNotNull(repo.cierreQueEspera())
        repo.solicitarPaseConServidor(listOf(retenido)); repo.reproducirPendientes()
        monotonico += ESPACIO_ENTRE_PASES_MS

        repo.solicitarPaseConServidor(listOf(retenido, retenido, retenido)); repo.reproducirPendientes()
        assertEquals(1, almacen.cierre()?.pasesConServidor)
        monotonico += ESPACIO_ENTRE_PASES_MS
        repo.solicitarPaseConServidor(listOf(PendingDrawerOp("CLOSE", "otra-caja", 100, localId = "otro-cierre", at = 9))); repo.reproducirPendientes()
        assertEquals("un cierre ajeno no le suma a éste", 1, almacen.cierre()?.pasesConServidor)
        repo.solicitarPaseConServidor(emptyList()); repo.reproducirPendientes()
        assertEquals(1, almacen.cierre()?.pasesConServidor)
    }

    /**
     * 🔴 Codex apps r11: el cierre A estaba retenido y se pidió su pase; luego el cajero REINTENTA una apertura vieja rechazada (B), que
     * revive DELANTE de A. La corrida siguiente se detiene en B y no llega a A: la petición se descarta y A no gana el pase con algo vivo
     * delante (si lo ganara, al soltarse B saldría antes que sus ventas).
     */
    @Test
    fun `P1 la peticion de pase de un cierre con algo vivo delante se descarta`() = runTest {
        fun apertura(rechazada: Boolean) =
            """{"kind":"OPEN","sessionId":"caja-B","amountCents":10000,"localId":"abrir-B","at":1${if (rechazada) ""","rechazadaEn":5,"motivoDelRechazo":"No"""" else ""}}"""
        val cierreA = """{"kind":"CLOSE","sessionId":"srv-1","amountCents":25000,"localId":"cerrar-A","at":2}"""
        val almacen = AlmacenDeLaCaja(colaCon(apertura(rechazada = true), cierreA))
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repoDeLaCaja(almacen, llamadas, colaDeCobros(cobros = listOf(cobroDeLaCaja("p1", "srv-1"))))
        var monotonico = 0L
        repo.relojMonotonico = { monotonico }
        repo.reproducirPendientes()
        assertEquals("con B rechazada, A queda retenido por sus ventas", "srv-1", repo.cierreQueEspera()?.sessionId)
        repo.solicitarPaseConServidor(listOfNotNull(repo.cierreQueEspera())); repo.reproducirPendientes()   // arma
        monotonico += ESPACIO_ENTRE_PASES_MS
        repo.solicitarPaseConServidor(listOfNotNull(repo.cierreQueEspera()))

        // El cajero reintenta B: revive delante de A (sin red para mandarla: la corrida se detiene en ella).
        almacen.cola = colaCon(apertura(rechazada = false), cierreA.replace("}", ""","pasesConServidor":0}"""))
        repo.reproducirPendientes()
        assertNull("la corrida no llegó a A", repo.cierreQueEspera())
        assertEquals(0, almacen.cierre()?.pasesConServidor)

        // B se resuelve (sale de la cola): la petición vieja ya no está; A no gana el pase sin evidencia nueva.
        almacen.cola = colaCon(cierreA)
        repo.reproducirPendientes()
        assertEquals("srv-1", repo.cierreQueEspera()?.sessionId)
        assertEquals("la petición vieja se descartó", 0, almacen.cierre()?.pasesConServidor)
        assertFalse(seMandoElCierre(llamadas))
    }

    /**
     * 🔴 Codex apps r12: «Reintentar» una apertura rechazada espera su turno con el candado del replay. Si la reviviera a media corrida,
     * la corrida seguiría con su foto de la cola y podría mandar lo de detrás (un cierre con su último pase) antes que ella.
     */
    @Test
    fun `reintentar una apertura espera a que termine la corrida del replay en curso`() = kotlinx.coroutines.runBlocking {
        val llegoAlServidor = java.util.concurrent.CountDownLatch(1)
        val soltar = java.util.concurrent.CountDownLatch(1)
        val client = mockk<okhttp3.OkHttpClient> {
            every { newCall(any()) } answers {
                mockk<okhttp3.Call> {
                    every { execute() } answers {
                        llegoAlServidor.countDown()
                        soltar.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        throw java.io.IOException("sin red")
                    }
                }
            }
        }
        val rechazadaB = """{"kind":"OPEN","sessionId":"caja-B","amountCents":10000,"localId":"abrir-B","at":1,"rechazadaEn":5,"motivoDelRechazo":"No"}"""
        val vivaC = """{"kind":"OPEN","sessionId":"caja-C","amountCents":10000,"localId":"abrir-C","at":2}"""
        val almacen = AlmacenDeLaCaja(colaCon(rechazadaB, vivaC))
        val repo = CashDrawerRepository(
            dao = FakeCashDrawerDao(), secureStorage = almacen.st, client = client,
            pendingCashSales = colaDeCobros(cobros = emptyList()), conectividad = conectividadDePrueba(true),
        )
        val llaveDeB = repo.operacionesRechazadas().single().localKey
        fun bSigueRechazada() = almacen.cola.orEmpty().contains("\"rechazadaEn\":5")

        val corrida = async(kotlinx.coroutines.Dispatchers.Default) { repo.reproducirPendientes() }
        assertTrue("la corrida no llegó a mandar C", llegoAlServidor.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val reintento = async(kotlinx.coroutines.Dispatchers.Default) { repo.reintentarApertura(llaveDeB) }
        kotlinx.coroutines.delay(300)
        assertFalse("reintentar no esperó a la corrida en curso", reintento.isCompleted)
        assertTrue("B revivió a media corrida", bSigueRechazada())

        soltar.countDown()
        corrida.await(); reintento.await()
        assertFalse("al terminar la corrida, B revive", bSigueRechazada())
    }

    @Test
    fun `el espacio entre pases, suelto`() {
        assertEquals(PasoDelCierre(cuenta = false, intentoEn = 100), pasoDelCierre(null, 100))
        assertEquals("a menos del espacio no cuenta ni mueve la cuenta", PasoDelCierre(false, 100), pasoDelCierre(100, 100 + ESPACIO_ENTRE_PASES_MS - 1))
        assertEquals(PasoDelCierre(true, 100 + ESPACIO_ENTRE_PASES_MS), pasoDelCierre(100, 100 + ESPACIO_ENTRE_PASES_MS))
        assertEquals("un reloj que retrocede (otro arranque) vuelve a armar", PasoDelCierre(false, 5), pasoDelCierre(100, 5))
        assertEquals(5L * 60 * 1000, ESPACIO_ENTRE_PASES_MS)
    }

    /**
     * 🔴 Sólo cuenta un cierre que la barrera 2 retuvo DE VERDAD. Uno que espera por otra razón (aquí, un retiro rechazado de su
     * caja) no ha empezado a esperar a sus ventas: si acumulara pases, al resolverse saldría de inmediato, antes que ellas.
     */
    @Test
    fun `P1 un cierre detenido por otra razon no queda como retenido por sus ventas`() = runTest {
        val almacen = AlmacenDeLaCaja(colaCon(retiroRechazado("srv-1", 5000, "loc-a"), cierre("srv-1", 25000)))
        val repo = repoDeLaCaja(almacen, mutableListOf(), colaDeCobros(cobros = listOf(cobroDeLaCaja("p1", "srv-1"))))

        repo.reproducirPendientes()

        assertNull(repo.cierreQueEspera())
    }

    @Test
    fun `P1 sin red el cierre tampoco sale solo`() = runTest {
        val almacen = AlmacenDeLaCaja(colaCon(cierre("srv-1", 25000)))
        val llamadas = mutableListOf<LlamadaCapturada>()
        val repo = repoDeLaCaja(almacen, llamadas, colaDeCobros(cobros = listOf(cobroDeLaCaja("p1", "srv-1"))), hayRed = false)
        var ahora = 1_000L
        repo.reloj = { ahora }

        repo.reproducirPendientes()
        ahora += 10 * TOPE_DE_LA_BARRERA_MS
        repo.reproducirPendientes()

        assertFalse("esperar sin red no es esperar al servidor: $llamadas", seMandoElCierre(llamadas))
        assertEquals(0, almacen.cierre()?.pasesConServidor)
    }

    @Test
    fun `la espera del cierre, suelta`() {
        assertFalse("sin cobros por subir no espera", elCierreEspera(pasesConServidor = 0, cobrosPorSubir = 0))
        assertTrue(elCierreEspera(pasesConServidor = 0, cobrosPorSubir = 2))
        assertTrue(elCierreEspera(pasesConServidor = PASES_DEL_CIERRE - 1, cobrosPorSubir = 2))
        assertFalse("al tope sale aunque falten ventas", elCierreEspera(pasesConServidor = PASES_DEL_CIERRE, cobrosPorSubir = 2))
        assertEquals(3, PASES_DEL_CIERRE)
    }

    @Test
    fun `las cajas que esperan su apertura, sueltas`() {
        val ahora = 10 * TOPE_DE_LA_BARRERA_MS
        val cola = listOf(
            PendingDrawerOp("OPEN", "sin-intentar", 100, localId = "a", at = 1),
            PendingDrawerOp("OPEN", "dentro-del-tope", 100, localId = "b", at = 2, primerReintentoEn = ahora - 1_000),
            PendingDrawerOp("OPEN", "vencida", 100, localId = "c", at = 3, primerReintentoEn = ahora - TOPE_DE_LA_BARRERA_MS),
            PendingDrawerOp("OPEN", "rechazada", 100, localId = "d", at = 4, rechazadaEn = 5),
            PendingDrawerOp("CLOSE", "un-cierre", 100, at = 5),
        )
        assertEquals(setOf("sin-intentar", "dentro-del-tope"), cajasEsperandoApertura(cola, ahora))
        assertNull(textoDelCierreQueEspera(0))
        assertEquals("El cierre de caja espera a que suban 3 ventas", textoDelCierreQueEspera(3))
        assertEquals("El cierre de caja espera a que suba 1 venta", textoDelCierreQueEspera(1))
    }
}

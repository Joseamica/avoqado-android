package com.avoqado.pos.escritorio.base

import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.pos.cashdrawer.data.CashDrawerDao
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.PendingCashSales
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.util.ConnectivityMonitor
import okhttp3.ResponseBody.Companion.toResponseBody
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.escritorio.Inyector
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.SalidaDelApartado
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 🔴 D2, rediseño r9 (24-oct-2026), con la base REAL de WINDOWS (Room + SQLite, el gráfico de Guice de la app): la venta en efectivo
 * de un cobro CON apartado entra al cajón del aparato una vez, en la MISMA escritura que saca su fila de `EN_VUELO`, sin depender
 * de quién la llame (antes la anotaba la cola desde afuera, después de pasarla). Estas pruebas sólo usan la API que ya existía;
 * las que necesitan la pieza nueva o el 2xx están en [SalidaDelApartadoRoomTest].
 */
class VentaDelApartadoRoomTest {
    private val carpeta = Files.createTempDirectory("fulltest-venta-del-apartado")
    private val inyector = Inyector.crear(ActividadDeEscritorio(carpeta))
    private val storage = inyector.getInstance(SecureStorage::class.java).apply {
        venueId = VENUE; userId = "staff-1"; userFirstName = "Ana"; userLastName = "Ruiz"
    }
    private val pagos = inyector.getInstance(PendingPaymentDao::class.java)
    private val cajon = inyector.getInstance(CashDrawerDao::class.java)
    private val caja = inyector.getInstance(CashPaymentRepository::class.java)

    @AfterTest fun cerrar() {
        runCatching { inyector.getInstance(AvoqadoDatabase::class.java).close() }
        carpeta.toFile().deleteRecursively()
    }

    private suspend fun abrir(id: String, status: String = "OPEN", haceMin: Long = 60) = cajon.insertSession(
        CashDrawerSessionEntity(id, VENUE, "PC", "staff-1", "Ana Ruiz", System.currentTimeMillis() - haceMin * 60_000, 50_000, status = status),
    )

    /** Un cobro rápido en efectivo puro apartado por esta app y que ya nadie resuelve (la venta se soltó). */
    private suspend fun abandonado(llave: String, enLaCaja: String?, importe: Int = 10_000, orden: String? = null) {
        caja.reservarCobro(
            CreateOrderRequest(items = emptyList(), subtotal = importe, total = importe, paymentMethod = "CASH"), "staff-1", importe, 0, null,
            orderId = orden, idempotencyKey = llave, venueId = VENUE, cajaDelCobro = enLaCaja,
        )
        caja.olvidarReservaEnMemoria(llave)
    }

    /** Un `CASH_SALE` ya en la caja, con el id que se le dé (un cuid = del servidor; un UUID = de este aparato). */
    private suspend fun ventaEnLaCaja(id: String, importe: Int, orden: String? = "orden-del-servidor", hora: Long = System.currentTimeMillis()) =
        cajon.insertEvent(
            CashDrawerEventEntity(
                id = id, sessionId = "caja-1", venueId = VENUE, type = "CASH_SALE", amountCents = importe,
                note = null, staffId = "staff-1", staffName = "Ana Ruiz", orderId = orden, createdAt = hora,
            ),
        )

    /** La hora en que quedó el apartado de K (la del ÚLTIMO intento con esa llave): el ancla de la cota de la gemela. */
    private suspend fun horaDelApartado(llave: String) = pagos.findForVenue(VENUE, llave)!!.createdAt

    private suspend fun ventasEn(vararg cajas: String) = cajas.flatMap { cajon.getSessionEvents(it) }.filter { it.type == "CASH_SALE" }
    private suspend fun estado(llave: String) = pagos.findForVenue(VENUE, llave)?.syncStatus
    private fun det(llave: String) = UUID.nameUUIDFromBytes("CASH_SALE:$llave".toByteArray(Charsets.UTF_8)).toString()

    /** Prueba 1: el barrido anota UNA venta, en la caja que el cobro capturó, aunque lo llame cualquiera (no sólo la cola). */
    @Test fun `el barrido con la caja abierta anota una sola venta en la caja capturada`() = runBlocking {
        abrir("caja-1")
        abandonado("k1", enLaCaja = "caja-1", importe = 12_500)

        assertEquals(1, caja.darPorCobradasLasAbandonadas())
        assertEquals(0, caja.darPorCobradasLasAbandonadas(), "la segunda vuelta ya no la toca")

        assertEquals("PENDING", estado("k1"))
        val venta = ventasEn("caja-1").single()
        assertEquals(det("k1"), venta.id, "el id de su llave")
        assertEquals(12_500, venta.amountCents)
    }

    /** Prueba 3: la caja capturada ya se cerró. La fila pasa a la cola, pero su venta no se anota en NINGUNA caja (queda en el log). */
    @Test fun `con la caja capturada cerrada la fila pasa a la cola sin anotarse en otra caja`() = runBlocking {
        abrir("caja-vieja", status = "CLOSED", haceMin = 300)
        abrir("caja-1")
        abandonado("k-vieja", enLaCaja = "caja-vieja")
        abandonado("k-control", enLaCaja = "caja-1") // control: con su caja abierta, sí se anota

        assertEquals(2, caja.darPorCobradasLasAbandonadas())

        assertEquals("PENDING", estado("k-vieja"))
        assertTrue(ventasEn("caja-vieja", "caja-1").none { it.id == det("k-vieja") }, "nunca en la caja de otro turno")
        assertEquals(listOf(det("k-control")), ventasEn("caja-1").map { it.id })
    }

    /** N5: la caja capturada es local y ya se cerró al fundirse con la del servidor (alias): se anota en la ABIERTA de las dos. */
    @Test fun `la venta va a la primera caja ABIERTA entre la capturada y su alias del servidor`() = runBlocking {
        abrir("local-1", status = "CLOSED")
        abrir("srv-1")
        storage.setDrawerSessionAliasesJson(VENUE, """[{"local":"local-1","servidor":"srv-1","en":1}]""")
        abandonado("k1", enLaCaja = "local-1")

        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        assertEquals(listOf(det("k1")), ventasEn("srv-1").map { it.id })
        assertTrue(ventasEn("local-1").isEmpty())
    }

    /** Prueba 10: «Sí se cobró» (una persona decide) también anota la venta, una vez, en la caja capturada. Antes no la anotaba. */
    @Test fun `Si se cobro anota la venta una vez en la caja capturada`() = runBlocking {
        abrir("caja-1")
        abandonado("k1", enLaCaja = "caja-1")

        assertTrue(caja.confirmarQueSeCobro("k1"))
        assertEquals(0, caja.darPorCobradasLasAbandonadas(), "ya no está en vuelo")

        assertEquals("PENDING", estado("k1"))
        assertEquals(listOf(det("k1")), ventasEn("caja-1").map { it.id })
    }

    /**
     * Codex r10 #2: una consulta del cajón importó la venta DEL SERVIDOR mientras la fila seguía en vuelo (su respuesta se perdió).
     * Barrer sin red no anota otra copia: el esperado la cuenta UNA vez. Sin la gemela, el mismo barrido sí anota.
     */
    @Test fun `con la gemela del servidor ya en la caja el barrido sin red no anota otra copia`() = runBlocking {
        abrir("caja-1")
        abandonado("k1", enLaCaja = "caja-1", importe = 10_000)
        cajon.insertEvent(
            CashDrawerEventEntity(
                id = "cmh0servidorventa0000000k1", sessionId = "caja-1", venueId = VENUE, type = "CASH_SALE", amountCents = 10_000,
                note = null, staffId = "staff-1", staffName = "Ana Ruiz", orderId = "orden-del-servidor", createdAt = System.currentTimeMillis(),
            ),
        )
        abandonado("k2", enLaCaja = "caja-1", importe = 7_000) // control: sin gemela

        assertEquals(2, caja.darPorCobradasLasAbandonadas())

        assertEquals("PENDING", estado("k1"), "la transición se hace igual")
        val ventas = ventasEn("caja-1")
        assertEquals(1, ventas.count { it.amountCents == 10_000 }, "la de 10_000 cuenta una vez: la del servidor")
        assertTrue(ventas.none { it.id == det("k1") })
        assertTrue(ventas.any { it.id == det("k2") }, "sin gemela, el barrido sí anota")
        assertEquals(17_000, ventas.sumOf { it.amountCents })
    }

    // MARK: - Ronda corta tras el rediseño: la cota de la gemela del servidor y lo que la distingue

    /** Una venta del servidor de OTRA orden no es la gemela de un cobro que sí tiene orden: su venta se anota. */
    @Test fun `una venta del servidor de otra orden no es la gemela - la venta del apartado se anota`() = runBlocking {
        abrir("caja-1")
        abandonado("k1", enLaCaja = "caja-1", importe = 10_000, orden = "orden-K")
        ventaEnLaCaja("cmh0servidorotraorden00001", 10_000, orden = "otra-orden")

        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        assertTrue(ventasEn("caja-1").any { it.id == det("k1") })
    }

    /**
     * La gemela nace mientras viaja el POST de K: en `[apartado − 5 min, apartado + 10 min]`. Una del mismo importe fuera de esa
     * cota (por abajo o por arriba, p. ej. otro café igual horas después) es OTRA venta: la del apartado se anota (sin un sobrante falso).
     */
    @Test fun `una venta del servidor de antes de la cota no es la gemela`() = runBlocking {
        abrir("caja-1")
        abandonado("k1", enLaCaja = "caja-1", importe = 10_000)
        ventaEnLaCaja("cmh0servidorantesdelacota1", 10_000, hora = horaDelApartado("k1") - 5 * 60_000 - 1)

        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        assertTrue(ventasEn("caja-1").any { it.id == det("k1") })
    }

    @Test fun `una venta del servidor de despues de la cota no es la gemela`() = runBlocking {
        abrir("caja-1")
        abandonado("k1", enLaCaja = "caja-1", importe = 10_000)
        ventaEnLaCaja("cmh0servidordespuesdelacot", 10_000, hora = horaDelApartado("k1") + 10 * 60_000 + 1)

        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        assertTrue(ventasEn("caja-1").any { it.id == det("k1") })
    }

    @Test fun `en los bordes de la cota si es la gemela`() = runBlocking {
        abrir("caja-1")
        abandonado("k-abajo", enLaCaja = "caja-1", importe = 10_000)
        ventaEnLaCaja("cmh0servidorbordeabajo0001", 10_000, hora = horaDelApartado("k-abajo") - 5 * 60_000)
        assertEquals(1, caja.darPorCobradasLasAbandonadas())
        abandonado("k-arriba", enLaCaja = "caja-1", importe = 20_000)
        ventaEnLaCaja("cmh0servidorbordearriba001", 20_000, hora = horaDelApartado("k-arriba") + 10 * 60_000)
        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        assertTrue(ventasEn("caja-1").none { it.id == det("k-abajo") || it.id == det("k-arriba") })
    }

    /**
     * Lo que distingue a la gemela DEL SERVIDOR: su id. Un cuid es del servidor; el UUID v3 de una llave (`idDeLaVentaEnEfectivo`) y un
     * UUID al azar son de este aparato y nunca cuentan como gemela, aunque coincidan importe, orden y hora.
     */
    @Test fun `un cuid es del servidor y un UUID v3 o al azar son locales`() = runBlocking {
        abrir("caja-1")
        abandonado("k-v3", enLaCaja = "caja-1", importe = 10_000)
        ventaEnLaCaja(det("otra-llave"), 10_000, orden = null)
        assertEquals(1, caja.darPorCobradasLasAbandonadas())
        abandonado("k-azar", enLaCaja = "caja-1", importe = 20_000)
        ventaEnLaCaja(UUID.randomUUID().toString(), 20_000, orden = null)
        assertEquals(1, caja.darPorCobradasLasAbandonadas())
        abandonado("k-cuid", enLaCaja = "caja-1", importe = 30_000)
        ventaEnLaCaja("cmh0servidorconcuid0000001", 30_000, orden = null)
        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        val ids = ventasEn("caja-1").map { it.id }
        assertTrue(det("k-v3") in ids, "UUID v3 de otra llave: local, no es gemela")
        assertTrue(det("k-azar") in ids, "UUID al azar: local, no es gemela")
        assertTrue(det("k-cuid") !in ids, "cuid: del servidor, sí es la gemela")
    }

    // MARK: - Ronda r11

    /**
     * Codex r11 #1: la gemela del PRIMER intento de K. El primer `/fast` llegó al servidor (su venta ya se importó al cajón) y su respuesta
     * se perdió; la cajera reintenta con la MISMA llave 20 min después, sin 2xx, y la fila se barre. La ventana va de la primera captura
     * (la que conserva la captura Q15) al último intento + 10 min: el esperado cuenta la venta UNA vez.
     */
    @Test fun `la gemela del primer intento de la llave tambien cuenta aunque el reintento sea 20 min despues`() = runBlocking {
        abrir("caja-1", haceMin = 120)
        val primerIntento = System.currentTimeMillis() - 60 * 60_000
        val venta = CreateOrderRequest(items = emptyList(), subtotal = 10_000, total = 10_000, paymentMethod = "CASH")
        caja.reservarCobro(venta, "staff-1", 10_000, 0, null, idempotencyKey = "k1", venueId = VENUE, cajaDelCobro = "caja-1", capturadaEn = primerIntento)
        ventaEnLaCaja("cmh0servidorprimerintento1", 10_000, hora = primerIntento + 60_000)
        caja.reservarCobro(venta, "staff-1", 10_000, 0, null, idempotencyKey = "k1", venueId = VENUE, cajaDelCobro = "caja-1",
            capturadaEn = primerIntento + 20 * 60_000)
        assertEquals(primerIntento + 20 * 60_000, horaDelApartado("k1"), "el reintento reescribe la hora del apartado")
        caja.olvidarReservaEnMemoria("k1")

        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        assertTrue(ventasEn("caja-1").none { it.id == det("k1") }, "la gemela del primer intento ya la cuenta")
        assertEquals(10_000, ventasEn("caja-1").sumOf { it.amountCents })
    }

    // MARK: - D15 (QA de WINDOWS, 25-oct): la venta del servidor que la caja YA traía al apartar es de otro cobro

    /**
     * D15: un café de $100 ya subió y su venta del servidor ya está en la caja; 2 min después se cobra otro café igual y el servidor no
     * contesta. Esa venta existía ANTES de que saliera el POST de K: no puede ser su gemela. Antes se tomaba por gemela, la venta de K no
     * se anotaba y la caja sin red esperaba $100 de menos (sobrante falso hasta volver la red).
     */
    @Test fun `una venta del servidor que la caja ya traia al apartar no es la gemela`() = runBlocking {
        abrir("caja-1")
        ventaEnLaCaja("cmh0servidorcafeanterior01", 10_000, orden = null, hora = System.currentTimeMillis() - 2 * 60_000)
        abandonado("k2", enLaCaja = "caja-1", importe = 10_000)

        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        assertTrue(ventasEn("caja-1").any { it.id == det("k2") }, "la venta de K se anota")
        assertEquals(20_000, ventasEn("caja-1").sumOf { it.amountCents }, "dos cafés, dos ventas")
    }

    /** La de antes no cuenta como gemela, pero la que llega DESPUÉS de apartar sí: cada billete cuenta una vez. */
    @Test fun `con una venta igual de antes y la gemela de despues no se anota otra`() = runBlocking {
        abrir("caja-1")
        ventaEnLaCaja("cmh0servidorcafeanterior01", 10_000, orden = null, hora = System.currentTimeMillis() - 2 * 60_000)
        abandonado("k2", enLaCaja = "caja-1", importe = 10_000)
        ventaEnLaCaja("cmh0servidorgemeladek20001", 10_000, orden = null)

        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        assertTrue(ventasEn("caja-1").none { it.id == det("k2") })
        assertEquals(20_000, ventasEn("caja-1").sumOf { it.amountCents })
    }

    /** Con alias (S3): K capturó la caja local y la venta de antes estaba en la del servidor. Tampoco es su gemela. */
    @Test fun `la venta de antes en la caja del alias tampoco es la gemela`() = runBlocking {
        abrir("local-1", status = "CLOSED")
        abrir("srv-1")
        storage.setDrawerSessionAliasesJson(VENUE, """[{"local":"local-1","servidor":"srv-1","en":1}]""")
        cajon.insertEvent(
            CashDrawerEventEntity("cmh0servidorcafeanterior02", "srv-1", VENUE, "CASH_SALE", 10_000, null, "staff-1", "Ana Ruiz", null,
                System.currentTimeMillis() - 60_000),
        )
        abandonado("k2", enLaCaja = "local-1", importe = 10_000)

        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        assertEquals(20_000, ventasEn("srv-1").sumOf { it.amountCents })
    }

    /**
     * Codex r19: K llegó al servidor (una fusión ya trajo su venta), la cajera tocó «Cancelar» y vuelve a cobrar con la MISMA llave (el
     * flujo la conserva para que el servidor deduplique). Esa llave ya salió: no toma otra foto y su gemela sigue contando. Si no, la foto
     * nueva excluía a su propia gemela y el billete contaba dos veces (faltante falso).
     */
    @Test fun `tras Cancelar la misma llave no toma otra foto y su gemela sigue contando`() = runBlocking {
        abrir("caja-1")
        val venta = CreateOrderRequest(items = emptyList(), subtotal = 10_000, total = 10_000, paymentMethod = "CASH")
        caja.reservarCobro(venta, "staff-1", 10_000, 0, null, idempotencyKey = "k1", venueId = VENUE, cajaDelCobro = "caja-1")
        ventaEnLaCaja("cmh0servidorgemeladek10001", 10_000, orden = null)
        caja.descartarReservaRechazada("k1")
        assertEquals(null, estado("k1"), "premisa: «Cancelar» borró el apartado")
        abandonado("k1", enLaCaja = "caja-1", importe = 10_000)

        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        assertTrue(ventasEn("caja-1").none { it.id == det("k1") })
        assertEquals(10_000, ventasEn("caja-1").sumOf { it.amountCents }, "un billete: cuenta UNA vez")
    }

    /**
     * Codex r20: la primera reserva de K no se guarda (disco), así que no sale el POST. El reintento con K sigue siendo la primera vez:
     * toma su foto y la venta del servidor de antes no se toma por su gemela.
     */
    @Test fun `si la primera reserva no se guarda el reintento toma su foto`() = runBlocking {
        abrir("caja-1")
        ventaEnLaCaja("cmh0servidorcafeanterior01", 10_000, orden = null, hora = System.currentTimeMillis() - 60_000)
        var fallar = true
        val discoQueFallaUnaVez = object : PendingPaymentDao by pagos {
            override suspend fun insert(payment: PendingPaymentEntity) {
                if (fallar) { fallar = false; throw java.io.IOException("disco de prueba") }
                pagos.insert(payment)
            }
        }
        val repo = CashPaymentRepository(storage, discoQueFallaUnaVez, inyector.getInstance(SalidaDelApartado::class.java))
        val venta = CreateOrderRequest(items = emptyList(), subtotal = 10_000, total = 10_000, paymentMethod = "CASH")
        val reservar = suspend { repo.reservarCobro(venta, "staff-1", 10_000, 0, null, idempotencyKey = "k2", venueId = VENUE, cajaDelCobro = "caja-1") }
        assertTrue(runCatching { reservar() }.isFailure, "premisa: la primera reserva no se guardó")
        reservar()
        repo.olvidarReservaEnMemoria("k2")

        assertEquals(1, repo.darPorCobradasLasAbandonadas())

        assertEquals(20_000, ventasEn("caja-1").sumOf { it.amountCents }, "dos cafés, dos ventas")
    }

    /**
     * Codex r11 #2: la protección exacta NO se trunca. Hay más de 500 cobros vivos de esta caja ANTES de K y más de 500 DESPUÉS (así ni
     * «los 500 más recientes» ni «sólo la primera tanda» encuentran a K), y la copia de K se anotó en los primeros 5 minutos de la caja.
     * El servidor confirma OTRA venta: la copia de K sobrevive mientras K pueda subir.
     */
    @Test fun `con mas de 500 cobros vivos la copia de un apartado sigue protegida al fusionar`() = runBlocking {
        val abrio = System.currentTimeMillis() - 120 * 60_000
        cajon.insertSession(CashDrawerSessionEntity("srv-1", VENUE, "PC", "staff-1", "Ana Ruiz", abrio, 50_000))
        repeat(501) { i -> pagos.insert(cobroVivo("viejo-$i", abrio + 60_000 + i)) }
        pagos.insert(cobroVivo("K", abrio + 2 * 60_000))
        repeat(501) { i -> pagos.insert(cobroVivo("vivo-$i", abrio + 30 * 60_000 + i)) }
        cajon.insertEvent(
            CashDrawerEventEntity(det("K"), "srv-1", VENUE, "CASH_SALE", 30_000, null, "staff-1", "Ana Ruiz", null, abrio + 2 * 60_000),
        )

        fusionarConOtraVenta(abrio, inyector.getInstance(PendingCashSales::class.java))

        assertTrue(cajon.getEvent(det("K")) != null, "la copia de K no se borra: K sigue por subir")
    }

    /**
     * Codex r12 #1: las tandas de la protección exacta leen UN estado coherente. K está en la SEGUNDA tanda (500 vivos antes que él) y,
     * entre la primera y la segunda, el replay de la cola lo confirma en otra corrutina. Si cada tanda fuera una lectura suelta, la
     * segunda ya no vería a K y la fusión borraría su copia (sobrante falso hasta la siguiente lectura). En una sola transacción de
     * lectura, la fusión ve a K por subir: su copia sobrevive a ESA fusión.
     */
    @Test fun `si el replay sube K entre dos tandas la fusion lee un estado coherente y la copia de K sobrevive`() = runBlocking {
        val abrio = System.currentTimeMillis() - 120 * 60_000
        cajon.insertSession(CashDrawerSessionEntity("srv-1", VENUE, "PC", "staff-1", "Ana Ruiz", abrio, 50_000))
        repeat(PendingCashSales.TANDA_DE_COBROS_VIVOS) { i -> pagos.insert(cobroVivo("viejo-$i", abrio + 60_000 + i)) }
        pagos.insert(cobroVivo("K", abrio + 2 * 60_000))
        cajon.insertEvent(
            CashDrawerEventEntity(det("K"), "srv-1", VENUE, "CASH_SALE", 30_000, null, "staff-1", "Ana Ruiz", null, abrio + 2 * 60_000),
        )
        var tandas = 0
        val conElReplayEnMedio = object : PendingPaymentDao by pagos {
            override suspend fun cobrosDeEfectivoVivos(venueId: String, cajas: List<String>, despuesDe: Long, despuesDeId: String, tanda: Int) =
                pagos.cobrosDeEfectivoVivos(venueId, cajas, despuesDe, despuesDeId, tanda).also {
                    if (tandas++ == 0) {
                        // El replay de la cola (otra corrutina, fuera de esta lectura) confirma K entre la primera tanda y la segunda.
                        val replay = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { pagos.updateStatus("K", "SYNCED") }
                        kotlinx.coroutines.withTimeoutOrNull(1_000) { replay.join() }
                    }
                }
        }
        val pendientes = PendingCashSales(
            inyector.getInstance(com.avoqado.pos.core.data.local.database.SyncIntentDao::class.java), conElReplayEnMedio,
            inyector.getInstance(AvoqadoDatabase::class.java),
        )

        fusionarConOtraVenta(abrio, pendientes)

        assertTrue(tandas >= 2, "K estaba en la segunda tanda")
        assertTrue(cajon.getEvent(det("K")) != null, "la fusión leyó a K por subir: su copia no se borra")
    }

    private fun cobroVivo(id: String, hora: Long) = PendingPaymentEntity(
        id = id, venueId = VENUE, staffId = "staff-1", amountCents = 10_000, tipCents = 0, method = "CASH", paymentType = "FAST",
        syncStatus = "PENDING", createdAt = hora, cashDrawerSessionId = "srv-1",
    )

    /**
     * Codex r13 #1: el servidor armó el corte ANTES de tener a K. Mientras la respuesta viaja, el replay confirma K (SYNCED). Al
     * aplicarla, K ya no está vivo, pero esa respuesta no traía su reemplazo: la copia se conserva por la foto que se tomó justo antes
     * de pedir el corte. La quita sólo el corte que SÍ trae la venta de K, y entonces cuenta una vez.
     */
    @Test fun `si el replay sube K mientras viaja el corte la copia sobrevive y el corte que trae K la cuenta una vez`() = runBlocking {
        val abrio = System.currentTimeMillis() - 120 * 60_000
        cajon.insertSession(CashDrawerSessionEntity("srv-1", VENUE, "PC", "staff-1", "Ana Ruiz", abrio, 50_000))
        pagos.insert(cobroVivo("K", abrio + 2 * 60_000))
        cajon.insertEvent(
            CashDrawerEventEntity(det("K"), "srv-1", VENUE, "CASH_SALE", 30_000, null, "staff-1", "Ana Ruiz", null, abrio + 2 * 60_000),
        )
        var traeK = false
        var alPedir: () -> Unit = { runBlocking { pagos.updateStatus("K", "SYNCED") } } // el replay, mientras viaja la respuesta
        val repo = CashDrawerRepository(
            cajon, storage, clienteDelCorte(abrio, { if (traeK) listOf(OTRA_VENTA, ventaDeK(abrio)) else listOf(OTRA_VENTA) }, { alPedir() }),
            inyector.getInstance(PendingCashSales::class.java), inyector.getInstance(ConnectivityMonitor::class.java),
        )

        repo.syncFromApi()

        assertEquals("SYNCED", estado("K"))
        assertTrue(cajon.getEvent(det("K")) != null, "el corte se armó sin K: su copia no se borra aunque K ya subió")

        alPedir = {}
        traeK = true
        repo.syncFromApi()

        assertTrue(cajon.getEvent(det("K")) == null, "el corte que trae la venta de K reclama su copia")
        assertEquals(10_000 + 30_000, ventasEn("srv-1").sumOf { it.amountCents }, "la otra venta y la de K, una vez cada una")
    }

    // MARK: - Codex r14 A1, A2 y A4: la fusión con el servidor

    private fun copiaDeK(abrio: Long) =
        CashDrawerEventEntity(det("K"), "srv-1", VENUE, "CASH_SALE", 30_000, null, "staff-1", "Ana Ruiz", null, abrio + 2 * 60_000)

    /**
     * Codex r14 A1: la copia de K NACE mientras viaja el corte (no estaba en la foto) y el replay confirma K antes de que llegue la
     * respuesta, que se armó sin K. La respuesta no conocía esa copia: no la puede borrar. La quita el corte que sí trae la venta de K.
     */
    @Test fun `la copia que nace mientras viaja el corte sobrevive a ese corte y el que trae K la cuenta una vez`() = runBlocking {
        val abrio = System.currentTimeMillis() - 120 * 60_000
        cajon.insertSession(CashDrawerSessionEntity("srv-1", VENUE, "PC", "staff-1", "Ana Ruiz", abrio, 50_000))
        var traeK = false
        var alPedir: () -> Unit = {
            runBlocking { pagos.insert(cobroVivo("K", abrio + 2 * 60_000)); cajon.insertEvent(copiaDeK(abrio)); pagos.updateStatus("K", "SYNCED") }
        }
        val repo = CashDrawerRepository(
            cajon, storage, clienteDelCorte(abrio, { if (traeK) listOf(OTRA_VENTA, ventaDeK(abrio)) else listOf(OTRA_VENTA) }, { alPedir() }),
            inyector.getInstance(PendingCashSales::class.java), inyector.getInstance(ConnectivityMonitor::class.java),
            inyector.getInstance(AvoqadoDatabase::class.java),
        )

        repo.syncFromApi()
        assertTrue(cajon.getEvent(det("K")) != null, "la respuesta no conocía la copia de K: no la borra")

        alPedir = {}
        traeK = true
        repo.syncFromApi()
        assertTrue(cajon.getEvent(det("K")) == null, "el corte que trae la venta de K reclama su copia")
        assertEquals(10_000 + 30_000, ventasEn("srv-1").sumOf { it.amountCents })
    }

    /**
     * Codex r15 #2: se pide `/current` sin caja en el aparato (la foto no tiene cajas). Mientras viaja, `/open` liga el aparato a la
     * caja S, se cobra K en S y K sube. El `/current` viejo, armado antes de K, no puede borrar esa copia: nació durante la petición
     * aunque su caja no estuviera en la foto. La quita el corte que sí trae la venta de K.
     */
    @Test fun `la copia que nace en una caja adoptada durante la peticion sobrevive a ese corte`() = runBlocking {
        val abrio = System.currentTimeMillis() - 120 * 60_000
        var traeK = false
        var alPedir: () -> Unit = {
            runBlocking {
                cajon.insertSession(CashDrawerSessionEntity("srv-1", VENUE, "PC", "staff-1", "Ana Ruiz", abrio, 50_000)) // `/open` ligó a S
                pagos.insert(cobroVivo("K", abrio + 2 * 60_000))
                cajon.insertEvent(copiaDeK(abrio))
                pagos.updateStatus("K", "SYNCED")
            }
        }
        val repo = CashDrawerRepository(
            cajon, storage, clienteDelCorte(abrio, { if (traeK) listOf(OTRA_VENTA, ventaDeK(abrio)) else listOf(OTRA_VENTA) }, { alPedir() }),
            inyector.getInstance(PendingCashSales::class.java), inyector.getInstance(ConnectivityMonitor::class.java),
            inyector.getInstance(AvoqadoDatabase::class.java),
        )

        repo.syncFromApi()
        assertTrue(cajon.getEvent(det("K")) != null, "la respuesta no conocía la copia de K: no la borra")

        alPedir = {}
        traeK = true
        repo.syncFromApi()
        assertTrue(cajon.getEvent(det("K")) == null)
        assertEquals(10_000 + 30_000, ventasEn("srv-1").sumOf { it.amountCents })
    }

    /**
     * Codex r16 #2 (capacidad): la foto no carga el historial. Las ventas de una caja CERRADA (sin nada en la cola) nunca entran a una
     * fusión con foto, así que la foto no las lee: con el uso, cada consulta de la caja cargaba todas las ventas del negocio.
     */
    @Test fun `la foto no lee las ventas de las cajas cerradas`() = runBlocking {
        val abrio = System.currentTimeMillis() - 120 * 60_000
        cajon.insertSession(CashDrawerSessionEntity("vieja", VENUE, "PC", "staff-1", "Ana Ruiz", abrio - 86_400_000, 50_000, status = "CLOSED"))
        val historial = (1..3).map { i ->
            CashDrawerEventEntity("cmh0historialconfirmada0000$i", "vieja", VENUE, "CASH_SALE", 1_000, null, "staff-1", "Ana Ruiz", null, abrio - 86_000_000)
        }
        historial.forEach { cajon.insertEvent(it) }
        cajon.insertSession(CashDrawerSessionEntity("srv-1", VENUE, "PC", "staff-1", "Ana Ruiz", abrio, 50_000))
        val leidas = mutableSetOf<String>()
        val contador = object : CashDrawerDao by cajon {
            override suspend fun getSessionEvents(sessionId: String) = cajon.getSessionEvents(sessionId).also { leidas += it.map { e -> e.id } }
        }
        CashDrawerRepository(
            contador, storage, clienteDelCorte(abrio, { listOf(OTRA_VENTA) }),
            inyector.getInstance(PendingCashSales::class.java), inyector.getInstance(ConnectivityMonitor::class.java),
            inyector.getInstance(AvoqadoDatabase::class.java),
        ).syncFromApi()

        assertTrue(historial.none { it.id in leidas }, "no se cargó ninguna venta del historial cerrado")
        assertEquals(3, ventasEn("vieja").size, "y el historial sigue intacto")
    }

    /**
     * Codex r16 #2: si la caja que se fusiona estaba CERRADA aquí (sin nada en la cola), la foto no leyó sus ventas y no sabe cuáles
     * existían. Sus ventas viejas no se toman por nacidas durante la petición: la copia que duplica una venta del servidor ya importada
     * se retira como antes, sin doble conteo.
     */
    @Test fun `una caja cerrada que la foto no leyo no toma sus ventas viejas por nacidas`() = runBlocking {
        val abrio = System.currentTimeMillis() - 120 * 60_000
        cajon.insertSession(CashDrawerSessionEntity("srv-1", VENUE, "PC", "staff-1", "Ana Ruiz", abrio, 50_000, status = "CLOSED"))
        val servidor = CashDrawerEventEntity("cmh0servidorotraventa00001", "srv-1", VENUE, "CASH_SALE", 10_000, null, "staff-1", "Ana Ruiz", "otra", abrio + 60 * 60_000)
        val copiaVieja = servidor.copy(id = UUID.randomUUID().toString())
        cajon.insertEvent(servidor)
        cajon.insertEvent(copiaVieja)

        CashDrawerRepository(
            cajon, storage, clienteDelCorte(abrio, { listOf(OTRA_VENTA) }),
            inyector.getInstance(PendingCashSales::class.java), inyector.getInstance(ConnectivityMonitor::class.java),
            inyector.getInstance(AvoqadoDatabase::class.java),
        ).syncFromApi()

        assertTrue(cajon.getEvent(copiaVieja.id) == null, "la copia vieja se retira: el servidor ya trae esa venta")
        assertEquals(10_000, ventasEn("srv-1").sumOf { it.amountCents })
    }

    /**
     * Codex r17: la caja S está CERRADA aquí (un cierre rechazado que la cajera dio por visto) y abierta en el servidor. Sale `/current`
     * y su foto no lee S. Mientras viaja, `/open` readopta S abierta y se cobra K con 2xx (K sube y se anota). La respuesta vieja alcanza
     * una caja que la foto no leyó: se DESCARTA sin aplicar nada y se reconsulta con S leída, que trae la venta de K y la cuenta una vez.
     */
    @Test fun `una caja cerrada que se readopta mientras viaja el corte no pierde la venta nueva`() = runBlocking {
        val abrio = System.currentTimeMillis() - 120 * 60_000
        cajon.insertSession(CashDrawerSessionEntity("srv-1", VENUE, "PC", "staff-1", "Ana Ruiz", abrio, 50_000, status = "CLOSED"))
        val llamadas = java.util.concurrent.atomic.AtomicInteger()
        val repo = CashDrawerRepository(
            cajon, storage,
            clienteDelCorte(abrio, { if (llamadas.get() >= 1) listOf(OTRA_VENTA, ventaDeK(abrio)) else listOf(OTRA_VENTA) }, {
                if (llamadas.incrementAndGet() == 1) runBlocking {
                    cajon.insertSession(CashDrawerSessionEntity("srv-1", VENUE, "PC", "staff-1", "Ana Ruiz", abrio, 50_000)) // `/open` la readopta
                    pagos.insert(cobroVivo("K", abrio + 2 * 60_000))
                    cajon.insertEvent(copiaDeK(abrio))
                    pagos.updateStatus("K", "SYNCED") // 2xx comprobado
                }
            }),
            inyector.getInstance(PendingCashSales::class.java), inyector.getInstance(ConnectivityMonitor::class.java),
            inyector.getInstance(AvoqadoDatabase::class.java),
        )

        repo.syncFromApi()

        assertEquals(2, llamadas.get(), "la respuesta vieja se descartó y se reconsultó con S leída")
        assertTrue(cajon.getEvent("cmh0servidorventadek000001") != null)
        assertEquals(10_000 + 30_000, ventasEn("srv-1").sumOf { it.amountCents }, "la venta de K cuenta una vez")
    }

    /**
     * Codex r14 A2: la fusión de una caja es UNA transacción. Entre leer las candidatas y borrar, otra escritura anota una venta nueva
     * (la salida de un apartado, un cobro): el borrado por tipo no la puede alcanzar, porque nunca la evaluó.
     */
    @Test fun `una venta anotada a media fusion no la borra el borrado de esa fusion`() = runBlocking {
        val abrio = System.currentTimeMillis() - 120 * 60_000
        cajon.insertSession(CashDrawerSessionEntity("srv-1", VENUE, "PC", "staff-1", "Ana Ruiz", abrio, 50_000))
        val nueva = CashDrawerEventEntity(UUID.randomUUID().toString(), "srv-1", VENUE, "CASH_SALE", 7_000, null, "staff-1", "Ana Ruiz", null, System.currentTimeMillis())
        var otraEscritura: kotlinx.coroutines.Job? = null
        val conUnaVentaEnMedio = object : CashDrawerDao by cajon {
            override suspend fun deleteUnconfirmedEvents(sessionId: String, serverOwnedTypes: List<String>, confirmedIds: List<String>, protectedIds: List<String>) {
                if (otraEscritura == null) {
                    // Otra corrutina (fuera de esta fusión) anota una venta entre la lectura de las candidatas y el borrado.
                    otraEscritura = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { cajon.insertEvent(nueva) }
                    kotlinx.coroutines.withTimeoutOrNull(1_000) { otraEscritura!!.join() }
                }
                cajon.deleteUnconfirmedEvents(sessionId, serverOwnedTypes, confirmedIds, protectedIds)
            }
        }
        val repo = CashDrawerRepository(
            conUnaVentaEnMedio, storage, clienteDelCorte(abrio, { listOf(OTRA_VENTA) }),
            inyector.getInstance(PendingCashSales::class.java), inyector.getInstance(ConnectivityMonitor::class.java),
            inyector.getInstance(AvoqadoDatabase::class.java),
        )

        repo.syncFromApi()
        otraEscritura!!.join()

        assertTrue(cajon.getEvent(nueva.id) != null, "la venta anotada a media fusión sigue en el cajón")
    }

    /**
     * Codex r14 A4: dos consultas de `/current` se cruzan. La nueva dice que ya no hay caja abierta (otro aparato la cerró) y la caja
     * se cierra aquí; después llega la vieja, que todavía la traía abierta. La vieja es anterior a la última aplicada: se descarta y no
     * reabre la caja (ni borra por tipo lo que la nueva ya importó).
     */
    @Test fun `una respuesta de la caja mas vieja que la ultima aplicada se descarta`() = runBlocking {
        val abrio = System.currentTimeMillis() - 120 * 60_000
        cajon.insertSession(CashDrawerSessionEntity("srv-1", VENUE, "PC", "staff-1", "Ana Ruiz", abrio, 50_000))
        var sinCaja = false
        val llamadas = java.util.concurrent.atomic.AtomicInteger()
        val sueltaLaVieja = java.util.concurrent.CountDownLatch(1)
        val repo = CashDrawerRepository(
            cajon, storage,
            clienteDelCorte(abrio, { listOf(OTRA_VENTA) }, {
                if (llamadas.incrementAndGet() == 1) sueltaLaVieja.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }, sinCaja = { sinCaja }),
            inyector.getInstance(PendingCashSales::class.java), inyector.getInstance(ConnectivityMonitor::class.java),
            inyector.getInstance(AvoqadoDatabase::class.java),
        )

        val vieja = async(kotlinx.coroutines.Dispatchers.IO) { repo.syncFromApi() }
        while (llamadas.get() < 1) kotlinx.coroutines.delay(10)
        sinCaja = true
        repo.syncFromApi() // la nueva: ya no hay caja abierta
        assertEquals("CLOSED", cajon.getSession("srv-1")?.status)
        sueltaLaVieja.countDown()
        vieja.await()

        assertEquals("CLOSED", cajon.getSession("srv-1")?.status, "la respuesta vieja no reabre la caja")
    }

    private fun ventaDeK(abrio: Long) = """{"id":"cmh0servidorventadek000001","type":"CASH_SALE","amount":300.00,"note":null,
        "staffId":"staff-1","staffName":"Ana Ruiz","orderId":"orden-K","localId":null,"createdAt":"${iso(abrio + 2 * 60_000)}"}"""

    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()

    /** El servidor de la caja `srv-1`: contesta `/cash-drawer/current` con [eventos]; [alPedir] corre mientras «viaja» la respuesta. */
    private fun clienteDelCorte(abrio: Long, eventos: () -> List<String>, alPedir: () -> Unit = {}, sinCaja: () -> Boolean = { false }) =
        okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            if (!chain.request().url.encodedPath.endsWith("/cash-drawer/current")) throw java.io.IOException("sin red")
            val respuesta = if (sinCaja()) """{"success":true,"data":null}""" else """{"success":true,"data":{"id":"srv-1","venueId":"$VENUE","deviceName":"PC","status":"OPEN",
                "openedByStaffId":"staff-1","openedByName":"Ana Ruiz","openedAt":"${iso(abrio)}","startingAmount":500.00,
                "closedByStaffId":null,"closedByName":null,"closedAt":null,"actualAmount":null,"overShort":null,"closingNote":null,
                "events":[${eventos().joinToString(",") { it.replace("ABRIO+60", iso(abrio + 60 * 60_000)) }}]}}"""
            alPedir()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(respuesta.toResponseBody()).build()
        }.build()

    /** La caja `srv-1` vista desde el servidor: trae OTRA venta (no la de K). */
    private suspend fun fusionarConOtraVenta(abrio: Long, pendientes: PendingCashSales) {
        CashDrawerRepository(
            cajon, storage, clienteDelCorte(abrio, { listOf(OTRA_VENTA) }), pendientes, inyector.getInstance(ConnectivityMonitor::class.java),
        ).syncFromApi()
    }

    private companion object {
        const val VENUE = "FULLTEST-VENTA-APARTADO"
        const val OTRA_VENTA = """{"id":"cmh0servidorotraventa00001","type":"CASH_SALE","amount":100.00,"note":null,"staffId":"staff-1",
            "staffName":"Ana Ruiz","orderId":"otra","localId":null,"createdAt":"ABRIO+60"}"""
    }
}

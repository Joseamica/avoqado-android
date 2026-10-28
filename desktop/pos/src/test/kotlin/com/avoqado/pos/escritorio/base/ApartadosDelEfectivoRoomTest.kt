package com.avoqado.pos.escritorio.base

import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.escritorio.Inyector
import com.avoqado.pos.payment.data.CapturaDelCobro
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.domain.ManualPaymentMethod
import com.avoqado.pos.payment.domain.TenderTypeOption
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 🔴 D2 v7 (24-oct-2026) con la SQL REAL: Room + SQLite del build de escritorio, que compila el MISMO `PendingPaymentDao` y el
 * mismo `CashPaymentRepository` que ANDROID (el gráfico de Guice de la app, como en producción en WINDOWS). Las pruebas del
 * ViewModel (`app/src/test/.../CobroRechazadoSinConfirmarTest`) usan un disco en memoria; aquí se fija que la SQL hace lo que
 * ese disco imita. Room de ANDROID sólo corre instrumentado (`androidTest`), que no se ejecuta en esta verificación.
 */
class ApartadosDelEfectivoRoomTest {
    private val carpeta = Files.createTempDirectory("fulltest-apartados-efectivo")
    private val inyector = Inyector.crear(ActividadDeEscritorio(carpeta))
    private val storage = inyector.getInstance(SecureStorage::class.java).apply { venueId = VENUE }
    private val dao = inyector.getInstance(PendingPaymentDao::class.java)
    private val caja = inyector.getInstance(CashPaymentRepository::class.java)

    @AfterTest fun cerrar() {
        runCatching { inyector.getInstance(AvoqadoDatabase::class.java).close() }
        carpeta.toFile().deleteRecursively()
    }

    private val venta = CreateOrderRequest(items = emptyList(), subtotal = 10_000, total = 10_000, paymentMethod = "CASH")

    private suspend fun apartar(
        llave: String,
        venue: String = VENUE,
        manual: ManualPaymentMethod? = null,
        tender: TenderTypeOption? = null,
        enLugarDe: List<String> = emptyList(),
    ) = caja.reservarCobro(venta, "staff-1", 10_000, 0, null, idempotencyKey = llave, manualMethod = manual, tenderType = tender,
        venueId = venue, enLugarDe = enLugarDe)

    /** Una fila de antes de Q15: `EN_VUELO` sin captura. */
    private fun heredada(id: String) = PendingPaymentEntity(id = id, venueId = VENUE, staffId = "staff-1", amountCents = 10_000, tipCents = 0,
        method = "CASH", paymentType = "FAST", syncStatus = "EN_VUELO")

    private suspend fun estado(id: String) = dao.findForVenue(VENUE, id)?.syncStatus

    /** Una fila `EN_VUELO` sin dueño escrita directo: con captura de la app de ANTES (sin la marca) o con la marca. */
    private suspend fun sinDuenio(id: String, marcada: Boolean) = dao.insert(heredada(id).copy(
        q15CaptureJson = ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(VENUE, id, amountCents = 10_000, tipCents = 0, method = "CASH",
            daPorCobradaAlAbandonar = true.takeIf { marcada })),
    ))

    private suspend fun marca(id: String) =
        ConciliacionDelCobro.leerCaptura(dao.findForVenue(VENUE, id)?.q15CaptureJson, VENUE, id)?.daPorCobradaAlAbandonar

    @Test fun `descartarReservaRechazada borra solo un EN_VUELO con captura y borrarSiEnVuelo sigue sin borrar`() = runBlocking {
        apartar("con-captura")
        dao.insert(heredada("sin-captura"))
        apartar("encolada"); dao.enVueloAPendiente("encolada")

        assertEquals(0, dao.borrarSiEnVuelo("con-captura"), "`AND 0`: soltar nunca borra")
        assertEquals("EN_VUELO", estado("con-captura"))

        assertEquals(1, dao.descartarReservaRechazada("con-captura"))
        assertNull(dao.findForVenue(VENUE, "con-captura"))
        assertEquals(0, dao.descartarReservaRechazada("sin-captura"), "una fila heredada sin captura no se toca")
        assertEquals("EN_VUELO", estado("sin-captura"))
        assertEquals(0, dao.descartarReservaRechazada("encolada"), "una encolada no se toca")
        assertEquals("PENDING", estado("encolada"))
    }

    /** Ronda 1 (I1): un `EN_VUELO` con motivo Q15 (2xx no comprobado) es evidencia por revisar: descartar no lo borra. */
    @Test fun `descartarReservaRechazada no borra un EN_VUELO con motivo Q15`() = runBlocking {
        apartar("por-revisar")
        dao.retenerPagoQ15(VENUE, "por-revisar", "RESULTADO_NO_COMPROBADO", 1L)
        assertEquals(0, dao.descartarReservaRechazada("por-revisar"))
        assertEquals("EN_VUELO", estado("por-revisar"))
    }

    /** Ronda final (A5): «Ya pagó de otra forma» (método manual o tipo del catálogo) nunca se borra: sigue como hoy. */
    @Test fun `descartarReservaRechazada no borra Ya pago de otra forma`() = runBlocking {
        apartar("transferencia", manual = ManualPaymentMethod.TRANSFER)
        apartar("del-catalogo", tender = TenderTypeOption("vale", 3, "Vale", false, "CASH", true, "MORE", 1))
        assertEquals(0, dao.descartarReservaRechazada("transferencia"))
        assertEquals(0, dao.descartarReservaRechazada("del-catalogo"))
        apartar("efectivo", enLugarDe = listOf("transferencia", "del-catalogo"))
        assertEquals("EN_VUELO", estado("transferencia"), "volver a cobrar con otra llave tampoco lo borra")
        assertEquals("EN_VUELO", estado("del-catalogo"))
    }

    @Test fun `el apartado del nuevo intento borra el viejo en la misma escritura, y si falla el viejo se queda`() = runBlocking {
        apartar("intento-1")
        apartar("intento-2", enLugarDe = listOf("intento-1"))
        assertNull(dao.findForVenue(VENUE, "intento-1"))
        assertEquals("EN_VUELO", estado("intento-2"))

        // La escritura del intento 3 truena DESPUÉS de borrar el 2 dentro de la transacción (su llave ya es de otro negocio):
        // Room deshace el borrado. Nunca ninguna fila por un billete.
        apartar("intento-3", venue = "OTRO-VENUE")
        assertFailsWith<IllegalArgumentException> { apartar("intento-3", enLugarDe = listOf("intento-2")) }
        assertEquals("EN_VUELO", estado("intento-2"), "el viejo se queda")
    }

    @Test fun `darPorCobradasLasAbandonadas pasa a PENDING con su llave solo el efectivo puro sin duenio`() = runBlocking {
        apartar("abandonada"); caja.olvidarReservaEnMemoria("abandonada")
        apartar("flujo-vivo")
        apartar("transferencia", manual = ManualPaymentMethod.entries.first()); caja.olvidarReservaEnMemoria("transferencia")
        apartar("del-catalogo", tender = TenderTypeOption("vale", 3, "Vale", false, "CASH", true, "MORE", 1))
        caja.olvidarReservaEnMemoria("del-catalogo")
        dao.insert(heredada("sin-captura"))
        apartar("por-revisar"); caja.olvidarReservaEnMemoria("por-revisar")
        dao.retenerPagoQ15(VENUE, "por-revisar", "RESULTADO_NO_COMPROBADO", 1L)
        apartar("otro-negocio", venue = "OTRO-VENUE"); caja.olvidarReservaEnMemoria("otro-negocio")

        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        val subida = assertNotNull(dao.findForVenue(VENUE, "abandonada"))
        assertEquals("PENDING", subida.syncStatus, "se da por cobrada con su MISMA llave")
        assertNotNull(subida.q15CaptureJson)
        for (sigue in listOf("flujo-vivo", "transferencia", "del-catalogo", "sin-captura", "por-revisar")) {
            assertEquals("EN_VUELO", estado(sigue), "$sigue no se toca")
        }
        assertEquals("EN_VUELO", dao.findForVenue("OTRO-VENUE", "otro-negocio")?.syncStatus)
        assertTrue(caja.cobrosSinConfirmar().none { it.id == "flujo-vivo" }, "el flujo vivo no es «sin confirmar»")
    }

    @Test fun `el barrido no toca la captura VIEJA sin la marca y si la marcada`() = runBlocking {
        sinDuenio("de-la-app-de-antes", marcada = false)
        sinDuenio("de-la-app-nueva", marcada = true)

        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        assertEquals("EN_VUELO", estado("de-la-app-de-antes"), "las «sin confirmar» de antes se quedan como hoy")
        assertEquals("PENDING", estado("de-la-app-nueva"))
    }

    @Test fun `la reserva marca su captura y el reintento con la misma llave la conserva en disco`() = runBlocking {
        apartar("con-marca")
        assertEquals(true, marca("con-marca"))
        apartar("con-marca") // reintento: `filaAlReemplazar` conserva la captura de disco
        assertEquals(true, marca("con-marca"))
        caja.queueCashPayment(venta, "staff-1", 10_000, 0, null, idempotencyKey = "con-marca", venueId = VENUE)
        assertEquals("PENDING", estado("con-marca"))
        assertEquals(true, marca("con-marca"), "el encolado con la misma llave también conserva la captura")
    }

    @Test fun `un reintento con la misma llave cuyo guardado falla deja la fila anterior del flujo`() = runBlocking {
        apartar("reintento")
        // Mismo id, otro negocio: el guardado del reintento truena (`La reserva pertenece a otro negocio`).
        assertFailsWith<IllegalArgumentException> { apartar("reintento", venue = "OTRO-VENUE") }

        assertEquals("EN_VUELO", estado("reintento"))
        assertTrue(caja.cobrosSinConfirmar().isEmpty(), "sigue siendo del flujo")
        assertEquals(0, caja.darPorCobradasLasAbandonadas(), "y no se da por cobrada mientras el flujo viva")
    }

    @Test fun `el 2xx con lo local fallido se da por cobrado y suelta la fila`() = runBlocking {
        apartar("registrado")
        assertTrue(caja.darPorCobrado("registrado"))
        assertEquals("PENDING", estado("registrado"))
    }

    /** Ronda 1 (M6): la cola del gráfico de la app (Guice en WINDOWS, como Hilt en ANDROID) llega CONECTADA al barrido. */
    @Test fun `la cola del grafico de la app barre de verdad lo abandonado`() = runBlocking {
        sinDuenio("abandonada-en-windows", marcada = true)
        inyector.getInstance(PaymentSyncService::class.java).darPorCobradasLasAbandonadasYa().join()
        assertEquals("PENDING", estado("abandonada-en-windows"))
    }

    private companion object { const val VENUE = "FULLTEST-APARTADOS" }
}

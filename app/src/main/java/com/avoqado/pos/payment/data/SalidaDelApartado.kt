package com.avoqado.pos.payment.data

import android.util.Log
import androidx.room.withTransaction
import com.avoqado.pos.cashdrawer.data.CashDrawerDao
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.PendingCashSales
import com.avoqado.pos.cashdrawer.data.leerAliasDeCajas
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventType
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerStatus
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🔴 D2, rediseño r9 (24-oct-2026): UN SOLO DUEÑO de la venta en el cajón del aparato para un cobro CON apartado de efectivo puro.
 *
 * Quien saca la fila de `EN_VUELO` hacia «cobrada» —2xx comprobado, encolar, el respaldo del 2xx, el barrido o «Sí se cobró»— anota
 * la venta en la MISMA transacción, y sólo si la fila ESTABA `EN_VUELO`. Un escritor tardío que la encuentra ya fuera no anota.
 * Antes la anotaban hasta cuatro escritores coordinados por un id que la fusión cambia y por una consulta previa no atómica (Codex
 * r9 #1, #3, #4 y #6): de ahí salían el duplicado, el cambio de caja, la caja equivocada y la anotación perdida si la app moría.
 *
 * - La caja es la que la fila CAPTURÓ, o su alias del servidor (S3): la primera de las dos que siga ABIERTA en el aparato (N5). Si
 *   ninguna lo está, la transición se hace igual, queda en el log y no se anota en otra caja.
 * - Id determinista ([CashDrawerRepository.idDeLaVentaEnEfectivo]) e `INSERT OR IGNORE`, nunca REPLACE.
 * - Si escribir la anotación falla (disco), la transacción entera se deshace: la fila sigue `EN_VUELO` para la siguiente vuelta.
 * - Sin el POST de compatibilidad a `/cash-drawer/sync` (N3): el servidor lo descarta, dentro de la transacción no cabe y en la
 *   vuelta de la cola bloquearía hasta 60 s con WiFi sin datos.
 * - Sin 2xx comprobado (r10 #2): si la caja ya trae el `CASH_SALE` DEL SERVIDOR de ese cobro (mismo importe, misma orden si la fila
 *   la tiene, creado en `[apartado − tolerancia de reloj, apartado + 10 min]`), no se anota. Ante la duda, un sobrante temporal (si esa venta
 *   era de otro aparato, hasta que suba la fila), nunca un faltante. Con 2xx comprobado se anota siempre.
 */
@Singleton
class SalidaDelApartado @Inject constructor(
    private val pagos: PendingPaymentDao,
    private val cajon: CashDrawerDao,
    private val secureStorage: SecureStorage,
    /** null sólo en pruebas con DAOs falsos (sin transacción). Hilt, y Guice en Windows, inyectan siempre la base real. */
    private val database: AvoqadoDatabase? = null,
) {
    /**
     * Agregado 2 de la ronda corta: quién está cobrando cada apartado, según el flujo que lo guardó (la sesión al confirmar el cobro,
     * como `recordCashSale` usaba `actorCapturado`/`nombreActorCapturado`). Sólo memoria: si el proceso muere, la salida es el barrido,
     * que usa la fila.
     */
    private val cajeros = ConcurrentHashMap<String, Pair<String, String>>()

    /** El flujo de cobro que aparta [llave] dice quién cobra. Lo usan las salidas DEL FLUJO (2xx, encolar, respaldo del 2xx). */
    fun recordarCajero(llave: String, staffId: String, nombre: String) {
        cajeros[llave] = staffId to nombre
    }

    /** La cajera borró ese apartado: ya no habrá salida que lo use. */
    fun olvidarCajero(llave: String) {
        cajeros.remove(llave)
    }

    /**
     * Saca la fila [id] de `EN_VUELO` con [transicion] (true = la escribió hacia «cobrada»). Si estaba `EN_VUELO` y era un apartado
     * de efectivo puro, anota su venta en la misma transacción. Devuelve lo que devolvió [transicion].
     *
     * @param conDosXx el servidor CONFIRMÓ el cobro: se anota siempre, sin buscar su gemela del servidor.
     * @param delFlujo la salida la hace el flujo que cobró (2xx, encolar, respaldo del 2xx): quien cobró es su cajera
     *   ([recordarCajero]). false = barrido o «Sí se cobró»: el `staffId` de la fila, y su nombre si es la sesión de ahora.
     * @param importe lo que entró al cajón, leído de la fila YA escrita. Por defecto su importe con propina (r10 #1: con 2xx
     *   comprobado quien llama pasa el definitivo, [OrderRepository.importeCobradoEnEfectivo]).
     */
    suspend fun salir(
        id: String,
        conDosXx: Boolean,
        delFlujo: Boolean,
        importe: (PendingPaymentEntity) -> Int = { it.amountCents + it.tipCents },
        transicion: suspend () -> Boolean,
    ): Boolean = enTransaccion {
        val antes = pagos.findByIdQ15(id)
        val salio = transicion()
        val estabaEnVuelo = antes?.syncStatus == PaymentSyncStatus.EN_VUELO.name
        if (salio && antes != null && estabaEnVuelo && esApartadoDeEfectivo(antes)) {
            val despues = pagos.findByIdQ15(id) ?: antes
            anotar(despues, importe(despues), conDosXx, quienCobro = if (delFlujo) cajeros[id] else null)
        }
        if (salio || !estabaEnVuelo) cajeros.remove(id)
        salio
    }

    private suspend fun <T> enTransaccion(bloque: suspend () -> T): T =
        if (database == null) bloque() else database.withTransaction { bloque() }

    private suspend fun anotar(fila: PendingPaymentEntity, importeCents: Int, conDosXx: Boolean, quienCobro: Pair<String, String>?) {
        val caja = fila.cashDrawerSessionId?.takeIf { it.isNotBlank() }
        if (importeCents <= 0 || caja == null) {
            Log.w(TAG, "⚠️ El cobro ${fila.id} no tiene caja (o es de \$0): su venta no se anota en el cajón")
            return
        }
        val sesion = cajaAbierta(caja, fila.venueId) ?: run {
            Log.w(TAG, "⚠️ La caja $caja del cobro ${fila.id} ya no está abierta en el aparato: su venta no se anota en otra caja")
            return
        }
        if (!conDosXx && gemelaDelServidor(sesion.id, fila, importeCents)) {
            Log.w(TAG, "⚠️ La caja ${sesion.id} ya trae del servidor una venta de $importeCents del cobro ${fila.id}: no se anota otra")
            return
        }
        val evento = CashDrawerEventEntity(
            id = CashDrawerRepository.idDeLaVentaEnEfectivo(fila.id),
            sessionId = sesion.id,
            venueId = fila.venueId,
            type = CashDrawerEventType.CASH_SALE.name,
            amountCents = importeCents,
            note = null,
            staffId = quienCobro?.first ?: fila.staffId,
            staffName = quienCobro?.second ?: nombreDe(fila.staffId),
            orderId = fila.orderId,
            // La hora en que el efectivo entró al cajón: la de la captura, no la de esta vuelta de la cola.
            createdAt = fila.createdAt,
        )
        if (cajon.insertarEventoSiNoExiste(evento) == -1L) {
            Log.d(TAG, "💵 La venta del cobro ${fila.id} ya estaba en el cajón: no se anota dos veces")
        } else {
            Log.d(TAG, "✅ Venta del cobro ${fila.id} anotada en la caja ${sesion.id}: $importeCents")
        }
    }

    /** Efectivo PURO con su captura: lo único que el barrido da por cobrado y que antes anotaba el ViewModel. */
    private fun esApartadoDeEfectivo(fila: PendingPaymentEntity): Boolean =
        fila.method == "CASH" && fila.tenderTypeId == null && fila.q15CaptureJson != null

    /** N5: la primera ABIERTA entre la caja capturada y su alias del servidor, de esa sucursal. */
    private suspend fun cajaAbierta(caja: String, sucursal: String): CashDrawerSessionEntity? =
        listOfNotNull(caja, leerAliasDeCajas(secureStorage, sucursal).firstOrNull { it.local == caja }?.servidor)
            .mapNotNull { cajon.getSession(it) }
            .firstOrNull { it.status == CashDrawerStatus.OPEN.name && it.venueId == sucursal }

    /**
     * r10 #2: la gemela del servidor llegó ANTES que la copia local (una consulta del cajón la importó con la fila en vuelo). Nace mientras
     * viaja ALGÚN POST de K, así que sólo cuenta la de `[primera captura − tolerancia de reloj, último intento + 10 min]`. La primera es
     * la de la captura Q15 (la primera manda); el último intento, el `createdAt` que el apartado reescribe en cada reintento con la misma
     * llave (tras un 409 de precio la llave y la fila son otras). Sin cota de arriba, otro cobro igual horas después se tomaba por gemela
     * y quedaba un sobrante falso.
     */
    private suspend fun gemelaDelServidor(sesion: String, fila: PendingPaymentEntity, importeCents: Int): Boolean {
        val captura = ConciliacionDelCobro.leerCaptura(fila.q15CaptureJson, fila.venueId, fila.id)
        // Codex r11 #1: desde la PRIMERA captura (la que conserva la captura Q15) hasta el último intento + 10 min: la gemela de un
        // intento anterior con la misma llave también cuenta. Fila heredada sin esa hora: el `createdAt`, como antes.
        val primera = captura?.primeraCapturaEn ?: fila.createdAt
        val desde = minOf(primera, fila.createdAt) - PendingCashSales.TOLERANCIA_DE_RELOJ_MILLIS
        val hasta = fila.createdAt + VENTANA_DE_LA_GEMELA_MILLIS
        // D15: lo que la caja ya traía al apartar es de otro cobro (existía antes del POST de K).
        val yaEstaban = captura?.ventasYaEnLaCaja.orEmpty().toSet()
        return cajon.getSessionEvents(sesion).any {
            it.type == CashDrawerEventType.CASH_SALE.name && esDelServidor(it.id) && it.id !in yaEstaban && it.amountCents == importeCents &&
                (fila.orderId == null || it.orderId == fila.orderId) && it.createdAt in desde..hasta
        }
    }

    /**
     * D15 (QA de WINDOWS, 25-oct): la foto que se guarda en la captura al apartar — los ids de las ventas DEL SERVIDOR que ya están en la
     * caja [caja] o en su alias desde la cota de abajo de la gemela ([capturadaEn] − tolerancia de reloj; lo de antes nunca cuenta).
     * Ninguna puede ser la gemela del cobro: el POST todavía no sale. Sin esto, el café igual que ya subió se tomaba por gemela del
     * siguiente y la caja sin red esperaba de menos. Lo que entra después de la foto sigue contando (ante la duda, el sobrante temporal).
     */
    suspend fun ventasDelServidorEnLaCaja(caja: String?, sucursal: String, capturadaEn: Long): List<String> {
        val propia = caja?.takeIf { it.isNotBlank() } ?: return emptyList()
        val cajas = listOfNotNull(propia, leerAliasDeCajas(secureStorage, sucursal).firstOrNull { it.local == propia }?.servidor).distinct()
        val desde = capturadaEn - PendingCashSales.TOLERANCIA_DE_RELOJ_MILLIS
        return cajas.flatMap { cajon.getSessionEvents(it) }
            .filter { it.type == CashDrawerEventType.CASH_SALE.name && esDelServidor(it.id) && it.createdAt >= desde }
            .map { it.id }
    }

    /** Lo que escribe este aparato lleva un UUID (al azar o el de la llave); lo que llega del servidor, su propio id. */
    private fun esDelServidor(id: String): Boolean = runCatching { UUID.fromString(id) }.isFailure

    /** Quien cobró, si es la sesión de ahora; si no, vacío (sólo se ve en el detalle local hasta la fusión). */
    private fun nombreDe(staffId: String): String {
        if (staffId != secureStorage.userId) return ""
        return "${secureStorage.userFirstName.orEmpty()} ${secureStorage.userLastName.orEmpty()}".trim()
            .ifEmpty { secureStorage.userEmail.orEmpty() }
    }

    private companion object {
        const val TAG = "💵 SalidaDelApartado"

        /** Hasta cuándo, después del apartado, puede nacer la gemela del servidor (espejo por valor de iOS). */
        const val VENTANA_DE_LA_GEMELA_MILLIS = 10 * 60_000L
    }
}

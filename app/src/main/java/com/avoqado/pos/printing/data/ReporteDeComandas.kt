package com.avoqado.pos.printing.data

import android.util.Log
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.printing.routing.PrintJobDto
import com.avoqado.pos.printing.routing.SyncPrintJobsRequest
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "🗒️ ReporteDeComandas"
private const val REASON_ORIGINAL = "ORIGINAL"
private const val TYPE_KITCHEN_TICKET = "KITCHEN_TICKET"
private const val STATUS_DONE = "DONE"
private const val STATUS_FAILED = "FAILED"
private const val MAX_ERROR_CHARS = 500

/** Tope corto: [reportar] NUNCA puede retener a quien llama más que esto — ni siquiera el KDS,
 *  que suelta un pedido a otra tablet en segundos cuando su impresora no contesta. */
private const val REPORT_TIMEOUT_MS = 5_000L

/**
 * «La libreta» (Task 16) — la app reporta al servidor lo que de verdad pasó con cada comanda,
 * para no depender de que el cliente reproduzca el fallo ("no las harán" — instrucción directa
 * del founder). El caso real que la origina: la impresora de cocina de un cliente se
 * desconectaba, y el fallo ocurría ENTERO en la LAN del local — nunca llegaba al servidor.
 * Diagnosticarlo costó una sesión entera sin encontrar la causa. Con esto, la primera falla de
 * la mañana la va a decir el propio servidor, sin depender de ninguna hipótesis.
 *
 * `ApiService.syncPrintJobs` ya estaba declarado (nadie lo llamaba); el server
 * (`print.mobile.service.ts`) ya guarda cada `PrintJob` con su estado y avisa a
 * ADMIN/MANAGER cuando pasa a FALLIDA. Es una RÉPLICA para auditoría/alertas — el server NO está
 * en el camino crítico de imprimir, así que perder un reporte JAMÁS puede perder o retrasar una
 * comanda real.
 */
@Singleton
class ReporteDeComandas @Inject constructor(
    private val apiService: ApiService,
    private val syncOutbox: SyncOutbox,
) {

    /**
     * @param venueId sin él no hay a quién reportarle — nunca se inventa uno; simplemente no
     *   se llama al server (el mismo `venueId` que ya usa `ComandaDispatcher.dispatch`).
     * @param orderId el id REAL de la orden (server), para que el dashboard pueda dar clic a
     *   la orden que falló. `orderNumber` es sólo texto de pantalla —a veces un placeholder
     *   aleatorio ("Q-1234") cuando no hay orden persistida— y NUNCA sirve para construir el
     *   `eventId`: dos órdenes distintas podrían compartirlo.
     * @param stationId de la estación que se reporta — null para un reporte AGREGADO (todo el
     *   lote de la comanda salió bien) o para un ticket sin estación ("SIN ESTACIÓN").
     * @param printerId de la impresora resuelta — hoy casi siempre null: ese dato no llega hasta
     *   [ComandaPrinter.Result] (ver Task 2); declarado como hueco, no inventado.
     *
     * 🔴 Fire-and-forget: JAMÁS propaga y JAMÁS retiene más de [REPORT_TIMEOUT_MS] — reportar no
     * puede frenar ni romper un cobro ni una impresión real.
     */
    suspend fun reportar(
        venueId: String?,
        orderId: String?,
        orderNumber: String,
        estado: EstadoDeComanda,
        intentos: Int,
        stationId: String? = null,
        printerId: String? = null,
    ) {
        if (venueId.isNullOrBlank()) return

        runCatching {
            val (status, error) = when (estado) {
                is EstadoDeComanda.Salio -> STATUS_DONE to null
                is EstadoDeComanda.NoSalio -> STATUS_FAILED to estado.causa?.take(MAX_ERROR_CHARS)
                // ReintentoDeComanda sólo llama a reportar() AL TERMINAR (Salio/NoSalio) — nunca
                // a media insistencia. Cubierto por exhaustividad del `when`, no por un caso real.
                is EstadoDeComanda.Insistiendo -> return@runCatching
            }

            val terminalId = syncOutbox.deviceId
            // Estable para la MISMA comanda+estación en reintentos sucesivos: el server deduplica
            // por (venueId, eventId, reason, seq). `orderId` puede faltar (venta sin orden
            // persistida) — cae a `orderNumber` en vez de la cadena literal "null", que colisionaría
            // TODAS las órdenes sin id sobre la MISMA estación.
            val eventId = "${orderId ?: orderNumber}:${stationId ?: "sin-estacion"}"

            val job = PrintJobDto(
                id = UUID.randomUUID().toString(),
                eventId = eventId,
                reason = REASON_ORIGINAL,
                seq = 1,
                type = TYPE_KITCHEN_TICKET,
                status = status,
                stationId = stationId,
                printerId = printerId,
                orderId = orderId,
                attempts = intentos,
                error = error,
            )
            val request = SyncPrintJobsRequest(terminalId = terminalId, jobs = listOf(job))

            val response = withTimeoutOrNull(REPORT_TIMEOUT_MS) {
                apiService.syncPrintJobs(venueId, request)
            }

            if (response != null && !response.data.registered) {
                // 🔴 El server DESCARTÓ el reporte EN SILENCIO (este terminal no es el gateway
                // designado del venue). Si no lo gritamos, creeremos que estamos midiendo cuando
                // en realidad no llega nada — justo la ceguera que esta libreta existe para evitar.
                Log.w(
                    TAG,
                    "⚠️ print-jobs/sync descartado: terminal=$terminalId no es el gateway de venue=$venueId",
                )
                runCatching {
                    FirebaseCrashlytics.getInstance().setCustomKey("print_job_sync_registered", false)
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "No se pudo reportar la comanda (venue=$venueId, orden=$orderNumber): ${e.message}")
        }
    }
}

package com.avoqado.pos.cashdrawer.data

import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.sync.SyncIntentTypes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Un cobro en efectivo que este aparato YA cobró y que sigue esperando a
 * reproducirse contra el server.
 *
 * @param orderId la orden a la que pertenece, si ya existía cuando se cobró. `null`
 *   en la venta de MOSTRADOR, que se cobra antes de que exista orden alguna.
 * @param totalCents el importe COMPLETO que entró al cajón, propina incluida — el
 *   mismo número con el que la fila entró al arqueo (`recordCashSale(total, …)`,
 *   donde `total = base + propina`). Ver [PendingCashSales].
 */
data class CobroSinReproducir(val orderId: String?, val totalCents: Int)

/**
 * Una venta que el server confirma en ESTE sync y que llega **por primera vez**: ni es
 * una fila mía que adoptó su id ([CashDrawerRepository.promoteEvent]), ni una que un
 * sync anterior ya copió.
 *
 * 🔴 Es la prueba de que un cobro SÍ aterrizó. La cola dice "yo todavía no lo he
 * reproducido"; NO dice "el server no lo tiene". Cuando la respuesta se pierde después
 * del commit —medido en producción: 6 reintentos, cero deduplicaciones— las dos cosas
 * son ciertas a la vez, y la copia local tiene que ceder en vez de protegerse.
 *
 * @param orderId la orden con la que el server la registró, si la tiene. En la venta de
 *   mostrador es la orden que creó ÉL, un id que este aparato nunca vio.
 * @param totalCents el importe con propina, el mismo número que escribe el cajón
 *   (`postCashSaleToDrawer` suma `amount + tipAmount`).
 */
data class VentaConfirmadaPorPrimeraVez(val orderId: String?, val totalCents: Int)

/**
 * Los cobros en efectivo que este aparato YA cobró y que el server TODAVÍA no ha
 * visto.
 *
 * 🔴 Para qué existe: `CASH_SALE` es un tipo del que el server es dueño, así que el
 * sync del cajón borra las copias locales que no vengan confirmadas (si no, la misma
 * venta suma dos veces). Pero una venta cobrada SIN RED todavía no puede venir
 * confirmada: su cobro está esperando en una cola. Borrarla en esa ventana le
 * desaparece al cajero dinero que sí está en el cajón, y encima justo cuando abre la
 * pantalla de caja para cerrar su turno.
 *
 * Esto NO es una inferencia sobre "qué tan nueva se ve" una venta: es la cola,
 * diciendo que el cobro sigue pendiente. Mientras un cobro esté aquí, el gemelo del
 * server no existe **por construcción**, así que conservar la copia local no puede
 * duplicar nada. En cuanto se reproduce, deja de aparecer y el siguiente sync la
 * borra como siempre.
 *
 * Las DOS colas cuentan, porque un cobro sin red cae en una o en la otra:
 *  - **outbox de intents** (`PAY_CASH`) cuando la mesa nació provisional; el
 *    `localOrderId` del payload es el MISMO id con el que la venta entró al cajón.
 *  - **`pending_payments`** cuando la orden ya existía, o cuando fue un cobro de
 *    mostrador y el POST falló.
 *
 * 🔴 `FAILED` NO cuenta a propósito. Un cobro fallido es ambiguo: puede haber
 * aterrizado en el server ("Order is already paid" es un 400 permanente) o no. Ante
 * la duda se mantiene el comportamiento de hoy —borrar la copia local—, que deja al
 * cajero con un SOBRANTE aparente en vez de con un faltante: nadie acusa a nadie de
 * un dinero que le sobra. Además esos cobros ya son visibles en cuarentena.
 *
 * 🔴 **Sólo el EFECTIVO protege.** Un cobro declarado a mano (terminal ajena,
 * transferencia) también se encola, pero nunca entró al cajón —`recordCashSale` se
 * corta en seco cuando hay `manualMethod`— así que no puede salvar ninguna fila. Si
 * lo dejáramos entrar, una tarjeta pendiente de $300 protegería una venta en efectivo
 * de $300 que el server sí tiene, y esos $300 se contarían dos veces.
 *
 * ## Por qué el monto, y no sólo la orden
 *
 * Espejo de `avoqado-ios/CashDrawer/Services/PendingCashSales.swift` (commit
 * `f85f4c6`). El pareo por `orderId` solo dejaba fuera justo al flujo más común de
 * una tienda en apagón: la venta de MOSTRADOR se cobra antes de que exista orden, así
 * que su fila del cajón nace con `orderId = null`. Sin nada con qué emparejarse, se
 * borraba aunque su `PAY_CASH` siguiera en el outbox: 500000 en pantalla donde el
 * cajón tenía 530000.
 *
 * 🔴 Son **CINCO** los sitios de `PaymentFlowViewModel` que registran la venta sin
 * orden, no cuatro — el mensaje de `82fda27` y su reporte listaban cuatro cada uno, y
 * ni siquiera los mismos. La cuenta se saca por FIRMA, nunca de memoria ni por número
 * de línea (que se mueve con cada edición); el desglose por flujo está en el KDoc de
 * `PaymentFlowViewModel.recordCashSale`:
 *
 * ```
 * grep -cE '^ +recordCashSale\(total(, null)?\)$' PaymentFlowViewModel.kt   # → 5 (de 10 sitios)
 * ```
 *
 * ⚠️ **Divergencia deliberada contra iOS, y a favor de Android:** allá TODO se parea
 * por monto; aquí el `orderId`, cuando existe, gana primero. La orden es un HECHO y el
 * monto una heurística, así que usar la heurística donde hay identidad sería
 * degradarse a propósito. El monto es la única salida para la fila que no tiene orden.
 */
@Singleton
class PendingCashSales @Inject constructor(
    private val intentDao: SyncIntentDao,
    private val pendingPaymentDao: PendingPaymentDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Los cobros EN EFECTIVO de ESTA caja que siguen esperando a reproducirse, de las
     * DOS colas.
     *
     * Es una LISTA y no un conjunto a propósito: cada cobro pendiente protege UNA
     * fila del cajón y sólo una. Dos cobros del mismo monto protegen dos filas; uno
     * solo no puede proteger dos, o el barrido dejaría vivo un duplicado y el cajero
     * cerraría con un faltante inventado.
     *
     * 🔴 **La ventana de la caja NO es un adorno** ([desdeMillis]). Sin cota, un cobro
     * que quedó atorado ayer —o uno hecho con la caja cerrada— pareaba por monto una
     * venta de HOY que el server ya confirmó, la copia local sobrevivía y el arqueo
     * pedía ese dinero dos veces. Es la misma fuga que cerró `729b0a8` ("el retiro de
     * ayer no se cuela a hoy"), reabierta por otra puerta, y se cierra con el mismo
     * criterio: `createdAt >= session.openedAt`, que es la MISMA ventana con la que el
     * server calcula su esperado — cliente y server no pueden divergir por
     * construcción.
     *
     * @param desdeMillis `openedAt` de la caja del server.
     */
    suspend fun sinReproducir(venueId: String, desdeMillis: Long): List<CobroSinReproducir> =
        deLaColaDeCobros(venueId, desdeMillis) + deLosIntentsDelOutbox(venueId, desdeMillis)

    /**
     * `pending_payments`: el cobro que ya tenía orden, o el de mostrador cuyo POST
     * falló.
     *
     * 🔴 El SQL sólo ACOTA (por local); quien DECIDE es este filtro en Kotlin, que es
     * donde los tests lo fijan. Si alguien afloja la consulta, el dinero sigue
     * protegido aquí. Al revés no: un filtro que vive únicamente en un `@Query` no lo
     * ejercita ninguna prueba de esta suite.
     */
    private suspend fun deLaColaDeCobros(venueId: String, desdeMillis: Long): List<CobroSinReproducir> =
        pendingPaymentDao.forVenue(venueId)
            .filter { it.syncStatus in COLA_VIVA }
            .filter { it.method == METODO_EFECTIVO }
            .filter { it.createdAt >= desdeMillis }
            .map {
                CobroSinReproducir(
                    orderId = it.orderId?.takeIf(String::isNotBlank),
                    // El arqueo suma el total CON propina, así que el monto que parea
                    // tiene que sumarla también o nunca coincidiría con la fila.
                    totalCents = it.amountCents + it.tipCents,
                )
            }

    /**
     * El `PAY_CASH` del outbox trae el importe partido en `amountCents` y `tipCents`
     * (ver `PaymentFlowViewModel.recordCashPaymentForOrder`), que sumados son
     * exactamente lo que entró al cajón.
     */
    private suspend fun deLosIntentsDelOutbox(venueId: String, desdeMillis: Long): List<CobroSinReproducir> =
        intentDao.pendingPayloads(venueId, SyncIntentTypes.PAY_CASH)
            .filter { it.createdAt >= desdeMillis }
            .mapNotNull { pendiente ->
            runCatching {
                val obj = json.parseToJsonElement(pendiente.payloadJson) as JsonObject
                // Un cobro declarado a mano no entra al cajón, así que tampoco puede
                // proteger una fila (mismo criterio que la otra cola).
                if (obj["method"] != null) return@runCatching null
                val total = (obj["amountCents"]?.jsonPrimitive?.intOrNull ?: 0) +
                    (obj["tipCents"]?.jsonPrimitive?.intOrNull ?: 0)
                if (total <= 0) return@runCatching null
                CobroSinReproducir(
                    orderId = obj["localOrderId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                    totalCents = total,
                )
            }.getOrNull()
        }

    companion object {
        /** Un cobro que sigue vivo en la cola. `FAILED` (cuarentena) queda fuera. */
        private val COLA_VIVA = setOf(PaymentSyncStatus.PENDING.name, PaymentSyncStatus.SYNCING.name)

        /** El nombre con el que `CashPaymentRepository` guarda el efectivo. */
        private const val METODO_EFECTIVO = "CASH"

        /**
         * 🔴 Las filas del cajón que el barrido NO puede borrar: las que siguen
         * esperando en una cola.
         *
         * @param ventasLocales las copias locales de `CASH_SALE` de esta caja que el
         *   server NO confirmó (las confirmadas ya se filtraron fuera por quien llama).
         * @param cobrosSinReproducir lo que devolvió [sinReproducir].
         * @param ventasConfirmadasPorPrimeraVez las ventas que el server trae en ESTE
         *   sync y que no son filas mías renombradas — ver
         *   [VentaConfirmadaPorPrimeraVez].
         *
         * 🔴 **Pasada 0 — lo que el server YA cubre deja de ser candidato.** La cola
         * dice "yo todavía no lo he reproducido", no "el server no lo tiene": cuando la
         * respuesta se pierde después del commit, las dos cosas son ciertas a la vez.
         * Cada venta que el server confirma por primera vez RECLAMA una copia local
         * —por orden si la hay, si no por monto y de la más VIEJA hacia adelante— y una
         * fila reclamada ya no se puede proteger. Sin esto, la copia local se salvaba
         * por error junto a la fila del server y la MISMA venta sumaba dos veces:
         * 560000 en pantalla con 530000 en el cajón, que es la dirección que acusa a
         * una persona de robar.
         *
         * De la más VIEJA hacia adelante porque la que el server ya tiene es la que se
         * cobró primero; la reciente es la que sigue esperando. Es el espejo exacto de
         * la pasada de protección, que va de la más reciente hacia atrás.
         *
         * Después, sobre lo que quedó, las dos pasadas de siempre:
         *
         *  1. **Identidad.** Un cobro nombrado con la MISMA orden que la fila es un
         *     hecho, no una inferencia: gana sobre cualquier pareo por monto y CONSUME
         *     ese cobro, para que no pueda además salvar a otra fila.
         *  2. **Monto, de la más reciente hacia atrás.** Es lo único que le queda a la
         *     venta de mostrador. Si dos ventas comparten monto, la que sigue esperando
         *     en la cola es la última cobrada — la más vieja ya se reprodujo y el server
         *     ya la tiene.
         *
         * Cada cobro se consume al usarse: proteger de más reintroduce el doble conteo
         * que el barrido vino a evitar. Sin cobros pendientes se comporta como antes de
         * que este archivo existiera: no protege nada.
         */
        fun ventasProtegidas(
            ventasLocales: List<CashDrawerEventEntity>,
            cobrosSinReproducir: List<CobroSinReproducir>,
            ventasConfirmadasPorPrimeraVez: List<VentaConfirmadaPorPrimeraVez> = emptyList(),
        ): Set<String> {
            if (ventasLocales.isEmpty()) return emptySet()
            val deLaMasRecienteHaciaAtras = ventasLocales.sortedByDescending { it.createdAt }
            val deLaMasViejaHaciaAdelante = deLaMasRecienteHaciaAtras.asReversed()

            // Pasada 0: el server ya las tiene. Primero por orden (identidad), y sólo
            // las que no encontraron orden compiten por monto — el mismo orden con el
            // que se decide la protección, para que las dos listas no se contradigan.
            val reclamadas = mutableSetOf<String>()
            val sinOrdenQueParear = mutableListOf<VentaConfirmadaPorPrimeraVez>()
            for (confirmada in ventasConfirmadasPorPrimeraVez) {
                val orderId = confirmada.orderId?.takeIf(String::isNotBlank)
                val porOrden = orderId?.let { buscada ->
                    deLaMasViejaHaciaAdelante.firstOrNull { it.id !in reclamadas && it.orderId == buscada }
                }
                if (porOrden != null) reclamadas += porOrden.id else sinOrdenQueParear += confirmada
            }
            for (confirmada in sinOrdenQueParear) {
                val porMonto = deLaMasViejaHaciaAdelante.firstOrNull {
                    it.id !in reclamadas && it.amountCents == confirmada.totalCents
                }
                if (porMonto != null) reclamadas += porMonto.id
            }

            if (cobrosSinReproducir.isEmpty()) return emptySet()
            val pendientes = cobrosSinReproducir.toMutableList()
            val protegidas = mutableSetOf<String>()

            for (venta in deLaMasRecienteHaciaAtras) {
                if (venta.id in reclamadas) continue
                val orderId = venta.orderId?.takeIf(String::isNotBlank) ?: continue
                val i = pendientes.indexOfFirst { it.orderId == orderId }
                if (i >= 0) {
                    pendientes.removeAt(i)
                    protegidas += venta.id
                }
            }

            for (venta in deLaMasRecienteHaciaAtras) {
                if (venta.id in reclamadas || venta.id in protegidas) continue
                val i = pendientes.indexOfFirst { it.totalCents == venta.amountCents }
                if (i >= 0) {
                    pendientes.removeAt(i)
                    protegidas += venta.id
                }
            }
            return protegidas
        }
    }
}

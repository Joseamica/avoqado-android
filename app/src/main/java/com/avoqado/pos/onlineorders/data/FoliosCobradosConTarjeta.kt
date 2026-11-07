package com.avoqado.pos.onlineorders.data

import com.avoqado.pos.core.data.local.SecureStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Tienda híbrida (A5, Codex fase 3-4 r1 #1): ESTE aparato cobró [montoCents] con tarjeta al folio [orderId], que debía
 * [saldoAntesCents] al cobrarse, a las [enMs] (reloj del aparato).
 */
@Serializable
data class FolioCobradoConTarjeta(val orderId: String, val montoCents: Long, val saldoAntesCents: Long, val enMs: Long)

/**
 * 🔴 DINERO. Los folios que este aparato cobró con la terminal ligada y que la lista del SERVIDOR todavía no cuenta. El cobro los
 * anota ANTES de soltar su pendiente de tarjeta (`PaymentFlowViewModel.applyCardCharged` → `reconocerCobro`): si la red se cae antes
 * de releer, la lista vieja (o la guardada, tras reiniciar) sigue diciendo «Por pagar», y sin esto la cajera podía cobrarle efectivo
 * encima. «Cobrar» se niega mientras haya marca, la pantalla lo pinta en «Por entregar» como el efectivo en cola, y la bandeja la
 * suelta cuando una lectura del SERVIDOR ya cuenta el pago (`tarjetasConfirmadas`).
 *
 * En disco con `commit()` (SecureStorage, como la lista de cobros con tarjeta pendientes) y fuera de `clearSession`: un cobro no deja
 * de existir porque alguien cierre sesión. Si el disco no acepta la escritura, SharedPreferences ya la tiene en memoria: protege
 * mientras viva el proceso.
 */
@Singleton
class FoliosCobradosConTarjeta internal constructor(
    private val leerCrudo: () -> String?,
    private val escribirCrudo: (String) -> Boolean,
) {
    @Inject
    constructor(secureStorage: SecureStorage) : this(
        leerCrudo = { secureStorage.foliosCobradosConTarjetaJson },
        escribirCrudo = secureStorage::guardarFoliosCobradosConTarjeta,
    )

    @Synchronized
    fun marcas(): List<FolioCobradoConTarjeta> =
        leerCrudo()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    /**
     * Otra parte con tarjeta del mismo folio se SUMA a su marca y conserva el saldo de antes de la primera: así «lo que falta bajó al
     * menos lo cobrado» se mide contra el mismo punto de partida. @return `false` si el disco no aceptó la escritura.
     */
    @Synchronized
    fun marcar(orderId: String, montoCents: Long, saldoAntesCents: Long, enMs: Long = System.currentTimeMillis()): Boolean {
        val lista = marcas()
        val nueva = if (lista.none { it.orderId == orderId }) {
            lista + FolioCobradoConTarjeta(orderId, montoCents, saldoAntesCents, enMs)
        } else {
            lista.map { if (it.orderId == orderId) it.copy(montoCents = it.montoCents + montoCents) else it }
        }
        return escribirCrudo(json.encodeToString(serializer, nueva))
    }

    /** Suelta las marcas que el SERVIDOR ya cuenta. Sólo las IGUALES: una que cambió mientras tanto (otra parte) se queda. */
    @Synchronized
    fun olvidar(confirmadas: Collection<FolioCobradoConTarjeta>) {
        if (confirmadas.isEmpty()) return
        val lista = marcas()
        val quedan = lista.filterNot { it in confirmadas }
        if (quedan.size != lista.size) escribirCrudo(json.encodeToString(serializer, quedan))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(FolioCobradoConTarjeta.serializer())

        /** Para pruebas y para quien no lo inyecta: el «disco» es un texto en memoria. */
        fun enMemoria(): FoliosCobradosConTarjeta {
            var crudo: String? = null
            return FoliosCobradosConTarjeta({ crudo }, { crudo = it; true })
        }
    }
}

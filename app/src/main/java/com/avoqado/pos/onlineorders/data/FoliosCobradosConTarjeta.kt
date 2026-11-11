package com.avoqado.pos.onlineorders.data

import com.avoqado.pos.core.data.local.SecureStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Tienda híbrida (R100): el cobro con tarjeta se abrió desde la bandeja «Pedidos en línea». [saldoAntesCents] = lo que debía la
 * orden al abrirlo; [esFolio] = folio de «Nadie» (si no, un pedido de «Mi equipo»). Viaja en el contexto durable del cobro
 * (`TerminalPaymentService.sendPaymentToTerminal`): con él, el positivo deja su marca «aquí ya se cobró» al salir de la lista.
 */
data class CobroDeLaBandeja(val saldoAntesCents: Long, val esFolio: Boolean)

/**
 * R100: ESTE aparato cobró [montoCents] con tarjeta (la solicitud [requestId]) a la orden [orderId] del negocio [venueId], que debía
 * [saldoAntesCents]. Las marcas que dejó A5 no traen negocio ni solicitud (null) y eran sólo de folios.
 */
@Serializable
data class FolioCobradoConTarjeta(
    val orderId: String,
    val montoCents: Long,
    val saldoAntesCents: Long,
    val enMs: Long,
    val venueId: String? = null,
    val requestId: String? = null,
    val esFolio: Boolean = true,
    /**
     * Tienda híbrida, parte 2 (A1): el cobro fue con SALDO o tarjeta de regalo (lo deja `CobroConSaldoCoordinator`, [requestId] = su
     * llave). Frena otro «Cobrar» igual que la de tarjeta, pero no mueve el folio a «Por entregar»: pudo ser parcial y todavía debe.
     */
    val conSaldo: Boolean = false,
)

/**
 * 🔴 DINERO (R100, fuente (c) de `dineroSinConfirmar`). Los cobros con tarjeta de la bandeja que este aparato ya dio por buenos y que
 * la lista del SERVIDOR quizá todavía no cuenta. La escribe `TerminalPaymentService` en el MISMO `commit` en que un positivo sale de
 * la lista durable de cobros con tarjeta (adopción del flujo, «Entendido» o vencimiento de su ventana): así un éxito directo, tardío
 * o de conciliación nunca queda sin protección. Mientras el positivo sigue en esa lista, la fuente (b) es la que frena.
 *
 * En `SecureStorage` (`commit()`, fuera de `clearSession`). Sólo la suelta una lectura 2xx, COMPLETA y del MISMO negocio que ya
 * cuenta el pago (`tarjetasConfirmadas`): nunca un 403/404, una lectura parcial ni la de otro negocio.
 */
@Singleton
class FoliosCobradosConTarjeta internal constructor(
    private val leerCrudo: () -> String?,
    /** Lee y escribe bajo el mismo candado que la lista de cobros pendientes (la escribe también `TerminalPaymentService`). */
    private val escribirCrudo: ((String?) -> String) -> Boolean,
) {
    @Inject
    constructor(secureStorage: SecureStorage) : this(
        leerCrudo = { secureStorage.foliosCobradosConTarjetaJson },
        escribirCrudo = secureStorage::escribirFoliosCobradosConTarjeta,
    )

    fun marcas(): List<FolioCobradoConTarjeta> = leer(leerCrudo())

    /** Las marcas que pueden ser de [venueId]: las suyas y las de A5, que no dicen de qué negocio son. [venueId] null = todas. */
    fun marcasDe(venueId: String?): List<FolioCobradoConTarjeta> =
        marcas().filter { venueId == null || it.venueId == null || it.venueId == venueId }

    /** @return `false` si el disco no aceptó la escritura. */
    fun anotar(marca: FolioCobradoConTarjeta): Boolean = escribirCrudo { agregar(it, marca) }

    /** Suelta las marcas que el SERVIDOR ya cuenta. Sólo las IGUALES: una que cambió mientras tanto se queda. */
    fun olvidar(confirmadas: Collection<FolioCobradoConTarjeta>) {
        if (confirmadas.isEmpty()) return
        // ANTES de soltarla: quien vea la marca ya fuera ve también la cuenta subida.
        confirmadas.forEach { soltadas.merge(it.orderId, 1L, Long::plus) }
        escribirCrudo { crudo -> encode(leer(crudo).filterNot { it in confirmadas }) }
    }

    /**
     * Codex r3 #1: cuántas veces se soltó una marca de [orderId] en este proceso (ésta es la ÚNICA puerta). «Cobrar» lo compara antes
     * y después de esperar: una marca que nació y se soltó mientras tanto no deja otro rastro.
     */
    fun vecesSoltada(orderId: String): Long = soltadas[orderId] ?: 0L

    private val soltadas = java.util.concurrent.ConcurrentHashMap<String, Long>()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(FolioCobradoConTarjeta.serializer())

        fun leer(crudo: String?): List<FolioCobradoConTarjeta> =
            crudo?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

        private fun encode(lista: List<FolioCobradoConTarjeta>): String = json.encodeToString(serializer, lista)

        /** Idempotente por `requestId`: el mismo cobro nunca cuenta dos veces. PURA. */
        fun agregar(crudo: String?, marca: FolioCobradoConTarjeta): String {
            val lista = leer(crudo)
            if (marca.requestId != null && lista.any { it.requestId == marca.requestId }) return encode(lista)
            return encode(lista + marca)
        }

        /** Para pruebas y para quien no lo inyecta: el «disco» es un texto en memoria. */
        fun enMemoria(): FoliosCobradosConTarjeta {
            var crudo: String? = null
            return FoliosCobradosConTarjeta({ crudo }, { nueva -> crudo = nueva(crudo); true })
        }
    }
}

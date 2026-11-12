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
data class CobroDeLaBandeja(
    val saldoAntesCents: Long,
    val esFolio: Boolean,
    /**
     * D14 (`hz:ce654971:D14`): el cobro NO salió de la bandeja sino de otra pantalla que cobra una cuenta que ya existía («Pedidos →
     * detalle», partes 2..N). Igual deja su marca: la bandeja no ofrece efectivo sobre una cuenta que esta caja ya cobró con tarjeta,
     * venga de donde venga. [saldoAntesCents] = lo que esa pantalla creyó que faltaba.
     */
    val fueraDeLaBandeja: Boolean = false,
)

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
    /**
     * D14: la dejó un cobro con tarjeta de otra pantalla ([CobroDeLaBandeja.fueraDeLaBandeja]). Puede ser de una cuenta que la bandeja
     * nunca ve (mostrador): la suelta la copia de folios si la ve (R104; «Mi equipo» no, pudo ser un folio), y si no, se cae a los
     * [VIDA_DE_LA_MARCA_FUERA_MS] (7 días) con la siguiente marca que se escriba.
     */
    val fueraDeLaBandeja: Boolean = false,
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
        // ANTES de soltarla: quien vea la marca ya fuera ve también la cuenta subida. Sólo las de TARJETA (Codex r4 #2): la de un
        // cobro con saldo se suelta con la misma copia de la que «Cobrar» toma el saldo (W-1, A1) y no es evidencia que se pierda.
        confirmadas.filterNot { it.conSaldo }.forEach { soltadas.merge(it.orderId, 1L, Long::plus) }
        // D13: también para la puerta del efectivo, de cualquier instancia y de los dos tipos (el servidor ya cuenta ese dinero).
        soltar(confirmadas)
        escribirCrudo { crudo -> encode(leer(crudo).filterNot { it in confirmadas }) }
    }

    /**
     * Codex r3 #1: cuántas veces se soltó una marca de TARJETA de [orderId] en este proceso (ésta es la ÚNICA puerta). «Cobrar» lo compara antes
     * y después de esperar: una marca que nació y se soltó mientras tanto no deja otro rastro.
     */
    fun vecesSoltada(orderId: String): Long = soltadas[orderId] ?: 0L

    private val soltadas = java.util.concurrent.ConcurrentHashMap<String, Long>()

    companion object {
        /**
         * D13: las marcas de TARJETA que se soltaron (o se cayeron por su vida) en ESTE proceso, de cualquier instancia (la bandeja
         * suelta; la puerta del efectivo de la pantalla de cobro lee), con su número de orden de salida. Una marca soltada ya no deja otro
         * rastro: la puerta la cuenta como nueva si se soltó DESPUÉS de la foto de su pantalla ([secuencia]). Sólo en memoria: al morir
         * el proceso muere también la pantalla de cobro que la necesitaba.
         */
        // ponytail: se olvidan a los 7 días de soltadas; una pantalla de cobro abierta más de 7 días ya no las ve.
        private val liberadas = java.util.concurrent.ConcurrentLinkedQueue<Liberada>()
        private val contador = java.util.concurrent.atomic.AtomicLong()
        private const val VIDA_DE_LO_SOLTADO_MS = 7 * 24 * 60 * 60 * 1000L

        private class Liberada(val marca: FolioCobradoConTarjeta, val numero: Long, val enMs: Long)

        private fun soltar(marcas: Collection<FolioCobradoConTarjeta>) {
            val ahora = System.currentTimeMillis()
            marcas.filterNot { it.conSaldo }.forEach { liberadas.add(Liberada(it, contador.incrementAndGet(), ahora)) }
            liberadas.removeIf { ahora - it.enMs > VIDA_DE_LO_SOLTADO_MS }
        }

        /** D13: el número de la última marca soltada; una foto lo guarda y la puerta cuenta las de después. */
        fun secuencia(): Long = contador.get()

        /**
         * D13: lo que una marca de TARJETA dice para la puerta del efectivo: su llave (la del cobro, la misma que en la lista durable) y lo
         * cobrado. null = marca de saldo: el cobro con saldo es sólo en línea y el SERVIDOR lo cuenta al confirmarlo (A1 la deja frenar sólo
         * sin red, en la bandeja); la puerta no la cuenta (Codex r1 #1 de la puerta).
         */
        fun dineroDe(marca: FolioCobradoConTarjeta, confirmado: Boolean = false): com.avoqado.pos.payment.domain.DineroDeLaOrden? {
            if (marca.conSaldo) return null
            return com.avoqado.pos.payment.domain.DineroDeLaOrden(
                llave = marca.requestId?.let(com.avoqado.pos.payment.domain.PuertaDelEfectivo::llaveDeTarjeta) ?: "marca:${marca.orderId}:${marca.enMs}",
                montoCents = marca.montoCents,
                esTarjeta = true,
                confirmado = confirmado,
            )
        }

        /** D13: las marcas de tarjeta de [orderId] soltadas (la copia ya las contaba) en este proceso DESPUÉS de la foto [desde]. */
        fun liberadasDe(orderId: String, desde: Long): List<com.avoqado.pos.payment.domain.DineroDeLaOrden> =
            liberadas.filter { it.marca.orderId == orderId && it.numero > desde }.mapNotNull { dineroDe(it.marca, confirmado = true) }

        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(FolioCobradoConTarjeta.serializer())

        /**
         * D14: lo que dura EN DISCO una marca de fuera de la bandeja que nadie suelta (una cuenta de mostrador): la lista no crece sin fin.
         * Mientras dura, frena igual tras reiniciar (Codex r3 #1). Techo escrito: una cuenta cobrada con tarjeta hace más de 7 días cuyo
         * cobro el SERVIDOR todavía no registra.
         */
        const val VIDA_DE_LA_MARCA_FUERA_MS = 7 * 24 * 60 * 60 * 1000L

        fun leer(crudo: String?): List<FolioCobradoConTarjeta> =
            crudo?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

        private fun encode(lista: List<FolioCobradoConTarjeta>): String = json.encodeToString(serializer, lista)

        /**
         * Idempotente por `requestId`: el mismo cobro nunca cuenta dos veces. D14: al escribir se caen las marcas de fuera de la bandeja que
         * cumplieron su vida ([VIDA_DE_LA_MARCA_FUERA_MS]).
         */
        fun agregar(crudo: String?, marca: FolioCobradoConTarjeta, ahoraMs: Long = System.currentTimeMillis()): String {
            val lista = leer(crudo).filterNot { it.fueraDeLaBandeja && ahoraMs - it.enMs > VIDA_DE_LA_MARCA_FUERA_MS }
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

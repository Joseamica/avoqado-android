package com.avoqado.pos.payment.domain

import kotlinx.coroutines.sync.Mutex

/**
 * 🔴 DINERO (D13, `hz:ce654971:D13`). La puerta del efectivo: antes de apartar el efectivo de una orden que YA existe, la pantalla de
 * cobro mira de UNA vez todo el dinero de esa orden que este aparato tiene y lo compara con la [foto][DineroConocido] de lo que su saldo
 * ya contaba cuando se fijó. La bandeja «Pedidos en línea» revisaba antes de abrir y la puerta apartaba después: cada revisión que se
 * movía dentro de la bandeja abría otra rendija (Codex r3, r7 y r8 de `docs/audit/2026-11-11-folio-efectivo-tras-tarjeta-android/`).
 * Aquí la revisión y el apartado van bajo el mismo candado ([candadoDe]) y el dinero se reconoce por su LLAVE, no por su estado: un efectivo que pasa
 * de `EN_VUELO` a `SYNCED` durante la lectura sigue siendo el mismo (r8).
 */
object PuertaDelEfectivo {
    /**
     * Una puerta por ORDEN en el proceso: dos pantallas que confirman efectivo de la misma orden no revisan las dos antes de apartar. La
     * toma TODO apartado de una orden que ya existe (con foto o sin ella, Codex r6 #1); uno de otra orden nunca espera por ella.
     */
    // ponytail: los candados no se borran (uno por orden cobrada con foto en la vida del proceso); se borrarían si crecieran de verdad.
    fun candadoDe(orderId: String): Mutex = candados.computeIfAbsent(orderId) { Mutex() }

    private val candados = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    const val TEXTO_TARJETA = "Esta cuenta ya se cobró con tarjeta en esta caja. Revisa la cuenta antes de recibir efectivo."
    const val TEXTO_EFECTIVO = "Ya hay otro cobro de esta cuenta guardado en esta caja. Revisa la cuenta antes de recibir efectivo."
    const val TEXTO_NO_SE_PUDO = "No se pudo revisar los cobros de esta cuenta. Intenta de nuevo antes de recibir el pago."

    fun llaveDeEfectivo(id: String) = "efectivo:$id"
    fun llaveDeTarjeta(requestId: String) = "tarjeta:$requestId"

    /**
     * La decisión, PURA. [ahora] = el dinero de la orden que el aparato tiene en este instante (las marcas soltadas, sólo las de DESPUÉS
     * de la foto); [conocido] = la foto; [propias] = las llaves de ESTE intento (su apartado y los anteriores de esta venta, que reemplaza:
     * es el mismo dinero). [saldoCents] = lo que la pantalla creyó que faltaba (R0); [efectivoCents] = este cobro sin propina (C).
     *
     * Un efectivo ya SUBIDO lo cuenta R0 si el SERVIDOR lo trajo en la misma respuesta de la que salió R0 ([DineroConocido.pagosContados],
     * por el id del pago que dio su 2xx): no depende de cuándo se tomó la foto ni de qué saldo devolvió un reenvío (Codex r2 #2 y r3 #2).
     *
     * Frena si entró dinero que R0 no cuenta y, con él, este cobro pasa de lo que faltaba. Un abono que cabe pasa.
     */
    fun decidir(
        conocido: DineroConocido,
        ahora: List<DineroDeLaOrden>,
        propias: Set<String>,
        saldoCents: Long,
        efectivoCents: Long,
    ): String? {
        val nuevo = ahora.filter { d ->
            d.llave !in conocido.llaves && d.llave !in propias && !(d.pagoDelServidor != null && conocido.pagosContados?.contains(d.pagoDelServidor) == true)
        }.distinctBy { it.llave }
        if (nuevo.isEmpty()) return null
        if (nuevo.sumOf { it.montoCents } + efectivoCents <= saldoCents) return null
        return if (nuevo.any { it.esTarjeta }) TEXTO_TARJETA else TEXTO_EFECTIVO
    }
}

/**
 * Una pieza de dinero de una orden que tiene ESTE aparato: un efectivo de la cola (cualquier estado) o un cobro con tarjeta cobrado (de la
 * lista durable, su marca o su marca ya soltada). [montoCents] sin propina. [confirmado] = el efectivo ya `SYNCED` (2xx) o la marca
 * soltada. [pagoDelServidor] = el id del pago que dio el 2xx de este efectivo (null si no lo dijo o no ha subido).
 */
data class DineroDeLaOrden(
    val llave: String,
    val montoCents: Long,
    val esTarjeta: Boolean,
    val confirmado: Boolean = false,
    val pagoDelServidor: String? = null,
)

/**
 * La foto de quien fijó el saldo:
 * - [pagosContados] = los ids de los pagos COMPLETADOS que trajo la MISMA respuesta del SERVIDOR de la que salió el saldo (null si el
 *   saldo salió de la copia guardada, sin red): un efectivo subido cuyo pago está ahí ya lo cuenta el saldo;
 * - [llaves] = el efectivo que ya estaba SUBIDO antes de leer el saldo (el respaldo cuando no hay lista o su 2xx no dio el id);
 * - [soltadasHasta] = hasta qué marca soltada se conocía (`FoliosCobradosConTarjeta.secuencia`): las soltadas después son nuevas.
 * Una tarjeta o una marca VIVAS nunca se dan por contadas: el servidor puede no registrar todavía un cobro de la terminal (Codex W-1 r6
 * #2); la bandeja ya no abre con ellas y el detalle tampoco con una tarjeta en la lista.
 */
data class DineroConocido(val llaves: Set<String>, val soltadasHasta: Long = 0L, val pagosContados: Set<String>? = null) {
    companion object {
        /** Vacía: todo es nuevo (lo más seguro cuando la foto no se pudo leer). */
        val NADA = DineroConocido(emptySet())

        /** Bandeja y «Pedidos → detalle», ANTES de leer su saldo: el efectivo ya subido. */
        fun antesDeLeerElSaldo(efectivo: List<DineroDeLaOrden>, soltadasHasta: Long) =
            DineroConocido(efectivo.filter { !it.esTarjeta && it.confirmado }.map { it.llave }.toSet(), soltadasHasta)
    }
}

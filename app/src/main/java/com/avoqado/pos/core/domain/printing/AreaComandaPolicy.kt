// Vale de área — CUÁNDO le toca comanda a un área. Lógica PURA (sin Android, sin red, sin estado):
// toda la decisión vive aquí para poder probarla con una tabla de verdad y no a punta de hardware.
//
// Espejo del server: `FulfillmentArea.fulfillmentMode` (§5.6 del spec
// docs/superpowers/specs/2026-07-28-vales-por-area-y-bascula-design.md).
package com.avoqado.pos.core.domain.printing

/**
 * Modo de entrega de un área. Los nombres se espejan **por nombre EXACTO** con el enum del server;
 * un nombre distinto no revienta, falla en silencio (el área deja de imprimir y nadie se entera).
 */
enum class FulfillmentMode {
    /** Entrega al momento, no guarda nada — el pan que te llevas. Es el default del server. */
    IMMEDIATE,

    /** Prepara YA y guarda hasta que el cliente vuelva con el ticket pagado — la cremería. */
    HOLD_UNTIL_PAID,

    /** No prepara nada hasta que el cliente regresa pagado — la cafetería. */
    PREPARE_ON_PAID,
    ;

    companion object {
        /**
         * Un modo desconocido (server más nuevo que la app, dato sucio, campo vacío) cae a
         * [IMMEDIATE] a propósito: fail-open. La alternativa —tratarlo como "no sé, no imprimo"—
         * deja al área sin comanda por un string que nadie miró.
         */
        fun fromServer(raw: String?): FulfillmentMode =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: IMMEDIATE
    }
}

/** Los dos momentos en que un vale de área puede pedir su comanda. */
enum class ComandaMoment {
    /** El área acaba de emitir el vale y se quedó con el producto. El cliente aún NO paga. */
    AREA_TICKET_ISSUED,

    /** El cliente volvió con el ticket ya pagado y el área lo escaneó. */
    AREA_TICKET_PAID,
}

/**
 * La tabla de verdad de §5.6, y nada más.
 *
 * | modo | ¿comanda al emitir el vale? | ¿comanda al regresar pagado? |
 * |---|---|---|
 * | `IMMEDIATE` | sí | no |
 * | `HOLD_UNTIL_PAID` | sí | no |
 * | `PREPARE_ON_PAID` | no | sí |
 *
 * `IMMEDIATE` y `HOLD_UNTIL_PAID` preparan **antes** de que el cliente pase a la caja, así que su
 * comanda tiene que salir al emitir el vale — que es justo lo que el mostrador no sabía hacer: ahí
 * la comanda sale DESPUÉS de cobrar.
 *
 * 🔎 **`PREPARE_ON_PAID`, evaluado (el spec dice "probablemente sobra" y deja la decisión abierta).**
 * En ese modo quien escanea el ticket pagado y quien prepara suelen ser la MISMA persona en el mismo
 * mostrador, así que el papel es ceremonia: se imprime, se lee de reojo y se tira. Pero "suelen" no
 * es "siempre" — un área con dos manos (una atiende el mostrador, otra prepara atrás) es exactamente
 * para lo que existe una comanda, y el KDS que la sustituiría es fase 2. Entre imprimir de más y
 * dejar a una cocina sin enterarse, esta tabla imprime: es el mismo criterio que
 * `.claude/rules/offline-first-y-hub-lan.md` §4.1a ("el fail-safe no puede ser no imprimir").
 * Lo correcto a futuro es una bandera por área ("esta área no necesita papel"), configurable por
 * quien conoce el local — no una constante escondida en el cliente.
 */
object AreaComandaPolicy {

    fun shouldPrint(mode: FulfillmentMode, moment: ComandaMoment): Boolean = when (mode) {
        FulfillmentMode.IMMEDIATE,
        FulfillmentMode.HOLD_UNTIL_PAID,
        -> moment == ComandaMoment.AREA_TICKET_ISSUED

        FulfillmentMode.PREPARE_ON_PAID -> moment == ComandaMoment.AREA_TICKET_PAID
    }
}

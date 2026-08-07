package com.avoqado.pos.pos.domain

/**
 * Upsell — grupo de control y identidad de las líneas aceptadas.
 *
 * Spec: Avoqado-HQ/specs/upsell-pantalla-cliente-2026-08-03.md (R5, C5)
 *
 * 🔴 Esto es un ESPEJO EXACTO de `isHoldout` en el server
 * (`src/services/upsell/upsellImpression.service.ts`). No es duplicación por
 * descuido: el POS tiene que poder sortear SIN RED, o el upsell dejaría de
 * funcionar justo cuando más falta hace.
 *
 * Y por eso hay vectores compartidos (`upsell-test-vectors.json`): si Kotlin
 * reparte distinto que TypeScript, nada truena — el reporte de aumento
 * simplemente compara dos poblaciones distintas y miente en silencio.
 */
object UpsellHoldout {

    /**
     * Sorteo DETERMINISTA por impressionId: el mismo id siempre cae del mismo
     * lado. A propósito no es aleatorio — así un reintento del mismo momento no
     * cambia de grupo a media venta, y el reparto es auditable.
     */
    fun isHoldout(impressionId: String, percent: Int): Boolean = bucket(impressionId) < percent

    /** Expuesto sólo para poder verificar la paridad con el server. */
    fun bucket(impressionId: String): Int {
        // FNV-1a de 32 bits. `Int` en Kotlin ya se desborda igual que Math.imul
        // en JS; lo único que hay que replicar es leerlo SIN SIGNO al final,
        // que es lo que hace el `>>> 0` del server.
        var hash = 0x811c9dc5.toInt()
        for (ch in impressionId) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        return ((hash.toLong() and 0xFFFFFFFFL) % 100).toInt()
    }

    /**
     * Identidad de una línea aceptada. Determinista para que el server pueda
     * atarla a la venta REAL: el reporte de ingreso sale de las líneas cobradas,
     * nunca del monto que reportó el POS.
     *
     * Mismo patrón que `sync:<intentId>:<idx>` del reducer offline.
     */
    fun externalId(impressionId: String, index: Int): String = "upsell:$impressionId:$index"
}

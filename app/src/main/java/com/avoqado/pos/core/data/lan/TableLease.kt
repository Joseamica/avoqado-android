package com.avoqado.pos.core.data.lan

/**
 * Hub LAN offline (Fase 3, PREMIUM `OFFLINE_LAN_HUB`) — núcleo de LEASES DE MESA.
 *
 * Sin internet cada dispositivo es una isla: dos meseros pueden abrir la misma
 * mesa y el conflicto solo se descubre al reconectar (el server arbitra y uno
 * acaba en cuarentena). Con el hub, las tablets se siguen viendo entre sí por el
 * WiFi del local y el conflicto se PREVIENE en el momento.
 *
 * Este archivo es LÓGICA PURA a propósito: sin red, sin reloj propio (el `now`
 * entra por parámetro). Toda la corrección del sistema vive aquí y por eso se
 * puede probar entera sin dos dispositivos. El transporte (descubrimiento mDNS +
 * socket) es plomería encima de esto.
 *
 * Espejo EXACTO en avoqado-ios: Services/LAN/TableLease.swift.
 *
 * ── Por qué LEASE y no candado ─────────────────────────────────────────────
 * Un candado sin caducidad deja la mesa muerta si la tablet se queda sin
 * batería: nadie puede cobrarla hasta reiniciar el sistema. Es el modo de falla
 * que hace que los meseros odien el POS. Un lease caduca solo: si el dueño deja
 * de renovarlo, a los [DEFAULT_TTL_MILLIS] la mesa vuelve a estar libre.
 *
 * ── Por qué ÉPOCA (fencing token) ──────────────────────────────────────────
 * Juan toma la mesa 5 con época 7 y se queda sin señal. El lease caduca, Alberto
 * la toma con época 8. Juan REGRESA creyendo que sigue siendo el dueño. Sin la
 * época, Juan pisaría el trabajo de Alberto; con ella, sus escrituras llegan con
 * un número viejo y se rechazan. La época sube SIEMPRE, incluso cuando el lease
 * caduca — nunca se reinicia (patrón de Kleppmann).
 */

/** Un permiso con caducidad sobre una mesa. */
data class TableLease(
    val tableId: String,
    val holderDeviceId: String,
    val holderStaffId: String,
    val holderName: String,
    /** Monotónica POR MESA. Nunca baja, ni cuando el lease caduca. */
    val epoch: Long,
    val expiresAtMillis: Long,
) {
    fun isLiveAt(nowMillis: Long): Boolean = nowMillis < expiresAtMillis
}

/** Resultado de pedir/renovar un lease. */
sealed class LeaseResult {
    /** Concedido: [lease] es la verdad vigente y trae la época a usar. */
    data class Granted(val lease: TableLease) : LeaseResult()

    /** Otro dispositivo lo tiene VIVO. Se devuelve quién, para poder decirlo. */
    data class Denied(val holder: TableLease) : LeaseResult()

    /**
     * Llegó con una época vieja: el emisor cree que es dueño pero ya no lo es
     * (el caso del dispositivo zombi). [currentEpoch] es la vigente.
     */
    data class Stale(val currentEpoch: Long) : LeaseResult()
}

/**
 * Registro autoritativo de leases. Vive en el dispositivo ELECTO como árbitro.
 *
 * No es una fuente de verdad paralela al server: es un acelerador de
 * coordinación mientras no hay internet. Todo lo que pasa aquí se reproduce
 * después por el outbox y el server valida como siempre — en el peor caso
 * (el árbitro se equivoca) el server rechaza y cae en cuarentena, o sea que
 * volvemos al comportamiento que ya teníamos.
 */
class LeaseRegistry(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private val leases = mutableMapOf<String, TableLease>()

    /**
     * Mayor época vista por mesa, INCLUSO de leases ya caducados o liberados.
     * Separado de [leases] a propósito: si la época se reiniciara al caducar,
     * un dispositivo zombi con la época vieja volvería a parecer válido.
     */
    private val highestEpoch = mutableMapOf<String, Long>()

    /**
     * Pide la mesa. Se concede si está libre, si el lease anterior ya caducó, o
     * si quien pide YA es el dueño (renovación implícita: reintentar por una
     * respuesta perdida no debe quitarle la mesa a nadie).
     */
    fun acquire(
        tableId: String,
        deviceId: String,
        staffId: String,
        staffName: String,
        nowMillis: Long,
    ): LeaseResult {
        val current = leases[tableId]
        if (current != null && current.isLiveAt(nowMillis) && current.holderDeviceId != deviceId) {
            return LeaseResult.Denied(current)
        }
        val nextEpoch = (highestEpoch[tableId] ?: 0L) + 1
        val lease = TableLease(
            tableId = tableId,
            holderDeviceId = deviceId,
            holderStaffId = staffId,
            holderName = staffName,
            epoch = nextEpoch,
            expiresAtMillis = nowMillis + ttlMillis,
        )
        leases[tableId] = lease
        highestEpoch[tableId] = nextEpoch
        return LeaseResult.Granted(lease)
    }

    /**
     * Extiende el lease. Solo lo logra el dueño CON la época vigente: si llega
     * con una vieja es un zombi y se le dice [LeaseResult.Stale] para que suelte
     * la mesa en su UI en vez de seguir creyéndose dueño.
     */
    fun renew(tableId: String, deviceId: String, epoch: Long, nowMillis: Long): LeaseResult {
        val current = leases[tableId] ?: return LeaseResult.Stale(highestEpoch[tableId] ?: 0L)
        if (current.holderDeviceId != deviceId || current.epoch != epoch) {
            if (current.isLiveAt(nowMillis) && current.holderDeviceId != deviceId) {
                return LeaseResult.Denied(current)
            }
            return LeaseResult.Stale(current.epoch)
        }
        // Un lease ya caducado NO se renueva: para eso está acquire, que sube la
        // época. Renovar sobre un caducado dejaría pasar al zombi.
        if (!current.isLiveAt(nowMillis)) return LeaseResult.Stale(current.epoch)

        val renewed = current.copy(expiresAtMillis = nowMillis + ttlMillis)
        leases[tableId] = renewed
        return LeaseResult.Granted(renewed)
    }

    /**
     * Suelta la mesa (cerrar cuenta, salir del panel). Solo el dueño vigente.
     * La época NO se reinicia — el zombi sigue quedando fuera.
     */
    fun release(tableId: String, deviceId: String, epoch: Long): Boolean {
        val current = leases[tableId] ?: return false
        if (current.holderDeviceId != deviceId || current.epoch != epoch) return false
        leases.remove(tableId)
        return true
    }

    /** Leases vivos ahora — el plano los pinta como "Mesa de {mesero}". */
    fun activeLeases(nowMillis: Long): List<TableLease> = leases.values.filter { it.isLiveAt(nowMillis) }.sortedBy { it.tableId }

    /** ¿Puede [deviceId] escribir en esta mesa ahora mismo? */
    fun canWrite(tableId: String, deviceId: String, nowMillis: Long): Boolean {
        val current = leases[tableId] ?: return false
        return current.isLiveAt(nowMillis) && current.holderDeviceId == deviceId
    }

    companion object {
        /**
         * 30s: suficiente para sobrevivir un bache de WiFi sin soltar la mesa en
         * plena comanda, y corto para que una tablet muerta no la secuestre más
         * de medio minuto. El dueño renueva a un tercio del TTL.
         */
        const val DEFAULT_TTL_MILLIS = 30_000L
        const val RENEW_INTERVAL_MILLIS = DEFAULT_TTL_MILLIS / 3
    }
}

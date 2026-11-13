package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_CONCEDIDA
import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_ESPERAR
import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_OCUPADA

/**
 * Hub D3 (mesa declarada) — lo que el ÁRBITRO sabe de cada caja: en qué mesas dice estar y qué aperturas le concedió.
 *
 * Sin `lock` propio: sólo lo toca [LeaseServer], bajo el suyo. Todo se mide con el reloj MONOTÓNICO del árbitro (`ahora`):
 * un salto de la hora de pared no revive ni mata a nadie.
 *
 * - Se cree el mensaje más nuevo de cada caja, en orden `(gen, seq)`; uno igual o más viejo es `stale` y no cambia nada.
 * - Una mesa está OCUPADA para X si otra presencia VIVA (menos de 30 s) la declara, como mesa o como apertura concedida, o si un
 *   permiso viejo vivo de otro aparato la tiene (app vieja sobre este mismo árbitro).
 * - Calentamiento: al asumir el arbitraje las aperturas esperan a oír a las cajas esperadas, o 12 s.
 * - Una caja está «en presencia» mientras tenga fila (10 min), viva o no: sus acquire/renew/release viejos ya no valen.
 */
internal class Presencias {

    private class Presencia(
        val gen: Long,
        val seq: Long,
        val staffName: String,
        val mesas: Set<String>,
        val concedidas: Set<WireApertura>,
        val vistoEn: Long,
    ) {
        /** Al asumir el arbitraje cuenta como vencida, pero conserva su `(gen, seq)` para seguir ordenando. */
        var vencida = false

        fun vivaEn(ahora: Long) = !vencida && ahora - vistoEn < TTL_MS

        fun declara(mesa: String) = mesa in mesas || concedidas.any { it.tableId == mesa }
    }

    private val porCaja = mutableMapOf<String, Presencia>()
    private var asumido = false
    private var pendientes = emptySet<String>()
    private var desde = 0L

    fun asumirArbitraje(esperados: Set<String>, ahora: Long) {
        porCaja.values.forEach { it.vencida = true }
        pendientes = esperados
        desde = ahora
        asumido = true
    }

    /** Lo que concedió como árbitro tampoco vale después: un reenvío ya no lo reconfirma. */
    fun dejarArbitraje() {
        porCaja.values.forEach { it.vencida = true }
        asumido = false
        pendientes = emptySet()
    }

    fun enPresencia(caja: String, ahora: Long) = porCaja[caja]?.let { ahora - it.vistoEn < OLVIDO_MS } == true

    /**
     * La presencia viva de otra caja que declara [mesa] (si son varias, la de `deviceId` menor), con forma de permiso viejo:
     * `epoch = 0` y vence en hora de pared ([pared]) lo que le quede de sus 30 s.
     */
    fun comoPermiso(mesa: String, salvo: String?, ahora: Long, pared: Long): WireLease? =
        quienDeclara(mesa, salvo, ahora)?.let { (caja, p) ->
            WireLease(
                tableId = mesa,
                holderDeviceId = caja,
                holderStaffId = "",
                holderName = p.staffName,
                epoch = 0,
                expiresAtMillis = pared + TTL_MS - (ahora - p.vistoEn),
            )
        }

    /** Para `list`: una entrada por mesa que declara alguna presencia viva y no está ya en [yaListadas]. */
    fun comoPermisos(ahora: Long, pared: Long, yaListadas: Set<String>): List<WireLease> =
        porCaja.values.filter { it.vivaEn(ahora) }
            .flatMapTo(sortedSetOf<String>()) { p -> p.mesas + p.concedidas.map { it.tableId } }
            .minus(yaListadas)
            .mapNotNull { comoPermiso(it, null, ahora, pared) }

    /** [viejos]: los permisos vivos de [LeaseRegistry]; el de otro aparato también ocupa la mesa. */
    fun estado(r: LeaseRequest, ahora: Long, arbitro: String, viejos: List<TableLease>): LeaseResponse {
        val mesas = r.mesas.orEmpty()
        val aperturas = r.aperturas.orEmpty()
        val ids = mesas + aperturas.flatMap { listOf(it.id, it.tableId) }
        if (r.deviceId.isEmpty() || r.gen <= 0 || r.seq <= 0 || mesas.size > MAX_MESAS || aperturas.size > MAX_APERTURAS ||
            ids.any { it.isEmpty() || it.length > MAX_ID } || aperturas.distinctBy { it.id }.size != aperturas.size
        ) {
            return LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "Estado inválido")
        }

        // Antes de ordenar: una caja olvidada (10 min) vuelve a empezar con cualquier (gen, seq).
        porCaja.values.removeAll { ahora - it.vistoEn >= OLVIDO_MS }
        val anterior = porCaja[r.deviceId]
        if (anterior != null && (r.gen < anterior.gen || (r.gen == anterior.gen && r.seq <= anterior.seq))) {
            return LeaseResponse(status = LeaseProtocol.STATUS_STALE)
        }

        pendientes = pendientes - r.deviceId // oírla cuenta ANTES de decidir sus aperturas
        val conservadas = if (anterior?.vivaEn(ahora) == true) anterior.concedidas else emptySet()
        val resultados = aperturas.map { a ->
            if (a in conservadas) WireResultadoApertura(a.id, APERTURA_CONCEDIDA) else decidir(a, r.deviceId, ahora, viejos)
        }
        porCaja[r.deviceId] = Presencia(
            gen = r.gen,
            seq = r.seq,
            staffName = r.staffName,
            mesas = mesas.toSet(),
            concedidas = aperturas.filterIndexed { i, _ -> resultados[i].resultado == APERTURA_CONCEDIDA }.toSet(),
            vistoEn = ahora,
        )
        return LeaseResponse(status = LeaseProtocol.STATUS_OK, arbitro = arbitro, aperturas = resultados)
    }

    private fun decidir(a: WireApertura, caja: String, ahora: Long, viejos: List<TableLease>): WireResultadoApertura {
        val falta = CALENTAMIENTO_MS - (ahora - desde)
        if (asumido && pendientes.isNotEmpty() && falta > 0) return WireResultadoApertura(a.id, APERTURA_ESPERAR, esperarMs = falta)
        if (!asumido) return WireResultadoApertura(a.id, APERTURA_ESPERAR, esperarMs = NO_ASUMIDO_MS)
        val quien = viejos.firstOrNull { it.tableId == a.tableId && it.holderDeviceId != caja }?.holderName
            ?: quienDeclara(a.tableId, caja, ahora)?.value?.staffName
        return if (quien != null) {
            WireResultadoApertura(a.id, APERTURA_OCUPADA, quien = quien)
        } else {
            WireResultadoApertura(a.id, APERTURA_CONCEDIDA)
        }
    }

    private fun quienDeclara(mesa: String, salvo: String?, ahora: Long): Map.Entry<String, Presencia>? =
        porCaja.entries.filter { (caja, p) -> caja != salvo && p.vivaEn(ahora) && p.declara(mesa) }.minByOrNull { it.key }

    private companion object {
        const val TTL_MS = LeaseRegistry.DEFAULT_TTL_MILLIS
        const val CALENTAMIENTO_MS = 12_000L
        const val OLVIDO_MS = 600_000L
        const val NO_ASUMIDO_MS = 1_000L
        const val MAX_MESAS = 50
        const val MAX_APERTURAS = 20
        const val MAX_ID = 64
    }
}

package com.avoqado.pos.tables.presentation

import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.sync.FilaDeLaOrden
import com.avoqado.pos.core.data.sync.SyncIntentTypes
import com.avoqado.pos.pos.data.model.CartItem

/** 9b (`hz:e5ee98bc:D13`): la mesa se cobró sin internet en esta tablet y su efectivo espera en la cola. */
internal const val CUENTA_COBRADA_AQUI =
    "Esta cuenta ya está pagada: falta subirla. Se sube sola cuando vuelva la conexión; no la cobres otra vez."
internal const val SALDO_NO_RECUPERADO =
    "No se pudo recuperar el saldo completo de esta cuenta. Conéctate y reabre la cuenta antes de cobrar; tus productos siguen guardados."
internal const val CUENTA_SE_ACTUALIZA = "La cuenta se está actualizando. Intenta en un momento."
internal const val CAMBIOS_SIN_SINCRONIZAR = "Esta mesa tiene cambios sin sincronizar: cóbrala cuando se sincronicen."

internal sealed interface SaldoSinRed {
    data class Conocido(val rondasCents: Int, val cobradoCents: Int) : SaldoSinRed
    data class EnGris(val motivo: String) : SaldoSinRed
}

/**
 * D5 · B2a (7b): el saldo de una mesa abierta SIN internet, rearmado de disco cuando la app se reinició. Todo es de esta
 * tablet: el servidor nunca la vio. Rondas del borrador cuya fila sigue en la cola, valoradas como al mandarlas
 * ([valorRondaSinRedComprobado]), y el efectivo que sigue en la cola. Lo que no se puede asegurar queda en gris con su razón.
 */
internal fun saldoDeMesaProvisional(rondas: Map<String, List<CartItem>>, filas: List<FilaDeLaOrden>): SaldoSinRed {
    val enCamino = setOf(SyncIntentEntity.STATUS_PENDING, SyncIntentEntity.STATUS_HELD)
    val tipos = setOf(SyncIntentTypes.OPEN_TABLE, SyncIntentTypes.ADD_ITEMS, SyncIntentTypes.PAY_CASH)
    // Dividir, juntar, mover, descuentos, cancelar o un rechazo: la cuenta ya no es la suma de lo que le registró esta tablet.
    if (filas.any { it.estado !in enCamino || it.tipo !in tipos }) return SaldoSinRed.EnGris(CAMBIOS_SIN_SINCRONIZAR)
    // Sin su apertura en la cola, el servidor ya pudo conocerla (B2b): espera al internet.
    if (filas.none { it.tipo == SyncIntentTypes.OPEN_TABLE }) return SaldoSinRed.EnGris(SALDO_NO_RECUPERADO)
    val rondasEnCola = filas.filter { it.tipo == SyncIntentTypes.ADD_ITEMS }.map { it.id }.toSet()
    // Una ronda en la cola sin su borrador no tiene precio que sumar.
    if (!rondas.keys.containsAll(rondasEnCola)) return SaldoSinRed.EnGris(SALDO_NO_RECUPERADO)
    // Un borrador sin fila (la app murió antes de encolarlo) se vuelve a encolar solo al abrir la cuenta (Codex r4 #3).
    if (!rondasEnCola.containsAll(rondas.keys)) return SaldoSinRed.EnGris(CUENTA_SE_ACTUALIZA)
    return try {
        SaldoSinRed.Conocido(
            rondasCents = rondas.values.fold(0) { total, items -> Math.addExact(total, valorRondaSinRedComprobado(items)) },
            cobradoCents = filas.filter { it.tipo == SyncIntentTypes.PAY_CASH }
                .fold(0) { total, fila -> Math.addExact(total, requireNotNull(fila.importeCents).also { require(it >= 0) }) },
        )
    } catch (_: ArithmeticException) { SaldoSinRed.EnGris(SALDO_NO_RECUPERADO) }
      catch (_: IllegalArgumentException) { SaldoSinRed.EnGris(SALDO_NO_RECUPERADO) }
}

package com.avoqado.pos.tables.presentation

import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.sync.FilaDeLaOrden
import com.avoqado.pos.core.data.sync.SyncIntentTypes
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.core.util.aCentavos

/** 9b (`hz:e5ee98bc:D13`): la mesa se cobró sin internet en esta tablet y su efectivo espera en la cola. */
internal const val CUENTA_COBRADA_AQUI =
    "Esta cuenta ya está pagada: falta subirla. Se sube sola cuando vuelva la conexión; no la cobres otra vez."
internal const val SALDO_NO_RECUPERADO =
    "No se pudo recuperar el saldo completo de esta cuenta. Conéctate y reabre la cuenta antes de cobrar; tus productos siguen guardados."
internal const val CUENTA_SE_ACTUALIZA = "La cuenta se está actualizando. Intenta en un momento."
internal const val CAMBIOS_SIN_SINCRONIZAR = "Esta mesa tiene cambios sin sincronizar: cóbrala cuando se sincronicen."
/** D45: un cobro de la cuenta que ESTE aparato guardó en la cola de COBROS y el SERVIDOR todavía no cuenta (la regla de la bandeja). */
internal const val MESA_COBRO_SIN_SINCRONIZAR = "Esta mesa tiene un cobro sin sincronizar: cóbrala cuando se sincronice."
internal const val MESA_COBRO_POR_REVISAR =
    "Esta mesa tiene un cobro por revisar: un gerente lo resuelve en Operaciones por revisar antes de cobrarla otra vez."
internal const val MESA_COBROS_SIN_LEER = "No se pudo revisar si esta mesa tiene cobros sin sincronizar. Intenta de nuevo."

internal sealed interface SaldoSinRed {
    /** [hayCobro]: hay al menos un `PAY_CASH` de la orden en la cola (también un cierre de $0). */
    data class Conocido(val rondasCents: Int, val cobradoCents: Int, val hayCobro: Boolean) : SaldoSinRed {
        val porPagarCents: Int get() = Math.subtractExact(rondasCents, cobradoCents).coerceAtLeast(0)
    }
    data class EnGris(val motivo: String) : SaldoSinRed
}

/**
 * D5 · B2a (7b): el saldo de una mesa abierta SIN internet, leído de disco. Es el ÚNICO número con el que se cobra una mesa
 * provisional (se reinicie o no la app): el que se lleva en memoria no decide. Todo es de esta tablet: el servidor nunca la vio. Rondas del borrador cuya fila sigue en la cola, valoradas como al mandarlas
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
    val cobros = filas.filter { it.tipo == SyncIntentTypes.PAY_CASH }
    return try {
        SaldoSinRed.Conocido(
            rondasCents = rondas.values.fold(0) { total, items -> Math.addExact(total, valorRondaSinRedComprobado(items)) },
            cobradoCents = cobros.fold(0) { total, fila -> Math.addExact(total, requireNotNull(fila.importeCents).also { require(it >= 0) }) },
            hayCobro = cobros.isNotEmpty(),
        )
    } catch (_: ArithmeticException) { SaldoSinRed.EnGris(SALDO_NO_RECUPERADO) }
      catch (_: IllegalArgumentException) { SaldoSinRed.EnGris(SALDO_NO_RECUPERADO) }
}

// MARK: - D5 · B2b (piloto `MESAS_SIN_RED`): mesa del SERVIDOR cobrada sin internet en la tablet que la tomó

internal const val TOMADA_EN_OTRA_TABLET = "Esta mesa la tomó otra tablet: cóbrala ahí o cuando vuelva el internet."
internal const val SIN_CUENTA_COMPLETA =
    "Esta tablet no alcanzó a ver la cuenta completa antes de quedarse sin internet: cóbrala cuando vuelva la conexión."
/** Sin foto, con efectivo de esta tablet en la cola: no se sabe si cubrió todo, así que no se ofrece cobrar otra vez. */
internal const val COBRO_AQUI_SIN_SUBIR =
    "Ya se cobró aquí y falta subirlo. Se sube solo cuando vuelva la conexión; si falta algo, cóbralo entonces."

/** B3 (visto 1): la cuenta de la pantalla ya se cerró (su cobro y su liberación subieron). */
internal const val CUENTA_CERRADA = "Esta cuenta ya se cerró. Regresa al plano."
/** B3 C7: una cuenta en $0 (cortesía total) sin internet. */
internal const val CUENTA_EN_CERO_SIN_RED = "Esta cuenta está en \$0.00: ciérrala cuando vuelva la conexión."

/** (c) La cuenta es simple: sin abonos, sin descuento de cuenta, sin cargo por servicio, sin IVA aparte y abierta. Null = simple. */
internal fun motivoCuentaNoSimple(d: com.avoqado.pos.tables.data.OrderDetail): String? {
    fun sinInternet(que: String) = "Esta cuenta tiene $que: sin internet no se cobra aquí. Cóbrala cuando vuelva la conexión."
    return when {
        d.status in setOf("CANCELLED", "COMPLETED", "DELETED") || d.paymentStatus == "PAID" -> "Esta cuenta ya está cerrada en el servidor."
        d.payments.isNotEmpty() || (d.cobrosConSaldo ?: 0) > 0 || (d.preBillBalance?.totalPaidCents ?: 0) != 0 || d.esCuentaDeTarjetas -> sinInternet("abonos")
        d.discounts.isNotEmpty() || d.discountAmount != 0.0 -> sinInternet("descuento de cuenta")
        d.serviceCharges.isNotEmpty() || d.serviceChargeAmount != 0.0 -> sinInternet("cargo por servicio")
        d.addedTaxAmount != 0.0 -> sinInternet("IVA aparte")
        else -> null
    }
}

/**
 * D5 · B2b (decisión A, Codex b2b-r2 #1): la huella de una cuenta leída. Con la versión de la orden (el servidor la sube en cada
 * cambio), `id#v<versión>`; sin ella, id + total + pagado + renglones ordenados, en centavos. Una relectura idéntica da la misma.
 */
internal fun huellaDeCuenta(d: com.avoqado.pos.tables.data.OrderDetail): String = d.version?.let { "${d.id}#v$it" } ?: buildString {
    fun c(x: Double) = Math.round(x * 100)
    append(d.id).append('|').append(c(d.total)).append('|').append(d.payments.sumOf { c(it.amount) })
    d.items.sortedBy { it.id }.forEach { append('|').append(it.id).append(':').append(it.quantity).append(':').append(c(it.total)) }
}

/**
 * D5 · B2b: el saldo sin red de una mesa del servidor = FOTO limpia + rondas propias posteriores (valoradas con su borrador) −
 * efectivo propio posterior, en cualquier estado de la cola salvo rechazo. Todo lo que no se puede asegurar queda en gris con su
 * razón (promesa v5 y R5-1). [filas]: las de la orden posteriores a la foto (cualquier estado) más las sin resolver, con `seq`.
 */
internal fun saldoDeMesaDelServidor(
    foto: com.avoqado.pos.core.data.sync.FotoDeMesa?,
    rondas: Map<String, List<CartItem>>,
    filas: List<FilaDeLaOrden>,
    recuperando: Boolean,
    tomadaAqui: Boolean,
    ultimaCuenta: com.avoqado.pos.tables.data.OrderDetail?,
    cobroFueraDeLaCola: Boolean,
): SaldoSinRed {
    val enCamino = setOf(SyncIntentEntity.STATUS_PENDING, SyncIntentEntity.STATUS_HELD)
    val cuentan = enCamino + SyncIntentEntity.STATUS_ACKED
    val dinero = setOf(SyncIntentTypes.ADD_ITEMS, SyncIntentTypes.PAY_CASH)
    // Un rechazo, otro cambio sin subir (descuento, dividir, juntar…) o un cobro en otra cola: la cuenta ya no es foto + lo propio.
    if (filas.any { it.estado == SyncIntentEntity.STATUS_REJECTED || (it.estado in enCamino && it.tipo !in dinero) } || cobroFueraDeLaCola)
        return SaldoSinRed.EnGris(CAMBIOS_SIN_SINCRONIZAR)
    if (foto == null) return SaldoSinRed.EnGris(when {
        filas.any { it.tipo == SyncIntentTypes.PAY_CASH && it.estado in enCamino } -> COBRO_AQUI_SIN_SUBIR
        !tomadaAqui -> TOMADA_EN_OTRA_TABLET
        else -> ultimaCuenta?.let(::motivoCuentaNoSimple) ?: SIN_CUENTA_COMPLETA
    })
    if (!tomadaAqui) return SaldoSinRed.EnGris(TOMADA_EN_OTRA_TABLET)
    // La foto sólo vale si es la última cuenta que esta tablet leyó con éxito: si después leyó otra (un descuento del panel) y no
    // pudo borrar la foto, aquí no se usa (no depende del borrado).
    if (ultimaCuenta == null || huellaDeCuenta(ultimaCuenta) != huellaDeCuenta(foto.detalle))
        return SaldoSinRed.EnGris(ultimaCuenta?.let(::motivoCuentaNoSimple) ?: SIN_CUENTA_COMPLETA)
    if (recuperando) return SaldoSinRed.EnGris(CUENTA_SE_ACTUALIZA)
    val (posteriores, previas) = filas.partition { it.seq > foto.seq }
    // Algo de antes de la foto sigue sin subir: la foto no lo contiene y no se sabe cuánto suma.
    if (previas.any { it.estado in enCamino }) return SaldoSinRed.EnGris(CAMBIOS_SIN_SINCRONIZAR)
    val renglones = foto.detalle.items.mapNotNull { it.externalId }
    fun enLaFoto(llave: String) = renglones.any { it.startsWith("sync:$llave:") }
    val rondasPosteriores = posteriores.filter { it.tipo == SyncIntentTypes.ADD_ITEMS && it.estado in cuentan }
    // Una ronda posterior sin borrador, vieja (re-encolada, A3) o ya presente en la foto no se puede sumar con certeza.
    if (rondasPosteriores.any { it.id !in rondas || it.id in foto.llavesPrevias || enLaFoto(it.id) }) return SaldoSinRed.EnGris(SALDO_NO_RECUPERADO)
    // Codex r4 #3: una ronda del borrador sin fila (la app murió antes de encolarla) aún no suma. La que el gerente marcó resuelta ya
    // no está en el borrador (D29).
    val conFila = filas.map { it.id }.toSet()
    if (rondas.keys.any { it !in conFila && !enLaFoto(it) }) return SaldoSinRed.EnGris(CUENTA_SE_ACTUALIZA)
    val cobros = posteriores.filter { it.tipo == SyncIntentTypes.PAY_CASH && it.estado in cuentan }
    return try {
        SaldoSinRed.Conocido(
            rondasCents = rondasPosteriores.fold(foto.detalle.total.aCentavos().also { require(it >= 0) }) { total, fila ->
                Math.addExact(total, valorRondaSinRedComprobado(rondas.getValue(fila.id)))
            },
            cobradoCents = cobros.fold(0) { total, fila -> Math.addExact(total, requireNotNull(fila.importeCents).also { require(it >= 0) }) },
            hayCobro = cobros.isNotEmpty(),
        )
    } catch (_: ArithmeticException) { SaldoSinRed.EnGris(SALDO_NO_RECUPERADO) }
      catch (_: IllegalArgumentException) { SaldoSinRed.EnGris(SALDO_NO_RECUPERADO) }
}

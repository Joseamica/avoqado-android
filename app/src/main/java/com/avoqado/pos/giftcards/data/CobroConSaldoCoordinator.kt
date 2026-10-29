package com.avoqado.pos.giftcards.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.giftcards.domain.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

sealed interface DesenlaceDeCobro {
    data class Cobrado(val cobro: CobroConSaldo) : DesenlaceDeCobro
    data class SaldoInsuficiente(val saldoDisponible: Double, val texto: String) : DesenlaceDeCobro
    data class Rechazado(val texto: String, val volverAIdentificar: Boolean = false) : DesenlaceDeCobro
    data class Reintentable(val texto: String, val retryAfterSeg: Int?) : DesenlaceDeCobro
    data class NoSalio(val texto: String) : DesenlaceDeCobro
    data object Incierto : DesenlaceDeCobro
}
enum class Recuperacion { RECUPERADA, CARRITO_OCUPADO, NO_SE_PUDO_SOLTAR, PENDIENTE_DE_CONFIRMAR }

@Singleton class CobroConSaldoCoordinator @Inject constructor(
    private val repo: GiftCardsRepository,
    private val store: SolicitudesDeTarjetaStore,
    private val secureStorage: SecureStorage,
    private val cobrosLocales: CobrosLocalesDeLaCuenta,
) {
    private val mutex = Mutex()
    private val entregasPendientes = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    private val destinosDeEntrega = mutableMapOf<String, () -> Boolean>()
    val pendienteDeConfirmar = entregasPendientes.asStateFlow()
    fun entregaPendienteDelNegocio(): String? = entregasPendientes.value.entries.firstOrNull { it.value == secureStorage.venueId }?.key
    fun confirmarEntrega(llave: String): Boolean {
        val venue = entregasPendientes.value[llave] ?: return false
        if (venue != secureStorage.venueId) return false
        if (destinosDeEntrega[llave]?.invoke() != true) {
            val s = SolicitudesDeTarjeta.reservas(secureStorage.giftCardRequestsJson)
                .firstOrNull { it.llave == llave && it.venueId == venue } ?: return false
            store.devolverEntrega(s)
            destinosDeEntrega.remove(llave); entregasPendientes.value -= llave
            return false
        }
        if (!store.confirmarEntrega(llave)) return false
        destinosDeEntrega.remove(llave); entregasPendientes.value -= llave
        return true
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val porRecuperar = store.solicitudes.map { rows -> rows.filter { it.venueId == secureStorage.venueId && it.porRecuperar } }
    val porTerminar = store.solicitudes.map { rows -> rows.filter { it.venueId == secureStorage.venueId && it.porTerminar } }
    fun cuentaTrabada(orderId: String?): Boolean = orderId != null && secureStorage.venueId?.let { venue ->
        store.delNegocio(venue).any { it.tipo == "GIFT_CARD_REDEEM" && it.orderId == orderId && it.mandado && !it.porTerminar && !it.porRecuperar }
    } == true

    suspend fun cobrar(venueId: String, orderId: String, prueba: PruebaDeTarjeta, montoCents: Int,
        propinaCents: Int, foto: FotoDeLaVenta? = null, vaciarCarrito: (() -> Unit)? = null,
        deMostrador: Boolean = foto != null): DesenlaceDeCobro = exclusivo {
        if (secureStorage.venueId != venueId || montoCents <= 0 || propinaCents < 0) return@exclusivo DesenlaceDeCobro.NoSalio(TextosDeTarjetas.SIN_RED)
        if (cuentaTrabada(orderId)) return@exclusivo DesenlaceDeCobro.NoSalio(TextosDeTarjetas.COMPROBANDO_COBRO)
        if (cobrosLocales.hayPendiente(venueId, orderId)) return@exclusivo DesenlaceDeCobro.NoSalio(TextosDeTarjetas.COBRO_SIN_SINCRONIZAR)
        val llave = UUID.randomUUID().toString()
        val cuerpo = cuerpo(llave, prueba, montoCents, propinaCents, JsonObject(emptyMap()))
        val s = SolicitudDeTarjeta(tipo = "GIFT_CARD_REDEEM", llave = llave, venueId = venueId,
            creadaEn = System.currentTimeMillis(), orderId = orderId, cuerpo = cuerpo,
            foto = foto?.let { json.encodeToString(FotoDeLaVenta.serializer(), it) }, deMostrador = deMostrador,
            iniciadoPor = secureStorage.userId, iniciadoPorNombre = nombreDelAutor())
        preparar(s, vaciarCarrito, null)
    }

    suspend fun cobrarNuevaCuenta(venueId: String, cuentaJson: JsonObject, foto: FotoDeLaVenta,
        prueba: PruebaDeTarjeta, montoCents: Int, propinaCents: Int, vaciarCarrito: () -> Unit,
        regresarAlCarrito: (FotoDeLaVenta) -> Boolean): DesenlaceDeCobro {
        return cobrarNuevaCuenta(venueId, cuentaJson, foto, prueba, montoCents, propinaCents, vaciarCarrito, { true }, regresarAlCarrito)
    }

    suspend fun cobrarNuevaCuenta(venueId: String, cuentaJson: JsonObject, foto: FotoDeLaVenta,
        prueba: PruebaDeTarjeta, montoCents: Int, propinaCents: Int, vaciarCarrito: () -> Unit,
        destinoVigente: () -> Boolean, regresarAlCarrito: (FotoDeLaVenta) -> Boolean): DesenlaceDeCobro = exclusivo {
        if (secureStorage.venueId != venueId || montoCents <= 0 || propinaCents < 0) return@exclusivo DesenlaceDeCobro.NoSalio(TextosDeTarjetas.SIN_RED)
        val llave = UUID.randomUUID().toString()
        val s = SolicitudDeTarjeta(tipo = "GIFT_CARD_REDEEM", llave = llave, venueId = venueId,
            creadaEn = System.currentTimeMillis(), cuerpo = cuerpo(llave, prueba, montoCents, propinaCents, cuentaJson),
            nuevaCuenta = true, foto = json.encodeToString(FotoDeLaVenta.serializer(), foto), deMostrador = true,
            iniciadoPor = secureStorage.userId, iniciadoPorNombre = nombreDelAutor())
        preparar(s, vaciarCarrito, regresarAlCarrito, destinoVigente)
    }

    private fun nombreDelAutor() = listOfNotNull(secureStorage.userFirstName, secureStorage.userLastName).joinToString(" ")

    private fun cuerpo(llave: String, prueba: PruebaDeTarjeta, monto: Int, propina: Int, cuenta: JsonObject): String = buildJsonObject {
        cuenta.filterKeys { it !in setOf("externalId", "tableId", "isOfflineReplay") }.forEach { (k,v) -> put(k,v) }
        put("idempotencyKey", llave); put("proof", prueba.aJson())
        put("amount", monto / 100.0); put("tip", propina / 100.0)
        secureStorage.userId?.let { put("iniciadoPor", it) }
        nombreDelAutor().takeIf { it.isNotBlank() }?.let { put("iniciadoPorNombre", it) }
    }.toString()

    private suspend fun preparar(s: SolicitudDeTarjeta, vaciar: (() -> Unit)?, entregar: ((FotoDeLaVenta) -> Boolean)?, destinoVigente: () -> Boolean = { true }): DesenlaceDeCobro {
        if (secureStorage.venueId != s.venueId) return DesenlaceDeCobro.NoSalio(TextosDeTarjetas.SIN_RED)
        if (!store.enVueloDeEsteProceso.add(s.llave)) return DesenlaceDeCobro.Incierto
        try {
            if (!store.guardar(s)) return DesenlaceDeCobro.NoSalio(TextosDeTarjetas.NO_SE_PUDO_GUARDAR)
            if (!store.marcarMandado(s.llave)) {
                store.quitar(s.llave)
                return DesenlaceDeCobro.NoSalio(TextosDeTarjetas.NO_SE_PUDO_PREPARAR_EL_COBRO)
            }
            vaciar?.invoke()
            return enviar(s.copy(mandado = true), entregar, destinoVigente = destinoVigente)
        } finally { store.enVueloDeEsteProceso.remove(s.llave) }
    }

    suspend fun resolver(llave: String, destinoVigente: () -> Boolean = { true },
        entregar: ((FotoDeLaVenta) -> Boolean)? = null): DesenlaceDeCobro = exclusivo {
        val venue = secureStorage.venueId ?: return@exclusivo DesenlaceDeCobro.Incierto
        if (!store.enVueloDeEsteProceso.add(llave)) return@exclusivo DesenlaceDeCobro.Incierto
        try {
            val s = store.delNegocio(venue).firstOrNull { it.llave == llave && it.tipo == "GIFT_CARD_REDEEM" }
                ?: return@exclusivo DesenlaceDeCobro.Incierto
            if (!s.mandado || s.porTerminar || s.porRecuperar) return@exclusivo DesenlaceDeCobro.Incierto
            consultar(s, entregar, destinoVigente)
        } finally { store.enVueloDeEsteProceso.remove(llave) }
    }

    private suspend fun consultar(s: SolicitudDeTarjeta, entregar: ((FotoDeLaVenta) -> Boolean)?,
        destinoVigente: () -> Boolean): DesenlaceDeCobro = when (val r = repo.recuperarCobro(s.venueId, s.llave)) {
        is Respuesta.Ok -> r.valor?.let { confirmar(s,it) } ?: enviar(s, entregar, destinoVigente = destinoVigente)
        is Respuesta.Rechazo -> if (r.fallo.http == 404) enviar(s, entregar, destinoVigente = destinoVigente) else DesenlaceDeCobro.Incierto
        Respuesta.SinRespuesta -> DesenlaceDeCobro.Incierto
    }

    private suspend fun enviar(s: SolicitudDeTarjeta, entregar: ((FotoDeLaVenta) -> Boolean)?, consultarReuso: Boolean = true, destinoVigente: () -> Boolean = { true }): DesenlaceDeCobro {
        repeat(3) { intento ->
            // La sesión que manda es la actual; el cuerpo sigue siendo el del primer guardado (Q110).
            if (secureStorage.venueId != s.venueId || !secureStorage.isLoggedIn) return DesenlaceDeCobro.Incierto
            if (System.currentTimeMillis() < (store.reenviarDesde(s.llave) ?: 0L)) return DesenlaceDeCobro.Incierto
            val r = if (s.nuevaCuenta) repo.cobrarNuevaCuenta(s.venueId, s.cuerpo)
                else s.orderId?.let { repo.cobrar(s.venueId, it, s.cuerpo) } ?: return DesenlaceDeCobro.Incierto
            when (r) {
                is Respuesta.Ok -> return confirmar(s, r.valor)
                Respuesta.SinRespuesta -> return DesenlaceDeCobro.Incierto
                is Respuesta.Rechazo -> {
                    val f = r.fallo
                    if (f.code == "IDEMPOTENCY_KEY_REUSED") {
                        if (!consultarReuso) return DesenlaceDeCobro.Incierto
                        return when (val hallado = repo.recuperarCobro(s.venueId, s.llave)) {
                            is Respuesta.Ok -> hallado.valor?.let { confirmar(s,it) } ?: enviar(s, entregar, false, destinoVigente)
                            else -> DesenlaceDeCobro.Incierto
                        }
                    }
                    if (f.code == "ORDER_PAYMENT_CONFLICT" && intento < 2) return@repeat
                    if (f.http == 429 && !store.aplazarReenvio(s.llave,
                        System.currentTimeMillis() + (f.retryAfterSeg ?: 0).coerceAtLeast(0) * 1000L)) return DesenlaceDeCobro.Incierto
                    if (esReintentable(f.http, f.code) && f.code != "ORDER_PAYMENT_CONFLICT")
                        return DesenlaceDeCobro.Reintentable(texto(f), f.retryAfterSeg)
                    val rechazo = if (f.code == "GIFT_CARD_INSUFFICIENT_BALANCE") DesenlaceDeCobro.SaldoInsuficiente(f.saldoDisponible ?: 0.0, texto(f))
                        else DesenlaceDeCobro.Rechazado(texto(f), f.code in setOf("GIFT_CARD_PHONE_PROOF_INVALID", "GIFT_CARD_PROOF_EXPIRED", "GIFT_CARD_PROOF_INVALID"))
                    if (s.nuevaCuenta && s.orderId == null) {
                        if (entregar == null || secureStorage.venueId != s.venueId) store.dejarPorRecuperar(s.llave)
                        else if (soltarYEntregar(s, { true }, entregar, destinoVigente) in setOf(Recuperacion.NO_SE_PUDO_SOLTAR, Recuperacion.PENDIENTE_DE_CONFIRMAR))
                            return DesenlaceDeCobro.Rechazado(TextosDeTarjetas.NO_SE_PUDO_RECUPERAR)
                    } else if (s.deMostrador && s.orderId != null) store.dejarPorTerminar(s.llave, s.orderId, s.orderNumber, null)
                    else store.quitar(s.llave)
                    return rechazo
                }
            }
        }
        return DesenlaceDeCobro.Incierto
    }

    private fun confirmar(s: SolicitudDeTarjeta, r: CobroConSaldo): DesenlaceDeCobro {
        if (s.deMostrador && r.orderRemaining > 0) {
            store.dejarPorTerminar(s.llave, r.orderId, r.orderNumber, (r.orderRemaining * 100).roundToInt())
        } else store.quitar(s.llave)
        return DesenlaceDeCobro.Cobrado(r)
    }
    suspend fun recuperarVenta(llave: String, carritoVacio: () -> Boolean,
        restaurar: (FotoDeLaVenta) -> Boolean): Recuperacion = recuperarVenta(llave, carritoVacio, { true }, restaurar)
    suspend fun recuperarVenta(llave: String, carritoVacio: () -> Boolean,
        destinoVigente: () -> Boolean, restaurar: (FotoDeLaVenta) -> Boolean): Recuperacion {
        if (!mutex.tryLock()) return Recuperacion.NO_SE_PUDO_SOLTAR
        return try { withContext(NonCancellable) {
            val s = secureStorage.venueId?.let { v -> store.delNegocio(v).firstOrNull { it.llave == llave && it.porRecuperar && it.nuevaCuenta && it.orderId == null } }
                ?: return@withContext Recuperacion.NO_SE_PUDO_SOLTAR
            soltarYEntregar(s, carritoVacio, restaurar, destinoVigente)
        } } finally { mutex.unlock() }
    }
    private fun soltarYEntregar(s: SolicitudDeTarjeta, vacio: () -> Boolean, entregar: (FotoDeLaVenta) -> Boolean, destinoVigente: () -> Boolean): Recuperacion {
        val foto = s.foto?.let { runCatching { json.decodeFromString(FotoDeLaVenta.serializer(), it) }.getOrNull() }
            ?: return Recuperacion.NO_SE_PUDO_SOLTAR
        if (!vacio()) { store.dejarPorRecuperar(s.llave); return Recuperacion.CARRITO_OCUPADO }
        if (!store.reservarEntrega(s.llave)) { store.dejarPorRecuperar(s.llave); return Recuperacion.NO_SE_PUDO_SOLTAR }
        if (!entregar(foto)) {
            store.devolverEntrega(s)
            return Recuperacion.CARRITO_OCUPADO
        }
        if (!store.confirmarEntrega(s.llave)) {
            destinosDeEntrega[s.llave] = destinoVigente
            entregasPendientes.value += s.llave to s.venueId
            return Recuperacion.PENDIENTE_DE_CONFIRMAR
        }
        return Recuperacion.RECUPERADA
    }
    private suspend fun exclusivo(bloque: suspend () -> DesenlaceDeCobro): DesenlaceDeCobro {
        if (!mutex.tryLock()) return DesenlaceDeCobro.NoSalio(TextosDeTarjetas.COMPROBANDO_COBRO)
        return try { withContext(NonCancellable) { bloque() } } finally { mutex.unlock() }
    }
    private fun texto(f: FalloDeTarjeta): String = when {
        f.http == 403 -> TextosDeTarjetas.SIN_PERMISO_COBRAR
        f.code == null && f.http == 400 && f.message == "Order is already paid" -> TextosDeTarjetas.CUENTA_YA_PAGADA
        f.code == null && f.http == 404 -> TextosDeTarjetas.CUENTA_NO_ENCONTRADA
        else -> f.message ?: TextosDeTarjetas.SIN_RED
    }
    companion object {
        // Contrato 1b Q94: sólo red/5xx, 429 y los dos conflictos nombrados conservan llave.
        fun esReintentable(status: Int?, code: String?): Boolean = status == null || status >= 500 || status == 429 ||
            code in setOf("ORDER_PAYMENT_CONFLICT", "ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE")
    }
}

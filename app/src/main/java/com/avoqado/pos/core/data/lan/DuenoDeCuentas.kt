package com.avoqado.pos.core.data.lan

import android.os.SystemClock
import android.util.Log
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.orders.data.CuentasAbiertasAqui
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * D12 · Opción D — ESTA caja como DUEÑA de las cuentas que abrió (`CuentasAbiertasAqui`): guarda su [Registro] en disco, por sucursal,
 * y contesta a las demás cajas y a sus propios cobros. Por una cuenta que no abrió contesta `null` («no es mía»).
 *
 * - **Disco:** `PayloadCache.saveDurable` (`TYPE_REGISTRO_DE_CUENTAS`). Si lo guardado no se puede leer NO se escribe encima y no se
 *   concede nada (falla cerrado). Todo cambio queda escrito ANTES de contestar. Una cuenta cuya marca «abierta aquí» ya se podó sale del
 *   registro (ya no hay dueño: nadie concede, gris en las demás; tabla exhaustiva de `opcion-d.md`).
 * - **R4:** LIBRE sólo si el registro lo dice Y esta caja no tiene dinero durable de esa cuenta ([dineroDeLaCuenta]); si lo tiene, contesta
 *   «cobrada aquí, falta subirla» sin escribirlo (puede resolverse como no cobrado).
 * - **R2:** lo propio se reconcilia ANTES de contestar: un COMPROMETIDA(aquí) cuyo intento ya no vive en este proceso pasa a CERRADA si su
 *   fila `pending_payments` existe (con «falta subirla» mientras no sea SYNCED), se queda si hay una tarjeta en curso de la cuenta, y si no
 *   hay nada, se suelta.
 */
@Singleton
class DuenoDeCuentas internal constructor(
    private val cache: PayloadCache,
    private val abiertas: CuentasAbiertasAqui,
    /**
     * R4: cobro local pendiente (efectivo, regalo, cola), tarjeta en duda o cobrada, o marca de cobro aún sin aplicar a la copia. [enLinea]
     * (I4): quien pregunta cobra con internet: lo que el SERVIDOR ya contestó (FAILED) no cuenta.
     */
    private val dineroDeLaCuenta: suspend (venueId: String, orderId: String, enLinea: Boolean) -> Boolean,
    /** ¿Una tarjeta de esta caja sigue en curso o en duda para esa cuenta? (su lista durable). */
    private val tarjetaEnCurso: (venueId: String, orderId: String) -> Boolean,
    /** El `syncStatus` de la fila `pending_payments` de esa llave, o null si no existe. */
    private val estadoDeLaFila: suspend (llave: String) -> String?,
    private val miDeviceId: () -> String,
    private val miCaja: () -> String,
) {
    @Inject constructor(
        cache: PayloadCache,
        abiertas: CuentasAbiertasAqui,
        cobrosLocales: com.avoqado.pos.giftcards.data.CobrosLocalesDeLaCuenta,
        tarjetas: com.avoqado.pos.payment.data.TerminalPaymentService,
        cobradas: com.avoqado.pos.orders.data.CuentasCobradasAqui,
        pendientes: com.avoqado.pos.core.data.local.database.PendingPaymentDao,
        outbox: com.avoqado.pos.core.data.sync.SyncOutbox,
        ajustes: com.avoqado.pos.tpvsettings.data.TpvSettingsRepository,
    ) : this(
        cache = cache,
        abiertas = abiertas,
        dineroDeLaCuenta = { venue, orden, enLinea ->
            cobrosLocales.hayPendiente(venue, orden, enLinea) ||
                tarjetas.cobrosConTarjetaDelNegocio(venue).any { it.orderId == orden } ||
                cobradas.marcasDe(venue).any { it.orderId == orden }
        },
        tarjetaEnCurso = { venue, orden -> tarjetas.cobrosConTarjetaDelNegocio(venue).any { it.orderId == orden } },
        estadoDeLaFila = { llave -> pendientes.syncStatusOf(llave) },
        miDeviceId = { outbox.deviceId },
        miCaja = { nombreDeCaja(ajustes.terminalNavigation.value.terminalName) },
    )

    /** Seams de prueba: el reloj monotónico con su arranque, y la hora de pared (sólo para enseñar y podar). */
    internal var reloj: () -> Instante = { Instante(ARRANQUE, SystemClock.elapsedRealtime()) }
    internal var horaDePared: () -> Long = System::currentTimeMillis

    /** M6: el registro (disco y JSON) nunca en el hilo de quien llama. */
    internal var io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    private val mutex = Mutex()

    /** Intentos PROPIOS en curso en este proceso (entre comprometer y su desenlace): la reconciliación no los toca. */
    private val vivos = ConcurrentHashMap.newKeySet<String>()

    val deviceId: String get() = miDeviceId()
    val nombreCaja: String get() = miCaja()

    // MARK: - Lo que preguntan las otras cajas (y la propia, por el mismo camino)

    /** null = no es mía, o no sé leer mi registro (no contesto por ella). */
    suspend fun apartar(venueId: String, orderId: String, s: Solicitud): Respuesta? = pedir(venueId, orderId, s, comprometer = false)

    suspend fun comprometer(venueId: String, orderId: String, s: Solicitud): Respuesta? = pedir(venueId, orderId, s, comprometer = true)

    /**
     * R2: el cobro PROPIO compromete en local y queda en disco ANTES de reservar. El intento vive en este proceso hasta [cerrarPropio] o
     * [terminarPropio]. null = no es mía. LANZA si el disco falla: sin ese registro no se cobra.
     */
    suspend fun comprometerPropio(venueId: String, orderId: String, intento: String, quien: String, enLinea: Boolean): Respuesta? {
        vivos += intento // ANTES del disco: nadie lo reconcilia mientras se escribe
        val r = try {
            pedir(venueId, orderId, Solicitud(deviceId, nombreCaja, quien, intento, enLinea), comprometer = true, lanzarSiFalla = true)
        } catch (e: Throwable) {
            vivos -= intento
            throw e
        }
        if (r != Respuesta.Concedida) vivos -= intento
        return r
    }

    /** El cobro propio terminó con dinero (en línea o en la cola): CERRADA(aquí). Nunca lanza (salvo la cancelación). */
    suspend fun cerrarPropio(venueId: String, orderId: String, intento: String, quien: String, subida: Boolean) {
        try {
            cobrada(venueId, orderId, Solicitud(deviceId, nombreCaja, quien, intento), subida)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Lo durable (la fila o la tarjeta) sigue diciendo que hubo dinero: la reconciliación lo vuelve CERRADA al leerlo.
            Log.w(TAG, "⚠️ no se pudo cerrar $orderId en el registro: ${e.message}")
        } finally {
            vivos -= intento
        }
    }

    /** El flujo del cobro propio se cerró: su intento deja de estar vivo y se reconcilia con lo que quedó en disco. Nunca lanza. */
    suspend fun terminarPropio(venueId: String, orderId: String, intento: String) {
        if (vivos.remove(intento)) estadoDe(venueId, orderId)
    }

    /** «Suelta» de un intento (R1). null = no es mía. LANZA si no se pudo guardar. */
    suspend fun soltar(venueId: String, orderId: String, intento: String): Boolean? =
        conRegistro(venueId, orderId) { reg -> RegistroDeCuentas.soltar(reg, orderId, intento) to true }

    /** «Cobrada»: siempre se acepta, también de un intento cancelado. null = no es mía. LANZA si no se pudo guardar (no hay acuse). */
    suspend fun cobrada(venueId: String, orderId: String, s: Solicitud, subida: Boolean): Boolean? =
        conRegistro(venueId, orderId) { reg -> RegistroDeCuentas.cobrada(reg, orderId, s, faltaSubirla = !subida, reloj(), horaDePared()) to true }

    /** «No se cobró: cobrar aquí» (una persona en ESTA caja). false = todavía no se puede, no es mía o el disco falló. */
    suspend fun noSeCobro(venueId: String, orderId: String, quienDeclara: String): Boolean = try {
        conRegistro(venueId, orderId) { reg ->
            val antes = reg.cuentas[orderId]
            val d = RegistroDeCuentas.noSeCobro(reg, orderId, reloj(), horaDePared())
            // ponytail: la bitácora es el log del aparato más el intento cancelado en disco; una nota en el servidor pediría un intent nuevo.
            if (d.hecho) Log.w(TAG, "📝 «No se cobró» de $orderId, declarado por $quienDeclara: intento ${antes?.intento} de ${antes?.caja} cancelado")
            d.registro to d.hecho
        } == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "⚠️ no se pudo declarar «No se cobró» de $orderId: ${e.message}")
        false
    }

    /**
     * Lo que dijeron las lecturas del SERVIDOR ([porElServidor]: pagadas, canceladas o fuera de la lista) y los cobros con tarjeta de esta
     * caja que ya constan ([cobradasAqui]): CERRADA. Sólo las que abrió esta caja. LANZA si el disco falla (quien llama conserva su prueba).
     */
    suspend fun alSaberCerradas(venueId: String, porElServidor: Collection<String>, cobradasAqui: Collection<String>) {
        if (porElServidor.isEmpty() && cobradasAqui.isEmpty()) return
        conRegistroYMias(venueId, orderId = null) { reg, mias ->
            var r = reg
            for (o in cobradasAqui) if (o in mias) {
                r = RegistroDeCuentas.cobrada(r, o, Solicitud(deviceId, nombreCaja, "", "tarjeta:$o"), faltaSubirla = false, reloj(), horaDePared())
            }
            for (o in porElServidor) if (o in mias) r = RegistroDeCuentas.servidorLaCerro(r, o, reloj(), horaDePared())
            r to Unit
        }
    }

    /** Lo que la cuenta dice hoy en el registro (ya reconciliado), para la lista y el detalle. null = LIBRE, no es mía o no se pudo leer. */
    suspend fun estadoDe(venueId: String, orderId: String): CuentaEnElRegistro? = try {
        conRegistro(venueId, orderId) { reg -> reg to reg.cuentas[orderId] }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "⚠️ no se pudo leer el registro de $venueId: ${e.message}")
        null
    }

    /** ¿Ya puede una persona de esta caja declarar «No se cobró» sobre el cobro de OTRA caja? */
    fun puedeDeclararNoSeCobro(c: CuentaEnElRegistro?): Boolean = c != null && c.por != deviceId && RegistroDeCuentas.puedeDeclararNoSeCobro(c, reloj())

    // MARK: - Disco

    private suspend fun pedir(venueId: String, orderId: String, s: Solicitud, comprometer: Boolean, lanzarSiFalla: Boolean = false): Respuesta? = try {
        conRegistro(venueId, orderId) { reg ->
            // R4: sin nada en el registro, el dinero durable de esta caja manda; no se escribe (puede resolverse como no cobrado).
            if (reg.cuentas[orderId] == null && s.intento !in reg.cancelados && dineroDeLaCuenta(venueId, orderId, s.enLinea)) {
                reg to Respuesta.Cerrada(nombreCaja, faltaSubirla = true)
            } else {
                val paso = if (comprometer) RegistroDeCuentas.comprometer(reg, orderId, s, reloj(), horaDePared())
                else RegistroDeCuentas.apartar(reg, orderId, s, reloj(), horaDePared())
                paso.registro to paso.respuesta
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (lanzarSiFalla) throw e
        Log.w(TAG, "⚠️ no contesto por $orderId: ${e.message}")
        null
    }

    private suspend fun <T> conRegistro(venueId: String, orderId: String, cambio: suspend (Registro) -> Pair<Registro, T>): T? =
        conRegistroYMias(venueId, orderId) { reg, _ -> cambio(reg) }

    /**
     * Lee (normalizado, sin las cuentas que ya no son mías y con lo propio reconciliado), aplica [cambio] y guarda si algo cambió, bajo un
     * solo candado y sin poder cortarse a la mitad. null = [orderId] no es mía. LANZA si el disco no se deja leer o escribir: nunca se
     * contesta algo que no quedó guardado.
     */
    private suspend fun <T> conRegistroYMias(venueId: String, orderId: String?, cambio: suspend (Registro, Set<String>) -> Pair<Registro, T>): T? =
        withContext(NonCancellable + io) {
            mutex.withLock {
                val mias = abiertas.todas(venueId)
                if (orderId != null && orderId !in mias) return@withLock null
                val guardado = cache.loadDurable(PayloadCache.TYPE_REGISTRO_DE_CUENTAS, venueId)
                val leido = if (guardado == null) Registro() else RegistroDeCuentas.leer(guardado.json)
                    ?: throw IllegalStateException("registro ilegible de $venueId: no se escribe encima")
                val normal = RegistroDeCuentas.normalizar(leido, reloj(), horaDePared())
                val base = reconciliar(venueId, normal.copy(cuentas = normal.cuentas.filterKeys { it in mias }))
                val (nuevo, t) = cambio(base, mias)
                if (nuevo != leido) cache.saveDurable(PayloadCache.TYPE_REGISTRO_DE_CUENTAS, venueId, RegistroDeCuentas.escribir(nuevo))
                t
            }
        }

    /**
     * R2: lo propio cuyo intento ya no vive en este proceso, contra lo que quedó en disco. C1: sólo un cobro que SÍ ocurrió cierra (la cola
     * ya tiene el efectivo: PENDING, SYNCING, SYNCED o FAILED); una `EN_VUELO` está en duda y sigue COMPROMETIDA. I4: «falta subirla» se
     * quita cuando el SERVIDOR contestó (SYNCED o FAILED); si la fila se borró sin verse, sólo una lectura del servidor lo quita.
     */
    private suspend fun reconciliar(venueId: String, reg: Registro): Registro {
        val yo = deviceId
        var r = reg
        for ((orden, c) in reg.cuentas) {
            if (c.por != yo || c.intento in vivos) continue
            if (c.estado == EstadoDeCuenta.CERRADA && c.faltaSubirla) {
                if (estadoDeLaFila(c.intento) in CONTESTADA) {
                    r = RegistroDeCuentas.cobrada(r, orden, Solicitud(yo, c.caja, c.quien, c.intento), faltaSubirla = false, reloj(), horaDePared())
                }
                continue
            }
            if (c.estado != EstadoDeCuenta.COMPROMETIDA) continue
            val fila = estadoDeLaFila(c.intento)
            r = when {
                fila == EN_VUELO -> r
                fila != null -> RegistroDeCuentas.cobrada(r, orden, Solicitud(yo, c.caja, c.quien, c.intento), faltaSubirla = fila !in CONTESTADA, reloj(), horaDePared())
                tarjetaEnCurso(venueId, orden) -> r
                else -> RegistroDeCuentas.soltar(r, orden, c.intento)
            }
        }
        return r
    }

    /** C1: «Reintentar» con otra llave: el compromiso propio pasa a la llave nueva ANTES de guardar su efectivo. false = no estaba o el disco falló. */
    suspend fun renombrarIntentoPropio(venueId: String, orderId: String, viejo: String, nuevo: String): Boolean {
        vivos += nuevo
        val hecho = try {
            conRegistro(venueId, orderId) { reg ->
                val nuevoReg = RegistroDeCuentas.renombrarIntento(reg, orderId, deviceId, viejo, nuevo)
                nuevoReg to (nuevoReg !== reg)
            } == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ no se pudo pasar el intento $viejo a $nuevo: ${e.message}")
            false
        }
        vivos -= if (hecho) viejo else nuevo
        return hecho
    }

    companion object {
        private const val TAG = "DuenoDeCuentas"

        /** C1: la fila de un cobro que SÍ ocurrió (la cola ya tiene el efectivo). `EN_VUELO` no: está en duda. */
        internal val CON_DINERO = setOf("PENDING", "SYNCING", "SYNCED", "FAILED")

        /** I4: el SERVIDOR ya contestó esa fila (la aplicó o la rechazó): ya no «falta subirla». */
        internal val CONTESTADA = setOf("SYNCED", "FAILED")
        internal const val EN_VUELO = "EN_VUELO"

        /** Arranque del aparato: el `boot_id` del kernel si se puede leer; si no, la hora de arranque al minuto. */
        val ARRANQUE: String by lazy {
            runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: "t${(System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000L}"
        }

        /** El nombre que viaja en el anuncio y en las respuestas: el de los ajustes (≤ 60), o «otra caja». */
        fun nombreDeCaja(terminalName: String?): String = terminalName?.trim()?.takeIf { it.isNotEmpty() }?.take(60) ?: "otra caja"
    }
}

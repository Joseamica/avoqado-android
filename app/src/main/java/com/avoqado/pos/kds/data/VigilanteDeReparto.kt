package com.avoqado.pos.kds.data

import android.content.Context
import android.media.RingtoneManager
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.kds.domain.CanalReparto
import com.avoqado.pos.kds.domain.KDSOrder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "🛵 VigilanteReparto"

/**
 * Vigilante GLOBAL de los pedidos de Rappi/Uber: con la app en primer plano, en CUALQUIER pantalla, saca en papel la
 * comanda que llegó sola y nadie ha impreso, y avisa con la franja «Nuevo pedido de Rappi #folio» y un sonido que
 * insiste hasta «Entendido». Antes sólo lo hacía «Pantalla de cocina», y un negocio que sólo usa impresoras (Testarudo,
 * 12-nov-2026) no se enteraba del pedido. Molde: `VigilanteDePedidosEnLinea`.
 *
 * - Corre con sesión, `orders:update` (lo que pide reclamar la impresión) y el plan `DELIVERY_CHANNELS`. Sólo lee comandas
 *   si el negocio tiene un canal `ACTIVE` o `PAUSED`; si la lista de canales nunca se pudo leer, lee igual (falla
 *   abierto: en este dominio el fail-safe no puede ser no imprimir).
 * - Lo que no sacó NI UNA hoja lo suelta [ImpresionDeComandasDeReparto] y lo reintenta el siguiente sondeo (de éste o de
 *   otro aparato). Lo PARCIAL no se reintenta solo: la franja ofrece «Reimprimir lo que faltó» ([reimprimir]).
 * - Los avisos llegan por `impresas`: los de este vigilante y los de «Pantalla de cocina» del mismo aparato.
 * - Los pedidos MANUAL (`needsAcceptance`) no se tocan: se aceptan y se imprimen en «Pantalla de cocina», como hoy.
 * - Sin red el sondeo falla en silencio y los avisos se quedan: el pedido vive en el servidor y sale al volver la red.
 */
@Singleton
class VigilanteDeReparto internal constructor(
    private val puedeImprimir: () -> Boolean,
    private val leerCanales: suspend () -> Result<List<CanalReparto>>,
    private val leerComandas: suspend () -> Result<List<KDSOrder>>,
    private val imprimir: suspend (List<KDSOrder>) -> Unit,
    private val reintentar: suspend (ComandaImpresa) -> ComandaImpresa,
    private val impresas: Flow<ComandaImpresa>,
    private val sonar: () -> Unit,
    private val scope: CoroutineScope,
    /** La sucursal de ahora: lo de una sucursal anterior no se queda ni en la franja ni en el sonido. */
    private val venueActual: () -> String?,
    private val ahora: () -> Long = System::currentTimeMillis,
) : DefaultLifecycleObserver {

    @Inject
    constructor(
        kdsRepository: KDSRepository,
        impresion: ImpresionDeComandasDeReparto,
        roleManager: RoleManager,
        planManager: PlanManager,
        secureStorage: SecureStorage,
        @ApplicationContext contexto: Context,
    ) : this(
        puedeImprimir = {
            secureStorage.venueId != null && secureStorage.accessToken != null &&
                roleManager.canAccessKDS && planManager.hasFeature("DELIVERY_CHANNELS")
        },
        leerCanales = { kdsRepository.leerCanalesParaImprimir() },
        leerComandas = { kdsRepository.fetchLasMasNuevas() },
        imprimir = { impresion.imprimirPendientes(it) },
        reintentar = { impresion.reimprimirLoQueFalto(it) },
        impresas = impresion.impresas,
        sonar = { sonarAviso(contexto) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        venueActual = { secureStorage.venueId },
    )

    private val _avisos = MutableStateFlow<List<ComandaImpresa>>(emptyList())

    /** Los pedidos que ESTE aparato sacó (o intentó sacar) y nadie ha reconocido con «Entendido». */
    val avisos: StateFlow<List<ComandaImpresa>> = _avisos.asStateFlow()

    private val _proveedor = MutableStateFlow(nombreDelProveedor(emptyList()))

    /** «Rappi», «Uber Eats», «DiDi Food» o «delivery»: para el texto de la franja. */
    val proveedor: StateFlow<String> = _proveedor.asStateFlow()

    @Volatile private var canales: List<CanalReparto>? = null
    /** Cuándo se intentó leer la lista de canales por última vez (`null` = nunca, o se olvidó). */
    @Volatile private var canalesLeidosEn: Long? = null
    @Volatile private var venueDeLosDatos: String? = null

    /** Un «Reimprimir» en curso por pedido: un segundo toque mientras corre el primero no manda otra copia. */
    private val reimprimiendo = mutableSetOf<String>()

    private var ciclo: Job? = null
    private var sonido: Job? = null
    private var escucha: Job? = null

    /** Calla y descarta todos los avisos, también lo que faltó: lo decidió una persona. */
    fun entendido() {
        _avisos.value = emptyList()
    }

    /** «Reimprimir lo que faltó»: manda sólo los trabajos congelados del aviso; el resultado llega por `impresas`. */
    fun reimprimir(kdsId: String) {
        val aviso = _avisos.value.firstOrNull { it.kdsId == kdsId && it.porReimprimir.isNotEmpty() } ?: return
        synchronized(reimprimiendo) { if (!reimprimiendo.add(kdsId)) return }
        scope.launch {
            try {
                reintentar(aviso)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo reimprimir lo que faltó de ${aviso.orderNumber}: ${e.message}")
            } finally {
                synchronized(reimprimiendo) { reimprimiendo.remove(kdsId) }
            }
        }
    }

    @Synchronized
    override fun onStart(owner: LifecycleOwner) {
        ciclo?.cancel()
        sonido?.cancel()
        escucha?.cancel()
        // UNDISPATCHED: suscrito ANTES del primer sondeo. `impresas` no guarda nada para quien todavía no escucha, y un
        // papel que sale sin su aviso es justo lo que este vigilante existe para evitar.
        escucha = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            impresas.collect { c -> _avisos.update { lista -> lista.filterNot { it.kdsId == c.kdsId } + c } }
        }
        sonido = scope.launch {
            _avisos.map { lista -> lista.map { it.kdsId }.toSet() }
                // Sólo un pedido NUEVO reinicia el timbre; que cambie uno que ya sonaba (reimpreso) no da un timbre extra.
                .distinctUntilChanged()
                .collectLatest { ids ->
                    while (ids.isNotEmpty() && isActive) {
                        try { sonar() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            Log.w(TAG, "No se pudo reproducir el aviso: ${e.message}")
                        }
                        delay(REPETIR_SONIDO_MS)
                    }
                }
        }
        ciclo = scope.launch {
            while (isActive) {
                try { sondeo() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    Log.w(TAG, "Sondeo de reparto falló: ${e.message}")
                }
                delay(INTERVALO_MS)
            }
        }
    }

    /** En segundo plano no se consulta, no se imprime ni suena: no hay promesa de avisar con la app dormida. */
    @Synchronized
    override fun onStop(owner: LifecycleOwner) {
        ciclo?.cancel(); ciclo = null
        sonido?.cancel(); sonido = null
        escucha?.cancel(); escucha = null
    }

    private fun olvidar() {
        _avisos.value = emptyList()
        canales = null
        canalesLeidosEn = null
        _proveedor.value = nombreDelProveedor(emptyList())
    }

    private suspend fun sondeo() {
        if (!puedeImprimir()) {
            olvidar()
            venueDeLosDatos = null
            return
        }
        val venue = venueActual()
        if (venueDeLosDatos != venue) {
            olvidar()
            venueDeLosDatos = venue
        }

        val ahoraMs = ahora()
        val ultima = canalesLeidosEn
        if (ultima == null || ahoraMs - ultima >= CANALES_CADA_MS) {
            canalesLeidosEn = ahoraMs
            leerCanales()
                .onSuccess {
                    canales = it
                    _proveedor.value = nombreDelProveedor(conectados(it))
                }
                // Se conservan los últimos; si nunca se leyeron, se sigue leyendo comandas (falla abierto).
                .onFailure { Log.d(TAG, "Canales no leídos: ${it.message}") }
        }
        val conocidos = canales
        if (conocidos != null && conectados(conocidos).isEmpty()) return

        val lista = leerComandas().getOrElse { return }
        retirarLosQueYaNoVienen(lista)
        val pendientes = lista.filter { it.needsPrint && !it.needsAcceptance }
        if (pendientes.isNotEmpty()) imprimir(pendientes)
    }

    /**
     * Un aviso cuya comanda ya no viene (Rappi la canceló, o alguien la terminó) se retira — pero sólo si DEBERÍA venir:
     * la lista son las 100 más nuevas, y la ausencia de una más vieja que todas no prueba nada.
     */
    private fun retirarLosQueYaNoVienen(lista: List<KDSOrder>) {
        val ids = lista.map { it.id }.toSet()
        val masVieja = lista.minOfOrNull { it.createdAt }
        _avisos.update { avisos -> avisos.filter { it.kdsId in ids || (masVieja != null && it.createdAt < masVieja) } }
    }

    private fun conectados(canales: List<CanalReparto>) = canales.filter { it.estado == "ACTIVE" || it.estado == "PAUSED" }

    companion object {
        const val INTERVALO_MS = 15_000L
        const val CANALES_CADA_MS = 300_000L
        const val REPETIR_SONIDO_MS = 20_000L

        private fun sonarAviso(contexto: Context) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(contexto, uri)?.play()
        }
    }
}

/** El nombre que dice la franja: el del único proveedor conectado, o «delivery» si hay varios o ninguno conocido. */
internal fun nombreDelProveedor(canales: List<CanalReparto>): String =
    when (canales.map { it.proveedor }.distinct().singleOrNull()) {
        "RAPPI" -> "Rappi"
        "UBER_EATS" -> "Uber Eats"
        "DIDI_FOOD" -> "DiDi Food"
        else -> "delivery"
    }

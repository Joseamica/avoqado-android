package com.avoqado.pos.printing.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.printing.routing.ImpresoraObservadaRequest
import com.avoqado.pos.printing.routing.PrintConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VigilanteDeImpresoras"

/** Primera ronda tras abrir la app: deja que cargue la config y que la red se asiente. */
private const val PRIMERA_RONDA_MS = 30_000L

/** Cada cuánto se revisa que cada impresora siga siendo la misma en su dirección. */
private const val CADA_RONDA_MS = 10 * 60_000L

/**
 * Tras volver la red (un módem que se reinicia tira el WiFi de todos): una ronda pronto, y otra
 * cuando las impresoras ya terminaron de pedir su dirección nueva (arrancan más lento que el módem).
 */
private val RONDAS_AL_VOLVER_LA_RED_MS = listOf(30_000L, 120_000L)

/** Un aviso recién encolado sale pronto, sin esperar la siguiente ronda. */
private const val AVISO_PRONTO_MS = 3_000L

/**
 * El que hace que «la impresora que se encuentra sola» no dependa de nadie (Testarudo, 2-oct-2026):
 *
 * 1. Corre [PrinterService.vigilar] al abrir la app, cuando vuelve la red y cada 10 min: detecta que
 *    dos ticketeras se intercambiaron la IP y aprende la identidad de las que no la tienen.
 * 2. Manda al servidor los avisos pendientes («Cocina ahora está en .67», «su identidad es mac:…»),
 *    para que el panel deje de mostrar la dirección vieja y las demás tablets la reciban. Sin red se
 *    quedan guardados en el aparato y salen al volver.
 *
 * Nunca bloquea una venta ni una comanda: todo corre aparte.
 */
@Singleton
class VigilanteDeImpresoras @Inject constructor(
    @ApplicationContext private val context: Context,
    private val printerService: PrinterService,
    private val printConfigRepository: PrintConfigRepository,
    private val apiService: ApiService,
    private val secureStorage: SecureStorage,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rondaMutex = Mutex()
    private var iniciado = false
    private var avisoProgramado: Job? = null

    @Synchronized
    fun start() {
        if (iniciado) return
        iniciado = true
        printerService.venueActual = { secureStorage.venueId }
        printerService.alEncolarAviso = { programarAvisos() }
        escucharLaRed()
        scope.launch {
            delay(PRIMERA_RONDA_MS)
            while (true) {
                ronda("periódica")
                delay(CADA_RONDA_MS)
            }
        }
    }

    private fun escucharLaRed() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val pedido = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(
                pedido,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        RONDAS_AL_VOLVER_LA_RED_MS.forEach { espera ->
                            scope.launch {
                                delay(espera)
                                ronda("volvió la red")
                            }
                        }
                    }
                },
            )
        }.onFailure { Log.w(TAG, "No se pudo escuchar la red: ${it.message}") }
    }

    private fun programarAvisos() {
        avisoProgramado?.cancel()
        avisoProgramado = scope.launch {
            delay(AVISO_PRONTO_MS)
            mandarAvisos()
        }
    }

    /** Una ronda completa: revisa las impresoras y manda los avisos. Una a la vez. */
    suspend fun ronda(motivo: String) {
        if (!rondaMutex.tryLock()) return
        try {
            val config = printConfigRepository.getCurrentConfig()
            val deRed = config.printers.filter { it.active && it.connectionType.trim().uppercase() == "NETWORK" && !it.address.isNullOrBlank() }
            printerService.conocerImpresorasDeRed(
                deRed.associate { it.id to hostDe(it.address!!.trim()) },
                deRed.mapNotNull { info -> info.stableKey?.takeIf { it.isNotBlank() }?.let { info.id to it } }.toMap(),
            )
            val corregidas = printerService.vigilar()
            if (corregidas > 0) Log.w(TAG, "🔎 Ronda ($motivo): $corregidas impresora(s) corregida(s)")
            mandarAvisos()
        } catch (e: Exception) {
            Log.w(TAG, "Ronda ($motivo) falló: ${e.message}")
        } finally {
            rondaMutex.unlock()
        }
    }

    /**
     * Manda cada aviso pendiente. Sale de la lista cuando el servidor contestó (sea cual sea su
     * decisión) o lo rechazó para siempre (4xx); un fallo de red lo deja para la siguiente vez.
     */
    internal suspend fun mandarAvisos() = avisosMutex.withLock {
        var refrescar: String? = null
        for ((idImpresora, aviso) in printerService.avisosPendientes.todas()) {
            try {
                val r = apiService.reportarImpresoraObservada(
                    aviso.venueId,
                    idImpresora,
                    ImpresoraObservadaRequest(aviso.previousAddress, aviso.address, aviso.stableKey),
                ).data
                quitarSiSigueIgual(idImpresora, aviso)
                Log.i(TAG, "📨 Aviso de $idImpresora: ${r?.motivo} (${aviso.previousAddress} → ${aviso.address})")
                // El servidor la cambió (o ya tenía otra más nueva): se baja la config para que
                // TODAS las rutas lean la dirección buena de ahí.
                if (r?.motivo == "ACTUALIZADA" || r?.motivo == "DIRECCION_YA_CAMBIO") refrescar = aviso.venueId
            } catch (e: HttpException) {
                if (e.code() in 400..499) {
                    quitarSiSigueIgual(idImpresora, aviso)
                    Log.w(TAG, "Aviso de $idImpresora rechazado (${e.code()}): no se reintenta")
                } else {
                    Log.w(TAG, "Aviso de $idImpresora: el servidor falló (${e.code()}), se reintenta luego")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Aviso de $idImpresora sin red (${e.message}): se reintenta luego")
            }
        }
        refrescar?.takeIf { it == secureStorage.venueId }?.let { runCatching { printConfigRepository.refresh(it) } }
    }

    private val avisosMutex = Mutex()

    /** No borra un aviso más NUEVO que llegó mientras éste viajaba (otra mudanza a media llamada). */
    private fun quitarSiSigueIgual(idImpresora: String, enviado: AvisoDeImpresora) {
        printerService.avisosPendientes.quitarSi(idImpresora, enviado)
    }

    private fun hostDe(raw: String): String {
        val separador = raw.lastIndexOf(':')
        val puerto = if (separador > 0) raw.substring(separador + 1).toIntOrNull() else null
        return if (puerto != null) raw.substring(0, separador) else raw
    }
}

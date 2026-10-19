package com.avoqado.pos.pos.data

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.avoqado.pos.core.util.ConnectivityMonitor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * «Se cobra el precio que vio el cliente»: el servidor sólo respeta el precio que la caja mostró si su catálogo tiene
 * ≤ 72 h, así que la caja baja el catálogo sola. Al volver a primer plano, cada [INTERVALO_MS] mientras siga en primer
 * plano y una vez cuando vuelve la red. Se registra en el ciclo de vida del PROCESO (`AvoqadoApp.onCreate`) y, en
 * Windows, en `Arranque.iniciar`.
 *
 * - Es un refresco DE FONDO ([ProductsRepository.fetchProducts] con `enSegundoPlano`): con productos ya en pantalla no
 *   enciende la carga ni el error que lee el Cobrar, aunque la petición se quede colgada (WiFi conectado sin datos); con la
 *   lista vacía se ve como una carga normal.
 * - Sin red es un estado normal: el repositorio se traga sus errores y conserva el catálogo del caché.
 * - Nunca hay dos a la vez. Los disparos internos (onStart, la reconexión, el reloj de 5 min) pueden coincidir y, con uno
 *   en vuelo, se DESCARTAN. Un [refrescarAhora] explícito no se descarta: queda pendiente (uno solo) y corre UNO más al
 *   terminar el que iba en vuelo, que pudo arrancar antes del cambio que lo motivó (p. ej. un 409 de precio).
 * - Sólo reemplaza la lista de productos: NUNCA toca las líneas del carrito (cada línea conserva el precio y la hora de
 *   catálogo con que se agregó).
 */
@Singleton
class RefrescoDelCatalogo internal constructor(
    private val refrescar: suspend () -> Unit,
    private val conectado: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    @Inject
    constructor(productos: ProductsRepository, red: ConnectivityMonitor) : this(
        refrescar = { productos.fetchProducts(enSegundoPlano = true) },
        conectado = red.isConnected,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** El ciclo de primer plano: un refresco al arrancar, uno cada 5 min y uno cuando vuelve la red. */
    private var ciclo: Job? = null

    /**
     * Estado del refresco, siempre bajo [candado]: marcar «pendiente» y decidir «ya terminé» son UNA sola decisión atómica.
     * Sin eso, una marca que llega justo después de que el refresco en vuelo ya miró si había pendiente se perdería.
     */
    private val candado = Any()
    private var enVuelo = false
    private var pendiente = false

    @Synchronized
    override fun onStart(owner: LifecycleOwner) {
        ciclo?.cancel()
        ciclo = scope.launch {
            launch {
                var habiaRed = conectado.value
                conectado.collect { hayRed ->
                    if (hayRed && !habiaRed) dispararDeFondo()
                    habiaRed = hayRed
                }
            }
            while (isActive) {
                dispararDeFondo()
                delay(INTERVALO_MS)
            }
        }
    }

    /** Detiene el ciclo. Un refresco que ya iba en vuelo no se cancela (vive en [scope]): termina y actualiza el catálogo. */
    @Synchronized
    override fun onStop(owner: LifecycleOwner) {
        ciclo?.cancel()
        ciclo = null
    }

    /**
     * Un refresco de fondo ya, en su propio [scope], sin esperar al ciclo. Si hay uno en vuelo NO se descarta: queda
     * pendiente (los que lleguen mientras tanto se funden en uno solo) y corre exactamente uno más cuando el que iba en
     * vuelo termine. Para quien necesita la lista DESPUÉS de un cambio (p. ej. tras un 409 de precio).
     */
    fun refrescarAhora() {
        val lanzar = synchronized(candado) {
            if (enVuelo) {
                pendiente = true
                false
            } else {
                enVuelo = true
                true
            }
        }
        if (lanzar) scope.launch { correr() }
    }

    /** Disparo interno (onStart, reloj, reconexión): con uno en vuelo se descarta, sin dejar nada pendiente. */
    private fun dispararDeFondo() {
        val lanzar = synchronized(candado) {
            if (enVuelo) false else { enVuelo = true; true }
        }
        if (lanzar) scope.launch { correr() }
    }

    /**
     * El refresco en vuelo, más el pendiente si lo hubo. Un refresco que truena (aquí o en el repositorio) no apaga el
     * ciclo ni la escucha de la red, y el pendiente corre igual.
     */
    private suspend fun correr() {
        try {
            do {
                try {
                    refrescar()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("RefrescoDelCatalogo", "No se pudo refrescar el catálogo: ${e.message}")
                }
            } while (hayOtroPendiente())
        } catch (e: Throwable) {
            // Cancelación o un Error (OOM, LinkageError): nunca dejar el estado «en vuelo» pegado (en Windows la JVM sigue viva).
            synchronized(candado) { enVuelo = false; pendiente = false }
            throw e
        }
    }

    /** Atómico: o toma la marca de «pendiente» (y sigue en vuelo), o se declara terminado. */
    private fun hayOtroPendiente(): Boolean = synchronized(candado) {
        if (pendiente) {
            pendiente = false
            true
        } else {
            enVuelo = false
            false
        }
    }

    companion object {
        const val INTERVALO_MS = 5 * 60 * 1000L
    }
}

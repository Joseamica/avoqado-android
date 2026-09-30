package com.avoqado.pos.escritorio

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.room.useReaderConnection
import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.escritorio.Escritorio
import com.avoqado.escritorio.configuracionDeAndroid
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.AvoqadoDatabase
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.customerdisplay.DeviceCapabilitySyncCoordinator
import com.avoqado.pos.kiosk.domain.KioskDriver
import com.avoqado.pos.kiosk.domain.KioskPrefs
import com.avoqado.pos.kiosk.domain.KioskState
import com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator
import com.avoqado.pos.reservations.data.ReservationActionsRetrier
import com.google.inject.CreationException
import com.google.inject.Injector
import com.google.inject.ProvisionException
import java.awt.GraphicsEnvironment
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException
import java.nio.file.FileSystemException
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object Arranque {
    /** Con este tamaño abre la ventana, y con él se siembra la Configuration antes de la primera petición. */
    val TAMANO_INICIAL = DpSize(1280.dp, 800.dp)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Lo que en Android hacen el sistema, Hilt y AvoqadoApp.onCreate antes de la primera pantalla.
     * Lanza si lo guardado no se puede leer (preferencias ilegibles, base de otra versión): Main lo DICE y sale sin borrar.
     */
    fun abrir(carpeta: Path) {
        val actividad = ActividadDeEscritorio(carpeta)
        // 🔴 ANTES de la red: DeviceHeadersInterceptor lee smallestScreenWidthDp UNA vez (by lazy); con 0 diría PHONE toda la sesión.
        actividad.resources.configuration.copiarDe(
            configuracionDeAndroid(TAMANO_INICIAL.width, TAMANO_INICIAL.height, densidadDeLaPantalla()),
        )
        val inyector = Inyector.crear(actividad)   // la actividad, no un Context suelto: findActivity() la busca (ScreenPinningSheet)
        // Room abre la base hasta la primera consulta. Se abre aquí para que una base de otra versión (no hay migraciones)
        // truene DENTRO del arranque, con aviso, y no en una corrutina a media venta.
        runBlocking { inyector.getInstance(AvoqadoDatabase::class.java).useReaderConnection { it.usePrepared("SELECT 1") { s -> s.step() } } }
        Escritorio.instalar(actividad, inyector)
        iniciar(inyector, RaizDeEscritorio.lifecycle)
    }

    /** 🔴 Espejo de AvoqadoApp.onCreate (Android): si aquel cambia, éste también. Es parte del costo de mantener la copia. */
    fun iniciar(inyector: Injector, ciclo: Lifecycle) {
        ciclo.addObserver(inyector.getInstance(DeviceCapabilitySyncCoordinator::class.java))
        VenueTimeZone.set(inyector.getInstance(SecureStorage::class.java).venueTimezone)
        inyector.getInstance(ReservationActionsRetrier::class.java).start(appScope)
        inyector.getInstance(CancelacionDeCobroCoordinator::class.java).start()
        inyector.getInstance(KioskDriver::class.java).attach()
        val kioskPrefs = inyector.getInstance(KioskPrefs::class.java)
        val kioskState = inyector.getInstance(KioskState::class.java)
        appScope.launch { kioskPrefs.enabled.collect { kioskState.setEnabled(it) } }
    }

    /** La escala de la pantalla principal, la misma que Compose le da a la ventana (LocalDensity). Sin pantalla, 1. */
    private fun densidadDeLaPantalla(): Float =
        if (GraphicsEnvironment.isHeadless()) 1f
        else GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX.toFloat()
}

/** Las envolturas de Guice, de la reflexión y de los inicializadores: debajo está la excepción de verdad. */
private val envolturas = listOf(
    ProvisionException::class.java, CreationException::class.java, InvocationTargetException::class.java,
    ExceptionInInitializerError::class.java, UndeclaredThrowableException::class.java,
)

/**
 * Quita SÓLO envolturas y se detiene en la primera excepción que no lo es. La causa de una excepción de la app es
 * detalle para la bitácora: bajo «Preferencias ilegibles…» viene el fallo al apartar el archivo, que es sólo una ruta.
 */
fun causaRaiz(t: Throwable): Throwable =
    generateSequence(t) { e -> e.cause?.takeIf { c -> c !== e && envolturas.any { it.isInstance(e) } } }.take(20).last()

/**
 * Lo que ve el cajero si el arranque truena (en Windows no hay consola). Sólo lo principal: el resto va a la bitácora.
 * Sin [carpeta] (no se pudo resolver, o la bitácora misma falló) no se apunta a unos logs que no existen.
 * [alAbrir] = todavía no terminaba `Arranque.abrir`; después (la ventana tronó) la app no «no abrió»: se cerró.
 */
fun mensajeDeArranqueFallido(t: Throwable, carpeta: Path?, alAbrir: Boolean = true): String {
    val causa = causaRaiz(t)
    val propio = causa.message?.takeIf { it.isNotBlank() }?.lines()?.take(10)?.joinToString("\n")
    val texto = when {
        propio == null -> causa.javaClass.simpleName
        causa is FileSystemException -> "${causa.javaClass.simpleName}: $propio"   // su mensaje es sólo la ruta
        causa is IllegalStateException && "migration" in propio -> "La base de datos del aparato es de otra versión de la app. $propio"
        else -> propio
    }
    val detalle = carpeta?.let { " Detalle en ${it.resolve("logs")}" }.orEmpty()
    val que = if (alAbrir) "No se pudo abrir Avoqado POS" else "Avoqado POS se cerró por un error"
    return "$que: $texto\nNo se borró nada.$detalle"
}

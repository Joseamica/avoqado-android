package com.avoqado.pos

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.customerdisplay.DeviceCapabilitySyncCoordinator
import com.avoqado.pos.reservations.data.ReservationActionsRetrier
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class AvoqadoApp : Application() {
    @Inject lateinit var secureStorage: SecureStorage
    @Inject lateinit var reservationActionsRetrier: ReservationActionsRetrier
    @Inject lateinit var kioskDriver: com.avoqado.pos.kiosk.domain.KioskDriver
    @Inject lateinit var kioskPrefs: com.avoqado.pos.kiosk.domain.KioskPrefs
    @Inject lateinit var kioskState: com.avoqado.pos.kiosk.domain.KioskState
    @Inject lateinit var deviceCapabilitySyncCoordinator: DeviceCapabilitySyncCoordinator

    /**
     * 🔴 Las cancelaciones de cobro se reproducen desde el ARRANQUE DEL PROCESO, no desde la
     * pantalla de cobro ni desde la sesión: una cancelación que quedó a medias cuando la app murió
     * tiene que seguir su camino aunque nadie vuelva a abrir Cobrar, y sobrevive al logout y al
     * cambio de sucursal igual que la llave durable del cobro.
     */
    @Inject lateinit var cancelacionDeCobroCoordinator:
        com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Lifecycle del PROCESO, una sola vez. MainActivity puede reiniciarse o
        // mudarse de display y no debe multiplicar el scheduler.
        ProcessLifecycleOwner.get().lifecycle.addObserver(deviceCapabilitySyncCoordinator)
        // Hydrate the global venue timezone holder so DTO-level formatters
        // (TransactionModel.timeDisplay etc.) work from the very first compose.
        VenueTimeZone.set(secureStorage.venueTimezone)
        reservationActionsRetrier.start(appScope)
        cancelacionDeCobroCoordinator.start()

        // El kiosco: el interruptor del aparato manda, y el motor sólo se
        // enchufa una vez. Apagado (lo normal) esto no hace absolutamente
        // nada — la segunda pantalla sigue siendo el espejo del mostrador.
        kioskDriver.attach()
        // Se OBSERVA, no se lee una vez: si el negocio prende o apaga el kiosco
        // desde Ajustes, tiene que surtir efecto en el momento y no hasta el
        // siguiente arranque de la app.
        appScope.launch { kioskPrefs.enabled.collect { kioskState.setEnabled(it) } }
    }
}

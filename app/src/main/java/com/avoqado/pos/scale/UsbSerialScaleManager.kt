package com.avoqado.pos.scale

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.avoqado.pos.areatickets.data.ScaleProfile
import com.avoqado.pos.pos.data.model.NormalizedScaleReading
import com.avoqado.pos.pos.data.model.ScaleFrameRejection
import com.avoqado.pos.pos.data.model.ScaleProtocol
import com.avoqado.pos.pos.data.model.ScaleStabilityTracker
import com.avoqado.pos.pos.data.model.decodeScaleFrame
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "UsbSerialScale"
private const val ACTION_USB_SCALE_PERMISSION = "com.avoqado.pos.USB_SCALE_PERMISSION"
private const val SERIAL_WRITE_TIMEOUT_MILLIS = 1_000
private const val TORREY_POLL_INTERVAL_MILLIS = 250L

sealed interface ScaleConnectionState {
    data object NotConfigured : ScaleConnectionState
    data class Connecting(val profileName: String) : ScaleConnectionState
    data class Ready(val profileName: String) : ScaleConnectionState
    data class Unstable(
        val profileName: String,
        val reading: NormalizedScaleReading,
    ) : ScaleConnectionState
    data class Stable(
        val profileName: String,
        val reading: NormalizedScaleReading,
    ) : ScaleConnectionState
    data class Problem(
        val profileName: String,
        val message: String,
    ) : ScaleConnectionState
}

/**
 * Transporte Android USB host para perfiles de báscula certificados.
 *
 * La clase no adivina por marca. El servidor debe entregar el protocolo, parámetros seriales y,
 * cuando exista más de un dispositivo USB, VID/PID. Así una impresora o una báscula de otro local
 * nunca se abre como si fuera el equipo configurado.
 */
@Singleton
class UsbSerialScaleManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : SerialInputOutputManager.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ScaleConnectionState>(ScaleConnectionState.NotConfigured)
    val state: StateFlow<ScaleConnectionState> = _state.asStateFlow()

    private val usbManager: UsbManager?
        get() = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private var currentProfile: ScaleProfile? = null
    private var currentProtocol: ScaleProtocol? = null
    private var serialPort: UsbSerialPort? = null
    private var inputOutputManager: SerialInputOutputManager? = null
    private var pollingJob: Job? = null
    private val frameBuffer = StringBuilder()
    private var stabilityTracker = ScaleStabilityTracker()

    suspend fun connect(profile: ScaleProfile) = withContext(Dispatchers.IO) {
        disconnect(updateState = false)
        currentProfile = profile
        _state.value = ScaleConnectionState.Connecting(profile.name)

        if (!profile.active ||
            profile.transport != "ANDROID_USB_SERIAL" ||
            "AREA_TICKET_LINE" !in profile.allowedContexts
        ) {
            fail(profile, "Este perfil no está habilitado para pesar productos del vale.")
            return@withContext
        }

        val protocol = ScaleProtocol.fromProfileType(
            profile.frameParser?.get("type")?.jsonPrimitive?.contentOrNull,
        )
        if (protocol == null) {
            fail(profile, "Falta configurar el protocolo certificado de esta báscula.")
            return@withContext
        }
        currentProtocol = protocol

        val manager = usbManager
        if (manager == null) {
            fail(profile, "USB no está disponible en esta terminal.")
            return@withContext
        }
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        val driver = drivers.singleOrNull { candidate ->
            profile.matches(candidate.device)
        } ?: run {
            val matchingCount = drivers.count { profile.matches(it.device) }
            val message = when {
                drivers.isEmpty() -> "No se detectó una báscula serial por USB. Revisa cable y adaptador."
                matchingCount > 1 -> "Hay más de una báscula compatible conectada; configura VID/PID."
                profile.vendorId == null || profile.productId == null ->
                    "Hay varios puertos seriales USB; configura VID/PID para elegir la báscula."
                else -> "La báscula configurada no está conectada por USB."
            }
            fail(profile, message)
            return@withContext
        }

        if (!ensurePermission(driver.device)) {
            fail(profile, "Permiso USB denegado para ${driver.device.productName ?: profile.name}.")
            return@withContext
        }
        val connection = manager.openDevice(driver.device)
        if (connection == null) {
            fail(profile, "No se pudo abrir la conexión USB de la báscula.")
            return@withContext
        }

        val port = driver.ports.firstOrNull()
        if (port == null) {
            connection.close()
            fail(profile, "El dispositivo USB no expone un puerto serial.")
            return@withContext
        }

        runCatching {
            port.open(connection)
            port.setParameters(
                profile.baudRate ?: protocol.defaultBaudRate(),
                profile.dataBits ?: 8,
                profile.stopBits.toUsbStopBits(),
                profile.parity.toUsbParity(),
            )
            serialPort = port
            stabilityTracker = ScaleStabilityTracker()
            synchronized(frameBuffer) { frameBuffer.clear() }
            inputOutputManager = SerialInputOutputManager(port, this@UsbSerialScaleManager).also {
                it.start()
            }
            _state.value = ScaleConnectionState.Ready(profile.name)
            startPolling(protocol)
            Log.i(TAG, "Connected ${profile.name} with ${protocol.profileType}")
        }.onFailure { error ->
            runCatching { port.close() }
            fail(profile, "No se pudo configurar la báscula: ${error.message ?: "error serial"}")
        }
    }

    fun disconnect(updateState: Boolean = true) {
        pollingJob?.cancel()
        pollingJob = null
        inputOutputManager?.stop()
        inputOutputManager = null
        runCatching { serialPort?.close() }
        serialPort = null
        currentProfile = null
        currentProtocol = null
        stabilityTracker.reset()
        synchronized(frameBuffer) { frameBuffer.clear() }
        if (updateState) _state.value = ScaleConnectionState.NotConfigured
    }

    override fun onNewData(data: ByteArray) {
        val text = data.toString(StandardCharsets.US_ASCII)
        val frames = synchronized(frameBuffer) {
            frameBuffer.append(text)
            buildList {
                var separator = frameBuffer.indexOfAny(charArrayOf('\r', '\n'))
                while (separator >= 0) {
                    val frame = frameBuffer.substring(0, separator).trim()
                    frameBuffer.delete(0, separator + 1)
                    if (frame.isNotEmpty()) add(frame)
                    separator = frameBuffer.indexOfAny(charArrayOf('\r', '\n'))
                }
            }
        }
        frames.forEach(::handleFrame)
    }

    override fun onRunError(e: Exception) {
        val profile = currentProfile ?: return
        fail(profile, "Se perdió la conexión con la báscula: ${e.message ?: "revisa el cable"}")
        inputOutputManager = null
        runCatching { serialPort?.close() }
        serialPort = null
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun handleFrame(frame: String) {
        val profile = currentProfile ?: return
        val protocol = currentProtocol ?: return
        val decoded = decodeScaleFrame(
            protocol = protocol,
            deviceId = profile.id,
            rawFrame = frame,
        )
        val rawReading = decoded.reading
        if (rawReading != null) {
            val reading = if (protocol == ScaleProtocol.TORREY_PCR_ASCII) {
                stabilityTracker.observe(rawReading)
            } else {
                rawReading
            }
            _state.value = if (reading.stable) {
                ScaleConnectionState.Stable(profile.name, reading)
            } else {
                ScaleConnectionState.Unstable(profile.name, reading)
            }
            return
        }
        when (decoded.rejection) {
            ScaleFrameRejection.OVERLOAD ->
                fail(profile, "La báscula reporta sobrecarga o peso fuera de rango.")
            ScaleFrameRejection.NEGATIVE_WEIGHT ->
                fail(profile, "La báscula reporta peso negativo. Revisa tara y cero.")
            ScaleFrameRejection.UNSUPPORTED_UNIT ->
                fail(profile, "La báscula no está enviando kilogramos.")
            ScaleFrameRejection.EMPTY,
            ScaleFrameRejection.MALFORMED,
            null,
            -> Log.w(TAG, "Ignored frame from ${profile.name}: ${frame.take(80)}")
        }
    }

    private fun startPolling(protocol: ScaleProtocol) {
        val command = protocol.pollCommand ?: return
        pollingJob = scope.launch {
            while (isActive) {
                runCatching { serialPort?.write(command, SERIAL_WRITE_TIMEOUT_MILLIS) }
                    .onFailure { error ->
                        onRunError(error as? Exception ?: IllegalStateException(error))
                        return@launch
                    }
                delay(TORREY_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun fail(profile: ScaleProfile, message: String) {
        _state.value = ScaleConnectionState.Problem(profile.name, message)
        Log.w(TAG, "${profile.name}: $message")
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag", "MutableImplicitPendingIntent")
    private suspend fun ensurePermission(device: UsbDevice): Boolean {
        val manager = usbManager ?: return false
        if (manager.hasPermission(device)) return true

        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_USB_SCALE_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    val replyDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = replyDevice?.deviceId == device.deviceId &&
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (continuation.isActive) continuation.resume(granted)
                }
            }
            val filter = IntentFilter(ACTION_USB_SCALE_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            val permissionIntent = Intent(ACTION_USB_SCALE_PERMISSION).setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            manager.requestPermission(
                device,
                PendingIntent.getBroadcast(context, 41, permissionIntent, flags),
            )
            continuation.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
}

private fun ScaleProfile.matches(device: UsbDevice): Boolean {
    val expectedVendorId = vendorId
    val expectedProductId = productId
    return when {
        expectedVendorId != null && expectedProductId != null ->
            device.vendorId == expectedVendorId && device.productId == expectedProductId
        expectedVendorId != null -> device.vendorId == expectedVendorId
        expectedProductId != null -> device.productId == expectedProductId
        else -> true
    }
}

private fun ScaleProtocol.defaultBaudRate(): Int = when (this) {
    ScaleProtocol.JUSTA_LP7516_ASCII -> 9_600
    ScaleProtocol.TORREY_PCR_ASCII -> 115_200
}

private fun Int?.toUsbStopBits(): Int = when (this) {
    2 -> UsbSerialPort.STOPBITS_2
    else -> UsbSerialPort.STOPBITS_1
}

private fun String?.toUsbParity(): Int = when (this?.uppercase()) {
    "EVEN" -> UsbSerialPort.PARITY_EVEN
    "ODD" -> UsbSerialPort.PARITY_ODD
    else -> UsbSerialPort.PARITY_NONE
}

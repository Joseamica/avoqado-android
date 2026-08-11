package com.avoqado.pos.customerdisplay

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puente táctil: los toques del panel del CLIENTE que Android entrega en la
 * pantalla del CAJERO.
 *
 * 🔴 El hecho medido (Sunmi T3 Pro, 2026-08-10, con el aparato enfrente): el
 * panel del cliente SÍ trae digitalizador multitáctil (`SUNMI NP511`,
 * `TOUCH | TOUCH_MT`), pero Android **no lo asocia a esa pantalla**
 * (`AssociatedDisplayPort: <none>`, `displayId=''`) y sus toques aterrizan en la
 * ventana de la pantalla del cajero, mapeados al espacio de 1920x1080 del panel
 * grande — no al de 1280x800 del chico. Verificado con
 * `settings put system show_touches 1`: el dedo va a la pantalla del cliente y
 * el indicador aparece en la del cajero, desplazado.
 *
 * Dos consecuencias, y las dos importan:
 * 1. "El cliente elige propina y calificación" no sirve en ese modelo.
 * 2. 🔴 Un cliente tocando SU pantalla está apretando cosas en la caja. Eso pasa
 *    hoy, en producción, sin ninguna función nuestra de por medio.
 *
 * No hay arreglo por configuración: el equipo es build de producción **sin
 * root** (no se puede escribir un `.idc` con `touch.displayId`) y la pantalla es
 * **virtual**, creada por `com.sunmi.usbscreen`. Así que hacemos de puente
 * nosotros: identificamos el dispositivo que generó el toque, lo sacamos del
 * camino de la caja, y lo reenviamos traducido a la ventana del cliente (que es
 * nuestra y vive en nuestro proceso).
 *
 * Esta clase resuelve SOLO la primera mitad: **quién** generó el toque y en qué
 * espacio de coordenadas viene. El reenvío lo hace [CustomerDisplayManager], que
 * es el único que sabe qué ventana de cliente hay montada.
 */

// MARK: - Decisión pura (sin Android, y por eso testeable sin el aparato)

/** Lo mínimo de un dispositivo de entrada para poder decidir sin Android. */
internal data class TouchDeviceInfo(
    val deviceId: Int,
    val name: String,
    val isTouchscreen: Boolean,
    /** `true` externo, `false` integrado, `null` no se pudo determinar. */
    val external: Boolean?,
    /** Pantalla a la que Android lo ruteó; `null` = desconocido, [NO_ASSOCIATED_DISPLAY] = ninguna. */
    val associatedDisplayId: Int?,
)

/** Lo que reporta Android cuando un dispositivo no está atado a ninguna pantalla. */
internal const val NO_ASSOCIATED_DISPLAY = -1

/**
 * Qué dispositivos táctiles hay que puentear.
 *
 * La característica que importa es **"táctil externo sin display asociado"**, no
 * la marca: otro modelo puede llamarle distinto a su panel, y un `if (name ==
 * "SUNMI NP511")` dejaría fuera al siguiente equipo que llegue con el mismo
 * defecto de ruteo.
 *
 * 🔴 Por qué exigir `external == true` de forma POSITIVA, y no "no sabemos ⇒
 * adelante": el error caro no es dejar de puentear (todo sigue como hoy), es
 * tragarse los toques del panel del CAJERO — eso deja el POS inservible con
 * fila en la caja. Si no podemos afirmar que un táctil es externo, no se toca.
 *
 * 🔴 Y la invariante que cierra el mismo riesgo por el otro lado: **nunca
 * reclamamos TODOS los táctiles**. Si el filtro se queda con todos, algo entendimos
 * mal del equipo (p. ej. un OEM que marca su panel integrado como externo) y
 * consumirlos sería quedarnos sin caja. Ante la duda, no se puentea nada.
 *
 * 🔴 Lo que NO hace falta defender, y conviene saber para no añadir guardas de
 * más: un táctil correctamente asociado a SU pantalla nunca llega hasta aquí. Sus
 * eventos se entregan a la ventana que vive en ESA pantalla, no a la caja, así
 * que el filtro del puente —que corre en `MainActivity.dispatchTouchEvent`— ni
 * siquiera los ve. Lo único que este filtro puede llegar a ver es lo que ya
 * aterrizó en la caja.
 */
internal fun resolveBridgedTouchDeviceIds(devices: List<TouchDeviceInfo>): Set<Int> {
    val touchscreens = devices.filter { it.isTouchscreen }
    if (touchscreens.isEmpty()) return emptySet()

    val huerfanos = touchscreens.filter { device ->
        device.external == true &&
            (device.associatedDisplayId == null || device.associatedDisplayId == NO_ASSOCIATED_DISPLAY)
    }
    if (huerfanos.isEmpty()) return emptySet()
    // Invariante: si no queda ni un táctil para el cajero, no puenteamos nada.
    if (huerfanos.size == touchscreens.size) return emptySet()

    return huerfanos.map { it.deviceId }.toSet()
}

/** Factores de escala del espacio del táctil al de la pantalla del cliente. */
internal data class TouchScale(val x: Float, val y: Float)

/**
 * Del espacio en el que VIENE el toque al espacio de la ventana del cliente.
 *
 * 🔴 Las dimensiones son SIEMPRE las reales de tiempo de ejecución (el rango del
 * digitalizador y el tamaño de la ventana del cliente), nunca constantes:
 * 1920x1080 → 1280x800 es lo del T3 Pro de hoy, no una ley. Un monitor distinto,
 * o el mismo equipo con otra densidad, dan otros números.
 *
 * Devuelve `null` si alguna dimensión todavía no es utilizable (una ventana
 * recién creada mide 0): sin escala no se reenvía, pero el toque igual se consume
 * — es preferible perder ese toque a que apriete algo en la caja.
 */
internal fun computeTouchScale(
    sourceWidth: Float,
    sourceHeight: Float,
    targetWidth: Float,
    targetHeight: Float,
): TouchScale? {
    if (sourceWidth <= 0f || sourceHeight <= 0f) return null
    if (targetWidth <= 0f || targetHeight <= 0f) return null
    return TouchScale(x = targetWidth / sourceWidth, y = targetHeight / sourceHeight)
}

/**
 * Traduce UNA coordenada. Sin recortes a propósito: recortar al borde escondería
 * un mapeo equivocado haciéndolo parecer "casi bien", y un punto un pixel afuera
 * simplemente no acierta a nada. Es la MISMA multiplicación que aplica la matriz
 * del reenvío, así que lo que se registra en el log es exactamente lo que se
 * despacha.
 */
internal fun mapTouchCoordinate(value: Float, scale: Float): Float = value * scale

// MARK: - Enganche con Android

/**
 * Cachea qué ids de dispositivo hay que puentear y en qué espacio reportan.
 *
 * Se refresca solo cuando el hardware cambia (conectar/desconectar el panel), no
 * en cada toque: `InputDevice.getDevice()` cruza a `InputManagerService` y
 * `dispatchTouchEvent` es de las rutas más calientes que hay.
 */
@Singleton
class CustomerTouchBridge @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val tag = "🖥️CustomerDisplay"

    @Volatile
    private var bridgedIds: Set<Int> = emptySet()

    /** Ancho/alto del espacio en que reporta cada táctil puenteado. */
    @Volatile
    private var sourceSpans: Map<Int, Pair<Float, Float>> = emptyMap()

    private var listenerRegistered = false

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refresh()
        override fun onInputDeviceRemoved(deviceId: Int) = refresh()
        override fun onInputDeviceChanged(deviceId: Int) = refresh()
    }

    /**
     * Vuelve a mirar qué dispositivos de entrada hay. Nada de esto puede tumbar
     * la caja: si el fabricante devuelve algo raro, nos quedamos sin puente y la
     * app se comporta exactamente como antes.
     */
    fun refresh() {
        runCatching {
            registerListenerOnce()
            // `.toList()`: getDeviceIds() devuelve un IntArray y los arrays de
            // primitivos no tienen mapNotNull.
            val devices: List<TouchDeviceInfo> = InputDevice.getDeviceIds().toList().mapNotNull { id ->
                val device = InputDevice.getDevice(id) ?: return@mapNotNull null
                TouchDeviceInfo(
                    deviceId = id,
                    name = device.name.orEmpty(),
                    isTouchscreen = device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN),
                    external = device.isExternalCompat(),
                    associatedDisplayId = device.associatedDisplayIdCompat(),
                )
            }
            val resolved = resolveBridgedTouchDeviceIds(devices)
            bridgedIds = resolved
            sourceSpans = resolved.associateWith { id -> spanOf(id) }
                .filterValues { it != null }
                .mapValues { (_, span) -> span!! }

            if (resolved.isNotEmpty()) {
                // Rastro para verificar en el aparato: es la primera línea que hay
                // que buscar en logcat cuando el cliente toca y no pasa nada.
                val nombres = devices
                    .filter { device -> device.deviceId in resolved }
                    .joinToString { device ->
                        val span = sourceSpans[device.deviceId]
                        val rango = if (span == null) "rango?" else "${span.first.toInt()}x${span.second.toInt()}"
                        "${device.name}#${device.deviceId} $rango"
                    }
                Log.i(tag, "Puente táctil armado para: $nombres (Android no rutea estos toques a la pantalla del cliente)")
            }
        }.onFailure {
            bridgedIds = emptySet()
            sourceSpans = emptyMap()
            Log.w(tag, "No se pudieron leer los dispositivos de entrada; sin puente táctil: ${it.message}")
        }
    }

    /** ¿Este toque lo generó el panel del cliente? Barato: mirar un `Set`. */
    fun isFromCustomerPanel(event: MotionEvent): Boolean = event.deviceId in bridgedIds

    /** ¿Hay algún panel de cliente que podamos puentear? Alimenta `touchCapable`. */
    fun hasBridgedDevices(): Boolean = bridgedIds.isNotEmpty()

    /**
     * Espacio en el que reporta ese táctil, o `null` si el equipo no lo dice.
     * El manager cae entonces al tamaño de la ventana del cajero, que es donde
     * el sistema está entregando estos toques.
     */
    fun sourceSpanFor(deviceId: Int): Pair<Float, Float>? = sourceSpans[deviceId]

    private fun registerListenerOnce() {
        if (listenerRegistered) return
        val im = appContext.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return
        im.registerInputDeviceListener(inputListener, Handler(Looper.getMainLooper()))
        listenerRegistered = true
    }

    /**
     * Ancho/alto que reporta el digitalizador. Es `max - min + 1` porque el rango
     * es inclusivo: un panel de 1920 px reporta `min=0, max=1919`. Con `max - min`
     * a secas, el pixel del borde se mapearía justo FUERA de la pantalla del
     * cliente.
     */
    private fun spanOf(deviceId: Int): Pair<Float, Float>? = runCatching {
        val device = InputDevice.getDevice(deviceId)
        val rangeX = device?.getMotionRange(MotionEvent.AXIS_X)
        val rangeY = device?.getMotionRange(MotionEvent.AXIS_Y)
        if (rangeX == null || rangeY == null) return@runCatching null
        val width = rangeX.max - rangeX.min + 1f
        val height = rangeY.max - rangeY.min + 1f
        if (width <= 1f || height <= 1f) null else width to height
    }.getOrNull()
}

/**
 * ¿Es un táctil EXTERNO? Público desde API 34; antes era `@hide`, y este proyecto
 * soporta desde 26 — una llamada directa en un Sunmi con Android 9/11 sería un
 * `NoSuchMethodError` que tumba la caja. Se gatea por versión y se cae a
 * reflexión, y si el fabricante la bloquea queda en `null` = "no sabemos", que
 * es lo mismo que "no se puentea".
 */
private fun InputDevice.isExternalCompat(): Boolean? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        runCatching { isExternal }.getOrNull()
    } else {
        runCatching { InputDevice::class.java.getMethod("isExternal").invoke(this) as? Boolean }.getOrNull()
    }

/**
 * A qué pantalla ruteó Android este dispositivo. **No hay API pública** que lo
 * conteste (es lo que `dumpsys input` imprime como `AssociatedDisplayPort`), así
 * que se intenta por reflexión y lo normal es quedarse en `null` = desconocido.
 *
 * Se consulta igual porque cuando SÍ contesta es la respuesta más directa
 * posible, y porque el día que Android la publique este código ya la aprovecha.
 * El peso de la decisión no está aquí: lo llevan `isExternal`, la invariante de
 * [resolveBridgedTouchDeviceIds] y el hecho de que un táctil bien ruteado nunca
 * entrega sus eventos en la ventana de la caja.
 */
private fun InputDevice.associatedDisplayIdCompat(): Int? = runCatching {
    InputDevice::class.java.getMethod("getAssociatedDisplayId").invoke(this) as? Int
}.getOrNull()

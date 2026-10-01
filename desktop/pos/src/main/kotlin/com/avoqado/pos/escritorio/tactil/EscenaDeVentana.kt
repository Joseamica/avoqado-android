package com.avoqado.pos.escritorio.tactil

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import javax.swing.JComponent

/**
 * El dedo llega a Compose como DEDO (PointerType.Touch), con todos los dedos vivos: igual que en Android.
 *
 * El primer dedo reinicia antes (medido en Windows el 30-sep, causa leída en ui-desktop 1.7.3): con el mouse quieto
 * encima, Compose se repite su último movimiento cada vez que algo cambia de lugar; si ese repetido «no cambió nada»,
 * HitPathTracker no hace el pase final y cada nodo se queda con el mouse guardado, que se SUMA al primer dedo («mouse arriba
 * + dedo abajo»): awaitFirstDown no ve un dedo que empieza y la lista no se desliza. Un Exit del mouse no basta (Codex): si
 * los nodos nunca «entraron», Compose lo convierte en ese mismo movimiento repetido.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
fun ComposeScene.enviarToque(e: EventoDeToque) {
    require(!e.cancela) { "una cancelación se entrega con reiniciarGestos(), nunca como «soltar»" }
    // El primer dedo empieza con Compose limpio, sin el mouse (en Android el toque también lo saca). false = algún dueño
    // está procesando otro evento: no se limpió nada y el dedo sigue como antes de este arreglo (nunca se cierra el POS).
    if (e.tipo == TipoDeEvento.PRESIONA && e.punteros.size == 1) reiniciarGestos(setOf(0L))
    sendPointerEvent(
        eventType = when (e.tipo) {
            TipoDeEvento.PRESIONA -> PointerEventType.Press
            TipoDeEvento.MUEVE -> PointerEventType.Move
            TipoDeEvento.SUELTA -> PointerEventType.Release
        },
        pointers = e.punteros.map { ComposeScenePointer(PointerId(it.id), it.posicion, it.presionado, PointerType.Touch) },
        timeMillis = e.tiempoMs,
        button = if (e.tipo == TipoDeEvento.MUEVE) null else PointerButton.Primary,   // como el mouse: Compose pasa a modo táctil
    )
}

/**
 * Reinicia TODO gesto en curso (dedo o mouse) en la escena y en sus capas, SIN mandar ningún evento: lo que Android hace
 * con ACTION_CANCEL. `dedos` = los ids que Compose tenía de este gesto: se borran también sus posiciones guardadas.
 * `false` = Compose está procesando otro evento en este momento (se llamó desde un bucle anidado): no se tocó nada y hay
 * que reintentar después.
 */
@OptIn(InternalComposeUiApi::class)
fun ComposeScene.reiniciarGestos(dedos: Set<Long> = emptySet()): Boolean {
    val procesadores = duenos(this).map { checkNotNull(leerCampo(it, "pointerInputEventProcessor")) { "sin pointerInputEventProcessor" } }
    if (procesadores.any { leerCampo(it, "isProcessing") == true }) return false
    procesadores.forEach { llamar(it, "processCancel") }
    val entrada = checkNotNull(leerCampo(this, "inputHandler")) { "sin inputHandler" }
    llamar(checkNotNull(leerCampo(entrada, "syntheticEventSender")) { "sin syntheticEventSender" }, "reset")
    @Suppress("UNCHECKED_CAST")
    val posiciones = checkNotNull(leerCampo(entrada, "pointerPositions")) { "sin pointerPositions" } as MutableMap<PointerId, Offset>
    dedos.forEach { posiciones.remove(PointerId(it)) }
    escribirCampo(this, "gestureOwner", null)
    return true
}

/** La escena y cada capa (diálogos, hojas, menús) tienen su propio dueño de entrada. */
private fun duenos(escena: Any): List<Any> = buildList {
    add(checkNotNull(leerCampo(escena, "mainOwner")) { "la escena no es de un solo lienzo" })
    (leerCampo(escena, "layers") as? List<*>)?.toList()?.forEach { capa -> capa?.let { llamar(it, "getOwner") }?.let(::add) }
}

/**
 * La escena de Compose de una ventana, por reflexión sobre CMP 1.7.3 (no hay API pública; el toque nativo oficial es el
 * PR JetBrains/compose-multiplatform-core#3218, abierto). `caminoFaltante()` lo fija en una prueba.
 */
@OptIn(InternalComposeUiApi::class)
class EscenaDeVentana private constructor(private val mediador: Any, val escena: ComposeScene) {
    /** El componente de Skia (dentro va el lienzo nativo que recibe los WM_POINTER). */
    val lienzo: JComponent get() = llamar(mediador, "getContentComponent") as JComponent

    /** Respecto a él calcula Compose la posición del mouse (`ComposeSceneMediator.getPosition`). */
    val contenedor: JComponent get() = leerCampo(mediador, "container") as JComponent

    /** La capa (diálogo, hoja) con el foco de entrada, o null si es la pantalla principal. Sólo se compara por identidad. */
    fun capaEnfocada(): Any? = leerCampo(escena, "focusedLayer")

    val esquina: Offset get() = (llamar(mediador, "getSceneBoundsInPx") as Rect?)?.topLeft ?: Offset.Zero

    companion object {
        private data class Paso(val clase: String, val miembro: String, val esCampo: Boolean)

        private val CAMINO = listOf(
            Paso("androidx.compose.ui.awt.ComposeWindow", "composePanel", true),
            Paso("androidx.compose.ui.awt.ComposeWindowPanel", "_composeContainer", true),
            Paso("androidx.compose.ui.scene.ComposeContainer", "mediator", true),
            Paso("androidx.compose.ui.scene.ComposeSceneMediator", "getScene", false),
            Paso("androidx.compose.ui.scene.ComposeSceneMediator", "getContentComponent", false),
            Paso("androidx.compose.ui.scene.ComposeSceneMediator", "getSceneBoundsInPx", false),
            Paso("androidx.compose.ui.scene.ComposeSceneMediator", "container", true),
            Paso("androidx.compose.ui.scene.CanvasLayersComposeSceneImpl", "mainOwner", true),
            Paso("androidx.compose.ui.scene.CanvasLayersComposeSceneImpl", "layers", true),
            Paso("androidx.compose.ui.scene.CanvasLayersComposeSceneImpl", "gestureOwner", true),
            Paso("androidx.compose.ui.scene.CanvasLayersComposeSceneImpl", "focusedLayer", true),
            Paso("androidx.compose.ui.scene.CanvasLayersComposeSceneImpl\$AttachedComposeSceneLayer", "getOwner", false),
            Paso("androidx.compose.ui.scene.BaseComposeScene", "inputHandler", true),
            Paso("androidx.compose.ui.scene.ComposeSceneInputHandler", "syntheticEventSender", true),
            Paso("androidx.compose.ui.scene.ComposeSceneInputHandler", "pointerPositions", true),
            Paso("androidx.compose.ui.input.pointer.SyntheticEventSender", "reset", false),
            Paso("androidx.compose.ui.node.RootNodeOwner", "pointerInputEventProcessor", true),
            Paso("androidx.compose.ui.input.pointer.PointerInputEventProcessor", "processCancel", false),
            Paso("androidx.compose.ui.input.pointer.PointerInputEventProcessor", "isProcessing", true),
        )

        /** `null` si la versión de Compose trae todo lo que usa el puente; si no, qué falta. No carga AWT. */
        fun caminoFaltante(): String? = CAMINO.firstNotNullOfOrNull { p ->
            val c = runCatching { Class.forName(p.clase, false, EscenaDeVentana::class.java.classLoader) }.getOrNull()
                ?: return@firstNotNullOfOrNull "falta la clase ${p.clase}"
            val existe = if (p.esCampo) c.declaredFields.any { it.name == p.miembro }
            else c.declaredMethods.any { it.name == p.miembro && it.parameterCount == 0 }
            if (existe) null else "falta ${p.clase}.${p.miembro}"
        }

        /** Se llama DESPUÉS del primer cuadro: antes, el contenedor todavía no existe. */
        fun de(ventana: ComposeWindow): Result<EscenaDeVentana> = runCatching {
            caminoFaltante()?.let { error("Compose cambió: $it") }
            val panel = leerCampo(ventana, "composePanel") ?: error("composePanel es null")
            val contenedor = leerCampo(panel, "_composeContainer") ?: error("_composeContainer es null")
            val mediador = leerCampo(contenedor, "mediator") ?: error("mediator es null")
            val escena = llamar(mediador, "getScene") as ComposeScene
            // Con compose.layers.type=WINDOW|COMPONENT los diálogos irían en otras ventanas y reiniciar no los alcanzaría.
            checkNotNull(leerCampo(escena, "mainOwner")) { "la escena no es de un solo lienzo (${escena.javaClass.simpleName})" }
            EscenaDeVentana(mediador, escena)
        }
    }
}

/** Campo privado declarado en la clase del objeto o en sus padres; `null` si no existe. */
private fun leerCampo(obj: Any, nombre: String): Any? {
    var c: Class<*>? = obj.javaClass
    while (c != null) {
        c.declaredFields.firstOrNull { it.name == nombre }?.let { return it.apply { isAccessible = true }.get(obj) }
        c = c.superclass
    }
    return null
}

private fun escribirCampo(obj: Any, nombre: String, valor: Any?) {
    var c: Class<*>? = obj.javaClass
    while (c != null) {
        c.declaredFields.firstOrNull { it.name == nombre }?.let { it.apply { isAccessible = true }.set(obj, valor); return }
        c = c.superclass
    }
    error("falta ${obj.javaClass.name}.$nombre")
}

private fun llamar(obj: Any, nombre: String): Any? {
    var c: Class<*>? = obj.javaClass
    while (c != null) {
        c.declaredMethods.firstOrNull { it.name == nombre && it.parameterCount == 0 }
            ?.let { return it.apply { isAccessible = true }.invoke(obj) }
        c = c.superclass
    }
    error("falta ${obj.javaClass.name}.$nombre()")
}

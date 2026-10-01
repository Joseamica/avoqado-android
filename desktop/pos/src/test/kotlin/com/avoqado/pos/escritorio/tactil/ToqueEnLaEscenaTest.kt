package com.avoqado.pos.escritorio.tactil

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La escena REAL de Compose, sin pantalla. El dedo pasa por TraductorDeToques + enviarToque/reiniciarGestos, lo mismo que
 * en Windows. El control (arrastre como mouse, lo que Windows entrega hoy) demuestra que las pruebas distinguen.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class ToqueEnLaEscenaTest {
    private fun ImageComposeScene.interna(): ComposeScene =
        ImageComposeScene::class.java.getDeclaredField("scene").apply { isAccessible = true }.get(this) as ComposeScene

    /** Lo mismo que hace la entrega de producción con cada evento. */
    private fun ImageComposeScene.entregar(e: EventoDeToque?) {
        if (e == null) return
        if (e.cancela) assertTrue(interna().reiniciarGestos(e.punteros.map { it.id }.toSet()), "fuera de una entrega, Compose no está ocupado")
        else interna().enviarToque(e)
    }

    /** Las posiciones que Compose guarda por dedo (ComposeSceneInputHandler.pointerPositions), por reflexión. */
    private fun ImageComposeScene.posicionesGuardadas(): List<Long> {
        var c: Class<*>? = interna().javaClass
        var entrada: Any? = null
        while (c != null && entrada == null) {
            entrada = c.declaredFields.firstOrNull { it.name == "inputHandler" }?.apply { isAccessible = true }?.get(interna())
            c = c.superclass
        }
        val mapa = entrada!!.javaClass.getDeclaredField("pointerPositions").apply { isAccessible = true }.get(entrada) as Map<*, *>
        return mapa.keys.map { (it as PointerId).value }
    }

    private class Reloj(var t: Long = 1_000L)

    private fun ImageComposeScene.cuadros(reloj: Reloj, n: Int = 1) = repeat(n) { reloj.t += 16; render(reloj.t * 1_000_000) }

    private fun ImageComposeScene.dedo(tr: TraductorDeToques, reloj: Reloj, id: Int, x: Int, y: Int, fase: FaseDeToque) {
        entregar(tr.traducir(ContactoDeWindows(id, x, y, fase, reloj.t)))
        cuadros(reloj)
    }

    private fun ImageComposeScene.tocar(tr: TraductorDeToques, reloj: Reloj, id: Int, x: Int, y: Int, fin: FaseDeToque = FaseDeToque.SUBE) {
        entregar(tr.traducir(ContactoDeWindows(id, x, y, FaseDeToque.BAJA, reloj.t)))
        reloj.t += 60; render(reloj.t * 1_000_000)
        entregar(tr.traducir(ContactoDeWindows(id, x, y, fin, reloj.t)))
        cuadros(reloj, 5)
    }

    private fun traductor() = TraductorDeToques { x, y -> Offset(x.toFloat(), y.toFloat()) }

    private fun conEscena(ancho: Int, alto: Int, contenido: @Composable () -> Unit, prueba: (ImageComposeScene, Reloj) -> Unit) {
        val s = ImageComposeScene(ancho, alto, Density(1f), content = contenido)
        try {
            val reloj = Reloj().also { s.render(it.t * 1_000_000) }
            prueba(s, reloj)
        } finally {
            s.close()
        }
    }

    // --- deslizar ---

    private val ys = (0..20).map { 350 - it * 15 }   // de y=350 a y=50: 300 px sobre filas de 40 px

    private fun lista(estado: LazyListState): @Composable () -> Unit = {
        LazyColumn(Modifier.fillMaxSize(), state = estado) { items(100) { Box(Modifier.fillMaxWidth().height(40.dp)) } }
    }

    @Test fun `el dedo por el puente desplaza la lista`() {
        val estado = LazyListState()
        conEscena(400, 400, lista(estado)) { s, reloj ->
            val tr = traductor()
            ys.forEachIndexed { i, y -> s.dedo(tr, reloj, 7, 200, y, if (i == 0) FaseDeToque.BAJA else FaseDeToque.MUEVE) }
            s.dedo(tr, reloj, 7, 200, ys.last(), FaseDeToque.SUBE)
            s.cuadros(reloj, 40)
            assertTrue(estado.firstVisibleItemIndex >= 5, "debió mover al menos 5 filas; movió ${estado.firstVisibleItemIndex}")
        }
    }

    @Test fun `arrastrar como mouse NO la desplaza, que es lo que Windows entrega hoy`() {
        val estado = LazyListState()
        conEscena(400, 400, lista(estado)) { s, reloj ->
            ys.forEachIndexed { i, y ->
                s.sendPointerEvent(
                    if (i == 0) PointerEventType.Press else PointerEventType.Move, Offset(200f, y.toFloat()),
                    timeMillis = reloj.t, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true),
                )
                s.cuadros(reloj)
            }
            s.sendPointerEvent(PointerEventType.Release, Offset(200f, ys.last().toFloat()), timeMillis = reloj.t, type = PointerType.Mouse, buttons = PointerButtons())
            s.cuadros(reloj, 40)
            assertEquals(0, estado.firstVisibleItemIndex)
        }
    }

    /**
     * Medido en Windows (30-sep): con el mouse quieto encima, Compose 1.7.3 se repite su último movimiento cada vez que algo
     * cambia de lugar en pantalla; si ese repetido no cambió nada, no reparte y deja el mouse guardado en cada nodo, y se le
     * pega al siguiente dedo («mouse arriba + dedo abajo»): la lista no reconoce un dedo que empieza y no se desliza.
     */
    @Test fun `un mouse quieto encima de la lista no le quita el deslizamiento al dedo`() {
        val estado = LazyListState()
        conEscena(400, 400, lista(estado)) { s, reloj ->
            s.sendPointerEvent(PointerEventType.Enter, Offset(300f, 200f), timeMillis = reloj.t, type = PointerType.Mouse, buttons = PointerButtons())
            s.sendPointerEvent(PointerEventType.Move, Offset(300f, 210f), timeMillis = reloj.t, type = PointerType.Mouse, buttons = PointerButtons())
            s.sendPointerEvent(PointerEventType.Move, Offset(300f, 210f), timeMillis = reloj.t, type = PointerType.Mouse, buttons = PointerButtons())   // el repetido
            s.cuadros(reloj)
            val tr = traductor()
            ys.forEachIndexed { i, y -> s.dedo(tr, reloj, 7, 200, y, if (i == 0) FaseDeToque.BAJA else FaseDeToque.MUEVE) }
            s.dedo(tr, reloj, 7, 200, ys.last(), FaseDeToque.SUBE)
            s.cuadros(reloj, 40)
            assertTrue(estado.firstVisibleItemIndex >= 5, "debió mover al menos 5 filas; movió ${estado.firstVisibleItemIndex}")
        }
    }

    /** Codex (30-sep): reiniciar con el mouse encima y dar un clic sin moverlo deja nodos «sin entrar»; un Exit ahí no limpia. */
    private fun ImageComposeScene.mouseQueNoLimpiaUnExit() {
        sendPointerEvent(PointerEventType.Enter, Offset(300f, 210f), type = PointerType.Mouse, buttons = PointerButtons())
        assertTrue(interna().reiniciarGestos())
        sendPointerEvent(PointerEventType.Press, Offset(300f, 210f), type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, Offset(300f, 210f), type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
    }

    private fun ImageComposeScene.deslizarConElDedo(reloj: Reloj) {
        val tr = traductor()
        ys.forEachIndexed { i, y -> dedo(tr, reloj, 7, 200, y, if (i == 0) FaseDeToque.BAJA else FaseDeToque.MUEVE) }
        dedo(tr, reloj, 7, 200, ys.last(), FaseDeToque.SUBE)
        cuadros(reloj, 40)
    }

    @Test fun `tras un reinicio y un clic sin mover el mouse, el dedo también desliza`() {
        val estado = LazyListState()
        conEscena(400, 400, lista(estado)) { s, reloj ->
            s.mouseQueNoLimpiaUnExit()
            s.cuadros(reloj)
            s.deslizarConElDedo(reloj)
            assertTrue(estado.firstVisibleItemIndex >= 5, "debió mover al menos 5 filas; movió ${estado.firstVisibleItemIndex}")
        }
    }

    @Test fun `lo mismo con la lista dentro de una ventana emergente`() {
        val estado = LazyListState()
        conEscena(400, 400, {
            Popup(alignment = Alignment.Center, properties = PopupProperties(focusable = true), onDismissRequest = {}) {
                Box(Modifier.size(400.dp)) { lista(estado)() }
            }
        }) { s, reloj ->
            s.cuadros(reloj)
            s.mouseQueNoLimpiaUnExit()
            s.cuadros(reloj)
            s.deslizarConElDedo(reloj)
            assertTrue(estado.firstVisibleItemIndex >= 5, "debió mover al menos 5 filas; movió ${estado.firstVisibleItemIndex}")
        }
    }

    @Test fun `el mouse vuelve a funcionar en cuanto se mueve, después del dedo`() {
        var entradas = 0
        conEscena(400, 400, {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) if (awaitPointerEvent().type == PointerEventType.Enter) entradas++ }
            })
        }) { s, reloj ->
            s.sendPointerEvent(PointerEventType.Enter, Offset(300f, 200f), timeMillis = reloj.t, type = PointerType.Mouse, buttons = PointerButtons())
            s.cuadros(reloj)
            s.tocar(traductor(), reloj, 7, 100, 100)
            val antes = entradas
            s.sendPointerEvent(PointerEventType.Move, Offset(310f, 200f), timeMillis = reloj.t, type = PointerType.Mouse, buttons = PointerButtons())
            s.cuadros(reloj)
            assertEquals(antes + 1, entradas, "el mouse debió volver a entrar al moverse")
            assertTrue(0L in s.posicionesGuardadas(), "Compose debió volver a saber dónde está el mouse")
        }
    }

    @Test fun `un arrastre cancelado se queda donde quedó, sin inercia`() {
        val estado = LazyListState()
        conEscena(400, 400, lista(estado)) { s, reloj ->
            val tr = traductor()
            ys.take(12).forEachIndexed { i, y -> s.dedo(tr, reloj, 7, 200, y, if (i == 0) FaseDeToque.BAJA else FaseDeToque.MUEVE) }
            s.dedo(tr, reloj, 7, 200, 0, FaseDeToque.CANCELA)
            val alCancelar = estado.firstVisibleItemIndex to estado.firstVisibleItemScrollOffset
            assertTrue(alCancelar.first >= 3, "el arrastre sí debió mover antes de cancelar; movió ${alCancelar.first}")
            s.cuadros(reloj, 40)
            assertEquals(alCancelar, estado.firstVisibleItemIndex to estado.firstVisibleItemScrollOffset)
        }
    }

    // --- tocar ---

    @Test fun `un toque del dedo es un clic`() {
        var clics = 0
        conEscena(200, 200, { Box(Modifier.fillMaxSize().clickable { clics++ }) }) { s, reloj ->
            s.tocar(traductor(), reloj, 3, 100, 100)
            assertEquals(1, clics)
        }
    }

    /**
     * Una indicación que sólo anota lo que le llega. En escritorio el `clickable` enfoca lo tocado con mouse o con dedo
     * (CMP 1.7.3), pero la indicación sólo recibe `FocusInteraction.Focus` en modo teclado: eso es la MARCA del foco.
     */
    private class Anotadora(val vistos: MutableList<Interaction> = mutableListOf()) : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {
            override fun onAttach() {
                coroutineScope.launch { interactionSource.interactions.collect { vistos += it } }
            }
        }
        override fun equals(other: Any?) = other === this
        override fun hashCode() = System.identityHashCode(this)
    }

    @Test fun `el dedo pone a Compose en modo táctil y no marca el foco de lo que tocó`() {
        val marca = Anotadora()
        lateinit var modos: InputModeManager
        conEscena(200, 200, {
            modos = LocalInputModeManager.current
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = marca) { })
        }) { s, reloj ->
            modos.requestInputMode(InputMode.Keyboard)   // como en la PC: hay teclado
            s.cuadros(reloj)
            s.tocar(traductor(), reloj, 1, 100, 100)
            assertEquals(InputMode.Touch, modos.inputMode)
            assertTrue(marca.vistos.none { it is FocusInteraction.Focus }, "con el mouse la marca del foco no se ve; con el dedo tampoco")
        }
    }

    @Test fun `un toque cancelado NO es un clic y el siguiente toque sí cuenta`() {
        var clics = 0
        conEscena(200, 200, { Box(Modifier.fillMaxSize().clickable { clics++ }) }) { s, reloj ->
            val tr = traductor()
            s.tocar(tr, reloj, 3, 100, 100, fin = FaseDeToque.CANCELA)
            assertEquals(0, clics, "Windows canceló el contacto: no debe ejecutarse el botón")
            s.tocar(tr, reloj, 4, 100, 100)
            assertEquals(1, clics, "después de una cancelación el dedo tiene que seguir funcionando")
        }
    }

    @Test fun `con dos dedos, cancelar uno cancela el gesto y soltar el otro no es un clic`() {
        var clics = 0
        conEscena(200, 200, { Box(Modifier.fillMaxSize().clickable { clics++ }) }) { s, reloj ->
            val tr = traductor()
            s.dedo(tr, reloj, 1, 50, 50, FaseDeToque.BAJA)
            s.dedo(tr, reloj, 2, 60, 60, FaseDeToque.BAJA)
            s.dedo(tr, reloj, 1, 50, 50, FaseDeToque.CANCELA)
            s.dedo(tr, reloj, 2, 60, 60, FaseDeToque.SUBE)
            s.cuadros(reloj, 5)
            assertEquals(0, clics)
            s.tocar(tr, reloj, 5, 100, 100)
            assertEquals(1, clics, "exactamente uno: un dedo viejo «presionado» se soltaría aquí como clic fantasma")
        }
    }

    @Test fun `cancelar con un diseño pendiente no deja un dedo fantasma`() {
        var clics = 0
        var grande by mutableStateOf(false)
        conEscena(200, 200, { Box(Modifier.size(if (grande) 120.dp else 100.dp).clickable { clics++ }) }) { s, reloj ->
            val tr = traductor()
            s.dedo(tr, reloj, 3, 50, 50, FaseDeToque.BAJA)
            s.entregar(tr.traducir(ContactoDeWindows(3, 50, 50, FaseDeToque.CANCELA, reloj.t)))
            grande = true   // el diseño cambia bajo el dedo quieto: Compose querrá «actualizar la posición del puntero»
            Snapshot.sendApplyNotifications()
            s.cuadros(reloj, 5)
            assertEquals(0, clics)
            s.tocar(tr, reloj, 4, 50, 50)
            assertEquals(1, clics, "exactamente uno: el contacto cancelado no debe revivir con el cambio de diseño")
        }
    }

    @Test fun `cancelar borra las posiciones guardadas de esos dedos`() {
        conEscena(200, 200, { Box(Modifier.fillMaxSize()) }) { s, reloj ->
            val tr = traductor()
            s.dedo(tr, reloj, 7, 50, 50, FaseDeToque.BAJA)
            s.dedo(tr, reloj, 8, 60, 60, FaseDeToque.BAJA)
            assertTrue(s.posicionesGuardadas().containsAll(listOf(7L, 8L)), "Compose sí las guardó: ${s.posicionesGuardadas()}")
            s.dedo(tr, reloj, 7, 50, 50, FaseDeToque.CANCELA)
            assertTrue(s.posicionesGuardadas().none { it == 7L || it == 8L }, "quedaron dedos cancelados: ${s.posicionesGuardadas()}")
        }
    }

    @Test fun `en un diálogo de Compose, cancelar no agrega ni un clic ni otro «toque afuera»`() {
        var clics = 0
        var fondo = 0
        var cierres = 0
        val contenido: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().clickable { fondo++ })
            Popup(alignment = Alignment.Center, onDismissRequest = { cierres++ }, properties = PopupProperties(focusable = true)) {
                Box(Modifier.size(100.dp).clickable { clics++ })   // de 50 a 150 en una escena de 200
            }
        }
        conEscena(200, 200, contenido) { s, reloj ->
            val tr = traductor()
            s.cuadros(reloj, 3)
            s.tocar(tr, reloj, 1, 100, 100)
            assertEquals(1, clics, "el dedo llega a la capa del diálogo")
            s.tocar(tr, reloj, 2, 100, 100, fin = FaseDeToque.CANCELA)
            assertEquals(1, clics)
            assertEquals(0, cierres, "adentro, cancelar no avisa «toque afuera»")
            s.dedo(tr, reloj, 3, 10, 10, FaseDeToque.BAJA)   // afuera: Compose avisa al BAJAR, igual que Android
            val cierresAlBajar = cierres
            assertEquals(1, cierresAlBajar, "Compose avisa «toque afuera» al BAJAR, igual que Android")
            s.dedo(tr, reloj, 3, 10, 10, FaseDeToque.CANCELA)
            s.cuadros(reloj, 5)
            assertEquals(cierresAlBajar, cierres, "cancelar no agrega otro cierre")
            assertEquals(0, fondo, "ni un clic en lo de atrás")
            s.tocar(tr, reloj, 4, 100, 100)
            assertEquals(2, clics, "y el siguiente toque funciona")
        }
    }

    @Test fun `tras cancelar, el siguiente toque busca de nuevo la capa bajo el dedo`() {
        var clics = 0
        var fondo = 0
        var mostrar by mutableStateOf(false)
        val contenido: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().clickable { fondo++ })
            if (mostrar) Popup(alignment = Alignment.Center, properties = PopupProperties(focusable = true), onDismissRequest = {}) {
                Box(Modifier.size(100.dp).clickable { clics++ })
            }
        }
        conEscena(200, 200, contenido) { s, reloj ->
            val tr = traductor()
            s.dedo(tr, reloj, 1, 100, 100, FaseDeToque.BAJA)
            s.dedo(tr, reloj, 1, 100, 100, FaseDeToque.CANCELA)
            mostrar = true
            Snapshot.sendApplyNotifications()
            s.cuadros(reloj, 5)
            s.tocar(tr, reloj, 2, 100, 100)
            assertEquals(1, clics, "el toque debe llegar al diálogo")
            assertEquals(0, fondo, "y no al fondo, que era el dueño del gesto cancelado")
        }
    }

    @Test fun `reiniciar desde adentro de una entrega no toca nada y avisa que hay que reintentar`() {
        var resultado: Boolean? = null
        lateinit var escena: ComposeScene
        val contenido: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Press && resultado == null) resultado = escena.reiniciarGestos()
                    }
                }
            })
        }
        conEscena(200, 200, contenido) { s, reloj ->
            escena = s.interna()
            s.dedo(traductor(), reloj, 1, 100, 100, FaseDeToque.BAJA)
            assertEquals(false, resultado, "Compose estaba procesando: processCancel no haría nada, así que no se finge éxito")
            assertTrue(escena.reiniciarGestos(setOf(1L)), "afuera de la entrega sí se puede")
        }
    }
}

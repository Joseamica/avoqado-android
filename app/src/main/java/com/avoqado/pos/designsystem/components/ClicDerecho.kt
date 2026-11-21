package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * El POS se usa HÍBRIDO: dedo, mouse y teclado a la vez (founder, 20-nov: «este cliente usa mouse y teclado… tiene que
 * estar bien adaptado para todo»; Flautas Coyoacán contestó «pero uso computadora» al «hazle swipe»). La regla: nunca se
 * quita el gesto del dedo, se le agrega al lado el camino del mouse.
 *
 * El clic DERECHO hace lo mismo que mantener presionado ([accion] = el mismo `onLongClick`/`onLongPress`). Va ANTES (por
 * fuera) del `combinedClickable`/`detectTapGestures` de lo que abre: se queda con todo el clic derecho (apretar y soltar),
 * así que lo de abajo no lo ve como un toque y no abre la mesa o el producto además del menú.
 *
 * Sólo reacciona al botón secundario del mouse (o de la pluma): el dedo nunca lo trae, así que en una tableta sin mouse no
 * cambia nada. En una tableta de ANDROID con mouse también sirve (es el mismo gesto).
 */
fun Modifier.clicDerecho(accion: () -> Unit): Modifier = this then ClicDerechoElemento(accion)

private data class ClicDerechoElemento(val accion: () -> Unit) : ModifierNodeElement<ClicDerechoNodo>() {
    override fun create() = ClicDerechoNodo(accion)
    override fun update(node: ClicDerechoNodo) { node.accion = accion }
    override fun InspectorInfo.inspectableProperties() { name = "clicDerecho" }
}

internal class ClicDerechoNodo(var accion: () -> Unit) : Modifier.Node(), PointerInputModifierNode {
    /** Desde que se apretó el botón derecho hasta que se soltaron todos: todo es de este gesto. */
    private var gestoDerecho = false

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        // Initial va de afuera hacia adentro: lo vemos antes que el `combinedClickable` que está más adentro.
        if (pass != PointerEventPass.Initial) return
        if (!gestoDerecho) {
            if (pointerEvent.type != PointerEventType.Press || !pointerEvent.buttons.isSecondaryPressed) return
            gestoDerecho = true
            pointerEvent.changes.forEach { it.consume() }
            accion()
            return
        }
        // El menú que abrió puede quedarse con el «soltar» (es otra ventana): un clic izquierdo nuevo ya no es de este gesto.
        if (pointerEvent.type == PointerEventType.Press && !pointerEvent.buttons.isSecondaryPressed) {
            gestoDerecho = false
            return
        }
        pointerEvent.changes.forEach { it.consume() }
        if (pointerEvent.changes.none { it.pressed }) gestoDerecho = false
    }

    override fun onCancelPointerInput() { gestoDerecho = false }
}

/**
 * El botón «⋯» visible, SÓLO en el POS de Windows: abre el mismo menú que mantener presionado o el clic derecho, con un
 * clic y sin adivinar que existe (founder, 20-nov: «cada acción importante tiene un camino con UN clic»). En ANDROID no
 * se pinta: ahí la fila se queda como siempre.
 */
@Composable
fun BotonDeMasAcciones(onClick: () -> Unit, modifier: Modifier = Modifier, descripcion: String = "Más acciones") {
    if (!LocalEsEscritorio.current) return
    IconButton(onClick = onClick, modifier = modifier.size(32.dp)) {
        Icon(
            imageVector = Icons.Filled.MoreHoriz,
            contentDescription = descripcion,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

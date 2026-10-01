package com.avoqado.pos.escritorio.tactil

import androidx.compose.ui.geometry.Offset

/** Qué hizo un dedo, en el idioma de Windows (WM_POINTERDOWN/UPDATE/UP, cancelación). */
enum class FaseDeToque { BAJA, MUEVE, SUBE, CANCELA }

/** Un dedo según Windows: posición en píxeles FÍSICOS del cliente del lienzo nativo; tiempo del propio contacto. */
data class ContactoDeWindows(val id: Int, val clienteX: Int, val clienteY: Int, val fase: FaseDeToque, val tiempoMs: Long)

enum class TipoDeEvento { PRESIONA, MUEVE, SUELTA }

data class Puntero(val id: Long, val posicion: Offset, val presionado: Boolean)

/**
 * Lo que recibe la escena de Compose: SIEMPRE todos los dedos vivos, como en Android. `cancela = true` NO se manda a
 * Compose: quien entrega reinicia los gestos (un «soltar» sería un clic, una inercia o un «toque afuera»).
 */
data class EventoDeToque(val tipo: TipoDeEvento, val punteros: List<Puntero>, val tiempoMs: Long, val cancela: Boolean = false)

/** Convierte los contactos de Windows en eventos de Compose. Lo usa UN solo hilo (el de Swing): no es seguro entre hilos. */
class TraductorDeToques(private val aEscena: (clienteX: Int, clienteY: Int) -> Offset) {
    private val vivos = LinkedHashMap<Int, Offset>()

    val hayDedos: Boolean get() = vivos.isNotEmpty()

    fun traducir(c: ContactoDeWindows): EventoDeToque? = when (c.fase) {
        FaseDeToque.BAJA -> {
            val nuevo = c.id !in vivos
            vivos[c.id] = aEscena(c.clienteX, c.clienteY)
            EventoDeToque(if (nuevo) TipoDeEvento.PRESIONA else TipoDeEvento.MUEVE, todos(), c.tiempoMs)
        }
        FaseDeToque.MUEVE -> {
            val nueva = aEscena(c.clienteX, c.clienteY)
            if (c.id !in vivos || vivos[c.id] == nueva) null
            else {
                vivos[c.id] = nueva
                EventoDeToque(TipoDeEvento.MUEVE, todos(), c.tiempoMs)
            }
        }
        FaseDeToque.SUBE -> if (c.id !in vivos) null else {
            vivos[c.id] = aEscena(c.clienteX, c.clienteY)
            val evento = EventoDeToque(TipoDeEvento.SUELTA, todos(soltando = setOf(c.id)), c.tiempoMs)
            vivos.remove(c.id)
            evento
        }
        // Compose no sabe cancelar un solo puntero: se cancela el gesto entero y los demás dedos quedan olvidados.
        FaseDeToque.CANCELA -> if (c.id !in vivos) null else soltarTodos(c.tiempoMs)
    }

    /** Cancela el gesto entero: la ventana perdió el foco, el puente se apagó, se abrió un modal o algo falló. */
    fun soltarTodos(tiempoMs: Long): EventoDeToque? {
        if (vivos.isEmpty()) return null
        val evento = EventoDeToque(TipoDeEvento.SUELTA, todos(soltando = vivos.keys.toSet()), tiempoMs, cancela = true)
        vivos.clear()
        return evento
    }

    private fun todos(soltando: Set<Int> = emptySet()) =
        vivos.map { (id, pos) -> Puntero(id.toLong(), pos, presionado = id !in soltando) }
}

/**
 * Píxeles físicos del cliente del lienzo nativo → posición en la escena de Compose. Imita a
 * `ComposeSceneMediator.getPosition(MouseEvent)` de CMP 1.7.3: punto relativo al contenedor (lógico) × densidad − esquina.
 */
fun aEscena(
    clienteX: Int,
    clienteY: Int,
    escala: Double,
    origenLienzoEnContenedor: Offset,
    densidad: Float,
    esquinaEscena: Offset,
): Offset = Offset(
    ((clienteX / escala).toFloat() + origenLienzoEnContenedor.x) * densidad - esquinaEscena.x,
    ((clienteY / escala).toFloat() + origenLienzoEnContenedor.y) * densidad - esquinaEscena.y,
)

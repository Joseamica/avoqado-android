package com.avoqado.pos.escritorio.teclado

enum class Puntero { DEDO, MOUSE }

/** Decide si el teclado en pantalla se ve. Pura: sin Swing ni Compose. */
class TecladoHibrido {
    var visible: Boolean = false
        private set

    /** El campo con el foco entró (activa=true) o salió (false) del modo escritura; [ultimo] = el último puntero que presionó. */
    fun alCambiarEntrada(activa: Boolean, ultimo: Puntero?) {
        visible = activa && ultimo == Puntero.DEDO
    }

    /** 150 ms después de soltar un DEDO: si hay un campo en modo escritura, se muestra (volver a tocar el campo lo reabre). */
    fun trasSoltarDedo(entradaActiva: Boolean) {
        if (entradaActiva) visible = true
    }

    fun alTeclaFisica() { visible = false }          // tecla física o lector de códigos
    fun alPresionarConMouse() { visible = false }
    fun alOcultarAMano() { visible = false }
}

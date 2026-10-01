package com.avoqado.pos.escritorio.teclado

import kotlin.math.hypot

/** ¿El dedo bajó y subió sin moverse más que [tolerancia] (píxeles) y sin otro dedo? Entonces fue un toque; si no, un gesto. */
class DetectorDeToque(private val tolerancia: Float) {
    private var x0 = 0f
    private var y0 = 0f
    private var gesto = false

    fun alBajar(x: Float, y: Float, dedos: Int) { x0 = x; y0 = y; gesto = dedos > 1 }

    fun alMover(x: Float, y: Float, dedos: Int) {
        if (dedos > 1 || hypot(x - x0, y - y0) > tolerancia) gesto = true
    }

    fun fueToque() = !gesto
}

package com.avoqado.pos.pos.presentation.product

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * ¿Es tan claro el color que eligió el negocio que la letra blanca no se lee encima? Pasaba con «Postres» en amarillo
 * (revisión visual en iPhone, 30-oct-2026). Luminancia relativa (WCAG) > 0.45 = el blanco queda debajo de ~2:1 de
 * contraste: amarillo, verde limón, ámbar y pasteles. Los colores fuertes de siempre (naranja, verde, azul, rosa, morado)
 * siguen con letra blanca. Espejo de `Color.esClaro(hex:)` en iOS.
 *
 * Lee «#RRGGBB» y «#AARRGGBB» (el orden de `android.graphics.Color.parseColor`, que pinta el fondo). Puro: sin Android.
 * Un color casi transparente no cuenta: lo que se ve es el fondo de atrás, así que se queda la letra blanca de siempre
 * (Codex r1 #3: «#00FFFFFF» sobre modo oscuro habría dejado letra negra sobre negro).
 */
fun esColorClaro(hex: String): Boolean {
    val limpio = hex.trim().removePrefix("#")
    val rgb = when (limpio.length) {
        6 -> limpio
        8 -> {
            val alfa = limpio.substring(0, 2).toIntOrNull(16) ?: return false
            if (alfa < 128) return false
            limpio.substring(2)
        }
        else -> return false
    }
    val valor = rgb.toLongOrNull(16) ?: return false
    fun lineal(c: Long): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    val r = lineal((valor shr 16) and 0xFF)
    val g = lineal((valor shr 8) and 0xFF)
    val b = lineal(valor and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.45
}

/** ¿Es casi transparente («#AARRGGBB» con alfa < 128)? Lo que se ve entonces es el fondo de la pantalla. */
fun esColorCasiTransparente(hex: String): Boolean {
    val limpio = hex.trim().removePrefix("#")
    if (limpio.length != 8) return false
    val alfa = limpio.substring(0, 2).toIntOrNull(16) ?: return false
    return alfa < 128
}

/**
 * La letra que se lee sobre el color del negocio: oscura si el fondo es claro, blanca si no. Si el color es casi
 * transparente, lo que se ve es el fondo de la pantalla y va [letraDelTema] (Codex r2 #1: «#00FFFFFF» dejaba blanco sobre
 * blanco en modo claro). Espejo de `Color.letraSobre(hex:)` en iOS.
 */
fun letraSobreColorDelNegocio(hex: String, letraDelTema: Color): Color = when {
    esColorCasiTransparente(hex) -> letraDelTema
    esColorClaro(hex) -> Color.Black.copy(alpha = 0.85f)
    else -> Color.White
}

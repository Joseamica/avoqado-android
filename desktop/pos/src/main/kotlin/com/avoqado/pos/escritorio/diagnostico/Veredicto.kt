package com.avoqado.pos.escritorio.diagnostico

enum class Nivel { BIEN, LENTA, NO_SIRVE, INFO, SIN_MEDIR }   // ✅ ⚠️ ❌ ℹ️ (no se pudo medir)

data class DatosDelEquipo(
    val windows: Boolean, val versionSO: String?,          // "10.0" en Windows 10 y 11
    val ramGb: Double?, val nucleos: Int?, val procesador: String?,
    val discoLibreGb: Double?, val anchoPx: Int?, val altoPx: Int?,
    val puntosTactiles: Int?,                               // GetSystemMetrics(SM_MAXIMUMTOUCHES); null fuera de Windows
    val motorDeDibujo: String?, val primerCuadroMs: Long?, val toqueConElDedo: String?,
)

data class Punto(val nombre: String, val nivel: Nivel, val texto: String)

data class Veredicto(val global: Nivel, val puntos: List<Punto>) {
    /** El bloque que va arriba de diagnostico.txt. */
    fun comoTexto(): String {
        val encabezado = when (global) {
            Nivel.BIEN -> "✅ Esta PC sirve para Avoqado POS."
            Nivel.LENTA -> "⚠️ Avoqado POS funciona en esta PC, pero con limitaciones (abajo)."
            else -> "❌ Esta PC no alcanza para Avoqado POS (abajo qué falta)."
        }
        return (listOf(encabezado) + puntos.map { "${simbolo(it.nivel)} ${it.nombre}: ${it.texto}" }).joinToString("\n")
    }

    private fun simbolo(n: Nivel) = when (n) {
        Nivel.BIEN -> "✅"; Nivel.LENTA -> "⚠️"; Nivel.NO_SIRVE -> "❌"; Nivel.INFO, Nivel.SIN_MEDIR -> "ℹ️"
    }
}

// Umbrales: se afinan con una PC barata real. Un solo lugar.
internal const val WINDOWS_MIN = 10.0
internal const val RAM_BIEN_GB = 7.5          // las de 8 GB reportan un poco menos
internal const val RAM_LENTA_GB = 3.5
internal const val NUCLEOS_BIEN = 4
internal const val NUCLEOS_LENTA = 2
internal const val CUADRO_BIEN_MS = 8_000L
internal const val CUADRO_LENTA_MS = 20_000L
internal const val DISCO_BIEN_GB = 2.0
internal const val DISCO_LENTA_GB = 0.5
internal val PANTALLA_BIEN = 1366 to 768
internal val PANTALLA_LENTA = 1024 to 768

private const val NO_MEDIDO = "no se pudo medir (no cuenta en contra)."

fun veredicto(d: DatosDelEquipo): Veredicto {
    val puntos = listOf(
        windows(d), ram(d), procesador(d), primerCuadro(d), motor(d), disco(d), pantalla(d), tactil(d),
    )
    val global = puntos.map { it.nivel }.filter { it in setOf(Nivel.BIEN, Nivel.LENTA, Nivel.NO_SIRVE) }
        .maxByOrNull { it.ordinal } ?: Nivel.BIEN
    return Veredicto(global, puntos)
}

private fun windows(d: DatosDelEquipo): Punto {
    if (!d.windows) return Punto("Windows", Nivel.INFO, "no aplica (esta prueba no corre en Windows).")
    val v = d.versionSO?.substringBefore('.')?.toDoubleOrNull()
        ?: return Punto("Windows", Nivel.SIN_MEDIR, NO_MEDIDO)
    return if (v >= WINDOWS_MIN) Punto("Windows", Nivel.BIEN, "Windows 10 u 11.")
    else Punto("Windows", Nivel.NO_SIRVE, "es una versión vieja (7 u 8). Hace falta Windows 10 u 11.")
}

private fun ram(d: DatosDelEquipo): Punto {
    val r = d.ramGb ?: return Punto("Memoria RAM", Nivel.SIN_MEDIR, NO_MEDIDO)
    val g = "%.1f GB".format(r)
    return when {
        r >= RAM_BIEN_GB -> Punto("Memoria RAM", Nivel.BIEN, "$g, suficiente.")
        r >= RAM_LENTA_GB -> Punto("Memoria RAM", Nivel.LENTA, "$g: funciona, pero va a ir lenta. Recomendado: 8 GB.")
        else -> Punto("Memoria RAM", Nivel.NO_SIRVE, "$g: no alcanza. Hace falta al menos 4 GB (recomendado 8 GB).")
    }
}

private fun procesador(d: DatosDelEquipo): Punto {
    val n = d.nucleos ?: return Punto("Procesador", Nivel.SIN_MEDIR, NO_MEDIDO)
    val nombre = d.procesador?.let { "$it, " } ?: ""
    return when {
        n >= NUCLEOS_BIEN -> Punto("Procesador", Nivel.BIEN, "$nombre$n núcleos.")
        n >= NUCLEOS_LENTA -> Punto("Procesador", Nivel.LENTA, "$nombre$n núcleos: va a ir justo. Recomendado: 4 núcleos o más.")
        else -> Punto("Procesador", Nivel.NO_SIRVE, "$nombre$n núcleo: no alcanza. Hacen falta al menos 2.")
    }
}

private fun primerCuadro(d: DatosDelEquipo): Punto {
    val ms = d.primerCuadroMs ?: return Punto("Tiempo para abrir", Nivel.SIN_MEDIR, NO_MEDIDO)
    val s = "%.1f s".format(ms / 1000.0)
    return when {
        ms <= CUADRO_BIEN_MS -> Punto("Tiempo para abrir", Nivel.BIEN, "la app abrió en $s.")
        ms <= CUADRO_LENTA_MS -> Punto("Tiempo para abrir", Nivel.LENTA, "la app tardó $s en abrir: es lenta. Cierra otros programas o usa una PC con más memoria.")
        else -> Punto("Tiempo para abrir", Nivel.NO_SIRVE, "la app tardó $s en abrir: demasiado para vender con ella.")
    }
}

private fun motor(d: DatosDelEquipo): Punto {
    val m = d.motorDeDibujo ?: return Punto("Gráficos", Nivel.SIN_MEDIR, NO_MEDIDO)
    return when {
        m.startsWith("SOFTWARE") -> Punto("Gráficos", Nivel.LENTA, "$m: dibuja sin la tarjeta de video, las animaciones van a ir lentas. Actualiza el controlador de video.")
        m == "DIRECT3D" || m == "OPENGL" -> Punto("Gráficos", Nivel.BIEN, "usa la tarjeta de video ($m).")
        else -> Punto("Gráficos", Nivel.INFO, m)
    }
}

private fun disco(d: DatosDelEquipo): Punto {
    val g = d.discoLibreGb ?: return Punto("Disco libre", Nivel.SIN_MEDIR, NO_MEDIDO)
    val t = "%.1f GB".format(g)
    return when {
        g >= DISCO_BIEN_GB -> Punto("Disco libre", Nivel.BIEN, "$t libres.")
        g >= DISCO_LENTA_GB -> Punto("Disco libre", Nivel.LENTA, "$t libres: queda poco. Libera espacio (recomendado: 2 GB).")
        else -> Punto("Disco libre", Nivel.NO_SIRVE, "$t libres: no alcanza. Libera espacio en el disco.")
    }
}

private fun pantalla(d: DatosDelEquipo): Punto {
    val w = d.anchoPx
    val h = d.altoPx
    if (w == null || h == null) return Punto("Pantalla", Nivel.SIN_MEDIR, NO_MEDIDO)
    val t = "${w}×$h"
    return when {
        w >= PANTALLA_BIEN.first && h >= PANTALLA_BIEN.second -> Punto("Pantalla", Nivel.BIEN, "$t.")
        w >= PANTALLA_LENTA.first && h >= PANTALLA_LENTA.second -> Punto("Pantalla", Nivel.LENTA, "$t: chica, se va a ver apretado. Recomendado: 1366×768 o más.")
        else -> Punto("Pantalla", Nivel.NO_SIRVE, "$t: muy chica para el punto de venta. Hace falta al menos 1024×768.")
    }
}

private fun tactil(d: DatosDelEquipo): Punto {
    val p = d.puntosTactiles ?: return Punto("Pantalla táctil", Nivel.INFO, "no aplica fuera de Windows.")
    return if (p > 0) Punto("Pantalla táctil", Nivel.INFO, "táctil con $p puntos: deslizar con el dedo funciona.")
    else Punto(
        "Pantalla táctil", Nivel.INFO,
        "Windows no ve una pantalla táctil: si la pantalla sí responde al dedo, se está haciendo pasar por mouse y deslizar no va a funcionar. Revisa el controlador de la pantalla.",
    )
}

package com.avoqado.pos.escritorio.dibujo

import com.avoqado.pos.escritorio.diagnostico.DatosDelEquipo
import java.security.SecureRandom
import java.util.Locale

/** «Misma apertura»: 8 hex al azar, uno por proceso. No es una hora; el reloj de la PC nunca viaja (hz:a5fa2439:D1). */
internal val ID_DE_PROCESO: String = String.format(Locale.ROOT, "%08x", SecureRandom().nextInt())

// Lo que acepta el servidor (avoqado-server, deviceDiagnostics.ts): fuera de esto no se manda nada que lo haga tirar el reporte.
private val MOTORES = setOf("DIRECT3D", "OPENGL", "SOFTWARE_FAST", "SOFTWARE_COMPAT")
private val PROCESO = Regex("[0-9a-f]{8}")
private const val REVISION_MAX = 1_000_000_000L
private const val TOPE = 512

/**
 * La cabecera `x-device-diagnostics` del sondeo: `v=1;proc=…;rev=…;api=…;paint=…;light=…;perf=…;set=…;forced=…;fail=…;hand=…`
 * y, si se conocen, `cpu`, `cores`, `ram` y `screen`. null fuera de Windows, sin pintar o con algo que el servidor rechazaría.
 */
fun cabeceraDelEquipo(e: Estado, equipo: DatosDelEquipo, proceso: String, revision: Long, esWindows: Boolean): String? {
    val api = e.motor?.takeIf { it in MOTORES } ?: return null
    val paint = e.pintarMs?.coerceIn(0, PINTAR_TOPE_MS) ?: return null
    val perf = when (e.rendimiento) {
        Rendimiento.FLUIDA -> "FLUID"
        Rendimiento.LIGERA -> "LIGHT"
        Rendimiento.NO_ALCANZA -> "INSUFFICIENT"
        null -> return null
    }
    if (!esWindows || revision !in 1..REVISION_MAX || !PROCESO.matches(proceso)) return null
    val set = when (e.modo) { Modo.AUTO -> "AUTO"; Modo.RAPIDO -> "FAST" }
    val texto = StringBuilder(
        "v=1;proc=$proceso;rev=$revision;api=$api;paint=$paint;light=${uno(e.modoLigero)};perf=$perf;set=$set;" +
            "forced=${uno(e.forzada)};fail=${uno(e.fallo)};hand=${uno(e.aMano)}",
    )
    val cpu = equipo.procesador?.filter { it in ' '..'~' && it != ';' && it != '=' }?.take(80)?.trim()?.takeIf { it.isNotEmpty() }
    val ancho = equipo.anchoPx?.takeIf { it in 100..99_999 }
    val alto = equipo.altoPx?.takeIf { it in 100..99_999 }
    listOfNotNull(
        cpu?.let { "cpu=$it" },
        equipo.nucleos?.takeIf { it in 1..512 }?.let { "cores=$it" },
        equipo.ramGb?.takeIf { it in 0.0..4096.0 }?.let { "ram=" + String.format(Locale.ROOT, "%.1f", it) },
        if (ancho != null && alto != null) "screen=${ancho}x$alto" else null,
    ).forEach { par -> if (texto.length + 1 + par.length <= TOPE) texto.append(';').append(par) }   // nunca se corta un par
    return texto.toString()
}

private fun uno(b: Boolean) = if (b) "1" else "0"

package com.avoqado.pos.transactions.data.model

/**
 * Qué se le dice al cajero después de REIMPRIMIR un ticket.
 *
 * 🔴 El ticket nunca se bloquea por el QR: si la liga del recibo no se pudo traer, el papel sale
 * igual (sin QR y sin leyenda) y el aviso lo DICE. Pero lo dice sin mentir: un 403/404/500 no es
 * «sin conexión», y mandar al cajero a revisar el WiFi por un problema del servidor le hace perder
 * el rato buscando donde no es.
 *
 * Si la impresión falló, manda su propio error: el QR es lo de menos cuando no hay papel.
 */
fun avisoDeReimpresion(
    mensajeDeImpresion: String,
    imprimio: Boolean,
    liga: ResultadoLigaRecibo,
): String {
    if (!imprimio) return mensajeDeImpresion

    return when (liga) {
        is ResultadoLigaRecibo.Obtenida -> mensajeDeImpresion
        is ResultadoLigaRecibo.SinRed -> "$mensajeDeImpresion — sin QR de facturación (sin conexión)"
        is ResultadoLigaRecibo.FalloDelServidor -> "$mensajeDeImpresion — sin QR de facturación (el servidor no respondió)"
    }
}

/** Cómo se pinta el aviso: éxito, aviso (salió pero sin QR) o error (no salió). */
enum class TonoDelAviso { EXITO, AVISO, ERROR }

/**
 * El tono del aviso sale del RESULTADO, nunca del texto.
 *
 * 🔴 Regresión que encontró el QA de hardware (Sunmi OrderPAD 3, 12-sep-2026): el sheet pintaba
 * verde sólo si el texto era exactamente "Recibo impreso". Cuando la liga no llegaba, el mensaje
 * pasaba a "Recibo impreso — sin QR de facturación (sin conexión)" y caía en ROJO de error — sobre
 * un ticket que SÍ se imprimió. Decidir por el texto se rompe en cuanto alguien cambia una palabra.
 *
 * Y la regla del workspace: sin red es un ESTADO NORMAL, nunca rojo. Por eso "salió sin QR" es
 * [TonoDelAviso.AVISO], no [TonoDelAviso.ERROR].
 */
fun tonoDeReimpresion(imprimio: Boolean, liga: ResultadoLigaRecibo): TonoDelAviso = when {
    !imprimio -> TonoDelAviso.ERROR
    liga is ResultadoLigaRecibo.Obtenida -> TonoDelAviso.EXITO
    else -> TonoDelAviso.AVISO
}

/** El aviso completo que consume la pantalla: qué decir y con qué tono. */
data class ResultadoDeReimpresion(val mensaje: String, val tono: TonoDelAviso)

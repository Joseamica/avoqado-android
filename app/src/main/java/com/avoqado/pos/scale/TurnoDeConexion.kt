package com.avoqado.pos.scale

/**
 * D7 (Codex D7 r1 #4): cada conexión de la báscula lleva su turno. Soltar la báscula (la X o la etiqueta que cierra la captura)
 * cierra el turno vigente; una conexión que termina DESPUÉS (abrir el puerto USB tarda) ya no es la vigente: cierra su puerto y
 * no publica «Lista» ni un error, ni pisa a la conexión que abrió la captura siguiente.
 */
class TurnoDeConexion {
    private var actual = 0L

    @Synchronized fun abrir(): Long = ++actual

    @Synchronized fun cerrar() { actual++ }

    @Synchronized fun vigente(turno: Long): Boolean = turno == actual
}

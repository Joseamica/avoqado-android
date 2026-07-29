package com.avoqado.pos.core.data.network

/**
 * Traduce los mensajes que manda el server a algo que un mesero pueda leer.
 *
 * El server escribe sus errores para quien depura: `Permission
 * 'reservations:create' required`. Ese texto llegaba TAL CUAL a la pantalla, así
 * que quien intentaba agendar una cita sin permiso leía una frase en inglés con
 * comillas y dos puntos, sin saber si hizo algo mal, si la app se rompió, ni a
 * quién pedirle ayuda.
 *
 * No se traduce en el server a propósito: ahí el detalle técnico sirve para los
 * logs y para el dashboard. Lo que cambia es lo que ve la persona.
 */
object ServerErrorText {

    private val PERMISSION_REGEX = Regex("""Permission\s+'([^']+)'\s+required""", RegexOption.IGNORE_CASE)

    /**
     * @param raw mensaje del server, tal cual llegó.
     * @param fallback qué decir cuando no hay mensaje.
     */
    fun humanize(raw: String?, fallback: String = "No se pudo completar la acción. Inténtalo de nuevo."): String {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) return fallback

        PERMISSION_REGEX.find(text)?.let { match ->
            val permiso = match.groupValues[1]
            // Se conserva el código del permiso: es lo que el administrador necesita
            // buscar para activarlo, y sin él el mesero no tiene cómo pedir ayuda.
            return "No tienes permiso para hacer esto. Pídele a un administrador que te " +
                "active «$permiso»."
        }

        // Otros errores que el server manda en inglés y no significan nada para quien
        // está en el piso.
        if (text.equals("Forbidden", ignoreCase = true) ||
            text.equals("Unauthorized", ignoreCase = true)
        ) {
            return "No tienes permiso para hacer esto."
        }

        return text
    }
}

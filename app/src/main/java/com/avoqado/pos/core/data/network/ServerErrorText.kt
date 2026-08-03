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
     * Versión para una excepción, que es lo que llega a la mayoría de los `catch`.
     *
     * Cubre los dos idiomas ajenos que veía el usuario: el del SISTEMA — un
     * `UnknownHostException` llega como "Unable to resolve host ...", en inglés y
     * hablando de DNS— y el del server, que escribe para quien depura. Sin esto,
     * quedarse sin WiFi se leía como si la app estuviera rota.
     *
     * @param offlineMessage qué decir cuando el fallo es de red. Se personaliza
     *   donde la falta de conexión significa algo concreto — el reloj checador,
     *   por ejemplo, EXIGE internet porque el turno vive en el server, y decirlo
     *   evita que alguien se vaya creyendo que marcó.
     */
    fun humanize(
        error: Throwable?,
        fallback: String = "No se pudo completar la acción. Inténtalo de nuevo.",
        offlineMessage: String = "Sin conexión. Revisa la red e inténtalo de nuevo.",
    ): String {
        if (error == null) return fallback
        return when (error) {
            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.net.NoRouteToHostException,
            is javax.net.ssl.SSLException,
            -> offlineMessage
            is java.net.SocketTimeoutException,
            -> "El servidor tardó demasiado en responder. Inténtalo de nuevo."
            is java.io.InterruptedIOException,
            -> offlineMessage
            else -> humanize(error.message, fallback)
        }
    }

    /** Espejo de iOS: distinguir sin-red de fallo real, sin tener la excepción. */
    fun isOffline(error: Throwable?): Boolean = when (error) {
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException,
        is java.io.InterruptedIOException,
        -> true
        else -> false
    }

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

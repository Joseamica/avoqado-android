package com.avoqado.pos.core.data.network

import com.avoqado.pos.core.domain.PermissionLabels
import kotlinx.serialization.json.contentOrNull

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
    private val JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private const val CATALOG_GOVERNANCE_REQUIRED = "CATALOG_GOVERNANCE_REQUIRED"
    private const val CATALOG_GOVERNANCE_MESSAGE =
        "Este producto debe crearse o activarse desde el Catálogo maestro."

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
            // 🔴 Retrofit: `HttpException.message` es "HTTP 400 Bad Request" y el
            // MOTIVO real viaja en el cuerpo, que nadie leía.
            //
            // Así, un rechazo de negocio perfectamente explicado por el server
            // —"La cuenta origen ya tiene pagos; no se puede fusionar"— llegaba
            // al mesero como "HTTP 400". Visto en la T3 el 2026-08-09 al intentar
            // fusionar cuentas: el aviso no decía nada útil y no había forma de
            // saber qué hacer. Vale para TODA llamada Retrofit de la app, no sólo
            // para fusionar.
            // Sin mensaje del server se va al fallback DEL LLAMADOR ("No se pudo
            // fusionar"), nunca a `error.message`: ese es "HTTP 500
            // Response.error()" y filtrar esa jerga a la pantalla es el mismo
            // defecto que estamos arreglando, sólo que con otro texto.
            is retrofit2.HttpException -> serverMessageFrom(error)?.let { humanize(it, fallback) } ?: fallback
            else -> humanize(error.message, fallback)
        }
    }

    /**
     * El `message` que el server puso en el cuerpo del error, o null si no vino.
     *
     * El cuerpo sólo se puede leer UNA vez, así que se hace aquí y se atrapa
     * cualquier fallo: un error al explicar un error no puede tumbar la pantalla.
     */
    fun serverMessageFrom(error: retrofit2.HttpException): String? = try {
        val body = error.response()?.errorBody()?.string()
        if (body.isNullOrBlank()) {
            null
        } else {
            val root = JSON.parseToJsonElement(body)
                .let { it as? kotlinx.serialization.json.JsonObject }
            listOf("message", "error", "errorMessage")
                .firstNotNullOfOrNull { key ->
                    (root?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
                        ?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Extrae el error de las fronteras OkHttp que no usan Retrofit. El código
     * estable del catálogo conserva una instrucción útil incluso si una respuesta
     * excepcional omite `message`; los demás errores respetan el texto del server.
     */
    fun fromResponseBody(rawBody: String?, fallback: String): String = try {
        val root = rawBody
            ?.takeIf { it.isNotBlank() }
            ?.let(JSON::parseToJsonElement)
            as? kotlinx.serialization.json.JsonObject
        val code = (root?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val message = listOf("message", "error", "errorMessage")
            .firstNotNullOfOrNull { key ->
                (root?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            }
        if (code == CATALOG_GOVERNANCE_REQUIRED) {
            message ?: CATALOG_GOVERNANCE_MESSAGE
        } else {
            humanize(message, fallback)
        }
    } catch (_: Exception) {
        fallback
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
            // 🔴 Se nombra la ACCIÓN, nunca el código.
            //
            // Hasta 2026-08-16 aquí se enseñaba el código crudo ("que te active
            // «tpv:read»"), con el argumento de que el administrador lo
            // necesitaba para buscarlo. En hardware se vio lo que costaba: un
            // CASHIER cobrando leyó exactamente eso en la pantalla de propina
            // —la app consulta sola qué terminales PAX están en línea— y el
            // texto no explicaba nada. Queja textual del founder: "tiene
            // tpv:read, no explica la causa real".
            //
            // `PermissionLabels` ya traducía permiso → acción, pero sólo lo
            // usaba el teclado del PIN de gerente. El código sigue existiendo
            // para quien depura: va al logcat en `ForbiddenInterceptor`, que es
            // donde sirve, no en la cara de quien está atendiendo.
            val accion = PermissionLabels.labelOrNull(permiso)
            return if (accion != null) {
                "No tienes permiso para hacer esto. Pídele a un administrador que te active «$accion»."
            } else {
                // Sin etiqueta NO se cae al código ni al respaldo metido a
                // fuerzas ("que te active «esta acción»", que suena a app rota):
                // la frase tiene que sostenerse sola.
                "No tienes permiso para hacer esto. Pídele a un administrador que te dé permiso para esta acción."
            }
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

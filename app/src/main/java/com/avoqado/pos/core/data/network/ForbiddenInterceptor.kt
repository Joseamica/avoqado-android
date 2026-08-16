package com.avoqado.pos.core.data.network

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class ForbiddenResponse(
    val error: String? = null,
    val message: String? = null,
    val required: String? = null,
    val userRole: String? = null,
    /**
     * Sólo viene cuando el 403 es del CANDADO DE PLAN, no de permisos. Se
     * distingue porque presentarlo como "no tienes permisos" manda al mesero a
     * pedirle permisos a su jefe en vez de al upsell — el mismo bug que iOS ya
     * documenta haber evitado en `APIClient.swift`.
     */
    val featureCode: String? = null,
    /**
     * Sólo viene cuando el venue activó el PIN de autorización de gerente Y el
     * 403 es de permisos. `null` (server viejo o switch apagado) = no se ofrece
     * teclado; el comportamiento es exactamente el de hoy.
     */
    val overridable: Boolean? = null,
)

@Singleton
class ErrorNotifier @Inject constructor() {
    private val _forbiddenError = MutableStateFlow<String?>(null)
    val forbiddenError: StateFlow<String?> = _forbiddenError.asStateFlow()

    fun notify(message: String) {
        _forbiddenError.value = message
    }

    fun clear() {
        _forbiddenError.value = null
    }
}

class ForbiddenInterceptor(
    private val errorNotifier: ErrorNotifier,
    private val overrideCoordinator: ManagerOverrideCoordinator,
) : Interceptor {

    companion object {
        /** Marca las peticiones que corren solas, sin que nadie las pida. */
        const val BACKGROUND_HEADER = "X-Avoqado-Background"

        /** La función interpreta y presenta su propio error con contexto operativo. */
        const val LOCAL_ERROR_HEADER = "X-Avoqado-Local-Error"

        /**
         * La petición corre con un plazo CORTO y no puede quedarse esperando a
         * que una persona teclee un PIN.
         *
         * 🔴 Lo pone la ruta del dinero (`OrderRepository.moneyClient`,
         * `callTimeout` de 15 s). Sin esto, el teclado se abría DENTRO de la
         * llamada: a los 15 s OkHttp la cancelaba con `InterruptedIOException`,
         * que `isQueueableError` clasifica como fallo de red — y una venta que
         * el server RECHAZÓ por permisos terminaba encolada, pintada como
         * cobrada, sumada al corte y con su comanda impresa. Marcada así, el
         * 403 vuelve como RESPUESTA (no como excepción) y sigue el camino de
         * siempre: rechazo de negocio, visible, sin encolar.
         */
        const val FAIL_FAST_HEADER = "X-Avoqado-Fail-Fast"

        /** Token de un solo uso del PIN de autorización de gerente. */
        const val PERMISSION_OVERRIDE_HEADER = "X-Permission-Override"
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // 🔴 Un rechazo de una tarea de FONDO no interrumpe a nadie.
        //
        // Este interceptor es global, así que el replay del outbox — que corre
        // solo, sin que nadie lo pida — sacaba el diálogo modal encima de la
        // pantalla en la que estuviera el mesero. Medido en la tablet: 23
        // rechazos seguidos del mismo intent = el diálogo saltando una y otra
        // vez sobre el calendario. Esos rechazos ya tienen su sitio: la
        // cuarentena, donde alguien puede resolverlos con calma.
        if (request.header(BACKGROUND_HEADER) != null) {
            if (response.code == 403) {
                Log.w("🔒 RBAC", "403 en tarea de fondo (${request.url.encodedPath}) — va a cuarentena, sin diálogo")
            }
            return response
        }

        // Algunos módulos tienen errores de dominio mucho más útiles que el modal
        // RBAC global (por ejemplo: "esta terminal es Cremería, escanea en Caja").
        // Si el repositorio ya los presentará, no dupliques ni tapes ese mensaje.
        if (request.header(LOCAL_ERROR_HEADER) != null) return response

        if (response.code != 403) return response

        return classifyForbidden(chain, request, response)
    }

    /**
     * Clasifica un 403 y decide qué hacer con él.
     *
     * Se llama una segunda vez —y sólo una— sobre el reintento con token: para
     * entonces la petición YA lleva el header, así que la rama del teclado se
     * salta sola y no hay recursión infinita.
     */
    private fun classifyForbidden(
        chain: Interceptor.Chain,
        request: okhttp3.Request,
        response: Response,
    ): Response {
        val body = response.peekBody(4096).string()

        // 🔴 Un 403 NO prueba que falten permisos: prueba que ALGUIEN dijo que
        // no, y ese alguien puede no ser nuestra API.
        //
        // Medido en hardware el 2026-08-13: con el túnel de ngrok caído, la
        // app decía "No tienes permisos" y mandaba a buscar un problema de
        // roles inexistente — mientras el banner de sin conexión estaba
        // puesto, o sea contradiciéndose en la misma pantalla. En producción
        // la API vive detrás de Cloudflare, que responde 403 con HTML (WAF,
        // rate limit, reglas de país), y las terminales de PlayTelecom operan
        // dentro de redes corporativas de Walmart con proxy.
        //
        // Nuestro 403 SIEMPRE trae JSON con `error` o `message`
        // (`authorizeRole.middleware.ts`). El del intermediario, no. Si el
        // cuerpo no es nuestro, esto es un fallo de transporte y le toca al
        // ConnectivityBanner, no a un modal de permisos.
        val parsed = try {
            json.decodeFromString<ForbiddenResponse>(body)
        } catch (_: Exception) {
            null
        }
        if (parsed == null || (parsed.error == null && parsed.message == null)) {
            Log.w(
                "🔒 RBAC",
                "403 sin cuerpo nuestro en ${request.url.encodedPath} " +
                    "(${response.header("Content-Type")}) — intermediario, no permisos",
            )
            return response
        }

        // El candado de PLAN tampoco es falta de permiso: lo resuelve el
        // upsell, no un administrador dando permisos.
        if (parsed.featureCode != null) {
            Log.w("🔒 RBAC", "403 de candado de plan (${parsed.featureCode}) — sin modal de permisos")
            return response
        }

        // 🔴 PIN de autorización de gerente. Sólo llega aquí lo que YA se
        // descartó arriba: no es de un intermediario y no es candado de plan
        // — o sea, un "no" real de nuestra API por falta de permiso.
        //
        // El reintento vive AQUÍ, bloqueando el hilo de red, a propósito: así
        // el resultado llega al ViewModel que hizo la llamada original. Si el
        // token se pidiera "por fuera", ese ViewModel ya habría pintado un
        // error y el éxito posterior sería invisible. Es el mismo patrón que
        // `TokenRefreshAuthenticator` con el 401.
        //
        // El guard del header evita el bucle: si la petición ya traía un
        // token y aun así volvió 403 (expirado, reusado), no se vuelve a
        // pedir; se cae al mensaje de siempre.
        if (parsed.overridable == true &&
            parsed.required != null &&
            request.header(PERMISSION_OVERRIDE_HEADER) == null &&
            // Una llamada con plazo corto (la ruta del dinero) NO se retiene
            // esperando a una persona: se cae al mensaje de abajo, que el
            // cajero SÍ ve, en vez de morir por timeout y encolarse como venta
            // buena. Autorizar un cobro con PIN necesita pedir el token FUERA
            // de la llamada; hasta entonces, aquí el "no" es un no.
            request.header(FAIL_FAST_HEADER) == null
        ) {
            val token = overrideCoordinator.awaitToken(parsed.required)
            if (token != null) {
                response.close()
                val retriedRequest = request.newBuilder()
                    .header(PERMISSION_OVERRIDE_HEADER, token)
                    .build()
                Log.d("🔒 RBAC", "Reintentando ${request.url.encodedPath} con autorización de gerente")
                val retriedResponse = chain.proceed(retriedRequest)
                // Si el token no alcanzó (caducó, ya se usó), el "no" NO puede
                // quedarse mudo — un botón que no hace nada es el bug que este
                // interceptor existe para evitar. Se clasifica igual, y la rama
                // de arriba ya no se abre porque la petición lleva el header.
                if (retriedResponse.code == 403) {
                    return classifyForbidden(chain, retriedRequest, retriedResponse)
                }
                return retriedResponse
            }
            // Canceló. La acción falla como fallaba antes, pero SIN el modal
            // de "no tienes permiso": ya le dijimos por qué en el teclado.
            Log.w("🔒 RBAC", "El usuario canceló la autorización para ${parsed.required}")
            return response
        }

        // El server manda el detalle técnico ("Permission 'x' required"), útil
        // para logs; a la persona se le enseña algo que pueda accionar.
        //
        // 🔴 El código del permiso se loguea A PROPÓSITO, y con la ruta: desde
        // 2026-08-16 ya no aparece en pantalla —ahí va la acción en español— así
        // que este renglón es el único sitio donde queda registrado cuál fue.
        // Sin él, un reporte de "no me deja" se vuelve imposible de rastrear.
        val message = ServerErrorText.humanize(parsed.message, "No tienes permisos para esta acción")
        Log.w(
            "🔒 RBAC",
            "403 Forbidden en ${request.url.encodedPath} — falta '${parsed.required ?: "?"}' " +
                "(rol ${parsed.userRole ?: "?"}); en pantalla: $message",
        )
        errorNotifier.notify(message)

        return response
    }
}

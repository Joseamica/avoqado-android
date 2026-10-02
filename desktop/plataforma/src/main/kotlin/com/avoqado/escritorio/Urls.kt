package com.avoqado.escritorio

/**
 * De dónde sale el backend: -Davoqado.api > AVOQADO_API > el default del build. Todo pasa por la guarda del modo:
 * - PRUEBA: jamás avoqado.io (ni un subdominio): la prueba no habla con producción.
 * - PRODUCCIÓN: sólo https, el host EXACTO api.avoqado.io (API) o dashboard.avoqado.io (dashboard), puerto 443 y sin credenciales, query ni fragmento.
 */
object Urls {
    private const val HOST_API = "api.avoqado.io"
    private const val HOST_DASHBOARD = "dashboard.avoqado.io"

    fun api(
        porDefecto: String,
        produccion: Boolean,
        propiedad: (String) -> String? = System::getProperty,
        entorno: (String) -> String? = System::getenv,
    ): String = comprobar(propiedad("avoqado.api") ?: entorno("AVOQADO_API") ?: porDefecto, produccion, setOf(HOST_API))

    fun dashboard(
        porDefecto: String,
        produccion: Boolean,
        propiedad: (String) -> String? = System::getProperty,
        entorno: (String) -> String? = System::getenv,
    ): String = comprobar(propiedad("avoqado.dashboard") ?: entorno("AVOQADO_DASHBOARD") ?: porDefecto, produccion, setOf(HOST_DASHBOARD))

    /** Para URLs que la app lee por su cuenta (p. ej. avoqado.test.baseUrl): misma guarda del modo. Es una base de API: en producción sólo api. */
    fun validar(url: String, produccion: Boolean): String = comprobar(url, produccion, setOf(HOST_API))

    private fun comprobar(url: String, produccion: Boolean, hostsDeProduccion: Set<String>): String {
        // Se compara el HOST parseado: un `%2e` en el host deja a java.net.URI sin host (null) y también se rechaza.
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val host = uri?.host?.lowercase()?.trimEnd('.')
        if (produccion) {
            // Sólo https, host exacto, puerto por omisión (o 443) y nada de credenciales, query ni fragmento: la base no admite más.
            check(
                host != null && uri.scheme?.lowercase() == "https" && host in hostsDeProduccion &&
                    (uri.port == -1 || uri.port == 443) &&
                    uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null,
            ) {
                "Esta versión de Avoqado POS sólo habla con https://${hostsDeProduccion.joinToString(" o https://")} ($url no es válida)."
            }
        } else {
            check(host != null && host != "avoqado.io" && !host.endsWith(".avoqado.io")) {
                "Esta prueba de escritorio nunca habla con producción ni con una URL rara ($url). Usa el backend local."
            }
        }
        return url
    }
}

package com.avoqado.escritorio

/** De dónde sale el backend: -Davoqado.api > AVOQADO_API > el default del build. Producción, jamás. */
object Urls {
    fun api(
        porDefecto: String,
        propiedad: (String) -> String? = System::getProperty,
        entorno: (String) -> String? = System::getenv,
    ): String = noProduccion(propiedad("avoqado.api") ?: entorno("AVOQADO_API") ?: porDefecto)

    fun dashboard(
        porDefecto: String,
        propiedad: (String) -> String? = System::getProperty,
        entorno: (String) -> String? = System::getenv,
    ): String = noProduccion(propiedad("avoqado.dashboard") ?: entorno("AVOQADO_DASHBOARD") ?: porDefecto)

    /** Para URLs que la app lee por su cuenta (p. ej. avoqado.test.baseUrl): mismo guardián de producción. */
    fun validar(url: String): String = noProduccion(url)

    private fun noProduccion(url: String): String {
        // Se compara el HOST parseado: un `%2e` en el host deja a java.net.URI sin host (null) y también se rechaza.
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()?.trimEnd('.')
        check(host != null && !host.endsWith("avoqado.io")) {
            "Esta prueba de escritorio nunca habla con producción ni con una URL rara ($url). Usa el backend local."
        }
        return url
    }
}

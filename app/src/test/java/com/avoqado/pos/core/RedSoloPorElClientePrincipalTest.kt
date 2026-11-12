package com.avoqado.pos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D5 · B2b, candado estructural del punto de paso ① (`PuertaDeLaFoto`): toda escritura de la app al SERVIDOR sale por el cliente
 * HTTP principal de `NetworkModule` (o uno derivado con `newBuilder()`, que hereda sus interceptores). Truena si aparece un cliente
 * HTTP nuevo fuera de la lista conocida, si alguien le quita a un derivado un interceptor que no sea el de bitácora, o si la puerta
 * deja de ser el primer interceptor del principal. Revisa el CÓDIGO, como `PlanManagerTest`.
 */
class RedSoloPorElClientePrincipalTest {
    private val raiz = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
        ?: error("No encontré src/main/java desde ${File(".").absolutePath}")
    private val fuentes = raiz.walkTopDown().filter { it.isFile && it.extension == "kt" }.associateWith { it.readText() }
    private fun ruta(f: File) = f.relativeTo(raiz).path.replace('\\', '/')

    /** Los únicos clientes que NO derivan del principal, y por qué no escriben sobre una cuenta. */
    private val conocidos = mapOf(
        "com/avoqado/pos/core/data/network/SesionSinRenovar.kt" to "la base de conexiones de la que nace el principal",
        "com/avoqado/pos/core/di/NetworkModule.kt" to "el principal (lleva la puerta)",
        "com/avoqado/pos/core/data/network/TokenRefreshAuthenticator.kt" to "renovar la sesión (/auth/refresh)",
        "com/avoqado/pos/core/data/network/PermissionOverrideRepository.kt" to "el PIN de gerente (/permission-overrides)",
        "com/avoqado/pos/customerdisplay/CustomerDisplayImageCache.kt" to "baja imágenes (GET)",
        "com/avoqado/pos/printing/data/ReceiptLogoCache.kt" to "baja el logo del recibo (GET)",
        "com/avoqado/pos/core/util/ConnectivityMonitor.kt" to "la sonda de conexión (GET)",
        "com/avoqado/pos/printing/data/BuscadorDeImpresora.kt" to "habla con impresoras del local, no con el servidor",
    )

    @Test fun `ningun cliente HTTP nuevo fuera del principal y la lista conocida`() {
        val cliente = Regex("""OkHttpClient\(\)|OkHttpClient\.Builder\(\)|ConexionesCompartidas\.base\b|HttpURLConnection|openConnection\(|io\.ktor|java\.net\.http\.""")
        val usan = fuentes.filter { (_, texto) -> cliente.containsMatchIn(texto) }.keys.map(::ruta).toSet()
        assertTrue("El barrido no encontró al principal: la regex se rompió", "com/avoqado/pos/core/di/NetworkModule.kt" in usan)
        val nuevos = usan - conocidos.keys
        assertTrue("Cliente HTTP fuera del principal: sus escrituras no pasan por la puerta de la foto (D5 · B2b). " +
            "Deriva del principal (`client.newBuilder()`) o agrégalo aquí con su razón: $nuevos", nuevos.isEmpty())
    }

    @Test fun `un derivado solo puede quitar el interceptor de bitacora`() {
        val quitar = Regex("""(network)?[iI]nterceptors\(\)\s*\.\s*(removeAll|remove|removeIf|clear|retainAll)\b[^\n]*""")
        val malos = fuentes.flatMap { (f, texto) -> quitar.findAll(texto).map { ruta(f) to it.value }.toList() }
            .filterNot { (_, linea) -> linea.contains("HttpLoggingInterceptor") && !linea.contains("clear") }
        assertTrue("Un cliente derivado quita interceptores que no son de bitácora (¿la puerta de la foto?): $malos", malos.isEmpty())
    }

    @Test fun `la puerta de la foto es el primer interceptor del cliente principal`() {
        val modulo = fuentes.entries.single { ruta(it.key) == "com/avoqado/pos/core/di/NetworkModule.kt" }.value
        val primero = Regex("""ConexionesCompartidas\.base\.newBuilder\(\)\s*(//[^\n]*\s*)*\.addInterceptor\(([^)(]*)""").find(modulo)
        assertEquals("com.avoqado.pos.core.data.network.PuertaDeLaFoto", primero?.groupValues?.get(2)?.trim())
    }
}

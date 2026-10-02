package com.avoqado.escritorio

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assume
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64

/** Ayudantes compartidos por `PreferenciasCifradasTest` y `PreferenciasCifradasReglasTest` (sólo mover: ningún cuerpo cambió). */
/** Una carpeta `shared_prefs` nueva, con los tres archivos del almacén. */
internal class Carpeta {
    val raiz: Path = Files.createTempDirectory("Avoqado POS cifradas José ñ")
    val dir: Path = raiz.resolve("shared_prefs")
    val claro: Path = dir.resolve("$NOMBRE.json")
    val cifrado: Path = dir.resolve("$NOMBRE.cifrado")
    val marcador: Path = dir.resolve("$NOMBRE.cifrado.verificado")

    fun abrir(codec: CodecDePreferencias = CodecFalso, fallaEn: (PasoDeConversion) -> Unit = {}): SharedPreferences =
        preferenciasCifradas(dir, NOMBRE, codec, fallaEn)

    /** El claro de una caja real de hoy: sesión, aparato y un cobro con tarjeta sin confirmar. */
    fun sembrarClaro(): Map<String, Any> {
        val editor = PreferenciasEnArchivo(claro).edit()
        VALORES.forEach { (k, v) ->
            when (v) {
                is String -> editor.putString(k, v)
                is Boolean -> editor.putBoolean(k, v)
                is Int -> editor.putInt(k, v)
                is Long -> editor.putLong(k, v)
                is Float -> editor.putFloat(k, v)
                is Set<*> -> editor.putStringSet(k, v.map { it as String }.toSet())
                else -> error("tipo no soportado: $v")
            }
        }
        check(editor.commit()) { "no se pudo sembrar el claro" }
        return VALORES
    }

    /** Lo que dice el marcador: `sha256:<hex>` del claro convertido, o `ninguno`. */
    fun marcadorDice(): String = Files.readString(marcador).trim()

    /** Los claros apartados (`<nombre>.json.version-anterior-<ms>`). */
    fun apartados(): List<Path> = Files.list(dir).use { s ->
        s.filter { it.fileName.toString().matches(Regex("${Regex.escape("$NOMBRE.json")}\\.version-anterior-\\d+")) }.toList()
    }

    /** Corre [bloque] con la carpeta sin permiso de escritura: no se puede crear, borrar ni mover nada. */
    fun sinEscritura(bloque: () -> Unit) {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
        try {
            Assume.assumeFalse("corre como root: no hay carpeta sin permiso", Files.isWritable(dir))
            bloque()
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
    }

    /** Nombre → bytes de todo lo que hay en la carpeta: si algo se aparta, borra, renombra o crea, cambia. */
    fun foto(): Map<String, String> =
        if (Files.notExists(dir)) emptyMap()
        else Files.list(dir).use { s -> s.toList() }
            .associate { it.fileName.toString() to Base64.getEncoder().encodeToString(Files.readAllBytes(it)) }
            .toSortedMap()
}

/** «El proceso murió aquí»: un Error, así que ningún `catch (Exception)` lo limpia, igual que un apagón. */
internal class MuerteSimulada : Error("el proceso murió aquí")

/** Reversible: cabecera «AVQ1» + XOR. Sin la cabecera lanza, como DPAPI con otro usuario de Windows. */
internal object CodecFalso : CodecDePreferencias {
    private val CABECERA = "AVQ1".toByteArray()
    override fun cifrar(claro: ByteArray): ByteArray = CABECERA + xor(claro)
    override fun descifrar(cifrado: ByteArray): ByteArray {
        check(cifrado.size >= CABECERA.size && cifrado.copyOfRange(0, CABECERA.size).contentEquals(CABECERA)) { "sin cabecera AVQ1" }
        return xor(cifrado.copyOfRange(CABECERA.size, cifrado.size))
    }
    private fun xor(b: ByteArray) = ByteArray(b.size) { (b[it].toInt() xor 0x5A).toByte() }
}

internal fun codecQueAlDescifrar(cambio: (ByteArray) -> ByteArray) = object : CodecDePreferencias {
    override fun cifrar(claro: ByteArray) = CodecFalso.cifrar(claro)
    override fun descifrar(cifrado: ByteArray) = cambio(CodecFalso.descifrar(cifrado))
}

/** Un `.cifrado` legible con sólo strings, en el formato de siempre ({"llave": {"t": "s", "v": …}}). */
internal fun cifradoDe(vararg pares: Pair<String, String>): ByteArray {
    val json = JSONObject()
    pares.forEach { (k, v) -> json.put(k, JSONObject().put("t", "s").put("v", v)) }
    return CodecFalso.cifrar(json.toString().toByteArray(UTF_8))
}

internal fun contiene(bytes: ByteArray, texto: String) = String(bytes, ISO_8859_1).contains(texto)

internal fun huella(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun vecesElPrefijo(e: PreferenciasIlegibles) = Regex("Preferencias ilegibles:").findAll(e.motivo).count()

/** La pila completa (mensaje, causas y suprimidas), como la imprime la bitácora. */
internal fun pila(e: Throwable) = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

/** Lo que la bitácora (que escribe en System.out) imprimió mientras corría [bloque]. */
internal fun <T> bitacoraDe(bloque: () -> T): Pair<T, String> {
    val antes = System.out
    val captura = ByteArrayOutputStream()
    System.setOut(PrintStream(captura, true, UTF_8))
    val resultado = try { bloque() } finally { System.setOut(antes) }
    return resultado to captura.toString(UTF_8)
}

internal const val NOMBRE = "avoqado_secure_prefs"
internal const val TOKEN = "eyJhbGciOi.token-de-sesion-SECRETO"
internal const val SECRETO = "eyJ-SECRETO"
internal const val DEVICE_ID = "AVQD-escritorio-7f3a"
internal const val PENDIENTES = """[{"requestId":"req-1","orderId":"ord-9","amountCents":15000,"tipCents":1500}]"""
internal const val MOTIVO_DPAPI = "no se pudo descifrar: ¿otro usuario de Windows o se restableció su contraseña?"
internal val VALORES: Map<String, Any> = mapOf(
    "accessToken" to TOKEN,
    "deviceId" to DEVICE_ID,
    "pendingCardCharges" to PENDIENTES,
    "isBiometricLoginEnabled" to true,
    "venueCount" to 3,
    "tokenExpiresAt" to 1_790_000_000_000L,
    "propinaSugerida" to 0.15f,
    "wastePlanBlockedVenues" to setOf("venue-1", "venue-2"),
)

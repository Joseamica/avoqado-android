package com.avoqado.escritorio

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Log
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest

/** Cómo se guardan en disco los bytes del JSON de unas preferencias. */
interface CodecDePreferencias {
    fun cifrar(claro: ByteArray): ByteArray
    fun descifrar(cifrado: ByteArray): ByteArray
}

/** Los bytes tal cual: el claro de siempre. */
object SinCifrar : CodecDePreferencias {
    override fun cifrar(claro: ByteArray): ByteArray = claro
    override fun descifrar(cifrado: ByteArray): ByteArray = cifrado
}

/**
 * DPAPI del usuario actual de Windows (`CryptProtectData`, sin la bandera de máquina): sólo ese usuario, en ese equipo,
 * descifra. Sin UI. 🔴 Sólo existe en Windows: fuera de Windows tocarlo truena aquí, antes de cargar Crypt32.
 */
object DpapiDeWindows : CodecDePreferencias {
    init {
        check(esWindows()) { "DPAPI sólo existe en Windows (os.name=${System.getProperty("os.name")})" }
    }

    override fun cifrar(claro: ByteArray): ByteArray = Crypt32Util.cryptProtectData(claro, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
    override fun descifrar(cifrado: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(cifrado, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
}

/** Lo guardado no se puede leer o verificar: la app NO arranca (Main lo dice) y no se aparta ni se borra nada. */
class PreferenciasIlegibles(val motivo: String, causa: Throwable?) : IllegalStateException(motivo, causa)

/** Todo motivo empieza así UNA sola vez, aunque envuelva a otro («No se pudo abrir Avoqado POS: Preferencias ilegibles: …»). */
private const val PREFIJO = "Preferencias ilegibles: "

/**
 * 🔴 Ni [detalle] ni [causa] pueden traer VALORES de las preferencias (el token): la bitácora imprime la pila con sus
 * «Caused by». Rutas, textos fijos y fallos de disco o de DPAPI sí.
 */
internal fun ilegibles(detalle: String, causa: Throwable?) = PreferenciasIlegibles(PREFIJO + detalle, causa)

/** El motivo sin el prefijo, para anidarlo en otro sin repetirlo. */
internal val PreferenciasIlegibles.detalle: String get() = motivo.removePrefix(PREFIJO)

/** Dónde puede «morir» la conversión: las pruebas interrumpen después de cada paso. */
internal enum class PasoDeConversion { CIFRADO_ESCRITO, CIFRADO_VERIFICADO, MARCADOR_PUBLICADO, CLARO_BORRADO }

/**
 * `<carpeta>/<nombre>.cifrado` con conversión verificada desde `<nombre>.json`. Lanza PreferenciasIlegibles; nunca
 * vacío por error.
 *
 * - `<nombre>.json`: el claro de hoy.
 * - `<nombre>.cifrado`: el cifrado (el MISMO JSON, pasado por [codec]).
 * - `<nombre>.cifrado.verificado`: el marcador. Dice «este `.cifrado` es la autoridad»: se comparó contra el claro (o
 *   nació vacío) y salió idéntico. Se publica de una sola forma: temporal + force + ATOMIC_MOVE. Su contenido dice DE
 *   QUÉ claro salió: `sha256:<hex de los bytes exactos del claro convertido>`, o `ninguno` si nació sin claro.
 *
 * 🔴 Aquí viven el token, los cobros con tarjeta sin confirmar y la cola del cajón: ante cualquier duda, no arranca.
 */
fun preferenciasCifradas(carpeta: Path, nombre: String, codec: CodecDePreferencias): SharedPreferences =
    preferenciasCifradas(carpeta, nombre, codec) {}

internal fun preferenciasCifradas(
    carpeta: Path,
    nombre: String,
    codec: CodecDePreferencias,
    fallaEn: (PasoDeConversion) -> Unit,
): SharedPreferences {
    val claro = carpeta.resolve("$nombre.json")
    val cifrado = carpeta.resolve("$nombre.cifrado")
    val marcador = carpeta.resolve("$nombre.cifrado.verificado")

    // Regla 2: un marcador sin cifrado es huérfano. Se borra ANTES de escribir cualquier cifrado: si siguiera ahí, un
    // cifrado a medio escribir quedaría «verificado».
    var hayMarcador = existe(marcador)
    val hayCifrado = existe(cifrado)
    if (hayMarcador && !hayCifrado) {
        try {
            Files.delete(marcador)
        } catch (e: IOException) {
            throw ilegibles("el marcador huérfano «${marcador.fileName}» no se pudo borrar", e)
        }
        hayMarcador = false
    }
    val hayClaro = existe(claro)

    return when {
        // Regla 3: el cifrado verificado manda, con lectura estricta. Si es ilegible lanza y NO recurre al claro (podría
        // ser más viejo que lo último guardado). Si se leyó y queda un claro, se borra SÓLO si es el que se convirtió.
        hayMarcador -> PreferenciasEnArchivo(cifrado, codec).also {
            if (hayClaro) resolverClaroSobrante(claro, origenSegunElMarcador(marcador))
        }

        // Regla 4: nunca se convirtió (o se interrumpió). Cualquier .cifrado presente se ignora: nunca se verificó.
        hayClaro -> {
            // El claro se lee UNA vez: la huella del marcador y los valores salen de los MISMOS bytes.
            val bytes = leerBytesEstricto(claro)
            val origen = huellaDe(bytes)
            convertir(interpretarEstricto(claro.fileName, bytes, SinCifrar), origen, cifrado, marcador, codec, fallaEn)
            PreferenciasEnArchivo(cifrado, codec).also {
                resolverClaroSobrante(claro, origen)   // paso 6: sólo después del marcador, y sólo si sigue siendo el convertido
                fallaEn(PasoDeConversion.CLARO_BORRADO)
            }
        }

        // Regla 5: cifrado sin marcador ni claro. Sólo un `{}` legible es una inicialización interrumpida (regla 6).
        hayCifrado -> {
            val leidos = try {
                leerEstricto(cifrado, codec)
            } catch (e: PreferenciasIlegibles) {
                throw ilegibles("cifrado sin verificar «${cifrado.fileName}» (${e.detalle})", e)
            }
            if (leidos.isNotEmpty()) {
                throw ilegibles("cifrado sin verificar «${cifrado.fileName}»: tiene datos y no hay .verificado ni claro", null)
            }
            try {
                publicarMarcador(marcador, SIN_CLARO)
            } catch (e: Exception) {
                throw ilegibles("no se pudo publicar «${marcador.fileName}»", e)
            }
            fallaEn(PasoDeConversion.MARCADOR_PUBLICADO)
            PreferenciasEnArchivo(cifrado, codec)
        }

        // Regla 6: aparato nuevo. Nace con un cifrado vacío verificado; si se interrumpe antes del marcador, la regla 5.
        else -> {
            convertir(emptyMap(), SIN_CLARO, cifrado, marcador, codec, fallaEn)
            PreferenciasEnArchivo(cifrado, codec)
        }
    }
}

/**
 * Pasos 3 a 5 de la regla 4 (y la inicialización de la regla 6, con [valores] vacíos): escribe el cifrado, lo reabre,
 * lo descifra, lo lee estricto y lo compara (llaves, valores y tipos), y sólo entonces publica el marcador con [origen].
 * Si falla un paso, borra el cifrado a medias y lanza diciendo QUÉ paso falló: el claro sigue como autoridad. (Una muerte
 * del proceso no pasa por el catch: el siguiente arranque ignora el cifrado sin marcador.)
 */
private fun convertir(
    valores: Map<String, Any>,
    origen: String,
    cifrado: Path,
    marcador: Path,
    codec: CodecDePreferencias,
    fallaEn: (PasoDeConversion) -> Unit,
) {
    var paso = "cifrar y guardar «${cifrado.fileName}»"
    try {
        escribirAtomico(cifrado, codec.cifrar(serializar(valores)))
        fallaEn(PasoDeConversion.CIFRADO_ESCRITO)
        paso = "releer y descifrar «${cifrado.fileName}»"
        val releidos = leerEstricto(cifrado, codec)
        paso = "comparar lo releído con el original"
        if (releidos != valores) throw ilegibles("no salió idéntico (llaves, valores o tipos)", null)
        fallaEn(PasoDeConversion.CIFRADO_VERIFICADO)
        paso = "publicar «${marcador.fileName}»"
        publicarMarcador(marcador, origen)
    } catch (e: Exception) {
        runCatching { Files.deleteIfExists(cifrado) }.onFailure { e.addSuppressed(it) }
        val porque = (e as? PreferenciasIlegibles)?.detalle ?: listOfNotNull(e.javaClass.simpleName, e.message).joinToString(": ")
        throw ilegibles(
            if (origen == SIN_CLARO) "no se pudo crear «${cifrado.fileName}» vacío: falló al $paso ($porque)"
            else "no se pudo convertir a cifrado: falló al $paso ($porque); el claro sigue intacto",
            e,
        )
    }
    fallaEn(PasoDeConversion.MARCADOR_PUBLICADO)
}

/** Lo que dice el marcador de un cifrado que nació sin claro (reglas 5 y 6). */
private const val SIN_CLARO = "ninguno"

/** La huella que el marcador guarda del claro convertido. No revela el token: puede ir en claro. */
private fun huellaDe(bytes: ByteArray): String =
    "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun publicarMarcador(marcador: Path, origen: String) = escribirAtomico(marcador, "$origen\n".toByteArray(UTF_8))

/** Lo que dice el marcador (`sha256:…`, `ninguno` o lo de un formato viejo). null = no se pudo leer. */
private fun origenSegunElMarcador(marcador: Path): String? = try {
    String(reintentando({ it is IOException }) { Files.readAllBytes(marcador) }, UTF_8).trim()
} catch (e: IOException) {
    Log.w(TAG, "«${marcador.fileName}» no se pudo leer: un claro que quede junto al cifrado se aparta (no se borra)", e)
    null
}

/**
 * Queda un claro junto al cifrado verificado (regla 3, y paso 6 de la regla 4). Manda el cifrado y la app arranca:
 * - si su huella es la de [convertido], es el MISMO claro que ya se convirtió (antivirus, o el proceso murió antes de
 *   borrarlo): se borra para que el token no quede en claro;
 * - cualquier otro —huella distinta, marcador `ninguno`, de formato viejo o ilegible— se escribió DESPUÉS de convertir
 *   (¿una versión anterior del POS en esta carpeta?) y puede traer un cobro pendiente: se APARTA, nunca se borra.
 * Lo que no se pueda hacer hoy (leer, borrar, apartar) se avisa y se reintenta en el siguiente arranque.
 */
private fun resolverClaroSobrante(claro: Path, convertido: String?) {
    val bytes = try {
        reintentando({ it is IOException && it !is NoSuchFileException }) { Files.readAllBytes(claro) }
    } catch (e: NoSuchFileException) {
        return   // ya no está: nada que resolver
    } catch (e: IOException) {
        Log.w(
            TAG, "Quedó «${claro.fileName}» junto al cifrado verificado y no se pudo leer: no se toca, manda la sesión cifrada " +
                "y se reintenta al arrancar",
            e,
        )
        return
    }
    if (convertido != null && huellaDe(bytes) == convertido) {
        borrarClaroConvertido(claro)
    } else {
        apartarClaroDistinto(claro, bytes, convertido)
    }
}

/**
 * El claro ya está cifrado y verificado: se borra para que el token no quede en claro. Si no se puede (antivirus), se
 * arranca igual con aviso y se reintenta en cada arranque (ruling Codex v3 #5: bloquear el POS cuesta ventas).
 */
private fun borrarClaroConvertido(claro: Path) {
    try {
        reintentando(::esBloqueoPasajero) { Files.deleteIfExists(claro) }
    } catch (e: IOException) {
        Log.w(TAG, "«${claro.fileName}» ya está cifrado y verificado pero no se pudo borrar: queda en claro y se reintenta al arrancar", e)
    }
}

/** 🔴 Un claro distinto del convertido NUNCA se borra: se mueve a `<nombre>.json.version-anterior-<ms>`, o se queda. */
private fun apartarClaroDistinto(claro: Path, bytes: ByteArray, convertido: String?) {
    val sinHuella = if (convertido?.startsWith("sha256:") == true) "" else " (el marcador no dice qué claro se convirtió)"
    val pendientes = avisoDePendientes(bytes)
    try {
        // Sin REPLACE_EXISTING: un apartado anterior nunca se pisa (el nombre sale en cada intento, con su hora).
        val apartado = reintentando(::esBloqueoPasajero) {
            claro.resolveSibling("${claro.fileName}.version-anterior-${System.currentTimeMillis()}").also { Files.move(claro, it) }
        }
        Log.w(
            TAG, "Un claro distinto del convertido apareció después de la conversión (¿se abrió una versión anterior del POS?); " +
                "se apartó a «${apartado.fileName}»; manda la sesión cifrada$sinHuella$pendientes",
        )
    } catch (e: IOException) {
        Log.w(
            TAG, "Un claro distinto del convertido apareció después de la conversión (¿se abrió una versión anterior del POS?) y " +
                "no se pudo apartar: «${claro.fileName}» se queda donde está (NO se borra), manda la sesión cifrada y se reintenta " +
                "al arrancar$sinHuella$pendientes",
            e,
        )
    }
}

/**
 * Toda llave `pending*` de `SecureStorage` (cobros con tarjeta sin confirmar, la cola del cajón `pendingDrawerOps.<venue>`,
 * los vales de caja externa `pendingAreaTicket*`): lo que el POS nuevo no va a conciliar solo. Nunca dice valores.
 */
private fun avisoDePendientes(bytes: ByteArray): String {
    val valores = try {
        interpretar(bytes)
    } catch (e: Exception) {
        return "; su contenido no se entiende: revisarlo con soporte"
    }
    val hay = valores.any { (llave, valor) ->
        llave.startsWith("pending") && when (valor) {
            is String -> valor.trim() !in setOf("", "[]", "{}", "null")
            is Set<*> -> valor.isNotEmpty()
            else -> true
        }
    }
    return if (hay) "; trae operaciones pendientes: avisar a soporte" else ""
}

/**
 * Lo que un antivirus o el indexador causan un instante al borrar o mover: acceso denegado o, en Windows, la violación
 * de uso compartido (llega como `FileSystemException`, no como `AccessDeniedException`). Lo que ya no existe no se reintenta.
 */
internal fun esBloqueoPasajero(e: Exception): Boolean = e is FileSystemException && e !is NoSuchFileException

/** «No se pudo saber» (antivirus, carpeta sin permiso) NO es «no existe»: lanza. */
private fun existe(archivo: Path): Boolean = when {
    Files.exists(archivo) -> true
    Files.notExists(archivo) -> false
    else -> throw ilegibles("«${archivo.fileName}» no se pudo leer (no se sabe si existe)", null)
}

internal fun esWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

/** `EncryptedSharedPreferences.create` de escritorio (regla 7). */
object PreferenciasSeguras {
    @JvmStatic fun abrir(contexto: Context, nombre: String): SharedPreferences =
        abrir(contexto, nombre, esWindows()) { DpapiDeWindows }

    /** [codec] se pide SÓLO en Windows: fuera de Windows [DpapiDeWindows] nunca se instancia. */
    internal fun abrir(contexto: Context, nombre: String, windows: Boolean, codec: () -> CodecDePreferencias): SharedPreferences {
        if (!windows) {
            Log.w("Escritorio", "Fuera de Windows no hay DPAPI: «$nombre» se guarda SIN cifrar")
            return contexto.getSharedPreferences(nombre, Context.MODE_PRIVATE)
        }
        return contextoDeEscritorio(contexto).getPreferenciasCifradas(nombre, codec)
    }

    /** La actividad envuelve al ContextoDeEscritorio, que es quien sabe dónde está `shared_prefs`. */
    private fun contextoDeEscritorio(contexto: Context): ContextoDeEscritorio =
        generateSequence(contexto) { (it as? ContextWrapper)?.baseContext }.take(10).filterIsInstance<ContextoDeEscritorio>().firstOrNull()
            ?: error("EncryptedSharedPreferences necesita un ContextoDeEscritorio y llegó ${contexto.javaClass.name}")
}

private const val TAG = "Preferencias"

package com.avoqado.pos.maquina.di

import android.content.Context
import android.util.Log
import com.avoqado.escritorio.CodecDePreferencias
import com.avoqado.escritorio.SinCifrar
import com.avoqado.pos.maquina.BovedaDeLaMaquina
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Singleton

/**
 * Reemplazo de escritorio del MaquinaModule de Android (R5#3): la credencial de la máquina en
 * `<carpeta de datos>/files/maquina-de-efectivo.credencial`, cifrada con DPAPI en modo de MÁQUINA. Una copia del perfil (o de
 * la carpeta) en otra PC no la abre: [BovedaDeLaMaquina.leer] da null y la hoja pide emparejar otra vez.
 */
@Module
@InstallIn(SingletonComponent::class)
object MaquinaModule {
    @Provides
    @Singleton
    fun provideBovedaDeLaMaquina(@ApplicationContext context: Context): BovedaDeLaMaquina {
        val archivo = context.filesDir.toPath().resolve("maquina-de-efectivo.credencial")
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) return BovedaEnArchivo(archivo, DpapiDeMaquina)
        // Como `PreferenciasSeguras`: la Mac sólo corre el POS para desarrollar; la máquina se empareja en WINDOWS.
        Log.w("Escritorio", "Fuera de Windows no hay DPAPI: la credencial de la máquina se guarda SIN cifrar (sólo desarrollo)")
        return BovedaEnArchivo(archivo, SinCifrar)
    }
}

/**
 * DPAPI de MÁQUINA (`CryptProtectData` con `CRYPTPROTECT_LOCAL_MACHINE`, sin UI): sólo ESTA PC lo descifra, con cualquier
 * usuario suyo. A diferencia de `DpapiDeWindows` (la sesión, por usuario), una copia del perfil a otra PC no sirve.
 * 🔴 Sólo en Windows: fuera truena aquí, antes de cargar Crypt32.
 */
object DpapiDeMaquina : CodecDePreferencias {
    init {
        check(System.getProperty("os.name").orEmpty().startsWith("Windows")) { "DPAPI sólo existe en Windows" }
    }

    override fun cifrar(claro: ByteArray): ByteArray =
        Crypt32Util.cryptProtectData(claro, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN or WinCrypt.CRYPTPROTECT_LOCAL_MACHINE)

    override fun descifrar(cifrado: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(cifrado, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
}

/** Un archivo con lo que sale de [codec]. Se escribe entero o no se escribe (temporal + ATOMIC_MOVE). */
class BovedaEnArchivo(private val archivo: Path, private val codec: CodecDePreferencias) : BovedaDeLaMaquina {
    @Synchronized
    override fun guardar(credencial: String) {
        Files.createDirectories(archivo.parent)
        val temporal = Files.createTempFile(archivo.parent, ".maquina", ".tmp")
        try {
            Files.write(temporal, codec.cifrar(credencial.toByteArray(UTF_8)))
            Files.move(temporal, archivo, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporal)
        }
    }

    /** null si no hay o si esta PC no la puede descifrar (no se borra: el gerente vuelve a emparejar y la reemplaza). */
    @Synchronized
    override fun leer(): String? {
        if (Files.notExists(archivo)) return null
        return try {
            String(codec.descifrar(Files.readAllBytes(archivo)), UTF_8)
        } catch (e: Exception) {
            // 🔴 Sin el mensaje de la causa: no se arriesga a que traiga bytes de la credencial.
            Log.w("Escritorio", "La credencial de la máquina no se pudo leer en esta PC (${e.javaClass.simpleName}): hay que emparejar otra vez")
            null
        }
    }

    @Synchronized
    override fun borrar() {
        Files.deleteIfExists(archivo)
    }
}

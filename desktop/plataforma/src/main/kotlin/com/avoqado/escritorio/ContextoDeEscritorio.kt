package com.avoqado.escritorio

import android.bluetooth.BluetoothManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.util.Log
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** El Context de escritorio: todo cuelga de la carpeta de datos del usuario. */
class ContextoDeEscritorio(private val carpeta: Path) : Context() {
    private val preferencias = ConcurrentHashMap<String, PreferenciasEnArchivo>()
    private val cifradas = ConcurrentHashMap<String, SharedPreferences>()
    private val servicios = ConcurrentHashMap<String, Any>()
    private val recursos = Resources()
    private val contenido = ContentResolver()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        preferencias.computeIfAbsent(name) { PreferenciasEnArchivo(carpeta.resolve("shared_prefs").resolve("$it.json")) }

    /**
     * EncryptedSharedPreferences en Windows: la MISMA carpeta `shared_prefs`, una instancia por nombre (como
     * getSharedPreferences). [codec] se pide sólo al abrir por primera vez. Lanza PreferenciasIlegibles.
     */
    fun getPreferenciasCifradas(name: String, codec: () -> CodecDePreferencias): SharedPreferences =
        cifradas.computeIfAbsent(name) { preferenciasCifradas(carpeta.resolve("shared_prefs"), it, codec()) }
    override fun getSystemService(name: String): Any? =
        servicios.computeIfAbsent(name) { ServiciosDeSistema.crear(it) ?: NINGUNO }.takeUnless { it === NINGUNO }
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = dir("files")
    override fun getDatabasePath(name: String): File = File(dir("databases"), name)
    override fun getPackageName(): String = "com.avoqado.pos"
    override fun getPackageManager(): PackageManager = PackageManager()
    override fun getResources(): Resources = recursos
    override fun getContentResolver(): ContentResolver = contenido
    override fun startActivity(intent: Intent) = AccionesDeEscritorio.abrir(intent)

    private fun dir(nombre: String): File = Files.createDirectories(carpeta.resolve(nombre)).toFile()

    private companion object { val NINGUNO = Any() }
}

/** Los servicios del sistema que la app pide por nombre. Uno desconocido ⇒ null, como un aparato que no lo trae. */
object ServiciosDeSistema {
    fun crear(nombre: String): Any? = when (nombre) {
        Context.CONNECTIVITY_SERVICE -> ConnectivityManager()
        Context.NSD_SERVICE -> NsdManager()
        Context.WIFI_SERVICE -> WifiManager()
        Context.BLUETOOTH_SERVICE -> BluetoothManager()
        else -> null
    }
}

/** startActivity de escritorio: sólo «ver una dirección» (se abre el navegador); lo demás lo dice en la bitácora. */
object AccionesDeEscritorio {
    fun abrir(intent: Intent) {
        val uri = intent.data
        if (intent.action != Intent.ACTION_VIEW || uri == null) {
            Log.w("Escritorio", "No disponible en Windows todavía: ${intent.action}")
            return
        }
        runCatching {
            check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) { "sin navegador (¿sin ventana?)" }
            Desktop.getDesktop().browse(URI(uri.toString()))
        }.onFailure { Log.w("Escritorio", "No se pudo abrir $uri: ${it.message}") }
    }
}

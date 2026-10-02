package com.avoqado.escritorio

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `context.getSystemService(Clase::class.java)` como en Android, y el `log` de Crashlytics a la bitácora. */
class ServiciosPorClaseTest {
    private val carpeta: Path = Files.createTempDirectory("Avoqado POS servicios ñ")

    @Test fun `por clase da el MISMO servicio que por nombre`() {
        val contexto = ContextoDeEscritorio(carpeta)
        assertSame(contexto.getSystemService(Context.CONNECTIVITY_SERVICE), assertNotNull(contexto.getSystemService(ConnectivityManager::class.java)))
        assertSame(contexto.getSystemService(Context.NSD_SERVICE), assertNotNull(contexto.getSystemService(NsdManager::class.java)))
        assertSame(contexto.getSystemService(Context.WIFI_SERVICE), assertNotNull(contexto.getSystemService(WifiManager::class.java)))
        assertSame(contexto.getSystemService(Context.BLUETOOTH_SERVICE), assertNotNull(contexto.getSystemService(BluetoothManager::class.java)))
    }

    @Test fun `una clase que no es servicio da null, como un aparato que no lo trae`() {
        assertNull(ContextoDeEscritorio(carpeta).getSystemService(String::class.java))
    }

    @Test fun `tambien a traves de la actividad (ContextWrapper)`() {
        assertNotNull(ActividadDeEscritorio(carpeta).getSystemService(NsdManager::class.java))
    }

    @Test fun `el log de Crashlytics va a la bitacora como D, saneado, y en produccion no se escribe`() {
        Bitacora.iniciar(carpeta)
        FirebaseCrashlytics.getInstance().log("Impresora p1 se movió de 192.168.1.64 a 192.168.1.80")
        FirebaseCrashlytics.getInstance().log("token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.firma-falsa")
        Bitacora.soloInformativo = true
        try { FirebaseCrashlytics.getInstance().log("detalle de producción") } finally { Bitacora.soloInformativo = false }
        val texto = Files.list(carpeta.resolve("logs")).use { it.toList() }.single().readText()
        assertTrue("D/Crashlytics: Impresora p1 se movió de 192.168.1.64 a 192.168.1.80" in texto, texto)
        assertTrue("D/Crashlytics: token <jwt>" in texto, texto)
        assertFalse("detalle de producción" in texto, texto)
    }
}

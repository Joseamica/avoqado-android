package com.avoqado.pos.escritorio

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.avoqado.escritorio.Bitacora
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.escritorio.PreferenciasIlegibles
import com.google.inject.ProvisionException
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** En Windows no hay consola (javaw): si el arranque truena, el cajero ve la causa real y sabe que no se borró nada. */
class ArranqueFallidoTest {
    private val carpeta = Path.of("Avoqado POS")
    private val pie = "\nNo se borró nada. Detalle en ${carpeta.resolve("logs")}"

    @Test fun `preferencias ilegibles - dice la causa de la app bajo 3 envolturas, no la ruta que trae debajo`() {
        // La forma REAL de PreferenciasEnArchivo: su IOException trae como causa el fallo al apartar el archivo (sólo una ruta).
        val prefs = IOException(
            "Preferencias ilegibles en avoqado_secure_prefs.json y no se pudieron apartar: no se arranca encima",
            AccessDeniedException("C:\\Users\\Caja\\AppData\\Roaming\\Avoqado POS\\shared_prefs\\avoqado_secure_prefs.json"),
        )
        val envuelto = ProvisionException("Unable to provision", InvocationTargetException(UndeclaredThrowableException(prefs)))
        assertEquals("No se pudo abrir Avoqado POS: ${prefs.message}$pie", mensajeDeArranqueFallido(envuelto, carpeta))
    }

    @Test fun `preferencias cifradas ilegibles (DPAPI) - dice el motivo bajo las envolturas de Guice`() {
        val ilegibles = PreferenciasIlegibles(
            "Preferencias ilegibles: «avoqado_secure_prefs.cifrado» no se pudo descifrar: ¿otro usuario de Windows o se restableció su contraseña?",
            RuntimeException("Win32Exception: la clave no es válida"),
        )
        val envuelto = ProvisionException("Unable to provision", InvocationTargetException(ilegibles))
        assertEquals("No se pudo abrir Avoqado POS: ${ilegibles.motivo}$pie", mensajeDeArranqueFallido(envuelto, carpeta))
    }

    @Test fun `si la causa sólo trae una ruta, dice qué clase de error es`() {
        val envuelto = ProvisionException("Unable to provision", AccessDeniedException("C:\\ruta\\shared_prefs"))
        assertEquals(
            "No se pudo abrir Avoqado POS: AccessDeniedException: C:\\ruta\\shared_prefs$pie",
            mensajeDeArranqueFallido(envuelto, carpeta),
        )
    }

    @Test fun `antes de terminar el arranque no se pudo abrir, despues se cerro por un error`() {
        val envuelto = ProvisionException("Unable to provision", IOException("Preferencias ilegibles en x.json"))
        assertEquals(
            "No se pudo abrir Avoqado POS: Preferencias ilegibles en x.json$pie",
            mensajeDeArranqueFallido(envuelto, carpeta, alAbrir = true),
        )
        assertEquals(
            "Avoqado POS se cerró por un error: Preferencias ilegibles en x.json$pie",
            mensajeDeArranqueFallido(envuelto, carpeta, alAbrir = false),
        )
    }

    @Test fun `sin carpeta ni bitacora no apunta a unos logs que no existen`() {
        assertEquals(
            "No se pudo abrir Avoqado POS: AccessDeniedException: C:\\Users\\Caja\\AppData\\Roaming\nNo se borró nada.",
            mensajeDeArranqueFallido(AccessDeniedException("C:\\Users\\Caja\\AppData\\Roaming"), carpeta = null),
        )
    }

    @Test fun `una base de otra version se dice en español antes del texto de Room`() {
        val room = IllegalStateException("A migration from 999 to 13 was required but not found.")
        assertEquals(
            "No se pudo abrir Avoqado POS: La base de datos del aparato no se pudo actualizar a esta versión de la app. ${room.message}$pie",
            mensajeDeArranqueFallido(ProvisionException("Unable to provision", room), carpeta),
        )
    }

    @Test fun `una migración que no deja el esquema esperado también se dice en español`() {
        val room = IllegalStateException("Migration didn't properly handle: pending_payments(com.avoqado.pos.core.data.local.database.PendingPaymentEntity).")
        assertEquals(
            "No se pudo abrir Avoqado POS: La base de datos del aparato no se pudo actualizar a esta versión de la app. ${room.message}$pie",
            mensajeDeArranqueFallido(ProvisionException("Unable to provision", room), carpeta),
        )
    }

    @Test fun `una base de otra version truena DENTRO del arranque y no se borra nada`() {
        val carpeta = Files.createTempDirectory("Avoqado POS base ajena ñ")
        Bitacora.iniciar(carpeta)
        val base = ContextoDeEscritorio(carpeta).getDatabasePath("avoqado_db").path
        BundledSQLiteDriver().open(base).use { c ->
            listOf("CREATE TABLE marca(x TEXT)", "INSERT INTO marca VALUES('cobro pendiente')", "PRAGMA user_version = 999")
                .forEach { sql -> c.prepare(sql).use { it.step() } }
        }

        val error = assertFailsWith<Throwable> { Arranque.abrir(carpeta) }
        val mensaje = mensajeDeArranqueFallido(error, carpeta)
        println("Aviso de arranque con una base de otra versión:\n$mensaje")
        // Contra la causa, no contra el mensaje: la carpeta temporal lleva dígitos al azar y podría traer un «999».
        assertTrue(causaRaiz(error).message.orEmpty().contains("migration from 999"), mensaje)
        assertTrue(
            mensaje.startsWith("No se pudo abrir Avoqado POS: La base de datos del aparato no se pudo actualizar a esta versión de la app. A migration from 999"),
            mensaje,
        )

        BundledSQLiteDriver().open(base).use { c ->
            c.prepare("PRAGMA user_version").use { assertTrue(it.step()); assertEquals(999L, it.getLong(0)) }
            c.prepare("SELECT x FROM marca").use { assertTrue(it.step()); assertEquals("cobro pendiente", it.getText(0)) }
        }
    }
}

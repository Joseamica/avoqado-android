package com.avoqado.pos.escritorio

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.avoqado.escritorio.CodecDePreferencias
import com.avoqado.escritorio.ContextoDeEscritorio
import com.avoqado.pos.designsystem.components.LocalEsEscritorio
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.maquina.TEXTO_PENDIENTES_DE_LA_MAQUINA
import com.avoqado.pos.maquina.di.BovedaEnArchivo
import com.avoqado.pos.maquina.di.DpapiDeMaquina
import com.avoqado.pos.maquina.di.MaquinaModule
import com.avoqado.pos.maquina.ui.ContenidoDeLaMaquina
import com.avoqado.pos.maquina.ui.EstadoDeLaMaquina
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * C6 en WINDOWS (R5#3): la credencial de la máquina se guarda con DPAPI en modo de MÁQUINA (una copia del perfil en otra
 * PC no la descifra) en la carpeta de datos, nunca en claro; y la hoja dice por qué no se puede cambiar la IP ni
 * desemparejar con cobros de la máquina sin subir o en duda (R5#2).
 */
@OptIn(ExperimentalTestApi::class)
class MaquinaDeEfectivoSheetTest {
    private val carpeta = Files.createTempDirectory("maquina-de-efectivo")
    private val archivo = carpeta.resolve("files").resolve("maquina.credencial")
    @AfterTest fun limpiar() { carpeta.toFile().deleteRecursively() }

    private val credencial = "Zm9vYmFyLWNyZWRlbmNpYWwtZGUtcHJ1ZWJhLTEyMw"

    @Test fun `en WINDOWS la credencial queda cifrada con DPAPI de maquina y se vuelve a leer`() {
        assumeTrue("DPAPI sólo existe en Windows", System.getProperty("os.name").orEmpty().startsWith("Windows"))
        BovedaEnArchivo(archivo, DpapiDeMaquina).guardar(credencial)

        val blob = Files.readAllBytes(archivo)
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(credencial), "el archivo trae la credencial en claro")
        assertFalse(String(blob, Charsets.UTF_16LE).contains(credencial), "el archivo trae la credencial en claro (UTF-16)")
        assertEquals(credencial, BovedaEnArchivo(archivo, DpapiDeMaquina).leer(), "otra instancia (otro arranque) la lee")
    }

    @Test fun `la boveda guarda lo que sale del cifrado, nunca la credencial en claro`() {
        val boveda = BovedaEnArchivo(archivo, XorDePrueba)

        boveda.guardar(credencial)

        val blob = Files.readAllBytes(archivo)
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(credencial))
        assertTrue(blob.contentEquals(XorDePrueba.cifrar(credencial.toByteArray())))
        assertEquals(credencial, BovedaEnArchivo(archivo, XorDePrueba).leer())
    }

    @Test fun `P1 una credencial que esta PC no puede descifrar (perfil copiado de otra) se lee como sin credencial`() {
        BovedaEnArchivo(archivo, XorDePrueba).guardar(credencial)

        assertNull(BovedaEnArchivo(archivo, OtraPc).leer())
        assertTrue(Files.exists(archivo), "no se borra: puede ser de un disco que se lee mal un momento")
    }

    @Test fun `borrar quita la credencial del disco`() {
        val boveda = BovedaEnArchivo(archivo, XorDePrueba)
        boveda.guardar(credencial)

        boveda.borrar()

        assertFalse(Files.exists(archivo))
        assertNull(boveda.leer())
        boveda.borrar()   // repetirlo no truena
    }

    @Test fun `la credencial vive en la carpeta de datos del POS`() {
        val boveda = MaquinaModule.provideBovedaDeLaMaquina(ContextoDeEscritorio(carpeta))

        boveda.guardar(credencial)

        val guardados = carpeta.toFile().walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, guardados.size, "esperaba un solo archivo: $guardados")
        assertEquals(credencial, boveda.leer())
        boveda.borrar()
    }

    @Test fun `P1 con cobros de la maquina sin subir la hoja dice por que y no deja desemparejar`() = runComposeUiTest {
        val estado = EstadoDeLaMaquina(
            cargado = true, ip = "192.168.1.50", ipGuardada = "192.168.1.50", activa = true, conCredencial = true,
            hayPendientes = true,
        )
        setContent { Hoja(estado) }

        onNodeWithText(TEXTO_PENDIENTES_DE_LA_MAQUINA).assertIsDisplayed()
        onNodeWithText("Desemparejar").assertIsNotEnabled()
        onNodeWithText("Probar conexión").assertIsEnabled()
    }

    @Test fun `sin emparejar la hoja deja emparejar una IP bien escrita`() = runComposeUiTest {
        setContent { Hoja(EstadoDeLaMaquina(cargado = true, ip = "192.168.1.50")) }

        onNodeWithText("Emparejar esta PC con la máquina").assertIsEnabled()
        onNodeWithText("Sin emparejar").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun Hoja(estado: EstadoDeLaMaquina) {
        val contexto = ContextoDeEscritorio(carpeta)
        CompositionLocalProvider(
            LocalContext provides contexto,
            LocalConfiguration provides contexto.resources.configuration,
            LocalView provides View(contexto),
            LocalEsEscritorio provides true,
        ) {
            AvoqadoTheme {
                ContenidoDeLaMaquina(estado, onIp = {}, onProbar = {}, onEmparejar = {}, onDesemparejar = {}, onCerrar = {})
            }
        }
    }

    /** Un cifrado reversible de mentira: sirve para ver que la bóveda escribe lo cifrado y no el claro. */
    private object XorDePrueba : CodecDePreferencias {
        override fun cifrar(claro: ByteArray) = ByteArray(claro.size) { (claro[it].toInt() xor 0x5A).toByte() }
        override fun descifrar(cifrado: ByteArray) = cifrar(cifrado)
    }

    /** Lo que DPAPI hace en otra PC con un blob de máquina ajeno: no lo puede abrir. */
    private object OtraPc : CodecDePreferencias {
        override fun cifrar(claro: ByteArray) = claro
        override fun descifrar(cifrado: ByteArray): ByteArray = throw IllegalStateException("Clave no válida para el estado especificado")
    }
}

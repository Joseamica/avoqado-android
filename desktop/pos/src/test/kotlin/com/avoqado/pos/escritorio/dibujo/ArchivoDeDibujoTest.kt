package com.avoqado.pos.escritorio.dibujo

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue

/** `dibujo.json` (hz:a5fa2439:D2): ante cualquier duda, automático; nunca lanza. */
class ArchivoDeDibujoTest {
    private val carpeta = Files.createTempDirectory("dibujo")
    private val archivo = carpeta.resolve("dibujo.json")
    @AfterTest fun limpiar() {
        carpeta.toFile().setWritable(true)
        carpeta.toFile().deleteRecursively()
    }

    @Test fun `ida y vuelta devuelve el mismo ajuste`() {
        val a = Ajuste(modo = Modo.RAPIDO, intentando = true, fallo = true, sinAviso = true)
        assertTrue(ArchivoDeDibujo.guardar(carpeta, a))
        assertEquals(a, ArchivoDeDibujo.leer(carpeta))
    }

    @Test fun `sin archivo es automatico`() {
        assertEquals(Ajuste(), ArchivoDeDibujo.leer(carpeta))
    }

    @Test fun `archivo danado es automatico sin lanzar`() {
        Files.writeString(archivo, "{basura")
        assertEquals(Ajuste(), ArchivoDeDibujo.leer(carpeta))
    }

    @Test fun `intentando en automatico es incoherente`() {
        Files.writeString(archivo, """{"modo":"AUTO","intentando":true}""")
        assertEquals(Ajuste(), ArchivoDeDibujo.leer(carpeta))
    }

    @Test fun `carpeta de solo lectura - guardar devuelve false sin lanzar y el archivo de antes sigue`() {
        assumeTrue("sólo con permisos POSIX", Files.getFileStore(carpeta).supportsFileAttributeView("posix"))
        val antes = Ajuste(modo = Modo.RAPIDO)
        assertTrue(ArchivoDeDibujo.guardar(carpeta, antes))
        Files.setPosixFilePermissions(carpeta, PosixFilePermissions.fromString("r-x------"))
        assumeFalse("corre como root: la carpeta sigue escribible", Files.isWritable(carpeta))
        assertFalse(ArchivoDeDibujo.guardar(carpeta, Ajuste(modo = Modo.AUTO, fallo = true)))
        assertEquals(antes, ArchivoDeDibujo.leer(carpeta))
    }

    @Test fun `tras guardar no queda temporal`() {
        assertTrue(ArchivoDeDibujo.guardar(carpeta, Ajuste(modo = Modo.RAPIDO)))
        assertTrue(ArchivoDeDibujo.guardar(carpeta, Ajuste(modo = Modo.AUTO, fallo = true)))
        assertEquals(listOf("dibujo.json"), carpeta.listDirectoryEntries().map { it.name })
    }

    @Test fun `la pista de caer sola a software va y vuelve, y un archivo de antes la lee apagada`() {
        val a = Ajuste(caeSolaASoftware = true)
        assertTrue(ArchivoDeDibujo.guardar(carpeta, a))
        assertEquals(a, ArchivoDeDibujo.leer(carpeta))
        Files.writeString(archivo, """{"modo":"AUTO","intentando":false,"fallo":false,"sinAviso":false}""")
        assertEquals(Ajuste(), ArchivoDeDibujo.leer(carpeta))
    }

    @Test fun `un campo desconocido se ignora`() {
        Files.writeString(archivo, """{"modo":"RAPIDO","campoDeUnaVersionNueva":"x"}""")
        assertEquals(Ajuste(modo = Modo.RAPIDO), ArchivoDeDibujo.leer(carpeta))
    }
}

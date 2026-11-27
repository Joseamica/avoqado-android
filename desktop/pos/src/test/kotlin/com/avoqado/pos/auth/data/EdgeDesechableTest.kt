package com.avoqado.pos.auth.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edge con perfil desechable en WINDOWS (hz:6bfcd3a1:D1, Codex r4#1). */
class EdgeDesechableTest {
    private val sufijo = "Microsoft\\Edge\\Application\\msedge.exe"

    @Test fun `buscarEdge toma la primera ruta que existe`() {
        val env = mapOf("ProgramFiles(x86)" to "X86", "ProgramFiles" to "PF", "LOCALAPPDATA" to "LA")
        val r = buscarEdge({ env[it] }) { it.path == File("PF", sufijo).path || it.path == File("LA", sufijo).path }
        assertEquals(File("PF", sufijo).path, r?.path)
    }

    @Test fun `sin ninguna ⇒ null`() = assertNull(buscarEdge({ "C" }) { false })

    @Test fun `variable de entorno ausente se salta`() {
        val r = buscarEdge({ if (it == "LOCALAPPDATA") "LA" else null }) { true }
        assertEquals(File("LA", sufijo).path, r?.path)
    }

    @Test fun `la orden lleva --user-data-dir con la carpeta del intento, no pasa por cmd ni lleva --inprivate`() {
        val perfil = File("tmp", "avoqado-google-1")
        val o = ordenDeEdge(File("msedge.exe"), perfil, "https://x/?a=1&b=2")
        assertEquals(
            listOf("msedge.exe", "--user-data-dir=${perfil.path}", "--no-first-run", "--no-default-browser-check", "https://x/?a=1&b=2"),
            o,
        )
        assertFalse(o.any { it.contains("inprivate", true) || it.equals("cmd", true) })
    }

    private class ProcesoFalso : Process() {
        var muerto = false
        override fun destroy() { muerto = true }
        override fun destroyForcibly(): Process { muerto = true; return this }
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
    }

    @Test fun `limpiar mata el proceso y borra la carpeta`() {
        val perfil = Files.createTempDirectory("avoqado-google-").toFile()
        File(perfil, "Default").mkdirs()
        val p = ProcesoFalso()
        limpiar(p, perfil, esperaMs = 0)
        assertTrue(p.muerto)
        assertFalse(perfil.exists())
    }

    @Test fun `limpiar sin proceso igual borra la carpeta`() {
        val perfil = Files.createTempDirectory("avoqado-google-").toFile()
        limpiar(null, perfil, esperaMs = 0)
        assertFalse(perfil.exists())
    }

    @Test fun `borrar que falla 3 veces no lanza, lo intenta 3 veces y la carpeta queda para el siguiente intento`() {
        val perfil = Files.createTempDirectory("avoqado-google-").toFile()
        try {
            var veces = 0
            limpiar(ProcesoFalso(), perfil, borrar = { veces++; false }, esperaMs = 0)
            assertEquals(3, veces)
            assertTrue(perfil.exists())
        } finally {
            perfil.deleteRecursively()
        }
    }

    @Test fun `borrar que lanza tampoco rompe`() {
        val perfil = Files.createTempDirectory("avoqado-google-").toFile()
        try {
            assertFalse(borrarPerfil(perfil, { error("bloqueado") }, 0))
        } finally {
            perfil.deleteRecursively()
        }
    }
}

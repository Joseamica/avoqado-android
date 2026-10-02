package com.avoqado.escritorio

import org.json.JSONObject
import org.junit.Assume
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reglas 1, 2 y 3 de `PreferenciasCifradas`: lectura estricta del claro, marcador huérfano, cifrado + marcador y el claro que queda
 * junto al cifrado verificado (se aparta, nunca se borra). Mismas pruebas de siempre, en su propio archivo (el original pasaba de 500 líneas).
 */
class PreferenciasCifradasReglasTest {

    // --- Regla 1: lectura estricta del claro ---

    @Test fun `un claro con JSON invalido lanza danado y no se toca`() {
        val c = Carpeta()
        Files.createDirectories(c.dir)
        Files.writeString(c.claro, "{\"accessToken\":")
        val antes = c.foto()
        val e = assertFailsWith<PreferenciasIlegibles> { c.abrir() }
        assertTrue("dañado" in e.motivo, e.motivo)
        assertEquals(antes, c.foto(), "se apartó, renombró o creó algo")
    }

    @Test fun `un claro que no se puede leer lanza no se pudo leer y no se toca`() {
        val c = Carpeta()
        c.sembrarClaro()
        val antes = c.foto()
        Files.setPosixFilePermissions(c.claro, PosixFilePermissions.fromString("-w-------"))
        try {
            Assume.assumeFalse("corre como root: no hay archivo sin permiso de lectura", Files.isReadable(c.claro))
            val e = assertFailsWith<PreferenciasIlegibles> { c.abrir() }
            assertTrue("no se pudo leer" in e.motivo, e.motivo)
        } finally {
            Files.setPosixFilePermissions(c.claro, PosixFilePermissions.fromString("rw-------"))
        }
        assertEquals(antes, c.foto())
    }

    @Test fun `el token no llega a la bitacora - un valor con tipo invalido no viaja en ningun motivo, causa ni aviso`() {
        // org.json mete el VALOR en su mensaje: «JSONObject["v"] is not a int (class java.lang.String : eyJ-SECRETO)».
        for (json in listOf("{\"accessToken\":{\"t\":\"i\",\"v\":\"$SECRETO\"}}", "{\"accessToken\":\"$SECRETO\"}")) {
            val conClaro = Carpeta().apply { Files.createDirectories(dir); Files.writeString(claro, json) }
            val sinVerificar = Carpeta().apply { Files.createDirectories(dir); Files.write(cifrado, CodecFalso.cifrar(json.toByteArray())) }
            val verificado = Carpeta().apply {
                Files.createDirectories(dir); Files.write(cifrado, CodecFalso.cifrar(json.toByteArray())); Files.writeString(marcador, "ninguno\n")
            }
            for (c in listOf(conClaro, sinVerificar, verificado)) {
                val e = assertFailsWith<PreferenciasIlegibles>(json) { c.abrir() }
                assertTrue("dañado" in e.motivo, e.motivo)
                assertFalse(SECRETO in pila(e), "el valor viajó en el motivo o en una causa:\n${pila(e)}")
            }
            // La lectura tolerante (sin códec): se aparta y su aviso tampoco lo trae; y si no se puede apartar, tampoco lo que lanza.
            val tolerante = Carpeta().apply { Files.createDirectories(dir); Files.writeString(claro, json) }
            val (_, bitacora) = bitacoraDe { PreferenciasEnArchivo(tolerante.claro) }
            assertFalse(SECRETO in bitacora, bitacora)
            val sinApartar = Carpeta().apply { Files.createDirectories(dir); Files.writeString(claro, json) }
            sinApartar.sinEscritura {
                val e = assertFailsWith<IOException> { PreferenciasEnArchivo(sinApartar.claro) }
                assertFalse(SECRETO in pila(e), pila(e))
            }
        }
    }

    @Test fun `si no se puede saber si existen los archivos, lanza no se pudo leer y no toca nada`() {
        val c = Carpeta()
        c.sembrarClaro()
        c.abrir()   // convierte: cifrado + marcador
        val antes = c.foto()
        Files.setPosixFilePermissions(c.dir, PosixFilePermissions.fromString("rw-------"))   // sin x: no se puede ni ver un archivo
        try {
            Assume.assumeFalse("corre como root: la existencia sí se puede saber", Files.exists(c.marcador) || Files.notExists(c.marcador))
            val e = assertFailsWith<PreferenciasIlegibles> { c.abrir() }
            assertTrue("no se pudo leer" in e.motivo, e.motivo)
        } finally {
            Files.setPosixFilePermissions(c.dir, PosixFilePermissions.fromString("rwx------"))
        }
        assertEquals(antes, c.foto())
    }

    @Test fun `borrar o apartar el claro tambien reintenta la violacion de uso compartido de Windows`() {
        assertTrue(esBloqueoPasajero(FileSystemException("x.json", null, "being used by another process")))
        assertTrue(esBloqueoPasajero(AccessDeniedException("x.json")))
        assertTrue(esBloqueoPasajero(FileAlreadyExistsException("x.json.version-anterior-1")))
        assertFalse(esBloqueoPasajero(NoSuchFileException("x.json")), "lo que ya no existe no se reintenta")
        assertFalse(esBloqueoPasajero(IOException("disco")))
    }

    // --- Regla 2: marcador huérfano ---

    @Test fun `marcador huerfano con claro - se borra el marcador y se convierte desde el claro`() {
        val c = Carpeta()
        val valores = c.sembrarClaro()
        Files.writeString(c.marcador, "verificado")
        // Si el huérfano siguiera ahí, morir con el cifrado recién escrito lo daría por verificado.
        assertFailsWith<MuerteSimulada> {
            c.abrir {
                if (it == PasoDeConversion.CIFRADO_ESCRITO) {
                    assertFalse(Files.exists(c.marcador), "el marcador huérfano seguía al escribir el cifrado")
                    throw MuerteSimulada()
                }
            }
        }
        Files.write(c.cifrado, cifradoDe("accessToken" to "token-viejo-sin-verificar"))
        assertEquals(valores, c.abrir().all)
        assertTrue(Files.exists(c.cifrado) && Files.exists(c.marcador))
        assertFalse(Files.exists(c.claro))
    }

    @Test fun `marcador huerfano sin claro - nace vacio y verificado`() {
        val c = Carpeta()
        Files.createDirectories(c.dir)
        Files.writeString(c.marcador, "verificado")
        val p = c.abrir()
        assertTrue(p.all.isEmpty())
        assertTrue(Files.exists(c.marcador))
        assertEquals("{}", String(CodecFalso.descifrar(Files.readAllBytes(c.cifrado)), UTF_8))
    }

    @Test fun `un marcador huerfano que no se puede borrar lanza y no toca nada`() {
        val c = Carpeta()
        c.sembrarClaro()
        Files.writeString(c.marcador, "verificado")
        val antes = c.foto()
        c.sinEscritura { assertFailsWith<PreferenciasIlegibles> { c.abrir() } }
        assertEquals(antes, c.foto())
    }

    // --- Regla 3: cifrado + marcador ---

    @Test fun `marcador con cifrado ilegible (con o sin claro) lanza, no recurre al claro y no borra nada`() {
        val indescifrable = "basura sin cabecera".toByteArray()
        val danado = CodecFalso.cifrar("{\"accessToken\":".toByteArray())
        for ((bytes, motivo) in listOf(indescifrable to MOTIVO_DPAPI, danado to "dañado")) {
            for (conClaro in listOf(true, false)) {
                val c = Carpeta()
                if (conClaro) c.sembrarClaro() else Files.createDirectories(c.dir)
                Files.write(c.cifrado, bytes)
                Files.writeString(c.marcador, "verificado")
                val antes = c.foto()
                val e = assertFailsWith<PreferenciasIlegibles>("$motivo conClaro=$conClaro") { c.abrir() }
                assertTrue(motivo in e.motivo, e.motivo)
                assertEquals(antes, c.foto(), "$motivo conClaro=$conClaro: se apartó, borró o creó algo")
            }
        }
    }

    @Test fun `marcador con cifrado que no se puede leer lanza no se pudo leer y no borra nada`() {
        val c = Carpeta()
        c.sembrarClaro()
        c.abrir()   // convierte: cifrado + marcador
        PreferenciasEnArchivo(c.claro).edit().putString("accessToken", "claro-viejo").commit()
        val antes = c.foto()
        Files.setPosixFilePermissions(c.cifrado, PosixFilePermissions.fromString("-w-------"))
        try {
            Assume.assumeFalse("corre como root: no hay archivo sin permiso de lectura", Files.isReadable(c.cifrado))
            val e = assertFailsWith<PreferenciasIlegibles> { c.abrir() }
            assertTrue("no se pudo leer" in e.motivo, e.motivo)
        } finally {
            Files.setPosixFilePermissions(c.cifrado, PosixFilePermissions.fromString("rw-------"))
        }
        assertEquals(antes, c.foto())
    }

    // --- Regla 3: el claro que queda junto al cifrado verificado. Se borra SÓLO el que se convirtió (huella del marcador) ---

    @Test fun `el claro ya convertido que no se pudo borrar se queda con aviso y se borra al reintentar, sin apartar nada`() {
        val c = Carpeta()
        val valores = c.sembrarClaro()
        val original = Files.readAllBytes(c.claro)
        assertFailsWith<MuerteSimulada> { c.abrir { if (it == PasoDeConversion.MARCADOR_PUBLICADO) throw MuerteSimulada() } }
        assertEquals(huella(original), c.marcadorDice())
        c.sinEscritura {
            assertEquals(valores, c.abrir().all, "no arrancó con el cifrado verificado")
            assertContentEquals(original, Files.readAllBytes(c.claro))
        }
        assertEquals(valores, c.abrir().all)
        assertFalse(Files.exists(c.claro), "no se reintentó borrar el claro (misma huella) en el siguiente arranque")
        assertTrue(c.apartados().isEmpty(), "se apartó el MISMO claro que se convirtió")
    }

    @Test fun `un claro escrito despues de convertir (version anterior del POS) se aparta byte a byte, manda el cifrado y avisa`() {
        val c = Carpeta()
        val valores = c.sembrarClaro()
        c.abrir()   // convierte y borra el claro
        // Una versión anterior del POS (sin cifrado) abre la misma carpeta: no ve sesión y deja un cobro pendiente en claro.
        assertTrue(PreferenciasEnArchivo(c.claro).edit().putString("accessToken", "token-de-la-version-anterior").putString("pendingCardCharges", PENDIENTES).commit())
        val delViejo = Files.readAllBytes(c.claro)
        val (p, bitacora) = bitacoraDe { c.abrir() }
        assertEquals(valores, p.all, "no mandó el cifrado")
        assertFalse(Files.exists(c.claro))
        val apartado = c.apartados().single()
        assertContentEquals(delViejo, Files.readAllBytes(apartado), "no se apartó byte a byte")
        assertTrue("versión anterior del POS" in bitacora && apartado.fileName.toString() in bitacora, bitacora)
        assertTrue("trae operaciones pendientes: avisar a soporte" in bitacora, bitacora)
        val antes = c.foto()
        assertEquals(valores, c.abrir().all)
        assertEquals(antes, c.foto(), "el siguiente arranque tocó el apartado")
    }

    @Test fun `el aviso de pendientes cubre toda llave pending (vales de caja externa, cajon) y calla si no hay`() {
        val casos = listOf(
            "pendingAreaTicketPrintRecords" to "[{\"codigo\":\"V-1\"}]",
            "pendingAreaTicketIssueKey" to "llave-1",
            "pendingDrawerOps.venue-1" to "[{\"op\":\"retiro\"}]",
            "accessToken" to "solo-sesion",
        )
        for ((llave, valor) in casos) {
            val c = Carpeta()
            c.sembrarClaro()
            c.abrir()   // convierte y borra el claro
            assertTrue(PreferenciasEnArchivo(c.claro).edit().putString(llave, valor).commit())
            val (_, bitacora) = bitacoraDe { c.abrir() }
            assertTrue("versión anterior del POS" in bitacora, "$llave: $bitacora")
            assertEquals(llave.startsWith("pending"), "trae operaciones pendientes" in bitacora, "$llave: $bitacora")
            assertFalse(valor in bitacora, "$llave: la bitácora trae el valor")
        }
    }

    @Test fun `un marcador sin huella (ninguno, vacio o el formato viejo) nunca borra un claro - lo aparta`() {
        for (contenido in listOf("ninguno\n", "", "verificado\n")) {
            val c = Carpeta()
            assertTrue(c.abrir().edit().putString("deviceId", "del-cifrado").commit())   // aparato nuevo: marcador «ninguno»
            Files.writeString(c.marcador, contenido)
            c.sembrarClaro()
            val claro = Files.readAllBytes(c.claro)
            val (p, bitacora) = bitacoraDe { c.abrir() }
            assertEquals("del-cifrado", p.getString("deviceId", null), "«$contenido»: no mandó el cifrado")
            assertFalse(Files.exists(c.claro), "«$contenido»")
            assertContentEquals(claro, Files.readAllBytes(c.apartados().single()), "«$contenido»")
            assertTrue("trae operaciones pendientes" in bitacora, "«$contenido»: $bitacora")
        }
    }

    @Test fun `un claro distinto que no se puede apartar se queda donde esta (nunca se borra) y se aparta al volver los permisos`() {
        val c = Carpeta()
        val valores = c.sembrarClaro()
        c.abrir()
        assertTrue(PreferenciasEnArchivo(c.claro).edit().putString("accessToken", "token-de-la-version-anterior").commit())
        val delViejo = Files.readAllBytes(c.claro)
        c.sinEscritura {
            assertEquals(valores, c.abrir().all)
            assertContentEquals(delViejo, Files.readAllBytes(c.claro), "se tocó el claro sin poder apartarlo")
            assertTrue(c.apartados().isEmpty())
        }
        assertEquals(valores, c.abrir().all)
        assertFalse(Files.exists(c.claro))
        assertContentEquals(delViejo, Files.readAllBytes(c.apartados().single()))
    }

    @Test fun `un claro que no se puede leer junto al cifrado verificado no se toca, arranca y se resuelve al siguiente arranque`() {
        val c = Carpeta()
        val valores = c.sembrarClaro()
        assertFailsWith<MuerteSimulada> { c.abrir { if (it == PasoDeConversion.MARCADOR_PUBLICADO) throw MuerteSimulada() } }
        val antes = c.foto()
        Files.setPosixFilePermissions(c.claro, PosixFilePermissions.fromString("-w-------"))
        try {
            Assume.assumeFalse("corre como root: no hay archivo sin permiso de lectura", Files.isReadable(c.claro))
            assertEquals(valores, c.abrir().all)
        } finally {
            Files.setPosixFilePermissions(c.claro, PosixFilePermissions.fromString("rw-------"))
        }
        assertEquals(antes, c.foto(), "se borró o apartó un claro que no se pudo leer")
        assertEquals(valores, c.abrir().all)
        assertFalse(Files.exists(c.claro), "ya legible y con la misma huella, se borra")
        assertTrue(c.apartados().isEmpty())
    }

    @Test fun `un marcador que no se puede leer no cambia quien manda - arranca el cifrado y el claro se aparta, no se borra`() {
        val c = Carpeta()
        val valores = c.sembrarClaro()
        val original = Files.readAllBytes(c.claro)
        assertFailsWith<MuerteSimulada> { c.abrir { if (it == PasoDeConversion.MARCADOR_PUBLICADO) throw MuerteSimulada() } }
        Files.setPosixFilePermissions(c.marcador, PosixFilePermissions.fromString("-w-------"))
        try {
            Assume.assumeFalse("corre como root: no hay archivo sin permiso de lectura", Files.isReadable(c.marcador))
            assertEquals(valores, c.abrir().all)
        } finally {
            Files.setPosixFilePermissions(c.marcador, PosixFilePermissions.fromString("rw-------"))
        }
        assertFalse(Files.exists(c.claro))
        assertContentEquals(original, Files.readAllBytes(c.apartados().single()))
    }

    @Test fun `si el claro cambia a media conversion, se convierte lo leido (una sola lectura) y el claro nuevo se aparta`() {
        val c = Carpeta()
        val valores = c.sembrarClaro()
        val original = Files.readAllBytes(c.claro)
        var escritoEnMedio = ByteArray(0)
        val p = c.abrir {
            if (it == PasoDeConversion.CIFRADO_ESCRITO) {
                check(PreferenciasEnArchivo(c.claro).edit().putString("accessToken", "escrito-a-media-conversion").commit())
                escritoEnMedio = Files.readAllBytes(c.claro)
            }
        }
        assertEquals(valores, p.all)
        assertEquals(huella(original), c.marcadorDice(), "la huella no salió de los MISMOS bytes que se convirtieron")
        assertFalse(Files.exists(c.claro))
        assertContentEquals(escritoEnMedio, Files.readAllBytes(c.apartados().single()), "se borró un claro distinto del convertido")
    }
}

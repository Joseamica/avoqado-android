package com.avoqado.escritorio

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.junit.Assume
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 🔴 En `avoqado_secure_prefs` viven el token, los cobros con tarjeta sin confirmar y la cola del cajón. Ningún error
 * arranca vacío, ninguna interrupción hace aceptar un cifrado sin verificar, y nada se aparta ni se borra fuera de las
 * reglas. DPAPI sólo existe en Windows: aquí cifra un códec falso reversible.
 */
class PreferenciasCifradasTest {

    // --- Regla 6 + ida y vuelta ---

    @Test fun `ida y vuelta de los 6 tipos`() {
        val c = Carpeta()
        assertTrue(
            c.abrir().edit().putString("s", "hola ñ").putBoolean("b", true).putInt("i", 7).putLong("l", 9_000_000_000L)
                .putFloat("f", 1.5f).putStringSet("set", setOf("a", "b")).commit(),
        )
        val p = c.abrir()
        assertEquals("hola ñ", p.getString("s", null))
        assertTrue(p.getBoolean("b", false))
        assertEquals(7, p.getInt("i", 0))
        assertEquals(9_000_000_000L, p.getLong("l", 0))
        assertEquals(1.5f, p.getFloat("f", 0f))
        assertEquals(setOf("a", "b"), p.getStringSet("set", null))
        assertEquals(
            mapOf<String, Any>("s" to "hola ñ", "b" to true, "i" to 7, "l" to 9_000_000_000L, "f" to 1.5f, "set" to setOf("a", "b")),
            p.all,
        )
        assertFalse(Files.exists(c.claro), "se guardó en claro")
        assertFalse(contiene(Files.readAllBytes(c.cifrado), "hola"), "el valor quedó legible en el cifrado")
    }

    @Test fun `aparato nuevo - nace con cifrado vacio verificado, guarda y el segundo arranque conserva lo guardado`() {
        val c = Carpeta()
        val p = c.abrir()
        assertTrue(p.all.isEmpty())
        assertTrue(Files.exists(c.marcador), "un aparato nuevo no publicó .verificado")
        assertEquals("ninguno", c.marcadorDice(), "nació sin claro: el marcador no tiene huella que guardar")
        assertEquals("{}", String(CodecFalso.descifrar(Files.readAllBytes(c.cifrado)), UTF_8))
        assertTrue(p.edit().putString("accessToken", TOKEN).putString("deviceId", DEVICE_ID).commit())
        val segundo = c.abrir()
        assertEquals(mapOf<String, Any>("accessToken" to TOKEN, "deviceId" to DEVICE_ID), segundo.all)
        assertFalse(Files.exists(c.claro))
    }

    @Test fun `aparato nuevo - si muere entre el cifrado vacio y el marcador, el siguiente arranque publica el marcador y sigue`() {
        for (paso in listOf(PasoDeConversion.CIFRADO_ESCRITO, PasoDeConversion.CIFRADO_VERIFICADO, PasoDeConversion.MARCADOR_PUBLICADO)) {
            val c = Carpeta()
            assertFailsWith<MuerteSimulada>("$paso") { c.abrir { if (it == paso) throw MuerteSimulada() } }
            val p = c.abrir()
            assertTrue(p.all.isEmpty(), "$paso")
            assertTrue(Files.exists(c.marcador), "$paso: no se publicó el marcador")
            assertTrue(p.edit().putString("pendingCardCharges", PENDIENTES).commit(), "$paso")
            assertEquals(PENDIENTES, c.abrir().getString("pendingCardCharges", null), "$paso")
        }
    }

    // --- Regla 4: conversión desde el claro ---

    @Test fun `conversion - el claro desaparece solo despues de verificado y el token no queda legible en el cifrado`() {
        val c = Carpeta()
        val valores = c.sembrarClaro()
        val original = Files.readAllBytes(c.claro)
        val visto = mutableListOf<String>()
        val p = c.abrir { paso -> visto += "$paso claro=${Files.exists(c.claro)} marcador=${Files.exists(c.marcador)}" }
        assertEquals(
            listOf(
                "CIFRADO_ESCRITO claro=true marcador=false",
                "CIFRADO_VERIFICADO claro=true marcador=false",
                "MARCADOR_PUBLICADO claro=true marcador=true",
                "CLARO_BORRADO claro=false marcador=true",
            ),
            visto,
        )
        assertEquals(valores, p.all)
        assertFalse(Files.exists(c.claro))
        assertEquals(huella(original), c.marcadorDice(), "el marcador no dice QUÉ claro se convirtió")
        assertTrue(c.apartados().isEmpty(), "se apartó el MISMO claro que se convirtió")
        val bytes = Files.readAllBytes(c.cifrado)
        assertFalse(contiene(bytes, TOKEN), "el token quedó legible en el cifrado")
        assertFalse(contiene(bytes, "accessToken"), "las llaves quedaron legibles en el cifrado")
    }

    @Test fun `si el proceso muere despues de cualquier paso, el siguiente arranque termina bien y nunca acepta un cifrado sin verificar`() {
        for (paso in PasoDeConversion.entries) {
            val c = Carpeta()
            val valores = c.sembrarClaro()
            val original = Files.readAllBytes(c.claro)
            assertFailsWith<MuerteSimulada>("$paso") { c.abrir { if (it == paso) throw MuerteSimulada() } }
            val verificado = paso >= PasoDeConversion.MARCADOR_PUBLICADO
            assertEquals(verificado, Files.exists(c.marcador), "$paso: el marcador no puede existir antes de terminar la verificación")
            if (!verificado) {
                assertContentEquals(original, Files.readAllBytes(c.claro), "$paso: el claro cambió sin .verificado")
                // Un cifrado sin verificar no manda NUNCA: aunque sea legible y diga otra cosa, gana el claro.
                Files.write(c.cifrado, cifradoDe("accessToken" to "token-viejo-sin-verificar"))
            }
            val p = c.abrir()
            assertEquals(valores, p.all, "$paso")
            assertTrue(Files.exists(c.cifrado) && Files.exists(c.marcador), "$paso")
            assertFalse(Files.exists(c.claro), "$paso")
            assertEquals(valores, c.abrir().all, "$paso: segundo arranque")
        }
    }

    @Test fun `un cifrado sin verificar junto al claro (legible, indescifrable o danado) se ignora y se reescribe desde el claro`() {
        val danado = CodecFalso.cifrar("{\"accessToken\":".toByteArray())
        for ((nombre, bytes) in listOf("legible" to cifradoDe("accessToken" to "otro"), "indescifrable" to "basura".toByteArray(), "dañado" to danado)) {
            val c = Carpeta()
            val valores = c.sembrarClaro()
            Files.write(c.cifrado, bytes)
            assertEquals(valores, c.abrir().all, nombre)
            assertTrue(Files.exists(c.marcador), nombre)
            assertFalse(Files.exists(c.claro), nombre)
            assertEquals(valores, c.abrir().all, nombre)
        }
    }

    @Test fun `si falla un paso de la conversion, se borra el cifrado a medias, lanza y el claro sigue de autoridad`() {
        for (paso in listOf(PasoDeConversion.CIFRADO_ESCRITO, PasoDeConversion.CIFRADO_VERIFICADO)) {
            val c = Carpeta()
            val valores = c.sembrarClaro()
            val original = Files.readAllBytes(c.claro)
            assertFailsWith<PreferenciasIlegibles>("$paso") { c.abrir { if (it == paso) throw IOException("disco lleno") } }
            assertFalse(Files.exists(c.cifrado), "$paso: quedó el cifrado a medias")
            assertFalse(Files.exists(c.marcador), "$paso")
            assertContentEquals(original, Files.readAllBytes(c.claro), "$paso")
            assertEquals(valores, c.abrir().all, "$paso: el siguiente arranque convierte")
        }
    }

    @Test fun `si el cifrado releido no sale identico (llaves, valores o tipos), no se publica el marcador y el claro sigue`() {
        val olvidaTodo = codecQueAlDescifrar { "{}".toByteArray() }
        val cambiaUnTipo = codecQueAlDescifrar { String(it, UTF_8).replace("\"t\":\"i\"", "\"t\":\"l\"").toByteArray() }
        val noDescifra = codecQueAlDescifrar { throw IllegalStateException("DPAPI: clave equivocada") }
        val casos = listOf(Triple("olvida todo", olvidaTodo, "comparar"), Triple("cambia un tipo", cambiaUnTipo, "comparar"), Triple("no descifra", noDescifra, "releer"))
        for ((nombre, codec, paso) in casos) {
            val c = Carpeta()
            val valores = c.sembrarClaro()
            val original = Files.readAllBytes(c.claro)
            val e = assertFailsWith<PreferenciasIlegibles>(nombre) { c.abrir(codec) }
            assertTrue("falló al $paso" in e.motivo && "no se pudo cifrar" !in e.motivo, "$nombre: el motivo no dice qué paso falló: ${e.motivo}")
            assertEquals(1, vecesElPrefijo(e), "$nombre: ${e.motivo}")
            assertFalse(Files.exists(c.cifrado), "$nombre: quedó el cifrado a medias")
            assertFalse(Files.exists(c.marcador), "$nombre: se publicó un marcador sin verificar")
            assertContentEquals(original, Files.readAllBytes(c.claro), nombre)
            assertEquals(valores, c.abrir().all, "$nombre: con un códec sano sí convierte")
        }
    }

    @Test fun `si no se puede publicar el marcador, el motivo lo dice (no «no se pudo cifrar») y el claro sigue`() {
        val c = Carpeta()
        c.sembrarClaro()
        val original = Files.readAllBytes(c.claro)
        // El marcador es una carpeta con algo dentro: el rename atómico no la puede reemplazar.
        val e = assertFailsWith<PreferenciasIlegibles> {
            c.abrir { if (it == PasoDeConversion.CIFRADO_VERIFICADO) Files.createDirectories(c.marcador.resolve("ocupado")) }
        }
        assertTrue("falló al publicar" in e.motivo && "no se pudo cifrar" !in e.motivo, e.motivo)
        assertEquals(1, vecesElPrefijo(e), e.motivo)
        assertFalse(Files.exists(c.cifrado), "quedó el cifrado a medias")
        assertContentEquals(original, Files.readAllBytes(c.claro))
    }

    @Test fun `un surrogate suelto no se guarda cambiado por un signo de interrogacion - commit da false y el cifrado anterior queda intacto`() {
        val c = Carpeta()
        val p = c.abrir()
        assertTrue(p.edit().putString("cliente", "bien").commit())
        val antes = c.foto()
        assertFalse(p.edit().putString("cliente", "emoji roto \uD83D").commit(), "se guardó algo distinto de lo que hay en memoria")
        assertEquals(antes, c.foto())
        assertEquals("bien", c.abrir().getString("cliente", null))
    }

    @Test fun `dos aperturas seguidas conservan todas las llaves con su tipo, incluidos el cobro pendiente y el aparato`() {
        val c = Carpeta()
        val valores = c.sembrarClaro()
        val primera = c.abrir()
        assertEquals(valores, primera.all)
        val nuevos = """[{"requestId":"req-2","orderId":"ord-10","amountCents":9900}]"""
        assertTrue(primera.edit().putString("pendingCardCharges", nuevos).commit())
        val segunda = c.abrir()
        assertEquals(valores + ("pendingCardCharges" to nuevos), segunda.all)
        assertIs<String>(segunda.all["pendingCardCharges"])
        assertEquals(DEVICE_ID, segunda.getString("deviceId", null))
        assertIs<Int>(segunda.all["venueCount"])
        assertIs<Long>(segunda.all["tokenExpiresAt"])
        assertIs<Float>(segunda.all["propinaSugerida"])
        assertEquals(valores + ("pendingCardCharges" to nuevos), c.abrir().all)
    }

    // --- Regla 5: cifrado sin marcador y sin claro ---

    @Test fun `un cifrado vacio sin marcador ni claro es una inicializacion interrumpida - se publica el marcador`() {
        val c = Carpeta()
        Files.createDirectories(c.dir)
        Files.write(c.cifrado, CodecFalso.cifrar("{}".toByteArray()))
        assertTrue(c.abrir().all.isEmpty())
        assertEquals("ninguno", c.marcadorDice())
    }

    @Test fun `un cifrado NO vacio o indescifrable sin marcador ni claro lanza y no se toca`() {
        for (bytes in listOf(cifradoDe("accessToken" to TOKEN), "basura sin cabecera".toByteArray())) {
            val c = Carpeta()
            Files.createDirectories(c.dir)
            Files.write(c.cifrado, bytes)
            val antes = c.foto()
            val e = assertFailsWith<PreferenciasIlegibles> { c.abrir() }
            assertTrue("cifrado sin verificar" in e.motivo, e.motivo)
            assertEquals(1, vecesElPrefijo(e), e.motivo)
            assertEquals(antes, c.foto(), "se apartó, borró o creó algo")
        }
    }

    // --- Regla 7: EncryptedSharedPreferences ---

    @Test fun `en Windows EncryptedSharedPreferences cifra en la carpeta shared_prefs del contexto, una instancia por nombre`() {
        val c = Carpeta()
        val actividad = ActividadDeEscritorio(c.raiz)
        val p = PreferenciasSeguras.abrir(actividad, NOMBRE, windows = true) { CodecFalso }
        assertTrue(p.edit().putString("accessToken", TOKEN).commit())
        assertTrue(Files.exists(c.cifrado) && Files.exists(c.marcador), "no quedó en ${c.cifrado}")
        assertFalse(Files.exists(c.claro))
        assertFalse(contiene(Files.readAllBytes(c.cifrado), TOKEN))
        assertSame(p, PreferenciasSeguras.abrir(actividad, NOMBRE, windows = true) { error("una segunda apertura no vuelve a abrir") })
    }

    @Test fun `fuera de Windows EncryptedSharedPreferences es lo de hoy y no toca DPAPI`() {
        Assume.assumeFalse("esta prueba es de fuera de Windows", System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val c = Carpeta()
        val actividad = ActividadDeEscritorio(c.raiz)
        val p = EncryptedSharedPreferences.create(
            actividad, NOMBRE, MasterKey.Builder(actividad).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        assertSame(actividad.getSharedPreferences(NOMBRE, 0), p)
        assertTrue(p.edit().putString("accessToken", TOKEN).commit())
        assertTrue(Files.exists(c.claro))
        assertFalse(Files.exists(c.cifrado))
        assertSame(p, PreferenciasSeguras.abrir(actividad, NOMBRE, windows = false) { error("DpapiDeWindows se pidió fuera de Windows") })
    }

    @Test fun `DpapiDeWindows nunca se instancia fuera de Windows - tocarlo truena antes de llamar a Crypt32`() {
        Assume.assumeFalse("esta prueba es de fuera de Windows", System.getProperty("os.name").orEmpty().startsWith("Windows"))
        assertFails { DpapiDeWindows.toString() }
    }
}

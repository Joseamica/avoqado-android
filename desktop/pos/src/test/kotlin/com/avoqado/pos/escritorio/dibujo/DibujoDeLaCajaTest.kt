package com.avoqado.pos.escritorio.dibujo

import com.avoqado.pos.BuildConfig
import com.avoqado.pos.escritorio.Diagnostico
import com.avoqado.pos.escritorio.diagnostico.DatosDelEquipo
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue

/** El dueño del modo de dibujo (hz:a5fa2439:D2): reloj falso, carpeta temporal y un `fijar` que anota. */
class DibujoDeLaCajaTest {
    private val carpeta = Files.createTempDirectory("dibujo-caja")
    private val archivo = carpeta.resolve("dibujo.json")
    private var relojNs = 0L
    private val fijados = mutableListOf<String>()
    private var archivoAlFijar: Ajuste? = null
    private val caja = DibujoDeLaCaja
    private val e get() = caja.estado.value

    @BeforeTest fun armar() = caja.reiniciarParaPruebas()

    @AfterTest fun limpiar() {
        caja.reiniciarParaPruebas()
        carpeta.toFile().setWritable(true)
        carpeta.toFile().deleteRecursively()
    }

    /** Un proceso nuevo: se rearma el objeto y abre con este entorno y estas propiedades. */
    private fun arrancar(entorno: Map<String, String> = emptyMap(), propiedades: Map<String, String> = emptyMap()) {
        caja.reiniciarParaPruebas()
        relojNs = 0
        fijados.clear()
        archivoAlFijar = null
        caja.alArrancar(carpeta, entorno::get, propiedades::get, fijar = { fijados += it; archivoAlFijar = crudo() }, ahoraNs = { relojNs })
    }

    private fun pintar(motor: String, ms: Long) {
        relojNs += ms * 1_000_000
        caja.alPintar(motor)
    }

    /** Lo que dice el archivo, sin la lectura indulgente de ArchivoDeDibujo (que tomaría «intentando + AUTO» por automático). */
    private fun crudo(): Ajuste? = if (archivo.exists()) Json.decodeFromString(Ajuste.serializer(), archivo.readText()) else null

    private fun escribir(a: Ajuste) = assertTrue(ArchivoDeDibujo.guardar(carpeta, a))

    private fun soloLectura() {
        assumeTrue("sólo con permisos POSIX", Files.getFileStore(carpeta).supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(carpeta, PosixFilePermissions.fromString("r-x------"))
        assumeFalse("corre como root: la carpeta sigue escribible", Files.isWritable(carpeta))
    }

    private fun escribible() = Files.setPosixFilePermissions(carpeta, PosixFilePermissions.fromString("rwx------"))

    @Test fun `sin archivo no fija ni escribe nada y Direct3D rapido es fluida`() {
        arrancar()
        assertEquals(emptyList(), fijados)
        assertFalse(archivo.exists())
        pintar("DIRECT3D", 600)
        assertFalse(archivo.exists())
        assertEquals(Rendimiento.FLUIDA, e.rendimiento)
        assertEquals(600L, e.pintarMs)
    }

    @Test fun `OpenGL lento ofrece el rapido, elegirlo se guarda y la siguiente apertura lo fija`() {
        val revisiones = mutableListOf<Long>()
        arrancar()
        caja.alCambiar = { _, n -> revisiones += n }
        pintar("OPENGL", 9_400)
        assertEquals(Aviso.OFRECER_RAPIDO, e.aviso)
        assertEquals(1, revisiones.size)

        assertTrue(caja.elegir(Modo.RAPIDO))
        assertEquals(Ajuste(modo = Modo.RAPIDO), crudo())
        assertTrue(caja.recienElegido.value)
        assertTrue(e.aplicaAlReabrir)
        assertEquals(2, revisiones.size)
        assertTrue(revisiones[1] > revisiones[0])

        // Siguiente proceso: la marca de intento ya está escrita cuando se fija el motor.
        arrancar()
        assertEquals(listOf(ModoDeDibujo.MOTOR_RAPIDO), fijados)
        assertEquals(Ajuste(modo = Modo.RAPIDO, intentando = true), archivoAlFijar)
        assertTrue(e.modoLigero)
        assertFalse(caja.recienElegido.value)
        pintar("SOFTWARE_FAST", 700)
        assertEquals(Ajuste(modo = Modo.RAPIDO), crudo())
        assertTrue(e.forzada)
        assertFalse(e.aplicaAlReabrir)
    }

    @Test fun `la apertura rapida anterior no pinto - no fija, regresa a automatico y avisa el fallo`() {
        escribir(Ajuste(modo = Modo.RAPIDO, intentando = true))
        arrancar()
        assertEquals(emptyList(), fijados)
        assertEquals(Ajuste(modo = Modo.AUTO, fallo = true), crudo())
        pintar("OPENGL", 9_400)
        assertEquals(Aviso.FALLO_RAPIDO, e.aviso)
    }

    @Test fun `carpeta de solo lectura con rapido - no fija y nada lanza`() {
        escribir(Ajuste(modo = Modo.RAPIDO))
        soloLectura()
        arrancar()
        assertEquals(emptyList(), fijados)
        assertFalse(e.forzada)
        pintar("OPENGL", 600)
        assertEquals(emptyList(), fijados)
        escribible()
        assertEquals(Ajuste(modo = Modo.RAPIDO), crudo())
    }

    @Test fun `carpeta de solo lectura con la marca puesta - esta apertura va en automatico con fallo y el archivo la conserva`() {
        escribir(Ajuste(modo = Modo.RAPIDO, intentando = true))
        soloLectura()
        arrancar()
        assertEquals(emptyList(), fijados)
        assertEquals(Modo.AUTO, e.modo)
        assertTrue(e.fallo)
        pintar("OPENGL", 9_400)
        assertEquals(Aviso.FALLO_RAPIDO, e.aviso)
        assertFalse(e.aplicaAlReabrir)
        escribible()
        assertEquals(Ajuste(modo = Modo.RAPIDO, intentando = true), crudo())
    }

    @Test fun `si falla el guardado que quita la marca, la siguiente apertura va en automatico con fallo`() {
        escribir(Ajuste(modo = Modo.RAPIDO))
        arrancar()
        assertEquals(listOf(ModoDeDibujo.MOTOR_RAPIDO), fijados)
        soloLectura()
        pintar("SOFTWARE_FAST", 700)
        escribible()
        assertEquals(Ajuste(modo = Modo.RAPIDO, intentando = true), crudo())

        arrancar()
        assertEquals(emptyList(), fijados)
        assertEquals(Modo.AUTO, e.modo)
        assertTrue(e.fallo)
        assertEquals(Ajuste(modo = Modo.AUTO, fallo = true), crudo())
    }

    @Test fun `se fijo, Skiko abrio con otro motor y no se puede guardar - esta sesion ya dice automatico con fallo`() {
        escribir(Ajuste(modo = Modo.RAPIDO))
        arrancar()
        assertEquals(listOf(ModoDeDibujo.MOTOR_RAPIDO), fijados)
        val anotados = mutableListOf<Estado>()
        caja.alCambiar = { estado, _ -> anotados += estado }
        soloLectura()
        pintar("OPENGL", 9_400)
        assertEquals(Modo.AUTO, e.modo)
        assertTrue(e.fallo)
        assertFalse(e.forzada)
        assertFalse(e.aplicaAlReabrir)
        assertEquals(Aviso.FALLO_RAPIDO, e.aviso)
        assertEquals(listOf(e), anotados)
        escribible()
        assertEquals(Ajuste(modo = Modo.RAPIDO, intentando = true), crudo())
    }

    @Test fun `pinto sin poder quitar la marca y luego elige automatico - el archivo queda coherente`() {
        escribir(Ajuste(modo = Modo.RAPIDO))
        arrancar()
        soloLectura()
        pintar("SOFTWARE_FAST", 700)
        escribible()
        assertTrue(caja.elegir(Modo.AUTO))
        assertEquals(Ajuste(modo = Modo.AUTO, sinAviso = true), crudo())

        arrancar()
        assertEquals(emptyList(), fijados)
        assertEquals(Modo.AUTO, e.modo)
        assertFalse(e.fallo)
        assertEquals(Ajuste(modo = Modo.AUTO, sinAviso = true), crudo())
    }

    @Test fun `elegir sin poder guardar - false y nada cambia`() {
        arrancar()
        pintar("OPENGL", 9_400)
        val antes = e
        soloLectura()
        assertFalse(caja.elegir(Modo.RAPIDO))
        assertEquals(antes, e)
        assertFalse(caja.recienElegido.value)
    }

    @Test fun `callar el aviso sin poder guardar lo calla en esta sesion`() {
        arrancar()
        pintar("OPENGL", 9_400)
        soloLectura()
        assertFalse(caja.callarAviso())
        assertNull(e.aviso)
        escribible()
        assertFalse(archivo.exists())
    }

    @Test fun `dos hilos eligen a la vez - gana un resultado completo y el ultimo aviso trae ese estado`() {
        arrancar()
        pintar("OPENGL", 9_400)
        repeat(200) {
            val anotados = Collections.synchronizedList(mutableListOf<Pair<Estado, Long>>())
            caja.alCambiar = { estado, n -> anotados += estado to n }
            val salida = CountDownLatch(1)
            val resultados = Collections.synchronizedList(mutableListOf<Boolean>())
            val hilos = listOf(Modo.RAPIDO, Modo.AUTO).map { modo -> thread { salida.await(); resultados += caja.elegir(modo) } }
            salida.countDown()
            hilos.forEach { it.join() }

            assertEquals(listOf(true, true), resultados.toList())
            val final = crudo()
            assertTrue(final == Ajuste(modo = Modo.RAPIDO) || final == Ajuste(modo = Modo.AUTO, sinAviso = true), "archivo: $final")
            // el archivo entero dice lo mismo que estado, sin marca (elegir automático calla los avisos)
            assertEquals(Ajuste(modo = e.modo, fallo = e.fallo, sinAviso = e.modo == Modo.AUTO), final)
            val (ultimo, revision) = anotados.last()
            assertEquals(e, ultimo)
            assertEquals(anotados.maxOf { it.second }, revision)
        }
    }

    @Test fun `un valor a mano que Skiko no entiende no prende el modo ligero antes de pintar`() {
        arrancar(entorno = mapOf("SKIKO_RENDER_API" to "software"))   // Skiko lo ignora y abre con su motor de siempre
        assertTrue(e.aMano)
        assertFalse(e.modoLigero)
    }

    @Test fun `motor a mano en software - no fija, ligero desde el arranque y elegir no aplica al reabrir`() {
        escribir(Ajuste(modo = Modo.RAPIDO))
        arrancar(entorno = mapOf("SKIKO_RENDER_API" to "SOFTWARE"))
        assertEquals(emptyList(), fijados)
        assertEquals(Ajuste(modo = Modo.RAPIDO), crudo())
        assertTrue(e.aMano)
        assertTrue(e.modoLigero)
        assertTrue(caja.elegir(Modo.AUTO))
        assertEquals(Ajuste(modo = Modo.AUTO, sinAviso = true), crudo())
        assertFalse(e.aplicaAlReabrir)
    }

    // MARK: - La cabecera del sondeo, fuera del camino de la apertura

    private val equipoFalso = DatosDelEquipo(
        windows = true, versionSO = "10.0", ramGb = 3.87, nucleos = 4, procesador = "Intel(R) Core(TM) i5-3470 CPU @ 3.20GHz",
        discoLibreGb = 40.0, anchoPx = 1366, altoPx = 768, puntosTactiles = 0,
        motorDeDibujo = null, primerCuadroMs = null, toqueConElDedo = null,
    )

    @Test fun `al pintar la cabecera sale sin leer el equipo, y al leerlo se republica completa con una revision mayor`() {
        val lecturas = mutableListOf<Boolean>()   // true = se leyó DURANTE alPintar
        var pintando = false
        val cabeceras = mutableListOf<String?>()
        val sondeo = SondeoDelDibujo(esWindows = true, leerEquipo = { lecturas += pintando; equipoFalso }) { cabeceras += it }
        arrancar()
        caja.alCambiar = sondeo.alCambiar
        pintando = true
        pintar("OPENGL", 9_400)
        pintando = false
        assertEquals(emptyList(), lecturas, "el equipo no se lee en el camino de la apertura")
        val primera = cabeceras.single()!!
        assertTrue(";rev=1;" in primera && primera.endsWith(";hand=0"), primera)   // sólo los pares obligatorios

        val antes = e
        assertEquals(equipoFalso, sondeo.equipo())
        assertEquals(listOf(false), lecturas)
        assertEquals(antes, e, "republicar no cambia el estado")
        val segunda = cabeceras.last()!!
        assertEquals(2, cabeceras.size)
        assertTrue(";rev=2;" in segunda, segunda)
        assertTrue(segunda.endsWith(";cpu=Intel(R) Core(TM) i5-3470 CPU @ 3.20GHz;cores=4;ram=3.9;screen=1366x768"), segunda)
        assertEquals(primera.substringAfter(";rev=1"), segunda.substringAfter(";rev=2").substringBefore(";cpu="))
    }

    @Test fun `republicar sin pintar o con el apagador no hace nada`() {
        val revisiones = mutableListOf<Long>()
        arrancar()
        caja.alCambiar = { _, n -> revisiones += n }
        caja.republicar()
        assertEquals(emptyList(), revisiones)

        arrancar(propiedades = mapOf("avoqado.dibujo.apagado" to "true"))
        caja.alCambiar = { _, n -> revisiones += n }
        pintar("OPENGL", 9_400)
        caja.republicar()
        assertEquals(emptyList(), revisiones)
    }

    @Test fun `con el apagador el diagnostico no habla de como dibuja`() {
        arrancar(propiedades = mapOf("avoqado.dibujo.apagado" to "true"))
        assertNull(caja.paraElDiagnostico())
        val logs = Files.createTempDirectory("diag-apagado")
        try {
            Diagnostico.escribir(logs, "DIRECT3D", 1234, "omitido: no es Windows", caja.paraElDiagnostico())
            val lineas = logs.resolve("logs/diagnostico.txt").readText().lines()
            assertFalse(lineas.any { it.startsWith("Dibujo:") }, lineas.joinToString("\n"))
        } finally {
            logs.toFile().deleteRecursively()
        }
        arrancar()
        assertEquals(e, caja.paraElDiagnostico())   // sin el apagador, lo de siempre
    }

    // MARK: - La pista: la computadora cae sola a software

    @Test fun `la primera vez que cae sola a software se anota la pista y la siguiente apertura ya arranca ligera`() {
        arrancar()
        assertFalse(archivo.exists(), "sin archivo, nada se escribe antes de la ventana")
        assertFalse(e.modoLigero)
        pintar("SOFTWARE_FAST", 700)
        assertTrue(e.modoLigero)
        assertEquals(Ajuste(caeSolaASoftware = true), crudo())

        arrancar()
        assertEquals(emptyList(), fijados, "la pista no fija ningún motor")
        assertTrue(e.modoLigero, "ligero antes de pintar")
        assertFalse(e.forzada)
    }

    @Test fun `con la pista no fija nada y, si pinta con la tarjeta de video, deja de ser ligera y se quita`() {
        escribir(Ajuste(caeSolaASoftware = true))
        arrancar()
        assertEquals(emptyList(), fijados)
        assertTrue(e.modoLigero)
        assertEquals(Ajuste(caeSolaASoftware = true), crudo(), "nada se escribe antes de la ventana")
        pintar("DIRECT3D", 600)
        assertFalse(e.modoLigero)
        assertEquals(Ajuste(), crudo())
    }

    @Test fun `a mano manda - la pista no cuenta y no se anota`() {
        escribir(Ajuste(caeSolaASoftware = true))
        arrancar(entorno = mapOf("SKIKO_RENDER_API" to "DIRECT3D"))
        assertFalse(e.modoLigero)
        pintar("DIRECT3D", 600)
        assertEquals(Ajuste(caeSolaASoftware = true), crudo())

        escribir(Ajuste())
        arrancar(entorno = mapOf("SKIKO_RENDER_API" to "SOFTWARE"))
        pintar("SOFTWARE", 700)
        assertEquals(Ajuste(), crudo())
    }

    @Test fun `apagado - ninguna funcion lee, escribe ni fija`() {
        val inicial = ModoDeDibujo.estado(Ajuste(), Apertura(aMano = false, fijo = false, pidioSoftware = false))
        assertEquals(inicial, e)
        val antes = Ajuste(modo = Modo.RAPIDO, intentando = true)
        escribir(antes)
        var avisos = 0
        arrancar(propiedades = mapOf("avoqado.dibujo.apagado" to "true"))
        caja.alCambiar = { _, _ -> avisos++ }
        pintar("OPENGL", 9_400)
        assertFalse(caja.elegir(Modo.RAPIDO))
        assertFalse(caja.callarAviso())
        assertEquals(emptyList(), fijados)
        assertEquals(antes, crudo())
        assertEquals(listOf("dibujo.json"), carpeta.listDirectoryEntries().map { it.name })
        assertEquals(inicial, e)
        assertEquals(0, avisos)
        assertFalse(caja.recienElegido.value)
    }

    @Test fun `pintar dos veces - la segunda no hace nada`() {
        var avisos = 0
        arrancar()
        caja.alCambiar = { _, _ -> avisos++ }
        pintar("OPENGL", 9_400)
        val despues = e
        pintar("DIRECT3D", 100)
        assertEquals(despues, e)
        assertEquals(1, avisos)
    }

    @Test fun `desarrollo - fingir el motor y el tiempo, y no fijar trata la apertura como fijada`() {
        assumeFalse("las propiedades de desarrollo no valen en producción", BuildConfig.PRODUCCION)
        arrancar(propiedades = mapOf("avoqado.dibujo.fingir" to "OPENGL:9000"))
        pintar("METAL", 50)
        assertEquals("OPENGL", e.motor)
        assertEquals(Rendimiento.NO_ALCANZA, e.rendimiento)

        escribir(Ajuste(modo = Modo.RAPIDO))
        arrancar(propiedades = mapOf("avoqado.dibujo.noFijar" to "true", "avoqado.dibujo.fingir" to "SOFTWARE_FAST:700"))
        assertEquals(emptyList(), fijados)
        assertEquals(Ajuste(modo = Modo.RAPIDO, intentando = true), crudo())
        pintar("METAL", 50)
        assertEquals(Ajuste(modo = Modo.RAPIDO), crudo())
        assertTrue(e.forzada)
    }
}

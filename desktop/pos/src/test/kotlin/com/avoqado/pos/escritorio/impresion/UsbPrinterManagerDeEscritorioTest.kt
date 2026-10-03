package com.avoqado.pos.escritorio.impresion

import com.avoqado.pos.printing.data.UsbPrinterManager
import com.avoqado.pos.printing.data.model.PrinterException
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsbPrinterManagerDeEscritorioTest {
    /** Detiene un hilo DENTRO del falso (abrir o escribir) hasta que la prueba lo suelte. */
    private class Pausa {
        val entro = CountDownLatch(1)
        val suelta = CountDownLatch(1)
        fun aqui() { entro.countDown(); suelta.await(10, TimeUnit.SECONDS) }
    }

    /**
     * Un Windows falso. Como el de verdad: un COM se abre EXCLUSIVO (una segunda apertura física falla) y un handle
     * cerrado no escribe nada.
     */
    private class SistemaFalso : SistemaDeImpresion {
        val colasHoy = mutableListOf(ColaInfo("POS-80", "USB001", "Generic / Text Only", 0, 0), ColaInfo("Cocina", "USB002", "Generic / Text Only", 0, 0))
        val puertosHoy = mutableListOf(PuertoInfo("COM3", "\\Device\\USBSER000"))
        val trabajos: MutableList<Pair<String, ByteArray>> = Collections.synchronizedList(mutableListOf())
        val escritoEnPuerto = ConcurrentHashMap<String, MutableList<Byte>>()
        val puertosAbiertos: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val aperturasDePuerto = AtomicInteger()
        val baudiosPedidos: MutableList<Int?> = Collections.synchronizedList(mutableListOf())
        /** «abrir COM3» / «cerrar COM3», en el orden en que Windows los vio. */
        val eventos: MutableList<String> = Collections.synchronizedList(mutableListOf())
        /** Cuántas veces se le preguntó a «Windows» por sus colas o puertos. */
        val consultas = AtomicInteger()
        @Volatile var truenaSiLoConsultan = false
        @Volatile var fallaAlListar: Throwable? = null
        @Volatile var fallaAlLeerCola: Throwable? = null
        @Volatile var fallaAlImprimir: Throwable? = null
        @Volatile var fallaAlAbrirPuerto: Throwable? = null
        @Volatile var fallaAlEscribirEnPuerto: Throwable? = null
        @Volatile var esperaAlAbrirPuertoMs = 0L
        @Volatile var esperaPorByteMs = 0L
        @Volatile var pausaDeApertura: Pausa? = null
        val pausaDeEscritura = ConcurrentHashMap<String, Pausa>()

        private fun consulta() {
            consultas.incrementAndGet()
            if (truenaSiLoConsultan) throw AssertionError("le preguntaron a Windows en el hilo de la pantalla")
        }

        override fun colas(): List<ColaInfo> { consulta(); return fallaAlListar?.let { throw it } ?: colasHoy.toList() }
        override fun cola(nombre: String): ColaInfo? { consulta(); return fallaAlLeerCola?.let { throw it } ?: colasHoy.firstOrNull { it.nombre == nombre } }
        override fun puertos(): List<PuertoInfo> { consulta(); return fallaAlListar?.let { throw it } ?: puertosHoy.toList() }
        override fun imprimirEnCola(nombre: String, documento: String, bytes: ByteArray) {
            fallaAlImprimir?.let { throw it }
            trabajos += nombre to bytes
        }
        override fun abrirPuerto(puerto: String, baudios: Int?): CanalAbierto {
            fallaAlAbrirPuerto?.let { throw it }
            baudiosPedidos += baudios
            aperturasDePuerto.incrementAndGet()
            if (esperaAlAbrirPuertoMs > 0) Thread.sleep(esperaAlAbrirPuertoMs)
            pausaDeApertura?.aqui()
            if (puertosHoy.none { it.puerto == puerto }) throw PrinterException.ConnectionFailed("$puerto no existe")
            if (!puertosAbiertos.add(puerto)) throw PrinterException.ConnectionFailed("$puerto ya está abierto (Windows lo abre exclusivo)")
            eventos += "abrir $puerto"
            return object : CanalAbierto {
                @Volatile var cerrado = false
                override fun escribir(bytes: ByteArray) {
                    if (cerrado) throw PrinterException.PrintFailed("$puerto: escritura en un handle cerrado")
                    fallaAlEscribirEnPuerto?.let { throw it }
                    pausaDeEscritura[puerto]?.aqui()
                    val destino = escritoEnPuerto.computeIfAbsent(puerto) { Collections.synchronizedList(mutableListOf()) }
                    for (b in bytes) {
                        if (cerrado) throw PrinterException.PrintFailed("$puerto: lo cerraron a media escritura")
                        destino += b
                        if (esperaPorByteMs > 0) Thread.sleep(esperaPorByteMs)
                    }
                }
                override fun close() {
                    if (cerrado) return
                    // Primero se anota y DESPUÉS se suelta el puerto: quien espera «ya no está abierto» (esperarHasta) ve
                    // también el «cerrar». Al revés, en el runner lento de GitHub la prueba leía la lista entre los dos pasos.
                    cerrado = true; eventos += "cerrar $puerto"; puertosAbiertos -= puerto
                }
            }
        }
    }

    private lateinit var falso: SistemaFalso
    private lateinit var original: SistemaDeImpresion
    private val usb = UsbPrinterManager(com.avoqado.escritorio.ContextoDeEscritorio(java.nio.file.Files.createTempDirectory("usb-escritorio")))

    @BeforeTest fun antes() { original = ImpresionDeEscritorio.sistema; falso = SistemaFalso(); ImpresionDeEscritorio.sistema = falso }
    @AfterTest fun despues() { ImpresionDeEscritorio.sistema = original }

    /** El cierre pendiente se completa en otro hilo: se espera a que se cumpla, con tope. */
    private fun esperarHasta(condicion: () -> Boolean) {
        val limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condicion() && System.nanoTime() < limite) Thread.sleep(5)
    }

    /** Espera a que el hilo deje de correr: o terminó, o está detenido esperando un candado. */
    private fun esperarQueSeDetenga(t: Thread) {
        val limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while ((t.state == Thread.State.NEW || t.state == Thread.State.RUNNABLE) && System.nanoTime() < limite) Thread.sleep(5)
    }

    @Test fun `buscar lista las colas y los puertos de Windows`() {
        assertEquals(listOf("usb:cola:Cocina", "usb:cola:POS-80", "usb:com:COM3"), usb.discoverPrinters().map { it.address }.sorted())
    }

    @Test fun `cola - abrir, escribir y la impresora recibe los bytes tal cual`() = runBlocking<Unit> {
        usb.open("p1", "usb:cola:POS-80")
        assertTrue(usb.isOpen("p1"))
        val ticket = byteArrayOf(0x1B, 0x40, 0x41, 0x0A, 0x1B, 0x70, 0x00, 0x32, 0xFA.toByte())   // ESC @, «A», ESC p (cajón)
        usb.write("p1", ticket)
        assertEquals("POS-80", falso.trabajos.single().first)
        assertContentEquals(ticket, falso.trabajos.single().second)
    }

    /** #2: con el cliente esperando su cambio, «sin papel» no puede impedir abrir el cajón. */
    @Test fun `sin papel - el pulso del cajon llega igual`() = runBlocking<Unit> {
        falso.colasHoy[0] = falso.colasHoy[0].copy(estado = 0x10)   // PRINTER_STATUS_PAPER_OUT
        usb.open("caja", "usb:cola:POS-80")
        // Los 18 bytes que mandó openCashDrawer en la prueba de Windows real (evidencia/recibido-9101-002.bin).
        val pulso = byteArrayOf(0x1B, 0x40, 0x1B, 0x74, 0x10, 0x1D, 0x4C, 0x00, 0x00, 0x1D, 0x57, 0x40, 0x02, 0x1B, 0x70, 0x00, 0x32, 0xFA.toByte())
        usb.write("caja", pulso)
        assertContentEquals(pulso, falso.trabajos.single().second)
    }

    @Test fun `cola fuera de linea - open falla con el motivo y no queda abierta`() = runBlocking<Unit> {
        falso.colasHoy[0] = falso.colasHoy[0].copy(estado = 0x80)
        val e = assertFailsWith<PrinterException.ConnectionFailed> { usb.open("p1", "usb:cola:POS-80") }
        assertTrue(e.message!!.contains("fuera de línea"))
        assertFalse(usb.isOpen("p1"))
    }

    @Test fun `una cola que ya no existe falla al abrir con el remedio`() = runBlocking<Unit> {
        val e = assertFailsWith<PrinterException.ConnectionFailed> { usb.open("p9", "usb:cola:Desconectada") }
        assertTrue(
            e.message!!.contains("Windows ya no tiene la impresora «Desconectada» (la borraron o le cambiaron el nombre). Bórrala de Avoqado y vuelve a buscarla."),
            e.message,
        )
    }

    /**
     * #3: PrinterService llama findDevice ANTES de su withContext(IO), o sea en el hilo de la pantalla: no puede consultar
     * a Windows (GetPrinter puede tardar). Sólo dice si la dirección es de Windows; si la impresora ya no está, lo dicen
     * open y write, que corren en IO.
     */
    @Test fun `findDevice no le pregunta nada a Windows`() {
        falso.truenaSiLoConsultan = true
        assertNotNull(usb.findDevice("usb:cola:POS-80"))
        assertNotNull(usb.findDevice("usb:cola:Desconectada"))
        assertNotNull(usb.findDevice("usb:com:COM9"))
        assertNull(usb.findDevice("usb:1208:514"))
        assertNull(usb.findDevice("192.168.1.50"))
        assertEquals(0, falso.consultas.get(), "findDevice consultó a Windows")
    }

    @Test fun `com - abre una vez, escribe y cierra`() = runBlocking<Unit> {
        usb.open("p2", "usb:com:COM3")
        usb.write("p2", byteArrayOf(1, 2, 3))
        usb.open("p2", "usb:com:COM3")                      // reabrir no abre un segundo canal
        assertEquals(setOf("COM3"), falso.puertosAbiertos)
        usb.close("p2")
        assertTrue(falso.puertosAbiertos.isEmpty())
        assertEquals<List<Byte>?>(listOf<Byte>(1, 2, 3), falso.escritoEnPuerto["COM3"])
    }

    /** #13 y #14: «Buscar» guarda el COM sin velocidad: se abre con la que Windows tiene; una escrita se respeta. */
    @Test fun `com - sin velocidad abre con la de Windows, con velocidad abre con esa`() = runBlocking<Unit> {
        usb.open("barra", "usb:com:COM3")
        usb.close("barra")
        usb.open("barra", "usb:com:COM3:115200")
        assertEquals(listOf<Int?>(null, 115_200), falso.baudiosPedidos.toList())
    }

    /** #23: de verdad a la vez: un COM atorado a media escritura no detiene a las demás impresoras. */
    @Test fun `todas a la vez - un COM atorado no detiene a las demas`() {
        falso.puertosHoy += PuertoInfo("COM4", "\\Device\\USBSER001")
        runBlocking {
            usb.open("caja", "usb:cola:POS-80"); usb.open("cocina", "usb:cola:Cocina")
            usb.open("barra", "usb:com:COM3"); usb.open("postres", "usb:com:COM4")
        }
        val pausa = Pausa().also { falso.pausaDeEscritura["COM3"] = it }
        val errores = Collections.synchronizedList(mutableListOf<Throwable>())
        val barra = thread { runCatching { usb.write("barra", byteArrayOf(5)) }.onFailure { errores += it } }
        assertTrue(pausa.entro.await(5, TimeUnit.SECONDS), "la barra no llegó a escribir")
        val otras = listOf("caja" to 7, "cocina" to 9, "postres" to 3).map { (id, b) ->
            thread { runCatching { usb.write(id, byteArrayOf(b.toByte())) }.onFailure { errores += it } }
        }
        otras.forEach { it.join(5_000) }
        assertTrue(otras.none { it.isAlive }, "una impresora atorada detuvo a las demás")
        assertTrue(barra.isAlive, "la barra sigue atorada")
        assertEquals(setOf("POS-80", "Cocina"), falso.trabajos.map { it.first }.toSet())
        assertEquals<List<Byte>?>(listOf<Byte>(3), falso.escritoEnPuerto["COM4"])
        pausa.suelta.countDown()
        barra.join(5_000)
        assertEquals(emptyList(), errores.toList())
        assertEquals<List<Byte>?>(listOf<Byte>(5), falso.escritoEnPuerto["COM3"])
        usb.close("caja")                                                                    // cerrar una no cierra las demás
        assertTrue(usb.isOpen("cocina")); assertTrue(usb.isOpen("barra")); assertTrue(usb.isOpen("postres"))
    }

    @Test fun `escribir sin abrir es NotConnected, igual que Android`() {
        assertFailsWith<PrinterException.NotConnected> { usb.write("nadie", byteArrayOf(1)) }
    }

    @Test fun `una direccion de Android (usb VID PID) no se puede abrir en Windows y lo dice`() = runBlocking<Unit> {
        val e = assertFailsWith<PrinterException.ConnectionFailed> { usb.open("p3", "usb:1208:514") }
        assertTrue(e.message!!.contains("Esta impresora se configuró en otro aparato. Bórrala y vuelve a buscarla."), e.message)
    }

    // --- un Error de JNA nunca sale como Error (PrinterService sólo atrapa Exception: sin esto, ni cajón ni comanda) ---

    @Test fun `un Error de JNA al imprimir en cola sale como PrinterException y la impresora queda cerrada`() = runBlocking<Unit> {
        usb.open("p1", "usb:cola:POS-80")
        falso.fallaAlImprimir = UnsatisfiedLinkError("Unable to load library 'winspool.drv'")
        val e = assertFailsWith<PrinterException> { usb.write("p1", byteArrayOf(1, 2)) }
        assertTrue(e.message!!.contains("No se pudo hablar con la impresora en Windows"), e.message)
        assertFalse("winspool" in e.message!!, e.message)
        assertFalse(usb.isOpen("p1"))
    }

    @Test fun `un Error de JNA al escribir en COM sale como PrinterException y el puerto queda cerrado`() = runBlocking<Unit> {
        usb.open("barra", "usb:com:COM3")
        falso.fallaAlEscribirEnPuerto = UnsatisfiedLinkError("kernel32")
        assertFailsWith<PrinterException> { usb.write("barra", byteArrayOf(1)) }
        assertFalse(usb.isOpen("barra"))
        assertTrue(falso.puertosAbiertos.isEmpty())
    }

    @Test fun `un Error de JNA al abrir sale como ConnectionFailed, y findDevice nunca lanza`() = runBlocking<Unit> {
        falso.fallaAlAbrirPuerto = NoClassDefFoundError("com/sun/jna/platform/win32/Kernel32")
        assertFailsWith<PrinterException.ConnectionFailed> { usb.open("barra", "usb:com:COM3") }
        assertFalse(usb.isOpen("barra"))
        falso.fallaAlLeerCola = UnsatisfiedLinkError("winspool.drv")
        assertFailsWith<PrinterException.ConnectionFailed> { usb.open("p1", "usb:cola:POS-80") }
        assertFalse(usb.isOpen("p1"))
        assertNotNull(usb.findDevice("usb:cola:POS-80"))   // no consulta a Windows: no tiene cómo tronar
    }

    // --- lo que pasa entre abrir y escribir ---

    @Test fun `pausada entre abrir y escribir - ConnectionFailed y queda cerrada`() = runBlocking<Unit> {
        usb.open("p1", "usb:cola:POS-80")
        falso.colasHoy[0] = falso.colasHoy[0].copy(estado = 0x1)
        val e = assertFailsWith<PrinterException.ConnectionFailed> { usb.write("p1", byteArrayOf(1)) }
        assertTrue(e.message!!.contains("en pausa"))
        assertFalse(usb.isOpen("p1"))
        assertTrue(falso.trabajos.isEmpty())
    }

    @Test fun `borrada de Windows entre abrir y escribir - lo dice y queda cerrada`() = runBlocking<Unit> {
        usb.open("p1", "usb:cola:POS-80")
        falso.colasHoy.removeAt(0)
        val e = assertFailsWith<PrinterException.ConnectionFailed> { usb.write("p1", byteArrayOf(1)) }
        assertTrue(e.message!!.contains("Windows ya no tiene la impresora «POS-80»"), e.message)
        assertFalse(usb.isOpen("p1"))
        assertTrue(falso.trabajos.isEmpty())
    }

    @Test fun `COM que falla al escribir queda cerrado`() = runBlocking<Unit> {
        usb.open("barra", "usb:com:COM3")
        falso.fallaAlEscribirEnPuerto = PrinterException.PrintFailed("se desconectó")
        assertFailsWith<PrinterException.PrintFailed> { usb.write("barra", byteArrayOf(1)) }
        assertFalse(usb.isOpen("barra"))
        assertTrue(falso.puertosAbiertos.isEmpty())
    }

    @Test fun `buscar con un Windows que truena da lista vacia`() {
        falso.fallaAlListar = UnsatisfiedLinkError("winspool.drv")
        assertEquals(emptyList(), usb.discoverPrinters())
        falso.fallaAlListar = IllegalStateException("registro ilegible")
        assertEquals(emptyList(), usb.discoverPrinters())
    }

    // --- fuera de Windows lo dice, también para una cola ---

    @Test fun `fuera de Windows abrir dice que solo funciona en Windows`() = runBlocking<Unit> {
        ImpresionDeEscritorio.sistema = SistemaFueraDeWindows
        for (direccion in listOf("usb:cola:POS-80", "usb:com:COM3")) {
            val e = assertFailsWith<PrinterException.ConnectionFailed>(direccion) { usb.open("p1", direccion) }
            assertTrue(e.message!!.contains("Las impresoras USB y COM sólo funcionan en Windows."), e.message)
            assertFalse(usb.isOpen("p1"))
        }
        assertEquals(emptyList(), usb.discoverPrinters())
    }

    // --- un candado por impresora (por id: dos impresoras guardadas distintas al MISMO COM no las cubre) ---

    @Test fun `dos aperturas a la vez de la misma impresora (mismo id) abren un solo canal`() {
        falso.esperaAlAbrirPuertoMs = 300
        val errores = Collections.synchronizedList(mutableListOf<Throwable>())
        val barrera = CyclicBarrier(2)
        val hilos = List(2) {
            thread {
                runCatching { barrera.await(); runBlocking { usb.open("barra", "usb:com:COM3") } }.onFailure { errores += it }
            }
        }
        hilos.forEach { it.join(10_000) }
        assertEquals(emptyList(), errores.toList())
        assertEquals(1, falso.aperturasDePuerto.get(), "un segundo CreateFile al mismo COM falla (lo tiene el primero)")
        assertTrue(usb.isOpen("barra"))
    }

    @Test fun `dos tickets a la vez al mismo COM salen uno tras otro, sin intercalarse`() {
        runBlocking { usb.open("barra", "usb:com:COM3") }
        falso.esperaPorByteMs = 3
        val errores = Collections.synchronizedList(mutableListOf<Throwable>())
        val barrera = CyclicBarrier(2)
        val hilos = listOf<Byte>(1, 2).map { valor ->
            thread {
                runCatching { barrera.await(); usb.write("barra", ByteArray(12) { valor }) }.onFailure { errores += it }
            }
        }
        hilos.forEach { it.join(10_000) }
        assertEquals(emptyList(), errores.toList())
        val escrito = falso.escritoEnPuerto.getValue("COM3").toList()
        val esperado = listOf(List<Byte>(12) { 1 } + List<Byte>(12) { 2 }, List<Byte>(12) { 2 } + List<Byte>(12) { 1 })
        assertTrue(escrito in esperado, "se intercalaron: $escrito")
    }

    /**
     * #5, ronda 2: «Desconectar» corre en el hilo de la pantalla (PrinterConfigSheet → PrinterService.disconnect → close).
     * Con el COM escribiendo, close regresa ENSEGUIDA (no espera en pantalla), la impresora deja de contar como abierta y
     * otro ticket ya no entra; el cierre se completa al terminar la escritura, sin cortar el ticket que iba.
     */
    @Test fun `cerrar mientras se escribe en el COM regresa enseguida y cierra al terminar la escritura`() {
        runBlocking { usb.open("barra", "usb:com:COM3") }
        val pausa = Pausa().also { falso.pausaDeEscritura["COM3"] = it }
        val errores = Collections.synchronizedList(mutableListOf<Throwable>())
        val ticket = ByteArray(16) { (it + 1).toByte() }
        val escritor = thread { runCatching { usb.write("barra", ticket) }.onFailure { errores += it } }
        assertTrue(pausa.entro.await(5, TimeUnit.SECONDS), "no llegó a escribir")

        val inicio = System.nanoTime()
        usb.close("barra")
        val ms = (System.nanoTime() - inicio) / 1_000_000
        assertTrue(ms < 100, "close tardó $ms ms con el COM escribiendo (la pantalla se congela)")
        assertFalse(usb.isOpen("barra"), "con el cierre pendiente ya no cuenta como abierta")
        assertTrue("COM3" in falso.puertosAbiertos, "se cerró el COM a media escritura")
        assertFailsWith<PrinterException.NotConnected> { usb.write("barra", byteArrayOf(9)) }   // otro ticket no entra

        pausa.suelta.countDown()
        escritor.join(5_000)
        esperarHasta { falso.puertosAbiertos.isEmpty() }
        assertEquals(emptyList(), errores.toList())
        assertEquals(ticket.toList(), falso.escritoEnPuerto["COM3"]?.toList(), "el ticket que iba salió completo, y el 9 no")
        assertEquals(listOf("abrir COM3", "cerrar COM3"), falso.eventos.toList())
        assertFalse(usb.isOpen("barra"))
    }

    /**
     * #5, ronda 2: la reconexión de PrinterService hace disconnect() y luego connect() → open en IO. Con un cierre
     * pendiente, open ESPERA a que termine y abre un canal NUEVO: no reusa el viejo ni se adelanta al cierre (Windows abre
     * el COM exclusivo). Aquí el cierre pendiente corre cuando la prueba lo suelta, para que el orden no dependa de la suerte.
     */
    @Test fun `cerrar y reabrir con el COM escribiendo - la reapertura espera al cierre y abre un canal nuevo`() {
        runBlocking { usb.open("barra", "usb:com:COM3") }
        val cierres = LinkedBlockingQueue<Runnable>()
        usb.lanzarCierre = { cierres += it }
        val pausa = Pausa().also { falso.pausaDeEscritura["COM3"] = it }
        val errores = Collections.synchronizedList(mutableListOf<Throwable>())
        val ticket = ByteArray(16) { (it + 1).toByte() }
        val escritor = thread { runCatching { usb.write("barra", ticket) }.onFailure { errores += it } }
        assertTrue(pausa.entro.await(5, TimeUnit.SECONDS), "no llegó a escribir")

        usb.close("barra")
        val abridor = thread { runCatching { runBlocking { usb.open("barra", "usb:com:COM3") } }.onFailure { errores += it } }
        pausa.suelta.countDown()
        escritor.join(5_000)
        Thread.sleep(300)                                                        // de sobra para adelantarse, si no esperara
        assertTrue(abridor.isAlive, "el open no esperó al cierre pendiente: ${falso.eventos.toList()} ${errores.toList()}")
        assertEquals(listOf("abrir COM3"), falso.eventos.toList())

        assertNotNull(cierres.poll(5, TimeUnit.SECONDS), "no quedó un cierre pendiente").run()
        abridor.join(5_000)
        assertEquals(emptyList(), errores.toList())
        assertEquals(listOf("abrir COM3", "cerrar COM3", "abrir COM3"), falso.eventos.toList())
        assertTrue(usb.isOpen("barra"))
        assertEquals(2, falso.aperturasDePuerto.get(), "abrió un canal nuevo")
        assertEquals(ticket.toList(), falso.escritoEnPuerto["COM3"]?.toList())
    }

    /** #5: un cierre que llega mientras se abre no deja registrado (y tomado) un canal que debía quedar cerrado. */
    @Test fun `cerrar mientras se abre no deja un canal abierto registrado`() {
        val pausa = Pausa().also { falso.pausaDeApertura = it }
        val errores = Collections.synchronizedList(mutableListOf<Throwable>())
        val abridor = thread { runCatching { runBlocking { usb.open("barra", "usb:com:COM3") } }.onFailure { errores += it } }
        assertTrue(pausa.entro.await(5, TimeUnit.SECONDS), "no llegó a abrir")
        val cerrador = thread { usb.close("barra") }
        esperarQueSeDetenga(cerrador)
        pausa.suelta.countDown()
        abridor.join(5_000); cerrador.join(5_000)
        esperarHasta { falso.puertosAbiertos.isEmpty() }                              // el cierre pendiente corre en otro hilo
        assertEquals(emptyList(), errores.toList())
        assertFalse(usb.isOpen("barra"), "quedó registrada abierta una impresora que se cerró")
        assertTrue(falso.puertosAbiertos.isEmpty(), "quedó el COM tomado")
    }
}

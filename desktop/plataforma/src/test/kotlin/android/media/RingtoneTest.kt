package android.media

import java.awt.HeadlessException
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.LineUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El sonido de la pantalla de cocina (KDSViewModel.playNotificationSound → RingtoneManager.getRingtone(...).play()).
 * En el paquete de Android a propósito: usa el constructor de paquete (sistema, archivo, reproductor, bip e hilo inyectables).
 */
class RingtoneTest {
    private val enEsteHilo = Executor { it.run() }

    private class ReproductorFalso(private val falla: Throwable? = null) : Ringtone.Reproductor {
        val tocados = mutableListOf<File>()
        val detenidos = AtomicInteger()
        override fun tocar(wav: File) {
            tocados += wav
            falla?.let { throw it }
        }
        override fun detener() { detenidos.incrementAndGet() }
    }

    /** Una carpeta que hace de %WINDIR%, con Media\Windows Notify System Generic.wav (silencio). */
    private fun windirConSonido(): File {
        val windir = Files.createTempDirectory("WINDIR prueba ñ").toFile()
        File(windir, "Media").mkdirs()
        File(windir, "Media/Windows Notify System Generic.wav").writeBytes(wavDeSilencio())
        return windir
    }

    @Test fun `en Windows con el archivo pide tocar ESE archivo y no hace bip`() {
        val windir = windirConSonido()
        val wav = Ringtone.sonidoDeWindows(windir.path)
        assertEquals(File(windir, "Media/Windows Notify System Generic.wav").canonicalFile, wav?.canonicalFile)
        val reproductor = ReproductorFalso()
        val bips = AtomicInteger()

        Ringtone(true, wav, reproductor, { bips.incrementAndGet() }, enEsteHilo).play()

        assertEquals(listOf(wav), reproductor.tocados)
        assertEquals(0, bips.get())
    }

    @Test fun `en Windows sin el archivo suena el bip`() {
        val sinArchivo = File(Files.createTempDirectory("WINDIR vacío").toFile(), "Media/Windows Notify System Generic.wav")
        val reproductor = ReproductorFalso()
        val bips = AtomicInteger()

        Ringtone(true, sinArchivo, reproductor, { bips.incrementAndGet() }, enEsteHilo).play()

        assertEquals(1, bips.get())
        assertTrue(reproductor.tocados.isEmpty(), "no se pide tocar un archivo que no existe")
    }

    @Test fun `sin WINDIR no hay ruta y suena el bip`() {
        assertNull(Ringtone.sonidoDeWindows(null))
        assertNull(Ringtone.sonidoDeWindows("  "))
        val bips = AtomicInteger()

        Ringtone(true, null, ReproductorFalso(), { bips.incrementAndGet() }, enEsteHilo).play()

        assertEquals(1, bips.get())
    }

    @Test fun `un reproductor que lanza cae al bip y play no lanza`() {
        val wav = Ringtone.sonidoDeWindows(windirConSonido().path)
        for (falla in listOf(LineUnavailableException("sin tarjeta de sonido"), IllegalArgumentException("sin mezclador"), UnsatisfiedLinkError("sin jsound.dll"))) {
            val bips = AtomicInteger()
            Ringtone(true, wav, ReproductorFalso(falla), { bips.incrementAndGet() }, enEsteHilo).play()
            assertEquals(1, bips.get(), "con ${falla.javaClass.simpleName} debe sonar el bip")
        }
    }

    @Test fun `fuera de Windows suena el bip aunque el archivo exista`() {
        val wav = Ringtone.sonidoDeWindows(windirConSonido().path)
        val reproductor = ReproductorFalso()
        val bips = AtomicInteger()

        Ringtone(false, wav, reproductor, { bips.incrementAndGet() }, enEsteHilo).play()

        assertEquals(1, bips.get())
        assertTrue(reproductor.tocados.isEmpty())
    }

    @Test fun `un bip que tambien lanza no tumba a quien llama`() {
        val wav = Ringtone.sonidoDeWindows(windirConSonido().path)
        val bipRoto = Runnable { throw HeadlessException() }
        Ringtone(true, wav, ReproductorFalso(LineUnavailableException("x")), bipRoto, enEsteHilo).play()
        Ringtone(false, wav, ReproductorFalso(), bipRoto, enEsteHilo).play()
        Ringtone(true, null, ReproductorFalso(), bipRoto, enEsteHilo).play()
        // Un hilo que no se puede crear tampoco: cae al bip (roto) y no lanza.
        Ringtone(true, wav, ReproductorFalso(), bipRoto, Executor { throw IllegalStateException("sin hilos") }).play()
    }

    @Test fun `stop detiene el sonido y nunca lanza`() {
        val reproductor = ReproductorFalso()
        Ringtone(true, null, reproductor, {}, enEsteHilo).stop()
        assertEquals(1, reproductor.detenidos.get())

        val roto = object : Ringtone.Reproductor {
            override fun tocar(wav: File) {}
            override fun detener() { throw IllegalStateException("clip ya cerrado") }
        }
        Ringtone(true, null, roto, {}, enEsteHilo).stop()
    }

    @Test fun `play no bloquea al que llama - toca en un hilo daemon`() {
        val wav = Ringtone.sonidoDeWindows(windirConSonido().path)
        val suelta = CountDownLatch(1)
        val empezo = CountDownLatch(1)
        val hilo = AtomicReference<Thread>()
        val lento = object : Ringtone.Reproductor {
            override fun tocar(wav: File) {
                hilo.set(Thread.currentThread())
                empezo.countDown()
                suelta.await(10, TimeUnit.SECONDS)
            }
            override fun detener() {}
        }
        val antes = System.nanoTime()
        Ringtone(true, wav, lento, {}, Ringtone::enHiloAparte).play()
        val ms = (System.nanoTime() - antes) / 1_000_000

        assertTrue(empezo.await(5, TimeUnit.SECONDS), "el reproductor nunca arrancó")
        assertTrue(ms < 1_000, "play() esperó $ms ms al reproductor")
        assertTrue(hilo.get() !== Thread.currentThread())
        assertTrue(hilo.get().isDaemon, "un hilo que no es daemon impediría cerrar la app")
        suelta.countDown()
    }

    @Test fun `el reproductor real con un archivo que no es audio cae al bip`() {
        val basura = File.createTempFile("no-es-audio", ".wav").apply { writeText("esto no es un WAV") }
        val bips = AtomicInteger()

        Ringtone(true, basura, Ringtone.ReproductorDeClip(), { bips.incrementAndGet() }, enEsteHilo).play()

        assertEquals(1, bips.get())
    }

    @Test fun `el reproductor real con un WAV de silencio no lanza - suena o cae al bip`() {
        val wav = Ringtone.sonidoDeWindows(windirConSonido().path)
        val reproductor = Ringtone.ReproductorDeClip()
        val ringtone = Ringtone(true, wav, reproductor, {}, enEsteHilo)
        ringtone.play()   // en una máquina sin salida de audio (el CI) Java Sound lanza: cae al bip, que aquí no hace nada
        ringtone.stop()
        ringtone.stop()   // dos veces: el clip ya cerrado no truena
    }

    /**
     * Codex (auditoría del bloque): un WAV con cabecera válida y CERO muestras abre un Clip que nunca manda STOP; sin
     * esta guarda cada pedido dejaba otro clip y su hilo abiertos, sin sonido ni bip. (En una máquina sin salida de audio,
     * el CI, Java Sound ya lanza al pedir el clip y cae al bip igual: la prueba muerde donde hay audio, como esta Mac.)
     */
    @Test fun `el reproductor real con un WAV sin muestras cierra y cae al bip`() {
        val vacio = File.createTempFile("sin-muestras", ".wav").apply { writeBytes(wavDeSilencio(muestras = 0)) }
        val bips = AtomicInteger()

        Ringtone(true, vacio, Ringtone.ReproductorDeClip(), { bips.incrementAndGet() }, enEsteHilo).play()

        assertEquals(1, bips.get(), "un WAV sin muestras no sonó ni cayó al bip")
    }

    /** Codex: `Clip.stop()`/`close()` pueden esperar al hilo de audio (join de hasta 2 s): stop() no puede trabar a quien llama. */
    @Test fun `stop no bloquea al que llama - detiene en un hilo daemon`() {
        val suelta = CountDownLatch(1)
        val detuvo = CountDownLatch(1)
        val hilo = AtomicReference<Thread>()
        val lento = object : Ringtone.Reproductor {
            override fun tocar(wav: File) {}
            override fun detener() {
                hilo.set(Thread.currentThread())
                suelta.await(10, TimeUnit.SECONDS)
                detuvo.countDown()
            }
        }
        val antes = System.nanoTime()
        Ringtone(true, null, lento, {}, Ringtone::enHiloAparte).stop()
        val ms = (System.nanoTime() - antes) / 1_000_000

        assertTrue(ms < 1_000, "stop() esperó $ms ms al reproductor")
        suelta.countDown()
        assertTrue(detuvo.await(5, TimeUnit.SECONDS), "el reproductor nunca se detuvo")
        assertTrue(hilo.get() !== Thread.currentThread())
        assertTrue(hilo.get().isDaemon, "un hilo que no es daemon impediría cerrar la app")
    }

    /** 0.05 s de silencio, PCM 16 bits mono a 8 kHz: un WAV de verdad que no hace ruido en la máquina que corre la prueba. */
    private fun wavDeSilencio(muestras: Int = 400): ByteArray {
        val datos = muestras * 2
        val cabecera = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + datos); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(datos)
        }.array()
        return ByteArrayOutputStream().apply { write(cabecera); write(ByteArray(datos)) }.toByteArray()
    }
}

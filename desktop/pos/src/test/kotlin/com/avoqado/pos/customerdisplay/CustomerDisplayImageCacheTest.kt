package com.avoqado.pos.customerdisplay

import android.graphics.BitmapFactory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El caché del logo contra un servidor HTTP de verdad (el del JDK), en escritorio: aquí `BitmapFactory` sí decodifica
 * (en las pruebas JVM de Android es un stub). `httpsOnly = false` y los orígenes son la URL del servidor de prueba.
 */
class CustomerDisplayImageCacheTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var dir: File
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val ifNoneMatch = ConcurrentHashMap<String, String>()
    private val handlers = ConcurrentHashMap<String, (HttpExchange) -> Unit>()
    private val liberar = CountDownLatch(1)
    private var ahora = 1_000_000_000_000L

    @BeforeTest fun armar() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
            exchange.requestHeaders.getFirst("If-None-Match")?.let { ifNoneMatch[path] = it }
            try {
                (handlers[path] ?: { it.sendResponseHeaders(404, -1) })(exchange)
            } catch (_: Exception) {
                // el cliente cortó la conexión (tope de tamaño o de tiempo): es lo que se prueba
            } finally {
                exchange.close()
            }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        dir = Files.createTempDirectory("logo-cache").toFile()
    }

    @AfterTest fun apagar() {
        liberar.countDown()
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun cache(client: OkHttpClient = cliente()) = CustomerDisplayImageCache(dir, client, httpsOnly = false) { ahora }

    private fun cliente(callTimeoutMs: Long = 10_000) = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()

    private fun origins() = listOf(base)

    private fun hitsDe(path: String) = hits[path]?.get() ?: 0

    private fun sirve(path: String, bytes: ByteArray, etag: String? = null) {
        handlers[path] = { exchange ->
            etag?.let { exchange.responseHeaders.add("ETag", it) }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun responde(path: String, code: Int) {
        handlers[path] = { it.sendResponseHeaders(code, -1) }
    }

    private fun imagen(ancho: Int, alto: Int, formato: EncodedImageFormat = EncodedImageFormat.PNG): ByteArray {
        val surface = Surface.makeRasterN32Premul(ancho, alto)
        surface.canvas.clear(0xFF2E7D32.toInt())
        return surface.makeImageSnapshot().encodeToData(formato, 90)!!.bytes
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `asset PNG con hash correcto queda listo y se lee sin red`() = runBlocking<Unit> {
        val png = imagen(64, 64)
        sirve("/logo.png", png)
        val ref = LogoRef.Asset("$base/logo.png", sha256(png))
        val cache = cache()

        assertEquals(ImageStatus.READY, cache.ensure(ref, origins()))
        assertEquals(ImageStatus.READY, cache.ensure(ref, origins()))

        assertEquals(1, hitsDe("/logo.png"))
        val file = assertNotNull(cache.cachedFile(ref))
        assertContentEquals(png, file.readBytes())
        assertEquals("${sha256(png)}.img", file.name)
        assertEquals("assets", file.parentFile.name)
    }

    @Test fun `asset WebP queda listo y decodifica`() = runBlocking<Unit> {
        val webp = imagen(64, 64, EncodedImageFormat.WEBP)
        sirve("/logo.webp", webp)
        val ref = LogoRef.Asset("$base/logo.webp", sha256(webp))
        val cache = cache()

        assertEquals(ImageStatus.READY, cache.ensure(ref, origins()))

        val bitmap = assertNotNull(BitmapFactory.decodeFile(assertNotNull(cache.cachedFile(ref)).path))
        assertEquals(64, bitmap.width)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assertNull(BitmapFactory.decodeFile(cache.cachedFile(ref)!!.path, bounds))
        assertEquals(64, bounds.outWidth)
        assertEquals(64, bounds.outHeight)
    }

    @Test fun `asset con hash adulterado falla y no se reintenta`() = runBlocking<Unit> {
        val png = imagen(64, 64)
        sirve("/logo.png", png)
        val ref = LogoRef.Asset("$base/logo.png", sha256(imagen(32, 32)))
        val cache = cache()

        assertEquals(ImageStatus.FAILED, cache.ensure(ref, origins()))
        ahora += TimeUnit.DAYS.toMillis(2)
        assertEquals(ImageStatus.FAILED, cache.ensure(ref, origins()))

        assertEquals(1, hitsDe("/logo.png"))
        assertNull(cache.cachedFile(ref))
    }

    @Test fun `legado revalida con 304 sin bajar de nuevo`() = runBlocking<Unit> {
        val png = imagen(64, 64)
        sirve("/venue.png", png, etag = "\"v1\"")
        val cache = cache()
        assertEquals(ImageStatus.READY, cache.ensure(LogoRef.Venue("$base/venue.png", "1"), origins()))

        responde("/venue.png", 304)
        val nueva = LogoRef.Venue("$base/venue.png", "2")
        assertTrue(cache.revalidationDue(nueva))
        assertEquals(ImageStatus.READY, cache.ensure(nueva, origins()))

        assertEquals("\"v1\"", ifNoneMatch["/venue.png"])
        assertEquals(2, hitsDe("/venue.png"))
        assertFalse(cache.revalidationDue(nueva))
        assertContentEquals(png, cache.cachedFile(nueva)!!.readBytes())
        assertEquals("venue", cache.cachedFile(nueva)!!.parentFile.name)
    }

    @Test fun `legado con 200 reemplaza`() = runBlocking<Unit> {
        val vieja = imagen(64, 64)
        val nueva = imagen(48, 48)
        sirve("/venue.png", vieja, etag = "\"v1\"")
        val cache = cache()
        assertEquals(ImageStatus.READY, cache.ensure(LogoRef.Venue("$base/venue.png", "1"), origins()))

        sirve("/venue.png", nueva, etag = "\"v2\"")
        val ref = LogoRef.Venue("$base/venue.png", "2")
        assertEquals(ImageStatus.READY, cache.ensure(ref, origins()))

        assertContentEquals(nueva, cache.cachedFile(ref)!!.readBytes())
        assertFalse(cache.revalidationDue(ref))
    }

    @Test fun `legado con fallo conserva la copia`() = runBlocking<Unit> {
        val png = imagen(64, 64)
        sirve("/venue.png", png)
        val cache = cache()
        assertEquals(ImageStatus.READY, cache.ensure(LogoRef.Venue("$base/venue.png", "1"), origins()))

        responde("/venue.png", 500)
        val ref = LogoRef.Venue("$base/venue.png", "2")
        assertEquals(ImageStatus.READY, cache.ensure(ref, origins()))

        assertEquals(2, hitsDe("/venue.png"))
        assertContentEquals(png, cache.cachedFile(ref)!!.readBytes())
    }

    @Test fun `host ajeno no se pide`() = runBlocking<Unit> {
        val png = imagen(64, 64)
        sirve("/logo.png", png)
        val ajena = "http://localhost:${server.address.port}/logo.png"   // mismo servidor, otro host: fuera de la lista
        val cache = cache()

        assertEquals(ImageStatus.FAILED, cache.ensure(LogoRef.Asset(ajena, sha256(png)), origins()))
        assertEquals(ImageStatus.FAILED, cache.ensure(LogoRef.Asset("$base.evil.test/logo.png", sha256(png)), origins()))
        assertEquals(ImageStatus.FAILED, cache.ensure(LogoRef.Venue(ajena, "1"), origins()))

        assertEquals(0, hitsDe("/logo.png"))
    }

    @Test fun `redireccion no se sigue`() = runBlocking<Unit> {
        val png = imagen(64, 64)
        sirve("/destino.png", png)
        handlers["/logo.png"] = { exchange ->
            exchange.responseHeaders.add("Location", "$base/destino.png")
            exchange.sendResponseHeaders(302, -1)
        }
        val ref = LogoRef.Asset("$base/logo.png", sha256(png))
        val cache = cache()

        assertEquals(ImageStatus.FAILED, cache.ensure(ref, origins()))
        assertEquals(ImageStatus.FAILED, cache.ensure(ref, origins()))

        assertEquals(1, hitsDe("/logo.png"))
        assertEquals(0, hitsDe("/destino.png"))
    }

    @Test fun `mas de 5 MB se corta`() = runBlocking<Unit> {
        val tope = 5 * 1024 * 1024
        handlers["/declarado.png"] = { it.sendResponseHeaders(200, tope + 1L); it.responseBody.use { out -> out.write(ByteArray(tope + 1)) } }
        handlers["/sin-largo.png"] = { exchange ->
            exchange.sendResponseHeaders(200, 0)   // chunked: el tope se aplica leyendo
            exchange.responseBody.use { out -> repeat(6) { out.write(ByteArray(1024 * 1024)) } }
        }
        val cache = cache()
        val declarado = LogoRef.Asset("$base/declarado.png", "a".repeat(64))
        val sinLargo = LogoRef.Asset("$base/sin-largo.png", "b".repeat(64))

        assertEquals(ImageStatus.FAILED, cache.ensure(declarado, origins()))
        assertEquals(ImageStatus.FAILED, cache.ensure(sinLargo, origins()))
        assertEquals(ImageStatus.FAILED, cache.ensure(sinLargo, origins()))

        assertEquals(1, hitsDe("/sin-largo.png"))
        assertNull(cache.cachedFile(sinLargo))
        assertTrue(File(dir, "assets").listFiles().orEmpty().none { it.name.endsWith(".tmp") || it.name.endsWith(".img") })
    }

    @Test fun `imagen de 5000 px de lado se rechaza`() = runBlocking<Unit> {
        val ancha = imagen(5000, 10)
        sirve("/ancha.png", ancha)
        val ref = LogoRef.Asset("$base/ancha.png", sha256(ancha))
        val cache = cache()

        assertEquals(ImageStatus.FAILED, cache.ensure(ref, origins()))
        assertNull(cache.cachedFile(ref))
    }

    @Test fun `una respuesta que no termina se corta por tiempo`() = runBlocking<Unit> {
        handlers["/lento.png"] = { exchange ->
            exchange.sendResponseHeaders(200, 1000)
            exchange.responseBody.write(ByteArray(10))
            exchange.responseBody.flush()
            liberar.await(30, TimeUnit.SECONDS)
        }
        val ref = LogoRef.Asset("$base/lento.png", "c".repeat(64))
        val cache = cache(cliente(callTimeoutMs = 500))

        val inicio = System.nanoTime()
        assertEquals(ImageStatus.PENDING, cache.ensure(ref, origins()))
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - inicio) < 10)
        assertNull(cache.cachedFile(ref))
    }

    @Test fun `revalidationDue con version nueva o a las 24 h`() = runBlocking<Unit> {
        val png = imagen(64, 64)
        sirve("/venue.png", png)
        val cache = cache()
        val ref = LogoRef.Venue("$base/venue.png", "1")
        assertTrue(cache.revalidationDue(ref))   // nunca bajado
        assertEquals(ImageStatus.READY, cache.ensure(ref, origins()))

        assertFalse(cache.revalidationDue(ref))
        assertTrue(cache.revalidationDue(LogoRef.Venue("$base/venue.png", "2")))
        ahora += TimeUnit.HOURS.toMillis(24) - 1
        assertFalse(cache.revalidationDue(ref))
        ahora += 1
        assertTrue(cache.revalidationDue(ref))
        assertFalse(cache.revalidationDue(LogoRef.Asset("$base/venue.png", sha256(png))))
    }

    @Test fun `cinco fallos cuentan como fallida y al volver la red queda lista`() = runBlocking<Unit> {
        val png = imagen(64, 64)
        responde("/logo.png", 503)
        val ref = LogoRef.Asset("$base/logo.png", sha256(png))
        val cache = cache()

        repeat(4) { intento ->
            assertEquals(ImageStatus.PENDING, cache.ensure(ref, origins()), "intento ${intento + 1}")
            // Antes de la espera agendada no se pide nada.
            assertEquals(ImageStatus.PENDING, cache.ensure(ref, origins()))
            assertEquals(intento + 1, hitsDe("/logo.png"))
            ahora += TimeUnit.MINUTES.toMillis(61)
        }
        assertEquals(ImageStatus.FAILED, cache.ensure(ref, origins()))
        assertEquals(5, hitsDe("/logo.png"))
        assertEquals(ImageStatus.FAILED, cache.ensure(ref, origins()))
        assertEquals(5, hitsDe("/logo.png"))

        sirve("/logo.png", png)   // vuelve la red
        ahora += TimeUnit.MINUTES.toMillis(61)
        assertEquals(ImageStatus.READY, cache.ensure(ref, origins()))
        assertNotNull(cache.cachedFile(ref))
    }

    @Test fun `legado con copia - permanente en la version 2 no bloquea la version 3`() = runBlocking<Unit> {
        val vieja = imagen(64, 64)
        val nueva = imagen(48, 48)
        sirve("/venue.png", vieja, etag = "\"v1\"")
        val cache = cache()
        assertEquals(ImageStatus.READY, cache.ensure(LogoRef.Venue("$base/venue.png", "1"), origins()))

        handlers["/venue.png"] = { exchange ->
            exchange.responseHeaders.add("Location", "$base/otra.png")
            exchange.sendResponseHeaders(302, -1)
        }
        val v2 = LogoRef.Venue("$base/venue.png", "2")
        assertEquals(ImageStatus.READY, cache.ensure(v2, origins()))   // se sigue pintando la copia
        assertEquals(2, hitsDe("/venue.png"))
        // Con el permanente vigente para ESA versión no se relanza en cada poll.
        assertFalse(cache.revalidationDue(v2))
        assertEquals(ImageStatus.READY, cache.ensure(v2, origins()))
        assertEquals(2, hitsDe("/venue.png"))

        sirve("/venue.png", nueva, etag = "\"v3\"")
        val v3 = LogoRef.Venue("$base/venue.png", "3")
        assertTrue(cache.revalidationDue(v3))
        assertEquals(ImageStatus.READY, cache.ensure(v3, origins()))
        assertEquals(3, hitsDe("/venue.png"))
        assertContentEquals(nueva, cache.cachedFile(v3)!!.readBytes())
        assertFalse(cache.revalidationDue(v3))
    }

    @Test fun `dos ensure simultaneos del mismo asset piden una sola vez`() = runBlocking<Unit> {
        val png = imagen(64, 64)
        handlers["/logo.png"] = { exchange ->
            Thread.sleep(300)   // que la segunda llamada llegue mientras la primera sigue bajando
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }
        val ref = LogoRef.Asset("$base/logo.png", sha256(png))
        val cache = cache()

        val estados = List(2) { async { cache.ensure(ref, origins()) } }.awaitAll()

        assertEquals(listOf(ImageStatus.READY, ImageStatus.READY), estados)
        assertEquals(1, hitsDe("/logo.png"))
    }

    @Test fun `prune no borra un temporal reciente`() = runBlocking<Unit> {
        val cache = cache()
        val viejo = disperso(File(dir, "assets/img-1.tmp"), modificado = ahora - TimeUnit.HOURS.toMillis(2))
        val reciente1 = disperso(File(dir, "assets/img-2.tmp"), modificado = ahora - TimeUnit.MINUTES.toMillis(1))
        val reciente2 = disperso(File(dir, "venue/img-3.tmp"), modificado = ahora - TimeUnit.MINUTES.toMillis(59))

        cache.prune(emptyList())

        assertFalse(viejo.exists())
        assertTrue(reciente1.exists())   // una descarga en curso: borrarla la volvería «no es imagen» permanente
        assertTrue(reciente2.exists())
    }

    @Test fun `prune respeta lo referenciado`() = runBlocking<Unit> {
        val png = imagen(64, 64)
        sirve("/logo.png", png)
        val ref = LogoRef.Asset("$base/logo.png", sha256(png))
        val cache = cache()
        assertEquals(ImageStatus.READY, cache.ensure(ref, origins()))
        cache.cachedFile(ref)!!.setLastModified(1_000)   // el más viejo de todos, pero referenciado

        val vieja = disperso(File(dir, "assets/${"d".repeat(64)}.img"), modificado = 2_000)
        val nueva = disperso(File(dir, "venue/${"e".repeat(64)}.img"), modificado = 3_000)

        cache.prune(listOf(ref))

        assertNotNull(cache.cachedFile(ref))
        assertFalse(vieja.exists())
        assertTrue(nueva.exists())   // con borrar la vieja ya quedó bajo 100 MB
    }

    /** 60 MB que no ocupan disco: `length()` (lo que cuenta prune) es el largo lógico. */
    private fun disperso(file: File, modificado: Long): File {
        file.parentFile.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(60L * 1024 * 1024) }
        file.setLastModified(modificado)
        return file
    }
}

package com.avoqado.escritorio

import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CacheDeImagenesTest {
    @Test fun `baja la imagen una vez y la segunda sale de memoria`() {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()
        val pedidos = AtomicInteger()
        val servidor = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/a.png") { pedidos.incrementAndGet(); it.sendResponseHeaders(200, png.size.toLong()); it.responseBody.use { b -> b.write(png) } }
            start()
        }
        val url = "http://127.0.0.1:${servidor.address.port}/a.png"
        try {
            assertNull(CacheDeImagenes.enMemoria(url))
            assertEquals(3, assertNotNull(CacheDeImagenes.cargar(url)).width)
            assertNotNull(CacheDeImagenes.enMemoria(url))
            CacheDeImagenes.cargar(url)
            assertEquals(1, pedidos.get())
        } finally { servidor.stop(0) }
    }

    @Test fun `una url rota devuelve null, no truena`() {
        assertNull(CacheDeImagenes.cargar("http://127.0.0.1:9/no-existe.png"))
    }
}

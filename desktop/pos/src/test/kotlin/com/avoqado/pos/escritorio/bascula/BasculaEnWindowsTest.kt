package com.avoqado.pos.escritorio.bascula

import com.avoqado.pos.areatickets.data.ScaleProfile
import com.avoqado.pos.scale.ScaleConnectionState
import com.avoqado.pos.scale.ScaleUsageContext
import com.avoqado.pos.scale.UsbSerialScaleManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** La báscula de escritorio con un puerto COM falso: el mismo perfil del servidor y la misma lógica de tramas de Android. */
class BasculaEnWindowsTest {
    private val manager = UsbSerialScaleManager()
    private val puerto = PuertoSerie("COM3", "\\Device\\Serial2", 0x1A86, 0x7523)
    private val canal = CanalFalso()
    private var abiertoCon: List<Any?> = emptyList()

    init {
        manager.puertos = { listOf(puerto) }
        manager.abrir = { p, baudios, datos, paridad, parada -> abiertoCon = listOf(p.nombre, baudios, datos, paridad, parada); canal }
    }

    @AfterTest fun soltar() = manager.disconnect()

    private fun perfil(activo: Boolean = true, tipo: String? = "JUSTA_LP7516_ASCII") = ScaleProfile(
        id = "justa-1", name = "Justa mostrador", location = "Mostrador", model = "LP7516",
        allowedContexts = listOf("AREA_TICKET_LINE"), transport = "ANDROID_USB_SERIAL",
        vendorId = 0x1A86, productId = 0x7523, frameParser = tipo?.let { buildJsonObject { put("type", JsonPrimitive(it)) } },
        active = activo,
    )

    private fun esperar(condicion: (ScaleConnectionState) -> Boolean): ScaleConnectionState {
        val limite = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < limite) {
            manager.state.value.let { if (condicion(it)) return it }
            Thread.sleep(20)
        }
        return manager.state.value
    }

    @Test fun `conecta por el COM del VID PID y una trama estable de Justa da el peso`() = runBlocking {
        manager.connect(perfil(), ScaleUsageContext.AREA_TICKET_LINE)
        assertEquals(listOf("COM3", 9_600, 8, null, null), abiertoCon)
        canal.llega("ST,GS,+  36.320kg\r\n")
        val estado = esperar { it is ScaleConnectionState.Stable }
        assertIs<ScaleConnectionState.Stable>(estado)
        assertEquals("36.320", estado.reading.netKg)
        assertTrue(esperar { canal.escrito.isNotEmpty() }.let { canal.escrito.first().contentEquals(byteArrayOf('R'.code.toByte())) }, "consulta con «R» como Android")
    }

    @Test fun `una trama de sobrecarga se dice como en Android`() = runBlocking {
        manager.connect(perfil(), ScaleUsageContext.AREA_TICKET_LINE)
        canal.llega("OL,GS,+99.999kg\r\n")
        val estado = esperar { it is ScaleConnectionState.Problem }
        assertEquals(ScaleConnectionState.Problem("Justa mostrador", "La báscula reporta sobrecarga o peso fuera de rango."), estado)
    }

    @Test fun `si se cae el puerto lo dice y no vuelve a leer`() = runBlocking {
        manager.connect(perfil(), ScaleUsageContext.AREA_TICKET_LINE)
        canal.caido = true
        val estado = esperar { it is ScaleConnectionState.Problem }
        assertIs<ScaleConnectionState.Problem>(estado)
        assertTrue(estado.message.startsWith("Se perdió la conexión con la báscula"))
        assertTrue(canal.cerrado)
    }

    @Test fun `perfil apagado, sin protocolo o puerto que no abre - Problem con el motivo, nunca truena`() = runBlocking {
        manager.connect(perfil(activo = false), ScaleUsageContext.AREA_TICKET_LINE)
        assertEquals("Este perfil no está habilitado para productos del vale.", (manager.state.value as ScaleConnectionState.Problem).message)
        manager.connect(perfil(tipo = null), ScaleUsageContext.AREA_TICKET_LINE)
        assertEquals("Falta configurar el protocolo certificado de esta báscula.", (manager.state.value as ScaleConnectionState.Problem).message)
        manager.abrir = { p, _, _, _, _ -> throw java.io.IOException(mensajeDeApertura(p.nombre, 5)) }
        manager.connect(perfil(), ScaleUsageContext.AREA_TICKET_LINE)
        assertTrue((manager.state.value as ScaleConnectionState.Problem).message.startsWith("Otro programa tiene abierto COM3"))
    }

    @Test fun `desconectar cierra el puerto y deja de avisar`() = runBlocking {
        manager.connect(perfil(), ScaleUsageContext.AREA_TICKET_LINE)
        manager.disconnect()
        assertTrue(canal.cerrado)
        canal.llega("ST,GS,+  36.320kg\r\n")
        Thread.sleep(150)
        assertEquals(ScaleConnectionState.NotConfigured, manager.state.value)
    }

    @Test fun `si se desconecta mientras el puerto tarda en abrir, no revive ni deja el COM ocupado`() {
        val abriendo = java.util.concurrent.CountDownLatch(1)
        val soltar = java.util.concurrent.CountDownLatch(1)
        manager.abrir = { _, _, _, _, _ -> abriendo.countDown(); soltar.await(); canal }
        val hilo = Thread { runBlocking { manager.connect(perfil(), ScaleUsageContext.AREA_TICKET_LINE) } }.apply { start() }
        assertTrue(abriendo.await(3, java.util.concurrent.TimeUnit.SECONDS))
        manager.disconnect()   // el cajero cerró el panel
        soltar.countDown()
        hilo.join(3_000)
        assertTrue(canal.cerrado, "la apertura tardía se cierra")
        assertEquals(ScaleConnectionState.NotConfigured, manager.state.value)
    }

    @Test fun `un desconectar mientras se cierra el puerto viejo no deja revivir la reconexion`() {
        val cerrando = java.util.concurrent.CountDownLatch(1)
        val soltar = java.util.concurrent.CountDownLatch(1)
        val viejo = object : CanalSerie {
            @Volatile var cerrado = false
            override fun leer(destino: ByteArray): Int { Thread.sleep(10); return if (cerrado) -1 else 0 }
            override fun escribir(bytes: ByteArray) = !cerrado
            override fun cerrar() { cerrando.countDown(); soltar.await(); cerrado = true }   // cerrar tarda (lectura en curso)
        }
        manager.abrir = { _, _, _, _, _ -> viejo }
        runBlocking { manager.connect(perfil(), ScaleUsageContext.AREA_TICKET_LINE) }
        manager.abrir = { _, _, _, _, _ -> canal }
        val reconexion = Thread { runBlocking { manager.connect(perfil(), ScaleUsageContext.AREA_TICKET_LINE) } }.apply { start() }
        assertTrue(cerrando.await(3, java.util.concurrent.TimeUnit.SECONDS))
        manager.disconnect()   // el cajero cierra mientras el puerto viejo todavía se está cerrando
        soltar.countDown()
        reconexion.join(3_000)
        Thread.sleep(100)
        assertEquals(ScaleConnectionState.NotConfigured, manager.state.value)
        assertTrue(canal.cerrado || abiertoCon.isEmpty(), "si la reconexión llegó a abrir, lo cierra")
    }

    @Test fun `una consulta de la conexion anterior que falla no tumba la conexion nueva`() = runBlocking<Unit> {
        val soltar = java.util.concurrent.CountDownLatch(1)
        val viejo = object : CanalSerie {
            @Volatile var cerrado = false
            override fun leer(destino: ByteArray): Int { Thread.sleep(10); return if (cerrado) -1 else 0 }
            override fun escribir(bytes: ByteArray): Boolean { soltar.await(); return false }   // escritura atorada que termina mal
            override fun cerrar() { cerrado = true }
        }
        manager.abrir = { _, _, _, _, _ -> viejo }
        manager.connect(perfil(), ScaleUsageContext.AREA_TICKET_LINE)
        Thread.sleep(100)   // la consulta ya está atorada en escribir
        manager.abrir = { _, _, _, _, _ -> canal }
        manager.connect(perfil(), ScaleUsageContext.AREA_TICKET_LINE)   // desconecta la vieja y abre la nueva
        soltar.countDown()
        Thread.sleep(200)
        assertTrue(!canal.cerrado, "la conexión nueva sigue abierta")
        canal.llega("ST,GS,+  36.320kg\r\n")
        assertIs<ScaleConnectionState.Stable>(esperar { it is ScaleConnectionState.Stable })
    }

    @Test fun `una lectura de la conexion anterior no publica su peso con el perfil nuevo`() = runBlocking<Unit> {
        val viejo = CanalFalso()
        manager.abrir = { _, _, _, _, _ -> viejo }
        manager.connect(perfil(), ScaleUsageContext.AREA_TICKET_LINE)
        manager.abrir = { _, _, _, _, _ -> canal }
        manager.connect(perfil().copy(name = "Otra báscula"), ScaleUsageContext.AREA_TICKET_LINE)
        viejo.llega("ST,GS,+  99.000kg\r\n")   // llega tarde por el canal viejo
        canal.llega("ST,GS,+  1.250kg\r\n")
        val estado = esperar { it is ScaleConnectionState.Stable }
        assertIs<ScaleConnectionState.Stable>(estado)
        Thread.sleep(150)
        val final = manager.state.value as ScaleConnectionState.Stable
        assertEquals("1.250", final.reading.netKg)
        assertEquals("Otra báscula", final.profileName)
    }

    @Test fun `un puerto que falla de inmediato termina en Problem, no en Lista`() = runBlocking<Unit> {
        canal.caido = true
        manager.connect(perfil(), ScaleUsageContext.AREA_TICKET_LINE)
        val estado = esperar { it is ScaleConnectionState.Problem }
        assertIs<ScaleConnectionState.Problem>(estado)
        Thread.sleep(100)
        assertIs<ScaleConnectionState.Problem>(manager.state.value)
    }

    private class CanalFalso : CanalSerie {
        private val porLeer = ConcurrentLinkedQueue<ByteArray>()
        val escrito = CopyOnWriteArrayList<ByteArray>()
        @Volatile var caido = false
        @Volatile var cerrado = false
        fun llega(texto: String) { porLeer += texto.toByteArray(Charsets.US_ASCII) }
        override fun leer(destino: ByteArray): Int {
            if (caido || cerrado) return -1
            val b = porLeer.poll() ?: run { Thread.sleep(10); return 0 }
            b.copyInto(destino); return b.size
        }
        override fun escribir(bytes: ByteArray): Boolean { escrito += bytes; return !caido }
        override fun cerrar() { cerrado = true }
    }
}

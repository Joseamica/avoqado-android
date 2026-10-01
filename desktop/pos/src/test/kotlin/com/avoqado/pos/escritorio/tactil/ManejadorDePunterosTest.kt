package com.avoqado.pos.escritorio.tactil

import androidx.compose.ui.geometry.Offset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val AWT = 0xAAAAL
private const val NUESTRO = 0x5555L
private const val WM_PAINT = 0x000F
private const val PT_MOUSE = 4

class ManejadorDePunterosTest {
    private class WindowsFalso(var procedimiento: Long = AWT) : Win32 {
        override val lienzo = 0x1234L
        val tipos = mutableMapOf<Int, Int>()
        val infos = mutableMapOf<Int, InfoDePuntero>()
        var consultasDeInfo = 0
        var antesDeInfo: (() -> Unit)? = null
        var ponerFalla = false
        var ponerTruena = false
        var consultarFallas = 0   // cuántas consultas del procedimiento devuelven 0 (error)
        var alPoner: (() -> Unit)? = null
        var llamarTruena = false
        var extra = 0L
        val registro = mutableListOf<String>()   // en orden: "poner:<proc>", "llamar:<proc>:<mensaje>", "defecto:<mensaje>"

        override fun tipo(id: Int) = tipos[id]
        override fun info(id: Int): InfoDePuntero? {
            consultasDeInfo++
            antesDeInfo?.invoke()
            return infos[id]
        }
        override fun procedimientoActual(): Long = if (consultarFallas > 0) { consultarFallas--; 0L } else procedimiento
        override fun ponerProcedimiento(proc: Long): Long {
            if (ponerTruena) error("SetWindowLongPtr truena")
            if (ponerFalla) return 0L
            val anterior = procedimiento
            procedimiento = proc
            registro += "poner:$proc"
            alPoner?.invoke()
            return anterior
        }
        override fun extraDelMensaje() = extra
        override fun llamar(proc: Long, mensaje: Int, wParam: Long, lParam: Long): Long {
            if (llamarTruena) error("CallWindowProc truena")
            registro += "llamar:$proc:$mensaje"
            return 7
        }
        override fun porDefecto(mensaje: Int, wParam: Long, lParam: Long): Long {
            registro += "defecto:$mensaje"
            return 8
        }
        fun llamadas() = registro.filter { it.startsWith("llamar") }
    }

    private class Prueba(val w: WindowsFalso = WindowsFalso()) {
        val encolados = mutableListOf<ContactoDeWindows>()
        val apagados = mutableListOf<String>()
        val notas = mutableListOf<String>()
        var encolarFalla: (ContactoDeWindows) -> Boolean = { false }
        val m = ManejadorDePunteros(
            w32 = w, nuestro = NUESTRO, generacion = { 0 },
            encolar = { c, _ -> if (encolarFalla(c)) error("cola rota") else encolados += c },
            alApagarse = { apagados += it },
            anotar = { notas += it },
        )
        fun dedo(id: Int, x: Int = 10, y: Int = 20, dw: Int = 1_000, banderas: Int = POINTER_FLAG_INCONTACT) {
            w.tipos[id] = PT_TOUCH
            w.infos[id] = InfoDePuntero(banderas, x, y, dw, x + 100, y + 100, 0x1234L)
        }
        /** wParam = id en la palabra baja + la bandera «en contacto» en la alta, como lo arma Windows. */
        fun msg(mensaje: Int, id: Int = 0, enContacto: Boolean = mensaje == WM_POINTERDOWN || mensaje == WM_POINTERUPDATE) =
            m.mensaje(mensaje, id.toLong() or (if (enContacto) POINTER_MESSAGE_FLAG_INCONTACT.toLong() shl 16 else 0L), 0L)
    }

    private fun lista() = Prueba().also { it.m.enganchar() }

    @Test fun `un contacto de dedo se consume completo y se encola con el tiempo de Windows`() {
        val p = lista()
        p.dedo(5, y = 20, dw = 1_000)
        assertEquals(0L, p.msg(WM_POINTERENTER, 5))
        assertEquals(0L, p.msg(WM_POINTERDOWN, 5))
        p.dedo(5, y = 60, dw = 1_016)
        assertEquals(0L, p.msg(WM_POINTERUPDATE, 5))
        p.dedo(5, y = 60, dw = 1_030, banderas = 0)
        assertEquals(0L, p.msg(WM_POINTERUP, 5))
        assertEquals(0L, p.msg(WM_POINTERLEAVE, 5))
        assertEquals(listOf(FaseDeToque.BAJA, FaseDeToque.MUEVE, FaseDeToque.SUBE, FaseDeToque.CANCELA), p.encolados.map { it.fase })
        assertEquals(listOf(20, 60, 60, 0), p.encolados.map { it.clienteY })
        assertEquals(listOf(0L, 16L, 30L, 30L), p.encolados.map { it.tiempoMs - p.encolados.first().tiempoMs })
        assertTrue(p.w.llamadas().isEmpty(), "nada del contacto llegó a Windows: no hay mouse promovido")
        assertTrue(p.notas.single().startsWith("Dedo en Windows: lienzo=4660 destino=4660"))
    }

    @Test fun `un dedo que se va sin UP se cancela`() {
        val p = lista()
        p.dedo(5)
        assertEquals(0L, p.msg(WM_POINTERDOWN, 5))
        assertEquals(0L, p.msg(WM_POINTERLEAVE, 5, enContacto = false), "el LEAVE de una secuencia nuestra se consume")
        assertEquals(listOf(FaseDeToque.BAJA, FaseDeToque.CANCELA), p.encolados.map { it.fase }, "Compose no se queda con el dedo abajo")
        assertEquals(listOf(5, 5), p.encolados.map { it.id })
    }

    @Test fun `el mouse, la pluma y la activación pasan intactos al procedimiento de AWT`() {
        val p = lista()
        p.w.tipos[1] = PT_MOUSE
        assertEquals(7L, p.msg(WM_POINTERDOWN, 1))
        p.dedo(2)
        p.msg(WM_POINTERENTER, 2)
        assertEquals(7L, p.msg(WM_POINTERACTIVATE, 2))
        assertEquals(7L, p.msg(WM_PAINT))
        assertEquals(listOf("llamar:$AWT:$WM_POINTERDOWN", "llamar:$AWT:$WM_POINTERACTIVATE", "llamar:$AWT:$WM_PAINT"), p.w.llamadas())
    }

    @Test fun `perder la captura sin UP ni LEAVE cancela por id, sin preguntarle nada a Windows, y deja restaurar`() {
        val p = lista()
        p.dedo(4)
        p.msg(WM_POINTERDOWN, 4); p.msg(WM_POINTERUPDATE, 4)
        val consultas = p.w.consultasDeInfo
        assertEquals(0L, p.msg(WM_POINTERCAPTURECHANGED, 4))
        assertEquals(consultas, p.w.consultasDeInfo, "CAPTURECHANGED no trae información nueva")
        assertEquals(FaseDeToque.CANCELA, p.encolados.last().fase)
        p.m.apagar("prueba")
        p.msg(WM_PAINT)
        assertEquals(AWT, p.w.procedimiento, "sin contactos a medias, se restauró")
    }

    @Test fun `un movimiento que no se pudo leer cancela el gesto y el soltar ya no es un clic`() {
        val p = Prueba()
        val enviados = mutableListOf<EventoDeToque>()
        val programados = ArrayDeque<() -> Unit>()
        val entrega = EntregaDeToques(
            TraductorDeToques { x, y -> Offset(x.toFloat(), y.toFloat()) }, enviar = { enviados += it }, reiniciar = { true },
            puedeEntregar = { true }, alBajarUnDedo = {}, programar = { _, a -> programados += a },
            fallas = ContadorDeFallas {}, alFallarLimpieza = {}, alErrorDeLaApp = {},
        )
        val m = ManejadorDePunteros(p.w, NUESTRO, entrega::generacionActual, entrega::encolar, {}, {})
        m.enganchar()
        fun msg(mensaje: Int, id: Int, enContacto: Boolean) =
            m.mensaje(mensaje, id.toLong() or (if (enContacto) POINTER_MESSAGE_FLAG_INCONTACT.toLong() shl 16 else 0L), 0L)
        p.dedo(3)
        msg(WM_POINTERDOWN, 3, true)
        p.w.infos.remove(3)
        msg(WM_POINTERUPDATE, 3, true)                       // Windows no dio la información
        p.dedo(3, banderas = 0)
        msg(WM_POINTERUP, 3, false)                          // el soltar sí trae información
        while (programados.isNotEmpty()) programados.removeFirst()()
        assertEquals(listOf(TipoDeEvento.PRESIONA), enviados.map { it.tipo }, "sin SUELTA: cero clics")
    }

    @Test fun `sin información al bajar, el contacto se consume y no llega a nadie`() {
        val p = lista()
        p.w.tipos[3] = PT_TOUCH
        assertEquals(0L, p.msg(WM_POINTERDOWN, 3))
        assertEquals(listOf(FaseDeToque.CANCELA), p.encolados.map { it.fase }, "un CANCELA de un dedo que nadie conoce no afecta a los demás")
    }

    @Test fun `tres fallas de Windows seguidas apagan el puente una vez y el siguiente dedo pasa como mouse`() {
        val p = lista()
        (1..3).forEach { id -> p.w.tipos[id] = PT_TOUCH; p.msg(WM_POINTERDOWN, id) }   // sin info: 3 fallas
        assertEquals(1, p.apagados.size)
        assertFalse(p.m.activo)
        p.dedo(9)
        assertEquals(7L, p.msg(WM_POINTERDOWN, 9))
    }

    @Test fun `si Windows nunca da la información, secuencias completas igual apagan el puente`() {
        val p = lista()
        (1..5).forEach { id ->   // tipo sí, información nunca: el LEAVE (que no le pregunta nada a Windows) no cuenta como éxito
            p.w.tipos[id] = PT_TOUCH
            p.msg(WM_POINTERENTER, id); p.msg(WM_POINTERDOWN, id); p.msg(WM_POINTERUP, id); p.msg(WM_POINTERLEAVE, id)
        }
        assertEquals(1, p.apagados.size)
        assertFalse(p.m.activo)
    }

    @Test fun `tres encolados fallidos seguidos apagan el puente aunque la lectura salga bien`() {
        val p = lista()
        p.encolarFalla = { it.fase != FaseDeToque.CANCELA }
        (1..3).forEach { id -> p.dedo(id); assertEquals(0L, p.msg(WM_POINTERDOWN, id)) }
        assertEquals(1, p.apagados.size, "el éxito se cuenta DESPUÉS de encolar, así que las fallas sí se acumulan")
    }

    @Test fun `si ni la cancelación se puede encolar, el puente se apaga en ese momento`() {
        val p = lista()
        p.dedo(2)
        p.msg(WM_POINTERDOWN, 2)
        p.encolarFalla = { true }
        p.dedo(2, banderas = 0)
        assertEquals(0L, p.msg(WM_POINTERUP, 2), "el mensaje ya decidido se sigue consumiendo")
        assertEquals(1, p.apagados.size)
        assertTrue(p.apagados.single().contains("cancelación"))
    }

    @Test fun `si pasar al original truena, no sale ninguna excepción`() {
        val p = lista()
        p.w.llamarTruena = true
        assertEquals(0L, p.msg(WM_PAINT))
    }

    @Test fun `un mensaje que llega a media instalación va al procedimiento de AWT`() {
        val p = Prueba()
        var respuesta = -1L
        p.w.alPoner = { respuesta = p.m.mensaje(WM_PAINT, 0, 0) }
        p.m.enganchar()
        assertEquals(7L, respuesta)
        assertEquals(listOf("llamar:$AWT:$WM_PAINT"), p.w.llamadas())
        assertEquals(NUESTRO, p.w.procedimiento)
        assertTrue(p.m.activo)
    }

    @Test fun `si no se puede instalar, queda apagado y la ventana como estaba`() {
        val p = Prueba()
        p.w.ponerFalla = true
        assertFailsWith<IllegalStateException> { p.m.enganchar() }
        assertFalse(p.m.activo)
        assertEquals(AWT, p.w.procedimiento)
    }

    @Test fun `apagar desde varios hilos avisa una sola vez`() {
        val avisos = AtomicInteger(0)
        val m = ManejadorDePunteros(WindowsFalso(), NUESTRO, { 0 }, { _, _ -> }, { avisos.incrementAndGet() }, {})
        m.enganchar()
        val salida = CountDownLatch(1)
        val hilos = (1..8).map { thread { salida.await(); m.apagar("carrera") } }
        salida.countDown()
        hilos.forEach { it.join() }
        assertEquals(1, avisos.get())
    }

    @Test fun `apagado con un dedo abajo, restaura hasta que ese contacto termina`() {
        val p = lista()
        p.dedo(6)
        p.msg(WM_POINTERDOWN, 6)
        p.m.apagar("prueba")
        p.msg(WM_POINTERUPDATE, 6)
        assertEquals(NUESTRO, p.w.procedimiento, "no se restaura con un contacto a medias")
        p.msg(WM_POINTERUP, 6)
        p.msg(WM_PAINT)
        assertEquals(AWT, p.w.procedimiento)
    }

    @Test fun `si restaurar falla, queda pendiente, se anota una vez y se reintenta`() {
        val p = lista()
        p.m.apagar("prueba")
        p.w.ponerFalla = true
        p.msg(WM_PAINT); p.msg(WM_PAINT)
        assertEquals(NUESTRO, p.w.procedimiento)
        assertEquals(1, p.notas.count { "restaurar" in it })
        p.w.ponerFalla = false
        p.msg(WM_PAINT)
        assertEquals(AWT, p.w.procedimiento)
    }

    @Test fun `si consultar el procedimiento falla, no se confunde con otro gancho y se restaura después`() {
        val p = lista()
        p.m.apagar("prueba")
        p.w.consultarFallas = 1
        p.msg(WM_PAINT)
        assertEquals(NUESTRO, p.w.procedimiento, "con la consulta fallida no se toca nada…")
        p.msg(WM_PAINT)
        assertEquals(AWT, p.w.procedimiento, "…pero sigue pendiente y se restaura en cuanto se puede consultar")
    }

    @Test fun `si alguien subclasificó encima, no nos quitamos`() {
        val p = lista()
        p.w.procedimiento = 0xBBBBL   // otro gancho encima del nuestro
        p.m.apagar("prueba")
        p.msg(WM_PAINT)
        assertEquals(0xBBBBL, p.w.procedimiento)
    }

    @Test fun `al destruirse el lienzo se restaura ANTES de pasar el mensaje`() {
        val p = lista()
        p.msg(WM_NCDESTROY)
        assertEquals(listOf("poner:$AWT", "llamar:$AWT:$WM_NCDESTROY"), p.w.registro.takeLast(2))
        assertFalse(p.m.activo)
    }

    @Test fun `con un modal abierto no se toman dedos nuevos y lo que va no se encola`() {
        val p = lista()
        p.dedo(1); p.msg(WM_POINTERDOWN, 1)
        p.m.modalAbierto = true
        assertEquals(0L, p.msg(WM_POINTERUPDATE, 1), "el contacto que ya era nuestro se termina de consumir")
        p.dedo(2)
        assertEquals(7L, p.msg(WM_POINTERDOWN, 2), "uno nuevo pasa como hoy y AWT lo bloquea como a cualquier mouse")
        assertEquals(listOf(FaseDeToque.BAJA), p.encolados.map { it.fase })
    }

    @Test fun `con un botón del mouse apretado no se toman dedos nuevos`() {
        val p = lista()
        p.dedo(1); p.msg(WM_POINTERDOWN, 1)
        p.m.mouseApretado = true
        assertEquals(0L, p.msg(WM_POINTERUPDATE, 1), "el contacto que ya era nuestro se termina de consumir")
        p.dedo(2)
        assertEquals(7L, p.msg(WM_POINTERDOWN, 2), "uno nuevo pasa como hoy")
        assertEquals(listOf(FaseDeToque.BAJA), p.encolados.map { it.fase })
    }

    @Test fun `engancharse dos veces se rechaza`() {
        val p = lista()
        assertFailsWith<IllegalStateException> { p.m.enganchar() }
        assertEquals(NUESTRO, p.w.procedimiento)
        assertEquals(7L, p.msg(WM_PAINT))
        assertEquals("llamar:$AWT:$WM_PAINT", p.w.llamadas().last(), "sigue pasando al de AWT, no a sí mismo")
    }

    @Test fun `si poner el procedimiento truena, queda apagado`() {
        val p = Prueba()
        p.w.ponerTruena = true
        assertFailsWith<IllegalStateException> { p.m.enganchar() }
        assertFalse(p.m.activo)
    }

    @Test fun `si Windows no dice el tipo del puntero, cuenta como falla`() {
        val p = lista()
        (1..3).forEach { id -> p.w.infos[id] = InfoDePuntero(POINTER_FLAG_INCONTACT, 1, 1, 1_000, 1, 1, 0x1234L); p.msg(WM_POINTERDOWN, id) }
        assertEquals(1, p.apagados.size)
    }

    @Test fun `al destruirse el lienzo con otro gancho encima, no nos quitamos`() {
        val p = lista()
        p.w.procedimiento = 0xBBBBL   // otro gancho encima del nuestro
        p.msg(WM_NCDESTROY)
        assertEquals(0xBBBBL, p.w.procedimiento)
    }

    @Test fun `un BAJA leído mientras Swing cancela no se entrega después`() {
        val w = WindowsFalso()
        val enviados = mutableListOf<EventoDeToque>()
        val programados = ArrayDeque<() -> Unit>()
        val entrega = EntregaDeToques(
            TraductorDeToques { x, y -> Offset(x.toFloat(), y.toFloat()) }, enviar = { enviados += it }, reiniciar = { true },
            puedeEntregar = { true }, alBajarUnDedo = {}, programar = { _, a -> synchronized(programados) { programados += a } },
            fallas = ContadorDeFallas {}, alFallarLimpieza = {}, alErrorDeLaApp = {},
        )
        val m = ManejadorDePunteros(w, NUESTRO, entrega::generacionActual, entrega::encolar, {}, {})
        m.enganchar()
        w.tipos[1] = PT_TOUCH
        w.infos[1] = InfoDePuntero(POINTER_FLAG_INCONTACT, 10, 10, 1_000, 110, 110, 0x1234L)
        val leyendo = CountDownLatch(1)
        val seguir = CountDownLatch(1)
        w.antesDeInfo = { leyendo.countDown(); seguir.await(5, TimeUnit.SECONDS) }
        val nativo = thread { m.mensaje(WM_POINTERDOWN, 1L or (POINTER_MESSAGE_FLAG_INCONTACT.toLong() shl 16), 0L) }
        assertTrue(leyendo.await(5, TimeUnit.SECONDS))
        entrega.cancelar("prueba")          // Swing cancela mientras el hilo nativo lee
        seguir.countDown()
        nativo.join()
        w.antesDeInfo = null
        synchronized(programados) { while (programados.isNotEmpty()) programados.removeFirst()() }
        assertTrue(enviados.isEmpty(), "el BAJA de antes de cancelar no se entrega")
    }

    @Test fun `un mouse que llega durante un dedo queda anotado con su firma y sigue pasando igual`() {
        val p = lista()
        p.dedo(5)
        p.msg(WM_POINTERDOWN, 5)
        p.w.extra = 0xFF515780L
        assertEquals(7L, p.m.mensaje(0x0200, 0L, (40L shl 16) or 30L))
        val nota = p.notas.single { it.startsWith("Mouse durante un dedo") }
        assertTrue("msg=0x0200" in nota && "GENERADO-POR-EL-DEDO" in nota && "cliente=(30,40)" in nota, nota)
        val q = lista()
        assertEquals(7L, q.m.mensaje(0x0200, 0L, 0L))
        assertTrue(q.notas.none { it.startsWith("Mouse durante") })
    }
}

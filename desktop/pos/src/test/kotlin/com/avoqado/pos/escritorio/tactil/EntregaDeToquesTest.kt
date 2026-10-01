package com.avoqado.pos.escritorio.tactil

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntregaDeToquesTest {
    /** Todo lo de alrededor, falso y a la vista. `programados` son las tareas que en producción irían al hilo de Swing. */
    private class Banco(
        var puede: Boolean = true,
        val reinicios: MutableList<Boolean> = mutableListOf(),   // respuestas de reiniciar(); vacía = true
        val enviarHace: (EventoDeToque) -> Unit = {},
    ) {
        val enviados = mutableListOf<EventoDeToque>()
        val reiniciadosCon = mutableListOf<Set<Long>>()
        val erroresDeLaApp = mutableListOf<Throwable>()
        var activaciones = 0
        var apagados = 0
        var limpiezasFallidas = 0
        val notas = mutableListOf<String>()
        val programados = ArrayDeque<Pair<Int, () -> Unit>>()
        var profundidad = 0
        var profundidadMax = 0
        var reiniciarTruena = false
        var puedeTruena = false
        lateinit var entrega: EntregaDeToques

        fun crear(tope: Int = 256, lote: Int = 64): EntregaDeToques {
            entrega = EntregaDeToques(
                traductor = TraductorDeToques { x, y -> Offset(x.toFloat(), y.toFloat()) },
                enviar = { e ->
                    profundidad++
                    profundidadMax = maxOf(profundidadMax, profundidad)
                    try {
                        enviarHace(e)
                        enviados += e
                    } finally {
                        profundidad--
                    }
                },
                reiniciar = { dedos ->
                    if (reiniciarTruena) error("Compose cambió")
                    reiniciadosCon += dedos
                    reinicios.removeFirstOrNull() ?: true
                },
                puedeEntregar = { if (puedeTruena) error("se cayó al preguntar") else puede },
                alBajarUnDedo = { activaciones++ },
                programar = { ms, accion -> programados.addLast(ms to accion) },
                fallas = ContadorDeFallas { apagados++ },
                alFallarLimpieza = { limpiezasFallidas++ },
                alErrorDeLaApp = { erroresDeLaApp += it },
                tope = tope,
                lote = lote,
                anotar = { notas += it },
            )
            return entrega
        }

        /** Lo que haría el hilo nativo: toma la generación y encola. */
        fun dar(id: Int, y: Int, fase: FaseDeToque) = entrega.encolar(ContactoDeWindows(id, 0, y, fase, y.toLong()), entrega.generacionActual())

        /** Corre lo programado hasta que no quede nada (como el hilo de Swing). */
        fun correr(max: Int = 10_000) {
            var n = 0
            while (programados.isNotEmpty() && n++ < max) programados.removeFirst().second()
        }
    }

    @Test fun `entrega en orden y sin reentrada aunque un manejador abra un bucle anidado`() {
        lateinit var b: Banco
        b = Banco(enviarHace = { e ->
            if (e.tipo == TipoDeEvento.PRESIONA) {
                b.dar(1, 20, FaseDeToque.MUEVE)
                b.entrega.drenar()   // el bucle anidado
            }
        })
        b.crear()
        b.dar(1, 10, FaseDeToque.BAJA)
        b.correr()
        assertEquals(1, b.profundidadMax, "el drenar anidado no debe entregar adentro del otro")
        assertEquals(listOf(TipoDeEvento.PRESIONA, TipoDeEvento.MUEVE), b.enviados.map { it.tipo })
        assertEquals(0, b.apagados)
        assertTrue(b.reiniciadosCon.isEmpty())
    }

    @Test fun `con un modal abierto o el puente apagado se cancela lo que va y se tira lo pendiente`() {
        val b = Banco()
        b.crear()
        b.dar(1, 10, FaseDeToque.BAJA); b.correr()
        b.puede = false
        b.dar(1, 40, FaseDeToque.MUEVE); b.dar(1, 50, FaseDeToque.SUBE); b.correr()
        assertEquals(listOf(TipoDeEvento.PRESIONA), b.enviados.map { it.tipo }, "nada más llega a Compose")
        assertEquals(listOf(setOf(1L)), b.reiniciadosCon, "se reinicia el dedo que Compose tenía; no se suelta como clic")
    }

    @Test fun `un BAJA que esperaba en la cola no se entrega después de perder el foco`() {
        val b = Banco()
        val entrega = b.crear()
        b.dar(1, 10, FaseDeToque.BAJA)
        entrega.cancelar("prueba")
        b.correr()
        assertTrue(b.enviados.isEmpty())
    }

    @Test fun `lo leído con una generación vieja se tira aunque se encole después de cancelar`() {
        val b = Banco()
        val entrega = b.crear()
        val vieja = entrega.generacionActual()   // el hilo nativo la tomó antes de leer…
        entrega.cancelar("prueba")                        // …Swing canceló mientras leía…
        entrega.encolar(ContactoDeWindows(1, 0, 10, FaseDeToque.BAJA, 10), vieja)   // …y encoló tarde
        b.correr()
        assertTrue(b.enviados.isEmpty())
        b.dar(2, 10, FaseDeToque.BAJA); b.correr()
        assertEquals(listOf(TipoDeEvento.PRESIONA), b.enviados.map { it.tipo }, "lo de la generación nueva sí entra")
    }

    @Test fun `invalidar lo pendiente tira lo que el hilo nativo estaba leyendo`() {
        val b = Banco()
        val entrega = b.crear()
        val leyendo = entrega.generacionActual()   // el hilo nativo empezó a leer un BAJA…
        entrega.invalidarPendientes()              // …llegó el mouse…
        entrega.encolar(ContactoDeWindows(1, 0, 10, FaseDeToque.BAJA, 10), leyendo)   // …y el BAJA se encoló tarde
        b.correr()
        assertTrue(b.enviados.isEmpty())
        assertTrue(b.reiniciadosCon.isEmpty(), "sin dedo en curso no hay nada que reiniciar en Compose")
        b.dar(2, 10, FaseDeToque.BAJA); b.correr()
        assertEquals(listOf(TipoDeEvento.PRESIONA), b.enviados.map { it.tipo }, "lo que llega después sí entra")
    }

    @Test fun `si Compose está ocupado al cancelar, se reintenta y mientras tanto no entra nada`() {
        val b = Banco(reinicios = mutableListOf(false, false))   // dos veces ocupado, luego sí
        val entrega = b.crear()
        b.dar(1, 10, FaseDeToque.BAJA); b.correr()
        entrega.cancelar("prueba")
        b.dar(2, 10, FaseDeToque.BAJA)                 // llega mientras sigue pendiente
        b.programados.removeFirst().second()           // 1.er reintento: sigue ocupado
        b.correr()                                     // el BAJA nuevo se tira (pendiente) y el 2.º reintento ya puede
        assertEquals(3, b.reiniciadosCon.size)
        assertEquals(listOf(TipoDeEvento.PRESIONA), b.enviados.map { it.tipo })
        b.dar(3, 10, FaseDeToque.BAJA); b.correr()
        assertEquals(TipoDeEvento.PRESIONA, b.enviados.last().tipo, "ya reiniciado, lo nuevo sí entra")
    }

    @Test fun `si el primer soltar falla, Compose se reinicia y el gesto siguiente se completa`() {
        var fallasRestantes = 1
        val b = Banco(enviarHace = { e -> if (e.tipo == TipoDeEvento.SUELTA && fallasRestantes-- > 0) error("Compose truena al soltar") })
        b.crear()
        b.dar(1, 10, FaseDeToque.BAJA); b.dar(1, 10, FaseDeToque.SUBE); b.correr()
        assertEquals(listOf(setOf(1L)), b.reiniciadosCon, "no queda un dedo que nadie va a soltar")
        b.dar(2, 10, FaseDeToque.BAJA); b.dar(2, 10, FaseDeToque.SUBE); b.correr()
        assertEquals(
            listOf(TipoDeEvento.PRESIONA, TipoDeEvento.PRESIONA, TipoDeEvento.SUELTA), b.enviados.map { it.tipo },
            "el segundo gesto llega completo: presionar y soltar",
        )
        assertEquals(1, b.erroresDeLaApp.size, "lo que lanzó la app se reporta como con el mouse")
        assertEquals(0, b.apagados, "y no cuenta como falla del puente")
    }

    @Test fun `si reiniciar truena se apaga el puente`() {
        val b = Banco()
        b.crear()
        b.reiniciarTruena = true
        b.dar(1, 10, FaseDeToque.BAJA); b.dar(1, 10, FaseDeToque.CANCELA); b.correr()
        assertEquals(1, b.limpiezasFallidas)
    }

    @Test fun `cancelar un dedo reinicia el gesto entero sin mandarle nada a Compose`() {
        val b = Banco()
        b.crear()
        b.dar(1, 10, FaseDeToque.BAJA); b.dar(2, 20, FaseDeToque.BAJA); b.dar(1, 10, FaseDeToque.CANCELA); b.correr()
        assertEquals(listOf(TipoDeEvento.PRESIONA, TipoDeEvento.PRESIONA), b.enviados.map { it.tipo })
        assertEquals(listOf(setOf(1L, 2L)), b.reiniciadosCon)
    }

    @Test fun `con contactos completos la cola nunca pasa del tope y desbordar cancela todo`() {
        val b = Banco()
        val entrega = b.crear(tope = 8)
        var maximo = 0
        (1..20).forEach { id ->
            b.dar(id, 10, FaseDeToque.BAJA); b.dar(id, 10, FaseDeToque.SUBE)
            maximo = maxOf(maximo, entrega.retenidos)
        }
        assertTrue(maximo <= 8, "retuvo $maximo")
        b.correr()
        assertTrue(b.enviados.isEmpty(), "desbordada: nada de lo retenido se entrega")
        assertEquals(1, b.reiniciadosCon.size)
        b.dar(50, 10, FaseDeToque.BAJA); b.correr()
        assertEquals(listOf(TipoDeEvento.PRESIONA), b.enviados.map { it.tipo }, "y luego sigue funcionando")
    }

    @Test fun `un productor más rápido de movimientos no acapara el hilo ni pierde bajar ni soltar`() {
        val b = Banco()
        val entrega = b.crear(tope = 50, lote = 10)
        b.dar(1, 0, FaseDeToque.BAJA)
        (1..1_000).forEach { b.dar(1, it, FaseDeToque.MUEVE) }
        b.dar(1, 1_001, FaseDeToque.SUBE)
        assertTrue(entrega.retenidos <= 50)
        b.programados.removeFirst().second()   // UN drenado
        assertTrue(b.enviados.size <= 10, "un drenado entrega a lo más un lote; entregó ${b.enviados.size}")
        assertEquals(1, b.programados.size, "y se reprograma una sola vez")
        b.correr()
        assertEquals(TipoDeEvento.PRESIONA, b.enviados.first().tipo)
        assertEquals(TipoDeEvento.SUELTA, b.enviados.last().tipo)
        assertTrue(b.reiniciadosCon.isEmpty(), "tirar movimientos no es desbordar")
    }

    @Test fun `cancelar varias veces con Compose ocupado deja una sola cadena de reintentos`() {
        val b = Banco(reinicios = mutableListOf(false, false, false, false))
        val entrega = b.crear()
        repeat(3) { entrega.cancelar("prueba") }
        assertEquals(1, b.programados.count { it.first == 16 }, "una sola cadena, no tres")
        b.correr()
        assertEquals(5, b.reiniciadosCon.size, "3 intentos al cancelar + 2 de la cadena, hasta que Compose se libera")
        assertTrue(b.programados.isEmpty())
    }

    @Test fun `con el puente apagado y nada en curso, un contacto no reinicia nada`() {
        val b = Banco(puede = false)
        b.crear()
        b.dar(1, 10, FaseDeToque.BAJA); b.dar(1, 20, FaseDeToque.MUEVE); b.correr()
        assertTrue(b.enviados.isEmpty())
        assertTrue(b.reiniciadosCon.isEmpty(), "no hay nada que cancelar")
    }

    @Test fun `si algo truena afuera de enviar, no sale de drenar y cuenta como falla`() {
        val b = Banco()
        b.crear()
        b.puedeTruena = true
        repeat(3) {
            b.dar(it + 1, 10, FaseDeToque.BAJA)
            b.correr()   // no debe lanzar
        }
        assertEquals(1, b.apagados)
    }

    @Test fun `al bajar un dedo se avisa para activar la ventana`() {
        val b = Banco()
        b.crear()
        b.dar(1, 10, FaseDeToque.BAJA); b.dar(1, 20, FaseDeToque.MUEVE); b.correr()
        assertEquals(1, b.activaciones)
    }

    @Test fun `los errores de la app se reportan, reinician el gesto y no apagan el puente`() {
        val b = Banco(enviarHace = { error("un onClick truena") })
        b.crear()
        (1..5).forEach {   // de uno en uno: cada error cancela y vacía la cola
            b.dar(it, 10, FaseDeToque.BAJA)
            b.correr()
        }
        assertEquals(5, b.erroresDeLaApp.size)
        assertEquals(0, b.apagados)
        assertEquals(5, b.reiniciadosCon.size)
    }

    @Test fun `si la cola desborda a media entrega, lo retenido no se entrega y el dedo se reinicia`() {
        lateinit var b: Banco
        b = Banco(enviarHace = { e ->
            if (e.tipo == TipoDeEvento.PRESIONA && e.punteros.singleOrNull()?.id == 1L) {   // singleOrNull: con 2 dedos, single() lanzaría
                (2..20).forEach { id -> b.dar(id, 10, FaseDeToque.BAJA); b.dar(id, 10, FaseDeToque.SUBE) }
                b.dar(1, 10, FaseDeToque.CANCELA)   // ya no cabe: se pierde
            }
        })
        b.crear(tope = 8)
        b.dar(1, 10, FaseDeToque.BAJA)
        b.correr()
        assertEquals(listOf(TipoDeEvento.PRESIONA), b.enviados.map { it.tipo }, "nada de lo retenido se entrega")
        assertTrue(b.reiniciadosCon.any { 1L in it }, "el dedo 1 que Compose tenía se reinició")
    }

    @Test fun `hayDedos dice si hay un dedo en curso`() {
        val b = Banco()
        val entrega = b.crear()
        assertFalse(entrega.hayDedos, "al inicio no hay nada")
        b.dar(1, 10, FaseDeToque.BAJA); b.correr()
        assertTrue(entrega.hayDedos, "un BAJA entregado es un dedo en curso")
        entrega.cancelar("prueba")
        assertFalse(entrega.hayDedos, "cancelar lo olvida")
    }

    @Test fun `cada cancelación deja su motivo en la bitácora, con tope`() {
        val b = Banco()
        val entrega = b.crear()
        b.dar(1, 10, FaseDeToque.BAJA); b.correr()
        entrega.cancelar("prueba de motivo")
        assertTrue(b.notas.any { it.startsWith("Gesto cancelado: prueba de motivo") && it.contains("dedos en Compose=[1]") }, b.notas.toString())
        repeat(40) { entrega.cancelar("x") }
        assertEquals(30, b.notas.count { it.startsWith("Gesto cancelado") })
    }
}

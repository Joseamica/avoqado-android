package com.avoqado.pos.tables

import com.avoqado.pos.tables.data.AddOrderItemRequest
import com.avoqado.pos.tables.data.RondaConLlave
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * La ronda con llave (spec 2026-09-27 §5; patrón de avoqado-tpv `TablesRepository.addItems`): la red de seguridad se
 * escribe RETENIDA antes de la red; éxito o rechazo la descartan, la red caída (o una cancelación) la suelta.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RondaConLlaveTest {

    private val pasos = mutableListOf<String>()

    private fun linea(nombre: String) = AddOrderItemRequest(quantity = 1, customName = nombre, customUnitPriceCents = 100)

    @Test
    fun `las llaves son las del reducer, por indice, y no tocan lo demas`() {
        val conLlaves = RondaConLlave.conLlaves(listOf(linea("Pan"), linea("Café")), "r1")
        assertEquals(listOf("sync:r1:0", "sync:r1:1"), conLlaves.map { it.externalId })
        assertEquals("Café", conLlaves[1].customName)
    }

    @Test
    fun `P1 primero se guarda retenido, luego en linea, y con exito se descarta`() = runTest {
        val d = RondaConLlave.enviar(
            guardarRetenido = { pasos += "guardar" },
            enLinea = { pasos += "enLinea"; Result.success(7) },
            soltar = { pasos += "soltar" },
            descartar = { pasos += "descartar" },
            esErrorDeRed = { it is IOException },
        )
        assertEquals(RondaConLlave.Desenlace.Enviada(7), d)
        assertEquals(listOf("guardar", "enLinea", "descartar"), pasos)
    }

    @Test
    fun `P1 sin red se suelta para repetirse, no se descarta`() = runTest {
        val d = RondaConLlave.enviar(
            guardarRetenido = { pasos += "guardar" },
            enLinea = { Result.failure<Unit>(IOException("sin red")) },
            soltar = { pasos += "soltar" },
            descartar = { pasos += "descartar" },
            esErrorDeRed = { it is IOException },
        )
        assertEquals(RondaConLlave.Desenlace.Encolada, d)
        assertEquals(listOf("guardar", "soltar"), pasos)
    }

    @Test
    fun `P1 M2 una excepcion en descartar tras exito no tumba la app`() = runTest {
        val d = RondaConLlave.enviar(
            guardarRetenido = { pasos += "guardar" },
            enLinea = { pasos += "enLinea"; Result.success(7) },
            soltar = { pasos += "soltar" },
            descartar = { pasos += "descartar"; throw IllegalStateException("disco lleno") },
            esErrorDeRed = { it is IOException },
        )
        assertEquals("la ronda ya está en el servidor: el DELETE que revienta no debe tumbar nada", RondaConLlave.Desenlace.Enviada(7), d)
        assertEquals(listOf("guardar", "enLinea", "descartar"), pasos)
    }

    @Test
    fun `un rechazo del servidor descarta el intent y regresa el error tal cual`() = runTest {
        val rechazo = IllegalStateException("409")
        val d = RondaConLlave.enviar(
            guardarRetenido = { pasos += "guardar" },
            enLinea = { Result.failure<Unit>(rechazo) },
            soltar = { pasos += "soltar" },
            descartar = { pasos += "descartar" },
            esErrorDeRed = { it is IOException },
        )
        assertEquals(RondaConLlave.Desenlace.Rechazada(rechazo), d)
        assertEquals(listOf("guardar", "descartar"), pasos)
    }

    @Test
    fun `si no se pudo guardar no se intenta nada en linea y lo escrito a medias se descarta`() = runTest {
        val d = RondaConLlave.enviar(
            guardarRetenido = { throw IllegalStateException("disco lleno") },
            enLinea = { pasos += "enLinea"; Result.success(Unit) },
            soltar = { pasos += "soltar" },
            descartar = { pasos += "descartar" },
            esErrorDeRed = { false },
        )
        assertEquals("disco lleno", (d as RondaConLlave.Desenlace.NoSeGuardo).error.message)
        assertEquals("nada en línea; si la fila alcanzó a escribirse, no se queda de barrera", listOf("descartar"), pasos)
    }

    @Test
    fun `P1 si cancelan mientras se escribe la red de seguridad se suelta - nunca queda retenida`() = runTest {
        val trabajo = launch {
            RondaConLlave.enviar<Unit>(
                guardarRetenido = { pasos += "guardar"; awaitCancellation() },
                enLinea = { pasos += "enLinea"; Result.success(Unit) },
                soltar = { pasos += "soltar" },
                descartar = { pasos += "descartar" },
                esErrorDeRed = { false },
            )
        }
        runCurrent()
        trabajo.cancelAndJoin()
        assertEquals(listOf("guardar", "soltar"), pasos)
    }

    @Test
    fun `P1 una cancelacion envuelta en Result (runCatching del repositorio) suelta, no descarta`() = runTest {
        val d = RondaConLlave.enviar(
            guardarRetenido = { pasos += "guardar" },
            enLinea = { Result.failure<Unit>(CancellationException("se cerró la mesa")) },
            soltar = { pasos += "soltar" },
            descartar = { pasos += "descartar" },
            esErrorDeRed = { false },
        )
        assertEquals(RondaConLlave.Desenlace.Encolada, d)
        assertEquals(listOf("guardar", "soltar"), pasos)
    }

    @Test
    fun `P1 si cancelan a medio envio el intent se suelta igual - no se queda retenido`() = runTest {
        val trabajo = launch {
            RondaConLlave.enviar<Unit>(
                guardarRetenido = { pasos += "guardar" },
                enLinea = { awaitCancellation() },
                soltar = { pasos += "soltar" },
                descartar = { pasos += "descartar" },
                esErrorDeRed = { false },
            )
        }
        runCurrent()
        trabajo.cancelAndJoin()
        assertEquals(listOf("guardar", "soltar"), pasos)
    }

    @Test
    fun `P1 una excepcion de enLinea que no es cancelacion se trata como incierta, no como exito ni como retenida para siempre`() = runTest {
        val d = RondaConLlave.enviar<Unit>(
            guardarRetenido = { pasos += "guardar" },
            enLinea = { throw IllegalStateException("bug en el repositorio") },
            soltar = { pasos += "soltar" },
            descartar = { pasos += "descartar" },
            esErrorDeRed = { true },
        )
        assertEquals(RondaConLlave.Desenlace.Encolada, d)
        assertEquals(listOf("guardar", "soltar"), pasos)
    }
}

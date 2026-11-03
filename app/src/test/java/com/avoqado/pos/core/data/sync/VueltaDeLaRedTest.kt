package com.avoqado.pos.core.data.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D18, Codex r1 #2 y #4: Ventas e Informes recargan al volver la red sólo en una vuelta REAL del aparato (no en un parpadeo de
 * la bandera del servidor), y DESPUÉS de que suba lo cobrado sin red.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VueltaDeLaRedTest {
    private val red = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private val porSubir = MutableStateFlow(0)
    private var vueltas = 0

    private fun TestScope.vigilar() {
        val vuelta = VueltaDeLaRed(red, servidor, porSubir, esperaDeLaRutaMs = 2_000, esperaDeLaColaMs = 30_000)
        backgroundScope.launch { vuelta.alVolver { vueltas++ } }
        runCurrent()
    }

    private fun TestScope.cortarYVolver() {
        red.value = false
        runCurrent()
        red.value = true
        runCurrent()
    }

    @Test
    fun `arrancar con red no es una vuelta`() = runTest {
        vigilar()
        advanceTimeBy(60_000)
        assertEquals(0, vueltas)
    }

    @Test
    fun `la red vuelve - espera a que la ruta suba y actua una vez`() = runTest {
        vigilar()
        cortarYVolver()
        advanceTimeBy(1_999)
        assertEquals(0, vueltas)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, vueltas)

        advanceTimeBy(60_000)
        assertEquals(1, vueltas)
    }

    @Test
    fun `un parpadeo de la bandera del servidor con el WiFi arriba no es una vuelta`() = runTest {
        vigilar()
        repeat(3) {
            servidor.value = false
            runCurrent()
            servidor.value = true
            runCurrent()
            advanceTimeBy(10_000)
        }
        assertEquals(0, vueltas)
    }

    @Test
    fun `con un cobro sin subir espera a que la cola se vacie`() = runTest {
        porSubir.value = 1
        vigilar()
        cortarYVolver()
        advanceTimeBy(10_000)
        assertEquals("la recarga no sale con el cobro todavía en la cola", 0, vueltas)

        porSubir.value = 0
        runCurrent()
        assertEquals(1, vueltas)
    }

    @Test
    fun `si la cola no se vacia en 30 s, actua igual`() = runTest {
        porSubir.value = 2
        vigilar()
        cortarYVolver()
        advanceTimeBy(2_000 + 29_999)
        assertEquals(0, vueltas)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, vueltas)
    }

    @Test
    fun `espera a que el servidor conteste`() = runTest {
        vigilar()
        red.value = false
        servidor.value = false
        runCurrent()
        red.value = true
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(0, vueltas)

        servidor.value = true
        runCurrent()
        assertEquals(1, vueltas)
    }

    @Test
    fun `si la red se vuelve a ir mientras espera, no actua hasta la siguiente vuelta`() = runTest {
        vigilar()
        cortarYVolver()
        advanceTimeBy(1_000)
        red.value = false
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(0, vueltas)

        red.value = true
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(1, vueltas)
    }
}

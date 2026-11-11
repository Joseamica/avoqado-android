package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.lan.RegistroDeCuentas.NO_CONFIRMO_MS
import com.avoqado.pos.core.data.lan.RegistroDeCuentas.TTL_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D12 · Opción D, pieza 1: el registro del DUEÑO de cada cuenta (la caja que la abrió), PURO y con reloj falso. Reglas en
 * `docs/audit/2026-11-10-d12-cuenta-abierta-sin-red/opcion-d.md` (R1, R6, H1, H2) y el brief `opcion-d-brief.md`.
 */
class RegistroDeCuentasTest {
    private var arranque = "boot-1"
    private var ms = 1_000L
    private var hora = 1_700_000_000_000L
    private fun ahora() = Instante(arranque, ms)
    private fun pasan(n: Long) { ms += n; hora += n }

    private val ana = Solicitud(por = "caja-2", caja = "Caja 2", quien = "Ana", intento = "i-ana")
    private val beto = Solicitud(por = "caja-3", caja = "Caja 3", quien = "Beto", intento = "i-beto")
    private val r = RegistroDeCuentas

    private fun Registro.apartar(s: Solicitud) = r.apartar(this, "o1", s, ahora(), hora)
    private fun Registro.comprometer(s: Solicitud) = r.comprometer(this, "o1", s, ahora(), hora)
    private fun Registro.estado() = r.normalizar(this, ahora(), hora).cuentas["o1"]

    @Test fun `apartar concede y vence a los 30 s sin comprometer`() {
        val paso = Registro().apartar(ana)
        assertEquals(Respuesta.Concedida, paso.respuesta)
        pasan(TTL_MS - 1)
        assertEquals(EstadoDeCuenta.APARTADA, paso.registro.estado()?.estado)
        assertEquals(Respuesta.Ocupada("Ana", "Caja 2"), paso.registro.apartar(beto).respuesta)
        pasan(1)
        assertNull(paso.registro.estado())
        assertEquals(Respuesta.Concedida, paso.registro.apartar(beto).respuesta)
    }

    @Test fun `renovar con el mismo intento extiende el apartado`() {
        var reg = Registro().apartar(ana).registro
        pasan(20_000)
        reg = reg.apartar(ana).also { assertEquals(Respuesta.Concedida, it.respuesta) }.registro
        pasan(25_000) // 45 s desde el primero, 25 s desde la renovación
        assertEquals(Respuesta.Ocupada("Ana", "Caja 2"), reg.apartar(beto).respuesta)
    }

    @Test fun `suelta sólo afecta su propio intento (R1)`() {
        val apartada = Registro().apartar(ana).registro
        assertEquals(apartada, r.soltar(apartada, "o1", "i-otro"))
        assertNull(r.soltar(apartada, "o1", "i-ana").cuentas["o1"])

        val comprometida = apartada.comprometer(ana).registro
        assertEquals(comprometida, r.soltar(comprometida, "o1", "i-otro"))
        assertNull(r.soltar(comprometida, "o1", "i-ana").cuentas["o1"])
    }

    @Test fun `comprometer desde el apartado propio o desde libre concede, y el de otro intento no`() {
        val apartada = Registro().apartar(ana).registro
        assertEquals(Respuesta.Ocupada("Ana", "Caja 2"), apartada.comprometer(beto).respuesta)
        assertEquals(EstadoDeCuenta.COMPROMETIDA, apartada.comprometer(ana).registro.estado()?.estado)
        // El dueño es la única autoridad: desde LIBRE (el apartado venció o el dueño se reinició) comprometer es apartar y comprometer.
        assertEquals(Respuesta.Concedida, Registro().comprometer(beto).respuesta)
    }

    @Test fun `COMPROMETIDA nunca se libera por tiempo`() {
        val reg = Registro().comprometer(ana).registro
        pasan(24 * 60 * 60 * 1000L)
        assertEquals(EstadoDeCuenta.COMPROMETIDA, reg.estado()?.estado)
        assertEquals(Respuesta.Ocupada("Ana", "Caja 2"), reg.apartar(beto).respuesta)
    }

    @Test fun `No se cobró sale a los 2 min, cancela el intento y rechaza sus apartar y comprometer tardíos`() {
        val reg = Registro().comprometer(ana).registro
        pasan(NO_CONFIRMO_MS - 1)
        assertFalse(r.puedeDeclararNoSeCobro(reg.estado(), ahora()))
        assertFalse(r.noSeCobro(reg, "o1", ahora(), hora).hecho)
        pasan(1)
        assertTrue(r.puedeDeclararNoSeCobro(reg.estado(), ahora()))
        val libre = r.noSeCobro(reg, "o1", ahora(), hora).also { assertTrue(it.hecho) }.registro
        assertNull(libre.estado())
        assertEquals(Respuesta.Rechazada, libre.apartar(ana).respuesta)
        assertEquals(Respuesta.Rechazada, libre.comprometer(ana).respuesta)
        // Otro intento sí puede.
        assertEquals(Respuesta.Concedida, libre.apartar(beto).respuesta)
    }

    @Test fun `un cobrada de un intento cancelado sí se acepta y la deja CERRADA`() {
        val cancelada = r.noSeCobro(Registro().comprometer(ana).registro.also { pasan(NO_CONFIRMO_MS) }, "o1", ahora(), hora).registro
        val cerrada = r.cobrada(cancelada, "o1", ana, faltaSubirla = true, ahora(), hora)
        assertEquals(EstadoDeCuenta.CERRADA, cerrada.estado()?.estado)
        assertEquals(Respuesta.Cerrada("Caja 2", faltaSubirla = true), cerrada.apartar(beto).respuesta)
    }

    @Test fun `volver a comprometer el mismo intento reinicia la espera de 2 min`() {
        var reg = Registro().comprometer(ana).registro
        pasan(NO_CONFIRMO_MS - 10_000)
        reg = reg.comprometer(ana).registro
        pasan(10_000)
        assertFalse(r.puedeDeclararNoSeCobro(reg.estado(), ahora()))
    }

    @Test fun `reinicio real del aparato - APARTADA cae, COMPROMETIDA se conserva y su espera vuelve a empezar, CERRADA queda`() {
        var reg = Registro().comprometer(ana).registro
        reg = r.apartar(reg, "o2", beto, ahora(), hora).registro
        reg = r.cobrada(reg, "o3", ana, faltaSubirla = false, ahora(), hora)
        pasan(5 * 24 * 60 * 60 * 1000L) // días encendida
        arranque = "boot-2"; ms = 500 // el contador monotónico empieza de nuevo
        val tras = r.normalizar(reg, ahora(), hora)
        assertNull(tras.cuentas["o2"])
        assertEquals(EstadoDeCuenta.COMPROMETIDA, tras.cuentas["o1"]?.estado)
        assertEquals(Instante("boot-2", 500), tras.cuentas["o1"]?.desde)
        assertEquals(EstadoDeCuenta.CERRADA, tras.cuentas["o3"]?.estado)
        assertFalse(r.puedeDeclararNoSeCobro(tras.cuentas["o1"], ahora()))
        pasan(NO_CONFIRMO_MS)
        assertTrue(r.puedeDeclararNoSeCobro(tras.cuentas["o1"], ahora()))
    }

    @Test fun `reiniciar sólo la app (mismo arranque) no reinicia la espera`() {
        val reg = Registro().comprometer(ana).registro
        pasan(NO_CONFIRMO_MS)
        // Releer del disco en el mismo arranque: el instante guardado sigue valiendo.
        assertTrue(r.puedeDeclararNoSeCobro(r.normalizar(reg, ahora(), hora).cuentas["o1"], ahora()))
    }

    @Test fun `CERRADA es final - ni el tiempo ni la subida confirmada la regresan a LIBRE (H1)`() {
        var reg = r.cobrada(Registro().comprometer(ana).registro, "o1", ana, faltaSubirla = true, ahora(), hora)
        assertEquals(Respuesta.Cerrada("Caja 2", true), reg.apartar(beto).respuesta)
        reg = r.cobrada(reg, "o1", ana, faltaSubirla = false, ahora(), hora) // la subida se confirmó
        pasan(3 * 24 * 60 * 60 * 1000L) // más que las 24 h de la copia
        assertEquals(EstadoDeCuenta.CERRADA, reg.estado()?.estado)
        assertFalse(reg.estado()!!.faltaSubirla)
        assertEquals(Respuesta.Cerrada("Caja 2", false), reg.apartar(beto).respuesta)
        assertEquals(Respuesta.Cerrada("Caja 2", false), reg.comprometer(beto).respuesta)
        // Un aviso repetido de la misma subida pendiente no vuelve a poner «falta subirla».
        assertFalse(r.cobrada(reg, "o1", ana, faltaSubirla = true, ahora(), hora).estado()!!.faltaSubirla)
    }

    @Test fun `CERRADA sin red bloquea siempre y en línea sólo si falta subirla`() {
        val enLinea = beto.copy(enLinea = true)
        val faltando = r.cobrada(Registro(), "o1", ana, faltaSubirla = true, ahora(), hora)
        assertEquals(Respuesta.Cerrada("Caja 2", true), faltando.apartar(enLinea).respuesta)
        assertEquals(Respuesta.Cerrada("Caja 2", true), faltando.comprometer(enLinea).respuesta)
        val subida = r.cobrada(faltando, "o1", ana, faltaSubirla = false, ahora(), hora)
        // Con internet y el saldo vivo del SERVIDOR: no se opone, y la cuenta sigue CERRADA para quien pregunte sin red.
        val enLineaPaso = subida.apartar(enLinea)
        assertEquals(Respuesta.Concedida, enLineaPaso.respuesta)
        assertEquals(subida, enLineaPaso.registro)
        assertEquals(Respuesta.Concedida, subida.comprometer(enLinea).respuesta)
        assertEquals(Respuesta.Cerrada("Caja 2", false), subida.apartar(beto).respuesta)
    }

    @Test fun `una lectura del SERVIDOR que la da cerrada escribe CERRADA y quita falta subirla`() {
        val libre = r.servidorLaCerro(Registro(), "o1", ahora(), hora)
        assertEquals(EstadoDeCuenta.CERRADA, libre.estado()?.estado)
        assertEquals(RegistroDeCuentas.SERVIDOR, libre.estado()?.por)
        val faltando = r.cobrada(Registro(), "o1", ana, faltaSubirla = true, ahora(), hora)
        val confirmada = r.servidorLaCerro(faltando, "o1", ahora(), hora).estado()!!
        assertFalse(confirmada.faltaSubirla)
        assertEquals("Caja 2", confirmada.caja)
    }

    @Test fun `otro cobro sobre una CERRADA conserva falta subirla si el anterior no subió`() {
        val vieja = r.cobrada(Registro(), "o1", ana, faltaSubirla = true, ahora(), hora)
        val otra = r.cobrada(vieja, "o1", beto, faltaSubirla = false, ahora(), hora).estado()!!
        assertTrue(otra.faltaSubirla)
    }

    @Test fun `el registro viaja al disco y regresa igual`() {
        var reg = Registro().comprometer(ana).registro
        reg = r.cobrada(reg, "o9", beto, faltaSubirla = true, ahora(), hora)
        reg = r.noSeCobro(r.comprometer(reg, "o5", ana.copy(intento = "i-x"), ahora(), hora).registro.also { pasan(NO_CONFIRMO_MS) },
            "o5", ahora(), hora).registro
        assertEquals(reg, RegistroDeCuentas.leer(RegistroDeCuentas.escribir(reg)))
        assertNull(RegistroDeCuentas.leer("{no es json"))
    }

    @Test fun `los intentos cancelados se podan a los 7 días`() {
        val reg = r.noSeCobro(Registro().comprometer(ana).registro.also { pasan(NO_CONFIRMO_MS) }, "o1", ahora(), hora).registro
        assertTrue("i-ana" in reg.cancelados)
        pasan(8 * 24 * 60 * 60 * 1000L)
        assertFalse("i-ana" in r.normalizar(reg, ahora(), hora).cancelados)
    }
}

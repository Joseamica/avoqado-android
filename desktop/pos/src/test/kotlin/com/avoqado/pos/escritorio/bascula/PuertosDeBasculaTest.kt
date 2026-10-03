package com.avoqado.pos.escritorio.bascula

import com.avoqado.pos.escritorio.impresion.DCB
import com.avoqado.pos.escritorio.impresion.F_BINARY
import com.avoqado.pos.escritorio.impresion.F_DSR_SENSITIVITY
import com.avoqado.pos.escritorio.impresion.F_DTR_CONTROL
import com.avoqado.pos.escritorio.impresion.F_INX
import com.avoqado.pos.escritorio.impresion.F_OUTX
import com.avoqado.pos.escritorio.impresion.F_OUTX_CTS_FLOW
import com.avoqado.pos.escritorio.impresion.F_OUTX_DSR_FLOW
import com.avoqado.pos.escritorio.impresion.F_PARITY
import com.avoqado.pos.escritorio.impresion.F_RTS_CONTROL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PuertosDeBasculaTest {
    private val ch340 = PuertoSerie("COM3", "\\Device\\Serial2", 0x1A86, 0x7523)
    private val ftdi = PuertoSerie("COM4", "\\Device\\VCP0", 0x0403, 0x6001)
    private val tarjetaMadre = PuertoSerie("COM1", "\\Device\\Serial0", null, null)
    private val bluetooth = PuertoSerie("COM5", "\\Device\\BthModem0", null, null)

    @Test fun `VID y PID de las claves del registro`() {
        assertEquals(0x1A86 to 0x7523, vidPidDe("VID_1A86&PID_7523"))
        assertEquals(0x0403 to 0x6001, vidPidDe("VID_0403+PID_6001+A50285BIA"))
        assertEquals(0x067B to 0x2303, vidPidDe("VID_067B&PID_2303&MI_00"))
        assertNull(vidPidDe("ROOT_HUB30"))
    }

    @Test fun `del registro salen los COM vivos con su VID PID, y sin VID PID los que no son USB`() {
        val registro = object : Registro {
            override fun presente(idDeInstancia: String) = true
            val claves = mapOf(
                "SYSTEM\\CurrentControlSet\\Enum\\USB" to listOf("VID_1A86&PID_7523", "ROOT_HUB30", "VID_046D&PID_C52B"),
                "SYSTEM\\CurrentControlSet\\Enum\\USB\\VID_1A86&PID_7523" to listOf("5&2b1"),
                "SYSTEM\\CurrentControlSet\\Enum\\USB\\VID_046D&PID_C52B" to listOf("6&aa"),   // un mouse: sin PortName
                "SYSTEM\\CurrentControlSet\\Enum\\FTDIBUS" to listOf("VID_0403+PID_6001+A50285BIA"),
                "SYSTEM\\CurrentControlSet\\Enum\\FTDIBUS\\VID_0403+PID_6001+A50285BIA" to listOf("0000"),
            )
            override fun subclaves(ruta: String) = claves[ruta].orEmpty()
            override fun texto(ruta: String, nombre: String) = when (ruta) {
                "SYSTEM\\CurrentControlSet\\Enum\\USB\\VID_1A86&PID_7523\\5&2b1\\Device Parameters" -> "COM3"
                "SYSTEM\\CurrentControlSet\\Enum\\FTDIBUS\\VID_0403+PID_6001+A50285BIA\\0000\\Device Parameters" -> "com4"
                else -> null
            }
            override fun valores(ruta: String) = mapOf(
                "\\Device\\Serial0" to "COM1", "\\Device\\Serial2" to "COM3", "\\Device\\VCP0" to "COM4", "\\Device\\BthModem0" to "COM5",
            )
        }
        assertEquals(listOf(tarjetaMadre, ch340, ftdi, bluetooth), puertosSerie(registro))
    }

    @Test fun `un solo puerto (sin contar Bluetooth) se usa aunque el perfil no traiga VID PID`() {
        assertEquals(EleccionDePuerto.Elegido(ch340), elegirPuerto(listOf(ch340, bluetooth), null, null))
        assertEquals(EleccionDePuerto.Elegido(tarjetaMadre), elegirPuerto(listOf(tarjetaMadre), null, null))
    }

    @Test fun `con VID PID se elige ese aunque haya otros`() {
        assertEquals(EleccionDePuerto.Elegido(ftdi), elegirPuerto(listOf(tarjetaMadre, ch340, ftdi), 0x0403, 0x6001))
    }

    @Test fun `nunca adivina - los mismos mensajes que Android`() {
        assertEquals(
            EleccionDePuerto.Falla("No se detectó una báscula serial (USB o COM). Revisa cable y adaptador."),
            elegirPuerto(listOf(bluetooth), null, null),
        )
        assertEquals(
            EleccionDePuerto.Falla("Hay más de una báscula compatible conectada; configura VID/PID."),
            elegirPuerto(listOf(ch340, ftdi), null, null),
        )
        assertEquals(
            EleccionDePuerto.Falla("La báscula configurada no está conectada."),
            elegirPuerto(listOf(ch340, ftdi), 0x067B, 0x2303),
        )
        assertEquals(
            EleccionDePuerto.Falla("Hay varios puertos seriales; configura VID/PID para elegir la báscula."),
            elegirPuerto(listOf(ch340, ftdi), 0x067B, null),
        )
    }

    /** El registro de una PC donde COM3 lo reclaman un CH340 y un FTDI; [presentes] dice cuáles están conectados AHORA. */
    private fun registroCom3(presentes: Set<String>) = object : Registro {
            override fun presente(idDeInstancia: String) = idDeInstancia in presentes
            override fun subclaves(ruta: String) = when (ruta) {
                "SYSTEM\\CurrentControlSet\\Enum\\USB" -> listOf("VID_1A86&PID_7523")
                "SYSTEM\\CurrentControlSet\\Enum\\USB\\VID_1A86&PID_7523" -> listOf("5&2b1")
                "SYSTEM\\CurrentControlSet\\Enum\\FTDIBUS" -> listOf("VID_0403+PID_6001+VIEJO")
                "SYSTEM\\CurrentControlSet\\Enum\\FTDIBUS\\VID_0403+PID_6001+VIEJO" -> listOf("0000")
                else -> emptyList()
            }
            override fun texto(ruta: String, nombre: String) = "COM3"   // los dos dicen COM3
            override fun valores(ruta: String) = mapOf("\\Device\\Serial2" to "COM3")
        }

    private val idCh340 = "USB\\VID_1A86&PID_7523\\5&2b1"
    private val idFtdi = "FTDIBUS\\VID_0403+PID_6001+VIEJO\\0000"

    @Test fun `un adaptador desconectado que conserva su COM no se cuenta`() {
        val com3 = puertosSerie(registroCom3(presentes = setOf(idCh340))).single()
        assertEquals(PuertoSerie("COM3", "\\Device\\Serial2", 0x1A86, 0x7523), com3)
        assertEquals(EleccionDePuerto.Falla("La báscula configurada no está conectada."), elegirPuerto(listOf(com3), 0x0403, 0x6001))
        val deLaTarjetaMadre = puertosSerie(registroCom3(presentes = emptySet())).single()
        assertNull(deLaTarjetaMadre.vendorId, "COM3 es ahora de la tarjeta madre: el VID/PID viejo no se le pega")
    }

    @Test fun `un COM reclamado por dos adaptadores conectados no se adivina`() {
        val com3 = puertosSerie(registroCom3(presentes = setOf(idCh340, idFtdi))).single()
        assertTrue(com3.ambiguo)
        assertNull(com3.vendorId)
        val falla = elegirPuerto(listOf(com3), 0x0403, 0x6001)
        assertTrue(falla is EleccionDePuerto.Falla && falla.mensaje.contains("COM3 registrado para dos adaptadores"))
        assertEquals(EleccionDePuerto.Elegido(com3), elegirPuerto(listOf(com3), null, null), "sin VID/PID, un único COM se usa como en Android")
    }

    @Test fun `dos adaptadores conectados del mismo modelo que dicen COM3 tambien son ambiguos`() {
        val registro = object : Registro {
            override fun presente(idDeInstancia: String) = true
            override fun subclaves(ruta: String) = when (ruta) {
                "SYSTEM\\CurrentControlSet\\Enum\\USB" -> listOf("VID_1A86&PID_7523")
                "SYSTEM\\CurrentControlSet\\Enum\\USB\\VID_1A86&PID_7523" -> listOf("5&2b1", "5&9c4")
                else -> emptyList()
            }
            override fun texto(ruta: String, nombre: String) = "COM3"
            override fun valores(ruta: String) = mapOf("\\Device\\Serial2" to "COM3")
        }
        val com3 = puertosSerie(registro).single()
        assertTrue(com3.ambiguo)
        assertTrue(elegirPuerto(listOf(com3), 0x1A86, 0x7523) is EleccionDePuerto.Falla)
    }

    @Test fun `solo no existe ese aparato cuenta como desconectado`() {
        assertEquals(false, conectadoSegunCodigo(CR_NO_SUCH_DEVNODE))
        assertEquals(true, conectadoSegunCodigo(0))       // CR_SUCCESS
        assertEquals(true, conectadoSegunCodigo(0x13))    // CR_FAILURE: no dice nada
    }

    @Test fun `la bascula va sin control de flujo y con DTR y RTS prendidos, aunque Windows tenga otro guardado`() {
        val dcb = DCB()
        dcb.bits = F_OUTX_CTS_FLOW or F_OUTX_DSR_FLOW or F_DSR_SENSITIVITY or F_OUTX or F_INX or (2 shl 4) or (2 shl 12)
        prepararDcbDeBascula(dcb, 9_600, 8, null, null)
        assertEquals(0, dcb.bits and (F_OUTX_CTS_FLOW or F_OUTX_DSR_FLOW or F_DSR_SENSITIVITY or F_OUTX or F_INX))
        assertEquals(1, (dcb.bits and F_DTR_CONTROL) shr 4)
        assertEquals(1, (dcb.bits and F_RTS_CONTROL) shr 12)
    }

    @Test fun `el DCB lleva bits, paridad y parada del perfil`() {
        val dcb = DCB()
        prepararDcbDeBascula(dcb, 9_600, 7, "even", 2)
        assertEquals(9_600, dcb.BaudRate)
        assertEquals(7.toByte(), dcb.ByteSize)
        assertEquals(2.toByte(), dcb.Parity)
        assertTrue(dcb.bits and F_PARITY != 0)
        assertTrue(dcb.bits and F_BINARY != 0)
        assertEquals(2.toByte(), dcb.StopBits)
        prepararDcbDeBascula(dcb, 115_200, 8, null, null)
        assertEquals(0.toByte(), dcb.Parity)
        assertTrue(dcb.bits and F_PARITY == 0)
        assertEquals(0.toByte(), dcb.StopBits)
    }

    @Test fun `el mensaje de apertura dice que hacer`() {
        assertTrue(mensajeDeApertura("COM3", 5).startsWith("Otro programa tiene abierto COM3"))
        assertTrue(mensajeDeApertura("COM3", 2).contains("ya no está conectado"))
    }
}

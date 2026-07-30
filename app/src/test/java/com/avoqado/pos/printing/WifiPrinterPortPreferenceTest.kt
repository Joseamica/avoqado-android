package com.avoqado.pos.printing

import com.avoqado.pos.printing.data.mergeResolvedWifiPrinter
import com.avoqado.pos.printing.data.model.DiscoveredPrinter
import com.avoqado.pos.printing.data.model.PrinterConnectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Una sola impresora física se anuncia en TRES tipos de servicio mDNS a la vez
 * (_printer=515/LPR, _ipp=631/IPP, _pdl-datastream=9100/raw) y cada anuncio
 * resuelve por separado. Sólo 9100 acepta ESC/POS crudo.
 *
 * Verificado en hardware (2026-07-29, Epson TM-m30III por Ethernet): el orden
 * de resolución es una CARRERA — esa vez ganó :631 y la impresora quedó listada
 * en un puerto que abre el socket y se traga los bytes sin imprimir.
 */
class WifiPrinterPortPreferenceTest {

    private fun wifi(address: String, port: Int, name: String = "EPSON TM-m30III") =
        DiscoveredPrinter(
            id = "${name}_$address",
            name = name,
            connectionType = PrinterConnectionType.WIFI,
            address = address,
            port = port,
        )

    @Test
    fun `una impresora nueva se agrega`() {
        val result = mergeResolvedWifiPrinter(emptyList(), wifi("192.168.100.220", 9100))
        assertEquals(listOf(wifi("192.168.100.220", 9100)), result)
    }

    @Test
    fun `el puerto crudo 9100 reemplaza a uno que llego primero en 631`() {
        val current = listOf(wifi("192.168.100.220", 631))
        val result = mergeResolvedWifiPrinter(current, wifi("192.168.100.220", 9100))
        assertEquals(1, result?.size)
        assertEquals(9100, result?.first()?.port)
    }

    @Test
    fun `el puerto crudo 9100 reemplaza a uno que llego primero en 515`() {
        val current = listOf(wifi("192.168.100.220", 515))
        val result = mergeResolvedWifiPrinter(current, wifi("192.168.100.220", 9100))
        assertEquals(9100, result?.first()?.port)
    }

    @Test
    fun `una vez en 9100 ningun otro anuncio la degrada`() {
        val current = listOf(wifi("192.168.100.220", 9100))
        assertNull(mergeResolvedWifiPrinter(current, wifi("192.168.100.220", 631)))
        assertNull(mergeResolvedWifiPrinter(current, wifi("192.168.100.220", 515)))
    }

    @Test
    fun `no se duplica la misma impresora en el mismo puerto`() {
        val current = listOf(wifi("192.168.100.220", 9100))
        assertNull(mergeResolvedWifiPrinter(current, wifi("192.168.100.220", 9100)))
    }

    @Test
    fun `dos impresoras distintas conviven`() {
        val current = listOf(wifi("192.168.100.220", 9100))
        val result = mergeResolvedWifiPrinter(
            current,
            wifi("192.168.100.3", 9100, name = "EPSON ET-2800 Series"),
        )
        assertEquals(2, result?.size)
    }

    /**
     * Una Bluetooth con la MISMA cadena en `address` (una MAC no choca con una
     * IP, pero el dedup nunca debe cruzar tipos de conexión).
     */
    @Test
    fun `el dedup no cruza tipos de conexion`() {
        val current = listOf(
            DiscoveredPrinter(
                id = "bt_192.168.100.220",
                name = "BT",
                connectionType = PrinterConnectionType.BLUETOOTH,
                address = "192.168.100.220",
            ),
        )
        val result = mergeResolvedWifiPrinter(current, wifi("192.168.100.220", 9100))
        assertEquals(2, result?.size)
    }
}

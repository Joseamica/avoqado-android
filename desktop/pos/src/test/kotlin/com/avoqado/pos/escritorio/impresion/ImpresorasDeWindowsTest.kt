package com.avoqado.pos.escritorio.impresion

import com.avoqado.pos.printing.data.model.PrinterConnectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ImpresorasDeWindowsTest {
    private fun cola(nombre: String, puerto: String = "USB001", driver: String = "Generic / Text Only", estado: Int = 0, atributos: Int = 0) =
        ColaInfo(nombre, puerto, driver, estado, atributos)

    @Test fun `la direccion va y vuelve, con dos puntos en el nombre de la cola`() {
        for (d in listOf(DestinoDeWindows.Cola("POS-80"), DestinoDeWindows.Cola("Caja: barra"), DestinoDeWindows.Com("COM3"), DestinoDeWindows.Com("COM12", 115200))) {
            assertEquals(d, leerDireccion(d.comoDireccion()))
        }
        assertEquals("usb:cola:POS-80", DestinoDeWindows.Cola("POS-80").comoDireccion())
        assertEquals("usb:com:COM3", DestinoDeWindows.Com("COM3").comoDireccion())
        assertEquals("usb:com:COM3:115200", DestinoDeWindows.Com("COM3", 115200).comoDireccion())
    }

    @Test fun `direcciones que no son de Windows dan null`() {
        for (a in listOf("usb:1208:514", "192.168.1.50", "internal", "usb:cola:", "usb:com:", "usb:com:LPT1", "usb:com:COM3:rapido", "usb:com:COM3:0", "usb:com:COM3:", "usb:com:COM3:9600:1")) {
            assertNull(leerDireccion(a), a)
        }
    }

    /**
     * #13 y #14: SIN velocidad en la dirección se respeta la que Windows tiene configurada en ese puerto (Administrador de
     * dispositivos › Puertos › Configuración); una velocidad escrita se conserva, también 9600. «Buscar» guarda sin velocidad.
     */
    @Test fun `una direccion COM sin velocidad no trae velocidad, y una explicita se conserva aunque sea 9600`() {
        assertNull((leerDireccion("usb:com:COM3") as DestinoDeWindows.Com).baudios)
        assertEquals(9600, (leerDireccion("usb:com:COM3:9600") as DestinoDeWindows.Com).baudios)
        assertEquals("usb:com:COM3:9600", leerDireccion("usb:com:COM3:9600")!!.comoDireccion())
        assertEquals("usb:com:COM3", leerDireccion("usb:com:COM3")!!.comoDireccion())
        val buscada = impresorasVisibles(emptyList(), listOf(PuertoInfo("COM3", "\\Device\\USBSER000"))).single()
        assertEquals("usb:com:COM3", buscada.address)
    }

    @Test fun `las colas virtuales no son impresoras de tickets`() {
        assertTrue(esColaVirtual(cola("Microsoft Print to PDF", puerto = "PORTPROMPT:", driver = "Microsoft Print To PDF")))
        assertTrue(esColaVirtual(cola("Microsoft XPS Document Writer", puerto = "PORTPROMPT:", driver = "Microsoft XPS Document Writer v4")))
        assertTrue(esColaVirtual(cola("OneNote (Desktop)", puerto = "nul:", driver = "Send to Microsoft OneNote 16 Driver")))
        assertTrue(esColaVirtual(cola("Fax", puerto = "SHRFAX:", driver = "Microsoft Shared Fax Driver")))
        assertFalse(esColaVirtual(cola("POS-80")))
        assertFalse(esColaVirtual(cola("EPSON TM-T20III Receipt", puerto = "TMUSB001", driver = "EPSON TM-T20III Receipt5")))
        assertFalse(esColaVirtual(cola("Caja", puerto = "COM3:")))
    }

    /** Medido en una Windows real (Get-Printer, 1-oct): la cola de OneNote de la tienda NO dice «OneNote» en el driver. */
    @Test fun `OneNote for Windows 10 tampoco es impresora de tickets`() {
        assertTrue(esColaVirtual(cola(
            "OneNote for Windows 10",
            puerto = "Microsoft.Office.OneNote_16001.14326.22094.0_x64__8wekyb3d8bbwe_microsoft.onenoteim",
            driver = "Microsoft Software Printer Driver",
        )))
        assertTrue(esColaVirtual(cola("Enviar a una app", puerto = "USB004", driver = "Microsoft Software Printer Driver")))  // el driver solo basta
        assertTrue(esColaVirtual(cola("Guardar", puerto = "C:\\Tickets\\salida.pdf")))                                       // el puerto solo basta
    }

    /** #16: el nombre de la cola lo escribe el usuario: «Caja XPS» en USB001 con driver de tickets es una impresora. */
    @Test fun `una termica con XPS, PDF o FAX en el nombre si se lista`() {
        val caja = cola("Caja XPS", puerto = "USB001", driver = "EPSON TM-T20III Receipt5")
        assertFalse(esColaVirtual(caja))
        assertFalse(esColaVirtual(cola("Fax de la oficina", puerto = "USB003")))
        assertFalse(esColaVirtual(cola("Tickets PDF", puerto = "TMUSB001", driver = "EPSON TM-T88V Receipt")))
        assertEquals(listOf("usb:cola:Caja XPS"), impresorasVisibles(listOf(caja), emptyList()).map { it.address })
    }

    @Test fun `motivo fuera de linea - dice QUE pasa y QUE hacer`() {
        assertNull(motivoFueraDeLinea(cola("POS-80")))
        assertEquals(                                                                                       // PRINTER_STATUS_OFFLINE
            "La impresora «POS-80» está fuera de línea en Windows. Revisa que esté encendida y que el cable esté conectado.",
            motivoFueraDeLinea(cola("POS-80", estado = 0x80)),
        )
        assertEquals(                                                                                       // PRINTER_ATTRIBUTE_WORK_OFFLINE: la marcó una persona
            "La impresora «POS-80» está marcada como «Usar impresora sin conexión» en Windows. Quita esa marca en Impresoras y escáneres.",
            motivoFueraDeLinea(cola("POS-80", atributos = 0x400)),
        )
        assertEquals(                                                                                       // PRINTER_STATUS_PAUSED
            "La impresora «POS-80» está en pausa en Windows. Abre Impresoras y escáneres, entra a la impresora y quita la pausa.",
            motivoFueraDeLinea(cola("POS-80", estado = 0x1)),
        )
        assertTrue(motivoFueraDeLinea(cola("POS-80", estado = 0x2))!!.contains("error"))                    // PRINTER_STATUS_ERROR
        assertNull(motivoFueraDeLinea(cola("POS-80", estado = 0x400)))                                      // PRINTER_STATUS_PRINTING: ocupada, no fuera
    }

    /** #2: sin papel NO bloquea: el pulso del cajón no usa papel (y la USB de Android tampoco revisa el papel). */
    @Test fun `sin papel no impide usar la impresora - el cajon tiene que abrir`() {
        assertNull(motivoFueraDeLinea(cola("POS-80", estado = 0x10)))                                       // PRINTER_STATUS_PAPER_OUT
    }

    /** #10: Windows no siempre los acompaña con PRINTER_STATUS_ERROR: cada uno bloquea SOLO, con su texto. */
    @Test fun `pide atencion, problema de papel y borrandose bloquean solos - ocupada e imprimiendo no`() {
        assertEquals(                                                                                       // PRINTER_STATUS_USER_INTERVENTION
            "La impresora «POS-80» pide atención en Windows. Revísala (papel, tapa o botones) y vuelve a intentar.",
            motivoFueraDeLinea(cola("POS-80", estado = 0x100000)),
        )
        assertEquals(                                                                                       // PRINTER_STATUS_PAPER_PROBLEM
            "La impresora «POS-80» tiene un problema con el papel. Revisa el rollo y que no esté atorado, y vuelve a intentar.",
            motivoFueraDeLinea(cola("POS-80", estado = 0x40)),
        )
        assertEquals(                                                                                       // PRINTER_STATUS_PENDING_DELETION
            "Windows está borrando la impresora «POS-80». Bórrala también de Avoqado y vuelve a buscarla.",
            motivoFueraDeLinea(cola("POS-80", estado = 0x4)),
        )
        assertNull(motivoFueraDeLinea(cola("POS-80", estado = 0x200)))                                      // PRINTER_STATUS_BUSY
        assertNull(motivoFueraDeLinea(cola("POS-80", estado = 0x400)))                                      // PRINTER_STATUS_PRINTING
    }

    @Test fun `la lista pone USB primero, quita las virtuales y marca Bluetooth`() {
        val lista = impresorasVisibles(
            colas = listOf(cola("Oficina", puerto = "WSD-123"), cola("Microsoft Print to PDF", puerto = "PORTPROMPT:", driver = "Microsoft Print To PDF"), cola("POS-80")),
            puertos = listOf(PuertoInfo("COM5", "\\Device\\BthModem0"), PuertoInfo("COM3", "\\Device\\USBSER000")),
        )
        assertEquals(listOf("usb:cola:POS-80", "usb:cola:Oficina", "usb:com:COM3", "usb:com:COM5"), lista.map { it.address })
        assertTrue(lista.all { it.connectionType == PrinterConnectionType.USB })
        assertEquals(lista.map { it.id }.toSet().size, lista.size)                                         // ids únicos
        assertTrue(lista.single { it.address == "usb:com:COM5" }.name.contains("Bluetooth"))
        assertTrue(lista.single { it.address == "usb:cola:POS-80" }.name.contains("POS-80"))
    }

    @Test fun `USB primero es cualquier puerto USB, tambien los de Epson`() {
        val lista = impresorasVisibles(
            colas = listOf(cola("Oficina", puerto = "WSD-123"), cola("Zebra", puerto = "USB001"), cola("Epson", puerto = "TMUSB001"), cola("Star", puerto = "ESDPRT001")),
            puertos = emptyList(),
        )
        assertEquals(listOf("usb:cola:Epson", "usb:cola:Star", "usb:cola:Zebra", "usb:cola:Oficina"), lista.map { it.address })
    }

    @Test fun `de SERIALCOMM solo se listan puertos COM`() {
        val lista = impresorasVisibles(
            colas = emptyList(),
            puertos = listOf(
                PuertoInfo("COM3", "\\Device\\USBSER000"),
                PuertoInfo("CNCA0", "\\Device\\com0com10"),
                PuertoInfo("LPT1", "\\Device\\Parallel0"),
                PuertoInfo("COM1234", "\\Device\\Serial9"),
                PuertoInfo("", "\\Device\\Serial8"),
            ),
        )
        assertEquals(listOf("usb:com:COM3"), lista.map { it.address })
    }

    @Test fun `dos colas identicas no se pisan`() {
        val lista = impresorasVisibles(listOf(cola("POS-80"), cola("POS-80 (Copia 1)", puerto = "USB002")), emptyList())
        assertEquals(2, lista.map { it.id }.toSet().size)
    }
}

package com.avoqado.pos.printing.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the USB printer transport: printer-likeness predicate and
 * the "usb:VID:PID" address identity persisted in SavedPrinter.address.
 * (The UsbManager/bulkTransfer paths need hardware and are covered by the manual
 * smoke with a real TM-m30III.)
 */
class UsbPrinterManagerTest {

    // MARK: - isLikelyUsbPrinter

    @Test
    fun `epson vendor id is a printer even with vendor-specific class`() {
        assertTrue(isLikelyUsbPrinter(vendorId = EPSON_VENDOR_ID, interfaceClasses = listOf(255)))
    }

    @Test
    fun `printer-class interface is a printer regardless of vendor`() {
        // 7 = USB_CLASS_PRINTER (e.g. Star, Bixolon, generic ESC/POS)
        assertTrue(isLikelyUsbPrinter(vendorId = 1305, interfaceClasses = listOf(7)))
    }

    @Test
    fun `printer-class among multiple interfaces still matches`() {
        assertTrue(isLikelyUsbPrinter(vendorId = 1305, interfaceClasses = listOf(2, 10, 7)))
    }

    @Test
    fun `non-printer devices are excluded`() {
        // e.g. a USB keyboard (class 3 HID) or mass storage (8)
        assertFalse(isLikelyUsbPrinter(vendorId = 1133, interfaceClasses = listOf(3)))
        assertFalse(isLikelyUsbPrinter(vendorId = 2316, interfaceClasses = listOf(8)))
    }

    // MARK: - usb address round-trip

    @Test
    fun `usbAddress and parseUsbAddress round-trip`() {
        val address = usbAddress(vendorId = 1208, productId = 514)
        assertEquals("usb:1208:514", address)
        assertEquals(1208 to 514, parseUsbAddress(address))
    }

    @Test
    fun `parseUsbAddress rejects malformed addresses`() {
        assertNull(parseUsbAddress("192.168.1.50"))          // a WiFi address
        assertNull(parseUsbAddress("AA:BB:CC:DD:EE:FF"))      // a BT MAC (6 parts)
        assertNull(parseUsbAddress("usb:abc:514"))            // non-numeric VID
        assertNull(parseUsbAddress("usb:1208"))               // missing PID
        assertNull(parseUsbAddress(""))
    }
}

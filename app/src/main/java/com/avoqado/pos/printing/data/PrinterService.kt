package com.avoqado.pos.printing.data

import android.util.Log
import com.avoqado.pos.printing.data.model.PrinterDevice
import com.avoqado.pos.printing.data.model.ReceiptData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrinterService @Inject constructor() {

    private val _connectedPrinter = MutableStateFlow<PrinterDevice?>(null)
    val connectedPrinter: StateFlow<PrinterDevice?> = _connectedPrinter.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredPrinters = MutableStateFlow<List<PrinterDevice>>(emptyList())
    val discoveredPrinters: StateFlow<List<PrinterDevice>> = _discoveredPrinters.asStateFlow()

    fun startScan() {
        _isScanning.value = true
        Log.d("🖨", "Scanning for BT printers...")
        // TODO: Implement Bluetooth printer discovery
        _isScanning.value = false
    }

    fun connect(printer: PrinterDevice) {
        Log.d("🖨", "Connecting to printer: ${printer.name}")
        // TODO: Implement BT printer connection
        _connectedPrinter.value = printer.copy(isConnected = true)
    }

    fun disconnect() {
        _connectedPrinter.value = null
    }

    fun printReceipt(receipt: ReceiptData) {
        Log.d("🖨", "Printing receipt for order: ${receipt.orderNumber}")
        // TODO: Generate ESC/POS commands and send via BT
    }
}

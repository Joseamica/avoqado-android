package com.avoqado.pos.printing

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avoqado.pos.printing.data.AreaTicketPdfGenerator
import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.model.AreaTicketData
import com.avoqado.pos.printing.data.model.ReceiptItem
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AreaTicketPdfGeneratorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun generatedPdfRendersScannableCode128() {
        assertScannable(
            symbology = ESCPOSPrinter.BarcodeSymbology.CODE128_C,
            barcodeFormat = Barcode.FORMAT_CODE_128,
            fileName = "area-ticket-code128.pdf",
        )
    }

    @Test
    fun generatedPdfRendersScannableCode39() {
        assertScannable(
            symbology = ESCPOSPrinter.BarcodeSymbology.CODE39,
            barcodeFormat = Barcode.FORMAT_CODE_39,
            fileName = "area-ticket-code39.pdf",
        )
    }

    private fun assertScannable(
        symbology: ESCPOSPrinter.BarcodeSymbology,
        barcodeFormat: Int,
        fileName: String,
    ) {
        val code = "9340048086"
        val bytes = AreaTicketPdfGenerator().generate(
            ticket = AreaTicketData(
                areaTicketCode = code,
                areaName = "Cremería",
                items = listOf(
                    ReceiptItem(
                        name = "QA Jamón por kg",
                        quantity = 1,
                        unitPrice = 24000,
                        totalPrice = 10440,
                        weightSummary = "0.435 kg × \$240.00/kg",
                    ),
                ),
                totalCents = 10440,
                venueName = "Restaurante El Atole",
                holdsProduct = true,
            ),
            symbology = symbology,
        )

        assertTrue(bytes.copyOfRange(0, 5).decodeToString().startsWith("%PDF"))

        val file = File(context.cacheDir, fileName)
        file.writeBytes(bytes)
        val bitmap = renderFirstPage(file, scale = 3)
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(barcodeFormat)
                .build(),
        )
        try {
            val barcodes = Tasks.await(
                scanner.process(InputImage.fromBitmap(bitmap, 0)),
                10,
                TimeUnit.SECONDS,
            )
            assertEquals(code, barcodes.firstOrNull()?.rawValue)
        } finally {
            scanner.close()
            bitmap.recycle()
            if (InstrumentationRegistry.getArguments().getString("keepPdf") != "true") {
                file.delete()
            }
        }
    }

    private fun renderFirstPage(file: File, scale: Int): Bitmap {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        val page = renderer.openPage(0)
        return try {
            Bitmap.createBitmap(
                page.width * scale,
                page.height * scale,
                Bitmap.Config.ARGB_8888,
            ).also { bitmap ->
                page.render(
                    bitmap,
                    null,
                    Matrix().apply { setScale(scale.toFloat(), scale.toFloat()) },
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                )
            }
        } finally {
            page.close()
            renderer.close()
            descriptor.close()
        }
    }
}

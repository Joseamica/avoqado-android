package com.avoqado.pos.printing.data

import android.content.Context
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Completa el ticket de venta con la identidad del negocio (logo + encabezado
 * fiscal estilo SoftRestaurant) y la firma "Powered by Avoqado" (founder,
 * 2026-09-01). Vive en el embudo de PrinterService.printReceipt — así los
 * ViewModels que arman ReceiptData no cargan este plomería, y TODOS los
 * recibos (mesas, cobro rápido, transacciones, auto-print) salen iguales.
 *
 * Todo es cache-first: el receiptInfo viene del cache de settings y el logo
 * del disco — imprimir sin red imprime exactamente lo mismo.
 */
@Singleton
class ReceiptBranding @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val receiptLogoCache: ReceiptLogoCache,
    private val secureStorage: SecureStorage,
) {
    fun decorate(receipt: ReceiptData, paperWidth: PaperWidth): ReceiptData {
        val info = tpvSettingsRepository.receiptInfo.value
        val venueId = secureStorage.venueId
        // El campo del que llama SIEMPRE gana: si un ViewModel ya puso una
        // dirección o un ráster, aquí no se pisa.
        val logoRaster = receipt.venueLogoRaster ?: venueId
            ?.let { receiptLogoCache.cachedBitmap(it) }
            ?.let { RasterImages.toMonoRaster(it, targetWidthDots = paperWidth.dots * 3 / 5) }
        return receipt.copy(
            venueLegalName = receipt.venueLegalName ?: info?.legalName,
            venueRfc = receipt.venueRfc ?: info?.rfc,
            venueLugarExpedicion = receipt.venueLugarExpedicion ?: info?.lugarExpedicion,
            venueAddress = receipt.venueAddress ?: info?.addressLine,
            venuePhone = receipt.venuePhone ?: info?.phone,
            venueLogoRaster = logoRaster,
            poweredByAvoqadoRaster = receipt.poweredByAvoqadoRaster
                ?: RasterImages.avoqadoMark(context, widthDots = AVOQADO_MARK_WIDTH_DOTS),
        )
    }

    companion object {
        /** ~11 mm a 203 dpi: firma, no protagonista. */
        const val AVOQADO_MARK_WIDTH_DOTS = 88
    }
}

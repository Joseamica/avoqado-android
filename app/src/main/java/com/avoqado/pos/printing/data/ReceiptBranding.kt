package com.avoqado.pos.printing.data

import android.content.Context
import android.util.Log
import com.avoqado.pos.BuildConfig
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.printing.data.model.MonoRaster
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.receiptlayout.CanonicalLayout
import com.avoqado.pos.printing.receiptlayout.ImageRef
import com.avoqado.pos.printing.receiptlayout.ReceiptBlock
import com.avoqado.pos.printing.receiptlayout.ReceiptInput
import com.avoqado.pos.printing.receiptlayout.ReceiptInputMapper
import com.avoqado.pos.printing.receiptlayout.ReceiptLayoutParser
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class ReceiptPlan(
    val blocks: List<ReceiptBlock>,
    val input: ReceiptInput,
    val rasterFor: (ImageRef, Int) -> MonoRaster?,
    val usedFallback: Boolean,
    val dropped: Int,
)

/**
 * Arma TODO lo que el ticket de venta necesita para imprimirse: la receta del negocio (o la
 * canónica embebida), la venta en el formato del intérprete y las imágenes. Vive en el embudo de
 * `PrinterService.printReceipt`: mesas, cobro rápido, transacciones y auto-print salen iguales.
 *
 * Todo es cache-first: la receta y el `receiptInfo` vienen del caché de settings por venue y el
 * logo del disco — imprimir sin red imprime exactamente lo mismo.
 */
@Singleton
class ReceiptBranding @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val receiptLogoCache: ReceiptLogoCache,
    private val secureStorage: SecureStorage,
) {
    suspend fun plan(receipt: ReceiptData, paperWidth: PaperWidth): ReceiptPlan {
        val venueId = secureStorage.venueId
        // 🔴 Receta y emisores de la sucursal ACTIVA, leídos del disco en pareja (D18): nunca se mezclan
        // con otra sucursal ni dependen de que en este proceso ya haya corrido un refresh.
        val ticket = venueId?.let { tpvSettingsRepository.receiptTicketFor(it) }
        val payload = ticket?.layout
        // Un schemaVersion que esta app no conoce se trata como receta ilegible: canónica embebida.
        val layout = ReceiptLayoutParser.effective(payload?.takeIf { it.schemaVersion == CanonicalLayout.SCHEMA_VERSION }?.blocks)
        if (payload != null && (layout.usedFallback || layout.dropped > 0)) {
            Log.w(TAG, "Receta del ticket rev=${payload.revision}: descartados=${layout.dropped}, canónica=${layout.usedFallback}")
        }
        // D15: el logo se resuelve ANTES de interpretar, al tamaño que pide la receta. Sin uno que de
        // verdad quepa en el papel, la venta viaja sin logo: ni imagen NI el salto que la acompaña.
        val logoRaster = printableLogo(layout.blocks, paperWidth) { dots ->
            receipt.venueLogoRaster
                ?: venueId?.let { receiptLogoCache.cachedBitmap(it) }?.let { RasterImages.toMonoRaster(it, targetWidthDots = dots) }
        }
        val input = ReceiptInputMapper.map(
            receipt = receipt,
            info = ticket?.info,
            hasLogo = logoRaster != null,
            timezone = VenueTimeZone.zoneId().id,
            appVersion = BuildConfig.VERSION_NAME,
        )
        val rasterFor: (ImageRef, Int) -> MonoRaster? = { ref, widthPct ->
            when (ref) {
                ImageRef.LOGO -> logoRaster
                ImageRef.AVOQADO_MARK ->
                    receipt.poweredByAvoqadoRaster ?: RasterImages.avoqadoMark(context, widthDots = paperWidth.dots * widthPct / 100)
            }
        }
        return ReceiptPlan(layout.blocks, input, rasterFor, layout.usedFallback, layout.dropped)
    }

    private companion object {
        const val TAG = "ReceiptBranding"
    }
}

/**
 * D15: el logo que DE VERDAD se puede imprimir con esta receta y este papel, o null. Pura (sin Bitmap
 * ni Context) para poder probar lo que decide `plan`: sin bloque de logo no se carga ninguna imagen;
 * se pide al ancho que dice la receta; un ráster que no cabe en el papel es lo mismo que no tener logo.
 */
internal fun printableLogo(blocks: List<ReceiptBlock>, paperWidth: PaperWidth, load: (targetWidthDots: Int) -> MonoRaster?): MonoRaster? {
    val block = blocks.firstNotNullOfOrNull { it as? ReceiptBlock.Logo } ?: return null
    return load(paperWidth.dots * block.size.widthPct / 100)?.takeIf { it.widthDots <= paperWidth.dots }
}

package com.avoqado.pos.inventory.waste

import com.avoqado.pos.inventory.waste.data.CatalogoDeMerma
import com.avoqado.pos.inventory.waste.data.WasteCatalogEntity

/**
 * El catálogo sin red: lo guardado por venue, y lo que contesta el servidor cuando se le pregunta
 * por el plan o se le pide bajar el catálogo. Apunta cuántas veces se le preguntó.
 */
class CatalogoDeMermaFalso : CatalogoDeMerma {
    var porVenue: Map<String, List<WasteCatalogEntity>> = emptyMap()

    /** `true` = el plan incluye la merma · `false` = 403 con `featureCode` · `null` = no se supo. */
    var plan: Boolean? = true
    var refrescoExitoso = true

    /** Lo que el servidor «manda» al refrescar: por defecto, lo mismo que ya hay, con fecha nueva. */
    var alRefrescar: (venueId: String, ahora: Long) -> Unit = { venueId, ahora ->
        porVenue = porVenue + (venueId to porVenue[venueId].orEmpty().map { it.copy(actualizadoEn = ahora) })
    }

    var consultasDePlan = 0
    var refrescos = 0

    override suspend fun catalogo(venueId: String) = porVenue[venueId].orEmpty()

    override suspend fun catalogoActualizadoEn(venueId: String) = porVenue[venueId]?.maxOfOrNull { it.actualizadoEn }

    override suspend fun refrescarCatalogo(venueId: String, ahora: Long): Boolean {
        refrescos++
        if (refrescoExitoso) alRefrescar(venueId, ahora)
        return refrescoExitoso
    }

    override suspend fun consultarPlan(venueId: String): Boolean? {
        consultasDePlan++
        return plan
    }
}

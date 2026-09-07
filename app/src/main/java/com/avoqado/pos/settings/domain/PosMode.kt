package com.avoqado.pos.settings.domain

/**
 * Modo de operación del DISPOSITIVO (perfil, como los "modos" de Square):
 * UN solo selector — antes convivían dos sistemas (PosMode Retail/Restaurante
 * + VenueMode Estándar/Reservas legacy) desincronizados y con "Modo" pintado
 * dos veces en el menú Más.
 *
 * 🔴 `key` es la llave PERSISTIDA (aparato y migración del VenueMode viejo) y
 * NO se toca: renombrar la etiqueta no puede mover a nadie de modo.
 */
enum class PosMode(val key: String, val displayName: String, val description: String) {
    RETAIL("retail", "Mostrador", "Cobro en el mostrador — tiendas, cafeterías y barras"),
    RESTAURANT("restaurant", "Mesas", "Servicio en mesa — cuentas por mesa y comandas"),
    RESERVATIONS("reservations", "Citas", "Agenda — citas y clases"),
}

/**
 * El giro del negocio (`Venue.type`) **sugiere** un modo; nunca lo impone.
 *
 * 🔴 Sugerir y no imponer no es cautela de más: Testarudo tiene giro `RESTAURANT`
 * en producción y opera en MOSTRADOR. Si el giro decidiera el modo, su siguiente
 * tablet abriría en Mesas y el cajero se encontraría un plano de mesas que ese
 * negocio no usa. La etiqueta informa; el negocio elige.
 *
 * Devuelve `null` cuando el giro no dice nada útil (`OTHER`, ausente o desconocido)
 * — 61 de 96 venues activos están en `OTHER`, y marcar "sugerido para tu giro"
 * sobre un giro que literalmente es "Otro" sería una etiqueta que miente.
 */
fun modoSugeridoPorGiro(venueType: String?): PosMode? = when (venueType?.trim()?.uppercase()) {
    // Come en la mesa y la cuenta se abre por mesa.
    "RESTAURANT", "BAR", "HOTEL_RESTAURANT", "NIGHTCLUB" -> PosMode.RESTAURANT
    // Se agenda antes de llegar.
    "SALON", "SPA", "FITNESS", "FITNESS_STUDIO", "CLINIC", "VETERINARY" -> PosMode.RESERVATIONS
    // Se paga en la caja y el cliente se va. Incluye cafetería y comida rápida
    // a propósito: son mostrador aunque el giro suene a restaurante.
    "CAFE", "BAKERY", "FAST_FOOD", "FOOD_TRUCK", "CATERING", "CLOUD_KITCHEN",
    "RETAIL_STORE", "JEWELRY", "CLOTHING", "ELECTRONICS", "PHARMACY",
    "CONVENIENCE_STORE", "SUPERMARKET", "LIQUOR_STORE", "FURNITURE", "HARDWARE",
    "BOOKSTORE", "PET_STORE", "TELECOMUNICACIONES", "AUTO_SERVICE", "LAUNDRY",
    "REPAIR_SHOP", "CINEMA", "ARCADE", "BOWLING",
    -> PosMode.RETAIL
    else -> null
}

package com.avoqado.pos.settings.domain

/**
 * Modo de operación del DISPOSITIVO (perfil, como los "modos" de Square):
 * UN solo selector — antes convivían dos sistemas (PosMode Retail/Restaurante
 * + VenueMode Estándar/Reservas legacy) desincronizados y con "Modo" pintado
 * dos veces en el menú Más.
 */
enum class PosMode(val key: String, val displayName: String, val description: String) {
    RETAIL("retail", "Retail", "Comercio modo — punto de venta para tiendas"),
    RESTAURANT("restaurant", "Restaurante", "Servicio de mesa — gestión de mesas y pedidos"),
    RESERVATIONS("reservations", "Reservas", "Calendario — citas y clases"),
}

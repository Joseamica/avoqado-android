package com.avoqado.pos.settings.domain

enum class PosMode(val key: String, val displayName: String, val description: String) {
    RETAIL("retail", "Retail", "Comercio modo — punto de venta para tiendas"),
    RESTAURANT("restaurant", "Restaurante", "Servicio de mesa — gestión de mesas y pedidos"),
}

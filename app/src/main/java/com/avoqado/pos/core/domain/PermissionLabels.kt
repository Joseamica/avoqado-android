package com.avoqado.pos.core.domain

/**
 * Permiso técnico → cómo se lo decimos a un mesero.
 *
 * 🔴 Espejo EXACTO de `PermissionLabels.swift` en avoqado-ios. Si agregas uno
 * aquí, agrégalo allá en el MISMO trabajo, con el mismo texto en español.
 *
 * Nunca enseñes la string cruda ("orders:merge") en pantalla: el que está
 * enfrente no sabe qué es un permiso, sabe qué estaba tratando de hacer.
 */
object PermissionLabels {
    private val LABELS = mapOf(
        "orders:merge" to "fusionar cuentas",
        "orders:cancel" to "cancelar la cuenta",
        "orders:comp" to "dar una cortesía",
        "orders:void" to "anular artículos",
        "orders:update" to "modificar la cuenta",
        "orders:create" to "abrir una cuenta",
        "payments:refund" to "hacer un reembolso",
        "payments:create" to "cobrar",
        "discounts:apply" to "aplicar un descuento",
        "shifts:close" to "cerrar el turno",
        "tables:manage-all" to "modificar mesas de otro mesero",
    )

    const val FALLBACK = "esta acción"

    fun of(permission: String): String = LABELS[permission] ?: FALLBACK
}

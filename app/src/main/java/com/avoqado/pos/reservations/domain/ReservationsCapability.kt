package com.avoqado.pos.reservations.domain

data class ReservationsCapability(
    val canRead: Boolean,
    val canCreate: Boolean,
    val canUpdate: Boolean,
    val canCancel: Boolean,
) {
    val any: Boolean get() = canRead || canCreate || canUpdate || canCancel

    companion object {
        fun fromPermissions(perms: List<String>): ReservationsCapability {
            val set = perms.toSet()
            val wildcard = "*" in set || "reservations:*" in set
            return ReservationsCapability(
                canRead = wildcard || "reservations:read" in set,
                canCreate = wildcard || "reservations:create" in set,
                canUpdate = wildcard || "reservations:update" in set,
                canCancel = wildcard || "reservations:cancel" in set,
            )
        }
    }
}

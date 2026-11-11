package com.avoqado.pos.cashdrawer.domain

import com.avoqado.pos.cashdrawer.presentation.ROLES_RETIRAR

/** Cómo llega esta persona al retiro de efectivo (retiro con gerente). */
enum class AccesoAlRetiro { DIRECTO, CON_PIN, SIN_RED, SOLO_GERENTE }

fun accesoAlRetiro(puedeRetirar: Boolean, pinDeGerenteActivo: Boolean, enLinea: Boolean): AccesoAlRetiro = when {
    puedeRetirar -> AccesoAlRetiro.DIRECTO
    pinDeGerenteActivo -> if (enLinea) AccesoAlRetiro.CON_PIN else AccesoAlRetiro.SIN_RED
    else -> AccesoAlRetiro.SOLO_GERENTE
}

/**
 * Con la lista vigente (la calculó el SERVIDOR) manda ella, también vacía: un negocio que le quitó todo a un rol.
 * Con una lista de origen desconocido (sesión de antes del cambio) vale la lista o el rol: un dueño no se queda sin retiro.
 */
fun puedeRetirar(listaVigente: Boolean, tieneElPermiso: Boolean, rol: String?): Boolean =
    if (listaVigente) tieneElPermiso else (tieneElPermiso || rol in ROLES_RETIRAR)

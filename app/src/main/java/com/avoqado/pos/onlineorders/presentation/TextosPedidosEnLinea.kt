package com.avoqado.pos.onlineorders.presentation

/** Anexo A del spec (es-MX, literales). iOS usa los mismos textos. */
object TextosPedidosEnLinea {
    const val MENU = "Pedidos en línea"

    const val SECCION_POR_ACEPTAR = "Por aceptar"
    const val SECCION_EN_PREPARACION = "En preparación"
    const val SECCION_LISTOS = "Listos"
    const val SECCION_HOY = "Hoy"

    const val ACEPTAR = "Aceptar"
    const val RECHAZAR = "Rechazar"
    const val LISTO = "Listo"
    const val COBRAR = "Cobrar"
    const val ENTREGADO = "Entregado"

    const val PARA_RECOGER = "Para recoger"
    const val PAGADO = "Pagado"
    fun cobrarAlRecoger(monto: String) = "Cobrar al recoger $monto"

    const val MOTIVO_SIN_INGREDIENTES = "Sin ingredientes"
    const val MOTIVO_CERRADO = "Cerrado"
    const val MOTIVO_MUY_OCUPADOS = "Muy ocupados"
    const val MOTIVO_OTRO = "Otro"

    /** Texto -> código que espera el servidor. */
    val MOTIVOS: List<Pair<String, String>> = listOf(
        MOTIVO_SIN_INGREDIENTES to "OUT_OF_ITEMS",
        MOTIVO_CERRADO to "CLOSED",
        MOTIVO_MUY_OCUPADOS to "TOO_BUSY",
        MOTIVO_OTRO to "OTHER",
    )

    const val SIN_CONEXION = "Sin conexión: no llegan pedidos nuevos"
    const val REINTENTA = "Reintenta cuando vuelva la conexión"
    const val YA_NO_DISPONIBLE = "Ese pedido ya no está disponible"

    val MINUTOS_ACEPTAR: List<Int> = listOf(10, 15, 20, 30)
    val MINUTOS_PAUSA: List<Int> = listOf(15, 30, 60)
    const val REANUDAR = "Reanudar"
}

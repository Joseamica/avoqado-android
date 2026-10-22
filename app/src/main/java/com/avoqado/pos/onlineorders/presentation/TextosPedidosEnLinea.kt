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

    // --- Textos de la pantalla que el Anexo A no fija (iOS debe copiarlos tal cual) ---
    const val SE_MUESTRAN_ULTIMOS = "Se muestran los últimos 50"
    const val CARGAR_MAS = "Cargar más"
    // Task A4 — texto nuevo fuera del Anexo A: iOS debe copiarlo igual.
    // Líneas del encabezado de la comanda en papel (iOS debe copiarlas igual).
    fun listoALas(hora: String) = "Listo a las $hora"
    fun notaDelPedido(nota: String) = "Nota: $nota"
    const val REIMPRIMIR = "Reimprimir"
    const val REIMPRESA = "Comanda enviada a la impresora"
    const val REIMPRESION_FALLO = "No se pudo imprimir la comanda"
    // Task A5 — textos nuevos fuera del Anexo A: iOS debe copiarlos igual.
    const val COBRO_EN_EFECTIVO_GUARDADO = "Cobro en efectivo guardado: se sube al volver la conexión"
    const val COBRO_EN_EFECTIVO_POR_REVISAR =
        "Este pedido tiene un cobro en efectivo por revisar: un gerente lo resuelve en Operaciones por revisar"
    const val SALDO_NO_LEIDO = "No se pudo leer el saldo del pedido. Intenta de nuevo."
    // Los mismos textos que el aviso de las mesas (`TableOrderScreen`): un cobro con tarjeta de OTRA venta que sí pasó.
    const val COBRO_ANTERIOR_TITULO = "Cobro anterior resuelto"
    fun cobroAnteriorResuelto(mensaje: String) = "$mensaje. Búscala en Ventas si necesitas dar el recibo."
    const val NO_SE_PUDO_LEER = "No se pudo leer la bandeja. Reintentando…"
    const val SIN_PEDIDOS = "No hay pedidos por ahora"
    const val PAUSAR_TIENDA = "Pausar pedidos"
    const val PAUSAR_TITULO = "Pausar pedidos en línea"
    const val PAUSAR_AYUDA = "La tienda deja de recibir pedidos nuevos durante este tiempo."
    fun pausadaPor(minutos: Int) = "Pedidos en pausa por $minutos min"
    const val PAUSA_REANUDADA = "Pedidos reanudados"
    const val ACEPTAR_TITULO = "¿En cuántos minutos estará listo?"
    fun minutosEtiqueta(minutos: Int) = "$minutos min"
    const val RECHAZAR_TITULO = "¿Por qué lo rechazas?"
    const val CANCELAR = "Cancelar pedido"
    const val CANCELAR_TITULO = "¿Cancelar este pedido?"
    const val CANCELAR_CONFIRMA = "Cancelar pedido"
    const val CANCELAR_VOLVER = "Volver"
    const val CERRAR = "Cerrar"
    const val ENTENDIDO = "Entendido"
    const val TEASER_TITULO = "Pedidos en línea"
    const val TEASER_CUERPO = "Recibe los pedidos de la tienda web de tu negocio y acéptalos desde aquí."
    fun teaserPlan(plan: String) = "Incluido en el Plan $plan"
    fun pedidoNumero(folio: String) = "Pedido #$folio"
}

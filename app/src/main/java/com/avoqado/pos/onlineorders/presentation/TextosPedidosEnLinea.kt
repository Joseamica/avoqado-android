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

    // Pedidos en línea fase 2 (tarjeta): iOS debe copiarlos igual.
    const val TARJETA_SE_COBRA_AL_ACEPTAR = "Con tarjeta: se cobra al aceptar"
    const val CONFIRMANDO_PAGO = "Confirmando el pago…"
    const val PAGADO_CON_TARJETA = "Pagado con tarjeta"
    const val TARJETA_COBRADA_DEVUELVELA = "Tarjeta cobrada: devuélvela desde el panel"
    const val NO_SE_COBRO_LA_TARJETA = "No se cobró la tarjeta"
    const val LIBERANDO_LA_TARJETA = "Liberando la tarjeta…"
    const val COMANDA_PAGADO_CON_TARJETA = "PAGADO CON TARJETA"
    const val MOTIVO_PAGO_FALLIDO = "No se pudo cobrar la tarjeta"

    /** Motivo recibido (`rejectReason` del servidor) en texto; null si no hay o es un código que no conocemos. */
    fun textoDelMotivo(rejectReason: String?): String? = when (rejectReason) {
        "PAYMENT_FAILED" -> MOTIVO_PAGO_FALLIDO
        "OUT_OF_ITEMS" -> MOTIVO_SIN_INGREDIENTES
        "CLOSED" -> MOTIVO_CERRADO
        "TOO_BUSY" -> MOTIVO_MUY_OCUPADOS
        "OTHER" -> MOTIVO_OTRO
        else -> null
    }

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
    const val SOLO_EFECTIVO_SIN_SALDO = "Saldo sin actualizar: sólo efectivo. Para tarjeta, cierra este cobro y vuelve a abrirlo con conexión."
    const val SALDO_NO_LEIDO = "No se pudo leer el saldo del pedido. Intenta de nuevo."
    // Codex r2 #1 (P1 folio-efectivo, 11-nov): una tarjeta en duda de la orden cambió mientras «Cobrar» esperaba. iOS debe copiarlo igual.
    const val TARJETA_CAMBIO = "El cobro con tarjeta de este pedido acaba de cambiar. Intenta de nuevo."
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

    // Rappi 2b — textos nuevos: iOS debe copiarlos igual.
    const val SECCION_REPARTO_POR_COBRAR = "Rappi · por cobrar"
    /** «Venta $175 · Propina $20 · Rappi $10»; la propina y la parte de la plataforma se omiten cuando son null (valen 0). */
    fun desgloseDeReparto(venta: String, propina: String?, plataforma: String, terceros: String?) =
        listOfNotNull("Venta $venta", propina?.let { "Propina $it" }, terceros?.let { "$plataforma $it" }).joinToString(" · ")
    fun totalACobrar(monto: String) = "Total a cobrar $monto"
    fun notaDeReparto(plataforma: String, folio: String) = "$plataforma #$folio"
    const val REPARTO_SOLO_EFECTIVO = "Los pedidos de Rappi con tu repartidor se cobran en efectivo."
    const val REPARTO_VACIO = "No hay pedidos de Rappi por cobrar."
    fun mostrandoDe(n: Int, total: Int) = "Mostrando $n de $total"

    // Tienda híbrida (etapa 1, A2) — textos de los mockups v2 §3 y de las reglas de UX: iOS debe copiarlos igual.
    const val SECCION_POR_PAGAR = "Por pagar"
    const val SECCION_POR_ENTREGAR = "Por entregar"
    const val BUSCAR_FOLIO = "Buscar folio o nombre"
    fun folio(folio: String) = "Folio $folio"
    fun yaPago(pagado: String, total: String) = "Ya pagó $pagado de $total"
    fun falta(monto: String) = "Falta $monto"
    fun venceEn(minutos: Int) = "Vence en $minutos min"
    fun venceALas(hora: String) = "Vence a las $hora"
    fun yaVencio(hora: String) = "Venció a las $hora"
    fun pagado(monto: String) = "Pagado $monto"
    /** «W8M2C entregado a Leo»; sin nombre, «W8M2C entregado». */
    fun folioEntregado(folio: String, nombre: String?) =
        nombre?.trim()?.takeIf { it.isNotEmpty() }?.let { "$folio entregado a $it" } ?: "$folio entregado"
    /** El único aviso sin red de los folios (reemplaza al corto de la bandeja cuando hay folios). */
    const val SIN_CONEXION_FOLIOS =
        "Sin conexión: no llegan pedidos nuevos. El efectivo se guarda en el aparato y se sube solo. " +
            "«Cancelar», «Listo» y «Entregado» vuelven con la conexión."
    fun deHaceMin(minutos: Long) = if (minutos <= 0) "(de hace un momento)" else "(de hace $minutos min)"
    const val FOLIOS_ERROR = "No se pudieron leer los folios"
    const val FOLIOS_LEYENDO = "Leyendo folios…"
    fun sinResultadosEntre(cargados: Int) = "Sin resultados entre los $cargados cargados"
    const val SIN_RESULTADOS_ENTRE_AYUDA = "Puede haber más: toca «Cargar más» para seguir buscando."
    const val REINTENTAR = "Reintentar"
    const val LIMPIAR_BUSQUEDA = "Borrar búsqueda"
    const val POR_PAGAR_VACIO = "No hay pedidos por pagar"
    const val POR_PAGAR_VACIO_AYUDA = "Cuando alguien pida por el QR, aparece aquí con su folio y su nombre."
    fun sinResultados(texto: String) = "No encontramos «$texto»"
    // A3 (A2-m5): la clienta que llega tarde — la cajera sabe qué hacer, igual que en la TPV.
    const val SIN_RESULTADOS_AYUDA = "Si ya venció o ya se pagó, revisa «Por entregar». Si venció, cóbralo en «Cobrar» como venta normal."

    // Tienda híbrida (etapa 1, A3) — cobrar y cancelar un folio: iOS debe copiarlos igual.
    const val COBRAR_EN_EFECTIVO = "Cobrar en efectivo"
    /** Al tocar «Cobrar» (o al confirmar el efectivo) con red, el folio ya venció: no se cobra el folio. */
    const val FOLIO_VENCIDO = "Este pedido ya venció: cóbralo como venta normal"
    /** Igual, pero otra caja (o el panel) lo canceló antes de su hora. */
    const val FOLIO_CANCELADO = "Este folio ya se canceló: cóbralo como venta normal"
    /**
     * Ya lo cobraron: «Este folio ya se pagó» + «Lo cobró otra caja a las HH:MM. Ya está en «Por entregar».». Si el cobro salió de
     * ESTE aparato ([enEstaCaja]), no se culpa a otra caja: «Se cobró en esta caja a las HH:MM».
     */
    fun folioYaPagado(hora: String?, enEstaCaja: Boolean = false) =
        "Este folio ya se pagó. ${if (enEstaCaja) "Se cobró en esta caja" else "Lo cobró otra caja"}" +
            "${hora?.let { " a las $it" }.orEmpty()}. Ya está en «Por entregar»."
    const val CANCELAR_FOLIO = "Cancelar folio"
    fun cancelarFolioTitulo(folio: String) = "¿Cancelar el folio $folio?"
    fun cancelarFolioCuerpo(nombre: String?) =
        (nombre?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it lo verá" } ?: "Lo verá") +
            " cancelado en su celular. Úsalo para un pedido falso o si ya no lo quiere."
    const val NO = "No"
    const val SI_CANCELAR = "Sí, cancelar"
    fun pagadoEnEfectivo(monto: String) = "Pagado en efectivo $monto"
    fun pagadoALas(hora: String) = "Pagado a las $hora"
    // A5 (Codex fase 3-4 r1 #1): un folio cobrado con la terminal ligada que la lista todavía no cuenta. iOS los copia igual.
    const val FOLIO_COBRADO_CON_TARJETA = "Este folio ya se cobró con tarjeta en esta caja."
    // R100: lo mismo para un pedido de «Mi equipo» (vocabulario: «pedido», nunca «folio»). iOS lo copia igual.
    const val PEDIDO_COBRADO_CON_TARJETA = "Este pedido ya se cobró con tarjeta en esta caja."
    // R104: sin red, el saldo de un folio sólo sale de la copia guardada; si no está ahí, no se cobra. iOS lo copia igual.
    const val FOLIO_SIN_SALDO_GUARDADO = "Sin conexión: no se puede confirmar el saldo de este folio."
    const val COBRO_CON_TARJETA_AQUI = "Se cobró con tarjeta en esta caja: se actualiza al volver la conexión"
    fun pagadoConTarjeta(monto: String) = "$PAGADO_CON_TARJETA $monto"

    // Tienda híbrida, parte 2 (A1): el folio con su clienta y sus beneficios, y el cobro con saldo. iOS los copia igual.
    /** La misma frase que el DASHBOARD («Con cuenta: {{name}}»); el nombre es el corto del SERVIDOR («Ana L.»). */
    fun conCuenta(nombre: String) = "Con cuenta: $nombre"
    fun montoDeBeneficio(monto: String) = "−$monto"
    fun beneficiosDeMas(n: Int) = "y $n más"
    /** Un cobro con saldo de esta caja que la lista guardada todavía no cuenta: «Cobrar» no abre otro (pudo ser parcial). */
    const val FOLIO_COBRADO_CON_SALDO = "Este folio ya tiene un cobro con saldo en esta caja: lo que falte se cobra al volver la conexión."
}

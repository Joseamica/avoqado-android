package com.avoqado.pos.giftcards.domain

fun textoDeEntrega(canal: String, deliveryStatus: String?): String? =
    if (canal !in setOf("WHATSAPP", "EMAIL")) null else when (deliveryStatus) {
        "PENDING" -> TextosDeTarjetas.ENVIANDO
        "SENT" -> TextosDeTarjetas.ENVIADA
        "FAILED" -> TextosDeTarjetas.NO_SE_PUDO_ENVIAR
        else -> null
    }

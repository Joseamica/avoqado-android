package com.avoqado.pos.printing.data

data class CertificadoDeTarjeta(val negocio: String?, val monto: Double, val para: String?, val de: String?,
    val mensaje: String?, val liga: String?, val codigo: String, val folio: String, val vence: String?) {
    override fun toString() = "CertificadoDeTarjeta(****)"
}

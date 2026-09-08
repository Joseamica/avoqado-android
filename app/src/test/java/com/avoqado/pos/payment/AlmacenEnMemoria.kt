package com.avoqado.pos.payment

import com.avoqado.pos.printing.data.AlmacenDeTexto

/**
 * Almacén en memoria para las pruebas de `PaymentFlowViewModel`.
 *
 * Vive UNA sola vez y compartido: estas pruebas no ejercitan la persistencia entre arranques
 * —eso es de `ComandasPendientesStoreTest`, que sí la prueba entera—; aquí sólo hace falta que
 * el ViewModel tenga un almacén que no truene.
 */
class AlmacenEnMemoria : AlmacenDeTexto {
    private var texto: String? = null
    override fun leer(): String? = texto
    override fun escribir(texto: String) { this.texto = texto }
    override fun borrar() { texto = null }
}

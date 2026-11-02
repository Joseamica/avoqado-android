package com.avoqado.pos.transactions

import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.presentation.CustomersViewModel
import com.avoqado.pos.customers.presentation.desenlaceDelFormulario

/**
 * Una pantalla anfitriona del alta de clienta, con el estado que le da Compose (Codex apps r5). El proyecto no tiene pruebas de
 * Compose, así que aquí se reproduce ese estado.
 *
 * - Su bandera de «formulario abierto» y el número del alta que pidió ESE formulario tienen la misma persistencia.
 * - Con `remember` (Cambio, Reembolso, carrito, detalle, Clientes), los dos se pierden al girar.
 * - Con `rememberSaveable` (el recibo del cobro), los dos vuelven al girar.
 * - El formulario abierto aplica el desenlace como `CreateCustomerView`: con `desenlaceDelFormulario`, en la composición vigente.
 */
internal class PantallaConAlta(
    private val clientes: CustomersViewModel,
    private val sobreviveAlGiro: Boolean,
    /** El `onCustomerCreated` de la pantalla, el de la composición vigente. */
    private val alCrear: PantallaConAlta.(Customer) -> Unit,
) {
    var formularioAbierto = false
        private set
    var alta: Long? = null
        private set
    /** El rechazo que muestra el formulario abierto (su diálogo). */
    var rechazoVisible: String? = null
        private set

    /** `onCreateCustomer`: un formulario nuevo nace sin alta pedida. */
    fun abrirFormulario() { alta = null; formularioAbierto = true }
    /** «Guardar» → `onAlta(viewModel.createCustomer(…))`. */
    fun guardar(nombre: String) { alta = clientes.createCustomer(nombre, null, "5511112222", null) }
    /** Atrás o descartar la hoja. */
    fun cerrarFormulario() { formularioAbierto = false }
    /** La actividad se recrea: lo de `remember` se pierde, lo de `rememberSaveable` vuelve. */
    fun girar() { if (!sobreviveAlGiro) { formularioAbierto = false; alta = null } }
    /** Una pasada de composición: sólo el formulario abierto mira el desenlace. */
    fun componer() {
        rechazoVisible = null
        if (!formularioAbierto) return
        val d = desenlaceDelFormulario(clientes.altaTerminada.value, alta) ?: return
        rechazoVisible = d.error
        d.cliente?.let { alta = null; alCrear(it) }
    }
}

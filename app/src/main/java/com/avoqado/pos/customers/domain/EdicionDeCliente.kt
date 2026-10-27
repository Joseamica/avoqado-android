package com.avoqado.pos.customers.domain

import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.data.model.UpdateCustomerRequest

/**
 * Editar cliente desde el POS (26-oct-2026; promesa en `Avoqado/docs/audit/2026-10-26-editar-cliente-pos/`). Espejo de
 * `EdicionDeCliente.swift` en iOS: mismos textos y mismas reglas.
 */

/** El permiso que pide `PUT /mobile/venues/:venueId/customers/:customerId`. */
const val PERMISO_EDITAR_CLIENTES = "customers:update"

const val AVISO_SIN_PERMISO_EDITAR_CLIENTE =
    "Sólo un gerente puede editar clientes. Pídeselo a tu gerente; el dueño puede darte el permiso en Roles y " +
        "permisos o prender el PIN de gerente en el dashboard."

const val AVISO_SIN_RED_EDITAR_CLIENTE = "Sin conexión: para editar al cliente necesitas internet."

/**
 * Un guardado que entró con PIN de gerente y el servidor rechazó por permiso: el PIN venció o se canceló el teclado.
 * NO se reintenta solo (Codex r3 #1 y #2: el reintento automático usaba la autorización de otra edición y reabría el
 * teclado tras cancelarlo). El PIN viejo ya se usó, así que el siguiente «Guardar» abre el teclado.
 */
const val AVISO_FALTA_PIN_EDITAR_CLIENTE = "Falta la autorización de un gerente. Toca «Guardar» para pedir el PIN otra vez."

/** Lo que el cajero dejó escrito en el formulario, tal cual. */
data class ContactoEditado(val nombre: String, val apellido: String, val telefono: String, val correo: String)

enum class CampoDeContacto { NOMBRE, APELLIDO, TELEFONO, CORREO }

enum class AccesoAEditarCliente {
    /** Tiene `customers:update`: abre el formulario. */
    DIRECTO,

    /** No lo tiene, pero el negocio prendió el PIN de gerente: primero el teclado. */
    CON_PIN,

    /** No lo tiene y el PIN está apagado: el servidor rechazaría el PIN, así que se le explica qué hacer. */
    SIN_PERMISO,
}

fun accesoAEditarCliente(puedeEditar: Boolean, pinDeGerenteActivo: Boolean): AccesoAEditarCliente = when {
    puedeEditar -> AccesoAEditarCliente.DIRECTO
    pinDeGerenteActivo -> AccesoAEditarCliente.CON_PIN
    else -> AccesoAEditarCliente.SIN_PERMISO
}

/** Sin espacios, guiones ni paréntesis; un `+` sólo al principio. Así se compara y así viaja. */
fun normalizarTelefono(texto: String): String {
    val limpio = texto.trim()
    val digitos = limpio.filter { it.isDigit() }
    return if (limpio.startsWith("+")) "+$digitos" else digitos
}

private val TELEFONO_VALIDO = Regex("^\\+?[0-9]{10,15}$")
/** La MISMA regla que `z.string().email()` (zod 3.25) del servidor: lo que aquí pasa, allá pasa (Codex r2 #5). */
private val CORREO_VALIDO = Regex(
    "^(?!\\.)(?!.*\\.\\.)([A-Z0-9_'+\\-.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\\-]*\\.)+[A-Z]{2,}$",
    RegexOption.IGNORE_CASE,
)
private const val TOPE_NOMBRE = 50

/**
 * Lo que impide guardar, por campo. Un dato que el cliente YA tenía no se puede vaciar (el servidor no borra datos de
 * contacto); los formatos son los mismos que valida el servidor.
 */
fun erroresDeContacto(original: Customer, editado: ContactoEditado): Map<CampoDeContacto, String> {
    val errores = mutableMapOf<CampoDeContacto, String>()
    fun vaciado(antes: String?, ahora: String) = !antes.isNullOrBlank() && ahora.isBlank()

    val nombre = editado.nombre.trim()
    val apellido = editado.apellido.trim()
    val telefono = normalizarTelefono(editado.telefono)
    val correo = editado.correo.trim()

    when {
        vaciado(original.firstName, nombre) -> errores[CampoDeContacto.NOMBRE] = "No se puede dejar vacío"
        nombre.length > TOPE_NOMBRE -> errores[CampoDeContacto.NOMBRE] = "Máximo 50 letras"
    }
    when {
        vaciado(original.lastName, apellido) -> errores[CampoDeContacto.APELLIDO] = "No se puede dejar vacío"
        apellido.length > TOPE_NOMBRE -> errores[CampoDeContacto.APELLIDO] = "Máximo 50 letras"
    }
    when {
        vaciado(original.phone, telefono) -> errores[CampoDeContacto.TELEFONO] = "No se puede dejar vacío"
        telefono.isNotEmpty() && telefono != normalizarTelefono(original.phone.orEmpty()) &&
            !TELEFONO_VALIDO.matches(telefono) ->
            errores[CampoDeContacto.TELEFONO] = "El teléfono debe tener de 10 a 15 dígitos"
    }
    when {
        vaciado(original.email, correo) -> errores[CampoDeContacto.CORREO] = "No se puede dejar vacío"
        correo.isNotEmpty() && !correo.equals(original.email.orEmpty(), ignoreCase = true) &&
            !CORREO_VALIDO.matches(correo) ->
            errores[CampoDeContacto.CORREO] = "El correo no es válido"
    }
    return errores
}

/**
 * Sólo lo que cambió (recortado). `null` = nada que mandar. Mandar sólo lo cambiado evita pisar lo que otro aparato o
 * el dashboard editó mientras tanto en los demás campos.
 */
fun cambiosDeContacto(original: Customer, editado: ContactoEditado): UpdateCustomerRequest? {
    fun cambio(antes: String?, ahora: String): String? = ahora.takeIf { it.isNotEmpty() && it != antes.orEmpty().trim() }

    val telefono = normalizarTelefono(editado.telefono)
    val request = UpdateCustomerRequest(
        firstName = cambio(original.firstName, editado.nombre.trim()),
        lastName = cambio(original.lastName, editado.apellido.trim()),
        phone = telefono.takeIf { it.isNotEmpty() && it != normalizarTelefono(original.phone.orEmpty()) },
        email = editado.correo.trim().takeIf { it.isNotEmpty() && !it.equals(original.email.orEmpty().trim(), ignoreCase = true) },
    )
    return request.takeUnless { it == UpdateCustomerRequest() }
}

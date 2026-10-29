package com.avoqado.pos.giftcards.domain

import com.avoqado.pos.loyalty.data.PremioPorAplicar
import com.avoqado.pos.pos.data.model.*
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import kotlinx.serialization.Serializable

@Serializable
data class FotoDeLaVenta(
    val items: List<CartItem> = emptyList(),
    val pendingStampReward: PremioPorAplicar? = null,
    val orderDiscount: Discount? = null,
    val orderNote: String? = null,
    val orderTaxPercent: Int? = null,
    val orderType: String = "DINE_IN",
    val selectedStaffId: String = "",
    val selectedStaffName: String = "Staff",
    val reservationId: String? = null,
    val carritoGuardadoId: String? = null,
    val vinoDeCarritoGuardado: Boolean = false,
    val descuentoDeCuentaCongelado: DescuentoCongelado? = null,
    val creacionIncierta: String? = null,
    /** La llave de «Pagar después» viaja: el siguiente intento de la venta recuperada reusa la misma y no crea otra cuenta. */
    val llavePagarDespues: String? = null,
    val clienteId: String? = null,
    val clienteNombre: String? = null,
)

fun CartState.aFoto(cliente: CartViewModel.SelectedCustomer?): FotoDeLaVenta = FotoDeLaVenta(
    items = items.map { it.copy(selectedModifiers = it.selectedModifiers.toList()) },
    pendingStampReward = pendingStampReward,
    orderDiscount = orderDiscount?.copy(targetItemIds = orderDiscount.targetItemIds?.toList(), targetCategoryIds = orderDiscount.targetCategoryIds?.toList()),
    orderNote = orderNote, orderTaxPercent = orderTaxPercent, orderType = orderType,
    selectedStaffId = selectedStaffId, selectedStaffName = selectedStaffName,
    reservationId = reservationId, carritoGuardadoId = carritoGuardadoId, vinoDeCarritoGuardado = vinoDeCarritoGuardado,
    descuentoDeCuentaCongelado = descuentoDeCuentaCongelado?.copy(huellas = descuentoDeCuentaCongelado.huellas.toMap()),
    creacionIncierta = creacionIncierta, llavePagarDespues = llavePagarDespues, clienteId = cliente?.id, clienteNombre = cliente?.name,
)

fun FotoDeLaVenta.aCartState(): CartState = CartState(
    items = items.map { it.copy(selectedModifiers = it.selectedModifiers.toList()) },
    pendingStampReward = pendingStampReward,
    orderDiscount = orderDiscount?.copy(targetItemIds = orderDiscount.targetItemIds?.toList(), targetCategoryIds = orderDiscount.targetCategoryIds?.toList()),
    orderNote = orderNote, orderTaxPercent = orderTaxPercent, orderType = orderType,
    selectedStaffId = selectedStaffId, selectedStaffName = selectedStaffName,
    reservationId = reservationId, carritoGuardadoId = carritoGuardadoId, vinoDeCarritoGuardado = vinoDeCarritoGuardado,
    descuentoDeCuentaCongelado = descuentoDeCuentaCongelado?.copy(huellas = descuentoDeCuentaCongelado.huellas.toMap()),
    creacionIncierta = creacionIncierta, llavePagarDespues = llavePagarDespues,
)

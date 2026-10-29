package com.avoqado.pos.giftcards
import org.junit.Assert.*
import org.junit.Test

class ArmadosDelCarritoTest {
    @Test fun todoArmadoDeclaraSuMarca() {
        // A1–A12: init/fin descartan; A8/A9 conservan; A4 marca origen; A12 repone foto sin vínculo.
        // CheckoutScreen ahora finaliza con finalizarVentaSiEs; conserva la guarda de identidad.
        // runPrimaryAction ahora vacía con vaciar y conserva sus guardas.
        val armados = mapOf(
            "com/avoqado/pos/giftcards/presentation/VentaPorRecuperar.kt:recuperar:restaurarFoto" to 1,
            "com/avoqado/pos/giftcards/domain/FotoDeLaVenta.kt:<fuera>:CartState" to 1,
            "com/avoqado/pos/payment/presentation/CobroDeCuentaScreen.kt:CobroDeCuentaScreen:CartState" to 1,
            "com/avoqado/pos/pos/presentation/checkout/CheckoutScreen.kt:CheckoutScreen:restaurarFoto" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:<fuera>:_cartState = MutableStateFlow" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:<fuera>:defaultCartState" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:<fuera>:finalizarVenta" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:defaultCartState:CartState" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:fetchStaff:_cartState.value =" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:fetchStaff:defaultCartState" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:consumePendingTableCobrar:clearCart" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:saveCurrentCart:finalizarVenta" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:clearCart:_cartState.value =" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:clearCart:defaultCartState" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:finalizarVenta:clearCart" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:aplicarCobroConfirmado:clearCart" to 4, // D10: conserva identidad, creación en duda y origen guardado (U1)
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:restoreSavedCart:_cartState.value =" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:restoreSavedCart:CartState" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:restaurarFoto:_cartState.value =" to 1,
            // «Pagar después» de develop (27-oct): la venta sale del carrito al mandarse y vuelve entera si se rechaza.
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:separarDelCarrito:finalizarVenta" to 1,
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:devolverVenta:_cartState.value =" to 1,
            // D5 de vales: «Recuperar» termina la venta en pantalla si es la misma mercancía del vale.
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:alRecuperarVale:finalizarVenta" to 1,
            "com/avoqado/pos/pos/presentation/checkout/CheckoutScreen.kt:CheckoutScreen:saveCurrentCart" to 2,
            "com/avoqado/pos/pos/presentation/checkout/ShortcutsGridView.kt:SavedCartsSubView:restoreSavedCart" to 2,
            // OnlineOrdersViewModel.carritoDelCobro: saldo excluido por origen (decisión 5 de ROOT).
            "com/avoqado/pos/onlineorders/presentation/OnlineOrdersViewModel.kt:<fuera>:CartState" to 1,
            // adoptarOrdenDeLaParte: finaliza sólo con parte pagada y carrito igual.
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:adoptarOrdenDeLaParte:finalizarVenta" to 1,
            // vaciar: finaliza con guardas.
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:vaciar:finalizarVenta" to 1,
            // finalizarVentaSiEs: finaliza sólo si coincide la identidad.
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:finalizarVentaSiEs:finalizarVenta" to 1,
            "com/avoqado/pos/tables/presentation/TableOrderScreen.kt:TableOrderScreen:CartState" to 1
        )
        // Por entrada: T4 / vínculo / origen guardado. Las ramas parciales están probadas en CartSplitContinuidadTest.
        val manejos = mapOf(
            "com/avoqado/pos/giftcards/presentation/VentaPorRecuperar.kt:recuperar:restaurarFoto" to "regresa-foto / sin-vinculo / origen-de-foto-T1",
            "com/avoqado/pos/giftcards/domain/FotoDeLaVenta.kt:<fuera>:CartState" to "regresa-foto / sin-vinculo / origen-de-foto-T1",
            "com/avoqado/pos/payment/presentation/CobroDeCuentaScreen.kt:CobroDeCuentaScreen:CartState" to "ninguna / cuenta-del-server / ninguna",
            "com/avoqado/pos/pos/presentation/checkout/CheckoutScreen.kt:CheckoutScreen:restaurarFoto" to "regresa-foto / sin-vinculo / origen-de-foto-T1",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:<fuera>:_cartState = MutableStateFlow" to "borra-o-inicia-vacio / borra-o-inicia-vacio / borra-o-inicia-vacio",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:<fuera>:defaultCartState" to "borra-o-inicia-vacio / borra-o-inicia-vacio / borra-o-inicia-vacio",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:<fuera>:finalizarVenta" to "borra-o-inicia-vacio / borra-o-inicia-vacio / borra-o-inicia-vacio",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:defaultCartState:CartState" to "borra-o-inicia-vacio / borra-o-inicia-vacio / borra-o-inicia-vacio",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:fetchStaff:_cartState.value =" to "borra-o-inicia-vacio / borra-o-inicia-vacio / borra-o-inicia-vacio",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:fetchStaff:defaultCartState" to "borra-o-inicia-vacio / borra-o-inicia-vacio / borra-o-inicia-vacio",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:consumePendingTableCobrar:clearCart" to "borra-o-inicia-vacio / borra-o-inicia-vacio / borra-o-inicia-vacio",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:saveCurrentCart:finalizarVenta" to "guarda-sin-marca-y-finaliza / no-persiste / no-persiste",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:clearCart:_cartState.value =" to "borra-o-inicia-vacio / borra-o-inicia-vacio / borra-o-inicia-vacio",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:clearCart:defaultCartState" to "borra-o-inicia-vacio / borra-o-inicia-vacio / borra-o-inicia-vacio",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:finalizarVenta:clearCart" to "borra-o-inicia-vacio / borra-o-inicia-vacio / borra-o-inicia-vacio",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:aplicarCobroConfirmado:clearCart" to "conserva-en-parcial-o-borra-al-terminar / host-repone-si-debe / conserva-en-parcial",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:restoreSavedCart:_cartState.value =" to "borra / borra / marca-T6",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:restoreSavedCart:CartState" to "borra / borra / marca-T6",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:restaurarFoto:_cartState.value =" to "regresa-foto / sin-vinculo / origen-de-foto-T1",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:separarDelCarrito:finalizarVenta" to "borra-o-inicia-vacio / borra-o-inicia-vacio / borra-o-inicia-vacio",
            // Regresa la MISMA venta capturada (rechazo definitivo: no se creó); a medias no se difiere, así que no trae vínculo.
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:devolverVenta:_cartState.value =" to "regresa-la-venta / sin-vinculo / conserva-su-origen",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:alRecuperarVale:finalizarVenta" to "borra-o-inicia-vacio / borra-o-inicia-vacio / borra-o-inicia-vacio",
            "com/avoqado/pos/pos/presentation/checkout/CheckoutScreen.kt:CheckoutScreen:saveCurrentCart" to "guarda-sin-marca-y-finaliza / no-persiste / no-persiste",
            "com/avoqado/pos/pos/presentation/checkout/ShortcutsGridView.kt:SavedCartsSubView:restoreSavedCart" to "borra / borra / marca-T6",
            "com/avoqado/pos/onlineorders/presentation/OnlineOrdersViewModel.kt:<fuera>:CartState" to "ninguna / vínculo-en-origen-pedido / ninguna",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:adoptarOrdenDeLaParte:finalizarVenta" to "borra-o-inicia-vacio / borra-o-inicia-vacio / borra-o-inicia-vacio",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:vaciar:finalizarVenta" to "borra-o-inicia-vacio / borra-o-inicia-vacio / borra-o-inicia-vacio",
            "com/avoqado/pos/pos/presentation/cart/CartViewModel.kt:finalizarVentaSiEs:finalizarVenta" to "borra-si-es-su-venta / borra-si-es-su-venta / borra-si-es-su-venta",
            "com/avoqado/pos/tables/presentation/TableOrderScreen.kt:TableOrderScreen:CartState" to "ninguna / cuenta-del-server / ninguna"
        )
        assertEquals(armados.keys, manejos.keys)
        assertTrue(manejos.values.all { it.split(" / ").size == 3 })
        val reales = InventarioDeFuentes6.llamadas("_cartState\\s*(?:\\.value\\s*=|=\\s*MutableStateFlow)|\\b(CartState|defaultCartState|finalizarVenta|clearCart|restoreSavedCart|saveCurrentCart|restaurarFoto)\\s*\\(")
        assertEquals("Armado añadido o retirado: " + reales.joinToString { "${it.key}:${it.linea}" },armados,reales.groupingBy { it.key }.eachCount())
        val vm = java.io.File("src/main/java/com/avoqado/pos/pos/presentation/cart/CartViewModel.kt").readText()
        assertTrue(vm.contains("creacionIncierta = creacionOriginal"))
        assertTrue(vm.contains("vinoDeCarritoGuardado = vinoDeGuardado"))
        assertTrue(vm.contains("vinoDeCarritoGuardado = true"))
    }
}

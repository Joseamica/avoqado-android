package com.avoqado.pos.tables

import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.tables.data.OpenCheckSummary
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.presentation.motivoParaNoImprimirPreCuenta
import com.avoqado.pos.tables.presentation.motivoPreCuentaEnPanel
import com.avoqado.pos.tables.presentation.textoCuentaEnPlano
import com.avoqado.pos.tables.presentation.textoSubtotalDeMesa
import org.junit.Test
import org.junit.Assert.*

/** Presentation policies called by the real floor/check/action components; no alternate money formula. */
class TableSaldoPresentacionTest {
    @Test fun cuenta_provisional_no_presenta_cero_ni_cache_como_saldo_confirmado() {
        for(total in listOf(0.0,80.0,160.0)) {
            assertEquals("Por sincronizar",textoCuentaEnPlano(OpenCheckSummary("local_L",total=total,isProvisional=true)))
        }
    }
    @Test fun cuenta_del_servidor_conserva_cero_conocido_y_total() {
        assertEquals("$0.00",textoCuentaEnPlano(OpenCheckSummary("R",total=0.0)))
        assertEquals("$80.00",textoCuentaEnPlano(OpenCheckSummary("R",total=80.0)))
    }
    @Test fun subtotal_sin_cheque_es_por_confirmar() {
        assertEquals("Por confirmar",textoSubtotalDeMesa(null))
    }
    @Test fun subtotal_con_cheque_preserva_cero_formula_y_fallback_existentes() {
        assertEquals("$0.00",textoSubtotalDeMesa(OrderDetail("R")))
        assertEquals("$100.00",textoSubtotalDeMesa(OrderDetail("R",subtotal=100.0,total=80.0)))
        assertEquals("$160.00",textoSubtotalDeMesa(OrderDetail("R",subtotal=0.0,total=160.0)))
    }
    @Test fun precuenta_provisional_usa_motivo_canonico_aunque_no_haya_items_servidor() {
        val canon=motivoParaNoImprimirPreCuenta(true,null,"local_L",false,false,
            TrabajoSinResolver(2,0,setOf("OPEN_TABLE","ADD_ITEMS"),0))
        assertEquals("La cuenta todavía no se puede leer del servidor. Sin conexión no se imprime la pre-cuenta.",
            motivoPreCuentaEnPanel(false,canon))
    }
    @Test fun precuenta_con_ronda_encolada_no_dice_nada_enviado() {
        val canon=motivoParaNoImprimirPreCuenta(true,OrderDetail("R",total=80.0),"R",true,false,
            TrabajoSinResolver(1,0,setOf("ADD_ITEMS"),0))
        assertEquals("Esta mesa tiene cambios sin sincronizar: la pre-cuenta sale cuando se sincronicen.",
            motivoPreCuentaEnPanel(false,canon))
        assertEquals(canon,motivoPreCuentaEnPanel(true,canon))
    }
    @Test fun precuenta_lista_y_cuenta_realmente_vacia_preservan_estados_del_panel() {
        assertNull(motivoPreCuentaEnPanel(true,null))
        assertEquals("Todavía no hay nada enviado a cocina, así que no hay cuenta que imprimir.",motivoPreCuentaEnPanel(false,null))
    }
}

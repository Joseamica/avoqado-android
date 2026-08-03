package com.avoqado.pos.navigation

import com.avoqado.pos.settings.domain.PosMode

/**
 * Qué pestañas ve el usuario en la barra de abajo.
 *
 * Vive aparte de `AppState` —que arrastra media docena de dependencias— porque
 * es una decisión de producto que se rompe sin querer y hay que poder fijarla
 * con tests. Aquí no hay red, ni almacenamiento, ni reloj: sólo la política.
 */
object MainTabsPolicy {

    /**
     * @param reservationsEnabled el interruptor del local ("Activar reservas").
     * @param planAllowsReservations si el plan contratado las incluye.
     * @param posMode en qué está configurado ESTE dispositivo.
     */
    fun visibleTabs(
        reservationsEnabled: Boolean,
        planAllowsReservations: Boolean,
        posMode: PosMode,
        canAccessPOS: Boolean,
        canAccessInventory: Boolean,
        canAccessTransactions: Boolean,
    ): List<MainTab> {
        // 🔴 Calendario y Mesas CONVIVEN.
        //
        // Antes se excluían: el calendario exigía además estar en modo Reservas,
        // así que activar reservas en un restaurante cambiaba el modo y Mesas
        // DESAPARECÍA de la barra — el local perdía el plano de mesas por tomar
        // citas. Verificado en tablet el 2026-08-03.
        //
        // El modo ya no decide QUIÉN aparece, sólo quién va primero: es donde
        // arranca cada negocio, el plano en un restaurante y la agenda en uno
        // de citas.
        val showCalendar = reservationsEnabled && planAllowsReservations
        val showTables = posMode == PosMode.RESTAURANT

        val ordered = buildList {
            if (posMode == PosMode.RESERVATIONS) {
                if (showCalendar) add(MainTab.CALENDAR)
                if (showTables) add(MainTab.TABLES)
            } else {
                if (showTables) add(MainTab.TABLES)
                if (showCalendar) add(MainTab.CALENDAR)
            }
            // Diferencia REAL con iOS, y es de plataforma: allí el tab bar nativo
            // sólo admite 5 y el sexto se pliega en un "More" del sistema en
            // inglés, así que iOS cede Alertas —y Ventas cuando Calendario y
            // Mesas encabezan a la vez— al menú "Más". Aquí caben las siete, así
            // que no se esconde nada. Lo que el usuario ALCANZA es lo mismo.
            add(MainTab.CHECKOUT)
            add(MainTab.INVENTORY)
            add(MainTab.TRANSACTIONS)
            add(MainTab.NOTIFICATIONS)
            add(MainTab.MORE)
        }

        return ordered.filter { tab ->
            when (tab) {
                MainTab.CHECKOUT -> canAccessPOS
                MainTab.INVENTORY -> canAccessInventory
                MainTab.TRANSACTIONS -> canAccessTransactions
                MainTab.NOTIFICATIONS -> true
                MainTab.MORE -> true
                MainTab.CALENDAR -> true
                MainTab.TABLES -> canAccessPOS
            }
        }
    }
}

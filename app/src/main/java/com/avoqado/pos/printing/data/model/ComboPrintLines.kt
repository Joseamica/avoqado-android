package com.avoqado.pos.printing.data.model

/**
 * COMBOS EN EL PAPEL — decisión del founder 2026-08-18.
 *
 * Patrón del mercado, verificado antes de decidir: **Fudo** imprime "el nombre
 * del combo y, debajo, cada producto asociado"; **Square** marca en la comanda
 * que el refresco "is part of the Burger Combo"; **Toast/Maitre'D** llevan el
 * nombre del combo a "reports, order screen, guest checks and receipts".
 *
 * Antes de esto el combo DESAPARECÍA del papel: se imprimían sus productos
 * sueltos y un descuento anónimo, así que ni el cliente entendía qué compró ni
 * la cocina sabía que iba junto.
 *
 * 🔴 **No cambia importes.** El renglón del combo lleva la SUMA de sus
 * componentes y los componentes quedan en 0: el total impreso es exactamente el
 * mismo que antes, y cualquier consumidor que sume renglones sigue cuadrando.
 *
 * Función PURA y sin dependencias de Android a propósito — es la parte que se
 * prueba. Espejo exacto de `ComboPrintLines.swift` en avoqado-ios.
 */
object ComboPrintLines {

    /**
     * A qué combo pertenece un renglón.
     *
     * @param key la llave de AGRUPACIÓN, y NO es la misma en los dos papeles:
     *   - **ticket** → la instancia de promoción (`promotionInstanceId`): cada
     *     combo vendido es su propio renglón con su propio precio;
     *   - **comanda** → el NOMBRE del combo, porque el motor de ruteo ya
     *     consolidó líneas de instancias distintas ("3x Hamburguesa") y agrupar
     *     por instancia partiría el combo en pedazos que ya no existen.
     * @param name lo que se imprime en el renglón del combo.
     */
    data class Tag(val key: String, val name: String)

    /**
     * Ticket: renglón del combo con su precio total y debajo los componentes sin
     * precio. Un renglón suelto (tag null) sale tal cual y en su lugar.
     */
    fun receipt(tagged: List<Pair<Tag?, ReceiptItem>>): List<ReceiptItem> =
        group(
            tagged = tagged,
            header = { tag, members ->
                val total = members.sumOf { it.totalPrice }
                ReceiptItem(
                    name = tag.name,
                    // Una instancia = UN combo (misma semántica que la llave de
                    // idempotencia del server, `@@unique(orderId, instanceId)`).
                    quantity = 1,
                    unitPrice = total,
                    totalPrice = total,
                    isComboHeader = true,
                )
            },
            component = { it.copy(unitPrice = 0, totalPrice = 0, isComboComponent = true) },
        )

    /**
     * Comanda: nombre del combo y debajo los productos con SU cantidad — es lo
     * que la cocina tiene que preparar.
     */
    fun kitchen(tagged: List<Pair<Tag?, KitchenItem>>): List<KitchenItem> =
        group(
            tagged = tagged,
            header = { tag, _ ->
                // Sin cantidad ni modificadores: la cocina no prepara "un combo",
                // prepara los productos de abajo. El renderer no imprime el
                // `quantity` de un encabezado.
                KitchenItem(name = tag.name, quantity = 1, isComboHeader = true)
            },
            component = { it.copy(isComboComponent = true) },
        )

    /**
     * El recorrido compartido: preserva el orden de entrada y emite el bloque
     * completo de un combo en la posición de su PRIMER renglón. Un combo cuyas
     * líneas quedaron separadas (algo entre medio) se junta igual — en el papel
     * un combo partido en dos no significa nada.
     */
    private fun <T> group(
        tagged: List<Pair<Tag?, T>>,
        header: (Tag, List<T>) -> T,
        component: (T) -> T,
    ): List<T> {
        if (tagged.none { it.first != null }) return tagged.map { it.second }

        val membersByKey = LinkedHashMap<String, MutableList<T>>()
        for ((tag, line) in tagged) {
            if (tag != null) membersByKey.getOrPut(tag.key) { mutableListOf() }.add(line)
        }

        val emitted = mutableSetOf<String>()
        val out = mutableListOf<T>()
        for ((tag, line) in tagged) {
            if (tag == null) {
                out.add(line)
                continue
            }
            if (!emitted.add(tag.key)) continue
            val members = membersByKey[tag.key].orEmpty()
            out.add(header(tag, members))
            members.forEach { out.add(component(it)) }
        }
        return out
    }
}

/** Alias corto para no arrastrar el nombre anidado en cada sitio de llamada. */
typealias ComboTag = ComboPrintLines.Tag

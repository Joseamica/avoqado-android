package com.avoqado.pos.orders.data

import com.avoqado.pos.core.data.local.SecureStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * D12 (final fix ronda 2, R2-1): ESTE aparato confirmó un cobro NO efectivo sobre la orden [orderId] de [venueId] (null = no se sabe
 * de qué negocio: cuenta para todos). [llave] lo hace idempotente. [pagada] = el servidor dijo que no queda nada; si no, la cuenta
 * queda «con abono» (de regalo si [conSaldo]) con [saldoCents] (null = no se sabe ⇒ «sin saldo»). Nunca deja cobrable una cuenta.
 */
@Serializable
data class CuentaCobradaAqui(
    val llave: String,
    val venueId: String?,
    val orderId: String,
    val pagada: Boolean = false,
    val saldoCents: Int? = null,
    val conSaldo: Boolean = false,
) {
    fun comoEvidencia(): EvidenciaDelDetalle = if (pagada) EvidenciaDelDetalle.NoExiste else EvidenciaDelDetalle.Abono(saldoCents, conSaldo)
}

/**
 * 🔴 DINERO (R2-1). Los cobros NO efectivo de este aparato que la copia de «Pedidos» todavía no aplicó. Los escriben los puntos ÚNICOS
 * por donde pasa todo positivo, no la pantalla: la tarjeta al salir de su lista durable (`TerminalPaymentService.quitar`, en el MISMO
 * commit: respuesta directa, desenlace tardío y conciliación, por «Entendido», adopción o vencimiento) y el saldo de regalo al
 * confirmarse (`CobroConSaldoCoordinator.confirmar`: cobro directo, consulta, reuso de la llave y el resolutor). [CopiaDeCuentas] las
 * aplica en cuanto alguien la lee y sólo entonces las suelta; mientras tanto, mientras el positivo sigue en la lista de tarjetas, esa
 * lista es la que deja la cuenta en gris. En `SecureStorage` (`commit()`, fuera de `clearSession`).
 */
@Singleton
class CuentasCobradasAqui internal constructor(
    private val leerCrudo: () -> String?,
    private val escribirCrudo: ((String?) -> String) -> Boolean,
    /** Saca un positivo de la lista durable de cobros con tarjeta y anota su marca en el MISMO commit. */
    private val quitarTarjetaYAnotarCrudo: (requestId: String, marcas: (String?) -> String) -> Boolean,
) {
    @Inject
    constructor(secureStorage: SecureStorage) : this(
        leerCrudo = { secureStorage.cuentasCobradasAquiJson },
        escribirCrudo = secureStorage::escribirCuentasCobradasAqui,
        quitarTarjetaYAnotarCrudo = secureStorage::quitarCobroYAnotarCuenta,
    )

    /** @return `false` si el disco no aceptó la escritura. */
    fun anotar(marca: CuentaCobradaAqui): Boolean = escribirCrudo { agregar(it, marca) }

    /** @return `false` si el disco no aceptó el commit (el positivo sigue en la lista de tarjetas, que también deja la cuenta en gris). */
    fun quitarTarjetaYAnotar(requestId: String, marca: CuentaCobradaAqui): Boolean = quitarTarjetaYAnotarCrudo(requestId) { agregar(it, marca) }

    /** Las que pueden ser de [venueId]: las suyas y las que no dicen de qué negocio son. Si el disco no se deja leer, LANZA (falla cerrado). */
    fun marcasDe(venueId: String): List<CuentaCobradaAqui> = leer(leerCrudo()).filter { it.venueId == null || it.venueId == venueId }

    /** Suelta las que la copia ya aplicó. Sólo las IGUALES. */
    fun olvidar(aplicadas: Collection<CuentaCobradaAqui>) {
        if (aplicadas.isEmpty()) return
        escribirCrudo { crudo -> encode(leer(crudo).filterNot { it in aplicadas }) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(CuentaCobradaAqui.serializer())

        fun leer(crudo: String?): List<CuentaCobradaAqui> =
            crudo?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

        private fun encode(lista: List<CuentaCobradaAqui>): String = json.encodeToString(serializer, lista)

        /** Idempotente por [CuentaCobradaAqui.llave]. PURA. */
        fun agregar(crudo: String?, marca: CuentaCobradaAqui): String {
            val lista = leer(crudo)
            return if (lista.any { it.llave == marca.llave }) encode(lista) else encode(lista + marca)
        }
    }
}

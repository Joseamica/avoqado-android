package com.avoqado.pos.kds.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * La PIZARRA de los avisos de reparto: lo que la impresión apuntó y la franja enseña. Es estado y no un evento (Codex,
 * código c1): un evento se perdía si la franja no escuchaba en ese instante (app en segundo plano a media impresión) y,
 * si llegaba tarde, resucitaba un aviso ya descartado.
 *
 * - Cada intento toma la [epoca] al EMPEZAR y publica con ella. [descartar] («Entendido») marca SÓLO los pedidos que
 *   estaban en la franja: lo que termine después para ESOS pedidos, con una época anterior, ya no se apunta; un pedido
 *   nuevo que se esté imprimiendo sí (Codex c2 #1: descartar todo de golpe tiraba resultados válidos).
 * - La sucursal viaja en cada aviso ([ComandaImpresa.venueId]); quien enseña la pizarra filtra por la de ahora y
 *   [retirarDeOtrasSucursales] limpia al cambiar. Cambiar de sucursal ya no invalida nada.
 * - «Reimprimir» se aparta por pedido ([tomarParaReimprimir]) y se suelta DESPUÉS de apuntar su resultado
 *   ([soltarReimpresion]): no hay ventana en la que un segundo toque vea el aviso viejo y mande otra vez lo que ya salió.
 */
@Singleton
class AvisosDeReparto @Inject constructor() {
    private val _avisos = MutableStateFlow<List<ComandaImpresa>>(emptyList())
    val avisos: StateFlow<List<ComandaImpresa>> = _avisos.asStateFlow()

    private var epoca = 0L

    /** Intentos que empezaron antes de [olvidarTodo] ya no se apuntan, sean del pedido que sean. */
    private var piso = 0L

    /** Pedido → época en que se descartó. `ponytail:` se queda con los últimos [MAX_DESCARTADOS]; más viejos ya no vuelven. */
    private val descartadoEn = LinkedHashMap<String, Long>()
    private val reimprimiendo = mutableSetOf<String>()

    @Synchronized
    fun epoca(): Long = epoca

    /** Apunta (o reemplaza, por pedido) el resultado de un intento que empezó en [epocaDelIntento]. */
    @Synchronized
    fun publicar(c: ComandaImpresa, epocaDelIntento: Long) {
        if (epocaDelIntento < piso) return
        val descartado = descartadoEn[c.kdsId]
        if (descartado != null && descartado > epocaDelIntento) return
        _avisos.value = _avisos.value.filterNot { it.kdsId == c.kdsId } + c
    }

    /** «Entendido»: borra todo y cierra la puerta a lo que siga en vuelo para ESOS pedidos. */
    @Synchronized
    fun descartar() {
        epoca++
        for (id in _avisos.value.map { it.kdsId } + reimprimiendo) descartadoEn[id] = epoca
        while (descartadoEn.size > MAX_DESCARTADOS) descartadoEn.remove(descartadoEn.keys.first())
        _avisos.value = emptyList()
    }

    /**
     * Sin sesión, permiso o plan: borra todo y cierra la puerta a TODO lo que siga en vuelo, también a la primera impresión
     * de un pedido que todavía no estaba en la franja (Codex c3: con [descartar] reaparecía y sonaba hasta el siguiente sondeo).
     */
    @Synchronized
    fun olvidarTodo() {
        epoca++
        piso = epoca
        descartadoEn.clear()
        _avisos.value = emptyList()
    }

    /** Al cambiar de sucursal: quita lo de las demás (lo que no sabe de qué sucursal es, se queda). */
    @Synchronized
    fun retirarDeOtrasSucursales(venueId: String?) {
        _avisos.value = _avisos.value.filter { it.venueId == null || it.venueId == venueId }
    }

    /**
     * Quita los avisos cuya comanda ya no viene en la lista (Rappi la canceló o alguien la terminó), pero sólo si
     * DEBERÍA venir: la lista son las 100 más nuevas, y la ausencia de uno más viejo que todas no prueba nada.
     */
    @Synchronized
    fun retirarAusentes(ids: Set<String>, masVieja: Long?) {
        _avisos.value = _avisos.value.filter { it.kdsId in ids || (masVieja != null && it.createdAt < masVieja) }
    }

    /** El aviso a reimprimir y su época; `null` si no hay nada que mandar o ya hay un «Reimprimir» en curso. */
    @Synchronized
    fun tomarParaReimprimir(kdsId: String): Pair<ComandaImpresa, Long>? {
        val c = _avisos.value.firstOrNull { it.kdsId == kdsId && it.porReimprimir.isNotEmpty() } ?: return null
        if (!reimprimiendo.add(kdsId)) return null
        return c to epoca
    }

    @Synchronized
    fun soltarReimpresion(kdsId: String) {
        reimprimiendo.remove(kdsId)
    }

    private companion object {
        const val MAX_DESCARTADOS = 500
    }
}

package com.avoqado.pos.core.data.network

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * D22: la carga corre sola, sin que nadie la pida (la recarga automática de Informes). Viaja en el contexto de la corrutina
 * (`withContext(CargaDeFondo.enElContexto)`) hasta cada GET, que entonces lleva [ForbiddenInterceptor.BACKGROUND_HEADER]: un
 * 403 no abre el PIN de gerente ni el aviso de permisos. Así no hay que pasar una bandera por cada una de las cuatro
 * consultas del informe.
 */
object CargaDeFondo {
    private object Clave : CoroutineContext.Key<Marca>

    private class Marca : AbstractCoroutineContextElement(Clave)

    val enElContexto: CoroutineContext = Marca()

    suspend fun activa(): Boolean = coroutineContext[Clave] != null
}

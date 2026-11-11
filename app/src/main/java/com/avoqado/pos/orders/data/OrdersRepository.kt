package com.avoqado.pos.orders.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.core.data.network.LecturaFallida
import com.avoqado.pos.orders.data.model.OrderDetail
import com.avoqado.pos.orders.data.model.OrderPage
import com.avoqado.pos.orders.data.model.OrderSummary
import com.avoqado.pos.orders.data.model.comoDetalle
import com.avoqado.pos.orders.data.model.comoEvidencia
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.orders.presentation.OPEN_ORDERS_STATUS_FILTER
import com.avoqado.pos.payment.domain.TextosDeCuentaSinRed
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "🧾 OrdersRepo"

/** D22: un pedido que no se pudo abrir porque nadie contestó. Con «Pedidos» a la vista, [com.avoqado.pos.orders.presentation.OrdersViewModel.reabrirAlVolverLaRed] lo vuelve a pedir. */
internal const val TEXTO_PEDIDO_SIN_RED = "Sin conexión: para ver este pedido necesitas internet. Se abre solo al volver la conexión."

/**
 * D12: cuánto prueba una lectura de la lista. Sólo la página 1 SIN búsqueda y con «Todos» o «Abiertas» puede retirar ausentes
 * de la copia; cualquier otra (búsqueda, otro filtro, página siguiente) sólo pone al día lo que trae.
 */
fun lecturaDe(page: Int, append: Boolean, search: String?, status: String?, hasMore: Boolean): Lectura = when {
    page != 1 || append || !search.isNullOrBlank() || (status != null && status != OPEN_ORDERS_STATUS_FILTER) -> Lectura.SUELTA
    hasMore -> Lectura.PRIMERA_PAGINA
    else -> Lectura.LISTA_COMPLETA
}

/** D12 (Review Focus 1): `HH:mm` en la zona del NEGOCIO (`zona` null = la de la sesión), nunca la del aparato. */
fun horaDeLaCopia(vistaEn: Long, zona: String?): String =
    VenueTimeZone.at(Instant.ofEpochMilli(vistaEn), zona).format(DateTimeFormatter.ofPattern("HH:mm"))

@Singleton
class OrdersRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    /** D12: la copia en disco de las cuentas abiertas. Hilt (y Guice) siempre la pasan; null sólo en pruebas viejas. */
    private val copia: CopiaDeCuentas? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - State Flows

    private val _orders = MutableStateFlow<List<OrderSummary>>(emptyList())
    val orders: StateFlow<List<OrderSummary>> = _orders.asStateFlow()

    private val _selectedOrder = MutableStateFlow<OrderDetail?>(null)
    val selectedOrder: StateFlow<OrderDetail?> = _selectedOrder.asStateFlow()
    private var detalleSolicitado: Pair<String, Any>? = null

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _isLoadingDetail = MutableStateFlow(false)
    val isLoadingDetail: StateFlow<Boolean> = _isLoadingDetail.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _detalleSinRed = MutableStateFlow<String?>(null)
    /** D22: el pedido cuyo detalle no abrió porque nadie contestó, o `null`. Lo deja cada carga vigente al terminar. */
    val detalleSinRed: StateFlow<String?> = _detalleSinRed.asStateFlow()

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    // MARK: - D12: la copia sin red

    private val _copiaVistaA = MutableStateFlow<Long?>(null)
    /** Hora de lo que se ve en la lista cuando NO hay red (la de la copia, o la de la última lectura buena); null = con red. */
    val copiaVistaA: StateFlow<Long?> = _copiaVistaA.asStateFlow()

    private val _avisoDeLaLista = MutableStateFlow<String?>(null)
    /** T12b: el aviso gris de la lista cuando no es la respuesta del servidor a lo que se pidió (null = sí lo es). */
    val avisoDeLaLista: StateFlow<String?> = _avisoDeLaLista.asStateFlow()

    /** T12b: la lista enseña algo SIN RED (la copia, la última lectura o «buscar necesita internet»): al volver la red se pide otra vez. */
    var listaEsperaLaRed = false
        private set

    /** Final fix (F1): la lista a la vista contesta una búsqueda o un filtro (no «Todos»/«Abiertas»): sin red nunca se cambia por la copia. */
    private var listaConBusqueda = false

    /**
     * T12b: la lista es de [vistaA] (null = al día, o no hay nada guardado). Su aviso dice la razón real: nadie contestó, o el servidor
     * contestó mal. Ronda 1 (M1): sin red y sin nada guardado, también lo dice.
     */
    private fun listaDeLas(vistaA: Long?, sinRed: Boolean) {
        _copiaVistaA.value = vistaA
        _avisoDeLaLista.value = vistaA?.let {
            val hora = horaDeLaCopia(it, secureStorage.venueTimezone)
            if (sinRed) TextosDeCuentaSinRed.copiaDeLas(hora) else TextosDeCuentaSinRed.noSeCargaronDeLas(hora)
        } ?: TextosDeCuentaSinRed.SIN_CUENTAS_GUARDADAS.takeIf { sinRed }
        listaEsperaLaRed = sinRed
    }

    private val _detalleDeLaCopia = MutableStateFlow<CuentaGuardada?>(null)
    /** La cuenta de la copia que respalda el detalle a la vista sin red, o null. */
    val detalleDeLaCopia: StateFlow<CuentaGuardada?> = _detalleDeLaCopia.asStateFlow()

    /** De qué sucursal es lo que hay en [orders], y a qué hora se leyó (o la de la copia que se enseña). */
    private var venueDeLaLista: String? = null
    private var listaVistaA: Long? = null

    private fun ahora(): Long = copia?.ahora?.invoke() ?: System.currentTimeMillis()

    private fun avisoDeLaCopia(g: CuentaGuardada) =
        TextosDeCuentaSinRed.copiaDeLas(horaDeLaCopia(g.vistaEn, secureStorage.venueTimezone))

    // MARK: - Helpers

    private fun baseUrl(): String? {
        val venueId = secureStorage.venueId ?: return null
        return "${ApiConstants.BASE_URL}/mobile/venues/$venueId"
    }

    private suspend fun executeRequest(request: Request): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            response.code to (response.body?.string() ?: "")
        }

    // MARK: - Fetch Orders (paginated)

    suspend fun fetchOrders(
        page: Int = 1,
        pageSize: Int = 20,
        search: String? = null,
        status: String? = null,
    ): Result<OrderPage> {
        val base = baseUrl() ?: return Result.failure(IllegalStateException("Sin venue activo"))

        val urlBuilder = StringBuilder("$base/orders?page=$page&pageSize=$pageSize")
        if (!search.isNullOrBlank()) {
            val encoded = java.net.URLEncoder.encode(search.trim(), "UTF-8")
            urlBuilder.append("&search=$encoded")
        }
        if (!status.isNullOrBlank()) {
            urlBuilder.append("&status=$status")
        }

        return try {
            val request = Request.Builder()
                .url(urlBuilder.toString())
                .get()
                .build()

            val (code, body) = executeRequest(request)
            if (code in 200..299 && body.isNotEmpty()) {
                val root = json.parseToJsonElement(body).jsonObject
                val dataArray = root["data"]?.jsonArray
                    ?: return Result.failure(Exception("Orders: respuesta sin data"))
                val meta = root["meta"]?.jsonObject

                val orders = dataArray.map {
                    json.decodeFromJsonElement<OrderSummary>(it)
                }
                // `intOrNull`: `int` sobre un JSON null convierte el TEXTO "null"
                // y revienta el parseo entero — la lista de pedidos saldría vacía
                // por un campo de paginación ausente. Mismo defecto que tenía la
                // sesión de caja en curso.
                val total = meta?.get("total")?.jsonPrimitive?.intOrNull ?: orders.size
                val currentPage = meta?.get("page")?.jsonPrimitive?.intOrNull ?: page
                val pageCount = meta?.get("pageCount")?.jsonPrimitive?.intOrNull ?: 1

                Log.d(TAG, "✅ Loaded ${orders.size} orders (page $currentPage/$pageCount)")
                Result.success(
                    OrderPage(
                        orders = orders,
                        total = total,
                        page = currentPage,
                        pageCount = pageCount,
                    ),
                )
            } else {
                // Fallo TIPADO: nunca una página vacía que pise datos buenos
                // (spec estrategia-de-refresco §4.1 — era el bug rojo de la
                // auditoría: entrar sin red borraba visualmente los pedidos).
                Log.e(TAG, "❌ fetchOrders error: HTTP $code")
                Result.failure(Exception("Orders HTTP $code"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchOrders exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    // MARK: - Fetch Order Detail

    /**
     * `null` sólo cuando el servidor dice que el pedido no existe (404). Si no se pudo saber (sin red, el servidor contestó
     * mal, un 2xx sin `order` o un cuerpo que no se lee), lanza, y [FalloDeLectura.de] dice cuál fue. D22 (9-nov): antes todo
     * era `null`, y sin red la pantalla decía «No se encontró la orden» de un pedido que sí existe.
     */
    suspend fun fetchOrderDetail(orderId: String): OrderDetail? {
        val base = baseUrl() ?: return null
        val request = Request.Builder()
            .url("$base/orders/$orderId")
            .get()
            .build()

        // Cancelable de verdad: cerrar «Pedidos» corta la consulta y no deja que su respuesta (un 403 con su aviso) salga encima
        // de otra pantalla (Codex r2 #3). El cuerpo sólo se lee con un 2xx: un error ya contestó, y un corte al leer su cuerpo
        // no lo vuelve «Sin conexión» (Codex r1 #1).
        val (code, body) = suspendCancellableCoroutine<Pair<Int, String>> { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val leida = runCatching { response.use { it.code to if (it.isSuccessful) it.body?.string().orEmpty() else "" } }
                    if (cont.isActive) cont.resumeWith(leida)
                }
            })
        }
        if (code == 404) {
            Log.e(TAG, "❌ fetchOrderDetail: el pedido $orderId no existe (404)")
            return null
        }
        val orderObj = if (code in 200..299 && body.isNotEmpty()) json.parseToJsonElement(body).jsonObject["order"] else null
        if (orderObj == null) {
            Log.e(TAG, "❌ fetchOrderDetail error: HTTP $code sin pedido")
            throw LecturaFallida(FalloDeLectura.SERVIDOR)
        }
        val detail = json.decodeFromJsonElement<OrderDetail>(orderObj)
        Log.d(TAG, "✅ Loaded order detail ${detail.id}")
        return detail
    }

    // MARK: - State-Mutating Methods

    suspend fun loadOrders(
        page: Int,
        search: String?,
        status: String?,
        append: Boolean = false,
    ): Result<Unit> {
        if (append) {
            _isLoadingMore.value = true
        } else {
            // Refresh de fondo silencioso: sin skeleton encima de datos buenos.
            _isLoading.value = _orders.value.isEmpty()
        }
        // D22 (Codex r4 #3): la lista ya no toca `_errorMessage`, que es el aviso del DETALLE. Nada lo pintaba en la lista y
        // un refresco borraba el «Sin conexión…» del pedido seleccionado (quedaba «No se pudo cargar el pedido»).

        // D12: la sucursal y el turno se toman cuando la petición SALE (una respuesta atrasada no se mete en otra sucursal).
        val venueId = secureStorage.venueId
        val turno = copia?.abrirLectura()
        // Revisión r1: la copia sólo sin búsqueda ni otro filtro (no es el resultado de lo que se buscó).
        val conBusqueda = lecturaDe(1, false, search, status, hasMore = false) == Lectura.SUELTA
        val result = fetchOrders(page = page, search = search, status = status)
        val pageData = result.getOrNull()
        if (pageData != null) {
            if (copia != null && venueId != null && turno != null) {
                copia.alLeer(venueId, pageData.orders, lecturaDe(page, append, search, status, pageData.hasMore), turno)
            }
            if (secureStorage.venueId == venueId) {
                if (append) {
                    _orders.value = _orders.value + pageData.orders
                } else {
                    _orders.value = pageData.orders
                    listaVistaA = ahora()
                    listaConBusqueda = conBusqueda
                }
                venueDeLaLista = venueId
                _currentPage.value = pageData.page
                _hasMore.value = pageData.hasMore
                listaDeLas(null, sinRed = false)
            }
        } else {
            // Datos previos INTACTOS (spec §4.1), salvo que no haya nada o sean de otra sucursal (D12).
            val error = result.exceptionOrNull()
            Log.e(TAG, "❌ loadOrders: ${error?.message}")
            val actual = secureStorage.venueId
            val sinRed = error != null && FalloDeLectura.de(error) == FalloDeLectura.SIN_RED
            if (actual != null && sinRed && !conBusqueda && copia != null) {
                // Final fix (F1, Codex #5): sin red, la lista ES la copia vigente entera, también con filas a la vista o al pedir «más»:
                // una cuenta guardada de la página 2 no se queda fuera porque en memoria sólo estaba la página 1.
                ensenarLaCopia(actual)
            } else if (actual != null && sinRed && conBusqueda && !append) {
                // T12b: sin red, una búsqueda o un filtro no se contesta. Nunca la copia entera ni la lista de otra consulta como su
                // resultado: la lista se vacía y lo dice.
                _orders.value = emptyList()
                venueDeLaLista = actual
                listaVistaA = null
                listaConBusqueda = true
                _currentPage.value = 1
                _hasMore.value = false
                listaDeLas(null, sinRed = true)
                _avisoDeLaLista.value = TextosDeCuentaSinRed.BUSQUEDA_SIN_RED
            } else if (actual != null && sinRed && append) {
                // Final fix (F1): «Cargar más» de una búsqueda sin red no se puede contestar: lo que se ve se queda «como estaba» y el
                // botón se va hasta que vuelva la red (nunca un toque que no hace nada).
                if (secureStorage.venueId == actual) {
                    _hasMore.value = false
                    listaDeLas(listaVistaA, sinRed = true)
                }
            } else if (actual != null && (_orders.value.isEmpty() || venueDeLaLista != actual)) {
                // Revisión r1: un 5xx o un 403 no es «sin conexión» (y un 403 niega esas cuentas): sin la copia. Las filas de otra
                // sucursal se quitan.
                if (venueDeLaLista != actual) {
                    _orders.value = emptyList()
                    venueDeLaLista = actual
                    listaVistaA = null
                    listaConBusqueda = conBusqueda
                    listaDeLas(null, sinRed = false)
                    _currentPage.value = 1
                    _hasMore.value = false
                } else {
                    listaDeLas(null, sinRed) // T12b: un aviso de antes (la búsqueda sin red) no se queda con otra razón
                }
            } else if (!append && _copiaVistaA.value != null) {
                // T12b (punto 2): lo que se ve ya era «como estaba»; el aviso dice la razón de ESTA lectura (un 5xx no es «Sin conexión»).
                listaDeLas(_copiaVistaA.value, sinRed)
            }
            // T12b ronda 1 (I1): el servidor contestó mal y no hay nada que enseñar: la razón, nunca «No hay pedidos». No se repite solo.
            if (actual != null && !append && !sinRed && _orders.value.isEmpty() && _avisoDeLaLista.value == null &&
                secureStorage.venueId == actual) {
                _avisoDeLaLista.value = TextosDeCuentaSinRed.NO_SE_CARGARON
            }
        }
        _isLoading.value = false
        _isLoadingMore.value = false
        return result.map { }
    }

    /**
     * @param silencioso actualiza el pedido que ya se ve: sin círculo de carga, y si falla se queda lo que hay en pantalla.
     * @param reintento D22: vuelve a pedir un pedido que no abrió por falta de red. Su «Sin conexión…» se queda a la vista
     *   mientras consulta (sin círculo), y si la consulta se corta (otra selección, se fue la red, se cerró «Pedidos») no
     *   cambia nada (Codex r3 #3). Al terminar dice lo que toque, igual que un toque.
     */
    suspend fun loadOrderDetail(orderId: String, silencioso: Boolean = false, reintento: Boolean = false) {
        val solicitud = orderId to Any()
        detalleSolicitado = solicitud
        val venueId = secureStorage.venueId
        val turno = copia?.abrirLectura() // D12 (Codex r3 H1): lo que diga este detalle pone la copia al día
        if (!silencioso && !reintento) {
            _isLoadingDetail.value = true
            _errorMessage.value = null
            _detalleSinRed.value = null // un toque manda: corta la reapertura automática en curso (Codex r4 #1)
        }

        try {
            val detail = fetchOrderDetail(orderId)
            if (copia != null && venueId != null && turno != null) {
                copia.alLeerDetalle(venueId, orderId, detail?.comoEvidencia() ?: EvidenciaDelDetalle.NoExiste, turno)
            }
            if (detalleSolicitado != solicitud || (silencioso && detail == null)) return
            if (_detalleDeLaCopia.value != null) _errorMessage.value = null // su «Sin conexión · como estaba…»
            _detalleDeLaCopia.value = null
            _selectedOrder.value = detail
            _detalleSinRed.value = null
            if (detail == null) {
                _errorMessage.value = "No se encontró la orden"
            } else if (reintento) {
                _errorMessage.value = null // el «Sin conexión…» que se quedó a la vista mientras consultaba
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (detalleSolicitado != solicitud || silencioso) return
            Log.e(TAG, "❌ loadOrderDetail exception: ${e.message}", e)
            val sinRed = FalloDeLectura.de(e) == FalloDeLectura.SIN_RED
            // Final fix (F3, I-1): la reapertura del pedido que se VE y el servidor contesta mal (5xx): se queda a la vista con la razón
            // real, deja de estar sin red (la copia ya no cobra: `copiaParaCobrar`) y no se repite solo (D22). Antes de D12 nada lo recargaba.
            val respaldo = _detalleDeLaCopia.value
            if (reintento && !sinRed && respaldo != null && _selectedOrder.value?.id == orderId) {
                _errorMessage.value = TextosDeCuentaSinRed.pedidoNoSeCargoDeLas(horaDeLaCopia(respaldo.vistaEn, secureStorage.venueTimezone))
                _detalleSinRed.value = null
                return
            }
            // D12: sin red, la cuenta de la copia VIGENTE de la sucursal ACTUAL se enseña con su hora; la reapertura sigue.
            val actual = secureStorage.venueId
            val guardada = if (sinRed && copia != null && actual != null) copia.cuenta(actual, orderId) else null
            if (detalleSolicitado != solicitud) return
            if (guardada != null) {
                // Una reapertura que falla no desmonta lo que ya se ve de esa cuenta (Codex r3 #3).
                if (!reintento || _selectedOrder.value?.id != orderId) _selectedOrder.value = guardada.cuenta.comoDetalle()
                _detalleDeLaCopia.value = guardada
                _errorMessage.value = avisoDeLaCopia(guardada)
                _detalleSinRed.value = orderId
                return
            }
            _detalleDeLaCopia.value = null
            // Como antes de D22 (ahí el fallo llegaba como `null`): volver a pedir el pedido que se ve y fallar enseña el
            // fallo, no la foto anterior sin aviso (Codex r5).
            _selectedOrder.value = null
            _errorMessage.value = if (sinRed) TEXTO_PEDIDO_SIN_RED else "Error al cargar detalle de orden"
            _detalleSinRed.value = orderId.takeIf { sinRed }
        } finally {
            if (detalleSolicitado == solicitud) _isLoadingDetail.value = false
        }
    }

    /** Final fix (F1, Codex #5): sin red y sin búsqueda ni filtro, la lista es la copia VIGENTE de la sucursal, con su hora. */
    private suspend fun ensenarLaCopia(venueId: String) {
        val guardadas = copia?.cuentas(venueId).orEmpty()
        if (secureStorage.venueId != venueId) return
        _orders.value = guardadas.map { it.cuenta }
        venueDeLaLista = venueId
        listaConBusqueda = false
        listaVistaA = guardadas.minOfOrNull { it.vistaEn }
        listaDeLas(listaVistaA, sinRed = true)
        _currentPage.value = 1
        _hasMore.value = false // la copia es todo lo que hay: nada que pedir «más» encima
    }

    fun clearSelectedOrder() {
        detalleSolicitado = null
        _detalleSinRed.value = null
        _detalleDeLaCopia.value = null
        _selectedOrder.value = null
        _isLoadingDetail.value = false
    }

    /**
     * D12 (Codex r3 H4): se fue la red DE VERDAD con «Pedidos» a la vista (nadie contesta: lo decide
     * [com.avoqado.pos.orders.presentation.OrdersViewModel.copiaAlPerderLaRed], final fix F3). Sin otra consulta ni desmontar nada: la
     * lista es la copia (final fix F1, Codex #5: también las cuentas de páginas que ya no estaban en memoria) o, si contesta una búsqueda,
     * se queda «como estaba» sin «Cargar más»; y si el detalle a la vista vino del servidor, se respalda con su cuenta de la copia
     * VIGENTE de la sucursal ACTUAL (con su hora en el aviso). Copia vencida, ausente o de otra sucursal: el detalle no cambia.
     */
    suspend fun alPerderLaRed() {
        val actual = secureStorage.venueId ?: return
        if (venueDeLaLista == actual) {
            if (!listaConBusqueda && copia != null) {
                ensenarLaCopia(actual)
            } else if (_orders.value.isNotEmpty() && _avisoDeLaLista.value == null) {
                // T12b: un aviso que ya está (la búsqueda sin red) no se pisa, llegue esto antes o después de la lectura.
                _hasMore.value = false
                listaDeLas(listaVistaA, sinRed = true)
            }
        }
        val visto = _selectedOrder.value ?: return
        // Ya está sin red (lo dejó su lectura): nada que hacer. Un pedido respaldado que dejó de estarlo (final fix F3: el servidor
        // contestó mal al volver) sí vuelve a estar sin red.
        if (copia == null || _detalleSinRed.value == visto.id) return
        val solicitud = detalleSolicitado
        val guardada = copia.cuenta(actual, visto.id) ?: return
        if (detalleSolicitado !== solicitud || _selectedOrder.value !== visto || secureStorage.venueId != actual) return
        _detalleDeLaCopia.value = guardada
        _errorMessage.value = avisoDeLaCopia(guardada)
        _detalleSinRed.value = visto.id // al volver la red, la reapertura de D22 lo pide y quita el aviso
    }
}

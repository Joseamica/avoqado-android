package com.avoqado.pos.inventory.waste.data

import com.avoqado.pos.BuildConfig
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.inventory.data.RespuestaHttp
import com.avoqado.pos.payment.data.OrderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Con quién DEBE salir una petición de merma: viaja pegado a la petición (tag de OkHttp). */
private data class AutorEsperado(val staffId: String)

/** La credencial que iba a salir no es la de quien registró (o anula) la merma: la petición no sale. */
class CredencialAjena : IOException("La credencial no es la de quien registró la merma")

/**
 * Lo que la pantalla de captura y el bloqueo de plan necesitan del catálogo: lo guardado (sirve
 * sin red), bajarlo de nuevo, y preguntarle al servidor si el plan incluye la merma.
 */
interface CatalogoDeMerma {
    suspend fun catalogo(venueId: String): List<WasteCatalogEntity>
    suspend fun catalogoActualizadoEn(venueId: String): Long?
    suspend fun refrescarCatalogo(venueId: String, ahora: Long): Boolean

    /** `true` = el plan la incluye · `false` = 403 con `featureCode` · `null` = no se supo. */
    suspend fun consultarPlan(venueId: String): Boolean?
}

/**
 * La red de la merma: manda UNA fila y baja el catálogo. No decide nada — el desenlace lo decide
 * [WasteSyncCoordinator] con `clasificarRespuestaDeMerma`.
 *
 * Todo viaja marcado de SEGUNDO PLANO (`X-Avoqado-Background`): el drenado y el refresco corren
 * solos, sin nadie enfrente, así que un 403 «overridable» no puede abrir el teclado del PIN del
 * gerente encima de otra pantalla (`ForbiddenInterceptor`).
 */
@Singleton
class WasteRepository @Inject constructor(
    private val client: OkHttpClient,
    private val catalogoDao: WasteCatalogDao,
) : TransporteDeMerma, CatalogoDeMerma, HistorialDeMerma {

    private val tipoJson = "application/json; charset=utf-8".toMediaType()

    /**
     * 🔴 Codex r1 (P1) — el servidor registra la merma a nombre de quien firma el TOKEN. El motor comprueba
     * «sesión == autor de la fila» ANTES de la petición, y entre esa comprobación y la red cabe un relevo
     * por PIN; además un 401 se reenvía con el token que haya al refrescar. Por eso se comprueba AQUÍ, en
     * un interceptor de RED: corre en CADA salida —incluido el reenvío de `TokenRefreshAuthenticator`— y
     * mira la credencial que de verdad viaja. Si no es de quien debe, la petición no sale.
     */
    private val clienteDeMerma: OkHttpClient by lazy {
        client.newBuilder()
            .addNetworkInterceptor { chain ->
                val pedida = chain.request()
                val esperado = pedida.tag(AutorEsperado::class.java)?.staffId
                if (esperado == null || WasteApi.autorDelToken(pedida.header("Authorization")) != esperado) {
                    throw CredencialAjena()
                }
                chain.proceed(pedida)
            }
            .build()
    }

    /**
     * 🔴 La URL se arma con el venue DE LA FILA, nunca con el activo (Review Focus 3): una merma
     * capturada en el Centro no puede registrarse en la Sucursal Sur porque el aparato cambió de
     * sucursal antes de subirla.
     *
     * Sin transporte devuelve `0` (no revienta): el motor lo lee como «reintentar».
     */
    override suspend fun enviar(fila: PendingWasteEntity): RespuestaHttp =
        publicar(WasteApi.urlDeMerma(venueBaseUrl(fila.venueId)), WasteApi.cuerpoDeMerma(fila), autor = fila.staffId)

    /**
     * El `void` del folio (spec §4.3), al venue DE LA FILA. También de segundo plano: la ruta no pasa
     * por `checkPermission`, así que su 403 no se puede autorizar con el PIN de un gerente, y abrir el
     * teclado prometería algo que el servidor no hace.
     */
    override suspend fun anular(fila: PendingWasteEntity, porStaffId: String): RespuestaHttp =
        publicar(
            WasteApi.urlDeAnulacion(venueBaseUrl(fila.venueId)),
            WasteApi.cuerpoDeAnulacion(fila.idempotencyKey),
            autor = porStaffId,
        )

    private suspend fun publicar(url: String, cuerpo: String, autor: String): RespuestaHttp {
        val request = Request.Builder()
            .url(url)
            .header(ForbiddenInterceptor.BACKGROUND_HEADER, "1")
            .tag(AutorEsperado::class.java, AutorEsperado(autor))
            .post(cuerpo.toRequestBody(tipoJson))
            .build()
        return try {
            clienteDeMerma.newCall(request).respuesta()
        } catch (e: IOException) {
            RespuestaHttp(0, e.message.orEmpty())
        }
    }

    /**
     * 🔴 Codex r1: `execute()` bloqueaba el hilo y no se enteraba de que la corrutina ya se había cancelado
     * — el plazo de 5 s del cierre de sesión esperaba igual los 30 s del timeout de lectura. Aquí la
     * cancelación CORTA la llamada (`Call.cancel()`), también a media lectura del cuerpo.
     *
     * Un 4xx que no viene de nuestra API (proxy, portal cautivo, Cloudflare, 408) se lee como 503: no dice
     * nada de la merma. Misma regla que los cobros (`OrderRepository.isTransient4xx`) y que iOS
     * (`APIClient.isFromIntermediary`).
     */
    private suspend fun Call.respuesta(): RespuestaHttp = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val leida = try {
                        response.use {
                            val body = it.body?.string().orEmpty()
                            val deIntermediario = OrderRepository.isTransient4xx(
                                it.code, it.header("Content-Type"), it.header("ngrok-error-code"), body,
                            )
                            if (deIntermediario) RespuestaHttp(503, "") else RespuestaHttp(it.code, body)
                        }
                    } catch (e: IOException) {
                        cont.resumeWithException(e)
                        return
                    }
                    cont.resume(leida)
                }
            },
        )
    }

    /**
     * Baja el catálogo entero de la sucursal y lo reemplaza de una sola vez; si una página falla, el
     * catálogo anterior sigue intacto (`descargarCatalogo`). `true` si quedó el nuevo.
     */
    override suspend fun refrescarCatalogo(venueId: String, ahora: Long): Boolean {
        val destino = object : CatalogoDestino {
            override suspend fun reemplazar(venueId: String, items: List<WasteCatalogItem>, actualizadoEn: Long) {
                catalogoDao.reemplazarCatalogo(
                    venueId,
                    items.map {
                        WasteCatalogEntity(venueId, it.itemType, it.itemId, it.name, it.sku, it.unit, actualizadoEn)
                    },
                )
            }
        }
        return descargarCatalogo(venueId, destino, ahora) { page -> pedirPagina(venueId, page) }
    }

    /** El catálogo guardado de la sucursal, para buscar sin red (`buscarEnCatalogo`). */
    override suspend fun catalogo(venueId: String): List<WasteCatalogEntity> = catalogoDao.catalogoDelVenue(venueId)

    /** Cuándo se bajó; `null` = nunca. Es lo que permite decir «catálogo de hace N h». */
    override suspend fun catalogoActualizadoEn(venueId: String): Long? = catalogoDao.actualizadoEn(venueId)

    /**
     * ¿El plan de esta sucursal incluye la merma? Se le pregunta por UN artículo del catálogo, que
     * pasa por el MISMO candado de plan que registrar. Sólo un 403 con `featureCode` es «no»: un
     * 403 de permiso, un 5xx o la falta de red no dicen nada del plan.
     */
    override suspend fun consultarPlan(venueId: String): Boolean? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(WasteApi.urlDeArticulos(venueBaseUrl(venueId), page = 1, pageSize = 1))
            .header(ForbiddenInterceptor.BACKGROUND_HEADER, "1")
            .build()
        try {
            client.newCall(request).execute().use { r ->
                when {
                    r.isSuccessful -> true
                    r.code == 403 && WasteApi.leerFallo(r.body?.string().orEmpty()).featureCode != null -> false
                    else -> null
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Una página del historial. De segundo plano: es una lectura, y un 403 no debe abrir el teclado del PIN
     * del gerente — el alcance lo decide el servidor con los permisos PROPIOS de quien pregunta.
     */
    override suspend fun historial(venueId: String, page: Int): ResultadoDeHistorial = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(WasteApi.urlDeHistorial(venueBaseUrl(venueId), page))
            .header(ForbiddenInterceptor.BACKGROUND_HEADER, "1")
            .build()
        try {
            client.newCall(request).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.isSuccessful -> WasteApi.parsearHistorial(body)?.let { ResultadoDeHistorial.Pagina(it) }
                        ?: ResultadoDeHistorial.Fallo
                    r.code == 403 && WasteApi.leerFallo(body).featureCode != null -> ResultadoDeHistorial.SinPlan
                    else -> ResultadoDeHistorial.Fallo
                }
            }
        } catch (e: IOException) {
            ResultadoDeHistorial.SinRed
        }
    }

    private suspend fun pedirPagina(venueId: String, page: Int): PaginaDeCatalogo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(WasteApi.urlDeArticulos(venueBaseUrl(venueId), page))
            .header(ForbiddenInterceptor.BACKGROUND_HEADER, "1")
            .build()
        try {
            client.newCall(request).execute().use { r ->
                if (r.isSuccessful) WasteApi.parsearPagina(r.body?.string().orEmpty()) else null
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Misma base que `InventoryRepository.venueBaseUrl`, y con el mismo candado: la propiedad de
     * pruebas sólo se lee en DEBUG, porque este cliente lleva `AuthInterceptor` y en release mandaría
     * el token a cualquier servidor que alguien pusiera ahí.
     */
    private fun venueBaseUrl(venueId: String): String {
        val base = (if (BuildConfig.DEBUG) System.getProperty("avoqado.test.baseUrl") else null)
            ?: ApiConstants.BASE_URL
        return "$base/mobile/venues/$venueId"
    }
}

package com.avoqado.pos.customers.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.customers.data.model.CreateCustomerRequest
import com.avoqado.pos.customers.data.model.CreateCustomerResponse
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.data.model.CustomersResponse
import com.avoqado.pos.customers.data.model.PaginaDeClientes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomersRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Seam de pruebas: apuntar a un MockWebServer. En producción es [ApiConstants.BASE_URL]. */
    @androidx.annotation.VisibleForTesting
    internal var baseUrl: String = ApiConstants.BASE_URL

    /**
     * Los clientes que esta tablet ya recibió del servidor, del negocio ACTIVO: la base de la búsqueda sin red
     * (`filtrarClientesLocal`). Sólo en memoria a propósito —son datos personales y esta función no los escribe a
     * disco— y con tope ([TOPE_GUARDADOS]).
     */
    private val guardados = LinkedHashMap<String, Customer>()
    private var negocioDeGuardados: String? = null

    /** Sólo la lista: para quien no pinta el total (selectores de cliente de reservas). */
    suspend fun fetchCustomers(search: String? = null): Result<List<Customer>> = buscarClientes(search).map { it.clientes }

    /**
     * `GET /mobile/venues/:venueId/customers`. 🔴 El servidor lee `pageSize`: la app mandaba `limit`, recibía los 20 de
     * default y filtraba local — «Dany» era el #41 y nunca llegaba (Testarudo, 14-oct). [search] lo resuelve el servidor
     * sobre TODO el negocio (teléfono por dígitos, nombre sin acentos). Devuelve también cuántos cumplen en TODO el negocio
     * y si el servidor topó la búsqueda (auditoría A #10 y B3): antes ese total se tiraba.
     */
    suspend fun buscarClientes(search: String? = null): Result<PaginaDeClientes> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))

        return try {
            val url = "$baseUrl/mobile/venues/$venueId/customers".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", PAGE_SIZE.toString())
                .addQueryParameter("sortBy", "createdAt")
                .addQueryParameter("sortOrder", "desc")
                .apply { search?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("search", it) } }
                .build()
            val request = Request.Builder().url(url).get().build()

            val (code, body) = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response -> response.code to (response.body?.string() ?: "") }
            }

            if (code in 200..299) {
                val decoded = json.decodeFromString<CustomersResponse>(body)
                Log.d("👤", "Fetched ${decoded.data.size} of ${decoded.totalDelServidor ?: "?"} customers")
                guardar(venueId, decoded.data)
                Result.success(
                    PaginaDeClientes(
                        clientes = decoded.data,
                        total = decoded.totalDelServidor,
                        busquedaTopada = decoded.meta?.searchCapped == true,
                    ),
                )
            } else {
                Log.e("👤", "Fetch customers failed: $code - $body")
                Result.failure(Exception("Error al cargar clientes ($code)"))
            }
        } catch (e: CancellationException) {
            // 🔴 Se relanza. Tragársela como `Result.failure` hacía que una búsqueda CANCELADA (el cajero siguió
            // tecleando) se leyera como un fallo y pintara la lista equivocada.
            throw e
        } catch (e: Exception) {
            Log.e("👤", "Fetch customers error: ${e.message}")
            Result.failure(e)
        }
    }

    /** Lo guardado del negocio ACTIVO; vacío si se cambió de sucursal desde la última respuesta. */
    @Synchronized
    fun clientesGuardados(): List<Customer> {
        val venueId = secureStorage.venueId ?: return emptyList()
        return if (venueId == negocioDeGuardados) guardados.values.toList() else emptyList()
    }

    @Synchronized
    private fun guardar(venueId: String, lista: List<Customer>) {
        // Una respuesta que llega después de cambiar de sucursal no se mezcla con la sucursal nueva.
        if (venueId != secureStorage.venueId) return
        if (venueId != negocioDeGuardados) {
            guardados.clear()
            negocioDeGuardados = venueId
        }
        lista.forEach { cliente ->
            if (guardados.containsKey(cliente.id) || guardados.size < TOPE_GUARDADOS) guardados[cliente.id] = cliente
        }
    }

    suspend fun createCustomer(request: CreateCustomerRequest): Result<Customer> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))

        return try {
            val requestBody = json.encodeToString(CreateCustomerRequest.serializer(), request)
                .toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url("$baseUrl/mobile/venues/$venueId/customers")
                .post(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(httpRequest).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                val decoded = json.decodeFromString<CreateCustomerResponse>(body)
                val customer = decoded.customer
                if (customer != null) {
                    Log.d("👤", "Customer created: ${customer.fullName}")
                    guardar(venueId, listOf(customer))
                    Result.success(customer)
                } else {
                    Result.failure(Exception("Respuesta inválida del servidor"))
                }
            } else {
                Log.e("👤", "Create customer failed: $code - $body")
                val errorMsg = translateError(body)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("👤", "Create customer error: ${e.message}")
            Result.failure(e)
        }
    }

    private fun translateError(body: String): String {
        return when {
            body.contains("email or phone must be provided") ->
                "Debes proporcionar al menos un correo o teléfono."
            body.contains("email already exists") || body.contains("already registered") ->
                "Este correo electrónico ya está registrado."
            body.contains("phone already exists") ->
                "Este número de teléfono ya está registrado."
            body.contains("invalid email") ->
                "El correo electrónico no es válido."
            body.contains("invalid phone") ->
                "El número de teléfono no es válido."
            else -> "Error al crear cliente"
        }
    }

    companion object {
        /** Lo que pide cada búsqueda. El servidor topa en 100. */
        const val PAGE_SIZE = 50

        /** Tope de lo guardado en memoria para buscar sin red. */
        const val TOPE_GUARDADOS = 500
    }
}

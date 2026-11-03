package com.avoqado.pos.customers.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.customers.data.model.CreateCustomerRequest
import com.avoqado.pos.customers.data.model.CreateCustomerResponse
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.data.model.CustomersResponse
import com.avoqado.pos.customers.data.model.PaginaDeClientes
import com.avoqado.pos.customers.data.model.UpdateCustomerRequest
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

/** El servidor contestó, y dijo que no ([codigo] = status HTTP). Lo distingue de un fallo de red. */
class RechazoAlEditarCliente(val codigo: Int, mensaje: String) : Exception(mensaje)

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

    /** Cuenta que sólo sube: cada búsqueda toma la suya al SALIR y cada edición al GUARDAR. */
    private var reloj = 0L

    /**
     * Por id, el [reloj] de su última edición: una búsqueda que salió antes trae el dato viejo y no lo pisa (D4). No se
     * borra al cambiar de sucursal: una búsqueda vieja puede llegar al volver (los ids no se repiten entre sucursales).
     */
    private val editadoEn = HashMap<String, Long>()

    @Synchronized
    private fun tic(): Long = ++reloj

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

        val salida = tic()
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
                guardar(venueId, decoded.data, salida)
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

    /**
     * [salida]: el [reloj] con que salió la petición; `null` = una edición (manda siempre; lleno, saca al más viejo).
     * Un alta manda 0: si su respuesta llega tarde, una edición del mismo cliente ya es más nueva.
     */
    @Synchronized
    private fun guardar(venueId: String, lista: List<Customer>, salida: Long?) {
        // Una respuesta que llega después de cambiar de sucursal no se mezcla con la sucursal nueva.
        if (venueId != secureStorage.venueId) return
        if (venueId != negocioDeGuardados) {
            guardados.clear()
            negocioDeGuardados = venueId
        }
        lista.forEach { cliente ->
            if (salida != null && (editadoEn[cliente.id] ?: Long.MIN_VALUE) > salida) return@forEach
            if (!guardados.containsKey(cliente.id) && guardados.size >= TOPE_GUARDADOS) {
                if (salida != null) return@forEach
                guardados.remove(guardados.keys.first()) // la edición entra; sale el más viejo (sigue el tope)
            }
            guardados[cliente.id] = cliente
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
                    guardar(venueId, listOf(customer), salida = 0L)
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

    /**
     * Editar cliente (26-oct-2026). Online-only: un fallo de red sale tal cual (el ViewModel lo dice como «Sin conexión»).
     * El servidor responde sólo los datos de contacto; se fusionan sobre [original] (lo que la tablet ya tenía: visitas,
     * gasto…) y eso se devuelve y se guarda para buscar sin red.
     * Va por el cliente compartido: si el servidor dice 403 con PIN de gerente prendido, `ForbiddenInterceptor` abre el
     * teclado y reintenta solo.
     */
    suspend fun updateCustomer(original: Customer, request: UpdateCustomerRequest): Result<Customer> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No hay sucursal activa"))
        return try {
            val requestBody = json.encodeToString(UpdateCustomerRequest.serializer(), request)
                .toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url("$baseUrl/mobile/venues/$venueId/customers/${original.id}")
                .put(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                client.newCall(httpRequest).execute().use { response -> response.code to (response.body?.string() ?: "") }
            }

            if (code in 200..299) {
                val contacto = json.decodeFromString<CreateCustomerResponse>(body).customer
                    ?: return Result.failure(Exception("Respuesta inválida del servidor"))
                val actualizado = original.copy(
                    firstName = contacto.firstName,
                    lastName = contacto.lastName,
                    phone = contacto.phone,
                    email = contacto.email,
                )
                Log.d("👤", "Customer updated: ${actualizado.id}")
                synchronized(this) {
                    guardar(venueId, listOf(actualizado), salida = null)
                    editadoEn[actualizado.id] = tic()
                }
                Result.success(actualizado)
            } else {
                Log.e("👤", "Update customer failed: $code - $body")
                Result.failure(RechazoAlEditarCliente(code, errorAlEditar(code, body)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("👤", "Update customer error: ${e.message}")
            Result.failure(e)
        }
    }

    /** Por CÓDIGO del servidor, no por su texto en inglés (Codex r1 #6). */
    internal fun errorAlEditar(code: Int, body: String): String {
        val error = runCatching { json.decodeFromString<ErrorDelServidor>(body) }.getOrNull()
        // Un 400 de validación ya trae el motivo en español: «Error de validación: email: El correo no es válido».
        val motivo = error?.message?.takeIf { it.startsWith("Error de validación:") }?.substringAfterLast(": ")
        return when {
            error?.code == "CUSTOMER_PHONE_TAKEN" -> "Ese teléfono ya es de otro cliente."
            error?.code == "CUSTOMER_EMAIL_TAKEN" -> "Ese correo ya es de otro cliente."
            code == 400 && !motivo.isNullOrBlank() -> motivo
            code == 403 -> "No tienes permiso para editar clientes."
            code == 404 -> "Este cliente ya no existe en el negocio."
            else -> "No se pudo guardar el cliente. Intenta de nuevo."
        }
    }

    @kotlinx.serialization.Serializable
    private data class ErrorDelServidor(val code: String? = null, val message: String? = null)

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

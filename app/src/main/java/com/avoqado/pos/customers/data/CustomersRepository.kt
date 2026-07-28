package com.avoqado.pos.customers.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.customers.data.model.CreateCustomerRequest
import com.avoqado.pos.customers.data.model.CreateCustomerResponse
import com.avoqado.pos.customers.data.model.Customer
import com.avoqado.pos.customers.data.model.CustomersResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

    suspend fun fetchCustomers(): Result<List<Customer>> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))

        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/customers?limit=50&sortBy=createdAt&sortOrder=desc")
                .get()
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                val decoded = json.decodeFromString<CustomersResponse>(body)
                Log.d("👤", "Fetched ${decoded.data.size} customers")
                Result.success(decoded.data)
            } else {
                Log.e("👤", "Fetch customers failed: $code - $body")
                Result.failure(Exception("Error al cargar clientes ($code)"))
            }
        } catch (e: Exception) {
            Log.e("👤", "Fetch customers error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun createCustomer(request: CreateCustomerRequest): Result<Customer> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))

        return try {
            val requestBody = json.encodeToString(CreateCustomerRequest.serializer(), request)
                .toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/customers")
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
                    Result.success(customer)
                } else {
                    Result.failure(Exception("Respuesta invalida del servidor"))
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
                "Este correo electronico ya esta registrado."
            body.contains("phone already exists") ->
                "Este número de teléfono ya esta registrado."
            body.contains("invalid email") ->
                "El correo electronico no es valido."
            body.contains("invalid phone") ->
                "El número de teléfono no es valido."
            else -> "Error al crear cliente"
        }
    }
}

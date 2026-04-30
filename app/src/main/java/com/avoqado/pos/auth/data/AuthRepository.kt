package com.avoqado.pos.auth.data

import android.util.Log
import com.avoqado.pos.auth.data.model.AuthError
import com.avoqado.pos.auth.data.model.LoginRequest
import com.avoqado.pos.auth.data.model.LoginResult
import com.avoqado.pos.auth.data.model.RefreshRequest
import com.avoqado.pos.auth.data.model.VenueData
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.StoredVenue
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.notifications.data.NotificationsRepository
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val secureStorage: SecureStorage,
    private val productsRepository: ProductsRepository,
    private val discountsRepository: DiscountsRepository,
    private val tpvSettingsRepository: TpvSettingsRepository,
    private val savedCartsRepository: SavedCartsRepository,
    private val inventoryRepository: InventoryRepository,
    private val transactionRepository: TransactionRepository,
    private val notificationsRepository: NotificationsRepository,
) {
    // Event emitted when venue changes — CartViewModel observes this to clear the cart
    private val _venueSwitched = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val venueSwitched: SharedFlow<Unit> = _venueSwitched.asSharedFlow()

    suspend fun loginWithEmail(
        email: String,
        password: String,
        rememberMe: Boolean = true,
    ): LoginResult {
        Log.d("🔐", "loginWithEmail - email: $email, rememberMe: $rememberMe")

        return try {
            val response = apiService.login(
                LoginRequest(
                    email = email.trim().lowercase(),
                    password = password,
                    rememberMe = rememberMe,
                ),
            )

            if (response.success && response.accessToken != null && response.user != null) {
                val user = response.user
                val venues = user.venues
                val primaryVenue = venues.firstOrNull()

                if (primaryVenue == null) {
                    Log.e("🔐", "Login failed: no venues")
                    return LoginResult.Error("No tienes acceso a ningún establecimiento.")
                }

                // Save session data
                secureStorage.saveLogin(
                    userId = user.id,
                    email = user.email,
                    firstName = user.firstName,
                    lastName = user.lastName,
                    venueId = primaryVenue.id,
                    venueName = primaryVenue.name,
                    venueSlug = primaryVenue.slug,
                    role = primaryVenue.role,
                    accessToken = response.accessToken,
                    refreshToken = response.refreshToken ?: "",
                    venueTimezone = primaryVenue.timezone,
                    venuePermissions = primaryVenue.permissions,
                )

                // Save all venues for switching
                secureStorage.venuesList = venues.map { it.toStoredVenue() }

                Log.d("🔐", "✅ Login successful - user: ${user.id}, venue: ${primaryVenue.name}")

                LoginResult.Success(
                    userId = user.id,
                    email = user.email,
                    firstName = user.firstName,
                    lastName = user.lastName,
                    venues = venues,
                )
            } else {
                val message = response.message ?: "Error al iniciar sesión"
                Log.e("🔐", "❌ Login failed: $message")
                LoginResult.Error(message)
            }
        } catch (e: retrofit2.HttpException) {
            val errorMessage = when (e.code()) {
                401 -> "Correo electrónico o contraseña incorrectos."
                403 -> parseForbiddenError(e)
                else -> "Error del servidor (${e.code()})"
            }
            Log.e("🔐", "❌ Login HTTP error: ${e.code()} - $errorMessage")
            LoginResult.Error(errorMessage)
        } catch (e: Exception) {
            Log.e("🔐", "❌ Login exception: ${e.message}")
            LoginResult.Error("Error de conexión. Verifica tu internet.")
        }
    }

    suspend fun refreshTokensForBiometric(refreshToken: String): Pair<String, String> {
        Log.d("🔐", "refreshTokensForBiometric - refreshing...")

        val response = apiService.refreshToken(RefreshRequest(refreshToken))
        secureStorage.updateTokens(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
        )
        Log.d("🔐", "✅ Biometric token refresh successful")
        return Pair(response.accessToken, response.refreshToken)
    }

    suspend fun switchVenue(venue: StoredVenue) {
        Log.d("🔄", "Switching to venue: ${venue.name}")

        // 1. Update SecureStorage with new venue
        secureStorage.switchVenue(venue)

        // 2. Clear ALL venue-specific cached data
        productsRepository.clearCache()
        discountsRepository.clearCache()
        tpvSettingsRepository.clearCache()
        savedCartsRepository.clearCache()
        inventoryRepository.clearCache()
        transactionRepository.clearCache()
        notificationsRepository.clearCache()

        // 3. Refetch essential data for the new venue
        productsRepository.fetchProducts()
        discountsRepository.fetchDiscounts()
        tpvSettingsRepository.refreshSettings()

        // 4. Notify observers (CartViewModel) to clear cart
        _venueSwitched.tryEmit(Unit)

        Log.d("🔄", "✅ Switched to venue: ${venue.name}")
    }

    fun logout() {
        Log.d("🔐", "logout - clearing session")
        secureStorage.clearSession()
    }

    fun isLoggedIn(): Boolean = secureStorage.isLoggedIn

    private fun parseForbiddenError(e: retrofit2.HttpException): String {
        return try {
            val body = e.response()?.errorBody()?.string() ?: ""
            when {
                body.contains("verify", ignoreCase = true) ->
                    "Por favor verifica tu correo electrónico antes de iniciar sesión."
                body.contains("locked", ignoreCase = true) ->
                    "Cuenta bloqueada temporalmente. Intenta de nuevo más tarde."
                body.contains("NO_VENUE_ACCESS", ignoreCase = true) ->
                    "No tienes acceso a ningún establecimiento."
                else -> "Acceso denegado."
            }
        } catch (_: Exception) {
            "Acceso denegado."
        }
    }
}

private fun VenueData.toStoredVenue() = StoredVenue(
    id = id,
    name = name,
    slug = slug,
    logo = logo,
    role = role,
    timezone = timezone,
    permissions = permissions,
)

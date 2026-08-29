package com.avoqado.pos.auth.data

import android.util.Log
import com.avoqado.pos.auth.data.model.AuthError
import com.avoqado.pos.auth.data.model.LoginRequest
import com.avoqado.pos.auth.data.model.LoginResult
import com.avoqado.pos.auth.data.model.RefreshRequest
import com.avoqado.pos.auth.data.model.SwitchUserRequest
import com.avoqado.pos.auth.data.model.VenueData
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.StoredVenue
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.notifications.data.NotificationsRepository
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.PromotionsRepository
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
    private val promotionsRepository: PromotionsRepository,
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

    /**
     * Cambiar de usuario con PIN, sin cerrar sesión.
     *
     * Lo pidió el founder así (2026-08-29): «en lugar de que tenga que cerrar sesión y poner su
     * mail y contraseña otra vez… que salga el pinpad», y **«es como un logout login pero con
     * pin»**. Esa frase es el contrato: el servidor devuelve la MISMA forma que el login
     * (`LoginResponse`), así que aquí se guarda con el MISMO `saveLogin` — un segundo camino de
     * guardado es justo donde se quedaría un permiso viejo sin que nadie se entere.
     *
     * 🔴 Lo que NO se toca: el selector «Vendiendo: X» de la pantalla de cobro sigue siendo libre
     * y sin PIN (decisión del founder). Cambiar de vendedor es un gesto de mostrador; cambiar de
     * usuario es tomar posesión del aparato.
     *
     * 🔴 Sin red no se cambia de usuario, y se dice. No se guarda ningún verificador de PIN en el
     * aparato: cuatro dígitos se rompen probando diez mil veces.
     */
    suspend fun switchUser(pin: String): SwitchUserResult {
        val venueId = secureStorage.venueId
        if (venueId.isNullOrBlank()) {
            Log.e("🔐", "switchUser sin venue local")
            return SwitchUserResult.Error("Vuelve a iniciar sesión para continuar.")
        }

        return try {
            val response = apiService.switchUser(venueId, SwitchUserRequest(pin))
            val user = response.user
            val venue = user?.venues?.firstOrNull()

            if (!response.success || response.accessToken == null || user == null || venue == null) {
                return SwitchUserResult.Error(response.message ?: "No se pudo cambiar de usuario.")
            }

            // Reemplaza la sesión ENTERA: rol y permisos incluidos. Es lo que hace que el menú, los
            // botones y lo que se ve se repinten para quien acaba de entrar.
            secureStorage.saveLogin(
                userId = user.id,
                email = user.email,
                firstName = user.firstName,
                lastName = user.lastName,
                venueId = venue.id,
                venueName = venue.name,
                venueSlug = venue.slug,
                role = venue.role,
                accessToken = response.accessToken,
                refreshToken = response.refreshToken ?: "",
                venueTimezone = venue.timezone,
                venuePermissions = venue.permissions,
            )

            // 🔴 `venuesList` TAMBIÉN se reemplaza. Se descubrió en el simulador de iPad, donde la
            // tarjeta de identidad saca el rol de esta lista y no de `saveLogin`: con sólo
            // `saveLogin`, el nombre cambiaba a la persona nueva y la píldora seguía diciendo
            // «Propietario». Un rol que miente sobre lo que alguien puede hacer es peor que no
            // mostrarlo. Se arregla en las DOS apps aunque el síntoma se viera en una: la lista
            // la leen varias pantallas y el dato quedaba viejo igual.
            secureStorage.venuesList = listOf(venue.toStoredVenue())

            // 🔴 La venta se atribuye a quien acaba de entrar. Sin esto, entra Ana y las ventas
            // siguen saliendo a nombre del anterior. El cajero puede cambiarlo enseguida desde
            // Cobrar, sin PIN — eso no se toca.
            secureStorage.saveSelectedStaffForCurrentVenue(
                staffId = user.id,
                staffName = listOfNotNull(user.firstName, user.lastName).joinToString(" ").ifBlank { user.email },
            )

            Log.d("🔐", "✅ Cambio de usuario: ${user.email} (${venue.role})")
            SwitchUserResult.Success(userId = user.id, firstName = user.firstName, role = venue.role)
        } catch (e: retrofit2.HttpException) {
            // 401 es el rechazo esperado del PIN; el resto se nombra sin adornos.
            val message = when (e.code()) {
                401 -> "PIN incorrecto"
                429 -> "Demasiados intentos. Espera unos minutos o inicia sesión con tu contraseña."
                else -> "Error del servidor (${e.code()})"
            }
            Log.e("🔐", "❌ switchUser HTTP ${e.code()}")
            SwitchUserResult.Error(message)
        } catch (e: Exception) {
            Log.e("🔐", "❌ switchUser: ${e.message}")
            SwitchUserResult.Error("Sin conexión. Para cambiar de usuario necesitas internet.")
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

    /**
     * Re-emite la sesión para el venue que ya está seleccionado localmente.
     *
     * Esto repara sesiones creadas por versiones anteriores donde el storage
     * cambió de venue, pero el JWT conservó el venue previo. Debe ejecutarse
     * antes de arrancar cualquier cola offline: `sync/intents` sí exige que el
     * venue de la URL coincida con el del token.
     *
     * Falla abierto cuando no hay red. El POS conserva su sesión y puede seguir
     * trabajando offline; el siguiente arranque o cambio de venue reintentará.
     */
    suspend fun repairCurrentVenueBinding(): Boolean {
        val venueId = secureStorage.venueId?.takeIf { it.isNotBlank() } ?: return false
        val refresh = secureStorage.refreshToken?.takeIf { it.isNotBlank() } ?: return false

        return try {
            val tokens = apiService.refreshToken(RefreshRequest(refresh, venueId = venueId))
            secureStorage.updateTokens(tokens.accessToken, tokens.refreshToken)
            Log.d("🔄", "✅ Token ligado al venue actual")
            true
        } catch (e: Exception) {
            Log.w("🔄", "No se pudo ligar el token al venue actual: ${e.message}")
            false
        }
    }

    suspend fun switchVenue(venue: StoredVenue) {
        Log.d("🔄", "Switching to venue: ${venue.name}")

        // 1. Update SecureStorage with new venue
        secureStorage.switchVenue(venue)

        // 1.b 🔴 Re-emitir el token para el local nuevo.
        //
        // Sin esto la sesión quedaba partida: el storage decía un local y el
        // token seguía diciendo el anterior. Casi todo seguía "funcionando"
        // porque el server lee el venue de la URL, pero `sync/intents` compara
        // la URL contra el token y respondía 403 en bucle: los cobros hechos sin
        // red se quedaban atorados en el outbox sin subir nunca, sin aviso.
        //
        // Si falla (sin red), NO se aborta el cambio de local: el POS tiene que
        // poder vender igual. El token se re-emitirá en el siguiente refresh,
        // que ya conserva el venue.
        if (!repairCurrentVenueBinding() && secureStorage.refreshToken.isNullOrBlank()) {
            // Sin refresh token no hay forma de re-emitir: la sesión queda atada
            // al local anterior hasta el siguiente login. Se avisa en vez de
            // callar, que es justo lo que escondía el desajuste.
            Log.w("🔄", "Sin refresh token guardado: el token sigue apuntando al local anterior")
        }

        // 2. Clear ALL venue-specific cached data
        productsRepository.clearCache()
        discountsRepository.clearCache()
        promotionsRepository.clearCache()
        tpvSettingsRepository.clearCache()
        savedCartsRepository.clearCache()
        inventoryRepository.clearCache()
        transactionRepository.clearCache()
        notificationsRepository.clearCache()

        // 3. Refetch essential data for the new venue
        productsRepository.fetchProducts()
        discountsRepository.fetchDiscounts()
        promotionsRepository.refresh(venue.id)
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
    organizationId = organizationId,
)

/** Resultado de cambiar de usuario con PIN. */
sealed class SwitchUserResult {
    data class Success(val userId: String, val firstName: String?, val role: String?) : SwitchUserResult()
    data class Error(val message: String) : SwitchUserResult()
}

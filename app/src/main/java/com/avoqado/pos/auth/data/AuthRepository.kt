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
import com.avoqado.pos.core.data.network.RefrescoExclusivo
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.notifications.data.NotificationsRepository
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.PromotionsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    /**
     * 🔴 El MISMO candado que usa `TokenRefreshAuthenticator`. Los dos refrescos de la app
     * salían por caminos distintos sin verse: el 2026-09-02 se solaparon en una Sunmi D3 y
     * el segundo, al presentar un refresh token que el servidor acababa de rotar, tumbó la
     * sesión del cajero. Ver `RefrescoExclusivo`.
     */
    private val refrescoExclusivo: RefrescoExclusivo,
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
        // D4: si mientras viaja este login entra o sale alguien, su respuesta tardía no se instala.
        val sello = secureStorage.generacion

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

                // Save session data (y la lista de venues del MISMO login, en el mismo paso: D4)
                val instalada = secureStorage.instalarSiSigue(sello) {
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
                    secureStorage.venuesList = venues.map { it.toStoredVenue() }
                }
                if (instalada == null) {
                    Log.w("🔐", "Login tardío: ya entró otra persona en este aparato, no se instala")
                    return LoginResult.Error(OTRA_PERSONA_ENTRO)
                }

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

        // D4: un PIN cuya respuesta llega después de que entró o salió alguien no se instala. Si la sesión de quien sale
        // se cierra sola mientras viaja (el SERVIDOR la revoca al aceptar el PIN), la pantalla ya volvió al acceso y esta
        // petición se cancela con ella, como hoy: se vuelve a entrar (decisión documentada en el plan v3).
        val sello = secureStorage.generacion

        return try {
            val response = apiService.switchUser(venueId, SwitchUserRequest(pin))
            val user = response.user
            val venue = user?.venues?.firstOrNull()

            if (!response.success || response.accessToken == null || user == null || venue == null) {
                return SwitchUserResult.Error(response.message ?: "No se pudo cambiar de usuario.")
            }

            // Reemplaza la sesión ENTERA: rol y permisos incluidos. Es lo que hace que el menú, los
            // botones y lo que se ve se repinten para quien acaba de entrar.
            val instalada = secureStorage.instalarSiSigue(sello) {
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
            }
            if (instalada == null) {
                Log.w("🔐", "PIN tardío: ya entró otra persona en este aparato, no se instala")
                return SwitchUserResult.Error(OTRA_PERSONA_ENTRO)
            }

            Log.d("🔐", "✅ Cambio de usuario: ${user.email} (${venue.role})")
            SwitchUserResult.Success(userId = user.id, firstName = user.firstName, role = venue.role)
        } catch (e: retrofit2.HttpException) {
            // 401 es el rechazo esperado del PIN; el resto se nombra sin adornos.
            val message = when (e.code()) {
                401 -> "PIN incorrecto"
                429 -> "Demasiados intentos. Espera unos minutos o inicia sesión con tu contraseña."
                // 🔴 [Auditoría 2026-08-30] Un 403 aquí NO es un PIN equivocado: es el servidor
                // diciendo por qué ESTA sesión no puede relevar (el negocio está suspendido, o la
                // sesión nació sin aparato y hay que volver a entrar con contraseña). Ese texto ya
                // viene escrito para el usuario, así que se muestra tal cual en vez de taparlo con
                // "Error del servidor (403)", que no le dice a nadie qué hacer.
                //
                // Se lee el CAMPO `message` del JSON, nunca buscando palabras dentro del cuerpo:
                // el `parseForbiddenError` de este mismo archivo hace substring matching y es el
                // antipatrón que ya costó un botón fantasma en la PAX. Un cuerpo ilegible cae al
                // texto por defecto, nunca a una interpretación inventada.
                403 -> mensajeDelServidor(e) ?: "No puedes cambiar de usuario en este aparato."
                else -> "Error del servidor (${e.code()})"
            }
            Log.e("🔐", "❌ switchUser HTTP ${e.code()}")
            SwitchUserResult.Error(message)
        } catch (e: Exception) {
            Log.e("🔐", "❌ switchUser: ${e.message}")
            SwitchUserResult.Error("Sin conexión. Para cambiar de usuario necesitas internet.")
        }
    }

    /**
     * Corre el refresco bajo el candado compartido, sin bloquear el hilo principal.
     *
     * `withContext(Dispatchers.IO)`: `RefrescoExclusivo` usa un `ReentrantLock` —el único
     * candado que pueden tomar los dos mundos, el hilo de OkHttp del autenticador y esta
     * corrutina— y esperarlo bloquea el hilo que lo pide. En IO eso es lo esperado; en Main
     * congelaría la pantalla mientras el otro refresco termina.
     */
    private suspend fun <T> enExclusiva(bloque: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            // runBlocking DENTRO del candado a propósito: un ReentrantLock pertenece al HILO
            // que lo tomó, así que si el bloque suspendiera y se reanudara en otro hilo, el
            // unlock reventaría con IllegalMonitorStateException. Así todo —tomar, ejecutar,
            // soltar— ocurre en el mismo hilo de IO.
            refrescoExclusivo.enExclusiva { runBlocking { bloque() } }
        }

    /**
     * Entra con la huella (D4). Pide la renovación con la llave de la huella y, dentro del mismo candado, guarda la llave
     * rotada en la reserva de la huella SIEMPRE (comparando con la enviada: con la vieja, la siguiente huella mandaría un
     * refresh consumido y el servidor revocaría la sesión, Codex plan r1 #2) y luego instala la sesión, llaves e
     * identidad juntas, sólo si nadie entró ni salió desde [sello]. `false` = ya entró otra persona: no se instala.
     */
    suspend fun entrarConHuella(refreshToken: String, venue: StoredVenue?, sello: Long): Boolean {
        Log.d("🔐", "entrarConHuella - refreshing...")
        if (venue == null || secureStorage.biometricUserId == null) throw IllegalStateException("Missing biometric session data")

        return enExclusiva {
            // Antes de MANDAR la llave (Codex código r4 #1): si mientras esperaba turno ya entró alguien (por ejemplo, otro
            // toque de huella que terminó primero), esta llave puede estar consumida y mandarla revocaría la sesión nueva.
            if (!secureStorage.siSigueLaGeneracion(sello)) return@enExclusiva false
            val response = apiService.refreshToken(RefreshRequest(refreshToken))
            secureStorage.guardarRotadaDeLaHuellaSiSigue(refreshToken, response.refreshToken)
            val instalada = secureStorage.instalarSiSigue(sello) {
                secureStorage.updateTokens(accessToken = response.accessToken, refreshToken = response.refreshToken)
                secureStorage.restoreBiometricSession(venue)
            }
            Log.d("🔐", if (instalada != null) "✅ Biometric login" else "Huella tardía: ya entró otra persona, no se instala")
            instalada != null
        }
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
        // D4: si la sucursal cambia con la renovación en vuelo, las llaves rotadas se guardan igual (tirarlas deja un
        // refresh consumido) y se da UNA vuelta más por la sucursal que quedó (Codex plan r1 #4). Las dos vueltas son de
        // la sesión con que empezó: si mientras tanto entró otra persona, la segunda no la adopta (Codex código r1 #4).
        val sello = secureStorage.generacion
        repeat(2) {
            when (ligarAlVenueActual(sello)) {
                Ligado.SI -> return true
                Ligado.NO -> return false
                Ligado.CAMBIO_LA_SUCURSAL -> Log.w("🔄", "La sucursal cambió mientras se ligaba: otra vuelta por la vigente")
            }
        }
        return false
    }

    private enum class Ligado { SI, NO, CAMBIO_LA_SUCURSAL }

    private suspend fun ligarAlVenueActual(sello: Long): Ligado {
        val venueId = secureStorage.venueId?.takeIf { it.isNotBlank() } ?: return Ligado.NO

        return try {
            // 🔴 El refresh token se relee DENTRO del candado: si el autenticador acaba de
            // rotarlo mientras esperábamos turno, el de fuera ya está consumido y mandarlo
            // sería exactamente el doble refresco que este candado existe para evitar.
            // Pedir y GUARDAR van los dos adentro: guardando afuera, otro renovador podía tomar turno con el refresh
            // que esta petición acababa de consumir (Codex plan r1).
            enExclusiva {
                // Si mientras esperaba turno entró o salió alguien, o se eligió otra sucursal, no se pide nada: esta
                // reparación ya no es de la sesión ni del local que hay (D4; el cambio de sucursal pide la suya).
                // Sesión, sucursal y llave se leen en un solo paso: entre mirar y leer no se cuela otra persona.
                val vigente = secureStorage.refreshSiSigue(sello, venueId) ?: return@enExclusiva Ligado.NO
                val tokens = apiService.refreshToken(RefreshRequest(vigente, venueId = venueId))
                // Si mientras tanto entró otra persona o se cerró la sesión, no se pisa (Codex, D9 del Cambio, 29-oct).
                if (!secureStorage.updateTokensSiSigue(vigente, tokens.accessToken, tokens.refreshToken)) {
                    Log.w("🔄", "La sesión cambió mientras se ligaba al venue: no se guarda")
                    Ligado.NO
                } else if (secureStorage.venueId != venueId) {
                    Ligado.CAMBIO_LA_SUCURSAL
                } else {
                    Log.d("🔄", "✅ Token ligado al venue actual")
                    Ligado.SI
                }
            }
        } catch (e: Exception) {
            Log.w("🔄", "No se pudo ligar el token al venue actual: ${e.message}")
            Ligado.NO
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

    companion object {
        /** Un intento de entrar cuya respuesta llegó después de que entró o salió alguien (D4). */
        const val OTRA_PERSONA_ENTRO = "Ya entró otra persona en este aparato."
    }

    /**
     * El campo `message` del cuerpo de error, o null si no se puede leer.
     *
     * Lee la LLAVE, no el texto: comparar palabras dentro del cuerpo (como hace
     * `parseForbiddenError` aquí abajo) rompe en cuanto alguien reescribe una frase en el
     * servidor, y falla en silencio — el usuario ve el mensaje genérico y nadie se entera.
     */
    private fun mensajeDelServidor(e: retrofit2.HttpException): String? = try {
        mensajeDelCuerpo(e.response()?.errorBody()?.string())
    } catch (_: Exception) {
        null
    }

    /** El cuerpo de un error sólo se puede leer UNA vez: quien ya lo leyó le pasa el texto. */
    private fun mensajeDelCuerpo(body: String?): String? = try {
        if (body.isNullOrBlank()) null
        else org.json.JSONObject(body).optString("message").takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

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
                // Cualquier otro rechazo (negocio suspendido o cerrado, alta sin terminar…) trae en `message` qué pasa
                // y qué hacer: se muestra tal cual. «Acceso denegado.» a secas no le decía nada al dueño (D7, 26-oct).
                else -> mensajeDelCuerpo(body) ?: "Acceso denegado."
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
    venueType = type,
)

/** Resultado de cambiar de usuario con PIN. */
sealed class SwitchUserResult {
    data class Success(val userId: String, val firstName: String?, val role: String?) : SwitchUserResult()
    data class Error(val message: String) : SwitchUserResult()
}

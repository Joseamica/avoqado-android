package com.avoqado.pos.core.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "avoqado_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val plainPrefs: SharedPreferences =
        context.getSharedPreferences("avoqado_prefs", Context.MODE_PRIVATE)

    // MARK: - Session Data

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var userEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_USER_EMAIL, value).apply()

    var userFirstName: String?
        get() = prefs.getString(KEY_USER_FIRST_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_FIRST_NAME, value).apply()

    var userLastName: String?
        get() = prefs.getString(KEY_USER_LAST_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_LAST_NAME, value).apply()

    var userRole: String?
        get() = prefs.getString(KEY_USER_ROLE, null)
        set(value) = prefs.edit().putString(KEY_USER_ROLE, value).apply()

    var venueId: String?
        get() = prefs.getString(KEY_VENUE_ID, null)
        set(value) = prefs.edit().putString(KEY_VENUE_ID, value).apply()

    var venueName: String?
        get() = prefs.getString(KEY_VENUE_NAME, null)
        set(value) = prefs.edit().putString(KEY_VENUE_NAME, value).apply()

    var venueSlug: String?
        get() = prefs.getString(KEY_VENUE_SLUG, null)
        set(value) = prefs.edit().putString(KEY_VENUE_SLUG, value).apply()

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    // MARK: - Biometric Data (NOT cleared on logout)

    var isBiometricLoginEnabled: Boolean
        get() = plainPrefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = plainPrefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    var biometricRefreshToken: String?
        get() = prefs.getString(KEY_BIOMETRIC_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_REFRESH_TOKEN, value).apply()

    var biometricUserEmail: String?
        get() = prefs.getString(KEY_BIOMETRIC_USER_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_USER_EMAIL, value).apply()

    var biometricUserId: String?
        get() = prefs.getString(KEY_BIOMETRIC_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_USER_ID, value).apply()

    var biometricVenueId: String?
        get() = prefs.getString(KEY_BIOMETRIC_VENUE_ID, null)
        set(value) = prefs.edit().putString(KEY_BIOMETRIC_VENUE_ID, value).apply()

    // MARK: - Device Data

    var serialNumber: String
        get() {
            val existing = prefs.getString(KEY_SERIAL_NUMBER, null)
            if (existing != null) return existing
            val generated = "AVQD-${UUID.randomUUID().toString().replace("-", "").take(12).uppercase()}"
            prefs.edit().putString(KEY_SERIAL_NUMBER, generated).apply()
            return generated
        }
        set(value) = prefs.edit().putString(KEY_SERIAL_NUMBER, value).apply()

    var terminalId: String?
        get() = prefs.getString(KEY_TERMINAL_ID, null)
        set(value) = prefs.edit().putString(KEY_TERMINAL_ID, value).apply()

    var userPin: String?
        get() = prefs.getString(KEY_USER_PIN, null)
        set(value) = prefs.edit().putString(KEY_USER_PIN, value).apply()

    // MARK: - Venues List

    var venuesList: List<StoredVenue>
        get() {
            val raw = plainPrefs.getString(KEY_VENUES_LIST, null) ?: return emptyList()
            return try {
                json.decodeFromString(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(value) {
            plainPrefs.edit().putString(KEY_VENUES_LIST, json.encodeToString(value)).apply()
        }

    var biometricVenuesList: List<StoredVenue>
        get() {
            val raw = plainPrefs.getString(KEY_BIOMETRIC_VENUES_LIST, null) ?: return emptyList()
            return try {
                json.decodeFromString(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(value) {
            plainPrefs.edit().putString(KEY_BIOMETRIC_VENUES_LIST, json.encodeToString(value)).apply()
        }

    // MARK: - Login helpers

    val isLoggedIn: Boolean
        get() = accessToken != null && userId != null

    fun saveLogin(
        userId: String,
        email: String,
        firstName: String?,
        lastName: String?,
        venueId: String,
        venueName: String,
        venueSlug: String?,
        role: String?,
        accessToken: String,
        refreshToken: String,
    ) {
        this.userId = userId
        this.userEmail = email
        this.userFirstName = firstName
        this.userLastName = lastName
        this.venueId = venueId
        this.venueName = venueName
        this.venueSlug = venueSlug
        this.userRole = role
        this.accessToken = accessToken
        this.refreshToken = refreshToken
    }

    fun updateTokens(accessToken: String, refreshToken: String) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
    }

    fun switchVenue(venue: StoredVenue) {
        this.venueId = venue.id
        this.venueName = venue.name
        this.venueSlug = venue.slug
        this.userRole = venue.role
    }

    fun saveBiometricCredentials(
        userId: String,
        email: String,
        venueId: String,
        refreshToken: String,
        venues: List<StoredVenue>,
    ) {
        biometricUserId = userId
        biometricUserEmail = email
        biometricVenueId = venueId
        biometricRefreshToken = refreshToken
        biometricVenuesList = venues
        isBiometricLoginEnabled = true
    }

    fun restoreBiometricSession(venue: StoredVenue?): Boolean {
        val restoredUserId = biometricUserId ?: return false
        val restoredVenue = venue ?: return false

        userId = restoredUserId
        userEmail = biometricUserEmail
        switchVenue(restoredVenue)
        return true
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_FIRST_NAME)
            .remove(KEY_USER_LAST_NAME)
            .remove(KEY_USER_ROLE)
            .remove(KEY_VENUE_ID)
            .remove(KEY_VENUE_NAME)
            .remove(KEY_VENUE_SLUG)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TERMINAL_ID)
            .apply()
    }

    fun clearBiometricCredentials() {
        prefs.edit()
            .remove(KEY_BIOMETRIC_REFRESH_TOKEN)
            .remove(KEY_BIOMETRIC_USER_EMAIL)
            .remove(KEY_BIOMETRIC_USER_ID)
            .remove(KEY_BIOMETRIC_VENUE_ID)
            .apply()
        plainPrefs.edit()
            .remove(KEY_BIOMETRIC_ENABLED)
            .remove(KEY_BIOMETRIC_VENUES_LIST)
            .apply()
    }

    fun clearAll() {
        clearSession()
        clearBiometricCredentials()
        prefs.edit().clear().apply()
        plainPrefs.edit().clear().apply()
    }

    companion object {
        // Session
        private const val KEY_USER_ID = "userId"
        private const val KEY_USER_EMAIL = "userEmail"
        private const val KEY_USER_FIRST_NAME = "userFirstName"
        private const val KEY_USER_LAST_NAME = "userLastName"
        private const val KEY_USER_ROLE = "userRole"
        private const val KEY_VENUE_ID = "venueId"
        private const val KEY_VENUE_NAME = "venueName"
        private const val KEY_VENUE_SLUG = "venueSlug"
        private const val KEY_ACCESS_TOKEN = "accessToken"
        private const val KEY_REFRESH_TOKEN = "refreshToken"
        // Biometric
        private const val KEY_BIOMETRIC_ENABLED = "isBiometricLoginEnabled"
        private const val KEY_BIOMETRIC_REFRESH_TOKEN = "biometricRefreshToken"
        private const val KEY_BIOMETRIC_USER_EMAIL = "biometricUserEmail"
        private const val KEY_BIOMETRIC_USER_ID = "biometricUserId"
        private const val KEY_BIOMETRIC_VENUE_ID = "biometricVenueId"
        private const val KEY_BIOMETRIC_VENUES_LIST = "biometricVenuesList"
        // Device
        private const val KEY_SERIAL_NUMBER = "serialNumber"
        private const val KEY_TERMINAL_ID = "terminalId"
        private const val KEY_USER_PIN = "userPin"
        private const val KEY_VENUES_LIST = "venuesList"
    }
}

@kotlinx.serialization.Serializable
data class StoredVenue(
    val id: String,
    val name: String,
    val slug: String? = null,
    val logo: String? = null,
    val role: String? = null,
) {
    val displayRole: String
        get() = when (role?.uppercase()) {
            "SUPERADMIN" -> "Super Admin"
            "OWNER" -> "Propietario"
            "ADMIN" -> "Administrador"
            "MANAGER" -> "Gerente"
            "CASHIER" -> "Cajero"
            "WAITER" -> "Mesero"
            "KITCHEN" -> "Cocina"
            "HOST" -> "Anfitrion"
            "VIEWER" -> "Observador"
            "STAFF" -> "Staff"
            else -> role ?: "Staff"
        }
}

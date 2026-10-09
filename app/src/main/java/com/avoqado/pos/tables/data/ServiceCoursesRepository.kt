package com.avoqado.pos.tables.data

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.pos.data.model.ServiceCourseSnapshot
import com.avoqado.pos.pos.data.model.ServiceCoursesResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** Venue-scoped durable catalog. A failed refresh never replaces a good catalog. */
@Singleton
class ServiceCoursesRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    private val cache: PayloadCache,
    private val preparation: com.avoqado.pos.kds.data.KitchenPreparationRepository? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _courses = MutableStateFlow(ServiceCourseSnapshot.DEFAULTS)
    val courses = _courses.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private var scope: String? = null
    private var generation = 0L
    private var hasCatalog = false
    fun immediateSnapshot() = _courses.value.firstOrNull { it.kind == "IMMEDIATE" }
        ?.takeIf { scope == secureStorage.venueId && it.preparationVersion == 1 }

    suspend fun refresh(venueId: String) {
        val epoch = ++generation
        fun current() = epoch == generation && secureStorage.venueId == venueId
        preparation?.restoreCapabilities(venueId)
        if (!current()) return
        if (scope != venueId) {
            scope = venueId
            hasCatalog = false
            _courses.value = ServiceCourseSnapshot.DEFAULTS
            _notice.value = null
        }
        if (!hasCatalog) cache.load(TYPE, venueId)?.let { stored ->
            val saved = runCatching { json.decodeFromString<ServiceCoursesResponse>(stored.json).data }.getOrNull()
            if (saved != null && saved.enabled && saved.isValidFor(venueId) && current()) {
                _courses.value = workflowCourses(venueId, saved.courses)
                hasCatalog = true
            }
        }
        if (!current()) return
        _courses.value = workflowCourses(venueId, _courses.value)
        preparation?.refreshCapabilities(venueId)
        if (!current()) return
        _courses.value = workflowCourses(venueId, _courses.value)
        try {
            val token = secureStorage.accessToken ?: error("Sin sesión")
            val request = Request.Builder().url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/service-courses")
                .header("Authorization", "Bearer $token").build()
            val (code, body) = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
            }
            if (!current()) return
            require(code in 200..299) { "No se pudo actualizar el catálogo" }
            val payload = json.decodeFromString<ServiceCoursesResponse>(body).data
            require(payload.enabled && payload.isValidFor(venueId)) { "Catálogo incompatible" }
            cache.save(TYPE, venueId, body)
            if (!current()) return
            _courses.value = workflowCourses(venueId, payload.courses)
            hasCatalog = true
            _notice.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (current()) _notice.value = if (hasCatalog) "No se pudieron actualizar los tiempos. Se usa la última lista guardada."
                else "No hay una lista guardada. Se usan los tiempos predeterminados hasta conectar."
        }
        if (current()) _courses.value = workflowCourses(venueId, _courses.value)
    }

    private fun workflowCourses(venueId: String, courses: List<ServiceCourseSnapshot>): List<ServiceCourseSnapshot> =
        courses.mapIndexed { index, course -> if (preparation?.negotiated(venueId) == true)
            course.copy(preparationVersion = 1, sortOrder = index) else course.copy(preparationVersion = null, sortOrder = null) }

    companion object { const val TYPE = "service_courses" }
}

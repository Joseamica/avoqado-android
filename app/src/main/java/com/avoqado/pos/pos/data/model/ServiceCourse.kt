package com.avoqado.pos.pos.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/** Historical operator choice. Never resolve it again from the current catalog. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ServiceCourseSnapshot(val id: String, val label: String, val kind: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val preparationVersion: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sortOrder: Int? = null,
) {
    val legacyCourse: String? get() = if (kind == "IMMEDIATE") null else label
    companion object {
        val DEFAULTS = listOf(
            ServiceCourseSnapshot("immediate", "Inmediato", "IMMEDIATE"),
            ServiceCourseSnapshot("appetizers", "Aperitivos", "STANDARD"),
            ServiceCourseSnapshot("mains", "Principales", "STANDARD"),
            ServiceCourseSnapshot("desserts", "Postres", "STANDARD"),
        )
    }
}

@Serializable
data class ServiceCoursesPayload(
    val schemaVersion: Int = 1,
    val venueId: String,
    val revision: String,
    val enabled: Boolean = true,
    val source: String = "DEFAULT",
    val courses: List<ServiceCourseSnapshot>,
) {
    fun isValidFor(venue: String): Boolean = venueId == venue && schemaVersion == 1 &&
        courses.size in 1..32 && courses.first().id == "immediate" && courses.first().kind == "IMMEDIATE" &&
        courses.map { it.id }.distinct().size == courses.size &&
        courses.map { it.label.lowercase() }.distinct().size == courses.size &&
        courses.all { it.id.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && it.label.isNotBlank() && it.label.length <= 60 &&
            it.label.none { c -> c.code < 32 || c.code == 127 } &&
            (it.kind == "IMMEDIATE" && it.id == "immediate" || it.kind == "STANDARD" && it.id != "immediate") }
}

@Serializable
data class ServiceCoursesResponse(val success: Boolean = true, val data: ServiceCoursesPayload)

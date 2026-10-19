package com.avoqado.pos.customerdisplay

import kotlinx.serialization.Serializable

/*
 * La ficha de la pantalla del cliente (F2): lo que manda `GET /mobile/venues/:venueId/customer-display`.
 *
 * Los DTO espejan `FichaEfectiva` de `customerDisplayDesign.effective.ts` del server (campos que esta versión de la app
 * pinta; el resto lo salta `ignoreUnknownKeys`). La normalización es DEFENSIVA y campo por campo: un campo raro cae a su
 * valor de fábrica sin tirar los demás. Sólo una ficha de otra versión de esquema o sin huella válida se descarta entera.
 */

@Serializable
data class FichaDto(
    val schemaVersion: Int,
    val fingerprint: String,
    val theme: String? = null,
    val accent: AccentDto? = null,
    val logo: LogoDto? = null,
    val welcomeText: String? = null,
    val thanksText: String? = null,
)

@Serializable
data class AccentDto(
    val custom: Boolean = false,
    val color: String? = null,
    val on: String? = null,
)

@Serializable
data class LogoDto(
    val source: String,
    val url: String,
    val sha256: String? = null,
    val version: String? = null,
)

/** El logo ya validado: un activo propio (con hash que se verifica al bajarlo) o el logo del negocio (con su versión). */
sealed interface LogoRef {
    data class Asset(val url: String, val sha256: String) : LogoRef
    data class Venue(val url: String, val version: String) : LogoRef
}

/** La ficha lista para pintar. `null` en un campo = valor de fábrica. */
data class Ficha(
    val fingerprint: String,
    val dark: Boolean,
    val accentArgb: Long?,
    val onAccentArgb: Long?,
    val logo: LogoRef?,
    val welcomeText: String?,
    val thanksText: String?,
)

private const val SUPPORTED_SCHEMA_VERSION = 1
private const val MAX_TEXT_CODE_POINTS = 40   // el mismo tope que `MAX_TEXT_CODE_POINTS` del server
private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

/** `null` SÓLO si el esquema no es el que esta app entiende o la huella no es válida; lo demás cae a fábrica uno por uno. */
internal fun FichaDto.toFicha(imageOrigins: List<String>): Ficha? {
    if (schemaVersion != SUPPORTED_SCHEMA_VERSION || !isPollFingerprint(fingerprint)) return null
    // El acento va en pareja: un color sin su contraste (o al revés) no se pinta.
    val accentColor = accent?.takeIf { it.custom }?.color?.let(::hexToArgb)
    // El contraste sólo es blanco o negro (lo que calcula el server); otro valor = sin acento, igual que un color inválido.
    val onAccentColor = accent?.takeIf { it.custom }?.on?.takeIf { it == "#FFFFFF" || it == "#000000" }?.let(::hexToArgb)
    val accentValid = accentColor != null && onAccentColor != null
    return Ficha(
        fingerprint = fingerprint,
        dark = theme == "DARK",
        accentArgb = accentColor.takeIf { accentValid },
        onAccentArgb = onAccentColor.takeIf { accentValid },
        logo = logo?.toLogoRef(imageOrigins),
        welcomeText = welcomeText.normalizedText(),
        thanksText = thanksText.normalizedText(),
    )
}

/**
 * Sólo `https://` y sólo si la URL empieza EXACTO con un origen permitido seguido de `/`. Nunca `contains`:
 * `https://storage.googleapis.com.evil.test/x` no es de `https://storage.googleapis.com`.
 */
internal fun isAllowedImageUrl(url: String, origins: List<String>): Boolean =
    url.startsWith("https://") && origins.any { origin -> origin.startsWith("https://") && url.startsWith("$origin/") }

private fun LogoDto.toLogoRef(imageOrigins: List<String>): LogoRef? {
    if (!isAllowedImageUrl(url, imageOrigins)) return null
    return when (source) {
        "asset" -> sha256?.takeIf { SHA256_HEX.matches(it) }?.let { LogoRef.Asset(url, it) }
        "venue" -> version?.takeIf { it.isNotBlank() }?.let { LogoRef.Venue(url, it) }
        else -> null
    }
}

private fun String?.normalizedText(): String? =
    this?.takeIf { it.isNotBlank() && it.codePointCount(0, it.length) <= MAX_TEXT_CODE_POINTS }

private fun hexToArgb(hex: String): Long? =
    if (HEX_COLOR.matches(hex)) 0xFF000000L or hex.substring(1).toLong(16) else null

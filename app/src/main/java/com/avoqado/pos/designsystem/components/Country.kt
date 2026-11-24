package com.avoqado.pos.designsystem.components

/**
 * Country metadata for phone input country selector.
 * Flags are emoji (works across Android 11+ system font).
 */
data class Country(
    val isoCode: String, // "MX", "US", etc.
    val dialCode: String, // "52", "1" — without +
    val flag: String, // "🇲🇽"
    val name: String, // Spanish display name
)

object Countries {
    // Pinned at top — most common markets for Avoqado.
    val pinned: List<Country> = listOf(
        Country("MX", "52", "\uD83C\uDDF2\uD83C\uDDFD", "México"),
        Country("US", "1", "\uD83C\uDDFA\uD83C\uDDF8", "Estados Unidos"),
        Country("CA", "1", "\uD83C\uDDE8\uD83C\uDDE6", "Canadá"),
        Country("ES", "34", "\uD83C\uDDEA\uD83C\uDDF8", "España"),
    )

    val others: List<Country> = listOf(
        Country("AR", "54", "\uD83C\uDDE6\uD83C\uDDF7", "Argentina"),
        Country("BO", "591", "\uD83C\uDDE7\uD83C\uDDF4", "Bolivia"),
        Country("BR", "55", "\uD83C\uDDE7\uD83C\uDDF7", "Brasil"),
        Country("CL", "56", "\uD83C\uDDE8\uD83C\uDDF1", "Chile"),
        Country("CO", "57", "\uD83C\uDDE8\uD83C\uDDF4", "Colombia"),
        Country("CR", "506", "\uD83C\uDDE8\uD83C\uDDF7", "Costa Rica"),
        Country("CU", "53", "\uD83C\uDDE8\uD83C\uDDFA", "Cuba"),
        Country("DO", "1", "\uD83C\uDDE9\uD83C\uDDF4", "República Dominicana"),
        Country("EC", "593", "\uD83C\uDDEA\uD83C\uDDE8", "Ecuador"),
        Country("SV", "503", "\uD83C\uDDF8\uD83C\uDDFB", "El Salvador"),
        Country("GT", "502", "\uD83C\uDDEC\uD83C\uDDF9", "Guatemala"),
        Country("HN", "504", "\uD83C\uDDED\uD83C\uDDF3", "Honduras"),
        Country("NI", "505", "\uD83C\uDDF3\uD83C\uDDEE", "Nicaragua"),
        Country("PA", "507", "\uD83C\uDDF5\uD83C\uDDE6", "Panamá"),
        Country("PY", "595", "\uD83C\uDDF5\uD83C\uDDFE", "Paraguay"),
        Country("PE", "51", "\uD83C\uDDF5\uD83C\uDDEA", "Perú"),
        Country("PR", "1", "\uD83C\uDDF5\uD83C\uDDF7", "Puerto Rico"),
        Country("UY", "598", "\uD83C\uDDFA\uD83C\uDDFE", "Uruguay"),
        Country("VE", "58", "\uD83C\uDDFB\uD83C\uDDEA", "Venezuela"),
        Country("FR", "33", "\uD83C\uDDEB\uD83C\uDDF7", "Francia"),
        Country("DE", "49", "\uD83C\uDDE9\uD83C\uDDEA", "Alemania"),
        Country("GB", "44", "\uD83C\uDDEC\uD83C\uDDE7", "Reino Unido"),
        Country("IT", "39", "\uD83C\uDDEE\uD83C\uDDF9", "Italia"),
        Country("PT", "351", "\uD83C\uDDF5\uD83C\uDDF9", "Portugal"),
        Country("JP", "81", "\uD83C\uDDEF\uD83C\uDDF5", "Japón"),
        Country("CN", "86", "\uD83C\uDDE8\uD83C\uDDF3", "China"),
        Country("IN", "91", "\uD83C\uDDEE\uD83C\uDDF3", "India"),
        Country("AU", "61", "\uD83C\uDDE6\uD83C\uDDFA", "Australia"),
    )

    val all: List<Country> = pinned + others.sortedBy { it.name }

    fun byIso(iso: String?): Country = all.firstOrNull { it.isoCode == iso?.uppercase() } ?: pinned[0]
}

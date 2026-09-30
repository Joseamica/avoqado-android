// Prueba: avoqado-android compilado para escritorio (Windows/Mac).
// Build de Gradle APARTE: el build de Android (../settings.gradle.kts) no lo incluye y no sabe que existe,
// así que nada de aquí puede cambiar el APK. Lee las fuentes de ../app sin modificarlas.
pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "avoqado-pos-escritorio"
include(":plataforma", ":pos")

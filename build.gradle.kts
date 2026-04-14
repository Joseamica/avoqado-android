plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

val buildJvmVersion = JavaVersion.current().majorVersion.toIntOrNull()
check(buildJvmVersion != null && buildJvmVersion in 17..23) {
    "Avoqado Android requires JDK 17-23 to build. Current JVM: ${JavaVersion.current()}. Use JDK 21 or JDK 23."
}

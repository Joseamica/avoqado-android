plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    id("com.google.gms.google-services") version "4.4.2" apply false  // Firebase
    id("com.google.firebase.crashlytics") version "3.0.2" apply false  // Firebase Crashlytics
}

val buildJvmVersion = JavaVersion.current().majorVersion.toIntOrNull()
check(buildJvmVersion != null && buildJvmVersion in 17..24) {
    "Avoqado Android requires JDK 17-24 to run Gradle. " +
        "Current JVM: ${JavaVersion.current()}. Use JDK 21, JDK 23, or JDK 24. " +
        "Note: Android compilation target remains Java 17."
}

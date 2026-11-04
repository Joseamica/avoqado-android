# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class com.avoqado.pos.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Hilt
-keep class dagger.hilt.** { *; }

# 🔴 2.25.30 (4-nov-2026, Testarudo): R8 le quitó a TableOrderScreen(...) su último parámetro
# ($default) y dejó un registro con el tipo equivocado; ART rechaza la clase entera
# (VerifyError «register v4 has type ComposerImpl but expected Integer») y la app truena al
# abrir una mesa o al armar cualquier recibo (etiquetaDelTipoDeOrden vive en el mismo archivo).
# Conservar la firma de sus métodos evita esa optimización. Quitarlo sólo con una prueba que
# verifique la clase en un APK de release.
-keep,allowobfuscation class com.avoqado.pos.tables.presentation.TableOrderScreenKt { *; }

plugins {
    kotlin("jvm") version "2.2.10" apply false
    kotlin("plugin.compose") version "2.2.10" apply false
    kotlin("plugin.serialization") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}

// El Skia nativo de ESTA máquina (la Mac, el Linux de la nube o el Windows del Alienware). Las pruebas lo cargan de aquí:
// con uno fijo de Mac sólo corrían en una Mac (tablero D3, 27-oct). El paquete de Windows sigue llevando el suyo (:pos).
extra["skiaDeEstaMaquina"] = System.getProperty("os.name").lowercase().let { os ->
    when { "mac" in os -> "macos"; "win" in os -> "windows"; else -> "linux" }
} + if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "-arm64" else "-x64"

# Avoqado Android

Aplicación Android de punto de venta (POS) para Avoqado.

## Requisitos de build

- El proyecto permite ejecutar Gradle con JDK 17-24.
- Recomendado: JDK 24 para desarrollo local de este repo.
- La app sigue compilando con target Java/Kotlin 17 (`sourceCompatibility`, `targetCompatibility`, `jvmTarget`).

## JDK 24 sin afectar otros proyectos

- Este repo incluye `.java-version` para usar JDK 24 solo dentro de este directorio (si usas `jenv`/`asdf`/herramienta compatible).
- Si no usas gestor de versiones, puedes correr en una terminal local de este repo:
  `export JAVA_HOME=$(/usr/libexec/java_home -v 24)`
  y después:
  `./gradlew testDebugUnitTest`

## Comandos

- Ejecutar tests unitarios: `./gradlew testDebugUnitTest`
- Override del backend en debug: `./gradlew assembleDebug -Pavoqado.devBaseUrl=https://tu-url/api/v1`

## Backend debug

- Si no defines `avoqado.devBaseUrl`, el build `debug` usa `https://patchiest-noncommemorational-willia.ngrok-free.dev/api/v1`.
- También puedes fijarlo en `local.properties`:
  `avoqado.devBaseUrl=https://tu-url/api/v1`
- El build falla si `debug` apunta a la misma URL de producción.
- `release` sigue apuntando a `https://api.avoqado.io/api/v1`.

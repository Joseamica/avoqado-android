# Avoqado Android

Aplicación Android de punto de venta (POS) para Avoqado.

## Requisitos de build

- Usa JDK 17-23 para compilar. La combinación actual de Gradle 8.11.1 y AGP 8.7.3 no es compatible con Java 24.
- Recomendado: JDK 21 o JDK 23.

## Comandos

- Ejecutar tests unitarios: `./gradlew testDebugUnitTest`
- Override del backend en debug: `./gradlew assembleDebug -Pavoqado.devBaseUrl=https://tu-url/api/v1`

## Backend debug

- Si no defines `avoqado.devBaseUrl`, el build `debug` usa `https://humane-immortal-pika.ngrok-free.app/api/v1`.
- También puedes fijarlo en `local.properties`:
  `avoqado.devBaseUrl=https://tu-url/api/v1`
- El build falla si `debug` apunta a la misma URL de producción.
- `release` sigue apuntando a `https://api.avoqado.io/api/v1`.

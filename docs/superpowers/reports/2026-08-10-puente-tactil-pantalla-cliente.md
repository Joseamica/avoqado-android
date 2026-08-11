# Puente táctil para la pantalla del cliente

**Fecha:** 2026-08-10 · **Rama:** `feat/puente-tactil-pantalla-cliente`
**Equipo donde se midió el problema:** Sunmi T3 Pro (build de producción, sin root)

---

## 1. El problema, en una frase

El panel del cliente del T3 Pro **sí tiene digitalizador multitáctil**, pero Android
**no lo asocia a esa pantalla**, así que sus toques aterrizan en la pantalla del
**cajero**, con las coordenadas del panel grande.

Lo que imprime `dumpsys input`:

```
Device 7: SUNMI NP511
    Sources: TOUCHSCREEN
    Touch Input Mapper (mode - DIRECT)
    AssociatedDisplayPort:     <none>
    AssociatedDisplayUniqueId: <none>
    AssociatedDisplay: hasAssociatedDisplay=true, isExternal=true, displayId=''
    Motion Ranges:
      X: min=0.000, max=1919.000
      Y: min=0.000, max=1079.000     ← 1920x1080: el panel del CAJERO
```

Panel del cliente: **1280x800**. Panel del cajero: **1920x1080**.

Verificado en vivo con `settings put system show_touches 1`: el dedo va a la pantalla
del cliente y el indicador aparece en la del **cajero**, desplazado.

**Dos consecuencias, y las dos importan:**

1. "El cliente elige propina y calificación" no servía en ese modelo.
2. 🔴 **Un cliente tocando su pantalla está apretando cosas en la caja.** Eso pasa hoy,
   en producción, sin ninguna función nuestra de por medio.

No hay arreglo por configuración: build de producción **sin root** (no se puede escribir
un `.idc` con `touch.displayId`) y la pantalla es **virtual**, creada por
`com.sunmi.usbscreen`.

---

## 2. Cómo se identifica el dispositivo

**Por el dispositivo que generó el evento, nunca por dónde cayó el dedo.** Cada
`MotionEvent` trae `getDeviceId()`; el conjunto de ids a puentear se resuelve consultando
`InputDevice.getDeviceIds()` + `InputDevice.getDevice(id)`.

La característica que se busca es **"táctil externo sin display asociado"** — no la marca.
Otro modelo puede llamarle distinto a su panel; `if (name == "SUNMI NP511")` dejaría fuera
al siguiente equipo con el mismo defecto de ruteo.

Regla (pura y testeada, `resolveBridgedTouchDeviceIds`):

| Señal | De dónde sale | Qué pasa si no se puede leer |
|---|---|---|
| `SOURCE_TOUCHSCREEN` | `device.supportsSource(...)` — API pública | — |
| Es **externo** | `isExternal()`; público desde **API 34**, antes `@hide` → se gatea por `SDK_INT` y se cae a reflexión | `null` = "no sabemos" ⇒ **no se puentea** |
| **Sin** display asociado | No hay API pública (es el `AssociatedDisplayPort` de `dumpsys`); se intenta `getAssociatedDisplayId()` por reflexión | `null` = desconocido ⇒ no descarta |

Y dos guardas que valen más que las señales:

- 🔴 **Nunca se reclaman TODOS los táctiles.** Si tras el filtro no queda ni uno para el
  cajero, entendimos mal el equipo (p. ej. un OEM que marca su panel integrado como
  externo) y se devuelve el conjunto vacío. Tragarse los toques del cajero deja el POS
  inservible con fila en la caja; no puentear no cuesta nada.
- 🔴 **El puente solo se arma con la caja en la pantalla PRINCIPAL** (`bridgeArmed`), o
  sea en modo normal. En modo **invertido** la caja se muda a la segunda pantalla, que es
  física y por tanto tiene un táctil *externo* — el mismo perfil que buscamos. Sin esta
  guarda el puente se comería los toques del propio cajero. En invertido no se pierde
  nada: ese modo exige pantalla física, y una pantalla física sí recibe sus toques.

**Lo que NO hace falta defender** (y conviene saberlo para no añadir guardas de más): un
táctil correctamente asociado a su pantalla **nunca llega al filtro**. Sus eventos se
entregan a la ventana que vive en ESA pantalla, no a la caja. El filtro corre en
`MainActivity.dispatchTouchEvent`, así que lo único que puede ver es lo que ya aterrizó
en la caja.

---

## 3. La fórmula de traducción

```
escalaX = anchoVentanaCliente / anchoRangoTáctil
escalaY = altoVentanaCliente  / altoRangoTáctil

xCliente = xCrudo * escalaX
yCliente = yCrudo * escalaY
```

Con las dos dimensiones **leídas en tiempo de ejecución**, nunca constantes:

- **Origen** = rango del propio digitalizador: `getMotionRange(AXIS_X).max - .min + 1`.
  El `+1` no es cosmético: el rango es **inclusivo** (`0..1919` para un panel de 1920).
  Con `max - min` a secas el último pixel se mapea a `1280.0` — justo **afuera** — y la
  fila de botones pegada al borde derecho deja de responder. Hay un test para ese borde.
  Si el equipo no reporta rango, se cae al tamaño de la ventana del cajero (que es a
  dónde el sistema está entregando estos toques).
- **Destino** = `decorView.width/height` de la ventana del cliente.

Escalas **independientes en X e Y**: 16:9 → 16:10 no es una escala uniforme, y usar una
sola desplazaría todo en vertical. Hay test.

Números del T3 Pro de hoy: `1920x1080 → 1280x800` ⇒ `escalaX = 0.6667`, `escalaY = 0.7407`.
El centro (960, 540) cae en (640, 400); (1919, 1079) cae en (1279.33, 799.26).

**Sin recortes al borde a propósito:** recortar escondería un mapeo equivocado haciéndolo
parecer "casi bien". Un punto un pixel afuera simplemente no acierta a nada.

El reenvío usa `MotionEvent.transform(Matrix)` con esa escala, **no** `setLocation(x, y)`:
`setLocation` *desplaza* todos los punteros para dejar el primero en el punto dado, no los
escala — con dos pantallas de distinto tamaño el segundo dedo acaba donde no va, y el panel
del cliente es multitáctil de verdad. `transform` aplica la escala a todos los punteros y a
todo el histórico.

Se despacha directo al `decorView` de la ventana del cliente
(`presentation.window.decorView.dispatchTouchEvent(...)`): esa ventana es nuestra y vive
en nuestro proceso, así que no hay permisos de inyección de eventos de por medio.

---

## 4. 🔴 Cómo verificar el mapeo en el aparato (no está asumido: hay que medirlo)

**No des por hecho que la escala es lineal de panel completo.** Puede haber desplazamiento
(si el panel reporta un origen distinto) o rotación. El código registra crudo → traducido
justo para poder compararlo contra dónde cae el dedo de verdad.

1. **Confirmar que el puente se armó** — al abrir la app, con el panel conectado:

   ```bash
   adb logcat -s 🖥️CustomerDisplay | grep -i "puente"
   ```

   Debe salir una línea `Puente táctil armado para: SUNMI NP511#7 1920x1080 ...`.
   Si **no** sale: o `isExternal()` no se pudo leer, o el equipo enumera todos los
   táctiles como externos (invariante), o no hay panel. Contrastar con:

   ```bash
   adb shell dumpsys input | grep -A12 "Device .*NP511"
   ```

2. **Confirmar que la caja ya no se ensucia** (esto vale por sí solo): con el indicador de
   toques encendido —`adb shell settings put system show_touches 1`— tocar el panel del
   cliente. **Antes:** aparecía el círculo en la pantalla del cajero y llegaba a apretar
   cosas. **Ahora:** no debe pasar nada en la pantalla del cajero.
   Apagarlo al terminar: `settings put system show_touches 0`.

3. **Medir el mapeo con esquinas y centro.** Poner la pantalla del cliente en un estado con
   botones grandes y conocidos (propina o calificación: `CustomerContent.Tip` /
   `Rating`). Con `adb logcat -s 🖥️CustomerDisplay` corriendo, tocar en este orden y anotar
   lo que imprime cada `ACTION_DOWN`:

   | Dónde toca el dedo | `crudo(x, y)` esperado | `cliente(x, y)` esperado |
   |---|---|---|
   | Esquina superior izquierda | ≈ (0, 0) | ≈ (0, 0) |
   | Esquina superior derecha | ≈ (1919, 0) | ≈ (1279, 0) |
   | Esquina inferior izquierda | ≈ (0, 1079) | ≈ (0, 799) |
   | Centro | ≈ (960, 540) | ≈ (640, 400) |

   El log sale como:
   `Puente táctil: crudo(960.0, 540.0) en 1920x1080 → cliente(640.0, 400.0) en 1280x800`

   - Si `crudo` **no** empieza en ~0 en la esquina superior izquierda ⇒ hay
     **desplazamiento**: hay que restar el origen antes de escalar.
   - Si al tocar arriba-derecha el `crudo` sale con X pequeña y Y grande ⇒ hay
     **rotación** de 90°: el mapeo necesita intercambiar ejes.
   - Si `cliente` cuadra con la tabla pero el botón que se ilumina **no** es el que está
     bajo el dedo ⇒ el problema no es la escala, es qué ventana recibe (revisar que la
     `Presentation` esté montada en el display correcto).

4. **Comprobación de producto, con la función encendida:** Ajustes → Pantalla del cliente →
   *El cliente elige propina y calificación*. Hacer una venta: el cliente debe poder tocar
   propina y las estrellas **en su pantalla**, y el cajero ver el resultado.

---

## 5. Qué pasa si el puente no se puede montar

**Nada cambia respecto a hoy.** Todo el camino está envuelto en `runCatching` y cada rama
degrada hacia "la app de siempre":

| Falla | Qué pasa |
|---|---|
| No se pueden leer los dispositivos de entrada | Conjunto vacío, `handleCustomerPanelTouch` contesta `false` de inmediato y todo sigue igual |
| `isExternal()` no se puede leer (OEM/hidden API) | El dispositivo no es candidato ⇒ no se puentea |
| Todos los táctiles parecen externos | Invariante ⇒ no se puentea nada (la caja nunca se queda sin táctil) |
| Modo invertido | El puente ni se arma |
| Hay puente pero **no** hay ventana de cliente montada | El toque se **consume igual** (deja de ensuciar la caja) y se pierde. Es lo correcto: mejor perder ese toque que dejarlo apretar el carrito del cajero |
| La ventana del cliente todavía mide 0, o el rango del táctil es imposible | No hay escala ⇒ no se reenvía, pero se consume igual |
| El reenvío lanza excepción | Se registra `warn` y se consume igual |

En un equipo **sin** este defecto (teléfono, tablet, POS de una sola pantalla, D3 con
pantalla física) el costo es una consulta a un `Set` vacío por evento táctil.

---

## 6. Lo que este cambio **no** hace

- 🔴 **No habilita el modo invertido.** Son cosas distintas y no hay que "simplificarlas"
  en una sola condición. El puente hace que el **cliente** pueda tocar su pantalla: botones
  grandes, sin teclado. Invertir exige que el **cajero** trabaje ahí — la app completa,
  campos de texto, teclado en pantalla y foco de entrada del sistema — y eso el puente no
  lo da (la ventana del cliente es `FLAG_NOT_FOCUSABLE` a propósito, para no robarle el
  teclado a la caja). `invertible` sigue exigiendo pantalla **física**. Está comentado en
  `DisplayRoles.resolveDisplayRoles` y en `CustomerDisplayState.invertible`.
- **No arregla el IME en la pantalla del cliente.** El "escribe tu WhatsApp/correo" de la
  pantalla de agradecimiento aparece ahora también en el T3 Pro (porque `touchCapable` pasó
  a `true`), pero la ventana es `FLAG_NOT_FOCUSABLE`, así que el teclado podría no abrir.
  **Es una limitación preexistente**: pasa igual hoy en cualquier pantalla de cliente
  física. No se tocó el foco — el código documenta por qué es veneno (`FLAG_LOCAL_FOCUS_MODE`
  deja la ventana muda al tacto real). **Pendiente de verificar en hardware**; si estorba,
  el QR sigue siendo el camino y la salida sería ocultar ese campo cuando el puente sea la
  única razón de `touchCapable`.

---

## 7. Paridad iOS

**No aplica.** `avoqado-ios` no tiene módulo de pantalla de cliente (`find` no encuentra
ningún `CustomerDisplay*`), y el defecto es de ruteo de entrada de Android en un POS Sunmi
concreto. Cae en la excepción de "cosas genuinamente específicas de plataforma".

---

## 8. Archivos

| Archivo | Qué |
|---|---|
| `app/src/main/java/com/avoqado/pos/customerdisplay/CustomerTouchBridge.kt` | **Nuevo.** Decisión pura (a quién se puentea, con qué escala) + lectura de `InputDevice` |
| `app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayManager.kt` | `handleCustomerPanelTouch` / reenvío traducido, `bridgeArmed`, `touchCapable` con puente |
| `app/src/main/java/com/avoqado/pos/MainActivity.kt` | `dispatchTouchEvent` — la puerta por la que los toques dejan de llegar a la caja |
| `app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayState.kt` | KDoc de `touchCapable` e `invertible` (por qué NO son lo mismo) |
| `app/src/main/java/com/avoqado/pos/customerdisplay/DisplayRoles.kt` | KDoc: el puente no vuelve invertible una pantalla virtual |
| `app/src/main/java/com/avoqado/pos/settings/presentation/CustomerDisplaySheet.kt` | El texto de "Invertir pantallas" ya no dice que los toques no llegan (ahora sí llegan) |
| `app/src/test/java/com/avoqado/pos/customerdisplay/CustomerTouchBridgeTest.kt` | **Nuevo.** 17 tests de la parte pura |

---

## 9. Verificación

```
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

`BUILD SUCCESSFUL` — **946 tests, 0 fallos, 0 errores** (929 previos + 17 nuevos) y
`app-debug.apk` generado.

# Invertir pantallas en POS de doble pantalla (Sunmi D3 y equivalentes)

- **Fecha:** 2026-08-10
- **Estado:** diseño aprobado, pendiente de implementar
- **Tier:** FREE (mismo que el resto de *Pantalla del cliente*, decidido 2026-07-16)
- **Repos que toca:** `avoqado-android` (el trabajo real) · `avoqado-server` (campo + endpoints + MCP) · `avoqado-web-dashboard` (switch remoto)
- **iOS:** no aplica — ver [§9](#9-decisiones-tomadas-y-fuera-de-alcance)

---

## 1. Qué pide el cliente

En un POS de doble pantalla, hoy la **grande** es la del cajero y la **chica** la del cliente. Un
cliente pide lo contrario: **que el cliente vea la grande y el cajero trabaje en la chica**.

Es una petición razonable de mostrador: la pantalla del cliente es el escaparate del cobro
(total, propina, calificación, QR del recibo digital) y en la grande se lee de lejos.

**Alcance:** un interruptor que intercambia qué contenido va en cada pantalla. Nada más. No se
rediseña la interfaz del cliente ni la del cajero.

---

## 2. Evidencia de hardware (verificada en un D3 real, 2026-08-10)

Todo lo de abajo se midió por `adb` contra un Sunmi **D3**, Android 14, build `1.2.37`.

| Qué | Resultado |
|---|---|
| Pantalla 0 | `INTERNAL`, 1920×1080, digitalizador `ilitek_ts`, landscape |
| Pantalla 2 | `EXTERNAL` "Pantalla HDMI", 800×1280 rotada a 1280×800, **digitalizador propio `ilitek_second_ts`** |
| Flags de la pantalla 2 | `FLAG_PRESENTATION`, `FLAG_TRUSTED`, `FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY`, `displayGroupId 0` |
| Tamaño lógico de la pantalla 2 | `sw533dp w853dp h533dp`, 240 dpi, landscape → cae en layout de tablet, de poca altura |
| ¿Se puede mover una app a la pantalla 2? | **Sí.** `am start --display 2` dejó Ajustes *resumed y enfocado* ahí mientras Avoqado seguía *resumed* en la 0 |
| ¿Sale el teclado en la pantalla 2? | **Sí.** Con el buscador de Ajustes enfocado: `mCurTokenDisplayId=2`, `mInputShown=true`. La pantalla 2 tiene su propio `ImeContainer` y `ImeInsetsSourceProvider` |
| ¿Barras de sistema en la pantalla 2? | **No.** `nonDecorInsets=[0,0][0,0]` en las 4 rotaciones → sin barra de estado ni "atrás" del sistema |
| ¿Sunmi lo trae nativo? | **No.** Ver [§3](#3-lo-que-sunmi-no-tiene) |

Comandos para reproducirlo:

```bash
adb -s <device> shell "dumpsys display | grep -E 'DisplayDeviceInfo|touch '"
adb -s <device> shell "dumpsys input | grep -E 'ilitek|Orientation:'"
adb -s <device> shell "am start --display 2 -n com.android.settings.intelligence/.search.SearchActivity"
adb -s <device> shell "dumpsys input_method | grep -E 'mCurTokenDisplayId|mInputShown'"
```

> `screencap -d 2` devuelve 0 bytes en este D3. Para ver esa pantalla hay que usar scrcpy o
> mirar el aparato.

---

## 3. Lo que Sunmi NO tiene

Se revisó el aparato completo, no solo Ajustes de Android:

- **Ajustes → Pantalla:** brillo, tema oscuro, tiempo de espera, pantalla de bloqueo, aspecto.
  Ninguna opción de segunda pantalla ni de cambiar la principal.
- **Las 11 entradas que se inyectan en Ajustes** (`com.android.settings.action.EXTRA_SETTINGS`):
  3 de acuerdos legales, 2 del escáner, Google, auth electrónica, `dataService` secret setting,
  **ToolBox**, **usbscreen**, y la del servicio de impresión (`woyou.aidlservice.jiuiv5`).
- **App "Pantalla del cliente y configuración de NFC"** (`com.sunmi.usbscreen` v2.7.1): reporta
  *"La pantalla del cliente no está conectada"* — administra el **accesorio USB** de T2/T3, no ve
  la pantalla integrada del D3. Sus opciones son NFC e info de la app.
- **Sunmi ToolBox:** Kiosk, NTP Server, Scheduled power on/off, Internet accessible check,
  Registro del dispositivo. Nada de pantallas.
- **Sunmi baseservice / device ability:** Device Status, Enroll Me, Import QR code (enrolamiento
  tipo MDM). Nada.
- **Propiedades del sistema:** ninguna de pantalla principal/dual. Lo único parecido es
  `persist.sys.usb_screen_stretch`.

**Conclusión: hay que hacerlo en la app.** Queda una pregunta abierta para el proveedor —
resultados de búsqueda mencionan que en el **K2mini** "ambas pantallas se pueden poner como
principal por configuración de código". No está documentado para D3 y la doc de Sunmi bloquea la
lectura automática, así que es **una pista para preguntar, no un hecho**. Si existiera, sería
gratis y serviría para cualquier app — pero el plan no depende de ello.

---

## 4. La restricción que define el diseño

🔴 **Una ventana `Presentation` (`TYPE_PRESENTATION`) está prohibida en la pantalla principal.**
Android solo la acepta en pantallas que califican como *public presentation display*
(`Display.isPublicPresentation()`, que exige `FLAG_PRESENTATION`). La pantalla 0 **no** tiene ese
flag.

Consecuencia directa: el módulo actual, que monta al cliente con `Presentation`, **no puede
usarse para poner al cliente en la pantalla grande**. Y la vuelta simétrica —meter la caja dentro
del `Presentation`— es peor:

- Sin `FLAG_NOT_FOCUSABLE` se reproduce el bug que **congeló la caja** en el T3 Pro (ventana
  touch-modal robándose los toques de la otra pantalla) — documentado en
  `CustomerDisplayPresentation.kt:50-59`.
- Con el flag puesto, **no hay teclado del sistema**: por eso la pantalla del cliente tuvo que
  escribir su propio teclado (`CustomerDisplayScreen.kt:692`). Un cajero sin teclado no puede
  buscar productos ni escribir notas.

**Por eso el modo invertido usa una Activity para el cliente, no un `Presentation`.** El
mecanismo está probado: dos Activities pueden estar *resumed* al mismo tiempo, una por pantalla.

---

## 5. Diseño

### 5.1 El concepto: cada pantalla tiene un rol

```
DisplayRoles(cashierDisplayId, customerDisplayId, invertible)
        ↑ derivado de: pantallas presentes + booleano "invertido"
```

|  | Caja (`MainActivity`) | Cliente | Ventana del cliente |
|---|---|---|---|
| **Normal** (hoy) | pantalla por defecto | secundaria | `Presentation` — **sin cambios** |
| **Invertido** | secundaria física | pantalla por defecto | `CustomerDisplayActivity` |

**El modo normal no se toca.** No es conservadurismo: en un **T3 Pro** la pantalla del cliente es
*virtual y de otra app* (`com.sunmi.usbscreen`), y ahí lanzar una Activity probablemente no está
permitido, mientras el `Presentation` funciona hoy en producción. Los dos caminos existen porque
hay dos hardwares genuinamente distintos.

La interfaz del cliente es **la misma composable en ambos casos** (`CustomerDisplayScreen(state)`,
1,204 líneas intactas). Solo cambia la ventana que la hospeda.

### 5.2 Componentes

| Componente | Tipo | Qué hace |
|---|---|---|
| `DisplayRoleResolver` | **nuevo**, puro (sin Android) | Absorbe `chooseCustomerDisplayId` y le agrega el rol del cajero. Aquí vive toda la corrección → aquí van los tests. |
| `CustomerDisplayManager` | existente (187 líneas) | Deja de preguntar "¿cuál es la secundaria?" y pregunta "¿cuál le toca al cliente?". Monta `Presentation` (normal) o lanza/termina `CustomerDisplayActivity` (invertido). |
| `CustomerDisplayActivity` | **nueva** (~40 líneas) | Hospeda `CustomerDisplayScreen(state)` en la pantalla del cliente en modo invertido. |
| `CashierDisplayGuard` | **nuevo**, en `MainActivity.onCreate` | Si la caja no está en su pantalla, se relanza ahí. Con guarda anti-bucle. |
| `DisplayModePrefs` | **nuevo** (junto a `CustomerDisplayPrefs`) | El booleano local + bandera `dirty`. Es la autoridad para aplicar. |

### 5.3 Reglas del resolver

1. Sin segunda pantalla válida → `cashier = default`, `customer = null`, `invertible = false`.
2. Con segunda válida → normal: cajero en default, cliente en la secundaria. Invertido: al revés.
3. **`invertible = true` solo si la secundaria es física** (`ownerPackage == null`). Una virtual de
   vendor (T3 Pro) no entrega toques → **jamás invertible**. Esta regla es lo que evita dejar al
   cajero con una pantalla muerta.
4. Se conserva intacto lo que ya funciona: las virtuales de vendor siguen sirviendo **como
   pantalla de cliente**, y las de captura/remoto (AnyDesk, TeamViewer, scrcpy…) siguen
   descartándose por dueño.
5. Si la pantalla del cajero desaparece (desconectan el monitor en modo invertido), los roles se
   recalculan y la caja vuelve a la pantalla por defecto. Android ya mueve el contenido solo
   (`removeMode 0` = mover a la principal); el guard **no debe** rebotarla de vuelta.

### 5.4 `CashierDisplayGuard` — cómo se mueve la caja

```kotlin
val opts = ActivityOptions.makeBasic().setLaunchDisplayId(roles.cashierDisplayId)
startActivity(
    Intent(this, MainActivity::class.java)
        .addFlags(FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_CLEAR_TOP),
    opts.toBundle(),
)
```

- El launcher **siempre** abre en la pantalla por defecto, así que en modo invertido hay un
  relanzamiento visible de ~1 s en cada arranque en frío. Es aceptable y ocurre una vez.
- 🔴 **Guarda anti-bucle obligatoria.** Regla exacta: se lleva un contador de intentos **por
  `cashierDisplayId` objetivo**, en memoria del proceso. Cada `onCreate` que encuentra la caja en
  la pantalla equivocada incrementa el contador y relanza **solo si el contador es ≤ 1**; al
  llegar a 2 el modo se marca *no soportado* y ya no se relanza. El contador se reinicia **solo**
  cuando cambia el conjunto de pantallas presentes (conectar/desconectar) o cuando el usuario
  cambia el ajuste. Sin esto, un equipo que ignore `setLaunchDisplayId` relanza la app para
  siempre.
- Al degradar **nunca se borra la preferencia** y **nunca se bloquea la venta**: la caja se queda
  donde está y Ajustes explica qué pasó.

### 5.5 `CustomerDisplayActivity` — detalles que importan

- Manifest: `taskAffinity=""` propio, `excludeFromRecents="true"`, `launchMode="singleInstance"`,
  `resizeableActivity="true"`. Va en su propia tarea porque vive en otra pantalla.
- Ventana con **`FLAG_NOT_FOCUSABLE`**, mismo razonamiento que el `Presentation` de hoy: un
  letrero de cara al público jamás debe quitarle el teclado ni el foco al cajero. Los toques
  dentro de su propia ventana **sí** llegan (es como funciona hoy propina/calificación).
- Es **sin estado**: lee el singleton `CustomerDisplayState`. Si se recrea, no se pierde nada.
- La termina `CustomerDisplayManager.detach()` cuando la caja pasa a segundo plano — si no, el
  cliente se queda viendo un total congelado.

### 5.6 Persistencia y autoridad

Campo nuevo: **`Terminal.customerDisplayInverted Boolean @default(false)`** — columna dedicada, no
`configOverrides`. Razón: `configOverrides` está documentado como *"set from the dashboard — the
DEVICE never controls it"* (`terminal.tpv.controller.ts:660`) y el sync del TPV lo reemplaza
completo; meter ahí un valor que el equipo escribe es pedir que se borre solo. La columna sigue el
precedente de `defaultWorkspace` / `canIssueAreaTickets`, que ya son campos por dispositivo.

- **Lee:** campo **aditivo y opcional** dentro del `deviceTerminal` que ya devuelve
  `GET /api/v1/mobile/venues/:venueId/settings`. Ese canal por dispositivo ya existe y se
  identifica con el header `x-device-id`. Nunca se quita ni se renombra nada (apps viejas).
- **Escribe desde la app:** `PATCH /api/v1/mobile/venues/:venueId/terminals/:terminalId/display-mode`
  (nuevo, mínimo — no hay hoy ningún endpoint mobile de escritura de settings de terminal).
- **Escribe desde el dashboard:** reusa `PUT /api/v1/dashboard/venues/:venueId/tpv/:tpvId`
  (`updateTpv`, permiso `tpv:update`).
- **Regla de conflicto:** el valor **local aplica siempre** (funciona sin internet y en un POS
  recién instalado). Si hay un cambio local sin confirmar (`dirty`), la app **empuja** y ese
  refresh no lo pisa; al confirmar, `dirty = false` y el server vuelve a mandar. 🔴 **Un refresh
  fallido no cambia nada** — la lección de `PrintConfigRepository`.
- Nombre **idéntico** en los tres repos: `customerDisplayInverted`.

### 5.7 UI — Android

`Más → Hardware → Pantalla del cliente` (la fila ya existe, `MoreMenuScreen.kt:546`) →
`CustomerDisplaySheet` gana una segunda fila:

- **"Invertir pantallas"**, subtítulo *"El cliente ve la pantalla grande"*.
- **Confirmación antes de aplicar** con `AvoqadoDialog`: *"La caja se va a reiniciar en la otra
  pantalla."* **Bloqueada si hay carrito o cobro en curso** — mover la caja recrea la Activity.
- **`AvoqadoSuccessToast`** al terminar: *"¡Pantallas invertidas!"*.
- Cuando `invertible = false`, el switch **se ve y se explica**, nunca desaparece:
  *"La segunda pantalla de este equipo no es táctil; el cajero no podría trabajar en ella."*
- Si el modo quedó marcado *no soportado* por el guard:
  *"Este equipo no permitió mover la caja a la otra pantalla."*

### 5.8 UI — dashboard

`src/pages/Tpv/TpvId.tsx` (ficha del dispositivo, ya tiene pestañas y usa `Switch`): un toggle
**Invertir pantallas** en la configuración del dispositivo, con la misma explicación de una línea.
Es el mismo registro del server que escribe la app.

### 5.9 MCP

El campo se refleja en la herramienta de terminales del MCP (`src/mcp/tools/terminals.ts`, que ya
expone `config`/`configOverrides`) **en el mismo cambio**. Una capacidad que no se ve por el MCP
está incompleta.

---

## 6. Pruebas

### 6.1 Unitarias (van primero — TDD)

`DisplayRoleResolver`, sin Android y sin hardware:

1. Sin segunda pantalla → cajero en default, cliente `null`, `invertible = false`.
2. Segunda física, modo normal → cajero default, cliente secundaria.
3. Segunda física, modo invertido → cajero secundaria, cliente default.
4. Segunda **virtual de vendor** (T3 Pro) → sirve como cliente, `invertible = false`, y pedir
   invertido **no** mueve la caja.
5. Segunda virtual de **captura** (AnyDesk/scrcpy) → descartada, como hoy.
6. Física + captura a la vez → gana la física.
7. Invertido y la pantalla del cajero desaparece → roles vuelven a default sin rebote.

### 6.2 En hardware (D3 físico)

- La caja arranca en la chica y **el teclado sale ahí** (buscar un producto, escribir una nota).
- El cliente ve su pantalla en la grande, y **propina/calificación responden al toque**.
- **Impresión:** comanda y recibo en modo invertido — es la preocupación explícita del founder.
- Cobro con terminal completo en modo invertido.
- Desconectar y reconectar la pantalla del cliente a media venta: la caja no queda huérfana.
- Apagar y prender el equipo en modo invertido: arranca en la pantalla correcta.
- Volver a modo normal y confirmar que **todo queda exactamente como antes**.

---

## 7. Riesgos abiertos

| Riesgo | Estado |
|---|---|
| Teclado del cajero en la pantalla chica | ✅ **Comprobado** en el D3 (`mCurTokenDisplayId=2`) |
| Mover la app a la pantalla chica | ✅ **Comprobado** (`am start --display 2`) |
| Cliente en la pantalla principal vía Activity | ⏳ Requiere código; el mecanismo (dos Activities, una por pantalla) sí está comprobado |
| **Sin barras de sistema en la chica** | Confirmado: sin reloj/wifi/batería ni "atrás" del sistema. Hay que revisar que **toda** pantalla tenga su propia salida. Para un POS es casi ventaja (modo kiosco) |
| Altura de 533 dp en la chica | El cobro cabe pero va apretado. Verificar en vivo y, si estorba, usar el layout de una columna en modo invertido |
| ¿La Activity del cliente le roba el IME al cajero? | Mitigado con `FLAG_NOT_FOCUSABLE`; verificar en hardware |
| T3 Pro y similares | **No soportado a propósito**; el switch se ve apagado y explicado |

---

## 8. Qué NO rompe (y por qué)

- **Impresión:** el ruteo va por red/USB/Bluetooth y la impresora interna por su servicio AIDL.
  Nada de eso cuelga de la pantalla. Es dibujo, no plomería. (Se prueba igual — ver §6.2.)
- **Cobro con tarjeta, escáner, cajón:** viven en la Activity y se mueven con ella.
- **Modo normal:** no se toca ni una línea del camino que hoy funciona en producción.

---

## 9. Decisiones tomadas y fuera de alcance

- **Tier: FREE.** Es un ajuste de cómo está armado el mostrador, no valor de pago.
- **Default: OFF.** Prenderlo por nuestra cuenta movería la caja de alguien sin avisar.
- **iOS: no aplica.** No existe pantalla de cliente en `avoqado-ios` (búsqueda de
  `customerDisplay`/`externalDisplay`/`UIScreen` sin resultados). Es la excepción de hardware por
  plataforma que permite la regla de paridad, y queda declarada aquí.
- **Presentación de ventas: exenta.** Es un ajuste de hardware de una capacidad que ya existe
  (*customer display*), no una capacidad nueva vendible.
- **Fuera de alcance:** rediseñar la pantalla del cliente para aprovechar 1920×1080, y cualquier
  gating de tier en Android (no existe hoy).
- **Pendiente con el proveedor:** si el D3 admite la configuración de código del K2mini para
  intercambiar la pantalla principal. Sería un plus, no un requisito.

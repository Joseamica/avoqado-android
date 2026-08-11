# Doble cobro con tarjeta — un fallo de transporte no es un fallo de cobro

**Fecha:** 2026-08-10 · **Rama:** `fix/doble-cobro-tarjeta` · **Repo:** `avoqado-android`

---

## 1. El incidente, y por qué la app lo interpretó mal

Medido con una tarjeta real en un Sunmi D3:

```
17:20:11-17:20:17  el backend de desarrollo REINICIÓ
      → ngrok devolvió 503; la espera larga de la app murió
      → la app pintó "Error en el pago / Error al procesar pago (503)"  [Reintentar] [Cancelar]
17:20:25.908  la TERMINAL reportó 'success' → el server registró el pago COMPLETED
              "⚠️ No in-flight long-poll for requestId"  (la app ya se había ido)
```

El cajero tocó **Reintentar** → "Seleccionar terminal" → **la tarjeta se cobró otra vez**.
Dos pagos COMPLETED de $0.25, en dos órdenes distintas, por una sola venta real.

La causa raíz no es el 503: es que **la app concluye "el pago falló" a partir de un error de
transporte**, en un punto donde la terminal ya pudo haber cobrado. Y su `Reintentar` arrancaba un
cobro nuevo sin preguntar cómo había quedado el anterior.

---

## 2. Qué encontré del long-poll

`TerminalPaymentService.sendPaymentToTerminal` hace un POST a
`/mobile/venues/{venueId}/terminal-payment` con `readTimeout` de **310 s** (el server hace
long-poll y corta a los 300 s). Ya existía una recuperación por estado durable —
`resolveViaStatus`, del commit `5463329`— **pero sólo se alcanzaba por dos puertas**:

| Camino | Antes | Problema |
|---|---|---|
| `504` | → `resolveViaStatus` | OK |
| Excepción (corte de red, timeout de OkHttp) | → `resolveViaStatus` | OK |
| **`503`, `502`, `500`, `408`** | **→ `else` → `Error("Error al procesar pago (503)")`** | 🔴 **el bug medido** |

En OkHttp un 503 es una **respuesta normal**, no una excepción — por eso caía al `else` ciego.
En iOS ese mismo 503 sí lanza (`APIError.serverError`), y por eso iOS no reproduce este trigger
concreto (ver §7).

Dos defectos más del código que ya estaba:

- **`getPaymentStatus` devolvía `TerminalPaymentStatusDto?`**, colapsando en un solo `null` dos
  cosas opuestas: `404 NOT_FOUND` (la solicitud **nunca existió** → nadie pasó una tarjeta) y
  "no pude preguntar" (**ignorancia pura**). Con ese `null`, `resolveViaStatus` devolvía siempre
  un `Error` → pantalla de Error → `Reintentar` → cargo a ciegas.
- **`resolveViaStatus` colapsaba "no se sabe" en `TerminalPaymentResult.Error`**, o sea que el
  desenlace indeterminado terminaba pintado como fracaso. Es la misma familia de la regla del
  repo: *"jamás pintes un éxito encolado como pantalla de Error"*.

Y un tercer hueco, encontrado por el coordinador probando en el mismo D3: **cancelar desde la
terminal (Nexgo) no emite nada**, y el POS se quedaba en *"Procesando pago… Esperando respuesta
de la terminal"* indefinidamente, sin salida.

---

## 3. ¿Existía el endpoint de consulta? Sí

**No hizo falta tocar el server.**

```
GET /api/v1/mobile/venues/:venueId/terminal-payment/:requestId
```

- Ruta: `avoqado-server/src/routes/mobile.routes.ts:1436`
- Controller: `src/controllers/mobile/terminal-payment.mobile.controller.ts:285`
- Servicio: `src/services/terminal-payment.service.ts:556`

Devuelve `{ success, inProgress, status, paymentId, terminalId, amount, tip, orderId, lateResult, … }`,
con `inProgress = status ∈ {PENDING, SENT, CANCEL_REQUESTED}` y 404 si no existe.
Estados terminales: `COMPLETED · FAILED · CANCELLED · TIMED_OUT · UNKNOWN`.

> **Limitación (no bloqueante, para cuando se toque el server):** el endpoint **no expone el
> motivo del rechazo**. La columna `failureCode` existe y se escribe
> (`terminal-payment.service.ts:638,655`) pero `getPaymentStatus` no la selecciona. Por eso el
> desenlace "declinada" muestra hoy un motivo genérico ("El cobro fue rechazado. No se cobró la
> tarjeta.") en vez del motivo del banco. Agregar `failureCode` al `select` y al JSON sería un
> cambio aditivo y compatible.

---

## 4. Los tres desenlaces, y cómo se distinguen

La decisión vive en una función **pura**, sin red y sin Android:
`app/src/main/java/com/avoqado/pos/payment/domain/CardChargeOutcome.kt`

```
ChargeWaitEnding          →  ¿hay que ir a preguntar?      (mustReconcile)
   Http(5xx | 408)        →  sí   (el server nunca dijo el desenlace)
   NetworkError           →  sí
   CeilingExceeded        →  sí   (se venció el plazo del POS)
   Http(4xx)              →  no   (respuesta real de negocio: consta que no se despachó)

ChargeStatusProbe         →  ¿qué desenlace?               (decide)
   Known("COMPLETED")     →  Charged        → éxito normal, sin error a la vista
   Known("FAILED")        →  NotCharged     → error real; Reintentar es SEGURO
   Known("CANCELLED")     →  NotCharged     → idem
   Known(inProgress)      →  KeepPolling
   Known(otro/desconocido)→  Undetermined   (TIMED_OUT, UNKNOWN, o un estado futuro)
   NotFound (confirmado)  →  NotCharged     → la solicitud nunca existió
   NotFound (1ª lectura)  →  KeepPolling    → pudo ser un POST rezagado
   Unreachable            →  KeepPolling → al agotarse: Undetermined
```

**Lo que ve el cajero en cada uno:**

| Desenlace | Pantalla | Acciones |
|---|---|---|
| **Cobró** | Éxito normal (recibo, propina/calificación si aplican) | Ningún error. Si se descubrió tarde, el cajero ni se entera |
| **No cobró** | `PaymentErrorView` con el motivo | **Reintentar** — seguro, consta que no hubo cargo |
| **No se sabe** | `PaymentUndeterminedView` (NUEVA) | **Volver a consultar** (destacada, sólo pregunta) · *Cobrar de nuevo* (advertencia obligatoria) · Cancelar |

La pantalla nueva dice **"Cobro sin confirmar"**, nunca "Error", con el texto
*"No pudimos confirmar el cobro. Revisa la terminal antes de volver a cobrar."*
"Cobrar de nuevo" **no cobra directo**: abre un `AvoqadoDialog` que advierte
*"Puede que la tarjeta YA se haya cobrado… esto le cobrará al cliente por segunda vez."*
Sólo un "Sí, cobrar de nuevo" humano y explícito dispara un cargo.

### `retry()` ya no cobra a ciegas

```kotlin
PaymentMethod.CARD -> {
    val pending = undeterminedRequestId
    if (pending != null) { reconcileThenOffer(pending, total); return }  // primero CONSULTA
    _state.value = PaymentFlowState.SelectingTerminal(total)             // sólo si consta que no hay cobro
    fetchTerminals()
}
```

`undeterminedRequestId` es **por venta** (se limpia en `startPaymentFlow`, igual que la llave de
idempotencia). Arrastrarlo entre ventas habría hecho que un `retry()` consultara el cobro de la
venta anterior y pintara como cobrada una venta que nadie cobró — cubierto con test.

---

## 5. El plazo de espera (`WAIT_CEILING_MS = 330 s`) y por qué ese número

El POS ya no espera para siempre. Al vencer **no declara fracaso**: corta la espera, va a
consultar el estado durable y, si tampoco se puede saber, cae en la pantalla honesta.

El número **tiene que ser más largo que la ventana que la terminal tiene a propósito, no más
corto** (`.claude/rules/offline-first-y-hub-lan.md`: *"la terminal espera 310 s a propósito:
alguien tiene que pasar la tarjeta"*):

| | |
|---|---|
| Server corta su long-poll | **300 s** ("La terminal no respondió en 5 minutos") |
| `readTimeout` de este cliente | **310 s** (10 s para que la respuesta viaje) |
| **Tope de reloj de pared del POS** | **330 s** (20 s de holgura sobre el 310 s) |

Es un tope de *reloj de pared*, no de socket: cubre el caso en que el socket queda vivo pero mudo
(proxy con keep-alive, TCP a medio cerrar) y el `readTimeout` nunca dispara — que es exactamente
el cuelgue observado. Se implementa con un watchdog que hace `call.cancel()` de OkHttp (cerrar el
socket es lo único que saca a `execute()` de un bloqueo). Un plazo más corto rompería cobros
legítimos, que sería peor que el bug que cierra. Hay un test que blinda `> 310 s`.

> El hueco de origen —que la app de la Nexgo no emita el resultado al cancelar, como sí hacía
> Blumon— vive en `avoqado-tpv` y se abre por separado. **No se tocó aquí.**

---

## 6. Evidencia de tests

TDD: los tests se escribieron primero y **se vio el rojo** (`Unresolved reference
'CardChargeDecision'`, `BUILD FAILED in 1m 12s`) antes de existir la implementación.

```
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:testDebugUnitTest
BUILD SUCCESSFUL · tests=909 failures=0 errors=0        (883 antes + 26 nuevos)

JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:assembleDebug
BUILD SUCCESSFUL
```

**`CardChargeDecisionTest` (21 tests)** — la decisión pura:
- `el escenario exacto del doble cobro — transporte 503 con la terminal YA cobrada` → `Charged`
- 5xx/408 son transporte; 404/409/422 **no**
- `COMPLETED`→cobró · `FAILED`/`CANCELLED`→no cobró · `TIMED_OUT`/`UNKNOWN`/estado futuro→**indeterminado**
- un 404 aislado **no** se cree a la primera; confirmado sí
- server inalcanzable hasta el final → indeterminado, nunca fracaso
- `vencerse el plazo manda a preguntar el estado — no es un fracaso`
- `el plazo maximo del POS es MAS LARGO que la ventana que la terminal tiene a proposito`

**`PaymentFlowViewModelTest` (5 nuevos)** — el flujo:
- `un desenlace no confirmado NO se pinta como Error`
- `retry con un cobro sin resolver NO cobra — primero consulta` → `sendPaymentToTerminal` **exactly = 1**
- `si la re-consulta dice que SI se cobro, el cajero ve exito y no un error` (paymentId `pay-tarde`, un solo cargo)
- `si consta que NO se cobro, recien ahi se ofrece cobrar` → `SelectingTerminal`
- `un cobro sin resolver NO se arrastra a la siguiente venta`

No se probó en hardware físico: hace falta un D3 + terminal + un backend que se pueda reiniciar a
media espera. La lógica de decisión está cubierta al 100 % por tests puros.

---

## 7. 🔴 Paridad iOS — NO portado, y iOS tiene el mismo desenlace colapsado

La regla del repo exige portar los fixes de dinero en el mismo trabajo. **No se hizo**: esta
tarea llegó explícitamente acotada a `avoqado-android`. Queda declarado aquí, no en silencio.

Lo que revisé en `avoqado-ios`:

| | iOS hoy |
|---|---|
| Trigger 503 | ✅ **No lo tiene** — `APIClient` lanza en 5xx, así que ya cae en `resolveViaStatus` (`TerminalPaymentService.swift:189`) |
| Espera infinita | ✅ No la tiene — `timeout: 315` en la petición |
| **Tercer desenlace** | 🔴 **Lo tiene colapsado**: `resolveViaStatus` lanza `TerminalPaymentError.timeout` y `PaymentFlowViewModel.swift:1391` lo convierte en `state = .error(...)` → pantalla de Error con Reintentar |
| **404 vs "no pude preguntar"** | 🔴 Igual de conflado: `try?` en `getPaymentStatus` hace que un 5xx del status se vea como 404 (`TerminalPaymentService.swift:218`) |

**Delta pendiente en iOS:** `CardChargeDecision` en Swift + tests, `case undetermined` en
`PaymentFlowState` (`PaymentModels.swift:133`), la vista equivalente a `PaymentUndeterminedView`,
y que su retry re-consulte antes de ofrecer cobrar. Los textos en español y los nombres de estado
deben espejarse **exactos**.

---

## 8. Segunda pasada — las dos ventanas que el arreglo no abría pero tampoco cerraba

La revisión encontró que el fix original era correcto pero **no durable**, y que un camino
seguía declarando "no se cobró" sin poder saberlo. Ambas cerradas.

### R2 — la pantalla honesta no sobrevivía a un cambio de pestaña

El agujero: `PaymentFlowScreen` no tenía `BackHandler` y su host era
`var showPaymentFlow by remember` (no `rememberSaveable`). El cajero que ve "Cobro sin
confirmar" hace lo que hace la gente — irse a **Transacciones** a comprobar si el pago entró.
Ese solo cambio de tab desmontaba la composición, la pantalla desaparecía en silencio, y el
siguiente "Cobrar" llamaba `startPaymentFlow` con `undeterminedRequestId` en RAM ya perdido:
**cargo a ciegas, sin advertencia.** Toda la ceremonia se evaporaba.

Cerrado en tres capas:

1. **La llave vive en DISCO** — `SecureStorage.pendingCardChargeRequestId`.
   `TerminalPaymentService.unresolvedRequestId` ahora delega ahí (era el código muerto que el
   revisor detectó: 9 escrituras, cero lecturas — **era justo la llave durable que faltaba**).
   No se limpia en `clearSession`: un cobro sin confirmar no deja de existir porque alguien
   cierre sesión o cambie de venue.
2. **`startPaymentFlow` la consulta ANTES de dejar cobrar.** Si hay llave pendiente, la venta
   nueva se topa con la pantalla "Cobro anterior sin confirmar" en vez de arrancar limpia.
3. **`BackHandler`** en el estado indeterminado + `rememberSaveable` en el host. Salir sigue
   permitido —bloquear la caja sería peor— pero nunca en silencio: un diálogo avisa que el cobro
   queda pendiente y que volverá a aparecer.

**Distinción crítica:** un cobro pendiente de OTRA venta no puede pagar la actual. Por eso
conviven dos referencias — la durable del servicio (disco, global) y la de la pantalla
(`SavedStateHandle`, de esta venta). Si difieren, `fromPreviousSale = true` y confirmar aquel
cobro **sólo suelta el bloqueo**: muestra "El cobro anterior sí se había realizado" y arranca
la venta actual desde su primer paso. Nunca `Success`. Cubierto con test.

### R1 — un 404 rápido ya no se declara "no se cobró"

El sondeo (0 / +500 ms / +2 s) resolvía un `NotFound` sostenido a `NotCharged` → pantalla de
Error → **Reintentar sin advertencia**. Pero el server crea la fila antes de emitir a la
terminal *con `validateStaffVenue` y la query de `order.paymentStatus` de por medio*: si el
socket murió con el request ya enviado, esa ventana supera los 2.5 s en un backend cargado o
recién arrancado. Y a `resolveOutcome` sólo se llega tras un final de transporte, o sea que ya
hay duda.

Ahora **`NotFound` → `Undetermined`**. Como efecto, `NotCharged` sólo nace de un estado terminal
que lo AFIRME (`FAILED`, `CANCELLED`): nunca de una ausencia ni de una ignorancia. Test:
`NO COBRO solo sale de un estado terminal que lo diga — nunca de una ausencia`.

### El sondeo ya no hereda el cliente de 310 s

`getPaymentStatus` reusaba el `OkHttpClient` de 310 s: tres sondeos contra un proxy que acepta
la conexión y nunca contesta daban hasta **~15 min de "Consultando…"** — el mismo cuelgue que el
tope de espera vino a matar. Ahora hay un `statusClient` aparte (conectar 5 s, leer 8 s,
`callTimeout` 10 s — acota la llamada COMPLETA) más un tope de reloj de pared de 35 s en
`resolveOutcome`. El plazo largo existe porque alguien tiene que llegar a pasar la tarjeta;
una **consulta** no espera a nadie.

### "Cancelar" ya no miente

`viewModel.cancel()` disparaba `cancelOrder` y navegaba afuera; un rechazo del server (409: la
orden ya está pagada) sólo se logueaba. El cajero creía que canceló algo que seguía vivo, y el
cobro podía aterrizar sobre esa orden. Nuevo `cancelAndExit()` —usado por las pantallas de
dinero (Error e indeterminado)— **espera el resultado**: sólo sale si la cancelación quedó, y si
el server rechaza muestra *"No se pudo cancelar la orden: … Sigue abierta — revísala en
Órdenes."* en un `AvoqadoErrorToast`.

### El pegamento, por fin con pruebas

`TerminalPaymentServiceHttpTest` (**16 tests, MockWebServer real**) cubre lo que nadie cubría —
el `when (responseCode)` de `sendPaymentToTerminal` y el mapeo de `getPaymentStatus`, que es
exactamente donde vivía el incidente:

- 503 + terminal ya cobrada → **Success** (el escenario del doble cobro, extremo a extremo)
- 503 + `FAILED` → Error · 503 + server también caído → Undetermined **conservando la llave**
- 404 / 409 → error de negocio directo, **sin consultar** (`requestCount == 1`)
- `getPaymentStatus`: 200 → `Known` · 404 → `NotFound` · 5xx/401/sin sesión → `Unreachable`
- `resolveOutcome` insiste mientras siga en curso; un 404 sostenido queda indeterminado

Requiere `runBlocking`, no `runTest`: el reloj virtual salta hacia adelante mientras la llamada
HTTP real está bloqueada y disparaba el tope de 35 s antes de que el servidor contestara.

### Menores

- `ceilingExceeded` pasó a `AtomicBoolean` (lo escribe el watchdog desde otro hilo).
- `recheckCardCharge` con la referencia de pantalla vacía cae a la llave durable — tras una
  muerte de proceso es la única que queda.
- Los 330 s se dejan como están: el margen sobre los 310 s está justificado en §5 y acortarlo
  rompería cobros legítimos.

### Verificación de esta pasada

```
:app:testDebugUnitTest   BUILD SUCCESSFUL · tests=926 failures=0 errors=0   (909 → 926)
:app:assembleDebug       BUILD SUCCESSFUL
```

---

## 9. Archivos tocados

| Archivo | Qué |
|---|---|
| `payment/domain/CardChargeOutcome.kt` | **NUEVO** — decisión pura: `ChargeStatusProbe`, `ChargeWaitEnding`, `CardChargeOutcome`, `CardChargeDecision` |
| `payment/data/TerminalPaymentService.kt` | 5xx/408 → reconciliación; `getPaymentStatus` devuelve `ChargeStatusProbe`; `resolveOutcome` público; tope de espera; `TerminalPaymentResult.Undetermined` |
| `payment/data/model/PaymentModels.kt` | `PaymentFlowState.Undetermined(totalAmount, message, checking)` |
| `payment/presentation/PaymentFlowViewModel.kt` | `retry()` re-consulta; `recheckCardCharge()`, `chargeAgainDespiteUndetermined()`, `applyCardCharged()`; `undeterminedRequestId` por venta |
| `payment/presentation/PaymentResultScreen.kt` | **`PaymentUndeterminedView`** + diálogo de advertencia |
| `payment/presentation/PaymentFlowScreen.kt` | Ruteo del estado nuevo |
| `core/data/local/SecureStorage.kt` | **`pendingCardChargeRequestId`** — la llave durable en disco |
| `pos/presentation/checkout/CheckoutScreen.kt` | `rememberSaveable` en el host del flujo de cobro |
| `test/.../CardChargeDecisionTest.kt` | **NUEVO** — 22 tests (decisión pura) |
| `test/.../TerminalPaymentServiceHttpTest.kt` | **NUEVO** — 16 tests con MockWebServer (el pegamento HTTP) |
| `test/.../PaymentFlowViewModelTest.kt` | +7 tests (flujo, durabilidad, venta anterior) |

**Tier:** ninguno. Es un fix de corrección en el camino del dinero, no una capacidad nueva — se
aplica en todos los planes, sin gating ni switch. No hay cambio visible al cliente que obligue a
tocar la presentación de ventas, ni capacidad nueva que exponer por el MCP.

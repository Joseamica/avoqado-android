# Estrategia de refresco: pull-to-refresh + revalidación al entrar

**Fecha:** 2026-08-13
**Repos:** `avoqado-android` + `avoqado-ios` (paridad obligatoria)
**Estado:** v3 — segunda auditoría (Claude, 2026-08-13): todas las cifras y citas
`archivo:línea` de v2 se re-verificaron contra el código y **cuadraron**; se cerró
un hueco de diseño del guard (§4.3, §4.5) y se sumaron hallazgos nuevos (§4.8, §11).

> **v1 tenía tres cifras equivocadas y una afirmación de diseño falsa.** Todas
> verificadas y corregidas abajo. La sección 11 lista qué cambió y por qué, para
> que nadie reintroduzca los errores leyendo notas viejas.

---

## 1. El problema, medido

El founder reporta: "muchos lugares no tengo cómo refrescar y no se refresca
automáticamente ciertas cosas". Medido, son **dos** problemas — no tres:

### 1.1 Falta el gesto, no la plomería (Android) — ESTE ES EL GRANDE

**14 ViewModels exponen un método público exacto `refresh()`** y no hay ninguna
forma de invocarlo desde la UI.

Verificado con:
```bash
rg -l --glob '*ViewModel.kt' '^\s*fun\s+refresh\s*\(' app/src/main/java | wc -l   # → 14
```

**Cero** ocurrencias de `PullToRefresh`, `pullRefresh` o `SwipeRefresh` en todo
`avoqado-android` (excluyendo `docs/` y `build/`). Solo 3 pantallas tienen botón
manual: `TablesScreen`, `CalendarTabHost`, `CheckoutScreen`.

🔴 **No contar por substring `refresh`.** Da 16 y mete falsos positivos que no
son "recargar lo que se ve": `MainTabHostViewModel.refreshFromStorage()`,
`CartViewModel.refreshCustomerDisplay()` y `.refreshProducts()`, y el **privado**
`CreateReservationViewModel.refreshStaffForCurrentProduct()` — que además
*borra* `assignedStaffId`/`assignedStaffName` al cambiar el mapping
(`CreateReservationViewModel.kt:333`). Ese último no se conecta a nada sin una
decisión de semántica aparte.

### 1.2 Pantallas sin recarga: **una**, no siete

De la lista de v1, solo **`CreateClassSessionViewModel`** encaja literalmente en
"carga remota una vez y no expone recarga". Las demás fueron mal clasificadas por
una heurística que solo buscaba `refresh|reload|onResume|onAppear`:

| ViewModel | Realidad |
|---|---|
| `KDSViewModel` | **Ya se refresca solo**: `while (isActive) { delay(10_000) }` (`KDSViewModel.kt:106`) |
| `WaitlistViewModel` | Ya expone `load()` (`:61`) |
| `TableOrderViewModel` | Ya expone `loadCheck()` (`:170`), más `loadMenus()`, `loadServiceCharges()` |
| `PaymentFlowViewModel` | `init` instala callbacks y cinco collectors — no es carga de datos |
| `SignInViewModel` | **No hace carga remota**: solo lee almacenamiento local (biometría) |
| `CashDrawerViewModel` | Revisar caso por caso antes de tocarlo |

🔴 **Consecuencia para quien implemente:** extraer el `init {}` a un `refresh()`
público **duplicaría suscripciones, polls y callbacks**. Regla: las
suscripciones y el `bindOnce()` se quedan en `init`; solo se extrae la operación
de carga **idempotente**.

### 1.3 Realtime: no existe en ninguna de las dos apps (FUERA DE ALCANCE)

v1 afirmaba "solo `TablesViewModel` y `PaymentFlowViewModel` escuchan Socket.IO
en Android". **Es falso: hay cero clientes Socket.IO en Android y cero en iOS**
— ni imports ni dependencias. El grep de v1 matcheó `SocketTimeout` dentro de un
`catch` (`TablesViewModel.kt:243`).

Lo que sí existe y no debe confundirse con realtime: buses locales en proceso
(el `orderBus` de KDS), polling (KDS cada 10 s) y el relay de pagos de terminal.

No cambia la decisión —realtime queda fuera— pero sí invalida el mapa de
arquitectura con el que se justificaban excepciones.

### 1.4 El reparto entre plataformas está al revés de lo asumido

iOS **ya tiene `.refreshable` en 16 vistas**. Android tiene cero. El atrasado es
Android.

---

## 2. Alcance

**Dentro:** el gesto de jalar para refrescar y la revalidación automática al entrar.

**Fuera:** realtime / Socket.IO — proyecto aparte que toca el server.

---

## 3. Decisiones

| Decisión | Valor | Por qué |
|---|---|---|
| Disparador | Revalidar **al entrar**, no polling | Un temporizador cada 30 s son ~960 llamadas por pantalla en un turno de 8 h con la tablet quieta |
| Filtro de antigüedad | **TTL 30 s** | Mesas→Cobrar→Mesas hace 1 llamada, no 3 |
| Trabajo en curso | El auto-refresh **se salta** la pantalla | En un POS, refrescar bajo el dedo tira carritos y cuentas editándose |
| Gesto manual | Ignora el TTL, **pero no el guard de dinero/stock/edición** | Corregido en v2: ver §4.5 |
| Sin red | **No es error** | Regla de offline-first ya escrita |
| Reloj | **Monotónico** (`elapsedRealtime()`) | Ver §4.6 |

### Precedente interno

`avoqado-web-dashboard` ya se comporta así: TanStack Query con `new QueryClient()`
sin config global (`main.tsx:30`) → `refetchOnWindowFocus: true`,
`refetchOnMount: true`, **`refetchInterval: false`** (sin polling). Afinado por
caso: `staleTime: 1 min` en auth/KYC (`AuthContext.tsx:114`),
`refetchOnWindowFocus: false` en notificaciones (`NotificationContext.tsx:141`).
Y sus query keys **ya incluyen `venueId`** (p. ej. `PayLaterAging.tsx:151`) —
que es justamente la identidad que a v1 le faltaba (§4.4).

---

## 4. Arquitectura

Enfoque: **pieza chica compartida, adopción pantalla por pantalla.** Se
descartaron la clase base de ViewModel (herencia rígida con Hilt, sin
equivalente limpio en Swift → divergencia) y una capa de caché tipo Store5
(reescribe la capa de datos de las dos apps; YAGNI).

### 4.1 🔴 Precondición: los repositorios no pueden borrar datos buenos

**Esto va ANTES de conectar cualquier gesto.** Hoy la promesa de "conserva lo
anterior" es falsa:

| Repositorio | Qué hace hoy |
|---|---|
| `OrdersRepository.kt:124`, `:177` | Convierte el fallo HTTP en `OrderPage()` vacío y **reemplaza** `_orders` con el vacío |
| `ReportsRepository.kt:94`, `:258` | Convierte fallos parciales o globales en `ReportData` vacío y lo publica |
| `ArticlesScreen.kt:86` | Publica `errorMessage` y la pantalla saca snackbar — el auto-refresh no sería silencioso |

Entrar sin red **borra visualmente pedidos e informes válidos**. Es el mismo
patrón del bug de `PrintConfigRepository` que ya está documentado en
`.claude/rules/offline-first-y-hub-lan.md:55`: un refresh fallido pisando la
config buena. Conectar el gate encima de esto multiplicaría el daño.

**Fix, por pantalla, antes de tocar la UI:** el refresh en segundo plano
preserva el payload anterior y devuelve un fallo **tipado**. Se separan tres
cosas que hoy están revueltas: `initialLoadError` (no hay nada que mostrar),
`backgroundRefreshError` (hay datos viejos, silencioso) y rechazo de negocio
(401/403/409, se propaga).

### 4.2 Contrato de refresco: `suspend fun refreshNow(): Result<Unit>`

Los `refresh()` actuales devuelven `Unit` y lanzan su propio
`viewModelScope.launch`; `TransactionRepository.fetchTransactions()` incluso se
traga la excepción (`TransactionRepository.kt:84`). **Con esas firmas el gate no
puede distinguir éxito de fallo, y marcaría como fresca una petición todavía en
vuelo.**

Cada pantalla adoptante expone:

```kotlin
suspend fun refreshNow(): Result<Unit>   // sin launch interno
```

La corrutina la crea el entrypoint de UI. El gate mueve el reloj solo tras
`Result.success`.

### 4.3 `RefreshGate` — con single-flight

```kotlin
enum class RefreshOutcome {
    Completed,        // block corrió y terminó en éxito
    Joined,           // había petición en vuelo; esta llamada esperó su resultado
    SkippedFresh,     // dentro del TTL — no se pidió
    SkippedBusy,      // workInProgress() == true — no se pidió
    SkippedCooldown,  // en cooldown tras fallos — no se pidió
    Failed,           // block corrió y falló (red)
}

class RefreshGate(
    private val ttl: Duration = 30.seconds,
    private val clock: () -> Duration,      // monotónico, inyectado
) {
    suspend fun run(
        workInProgress: () -> Boolean,      // v3: FUNCIÓN, no snapshot — ver invariante 6
        manual: Boolean,
        block: suspend () -> Result<Unit>,
    ): RefreshOutcome
    fun invalidate()      // el próximo auto ignora el TTL; un éxito en vuelo no sella
    fun resetCooldown()   // cableado a la recuperación de conectividad (invariante 4)
}
```

Invariantes:

1. 🔴 **Single-flight.** Un `Mutex` + bandera `inFlight`. **v1 afirmaba que el
   TTL absorbía la doble llamada de `LaunchedEffect` + `LifecycleResumeEffect`;
   es falso** — ambos leen el mismo `lastSuccessAt` viejo y ambos arrancan. Si
   ya hay una petición en vuelo, la segunda **se une** a ella, no lanza otra.
2. **Solo el éxito mueve el reloj**, y se sella con el instante de **término**,
   no el de inicio: si no, una petición vieja que termina tarde atrasa el reloj.
3. **Versión de invalidación.** `invalidate()` incrementa un contador; un éxito
   que llega con versión vieja **no** sella el reloj. Sin esto, un
   `invalidate()` durante una petición queda anulado por el éxito tardío.
4. **Cooldown de fallos** (`lastAttemptAt`, separado de `lastSuccessAt`) con
   backoff y jitter, que se reinicia al recuperar conectividad. Sin esto, con
   red intermitente cada entrada y cada resume reintentan de inmediato.
   *Wiring concreto:* Android ya tiene `ConnectivityMonitor.isConnected`
   (`core/util/ConnectivityMonitor.kt`) — se observa y al pasar a `true` se
   llama `resetCooldown()`. iOS: mismo patrón sobre su monitor de red.
5. **El guard aplica al automático**; el manual salta TTL y cooldown, pero **no**
   el guard de dinero/stock/edición (§4.5).
6. 🔴 **v3 — el guard se re-evalúa, no se fotografía.** `workInProgress` viaja
   como `() -> Boolean` y el gate lo evalúa **en el instante de decidir el
   lanzamiento**, no cuando el trigger disparó — cierra la brecha
   trigger→arranque. La ventana restante (el usuario empieza a editar con la
   petición YA en vuelo) **no la puede cerrar el gate**, porque `block` trae
   fetch y aplicación juntos: la cierra cada pantalla en §4.5.

### 4.4 🔴 Identidad del gate

El gate se identifica por **`(userId, venueId, resourceKey, filtros)`** y **vive
en el ViewModel**, no en el composable.

Sin esto: un éxito en la sucursal A bloquearía la carga de la B durante 30 s. El
código ya trata el cambio de venue como cambio total de identidad — Android
limpia siete cachés y recrea el árbol con `contentKey`
(`AvoqadoNavGraph.kt:120`), iOS recrea la vista con `.id(venueId:mode)`
(`MainTabView.swift:175`). El gate tiene que respetar esa frontera y cancelar lo
que esté en vuelo al cruzarla. Lo mismo en logout/login.

**Ownership:** estado efímero del ViewModel. Sobrevive cambios de configuración
(rotación), se reinicia con el proceso, la sesión o el contexto, y **nunca se
serializa** — un instante monotónico persistido en `SavedStateHandle` sería
basura tras un reinicio.

### 4.5 El guard: matriz por pantalla

No existe una señal global de "hay trabajo en curso", y **el estado editable no
siempre vive en el ViewModel**: en `IssueRefundSheet.kt:98` la cantidad, los
artículos, el motivo y los flags de envío están en `remember` de la UI, donde un
guard de ViewModel no los ve.

Cada pantalla adoptante declara su `workInProgress` observable, incluyendo
sheets abiertos, borradores y `isSubmitting`. Una pantalla de solo lectura pasa
`false`.

🔴 **El gesto manual no es carta blanca.** En dinero (devoluciones), stock
(conteo de inventario, `InventoryViewModel.kt:117`) y edición destructiva, el
manual **bloquea, confirma o difiere la aplicación del resultado** — no pisa el
trabajo del usuario solo porque él jaló la pantalla.

🔴 **v3 — pantallas cuyo dato visible ES el borrador** (carrito, cuenta de mesa,
conteo en curso): el guard del gate solo evita *arrancar* un refresh; una
petición que ya iba en vuelo cuando empezó la edición **aterriza igual**. En
esas pantallas, `refreshNow()` tiene la obligación adicional de **no aplicar el
payload encima de un borrador activo** — el chequeo va dentro del ViewModel, en
el punto de aplicación, donde el estado sí se ve. En iOS, cuando el estado del
sheet vive en `@State` de la vista (p. ej. `showRefundSheet` en
`TransactionListView.swift:12` y `TransactionDetailView.swift:62`), la vista lo
espeja al ViewModel con `.onChange(of:)` para que el guard lo alcance.

### 4.6 El reloj

**Android `SystemClock.elapsedRealtime()`** (monotónico, cuenta deep sleep).
**iOS `ContinuousClock`**. Nunca `currentTimeMillis()`: si alguien atrasa el
reloj del aparato, la pantalla queda "fresca" durante horas; si lo adelanta,
refresca de más. `uptimeMillis()` tampoco sirve — no cuenta suspensión.

### 4.7 `AvoqadoRefreshable` — el envoltorio

`PullToRefreshBox` de Material3 1.3.2 (BOM 2025.01.01, sin dependencias nuevas).
Es **`@ExperimentalMaterial3Api`** → requiere `@OptIn` explícito, y hay que
vigilarlo al subir el BOM.

**El wrapper NO es dueño del banner.** `ConnectivityBanner` ya está montado
globalmente (`AvoqadoNavGraph.kt:297`) y su copy es "las ventas se guardan en el
dispositivo". El wrapper solo **termina el spinner**; la conectividad global
sigue controlando el banner.

### 4.8 Disparadores

**Android:** las pantallas del menú Más **no son destinos de navegación, son
overlays** dentro de la misma Activity — el `ON_RESUME` de la Activity no se
dispara al abrirlas. Van dos: `LaunchedEffect` al mostrarse el overlay y
`LifecycleResumeEffect` para el regreso desde background. **La duplicación la
resuelve el single-flight de §4.3, no el TTL.**

**iOS:** `.onAppear` + `.onChange(of: scenePhase)`.

🔴 **v3 — triggers ad-hoc existentes: se REEMPLAZAN, no se les monta encima.**
Dos pantallas Android ya tienen revalidación al entrar hecha a mano, sin TTL ni
single-flight: `TransactionsScreen.kt:196-210` (contador `refreshKey` +
observer de `ON_RESUME`) y `CalendarTabHost`. Al adoptar el gate en cada una,
ese mecanismo se retira en el mismo cambio — dejarlo en paralelo duplica
peticiones y hace mentir al TTL. Igual con los **3 botones manuales** de
`TablesScreen`/`CalendarTabHost`/`CheckoutScreen`: al adoptar, o se rutean por
`gate.run(manual = true)` o se retiran; nunca quedan llamando `refresh()`
directo por fuera del gate. Y en iOS las **16 vistas `.refreshable`
existentes** no están exentas: §8 incluye migrarlas al contrato (gate + guard +
fallo silencioso), no solo agregar el gesto donde falta.

---

## 5. Flujo

```
Pantalla aparece / vuelve a primer plano / app vuelve del background
        ↓
gate.run(workInProgress, manual = false) { viewModel.refreshNow() }
        ↓
¿inFlight? → se une a la petición existente, no lanza otra
¿workInProgress? → no hace nada
¿dentro del TTL o del cooldown? → no hace nada
        ↓ procede
        ↓ Result.success          ↓ Result.failure (red)
sella reloj con el instante   datos previos intactos
de TÉRMINO, si la versión     el reloj NO avanza
de invalidación coincide      se arma el cooldown
```

---

## 6. Errores

| Caso | Comportamiento |
|---|---|
| Auto-refresh falla por red | **Silencioso.** Datos previos intactos; el reloj no avanza; entra cooldown |
| Gesto manual falla por red | El spinner termina. La conectividad global ya muestra el banner. Nunca error rojo |
| Rechazo de negocio (401/403/409) | Se propaga tal cual, como hoy |

---

## 7. Pruebas

`RefreshGate` es el único sitio con lógica:

- TTL: antes del umbral no pide; después sí.
- **Concurrencia:** dos llamadas simultáneas → **una sola** petición.
- **Orden inverso:** una respuesta vieja que llega tarde no atrasa el reloj.
- **`invalidate()` en vuelo:** el éxito tardío no sella el reloj.
- Fallo: no mueve el reloj y arma el cooldown; el manual salta el cooldown.
- Guard: con `workInProgress = true` nunca auto-refresca.
- **v3 — Guard re-evaluado:** `workInProgress` cambia a `true` entre el trigger
  y la decisión de lanzar → no lanza (la lambda se evalúa al decidir).
- **v3 — `resetCooldown()`:** tras un fallo, resetear limpia el backoff y el
  siguiente auto pide de inmediato.
- Cambio de `(userId, venueId)`: se resetea y cancela lo que esté en vuelo.
- Primer arranque: siempre pide.

Puro, con el reloj inyectado, sin red ni Compose. **Espejo exacto en Swift con
los mismos casos**, como el núcleo del hub LAN.

---

## 8. Entrega: rebanadas verticales

**Decisión del founder (2026-08-13):** cada pantalla se entrega en **Android e
iOS juntos**. Nunca existe una versión con el gesto y otra sin él.

Por cada pantalla, en este orden:
1. Arreglar su repositorio para que no borre datos al fallar (§4.1).
2. Convertir su carga a `suspend fun refreshNow(): Result<Unit>` (§4.2).
3. Declarar su `workInProgress` (§4.5).
4. Conectar el gesto en Android **y** en iOS.

Orden sugerido por uso: Transacciones → Órdenes → Artículos → Informes →
Inventario (ojo con §10) → el resto.

---

## 9. Tier y activación

Se registran por separado, como exige la regla:

- **Tier: core, todos los planes.** No es capacidad nueva ni valor de pago: es
  el acceso a datos que el usuario ya puede ver con los permisos que ya tiene.
- **Activación: sin switch.** No se pueden nombrar dos clientes reales que
  quieran lo contrario, así que es comportamiento core.
- **MCP y presentación de ventas: exentos.** No hay capacidad visible nueva.

---

## 10. Riesgos conocidos

- **`InventoryViewModel.refresh()` no cubre toda la pantalla.** Solo trae
  overview, materias primas y conteos (`:83`, `:203`, `:248`). Órdenes de compra
  y Traslados se cargan por métodos distintos (`:265`). Conectarlo tal cual daría
  un pull-to-refresh que **anima y no refresca nada** en dos secciones. Hay que
  despachar por `selectedSection`.
- **El TTL de 30 s es una apuesta, no una medición.** Es una constante.
- **`CreateReservationViewModel`** necesita decisión de semántica propia: su
  rutina de refresco borra el empleado asignado.
- **Superficie amplia**: ~19 pantallas en Android y ~22 vistas en iOS, con
  trabajo repetitivo propenso a copiar y pegar.

---

## 11. Qué cambió de v1 a v2 (auditoría Codex, 2026-08-13)

| # | v1 decía | Real |
|---|---|---|
| 1 | 17 ViewModels con `refresh()` | **14** — tres falsos positivos + un método privado |
| 2 | 7 ViewModels cargan una sola vez | **1** — KDS ya hace polling, Waitlist y TableOrder ya tienen carga, SignIn no carga nada remoto |
| 3 | 2 clientes Socket.IO en Android | **Cero** en las dos apps — el grep matcheó `SocketTimeout` |
| 4 | "El TTL absorbe la doble llamada" | **Falso** — hace falta single-flight |
| 5 | El gate podía saber si hubo éxito | **No** con las firmas actuales (`Unit` + `launch` interno) |
| 6 | "Conserva los datos anteriores" | **Falso hoy** — Órdenes e Informes publican vacío al fallar |
| 7 | Gate = un timestamp | Necesita `(userId, venueId, resourceKey)` |
| 8 | Manual ignora el guard siempre | No en dinero, stock ni edición destructiva |
| 9 | Fases: Android y luego iOS | Rebanadas verticales — violaba la regla de paridad |

**Lo que la auditoría NO pudo verificar** (queda para hardware): el
comportamiento real ante dos eventos simultáneos, rotación, suspensión y muerte
de proceso; no se corrieron builds, emulador ni tests.

## 12. Qué cambió de v2 a v3 (segunda auditoría, Claude, 2026-08-13)

Todas las cifras y citas `archivo:línea` de v2 se re-corrieron contra el código
y **cuadraron** (14 ViewModels, 0 pull-to-refresh Android, 16 `.refreshable`
iOS, 3 botones manuales, cero Socket.IO, los repos que publican vacío, el
precedente TanStack del dashboard, Material3 1.3.2). Cambios:

| # | v2 decía | v3 |
|---|---|---|
| 1 | `run(workInProgress: Boolean, …)` | `workInProgress: () -> Boolean`, re-evaluado al decidir el lanzamiento; la ventana en-vuelo la cierra la pantalla (§4.3 inv. 6, §4.5) |
| 2 | "no hay ninguna forma de invocar refresh desde la UI" | Matiz: `TransactionsScreen` y `CalendarTabHost` **ya revalidan al entrar** con un mecanismo ad-hoc sin TTL/single-flight — se reemplaza, no se duplica (§4.8) |
| 3 | Botones manuales sin destino declarado | Se rutean por `gate.run(manual = true)` o se retiran (§4.8) |
| 4 | iOS solo "conectar el gesto" | Las 16 `.refreshable` existentes se **migran** al contrato (§4.8) |
| 5 | Cooldown "se reinicia al recuperar conectividad", sin cómo | `resetCooldown()` cableado a `ConnectivityMonitor.isConnected` (§4.3 inv. 4) |
| 6 | `RefreshOutcome` sin definir | Definido en §4.3 (el espejo Swift lo necesita por nombre) |

Sin comparación de mercado nueva: el patrón (revalidar al entrar + gesto, sin
polling) es la convención dominante ya citada vía TanStack/`.refreshable`; el
TTL de 30 s sigue siendo apuesta declarada (§10). No se investigó cómo lo hace
Square/Toast/Fudo en sus apps POS — "no encontré" honesto, no bloquea.

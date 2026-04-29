# Spec: Reservas/Citas/Clases en Avoqado Android

**Status**: DRAFT — pending user approval
**Owner**: @amieva
**Date**: 2026-04-29
**Related**:
- `docs/research/square-deep-dive/square-feature-inventory.md` — qué tiene Square
- `docs/research/square-deep-dive/avoqado-web-reservations-map.md` — qué tiene el dashboard web
- `docs/research/square-deep-dive/avoqado-server-reservations-map.md` — qué tiene el server

---

## 0. TL;DR

Portear el sistema completo de reservas del web dashboard a Avoqado Android, optimizado para tablet+phone, **emulando el patrón "Modo" de Square** donde el tab Calendario aparece condicionalmente. Consolidamos en **5 fases entregables** a lo largo de ~9-12 sprints. Esta primera spec cubre **toda la arquitectura** y **Fase 1 (Visualización + acciones de turno)** en detalle implementable. Las fases 2–5 se especifican a alto nivel y se brainstormeará cada una al iniciarla.

## 1. Objetivos

1. **Paridad funcional con web dashboard** — staff y managers operan reservas/clases/waitlist desde tablet o teléfono sin abrir el dashboard.
2. **UX nativa, no port literal del web** — adoptar paradigmas de Android/Material3 + iOS-parity (Avoqado iOS) y patrones probados de Square POS Android.
3. **Conditional surfacing** — `venue.type === SERVICE` o feature flag activa la pestaña principal Calendario; opcionalmente el venue puede activarla manualmente desde "Más → Activar reservas".
4. **Cero duplicación de lógica de servidor** — reusar 100% los endpoints `/dashboard/venues/:venueId/reservations/*` y `/classSessions/*` desde el cliente Android (no abrir nuevos endpoints `/mobile/`).
5. **Robustez offline-aware** — operaciones de check-in/no-show/cancelación se encolan en `OfflineQueue` (igual que Orders) cuando hay pérdida de red.

## 2. Non-objetivos (para la primera entrega)

- NO implementar online booking embed/widget (es responsabilidad del web).
- NO portear "Square Assistant" / SMS confirmations bidireccionales (capa de comunicaciones server-side).
- NO crear nuevas migraciones de schema en server salvo que se requieran (ver §6 sobre `Reservation.kind` para Personal Events — pendiente).
- NO sustituir el calendario del web — ambos coexisten.

## 3. Arquitectura: el patrón "Modo"

Square POS opera en uno de tres modos por dispositivo (`reservas | tienda | Estándar`). Adoptamos un patrón análogo:

### 3.1 Decisión: ¿venue-level o device-level?

**Recomendación**: **device-level** (como Square), persistido en `SecureStorage` con key `KEY_VENUE_MODE`, sincronizable con server al login pero overrideable localmente. Razón: el mismo restaurante puede tener una tablet en "modo reservas" en hostess y otra en "modo POS" en barra; obligar el modo a nivel venue rompe ese caso real.

### 3.2 Tabs por modo (configuración propuesta)

| Modo | Tabs |
|---|---|
| **POS Estándar** (default) | Inicio · Pedidos · Cobrar · Más |
| **Reservas** | **Calendario** · Pedidos · Cobrar · Más |
| **Restaurante** (futuro) | Mesas · Pedidos · Cobrar · Más |

Implementación: `MainTab` enum existente se mantiene; agregamos `MainTab.Calendar`. La lista de tabs visible se computa en `MainTabHostViewModel.tabs: StateFlow<List<MainTab>>` a partir de `venueMode + venue.type + featureFlags`.

### 3.3 Activación condicional del modo Reservas

**Importante**: Las reservas son **independientes del `venue.type`**. Un venue retail (ej. un gym que vende suplementos Y reserva clases) puede activarlas igual que un venue service. NO hay auto-activación basada en tipo.

Lógica:
1. Si `venue.featureFlags.reservations === true` → modo Reservas disponible en el switcher.
2. Si `false` o ausente → el switcher sólo muestra Estándar (+ Restaurante futuro). Aparece el shortcut "Activar reservas" en Más.

UI para cambiar manualmente: **Más → tarjeta "Modo: Estándar ▾"** → bottom sheet con radios. **Cualquier staff loggeado puede cambiar el modo** (sin permission gating). Es una preferencia local del dispositivo, no destructiva.

### 3.4 Onboarding "Activar reservas" desde Más

Si `featureFlags.reservations !== true`, el grid de shortcuts en Más incluye:

> 🔓 **Activar reservas** — Permite a tu negocio recibir citas, manejar clases y administrar tu calendario desde Avoqado. **Gratis hoy.**

Tap → pantalla informativa + CTA "Activar reservas" → llama `PUT /venues/:id` con `featureFlags.reservations = true` → recarga `MainTabHostViewModel` → modo Reservas ya disponible en el switcher.

**Future-proof paywall**: el campo `featureFlags.reservations` permanece booleano, pero la pantalla de "Activar reservas" debe poder leer un flag remoto `reservations.requiresUpgrade` (default false hoy). Cuando lo activemos, el CTA cambia a "Suscribirse a Reservas" y abre flujo de billing. **Cero migration debt** — el cliente solo lee un campo más al render.

## 4. Fase 1 — Visualización + acciones de turno (implementable ahora)

### 4.1 Scope

Lo MÍNIMO que entrega valor en producción para staff de piso. NO incluye crear reservas (eso es Fase 2). Permite:

1. Ver tab Calendario condicionalmente.
2. Ver lista de reservas con tabs (Hoy, Pendientes, Confirmadas, No-show, Todas) + filtros (canal, búsqueda).
3. Ver calendario en vista Día y Semana.
4. Tap en reserva → detalle.
5. Acciones rápidas (con confirmación en bottom sheet):
   - **Confirmar** (PENDING → CONFIRMED)
   - **Check-in** (CONFIRMED → CHECKED_IN)
   - **Completar** (CHECKED_IN → COMPLETED)
   - **No-show** (CONFIRMED/PENDING → NO_SHOW)
   - **Cancelar** (PENDING/CONFIRMED → CANCELLED, con razón opcional)
   - **Reagendar** (cambia `startsAt`/`endsAt`, abre date+time picker)
6. Notificación push cuando llega una reserva nueva (canal `reservations`).
7. Banner offline si no hay red, queue de acciones tipo Orders.

### 4.2 Pantallas Fase 1

| # | Pantalla | Archivo | Notas |
|---|---|---|---|
| 1.1 | **Calendar Tab Host** | `reservations/presentation/CalendarTabHost.kt` | Top app bar (mes/año + ⋯), Day/Week toggle, body switcher |
| 1.2 | **Calendar Day View** | `reservations/presentation/calendar/CalendarDayView.kt` | Eje hora + week strip + grid + línea hora actual + reservaciones como bloques |
| 1.3 | **Calendar Week View** | `reservations/presentation/calendar/CalendarWeekView.kt` | 7 columnas con bloques de reserva (más densas, sin titles largos) |
| 1.4 | **Reservations List** | `reservations/presentation/list/ReservationsListScreen.kt` | Tab row (5 tabs) + filtros + DataList. Accesible desde ⋯ del calendario |
| 1.5 | **Reservation Detail Screen** | `reservations/presentation/detail/ReservationDetailScreen.kt` | Full-screen modal (estilo Square: X close left + acciones en bottom action bar). Same layout en tablet y phone. Incluye 6 botones de acciones |
| 1.6 | **Reschedule Sheet** | `reservations/presentation/detail/RescheduleSheet.kt` | Date+time picker + availability hint + Save pill |
| 1.7 | **Cancel Confirmation Sheet** | `reservations/presentation/detail/CancelReservationSheet.kt` | Razón opcional + Confirmar (red pill) |
| 1.8 | **Calendar Settings Sheet** | `reservations/presentation/calendar/CalendarSettingsSheet.kt` | Vista (Día/Semana/Lista) + status filters + color code |
| 1.9 | **Activar reservas (onboarding)** | `reservations/presentation/onboarding/ActivateReservationsScreen.kt` | Solo si feature off |

### 4.3 Componentes nuevos del design system

| Componente | Para qué | Reusa |
|---|---|---|
| `WeekStrip` | Strip D L M M J V S con número del día, día activo en círculo filled | `Surface` + `Row` |
| `CalendarDayGrid` | Grid de 24h con eje + slots con reservation blocks + current-time line | `Canvas` + `LazyColumn` |
| `CalendarWeekGrid` | Variante 7 columnas | mismo `Canvas` |
| `ReservationStatusBadge` | Pill chico con color por estado (PENDING ámbar, CONFIRMED azul, CHECKED_IN verde, etc.) | replicar `StatusBadge` iOS |
| `CurrentTimeIndicator` | Línea roja horizontal con timestamp en eje | nuevo `Composable` |
| `ReservationBlock` | Bloque clickable con cliente + servicio + estado + hora | nuevo |
| `EmptyStateBlock` | Empty state con ilustración + texto + CTA primary + secondary | nuevo (compartible) |
| `ActionSheetCenter` | Action sheet con full-width pills (cita/clase/evento) | extender `AvoqadoDialog` |
| `ModeSwitcherSheet` | Sheet de "Cambiar de modo" | nuevo |

### 4.4 ViewModels y data flow

```
ReservationsListViewModel (@HiltViewModel)
  ├─ uiState: StateFlow<ReservationsListUiState>
  ├─ Repository call: ReservationRepository.getReservations(filters)
  └─ Mutations: confirm/checkIn/complete/noShow/cancel/reschedule (con OfflineQueue)

CalendarViewModel (@HiltViewModel)
  ├─ view: StateFlow<CalendarView> (Day | Week)
  ├─ selectedDate: StateFlow<LocalDate>
  ├─ reservations: StateFlow<List<Reservation>> (auto-fetch on date+view change)
  └─ statusFilter: StateFlow<Set<ReservationStatus>>

ReservationDetailViewModel (@HiltViewModel, asistido por NavArg id)
  ├─ reservation: StateFlow<Reservation?>
  ├─ optimistic mutations con rollback
  └─ side-effects: snackbars + AvoqadoSuccessToast en éxito
```

### 4.5 Modelo de datos (Android)

```kotlin
@Serializable
data class Reservation(
  val id: String,
  val venueId: String,
  val confirmationCode: String,
  val cancelSecret: String,
  val status: ReservationStatus,
  val channel: ReservationChannel,
  val startsAt: Instant,        // UTC del server
  val endsAt: Instant,
  val duration: Int,             // minutos
  val customerId: String?,
  val customer: CustomerLite? = null,    // hydrated en list
  val guestName: String?,
  val guestPhone: String?,
  val guestEmail: String?,
  val partySize: Int,
  val tableId: String?,
  val table: TableLite? = null,
  val productId: String?,
  val product: ProductLite? = null,
  val classSessionId: String?,
  val classSession: ClassSessionLite? = null,
  val assignedStaffId: String?,
  val assignedStaff: StaffLite? = null,
  val depositAmount: BigDecimalString?,
  val depositStatus: DepositStatus?,
  val confirmedAt: Instant?,
  val checkedInAt: Instant?,
  val completedAt: Instant?,
  val cancelledAt: Instant?,
  val noShowAt: Instant?,
  val cancellationReason: String?,
  val specialRequests: String?,
  val internalNotes: String?,
  val tags: List<String>,
  val createdAt: Instant,
  val updatedAt: Instant
)

enum class ReservationStatus { PENDING, CONFIRMED, CHECKED_IN, COMPLETED, CANCELLED, NO_SHOW }
enum class ReservationChannel { DASHBOARD, WEB, PHONE, WHATSAPP, APP, WALK_IN, THIRD_PARTY }
enum class DepositStatus { PENDING, CARD_HOLD, PAID, REFUNDED, FORFEITED }
```

Mapping con timezone: leer `startsAt` UTC → convertir a `venueTimezone` con `VenueDateTimeFormatter` antes de display (regla establecida en v2.1.1).

### 4.6 API surface (cliente)

```kotlin
interface ReservationApiService {
  @GET("dashboard/venues/{venueId}/reservations")
  suspend fun list(
    @Path("venueId") venueId: String,
    @Query("page") page: Int,
    @Query("pageSize") pageSize: Int,
    @Query("status") status: String?,
    @Query("dateFrom") dateFrom: String?,
    @Query("dateTo") dateTo: String?,
    @Query("channel") channel: String?,
    @Query("search") search: String?,
  ): Response<PaginatedReservations>

  @GET("dashboard/venues/{venueId}/reservations/calendar")
  suspend fun calendar(
    @Path("venueId") venueId: String,
    @Query("dateFrom") from: String,   // YYYY-MM-DD
    @Query("dateTo") to: String,
    @Query("groupBy") groupBy: String?,// "table" | "staff" | null
  ): Response<List<Reservation>>

  @GET("dashboard/venues/{venueId}/reservations/{id}")
  suspend fun get(@Path("venueId") v: String, @Path("id") id: String): Response<Reservation>

  @POST("dashboard/venues/{venueId}/reservations/{id}/confirm")
  suspend fun confirm(@Path("venueId") v: String, @Path("id") id: String): Response<Reservation>

  @POST("dashboard/venues/{venueId}/reservations/{id}/check-in")
  suspend fun checkIn(@Path("venueId") v: String, @Path("id") id: String): Response<Reservation>

  @POST("dashboard/venues/{venueId}/reservations/{id}/complete")
  suspend fun complete(@Path("venueId") v: String, @Path("id") id: String): Response<Reservation>

  @POST("dashboard/venues/{venueId}/reservations/{id}/no-show")
  suspend fun noShow(@Path("venueId") v: String, @Path("id") id: String): Response<Reservation>

  @DELETE("dashboard/venues/{venueId}/reservations/{id}")
  suspend fun cancel(
    @Path("venueId") v: String, @Path("id") id: String,
    @Body body: CancelReservationBody,
  ): Response<Unit>

  @POST("dashboard/venues/{venueId}/reservations/{id}/reschedule")
  suspend fun reschedule(
    @Path("venueId") v: String, @Path("id") id: String,
    @Body body: RescheduleBody,
  ): Response<Reservation>
}
```

Auth: Bearer token via `AuthInterceptor` existente. **No requiere nuevos endpoints `/mobile/`** — el dashboard ya usa este shape autenticado.

### 4.7 Permisos

El server gateway en `reservation.routes.ts` aplica `checkPermission('reservations:read|create|update|cancel')`. El cliente Android **debe respetar el mismo modelo**:

- Decode `permissions[]` del JWT al login.
- `ReservationsCapability` data class con flags `canRead/canCreate/canUpdate/canCancel`.
- Esconder/deshabilitar UI según capability:
  - Sin `canRead` → tab Calendario no aparece (incluso en modo Reservas).
  - Sin `canUpdate` → botones de acciones grises con tooltip.
  - Sin `canCancel` → botón Cancelar oculto.

### 4.8 Push notifications

Nuevo canal Firebase: `reservations`.

Payload server: `{ type: "reservation.created" | "reservation.cancelled" | "reservation.checked_in", reservationId, venueId, summary }`.

Cliente:
- `PushNotificationManager.handleReservationPush(payload)` route a `Notification` Compose:
  - Heads-up notification con título "Nueva reserva: María López — 7:30 PM" y action "Ver".
- Tap → deep-link a `reservations/detail/{id}`.

### 4.9 Offline queue

Las acciones de transición de estado (confirm/check-in/complete/no-show/cancel) se persisten en `OfflineActionQueue` (igual a Orders). Al recuperar red:
- Replay en orden FIFO.
- Si hay 409 (estado avanzó server-side), descartar la acción y refrescar.
- Si hay 401 → renovar token y reintentar.
- Mostrar `ConnectivityBanner` + contador de acciones pendientes.

### 4.10 Adaptive layout (Square-style, single-column centered)

**Decisión**: NO usamos split view en Calendar (rompimos paridad con Transactions/Orders aquí intencionalmente, porque el calendario funciona mejor con todo el ancho disponible).

Tablet (sw ≥ 600dp):
- **Calendar Day view**: single-column centered con `max-width = 880dp` y `Modifier.padding(horizontal)` que crece con el ancho. Margen lateral grande en pantallas anchas.
- **Calendar Week view**: full-width (las 7 columnas necesitan todo el espacio).
- **Reservations List**: single-column centered con `max-width = 720dp`.
- **Reservation Detail**: **full-screen modal** que cubre todo (estilo Square `Crear cita`). X close (top-left circular) + título centrado + acciones en bottom action bar.

Phone:
- Calendar Day y Week: full screen (sin max-width).
- Reservations List: full screen.
- Reservation Detail: full-screen Activity/Composable destination (no bottom sheet — consistencia con tablet).

Razón del cambio vs propuesta inicial: Square POS Android lo hace así y se ve cleaner. Además, el detail screen necesita espacio para los 6 botones de acciones + customer info + special requests + deposit info — un bottom sheet phone se siente apretado.

### 4.11 Internationalization

Reusar copy del dashboard web (`public/locales/es/reservations.json`) — port directo a `app/src/main/res/values-es/strings_reservations.xml`. Mantener todos los strings en español (idioma actual default de la app).

## 5. Fases 2-5 (alto nivel, se brainstormearán al inicio de cada una)

### Fase 2 — CRUD reservas + waitlist
- `CreateReservationFlow` (5 pasos en sheet stack: cliente / servicio / fecha-hora / detalles / confirmar)
- `EditReservationSheet`
- `WaitlistScreen` con add/promote/remove + filter chips
- `WalkInQuickCreate` (botón flotante FAB en Calendar — 3-tap walk-in)
- ~6-8 pantallas, ~1-2 sprints

### Fase 3 — Clases + bulk recurring
- `CreateClassSessionSheet` (single + bulk via `RecurrenceRuleEditor`)
- `EditClassSessionSheet`
- `ClassAttendeesSheet` (lista + add/remove)
- `RecurrenceRuleEditor` reusable (RRULE-lite)
- ~5-7 pantallas, ~1-2 sprints

### Fase 4 — Settings completo
- `ReservationSettingsHost` con secciones (scheduling, deposits, cancellation, waitlist, reminders, public booking)
- `OperatingHoursEditor` (week schedule con max 3 ranges/día)
- `EditAvailabilityScreen` (one-time override + edit recurring)
- ~8-10 pantallas, ~2 sprints

### Fase 5 — Personal Events (NUEVO en server + cliente)
- Schema migration: `Reservation.kind: APPOINTMENT | CLASS | PERSONAL_EVENT` (enum)
- Server endpoints: ya existen, agregar `kind` field
- UI: `CreatePersonalEventSheet` (name, all-day, date, time, duration, block-as-busy, staff, notes)
- ~2-3 pantallas + schema + 1 sprint

## 6. Riesgos y trade-offs

| Riesgo | Mitigación |
|---|---|
| Endpoints `/dashboard/` requieren cookie de sesión | Confirmado que aceptan Bearer token (mismo middleware `requireAuth`). Smoke test en spike de día 1 |
| Timezone bugs (ya nos pasó en v2.1.0→v2.1.1) | TODO el código fecha pasa por `VenueDateTimeFormatter` — checked-in via lint rule |
| Performance del Day grid con 50+ reservaciones | Usar `LazyColumn` + key estable + `remember` para offset calc |
| Permission gating diverge entre web y Android | Wrapper compartido `withReservationPermission(action: String) { ... }` que respeta `permissions[]` del JWT |
| Offline queue con conflictos de estado | Estrategia "last-write-wins server" — al replay, si server retorna 409, mostrar toast "Esta reserva cambió, refrescando..." y silently refresh |
| Bottom-nav cambia entre modos puede confundir staff | Pequeño tooltip "Modo Reservas activo" la primera vez que entran al tab Calendar; persistente flag `coachMarkSeen` |

## 7. Dependencias / pre-requisitos

1. ✅ Server endpoints existen y funcionan (verificado en `avoqado-server-reservations-map.md`)
2. ✅ Permisos `reservations:*` ya implementados en server
3. ✅ JWT incluye `permissions[]` (verificar en spike)
4. ✅ Push channel `reservations` — backend probablemente ya envía pushes; verificar
5. ⏳ Server agregar `Reservation.kind` field (Fase 5 only)
6. ⏳ Server agregar mobile-friendly response shapes opcionales (`?include=customer,staff,table` para evitar N+1) — nice-to-have

## 8. Plan de implementación (resumen)

| Fase | Sprints | Pantallas | Issues estimadas | Releases |
|---|---|---|---|---|
| Fase 1 — Visualización + acciones | 2-3 | ~9-11 | ~25 | v2.2.0 |
| Fase 2 — CRUD + waitlist | 1-2 | ~6-8 | ~18 | v2.3.0 |
| Fase 3 — Clases + recurring | 1-2 | ~5-7 | ~15 | v2.4.0 |
| Fase 4 — Settings | 2 | ~8-10 | ~20 | v2.5.0 |
| Fase 5 — Personal Events | 1 | ~2-3 + schema | ~6 | v2.6.0 |

## 9. Open questions — RESUELTAS

1. ✅ **Tabs por modo**: en modo Reservas el bottom nav es **Calendario · Pedidos · Cobrar · Más**. Pedidos sigue visible para flujos retail concurrentes (un gym vende suplementos y reserva clases). Cobrar (`Take payment` de Square) también — el staff puede cobrar una reserva que terminó.
2. ✅ **Cambiar modo**: sin permission gating. Cualquier staff loggeado.
3. ✅ **Activar reservas**: gratis ahoy. Arquitectura ya considera flag remoto `reservations.requiresUpgrade` para futuro paywall sin re-trabajo.
4. ✅ **Empty state hoy sin reservas**: calendario vacío con `+` en header. **No** mostramos CTA "walk-in" en Android (sería más relevante en dashboard web cuando un host está en computadora).
5. ⏳ **Tablet split**: pendiente decisión final — propuesta default abajo.

## 9.1 Decisiones finales

A) ✅ **Tablet layout = single-column centered** (estilo Square POS Android). Calendario con max-width 880dp, margen lateral creciente. Sin split view.

B) ✅ **Detalle de reserva = full-screen modal** (estilo Square Crear cita: X close left + acciones en bottom action bar). Mismo layout en tablet y phone para consistencia.

C) ✅ **Walk-in en Fase 2 = reusar flujo de "Crear cita" normal** con tiempo "ahora" pre-llenado (estilo Square). NO crear FAB walk-in dedicado.

## 10. Aprobación

Espero feedback explícito en:
- ¿Empezamos por **Fase 1** como spec implementable o quieres incluir más fases en la primera entrega?
- ¿Algún cambio en el patrón "Modo" del §3?
- ¿Las 9 pantallas de Fase 1 cubren el flujo de operación que tienes en mente?

Una vez aprobado este spec, **invoco la skill `writing-plans`** para generar el plan paso a paso (issues con acceptance criteria, orden de implementación, test plan, checkpoints de revisión).

# Class Sessions on Android — Design Spec

**Status:** DRAFT — pending review
**Author:** Jose Antonio Amieva (with Claude Code)
**Date:** 2026-05-05
**Repos affected:** `avoqado-android` (100%) — server-side fully implemented
**Estimated effort:** 6–7 days across 4 phases

---

## 1. Goal

Bring class-session functionality to the Android tablet POS, achieving feature parity with the web dashboard's `CreateClassSessionDialog` / `EditClassSessionDialog` / calendar rendering. A "class session" is a group event (yoga class, pilates session, cooking workshop) bookable by multiple attendees against a single time slot, distinct from individual reservations.

The current Android calendar shows a stub action — `"Crear clase — disponible en Fase 3"` snackbar (`CalendarTabHost.kt:209`). This spec replaces the stub with a full implementation.

---

## 2. Context

### 2.1 What exists today (server) — fully implemented

| Layer | Status | Location |
|---|---|---|
| `ClassSession` Prisma model | ✅ | `prisma/schema.prisma:7874` |
| `Product.type = CLASS` + `maxParticipants` + `layoutConfig` | ✅ | `prisma/schema.prisma:1106-1107` |
| `Reservation.classSessionId` (booking ↔ session link) | ✅ | Schema + service code |
| `ClassSessionStatus` enum (`SCHEDULED`, `CANCELLED`, `COMPLETED`) | ✅ | |
| 8 REST endpoints mounted at `/api/v1/dashboard/venues/:venueId/class-sessions` (path matches existing reservations endpoint — see §3.7) | ✅ | `routes/dashboard/classSession.routes.ts` + service |
| Service computes `enrolled` and `available` on every read | ✅ | (no manual count needed in client) |
| `addAttendee` accepts both `customerId` AND guest fields | ✅ | `schemas/dashboard/classSession.schema.ts:55-63` — server validates `customerId` belongs to venue |
| Bulk recurring create via `/bulk` endpoint | ✅ | Accepts `weekdays: number[]` + `(occurrences | endDate)` |

### 2.2 What exists today (web dashboard) — full reference

| File | Lines | Role |
|---|---|---|
| `services/classSession.service.ts` | 138 | DTOs and 8 API methods — clone 1:1 to Kotlin |
| `pages/Reservations/components/CreateClassSessionDialog.tsx` | 632 | Single + recurring creation UI; auto-calc endTime; auto-fill capacity from layoutConfig; empty-state CTA opens product creation |
| `pages/Reservations/components/EditClassSessionDialog.tsx` | 476 | Edit + attendees roster + remove attendee |
| `pages/Reservations/ReservationCalendar.tsx:567` | inline | Calendar rendering: violet block, capacity badge, exclude reservations with `classSessionId` from regular rendering |

**Key web UX patterns to replicate:**
1. Empty state: if no `Product.type=CLASS` exists, show CTA "Crear clase" that opens `ServiceFormDialog` inline. **Android equivalent: extend existing `CreateProductView` (option A from brainstorm).**
2. Class product picker: dropdown with "+ Añadir nueva clase" pinned at top + list of existing CLASS products with name/duration/price.
3. Auto-calc `endTime` from `startTime + product.duration`.
4. Auto-fill `capacity` from `product.layoutConfig.spots[].enabled` count if layout exists, else from `product.maxParticipants`.
5. Recurrence: weekday multi-select chips (D L M X J V S, Sun-first) + end mode "Después de N sesiones" OR "En una fecha".
6. Calendar render: violet block (`bg-violet-500/20` web equivalent), capacity badge `enrolled/capacity`, "Lleno" pill when full or "X cupos" when ≤3 left.
7. Reservations linked to a class session are EXCLUDED from regular reservation rendering (they live inside the class block).

### 2.3 What exists today (Android) — partial

| Layer | Status |
|---|---|
| `Reservation.classSessionId: String?` field in DTO | ✅ `data/model/Reservation.kt:27` |
| `CreateReservationRequest.classSessionId: String?` | ✅ `data/model/CreateReservationRequest.kt:16` |
| `ClassSession.kt` data model | ❌ Not present |
| `ClassSessionApi.kt` Retrofit interface | ❌ Not present |
| `ClassSessionRepository.kt` | ❌ Not present |
| Class session UI (create/edit/render/attendees) | ❌ Stub snackbar only |
| `Product.kt` has `duration: Int?` | ✅ `pos/data/model/Product.kt:29` |
| `Product.kt` has `maxParticipants` and `layoutConfig` | ❌ **Missing — must add in Phase A** |
| `CreateProductRequest.kt` carries `duration` and `maxParticipants` | ❌ **Missing — must add in Phase B** |
| `CreateProductView.kt` exists but hardcodes `type = "FOOD_AND_BEV"` | ⚠️ `pos/presentation/product/CreateProductView.kt:123` |

### 2.4 Adjacent systems (NOT to break)

| System | Why isolated |
|---|---|
| `ReservationRepository` / individual reservations | Class sessions get their own repository. Reservation flow only changes in calendar render (filter by `classSessionId == null`). |
| `CreateReservationScreen` summary form | Same UX pattern reused for `CreateClassSessionScreen` to maintain consistency. |
| Drag-to-reschedule in Day grid | Not in MVP for class sessions. Preserved for individual reservations. |
| Existing customer pickers (`CustomerSection`) | Reused as-is for "Cliente existente" tab in Add Attendee. |

---

## 3. Architectural decisions

### 3.1 Q1 — Customer-Venue scope (resolved)

**Decision:** Customers are **per-venue** (not global). Confirmed in `prisma/schema.prisma:3984` (`Customer.venueId String` required, comment: "Unique per venue, not globally"). The existing `CustomersRepository.fetchCustomers()` already returns only the active venue's customers — no change needed.

`Consumer` (global identity for the public-facing app) is referenced via `Customer.consumerId` but is invisible to staff workflows. Out of scope.

### 3.2 Q2 — Recurrence pattern (resolved)

**Decision:** **Web pattern** — weekday multi-select + end mode (occurrences OR endDate).

**Rationale:**
- Server `/bulk` endpoint already accepts this shape.
- More flexible for real class schedules (Mon/Wed/Fri yoga, Tue/Thu/Sat spinning) — Square's "every N units" model can't express MWF.
- Maintains parity with web dashboard, so user mental model is unified across platforms.

**Rejected:** Square's "cada N unidades" model — would require client→server conversion AND limits expressivity.

### 3.3 Q3 — Empty state when no CLASS products exist (resolved)

**Decision:** **Option A** — extend existing `CreateProductView.kt` with a `productType: String = "FOOD_AND_BEV"` parameter + conditional fields when `type = "CLASS"` (`duration`, `maxParticipants`). The same component then opens as a sheet from the create-class flow's empty state.

**Trade-off accepted:** A small refactor to `CreateProductView` (add param + conditional rendering) vs. building a duplicate `CreateClassProductSheet`. Refactor is the right call — also unlocks future SERVICE / APPOINTMENTS_SERVICE creation from mobile.

### 3.4 Q4 — Attendee management (resolved)

**Decision:** Add Attendee sheet has **two tabs**:
1. **Cliente existente** — reuses `CustomerSection` (already built for Create Reservation) — selecting a customer fires `addAttendee({ customerId, guestName: customer.fullName, guestPhone: customer.phone, guestEmail: customer.email })` (server requires `guestName` always as a snapshot).
2. **Invitado** — three fields (name, optional phone, optional email) — fires `addAttendee({ guestName, guestPhone, guestEmail })`.

Server (`schemas/dashboard/classSession.schema.ts:55`) accepts both shapes. Web doesn't currently use `customerId` (deuda de UX); Android takes advantage from day 1.

### 3.5 Q5 — Calendar rendering for class sessions

**Decision:**
- Class sessions render as a **distinct visual block** in `CalendarDayGrid` and `CalendarWeekGrid`:
  - Background tint: violet/purple, alpha matched to existing reservation block (`0xE6` for visibility)
  - Border: 1dp solid violet, slightly darker
  - Top-right badge: `"{enrolled}/{capacity}"` with `Users` icon
  - When `enrolled == capacity`: small "Lleno" pill in same block
  - When `capacity - enrolled <= 3`: subtle "X cupos" hint
- **Reservations with `classSessionId != null` are EXCLUDED from individual reservation rendering** (same behavior as web `ReservationCalendar.tsx:500`). They're "inside" the class block.
- Tap a class block → opens `ClassSessionDetailSheet`.

### 3.6 Q6 — Edit & cancel session

**Decision (MVP):**
- **Edit**: ONLY `capacity`, `assignedStaffId`, and `internalNotes` are editable in MVP. Start/end times are **read-only** in the detail sheet. Time edits, drag-to-reschedule, and full reschedule-with-attendees flow are deferred to Phase 2 (delicate UX: when do we notify? do attendees auto-rebook? cancel-and-recreate?).
- **Cancel**: button on detail sheet → confirmation dialog → POST `/cancel`. Server cancels all child attendee reservations automatically. UI shows "Sesión cancelada" toast and refreshes calendar.

**Deferred to Phase 2:** Edit start/end times, drag-to-reschedule, reschedule-with-attendees notification flow.

---

### 3.7 Q7 — API URL convention (documented exception)

**Decision:** Class-session endpoints use `/dashboard/venues/:venueId/class-sessions/...` paths, mirroring `ReservationApi.kt:38`'s pattern. Both reservations and class-sessions are **explicitly exempt** from CLAUDE.md's "use `/mobile/` only" rule because the server (`mobile.routes.ts`) does not register either controller — they live exclusively under `/dashboard/`. If the server adds a `/mobile/class-sessions` mount in the future, both Android APIs migrate together; until then, this is the documented and consistent approach.

---

### 3.8 Q8 — Offline queue (deferred from MVP)

**Decision:** **No offline queue for class-session mutations in MVP.** Reads (list calendar, get one) work via standard HTTP (cached by OkHttp); mutations (create, update, cancel, addAttendee, removeAttendee) require online. If offline, the API call returns a failure and the UI shows a snackbar ("Sin conexión, intenta de nuevo").

**Why:** `ReservationRepository`'s offline queue is built on `PendingReservationActionDao` + `PendingReservationActionEntity` + `ReservationActionsRetrier` (Room DB + retry worker registered in `AvoqadoApp.kt`). Cloning that entire stack for class sessions adds ~0.5 day of plumbing for a marginal UX win — class management happens almost exclusively from staff inside the venue with WiFi available.

**Reconsider in Phase 2 if:**
- A real venue reports lost class-session creates due to flaky connectivity, or
- We unify the offline queue into a generic `PendingActionDao` that both reservations and class-sessions share.

---

### 3.9 Q9 — Permissions / role gating

**Decision:** Match server's permission gate at the UI level so unauthorized staff don't see broken affordances.

**Server gates each endpoint with:**

| Endpoint | Required permission |
|---|---|
| `GET /class-sessions` (list, getOne) | `reservations:read` |
| `POST /class-sessions` (create, bulk) | `reservations:create` |
| `PATCH /class-sessions/:id` (update) | `reservations:update` |
| `POST /class-sessions/:id/cancel` | `reservations:cancel` |
| `POST /:id/attendees` (add) | `reservations:create` |
| `DELETE /:id/attendees/:resId` (remove) | `reservations:cancel` |

**Client behavior:**
- Calendar always loads class sessions if the user can see reservations (`reservations:read` is the gate for the whole calendar).
- "Crear clase" action sheet item is **hidden** when user lacks `reservations:create`.
- Detail sheet's "Editar" button is **hidden** when user lacks `reservations:update`.
- Detail sheet's "Cancelar sesión" button is **hidden** when user lacks `reservations:cancel`.
- "+ Agregar asistente" button hidden without `reservations:create`.
- Swipe-to-delete attendee disabled without `reservations:cancel`.

Permissions are read from the existing auth-state holder (verify exact API in Phase B; mirror what `CalendarTabHost` already does to gate the `+` button on creating individual reservations — same permission name applies).

**Defensive defense:** if the staff role changes mid-session and they get 403 on a hidden-by-UI mutation, show "No tienes permiso para esta acción" toast (translated server error).

---

### 3.10 Q10 — Conflict / overlap detection

**Decision:** Two distinct conflict modes:

**A. Single create conflict (overlap with existing booking at same hour):**
- Server: `POST /class-sessions` returns `409 Conflict` with body `{ code: "TIME_CONFLICT", message: "...", conflictingResourceId?: "..." }` when start/end overlaps an active class session OR a reservation in the same staff/table slot.
- Client: surface inline error in the create screen — banner above the action button: "Conflicto: ya hay una clase/reserva a esa hora". Don't auto-dismiss the form (let user adjust time and retry). Offer two CTAs:
  - "Ver el conflicto" → opens calendar at the conflict time (UX nicety, optional in MVP)
  - "Cambiar hora" → focuses Date/Hora row in summary form

**B. Bulk create partial conflicts (some recurring dates collide):**
- Server: `POST /class-sessions/bulk` already returns `{ count, skipped, created[] }` and never errors out — partial success.
- Client: success toast `"X clases agendadas (Y omitidas por conflicto)"` when `skipped > 0`. Optionally expandable list of skipped dates (deferred to Phase 2).

**C. Add attendee race condition (capacity reached between read and POST):**
- Server: `POST /:id/attendees` returns `422` with `{ code: "CAPACITY_FULL" }` when `enrolled >= capacity` at write time.
- Client: inline error in Add Attendee sheet — "Sesión llena (12/12)" — refresh capacity counter from server. The `enrolled/capacity` badge in the calendar refreshes via the change-event flow (§3.11).

---

### 3.11 Q11 — Cache & state invalidation

**Decision:** Following the existing `ReservationRepository.changes` flow pattern (a `MutableSharedFlow<Unit>` that emits after every successful mutation), `ClassSessionRepository` exposes a parallel `changes: SharedFlow<Unit>`. Calendar and detail viewmodels subscribe and refetch.

**Concrete invalidation paths:**

| User action | Repository mutation | Emits to | Effect |
|---|---|---|---|
| Create class session | `repository.create()` success | `classSession.changes` + `reservationRepository.changes` | Calendar refetches both lists; create screen closes |
| Bulk create | `repository.createBulk()` success | same as above | Calendar refetches |
| Cancel session | `repository.cancel()` success | both flows | Calendar refetches; detail sheet auto-closes; toast "Sesión cancelada" |
| Add attendee | `repository.addAttendee()` success | `classSession.changes` only (no Reservation refetch needed — server returns the new attendee) | Detail sheet's Asistentes tab refreshes; capacity counter `enrolled/capacity` increments |
| Remove attendee | `repository.removeAttendee()` success | `classSession.changes` only | Asistentes tab removes row; capacity decrements |
| Edit (capacity/staff/notes) | `repository.update()` success | `classSession.changes` | Detail sheet refetches; calendar refetches if capacity changed (badge re-renders) |

**Why both flows for create/cancel:** A class session being created/cancelled affects the same calendar grid that renders reservations. If we only emit `classSession.changes`, the reservation list stays stale (e.g., reservations with `classSessionId` linked to the cancelled session would still render until next refresh).

**Calendar VM subscription pattern:**
```kotlin
init {
    viewModelScope.launch {
        merge(reservationRepository.changes, classSessionRepository.changes)
            .collect { fetch() }
    }
}
```

---

### 3.12 Q12 — EditClassSessionScreen design

**Decision:** Mirror `CreateClassSessionScreen`'s summary-form pattern but with a **subset of editable fields** and **destructive action zone** at the bottom.

**Layout:**

```
┌──────────────────────────────────────────────┐
│  X    Editar clase           Guardar        │
├──────────────────────────────────────────────┤
│  CLASE  (read-only)                          │
│  ┌────────────────────────────────────────┐ │
│  │ 🎯  Yoga Vinyasa                       │ │  ← non-tappable, no chevron
│  │     60 min                             │ │
│  └────────────────────────────────────────┘ │
│                                              │
│  FECHA Y HORA  (read-only — Phase 2)         │
│  ┌────────────────────────────────────────┐ │
│  │ 🕐  Mié 7 may · 18:00–19:00            │ │  ← non-tappable
│  └────────────────────────────────────────┘ │
│                                              │
│  CAPACIDAD                                   │
│  ┌────────────────────────────────────────┐ │
│  │ 👥  12 plazas                  >      │ │  ← editable, opens stepper sheet
│  └────────────────────────────────────────┘ │
│                                              │
│  STAFF                                       │
│  ┌────────────────────────────────────────┐ │
│  │ 👤  María González             >      │ │  ← editable, opens staff picker
│  └────────────────────────────────────────┘ │
│                                              │
│  NOTAS INTERNAS                              │
│  [textarea — editable inline]                │
│                                              │
│  ────────────────── Zona destructiva ──────  │
│                                              │
│  [ Cancelar sesión ]  ← red text, opens     │
│                          confirmation       │
└──────────────────────────────────────────────┘
```

**Behavior:**
- "Guardar" pill enabled only when `draft != original` (dirty flag).
- "Cancelar sesión" → confirmation dialog (per §3.6 cancel flow).
- Editable rows reuse the same picker sheets from `CreateClassSessionScreen` (CapacitySection, StaffSection).
- Read-only rows have visually distinct styling (no chevron, slightly muted).

**Capacity edit edge case:** If user lowers capacity below current `enrolled`, server returns `422 { code: "CAPACITY_BELOW_ENROLLED" }`. Client shows inline error: "No puedes bajar la capacidad por debajo de los N asistentes actuales".

---

### 3.13 Q13 — DST / timezone edge case (recurring)

**Decision:** Client always sends `startTime` and `endTime` as **local HH:mm strings** (e.g., `"18:00"`, `"19:00"`) and `startDate` / `endDate` as **local YYYY-MM-DD strings** to `/bulk`. Server is responsible for expanding the recurrence rule using the **venue's timezone** and producing UTC `startsAt` / `endsAt` per occurrence.

**Why this matters:**
- México's DST transitions (last Sunday in October, first Sunday in April) shift wall-clock time by ±1 hour.
- A recurring class scheduled "Mondays 18:00 for 8 weeks" must remain at **18:00 venue local** every week, even if a DST transition happens mid-series.
- Server's `/bulk` implementation already handles this correctly (uses `Venue.timezone` field + Luxon-equivalent date arithmetic). Verified in service code.

**Client contract:**
- Never convert times to UTC client-side for recurrence input — that double-converts when server expands.
- For `single` create (non-recurring), client converts `LocalDate + LocalTime` to UTC `Instant` using `ZoneId.of(venueTimezone)` (same pattern as `CreateReservationDraft.toRequest()`). Single create has no DST risk because it's a single point in time.

**Test coverage required:**
- E2E with mock server: bulk create spanning a DST boundary date. Verify all generated sessions show `18:00 venue local` even on the post-DST week.
- Manual smoke (deferrable until México next DST in October 2026): create recurring class at 6 PM that spans Oct 25 → 26 boundary; confirm both sessions render at 18:00 in calendar.

**Risk if mishandled:** All sessions after DST shift by 1 hour, breaking customer expectations + venue scheduling. Server already gets this right per code review; client just needs to NOT preempt the conversion.

---

## 4. Phased plan

### Phase A — Data layer (1 day)

**Goal:** Type-safe API client + repository, no UI.

**Deliverables (data layer for class sessions):**
- `data/model/ClassSession.kt` — Kotlin data class mirroring web's TypeScript interface
- `data/model/CreateClassSessionRequest.kt`
- `data/model/CreateClassSessionBulkRequest.kt`
- `data/model/UpdateClassSessionRequest.kt`
- `data/model/AddAttendeeRequest.kt`
- `data/model/ClassSessionAttendee.kt`
- `data/model/ClassSessionStatus.kt` enum (`SCHEDULED`, `CANCELLED`, `COMPLETED`)
- `data/model/BulkCreateResult.kt` — `{ count: Int, skipped: Int, created: List<{ id, startsAt, endsAt }> }`
- `data/ClassSessionApi.kt` — Retrofit interface with all 8 methods (no offline queue — see §3.8)
- `data/ClassSessionRepository.kt` — `@Singleton`, mirrors `ReservationRepository`'s public surface but **without the offline-queue infrastructure** (deferred per §3.8)
- Hilt binding: `ClassSessionRepository` exposed via constructor injection

**Deliverables (Product DTO extension — required for class flows):**
- Extend `pos/data/model/Product.kt`:
  ```kotlin
  data class Product(
      // ... existing fields ...
      val duration: Int? = null,                  // already present
      val maxParticipants: Int? = null,           // ⭐ NEW — needed to auto-fill capacity
      val layoutConfig: JsonElement? = null,      // ⭐ NEW — for spots-based capacity (kotlinx.serialization)
  )
  ```
  Server returns these fields when `type = CLASS`. Adding them is purely additive (nullable defaults preserve all existing call sites).
- Extend `pos/data/model/CreateProductRequest.kt`:
  ```kotlin
  data class CreateProductRequest(
      // ... existing fields ...
      val type: String,                       // already there but hardcoded — make caller pass it
      val duration: Int? = null,              // ⭐ NEW — for SERVICE / APPOINTMENTS_SERVICE / CLASS
      val maxParticipants: Int? = null,       // ⭐ NEW — only set for CLASS
  )
  ```
  Server-side `productMobileController.create` (verify path) must accept these — confirm in Phase A before merging. If server's `/mobile/products POST` doesn't accept them yet, escalate to dashboard endpoint or add field passthrough.

**Tests for the DTO extension:**
- `Product` deserialization round-trip with `maxParticipants`, `layoutConfig` populated
- Backward-compat: `Product` deserialization with both fields missing (existing FOOD_AND_BEV products) → no crash
- `CreateProductRequest` serialization with FOOD_AND_BEV type (no class fields) — same JSON shape as today

**Endpoints to wire (URL pattern):**

The existing `ReservationApi.kt:38` builds URLs as `${baseUrlProvider()}/dashboard/venues/$venueId/reservations` — the `apiBaseUrl` injected provider already includes the `/api/v1` prefix, so paths must be relative starting with `/dashboard/`. Class sessions follow the same convention. **Do NOT prefix with `/api/v1/` again — that duplicates the segment and breaks routing.**

```kotlin
// Inside ClassSessionApi.kt — pattern mirrors ReservationApi.kt
private fun base(): String? {
    val v = secureStorage.venueId ?: return null
    return "${baseUrlProvider()}/dashboard/venues/$v/class-sessions"
}

// list:        GET    {base}?dateFrom=&dateTo=&productId=&status=
// getOne:      GET    {base}/{sessionId}
// create:      POST   {base}
// createBulk:  POST   {base}/bulk
// update:      PATCH  {base}/{sessionId}
// cancel:      POST   {base}/{sessionId}/cancel
// addAttendee: POST   {base}/{sessionId}/attendees
// removeAttendee: DELETE {base}/{sessionId}/attendees/{reservationId}
```

**Documented exception to CLAUDE.md "use /mobile/ routes" rule:** Server's `mobile.routes.ts` does NOT register any reservation/class-session controller. Both live exclusively under `/dashboard/`. The existing `ReservationApi.kt` is already an explicit exception; class-sessions follows the same pattern. If/when server adds a `/mobile/class-sessions` mount, both APIs migrate together.

**Tests:**
- DTO serialization round-trip (mock JSON from web's response shape)
- Repository → API mock with success / 4xx / 5xx scenarios
- Offline-queue: mutation while offline → enqueued, replayed on reconnect

**Risk:** Low. Pure data layer, no UI surface area.

---

### Phase B — Single class session creation (1.5 days)

**Goal:** Tapping "Crear clase" in `CalendarTabHost` action sheet opens a working create flow.

**Deliverables:**
- `presentation/classSessions/CreateClassSessionScreen.kt` — main screen
- `presentation/classSessions/CreateClassSessionViewModel.kt` — `@HiltViewModel`
- `presentation/classSessions/sections/ClassProductSection.kt` — picker for `Product.type=CLASS`
- `presentation/classSessions/sections/DateTimeSection.kt` — date + start time + auto-calc end time
- `presentation/classSessions/sections/CapacitySection.kt` — stepper, default from product
- `presentation/classSessions/sections/StaffSection.kt` — staff picker (optional)
- `presentation/classSessions/sections/NotesSection.kt` — internal notes textarea

**Layout pattern:**
Same summary-form-with-bottom-sheet pattern as `CreateReservationScreen` (refactored 2026-04-30). Five tappable rows in main screen, each opens a `ModalBottomSheet`:

```
┌──────────────────────────────────────────────┐
│  X    Crear clase             Crear         │
├──────────────────────────────────────────────┤
│  CLASE                                       │
│  ┌────────────────────────────────────────┐ │
│  │ 🎯  Yoga Vinyasa             >        │ │
│  │     60 min                             │ │
│  └────────────────────────────────────────┘ │
│                                              │
│  FECHA Y HORA                                │
│  ┌────────────────────────────────────────┐ │
│  │ 🕐  Mié 7 may · 18:00–19:00     >    │ │
│  └────────────────────────────────────────┘ │
│                                              │
│  CAPACIDAD                                   │
│  ┌────────────────────────────────────────┐ │
│  │ 👥  12 plazas                  >      │ │
│  └────────────────────────────────────────┘ │
│                                              │
│  STAFF (opcional)                            │
│  ┌────────────────────────────────────────┐ │
│  │ 👤  Cualquiera                 >      │ │
│  └────────────────────────────────────────┘ │
│                                              │
│  REPETICIÓN (opcional)                       │
│  ┌────────────────────────────────────────┐ │
│  │ 🔁  No se repite               >      │ │
│  └────────────────────────────────────────┘ │
│                                              │
│  NOTAS INTERNAS (opcional)                   │
│  [textarea]                                  │
└──────────────────────────────────────────────┘
```

**Auto-calc / auto-fill rules** (matching web):
- When user picks a class product:
  - If `product.duration` set → auto-fill `endTime = startTime + duration` (read-only display, recalculates if start time changes)
  - If `product.layoutConfig.spots` exists → `capacity = spots.count { it.enabled }`
  - Else if `product.maxParticipants` set → `capacity = maxParticipants`
  - Else → `capacity = 10` (sensible default)

**Class product picker (ClassProductSection)** — `ModalBottomSheet`:
- Sticky top item: **"+ Añadir nueva clase"** → opens `CreateProductView` with `productType = "CLASS"` (Phase B-side: extend `CreateProductView`)
- LazyColumn of `Product.type=CLASS && active` with name, subtitle (duration), trailing price

**Empty state when zero CLASS products in venue:**
```
        [📚 icon, 64dp]
   No tienes clases configuradas
   Crea tu primer producto tipo Clase
   
       [+ Crear clase]   (pill, primary)
```
Tap CTA → opens extended `CreateProductView` with `productType = "CLASS"`.

**Submit:**
- Validate: product, date, start time required. End time auto-calculated. Capacity ≥1.
- Reject if `startsAt < now` (venue timezone).
- Call `repository.create(...)` → on success, `AvoqadoSuccessToast("¡Clase agendada!")` + close + invalidate calendar fetch.

**Risk:** Medium. New screen but reuses summary-form pattern. The `CreateProductView` extension is the only refactor risk — must not break existing FOOD_AND_BEV creation.

---

### Phase B+ — Recurring creation (extends Phase B, 1 day)

**Goal:** Add the recurrence picker so a single submission creates N sessions.

**Deliverables:**
- Extend `CreateClassSessionViewModel` with recurrence draft fields:
  ```kotlin
  data class CreateClassSessionDraft(
      ...,
      val isRecurring: Boolean = false,
      val weekdays: Set<Int> = emptySet(),  // server values: 0=Sun..6=Sat (matches /bulk schema)
      val endMode: EndMode = EndMode.COUNT,  // COUNT | DATE
      val occurrences: Int = 8,
      val endDate: LocalDate? = null,
  )
  ```
  **Visual ordering of chips matches web dashboard: L M X J V S D (Monday-first, Mexican convention).** The internal int values stay 0=Sunday..6=Saturday to match the server `/bulk` schema; the chip rendering just maps `[1, 2, 3, 4, 5, 6, 0]` to the visible button order so "L" sends `1`, "D" sends `0`.
- New section `RecurrenceSection.kt` — opens `ModalBottomSheet`:

```
┌──────────────────────────────────────────────┐
│  X     Repetición           Listo           │
├──────────────────────────────────────────────┤
│  REPETIR ESTA CLASE                          │
│  [ Toggle ]                                  │
│                                              │
│  Cuando ON ↓                                 │
│                                              │
│  DÍAS DE LA SEMANA                           │
│  [ L ] [ M ] [ X ] [ J ] [ V ] [ S ] [ D ]   │
│  (chips multi-select, Mon-first per web)     │
│                                              │
│  TERMINA                                     │
│  ⦿ Después de N sesiones    [8] [stepper]   │
│  ○ En una fecha              [date picker]  │
│                                              │
│  Las sesiones se crearán todas a la misma    │
│  hora. Si alguna fecha ya tiene una clase    │
│  agendada, se omite automáticamente.         │
└──────────────────────────────────────────────┘
```

**Validation:**
- If `isRecurring = true`: weekdays.isNotEmpty() required
- If `endMode = DATE`: endDate required + endDate >= startDate
- If `endMode = COUNT`: occurrences in 1..104

**Submit logic in ViewModel:**
```kotlin
fun submit() {
    if (draft.isRecurring) {
        repository.createBulk(CreateClassSessionBulkRequest(
            productId = draft.productId,
            startDate = draft.date.format(ISO_LOCAL_DATE),
            startTime = draft.startTime.format("HH:mm"),
            endTime = draft.endTime.format("HH:mm"),
            weekdays = draft.weekdays.toList().sorted(),
            endDate = if (draft.endMode == DATE) draft.endDate?.format(ISO_LOCAL_DATE) else null,
            occurrences = if (draft.endMode == COUNT) draft.occurrences else null,
            capacity = draft.capacity,
            assignedStaffId = draft.staffId,
            internalNotes = draft.notes,
        ))
    } else {
        repository.create(CreateClassSessionRequest(
            productId = draft.productId,
            startsAt = ZonedDateTime.of(draft.date, draft.startTime, venueZone).toInstant().toString(),
            endsAt = ZonedDateTime.of(draft.date, draft.endTime, venueZone).toInstant().toString(),
            capacity = draft.capacity,
            assignedStaffId = draft.staffId,
            internalNotes = draft.notes,
        ))
    }
}
```

**Success toast:**
- Single: `"¡Clase agendada!"`
- Bulk: `"X clases agendadas"` + ` "(Y omitidas por conflicto)"` if `skipped > 0`

**Risk:** Low. Recurrence rule is local UI state; server does the expansion.

---

### Phase C — Calendar rendering (1 day)

**Goal:** Class sessions appear in `CalendarDayGrid` and `CalendarWeekGrid` distinct from reservations.

**Deliverables:**
- Extend `CalendarUiState` with `classSessions: List<ClassSession>`
- Extend `CalendarViewModel.fetch()` to fetch both reservations AND class sessions in parallel:
  ```kotlin
  val (resResult, csResult) = awaitAll(
      async { repository.fetchCalendar(from, to) },
      async { classSessionRepository.fetchList(from, to) },
  )
  ```
- New `presentation/components/ClassSessionBlock.kt` composable:
  - **Background and content colors come from new design-system tokens** (no hardcoded `Color(0x...)` per CLAUDE.md). Add to `designsystem/theme/Color.kt`:
    ```kotlin
    // ClassSession (calendar block) — distinct from reservation status colors
    val ClassSessionContainerLight = Color(0xE6_7C3AED)  // violet-600 90% alpha
    val ClassSessionContainerDark  = Color(0xE6_A78BFA)  // violet-400 90% alpha (dark mode)
    val ClassSessionContent        = Color.White         // text on violet, both modes
    val ClassSessionBorder         = Color(0xFF_5B21B6)  // violet-800 (border accent)
    ```
    These are wired into `AvoqadoTheme` via `Colors` extension (`AvoqadoTheme.colors.classSessionContainer` / `AvoqadoTheme.colors.classSessionContent`) following the same pattern other custom-domain tokens use (action colors, discount, etc).
  - Border 1dp `ClassSessionBorder`
  - Top-right badge `"{enrolled}/{capacity}"` with `Icons.Filled.Group` icon
  - "Lleno" pill when full; "X cupos" hint when ≤3 left
  - Tap → `onClassSessionClick(session)`
- Extend `CalendarDayGrid` and `CalendarWeekGrid`:
  - Accept `classSessions: List<ClassSession>` and `onClassSessionClick: (ClassSession) -> Unit`
  - Render class blocks alongside reservation blocks (same offset/height calculation)
  - **CRITICAL filter:** filter out reservations with `classSessionId != null` from regular block rendering — those live inside the class block
- Add filter toggle in `CalendarSettingsSheet`: **"Mostrar clases"** (default ON), persisted same way as other filters
- Wire `CalendarTabHost` to handle `onClassSessionClick` → opens `ClassSessionDetailSheet` (Phase D)

**Visual contrast:**

| | Reservation block | Class session block |
|---|---|---|
| Background | brand color tint (existing) | violet `0xE67C3AED` |
| Border | brand color | violet darker |
| Subtitle | customer name + service | product name + `enrolled/capacity` |
| Drag-to-reschedule | yes | **no in MVP** |

**Risk:** Medium. Touching the existing grid renderer is the highest-blast-radius change in this spec. Tests must cover: reservations still render correctly, class blocks don't overlap badly with reservations in the same hour, drag still works on reservations only.

---

### Phase D — Class session detail + attendees (1.5 days)

**Goal:** Tap a class block → see full info + manage attendees.

**Deliverables:**
- `presentation/classSessions/ClassSessionDetailSheet.kt` — bottom sheet (or fullscreen on phones)
- `presentation/classSessions/ClassSessionDetailViewModel.kt`
- `presentation/classSessions/AddAttendeeSheet.kt` — modal with two tabs

**Detail sheet layout:**

```
┌──────────────────────────────────────────────┐
│  X     Yoga Vinyasa                          │
│  Mié 7 may · 18:00 – 19:00                   │
├──────────────────────────────────────────────┤
│  Tabs: [ Detalles ]  [ Asistentes (8/12) ]   │
├──────────────────────────────────────────────┤
│ (Detalles tab)                               │
│  Capacidad: 12                               │
│  Staff: María González                       │
│  Notas: Lleva tu propia mat                  │
│                                              │
│  [ Editar ]                                  │
│                                              │
│  [ Cancelar sesión ] (destructive)           │
└──────────────────────────────────────────────┘
```

**Attendees tab:**

```
┌──────────────────────────────────────────────┐
│ (Asistentes tab — capacity: 8/12)            │
│                                              │
│ [ + Agregar asistente ]                     │
│                                              │
│ ●  María Pérez · 5512345678                 │
│ ●  Juan López · invitado                     │
│ ●  Ana Ruiz · 5567890123                     │
│   (swipe-to-delete or long-press → confirm) │
│                                              │
│ Si capacity reached:                         │
│   "Sesión llena — sin cupos disponibles"     │
└──────────────────────────────────────────────┘
```

**Add attendee sheet — two tabs:**

```
┌──────────────────────────────────────────────┐
│  X     Agregar asistente                     │
├──────────────────────────────────────────────┤
│  Tabs: [ Cliente existente ]  [ Invitado ]   │
├──────────────────────────────────────────────┤
│ (Cliente existente tab) — reuses             │
│  CustomerSection from CreateReservation,     │
│  picking auto-closes sheet and POSTs:        │
│  { customerId, guestName: customer.fullName, │
│    guestPhone: customer.phone,               │
│    guestEmail: customer.email }              │
│                                              │
│ (Invitado tab)                               │
│  Nombre*   [ ___________ ]                   │
│  Teléfono  [ ___________ ]                   │
│  Email     [ ___________ ]                   │
│  [ Agregar ] (full-width pill)              │
└──────────────────────────────────────────────┘
```

**Capacity guard:**
- Disable "+ Agregar asistente" button when `enrolled >= capacity`. Show "Sesión llena" sub-label.
- Server also validates server-side; client guard is UX only.

**Cancel session flow:**
- Button "Cancelar sesión" (destructive style)
- Confirmation dialog: "¿Cancelar esta sesión? Se notificará a los N asistentes inscritos."
- Confirm → `repository.cancel(sessionId)` → server cancels all child reservations atomically
- Toast: "Sesión cancelada"
- Detail sheet closes; calendar refreshes

**Risk:** Low-medium. The CustomerSection reuse is the cleanest path; tab switching is standard Compose.

---

## 5. Empty state & cross-flow integrations

### 5.1 No CLASS products in venue (Phase B+B+)

When user opens the class product picker and `classProducts.isEmpty()`:

```
        [📚 icon, 64dp]
   No tienes clases configuradas
   Crea tu primer producto tipo Clase
   
       [+ Crear clase]
```

Tap CTA → opens extended `CreateProductView` (option A from brainstorm) with `productType = "CLASS"`. The form shows class-specific fields (`duration`, `maxParticipants`) and HIDES food-specific fields (`isAlcoholic`, `kitchenName`, etc.).

**`CreateProductView` extension contract:**
```kotlin
@Composable
fun CreateProductView(
    productsRepository: ProductsRepository,
    productType: String = "FOOD_AND_BEV",  // ⭐ NEW PARAM
    onCreated: (Product) -> Unit = {},
    onDismiss: () -> Unit,
) {
    // Existing FOOD fields...
    if (productType == "CLASS") {
        DurationField(...)        // minutes
        MaxParticipantsField(...) // int stepper
    }
    // Submit sets type = productType in CreateProductRequest
}
```

After successful creation, the new `Product` is auto-selected in the class product picker.

### 5.2 Not in MVP

| Flow | Why |
|---|---|
| Public booking → customer picks a session for CLASS product | Server-side. Out of mobile scope. Phase 2+ once web does it. |
| Edit class session UX | Reschedule with attendees is delicate (notify? reschedule each? cancel each?). Single-edit (capacity, staff, notes only) IS in MVP via Phase D's "Editar". Time edits deferred. |
| Drag-to-reschedule a class on calendar | Web has it; Android Phase 2. |
| Layout/seatmap visual editor | Web doesn't have either. Phase 3. |
| Walk-in attendee fast-path during a class | Phase 2 — UX is "+ Agregar asistente" → Invitado tab, that's already covered. |
| Waitlist on full sessions | Server has waitlist for individual reservations; extending to classes is Phase 3. |

---

## 6. Schema/server impact

**Zero server changes needed.** Server is fully implemented and Android consumes existing endpoints.

The only schema-adjacent observation: server's `addAttendee` requires `guestName` even when `customerId` is provided (it stores a snapshot). Client always populates `guestName` from the selected customer's `fullName`.

---

## 7. UX details — visual consistency table

| Element | Reservation (existing) | Class session (new) | Source |
|---|---|---|---|
| Calendar block bg | brand status color, alpha 0xE6 | `AvoqadoTheme.colors.classSessionContainer` (violet ~90%) | Match web `bg-violet-500/20` semantically; we use 0xE6 for grid-line opacity per ReservationBlock.kt 2026-04-30 fix. **No hardcoded Color(0x..)** — token only. |
| Capacity badge | n/a | top-right `8/12` + Group icon | Web pattern |
| "Full" indicator | n/a | "Lleno" pill | Web pattern |
| Block tap | opens reservation detail | opens ClassSessionDetailSheet | Per phase D |
| Drag-to-reschedule | yes | **no in MVP** | Defer |
| Visual hierarchy when stacked | side-by-side columns if same hour | same | Existing layout |

---

## 8. Error handling

| Scenario | Behavior |
|---|---|
| Server returns 409 conflict on bulk create (some dates already have classes) | Server already returns `{ count, skipped, created[] }` — client shows "X clases agendadas (Y omitidas por conflicto)" toast |
| `addAttendee` race: capacity reached between read and POST | Server returns 409 — client shows "Sesión llena" inline error and refreshes capacity counter |
| Cancel session while attendees exist | Server cancels all child reservations atomically — no special client logic needed |
| `removeAttendee` for already-removed attendee | Server returns 404 — client treats as success (idempotent) and removes from local state |
| Network down during create | Repository's offline-queue (mirroring `ReservationRepository` pattern) — enqueue + retry on reconnect |
| Class session deleted while detail sheet open | Server returns 404 on next read — sheet shows "Sesión no encontrada" + auto-close after 2s |
| Customer with `customerId` belongs to different venue (impossible via UI but defensive) | Server validates and returns 422; client shows error toast |

---

## 9. Testing strategy

### Phase A — Data layer
- DTO round-trip tests with fixtures captured from web's actual server responses
- Repository unit tests with mocked Retrofit
- Offline-queue: enqueue → reconnect → replay scenario

### Phase B/B+ — Create flow
- ViewModel state transitions: product picked → endTime auto-fills, capacity auto-fills
- Recurrence picker: weekday selection, end-mode switch, validation errors
- E2E with mock server: single create + bulk create with conflict response

### Phase C — Calendar rendering
- Visual snapshot tests for `ClassSessionBlock` (light, dark, full, has-spots, almost-full)
- Calendar grid with mixed reservations + class sessions in same hour — no overlap bugs
- Reservation with `classSessionId != null` → NOT rendered as standalone

### Phase D — Detail + attendees
- Tab switching preserves state
- Add via existing customer → POST contains `customerId`
- Add via guest → POST contains only guest fields
- Capacity guard hides "+ Agregar" at full
- Cancel confirmation flow

### Manual smoke (physical SM-X133)
- Full happy path: create → see on calendar → tap → add 3 attendees (2 customers + 1 guest) → cancel session → toast → calendar refreshes
- Empty venue (no CLASS products) → CTA → create class product inline → returns to picker with new product preselected
- Bulk recurring: 8 weeks × MWF → submit → "24 clases agendadas" toast → calendar shows 24 blocks across 8 weeks

---

## 10. Out of scope (deferred to v2+)

- ❌ Public booking integration (customer picks session from `PublicBookingPage`) — server-side, separate spec
- ❌ Drag-to-reschedule class sessions on calendar
- ❌ Edit class session start/end time (read-only in detail sheet for MVP — only capacity/staff/notes editable)
- ❌ Layout/seatmap visual editor (`Product.layoutConfig.spots`)
- ❌ Waitlist for full class sessions
- ❌ Notify-attendees-on-cancel customization (server already cancels their reservations; UI for custom message is v2)
- ❌ Recurring rule editor for ALREADY-CREATED sessions (e.g., "delete all future occurrences of this series") — series tracking not in server schema yet
- ❌ Class series analytics (attendance %, no-shows, revenue per series)
- ❌ Migration of existing reservation `classSessionId` orphans (none in production today)

---

## 11. Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Refactoring `CreateProductView` breaks FOOD creation | Medium | High | Default param value preserves existing behavior; regression test on FOOD_AND_BEV before merging Phase B |
| Calendar grid rendering breaks for venues with mixed bookings | Medium | High | Phase C must include manual smoke on a venue seeded with reservations + class sessions in same hour |
| Server's `enrolled/available` becomes stale after `addAttendee` | Low | Medium | Repository emits change event after mutation; Detail sheet observes and refetches |
| `customerId` validation fails for customer recently moved between venues | Low | Low | Server returns 422; client shows error toast; user re-selects |
| Bulk create with 100+ occurrences times out | Low | Medium | Server already handles atomically; 104 occurrences cap (matches web zod schema) |
| Recurrence rule + venue timezone DST transition creates wrong-hour sessions | Medium | High | Server expands rule using venue timezone (already implemented in `/bulk` endpoint per service code). Client only sends local startTime + endTime. Verify with manual smoke spanning DST boundary. |

---

## 12. References

### Server (no changes)
- `prisma/schema.prisma:7874-7913` — `ClassSession` model
- `prisma/schema.prisma:1106-1107` — `Product` class-specific fields
- `routes/dashboard/classSession.routes.ts:24-87` — 8 endpoints
- `services/dashboard/classSession.dashboard.service.ts` — service layer
- `schemas/dashboard/classSession.schema.ts:55-63` — `addAttendee` schema (accepts both customerId and guest)

### Web dashboard (reference patterns to clone)
- `services/classSession.service.ts` — clone DTOs
- `pages/Reservations/components/CreateClassSessionDialog.tsx` — single + recurring create UX
- `pages/Reservations/components/EditClassSessionDialog.tsx` — edit + attendees roster
- `pages/Reservations/ReservationCalendar.tsx:567-640` — class session block rendering

### Android (existing infrastructure to reuse)
- `presentation/reservations/create/CreateReservationScreen.kt` — summary form pattern
- `presentation/reservations/create/sections/CustomerSection.kt` — customer picker (reuse in Add Attendee)
- `presentation/components/ReservationBlock.kt` — reference for new `ClassSessionBlock`
- `pos/presentation/product/CreateProductView.kt` — extend with `productType` param
- `data/ReservationRepository.kt` — pattern for `ClassSessionRepository`

### Square (Android sm-x133, captured 2026-05-05)

Live captures from the physical SM-X133 device with `Square` app v6.x. Screenshots stored under `images/2026-05-05-class-sessions/` next to this spec.

#### Crear clase — main screen (top)

![Square create class — top of form](images/2026-05-05-class-sessions/01-square-create-main.png)

Header: ←arrow + title left-aligned + "Guardar" pill (dark) right. Body sections: "Detalles de clase" with info icon → Nombre de la clase dropdown. Then "Calendario de clases" → Fecha labeled box.

#### Crear clase — fields below the fold

![Square create class — date/time/repetitions/capacity/staff](images/2026-05-05-class-sessions/02-square-create-fields-bottom.png)

Hora de inicio + Hora de finalización selectors → "Repeticiones: Nunca" tappable row → "Lugares disponibles" plain numeric input → "Personal" selector with subtext ("Nombre desconocido" when none).

#### Class picker dropdown

![Square class name picker](images/2026-05-05-class-sessions/03-square-class-picker.png)

Pinned top item "Agregar clase nueva" + list of existing CLASS products (name + subtitle + price right-aligned). Same pattern web dashboard uses; Android replicates.

#### Recurrence picker — toggle off

![Square recurrence picker — off](images/2026-05-05-class-sessions/04-square-recurrence-off.png)

Modal sheet with X close (left) + "Guardar" pill (right). Title "Establecer repetición". Single toggle "Repetir clase" — off by default, no other fields visible.

#### Recurrence picker — toggle on

![Square recurrence picker — on](images/2026-05-05-class-sessions/05-square-recurrence-on.png)

When toggle is ON: "Repetir cada [N stepper] [Frecuencia]" + "Termina" dropdown.

#### Frecuencia options

![Square frequency dropdown](images/2026-05-05-class-sessions/06-square-frequency-options.png)

`Días | Semanas | Meses | Años`. **Avoqado rejects this model** in favor of web's weekday-multi-select pattern (per §3.2) — see the spec's reasoning on MWF use cases.

#### Termina options

![Square end-mode dropdown](images/2026-05-05-class-sessions/07-square-end-options.png)

`Nunca | En una fecha establecida | Después de un número de clases`. Avoqado supports the latter two (`endDate` and `occurrences`), drops "Nunca" since the web/server contract requires bounded series.

#### Recurrence with end-date selected

![Square recurrence with end-date](images/2026-05-05-class-sessions/08-square-recurrence-end-date.png)

When `Termina = En una fecha establecida`, a "Fecha" picker appears. Avoqado's RecurrenceSection mirrors this pattern (date input only renders when `endMode = DATE`).

### Web dashboard (Mindform venue, captured 2026-05-05 via Playwright)

Captures from `dashboard.avoqado.io` logged in as superadmin. These are the **paridad reference** — the visual contract Android replicates exactly.

#### Reservations overview

![Avoqado web — reservations overview](images/2026-05-05-class-sessions/web-01-reservations-overview.png)

Top-level Reservations page with stat cards (Today / Pending / In Progress / No Show Rate), filter tabs (All / Pending / Confirmed / Today / No Show), and the data table. Sidebar nav shows: Overview / Calendar / Waitlist / Online booking / Settings.

#### Calendar — empty Day view

![Avoqado web — calendar Day view](images/2026-05-05-class-sessions/web-02-calendar.png)

Day grid 09:00–22:00 with red current-time indicator. Top controls: date arrows, date label, Interval Day toggle, Group by, settings/clock icons, "+ Crear" button (top-right, dark pill). This is the layout we mirror in Android Day view (already shipped — `CalendarDayView.kt`).

#### "+ Crear" menu

![Avoqado web — Crear menu](images/2026-05-05-class-sessions/web-03-create-menu.png)

Dropdown with two items: **Cita** (icon: calendar) and **Clase** (icon: people). Same split Android implements via `ActionSheetCenter` in `CalendarTabHost.kt`. Selecting "Clase" opens the dialog below.

#### Schedule class dialog (default state)

![Avoqado web — Schedule class dialog](images/2026-05-05-class-sessions/web-04-create-class-dialog.png)

Modal dialog (centered, ~440px wide) with title `🎯 Schedule class`. Fields top-to-bottom:
- **Class name** (combobox, "Select a class" placeholder)
- **Date** (HTML date input, default = today in venue tz)
- **Start time** + **End time** (paired clock inputs, side by side; End is empty until product picked)
- **Available spots** (numeric, default 15) + **Assigned staff** (combobox, "Unassigned" default)
- **Repetir esta clase** checkbox (off by default)
- **Internal notes (Optional)** textarea
- Footer: Cancel + Schedule (Schedule disabled until class picked)

**Note:** title shows "Schedule class" in English while form labels mix English ("Class name", "Date") + Spanish ("Repetir esta clase", "Internal notes"). This is web's i18n-in-progress state. **Android spec uses pure Spanish: "Crear clase", "Nombre de la clase", "Fecha", etc.** — same field shape, fully localized.

#### Class name picker (dropdown open)

![Avoqado web — Class picker dropdown](images/2026-05-05-class-sessions/web-05-class-picker.png)

Pinned top item: **"+ Add new class"** (matches Android empty-state CTA). Below: **All classes** group label + list of CLASS products with name + price right-aligned (no duration shown here — web's compact variant). Mindform venue has 2 classes seeded: "clase lagree $380.00" and "Cellular Cleanse $17060.00".

#### Recurrence ON — full recurring section

![Avoqado web — recurrence on with weekday picker](images/2026-05-05-class-sessions/web-06-recurrence-on.png)

After selecting "clase lagree" and toggling "Repetir esta clase":
- **Available spots: 24** with auto-fill subtitle: **"Auto-calculado: 24 lugares del mapa"** (proves layoutConfig auto-fill — clase lagree has a 24-seat layout configured)
- **Días de la semana**: 7 chip buttons in **L M X J V S D order** (Monday-first, Mexican convention). Each chip shows tooltip with full day name (Lunes, Martes, Miércoles, Jueves, Viernes, Sábado, Domingo).
- **Termina** dropdown ("Después de N sesiones" default) + **# de sesiones** numeric input (default 8) side by side
- Hint paragraph: *"Las sesiones se crearán todas a la misma hora. Si alguna fecha ya tiene una clase agendada, se omite automáticamente."*

**Critical for Android port:** chip order is **Mon-first (L M X J V S D)**, not Sun-first. Internal int values for the `weekdays[]` payload remain 0=Sun..6=Sat to match the server `/bulk` schema; only the visual ordering changes.

#### Termina options

![Avoqado web — Termina dropdown](images/2026-05-05-class-sessions/web-07-termina-options.png)

Only 2 options: **"Después de N sesiones"** (count mode) and **"En una fecha"** (date mode). When date mode is selected, the `# de sesiones` input is replaced with a date picker for `endDate`. Web does NOT include Square's "Nunca" option — Avoqado's bulk endpoint requires bounded series.

### Captures NOT taken (acknowledge gap)

| Screen | Reason absent |
|---|---|
| Square Android — class detail (after tap on existing class) | Account `hola@avoqado.io` on sm-x133 has zero classes (fresh test account). Cannot tap a class that doesn't exist. |
| Square Android — add attendee flow | Same reason — no existing class to add attendees to. |
| Web dashboard — calendar with violet class block | Mindform venue calendar empty in current date range; couldn't trigger the violet block render without seeding a class. Code reference `pages/Reservations/ReservationCalendar.tsx:567-640` is authoritative. |
| Web dashboard — `EditClassSessionDialog` with attendees tab | Same — no existing class to edit. Code reference `pages/Reservations/components/EditClassSessionDialog.tsx` is authoritative. |

These two web-dashboard captures (calendar block + edit dialog) are the only remaining gaps. They can be filled in a follow-up by seeding a test class on Mindform, or referenced via the cited TS source files.

---

## 13. Approval criteria

This spec is ready to move to implementation when the reviewer confirms:

- [ ] Section 3 decisions (per-venue customers, web recurrence pattern, CreateProductView extension, calendar render, edit-only-non-time, no offline queue, URL convention exception, permissions gating, conflict handling, cache invalidation, EditClassSessionScreen design, DST handling)
- [ ] Phase boundaries (Section 4) are sensible — A and B can ship independently; B+ extends B; C requires A; D requires C
- [ ] Schema/data shape matches what server expects (Section 6)
- [ ] Product DTO extension (`maxParticipants`, `layoutConfig` on `Product.kt`; `duration` + `maxParticipants` on `CreateProductRequest.kt`) won't break existing FOOD_AND_BEV creation
- [ ] URL pattern in Phase A matches existing `ReservationApi.kt` convention (relative `/dashboard/...`, no `/api/v1/` re-prefix)
- [ ] Permissions gating (§3.9) — UI hides actions per server role gates
- [ ] Conflict handling (§3.10) — both single 409 and bulk partial-skip flows are spec'd
- [ ] Cache invalidation (§3.11) — both reservation + class-session change flows merge into calendar VM
- [ ] EditClassSessionScreen (§3.12) — read-only fields render distinctly; capacity-below-enrolled error path covered
- [ ] DST handling (§3.13) — client never converts recurring times to UTC; server expansion is authoritative
- [ ] No hardcoded `Color(0x...)` in any composable — all violet usage flows through `AvoqadoTheme.colors.classSession*` tokens
- [ ] Square UI references (§12) embedded; web-dashboard captures acknowledged as deferred follow-up
- [ ] Out-of-scope items (Section 10) are acceptable for MVP
- [ ] Risk mitigations (Section 11) are realistic

If approved, implementation invokes the writing-plans skill to break Phases A–D into bite-sized tasks for subagent execution.

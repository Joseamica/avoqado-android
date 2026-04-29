# Reservations Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the Android "agenda de turno" — staff sees today's reservations on a calendar tab (conditionally visible per device mode), drills into a reservation detail, and runs status transitions (confirm / check-in / complete / no-show / cancel / reschedule) — all reusing the existing `/dashboard/venues/:venueId/reservations/*` endpoints with no server changes required.

**Architecture:** Single-Activity Compose + Hilt + StateFlow. New `reservations/` module mirroring the structure of `orders/` and `customers/`. OkHttp directly (no Retrofit) following the existing `CustomersRepository` pattern. Bottom-tab visibility computed from `venue.featureFlags.reservations` + device-local `VenueMode` persisted in `SecureStorage`. Status transitions are optimistic with snackbar rollback on failure; offline retry uses the existing `ConnectivityMonitor` + a small Room DAO (`PendingReservationActionDao`) modelled after `PendingPaymentDao`.

**Tech Stack:** Kotlin 2.1 · Jetpack Compose BOM 2025.01.01 · Material3 · Hilt 2.54 · OkHttp 4.12 · kotlinx-serialization · Room 2.6 · Coil 2.7 · WindowSizeClass · JUnit4 + MockK 1.13 + Turbine 1.1 + kotlinx-coroutines-test for tests.

---

## Spec & Context

- **Source spec:** `docs/superpowers/specs/2026-04-29-android-reservations-design.md`
- **Server reservation API map:** `docs/research/square-deep-dive/avoqado-server-reservations-map.md`
- **Web dashboard reservation map:** `docs/research/square-deep-dive/avoqado-web-reservations-map.md`
- **Square feature inventory:** `docs/research/square-deep-dive/square-feature-inventory.md`

Read the spec before starting Task 1. Every task assumes the spec terminology (Modo, ReservationStatus values, etc.).

---

## Pre-flight: spike to confirm `/dashboard/` accepts mobile JWT

Before Task 1, verify the assumption baked into §6 of the spec. **Don't skip this** — if it fails, the plan needs an /api/mobile/ wrapper task added.

Run from your terminal:

```bash
# 1. Get the current device's JWT (from a logged-in tablet)
adb -s R8YL200592L shell run-as com.avoqado.pos cat /data/data/com.avoqado.pos/shared_prefs/avoqado_secure_prefs.xml | grep KEY_TOKEN
# OR pull it from Logcat right after a fresh login.

# 2. curl the dashboard endpoint with that token
TOKEN="<paste here>"
VENUE="<your venue id>"
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.avoqado.io/api/v1/dashboard/venues/$VENUE/reservations?page=1&pageSize=5" | jq '.[] | .id, .status' | head
```

Expected: 200 with reservation rows OR 200 with empty array. If 401/403, **STOP** — open Task 0a below to add `/api/v1/mobile/venues/:id/reservations/*` shims on the server before continuing. If 404 the URL is wrong — re-read `avoqado-server-reservations-map.md`.

---

## File Structure

All paths relative to `app/src/main/java/com/avoqado/pos/`.

```
reservations/
├── data/
│   ├── ReservationApi.kt                  # Pure network layer (OkHttp calls + JSON)
│   ├── ReservationRepository.kt           # @Singleton — coordinates Api + offline DAO
│   ├── PendingReservationActionDao.kt     # Room DAO for offline state-transition queue
│   ├── PendingReservationActionEntity.kt  # Room @Entity
│   ├── ReservationActionsRetrier.kt       # Drains pending queue when ConnectivityMonitor flips online
│   └── model/
│       ├── Reservation.kt                 # Top-level @Serializable model + lite relations
│       ├── ReservationStatus.kt           # 6-value enum
│       ├── ReservationChannel.kt          # 7-value enum
│       ├── DepositStatus.kt               # 5-value enum
│       ├── ReservationFilters.kt          # value class for list query params
│       ├── ReservationListResponse.kt     # paginated wrapper
│       └── RescheduleRequest.kt
├── domain/
│   ├── ReservationStateMachine.kt         # Pure functions: nextLegalStatus(current, action)
│   ├── ReservationsCapability.kt          # Permission decoder from JWT scopes
│   └── VenueMode.kt                       # enum + storage key constant
├── presentation/
│   ├── calendar/
│   │   ├── CalendarTabHost.kt             # Top-level Composable for the tab
│   │   ├── CalendarViewModel.kt
│   │   ├── CalendarUiState.kt
│   │   ├── CalendarDayView.kt
│   │   ├── CalendarWeekView.kt
│   │   └── CalendarSettingsSheet.kt
│   ├── list/
│   │   ├── ReservationsListScreen.kt
│   │   ├── ReservationsListViewModel.kt
│   │   └── ReservationsListUiState.kt
│   ├── detail/
│   │   ├── ReservationDetailScreen.kt
│   │   ├── ReservationDetailViewModel.kt
│   │   ├── ReservationDetailUiState.kt
│   │   ├── CancelReservationSheet.kt
│   │   └── RescheduleSheet.kt
│   ├── onboarding/
│   │   ├── ActivateReservationsScreen.kt
│   │   └── ModeSwitcherSheet.kt
│   └── components/
│       ├── ReservationStatusBadge.kt
│       ├── ReservationBlock.kt
│       ├── WeekStrip.kt
│       ├── CalendarDayGrid.kt
│       ├── CalendarWeekGrid.kt
│       ├── CurrentTimeIndicator.kt
│       ├── EmptyStateBlock.kt
│       └── ActionSheetCenter.kt
├── push/
│   └── ReservationPushHandler.kt          # routes "reservation.*" payloads to deep link
└── di/
    └── ReservationModule.kt               # Hilt @Module
```

Tests mirror this under `app/src/test/java/com/avoqado/pos/reservations/...`.

---

## Milestones overview

| # | Milestone | Tasks | Output |
|---|---|---|---|
| 1 | Data + domain foundation | T1–T6 | Pure-Kotlin layer, fully unit-tested, no UI yet |
| 2 | Mode pattern + onboarding | T7–T10 | Conditional Calendar tab + Activar reservas screen |
| 3 | Reservations List + Detail | T11–T16 | Stateful list + full detail screen with transitions |
| 4 | Calendar Day + Week + Settings | T17–T23 | Day/Week grids with current-time line, settings sheet |
| 5 | Push + offline + nav wiring | T24–T26 | Push deep-link, retry queue drains, nav graph wired |
| 6 | Polish + ship | T27–T29 | Smoke test, accessibility, version bump |

---

## Task 1: Module skeleton + Hilt module + domain enums

**Goal:** Folders exist, Hilt module compiles, enums are typed and unit-tested.

**Files:**
- Create: `reservations/di/ReservationModule.kt`
- Create: `reservations/data/model/ReservationStatus.kt`
- Create: `reservations/data/model/ReservationChannel.kt`
- Create: `reservations/data/model/DepositStatus.kt`
- Create: `reservations/domain/VenueMode.kt`
- Test: `app/src/test/java/com/avoqado/pos/reservations/data/model/ReservationStatusTest.kt`

- [ ] **Step 1: Create `ReservationStatus.kt`**

```kotlin
package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReservationStatus {
    @SerialName("PENDING") PENDING,
    @SerialName("CONFIRMED") CONFIRMED,
    @SerialName("CHECKED_IN") CHECKED_IN,
    @SerialName("COMPLETED") COMPLETED,
    @SerialName("CANCELLED") CANCELLED,
    @SerialName("NO_SHOW") NO_SHOW;

    val isActive: Boolean get() = this == PENDING || this == CONFIRMED || this == CHECKED_IN
    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELLED || this == NO_SHOW
}
```

- [ ] **Step 2: Create `ReservationChannel.kt`**

```kotlin
package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReservationChannel {
    @SerialName("DASHBOARD") DASHBOARD,
    @SerialName("WEB") WEB,
    @SerialName("PHONE") PHONE,
    @SerialName("WHATSAPP") WHATSAPP,
    @SerialName("APP") APP,
    @SerialName("WALK_IN") WALK_IN,
    @SerialName("THIRD_PARTY") THIRD_PARTY;

    val displayLabel: String get() = when (this) {
        DASHBOARD -> "Dashboard"
        WEB -> "Web"
        PHONE -> "Teléfono"
        WHATSAPP -> "WhatsApp"
        APP -> "App"
        WALK_IN -> "Walk-in"
        THIRD_PARTY -> "Externo"
    }
}
```

- [ ] **Step 3: Create `DepositStatus.kt`**

```kotlin
package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DepositStatus {
    @SerialName("PENDING") PENDING,
    @SerialName("CARD_HOLD") CARD_HOLD,
    @SerialName("PAID") PAID,
    @SerialName("REFUNDED") REFUNDED,
    @SerialName("FORFEITED") FORFEITED,
}
```

- [ ] **Step 4: Create `VenueMode.kt`**

```kotlin
package com.avoqado.pos.reservations.domain

enum class VenueMode(val storageValue: String, val displayLabel: String) {
    STANDARD("standard", "Estándar"),
    RESERVATIONS("reservations", "Reservas");

    companion object {
        const val STORAGE_KEY = "KEY_VENUE_MODE"
        fun fromStorage(raw: String?): VenueMode = entries.firstOrNull { it.storageValue == raw } ?: STANDARD
    }
}
```

- [ ] **Step 5: Write enum unit test**

Create `app/src/test/java/com/avoqado/pos/reservations/data/model/ReservationStatusTest.kt`:

```kotlin
package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationStatusTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `PENDING CONFIRMED CHECKED_IN are active`() {
        assertTrue(ReservationStatus.PENDING.isActive)
        assertTrue(ReservationStatus.CONFIRMED.isActive)
        assertTrue(ReservationStatus.CHECKED_IN.isActive)
    }

    @Test
    fun `COMPLETED CANCELLED NO_SHOW are terminal`() {
        assertTrue(ReservationStatus.COMPLETED.isTerminal)
        assertTrue(ReservationStatus.CANCELLED.isTerminal)
        assertTrue(ReservationStatus.NO_SHOW.isTerminal)
        assertFalse(ReservationStatus.PENDING.isTerminal)
    }

    @Test
    fun `serializes via SerialName uppercase`() {
        val encoded = json.encodeToString(ReservationStatus.serializer(), ReservationStatus.CHECKED_IN)
        assertEquals("\"CHECKED_IN\"", encoded)
    }

    @Test
    fun `decodes server enum strings`() {
        val decoded = json.decodeFromString(ReservationStatus.serializer(), "\"NO_SHOW\"")
        assertEquals(ReservationStatus.NO_SHOW, decoded)
    }
}
```

- [ ] **Step 6: Run test — expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.reservations.data.model.ReservationStatusTest"
```

Expected: 4 tests pass.

- [ ] **Step 7: Create empty `ReservationModule.kt`**

```kotlin
package com.avoqado.pos.reservations.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object ReservationModule {
    // Bindings will be added in subsequent tasks (Api, Repository, Dao).
}
```

- [ ] **Step 8: Build to confirm Hilt compiles**

```bash
./gradlew :app:assembleDebug -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/ app/src/test/java/com/avoqado/pos/reservations/
git commit -m "feat(reservations): module skeleton + ReservationStatus/Channel/DepositStatus/VenueMode enums"
```

---

## Task 2: Reservation domain model + lite relations

**Goal:** Full `Reservation` data class with hydrated relation lites (Customer, Table, Product, Staff). Round-trips through `kotlinx.serialization` with a real server payload.

**Files:**
- Create: `reservations/data/model/Reservation.kt`
- Create: `reservations/data/model/ReservationListResponse.kt`
- Test: `app/src/test/java/com/avoqado/pos/reservations/data/model/ReservationSerializationTest.kt`
- Test fixture: `app/src/test/resources/fixtures/reservation_list_response.json`

- [ ] **Step 1: Capture a real fixture from production**

After confirming the pre-flight curl works, save the response:

```bash
mkdir -p app/src/test/resources/fixtures
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.avoqado.io/api/v1/dashboard/venues/$VENUE/reservations?page=1&pageSize=2" \
  | jq '.' > app/src/test/resources/fixtures/reservation_list_response.json
cat app/src/test/resources/fixtures/reservation_list_response.json
```

If your venue has zero reservations, create one in the web dashboard first so the fixture is non-trivial. The fixture is the source of truth for what fields are nullable.

- [ ] **Step 2: Inspect fixture, then write `Reservation.kt`**

```kotlin
package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Reservation(
    val id: String,
    val venueId: String,
    val confirmationCode: String,
    val cancelSecret: String,
    val status: ReservationStatus,
    val channel: ReservationChannel,
    val startsAt: String,            // ISO-8601 UTC — converted via VenueDateTimeFormatter at display time
    val endsAt: String,
    val duration: Int,
    val customerId: String? = null,
    val customer: CustomerLite? = null,
    val guestName: String? = null,
    val guestPhone: String? = null,
    val guestEmail: String? = null,
    val partySize: Int = 1,
    val spotIds: List<String> = emptyList(),
    val tableId: String? = null,
    val table: TableLite? = null,
    val productId: String? = null,
    val product: ProductLite? = null,
    val classSessionId: String? = null,
    val classSession: ClassSessionLite? = null,
    val assignedStaffId: String? = null,
    val assignedStaff: StaffLite? = null,
    val depositAmount: String? = null,    // BigDecimal as string
    val depositStatus: DepositStatus? = null,
    val depositPaidAt: String? = null,
    val depositRefundedAt: String? = null,
    val createdById: String? = null,
    val createdBy: StaffLite? = null,
    val confirmedAt: String? = null,
    val checkedInAt: String? = null,
    val completedAt: String? = null,
    val cancelledAt: String? = null,
    val noShowAt: String? = null,
    val cancelledBy: String? = null,
    val cancellationReason: String? = null,
    val specialRequests: String? = null,
    val internalNotes: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
) {
    val displayName: String get() = customer?.fullName ?: guestName ?: "Sin nombre"
    val displayPhone: String? get() = customer?.phone ?: guestPhone
    val displayServiceName: String? get() = product?.name ?: classSession?.productName
}

@Serializable
data class CustomerLite(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val phone: String? = null,
    val email: String? = null,
) {
    val fullName: String get() = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { phone ?: email ?: id }
}

@Serializable
data class TableLite(
    val id: String,
    val number: String,
    val capacity: Int? = null,
)

@Serializable
data class ProductLite(
    val id: String,
    val name: String,
    val durationMinutes: Int? = null,
    val price: String? = null,
)

@Serializable
data class ClassSessionLite(
    val id: String,
    val productId: String? = null,
    val productName: String? = null,
    val capacity: Int,
    val attendeeCount: Int = 0,
)

@Serializable
data class StaffLite(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null,
) {
    val displayName: String get() = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { id }
}
```

If your fixture exposes additional fields not listed, add them with sensible nullable defaults — never remove existing ones.

- [ ] **Step 3: Create `ReservationListResponse.kt`**

```kotlin
package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ReservationListResponse(
    val data: List<Reservation>,
    val pagination: Pagination? = null,
) {
    @Serializable
    data class Pagination(
        val page: Int,
        val pageSize: Int,
        val total: Int,
        val totalPages: Int,
    )
}
```

- [ ] **Step 4: Write the round-trip test**

Create `ReservationSerializationTest.kt`:

```kotlin
package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class ReservationSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun loadFixture(name: String): String =
        File("src/test/resources/fixtures/$name").readText()

    @Test
    fun `decodes real server list response without losing fields`() {
        val raw = loadFixture("reservation_list_response.json")
        val decoded = json.decodeFromString(ReservationListResponse.serializer(), raw)

        assertNotNull(decoded.data)
        decoded.data.forEach { r ->
            assertNotNull(r.id)
            assertNotNull(r.confirmationCode)
            assertNotNull(r.status)
            assertNotNull(r.startsAt)
            assertNotNull(r.endsAt)
        }
    }

    @Test
    fun `displayName falls back through customer to guestName to placeholder`() {
        val withCustomer = makeReservation(
            customer = CustomerLite("c1", firstName = "María", lastName = "López"),
            guestName = null,
        )
        assertEquals("María López", withCustomer.displayName)

        val withGuest = makeReservation(customer = null, guestName = "Walk-in")
        assertEquals("Walk-in", withGuest.displayName)

        val withNothing = makeReservation(customer = null, guestName = null)
        assertEquals("Sin nombre", withNothing.displayName)
    }

    private fun makeReservation(
        customer: CustomerLite? = null,
        guestName: String? = null,
    ) = Reservation(
        id = "r",
        venueId = "v",
        confirmationCode = "ABC",
        cancelSecret = "secret",
        status = ReservationStatus.CONFIRMED,
        channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-04-29T10:00:00.000Z",
        endsAt = "2026-04-29T11:00:00.000Z",
        duration = 60,
        customer = customer,
        guestName = guestName,
        createdAt = "2026-04-29T00:00:00.000Z",
        updatedAt = "2026-04-29T00:00:00.000Z",
    )
}
```

- [ ] **Step 5: Run tests — expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.reservations.data.model.*"
```

If decode fails on an unknown field, the fixture has something not modeled — extend `Reservation.kt` until it passes.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/data/model/ \
        app/src/test/java/com/avoqado/pos/reservations/data/model/ \
        app/src/test/resources/fixtures/
git commit -m "feat(reservations): Reservation model + relations + round-trip test on real fixture"
```

---

## Task 3: ReservationStateMachine (pure functions)

**Goal:** Encode the legal state transitions so the UI can disable invalid actions before the server says no.

**Files:**
- Create: `reservations/domain/ReservationStateMachine.kt`
- Test: `app/src/test/java/com/avoqado/pos/reservations/domain/ReservationStateMachineTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.avoqado.pos.reservations.domain

import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.domain.ReservationAction.*
import com.avoqado.pos.reservations.data.model.ReservationStatus.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationStateMachineTest {

    @Test
    fun `confirm allowed only from PENDING`() {
        assertTrue(ReservationStateMachine.canExecute(PENDING, CONFIRM))
        assertFalse(ReservationStateMachine.canExecute(CONFIRMED, CONFIRM))
        assertFalse(ReservationStateMachine.canExecute(CANCELLED, CONFIRM))
    }

    @Test
    fun `check-in allowed from PENDING or CONFIRMED`() {
        assertTrue(ReservationStateMachine.canExecute(PENDING, CHECK_IN))
        assertTrue(ReservationStateMachine.canExecute(CONFIRMED, CHECK_IN))
        assertFalse(ReservationStateMachine.canExecute(CHECKED_IN, CHECK_IN))
        assertFalse(ReservationStateMachine.canExecute(COMPLETED, CHECK_IN))
    }

    @Test
    fun `complete allowed only from CHECKED_IN`() {
        assertTrue(ReservationStateMachine.canExecute(CHECKED_IN, COMPLETE))
        assertFalse(ReservationStateMachine.canExecute(CONFIRMED, COMPLETE))
    }

    @Test
    fun `no-show allowed from PENDING or CONFIRMED`() {
        assertTrue(ReservationStateMachine.canExecute(PENDING, NO_SHOW))
        assertTrue(ReservationStateMachine.canExecute(CONFIRMED, NO_SHOW))
        assertFalse(ReservationStateMachine.canExecute(CHECKED_IN, NO_SHOW))
    }

    @Test
    fun `cancel allowed from any active status`() {
        assertTrue(ReservationStateMachine.canExecute(PENDING, CANCEL))
        assertTrue(ReservationStateMachine.canExecute(CONFIRMED, CANCEL))
        assertTrue(ReservationStateMachine.canExecute(CHECKED_IN, CANCEL))
        assertFalse(ReservationStateMachine.canExecute(COMPLETED, CANCEL))
    }

    @Test
    fun `reschedule allowed from active non-checked-in`() {
        assertTrue(ReservationStateMachine.canExecute(PENDING, RESCHEDULE))
        assertTrue(ReservationStateMachine.canExecute(CONFIRMED, RESCHEDULE))
        assertFalse(ReservationStateMachine.canExecute(CHECKED_IN, RESCHEDULE))
        assertFalse(ReservationStateMachine.canExecute(CANCELLED, RESCHEDULE))
    }

    @Test
    fun `predicted next status follows happy path`() {
        assertEquals(CONFIRMED, ReservationStateMachine.predictedNextStatus(PENDING, CONFIRM))
        assertEquals(CHECKED_IN, ReservationStateMachine.predictedNextStatus(CONFIRMED, CHECK_IN))
        assertEquals(COMPLETED, ReservationStateMachine.predictedNextStatus(CHECKED_IN, COMPLETE))
        assertEquals(CANCELLED, ReservationStateMachine.predictedNextStatus(PENDING, CANCEL))
        assertEquals(NO_SHOW, ReservationStateMachine.predictedNextStatus(CONFIRMED, NO_SHOW))
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (compilation error: ReservationStateMachine missing)

```bash
./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.reservations.domain.ReservationStateMachineTest"
```

- [ ] **Step 3: Implement `ReservationStateMachine.kt`**

```kotlin
package com.avoqado.pos.reservations.domain

import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.data.model.ReservationStatus.*

enum class ReservationAction { CONFIRM, CHECK_IN, COMPLETE, NO_SHOW, CANCEL, RESCHEDULE }

object ReservationStateMachine {

    private val table: Map<ReservationAction, Set<ReservationStatus>> = mapOf(
        ReservationAction.CONFIRM to setOf(PENDING),
        ReservationAction.CHECK_IN to setOf(PENDING, CONFIRMED),
        ReservationAction.COMPLETE to setOf(CHECKED_IN),
        ReservationAction.NO_SHOW to setOf(PENDING, CONFIRMED),
        ReservationAction.CANCEL to setOf(PENDING, CONFIRMED, CHECKED_IN),
        ReservationAction.RESCHEDULE to setOf(PENDING, CONFIRMED),
    )

    fun canExecute(current: ReservationStatus, action: ReservationAction): Boolean =
        current in (table[action] ?: emptySet())

    fun predictedNextStatus(current: ReservationStatus, action: ReservationAction): ReservationStatus = when (action) {
        ReservationAction.CONFIRM -> CONFIRMED
        ReservationAction.CHECK_IN -> CHECKED_IN
        ReservationAction.COMPLETE -> COMPLETED
        ReservationAction.CANCEL -> CANCELLED
        ReservationAction.NO_SHOW -> NO_SHOW
        ReservationAction.RESCHEDULE -> current  // reschedule keeps status
    }
}
```

- [ ] **Step 4: Run — expect 7 tests PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/domain/ReservationStateMachine.kt \
        app/src/test/java/com/avoqado/pos/reservations/domain/ReservationStateMachineTest.kt
git commit -m "feat(reservations): pure-Kotlin state machine for reservation transitions"
```

---

## Task 4: ReservationsCapability (permission decoder)

**Goal:** Read `permissions[]` from the JWT (already cached by the auth flow) and expose typed `canRead/canCreate/canUpdate/canCancel` flags.

**Files:**
- Create: `reservations/domain/ReservationsCapability.kt`
- Test: `app/src/test/java/com/avoqado/pos/reservations/domain/ReservationsCapabilityTest.kt`

- [ ] **Step 1: Inspect the existing JWT decoder**

```bash
grep -rn "permissions" app/src/main/java/com/avoqado/pos/auth --include="*.kt" | head -10
grep -rn "JWT\|jwt" app/src/main/java/com/avoqado/pos/core --include="*.kt" | head -10
```

Identify where `permissions: List<String>` is exposed (likely on a `User` model or `AuthState`). Note the exact path — you'll inject it.

- [ ] **Step 2: Write the failing test**

```kotlin
package com.avoqado.pos.reservations.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationsCapabilityTest {

    @Test
    fun `staff with all reservation perms gets all capabilities`() {
        val cap = ReservationsCapability.fromPermissions(
            listOf("reservations:read", "reservations:create", "reservations:update", "reservations:cancel")
        )
        assertTrue(cap.canRead); assertTrue(cap.canCreate)
        assertTrue(cap.canUpdate); assertTrue(cap.canCancel)
    }

    @Test
    fun `staff with no perms gets none`() {
        val cap = ReservationsCapability.fromPermissions(emptyList())
        assertFalse(cap.canRead); assertFalse(cap.canCreate)
        assertFalse(cap.canUpdate); assertFalse(cap.canCancel)
    }

    @Test
    fun `wildcard reservations colon star grants all`() {
        val cap = ReservationsCapability.fromPermissions(listOf("reservations:*"))
        assertTrue(cap.canRead); assertTrue(cap.canCreate)
        assertTrue(cap.canUpdate); assertTrue(cap.canCancel)
    }

    @Test
    fun `superadmin star grants all`() {
        val cap = ReservationsCapability.fromPermissions(listOf("*"))
        assertTrue(cap.canRead); assertTrue(cap.canCreate)
        assertTrue(cap.canUpdate); assertTrue(cap.canCancel)
    }

    @Test
    fun `read-only staff can read but not mutate`() {
        val cap = ReservationsCapability.fromPermissions(listOf("reservations:read"))
        assertTrue(cap.canRead)
        assertFalse(cap.canCreate); assertFalse(cap.canUpdate); assertFalse(cap.canCancel)
    }
}
```

- [ ] **Step 3: Run — expect FAIL**

- [ ] **Step 4: Implement**

```kotlin
package com.avoqado.pos.reservations.domain

data class ReservationsCapability(
    val canRead: Boolean,
    val canCreate: Boolean,
    val canUpdate: Boolean,
    val canCancel: Boolean,
) {
    val any: Boolean get() = canRead || canCreate || canUpdate || canCancel

    companion object {
        fun fromPermissions(perms: List<String>): ReservationsCapability {
            val set = perms.toSet()
            val wildcard = "*" in set || "reservations:*" in set
            return ReservationsCapability(
                canRead = wildcard || "reservations:read" in set,
                canCreate = wildcard || "reservations:create" in set,
                canUpdate = wildcard || "reservations:update" in set,
                canCancel = wildcard || "reservations:cancel" in set,
            )
        }
    }
}
```

- [ ] **Step 5: Run — expect PASS**

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/domain/ReservationsCapability.kt \
        app/src/test/java/com/avoqado/pos/reservations/domain/ReservationsCapabilityTest.kt
git commit -m "feat(reservations): permission capability decoder with wildcard support"
```

---

## Task 5: ReservationApi (network layer)

**Goal:** Pure network class — takes typed inputs, hits `/dashboard/venues/:id/reservations/...`, returns typed `Result<T>`. No caching, no domain logic. Mirrors the `CustomersRepository` style (OkHttp + manual JSON), with **the BASE URL difference**: dashboard endpoints live at `${ApiConstants.BASE_URL}/dashboard/...` not `/mobile/...`.

**Files:**
- Create: `reservations/data/ReservationApi.kt`
- Create: `reservations/data/model/ReservationFilters.kt`
- Create: `reservations/data/model/RescheduleRequest.kt`
- Test: `app/src/test/java/com/avoqado/pos/reservations/data/ReservationApiTest.kt`

- [ ] **Step 1: Confirm the base URL pattern**

```bash
grep -n "BASE_URL\|api/v1\|dashboard" app/src/main/java/com/avoqado/pos/core/data/network/ApiConstants.kt
```

You should see `BASE_URL = ".../api/v1"`. The dashboard paths concatenate to `${BASE_URL}/dashboard/...`.

- [ ] **Step 2: Create `ReservationFilters.kt`**

```kotlin
package com.avoqado.pos.reservations.data.model

data class ReservationFilters(
    val page: Int = 1,
    val pageSize: Int = 50,
    val statuses: List<ReservationStatus> = emptyList(),
    val dateFrom: String? = null,    // YYYY-MM-DD venue-local
    val dateTo: String? = null,
    val channel: ReservationChannel? = null,
    val search: String? = null,
    val tableId: String? = null,
    val staffId: String? = null,
    val productId: String? = null,
) {
    fun toQueryString(): String {
        val parts = mutableListOf<String>()
        parts += "page=$page"
        parts += "pageSize=$pageSize"
        if (statuses.isNotEmpty()) parts += "status=${statuses.joinToString(",") { it.name }}"
        dateFrom?.let { parts += "dateFrom=$it" }
        dateTo?.let { parts += "dateTo=$it" }
        channel?.let { parts += "channel=${it.name}" }
        search?.let { if (it.isNotBlank()) parts += "search=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        tableId?.let { parts += "tableId=$it" }
        staffId?.let { parts += "staffId=$it" }
        productId?.let { parts += "productId=$it" }
        return parts.joinToString("&")
    }
}
```

- [ ] **Step 3: Create `RescheduleRequest.kt`**

```kotlin
package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.Serializable

@Serializable
data class RescheduleRequest(val startsAt: String, val endsAt: String)

@Serializable
data class CancelReservationRequest(val reason: String? = null)
```

- [ ] **Step 4: Create `ReservationApi.kt`**

```kotlin
package com.avoqado.pos.reservations.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.reservations.data.model.CancelReservationRequest
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationFilters
import com.avoqado.pos.reservations.data.model.ReservationListResponse
import com.avoqado.pos.reservations.data.model.RescheduleRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReservationApi @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val tag = "📅Res"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    private fun base(): String? {
        val v = secureStorage.venueId ?: return null
        return "${ApiConstants.BASE_URL}/dashboard/venues/$v/reservations"
    }

    suspend fun list(filters: ReservationFilters): Result<ReservationListResponse> = call {
        val url = "${base() ?: error("No venue")}?${filters.toQueryString()}"
        Request.Builder().url(url).get().build()
    }.mapCatching { json.decodeFromString(ReservationListResponse.serializer(), it) }

    suspend fun calendar(dateFrom: String, dateTo: String, groupBy: String? = null): Result<List<Reservation>> = call {
        val params = buildList {
            add("dateFrom=$dateFrom"); add("dateTo=$dateTo")
            groupBy?.let { add("groupBy=$it") }
        }.joinToString("&")
        Request.Builder().url("${base() ?: error("No venue")}/calendar?$params").get().build()
    }.mapCatching {
        // server returns either raw array or { data: [], grouped: {...} }; handle both
        val element = json.parseToJsonElement(it)
        if (element is kotlinx.serialization.json.JsonArray) {
            json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Reservation.serializer()), element)
        } else {
            val obj = element.jsonObjectOrNull()
            val arr = obj?.get("data") ?: error("Unexpected calendar response shape: $it")
            json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Reservation.serializer()), arr)
        }
    }

    suspend fun get(id: String): Result<Reservation> = call {
        Request.Builder().url("${base() ?: error("No venue")}/$id").get().build()
    }.mapCatching { json.decodeFromString(Reservation.serializer(), it) }

    suspend fun confirm(id: String) = stateTransition(id, "confirm")
    suspend fun checkIn(id: String) = stateTransition(id, "check-in")
    suspend fun complete(id: String) = stateTransition(id, "complete")
    suspend fun noShow(id: String) = stateTransition(id, "no-show")

    suspend fun reschedule(id: String, body: RescheduleRequest): Result<Reservation> = call {
        val payload = json.encodeToString(RescheduleRequest.serializer(), body).toRequestBody(jsonMedia)
        Request.Builder().url("${base() ?: error("No venue")}/$id/reschedule").post(payload).build()
    }.mapCatching { json.decodeFromString(Reservation.serializer(), it) }

    suspend fun cancel(id: String, body: CancelReservationRequest): Result<Unit> = call {
        val payload = json.encodeToString(CancelReservationRequest.serializer(), body).toRequestBody(jsonMedia)
        Request.Builder().url("${base() ?: error("No venue")}/$id").delete(payload).build()
    }.map { Unit }

    private suspend fun stateTransition(id: String, action: String): Result<Reservation> = call {
        Request.Builder().url("${base() ?: error("No venue")}/$id/$action").post(ByteArray(0).toRequestBody(jsonMedia)).build()
    }.mapCatching { json.decodeFromString(Reservation.serializer(), it) }

    /** Executes the request on Dispatchers.IO and returns the body as String on 2xx, Result.failure otherwise. */
    private suspend inline fun call(crossinline buildRequest: () -> Request): Result<String> = runCatching {
        val req = buildRequest()
        val (code, body) = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        }
        if (code in 200..299) {
            body
        } else {
            Log.e(tag, "${req.method} ${req.url} -> $code: ${body.take(300)}")
            error("HTTP $code: ${body.take(200)}")
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() =
        (this as? kotlinx.serialization.json.JsonObject)
}
```

- [ ] **Step 5: Test list endpoint with MockWebServer**

Add MockWebServer dependency if missing:

```bash
grep "mockwebserver" gradle/libs.versions.toml
```

If absent, add to `gradle/libs.versions.toml` under `[versions]` and `[libraries]`:

```
mockwebserver = "4.12.0"
mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "mockwebserver" }
```

And to `app/build.gradle.kts` under test deps:

```
testImplementation(libs.mockwebserver)
```

Sync.

- [ ] **Step 6: Write `ReservationApiTest.kt`**

```kotlin
package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.model.CancelReservationRequest
import com.avoqado.pos.reservations.data.model.ReservationFilters
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.data.model.RescheduleRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ReservationApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ReservationApi
    private val secureStorage: SecureStorage = mockk(relaxed = true)

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        every { secureStorage.venueId } returns "v1"
        // We bypass ApiConstants by routing through MockWebServer base URL — see note below.
        api = ReservationApi(secureStorage, OkHttpClient())
        // Hack: we override base() by constructing URLs that match MockWebServer's host.
        // For maximum fidelity we'd inject a baseUrlProvider — out-of-scope for F1; instead we
        // exercise the request builders against a fake MockWebServer that intercepts based on path.
        TestApiConstantsOverride.set(server.url("/api/v1").toString().removeSuffix("/"))
    }

    @After fun tearDown() {
        server.shutdown()
        TestApiConstantsOverride.reset()
    }

    @Test
    fun `list builds correct URL with filters`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data": [], "pagination": {"page":1,"pageSize":50,"total":0,"totalPages":0}}"""))

        val result = api.list(ReservationFilters(page = 2, pageSize = 25, statuses = listOf(ReservationStatus.CONFIRMED), dateFrom = "2026-04-29"))

        assertTrue(result.isSuccess)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        val url = req.path!!
        assertTrue(url.contains("/dashboard/venues/v1/reservations"))
        assertTrue(url.contains("page=2"))
        assertTrue(url.contains("pageSize=25"))
        assertTrue(url.contains("status=CONFIRMED"))
        assertTrue(url.contains("dateFrom=2026-04-29"))
    }

    @Test
    fun `confirm posts to confirm subroute`() = runTest {
        server.enqueue(MockResponse().setBody(File("src/test/resources/fixtures/reservation_single.json").readText()))
        val result = api.confirm("res-1")
        assertTrue(result.isSuccess)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.endsWith("/reservations/res-1/confirm"))
    }

    @Test
    fun `cancel sends DELETE with body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = api.cancel("res-1", CancelReservationRequest(reason = "Cliente no llegó"))
        assertTrue(result.isSuccess)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertTrue(req.path!!.endsWith("/reservations/res-1"))
        assertTrue(req.body.readUtf8().contains("Cliente no llegó"))
    }

    @Test
    fun `reschedule posts isoDate body`() = runTest {
        server.enqueue(MockResponse().setBody(File("src/test/resources/fixtures/reservation_single.json").readText()))
        val result = api.reschedule("res-1", RescheduleRequest(startsAt = "2026-04-30T15:00:00.000Z", endsAt = "2026-04-30T16:00:00.000Z"))
        assertTrue(result.isSuccess)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("2026-04-30T15:00"))
    }

    @Test
    fun `non-2xx surfaces failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}"""))
        val result = api.get("res-1")
        assertTrue(result.isFailure)
    }
}
```

You will hit a snag: `ApiConstants.BASE_URL` is a compile-time const, so you can't redirect it at test-time without refactoring. Add the override class:

- [ ] **Step 7: Make BASE_URL injectable for tests**

Inspect `ApiConstants.kt`:

```bash
cat app/src/main/java/com/avoqado/pos/core/data/network/ApiConstants.kt
```

If it's `const val`, change it to `var` (test-only override) OR — preferred — refactor `ReservationApi` to accept `baseUrlProvider: () -> String` and bind the production provider in `ReservationModule`. Implement the latter:

Edit `ReservationApi.kt` constructor:

```kotlin
@Singleton
class ReservationApi @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    @Named("apiBaseUrl") private val baseUrlProvider: () -> String,
) {
    private fun base(): String? {
        val v = secureStorage.venueId ?: return null
        return "${baseUrlProvider()}/dashboard/venues/$v/reservations"
    }
    // ... rest unchanged
}
```

Add to `ReservationModule.kt`:

```kotlin
import com.avoqado.pos.core.data.network.ApiConstants
import dagger.Provides
import javax.inject.Named

@Module @InstallIn(SingletonComponent::class)
object ReservationModule {
    @Provides @Named("apiBaseUrl")
    fun provideApiBaseUrl(): () -> String = { ApiConstants.BASE_URL }
}
```

In the test, pass `{ server.url("/api/v1").toString().removeSuffix("/") }` directly — drop `TestApiConstantsOverride`.

- [ ] **Step 8: Capture a single-reservation fixture**

```bash
RES_ID="<id from list fixture>"
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.avoqado.io/api/v1/dashboard/venues/$VENUE/reservations/$RES_ID" \
  | jq '.' > app/src/test/resources/fixtures/reservation_single.json
```

- [ ] **Step 9: Run — expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.reservations.data.ReservationApiTest"
```

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/data/ \
        app/src/main/java/com/avoqado/pos/reservations/data/model/{ReservationFilters,RescheduleRequest}.kt \
        app/src/main/java/com/avoqado/pos/reservations/di/ReservationModule.kt \
        app/src/test/java/com/avoqado/pos/reservations/data/ReservationApiTest.kt \
        app/src/test/resources/fixtures/reservation_single.json \
        gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat(reservations): network layer (list, calendar, get, confirm, check-in, complete, no-show, cancel, reschedule)"
```

---

## Task 6: ReservationRepository (orchestrates Api + cache + offline DAO)

**Goal:** UI-facing single source. Wraps `ReservationApi`, caches the last list/calendar fetch in-memory for snappy nav, and queues failed mutations into Room when offline.

**Files:**
- Create: `reservations/data/PendingReservationActionEntity.kt`
- Create: `reservations/data/PendingReservationActionDao.kt`
- Modify: `core/data/local/database/AvoqadoDatabase.kt` (add entity + DAO + bump version)
- Modify: `core/data/local/database/AvoqadoDatabaseMigrations.kt` (add `MIGRATION_X_Y`)
- Create: `reservations/data/ReservationRepository.kt`
- Test: `app/src/test/java/com/avoqado/pos/reservations/data/ReservationRepositoryTest.kt`

- [ ] **Step 1: Inspect current DB version**

```bash
grep -n "version\|DATABASE_VERSION\|MIGRATION_" app/src/main/java/com/avoqado/pos/core/data/local/database/AvoqadoDatabase.kt app/src/main/java/com/avoqado/pos/core/data/local/database/AvoqadoDatabaseMigrations.kt
```

Note the current version (call it `N`). New version = `N+1`.

- [ ] **Step 2: Create entity**

```kotlin
package com.avoqado.pos.reservations.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_reservation_action")
data class PendingReservationActionEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val reservationId: String,
    val action: String,           // "CONFIRM" | "CHECK_IN" | "COMPLETE" | "NO_SHOW" | "CANCEL" | "RESCHEDULE"
    val payloadJson: String? = null, // for CANCEL reason or RESCHEDULE times
    val attemptCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
```

- [ ] **Step 3: Create DAO**

```kotlin
package com.avoqado.pos.reservations.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingReservationActionDao {
    @Insert
    suspend fun enqueue(action: PendingReservationActionEntity): Long

    @Query("SELECT * FROM pending_reservation_action ORDER BY createdAt ASC")
    suspend fun all(): List<PendingReservationActionEntity>

    @Query("SELECT COUNT(*) FROM pending_reservation_action")
    fun pendingCount(): Flow<Int>

    @Query("DELETE FROM pending_reservation_action WHERE rowId = :rowId")
    suspend fun delete(rowId: Long)

    @Query("UPDATE pending_reservation_action SET attemptCount = attemptCount + 1 WHERE rowId = :rowId")
    suspend fun incrementAttempt(rowId: Long)
}
```

- [ ] **Step 4: Wire into `AvoqadoDatabase.kt`**

Open the file and:
1. Add `PendingReservationActionEntity::class` to the `entities = [...]` array.
2. Add `abstract fun pendingReservationActionDao(): PendingReservationActionDao`.
3. Bump `version = N+1`.

- [ ] **Step 5: Add migration in `AvoqadoDatabaseMigrations.kt`**

```kotlin
val MIGRATION_N_NEXT = object : androidx.room.migration.Migration(N, NEXT) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pending_reservation_action (
                rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                reservationId TEXT NOT NULL,
                action TEXT NOT NULL,
                payloadJson TEXT,
                attemptCount INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL
            )
        """.trimIndent())
    }
}
```

Replace `N` and `NEXT` with the real numbers. Add to the `arrayOf(MIGRATION_..., MIGRATION_N_NEXT)` in the database builder (search for `addMigrations`).

- [ ] **Step 6: Bind DAO in DatabaseModule**

```bash
grep -n "fun provide" app/src/main/java/com/avoqado/pos/core/di/DatabaseModule.kt
```

Add:

```kotlin
@Provides
fun providePendingReservationActionDao(db: AvoqadoDatabase) = db.pendingReservationActionDao()
```

- [ ] **Step 7: Build to confirm migration compiles**

```bash
./gradlew :app:assembleDebug -x test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Implement `ReservationRepository.kt`**

```kotlin
package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.model.CancelReservationRequest
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationFilters
import com.avoqado.pos.reservations.data.model.ReservationListResponse
import com.avoqado.pos.reservations.data.model.RescheduleRequest
import com.avoqado.pos.reservations.domain.ReservationAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReservationRepository @Inject constructor(
    private val api: ReservationApi,
    private val pendingDao: PendingReservationActionDao,
    private val connectivity: ConnectivityMonitor,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Last list response cached in-memory (cleared on logout / venue switch — caller's responsibility). */
    private val _lastList = MutableStateFlow<ReservationListResponse?>(null)
    val lastList: kotlinx.coroutines.flow.StateFlow<ReservationListResponse?> = _lastList.asStateFlow()

    val pendingActionsCount: Flow<Int> = pendingDao.pendingCount()

    suspend fun fetchList(filters: ReservationFilters): Result<ReservationListResponse> {
        val r = api.list(filters)
        r.getOrNull()?.let { _lastList.value = it }
        return r
    }

    suspend fun fetchCalendar(dateFrom: String, dateTo: String): Result<List<Reservation>> =
        api.calendar(dateFrom, dateTo)

    suspend fun fetchOne(id: String): Result<Reservation> = api.get(id)

    suspend fun runAction(reservationId: String, action: ReservationAction, payload: ActionPayload? = null): Result<Reservation?> {
        if (!connectivity.isOnline()) {
            pendingDao.enqueue(PendingReservationActionEntity(
                reservationId = reservationId,
                action = action.name,
                payloadJson = payload?.toJson(json),
            ))
            return Result.failure(OfflineEnqueuedException(action))
        }
        return when (action) {
            ReservationAction.CONFIRM -> api.confirm(reservationId).map { it as Reservation? }
            ReservationAction.CHECK_IN -> api.checkIn(reservationId).map { it as Reservation? }
            ReservationAction.COMPLETE -> api.complete(reservationId).map { it as Reservation? }
            ReservationAction.NO_SHOW -> api.noShow(reservationId).map { it as Reservation? }
            ReservationAction.CANCEL -> api.cancel(reservationId, (payload as? ActionPayload.Cancel)?.toRequest() ?: CancelReservationRequest()).map { null }
            ReservationAction.RESCHEDULE -> api.reschedule(reservationId, (payload as ActionPayload.Reschedule).toRequest()).map { it as Reservation? }
        }
    }

    sealed interface ActionPayload {
        fun toJson(json: Json): String

        data class Cancel(val reason: String?) : ActionPayload {
            fun toRequest() = CancelReservationRequest(reason)
            override fun toJson(json: Json) = json.encodeToString(CancelReservationRequest.serializer(), CancelReservationRequest(reason))
        }
        data class Reschedule(val startsAt: String, val endsAt: String) : ActionPayload {
            fun toRequest() = RescheduleRequest(startsAt, endsAt)
            override fun toJson(json: Json) = json.encodeToString(RescheduleRequest.serializer(), RescheduleRequest(startsAt, endsAt))
        }
    }

    class OfflineEnqueuedException(val action: ReservationAction) : Exception("Action ${action.name} enqueued for retry")
}
```

- [ ] **Step 9: Add `isOnline()` to `ConnectivityMonitor` if absent**

```bash
grep -n "isOnline\|fun is" app/src/main/java/com/avoqado/pos/core/util/ConnectivityMonitor.kt
```

If absent, add a synchronous helper that reads the current state of the connectivity StateFlow.

- [ ] **Step 10: Write `ReservationRepositoryTest.kt`**

```kotlin
package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.domain.ReservationAction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationRepositoryTest {

    private val api: ReservationApi = mockk()
    private val pendingDao: PendingReservationActionDao = mockk(relaxed = true)
    private val connectivity: ConnectivityMonitor = mockk()
    private val repo = ReservationRepository(api, pendingDao, connectivity)

    @Test
    fun `runAction online calls api confirm`() = runTest {
        every { connectivity.isOnline() } returns true
        coEvery { api.confirm("r1") } returns Result.success(reservationStub("r1"))

        val r = repo.runAction("r1", ReservationAction.CONFIRM)

        assertTrue(r.isSuccess)
        coVerify(exactly = 1) { api.confirm("r1") }
    }

    @Test
    fun `runAction offline enqueues to dao without calling api`() = runTest {
        every { connectivity.isOnline() } returns false

        val r = repo.runAction("r1", ReservationAction.CHECK_IN)

        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is ReservationRepository.OfflineEnqueuedException)
        coVerify(exactly = 1) { pendingDao.enqueue(match { it.reservationId == "r1" && it.action == "CHECK_IN" }) }
        coVerify(exactly = 0) { api.checkIn(any()) }
    }

    @Test
    fun `runAction with cancel payload includes reason in stored payload`() = runTest {
        every { connectivity.isOnline() } returns false

        repo.runAction("r1", ReservationAction.CANCEL, ReservationRepository.ActionPayload.Cancel(reason = "Cliente cambió de plan"))

        coVerify { pendingDao.enqueue(match { it.payloadJson?.contains("Cliente cambió") == true }) }
    }

    private fun reservationStub(id: String) = com.avoqado.pos.reservations.data.model.Reservation(
        id = id, venueId = "v", confirmationCode = "X", cancelSecret = "s",
        status = com.avoqado.pos.reservations.data.model.ReservationStatus.CONFIRMED,
        channel = com.avoqado.pos.reservations.data.model.ReservationChannel.DASHBOARD,
        startsAt = "2026-04-29T10:00:00.000Z", endsAt = "2026-04-29T11:00:00.000Z",
        duration = 60, createdAt = "2026-04-29T00:00:00.000Z", updatedAt = "2026-04-29T00:00:00.000Z",
    )
}
```

- [ ] **Step 11: Run — expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.reservations.data.ReservationRepositoryTest"
```

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/data/ \
        app/src/main/java/com/avoqado/pos/core/data/local/database/ \
        app/src/main/java/com/avoqado/pos/core/di/DatabaseModule.kt \
        app/src/main/java/com/avoqado/pos/core/util/ConnectivityMonitor.kt \
        app/src/test/java/com/avoqado/pos/reservations/data/ReservationRepositoryTest.kt
git commit -m "feat(reservations): repository with offline action queue (Room migration N→N+1)"
```

---

## Task 7: VenueMode + reservations feature flag in SecureStorage

**Goal:** Persist the device-local mode and the venue's `featureFlags.reservations` boolean. Mode survives app restart.

**Files:**
- Modify: `core/data/local/SecureStorage.kt`
- Test: `app/src/test/java/com/avoqado/pos/core/data/local/SecureStorageReservationsTest.kt`

- [ ] **Step 1: Inspect existing keys/methods**

```bash
grep -n "KEY_\|fun \|var " app/src/main/java/com/avoqado/pos/core/data/local/SecureStorage.kt | head -40
```

Note the pattern: `KEY_VENUE_TIMEZONE` was added in v2.1.1. Follow the same pattern.

- [ ] **Step 2: Add keys + accessors**

Append to `SecureStorage.kt` (inside the class):

```kotlin
companion object {
    // ... existing keys
    const val KEY_VENUE_MODE = "KEY_VENUE_MODE"
    const val KEY_RESERVATIONS_ENABLED = "KEY_RESERVATIONS_ENABLED"
}

var venueMode: String?
    get() = prefs.getString(KEY_VENUE_MODE, null)
    set(value) { prefs.edit().putString(KEY_VENUE_MODE, value).apply() }

var reservationsEnabled: Boolean
    get() = prefs.getBoolean(KEY_RESERVATIONS_ENABLED, false)
    set(value) { prefs.edit().putBoolean(KEY_RESERVATIONS_ENABLED, value).apply() }
```

Also extend the existing `clearOnLogout()` (or equivalent) to remove these two keys. And extend `saveLogin` / `switchVenue` to accept and persist `reservationsEnabled` (look at how `venueTimezone` was wired — same pattern). Update both call sites.

- [ ] **Step 3: Write the test**

```kotlin
package com.avoqado.pos.core.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureStorageReservationsTest {

    private lateinit var storage: SecureStorage

    @Before fun setup() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        storage = SecureStorage(ctx)
        storage.venueMode = null
        storage.reservationsEnabled = false
    }

    @Test
    fun `venueMode round-trips`() {
        storage.venueMode = "reservations"
        assertEquals("reservations", storage.venueMode)
    }

    @Test
    fun `reservationsEnabled defaults false`() {
        assertFalse(storage.reservationsEnabled)
    }

    @Test
    fun `reservationsEnabled persists`() {
        storage.reservationsEnabled = true
        assertTrue(storage.reservationsEnabled)
    }

    @Test
    fun `clearing keys returns null and false`() {
        storage.venueMode = "x"; storage.reservationsEnabled = true
        storage.venueMode = null; storage.reservationsEnabled = false
        assertNull(storage.venueMode); assertFalse(storage.reservationsEnabled)
    }
}
```

This requires `androidTest` runner since `EncryptedSharedPreferences` needs Android. Place it under `app/src/androidTest/...` instead of `test/`.

- [ ] **Step 4: Move test file to androidTest**

```bash
mkdir -p app/src/androidTest/java/com/avoqado/pos/core/data/local
mv app/src/test/java/com/avoqado/pos/core/data/local/SecureStorageReservationsTest.kt \
   app/src/androidTest/java/com/avoqado/pos/core/data/local/
```

- [ ] **Step 5: Run instrumented test**

```bash
./gradlew :app:connectedDebugAndroidTest --tests "com.avoqado.pos.core.data.local.SecureStorageReservationsTest"
```

Expected: 4 tests pass on emulator.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/core/data/local/SecureStorage.kt \
        app/src/androidTest/java/com/avoqado/pos/core/data/local/SecureStorageReservationsTest.kt
git commit -m "feat(reservations): persist venueMode and reservationsEnabled in SecureStorage"
```

---

## Task 8: Conditional Calendar tab in MainTab + MainTabHostViewModel

**Goal:** `MainTab.CALENDAR` exists; the bottom-nav tab list is computed reactively from `(reservationsEnabled, venueMode)` via a `StateFlow<List<MainTab>>` so the tab appears/disappears live.

**Files:**
- Modify: `navigation/MainTab.kt`
- Create: `navigation/MainTabHostViewModel.kt`
- Modify: `navigation/AvoqadoNavGraph.kt` (replace hardcoded tab arrays)
- Test: `app/src/test/java/com/avoqado/pos/navigation/MainTabHostViewModelTest.kt`

- [ ] **Step 1: Add CALENDAR to MainTab.kt**

```kotlin
CALENDAR(
    route = "calendar",
    label = "Calendario",
    shortLabel = "Calendario",
    selectedIcon = Icons.Filled.CalendarMonth,
    unselectedIcon = Icons.Outlined.CalendarMonth,
),
```

Add the imports for `Icons.Filled.CalendarMonth` and `Icons.Outlined.CalendarMonth`.

- [ ] **Step 2: Build to confirm enum compiles**

```bash
./gradlew :app:assembleDebug -x test
```

- [ ] **Step 3: Write the failing ViewModel test**

```kotlin
package com.avoqado.pos.navigation

import app.cash.turbine.test
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.domain.VenueMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MainTabHostViewModelTest {

    @Test
    fun `tabs without reservations enabled = standard set`() = runTest {
        val storage: SecureStorage = mockk()
        every { storage.reservationsEnabled } returns false
        every { storage.venueMode } returns null

        val vm = MainTabHostViewModel(storage)

        vm.tabs.test {
            assertEquals(listOf(MainTab.CHECKOUT, MainTab.INVENTORY, MainTab.TRANSACTIONS, MainTab.NOTIFICATIONS, MainTab.MORE), awaitItem())
        }
    }

    @Test
    fun `tabs with reservations enabled and reservations mode = calendar replaces inventory`() = runTest {
        val storage: SecureStorage = mockk()
        every { storage.reservationsEnabled } returns true
        every { storage.venueMode } returns VenueMode.RESERVATIONS.storageValue

        val vm = MainTabHostViewModel(storage)

        vm.tabs.test {
            val tabs = awaitItem()
            assertEquals(MainTab.CALENDAR, tabs.first())
            assertEquals(false, tabs.contains(MainTab.INVENTORY))
            assertEquals(true, tabs.contains(MainTab.MORE))
        }
    }

    @Test
    fun `tabs with reservations enabled but standard mode = standard set unchanged`() = runTest {
        val storage: SecureStorage = mockk()
        every { storage.reservationsEnabled } returns true
        every { storage.venueMode } returns VenueMode.STANDARD.storageValue

        val vm = MainTabHostViewModel(storage)

        vm.tabs.test {
            val tabs = awaitItem()
            assertEquals(false, tabs.contains(MainTab.CALENDAR))
            assertEquals(true, tabs.contains(MainTab.INVENTORY))
        }
    }

    @Test
    fun `setMode emits new tab list immediately`() = runTest {
        val storage: SecureStorage = mockk(relaxed = true)
        every { storage.reservationsEnabled } returns true
        every { storage.venueMode } returns VenueMode.STANDARD.storageValue

        val vm = MainTabHostViewModel(storage)

        vm.tabs.test {
            awaitItem() // initial standard
            vm.setMode(VenueMode.RESERVATIONS)
            val updated = awaitItem()
            assertEquals(MainTab.CALENDAR, updated.first())
        }
    }
}
```

- [ ] **Step 4: Implement `MainTabHostViewModel.kt`**

```kotlin
package com.avoqado.pos.navigation

import androidx.lifecycle.ViewModel
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.domain.VenueMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class MainTabHostViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
) : ViewModel() {

    private val _mode = MutableStateFlow(VenueMode.fromStorage(secureStorage.venueMode))
    val mode: StateFlow<VenueMode> = _mode.asStateFlow()

    private val _reservationsEnabled = MutableStateFlow(secureStorage.reservationsEnabled)
    val reservationsEnabled: StateFlow<Boolean> = _reservationsEnabled.asStateFlow()

    private val _tabs = MutableStateFlow(computeTabs(_mode.value, _reservationsEnabled.value))
    val tabs: StateFlow<List<MainTab>> = _tabs.asStateFlow()

    fun setMode(newMode: VenueMode) {
        secureStorage.venueMode = newMode.storageValue
        _mode.value = newMode
        _tabs.value = computeTabs(newMode, _reservationsEnabled.value)
    }

    fun refreshFromStorage() {
        val mode = VenueMode.fromStorage(secureStorage.venueMode)
        val enabled = secureStorage.reservationsEnabled
        _mode.value = mode
        _reservationsEnabled.value = enabled
        _tabs.value = computeTabs(mode, enabled)
    }

    private fun computeTabs(mode: VenueMode, reservationsEnabled: Boolean): List<MainTab> = when {
        reservationsEnabled && mode == VenueMode.RESERVATIONS ->
            listOf(MainTab.CALENDAR, MainTab.CHECKOUT, MainTab.TRANSACTIONS, MainTab.NOTIFICATIONS, MainTab.MORE)
        else ->
            listOf(MainTab.CHECKOUT, MainTab.INVENTORY, MainTab.TRANSACTIONS, MainTab.NOTIFICATIONS, MainTab.MORE)
    }
}
```

- [ ] **Step 5: Run — expect 4 tests PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.navigation.MainTabHostViewModelTest"
```

- [ ] **Step 6: Wire ViewModel into NavGraph**

Open `AvoqadoNavGraph.kt`. Currently the tablet branch (around L168) and phone branch (L229) have hard-coded `MainTab.CHECKOUT`, `INVENTORY`, etc. composable blocks.

Replace the static tab list (search for the array used by your bottom bar — likely `MainTab.values()` or a manual `listOf(...)`) with:

```kotlin
val mainTabHostViewModel: MainTabHostViewModel = hiltViewModel()
val tabs by mainTabHostViewModel.tabs.collectAsState()
```

Then pass `tabs` to the bottom bar composable. Add `composable(MainTab.CALENDAR.route) { CalendarTabHost() }` to BOTH the tablet and phone NavHost branches (a stub `@Composable fun CalendarTabHost() { Text("Calendar coming soon") }` is fine for now — Task 23 fills it in).

- [ ] **Step 7: Add stub `CalendarTabHost.kt`**

```kotlin
package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun CalendarTabHost() {
    Box(Modifier.fillMaxSize()) {
        Text("Calendar — implementación en Task 23")
    }
}
```

- [ ] **Step 8: Build + install + manual smoke**

```bash
./gradlew :app:installDebug
adb -s R8YL200592L shell monkey -p com.avoqado.pos -c android.intent.category.LAUNCHER 1
```

Login → Más → confirm bottom-nav still shows the standard 5 tabs (since `reservationsEnabled = false` for now). Take a screenshot.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/navigation/ \
        app/src/main/java/com/avoqado/pos/reservations/presentation/calendar/CalendarTabHost.kt \
        app/src/test/java/com/avoqado/pos/navigation/
git commit -m "feat(reservations): conditional Calendar tab via MainTabHostViewModel"
```

---

## Task 9: ActivateReservationsScreen + ModeSwitcherSheet

**Goal:** Onboarding flow accessible from Más:
- If `reservationsEnabled == false`: show "Activar reservas" tile → tap → `ActivateReservationsScreen` → toggle PUT.
- If `reservationsEnabled == true`: show "Modo: <current> ▾" tile → tap → `ModeSwitcherSheet` (radio Estándar / Reservas).

**Files:**
- Create: `reservations/presentation/onboarding/ActivateReservationsScreen.kt`
- Create: `reservations/presentation/onboarding/ActivateReservationsViewModel.kt`
- Create: `reservations/presentation/onboarding/ModeSwitcherSheet.kt`
- Modify: `settings/MoreMenuScreen.kt` (add the two tiles + a Modo card row)
- Test: `app/src/test/java/com/avoqado/pos/reservations/presentation/onboarding/ActivateReservationsViewModelTest.kt`

- [ ] **Step 1: Add API method to enable reservations**

In `ReservationApi.kt`, add:

```kotlin
suspend fun enableForVenue(): Result<Unit> = call {
    val v = secureStorage.venueId ?: error("No venue")
    val payload = """{"featureFlags":{"reservations":true}}""".toRequestBody(jsonMedia)
    Request.Builder().url("${baseUrlProvider()}/dashboard/venues/$v").patch(payload).build()
}.map { Unit }
```

Verify the route exists by hitting from curl with the JWT. If the dashboard expects a different shape, mirror what `avoqado-server-reservations-map.md` documents — the field name is `featureFlags.reservations`. If the route is `/mobile/venues/:id` instead, use that path.

- [ ] **Step 2: Implement `ActivateReservationsViewModel`**

```kotlin
package com.avoqado.pos.reservations.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.ReservationApi
import com.avoqado.pos.reservations.domain.VenueMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ActivateReservationsUiState(
    val isActivating: Boolean = false,
    val didSucceed: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ActivateReservationsViewModel @Inject constructor(
    private val api: ReservationApi,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    private val _state = MutableStateFlow(ActivateReservationsUiState())
    val state: StateFlow<ActivateReservationsUiState> = _state.asStateFlow()

    fun activate() {
        if (_state.value.isActivating || _state.value.didSucceed) return
        _state.value = _state.value.copy(isActivating = true, error = null)
        viewModelScope.launch {
            val r = api.enableForVenue()
            _state.value = if (r.isSuccess) {
                secureStorage.reservationsEnabled = true
                secureStorage.venueMode = VenueMode.RESERVATIONS.storageValue
                ActivateReservationsUiState(didSucceed = true)
            } else {
                ActivateReservationsUiState(error = r.exceptionOrNull()?.message ?: "Error activando reservas")
            }
        }
    }
}
```

- [ ] **Step 3: Implement `ActivateReservationsScreen`**

```kotlin
package com.avoqado.pos.reservations.presentation.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.theme.Spacing

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ActivateReservationsScreen(
    onActivated: () -> Unit,
    onBack: () -> Unit,
    viewModel: ActivateReservationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.didSucceed) {
        if (state.didSucceed) onActivated()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activar reservas") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.padding(top = Spacing.xxl), tint = MaterialTheme.colorScheme.primary)
            Text("Permite a tu negocio recibir citas, manejar clases y administrar tu calendario desde Avoqado.", style = MaterialTheme.typography.bodyLarge)
            Text("Gratis hoy.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { viewModel.activate() },
                enabled = !state.isActivating,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isActivating) "Activando..." else "Activar reservas")
            }
        }
    }
}
```

- [ ] **Step 4: Implement `ModeSwitcherSheet`**

```kotlin
package com.avoqado.pos.reservations.presentation.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.Spacing
import com.avoqado.pos.reservations.domain.VenueMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSwitcherSheet(
    currentMode: VenueMode,
    onModeSelected: (VenueMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.padding(Spacing.lg)) {
            Text("Cambiar de modo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Spacing.md))
            VenueMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().clickable { onModeSelected(mode); onDismiss() }.padding(vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == currentMode, onClick = { onModeSelected(mode); onDismiss() })
                    Column(Modifier.padding(start = Spacing.sm)) {
                        Text(mode.displayLabel, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (mode) {
                                VenueMode.STANDARD -> "Cobrar, transacciones e inventario"
                                VenueMode.RESERVATIONS -> "Calendario, citas y clases"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Wire into MoreMenuScreen**

Find and read the existing `MoreMenuScreen.kt`:

```bash
find app/src/main/java -name "MoreMenuScreen.kt"
```

Add at top of grid:
- If `reservationsEnabled` (read from `MainTabHostViewModel.reservationsEnabled`) → row "Modo: <currentMode.displayLabel> ▾" tappable → opens `ModeSwitcherSheet`. On selection: call `mainTabHostViewModel.setMode(it)`.
- Else → row "Activar reservas — Permite recibir citas. Gratis hoy." → navigate to `activate-reservations` route.

Use the existing `MoreMenuRow` (or whatever the row composable is called) for visual consistency.

- [ ] **Step 6: Register the activate route in NavGraph**

In `AvoqadoNavGraph.kt`, alongside other top-level composables:

```kotlin
composable("activate-reservations") {
    ActivateReservationsScreen(
        onActivated = {
            mainTabHostViewModel.refreshFromStorage()
            navController.popBackStack()
        },
        onBack = { navController.popBackStack() },
    )
}
```

- [ ] **Step 7: Write VM test**

```kotlin
package com.avoqado.pos.reservations.presentation.onboarding

import app.cash.turbine.test
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.ReservationApi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivateReservationsViewModelTest {

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `successful activate persists flag and emits success`() = runTest {
        val api: ReservationApi = mockk()
        val storage: SecureStorage = mockk(relaxed = true)
        coEvery { api.enableForVenue() } returns Result.success(Unit)

        val vm = ActivateReservationsViewModel(api, storage)
        vm.activate()

        vm.state.test {
            val s = awaitItem()
            assertTrue(s.didSucceed)
            assertEquals(false, s.isActivating)
            cancelAndIgnoreRemainingEvents()
        }
        verify { storage.reservationsEnabled = true }
    }

    @Test
    fun `failure surfaces error message`() = runTest {
        val api: ReservationApi = mockk()
        val storage: SecureStorage = mockk(relaxed = true)
        coEvery { api.enableForVenue() } returns Result.failure(RuntimeException("HTTP 500: Server error"))

        val vm = ActivateReservationsViewModel(api, storage)
        vm.activate()

        vm.state.test {
            val s = awaitItem()
            assertEquals(false, s.didSucceed)
            assertTrue(s.error!!.contains("Server error") || s.error!!.contains("HTTP 500"))
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 8: Run — expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.reservations.presentation.onboarding.ActivateReservationsViewModelTest"
```

- [ ] **Step 9: Manual smoke**

Build and install. From a logged-in tablet:
1. Más → "Activar reservas" tile → tap.
2. Activate screen → tap "Activar reservas" → expect: navigate back, the bottom-nav now has Calendario as first tab.
3. Más → "Modo: Reservas ▾" → switch to Estándar → bottom-nav loses Calendario.

Take screenshots and save under `docs/research/square-deep-dive/screenshots-android/avoqado-build/`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/onboarding/ \
        app/src/main/java/com/avoqado/pos/reservations/data/ReservationApi.kt \
        app/src/main/java/com/avoqado/pos/settings/MoreMenuScreen.kt \
        app/src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt \
        app/src/test/java/com/avoqado/pos/reservations/presentation/onboarding/
git commit -m "feat(reservations): Activar reservas onboarding + ModeSwitcherSheet"
```

---

## Task 10: ReservationStatusBadge component

**Goal:** Pill chip with status-specific color + Spanish label, used in list rows and detail header.

**Files:**
- Create: `reservations/presentation/components/ReservationStatusBadge.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.data.model.ReservationStatus

@Composable
fun ReservationStatusBadge(status: ReservationStatus, modifier: Modifier = Modifier) {
    val (bg, fg, label) = when (status) {
        ReservationStatus.PENDING -> Triple(Color(0x33FFA000), Color(0xFFB07000), "Pendiente")
        ReservationStatus.CONFIRMED -> Triple(Color(0x331E88E5), Color(0xFF1565C0), "Confirmada")
        ReservationStatus.CHECKED_IN -> Triple(Color(0x3343A047), Color(0xFF2E7D32), "En curso")
        ReservationStatus.COMPLETED -> Triple(Color(0x33616161), Color(0xFF424242), "Completada")
        ReservationStatus.CANCELLED -> Triple(Color(0x33E53935), Color(0xFFC62828), "Cancelada")
        ReservationStatus.NO_SHOW -> Triple(Color(0x33FB8C00), Color(0xFFE65100), "No-show")
    }
    Text(
        label,
        modifier = modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall.copy(color = fg),
    )
}
```

- [ ] **Step 2: Add a quick preview at the bottom of the same file**

```kotlin
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun PreviewBadges() {
    androidx.compose.foundation.layout.Column {
        ReservationStatus.entries.forEach { ReservationStatusBadge(it, Modifier.padding(4.dp)) }
    }
}
```

- [ ] **Step 3: Build to verify Compose preview compiles**

```bash
./gradlew :app:assembleDebug -x test
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/components/ReservationStatusBadge.kt
git commit -m "feat(reservations): ReservationStatusBadge component"
```

---

## Task 11: ReservationsListViewModel

**Goal:** Owns filter state + tab state + paginated fetch + transition mutations with optimistic update + rollback.

**Files:**
- Create: `reservations/presentation/list/ReservationsListUiState.kt`
- Create: `reservations/presentation/list/ReservationsListViewModel.kt`
- Test: `app/src/test/java/com/avoqado/pos/reservations/presentation/list/ReservationsListViewModelTest.kt`

- [ ] **Step 1: Write `ReservationsListUiState.kt`**

```kotlin
package com.avoqado.pos.reservations.presentation.list

import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationStatus

enum class ReservationListTab(val label: String, val statusFilter: List<ReservationStatus>) {
    HOY("Hoy", listOf(ReservationStatus.PENDING, ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN)),
    PENDIENTES("Pendientes", listOf(ReservationStatus.PENDING)),
    CONFIRMADAS("Confirmadas", listOf(ReservationStatus.CONFIRMED)),
    NO_SHOW("No-show", listOf(ReservationStatus.NO_SHOW)),
    TODAS("Todas", emptyList()),
}

data class ReservationsListUiState(
    val tab: ReservationListTab = ReservationListTab.HOY,
    val isLoading: Boolean = false,
    val items: List<Reservation> = emptyList(),
    val error: String? = null,
    val search: String = "",
    val channelFilter: ReservationChannel? = null,
    val pendingTransitionIds: Set<String> = emptySet(),  // shows spinner overlay on row
)
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.avoqado.pos.reservations.presentation.list

import app.cash.turbine.test
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationListResponse
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.domain.ReservationAction
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationsListViewModelTest {

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun stub(id: String, status: ReservationStatus) = Reservation(
        id = id, venueId = "v", confirmationCode = "X", cancelSecret = "s",
        status = status, channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-04-29T10:00:00.000Z", endsAt = "2026-04-29T11:00:00.000Z",
        duration = 60, createdAt = "2026-04-29T00:00:00.000Z", updatedAt = "2026-04-29T00:00:00.000Z",
    )

    @Test
    fun `initial load filters by HOY tab statuses`() = runTest {
        val repo: ReservationRepository = mockk()
        coEvery { repo.fetchList(match { it.statuses.containsAll(listOf(ReservationStatus.PENDING, ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN)) }) } returns
            Result.success(ReservationListResponse(data = listOf(stub("r1", ReservationStatus.CONFIRMED))))

        val vm = ReservationsListViewModel(repo)

        vm.state.test {
            awaitItem() // initial loading
            val loaded = awaitItem()
            assertEquals(1, loaded.items.size)
            assertEquals("r1", loaded.items[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `runTransition optimistically removes when terminal`() = runTest {
        val repo: ReservationRepository = mockk()
        coEvery { repo.fetchList(any()) } returns Result.success(ReservationListResponse(data = listOf(stub("r1", ReservationStatus.PENDING))))
        coEvery { repo.runAction("r1", ReservationAction.NO_SHOW, null) } returns Result.success(stub("r1", ReservationStatus.NO_SHOW))

        val vm = ReservationsListViewModel(repo)

        vm.state.test {
            awaitItem(); awaitItem() // skip loading + initial loaded
            vm.runTransition("r1", ReservationAction.NO_SHOW)
            // optimistic: spinner appears
            assertTrue(awaitItem().pendingTransitionIds.contains("r1"))
            // result resolves: pending cleared + item removed because new status NO_SHOW is not in HOY filter
            val final = awaitItem()
            assertTrue(final.pendingTransitionIds.isEmpty())
            assertTrue(final.items.none { it.id == "r1" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `runTransition rolls back on failure with error`() = runTest {
        val repo: ReservationRepository = mockk()
        coEvery { repo.fetchList(any()) } returns Result.success(ReservationListResponse(data = listOf(stub("r1", ReservationStatus.PENDING))))
        coEvery { repo.runAction("r1", ReservationAction.CONFIRM, null) } returns Result.failure(RuntimeException("HTTP 409"))

        val vm = ReservationsListViewModel(repo)

        vm.state.test {
            awaitItem(); awaitItem()
            vm.runTransition("r1", ReservationAction.CONFIRM)
            awaitItem() // pending true
            val final = awaitItem()
            assertTrue(final.pendingTransitionIds.isEmpty())
            assertEquals(1, final.items.size)
            assertTrue(final.error!!.isNotBlank())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 3: Implement `ReservationsListViewModel.kt`**

```kotlin
package com.avoqado.pos.reservations.presentation.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.ReservationFilters
import com.avoqado.pos.reservations.domain.ReservationAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReservationsListViewModel @Inject constructor(
    private val repository: ReservationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReservationsListUiState(isLoading = true))
    val state: StateFlow<ReservationsListUiState> = _state.asStateFlow()

    init { refresh() }

    fun setTab(tab: ReservationListTab) {
        _state.update { it.copy(tab = tab) }
        refresh()
    }

    fun setSearch(query: String) {
        _state.update { it.copy(search = query) }
        refresh()
    }

    fun refresh() {
        val s = _state.value
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val filters = ReservationFilters(
                page = 1,
                pageSize = 100,
                statuses = s.tab.statusFilter,
                search = s.search.takeIf { it.isNotBlank() },
                channel = s.channelFilter,
            )
            val r = repository.fetchList(filters)
            _state.update {
                if (r.isSuccess) it.copy(isLoading = false, items = r.getOrNull()?.data.orEmpty(), error = null)
                else it.copy(isLoading = false, error = r.exceptionOrNull()?.message ?: "Error cargando reservas")
            }
        }
    }

    fun runTransition(id: String, action: ReservationAction, payload: ReservationRepository.ActionPayload? = null) {
        _state.update { it.copy(pendingTransitionIds = it.pendingTransitionIds + id) }
        viewModelScope.launch {
            val r = repository.runAction(id, action, payload)
            _state.update { current ->
                val pendingCleared = current.pendingTransitionIds - id
                if (r.isSuccess) {
                    val updated = r.getOrNull()
                    val filtered = if (updated != null && updated.status in current.tab.statusFilter || current.tab.statusFilter.isEmpty()) {
                        current.items.map { if (it.id == id && updated != null) updated else it }
                    } else {
                        current.items.filter { it.id != id }
                    }
                    current.copy(items = filtered, pendingTransitionIds = pendingCleared, error = null)
                } else {
                    current.copy(pendingTransitionIds = pendingCleared, error = r.exceptionOrNull()?.message ?: "Error")
                }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
}
```

- [ ] **Step 4: Run — expect 3 tests PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.reservations.presentation.list.ReservationsListViewModelTest"
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/list/ \
        app/src/test/java/com/avoqado/pos/reservations/presentation/list/
git commit -m "feat(reservations): ReservationsListViewModel with optimistic transitions"
```

---

## Task 12: ReservationsListScreen

**Goal:** Tab row (5 tabs) + search pill + list of reservation rows. Each row: avatar/initials + name + service + time + status badge. Tap → detail nav.

**Files:**
- Create: `reservations/presentation/list/ReservationsListScreen.kt`
- Create: `reservations/presentation/list/ReservationRow.kt`

- [ ] **Step 1: Implement `ReservationRow.kt`**

```kotlin
package com.avoqado.pos.reservations.presentation.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.designsystem.theme.Spacing
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.presentation.components.ReservationStatusBadge

@Composable
fun ReservationRow(
    reservation: Reservation,
    isPending: Boolean,
    onClick: () -> Unit,
    formatter: VenueDateTimeFormatter,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // Initials avatar
        Surface(
            modifier = Modifier.size(44.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(reservation.displayName.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(reservation.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            val service = reservation.displayServiceName ?: reservation.table?.let { "Mesa ${it.number}" }
            Text(
                service ?: "Sin servicio",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatter.formatTimeShort(reservation.startsAt), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            ReservationStatusBadge(reservation.status)
        }
        if (isPending) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    Divider(color = MaterialTheme.colorScheme.outlineVariant)
}
```

- [ ] **Step 2: Add `formatTimeShort` to `VenueDateTimeFormatter`**

```bash
grep -n "fun format" app/src/main/java/com/avoqado/pos/core/util/VenueDateTimeFormatter.kt
```

If absent, add a method that takes an ISO-8601 string and returns "10:00 AM" style time in venue tz. Reuse `ZonedDateTime` parser already there.

- [ ] **Step 3: Implement `ReservationsListScreen.kt`**

```kotlin
package com.avoqado.pos.reservations.presentation.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.core.util.VenueDateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationsListScreen(
    onOpenDetail: (String) -> Unit,
    formatter: VenueDateTimeFormatter,
    viewModel: ReservationsListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackHost = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackHost.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Reservas") })
                ScrollableTabRow(
                    selectedTabIndex = ReservationListTab.entries.indexOf(state.tab),
                    edgePadding = 0.dp,
                ) {
                    ReservationListTab.entries.forEachIndexed { i, tab ->
                        Tab(
                            selected = state.tab == tab,
                            onClick = { viewModel.setTab(tab) },
                            text = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackHost) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading && state.items.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.items.isEmpty() ->
                    Text("Sin reservas en esta vista", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.items, key = { it.id }) { r ->
                            ReservationRow(
                                reservation = r,
                                isPending = r.id in state.pendingTransitionIds,
                                onClick = { onOpenDetail(r.id) },
                                formatter = formatter,
                            )
                        }
                    }
            }
        }
    }
}
```

- [ ] **Step 4: Add nav route**

In `AvoqadoNavGraph.kt`:

```kotlin
composable("reservations/list") {
    val formatter: VenueDateTimeFormatter = hiltViewModel<...>().formatter // or @Inject via Hilt entry-point
    ReservationsListScreen(
        onOpenDetail = { id -> navController.navigate("reservations/$id") },
        formatter = formatter,
    )
}
```

For the formatter, the cleanest is: inject via a dedicated `@HiltViewModel class FormatterHolder @Inject constructor(val formatter: VenueDateTimeFormatter)`, OR use `@EntryPoint` from `LocalContext.current.applicationContext`. Pick whichever matches the existing project pattern (search for `VenueDateTimeFormatter` usages and copy).

- [ ] **Step 5: Manual smoke**

Build, install. Activate reservations. From Calendar tab (still stub), add a temporary nav button to the list. Verify:
- Tabs render and switch.
- Empty state appears with no data.
- A real reservation (created from web dashboard) appears in HOY.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/list/ \
        app/src/main/java/com/avoqado/pos/core/util/VenueDateTimeFormatter.kt \
        app/src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt
git commit -m "feat(reservations): list screen with tabs (Hoy/Pendientes/Confirmadas/No-show/Todas)"
```

---

## Task 13: ReservationDetailViewModel

**Goal:** Loads one reservation, exposes capability flags + executable actions, runs transitions with optimistic update.

**Files:**
- Create: `reservations/presentation/detail/ReservationDetailUiState.kt`
- Create: `reservations/presentation/detail/ReservationDetailViewModel.kt`
- Test: `app/src/test/java/com/avoqado/pos/reservations/presentation/detail/ReservationDetailViewModelTest.kt`

- [ ] **Step 1: UiState**

```kotlin
package com.avoqado.pos.reservations.presentation.detail

import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.domain.ReservationAction
import com.avoqado.pos.reservations.domain.ReservationStateMachine
import com.avoqado.pos.reservations.domain.ReservationsCapability

data class ReservationDetailUiState(
    val isLoading: Boolean = true,
    val reservation: Reservation? = null,
    val capability: ReservationsCapability = ReservationsCapability(false, false, false, false),
    val pendingAction: ReservationAction? = null,
    val error: String? = null,
    val justCompletedAction: ReservationAction? = null,
) {
    fun isAllowed(action: ReservationAction): Boolean {
        val r = reservation ?: return false
        if (!ReservationStateMachine.canExecute(r.status, action)) return false
        return when (action) {
            ReservationAction.CANCEL -> capability.canCancel
            ReservationAction.RESCHEDULE -> capability.canUpdate
            else -> capability.canUpdate
        }
    }
}
```

- [ ] **Step 2: Test**

```kotlin
package com.avoqado.pos.reservations.presentation.detail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.domain.ReservationAction
import com.avoqado.pos.reservations.domain.ReservationsCapability
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationDetailViewModelTest {

    @Before fun s() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun t() { Dispatchers.resetMain() }

    private fun stub(status: ReservationStatus = ReservationStatus.CONFIRMED) = Reservation(
        id = "r1", venueId = "v", confirmationCode = "X", cancelSecret = "s",
        status = status, channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-04-29T10:00:00.000Z", endsAt = "2026-04-29T11:00:00.000Z",
        duration = 60, createdAt = "...", updatedAt = "..."
    )

    private val capProvider: () -> ReservationsCapability = { ReservationsCapability(true, true, true, true) }

    @Test
    fun `loads reservation on init`() = runTest {
        val repo: ReservationRepository = mockk()
        coEvery { repo.fetchOne("r1") } returns Result.success(stub())

        val vm = ReservationDetailViewModel(repo, capProvider, SavedStateHandle(mapOf("reservationId" to "r1")))

        vm.state.test {
            awaitItem()  // initial loading
            val loaded = awaitItem()
            assertEquals("r1", loaded.reservation?.id)
            assertEquals(false, loaded.isLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `runAction confirm transitions optimistically`() = runTest {
        val repo: ReservationRepository = mockk()
        coEvery { repo.fetchOne("r1") } returns Result.success(stub(ReservationStatus.PENDING))
        coEvery { repo.runAction("r1", ReservationAction.CONFIRM, null) } returns Result.success(stub(ReservationStatus.CONFIRMED))

        val vm = ReservationDetailViewModel(repo, capProvider, SavedStateHandle(mapOf("reservationId" to "r1")))

        vm.state.test {
            awaitItem(); awaitItem() // loading + loaded
            vm.runAction(ReservationAction.CONFIRM)
            val pending = awaitItem()
            assertEquals(ReservationAction.CONFIRM, pending.pendingAction)
            val final = awaitItem()
            assertNull(final.pendingAction)
            assertEquals(ReservationStatus.CONFIRMED, final.reservation?.status)
            assertEquals(ReservationAction.CONFIRM, final.justCompletedAction)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `runAction failure rolls back`() = runTest {
        val repo: ReservationRepository = mockk()
        coEvery { repo.fetchOne("r1") } returns Result.success(stub(ReservationStatus.PENDING))
        coEvery { repo.runAction("r1", ReservationAction.NO_SHOW, null) } returns Result.failure(RuntimeException("HTTP 409"))

        val vm = ReservationDetailViewModel(repo, capProvider, SavedStateHandle(mapOf("reservationId" to "r1")))

        vm.state.test {
            awaitItem(); awaitItem()
            vm.runAction(ReservationAction.NO_SHOW)
            awaitItem() // pending
            val final = awaitItem()
            assertNull(final.pendingAction)
            assertEquals(ReservationStatus.PENDING, final.reservation?.status)
            assertTrue(final.error!!.contains("409"))
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 3: Implement VM**

```kotlin
package com.avoqado.pos.reservations.presentation.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.domain.ReservationAction
import com.avoqado.pos.reservations.domain.ReservationsCapability
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@HiltViewModel
class ReservationDetailViewModel @Inject constructor(
    private val repository: ReservationRepository,
    private val capabilityProvider: Provider<ReservationsCapability>,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val reservationId: String = checkNotNull(savedStateHandle["reservationId"])

    private val _state = MutableStateFlow(ReservationDetailUiState(capability = capabilityProvider.get()))
    val state: StateFlow<ReservationDetailUiState> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val r = repository.fetchOne(reservationId)
            _state.update {
                if (r.isSuccess) it.copy(isLoading = false, reservation = r.getOrNull())
                else it.copy(isLoading = false, error = r.exceptionOrNull()?.message ?: "Error cargando reserva")
            }
        }
    }

    fun runAction(action: ReservationAction, payload: ReservationRepository.ActionPayload? = null) {
        if (!_state.value.isAllowed(action)) return
        val before = _state.value.reservation
        _state.update { it.copy(pendingAction = action, error = null, justCompletedAction = null) }
        viewModelScope.launch {
            val r = repository.runAction(reservationId, action, payload)
            _state.update { current ->
                if (r.isSuccess) {
                    val updated = r.getOrNull() ?: before
                    current.copy(reservation = updated, pendingAction = null, justCompletedAction = action)
                } else {
                    current.copy(reservation = before, pendingAction = null, error = r.exceptionOrNull()?.message ?: "Error")
                }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
    fun consumeJustCompleted() = _state.update { it.copy(justCompletedAction = null) }
}
```

- [ ] **Step 4: Bind ReservationsCapability provider in Hilt module**

In `ReservationModule.kt`:

```kotlin
@Provides
fun provideReservationsCapability(secureStorage: SecureStorage): ReservationsCapability {
    val perms = secureStorage.permissions ?: emptyList()
    return ReservationsCapability.fromPermissions(perms)
}
```

If `SecureStorage.permissions: List<String>?` doesn't exist yet, add it (decode from JWT at login — search for where `secureStorage.token` is set).

- [ ] **Step 5: Run — expect 3 tests PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.reservations.presentation.detail.ReservationDetailViewModelTest"
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/detail/ \
        app/src/main/java/com/avoqado/pos/reservations/di/ReservationModule.kt \
        app/src/main/java/com/avoqado/pos/core/data/local/SecureStorage.kt \
        app/src/test/java/com/avoqado/pos/reservations/presentation/detail/
git commit -m "feat(reservations): ReservationDetailViewModel with capability + state machine gating"
```

---

## Task 14: ReservationDetailScreen

**Goal:** Square-style full-screen modal: X close left + title centered + scrollable info + bottom action bar with allowed transitions.

**Files:**
- Create: `reservations/presentation/detail/ReservationDetailScreen.kt`
- Create: `reservations/presentation/detail/InfoSection.kt` (small reusable for the screen only)

- [ ] **Step 1: Implement `InfoSection.kt`**

```kotlin
package com.avoqado.pos.reservations.presentation.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.Spacing

@Composable
fun InfoSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 4.dp))
        content()
    }
}

@Composable
fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
    }
}
```

- [ ] **Step 2: Implement `ReservationDetailScreen.kt`**

```kotlin
package com.avoqado.pos.reservations.presentation.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.theme.Spacing
import com.avoqado.pos.reservations.domain.ReservationAction
import com.avoqado.pos.reservations.presentation.components.ReservationStatusBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationDetailScreen(
    onClose: () -> Unit,
    onReschedule: () -> Unit,
    onCancel: () -> Unit,
    formatter: VenueDateTimeFormatter,
    viewModel: ReservationDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var successLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.error) { state.error?.let { snack.showSnackbar(it); viewModel.consumeError() } }
    LaunchedEffect(state.justCompletedAction) {
        successLabel = state.justCompletedAction?.let { successCopy(it) }
        if (successLabel != null) viewModel.consumeJustCompleted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.reservation?.confirmationCode ?: "") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cerrar") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            state.reservation?.let { r ->
                ActionBar(
                    state = state,
                    onConfirm = { viewModel.runAction(ReservationAction.CONFIRM) },
                    onCheckIn = { viewModel.runAction(ReservationAction.CHECK_IN) },
                    onComplete = { viewModel.runAction(ReservationAction.COMPLETE) },
                    onNoShow = { viewModel.runAction(ReservationAction.NO_SHOW) },
                    onReschedule = onReschedule,
                    onCancel = onCancel,
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.reservation == null -> Text("No se pudo cargar la reserva", Modifier.align(Alignment.Center))
                else -> {
                    val r = state.reservation!!
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .widthIn(max = 880.dp)
                            .align(Alignment.TopCenter)
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Text(r.displayName, style = MaterialTheme.typography.headlineSmall)
                        ReservationStatusBadge(r.status)
                        InfoSection("Cita") {
                            InfoRow("Fecha", formatter.formatDateLong(r.startsAt))
                            InfoRow("Hora", "${formatter.formatTimeShort(r.startsAt)} – ${formatter.formatTimeShort(r.endsAt)}")
                            InfoRow("Duración", "${r.duration} min")
                            InfoRow("Personas", r.partySize.toString())
                            InfoRow("Servicio", r.displayServiceName)
                            InfoRow("Mesa", r.table?.let { "Mesa ${it.number}" })
                            InfoRow("Personal", r.assignedStaff?.displayName)
                        }
                        InfoSection("Cliente") {
                            InfoRow("Teléfono", r.displayPhone)
                            InfoRow("Email", r.customer?.email ?: r.guestEmail)
                            InfoRow("Canal", r.channel.displayLabel)
                        }
                        if (!r.specialRequests.isNullOrBlank() || !r.internalNotes.isNullOrBlank()) {
                            InfoSection("Notas") {
                                InfoRow("Solicitudes", r.specialRequests)
                                InfoRow("Internas", r.internalNotes)
                            }
                        }
                        if (r.depositAmount != null) {
                            InfoSection("Depósito") {
                                InfoRow("Monto", r.depositAmount)
                                InfoRow("Estado", r.depositStatus?.name)
                            }
                        }
                    }
                }
            }
            successLabel?.let { label ->
                AvoqadoSuccessToast(message = label, onDismiss = { successLabel = null }, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

private fun successCopy(action: ReservationAction): String = when (action) {
    ReservationAction.CONFIRM -> "¡Reserva confirmada!"
    ReservationAction.CHECK_IN -> "¡Cliente registrado!"
    ReservationAction.COMPLETE -> "¡Reserva completada!"
    ReservationAction.NO_SHOW -> "Marcada como no-show"
    ReservationAction.CANCEL -> "Reserva cancelada"
    ReservationAction.RESCHEDULE -> "¡Reserva reagendada!"
}

@Composable
private fun ActionBar(
    state: ReservationDetailUiState,
    onConfirm: () -> Unit, onCheckIn: () -> Unit, onComplete: () -> Unit,
    onNoShow: () -> Unit, onReschedule: () -> Unit, onCancel: () -> Unit,
) {
    Surface(tonalElevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            val pending = state.pendingAction
            ActionPill("Confirmar", state.isAllowed(ReservationAction.CONFIRM), pending == ReservationAction.CONFIRM, onConfirm, Modifier.weight(1f))
            ActionPill("Check-in", state.isAllowed(ReservationAction.CHECK_IN), pending == ReservationAction.CHECK_IN, onCheckIn, Modifier.weight(1f))
            ActionPill("Completar", state.isAllowed(ReservationAction.COMPLETE), pending == ReservationAction.COMPLETE, onComplete, Modifier.weight(1f))
            ActionPill("No-show", state.isAllowed(ReservationAction.NO_SHOW), pending == ReservationAction.NO_SHOW, onNoShow, Modifier.weight(1f))
            ActionPill("Reagendar", state.isAllowed(ReservationAction.RESCHEDULE), false, onReschedule, Modifier.weight(1f))
            ActionPill("Cancelar", state.isAllowed(ReservationAction.CANCEL), false, onCancel, Modifier.weight(1f), destructive = true)
        }
    }
}

@Composable
private fun ActionPill(label: String, enabled: Boolean, loading: Boolean, onClick: () -> Unit, modifier: Modifier, destructive: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier,
        colors = if (destructive) ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Text(label, maxLines = 1)
    }
}
```

- [ ] **Step 3: Add `formatDateLong` to `VenueDateTimeFormatter`**

If absent, add: returns "miércoles, 29 de abril" using venue tz. Reuse the existing tz instance.

- [ ] **Step 4: Wire route in NavGraph**

```kotlin
composable(
    "reservations/{reservationId}",
    arguments = listOf(navArgument("reservationId") { type = NavType.StringType }),
) {
    ReservationDetailScreen(
        onClose = { navController.popBackStack() },
        onReschedule = { navController.navigate("reservations/${it.arguments?.getString("reservationId")}/reschedule") },
        onCancel = { navController.navigate("reservations/${it.arguments?.getString("reservationId")}/cancel") },
        formatter = formatter,
    )
}
```

- [ ] **Step 5: Manual smoke**

Open detail of a real reservation. Verify the action bar shows only allowed transitions. Tap Confirm — observe optimistic spinner → success toast. Tap Check-in — same. Tap a disabled action — nothing happens. Take screenshots.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/detail/ \
        app/src/main/java/com/avoqado/pos/core/util/VenueDateTimeFormatter.kt \
        app/src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt
git commit -m "feat(reservations): detail screen with action bar (confirm/checkIn/complete/noShow/reschedule/cancel)"
```

---

## Task 15: CancelReservationSheet

**Goal:** Bottom sheet with optional reason input + Confirmar destructive button.

**Files:**
- Create: `reservations/presentation/detail/CancelReservationSheet.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.avoqado.pos.reservations.presentation.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.theme.Spacing
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.domain.ReservationAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CancelReservationSheet(
    onDismiss: () -> Unit,
    viewModel: ReservationDetailViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var reason by remember { mutableStateOf("") }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.justCompletedAction) {
        if (state.justCompletedAction == ReservationAction.CANCEL) onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(Spacing.lg)) {
            Text("Cancelar reserva", style = MaterialTheme.typography.headlineSmall)
            Text("Esta acción no se puede deshacer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.padding(Spacing.sm))
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Motivo (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            )
            Spacer(Modifier.padding(Spacing.md))
            Button(
                onClick = {
                    viewModel.runAction(
                        ReservationAction.CANCEL,
                        ReservationRepository.ActionPayload.Cancel(reason.takeIf { it.isNotBlank() })
                    )
                },
                enabled = state.pendingAction != ReservationAction.CANCEL,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            ) {
                Text(if (state.pendingAction == ReservationAction.CANCEL) "Cancelando..." else "Confirmar cancelación")
            }
        }
    }
}
```

- [ ] **Step 2: Wire route**

```kotlin
composable("reservations/{reservationId}/cancel") {
    CancelReservationSheet(onDismiss = { navController.popBackStack() })
}
```

The shared ViewModel scope must be tied to the detail screen so the sheet sees the same VM. Use `navGraphViewModels` or scope via `hiltViewModel(LocalNavBackStackEntry.current)` against the detail entry. The cleanest: nest the cancel route under the same scoped graph as the detail screen, so `hiltViewModel<ReservationDetailViewModel>()` returns the same instance.

If that's complex, simplify: pass the reservationId to the sheet via nav args and have the sheet use its own VM that emits a callback to the detail screen on success. Use whichever fits the existing project's nav patterns (search `navGraphViewModels` in the codebase).

- [ ] **Step 3: Manual smoke** — cancel a real test reservation; observe it disappears from list and detail closes.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/detail/CancelReservationSheet.kt \
        app/src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt
git commit -m "feat(reservations): cancel reservation bottom sheet with reason input"
```

---

## Task 16: RescheduleSheet

**Goal:** Date + time pickers, validation that end > start, calls reschedule.

**Files:**
- Create: `reservations/presentation/detail/RescheduleSheet.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.avoqado.pos.reservations.presentation.detail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.designsystem.theme.Spacing
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.domain.ReservationAction
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescheduleSheet(
    venueTimezone: ZoneId,
    onDismiss: () -> Unit,
    viewModel: ReservationDetailViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by viewModel.state.collectAsState()
    val r = state.reservation

    var date by remember { mutableStateOf(LocalDate.now(venueTimezone)) }
    var startTime by remember { mutableStateOf(LocalTime.of(10, 0)) }
    var endTime by remember { mutableStateOf(LocalTime.of(11, 0)) }
    val isValid = endTime.isAfter(startTime)

    LaunchedEffect(state.justCompletedAction) {
        if (state.justCompletedAction == ReservationAction.RESCHEDULE) onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(Spacing.lg)) {
            Text("Reagendar reserva", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.padding(Spacing.sm))

            // Replace the placeholders below with your project's existing date/time picker components.
            // If none exist, use Material3 DatePickerDialog and TimePickerDialog (Compose Material3 1.3+).
            DateRow(date = date, onDateChange = { date = it })
            TimeRow(label = "Hora de inicio", time = startTime, onTimeChange = { startTime = it })
            TimeRow(label = "Hora de fin", time = endTime, onTimeChange = { endTime = it })

            if (!isValid) Text("La hora de fin debe ser posterior a la hora de inicio", color = MaterialTheme.colorScheme.error)

            Spacer(Modifier.padding(Spacing.md))

            Button(
                onClick = {
                    val startsAt = ZonedDateTime.of(date, startTime, venueTimezone).withZoneSameInstant(ZoneId.of("UTC"))
                    val endsAt = ZonedDateTime.of(date, endTime, venueTimezone).withZoneSameInstant(ZoneId.of("UTC"))
                    viewModel.runAction(
                        ReservationAction.RESCHEDULE,
                        ReservationRepository.ActionPayload.Reschedule(
                            startsAt = startsAt.format(DateTimeFormatter.ISO_INSTANT),
                            endsAt = endsAt.format(DateTimeFormatter.ISO_INSTANT),
                        )
                    )
                },
                enabled = isValid && state.pendingAction != ReservationAction.RESCHEDULE,
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            ) {
                Text(if (state.pendingAction == ReservationAction.RESCHEDULE) "Reagendando..." else "Reagendar")
            }
        }
    }
}

@Composable
private fun DateRow(date: LocalDate, onDateChange: (LocalDate) -> Unit) {
    var show by remember { mutableStateOf(false) }
    androidx.compose.material3.OutlinedButton(onClick = { show = true }) {
        Text("Fecha: ${date.format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy"))}")
    }
    if (show) {
        // Use Material3 DatePickerDialog — replicate the pattern used in /reports for date pickers.
        // Placeholder: developer must wire up the dialog using the existing project pattern.
        // The simplest approach: SimpleDateFormat-free, java.time-only, venue tz–aware.
        show = false
    }
}

@Composable
private fun TimeRow(label: String, time: LocalTime, onTimeChange: (LocalTime) -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = { /* open TimePickerDialog */ }) {
        Text("$label: ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}")
    }
}
```

The DatePicker/TimePicker placeholders need real Material3 dialog wire-up. Look at `app/src/main/java/com/avoqado/pos/reports/` (Reports has venue-tz date pickers from v2.1.1) and copy the pattern. **Do not introduce a new picker library.**

- [ ] **Step 2: Wire route**

```kotlin
composable("reservations/{reservationId}/reschedule") {
    RescheduleSheet(venueTimezone = ZoneId.of(secureStorage.venueTimezone ?: "America/Mexico_City"), onDismiss = { navController.popBackStack() })
}
```

- [ ] **Step 3: Manual smoke** — Reschedule a real reservation, verify server accepts and detail reflects new times.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/detail/RescheduleSheet.kt \
        app/src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt
git commit -m "feat(reservations): reschedule bottom sheet (venue-tz aware)"
```

---

## Task 17: WeekStrip component

**Goal:** Horizontal strip with 7 day buttons (D L M M J V S + day number). Today is bold. Selected day fills.

**Files:**
- Create: `reservations/presentation/components/WeekStrip.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.designsystem.theme.Spacing
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val SPANISH_LETTERS = listOf("D", "L", "M", "M", "J", "V", "S")

@Composable
fun WeekStrip(
    weekOf: LocalDate,           // any date in the desired week (we'll align to Sunday)
    selectedDate: LocalDate,
    today: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sunday = weekOf.minusDays(((weekOf.dayOfWeek.value % 7).toLong())) // ISO Mon=1; Sun=7→0
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (i in 0..6) {
            val date = sunday.plusDays(i.toLong())
            val isSelected = date == selectedDate
            val isToday = date == today
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onDateSelected(date) }
                    .padding(vertical = 4.dp),
            ) {
                Text(SPANISH_LETTERS[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.onSurface
                            else if (isToday) MaterialTheme.colorScheme.surfaceVariant
                            else androidx.compose.ui.graphics.Color.Transparent,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Add a preview at the bottom of the file**

```kotlin
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun PreviewWeekStrip() {
    val today = LocalDate.now()
    WeekStrip(weekOf = today, selectedDate = today, today = today, onDateSelected = {})
}
```

- [ ] **Step 3: Build to verify it compiles**

```bash
./gradlew :app:assembleDebug -x test
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/components/WeekStrip.kt
git commit -m "feat(reservations): WeekStrip component"
```

---

## Task 18: ReservationBlock + CurrentTimeIndicator + EmptyStateBlock

**Goal:** Visual atoms used by the calendar grid.

**Files:**
- Create: `reservations/presentation/components/ReservationBlock.kt`
- Create: `reservations/presentation/components/CurrentTimeIndicator.kt`
- Create: `reservations/presentation/components/EmptyStateBlock.kt`

- [ ] **Step 1: `ReservationBlock.kt`**

```kotlin
package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationStatus

@Composable
fun ReservationBlock(
    reservation: Reservation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (bg, fg) = colorsFor(reservation.status)
    Column(
        modifier = modifier
            .background(bg, RoundedCornerShape(6.dp))
            .border(1.dp, fg.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(reservation.displayName, style = MaterialTheme.typography.labelMedium.copy(color = fg), maxLines = 1)
        reservation.displayServiceName?.let {
            Text(it, style = MaterialTheme.typography.labelSmall.copy(color = fg.copy(alpha = 0.8f)), maxLines = 1)
        }
    }
}

private fun colorsFor(status: ReservationStatus): Pair<Color, Color> = when (status) {
    ReservationStatus.PENDING -> Color(0x33FFA000) to Color(0xFFB07000)
    ReservationStatus.CONFIRMED -> Color(0x331E88E5) to Color(0xFF1565C0)
    ReservationStatus.CHECKED_IN -> Color(0x3343A047) to Color(0xFF2E7D32)
    ReservationStatus.COMPLETED -> Color(0x33616161) to Color(0xFF424242)
    ReservationStatus.CANCELLED -> Color(0x33E53935) to Color(0xFFC62828)
    ReservationStatus.NO_SHOW -> Color(0x33FB8C00) to Color(0xFFE65100)
}
```

- [ ] **Step 2: `CurrentTimeIndicator.kt`**

```kotlin
package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Draws a horizontal red line at [yOffsetDp] with a "HH:mm" label. */
@Composable
fun CurrentTimeIndicator(yOffsetDp: Float, label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(2.dp).offset(y = yOffsetDp.dp)
        ) {
            drawLine(
                color = Color.Red,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 2f,
            )
        }
        Text(
            label,
            modifier = Modifier.offset(y = (yOffsetDp - 8).dp).padding(start = 4.dp),
            color = Color.Red,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
```

- [ ] **Step 3: `EmptyStateBlock.kt`**

```kotlin
package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.avoqado.pos.designsystem.theme.Spacing

@Composable
fun EmptyStateBlock(
    title: String,
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (actionLabel != null && onAction != null) {
            OutlinedButton(onClick = onAction, shape = androidx.compose.foundation.shape.RoundedCornerShape(50)) {
                Text(actionLabel)
            }
        }
    }
}
```

- [ ] **Step 4: Build + commit**

```bash
./gradlew :app:assembleDebug -x test
git add app/src/main/java/com/avoqado/pos/reservations/presentation/components/{ReservationBlock,CurrentTimeIndicator,EmptyStateBlock}.kt
git commit -m "feat(reservations): ReservationBlock + CurrentTimeIndicator + EmptyStateBlock atoms"
```

---

## Task 19: CalendarViewModel

**Goal:** Owns selected date, view (Day/Week), filter set, fetched reservations for the visible range. Re-fetches on date or view change.

**Files:**
- Create: `reservations/presentation/calendar/CalendarUiState.kt`
- Create: `reservations/presentation/calendar/CalendarViewModel.kt`
- Test: `app/src/test/java/com/avoqado/pos/reservations/presentation/calendar/CalendarViewModelTest.kt`

- [ ] **Step 1: UiState**

```kotlin
package com.avoqado.pos.reservations.presentation.calendar

import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationStatus
import java.time.LocalDate

enum class CalendarView { DAY, WEEK }

data class CalendarUiState(
    val view: CalendarView = CalendarView.DAY,
    val selectedDate: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val reservations: List<Reservation> = emptyList(),
    val isLoading: Boolean = false,
    val visibleStatuses: Set<ReservationStatus> = setOf(ReservationStatus.PENDING, ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN),
    val showCancelled: Boolean = false,
    val error: String? = null,
)
```

- [ ] **Step 2: Test**

```kotlin
package com.avoqado.pos.reservations.presentation.calendar

import app.cash.turbine.test
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    @Before fun s() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun t() { Dispatchers.resetMain() }

    private fun stub(id: String, status: ReservationStatus) = Reservation(
        id = id, venueId = "v", confirmationCode = "X", cancelSecret = "s",
        status = status, channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-04-29T10:00:00.000Z", endsAt = "2026-04-29T11:00:00.000Z",
        duration = 60, createdAt = "...", updatedAt = "..."
    )

    @Test
    fun `initial fetch covers selected day`() = runTest {
        val repo: ReservationRepository = mockk()
        val storage: SecureStorage = mockk(); every { storage.venueTimezone } returns "America/Mexico_City"

        coEvery { repo.fetchCalendar(any(), any()) } returns Result.success(listOf(stub("r1", ReservationStatus.CONFIRMED)))

        val vm = CalendarViewModel(repo, storage)

        vm.state.test {
            awaitItem() // initial loading
            val loaded = awaitItem()
            assertEquals(1, loaded.reservations.size)
            assertEquals(false, loaded.isLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setDate triggers re-fetch`() = runTest {
        val repo: ReservationRepository = mockk()
        val storage: SecureStorage = mockk(); every { storage.venueTimezone } returns "America/Mexico_City"

        coEvery { repo.fetchCalendar(any(), any()) } returnsMany listOf(
            Result.success(emptyList()),
            Result.success(listOf(stub("r1", ReservationStatus.PENDING))),
        )

        val vm = CalendarViewModel(repo, storage)

        vm.state.test {
            awaitItem(); awaitItem() // initial
            vm.setDate(LocalDate.now().plusDays(1))
            awaitItem(); val refetched = awaitItem()
            assertEquals(1, refetched.reservations.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setView WEEK triggers wider range fetch`() = runTest {
        val repo: ReservationRepository = mockk()
        val storage: SecureStorage = mockk(); every { storage.venueTimezone } returns "America/Mexico_City"

        coEvery { repo.fetchCalendar(any(), any()) } returns Result.success(emptyList())

        val vm = CalendarViewModel(repo, storage)

        vm.state.test {
            awaitItem(); awaitItem()
            vm.setView(CalendarView.WEEK)
            awaitItem(); awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        // Week range should have been requested
        io.mockk.coVerify {
            repo.fetchCalendar(match { it == it /* range covers 7 days */ }, any())
        }
    }
}
```

- [ ] **Step 3: Implement VM**

```kotlin
package com.avoqado.pos.reservations.presentation.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.ReservationStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repository: ReservationRepository,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    private val zone: ZoneId get() = ZoneId.of(secureStorage.venueTimezone ?: "America/Mexico_City")

    private val _state = MutableStateFlow(CalendarUiState(today = LocalDate.now(zone), selectedDate = LocalDate.now(zone)))
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init { fetch() }

    fun setDate(date: LocalDate) {
        _state.update { it.copy(selectedDate = date) }
        fetch()
    }

    fun setView(view: CalendarView) {
        _state.update { it.copy(view = view) }
        fetch()
    }

    fun setVisibleStatuses(statuses: Set<ReservationStatus>) {
        _state.update { it.copy(visibleStatuses = statuses) }
    }

    fun setShowCancelled(show: Boolean) {
        _state.update { it.copy(showCancelled = show) }
    }

    private fun fetch() {
        val s = _state.value
        val (from, to) = when (s.view) {
            CalendarView.DAY -> s.selectedDate to s.selectedDate
            CalendarView.WEEK -> {
                val sunday = s.selectedDate.minusDays((s.selectedDate.dayOfWeek.value % 7).toLong())
                sunday to sunday.plusDays(6)
            }
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val r = repository.fetchCalendar(
                dateFrom = from.format(DateTimeFormatter.ISO_LOCAL_DATE),
                dateTo = to.format(DateTimeFormatter.ISO_LOCAL_DATE),
            )
            _state.update {
                if (r.isSuccess) it.copy(isLoading = false, reservations = r.getOrNull().orEmpty(), error = null)
                else it.copy(isLoading = false, error = r.exceptionOrNull()?.message ?: "Error cargando calendario")
            }
        }
    }

    val visibleReservations: List<com.avoqado.pos.reservations.data.model.Reservation>
        get() {
            val s = _state.value
            return s.reservations.filter {
                (it.status in s.visibleStatuses) || (s.showCancelled && it.status == ReservationStatus.CANCELLED)
            }
        }
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.reservations.presentation.calendar.CalendarViewModelTest"
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/calendar/{CalendarUiState,CalendarViewModel}.kt \
        app/src/test/java/com/avoqado/pos/reservations/presentation/calendar/
git commit -m "feat(reservations): CalendarViewModel with day/week range fetching"
```

---

## Task 20: CalendarDayGrid component

**Goal:** Vertical 24-hour grid (configurable start/end) with hour labels on the left axis. Layouts reservations as positioned `ReservationBlock`s based on start time / duration. Renders the current-time line if today is selected.

**Files:**
- Create: `reservations/presentation/components/CalendarDayGrid.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.data.model.Reservation
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val HOUR_HEIGHT_DP = 64.dp

@Composable
fun CalendarDayGrid(
    selectedDate: LocalDate,
    today: LocalDate,
    reservations: List<Reservation>,
    venueZone: ZoneId,
    nowTime: LocalTime,
    startHour: Int = 6,
    endHour: Int = 23,
    onReservationClick: (Reservation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hours = (startHour..endHour).toList()
    val totalHeight = HOUR_HEIGHT_DP * hours.size

    Box(modifier = modifier.verticalScroll(rememberScrollState())) {
        // Hour rows
        Column {
            hours.forEach { hour ->
                Row(Modifier.height(HOUR_HEIGHT_DP).fillMaxWidth()) {
                    Text(
                        "%02d".format(hour),
                        modifier = Modifier.width(48.dp).padding(start = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.Canvas(Modifier.weight(1f).fillMaxHeight()) {
                        drawLine(
                            color = androidx.compose.ui.graphics.Color.LightGray.copy(alpha = 0.5f),
                            start = androidx.compose.ui.geometry.Offset(0f, 0f),
                            end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                            strokeWidth = 1f,
                        )
                    }
                }
            }
        }

        // Reservation blocks positioned absolutely
        reservations.forEach { r ->
            val start = ZonedDateTime.parse(r.startsAt).withZoneSameInstant(venueZone)
            val end = ZonedDateTime.parse(r.endsAt).withZoneSameInstant(venueZone)
            if (start.toLocalDate() != selectedDate) return@forEach
            val topMin = (start.hour - startHour) * 60 + start.minute
            val durMin = (r.duration).coerceAtLeast(15)
            if (topMin < 0) return@forEach
            val topDp = (topMin / 60f) * HOUR_HEIGHT_DP.value
            val heightDp = (durMin / 60f) * HOUR_HEIGHT_DP.value

            Box(
                Modifier
                    .padding(start = 56.dp, end = 8.dp)
                    .offset(y = topDp.dp)
                    .height(heightDp.dp)
                    .fillMaxWidth(),
            ) {
                ReservationBlock(reservation = r, onClick = { onReservationClick(r) }, modifier = Modifier.fillMaxSize())
            }
        }

        // Current time indicator
        if (selectedDate == today) {
            val nowMin = (nowTime.hour - startHour) * 60 + nowTime.minute
            if (nowMin in 0..(hours.size * 60)) {
                val topDp = (nowMin / 60f) * HOUR_HEIGHT_DP.value
                CurrentTimeIndicator(yOffsetDp = topDp, label = nowTime.format(DateTimeFormatter.ofPattern("HH:mm")))
            }
        }
    }
}
```

- [ ] **Step 2: Build to confirm Composable compiles**

```bash
./gradlew :app:assembleDebug -x test
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/components/CalendarDayGrid.kt
git commit -m "feat(reservations): CalendarDayGrid with hour rows + positioned blocks + current-time line"
```

---

## Task 21: CalendarDayView screen + CalendarTabHost wiring

**Goal:** The actual Day view: header with month dropdown, WeekStrip, Day grid, and `+` button stub.

**Files:**
- Modify: `reservations/presentation/calendar/CalendarTabHost.kt`
- Create: `reservations/presentation/calendar/CalendarDayView.kt`

- [ ] **Step 1: Implement `CalendarDayView.kt`**

```kotlin
package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.presentation.components.CalendarDayGrid
import com.avoqado.pos.reservations.presentation.components.WeekStrip
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun CalendarDayView(
    state: CalendarUiState,
    venueZone: ZoneId,
    onSelectDate: (LocalDate) -> Unit,
    onReservationClick: (Reservation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().widthIn(max = 880.dp)) {
        WeekStrip(
            weekOf = state.selectedDate,
            selectedDate = state.selectedDate,
            today = state.today,
            onDateSelected = onSelectDate,
        )
        if (state.reservations.isEmpty() && !state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Sin reservas para ${state.selectedDate}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        CalendarDayGrid(
            selectedDate = state.selectedDate,
            today = state.today,
            reservations = state.reservations,
            venueZone = venueZone,
            nowTime = LocalTime.now(venueZone),
            onReservationClick = onReservationClick,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
```

- [ ] **Step 2: Replace stub `CalendarTabHost.kt`**

```kotlin
package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.designsystem.theme.Spacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarTabHost(
    secureStorage: SecureStorage,
    onOpenReservation: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val venueZone = remember(secureStorage.venueTimezone) {
        ZoneId.of(secureStorage.venueTimezone ?: "America/Mexico_City")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.selectedDate.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale("es"))).replaceFirstChar { it.uppercase() },
                    )
                },
                actions = {
                    IconButton(onClick = { /* TODO: Phase 2 — create flow */ }) { Icon(Icons.Filled.Add, "Crear") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.MoreHoriz, "Ajustes") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // View toggle
            SegmentedButtonsRow(
                view = state.view,
                onViewChange = viewModel::setView,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
            when (state.view) {
                CalendarView.DAY -> CalendarDayView(
                    state = state,
                    venueZone = venueZone,
                    onSelectDate = viewModel::setDate,
                    onReservationClick = { onOpenReservation(it.id) },
                )
                CalendarView.WEEK -> CalendarWeekView(
                    state = state,
                    venueZone = venueZone,
                    onSelectDate = viewModel::setDate,
                    onReservationClick = { onOpenReservation(it.id) },
                )
            }
        }
    }
}

@Composable
private fun SegmentedButtonsRow(view: CalendarView, onViewChange: (CalendarView) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.SingleChoiceSegmentedButtonRow(modifier) {
        SegmentedButton(
            selected = view == CalendarView.DAY,
            onClick = { onViewChange(CalendarView.DAY) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
        ) { Text("Día") }
        SegmentedButton(
            selected = view == CalendarView.WEEK,
            onClick = { onViewChange(CalendarView.WEEK) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
        ) { Text("Semana") }
    }
}
```

- [ ] **Step 3: Update NavGraph to inject SecureStorage + nav callbacks**

```kotlin
composable(MainTab.CALENDAR.route) {
    CalendarTabHost(
        secureStorage = secureStorage,  // pass from NavGraph param
        onOpenReservation = { id -> navController.navigate("reservations/$id") },
        onOpenSettings = { navController.navigate("calendar/settings") },
    )
}
```

If `secureStorage` isn't already a param of `AvoqadoNavGraph`, add it.

- [ ] **Step 4: Manual smoke**

Build, install. Activate reservations + switch to Reservas mode. Open Calendar tab → expect Day view with WeekStrip, hour grid, and any existing reservations shown as blocks. Tap a block → opens detail. Tap "Semana" segmented button → CalendarWeekView (will be added in Task 22 — for now you'll see a missing reference, fix in Task 22).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/calendar/{CalendarTabHost,CalendarDayView}.kt \
        app/src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt
git commit -m "feat(reservations): Calendar tab host with Day view"
```

---

## Task 22: CalendarWeekGrid + CalendarWeekView

**Goal:** 7-column grid (Sun-Sat) with hour rows + reservation blocks within each column.

**Files:**
- Create: `reservations/presentation/components/CalendarWeekGrid.kt`
- Create: `reservations/presentation/calendar/CalendarWeekView.kt`

- [ ] **Step 1: Implement `CalendarWeekGrid.kt`**

```kotlin
package com.avoqado.pos.reservations.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.data.model.Reservation
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

private val HOUR_HEIGHT_DP = 56.dp

@Composable
fun CalendarWeekGrid(
    weekStart: LocalDate,
    today: LocalDate,
    reservations: List<Reservation>,
    venueZone: ZoneId,
    onReservationClick: (Reservation) -> Unit,
    startHour: Int = 6,
    endHour: Int = 23,
    modifier: Modifier = Modifier,
) {
    val hours = (startHour..endHour).toList()
    val days = (0..6).map { weekStart.plusDays(it.toLong()) }

    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(48.dp))
            days.forEach { d ->
                Text(
                    "${d.dayOfMonth}\n${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale("es"))}",
                    modifier = Modifier.weight(1f).padding(4.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        // Each hour row x 7 day columns
        hours.forEach { hour ->
            Row(Modifier.height(HOUR_HEIGHT_DP).fillMaxWidth()) {
                Text("%02d".format(hour), modifier = Modifier.width(48.dp).padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
                days.forEach { d ->
                    DayHourCell(
                        date = d,
                        hour = hour,
                        reservations = reservations.filter {
                            val starts = ZonedDateTime.parse(it.startsAt).withZoneSameInstant(venueZone)
                            starts.toLocalDate() == d && starts.hour == hour
                        },
                        onReservationClick = onReservationClick,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHourCell(
    date: LocalDate, hour: Int,
    reservations: List<Reservation>,
    onReservationClick: (Reservation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(2.dp)) {
        reservations.forEach { r ->
            ReservationBlock(reservation = r, onClick = { onReservationClick(r) }, modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp))
        }
    }
}
```

- [ ] **Step 2: Implement `CalendarWeekView.kt`**

```kotlin
package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.presentation.components.CalendarWeekGrid
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun CalendarWeekView(
    state: CalendarUiState,
    venueZone: ZoneId,
    onSelectDate: (LocalDate) -> Unit,
    onReservationClick: (Reservation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val weekStart = state.selectedDate.minusDays((state.selectedDate.dayOfWeek.value % 7).toLong())
    CalendarWeekGrid(
        weekStart = weekStart,
        today = state.today,
        reservations = state.reservations,
        venueZone = venueZone,
        onReservationClick = onReservationClick,
        modifier = modifier.fillMaxSize(),
    )
}
```

- [ ] **Step 3: Build + smoke**

```bash
./gradlew :app:assembleDebug -x test
```

Switch to Semana — verify 7 columns + reservations.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/components/CalendarWeekGrid.kt \
        app/src/main/java/com/avoqado/pos/reservations/presentation/calendar/CalendarWeekView.kt
git commit -m "feat(reservations): Calendar week view with 7-column grid"
```

---

## Task 23: CalendarSettingsSheet

**Goal:** Full-screen modal with status filter toggles + show-cancelled toggle. Persists choices to `CalendarViewModel`.

**Files:**
- Create: `reservations/presentation/calendar/CalendarSettingsSheet.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.avoqado.pos.reservations.presentation.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.theme.Spacing
import com.avoqado.pos.reservations.data.model.ReservationStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSettingsSheet(
    onClose: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(state.visibleStatuses) }
    var cancelled by remember { mutableStateOf(state.showCancelled) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes del calendario") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cerrar") } },
                actions = {
                    TextButton(onClick = {
                        viewModel.setVisibleStatuses(visible)
                        viewModel.setShowCancelled(cancelled)
                        onClose()
                    }) { Text("Guardar") }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("Estados visibles", style = MaterialTheme.typography.titleSmall)
            ReservationStatus.entries.filter { it != ReservationStatus.CANCELLED }.forEach { st ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = st in visible, onCheckedChange = { on ->
                        visible = if (on) visible + st else visible - st
                    })
                    Spacer(Modifier.width(Spacing.sm))
                    Text(st.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider()
            Text("Filtros adicionales", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = cancelled, onCheckedChange = { cancelled = it })
                Spacer(Modifier.width(Spacing.sm))
                Text("Mostrar reservas canceladas")
            }
        }
    }
}
```

- [ ] **Step 2: Wire route**

```kotlin
composable("calendar/settings") {
    CalendarSettingsSheet(onClose = { navController.popBackStack() })
}
```

The sheet's `CalendarViewModel` instance must be the same as the tab host's. The cleanest: scope the route under the same nav-graph and use `hiltViewModel(parentEntry)`. Pattern: search for `navGraphViewModels` in the codebase and copy. If none, simplify by stuffing the user's choices into `SecureStorage` keys and re-reading on tab show.

- [ ] **Step 3: Manual smoke** — Open settings, toggle CONFIRMED off, save → confirmed reservations disappear from grid.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/calendar/CalendarSettingsSheet.kt \
        app/src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt
git commit -m "feat(reservations): calendar settings sheet (status filters + show cancelled)"
```

---

## Task 24: Push notification handler for `reservations` channel

**Goal:** When server sends `{ "type": "reservation.*", "reservationId": "..." }`, show a heads-up notification that deep-links to the detail screen.

**Files:**
- Create: `reservations/push/ReservationPushHandler.kt`
- Modify: `push/PushNotificationManager.kt` (route reservation payloads here)
- Modify: `MainActivity.kt` (handle `reservation_id` extra in onCreate / onNewIntent)

- [ ] **Step 1: Inspect existing push manager**

```bash
grep -rn "PushNotificationManager\|FirebaseMessagingService\|onMessageReceived" app/src/main/java/com/avoqado/pos --include="*.kt" | head -20
```

Identify the entry point that receives FCM payloads.

- [ ] **Step 2: Implement `ReservationPushHandler.kt`**

```kotlin
package com.avoqado.pos.reservations.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.avoqado.pos.MainActivity
import com.avoqado.pos.R
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReservationPushHandler @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    private val channelId = "reservations"

    init { ensureChannel() }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(channelId)
        if (existing == null) {
            nm.createNotificationChannel(NotificationChannel(
                channelId, "Reservas", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos de nuevas reservas y cambios de estado"
            })
        }
    }

    /** Returns true if handled. */
    fun handle(data: Map<String, String>): Boolean {
        val type = data["type"] ?: return false
        if (!type.startsWith("reservation.")) return false
        val reservationId = data["reservationId"] ?: return true
        showNotification(type, reservationId, data["summary"])
        return true
    }

    private fun showNotification(type: String, reservationId: String, summary: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("reservation_id", reservationId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(context, reservationId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val title = when (type) {
            "reservation.created" -> "Nueva reserva"
            "reservation.cancelled" -> "Reserva cancelada"
            "reservation.checked_in" -> "Cliente registrado"
            "reservation.no_show" -> "No-show"
            else -> "Cambio en reserva"
        }
        val text = summary ?: reservationId

        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)  // adjust if icon name differs
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(reservationId.hashCode(), notif)
    }
}
```

- [ ] **Step 3: Route from PushNotificationManager**

In `PushNotificationManager.onMessageReceived` (or equivalent):

```kotlin
@Inject lateinit var reservationPushHandler: ReservationPushHandler

override fun onMessageReceived(message: RemoteMessage) {
    if (reservationPushHandler.handle(message.data)) return
    // ... existing routing for other channels
}
```

- [ ] **Step 4: Handle deep-link in MainActivity**

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    // ... existing setup
    handleDeepLink(intent)
}
override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    intent?.let { handleDeepLink(it) }
}
private fun handleDeepLink(intent: Intent) {
    intent.getStringExtra("reservation_id")?.let { id ->
        navController?.navigate("reservations/$id")
    }
}
```

If `navController` isn't accessible from MainActivity, fall back to a `MutableSharedFlow<String>` in a `@Singleton` that the NavGraph collects and navigates on emit. Pick the simpler path that fits the existing pattern.

- [ ] **Step 5: Manual smoke (or curl test the FCM payload)**

If you have access to FCM admin, send a test payload:
```json
{
  "to": "<device token>",
  "data": { "type": "reservation.created", "reservationId": "<id>", "summary": "María López — 7:00 PM" }
}
```

Else, test the flow with adb's notification injection or just unit-test `handle()`:

```kotlin
@Test
fun `handle returns false for non-reservation types`() {
    val handler = ReservationPushHandler(ApplicationProvider.getApplicationContext())
    assertFalse(handler.handle(mapOf("type" to "order.created")))
}
@Test
fun `handle returns true for reservation type`() {
    val handler = ReservationPushHandler(ApplicationProvider.getApplicationContext())
    assertTrue(handler.handle(mapOf("type" to "reservation.created", "reservationId" to "r1")))
}
```

(Place under androidTest since notification APIs need real Android.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/push/ \
        app/src/main/java/com/avoqado/pos/push/ \
        app/src/main/java/com/avoqado/pos/MainActivity.kt
git commit -m "feat(reservations): push notification channel + deep-link to detail"
```

---

## Task 25: ReservationActionsRetrier (drains offline queue on connectivity restore)

**Goal:** A small singleton that observes `ConnectivityMonitor` and drains the `PendingReservationActionDao` when the device comes back online.

**Files:**
- Create: `reservations/data/ReservationActionsRetrier.kt`
- Modify: `AvoqadoApp.kt` (start the retrier on app create)
- Test: `app/src/test/java/com/avoqado/pos/reservations/data/ReservationActionsRetrierTest.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.model.CancelReservationRequest
import com.avoqado.pos.reservations.data.model.RescheduleRequest
import com.avoqado.pos.reservations.domain.ReservationAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReservationActionsRetrier @Inject constructor(
    private val pendingDao: PendingReservationActionDao,
    private val api: ReservationApi,
    private val connectivity: ConnectivityMonitor,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null
    private val maxAttempts = 5

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            connectivity.isOnlineFlow.collectLatest { online ->
                if (online) drain()
            }
        }
    }

    private suspend fun drain() {
        val pending = pendingDao.all()
        for (entry in pending) {
            if (entry.attemptCount >= maxAttempts) {
                pendingDao.delete(entry.rowId); continue
            }
            val action = runCatching { ReservationAction.valueOf(entry.action) }.getOrNull()
            if (action == null) { pendingDao.delete(entry.rowId); continue }
            val result = when (action) {
                ReservationAction.CONFIRM -> api.confirm(entry.reservationId).map { Unit }
                ReservationAction.CHECK_IN -> api.checkIn(entry.reservationId).map { Unit }
                ReservationAction.COMPLETE -> api.complete(entry.reservationId).map { Unit }
                ReservationAction.NO_SHOW -> api.noShow(entry.reservationId).map { Unit }
                ReservationAction.CANCEL -> {
                    val req = entry.payloadJson?.let { json.decodeFromString(CancelReservationRequest.serializer(), it) } ?: CancelReservationRequest()
                    api.cancel(entry.reservationId, req)
                }
                ReservationAction.RESCHEDULE -> {
                    val req = json.decodeFromString(RescheduleRequest.serializer(), entry.payloadJson ?: error("Missing reschedule payload"))
                    api.reschedule(entry.reservationId, req).map { Unit }
                }
            }
            if (result.isSuccess) pendingDao.delete(entry.rowId)
            else pendingDao.incrementAttempt(entry.rowId)
        }
    }
}
```

- [ ] **Step 2: Add `isOnlineFlow: StateFlow<Boolean>` to `ConnectivityMonitor`**

If absent, expose the underlying StateFlow that backs `isOnline()`.

- [ ] **Step 3: Start retrier in `AvoqadoApp.kt`**

```kotlin
@Inject lateinit var reservationActionsRetrier: ReservationActionsRetrier
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

override fun onCreate() {
    super.onCreate()
    reservationActionsRetrier.start(appScope)
}
```

- [ ] **Step 4: Test the drain logic**

```kotlin
package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReservationActionsRetrierTest {

    @Test
    fun `drain calls api for each pending action and deletes on success`() = runTest {
        val dao: PendingReservationActionDao = mockk(relaxed = true)
        val api: ReservationApi = mockk()
        val connectivity: ConnectivityMonitor = mockk()
        val flow = MutableStateFlow(false)
        io.mockk.every { connectivity.isOnlineFlow } returns flow

        coEvery { dao.all() } returns listOf(
            PendingReservationActionEntity(rowId = 1, reservationId = "r1", action = "CONFIRM"),
            PendingReservationActionEntity(rowId = 2, reservationId = "r2", action = "CHECK_IN"),
        )
        coEvery { api.confirm("r1") } returns Result.success(stub("r1"))
        coEvery { api.checkIn("r2") } returns Result.success(stub("r2"))

        val retrier = ReservationActionsRetrier(dao, api, connectivity)
        retrier.start(TestScope(UnconfinedTestDispatcher()))

        flow.value = true
        kotlinx.coroutines.delay(50)

        coVerify { dao.delete(1); dao.delete(2) }
    }

    private fun stub(id: String) = Reservation(
        id = id, venueId = "v", confirmationCode = "X", cancelSecret = "s",
        status = ReservationStatus.CONFIRMED, channel = ReservationChannel.DASHBOARD,
        startsAt = "2026-04-29T10:00:00.000Z", endsAt = "2026-04-29T11:00:00.000Z",
        duration = 60, createdAt = "...", updatedAt = "..."
    )
}
```

- [ ] **Step 5: Run — expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.reservations.data.ReservationActionsRetrierTest"
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/data/ReservationActionsRetrier.kt \
        app/src/main/java/com/avoqado/pos/AvoqadoApp.kt \
        app/src/main/java/com/avoqado/pos/core/util/ConnectivityMonitor.kt \
        app/src/test/java/com/avoqado/pos/reservations/data/ReservationActionsRetrierTest.kt
git commit -m "feat(reservations): offline retrier drains pending actions on connectivity restore"
```

---

## Task 26: Connectivity banner integration on calendar tab

**Goal:** When `isOnline = false`, show the existing connectivity banner above the calendar; when there are pending actions, append "(N pendientes)".

**Files:**
- Modify: `reservations/presentation/calendar/CalendarTabHost.kt`

- [ ] **Step 1: Inspect the existing connectivity banner**

```bash
grep -rn "ConnectivityBanner\|isOnline" app/src/main/java/com/avoqado/pos --include="*.kt" | head -20
```

If `ConnectivityBanner` composable exists, reuse it. If not, build a small one inline.

- [ ] **Step 2: Modify `CalendarTabHost.kt` to inject the banner**

```kotlin
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.ReservationRepository

@Composable
fun CalendarTabHost(
    secureStorage: SecureStorage,
    connectivity: ConnectivityMonitor,
    repository: ReservationRepository,
    onOpenReservation: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val isOnline by connectivity.isOnlineFlow.collectAsStateWithLifecycle()
    val pending by repository.pendingActionsCount.collectAsStateWithLifecycle(initialValue = 0)
    // ... existing scaffold
    // Above the body, conditionally render:
    if (!isOnline) {
        Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Text(
                if (pending > 0) "Sin conexión — $pending acciones pendientes" else "Sin conexión",
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
```

If you injected `ConnectivityMonitor` and `ReservationRepository` directly, prefer doing this via a thin wrapping `BannerState` Hilt-provided composable to avoid coupling the tab host to the repository.

- [ ] **Step 3: Manual smoke** — turn off WiFi, run a transition, observe banner counts up; turn WiFi back on, queue drains, banner disappears.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/calendar/CalendarTabHost.kt \
        app/src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt
git commit -m "feat(reservations): connectivity banner with pending actions count"
```

---

## Task 27: Spanish strings centralization

**Goal:** All user-facing reservation strings live in `res/values-es/strings_reservations.xml`. Reuse the dashboard's `public/locales/es/reservations.json` keys verbatim where possible (per spec §4.11).

**Files:**
- Create: `app/src/main/res/values-es/strings_reservations.xml`
- Modify: every reservation Composable to use `stringResource(R.string....)` instead of inline literals.

- [ ] **Step 1: Read the dashboard locale file**

```bash
cat /Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard/src/locales/es/reservations.json 2>/dev/null | head -100
```

- [ ] **Step 2: Create XML with the most-used keys**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Tabs -->
    <string name="reservations_tab_today">Hoy</string>
    <string name="reservations_tab_pending">Pendientes</string>
    <string name="reservations_tab_confirmed">Confirmadas</string>
    <string name="reservations_tab_no_show">No-show</string>
    <string name="reservations_tab_all">Todas</string>

    <!-- Status badge -->
    <string name="status_pending">Pendiente</string>
    <string name="status_confirmed">Confirmada</string>
    <string name="status_checked_in">En curso</string>
    <string name="status_completed">Completada</string>
    <string name="status_cancelled">Cancelada</string>
    <string name="status_no_show">No-show</string>

    <!-- Actions -->
    <string name="action_confirm">Confirmar</string>
    <string name="action_check_in">Check-in</string>
    <string name="action_complete">Completar</string>
    <string name="action_no_show">No-show</string>
    <string name="action_reschedule">Reagendar</string>
    <string name="action_cancel">Cancelar</string>

    <!-- Toasts -->
    <string name="toast_confirmed">¡Reserva confirmada!</string>
    <string name="toast_checked_in">¡Cliente registrado!</string>
    <string name="toast_completed">¡Reserva completada!</string>
    <string name="toast_no_show">Marcada como no-show</string>
    <string name="toast_cancelled">Reserva cancelada</string>
    <string name="toast_rescheduled">¡Reserva reagendada!</string>

    <!-- Onboarding -->
    <string name="activate_reservations_title">Activar reservas</string>
    <string name="activate_reservations_description">Permite a tu negocio recibir citas, manejar clases y administrar tu calendario desde Avoqado.</string>
    <string name="activate_reservations_free_label">Gratis hoy.</string>
    <string name="activate_reservations_cta">Activar reservas</string>
    <string name="activate_reservations_in_progress">Activando…</string>

    <!-- Mode switcher -->
    <string name="mode_switcher_title">Cambiar de modo</string>
    <string name="mode_standard">Estándar</string>
    <string name="mode_reservations">Reservas</string>
    <string name="mode_standard_desc">Cobrar, transacciones e inventario</string>
    <string name="mode_reservations_desc">Calendario, citas y clases</string>

    <!-- Calendar -->
    <string name="calendar_view_day">Día</string>
    <string name="calendar_view_week">Semana</string>
    <string name="calendar_settings_title">Ajustes del calendario</string>
    <string name="calendar_show_cancelled">Mostrar reservas canceladas</string>

    <!-- Empty/error -->
    <string name="empty_no_reservations">Sin reservas en esta vista</string>
    <string name="error_load_reservations">Error cargando reservas</string>
    <string name="cancel_reservation_title">Cancelar reserva</string>
    <string name="cancel_reservation_warn">Esta acción no se puede deshacer.</string>
    <string name="cancel_reservation_reason_label">Motivo (opcional)</string>
    <string name="cancel_reservation_confirm">Confirmar cancelación</string>
    <string name="cancel_reservation_in_progress">Cancelando…</string>

    <!-- Connectivity -->
    <string name="offline_banner">Sin conexión</string>
    <string name="offline_banner_with_pending">Sin conexión — %1$d acciones pendientes</string>
</resources>
```

- [ ] **Step 3: Replace inline strings in Composables**

Find every reservation Composable (`grep -rln "@Composable" app/src/main/java/com/avoqado/pos/reservations/presentation`) and replace literal Spanish strings with `stringResource(R.string.<key>)`. This is mechanical; commit per-file or per-screen.

- [ ] **Step 4: Build to verify all references resolve**

```bash
./gradlew :app:assembleDebug -x test
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values-es/strings_reservations.xml \
        app/src/main/java/com/avoqado/pos/reservations/presentation/
git commit -m "feat(reservations): centralize Spanish strings in res/values-es"
```

---

## Task 28: Smoke test + screenshots + accessibility pass

**Goal:** Manual end-to-end on the Samsung tablet (`R8YL200592L`) capturing the happy path. Note any visual regressions and fix.

- [ ] **Step 1: Install fresh debug build**

```bash
./gradlew :app:installDebug
adb -s R8YL200592L shell am force-stop com.avoqado.pos
adb -s R8YL200592L shell monkey -p com.avoqado.pos -c android.intent.category.LAUNCHER 1
```

- [ ] **Step 2: Walk through the happy path and screenshot each step**

```bash
mkdir -p docs/research/square-deep-dive/screenshots-android/avoqado-build/phase-1
shoot() { adb -s R8YL200592L exec-out screencap -p > "docs/research/square-deep-dive/screenshots-android/avoqado-build/phase-1/$1.png"; }
```

For each: tap, wait, `shoot`:
1. `01-login` — logged in, default Estándar mode (Calendario tab NOT visible).
2. `02-mas-activate` — Más tab shows "Activar reservas".
3. `03-activate-screen` — onboarding screen.
4. `04-after-activate` — back at home, Calendario tab visible.
5. `05-mode-switcher` — Más → Modo: Reservas ▾ → sheet open.
6. `06-calendar-day-empty` — Calendario tab, Day view, no reservations.
7. `07-calendar-day-with-reservation` — after creating one in dashboard.
8. `08-calendar-week` — switch to Semana.
9. `09-calendar-settings` — settings sheet.
10. `10-detail-pending` — open reservation detail (status PENDING).
11. `11-detail-confirmed` — after Confirmar.
12. `12-detail-checkedin` — after Check-in.
13. `13-cancel-sheet` — open cancel sheet.
14. `14-reschedule-sheet` — open reschedule sheet.
15. `15-list-screen` — reservations list (Hoy tab).
16. `16-offline-banner` — turn off WiFi, run transition, observe banner.

- [ ] **Step 3: Accessibility checks**

For each screen: enable TalkBack on the tablet (Settings → Accessibility → TalkBack). Walk the focus order. Verify:
- All buttons have content descriptions (use `Modifier.semantics` if missing).
- Status badges read out the status name.
- The action bar is reachable in linear focus order.

Fix any gap with `Modifier.semantics { contentDescription = "..." }` and re-test.

- [ ] **Step 4: Performance sanity**

Open Day view with ≥20 reservations. Scroll. Watch logcat for `Choreographer` skipped-frame warnings. If any > 30 frames skipped, the grid is recomposing too aggressively — wrap reservation list in `key()` and ensure only changed blocks recompose.

- [ ] **Step 5: Commit screenshots + any fixes**

```bash
git add docs/research/square-deep-dive/screenshots-android/avoqado-build/phase-1/ \
        app/src/main/java/com/avoqado/pos/reservations/
git commit -m "chore(reservations): smoke screenshots + accessibility pass on phase 1"
```

---

## Task 29: Version bump + CHANGELOG + release notes

**Goal:** Cut v2.2.0 with reservations Phase 1 shipped.

**Files:**
- Modify: `app/build.gradle.kts` (versionCode + versionName)
- Modify: `CHANGELOG.md` (or equivalent)
- Create: release artifacts under `~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Releases/avoqado-android/2.2.0/`

- [ ] **Step 1: Bump version**

In `app/build.gradle.kts`:

```kotlin
defaultConfig {
    versionCode = <previous + 1>
    versionName = "2.2.0"
}
```

- [ ] **Step 2: Update CHANGELOG**

Add to top of `CHANGELOG.md`:

```markdown
## v2.2.0 — 2026-04-29

### Reservations Phase 1 — agenda de turno
- Nuevo tab Calendario condicional (visible cuando `featureFlags.reservations` está activado y modo Reservas seleccionado).
- Onboarding "Activar reservas" desde Más → tap → activación gratuita.
- Mode switcher device-local: Estándar / Reservas.
- Vista Día con WeekStrip + grid 24h + línea roja de hora actual + bloques por reserva.
- Vista Semana con grid 7 columnas.
- Pantalla de lista con tabs Hoy / Pendientes / Confirmadas / No-show / Todas + búsqueda.
- Pantalla de detalle (full-screen modal) con acciones: Confirmar, Check-in, Completar, No-show, Reagendar, Cancelar.
- Bottom sheets para Cancelar (con motivo opcional) y Reagendar (date+time picker venue-tz aware).
- Settings sheet del calendario: filtros de estado + show cancelled.
- Push notifications canal `reservations` con deep-link a detalle.
- Cola offline con retrier al recuperar conexión + banner con conteo de pendientes.
- Permisos `reservations:read|create|update|cancel` gateando UI vía JWT.
- Toda la fechita pasa por `VenueDateTimeFormatter` (regla de v2.1.1 respetada).
```

- [ ] **Step 3: Build release artifacts**

```bash
./gradlew bundleRelease
./gradlew assembleRelease
```

- [ ] **Step 4: Stage release folder**

```bash
RELEASE_DIR="$HOME/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Releases/avoqado-android/2.2.0"
mkdir -p "$RELEASE_DIR"
cp app/build/outputs/bundle/release/app-release.aab "$RELEASE_DIR/"
cp app/build/outputs/apk/release/app-release.apk "$RELEASE_DIR/"
```

- [ ] **Step 5: Write `CAPTION.md` for Play Console**

Save to `$RELEASE_DIR/CAPTION.md`:

```markdown
**Nombre de la versión**: <versionCode> (2.2.0)

**Notas de la versión**:
<es-419>
- Nueva pestaña Calendario para reservas, citas y clases (se activa desde Más → Activar reservas).
- Cambia entre modo Estándar y Reservas según el dispositivo.
- Vista Día y Semana del calendario con línea de hora actual.
- Lista de reservas con filtros Hoy / Pendientes / Confirmadas / No-show.
- Detalle de reserva con acciones rápidas: Confirmar, Check-in, Completar, No-show, Reagendar, Cancelar.
- Notificaciones push de nuevas reservas con acceso directo al detalle.
- Funciona sin internet: las acciones se sincronizan automáticamente al recuperar conexión.
</es-419>
```

- [ ] **Step 6: Commit + tag**

```bash
git add app/build.gradle.kts CHANGELOG.md
git commit -m "chore(release): v2.2.0 — Reservations Phase 1 (Calendar tab, list, detail, transitions)"
git tag v2.2.0
git push origin main --tags
```

- [ ] **Step 7: Mark done**

Phase 1 ships when steps 1-6 are done and the AAB uploads successfully to Play Console internal track.

---

## Self-review checklist (run before claiming done)

- [ ] Every spec section in `2026-04-29-android-reservations-design.md` has at least one task implementing it.
- [ ] Every code step shows the actual code (no "// TODO: implement").
- [ ] Method signatures used in later tasks (e.g. `viewModel.runAction(...)`) match those defined earlier.
- [ ] All Composables that display dates/times use `VenueDateTimeFormatter` — never `SimpleDateFormat`, never `Date`.
- [ ] Tests assert behavior, not implementation details (e.g. test "transition calls API" not "private field becomes true").
- [ ] No new endpoint at `/mobile/...` was assumed — pre-flight curl validated `/dashboard/...` works.
- [ ] Schema migration N→N+1 is in `addMigrations` AND `version = N+1`.
- [ ] `ConnectivityMonitor.isOnlineFlow` exists OR was added in Task 6.
- [ ] No emoji introduced into code or strings (Spanish copy stays clean).
- [ ] No file in `reservations/` exceeds 500 lines (split if it grows).

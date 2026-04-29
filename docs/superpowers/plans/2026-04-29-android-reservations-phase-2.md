# Reservations Phase 2 Implementation Plan — CRUD + Walk-in + Waitlist

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir a staff crear, editar y administrar reservas + lista de espera desde tablet/teléfono Android, alcanzando paridad funcional con el dashboard web para el flujo de hostess (~50 acciones/día).

**Architecture:** Multi-step modal full-screen (estilo Square `Crear cita`) compartido por Crear / Editar / Promover-de-Waitlist. Una sola `CreateReservationViewModel` con estado por paso + back/forward + validación. Reusa `CustomersRepository`, `ProductsRepository`, `StaffRepository` (ya existentes) y agrega `TablesRepository` + endpoints POST/PUT en `ReservationApi`. Waitlist es módulo paralelo (`waitlist/`) con su propio repo+screen+sheet. Walk-in = mismo flow con `channel = WALK_IN` y `startsAt = now()` pre-rellenados.

**Tech Stack:** Kotlin · Jetpack Compose Material3 · Hilt · Retrofit/OkHttp · kotlinx-serialization · existing `OfflineActionQueue` for create/update offline support.

---

## File Structure (overview)

**New (data):**
- `reservations/data/model/CreateReservationRequest.kt`
- `reservations/data/model/UpdateReservationRequest.kt`
- `reservations/data/model/WaitlistEntry.kt`
- `reservations/data/WaitlistApi.kt`
- `reservations/data/WaitlistRepository.kt`
- `tables/data/Table.kt`
- `tables/data/TablesRepository.kt`

**New (domain):**
- `reservations/domain/ReservationsCapability.kt`
- `reservations/domain/CreateReservationDraft.kt`

**New (presentation — create flow):**
- `reservations/presentation/create/CreateReservationViewModel.kt`
- `reservations/presentation/create/CreateReservationScreen.kt`
- `reservations/presentation/create/steps/CustomerStep.kt`
- `reservations/presentation/create/steps/ServiceStep.kt`
- `reservations/presentation/create/steps/DateTimeStep.kt`
- `reservations/presentation/create/steps/DetailsStep.kt`
- `reservations/presentation/create/steps/ConfirmStep.kt`
- `reservations/presentation/create/components/StepperHeader.kt`
- `reservations/presentation/create/components/CustomerQuickCreateSheet.kt`

**New (presentation — waitlist):**
- `reservations/presentation/waitlist/WaitlistViewModel.kt`
- `reservations/presentation/waitlist/WaitlistScreen.kt`
- `reservations/presentation/waitlist/AddWaitlistSheet.kt`

**Modified:**
- `reservations/data/ReservationApi.kt` — add `create()` + `update()` methods
- `reservations/data/ReservationRepository.kt` — add `createReservation()` + `updateReservation()` (offline queue)
- `reservations/data/PendingReservationActionEntity.kt` — add CREATE/UPDATE actions
- `reservations/presentation/calendar/CalendarTabHost.kt` — wire action sheet "Crear cita" → CreateReservationFlow
- `reservations/presentation/detail/ReservationDetailScreen.kt` — wire "Editar" → CreateReservationFlow with prefill
- `navigation/AvoqadoNavGraph.kt` — register routes `reservations/create`, `reservations/edit/{id}`, `waitlist`
- `app/src/main/res/values-es/strings.xml` — new copy
- `app/build.gradle.kts` — bump version to v2.3.0

---

## Task 1: Reservation create/update API surface + payload models

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/reservations/data/model/CreateReservationRequest.kt`
- Create: `app/src/main/java/com/avoqado/pos/reservations/data/model/UpdateReservationRequest.kt`
- Modify: `app/src/main/java/com/avoqado/pos/reservations/data/ReservationApi.kt`

- [ ] **Step 1: Write CreateReservationRequest model**

```kotlin
package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateReservationRequest(
    val customerId: String? = null,
    val guestName: String? = null,
    val guestPhone: String? = null,
    val guestEmail: String? = null,
    val partySize: Int,
    val startsAt: String, // ISO-8601 UTC
    val endsAt: String,
    val productId: String? = null,
    val classSessionId: String? = null,
    val tableId: String? = null,
    val assignedStaffId: String? = null,
    val channel: String = "DASHBOARD", // DASHBOARD | WALK_IN | PHONE | etc.
    val specialRequests: String? = null,
    val internalNotes: String? = null,
    val tags: List<String> = emptyList(),
)
```

- [ ] **Step 2: Write UpdateReservationRequest model**

```kotlin
package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UpdateReservationRequest(
    val customerId: String? = null,
    val guestName: String? = null,
    val guestPhone: String? = null,
    val guestEmail: String? = null,
    val partySize: Int? = null,
    val productId: String? = null,
    val tableId: String? = null,
    val assignedStaffId: String? = null,
    val specialRequests: String? = null,
    val internalNotes: String? = null,
    val tags: List<String>? = null,
)
```

- [ ] **Step 3: Add `create()` + `update()` methods to ReservationApi**

In `ReservationApi.kt`, add after `cancel()`:

```kotlin
suspend fun create(body: CreateReservationRequest): Result<Reservation> = call {
    val payload = json.encodeToString(CreateReservationRequest.serializer(), body).toRequestBody(jsonMedia)
    Request.Builder().url(base() ?: error("No venue")).post(payload).build()
}.mapCatching { json.decodeFromString(Reservation.serializer(), it) }

suspend fun update(id: String, body: UpdateReservationRequest): Result<Reservation> = call {
    val payload = json.encodeToString(UpdateReservationRequest.serializer(), body).toRequestBody(jsonMedia)
    Request.Builder().url("${base() ?: error("No venue")}/$id").put(payload).build()
}.mapCatching { json.decodeFromString(Reservation.serializer(), it) }
```

- [ ] **Step 4: Build & verify**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/data/model/CreateReservationRequest.kt \
        app/src/main/java/com/avoqado/pos/reservations/data/model/UpdateReservationRequest.kt \
        app/src/main/java/com/avoqado/pos/reservations/data/ReservationApi.kt
git commit -m "feat(reservations): add create/update API surface + payload models"
```

---

## Task 2: Repository createReservation / updateReservation with offline queue

**Files:**
- Modify: `app/src/main/java/com/avoqado/pos/reservations/data/ReservationRepository.kt`
- Modify: `app/src/main/java/com/avoqado/pos/reservations/domain/ReservationAction.kt`
- Modify: `app/src/main/java/com/avoqado/pos/reservations/data/ReservationActionsRetrier.kt`

- [ ] **Step 1: Extend ReservationAction enum**

In `ReservationAction.kt`, add `CREATE` and `UPDATE` to the enum.

- [ ] **Step 2: Extend `ActionPayload` sealed interface in ReservationRepository**

Add inside `sealed interface ActionPayload`:

```kotlin
data class Create(val request: CreateReservationRequest) : ActionPayload {
    override fun toJson(json: Json): String =
        json.encodeToString(CreateReservationRequest.serializer(), request)
}

data class Update(val request: UpdateReservationRequest) : ActionPayload {
    override fun toJson(json: Json): String =
        json.encodeToString(UpdateReservationRequest.serializer(), request)
}
```

- [ ] **Step 3: Add createReservation + updateReservation to ReservationRepository**

```kotlin
suspend fun createReservation(request: CreateReservationRequest): Result<Reservation?> {
    if (!connectivity.isOnline()) {
        pendingDao.enqueue(
            PendingReservationActionEntity(
                reservationId = "PENDING_NEW", // placeholder; resolved on retry
                action = ReservationAction.CREATE.name,
                payloadJson = ActionPayload.Create(request).toJson(json),
            ),
        )
        return Result.failure(OfflineEnqueuedException(ReservationAction.CREATE))
    }
    return api.create(request).map { it as Reservation? }
}

suspend fun updateReservation(id: String, request: UpdateReservationRequest): Result<Reservation?> {
    if (!connectivity.isOnline()) {
        pendingDao.enqueue(
            PendingReservationActionEntity(
                reservationId = id,
                action = ReservationAction.UPDATE.name,
                payloadJson = ActionPayload.Update(request).toJson(json),
            ),
        )
        return Result.failure(OfflineEnqueuedException(ReservationAction.UPDATE))
    }
    return api.update(id, request).map { it as Reservation? }
}
```

- [ ] **Step 4: Update ReservationActionsRetrier to handle CREATE / UPDATE**

In the retrier's `replay()` switch, add:
```kotlin
ReservationAction.CREATE -> {
    val req = json.decodeFromString(CreateReservationRequest.serializer(), entity.payloadJson!!)
    api.create(req)
}
ReservationAction.UPDATE -> {
    val req = json.decodeFromString(UpdateReservationRequest.serializer(), entity.payloadJson!!)
    api.update(entity.reservationId, req)
}
```

- [ ] **Step 5: Build & commit**

```bash
./gradlew :app:compileDebugKotlin
git add -A
git commit -m "feat(reservations): repository createReservation/updateReservation with offline queue"
```

---

## Task 3: TablesRepository + Table model (for table picker)

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/tables/data/Table.kt`
- Create: `app/src/main/java/com/avoqado/pos/tables/data/TablesRepository.kt`

- [ ] **Step 1: Write Table model**

```kotlin
package com.avoqado.pos.tables.data

import kotlinx.serialization.Serializable

@Serializable
data class Table(
    val id: String,
    val number: String,
    val capacity: Int? = null,
    val active: Boolean = true,
)
```

- [ ] **Step 2: Write TablesRepository**

```kotlin
package com.avoqado.pos.tables.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class TablesRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    @Named("apiBaseUrl") private val baseUrlProvider: () -> String,
) {
    private val tag = "🪑Tables"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchTables(): Result<List<Table>> = runCatching {
        val venue = secureStorage.venueId ?: error("No venue")
        val url = "${baseUrlProvider()}/dashboard/venues/$venue/tables"
        val req = Request.Builder().url(url).get().build()
        val (code, body) = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        }
        if (code !in 200..299) {
            Log.e(tag, "GET $url -> $code: ${body.take(200)}")
            error("HTTP $code")
        }
        when (val element = json.parseToJsonElement(body)) {
            is JsonArray -> json.decodeFromJsonElement(ListSerializer(Table.serializer()), element)
            is JsonObject -> {
                val arr = element["data"] ?: error("Unexpected tables shape")
                json.decodeFromJsonElement(ListSerializer(Table.serializer()), arr)
            }
            else -> error("Unexpected tables shape")
        }
    }
}
```

- [ ] **Step 3: Build & commit**

```bash
./gradlew :app:compileDebugKotlin
git add app/src/main/java/com/avoqado/pos/tables
git commit -m "feat(tables): add TablesRepository for reservation table picker"
```

---

## Task 4: CreateReservationDraft domain model + ViewModel skeleton (TDD)

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/reservations/domain/CreateReservationDraft.kt`
- Create: `app/src/main/java/com/avoqado/pos/reservations/presentation/create/CreateReservationViewModel.kt`
- Create: `app/src/test/java/com/avoqado/pos/reservations/presentation/create/CreateReservationViewModelTest.kt`

- [ ] **Step 1: Write CreateReservationDraft + Step enum**

```kotlin
package com.avoqado.pos.reservations.domain

import com.avoqado.pos.reservations.data.model.CreateReservationRequest
import com.avoqado.pos.reservations.data.model.ReservationChannel
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class CreateStep { CUSTOMER, SERVICE, DATETIME, DETAILS, CONFIRM }

data class CreateReservationDraft(
    val step: CreateStep = CreateStep.CUSTOMER,
    val customerId: String? = null,
    val customerName: String? = null,    // display
    val guestName: String? = null,
    val guestPhone: String? = null,
    val guestEmail: String? = null,
    val isGuest: Boolean = false,
    val productId: String? = null,
    val productName: String? = null,
    val durationMinutes: Int = 60,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.of(9, 0),
    val partySize: Int = 1,
    val tableId: String? = null,
    val tableNumber: String? = null,
    val assignedStaffId: String? = null,
    val assignedStaffName: String? = null,
    val specialRequests: String? = null,
    val internalNotes: String? = null,
    val channel: ReservationChannel = ReservationChannel.DASHBOARD,
) {
    val canContinueFromCustomer: Boolean
        get() = customerId != null || (isGuest && !guestName.isNullOrBlank())
    val canContinueFromService: Boolean
        get() = productId != null
    val canSubmit: Boolean
        get() = canContinueFromCustomer && canContinueFromService

    fun toRequest(zone: ZoneId): CreateReservationRequest {
        val startLocal = ZonedDateTime.of(date, time, zone)
        val endLocal = startLocal.plusMinutes(durationMinutes.toLong())
        val iso = DateTimeFormatter.ISO_INSTANT
        return CreateReservationRequest(
            customerId = customerId.takeUnless { isGuest },
            guestName = if (isGuest) guestName else null,
            guestPhone = if (isGuest) guestPhone else null,
            guestEmail = if (isGuest) guestEmail else null,
            partySize = partySize,
            startsAt = iso.format(startLocal.toInstant()),
            endsAt = iso.format(endLocal.toInstant()),
            productId = productId,
            tableId = tableId,
            assignedStaffId = assignedStaffId,
            channel = channel.name,
            specialRequests = specialRequests,
            internalNotes = internalNotes,
        )
    }
}
```

- [ ] **Step 2: Write failing unit test for draft → request conversion**

```kotlin
package com.avoqado.pos.reservations.presentation.create

import com.avoqado.pos.reservations.domain.CreateReservationDraft
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CreateReservationDraftTest {
    @Test
    fun `draft produces matching ISO timestamps in venue zone`() {
        val draft = CreateReservationDraft(
            customerId = "c1",
            productId = "p1",
            durationMinutes = 90,
            date = LocalDate.of(2026, 5, 1),
            time = LocalTime.of(19, 30),
            partySize = 4,
        )
        val req = draft.toRequest(ZoneId.of("America/Mexico_City"))
        // 19:30 CDMX (UTC-6 standard) = 01:30 UTC next day
        assertEquals("2026-05-02T01:30:00Z", req.startsAt)
        assertEquals("2026-05-02T03:00:00Z", req.endsAt)
        assertEquals(4, req.partySize)
        assertEquals("c1", req.customerId)
        assertEquals("p1", req.productId)
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests CreateReservationDraftTest`
Expected: PASS.

- [ ] **Step 3: Write CreateReservationViewModel**

```kotlin
package com.avoqado.pos.reservations.presentation.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.domain.CreateReservationDraft
import com.avoqado.pos.reservations.domain.CreateStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class CreateReservationViewModel @Inject constructor(
    private val repository: ReservationRepository,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    val zone: ZoneId get() = ZoneId.of(secureStorage.venueTimezone ?: "America/Mexico_City")

    private val _draft = MutableStateFlow(CreateReservationDraft())
    val draft: StateFlow<CreateReservationDraft> = _draft.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _result = MutableStateFlow<Result<Reservation>?>(null)
    val result: StateFlow<Result<Reservation>?> = _result.asStateFlow()

    fun update(transform: (CreateReservationDraft) -> CreateReservationDraft) {
        _draft.update(transform)
    }

    fun next() = _draft.update { d -> d.copy(step = nextStepOf(d.step)) }
    fun back() = _draft.update { d -> d.copy(step = prevStepOf(d.step)) }
    fun goTo(step: CreateStep) = _draft.update { it.copy(step = step) }

    fun submit() {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            val r = repository.createReservation(_draft.value.toRequest(zone))
            _isSubmitting.value = false
            _result.value = r.map { it ?: error("Empty reservation") }
        }
    }

    private fun nextStepOf(s: CreateStep): CreateStep = when (s) {
        CreateStep.CUSTOMER -> CreateStep.SERVICE
        CreateStep.SERVICE -> CreateStep.DATETIME
        CreateStep.DATETIME -> CreateStep.DETAILS
        CreateStep.DETAILS -> CreateStep.CONFIRM
        CreateStep.CONFIRM -> CreateStep.CONFIRM
    }

    private fun prevStepOf(s: CreateStep): CreateStep = when (s) {
        CreateStep.CUSTOMER -> CreateStep.CUSTOMER
        CreateStep.SERVICE -> CreateStep.CUSTOMER
        CreateStep.DATETIME -> CreateStep.SERVICE
        CreateStep.DETAILS -> CreateStep.DATETIME
        CreateStep.CONFIRM -> CreateStep.DETAILS
    }
}
```

- [ ] **Step 4: Build & commit**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "*CreateReservationDraft*"
git add app/src/main/java/com/avoqado/pos/reservations/domain/CreateReservationDraft.kt \
        app/src/main/java/com/avoqado/pos/reservations/presentation/create/CreateReservationViewModel.kt \
        app/src/test/java/com/avoqado/pos/reservations/presentation/create/CreateReservationDraftTest.kt
git commit -m "feat(reservations): CreateReservationDraft domain model + step VM"
```

---

## Task 5: Stepper header + container shell (CreateReservationScreen)

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/reservations/presentation/create/components/StepperHeader.kt`
- Create: `app/src/main/java/com/avoqado/pos/reservations/presentation/create/CreateReservationScreen.kt`

- [ ] **Step 1: Write StepperHeader composable**

5-dot indicator + step title + back/X icon at left + Continuar/Crear pill at right.

```kotlin
package com.avoqado.pos.reservations.presentation.create.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.avoqado.pos.reservations.domain.CreateStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepperHeader(
    step: CreateStep,
    canContinue: Boolean,
    isFirstStep: Boolean,
    isLastStep: Boolean,
    isSubmitting: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onContinue: () -> Unit,
) {
    val title = when (step) {
        CreateStep.CUSTOMER -> "Cliente"
        CreateStep.SERVICE -> "Servicio"
        CreateStep.DATETIME -> "Fecha y hora"
        CreateStep.DETAILS -> "Detalles"
        CreateStep.CONFIRM -> "Confirmar"
    }
    Column {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = if (isFirstStep) onClose else onBack) {
                    Icon(
                        if (isFirstStep) Icons.Filled.Close else Icons.Filled.ArrowBack,
                        contentDescription = if (isFirstStep) "Cerrar" else "Atrás",
                    )
                }
            },
            actions = {
                FilledTonalButton(
                    onClick = onContinue,
                    enabled = canContinue && !isSubmitting,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(if (isLastStep) "Crear" else "Continuar")
                }
                Spacer(Modifier.width(8.dp))
            },
        )
        StepDots(current = step.ordinal, total = CreateStep.entries.size)
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(total) { i ->
            val activeColor = MaterialTheme.colorScheme.primary
            val inactiveColor = MaterialTheme.colorScheme.outlineVariant
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .background(if (i <= current) activeColor else inactiveColor, CircleShape),
            )
        }
    }
}
```

- [ ] **Step 2: Write CreateReservationScreen container**

Wraps step content + handles result Toast + close-on-success.

```kotlin
package com.avoqado.pos.reservations.presentation.create

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.reservations.domain.CreateStep
import com.avoqado.pos.reservations.presentation.create.components.StepperHeader
import com.avoqado.pos.reservations.presentation.create.steps.*
import androidx.compose.foundation.layout.WindowInsets

@Composable
fun CreateReservationScreen(
    onClose: () -> Unit,
    viewModel: CreateReservationViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()

    var showSuccess by remember { mutableStateOf(false) }
    LaunchedEffect(result) {
        result?.onSuccess { showSuccess = true }
    }

    val canContinue = when (draft.step) {
        CreateStep.CUSTOMER -> draft.canContinueFromCustomer
        CreateStep.SERVICE -> draft.canContinueFromService
        CreateStep.DATETIME -> true
        CreateStep.DETAILS -> true
        CreateStep.CONFIRM -> draft.canSubmit
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            StepperHeader(
                step = draft.step,
                canContinue = canContinue,
                isFirstStep = draft.step == CreateStep.CUSTOMER,
                isLastStep = draft.step == CreateStep.CONFIRM,
                isSubmitting = isSubmitting,
                onBack = viewModel::back,
                onClose = onClose,
                onContinue = {
                    if (draft.step == CreateStep.CONFIRM) viewModel.submit()
                    else viewModel.next()
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (draft.step) {
                CreateStep.CUSTOMER -> CustomerStep(viewModel)
                CreateStep.SERVICE -> ServiceStep(viewModel)
                CreateStep.DATETIME -> DateTimeStep(viewModel)
                CreateStep.DETAILS -> DetailsStep(viewModel)
                CreateStep.CONFIRM -> ConfirmStep(viewModel)
            }
        }
    }

    AvoqadoSuccessToast(
        visible = showSuccess,
        message = "¡Reserva creada!",
        onDismiss = {
            showSuccess = false
            onClose()
        },
    )
}
```

- [ ] **Step 3: Build & commit**

```bash
./gradlew :app:compileDebugKotlin
git add app/src/main/java/com/avoqado/pos/reservations/presentation/create/
git commit -m "feat(reservations): create-reservation screen shell + stepper header"
```

---

## Task 6: CustomerStep — search existing + guest path + quick-create

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/reservations/presentation/create/steps/CustomerStep.kt`
- Create: `app/src/main/java/com/avoqado/pos/reservations/presentation/create/components/CustomerQuickCreateSheet.kt`

- [ ] **Step 1: Write CustomerStep** — `SearchPillField` at top, list of customers (filtered), "Continuar como invitado" toggle row, "+ Crear cliente" CTA opens `CustomerQuickCreateSheet`.

The list pulls from `CustomersRepository.fetchCustomers()`. Picking a customer sets `customerId`, `customerName`, clears `isGuest`.

- [ ] **Step 2: Write CustomerQuickCreateSheet** — `AvoqadoDialog` with `firstName`, `lastName`, `AvoqadoPhoneInput` (E.164), email; on Submit, `CustomersRepository.createCustomer(...)` → on success, set `customerId` and dismiss.

- [ ] **Step 3: Build, install, smoke-test on tablet (R8YL200592L)**

Tap calendar slot → action sheet → "Crear cita" → CustomerStep should show search + customers list + guest toggle.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/create/steps/CustomerStep.kt \
        app/src/main/java/com/avoqado/pos/reservations/presentation/create/components/CustomerQuickCreateSheet.kt
git commit -m "feat(reservations): customer step with search + guest path + quick-create"
```

---

## Task 7: ServiceStep — product/service list with duration + filter chips

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/reservations/presentation/create/steps/ServiceStep.kt`

- [ ] **Step 1: Write ServiceStep** — pulls from `ProductsRepository.products`, shows category filter chips (existing chip component) + scrollable list with rows: name + duration ("60 min") + price. Tap → set `productId`, `productName`, `durationMinutes`. The selected row gets a leading checkmark.

- [ ] **Step 2: Empty state** — if no products with `durationMinutes != null`, show illustration + "Configura servicios en el dashboard" + secondary CTA "Continuar sin servicio" (sets `productId = null`, `durationMinutes = 60`).

- [ ] **Step 3: Build & smoke + commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/create/steps/ServiceStep.kt
git commit -m "feat(reservations): service step with product/service picker"
```

---

## Task 8: DateTimeStep — date picker + 15-min time grid + availability hint

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/reservations/presentation/create/steps/DateTimeStep.kt`

- [ ] **Step 1: Write DateTimeStep**
  - Top: `DatePicker` (Material3) inline.
  - Below: 7-day strip (D L M M J V S) with current date selected.
  - Below: time grid — 15-min slots from venue's operating hours window (default 09:00–22:00 if unknown). Each slot is a 56dp pill in a 4-column grid.
  - Each slot shows availability indicator: green dot if free / amber if 1+ overlap. (Compute by fetching `repository.fetchCalendar(date, date)` once on entry; intersect with `[time, time+duration]`.)
  - Tap slot → `update { it.copy(time = slot) }`.

- [ ] **Step 2: Build & smoke + commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/create/steps/DateTimeStep.kt
git commit -m "feat(reservations): date-time step with availability hint"
```

---

## Task 9: DetailsStep — partySize + table + staff + notes

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/reservations/presentation/create/steps/DetailsStep.kt`

- [ ] **Step 1: Write DetailsStep**
  - PartySize stepper (− 1 +) capped at 1..50.
  - Table chip picker — pulls from `TablesRepository.fetchTables()` (Task 3). Optional, "Sin mesa" chip clears.
  - Staff chip picker — pulls from `StaffRepository.staff`. Optional, "Cualquiera" chip clears.
  - Two text fields:
    - "Solicitudes especiales" (bound to `specialRequests`).
    - "Notas internas (no visibles al cliente)" (bound to `internalNotes`).

- [ ] **Step 2: Build & smoke + commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/create/steps/DetailsStep.kt
git commit -m "feat(reservations): details step (partySize/table/staff/notes)"
```

---

## Task 10: ConfirmStep — summary card + submit

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/reservations/presentation/create/steps/ConfirmStep.kt`

- [ ] **Step 1: Write ConfirmStep** — Card with sections: Cliente / Servicio / Fecha y hora / Detalles. Each row has a leading icon, label, value, and an "Editar" pill that calls `viewModel.goTo(CreateStep.X)`.

When `Crear` is tapped (handled by header), VM submits. Show inline `LinearProgressIndicator` while `isSubmitting`. On error, show inline error banner with "Reintentar".

- [ ] **Step 2: Build & smoke + commit**

```bash
git add app/src/main/java/com/avoqado/pos/reservations/presentation/create/steps/ConfirmStep.kt
git commit -m "feat(reservations): confirm step with summary + submit"
```

---

## Task 11: Wire CalendarTabHost action sheet → CreateReservationFlow

**Files:**
- Modify: `app/src/main/java/com/avoqado/pos/reservations/presentation/calendar/CalendarTabHost.kt`
- Modify: `app/src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt`

- [ ] **Step 1: Register `reservations/create?date=&time=&channel=` route in NavGraph** that hosts `CreateReservationScreen` with optional NavArgs.

- [ ] **Step 2: Replace `showComingSoon(...)` calls in CalendarTabHost** so:
  - "Crear cita" → `navController.navigate("reservations/create?date=$d&time=$t")`
  - "Crear evento personal" → keep snackbar "Disponible en Fase 5"
  - "Crear clase" → keep snackbar "Disponible en Fase 3"

- [ ] **Step 3: CreateReservationViewModel reads NavArgs via SavedStateHandle** and pre-fills `date` and `time` on init.

- [ ] **Step 4: Build, install, smoke (tap slot → action sheet → "Crear cita" → step 1 with date/time prefilled).**

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(reservations): wire calendar action sheet to create flow"
```

---

## Task 12: Walk-in path — channel WALK_IN + time = now()

**Files:**
- Modify: `app/src/main/java/com/avoqado/pos/reservations/presentation/calendar/CalendarTabHost.kt`

- [ ] **Step 1:** Add `Add` icon in top-bar tap handler that navigates to `reservations/create?walkin=true`. ViewModel detects `walkin=true` and pre-fills:
  - `channel = WALK_IN`
  - `date = LocalDate.now(zone)`
  - `time = LocalTime.now(zone).truncatedTo(ChronoUnit.MINUTES)` rounded up to next 15-min
  - `isGuest = true`, `guestName = "Walk-in"`
  - Skip first step → start at `SERVICE`.

- [ ] **Step 2: Smoke** — tap `+` in calendar header → opens flow on Servicio step.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(reservations): walk-in quick path (channel WALK_IN + now)"
```

---

## Task 13: EditReservationScreen — reuse CreateFlow with prefill

**Files:**
- Modify: `app/src/main/java/com/avoqado/pos/reservations/presentation/create/CreateReservationViewModel.kt`
- Modify: `app/src/main/java/com/avoqado/pos/reservations/presentation/detail/ReservationDetailScreen.kt`
- Modify: `app/src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt`

- [ ] **Step 1: Add `editingId: String?` SavedStateHandle field** to VM. If non-null, `init { fetchReservation(id) → seed draft }`. The `submit()` then calls `repository.updateReservation(...)` instead of `createReservation(...)`.

- [ ] **Step 2: Add "Editar" button in ReservationDetailScreen action bar** (before Cancelar). Tap → `navController.navigate("reservations/edit/{id}")`.

- [ ] **Step 3: Register `reservations/edit/{id}` route** with NavArg.

- [ ] **Step 4: Hide step CUSTOMER from edit** (cliente locked once reservation exists). Stepper goes Service → DateTime → Details → Confirm (4 steps).

- [ ] **Step 5: Smoke + commit**

```bash
git add -A
git commit -m "feat(reservations): edit reservation reusing create flow with prefill"
```

---

## Task 14: WaitlistEntry model + API + repository

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/reservations/data/model/WaitlistEntry.kt`
- Create: `app/src/main/java/com/avoqado/pos/reservations/data/WaitlistApi.kt`
- Create: `app/src/main/java/com/avoqado/pos/reservations/data/WaitlistRepository.kt`

- [ ] **Step 1: WaitlistEntry model** — id, customerId/guestName, partySize, productId, requestedAt, notes, status (WAITING|PROMOTED|REMOVED), priority.

- [ ] **Step 2: WaitlistApi** — endpoints `GET /dashboard/venues/:id/waitlist`, `POST` (add), `PATCH /:entryId` (update status), `DELETE /:entryId`.

- [ ] **Step 3: WaitlistRepository** with `fetchWaitlist()`, `addEntry(...)`, `promote(entryId)`, `remove(entryId, reason)`.

- [ ] **Step 4: Build & commit**

```bash
git add -A
git commit -m "feat(waitlist): data layer for waitlist entries"
```

---

## Task 15: WaitlistScreen + AddWaitlistSheet + Promote flow

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/reservations/presentation/waitlist/WaitlistViewModel.kt`
- Create: `app/src/main/java/com/avoqado/pos/reservations/presentation/waitlist/WaitlistScreen.kt`
- Create: `app/src/main/java/com/avoqado/pos/reservations/presentation/waitlist/AddWaitlistSheet.kt`
- Modify: `app/src/main/java/com/avoqado/pos/reservations/presentation/calendar/CalendarSettingsSheet.kt` (or top-bar) — add link to Waitlist
- Modify: `app/src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt`

- [ ] **Step 1: WaitlistScreen** — list with filter chips (Esperando | Promovidos | Removidos), each row shows party + customer/guest + requestedAt relative time + actions (Promover / Quitar).

- [ ] **Step 2: AddWaitlistSheet** — customer search/guest path (subset of CustomerStep), partySize stepper, optional product, notes, save.

- [ ] **Step 3: Promote flow** — tap "Promover" on a row → navigate to `reservations/create?fromWaitlist=$entryId&date=today&time=next-slot` with prefilled customer/partySize/product. On successful create → `repository.promote(entryId)`.

- [ ] **Step 4: Wire entry point** — add "Lista de espera" entry in CalendarSettingsSheet (or as a top-bar action). Smoke + commit.

```bash
git add -A
git commit -m "feat(waitlist): screen + add sheet + promote flow"
```

---

## Task 16: Permission gating — JWT permissions[] → ReservationsCapability

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/reservations/domain/ReservationsCapability.kt`
- Modify: `app/src/main/java/com/avoqado/pos/auth/data/AuthRepository.kt` (already decodes JWT)
- Modify: `app/src/main/java/com/avoqado/pos/core/data/local/SecureStorage.kt`

- [ ] **Step 1: ReservationsCapability data class** with `canRead/canCreate/canUpdate/canCancel` booleans + factory `fromPermissions(set: Set<String>)`.

- [ ] **Step 2: Persist `permissions[]`** in `SecureStorage.permissions: Set<String>` on login (read from JWT claim).

- [ ] **Step 3: Provide capability via Hilt** + apply to UI:
  - `CalendarTabHost`: hide `+` icon if `!canCreate`.
  - `ReservationDetailScreen`: hide `Editar` if `!canUpdate`, hide `Cancelar` if `!canCancel`.
  - `CalendarSettingsSheet`: hide "Lista de espera" if `!canRead`.

- [ ] **Step 4: Smoke + commit**

```bash
git add -A
git commit -m "feat(reservations): JWT permissions[] -> capability gating"
```

---

## Task 17: Localization strings (es)

**Files:**
- Modify: `app/src/main/res/values-es/strings.xml`

- [ ] **Step 1: Extract all hardcoded Spanish strings** from CreateReservationScreen / steps / Waitlist into `<string>` resources.
- [ ] **Step 2: Replace literals in composables** with `stringResource(R.string.xxx)`.
- [ ] **Step 3: Build & commit**

```bash
git add -A
git commit -m "feat(reservations): centralize Spanish strings to values-es"
```

---

## Task 18: Smoke E2E on tablet (R8YL200592L)

**Files:** none (verification only)

- [ ] **Step 1: Install:** `./gradlew assembleDebug && adb -s R8YL200592L install -r app/build/outputs/apk/debug/app-debug.apk`
- [ ] **Step 2: Smoke checklist** — capture screenshots after each step (`/tmp/avoqado_p2_NN_*.png`):
  1. Open Calendario tab.
  2. Tap empty slot at 15:00 → action sheet.
  3. Tap "Crear cita" → step Cliente with date/time prefilled.
  4. Search customer → pick one → step Servicio.
  5. Pick service → step Fecha y hora (date/time prefilled, slot shown).
  6. Step Detalles: change partySize, pick table, add note.
  7. Step Confirmar: tap "Editar" on Servicio → returns to step 2 → Continuar back to Confirmar.
  8. Tap "Crear" → success toast → screen closes → reservation appears in calendar.
  9. Tap reservation → detail → "Editar" → modify partySize → save → calendar updates.
  10. Tap `+` in calendar header → walk-in flow (channel WALK_IN, now prefilled).
  11. Open Lista de espera → add entry → promote → CreateFlow opens prefilled → submit.
- [ ] **Step 3: Take screenshots, save to `/tmp/`, verify each step works.**
- [ ] **Step 4: If any step fails — open issue, do NOT proceed to release.**

---

## Task 19: Release v2.3.0

**Files:**
- Modify: `app/build.gradle.kts` — `versionCode` +1, `versionName = "2.3.0"`
- Create: AAB + APK in `~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Releases/avoqado-android/2.3.0/`
- Create: `2.3.0/CAPTION.md` per CLAUDE.md instructions.

- [ ] **Step 1: Bump version**
- [ ] **Step 2: `./gradlew bundleRelease assembleRelease`**
- [ ] **Step 3: Copy AAB + APK + write CAPTION.md (Spanish bullets):**
  - Crear reservas en 5 pasos desde el calendario.
  - Editar reservas existentes.
  - Walk-in en 3 toques desde botón `+`.
  - Lista de espera con promoción a reserva.
  - Permisos respetan tu rol del dashboard.
- [ ] **Step 4: Commit + tag**

```bash
git add app/build.gradle.kts
git commit -m "chore(release): v2.3.0 — Reservations Phase 2 (Crear/Editar/Walk-in/Waitlist)"
git tag v2.3.0
```

---

## Notes for the implementer

- **Branch:** `feat/reservations-phase-2` (already created off main).
- **Reuse, don't recreate:** `CustomersRepository.fetchCustomers()`, `ProductsRepository.products`, `StaffRepository.staff`, `AvoqadoDialog`, `AvoqadoPhoneInput`, `AvoqadoSuccessToast`, `SearchPillField`, `PrimaryButton` are already in the codebase. Don't write new versions.
- **TDD where it adds value:** Draft → request conversion (Task 4) and capability gating (Task 16) deserve unit tests. UI-only steps (6-10) ship with screenshot smoke tests, not Compose tests, to keep velocity.
- **Offline:** Create/Update both go through `OfflineActionQueue`. Test by toggling airplane mode mid-flow.
- **Timezone:** All ISO-8601 timestamps via `VenueDateTimeFormatter`. Never trust device-local time.
- **No new server endpoints:** Phase 2 reuses `/dashboard/venues/:id/reservations` (POST + PUT exist already per spec §6) and `/dashboard/venues/:id/waitlist` (verify in spike day-1 of Task 14).

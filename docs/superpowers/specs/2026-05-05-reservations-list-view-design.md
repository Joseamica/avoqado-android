# Reservations List View — Design Spec

**Status:** DRAFT — pending review
**Author:** Jose Antonio Amieva (with Claude Code)
**Date:** 2026-05-05
**Repos affected:** `avoqado-android` (100%)
**Estimated effort:** 1–2 days

---

## 1. Goal

Add a third calendar view (`LIST`) alongside the existing `DAY` and `WEEK` views, matching Square's reservation list pattern: a chronological feed of upcoming reservations grouped by day, optimized for staff who want a "what's next" overview without scrolling a time grid.

The current Calendar Settings sheet already has a "Lista" radio option marked **"Próximamente"** (`CalendarSettingsSheet.kt:114`). This spec activates it.

---

## 2. Context

### 2.1 What exists today

| Layer | Status |
|---|---|
| `CalendarView` enum (`DAY`, `WEEK`) | ✅ `CalendarUiState.kt` |
| `CalendarSettingsSheet.kt` already shows the disabled "Lista" radio with "Próximamente" trailing label | ✅ Lines 109–115 |
| Persistence of selected view per venue via `CalendarViewModel.setView()` | ✅ Already venue-scoped |
| Data fetching via `repository.fetchCalendar(dateFrom, dateTo)` returning `List<Reservation>` | ✅ `ReservationRepository.kt:54` |
| Reservation status filters (`visibleStatuses`, `showCancelled`) | ✅ Already wired in settings sheet |
| Tap-to-open reservation detail flow (`onOpenReservation(id)`) | ✅ Used by `CalendarTabHost.kt` |
| `CalendarViewModel.fetch()` window selection by view | ✅ Lines 127–148 — currently `DAY` = single day, `WEEK` = sun..sat |

### 2.2 What is missing

1. `CalendarView.LIST` enum value
2. Removing the `enabled = false` + "Próximamente" trailing in `CalendarSettingsSheet`
3. New `CalendarListView.kt` composable
4. New routing branch in `CalendarTabHost.kt` for `CalendarView.LIST`
5. Window adjustment in `CalendarViewModel.fetch()` for `LIST` view (longer window than DAY/WEEK)

### 2.3 Adjacent systems (do NOT touch)

| System | Why isolated |
|---|---|
| `CalendarDayView` / `CalendarWeekView` | Existing views unchanged. List is additive. |
| `CalendarTabHost` top bar (date header, settings, +) | Same chrome reused; only the body switches by view. |
| Drag-to-reschedule (Day/Week) | Not in List view (rows aren't time-positioned). No regression possible. |
| Reservation detail screen | Same `onOpenReservation(id)` callback. |

---

## 3. Architectural decisions

### 3.1 Q1 — Window size for LIST view

**Decision:** `[today, today + 30 days]`.

**Rationale:**
- 30 days covers ~95% of "what's next" use cases (typical advance-booking horizon for restaurants/spas/clinics).
- Server endpoint already accepts arbitrary date ranges; cost is the same as a 7-day fetch on a small venue.
- Avoids infinite scroll complexity in MVP.
- If a venue regularly books more than 30 days out and complains, we extend the window in a 5-line change.

**Alternatives rejected:**
- `[today, today + 7 days]` → too short; users would have to switch back to Week view to see next week's bookings.
- `[today, today + 90 days]` → bloats payload for venues with high booking volume; staff don't actually need that horizon at-a-glance.
- Infinite scroll with paginated fetches → unnecessary complexity for MVP; can be added in Phase 2 if metrics show users hit the 30-day wall often.

### 3.2 Q2 — Past reservations

**Decision:** **Excluded from MVP.** Window starts at `today` (00:00 venue timezone), not earlier.

**Rationale:**
- Mental model is "what's coming next". Past reservations live in the existing Transactions / History flow.
- If a user wants to see past reservations, Day/Week views with date navigation cover that.
- Phase 2 could add a "Pasadas" segmented control if asked for.

### 3.3 Q3 — Grouping & headers

**Decision:** `LazyColumn` with **sticky headers** per day. Headers use relative labels for today/tomorrow, absolute thereafter.

**Header format examples (Spanish):**
- "Hoy"
- "Mañana"
- "Mié 7 may"
- "Sáb 31 may"

**Rationale:**
- Sticky headers let the user jump 5 days down and still see "Sáb 31 may" pinned at the top while reading rows. Square uses the same pattern.
- Relative labels are the warm path; absolute labels are the precise path. Both should be present.
- Days with zero reservations are **not rendered** (no empty header). Avoids visual noise.

### 3.4 Q4 — Row content

**Decision:** Single-line row with the following layout:

```
[●]  19:30   María González · Corte de cabello       [confirmada]
     2 personas · Mesa 4
```

| Element | Content | Style |
|---|---|---|
| Status dot (8dp circle) | Color from `ReservationStatus.accentColor` | Aligned with hour |
| Hour (24h, `HH:mm`) | Bold, `titleMedium` | Fixed width 60dp |
| Customer name (or `guestName` for guests) | `bodyLarge` | `weight(1f)` truncates with ellipsis |
| Service name (`product.name`) | `bodyMedium`, `onSurfaceVariant` | Same weight, single line |
| Optional secondary line | `partySize personas · Mesa N` if non-default | `bodySmall`, `onSurfaceVariant` |
| Optional status pill | Only if non-CONFIRMED (e.g. "Pendiente", "Llegado", "Cancelada") | `labelSmall` chip with status color tint |

**Rationale:**
- Status dot + hour is the primary scan path (when does next thing start, what's its status).
- Customer + service is the secondary information (who/what).
- Party/table secondary line only renders when non-default to avoid clutter.

### 3.5 Q5 — Filters

**Decision:** Reuse the **existing settings sheet filters** (`visibleStatuses`, `showCancelled`) — no new filter UI in List view.

**Rationale:**
- Consistency: the same filters that hide CANCELLED in Day/Week should hide them in List.
- Avoids 3 sources of truth (per-view filters would diverge).

### 3.6 Q6 — Persistence

**Decision:** `CalendarView.LIST` selection persists per-venue using the **same mechanism** that already persists `DAY`/`WEEK` choice (commit `2bec056`: "feat(reservations/calendar): persist DAY/WEEK view per venue"). Zero new persistence code.

### 3.7 Q7 — Empty state

**Decision:** Centered illustration + text + CTA when window has zero reservations after filters apply:

```
        [📅 icon, 64dp, onSurfaceVariant]

   Sin reservas próximas
   Toca + para crear una

       [Crear reserva] (pill)
```

The "Crear reserva" CTA opens the same `ActionSheetCenter` flow that the top-bar `+` button opens.

### 3.8 Q8 — Pull-to-refresh

**Decision:** Yes. Wraps the LazyColumn in `Modifier.pullToRefresh`. On pull, call `viewModel.refresh()` (already exists, used by lifecycle `ON_RESUME`).

---

## 4. UX walkthrough

```
┌──────────────────────────────────────────────────┐
│  TopAppBar: "Mayo 2026"          [+]  [⋯]       │  ← unchanged
├──────────────────────────────────────────────────┤
│ ━━━━━━━━━━━━━ Hoy ━━━━━━━━━━━━━━  (sticky)      │
│  ●  19:30  María González · Corte de cabello     │
│            2 personas · Mesa 4                   │
│  ●  20:00  Juan Pérez · Yoga                     │
│  ●  21:15  Ana Ruiz · Masaje 60min  [pendiente]  │
│ ━━━━━━━━━ Mañana ━━━━━━━━━━━                    │
│  ●  09:00  Carlos Vega · Cryo                    │
│  ●  10:30  Lucía Mora · Pilates                  │
│ ━━━━━━━━━ Mié 7 may ━━━━━━━━━━                  │
│  ●  14:00  ...                                   │
└──────────────────────────────────────────────────┘
```

Interaction:
- Tap row → opens `ReservationDetailScreen` (existing flow via `onOpenReservation(id)`)
- Pull down → refresh
- Empty filtered result → empty state above
- Network error → existing `Snackbar` host pattern in `CalendarTabHost`

---

## 5. Implementation plan

### File changes

| File | Change | Lines |
|---|---|---|
| `presentation/calendar/CalendarUiState.kt` | Add `LIST` to `CalendarView` enum | +1 |
| `presentation/calendar/CalendarSettingsSheet.kt` | Replace lines 109–115: enable radio, drop "Próximamente" trailing | ~5 |
| `presentation/calendar/CalendarTabHost.kt` | Add `CalendarView.LIST -> CalendarListView(...)` branch in `when (state.view)` | +5 |
| `presentation/calendar/CalendarViewModel.kt` | Extend `fetch()` switch: `LIST -> today to today.plusDays(30)` | +3 |
| `presentation/calendar/CalendarListView.kt` | **New file** — full screen | ~250 |

### `CalendarListView.kt` skeleton

```kotlin
@Composable
fun CalendarListView(
    state: CalendarUiState,
    venueZone: ZoneId,
    onReservationClick: (Reservation) -> Unit,
    onCreateClick: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = state.today
    val grouped = remember(state.reservations, state.visibleStatuses, state.showCancelled) {
        state.reservations
            .filter { it.matchesFilters(state) }
            .filter { ZonedDateTime.parse(it.startsAt).withZoneSameInstant(venueZone).toLocalDate() >= today }
            .groupBy { ZonedDateTime.parse(it.startsAt).withZoneSameInstant(venueZone).toLocalDate() }
            .toSortedMap()
    }

    if (grouped.isEmpty()) {
        EmptyListState(onCreate = onCreateClick)
        return
    }

    val pullState = rememberPullToRefreshState()
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            onRefresh()
            pullState.endRefresh()
        }
    }

    Box(modifier.pullToRefresh(state = pullState)) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            grouped.forEach { (date, items) ->
                stickyHeader(key = date.toString()) {
                    DayHeader(date = date, today = today)
                }
                items(items, key = { it.id }) { reservation ->
                    ReservationListRow(
                        reservation = reservation,
                        venueZone = venueZone,
                        onClick = { onReservationClick(reservation) },
                    )
                }
            }
        }
        PullToRefreshContainer(state = pullState, modifier = Modifier.align(Alignment.TopCenter))
    }
}
```

### `DayHeader` composable

```kotlin
@Composable
private fun DayHeader(date: LocalDate, today: LocalDate) {
    val label = when (date) {
        today -> "Hoy"
        today.plusDays(1) -> "Mañana"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale("es")))
            .replaceFirstChar { it.uppercase() }
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                horizontal = AvoqadoTheme.spacing.lg,
                vertical = AvoqadoTheme.spacing.sm,
            ),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
```

### `ReservationListRow` composable

```kotlin
@Composable
private fun ReservationListRow(
    reservation: Reservation,
    venueZone: ZoneId,
    onClick: () -> Unit,
) {
    val time = ZonedDateTime.parse(reservation.startsAt).withZoneSameInstant(venueZone).toLocalTime()
    val timeLabel = time.format(DateTimeFormatter.ofPattern("HH:mm"))
    val statusColor = reservation.status.accentColor
    val secondary = listOfNotNull(
        if (reservation.partySize > 1) "${reservation.partySize} personas" else null,
        reservation.table?.number?.let { "Mesa $it" },
    ).joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Box(modifier = Modifier
            .padding(top = 6.dp)
            .size(8.dp)
            .background(statusColor, CircleShape))
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(60.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = reservation.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            reservation.displayServiceName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (secondary.isNotBlank()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (reservation.status != ReservationStatus.CONFIRMED) {
            StatusChip(status = reservation.status)
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = (AvoqadoTheme.spacing.lg + 8 + AvoqadoTheme.spacing.md).dp),
    )
}
```

---

## 6. Error handling

| Scenario | Behavior |
|---|---|
| Network failure during fetch | Existing snackbar via `CalendarTabHost`; list shows last-known data + "Sin conexión" banner (already present) |
| Empty after filters | Empty state composable (Section 3.7) |
| Reservation deleted while detail open | Existing flow handles this (returns to calendar) |
| Pull-to-refresh during ongoing fetch | Idempotent — repository handles double-call |
| Date crosses midnight while screen open | Recompose triggered by `state.today` Flow (already updates daily) — sticky header re-labels "Hoy" → "Mié 6 may" naturally |

---

## 7. Testing strategy

### Unit
- `groupReservationsByDay()` extracted as pure helper, tested with edge cases:
  - Multiple reservations same day → grouped
  - Reservation crossing midnight → bucketed by `startsAt` date in venue timezone
  - Empty input → empty map
  - Mixed timezones → all converted to venue zone before bucketing

### Integration
- `CalendarViewModel.fetch()` with `view = LIST` → asserts window is `[today, today.plusDays(30)]`
- Settings sheet → tapping "Lista" → `viewModel.setView(LIST)` → `state.view == LIST` after Save

### Manual smoke (physical SM-X133)
- Switch from Day to List → list renders with sticky headers
- Pull to refresh → loading indicator → data refreshed
- Tap a row → reservation detail opens; back returns to list with scroll preserved
- Toggle a status filter in settings → list updates after Save
- Empty venue → empty state with "Crear reserva" CTA works

---

## 8. Out of scope (deferred)

- ❌ Past reservations / segmented "Próximas / Pasadas / Todas"
- ❌ Search box inside the list (settings sheet filters cover it)
- ❌ Infinite scroll past +30d
- ❌ Class sessions in list (Phase 2 — see `2026-05-05-class-sessions-android-design.md`)
- ❌ Group by staff / table / status (only by date in MVP)
- ❌ Swipe actions on rows (cancel, check-in)
- ❌ Bulk select / batch operations

---

## 9. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| 30-day window returns 1000+ rows for high-volume venue | Low | Medium | LazyColumn handles thousands of rows. If real perf issue, paginate via `dateTo` chunking. |
| Sticky header flicker on scroll fast | Low | Low | Compose 1.5+ has stable sticky implementation; manual smoke covers this. |
| Date timezone bug bucketing reservation into wrong day | Low | High | Pure helper unit-tested with TZ scenarios. |
| Pull-to-refresh conflict with vertical scroll | Low | Low | `pullToRefresh` modifier handles this natively in M3. |

---

## 10. Approval criteria

This spec is ready to move to implementation when reviewer confirms:

- [ ] Section 3 decisions (window, grouping, row content, filters, empty state) are sound
- [ ] No conflict with the existing `CalendarSettingsSheet`/`CalendarViewModel` shape
- [ ] Skeleton code in Section 5 reads as the right approach
- [ ] Out-of-scope items in Section 8 are acceptable for MVP

If approved, implementation invokes the writing-plans skill to break Section 5 into bite-sized tasks.

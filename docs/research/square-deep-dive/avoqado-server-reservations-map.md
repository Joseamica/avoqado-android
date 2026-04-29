# Avoqado Server Reservation API — Complete Reference

**Last Updated:** 2026-04-29  
**Scope:** Dashboard & Public API endpoints for Reservations, ClassSessions, Waitlist, and Settings  
**Target Audience:** Android Port Implementation  

---

## Table of Contents

1. [Reservation Endpoints](#reservation-endpoints)
2. [Class Session Endpoints](#class-session-endpoints)
3. [Waitlist Endpoints](#waitlist-endpoints)
4. [Settings Endpoints](#settings-endpoints)
5. [Data Models (Prisma Schema)](#data-models-prisma-schema)
6. [Business Rules & Algorithms](#business-rules--algorithms)
7. [State Machine & Transitions](#state-machine--transitions)
8. [Availability Engine](#availability-engine)
9. [Deposit & Payment Modes](#deposit--payment-modes)

---

## Reservation Endpoints

All endpoints are prefixed with `/venues/:venueId/reservations` and require permission `reservations:read`, `reservations:create`, `reservations:update`, or `reservations:cancel` depending on the operation.

### GET /venues/:venueId/reservations
**Permission:** `reservations:read`

List all reservations for a venue with pagination, filtering, and search.

**Query Parameters:**
- `page` (number, default=1): Page number for pagination
- `pageSize` (number, default=50, max=100): Results per page
- `status` (string, optional): Single status or comma-separated statuses (PENDING, CONFIRMED, CHECKED_IN, COMPLETED, CANCELLED, NO_SHOW)
- `dateFrom` (date, optional): Filter by start date (ISO string coerced to Date)
- `dateTo` (date, optional): Filter by end date
- `tableId` (string, optional): Filter by table assignment
- `staffId` (string, optional): Filter by assigned staff
- `productId` (string, optional): Filter by product/service
- `channel` (string, optional): Single channel (DASHBOARD, WEB, PHONE, WHATSAPP, APP, WALK_IN, THIRD_PARTY)
- `search` (string, optional): Free-text search on guest name, phone, confirmation code, customer first/last name

**Response:**
```json
{
  "data": [
    {
      "id": "ckxyz...",
      "venueId": "...",
      "confirmationCode": "RES-A3X7K2",
      "cancelSecret": "uuid...",
      "status": "CONFIRMED",
      "channel": "DASHBOARD",
      "startsAt": "2026-05-15T18:00:00Z",
      "endsAt": "2026-05-15T20:00:00Z",
      "duration": 120,
      "customerId": "...",
      "customer": {
        "id": "...",
        "firstName": "Juan",
        "lastName": "Pérez",
        "phone": "+5212345678",
        "email": "juan@example.com"
      },
      "guestName": null,
      "guestPhone": null,
      "guestEmail": null,
      "partySize": 2,
      "spotIds": [],
      "tableId": "table-1",
      "table": { "id": "...", "number": "5", "capacity": 4 },
      "productId": "...",
      "product": { "id": "...", "name": "Cena", "price": "150.00" },
      "classSessionId": null,
      "assignedStaffId": "staff-1",
      "assignedStaff": { "id": "...", "firstName": "Carlos", "lastName": "López" },
      "depositAmount": "150.00",
      "depositStatus": "PENDING",
      "depositProcessorRef": null,
      "depositPaidAt": null,
      "depositRefundedAt": null,
      "specialRequests": "Sin cebolla",
      "internalNotes": "VIP client",
      "tags": ["vip", "anniversary"],
      "confirmedAt": "2026-05-10T10:00:00Z",
      "checkedInAt": null,
      "completedAt": null,
      "cancelledAt": null,
      "noShowAt": null,
      "cancelledBy": null,
      "cancellationReason": null,
      "createdById": "staff-2",
      "createdBy": { "id": "...", "firstName": "María", "lastName": "Sánchez" },
      "statusLog": [
        { "status": "CONFIRMED", "at": "2026-05-10T10:00:00Z", "by": "staff-2" }
      ],
      "createdAt": "2026-05-10T10:00:00Z",
      "updatedAt": "2026-05-10T10:00:00Z"
    }
  ],
  "meta": {
    "total": 456,
    "page": 1,
    "pageSize": 50,
    "totalPages": 10
  }
}
```

**Side Effects:** None; read-only.

---

### POST /venues/:venueId/reservations
**Permission:** `reservations:create`

Create a new reservation. Performs multi-layer double-booking prevention (table, staff, product capacity).

**Request Body:**
```json
{
  "startsAt": "2026-05-15T18:00:00Z",
  "endsAt": "2026-05-15T20:00:00Z",
  "duration": 120,
  "channel": "DASHBOARD",
  "customerId": "cust-123",
  "guestName": "Juan",
  "guestPhone": "+5212345678",
  "guestEmail": "juan@example.com",
  "partySize": 2,
  "tableId": "table-1",
  "productId": "service-1",
  "assignedStaffId": "staff-1",
  "specialRequests": "Sin cebolla",
  "internalNotes": "Preferred customer",
  "tags": ["vip"]
}
```

**Validation:**
- `startsAt` < `endsAt` (required)
- `duration` must match `(endsAt - startsAt) / 60000` (tolerance: ±1 min)
- `endsAt` > `startsAt`

**Response:** Same as GET/:id (see below)

**Side Effects:**
1. **Double-booking Prevention (3 Layers):**
   - Layer 1: Table overlap (FOR UPDATE NOWAIT) — checks for PENDING, CONFIRMED, CHECKED_IN reservations with same table, overlapping times
   - Layer 1b: Staff overlap — checks for conflicts on assigned staff member
   - Layer 3: Product capacity gate — if product has eventCapacity, sums party sizes of overlapping reservations and checks against effective capacity (product.eventCapacity * onlineCapacityPercent / 100)
2. **Confirmation Code Generation:** Unique `RES-` + 6-char alphanumeric (no 0/O/1/I confusion)
3. **Auto-Confirm:** If `scheduling.autoConfirm=true` (default), status = CONFIRMED; otherwise = PENDING
4. **Deposit Calculation:** If deposits enabled, calculates depositAmount based on mode, party size, and service price (stored in Prisma.Decimal)
5. **Status Log:** Appends initial status entry with timestamp and creator ID
6. **Activity Log:** RESERVATION_CREATED action logged

---

### GET /venues/:venueId/reservations/:id
**Permission:** `reservations:read`

Fetch a single reservation by ID.

**Response:** Full reservation object (see POST response above)

**Side Effects:** None.

---

### PUT /venues/:venueId/reservations/:id
**Permission:** `reservations:update`

Update reservation fields (only on PENDING or CONFIRMED reservations). Recalculates availability constraints.

**Request Body:**
```json
{
  "startsAt": "2026-05-15T19:00:00Z",
  "endsAt": "2026-05-15T21:00:00Z",
  "duration": 120,
  "guestName": "Juan Carlos",
  "guestPhone": "+5212345679",
  "guestEmail": "juanc@example.com",
  "partySize": 3,
  "tableId": "table-2",
  "productId": null,
  "assignedStaffId": null,
  "specialRequests": "Sin cebolla, sin queso",
  "internalNotes": "Updated notes",
  "tags": ["vip", "updated"]
}
```

**Validation:**
- Reservation must be in PENDING or CONFIRMED status
- If times modified, re-run double-booking checks (excluding self)
- If capacity modified, re-check product capacity constraints

**Response:** Updated reservation object

**Side Effects:**
1. Re-validates table, staff, and product capacity conflicts
2. Recalculates duration if times change
3. Logs RESERVATION_UPDATED action
4. Returns guarded update (only proceeds if status unchanged during update)

---

### DELETE /venues/:venueId/reservations/:id
**Permission:** `reservations:cancel`

Cancel a reservation (soft delete; status → CANCELLED).

**Request Body:** None

**Response:** Cancelled reservation object

**Side Effects:**
1. Transitions status to CANCELLED
2. Sets `cancelledBy = "Cancelada por staff"`, `cancelledAt = now()`
3. Attempts credit refund via creditPack service (wrapped in try/catch to prevent blocking)
4. Logs RESERVATION_CANCELLED action
5. Triggers ReservationWaitlistEntry matching for potential auto-promotion

---

### POST /venues/:venueId/reservations/:id/confirm
**Permission:** `reservations:update`

Confirm a PENDING reservation.

**Request Body:** None

**Response:** Confirmed reservation object

**Transition:** PENDING → CONFIRMED

**Side Effects:**
1. Sets `confirmedAt = now()`, status = CONFIRMED
2. Appends status log entry with confirmer ID
3. Logs RESERVATION_CONFIRMED action

---

### POST /venues/:venueId/reservations/:id/check-in
**Permission:** `reservations:update`

Check in a guest (reservation becomes active).

**Request Body:** None

**Response:** Checked-in reservation object

**Transition:** CONFIRMED → CHECKED_IN

**Side Effects:**
1. Sets `checkedInAt = now()`, status = CHECKED_IN
2. Appends status log
3. Logs RESERVATION_CHECKED_IN action

---

### POST /venues/:venueId/reservations/:id/complete
**Permission:** `reservations:update`

Mark a reservation as completed (service delivered).

**Request Body:** None

**Response:** Completed reservation object

**Transition:** CHECKED_IN → COMPLETED

**Side Effects:**
1. Sets `completedAt = now()`, status = COMPLETED
2. Appends status log
3. Logs RESERVATION_COMPLETED action

---

### POST /venues/:venueId/reservations/:id/no-show
**Permission:** `reservations:update`

Mark a guest as no-show.

**Request Body:** None

**Response:** No-show reservation object

**Transition:** CONFIRMED → NO_SHOW (or CHECKED_IN → NO_SHOW in some flows)

**Side Effects:**
1. Sets `noShowAt = now()`, status = NO_SHOW
2. Appends status log
3. If `settings.cancellation.creditNoShowRefund = true`, attempts full credit refund (policy: creditRefundMode='ALWAYS', creditFreeRefundHoursBefore=0, creditLateRefundPercent=100)
4. Logs RESERVATION_NO_SHOW action

---

### POST /venues/:venueId/reservations/:id/reschedule
**Permission:** `reservations:update`

Move a reservation to a different time slot (same or different table/staff, same or different product).

**Request Body:**
```json
{
  "startsAt": "2026-05-20T18:00:00Z",
  "endsAt": "2026-05-20T20:00:00Z"
}
```

**Validation:**
- Reservation must be PENDING or CONFIRMED
- New times must not conflict with other active reservations
- Re-checks product capacity if applicable

**Response:** Rescheduled reservation object

**Side Effects:**
1. Calls updateReservation internally
2. Recalculates duration based on new times
3. Logs RESERVATION_RESCHEDULED action with old/new times
4. Does NOT modify table/staff/product if not provided (stays the same)

---

### GET /venues/:venueId/reservations/stats
**Permission:** `reservations:read`

Get aggregated reservation statistics.

**Query Parameters:**
- `dateFrom` (date, required): Start of period
- `dateTo` (date, required): End of period

**Response:**
```json
{
  "total": 150,
  "byStatus": {
    "PENDING": 5,
    "CONFIRMED": 80,
    "CHECKED_IN": 10,
    "COMPLETED": 45,
    "CANCELLED": 8,
    "NO_SHOW": 2
  },
  "byChannel": {
    "DASHBOARD": 100,
    "WEB": 30,
    "PHONE": 15,
    "WHATSAPP": 5,
    "WALK_IN": 0
  },
  "noShowRate": 1.3
}
```

**Side Effects:** None.

---

### GET /venues/:venueId/reservations/calendar
**Permission:** `reservations:read`

Get reservations grouped by day (optionally by table or staff).

**Query Parameters:**
- `dateFrom` (date, required)
- `dateTo` (date, required)
- `groupBy` (string, optional): 'table' | 'staff' (returns both flat list and grouped)

**Response:**
```json
{
  "reservations": [...],
  "grouped": {
    "table-1": [...],
    "table-2": [...],
    "unassigned": [...]
  }
}
```

**Side Effects:** None.

---

### GET /venues/:venueId/reservations/availability
**Permission:** `reservations:read`

Compute available time slots for a given date, considering operating hours, existing reservations, table capacity, staff availability, and product capacity.

**Query Parameters:**
- `date` (string, required): YYYY-MM-DD in any timezone; re-interpreted as venue local
- `duration` (number, optional, min=5, max=480): Slot duration in minutes (default from settings.scheduling.defaultDurationMin)
- `partySize` (number, optional, min=1, max=100): Number of guests
- `tableId` (string, optional): Specific table to check
- `staffId` (string, optional): Specific staff member
- `productId` (string, optional): Specific product/service

**Response:**
```json
{
  "date": "2026-05-15",
  "slots": [
    {
      "startsAt": "2026-05-15T18:00:00Z",
      "endsAt": "2026-05-15T19:00:00Z",
      "availableTables": [
        { "id": "table-2", "number": "3", "capacity": 4 },
        { "id": "table-3", "number": "4", "capacity": 6 }
      ],
      "availableStaff": [
        { "id": "staff-1", "firstName": "Carlos", "lastName": "López" },
        { "id": "staff-3", "firstName": "Ana", "lastName": "García" }
      ]
    }
  ]
}
```

**Algorithm:**
1. Fetch venue timezone from Venue.timezone
2. Convert query date to venue local 00:00:00–23:59:59
3. Lookup operating hours for day-of-week from ReservationSettings.operatingHours
4. Generate slot start times at slotIntervalMin intervals within operating hours
5. Query existing PENDING/CONFIRMED/CHECKED_IN reservations overlapping the date range
6. For each candidate slot:
   - Skip if pacing limit exceeded (pacingMaxPerSlot)
   - Skip if product capacity exceeded (sum partySize of overlapping reservations)
   - Filter tables: exclude booked tables, filter by capacity if partySize provided
   - Filter staff: exclude busy staff
   - Add to results if any tables or staff remain available

**Side Effects:** None.

---

### GET /venues/:venueId/reservations/settings
**Permission:** `reservations:read`

Fetch current reservation settings for the venue.

**Response:** ReservationConfig object (see Settings Endpoints below)

**Side Effects:** None.

---

### PUT /venues/:venueId/reservations/settings
**Permission:** `reservations:update`

Update reservation settings (scheduling, deposits, cancellation, waitlist, reminders, public booking, operating hours).

**Request Body:** See [Settings Endpoints → PUT /settings](#put-venuesiduereservationssettings-1)

**Response:** Updated ReservationSettings object

**Side Effects:**
1. Upserts ReservationSettings row
2. Logs RESERVATION_SETTINGS_UPDATED action

---

## Class Session Endpoints

All endpoints are prefixed with `/venues/:venueId/class-sessions` and require appropriate permissions.

### GET /venues/:venueId/class-sessions
**Permission:** `reservations:read`

List all class sessions for a venue within a date range.

**Query Parameters:**
- `dateFrom` (date, required): Start date
- `dateTo` (date, required): End date
- `productId` (string, optional): Filter by product
- `status` (string, optional): SCHEDULED | CANCELLED | COMPLETED

**Response:**
```json
[
  {
    "id": "session-1",
    "venueId": "...",
    "productId": "class-yoga",
    "product": {
      "id": "...",
      "name": "Yoga Matinal",
      "price": "300.00",
      "duration": 60,
      "maxParticipants": 20
    },
    "startsAt": "2026-05-15T07:00:00Z",
    "endsAt": "2026-05-15T08:00:00Z",
    "duration": 60,
    "capacity": 15,
    "assignedStaffId": "instructor-1",
    "assignedStaff": { "id": "...", "firstName": "María", "lastName": "Yoga" },
    "status": "SCHEDULED",
    "internalNotes": "Bring mats",
    "createdById": "staff-1",
    "createdBy": { "id": "...", "firstName": "Carlos", "lastName": "López" },
    "reservations": [
      {
        "id": "res-1",
        "confirmationCode": "RES-A3X7K2",
        "status": "CONFIRMED",
        "partySize": 2,
        "guestName": "Juan",
        "guestPhone": "+5212345678",
        "guestEmail": "juan@example.com",
        "specialRequests": null,
        "customer": { "id": "...", "firstName": "Juan", "lastName": "Pérez", "phone": "+5212345678" }
      }
    ],
    "enrolled": 2,
    "available": 13,
    "createdAt": "2026-05-10T10:00:00Z",
    "updatedAt": "2026-05-10T10:00:00Z"
  }
]
```

**Side Effects:** None; includes active reservations and computed enrolled/available counts.

---

### POST /venues/:venueId/class-sessions
**Permission:** `reservations:create`

Create a single class session.

**Request Body:**
```json
{
  "productId": "class-yoga",
  "startsAt": "2026-05-15T07:00:00Z",
  "endsAt": "2026-05-15T08:00:00Z",
  "capacity": 15,
  "assignedStaffId": "instructor-1",
  "internalNotes": "Bring mats"
}
```

**Validation:**
- `productId` must exist and type = 'CLASS'
- `startsAt` < `endsAt`
- `startsAt` >= now - 60s (prevents scheduling in the past)
- `assignedStaffId` must belong to venue (if provided)

**Response:** Same as GET :sessionId (see below)

**Side Effects:**
1. Creates ClassSession with capacity and duration (computed from times)
2. Status = SCHEDULED by default
3. Logs CLASS_SESSION_CREATED action

---

### POST /venues/:venueId/class-sessions/bulk
**Permission:** `reservations:create`

Create recurring class sessions from a recurrence rule (e.g., "every Mon/Wed/Fri 7am–8am for 12 weeks").

**Request Body:**
```json
{
  "productId": "class-yoga",
  "startDate": "2026-05-17",
  "startTime": "07:00",
  "endTime": "08:00",
  "weekdays": [1, 3, 5],
  "occurrences": 12,
  "capacity": 15,
  "assignedStaffId": "instructor-1",
  "internalNotes": "Summer session"
}
```

**OR with endDate instead of occurrences:**
```json
{
  "productId": "class-yoga",
  "startDate": "2026-05-17",
  "startTime": "07:00",
  "endTime": "08:00",
  "weekdays": [1, 3, 5],
  "endDate": "2026-07-31",
  "capacity": 15
}
```

**Validation:**
- Exactly one of `endDate` or `occurrences` (not both)
- `weekdays` = [0=Sun, 1=Mon, ..., 6=Sat], must contain at least one
- `startTime` < `endTime`
- Max 104 occurrences (roughly 2 years of weekly)

**Algorithm:**
1. Convert startDate/endDate to venue timezone using Luxon (DateTime.fromISO with zone)
2. Walk through dates day-by-day in venue timezone
3. For each date matching a requested weekday, compute startsAt/endsAt in UTC by fromZonedTime
4. Skip sessions with startsAt < now (allows partial runs; useful for "start today" requests)
5. Skip sessions with startsAt already in database (conflict avoidance for reruns)
6. Batch-create in single transaction (REPEATABLE READ isolation)

**Response:**
```json
{
  "created": [
    { "id": "session-1", "startsAt": "2026-05-17T07:00:00Z", "endsAt": "2026-05-17T08:00:00Z" },
    ...
  ],
  "count": 12,
  "skipped": 0
}
```

**Side Effects:**
1. Transactionally creates N ClassSession rows
2. Logs CLASS_SESSION_BULK_CREATED action with created count, skipped count, weekdays

---

### GET /venues/:venueId/class-sessions/:sessionId
**Permission:** `reservations:read`

Fetch a single class session with attendees.

**Response:** Same as POST /class-sessions (see above)

**Side Effects:** None.

---

### PATCH /venues/:venueId/class-sessions/:sessionId
**Permission:** `reservations:update`

Update a class session (time, capacity, instructor, notes). Only on SCHEDULED sessions.

**Request Body:**
```json
{
  "startsAt": "2026-05-15T08:00:00Z",
  "endsAt": "2026-05-15T09:00:00Z",
  "capacity": 20,
  "assignedStaffId": "instructor-2",
  "internalNotes": "Moved to larger room"
}
```

**Validation:**
- Session status must be SCHEDULED
- If reducing capacity, must not go below current enrollment (sum of active partySize)
- If updating times, `startsAt` < `endsAt`
- `assignedStaffId` must belong to venue

**Response:** Updated ClassSession object

**Side Effects:**
1. Updates only provided fields
2. If times change, recalculates duration
3. Logs CLASS_SESSION_UPDATED action

---

### POST /venues/:venueId/class-sessions/:sessionId/cancel
**Permission:** `reservations:cancel`

Cancel a class session. Cascades to all active reservations.

**Request Body:** None

**Response:** Cancelled ClassSession object

**State Transition:** SCHEDULED → CANCELLED

**Side Effects:**
1. Sets status = CANCELLED
2. Cancels all PENDING/CONFIRMED/CHECKED_IN reservations for the session
3. Sets reservation status = CANCELLED, cancelledBy = 'SYSTEM', cancellationReason = 'Sesión cancelada por el establecimiento'
4. Logs CLASS_SESSION_CANCELLED action
5. No credit refunds (system cancellation covers all customers equally; venue handles refunds separately)

---

### POST /venues/:venueId/class-sessions/:sessionId/attendees
**Permission:** `reservations:create`

Add an attendee (create a class reservation) to a session via dashboard.

**Request Body:**
```json
{
  "guestName": "María",
  "guestPhone": "+5212345678",
  "guestEmail": "maria@example.com",
  "partySize": 2,
  "customerId": "cust-123",
  "specialRequests": "Beginner level"
}
```

**Validation:**
- `guestName` required (min 1, max 255)
- `partySize` defaults to 1; min 1
- `customerId` must belong to venue (if provided)

**Algorithm:**
1. Use withSerializableRetry (SERIALIZABLE isolation)
2. Lock ClassSession row (FOR UPDATE)
3. Verify session exists, belongs to venue, status = SCHEDULED
4. Sum enrolled from active reservations
5. Check enrolled + partySize <= session.capacity
6. Generate unique confirmation code
7. Create Reservation with status = CONFIRMED, channel = DASHBOARD, startsAt/endsAt from session

**Response:** Created Reservation object

**Side Effects:**
1. Creates Reservation linked to ClassSession
2. Confirms immediately (staff dashboard action)
3. Logs implicit RESERVATION_CREATED action (via reservation service)
4. Race-safe via SERIALIZABLE isolation + row lock

---

### DELETE /venues/:venueId/class-sessions/:sessionId/attendees/:reservationId
**Permission:** `reservations:cancel`

Remove an attendee from a class session (cancel their reservation).

**Request Body:** None

**Response:** 204 No Content

**Side Effects:**
1. Sets reservation status = CANCELLED, cancelledAt = now(), cancelledBy = 'STAFF'
2. Removes attendee from session; capacity restored

---

## Waitlist Endpoints

All endpoints are prefixed with `/venues/:venueId/reservations/waitlist` and require appropriate permissions.

### GET /venues/:venueId/reservations/waitlist
**Permission:** `reservations:read`

List waitlist entries for a venue.

**Query Parameters:**
- `status` (string, optional): WAITING | NOTIFIED | PROMOTED | EXPIRED | CANCELLED

**Response:**
```json
[
  {
    "id": "wl-1",
    "venueId": "...",
    "customerId": "cust-456",
    "customer": { "id": "...", "firstName": "Ana", "lastName": "García", "phone": "+5219876543" },
    "guestName": null,
    "guestPhone": null,
    "partySize": 4,
    "desiredStartAt": "2026-05-15T19:00:00Z",
    "desiredEndAt": "2026-05-15T21:00:00Z",
    "position": 1,
    "status": "WAITING",
    "notifiedAt": null,
    "responseDeadline": null,
    "promotedReservationId": null,
    "promotedReservation": null,
    "notes": "Prefers window table",
    "createdAt": "2026-05-12T14:30:00Z",
    "updatedAt": "2026-05-12T14:30:00Z"
  }
]
```

**Side Effects:** None; read-only.

---

### POST /venues/:venueId/reservations/waitlist
**Permission:** `reservations:create`

Add a guest to the waitlist.

**Request Body:**
```json
{
  "customerId": "cust-456",
  "guestName": "Ana",
  "guestPhone": "+5219876543",
  "partySize": 4,
  "desiredStartAt": "2026-05-15T19:00:00Z",
  "desiredEndAt": "2026-05-15T21:00:00Z",
  "notes": "Prefers window table"
}
```

**Validation:**
- `customerId` OR `guestName` required
- Waitlist must not exceed maxSize (default 50)

**Algorithm:**
1. Check waitlist enabled in settings
2. Count WAITING entries; reject if >= maxSize
3. Calculate position based on priorityMode (see below)
4. Create entry with position

**Position Calculation:**
- **FIFO:** position = max(position) + 1 (simple sequential)
- **PARTY_SIZE:** position = partySize * 100 + count_of_same_size + 1 (smaller parties get priority)
- **BROADCAST:** position = 0 (no ordering; all notified simultaneously)

**Response:** Created ReservationWaitlistEntry

**Side Effects:**
1. Creates entry with calculated position
2. Logs WAITLIST_ADDED action

---

### DELETE /venues/:venueId/reservations/waitlist/:entryId
**Permission:** `reservations:cancel`

Remove a waitlist entry (customer cancels or times out).

**Request Body:** None

**Response:** 204 No Content

**Side Effects:**
1. Sets status = CANCELLED
2. Logs WAITLIST_REMOVED action

---

### POST /venues/:venueId/reservations/waitlist/:entryId/promote
**Permission:** `reservations:update`

Promote a waitlist entry to a reservation (staff converts them).

**Request Body:**
```json
{
  "reservationId": "res-123"
}
```

**Validation:**
- Entry must exist and status in [WAITING, NOTIFIED]
- Reservation must exist and belong to same venue

**Response:** Updated ReservationWaitlistEntry with promotedReservationId set

**Side Effects:**
1. Sets status = PROMOTED, promotedReservationId = provided reservation ID
2. Links entry to the confirmed reservation
3. Logs WAITLIST_PROMOTED action

---

## Settings Endpoints

Settings control venue-wide reservation behavior.

### GET /venues/:venueId/reservations/settings
**Permission:** `reservations:read`

Fetch reservation settings (scheduling, deposits, cancellation, waitlist, reminders, public booking, operating hours).

**Response:**
```json
{
  "id": "settings-1",
  "venueId": "...",
  "scheduling": {
    "slotIntervalMin": 15,
    "defaultDurationMin": 60,
    "autoConfirm": true,
    "maxAdvanceDays": 60,
    "minNoticeMin": 60,
    "noShowGraceMin": 15,
    "pacingMaxPerSlot": null,
    "onlineCapacityPercent": 100
  },
  "deposits": {
    "enabled": true,
    "mode": "card_hold",
    "percentageOfTotal": 50,
    "fixedAmount": null,
    "requiredForPartySizeGte": 4,
    "paymentWindowHrs": 24
  },
  "cancellation": {
    "allowCustomerCancel": true,
    "minHoursBeforeStart": 2,
    "forfeitDeposit": false,
    "noShowFeePercent": null,
    "creditRefundMode": "TIME_BASED",
    "creditFreeRefundHoursBefore": 12,
    "creditLateRefundPercent": 50,
    "creditNoShowRefund": false,
    "allowCustomerReschedule": true
  },
  "waitlist": {
    "enabled": true,
    "maxSize": 50,
    "priorityMode": "fifo",
    "notifyWindowMin": 30
  },
  "reminders": {
    "enabled": true,
    "channels": ["EMAIL"],
    "minutesBefore": [1440, 120]
  },
  "publicBooking": {
    "enabled": false,
    "requirePhone": true,
    "requireEmail": false
  },
  "operatingHours": {
    "monday": { "enabled": true, "ranges": [{ "open": "09:00", "close": "22:00" }] },
    "tuesday": { "enabled": true, "ranges": [{ "open": "09:00", "close": "22:00" }] },
    "wednesday": { "enabled": true, "ranges": [{ "open": "09:00", "close": "22:00" }] },
    "thursday": { "enabled": true, "ranges": [{ "open": "09:00", "close": "22:00" }] },
    "friday": { "enabled": true, "ranges": [{ "open": "09:00", "close": "22:00" }] },
    "saturday": { "enabled": true, "ranges": [{ "open": "09:00", "close": "22:00" }] },
    "sunday": { "enabled": false, "ranges": [] }
  }
}
```

**Side Effects:** None; creates defaults if not found.

---

### PUT /venues/:venueId/reservations/settings
**Permission:** `reservations:update`

Update reservation settings. Supports both flat and nested payload structures.

**Request Body (Nested Structure - Preferred):**
```json
{
  "scheduling": {
    "slotIntervalMin": 30,
    "defaultDurationMin": 90,
    "autoConfirm": false,
    "maxAdvanceDays": 90,
    "minNoticeMin": 120
  },
  "deposits": {
    "mode": "deposit",
    "percentageOfTotal": 25,
    "fixedAmount": null,
    "requiredForPartySizeGte": 6
  },
  "cancellation": {
    "allowCustomerCancel": true,
    "minHoursBeforeStart": 24,
    "creditRefundMode": "ALWAYS"
  },
  "waitlist": {
    "enabled": true,
    "maxSize": 100,
    "priorityMode": "party_size"
  },
  "operatingHours": {
    "monday": { "enabled": true, "ranges": [{ "open": "10:00", "close": "23:00" }] },
    ...
  }
}
```

**OR Flat Structure (Legacy):**
```json
{
  "slotIntervalMin": 30,
  "defaultDurationMin": 90,
  "autoConfirm": false,
  "depositMode": "deposit",
  "depositPercentage": 25,
  "allowCustomerCancel": true,
  "minHoursBeforeStart": 24,
  "waitlistEnabled": true,
  "waitlistMaxSize": 100
}
```

**Validation:**
- slotIntervalMin: 5–480
- defaultDurationMin: 5–480
- maxAdvanceDays: 0–365
- minNoticeMin: 0–10080
- noShowGraceMin: 0–240
- pacingMaxPerSlot: 1–1000 or null
- onlineCapacityPercent: 1–100
- depositPercentage: 0–100
- minHoursBeforeCancel: 0–720
- depositPaymentWindow: 1–168 hours
- waitlistMaxSize: 1–5000
- creditRefundMode: NEVER | ALWAYS | TIME_BASED
- creditFreeRefundHoursBefore: 0–720
- creditLateRefundPercent: 0–100

**Response:** Updated ReservationSettings object

**Side Effects:**
1. Upserts ReservationSettings (creates if not exists)
2. Logs RESERVATION_SETTINGS_UPDATED action

---

## Data Models (Prisma Schema)

### Reservation

```prisma
model Reservation {
  id      String @id @default(cuid())
  venueId String
  venue   Venue  @relation(fields: [venueId], references: [id], onDelete: Cascade)

  confirmationCode String
  cancelSecret     String @default(uuid())

  status  ReservationStatus  @default(PENDING)
  channel ReservationChannel @default(DASHBOARD)

  startsAt DateTime
  endsAt   DateTime
  duration Int // Minutes

  customerId String?
  customer   Customer? @relation(fields: [customerId], references: [id], onDelete: SetNull)
  guestName  String?
  guestPhone String?
  guestEmail String?
  partySize  Int       @default(1)
  spotIds    String[]  @default([])

  tableId         String?
  table           Table?        @relation(fields: [tableId], references: [id], onDelete: SetNull)
  productId       String?
  product         Product?      @relation(fields: [productId], references: [id], onDelete: SetNull)
  classSessionId  String?
  classSession    ClassSession? @relation(fields: [classSessionId], references: [id], onDelete: SetNull)
  assignedStaffId String?
  assignedStaff   Staff?        @relation("ReservationStaff", fields: [assignedStaffId], references: [id], onDelete: SetNull)

  depositAmount       Decimal?       @db.Decimal(10, 2)
  depositStatus       DepositStatus?
  depositProcessorRef String?
  depositPaidAt       DateTime?
  depositRefundedAt   DateTime?

  createdById String?
  createdBy   Staff?  @relation("ReservationCreatedBy", fields: [createdById], references: [id], onDelete: SetNull)

  confirmedAt DateTime?
  checkedInAt DateTime?
  completedAt DateTime?
  cancelledAt DateTime?
  noShowAt    DateTime?

  cancelledBy        String?
  cancellationReason String? @db.Text

  specialRequests String?  @db.Text
  internalNotes   String?  @db.Text
  tags            String[] @default([])

  statusLog Json?
  waitlistPromotion ReservationWaitlistEntry?
  creditTransactions CreditTransaction[]

  createdAt DateTime @default(now())
  updatedAt DateTime @updatedAt

  @@unique([venueId, confirmationCode])
  @@index([venueId, status, startsAt])
  @@index([venueId, startsAt, endsAt])
  @@index([venueId, tableId, startsAt])
  @@index([venueId, productId, startsAt])
  @@index([venueId, classSessionId])
  @@index([venueId, assignedStaffId, startsAt])
  @@index([customerId])
  @@index([cancelSecret])
}

enum ReservationStatus {
  PENDING
  CONFIRMED
  CHECKED_IN
  COMPLETED
  CANCELLED
  NO_SHOW
}

enum ReservationChannel {
  DASHBOARD
  WEB
  PHONE
  WHATSAPP
  APP
  WALK_IN
  THIRD_PARTY
}

enum DepositStatus {
  PENDING
  PAID
  REFUNDED
}
```

---

### ClassSession

```prisma
model ClassSession {
  id      String @id @default(cuid())
  venueId String
  venue   Venue  @relation(fields: [venueId], references: [id], onDelete: Cascade)

  productId String
  product   Product @relation(fields: [productId], references: [id], onDelete: Cascade)

  startsAt DateTime
  endsAt   DateTime
  duration Int

  capacity Int
  assignedStaffId String?
  assignedStaff   Staff?  @relation("ClassSessionStaff", fields: [assignedStaffId], references: [id], onDelete: SetNull)

  status ClassSessionStatus @default(SCHEDULED)
  internalNotes String? @db.Text

  reservations Reservation[]

  createdById String?
  createdBy   Staff?  @relation("ClassSessionCreatedBy", fields: [createdById], references: [id], onDelete: SetNull)

  createdAt DateTime @default(now())
  updatedAt DateTime @updatedAt

  @@index([venueId, startsAt])
  @@index([venueId, productId, startsAt])
  @@index([venueId, status, startsAt])
}

enum ClassSessionStatus {
  SCHEDULED
  CANCELLED
  COMPLETED
}
```

---

### ReservationWaitlistEntry

```prisma
model ReservationWaitlistEntry {
  id      String @id @default(cuid())
  venueId String
  venue   Venue  @relation(fields: [venueId], references: [id], onDelete: Cascade)

  customerId String?
  customer   Customer? @relation(fields: [customerId], references: [id], onDelete: SetNull)
  guestName  String?
  guestPhone String?

  partySize      Int            @default(1)
  desiredStartAt DateTime
  desiredEndAt   DateTime?
  status         WaitlistStatus @default(WAITING)
  position       Int

  notifiedAt       DateTime?
  responseDeadline DateTime?

  promotedReservationId String?      @unique
  promotedReservation   Reservation? @relation(fields: [promotedReservationId], references: [id], onDelete: SetNull)

  notes String? @db.Text

  createdAt DateTime @default(now())
  updatedAt DateTime @updatedAt

  @@index([venueId, status, desiredStartAt])
  @@index([venueId, status, partySize])
}

enum WaitlistStatus {
  WAITING
  NOTIFIED
  PROMOTED
  EXPIRED
  CANCELLED
}
```

---

### ReservationSettings

```prisma
model ReservationSettings {
  id      String @id @default(cuid())
  venueId String @unique
  venue   Venue  @relation(fields: [venueId], references: [id], onDelete: Cascade)

  // Scheduling
  slotIntervalMin    Int     @default(15)
  defaultDurationMin Int     @default(60)
  autoConfirm        Boolean @default(true)
  maxAdvanceDays     Int     @default(60)
  minNoticeMin       Int     @default(60)
  noShowGraceMin     Int     @default(15)
  pacingMaxPerSlot   Int?
  onlineCapacityPercent Int  @default(100)

  // Deposits
  depositMode          String   @default("none")
  depositFixedAmount   Decimal? @db.Decimal(10, 2)
  depositPercentage    Int?
  depositPartySizeGte  Int?
  depositPaymentWindow Int?

  // Waitlist
  waitlistEnabled      Boolean @default(true)
  waitlistMaxSize      Int     @default(50)
  waitlistPriorityMode String  @default("fifo")
  waitlistNotifyWindow Int     @default(30)

  // Public booking
  publicBookingEnabled Boolean @default(false)
  requirePhone         Boolean @default(true)
  requireEmail         Boolean @default(false)

  // Cancellation
  allowCustomerCancel  Boolean @default(true)
  minHoursBeforeCancel Int?    @default(2)
  forfeitDeposit       Boolean @default(false)
  noShowFeePercent     Int?
  allowCustomerReschedule Boolean @default(true)
  creditRefundMode    String  @default("TIME_BASED")
  creditFreeRefundHoursBefore Int @default(12)
  creditLateRefundPercent Int  @default(0)
  creditNoShowRefund   Boolean @default(false)

  // Reminders
  remindersEnabled  Boolean  @default(true)
  reminderChannels  String[] @default(["EMAIL"])
  reminderMinBefore Int[]    @default([1440, 120])

  // Operating hours
  operatingHours Json?

  createdAt DateTime @default(now())
  updatedAt DateTime @updatedAt
}
```

---

## Business Rules & Algorithms

### Double-Booking Prevention

**Three-Layer Validation (via `withSerializableRetry` transaction):**

1. **Table Overlap Check (FOR UPDATE NOWAIT)**
   - Query existing PENDING/CONFIRMED/CHECKED_IN reservations where:
     - Same table
     - Time ranges overlap: existing.startsAt < new.endsAt AND existing.endsAt > new.startsAt
   - If any found, throw ConflictError

2. **Staff Overlap Check (FOR UPDATE NOWAIT)**
   - Same logic as table, but on assignedStaffId
   - Prevents double-booking of staff members

3. **Product Capacity Check (FOR UPDATE without NOWAIT)**
   - If product has eventCapacity set:
     - Calculate effective capacity: product.eventCapacity * onlineCapacityPercent / 100
     - Sum partySize of overlapping active reservations
     - Check: occupiedSeats + requestedPartySize <= effectiveCapacity
     - If exceeded, throw ConflictError

**Race Safety:**
- Uses SERIALIZABLE isolation with exponential backoff retry (P2034 handling)
- Max 5 retries with 50–1600ms delays
- Prevents concurrent updates from double-counting capacity

---

### Availability Algorithm

**Compute available time slots for a requested date:**

1. **Timezone Resolution:**
   - Parse query date as venue local time (YYYY-MM-DD format re-interpreted in venue.timezone)
   - Extract day-of-week (Monday–Sunday)

2. **Operating Hours Lookup:**
   - Get operatingHours[dayKey] from ReservationSettings
   - If not found, use defaults (Mon–Sat 09:00–22:00, Sun closed)
   - If day closed (enabled=false), return empty slots

3. **Slot Generation:**
   - For each time range in daySchedule.ranges:
     - Convert range.open and range.close from venue local to UTC via fromZonedTime
     - Generate slot start times at slotIntervalMin intervals
     - Each slot duration = defaultDurationMin
     - Include slot if slot.start + duration <= range.end

4. **Overlap Detection:**
   - Query all PENDING/CONFIRMED/CHECKED_IN reservations within the day's UTC bounds
   - For each candidate slot, filter overlapping reservations:
     - existing.startsAt < slot.end AND existing.endsAt > slot.start

5. **Filtering:**
   - **Pacing:** Skip slot if overlapping count >= pacingMaxPerSlot
   - **Product Capacity:** Skip if occupied + partySize > effectiveCapacity
   - **Table Availability:**
     - If tableId specified: check for conflict; exclude if booked
     - If partySize specified: exclude tables with capacity < partySize; exclude booked tables
     - If neither: exclude booked tables; include all others
   - **Staff Availability:** Similar to tables; exclude busy staff members

6. **Output:**
   - For each available slot, return:
     - startsAt, endsAt
     - availableTables: list of tables that can accommodate the party
     - availableStaff: list of staff with no conflicts

---

### Deposit Calculation

**Triggered on createReservation and updateReservation:**

1. **Check Enabled & Mode:**
   - If depositMode = 'none', no deposit required
   - Supported modes: 'card_hold', 'deposit', 'prepaid'

2. **Party Size Threshold:**
   - If requiredForPartySizeGte is set and partySize < threshold, no deposit required

3. **Amount Calculation:**
   - **Fixed Amount:** depositAmount = depositFixedAmount
   - **Percentage of Service Price:** depositAmount = servicePrice * depositPercentage / 100
     - Service price sourced from product.price

4. **Storage:**
   - Store depositAmount as Prisma.Decimal(10, 2)
   - Set depositStatus = 'PENDING' (awaiting payment)
   - Store depositProcessorRef if processor returns a reference (e.g., Stripe PaymentIntent ID)

---

### Waitlist Position Calculation

**Triggered on addToWaitlist:**

1. **FIFO Mode:**
   - position = max(current positions in WAITING status) + 1
   - Fair; first-come-first-served

2. **PARTY_SIZE Mode:**
   - Prioritizes smaller parties (easier to seat)
   - position = partySize * 100 + count_of_same_size_waiting + 1
   - Example: party of 2 → position 201, 202, 203, ...; party of 4 → position 401, 402, ...
   - Smaller parties sort before larger

3. **BROADCAST Mode:**
   - position = 0 (no ordering)
   - All matching entries notified simultaneously (no sequential priority)

---

### Deposit Refund on Cancellation

**Triggered on cancelReservation:**

1. **Check Refund Policy:**
   - creditRefundMode in [NEVER, ALWAYS, TIME_BASED]

2. **Calculate Refund Eligibility:**
   - **NEVER:** No refund (customer loses credits)
   - **ALWAYS:** Refund 100% regardless of timing
   - **TIME_BASED:**
     - If cancelled >= creditFreeRefundHoursBefore before start: refund 100%
     - Otherwise: refund creditLateRefundPercent (0–100%)

3. **Async Refund:**
   - Call creditPack.public.service.refundCreditsForReservation
   - Wrapped in try/catch to prevent blocking cancellation if refund service fails
   - Logs error if refund fails

---

### No-Show Credit Refund

**Triggered on markNoShow:**

1. **Check Setting:**
   - If creditNoShowRefund = true:
     - Refund 100% of credits (policy: creditRefundMode='ALWAYS', creditFreeRefundHoursBefore=0, creditLateRefundPercent=100)
   - Otherwise: no refund (customer loses credits as penalty)

---

## State Machine & Transitions

### Reservation Status Transitions

```
PENDING ──────────── CONFIRMED ────────── CHECKED_IN ────── COMPLETED
   │                    │                      │
   │                    ├─────────────────────┘
   │                    │ (CANCELLED)
   └────────────────────┴──────────────────────┘
                    (CANCELLED)

   PENDING ────────────────────────────────────────────────────────────► NO_SHOW (alternate)
   CONFIRMED ───────────────────────────────────────────────────────────► NO_SHOW (alternate)
```

**State Details:**
- **PENDING:** Awaiting confirmation (if autoConfirm=false). Not yet counted in availability.
- **CONFIRMED:** Agreed to attend. Counted in availability. Guest can check in.
- **CHECKED_IN:** Present at venue. In progress or completed.
- **COMPLETED:** Service delivered. Final state.
- **CANCELLED:** Soft-deleted. No longer blocks availability. Refunds applied.
- **NO_SHOW:** Marked absent. No longer blocks availability. May trigger no-show fee.

**Valid Transitions:**
- PENDING → CONFIRMED, CANCELLED
- CONFIRMED → CHECKED_IN, CANCELLED, NO_SHOW
- CHECKED_IN → COMPLETED
- Others → (terminal, no transitions)

---

## Availability Engine

See [Availability Algorithm](#availability-algorithm) above for detailed flow. Additional notes:

- **Timezone-Aware:** All date arithmetic in venue.timezone (via date-fns-tz or Luxon)
- **UTC Storage:** Reservation startsAt/endsAt stored in UTC; re-interpret at runtime using venue.timezone
- **Operating Hours:** Per-day schedule with optional multiple ranges (up to 3 per day)
- **Dynamic Computation:** Slots computed on-the-fly (no materialized slot table)
- **Capacity Tiers:**
  - Table capacity (if table assigned)
  - Product capacity (if product assigned with eventCapacity)
  - Staff availability (no concurrent assignments)
  - Pacing limit (max concurrent bookings in a slot)

---

## Deposit & Payment Modes

**Modes:**
1. **none** — No deposit required (default)
2. **card_hold** — Hold funds on card (no immediate charge; Stripe test)
3. **deposit** — Charge upfront (full payment due before reservation confirms)
4. **prepaid** — Bundle with credit pack (customer depletes credits)

**Amount Calculation:**
- **Fixed Amount:** Set in depositFixedAmount (e.g., $50 MXN per reservation)
- **Percentage:** Set in depositPercentage (e.g., 50% of service price)

**Party Size Threshold:**
- depositPartySizeGte (e.g., 6): Only charge for parties >= 6 people
- Smaller parties exempt

**Payment Window:**
- depositPaymentWindow (hours): Customer has N hours to pay after booking
- Default: null (no deadline)

**Refund on Cancellation:**
- See [Deposit Refund](#deposit-refund-on-cancellation) above
- Controlled via creditRefundMode, creditFreeRefundHoursBefore, creditLateRefundPercent

---

## Android Port Implementation Notes

**Key Contracts to Honor:**

1. **Timezone Handling:**
   - All dates in request/response are ISO 8601 UTC strings
   - Server converts to venue.timezone for availability, operating hours, and date grouping
   - Client must display times in user's local timezone (or venue timezone if specified)

2. **Confirmation Code:**
   - Format: `RES-XXXXXX` (e.g., `RES-A3X7K2`)
   - Display to customer for verification (e.g., email, SMS, in-app)
   - Use for phone/WhatsApp lookups (human-readable, no special characters)

3. **Status Lifecycle:**
   - Follow state machine above; reject invalid transitions
   - Display status in clear language (e.g., "Confirmada", "Completada", "Cancelada")

4. **Deposits:**
   - If depositStatus = 'PENDING', prompt customer to pay
   - Display depositAmount and payment window expiration
   - Do not allow confirmation if payment required and not completed

5. **Availability:**
   - Respect operating hours; hide closed times
   - Respect capacity limits (pacing, table, product, staff)
   - Re-fetch availability if filters change (table, staff, partySize, productId)

6. **Waitlist:**
   - If availability exhausted, offer waitlist option
   - Display position and priority mode (helps set customer expectations)
   - Notify if promoted to reservation

7. **Error Handling:**
   - ConflictError (409): Double-booking or capacity limit; offer alternative times via /availability
   - BadRequestError (400): Invalid input; validate before sending
   - NotFoundError (404): Resource deleted; refresh list
   - P2034 retries: Transparent to client (server handles); may see brief delay

8. **Pessimistic Locking:**
   - Race-safe via SERIALIZABLE transactions + row locks (FOR UPDATE)
   - Concurrent operations on same resource may timeout or conflict
   - Implement retry UI if operation fails

---

**End of API Reference**

# Avoqado Web Dashboard: Comprehensive Reservations System Deep Dive

**Purpose:** Complete reference guide for porting the Avoqado reservation system to Android.  
**Last Updated:** April 2026  
**Scope:** All screens, components, API calls, state transitions, types, and user-facing strings.

---

## Table of Contents

1. [Type System & Data Models](#type-system--data-models)
2. [Service Layer (API)](#service-layer-api)
3. [Reservations Pages](#reservations-pages)
4. [Booking (Public) Pages](#booking-public-pages)
5. [Reservation Components](#reservation-components)
6. [Booking Components](#booking-components)
7. [Settings & Configuration](#settings--configuration)
8. [User-Facing Strings (Spanish)](#user-facing-strings-spanish)
9. [State Management & Caching](#state-management--caching)
10. [Business Rules & Validation](#business-rules--validation)

---

## Type System & Data Models

### Core Reservation Type

```typescript
export type ReservationStatus = 'PENDING' | 'CONFIRMED' | 'CHECKED_IN' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW'
export type ReservationChannel = 'DASHBOARD' | 'WEB' | 'PHONE' | 'WHATSAPP' | 'APP' | 'WALK_IN' | 'THIRD_PARTY'
export type DepositStatus = 'PENDING' | 'PAID' | 'REFUNDED'
export type WaitlistStatus = 'WAITING' | 'NOTIFIED' | 'PROMOTED' | 'CANCELLED' | 'EXPIRED'

interface Reservation {
  id: string
  venueId: string
  confirmationCode: string
  cancelSecret: string
  status: ReservationStatus
  channel: ReservationChannel
  startsAt: string (ISO 8601)
  endsAt: string (ISO 8601)
  duration: number (minutes)
  customerId: string | null
  customer: {
    id: string
    firstName: string
    lastName: string
    phone: string | null
    email: string | null
  } | null
  guestName: string | null
  guestPhone: string | null
  guestEmail: string | null
  partySize: number
  tableId: string | null
  table: {
    id: string
    number: string
    capacity: number
  } | null
  productId: string | null (for services)
  product: {
    id: string
    name: string
    price: number | null
  } | null
  classSessionId: string | null (for group classes)
  assignedStaffId: string | null
  assignedStaff: {
    id: string
    firstName: string
    lastName: string
  } | null
  createdById: string | null
  createdBy: {
    id: string
    firstName: string
    lastName: string
  } | null
  depositAmount: number | null
  depositStatus: DepositStatus | null
  confirmedAt: string | null (ISO 8601)
  checkedInAt: string | null (ISO 8601)
  completedAt: string | null (ISO 8601)
  cancelledAt: string | null (ISO 8601)
  noShowAt: string | null (ISO 8601)
  cancelledBy: string | null
  cancellationReason: string | null
  specialRequests: string | null
  internalNotes: string | null
  tags: string[]
  statusLog: StatusLogEntry[] | null
  createdAt: string (ISO 8601)
  updatedAt: string (ISO 8601)
}

interface StatusLogEntry {
  status: ReservationStatus
  at: string (ISO 8601)
  by: string | null (user name or system)
  reason?: string
}
```

### Waitlist Type

```typescript
interface WaitlistEntry {
  id: string
  venueId: string
  customerId: string | null
  customer: {
    id: string
    firstName: string
    lastName: string
    phone: string | null
  } | null
  guestName: string | null
  guestPhone: string | null
  partySize: number
  desiredStartAt: string (ISO 8601)
  desiredEndAt: string | null (ISO 8601)
  status: WaitlistStatus
  position: number
  notifiedAt: string | null
  responseDeadline: string | null
  promotedReservationId: string | null
  promotedReservation: {
    id: string
    confirmationCode: string
    status: ReservationStatus
  } | null
  notes: string | null
  createdAt: string (ISO 8601)
  updatedAt: string (ISO 8601)
}
```

### Class Session Type

```typescript
export type ClassSessionStatus = 'SCHEDULED' | 'CANCELLED' | 'COMPLETED'

interface ClassSession {
  id: string
  venueId: string
  productId: string
  product: {
    id: string
    name: string
    price: number | null
    maxParticipants: number | null
  }
  startsAt: string (ISO 8601)
  endsAt: string (ISO 8601)
  capacity: number
  enrolled: number
  available: number
  status: ClassSessionStatus
  assignedStaffId: string | null
  assignedStaff: {
    id: string
    firstName: string
    lastName: string
  } | null
  internalNotes: string | null
  createdAt: string (ISO 8601)
  updatedAt: string (ISO 8601)
}

interface ClassSessionAttendee {
  id: string
  confirmationCode: string
  status: string
  guestName: string | null
  guestPhone: string | null
  guestEmail: string | null
  partySize: number
  specialRequests: string | null
  createdAt: string (ISO 8601)
}
```

### Reservation Settings Type

```typescript
interface ReservationSettings {
  scheduling: {
    slotIntervalMin: number (e.g., 15)
    defaultDurationMin: number (e.g., 60)
    autoConfirm: boolean
    maxAdvanceDays: number (e.g., 60)
    minNoticeMin: number (e.g., 60)
    noShowGraceMin: number (e.g., 15)
    pacingMaxPerSlot: number | null
    onlineCapacityPercent: number (0-100)
  }
  deposits: {
    enabled: boolean
    mode: 'none' | 'card_hold' | 'deposit' | 'prepaid'
    percentageOfTotal: number | null
    fixedAmount: number | null
    requiredForPartySizeGte: number | null
    paymentWindowHrs: number | null
  }
  cancellation: {
    allowCustomerCancel: boolean
    minHoursBeforeStart: number | null
    forfeitDeposit: boolean
    noShowFeePercent: number | null
  }
  waitlist: {
    enabled: boolean
    maxSize: number
    priorityMode: 'fifo' | 'party_size' | 'broadcast'
    notifyWindowMin: number
  }
  reminders: {
    enabled: boolean
    channels: string[] (e.g., ['EMAIL', 'SMS', 'WHATSAPP'])
    minutesBefore: number[]
  }
  publicBooking: {
    enabled: boolean
    requirePhone: boolean
    requireEmail: boolean
  }
  operatingHours: OperatingHours
}

interface OperatingHours {
  monday: DaySchedule
  tuesday: DaySchedule
  wednesday: DaySchedule
  thursday: DaySchedule
  friday: DaySchedule
  saturday: DaySchedule
  sunday: DaySchedule
}

interface DaySchedule {
  enabled: boolean
  ranges: { open: string; close: string }[] // HH:mm format in venue timezone
}
```

### Request/Response Shapes

```typescript
interface CreateReservationRequest {
  startsAt: string (ISO 8601)
  endsAt: string (ISO 8601)
  duration: number
  channel?: ReservationChannel
  customerId?: string
  guestName?: string
  guestPhone?: string
  guestEmail?: string
  partySize?: number
  tableId?: string
  productId?: string
  assignedStaffId?: string
  specialRequests?: string
  internalNotes?: string
  tags?: string[]
}

interface UpdateReservationRequest {
  startsAt?: string
  endsAt?: string
  duration?: number
  guestName?: string
  guestPhone?: string
  guestEmail?: string | null
  partySize?: number
  tableId?: string | null
  productId?: string | null
  assignedStaffId?: string | null
  specialRequests?: string | null
  internalNotes?: string | null
  tags?: string[]
}

interface RescheduleRequest {
  startsAt: string (ISO 8601)
  endsAt: string (ISO 8601)
}

interface PaginatedReservationsResponse {
  data: Reservation[]
  meta: {
    total: number
    page: number
    pageSize: number
    totalPages: number
  }
}

interface ReservationStats {
  total: number
  byStatus: Record<ReservationStatus, number>
  byChannel: Record<ReservationChannel, number>
  noShowRate: number
}

interface AvailableSlot {
  startsAt: string (ISO 8601)
  endsAt: string (ISO 8601)
  availableTables: { id: string; number: string; capacity: number }[]
  availableStaff: { id: string; firstName: string; lastName: string }[]
}
```

---

## Service Layer (API)

### Reservation Service (`src/services/reservation.service.ts`)

All endpoints use prefix: `/api/v1/dashboard/venues/{venueId}/`

#### Query Reservations
- **Method:** `getReservations(venueId: string, params: ReservationQueryParams)`
- **HTTP:** GET `/reservations`
- **Query Parameters:**
  - `page?: number` (1-indexed)
  - `pageSize?: number` (default 20)
  - `status?: string` (single status, comma-separated for multiple)
  - `channels?: string[]` (preferred over single `channel` for multi-select)
  - `dateFrom?: string` (YYYY-MM-DD)
  - `dateTo?: string` (YYYY-MM-DD)
  - `tableId?: string`
  - `staffId?: string`
  - `productId?: string`
  - `search?: string` (searches name, phone, confirmation code)
- **Response:** `PaginatedReservationsResponse`
- **Behavior:** Returns paginated list of reservations with metadata. Filters reset page to 1 on client side.

#### Get Single Reservation
- **Method:** `getReservation(venueId: string, id: string)`
- **HTTP:** GET `/reservations/{id}`
- **Response:** `Reservation`

#### Create Reservation
- **Method:** `createReservation(venueId: string, data: CreateReservationRequest)`
- **HTTP:** POST `/reservations`
- **Request:** `CreateReservationRequest`
- **Response:** `Reservation`
- **Notes:** Returns full reservation object including status and timestamps. Auto-assigned channel is reflected in response.

#### Update Reservation
- **Method:** `updateReservation(venueId: string, id: string, data: UpdateReservationRequest)`
- **HTTP:** PUT `/reservations/{id}`
- **Request:** `UpdateReservationRequest` (all fields optional)
- **Response:** `Reservation`
- **Notes:** Partial update. Only provided fields are modified.

#### Cancel Reservation
- **Method:** `cancelReservation(venueId: string, id: string, reason?: string)`
- **HTTP:** DELETE `/reservations/{id}`
- **Body (optional):** `{ reason: string }`
- **Response:** `Reservation` (with updated status and timestamps)
- **Status Requirement:** Only PENDING or CONFIRMED can be cancelled (enforced server-side)

#### State Transitions (One-Way Actions)
- **Confirm:** POST `/reservations/{id}/confirm` → `Reservation`
  - Precondition: status === PENDING
  - Sets: status = CONFIRMED, confirmedAt = now
  
- **Check In:** POST `/reservations/{id}/check-in` → `Reservation`
  - Precondition: status === CONFIRMED
  - Sets: status = CHECKED_IN, checkedInAt = now
  
- **Complete:** POST `/reservations/{id}/complete` → `Reservation`
  - Precondition: status === CHECKED_IN
  - Sets: status = COMPLETED, completedAt = now
  
- **Mark No Show:** POST `/reservations/{id}/no-show` → `Reservation`
  - Precondition: status === CONFIRMED
  - Sets: status = NO_SHOW, noShowAt = now
  
- **Reschedule:** POST `/reservations/{id}/reschedule`
  - Precondition: status in [PENDING, CONFIRMED]
  - Body: `RescheduleRequest`
  - Response: `Reservation` (with updated startsAt/endsAt)

#### Get Availability
- **Method:** `getAvailability(venueId: string, params: AvailabilityQueryParams)`
- **HTTP:** GET `/reservations/availability`
- **Query Parameters:**
  - `date: string` (YYYY-MM-DD, required)
  - `duration?: number` (minutes)
  - `partySize?: number`
  - `tableId?: string`
  - `staffId?: string`
  - `productId?: string`
- **Response:** `{ date: string; slots: AvailableSlot[] }`
- **Behavior:** Returns 30-min slots on requested date respecting operating hours, pacing limits, and existing reservations.

#### Get Calendar (for month/week view)
- **Method:** `getCalendar(venueId: string, dateFrom: string, dateTo: string, groupBy?: 'table' | 'staff')`
- **HTTP:** GET `/reservations/calendar`
- **Query Parameters:**
  - `dateFrom: string` (YYYY-MM-DD)
  - `dateTo: string` (YYYY-MM-DD)
  - `groupBy?: 'table' | 'staff'` (optional)
- **Response:** `{ reservations: Reservation[]; grouped?: Record<string, Reservation[]> }`

#### Get Stats
- **Method:** `getStats(venueId: string, dateFrom: string, dateTo: string)`
- **HTTP:** GET `/reservations/stats?dateFrom={dateFrom}&dateTo={dateTo}`
- **Response:** `ReservationStats`
- **Behavior:** Used for stats cards on Reservations main page (typically for "today" range)

#### Waitlist Operations
- **Get Waitlist:** GET `/reservations/waitlist?status={status}`
  - Query: `status?: string` (filter by WAITING, NOTIFIED, etc.)
  - Response: `WaitlistEntry[]`
  
- **Add to Waitlist:** POST `/reservations/waitlist`
  - Body: `{ customerId?: string; guestName?: string; guestPhone?: string; partySize?: number; desiredStartAt: string; desiredEndAt?: string; notes?: string }`
  - Response: `WaitlistEntry`
  
- **Remove from Waitlist:** DELETE `/reservations/waitlist/{entryId}`
  - Response: void
  
- **Promote Waitlist Entry:** POST `/reservations/waitlist/{entryId}/promote`
  - Body: `{ reservationId: string }`
  - Response: `WaitlistEntry` (with status = PROMOTED, promotedReservationId set)

#### Settings
- **Get Settings:** GET `/reservations/settings` → `ReservationSettings`
- **Update Settings:** PUT `/reservations/settings`
  - Body: `Partial<ReservationSettings>`
  - Response: `ReservationSettings`

---

### Class Session Service (`src/services/classSession.service.ts`)

Endpoint prefix: `/api/v1/dashboard/venues/{venueId}/`

#### List Class Sessions
- **Method:** `getClassSessions(venueId: string, params: ListClassSessionsParams)`
- **HTTP:** GET `/class-sessions`
- **Query Parameters:**
  - `dateFrom: string` (YYYY-MM-DD)
  - `dateTo: string` (YYYY-MM-DD)
  - `productId?: string`
  - `status?: ClassSessionStatus`
- **Response:** `ClassSession[]`

#### Get Single Session
- **Method:** `getClassSession(venueId: string, sessionId: string)`
- **HTTP:** GET `/class-sessions/{sessionId}`
- **Response:** `ClassSession`

#### Create Single Class Session
- **Method:** `createClassSession(venueId: string, data: CreateClassSessionDto)`
- **HTTP:** POST `/class-sessions`
- **Request:**
  ```typescript
  {
    productId: string
    startsAt: string (ISO 8601 UTC)
    endsAt: string (ISO 8601 UTC)
    capacity: number
    assignedStaffId?: string | null
    internalNotes?: string | null
  }
  ```
- **Response:** `ClassSession`

#### Create Recurring Class Sessions (Bulk)
- **Method:** `createClassSessionsBulk(venueId: string, data: CreateClassSessionBulkDto)`
- **HTTP:** POST `/class-sessions/bulk`
- **Request:**
  ```typescript
  {
    productId: string
    startDate: string (YYYY-MM-DD in venue timezone)
    startTime: string (HH:mm local time)
    endTime: string (HH:mm local time)
    weekdays: number[] (0=Sunday ... 6=Saturday)
    endDate?: string (YYYY-MM-DD, mutually exclusive with occurrences)
    occurrences?: number (total instances, mutually exclusive with endDate)
    capacity: number
    assignedStaffId?: string | null
    internalNotes?: string | null
  }
  ```
- **Response:**
  ```typescript
  {
    count: number (total created)
    skipped: number (skipped due to conflicts)
    created: { id: string; startsAt: string; endsAt: string }[]
  }
  ```

#### Update Class Session
- **Method:** `updateClassSession(venueId: string, sessionId: string, data: UpdateClassSessionDto)`
- **HTTP:** PATCH `/class-sessions/{sessionId}`
- **Request:**
  ```typescript
  {
    startsAt?: string (ISO 8601)
    endsAt?: string (ISO 8601)
    capacity?: number
    assignedStaffId?: string | null
    internalNotes?: string | null
  }
  ```
- **Response:** `ClassSession`

#### Cancel Class Session
- **Method:** `cancelClassSession(venueId: string, sessionId: string)`
- **HTTP:** POST `/class-sessions/{sessionId}/cancel`
- **Response:** `ClassSession` (with status = CANCELLED)
- **Behavior:** Notifies all enrolled attendees of cancellation

#### Add Attendee to Session
- **Method:** `addAttendee(venueId: string, sessionId: string, data: {...})`
- **HTTP:** POST `/class-sessions/{sessionId}/attendees`
- **Request:**
  ```typescript
  {
    guestName: string
    guestPhone?: string
    guestEmail?: string
    partySize?: number
    specialRequests?: string
  }
  ```
- **Response:** `ClassSessionAttendee`

#### Remove Attendee
- **Method:** `removeAttendee(venueId: string, sessionId: string, reservationId: string)`
- **HTTP:** DELETE `/class-sessions/{sessionId}/attendees/{reservationId}`
- **Response:** void

---

### Public Booking Service (`src/services/publicBooking.service.ts`)

Endpoint prefix: `/api/v1/public/venues/{venueSlug}/` (no auth required, uses slug not ID)

#### Get Venue Info (Public)
- **Method:** `getVenueInfo(venueSlug: string)`
- **HTTP:** GET `/info`
- **Response:**
  ```typescript
  {
    name: string
    slug: string
    logo: string | null (URL)
    type: string (venue type)
    address: string | null
    phone: string | null
    timezone: string (IANA timezone)
    products: {
      id: string
      name: string
      price: number | null
      duration: number | null (minutes)
      eventCapacity: number | null
      type?: 'APPOINTMENTS_SERVICE' | 'EVENT' | 'CLASS'
      maxParticipants?: number | null
    }[]
    publicBooking: {
      enabled: boolean
      requirePhone: boolean
      requireEmail: boolean
    }
    operatingHours?: OperatingHours
  }
  ```

#### Get Public Availability
- **Method:** `getAvailability(venueSlug: string, params: PublicAvailabilityParams)`
- **HTTP:** GET `/availability`
- **Query Parameters:**
  - `date: string` (YYYY-MM-DD, required)
  - `duration?: number` (minutes)
  - `partySize?: number`
  - `productId?: string`
- **Response:**
  ```typescript
  {
    date: string
    slots: PublicSlot[]
  }
  ```
  where `PublicSlot` is:
  ```typescript
  {
    startsAt: string (ISO 8601)
    endsAt: string (ISO 8601)
    available: boolean
    classSessionId?: string (if for a class session)
    capacity?: number (for class)
    enrolled?: number (for class)
    remaining?: number (for class)
  }
  ```

#### Create Reservation (Public)
- **Method:** `createReservation(venueSlug: string, data: PublicCreateReservationRequest)`
- **HTTP:** POST `/reservations`
- **Request:**
  ```typescript
  {
    startsAt: string (ISO 8601)
    endsAt: string (ISO 8601)
    duration: number (minutes)
    guestName: string
    guestPhone: string
    guestEmail?: string
    partySize?: number
    productId?: string
    classSessionId?: string
    specialRequests?: string
  }
  ```
- **Response:**
  ```typescript
  {
    confirmationCode: string
    cancelSecret: string (use for management link)
    startsAt: string (ISO 8601)
    endsAt: string (ISO 8601)
    status: string
    depositRequired: boolean
    depositAmount: number | null
  }
  ```
- **Error Handling:**
  - 409 Conflict: Slot already taken (refetch availability and try another slot)
  - 400 Bad Request: Validation error (validate form before submit)

#### Get Reservation (Public, using cancelSecret)
- **Method:** `getReservation(venueSlug: string, cancelSecret: string)`
- **HTTP:** GET `/reservations/{cancelSecret}`
- **Response:**
  ```typescript
  {
    confirmationCode: string
    status: string
    startsAt: string (ISO 8601)
    endsAt: string (ISO 8601)
    duration: number
    partySize: number
    guestName: string | null
    product: { id: string; name: string; price: number | null } | null
    assignedStaff: { firstName: string; lastName: string } | null
    table: { number: string } | null
    specialRequests: string | null
    depositAmount: number | null
    depositStatus: string | null
  }
  ```

#### Cancel Reservation (Public)
- **Method:** `cancelReservation(venueSlug: string, cancelSecret: string, reason?: string)`
- **HTTP:** POST `/reservations/{cancelSecret}/cancel`
- **Body (optional):** `{ reason: string }`
- **Response:**
  ```typescript
  {
    confirmationCode: string
    status: string (CANCELLED)
    cancelledAt: string (ISO 8601)
    depositStatus: string | null
  }
  ```
- **Behavior:** Only callable within cancellation window (governed by ReservationSettings)

---

## Reservations Pages

### 1. Reservations.tsx (Main List Page)

**Route:** `/dashboard/{venueSlug}/reservations`

**Purpose:** Central hub for viewing and managing all reservations. Displays paginated list with filtering, search, and tabs for quick access to common views.

**Layout:**
- Header with title "Reservaciones" + subtitle
- Stats row (4 cards): Today's count, Pending, Checked-in, No-show rate
- Tab bar: All, Pending, Confirmed, Today, No Show (URL hash-synced)
- Filter pill for Channel (multi-select, but UI shows single select bug)
- Expandable search input (trigger icon, animated reveal)
- Data table with columns: Code, Guest (name+phone), Date/Time, Duration, Party Size, Table, Status, Channel
- Rows are clickable → navigate to ReservationDetail

**Key Controls:**
- **+ Create Button (dropdown):**
  - New Appointment (Cita) → opens CreateReservation modal
  - New Class (Clase) → opens CreateClassSessionDialog
- **Channel Filter:** Checkbox multi-select (DASHBOARD, WEB, PHONE, WHATSAPP, WALK_IN, THIRD_PARTY)
- **Search Input:** Free-text search (name, phone, confirmation code)

**Tabs & Filtering:**
- **all:** No status filter
- **pending:** status = PENDING
- **confirmed:** status = CONFIRMED
- **today:** dateFrom = today, dateTo = today
- **noShow:** status = NO_SHOW

**State (Local):**
- `activeTab: TabValue` (synced with URL hash)
- `pagination: { pageIndex: number; pageSize: number }` (defaults to pageSize=20)
- `searchTerm: string` (debounced 300ms)
- `channelFilter: string[]` (multi-select)
- `isSearchOpen: boolean` (expandable search UI)
- `showCreateModal: boolean` (CreateReservation modal)
- `showClassModal: boolean` (CreateClassSession modal)
- `createFormSubmitRef: MutableRefObject` (submit handler from modal child)

**API Calls:**
- `useQuery(['reservations', venueId, queryParams])`: fetches reservations on params change
- `useQuery(['reservation-stats', venueId, today])`: fetches stats for today

**Navigation:**
- Clicking row → `/reservations/{reservationId}` (ReservationDetail)
- Tab change → update URL hash

**Business Rules:**
- Only show "Create" dropdown if user has permission `reservations:create`
- Stats show real-time counts (PENDING, CHECKED_IN, NO_SHOW rate)
- Pagination resets to page 1 on any filter/search change

**Responsive Design:**
- Mobile: 2-column grid for stats, horizontal scroll on data table
- Desktop: 4-column stats, full table

---

### 2. ReservationDetail.tsx (Single Reservation View)

**Route:** `/dashboard/{venueSlug}/reservations/{reservationId}`

**Purpose:** Full detail view of a single reservation with ability to change status, reschedule, cancel, or update info.

**Layout:**
- Header: Confirmation code + Status badge (left), Action buttons (right, horizontal scroll on mobile)
- 3-column grid (responsive to 1 column on mobile):
  - **Left Column (2/3):**
    - Reservation Info card: Date, Time range, Duration, Party size, Table (if assigned), Staff (if assigned)
    - Notes card (if any): Special Requests + Internal Notes
    - Status Timeline card (if statusLog exists): Shows history of status changes with timestamps and actors
  - **Right Sidebar (1/3):**
    - Guest Info card: Name, Phone, Email (from customer or guest fields)
    - Meta Info card: Confirmation code (monospace), Channel, Tags (badges)
    - Deposit card (if depositAmount > 0): Deposit amount, status

**Action Buttons (Conditionally Shown):**
- **Confirm** (if status === PENDING): Filled button
- **Check In** (if status === CONFIRMED): Filled button
- **Complete** (if status === CHECKED_IN): Filled button
- **Reschedule** (if status in [PENDING, CONFIRMED]): Outline button
- **Mark No Show** (if status === CONFIRMED): Outline button with alert icon
- **Cancel** (if status in [PENDING, CONFIRMED]): Destructive button

**Dialogs:**
- **Cancel Dialog:** Asks for cancellation reason (optional), confirms action
- **Reschedule Dialog:** Shows new date + start/end time inputs, validates end > start
- **No Show Dialog:** Simple confirmation dialog

**State (Local):**
- `showCancelDialog: boolean`
- `showNoShowDialog: boolean`
- `showRescheduleDialog: boolean`
- `cancelReason: string`
- `rescheduleStart: string` (ISO)
- `rescheduleEnd: string` (ISO)

**API Calls:**
- `useQuery(['reservation', venueId, reservationId])`: fetches reservation
- `useMutation(confirmReservation)`: POST confirm, invalidates both detail and list queries
- `useMutation(checkIn)`: POST check-in, same invalidation
- `useMutation(complete)`: POST complete
- `useMutation(markNoShow)`: POST no-show
- `useMutation(cancelReservation)`: DELETE with optional reason body
- `useMutation(reschedule)`: POST reschedule with new times

**Business Rules:**
- Action buttons only visible if preconditions met (status checks)
- Confirmation code displayed in monospace, centered, with bg-muted
- Status changes are permanent (user must confirm in dialog)
- Cancellation optional reason is sent to backend for audit
- Each state transition mutation triggers full refresh of both detail + list queries
- Guest name prioritizes: customer.firstName + customer.lastName > guestName > "Invitado"

**Toast Notifications:**
- Success: "Reservación confirmada", "Check-in realizado", etc.
- Error: Backend error message or generic "Ocurrió un error..."

---

### 3. CreateReservation.tsx (Modal or Standalone Page)

**Route:** Typically used as modal from Reservations.tsx, but can be standalone at `/reservations/new`

**Purpose:** Multi-step form to create a new reservation with guest, date/time, service, and additional info.

**Form Sections:**
1. **Service Selection:** Product picker (combobox, searchable)
2. **Date & Time:** Date picker + Start/End time pickers
3. **Guest:** Toggle between "existing customer" and "new guest"
   - Existing: Customer search combobox
   - New: guestName + guestPhone + guestEmail fields
4. **Assignment:** Table selector + Staff selector (both optional)
5. **Additional:** Special Requests + Internal Notes (textareas)

**Key Controls:**
- **Service Selector:** Combobox with "Create Service" option inline
- **Date Picker:** Calendar widget, respects operating hours
- **Time Pickers:** Input fields or spinners (HH:mm)
- **Guest Mode Toggle:** Existing vs New
- **Customer Search:** Async search with customer.service
- **Table Selector:** Filtered by available tables for party size
- **Staff Selector:** Combobox from team.service
- **Submit Button:** In modal footer, trigged via submitRef callback

**Validation (Zod):**
```typescript
{
  date: string (YYYY-MM-DD)
  startTime: string (HH:mm)
  endTime: string (HH:mm) // must be > startTime
  duration: number (minutes, auto-calculated from times)
  partySize: number (>= 1)
  productId: string (optional)
  guestMode: 'existing' | 'new'
  customerId: string (if existing mode)
  guestName: string (if new mode, required)
  guestPhone: string (optional)
  guestEmail: string (optional, valid email)
  tableId: string (optional)
  assignedStaffId: string (optional)
  specialRequests: string (optional)
  internalNotes: string (optional)
}
```

**State (Local):**
- Form state (react-hook-form)
- `guestMode: 'existing' | 'new'`
- `customerSearch: string` (debounced)
- `productSearch: string` (debounced)
- `showCreateCustomerDialog: boolean` (inline customer creation)
- `showServiceTypeSelector: boolean` (choose service type before creating)
- `showServiceForm: boolean` (inline service creation)

**API Calls:**
- `useQuery(['products', venueId, 'all'])`: get all products for service selector
- `useInfiniteQuery(['customers', venueId, { search: customerSearch }])`: search customers
- `useQuery(['teams', venueId])`: get staff list
- `useQuery(['reservations/availability', { date, duration, partySize, ...}])`: check slot availability and available tables/staff
- `useMutation(createReservation)`: POST new reservation
- `useMutation(createCustomer)`: POST new customer (inline)
- `useMutation(createProduct)`: POST new product (inline)

**Business Rules:**
- Duration is auto-calculated from startTime - endTime
- Party size influences table availability (must have capacity >= partySize)
- Available tables fetched from /availability endpoint
- Start time must be within operating hours (from ReservationSettings.operatingHours)
- If minNoticeMin is set, start time must be at least that many minutes in future
- If maxAdvanceDays is set, start date must be within that many days from today
- On success, invalidate ['reservations', venueId] and ['reservation-stats', venueId]

**Responsive Design:**
- Modal: Full-screen on mobile, centered modal on desktop
- Form: Single column on mobile, multi-column on desktop (using grid)

---

### 4. ReservationSettings.tsx (Configuration Page)

**Route:** `/dashboard/{venueSlug}/reservations/settings`

**Purpose:** Configure all reservation system settings: scheduling, deposits, cancellation policy, waitlist, reminders, operating hours.

**Form Structure (Tabs or Sections):**
1. **Operating Hours:** Week-based schedule editor
2. **Scheduling:** Slot interval, default duration, auto-confirm, max advance days, min notice, no-show grace
3. **Pacing:** Max reservations per slot, online capacity %
4. **Deposits:** Enable/disable, mode (none/card_hold/deposit/prepaid), fixed/percentage, party size threshold
5. **Public Booking:** Enable, require phone, require email
6. **Cancellation:** Allow customer cancel, min hours before, forfeit deposit, no-show fee %
7. **Waitlist:** Enable, max size, priority mode (fifo/party_size/broadcast), notify window
8. **Reminders:** Enable, channels (EMAIL/SMS/WHATSAPP), minutes before

**Key Controls:**
- **Switches:** Toggle enables/disables for features
- **Input Fields:** Numbers and text for settings
- **Select Dropdowns:** Enums (deposit mode, priority mode)
- **Operating Hours Editor:** OperatingHoursEditor component (detailed below)
- **Submit Button:** "Guardar Cambios"

**Validation (Zod):**
```typescript
{
  slotIntervalMin: number (5-120)
  defaultDurationMin: number (15-480)
  autoConfirm: boolean
  maxAdvanceDays: number (1-365)
  minNoticeMin: number (0-10080)
  noShowGraceMin: number (0-120)
  pacingMaxPerSlot: number | null
  onlineCapacityPercent: number (0-100)
  depositMode: 'none' | 'card_hold' | 'deposit' | 'prepaid'
  depositFixedAmount: number | null
  depositPercentage: number (0-100) | null
  depositPartySizeGte: number | null
  publicBookingEnabled: boolean
  requirePhone: boolean
  requireEmail: boolean
  allowCustomerCancel: boolean
  minHoursBeforeCancel: number | null
  forfeitDeposit: boolean
  noShowFeePercent: number (0-100) | null
  waitlistEnabled: boolean
  waitlistMaxSize: number (1-500)
  waitlistPriorityMode: 'fifo' | 'party_size' | 'broadcast'
  waitlistNotifyWindow: number (5-120)
  remindersEnabled: boolean
}
```

**State (Local):**
- Form state (react-hook-form with resolver)
- `isDirty: boolean` (from form state)

**API Calls:**
- `useQuery(['reservation-settings', venueId])`: get current settings
- `useMutation(updateSettings)`: PUT /settings with Partial<ReservationSettings>

**Business Rules:**
- All fields are optional except structure validation (e.g., if deposits enabled, at least one of fixed/percentage must be set)
- Changes are persisted immediately on submit
- Toast notification on success
- dirty flag determines if Save button should be enabled

---

### 5. Waitlist.tsx (Waitlist Management)

**Route:** `/dashboard/{venueSlug}/reservations/waitlist`

**Purpose:** Manage customer waitlist with ability to add, promote, remove entries. Tab-based view (All, Waiting, Notified).

**Layout:**
- Header: Title + "Add to List" button
- Tab bar: All, Waiting, Notified
- Data table with columns: Position, Guest (name+phone), Party Size, Desired Time, Status, Actions (Promote, Remove)

**Tabs:**
- **all:** No filter
- **waiting:** status = WAITING
- **notified:** status = NOTIFIED

**Key Controls:**
- **+ Add to List Button:** Opens FullScreenModal with form
- **Promote Button (in table):** Converts waitlist entry to reservation
- **Remove Button (in table):** Deletes from waitlist (with confirmation dialog)

**Add to Waitlist Form:**
```typescript
{
  guestName: string (required)
  guestPhone: string (optional)
  partySize: number (>= 1)
  desiredStartAt: string (ISO 8601, required)
  notes: string (optional)
}
```

**State (Local):**
- `activeTab: TabValue` ('all' | 'waiting' | 'notified')
- `showAddModal: boolean`
- `removeEntry: WaitlistEntry | null` (for confirm dialog)
- Form state (react-hook-form)

**API Calls:**
- `useQuery(['waitlist', venueId, statusFilter])`: get waitlist (filtered by tab)
- `useMutation(addToWaitlist)`: POST /waitlist
- `useMutation(removeFromWaitlist)`: DELETE /waitlist/{entryId}
- `useMutation(promoteWaitlist)`: POST /waitlist/{entryId}/promote

**Business Rules:**
- Waitlist entries have position (1, 2, 3, ...)
- Priority mode from settings determines order (FIFO vs party size matching)
- Promote action converts waitlist entry to confirmed reservation
- Remove requires confirmation dialog
- Status badges show WAITING (default), NOTIFIED (approached), PROMOTED, CANCELLED, EXPIRED

**Responsive Design:**
- Table scrolls horizontally on mobile

---

### 6. OnlineBookingPage.tsx (Widget Embed Guide)

**Route:** `/dashboard/{venueSlug}/reservations/online-booking`

**Purpose:** Documentation and code snippets for embedding the public booking widget on external websites.

**Content Sections:**
1. **Introduction:** Explains widget purpose, benefits
2. **Customize Widget:** Dropdowns for language (es/en), theme (auto/light/dark), mode (inline/button/popup)
3. **Code Snippets:**
   - HTML embed (recommended)
   - WordPress shortcode (if plugin installed)
   - iframe (for restrictive hosts)
   - Link button (ultra-restrictive)
   - npm package (for React/Vue/Angular)
4. **Live Preview:** Embedded preview of the widget
5. **Setup Flow:** Step-by-step guide (HTML → iframe → button)

**No Form Submission:** This is informational only. Code snippets auto-update based on selections.

**Key Controls:**
- Language, Theme, Mode selectors
- Copy-to-clipboard buttons for each snippet
- "Open Preview in New Tab" button

---

## Booking (Public) Pages

### PublicBookingPage.tsx (Customer-Facing Booking Flow)

**Route:** `/booking/{venueSlug}` (public, no auth)

**Purpose:** Multi-step public booking experience where unauthenticated customers reserve online.

**Step Flow:**
1. **Service Selection** (if >1 product available): Choose service/product
2. **Date Selection:** Pick a date from calendar
3. **Time Selection:** Pick available time slot
4. **Guest Info:** Enter name, phone, (optional email), party size, special requests
5. **Confirmation:** Show booking confirmation with code, options to add to calendar, manage booking, or make new booking

**Dynamic Step Count:**
- If 0-1 products: Skip service step → 4 total steps
- If >1 products: Show service step → 5 total steps

**Layout (All Steps):**
- **Header:** BookingHeader component (venue logo, name, back button if not first step)
- **Step Indicator:** Shows current step (1/5, 2/5, etc.)
- **Content:** Component per step (ServiceSelector, DateSelector, TimeSlotPicker, GuestInfoForm, BookingConfirmation)
- **Footer:** Next/Submit button (disabled if form invalid)

**State (Local):**
- `step: number` (0 = loading, 1+ = actual steps)
- `selectedProduct: Product | null`
- `selectedDate: Date | undefined`
- `selectedSlot: PublicSlot | null`
- `bookingResult: PublicBookingResult | null`

**API Calls:**
- `useQuery(['public-venue', venueSlug])`: fetch venue info (products, operating hours, booking settings)
- `useQuery(['public-availability', venueSlug, dateStr, productId])`: fetch available slots for selected date + product
- `useMutation(createReservation)`: POST /public/venues/{slug}/reservations
  - On success: show confirmation screen
  - On conflict (409): show "slot taken" toast, refetch availability, reset step to time selector
  - On error: show error toast

**Business Rules:**
- Service step only shown if >1 product
- Available date range: today to 60 days ahead (from ReservationSettings.maxAdvanceDays)
- Time slots respect operating hours and existing reservations
- Class slots show remaining capacity; hide if fully booked
- Phone required based on publicBooking.requirePhone
- Email required based on publicBooking.requireEmail
- Slot conflict (409) triggers refetch and automatic re-prompt for time selection
- Confirmation code is primary identifier for managing booking

**Responsive Design:**
- Full-screen modal on desktop, full page on mobile
- Form layouts adapt: single column on mobile, multi-column on desktop

---

### BookingConfirmation.tsx (Component)

**Purpose:** Final confirmation screen after successful booking.

**Content:**
- Green checkmark icon
- "¡Reservación Confirmada!" heading
- Confirmation code (large, monospace, center-aligned)
- Details: Date, Time range, Guest count, Service name, Assigned staff, Table
- **Action Buttons:**
  - "Add to Google Calendar" → Opens Google Calendar new event template
  - "Download .ics" → Generates and downloads calendar file
  - "Manage Booking" → Navigates to public management page (where customer can cancel)
  - "Book Another" → Resets flow, go back to service/date selection

**Data Props:**
- `booking: PublicBookingResult` (confirmation code, times, status, deposit info)
- `venueInfo: PublicVenueInfo` (for name, timezone)
- `onManageBooking: () => void` (callback to navigate to management page)
- `onNewBooking: () => void` (callback to reset booking flow)

**Timezone Handling:**
- All dates/times formatted using venueInfo.timezone from client-side Date API

---

## Reservation Components

### CreateClassSessionDialog.tsx

**Purpose:** Modal form for creating single or recurring class sessions.

**Dialog Structure:**
- **Header:** "Agendar clase" (single) or "Agendar clase" (recurring)
- **Content:**
  - Class/Product selector (combobox, searchable, with "Create Class" inline option)
  - Date picker
  - Start time picker
  - End time picker
  - Capacity input
  - Staff selector (optional)
  - Internal notes textarea (max 2000 chars)
  - **Recurrence Section (Checkbox to toggle):**
    - Weekday checkboxes (Mon-Sun)
    - End mode toggle: "Ends on date" vs "Number of occurrences"
      - If "date": endDate picker
      - If "count": occurrences input (1-104)
- **Footer:** Cancel + Create buttons

**Validation (Zod):**
```typescript
{
  productId: string (required)
  date: string (YYYY-MM-DD, required)
  startTime: string (HH:mm, required)
  endTime: string (HH:mm, must be > startTime)
  capacity: number (>= 1, optional)
  assignedStaffId: string (optional)
  internalNotes: string (max 2000, optional)
  isRecurring: boolean (optional, default false)
  weekdays: number[] (0-6, required if recurring, at least 1)
  endMode: 'date' | 'count' (optional)
  endDate: string (required if endMode === 'date')
  occurrences: number (1-104, required if endMode === 'count')
}
```

**State (Local):**
- Form state (react-hook-form)
- `selectedProductId: string`
- `startTime: string`
- `isRecurring: boolean`
- `showServiceForm: boolean` (inline creation dialog)

**API Calls:**
- `useQuery(['products', venueId])`: get products for selector
- `useQuery(['teams', venueId])`: get staff for selector
- `useMutation(createClassSession)`: POST /class-sessions (single)
- `useMutation(createClassSessionsBulk)`: POST /class-sessions/bulk (recurring)

**Business Rules:**
- If single session: startsAt/endsAt are ISO UTC (frontend converts from local time + venue timezone)
- If recurring: local time (startTime/endTime) + weekdays list + endDate or occurrences sent to bulk endpoint
- Server skips dates that conflict with existing sessions
- Server returns count, skipped count, and list of created session IDs
- Cannot schedule in the past (validated on form)
- If selected product created inline, auto-select it after creation

---

### EditClassSessionDialog.tsx

**Purpose:** Similar to CreateClassSessionDialog, but for editing an existing session.

**Differences from Create:**
- Pre-populated with existing session data
- Only allows updating: startsAt, endsAt, capacity, assignedStaffId, internalNotes
- Does NOT allow changing productId or recurrence
- PUT (PATCH) instead of POST

**API Call:**
- `useMutation(updateClassSession)`: PATCH /class-sessions/{sessionId}

---

### EditAvailabilityDialog.tsx

**Purpose:** Allows staff to modify operating hours for a specific day or recurring schedule.

**Dialog Structure:**
- **Mode Toggle:** "Single change" vs "Recurring schedule"
- **Date Picker** (if single): Pick date to modify
- **Weekday Checkboxes** (if recurring): Select which days
- **Time Range Editor:** Add/remove time ranges (open/close)
  - Multiple ranges per day allowed (e.g., 9am-12pm, 2pm-6pm for lunch break)
  - Each range has Open + Close time inputs
  - Add button to add new range, remove button per range
  - Max 3 ranges per day
- **Actions:** Reset to defaults button, Done button

**Validation:**
- Each range must have close > open
- No overlapping ranges

**API Call:**
- Implied to update ReservationSettings.operatingHours via updateSettings

---

### OperatingHoursEditor.tsx (Sub-component)

**Purpose:** Reusable component for editing OperatingHours (7 days x multiple time ranges per day).

**Layout:**
- Column per day (Mon-Sun)
- Checkbox to enable/disable day
- If enabled: Add/remove time range buttons + open/close inputs per range
- Visual validation (errors on invalid times)

**Data Structure:**
```typescript
{
  [dayOfWeek]: {
    enabled: boolean
    ranges: { open: string; close: string }[] // HH:mm format
  }
}
```

---

### CalendarAttributesDialog.tsx

**Purpose:** Settings for customizing the calendar view appearance.

**Options:**
- **Status Display:** Show confirmed/pending/new customer/cancelled reservations
- **Color by Service:** Toggle to color-code reservations by service/product
- **Attribute Checkboxes:**
  - Confirmed
  - Pending
  - New customer
  - Show cancelled
  - Color by service (coming soon)

**No Direct API Call:** Settings applied locally to calendar rendering, may be persisted to localStorage or user preferences (not detailed in code review).

---

### ReservationStatusBadge.tsx

**Purpose:** Reusable component to display reservation status with color coding.

**Props:**
```typescript
{
  status: ReservationStatus
  className?: string
}
```

**Status → Color Mapping:**
- PENDING → warning (yellow)
- CONFIRMED → info (blue)
- CHECKED_IN → success (green)
- COMPLETED → neutral (gray)
- CANCELLED → error (red)
- NO_SHOW → error (red)

**Rendering:** `<StatusBadge variant={colorMap[status]}>{t(`status.${status}`)}</StatusBadge>`

---

## Booking Components

### ServiceSelector.tsx

**Purpose:** Multi-select UI for choosing a service/product in public booking flow.

**Props:**
```typescript
{
  services: Product[]
  selectedService: Product | null
  onSelect: (product: Product) => void
  isLoading?: boolean
}
```

**Layout:**
- Grid of service cards, each showing:
  - Service name
  - Price (if available)
  - Duration (HH:mm format)
  - Click to select (highlighted if selected)

**Behavior:**
- Shows loading spinner if isLoading
- If 0 products: "No hay servicios disponibles"

---

### DateSelector.tsx

**Purpose:** Calendar UI for selecting booking date.

**Props:**
```typescript
{
  minDate?: Date
  maxDate?: Date
  selectedDate?: Date
  onSelect: (date: Date) => void
  disabledDates?: Date[]
  isLoading?: boolean
}
```

**Behavior:**
- Shows calendar picker
- Disables dates outside operating hours (if known)
- Highlights selected date
- Clicking date calls onSelect

---

### TimeSlotPicker.tsx

**Purpose:** Grid of available time slots, grouped by hour.

**Props:**
```typescript
{
  slots: PublicSlot[]
  selectedSlot: PublicSlot | null
  onSelect: (slot: PublicSlot) => void
  timezone: string
  isLoading?: boolean
}
```

**Layout:**
- Grouped by hour (9:00, 10:00, etc.)
- Each slot shown as button with time
- For class slots: show capacity info (X lugares, Lleno if full)
- Selected slot highlighted
- Disabled if full

**Business Rules:**
- Slot is full if: `slot.remaining === 0` (for classes)
- Show remaining spots in small text below time

---

### GuestInfoForm.tsx

**Purpose:** Form for guest details before confirmation.

**Props:**
```typescript
{
  requirePhone: boolean
  requireEmail: boolean
  onSubmit: (data: GuestFormData) => void
  isSubmitting?: boolean
}
```

**Fields:**
```typescript
{
  guestName: string (required)
  guestPhone: string (required if requirePhone)
  guestEmail: string (required if requireEmail, must be valid email)
  partySize: number (optional, default 1)
  specialRequests: string (optional)
}
```

**Validation:**
- Name: min 1 char
- Phone: pattern validation if required
- Email: valid email if required
- Party size: >= 1

**Layout:**
- Stacked inputs on mobile, 2-column on desktop
- Submit button disabled if form invalid or submitting

---

### BookingStepIndicator.tsx

**Purpose:** Visual progress indicator showing current step.

**Props:**
```typescript
{
  currentStep: number
  totalSteps: number
  labels: string[]
}
```

**Layout:**
- Horizontal or vertical list of step labels
- Current step highlighted
- Completed steps checked or visually distinct
- Future steps greyed out

---

### CancellationPolicyBanner.tsx

**Purpose:** Info banner showing cancellation policy to customer.

**Props:**
```typescript
{
  settings: ReservationSettings
}
```

**Content:**
- Displays cancellation window ("Cancelación gratuita hasta X horas antes")
- Shows if deposit is forfeited on cancellation
- Shown during booking flow and on confirmation

---

### BookingHeader.tsx

**Purpose:** Header component for public booking pages.

**Props:**
```typescript
{
  venue?: PublicVenueInfo
  onBack?: () => void
  showBackButton?: boolean
}
```

**Content:**
- Venue logo (if available)
- Venue name
- Back button (if not on first step)
- "Powered by Avoqado" text (small)

---

## Settings & Configuration

### ReservationSettings Structure (Full)

All settings accessible via GET/PUT `/reservations/settings`:

```typescript
{
  scheduling: {
    slotIntervalMin: 15,              // Slots offered every N minutes
    defaultDurationMin: 60,            // Default appointment duration
    autoConfirm: true,                 // Auto-confirm on creation
    maxAdvanceDays: 60,                // How far customers can book ahead
    minNoticeMin: 60,                  // Min time before start to book
    noShowGraceMin: 15,                // Wait time before marking no-show
    pacingMaxPerSlot: null,            // Max concurrent reservations per slot (null = unlimited)
    onlineCapacityPercent: 100         // % of capacity available for online bookings
  },
  deposits: {
    enabled: false,                    // Require deposits?
    mode: 'none',                      // 'none' | 'card_hold' | 'deposit' | 'prepaid'
    percentageOfTotal: null,           // % of service price
    fixedAmount: null,                 // Fixed $ amount
    requiredForPartySizeGte: null,     // Only require if party size >= N
    paymentWindowHrs: null             // Hours to pay after booking
  },
  cancellation: {
    allowCustomerCancel: true,         // Customers can cancel online?
    minHoursBeforeStart: null,         // Cancellation window
    forfeitDeposit: false,             // Lose deposit if cancelled?
    noShowFeePercent: null             // % fee for no-shows
  },
  waitlist: {
    enabled: false,                    // Waitlist feature enabled?
    maxSize: 50,                       // Max people on waitlist
    priorityMode: 'fifo',              // 'fifo' | 'party_size' | 'broadcast'
    notifyWindowMin: 30                // Minutes to respond to notification
  },
  reminders: {
    enabled: true,                     // Send reminders?
    channels: ['EMAIL', 'SMS'],        // Channels
    minutesBefore: [1440, 60]          // 1 day before, 1 hour before
  },
  publicBooking: {
    enabled: true,                     // Accept online bookings?
    requirePhone: true,                // Phone field required?
    requireEmail: false                // Email field required?
  },
  operatingHours: {
    monday: { enabled: true, ranges: [{ open: '09:00', close: '18:00' }] },
    tuesday: { enabled: true, ranges: [{ open: '09:00', close: '18:00' }] },
    // ... etc for each day
  }
}
```

---

## User-Facing Strings (Spanish)

**All strings from `/src/locales/es/reservations.json`:**

### Navigation & Structure
- "Reservaciones" (title)
- "Gestiona reservaciones, disponibilidad y lista de espera" (subtitle)
- Tabs: "Todas", "Pendientes", "Confirmadas", "Hoy", "No Show"
- Nav: "Resumen", "Calendario", "Lista de espera", "Configuración"

### Table Columns
- "Código"
- "Cliente"
- "Fecha / Hora"
- "Duración"
- "Personas"
- "Mesa"
- "Estado"
- "Canal"
- "Personal"

### Status Labels
- PENDING: "Pendiente"
- CONFIRMED: "Confirmada"
- CHECKED_IN: "Check-in"
- COMPLETED: "Completada"
- CANCELLED: "Cancelada"
- NO_SHOW: "No Show"

### Channels
- DASHBOARD: "Dashboard"
- WEB: "Web"
- PHONE: "Teléfono"
- WHATSAPP: "WhatsApp"
- APP: "App"
- WALK_IN: "Sin cita"
- THIRD_PARTY: "Tercero"

### Deposit Status
- PENDING: "Pendiente"
- PAID: "Pagado"
- REFUNDED: "Reembolsado"

### Stats
- "Hoy" (count)
- "Pendientes"
- "En curso"
- "Tasa No Show"

### Actions
- "Crear" (dropdown)
- "Cita" (New Appointment)
- "Clase" (New Class)
- "Confirmar"
- "Check-in"
- "Completar"
- "Marcar No Show"
- "Cancelar"
- "Reagendar"
- "Editar"
- "Ver Detalles"
- "Guardar"
- "Guardar Cambios"
- "Volver"

### Forms
- "Crear cita"
- "Editar Reservación"
- Fields: "Fecha", "Hora de inicio", "Hora de fin", "Duración", "Personas", "Cliente", "Nombre del invitado", "Teléfono", "Email", "Mesa", "Personal asignado", "Canal", "Solicitudes especiales", "Notas internas", "Etiquetas"
- Placeholders: "Nombre del invitado", "Teléfono de contacto", "Email de contacto", "Alergias, cumpleaños, silla de bebé...", "Notas visibles solo para el personal"

### Detail Page
- "Reservación {{code}}"
- "Código de confirmación"
- "Historial"
- "Depósito" section: "Monto", "Estado"
- "Reagendar Reservación": "Nueva fecha", "Nueva hora de inicio", "Nueva hora de fin"
- "Cancelar Reservación": "¿Estás seguro de que deseas cancelar esta reservación?", "Razón de cancelación"
- "Marcar como No Show": "¿Confirmas que el cliente no se presentó?"
- Sections: "Información del Cliente", "Detalles de la Reservación", "Notas"

### Calendar
- "Calendario de Reservaciones"
- Views: "Día", "Semana", "5-días", "Mes"
- "Intervalo"
- Group by: "Sin agrupar", "Mesa", "Personal", "Sin asignar"
- "Sin reservaciones para este período"

### Class Sessions
- "Agendar clase"
- "Nueva clase"
- "Clase agendada exitosamente"
- "No hay clases creadas. Crea un producto tipo \"Clase\" primero."
- "Capacidad"
- "Se repite"
- "Crear clase"
- "Añadir nueva clase"
- "Todas las clases"
- "Gratis"
- "{{count}} lugar disponible" / "{{count}} lugares disponibles"
- "Lleno"
- "Agendar"
- "Clase actualizada exitosamente"
- "Clase cancelada"
- "Cancelar clase"
- "¿Cancelar esta clase?" / "Hay {{count}} asistente(s) registrado(s). Se les notificará de la cancelación."
- "Asistentes"
- "Asistente eliminado"
- Inline create: "Nueva clase", "Nombre", "Descripción", "Precio", "Duración (min)", "Cupo máximo", "Crear clase"

### Waitlist
- "Lista de Espera"
- "Gestiona clientes en lista de espera"
- "Lista de espera vacía" / "No hay clientes esperando en este momento"
- Table columns: "Pos.", "Cliente", "Personas", "Hora deseada", "Estado", "Registrado"
- Status: "Esperando", "Notificado", "Promovido", "Cancelado", "Expirado"
- Actions: "Agregar a Lista", "Promover", "Notificar", "Remover"
- "Agregar a Lista de Espera"
- Fields: "Nombre", "Teléfono", "Personas", "Hora deseada", "Notas"
- "Promover de Lista de Espera" / "Se creará una reservación para este cliente"
- Priority modes: "Orden de llegada", "Por tamaño de grupo", "Notificación masiva"

### Online Booking (Customer Facing)
- "Reservar" (page title)
- "Con tecnología de Avoqado"
- Steps: "Servicio", "Fecha", "Hora", "Datos", "Confirmado"
- "Selecciona un servicio"
- "{{min}} min"
- "No hay servicios disponibles"
- "Selecciona una fecha"
- "Hoy"
- "Selecciona un horario"
- "No hay horarios disponibles para esta fecha"
- "Cargando horarios..."
- "Lleno"
- "{{count}} lugares" / "{{count}} lugar"
- "Tus datos"
- Fields: "Teléfono", "Tu número de teléfono", "Nombre", "Tu nombre", "Email (opcional)", "tu@email.com", "Personas", "Solicitudes especiales", "Alergias, cumpleaños, necesidades de accesibilidad..."
- "Confirmar Reservación"
- "Reservando..."
- "¡Reservación Confirmada!"
- "Código de Confirmación"
- "Fecha y Hora"
- "Agregar a Google Calendar"
- "Descargar .ics"
- "Gestionar Reservación"
- "Reservar Otra"
- "Tu Reservación"
- "Cancelar Reservación"
- "Razón (opcional)" / "¿Por qué cancelas?"
- "Confirmar Cancelación"
- "Esta reservación ha sido cancelada"
- "Cancelada el {{date}}"
- "El depósito se perderá"
- "Atendido por"
- "Solicitudes Especiales"
- "Hacer Nueva Reservación"
- Policy: "Cancelación gratuita hasta {{hours}} horas antes", "Esta reservación no puede cancelarse en línea"
- Errors: "Esta página no existe", "El enlace de reservación puede ser incorrecto o el negocio ya no está disponible.", "Reservaciones en línea no disponibles", "Este negocio no está aceptando reservaciones en línea en este momento.", "Llama al {{phone}} para hacer una reservación", "Reservación no encontrada", "Este enlace de reservación puede estar expirado o ser inválido.", "Este horario ya no está disponible. Por favor elige otro horario.", "Algo salió mal. Por favor intenta de nuevo."

### Settings
- "Configuración de Reservaciones"
- "Configura horarios, depósitos y políticas"
- Sections: "Horario de Operación", "Programación", "Ritmo", "Depósitos", "Reservaciones Online", "Cancelación", "Lista de Espera", "Recordatorios"
- Operating hours: "Horario de Operación", "Configura tu horario semanal. Los días cerrados no mostrarán disponibilidad.", "Cerrado", "Agregar rango", "Eliminar rango", "Apertura", "Cierre"
- Days: "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"
- "Máximo 3 rangos por día"
- Scheduling: "Intervalo de slots", "Cada cuántos minutos se ofrecen horarios", "Duración predeterminada", "Duración estándar de una reservación", "Confirmar automáticamente", "Las reservaciones se confirman al crearlas", "Días máximos de anticipación", "Qué tan lejos pueden reservar los clientes", "Aviso mínimo", "Tiempo mínimo antes de la hora de inicio", "Gracia para No Show", "Minutos de espera antes de marcar No Show"
- Pacing: "Máximo por slot", "Límite de reservaciones simultáneas (vacío = sin límite)", "Capacidad online", "Porcentaje de capacidad disponible para reservaciones online"
- Deposits: "Requerir depósito", "Modo de depósito", "Sin depósito", "Retención de tarjeta", "Depósito parcial", "Prepago total", "Monto fijo", "Porcentaje", "Para grupos de", "personas o más", "Ventana de pago", "Horas para completar el pago"
- Public booking: "Habilitar reservaciones online", "Los clientes pueden reservar desde tu sitio web", "Requerir teléfono", "Requerir email"
- Cancellation: "Permitir cancelación por cliente", "Horas mínimas antes", "Horas antes del inicio para poder cancelar", "Perder depósito al cancelar", "Cargo por No Show", "Porcentaje del depósito que se cobra"
- Waitlist: "Habilitar lista de espera", "Tamaño máximo", "Modo de prioridad", "Ventana de notificación", "Minutos que tiene el cliente para responder"
- Reminders: "Enviar recordatorios", "Canales", "Email", "SMS", "WhatsApp", "Enviar antes de", "Minutos antes de la reservación"
- "Configuración guardada exitosamente"

### Toasts
- "Reservación creada exitosamente"
- "Reservación actualizada exitosamente"
- "Reservación cancelada"
- "Reservación confirmada"
- "Check-in realizado"
- "Reservación completada"
- "Marcada como No Show"
- "Reservación reagendada"
- "Agregado a la lista de espera"
- "Removido de la lista de espera"
- "Cliente promovido de la lista de espera"
- "Ocurrió un error. Por favor intenta de nuevo."

### Miscellaneous
- "min" (minutes)
- "hrs" (hours)
- "{{count}} persona" / "{{count}} personas"
- "Sin mesa"
- "Sin asignar"
- "Invitado" (unnamed guest)

---

## State Management & Caching

### React Query Keys (TanStack Query v5)

**Reservation Queries:**
```typescript
['reservations', venueId, queryParams]  // List with filters
['reservation', venueId, reservationId]  // Single detail
['reservation-stats', venueId, date]     // Daily stats
```

**Class Session Queries:**
```typescript
['class-sessions', venueId, dateFrom, dateTo, productId?, status?]
['class-session', venueId, sessionId]
['products', venueId, 'all']             // For service selector
```

**Public Booking Queries:**
```typescript
['public-venue', venueSlug]
['public-availability', venueSlug, dateStr, productId?]
```

**Waitlist Queries:**
```typescript
['waitlist', venueId, statusFilter?]
```

**Settings Queries:**
```typescript
['reservation-settings', venueId]
```

### Invalidation Strategy

**On Reservation Create:**
- Invalidate `['reservations', venueId]` (all pages)
- Invalidate `['reservation-stats', venueId]` (all dates)

**On Reservation Status Change (Confirm, CheckIn, Complete, NoShow, Cancel):**
- Invalidate `['reservation', venueId, reservationId]` (detail)
- Invalidate `['reservations', venueId]` (list, all filters)
- Invalidate `['reservation-stats', venueId]` (all dates)

**On Reservation Reschedule:**
- Same as status change

**On Waitlist Add/Remove/Promote:**
- Invalidate `['waitlist', venueId]` (all filters)

**On Settings Update:**
- Invalidate `['reservation-settings', venueId]`

**On Class Session Create/Update/Cancel:**
- Invalidate `['class-sessions', venueId]` (all date ranges)

### Caching Strategy

- **staleTime:** Typically 0 (immediate fetch on mount)
- **gcTime (formerly cacheTime):** 5-10 minutes for inactive queries
- **refetchOnWindowFocus:** true (recommended for freshness on tab switch)
- **refetchOnReconnect:** true (data may be stale after network loss)
- **retry:** 3 attempts with exponential backoff on network errors

---

## Business Rules & Validation

### Reservation State Machine

```
PENDING ──confirm──→ CONFIRMED ──check-in──→ CHECKED_IN ──complete──→ COMPLETED
   │                    ↓
   │               no-show (allowed)
   │                    ↓
   └─ cancel ────────→ CANCELLED
       (allowed)
                       ↑
                    cancel
                   (allowed)

Final states: COMPLETED, CANCELLED, NO_SHOW
```

### Status Transition Rules (Server-Enforced)

- **PENDING → CONFIRMED:** Via `confirmReservation()`
- **PENDING → CANCELLED:** Via `cancelReservation()`
- **CONFIRMED → CHECKED_IN:** Via `checkIn()`
- **CONFIRMED → NO_SHOW:** Via `markNoShow()` (alternative to check-in)
- **CONFIRMED → CANCELLED:** Via `cancelReservation()`
- **CHECKED_IN → COMPLETED:** Via `complete()`
- All other transitions rejected by backend

### Reservation Validation Rules

**On Creation:**
- startsAt must be in the future (or at least minNoticeMin away)
- startsAt must be within maxAdvanceDays from today
- startsAt must respect operating hours (DaySchedule.ranges)
- duration > 0
- partySize >= 1
- If tableId provided: table capacity >= partySize (checked via availability endpoint)
- If staffId provided: staff exists and is available
- guestName required if no customerId provided
- customerId and guestName mutually optional (but one must be set)

**On Update:**
- Same rules as creation apply to new times
- Cannot change status via update (use state transition endpoints)
- Cannot change productId (immutable)
- Cannot change channel (immutable, set at creation)

**On Reschedule:**
- New times must respect all creation rules
- Reservation must be in PENDING or CONFIRMED status
- Existing reservation is cancelled and new one created (server-side)

### Waitlist Rules

**On Add:**
- partySize >= 1
- desiredStartAt in future
- Cannot exceed maxSize (from settings)

**On Promote:**
- Converts waitlist entry to confirmed reservation
- Position recalculated for remaining waitlist (FIFO or by party size)

**Priority Mode (from settings):**
- **fifo:** First-in-first-out (position = order added)
- **party_size:** Match larger parties first (position = descending party size)
- **broadcast:** Notify all, first to confirm gets slot

### Class Session Rules

**On Create (Single):**
- startsAt < endsAt
- startsAt in future
- capacity >= 1
- Cannot exceed product.maxParticipants (if set)

**On Create (Bulk):**
- Validates each instance against single-create rules
- Skips dates that conflict with existing sessions
- Server returns count of actual created + skipped

**On Attendee Add:**
- Cannot exceed capacity
- guestName required

### Deposit Rules (if enabled)

- **mode = 'deposit':** Partial payment required upfront
- **mode = 'card_hold':** Card pre-authorized, charged on arrival
- **mode = 'prepaid':** Full payment required upfront
- **Fixed amount:** Applies regardless of service price
- **Percentage:** Calculated as (partySize * productPrice * depositPercent / 100)
- **Party size threshold:** Only charge deposit if partySize >= requiredForPartySizeGte
- **Payment window:** Customer has paymentWindowHrs hours to complete payment after booking

### Cancellation Rules (if enabled)

- **allowCustomerCancel:** If false, only staff can cancel (public customers cannot)
- **minHoursBeforeStart:** Cancellation must happen >= N hours before start (else forfeit deposit)
- **forfeitDeposit:** If cancellation outside window, lose entire deposit amount
- **noShowFeePercent:** If marked no-show, charge % of deposit as penalty (not forfeit entire amount)

### Operating Hours Rules

- **enabled = false:** Day is closed, no availability shown
- **ranges:** Multiple time ranges per day (e.g., 9am-12pm, 2pm-6pm)
- **No time outside ranges:** Availability endpoints only return slots within these hours
- **Overlapping ranges:** Server-side validation prevents overlaps

### Public Booking Rules

- **enabled = false:** Public booking page returns 404 or error
- **requirePhone = true:** guestPhone field mandatory before submission
- **requireEmail = true:** guestEmail field mandatory and must be valid email
- **Slot conflict (409 HTTP):** Another customer booked exact same slot; user must select different time

---

## Summary: Key Takeaways for Android Port

1. **Dual-Mode System:** Reservation system serves both authenticated staff (dashboard) and public customers (booking widget)

2. **State Machines:** Reservations follow strict state transitions (PENDING → CONFIRMED → CHECKED_IN → COMPLETED). UI must enforce these visually.

3. **Time Zones:** All times stored as ISO 8601 UTC in API. Client must convert to venue timezone for display. Venue timezone obtained from ReservationSettings.

4. **Availability Calculation:** Availability endpoint handles all business logic (operating hours, pacing, existing reservations). Client should not calculate locally.

5. **Deposits & Payments:** Deposit system is configurable per venue. Android app may need payment integration (credit card, bank account, etc.) depending on mode chosen.

6. **Cancellation Policy:** Strict time windows and forfeit rules. Must show to customer upfront in booking flow.

7. **Class Sessions:** Distinct from individual reservations. Support recurring creation (bulk endpoint), capacity tracking, attendee management.

8. **Waitlist:** Configurable priority mode (FIFO, party size, broadcast). Android must support promotion workflow.

9. **Settings:** Highly configurable system per venue. Android must load settings dynamically and enforce all rules (slot interval, duration, advance booking window, etc.).

10. **Localization:** Significant Spanish copy for Mexican market. All UI strings should be internationalized from the start.

11. **Query Caching:** React Query patterns suggest Android should implement similar invalidation strategy—invalidate multiple related queries when data changes.

12. **Form Validation:** Use Zod-like schema validation on client before API submission to avoid round-trips.

---

**End of Document**

This comprehensive guide covers every screen, component, API call, and business rule in the Avoqado web dashboard reservation system. Use it as the authoritative reference for designing the Android port.

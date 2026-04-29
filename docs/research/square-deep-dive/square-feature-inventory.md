# Square Appointments — Inventario Exhaustivo de Features

**Fuentes**: Exploración en vivo de `app.squareup.com/dashboard/appointments` (cuenta Healthy boutique) + Square POS Android v7.4.2 sobre Samsung SM-X133 (29 abr 2026) + `docs/research/square-appointments/FINDINGS.md` (23 feb 2026, dashboard web).

---

## 1. Modelo de "Modos" (arquitectura macro)

Square POS Android opera en **uno de tres modos** configurables por dispositivo (Más → tarjeta "Modo" → "Cambiar de modo"):

| Modo | ID interno | Tabs en bottom nav |
|---|---|---|
| **reservas** (Square Appointments) | `reservas` | Calendario · Proceso de pago · Clientes · Más |
| **tienda** (Square Retail) | `tienda` | (bottom tabs distintas — no exploradas en este barrido) |
| **Modo Estándar** (Square POS) | `Estándar` | (bottom tabs distintas) |

Implicaciones para Android Avoqado:
- El "tab Calendario" NO es universal — aparece solo cuando el venue está en modo *reservas* o ese tab está habilitado por feature.
- Los modos son por-dispositivo ("Activo en 1 dispositivo"), no por-venue. Permite que la misma cuenta tenga una tablet en modo *reservas* y otra en modo *Estándar*.
- Hay un `+` para activar más modos (probablemente módulos premium adicionales).

---

## 2. Square POS Android — Modo Reservas (tablet)

### 2.1 Bottom navigation (4 tabs)
```
[Calendario]  [Proceso de pago]  [Clientes]  [Más]
   ▼              ▼                  ▼          ▼
   📅              ▦                  👤         ☰
```
- El tab activo se marca con fondo gris suave (no underline ni color de marca).
- Sin badges en tabs (Square no usa contador de notificaciones en bottom nav).
- 75 dp aprox de altura (incluye safe area).

### 2.2 Tab Calendario — vista principal

**Header** (top app bar, sticky):
- Centro: `Mes año ▾` (ej. "Abril de 2026 ▾") — abre date picker como dialog modal.
- Derecha: `+` (icono outlined, circular invisible), `⋯` (kebab menu).
- Sin back button (es root tab).

**Week strip** (debajo del header):
- 7 columnas: D L M M J V S (Spanish abbreviations, Sunday-first).
- Cada celda: letra del día arriba (gris), número del día debajo.
- Día actual en círculo negro filled, día seleccionado highlighted similar.
- Tap en cualquier letra/número cambia el día activo.

**Calendar grid** (vista Día default):
- Eje vertical: hora (08, 09, 10, ...) en gris claro.
- Una columna full-width de slots de 60 minutos (en vista Día).
- **Línea roja horizontal de hora actual** con timestamp ("09:29") en rojo a la izquierda del eje.
- Slots vacíos = blancos. Tap en slot vacío → abre create action sheet con tiempo pre-llenado.

**Date picker dialog** (cuando se tapea `Mes año ▾`):
- Modal centrado, fondo opaco al 50%.
- Header: X close (top-left circular) + título "Seleccionar día" centrado + "Hoy" pill button (top-right).
- Mes nav: `◀ abr 2026 ▾ ▶` (chevrons L/R, año-mes con dropdown).
- Días de semana headers: do lu ma mi ju vi sá (lowercase, gris).
- Grid 6×7 de números, día seleccionado en círculo filled.

**Settings sheet** (`⋯` o icono de engrane abre lo mismo):
Modal full-screen modal con:
- Header: X close (left circular) + "Ajustes del calendario" centrado + "Guardar" pill button (right).
- Sección **Disponibilidad para reservas**:
  - Row "Realizar un cambio por única vez" › (one-time override)
  - Row "Editar el calendario frecuente" › (recurring availability)
- Sección **Vista del calendario** (radio):
  - ⚫ Día (default)
  - ○ Semana
  - ○ Lista
- Status filters (toggles):
  - ✅ Confirmada (ON default)
  - ❓ Sin confirmar (ON default)
  - ↑ Servicio anterior (varios empleados) (ON default)
  - ↓ Servicio posterior (varios empleados) (ON default)
- Sección **Filtros adicionales**:
  - Toggle "Mostrar las reservas canceladas" (OFF default)
- Sección **Código del color** (radio):
  - ⚫ Por empleado (default)
  - ○ Por servicio (con link "Surtido de servicios")

### 2.3 Create action sheet (`+` o tap en slot)

Action sheet centrado con bordes redondeados grandes:
- **Crear cita** (full width pill button)
- **Crear clase** (full width pill button)
- **Crear evento personal** (full width pill button)
- **Cancelar** (texto rojo)

Tap en slot vacío del calendar grid también abre este action sheet, además crea visualmente un placeholder azul "Nuevo evento" para mostrar dónde caerá.

### 2.4 Crear cita (full-screen modal)

Header: X circular (left) + "Guardar" pill (right). Sin título visible en header — el título "Crear cita" aparece como h2 en el cuerpo.

Form (single column scrollable, 65% width on tablet):
1. **Cliente** (sección)
   - Botón "Agregar cliente" (outlined pill, full width)
2. **Servicios y artículos**
   - Botones "Agregar servicio" + "Agregar artículo o descuento" (lado a lado, outlined pills)
3. **Fecha y hora** (collapsible row)
   - Resumen "mié, 29 de abr, 09:13" con chevron
4. **Repeticiones** (collapsible row)
   - Resumen "Nunca" con chevron
5. **Nota de la cita**
   - Textarea "Nota para el personal"

### 2.5 Crear clase (full-screen modal)

Header: ← back arrow (left) + "Guardar" pill (right).

Form:
- **Detalles de clase** (con info icon ℹ)
  - Selector "Nombre de la clase" (combobox — selecciona de catálogo)
- **Calendario de clases**
  - Selector "Fecha" (mié, 29 de abr de 2026)
  - Selector "Hora de inicio"
  - Selector "Hora de finalización"
- **Repeticiones** › (default "Nunca")
- Field "Lugares disponibles" (capacity input)
- **Personal** dropdown (default "Nombre desconocido")

### 2.6 Crear evento personal (full-screen modal)

Header: X close (left) + "Evento personal nuevo" (sin pill, label) + "Guardar" (text, no pill).

Form:
- **Nombre del evento** (text input)
- Toggle "Todo el día"
- Row "mié, 29 de abr de 2026" + "09:32" (date + time)
- Row "Duración" + "30 minutos"
- Texto secundario "Finaliza a las 10:02"
- Toggle "Bloquear el tiempo como Ocupado" (ON default)
- **Personal** › (selector con chevron, default "Nombre desconocido")
- **NOTAS DEL EVENTO** (textarea)

### 2.7 Tab Más

**Header**:
- "Te damos la bienvenida de nuevo" (h1)
- Pill "La prueba termina en 17 días" (badge con icono cronómetro)
- Pill "Personalizar" (top-right outlined)
- Subtítulo: "Healthy boutique" (account name)

**Modo card** (card destacada full-width):
- Icono calendario + label "Modo" + sublabel "reservas"
- Chevron derecho → abre "Cambiar de modo" dialog

**Grid 2-columnas de shortcuts** (cards con icono + texto):
| Columna izquierda | Columna derecha |
|---|---|
| Configuración (6) | Reservas en línea |
| Lista de espera | Pedidos |
| Artículos & Servicios | Notificaciones |
| Transacciones | Informes |
| Servicios bancarios | Complementos |

Cada card: icono outlined a la izquierda, texto a la derecha, chevron implícito (toda la card es tappable). Badge `(6)` en Configuración indica setup pendiente.

### 2.8 Lista de espera (waitlist)

- Header: "Lista de espera" + `+` circular (top-right).
- Filter chips horizontales: "Ordenar | Lo más nuevo", "Servicio", "Personal".
- Empty state: ilustración + "No hay solicitudes de lista de espera" + descripción ("Administra los ajustes de tu lista de espera a través de los ajustes de reservas en línea")
- CTAs: "Agregar a la lista de espera" (black pill) + "Administrar ajustes" (outlined pill).

### 2.9 Reservas en línea

- Title: "Reservas en línea"
- Description block.
- Toggle row "Activar reservas en línea" (OFF default).
- Row de iconos de canales: Google · Instagram · Square (todos circulares).

---

## 3. Square Dashboard Web — Appointments

### 3.1 Sidebar (sub-sidebar dedicado, reemplaza al main sidebar)

```
← Appointments
─────────────────
Search ⌕
Overview          (active state: gris claro, left accent)
Calendar
Waitlist
Online booking ▾
  · Channels
  · Settings
  · Advanced widget
  · Invite clients
Settings ▾
  · Calendar & booking
  · Payments & cancellations
  · Communications
  · History
─────────────────
[Take payment]    (sticky bottom, outlined)
🔔 💬 ❓ ✨        (icon row)
```

### 3.2 Overview

- Welcome heading + Hide setup guide button (top-right).
- Setup wizard con progress bar (% setup), expandable "Primary setup" + "Advanced setup".
- Items con check/X: "Get set up to take payments", "Set up your location", "Create your services".
- Two-column abajo: Upcoming appointments | Notifications.
- "Time zone: CST" en el header de Upcoming.

### 3.3 Calendar (`/dashboard/appointments/calendar`)

Header controls:
- `← Date Apr 26 – May 2 →` (date range nav)
- `Range Week` (range selector — Day/Week/All)
- Right side: `⋯` (more), gear (calendar settings), 🕐 (refresh? jump to now?), **Create** (black filled pill).

Grid:
- Vista Week por default.
- 7 columnas (Sun-Sat) con headers tipo "Sun 04/26", "Mon 04/27".
- Eje vertical: hora (1 AM – 11 PM, configurable por business hours).
- Fila "All day" arriba para eventos all-day.
- **Línea roja de current time** en columna del día actual (no full grid).
- Cells fuera de business hours: gris claro.
- Tap en cell vacía → modal Create.

Coachmarks visibles a primer load:
- "Create an appointment — Click here to create a new appointment, class, or personal event." (señalando botón Create)
- "You can also create a new appointment, class, or personal event by clicking anywhere on the calendar."

### 3.4 Create Appointment (full-screen modal)

URL pattern: `/appointments/new?skipBlade=false`

- Header: X close (top-left circular) + ⋯ Actions + Save (top-right black pill)
- Title "Create appointment" como h2.
- **2-column layout**: Form (~65%) | Customer sidebar (~35%)
- Form:
  1. Event type dropdown: **Appointment / Class (★) / Personal event** (Class marcada con estrella ⭐ premium)
  2. Customer combobox
  3. Services and items: combobox "Add services" + buttons "Add item" + "Add discount"
  4. Subtotal $0.00 / Total $0.00
  5. Date and time:
     - Toggle "Repeat"
     - Toggle "All day"
     - Date input + Time input (lado a lado)
  6. Notes textarea ("Appointment notes")
- Customer sidebar: empty state "No customer selected" + "Select a customer to view their details"

### 3.5 Waitlist (`/dashboard/appointments/waitlist`)

- Title "Waitlist" + ⭐ icon (premium feature)
- Add request button (top-right black pill)
- Info banner amarillo: "Introducing: Automated notifications" — auto-notify clients when matching availability opens.
- Empty state + link a Online Booking Settings.
- Tooltip flotante: "New: Capture multiple date and time preferences per client"

### 3.6 Online Booking sub-pages

| Sub-page | URL | Purpose |
|---|---|---|
| Channels | `/booking/channels` | "Get booked online" CTA + 3 mockups móviles + "Enable online booking" black button |
| Settings | `/booking/settings` | Configuración de online booking (requiere enable) |
| Advanced widget | `/booking/advanced` | HTML embed code |
| Invite clients | `/booking/invite` | Send booking links a clientes |

### 3.7 Settings sub-pages

#### 3.7a Calendar & booking (`/business/settings`)

Long scrollable form, sticky Cancel/Save footer:

1. **Appointment preferences** — Where do you accept appointments? (radio: At my business / Customer's location / Both / Phone only)
2. **Online booking preferences**:
   - Reservation guarantee (radio: Auto-accept all / Must accept or decline)
   - Customer booking timezone (radio: Allow customer choice / Lock to business)
3. **Waitlist** ⭐:
   - Toggle "Enable waitlist on online booking site"
   - Toggle "Send notification when opening occurs"
4. **Marketing opt-in** — Toggle "Allow text message marketing opt-in"
5. **Customer profile fields** ⭐ — "Add custom field (when booking appointment / when booking class)"
6. **Online scheduling**:
   - Dropdown "Appointments scheduled at: 30 minute intervals"
   - Dropdown "Must be made in advance: None"
   - Dropdown "Can't be scheduled farther than: 365 days"
   - Toggle "Allow multiple services online" (ON)
   - Toggle "Remove team members from booking site"
   - Toggle "Daily appointment limit" ⭐
7. **Manage calendar sync** — "Link Google Calendar"
8. **Fake-it filter** — Dropdown "Reduce availability to appear busier: Off"

#### 3.7b Payments & cancellations (`/business/cancellation_policy`)

1. **Payments**:
   - "Protect against no-shows and late cancellations"
   - Options: No requirements / Deposit / Full payment / Card hold
   - Afterpay for online booking
2. **Cancellation policy**:
   - Cut-off time (configurable, default None)
   - Policy text (custom textarea)
   - Toggle "Client self-reschedule/cancel before cut-off" (ON)
   - Note: "Clients cannot cancel appointments that are prepaid or charged a deposit"

#### 3.7c Communications (`/business/client_relations`)

1. **Confirmations and Reminders**:
   - Toggle "Send confirmation request" (ON)
   - Dropdown "Confirmation method: Text Message"
   - Dropdown "When: 2 days prior"
2. **Reminders**:
   - Toggle "SMS reminder" + dropdown "When: 1 hour prior"
   - Toggle "Email reminder" + dropdown "When: 1 day prior"
3. **Square Assistant** ⭐ — Toggle "AI-powered: clients confirm/cancel/change by replying to SMS"
4. **Forms** — "Add form via Square Contracts"
5. **Preferred business language**
6. **Customizable email/SMS templates** (rows expandibles):
   - New appointments: Client requested · Business accepted · Accepted with changes · Business declined
   - Confirmation & reminders: Confirmation request · Reminder · Reminder with confirmation
   - Appointment changes: Business changed · Business rescheduled · Client rescheduled · Client requested reschedule · Accepted reschedule with changes · Declined reschedule
   - Appointment cancellations: Business cancelled · No-show · Client cancelled

#### 3.7d History (`/business/history`)

- Tabla: Date · Service · Staff · Client
- Search bar
- Export button
- Audit trail

---

## 4. UX Patterns Square reutilizables (resumen)

| Patrón | Donde se usa | Equivalente Avoqado actual |
|---|---|---|
| Full-screen modal con X (left) + Save pill (right) | Crear cita/clase/evento, Ajustes calendario | `FullScreenModal` ✅ |
| Action sheet centrado con full-width pills | + → cita/clase/evento | falta — usar `AvoqadoDialog` |
| Coachmarks numerados (1 of 2) | Onboarding calendar | falta |
| Date picker como dialog full-screen modal | Mes año ▾ | usar `DatePickerDialog` Material3 |
| Status toggles inline en settings con icono+label+toggle | Ajustes calendario | usar `Switch` Material3 |
| Radio group con título de sección encima | Vista del calendario | usar `RadioButton` Material3 |
| Mode switcher en Más | Cambiar de modo | nuevo concepto — implementar |
| Línea roja current-time | Calendar grid | implementar custom drawing |
| Week strip header (D L M M J V S + número) | Calendar tab | implementar custom |
| Card "Modo" con sublabel + chevron | Más > Modo | usar `ListItem` Material3 |
| Empty state con ilustración + CTA + secondary action | Lista de espera | usar `EmptyState` (a crear si no existe) |
| Filter chips horizontales | Lista de espera | usar `FilterChip` Material3 |
| 2-column shortcuts grid en Más | Más tab | usar `LazyVerticalGrid` |

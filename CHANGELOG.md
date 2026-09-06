# Changelog

## [Unreleased]

## [2.18.1] - 2026-09-06

### Fixed
- **🔴 Al reconectar, la caja abierta sin red ya no mete su dinero en la caja de otro aparato.** Si otro aparato abrió la caja del negocio mientras la tablet estaba sin red, la tablet adoptaba esa caja como propia y le colgaba sus movimientos previos (un retiro de $50 hecho antes de que esa caja existiera aparecía como faltante en la caja del otro). Ahora «¿es mi caja?» se decide con la identidad (la llave de la apertura), no con el id local: una caja ajena sólo recibe lo ocurrido mientras estuvo abierta, nunca el cierre ni la apertura, y lo que queda fuera se marca en Caja («Ya lo vi») en vez de reintentarse para siempre.
- **El corte ya no pierde un centavo por renglón**: el desglose por método redondea en vez de truncar (2.30 salía como 2.29).

## [2.18.0] - 2026-09-05

### Added
- **Turno de caja del NEGOCIO**: la pantalla de Caja dice «Turno de caja» y muestra quién lo abrió y desde qué aparato; abrir la caja crea o liga el turno del negocio en el servidor (ya no es de la persona), y cualquier cobro cae dentro de ese turno aunque lo haga otra persona.
- **Cierre del día accionable**: cada pendiente (cuentas abiertas, caja, checador) navega a resolverlo; la lista de pedidos gana el filtro «Abiertas» y se abre ya filtrada.
- **Apertura de caja sin red DURABLE**: la apertura entra a la cola antes de tocar la red, sobrevive a matar la app, se reproduce SOLA al volver la red y ANTES que los cobros (cobro rápido y mesas), una sola vez, y la caja del servidor conserva la hora real y una llave idempotente.
- **Barrera con voz y tope**: si la apertura no llega al servidor, los cobros esperan y la app lo dice; a los 30 minutos con red (un 500 cuenta como «llegó») se sueltan con aviso; backoff de 30 s entre reintentos.

### Fixed
- Una apertura ligada a la caja ya abierta del negocio ya no se pinta como «adoptada» cuando es la propia; el aviso de adopción sale al abrir la caja, no al volver a entrar.
- El retiro encolado por versiones anteriores ya no se pierde al actualizar; la caja que sólo existe en el aparato lo dice.

### Added
- **Loader y splash de marca Avoqado**: las cargas bloqueantes ahora dibujan el isotipo desde la semilla y hacen crecer el trazo verde desde el pico inferior. El arranque nativo muestra la semilla de inmediato y Compose continúa la animación sin cambiar el flujo de navegación; los spinners compactos de botones y paginación permanecen nativos. Incluye modo sin movimiento para accesibilidad.

### Fixed
- **Bottom navbar tabs ahora se actualizan al cambiar de role sin force-stop**: tras logout → login con otro role (ej. ADMIN → WAITER), el navbar quedaba con los tabs del role anterior. Tap en un tab no permitido (Inventario para WAITER) crasheaba con `IllegalArgumentException: Navigation destination route=inventory cannot be found`. Causa: `AppState.visibleTabs` combinaba solo `_reservationsEnabled` y `_venueMode`; cuando esos valores no cambiaban entre sesiones, `StateFlow` (distinct-by-equality) no re-emitía aunque `roleManager.role` ya reportaba el nuevo role. Fix: agregar `_roleVersion: MutableStateFlow<Int>` al combine y bump en `refreshTabs()` (ya llamado desde `onLoginSuccess()`). Archivo: `app/src/main/java/com/avoqado/pos/auth/presentation/AppState.kt`.
- **Venue-switch ahora recomputa tabs**: al cambiar de venue desde Más → Sucursal a otro con role distinto, `MoreMenuViewModel.switchVenue()` actualizaba `secureStorage.userRole` pero no notificaba a `AppState`, dejando los tabs del venue anterior. Fix: `switchVenue(venue, onSwitched)` ahora acepta un callback que `MoreMenuScreen` cablea a `onTabsShouldRefresh` (= `appState.refreshTabs()`). Archivos: `MoreMenuViewModel.kt`, `MoreMenuScreen.kt`.

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
- Cola offline con retrier al recuperar conexión + banner con conteo de pendientes.
- Permisos `reservations:read|create|update|cancel` (capability decoder listo; wiring JWT pendiente para v2.2.1).
- Toda la fechita pasa por `VenueDateTimeFormatter` (regla de v2.1.1 respetada).

### Pendiente para v2.2.1
- Push notifications canal `reservations` con deep-link a detalle (handler implementado, FCM service wiring pendiente).
- Strings centralizados en res/values-es/strings_reservations.xml (actualmente inline).
- Smoke test E2E + screenshots de la build.

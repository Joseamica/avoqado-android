# Changelog

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

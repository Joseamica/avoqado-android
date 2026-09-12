# Changelog

## [Unreleased]

### Fixed
- **«Cancelar» un cobro con terminal ya no deja la venta huérfana.** Antes el POST de cancelación a la terminal y el DELETE de la orden salían A LA VEZ: cuando el borrado llegaba primero, el servidor contestaba 409 «hay un cobro en curso», el aviso se perdía en un toast sobre una pantalla que ya se había ido, y la cuenta quedaba abierta para siempre — medido en producción el 11-sep. Ahora «Cancelar» tiene UNA semántica: se cancela el COBRO y, sólo cuando consta que no se cobró, se cancela la ORDEN **si la creó este mismo flujo** (la cuenta de una mesa o la de un split que ya existía jamás se borran). La intención se guarda en el aparato ANTES de tocar la red y la reproduce un coordinador que sobrevive a que el cajero salga, a cerrar sesión, a cambiar de sucursal y a que la app muera; si no se puede guardar, no sale ninguna petición y la pantalla lo dice.
- **La cancelación dice en qué va, y nunca miente.** «Cancelando el cobro…» mientras se pide; «Cancelación pendiente» (ámbar, nunca rojo) cuando la terminal todavía no confirma, con «Volver a consultar» y «Salir (queda pendiente)» — salir no borra nada y la cancelación sigue sola. Sin red lo dice con todas sus letras. Lo que queda pendiente se ve FUERA del cobro: banner ámbar «Cancelaciones pendientes: N» y su sección en la hoja de pendientes.
- **Si la terminal SÍ cobró el cobro que se pidió cancelar, la venta queda pagada y se dice** («La terminal sí cobró este pago: la venta queda pagada»). La orden jamás se borra en ese caso, y si nadie estaba mirando, el cobro queda cargado en la llave durable para que la próxima venta lo muestre.
- **Cancelar mientras se crea la orden ya no manda el cobro.** El toque llegaba antes de que la orden existiera y, cuando volvía la creación, el cobro salía igual a la terminal sin nadie mirando; ahora no se envía y esa orden recién nacida se cancela por la vía durable. Lo mismo en efectivo: el pago ya no se registra contra una venta que el cajero dio por cancelada.
- **Un rechazo de admisión que nombra a ESTA solicitud ya no deja la tablet esperando a una terminal que nunca recibió nada.** `TERMINAL_NOT_CONNECTED`, `TERMINAL_NO_SOCKET`, `TERMINAL_NOT_IN_VENUE`, `ORDER_CANCELLED_NO_NEW_CHARGE`, `ORDER_ALREADY_PAID` y `ORDER_NOT_FOUND` correlacionados por `details.requestId` sueltan la llave y dicen que el cobro NO se envió; sin correlación se sigue consultando, como hasta hoy. Y un `503 TERMINAL_PAYMENT_ADMISSION_RETRY` se reintenta con el MISMO `requestId`, conservando la llave.
- **El DELETE de una orden se lee de verdad:** el 409 con el cobro que bloquea, la cuenta con dinero, la que ya no existe, el 4xx de un túnel caído y un 5xx dejaron de ser todos «Error al cancelar orden». En mesas, anular, anular en masa y fusionar con un cobro de tarjeta vivo dicen qué hacer: «Hay un cobro con tarjeta en curso para esta cuenta: cancélalo o espera a que termine» — también en la cuarentena, cuando la anulación se encoló sin red.
- **Una terminal OCUPADA ya no deja a la tablet atorada en «Cobro sin confirmar».** Cuando el servidor rechaza CREAR el cobro con `409 TERMINAL_BUSY` y nombra a OTRA solicitud como la que ocupa la terminal, este intento nunca existió: nada viajó a la terminal y ninguna tarjeta pudo cobrarse con esta llave. Antes se leía como «no sé si se cobró», la llave durable quedaba armada, el GET del estado contestaba 404 para siempre y la tablet se quedaba bloqueada por un cobro que jamás se envió (Testarudo, 10-sep, con la PAX atorada). Ahora se libera la llave y se dice qué la ocupa —«Terminal ocupada: otro cobro de $X hace N min (aparato)»— y, con todas sus letras, que ESTE cobro no se envió, para que elegir otra terminal sea seguro. 🔴 La correlación es la garantía, no el código: un `TERMINAL_BUSY` que nombre a esta MISMA solicitud, o que no nombre a nadie (server viejo), sigue tratándose como incertidumbre; y un 409/404 sin código sigue mandando a consultar el estado, nunca a concluir «no se cobró».
- El aviso de un cobro sin confirmar de una venta anterior ya no repite dos veces «Estamos confirmando el cobro…» (el prefijo se COMPONE con la instrucción en vez de copiarla).
- **El ticket de efectivo imprime el dinero REAL: «Recibido» y «Cambio».** Antes, el cambio que la app había calculado bien (recibido − total) se sustituía por el `changeCents` del servidor, que significa otra cosa — lo que sobró del importe ENVIADO sobre el saldo de la orden — y en un cobro normal vale siempre 0. Con el cambio en 0, el recibo deducía el recibido como `total + cambio` e imprimía el TOTAL como si fuera el billete del cliente: en Testarudo (10-sep) el cliente entregó $550 sobre $544.50 y el ticket dijo «Recibido: $544.50», sin renglón de cambio. Afectaba a TODA venta en efectivo con productos; la venta rápida nunca consultó ese campo, por eso ahí sí salía bien. Ahora el cambio es `recibido − lo que de verdad se cobró` (fórmula que también cubre el caso de otra caja moviendo la orden) y el recibo imprime el monto que tecleó el cajero. El ticket cuadra consigo mismo: Recibido − Cambio = TOTAL.

## [2.18.2] - 2026-09-08

### Fixed
- Descartar un conteo deja el borrador y la cancelación pendiente en UNA sola escritura (antes, si la app moría entre las dos, el conteo se quedaba abierto en el servidor).
- Confirmar espera a que termine cualquier envío en vuelo (un envío lento ya no puede sellar una cantidad vieja); mientras dice «Confirmando…» no se puede editar ni salir.
- Al retomar, una línea que el servidor ya reconoció cede ante una corrección hecha desde otro aparato; sólo lo pendiente de este aparato gana.
- «Sin conexión» mira si el servidor responde, no sólo si hay WiFi.
- El conteo queda atado a su sucursal: cambiar de sucursal a media captura no manda nada a la sucursal equivocada.
- Si otro aparato cerró el conteo, «Confirmar» lo dice con claridad (antes salía un error genérico).
- Una cancelación pendiente contra un servidor sin la ruta de cancelar se conserva y se reintenta (antes se daba por hecha).
- **El conteo de inventario ya no se pierde.** Lo contado se guarda en el aparato en cada línea (antes vivía sólo en memoria hasta «Confirmar»: salir, cerrar la app o quedarse sin red lo borraba, y al retomar el servidor estaba vacío — caso Mindform, 7-sep). Con red, el avance viaja al servidor conforme se cuenta.
- Al salir de un conteo se pregunta «¿Qué hacemos con este conteo?» — Guardar el avance · Descartar el conteo · Seguir contando. El botón BACK del sistema también pregunta.
- Retomar un conteo se para en la primera línea sin contar y conserva lo que se contó en este aparato aunque otro haya contado otras líneas.
- En un conteo cíclico, contar un artículo ya NO marca los demás como contados (todas las líneas compartían un id vacío).
- Retomar un conteo cíclico ya creado en el servidor ya no crea un segundo conteo.
- Sin conexión, la banda ámbar dice cuántas líneas quedaron guardadas en el aparato; al volver la red salen solas.
- Si otro aparato cerró o canceló el conteo, se avisa y lo contado aquí se conserva para consulta.
- El mensaje de «tienes un conteo sin terminar» ahora se ve también desde el detalle de un conteo.

### Added
- Tarjeta «Conteo sin terminar en este aparato · X de N contados» con «Continuar» en la lista de conteos.
- La lista muestra «En progreso · X de N contados» (dato del servidor).
- Descartar un conteo completo lo cancela en el servidor (con cola si no hay red).
- 🔴 Requiere el servidor con la fase 1 del conteo (cancelar, `countedAt`, `summary`) desplegado ANTES de instalar esta versión: sin él, «Descartar el conteo» no puede cancelar en el servidor.

## [2.18.1] - 2026-09-06

> Publicada a Play en dos builds con el mismo nombre: **(37)** trae sólo los arreglos de caja de
> abajo; **(38)**, subida el 6-sep por la noche, añade además el renombre de modos y el conteo
> sospechoso listados aquí. El `versionCode` lo calcula la CI contra Play, así que el nombre no
> cambió.

### Added
- **Los modos del punto de venta se llaman como el mercado**: Retail → **Mostrador**, Restaurante →
  **Mesas**, Reservas → **Citas**. Sólo cambia la etiqueta: el modo que cada aparato tenía guardado
  no se mueve. Y el giro del negocio ahora **sugiere** un modo en el selector («Sugerido para tu
  giro») sin cambiarlo por su cuenta — un negocio con giro de restaurante que cobra en mostrador
  sigue abriendo en Mostrador.
- **Al cerrar la caja, si el conteo coincide con el dinero que ya se sacó** (los retiros del día, o
  todo lo cobrado en efectivo), la app pregunta una vez si contaste lo que quedó DENTRO del cajón.
  Es una pregunta, no un bloqueo, y no revela el esperado: el conteo sigue siendo ciego.

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

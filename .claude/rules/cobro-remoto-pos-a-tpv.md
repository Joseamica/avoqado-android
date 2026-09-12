# Cobro remoto POS → TPV (lado que MANDA)

🔴 La regla completa vive en `avoqado-tpv/.claude/rules/cobro-remoto-pos-a-tpv.md` — léela antes de
tocar `payment/data/TerminalPaymentService.kt`, `payment/domain/CardChargeOutcome.kt` o la parte de
terminal de `PaymentFlowViewModel.kt`. La terminal **no está en una pantalla fija** cuando la tablet
le manda un cobro: hay una matriz «estado de la terminal × evento» que hay que declarar y probar.

**Condición obligatoria** (founder, 10-sep): nunca volver a autorizar con un desenlace pendiente ni perder
evidencia de dinero. Dentro de esa condición, las prioridades son: cobro disponible y sin demoras
evitables; registro correcto y recuperable; obligaciones con responsable; pantalla honesta. La espera de
hasta 330 s del POS por el resultado de la terminal es deliberada (`CardChargeOutcome.kt`): reducir
demoras evitables no es acortar esa ventana ni tratar su vencimiento como fallo financiero.

Invariantes de este lado, ya construidos y con prueba — no los debilites:

- La llave durable (`SecureStorage.pendingCardChargeRequestId`) se escribe ANTES del POST y sólo se
  suelta cuando el desenlace CONSTA para ese mismo `requestId`. **Cancelar no es «no se cobró».**
- Un `409 TERMINAL_BUSY` al CREAR cuya `blockingRequest.requestId` es de OTRA solicitud no es un
  cobro incierto (T15): se suelta la llave y se dice «Terminal ocupada: otro cobro de $X hace N min».
  Eso acredita que ESA solicitud nueva no se creó; **no resuelve el cobro del bloqueador**. Si nombra a
  la propia solicitud, no nombra a nadie, o es un 409/404 sin `code`, se sigue consultando.
- Un 404 del estado durable NO prueba ausencia de cargo (la fila nace después de validaciones); un
  `CANCELLED` sin `cancelDisposition = ACCEPTED` tampoco.
- El selector de terminales muestra «Conectada» aunque la terminal esté reservada o dormida (Doze):
  hoy el cajero lo descubre al enviar. El servidor YA manda `busy` en `/terminals/online`; `OnlineTerminal`
  de Android e iOS lo descarta — la fase 3 («ocupada por $X hace N min») es cambio de cliente. Y un 409 por
  ORDEN («esta venta tiene un cobro pendiente») hoy se muestra con la frase de terminal ocupada, que en ese
  caso no aplica.
- 🔴 Pendiente (10-sep): distinguir un POST nunca enviado requiere evidencia de todos los intentos del
  mismo `requestId`; `ConnectException`, `UnknownHostException` o un error TLS NO bastan por su clase.
  Aplicar el criterio del «Escenario del founder» de la regla principal. Mantener la llave durable
  antes del POST y conservarla si alguna copia pudo enviarse o todavía puede hacerlo. Timeout y
  GET 404 no resuelven esa incertidumbre. Implementar con TDD y a la vez en iOS.

Al probar: tablet real contra terminal real, y en el reporte se declaran las celdas de la matriz
ejecutadas, no ejecutadas y no aplicables. Las acciones que necesita el founder se piden EN MAYÚSCULAS y
con la acción exacta. Evidencia: `avoqado-server/docs/investigations/testarudo-relevo-2026-09-10/README.md` §2-ter.

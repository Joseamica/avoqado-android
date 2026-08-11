# Cancelar no es "no se cobró": el desenlace tardío ya no se tira a la basura

**Fecha:** 2026-08-10 · **Rama:** `fix/cancel-resultado-tardio` · **Repo:** avoqado-android

Segundo pasillo del mismo incidente que se cerró hoy en
[`2026-08-10-doble-cobro-tarjeta.md`](2026-08-10-doble-cobro-tarjeta.md). El error de fondo se
repite: **tirar información sobre un cobro que pudo haber ocurrido.** Lo encontró el port a iOS
revisando con ojos frescos (`avoqado-ios`, `fix/doble-cobro-tarjeta`, `65921fa`); Android lo
compartía tal cual.

---

## La secuencia que se cierra

1. El cajero manda el cobro a la terminal.
2. El cliente empieza a pagar y el cajero **cancela desde el POS**.
3. 🔴 **Cancelar es una PETICIÓN, no una garantía.** Si la tarjeta ya se pasó, la terminal cobra
   igual. El server lo sabe y lo documenta: `closeRowFromPaymentTx` reconcilia a `COMPLETED`
   cualquier fila —incluso una ya cerrada como `CANCELLED`— cuando el pago de verdad aterriza, y
   emite un `🚨` porque "un intento cancelado sí se llevó dinero".
4. La terminal responde **"cobrado"**, pero tarde: después de la cancelación. La espera dura hasta
   `WAIT_CEILING_MS` = 330 s, así que la ventana es enorme.
5. El guard de resultado obsoleto (`generation != paymentGeneration`) descartaba ese desenlace
   **entero — incluido el cobro exitoso** — con un `return` y un `Log.d`.

**Resultado:** el dinero se cobró y la venta quedó marcada como impaga. Nadie sabía que ese pago
existía. Y lo que sigue es predecible: el cajero ve la venta sin pagar y **cobra otra vez**.

Peor todavía en Android que en iOS: `TerminalPaymentService.sendPaymentToTerminal` **limpia la
llave durable** (`unresolvedRequestId = null`) al recibir el 2xx, *antes* de que el ViewModel
llegue a evaluar el guard. O sea que el éxito tardío no sólo se descartaba en pantalla: borraba
de disco la única referencia que permitía preguntar por él.

---

## El principio

**Si la terminal dice "cobré", eso se cree — aunque llegue tarde y aunque hayas cancelado.**

Un resultado obsoleto puede descartarse para efectos de *navegación* (el cajero ya se fue de esa
pantalla, no se le secuestra la pantalla nueva ni se imprime un recibo sobre ella), pero **jamás**
para efectos de *dinero*: ese cobro existe y la venta tiene que enterarse.

---

## La regla pura que se extrajo

`CardChargeDecision.unresolvedKeyAfterStaleResult(outcome, requestId, armedKey)` —
`app/src/main/java/com/avoqado/pos/payment/domain/CardChargeOutcome.kt`.

Dado un desenlace que llegó tarde tras cancelar, contesta **qué llave durable queda armada** para
la próxima venta:

| Desenlace tardío | Llave que queda | Por qué |
|---|---|---|
| `Charged` | `requestId` | El dinero salió: no puede desaparecer en silencio. |
| `Undetermined` | `requestId` | Sigue sin saberse: se resuelve preguntando, no cobrando. |
| `NotCharged` | `null` | Único caso que CONSTA. Cierra el asunto. |
| Cualquiera, con **otro** cobro ya dueño de la ranura | la llave ajena, intacta | Ver abajo. |

Reusa el invariante que ya gobierna este módulo: **"no cobró" sólo nace de un estado que lo
AFIRME** (`FAILED` / `CANCELLED`), nunca de una ausencia ni de una ignorancia.

Una llave **en blanco** cuenta como ranura LIBRE, nunca como cobro ajeno: un `""` no es
consultable (el GET del estado iría sin id) y, tratado como llave, congelaría la ranura y dejaría
la venta siguiente con un "Cobro sin confirmar" que nadie puede resolver. Tampoco se ARMA en
blanco: no se escribe basura en disco. Salió de un test rojo — el mock relajado devolvía `""`
donde producción devuelve `null` — y se quedó porque la defensa vale por sí sola.

### El parámetro `armedKey` (delta respecto a iOS)

`SecureStorage.pendingCardChargeRequestId` es **una sola ranura**. Escenario real: el cajero
cancela A, asume el riesgo (`chargeAgainDespiteUndetermined` suelta la llave), manda el cobro B en
otra terminal, B queda sin confirmar y **arma la ranura** — y recién entonces A, cuyo long-poll
seguía vivo, contesta "cobré". Si el rezagado pisara la llave, "Volver a consultar" resolvería A
("sí se había realizado"), limpiaría la ranura, y **B —el que todavía puede tener dinero encima—
se perdería para siempre.**

Por eso la regla nunca sobrescribe una llave que pertenece a un `requestId` distinto: gana el
cobro que sigue vivo. iOS (`65921fa`) tiene la versión de dos parámetros, sin este guard —
**divergencia consciente, pendiente de portar** (ver "Paridad" al final).

---

## Qué cambió

| Archivo | Cambio |
|---|---|
| `payment/domain/CardChargeOutcome.kt` | Nueva función pura `unresolvedKeyAfterStaleResult`. |
| `payment/data/TerminalPaymentService.kt` | `TerminalPaymentResult.Success` ahora lleva `requestId` (sin él, un éxito tardío no es re-armable). `rearmUnresolvedCharge(requestId)` abre la puerta para re-armar/soltar la llave durable. |
| `payment/presentation/PaymentFlowViewModel.kt` | El guard obsoleto llama a `handleStaleCardResult()` en vez de `return` pelón: aplica la regla, re-arma la llave y suelta `undeterminedRequestId` (esta pantalla ya no gobierna ese cobro). |

**No se inventó pantalla nueva.** El desenlace desemboca en la que ya existe: en la venta
siguiente `startFlow` encuentra la llave en disco y muestra **"Cobro sin confirmar"** con su
**"Volver a consultar"**. Como `undeterminedRequestId` quedó en `null`, entra por la ruta
`fromPreviousSale = true`: informa del cargo viejo **sin marcar como pagada la venta nueva**.

---

## Qué pasa en el camino feliz

Cancelar antes de que la terminal haga nada **sigue cancelando limpio**, y no por suerte: el
server contesta **409** a un cobro cancelado
(`terminal-payment.mobile.controller.ts`: `status === 'cancelled' ? 409`). Un 409 no es fallo de
transporte (`isTransportFailure` lo deja fuera a propósito), así que el cliente produce
`TerminalPaymentResult.Error` → `NotCharged` → **llave `null`**.

Sin referencias colgadas y sin pantallas de "Cobro sin confirmar" fantasma. Está blindado con
test propio (`cancelar antes de que la terminal haga nada sigue cancelando limpio`): si eso se
rompiera, CADA cancelación normal dejaría un fantasma bloqueando la venta siguiente — mucho más
visible y más caro que el bug que se cierra.

---

## Tests

**Test primero** (camino del dinero, no negociable).

`CardChargeDecisionTest` — la regla pura, 8 casos nuevos: cobro afirmado · rechazo afirmado ·
indeterminado · llave ajena más nueva (con y sin cargo) · llave propia · sin `requestId` · llave
en blanco.

`PaymentFlowViewModelTest` — el cableado real, 4 casos nuevos. El envío se deja **en vuelo**
(`coAnswers { delay(60_000) }`), se cancela en medio y recién entonces contesta la terminal:
cobró · sigue sin saberse · canceló limpio · llega cuando otro cobro ya es dueño de la ranura.

**Suite: 929 → 941, 0 fallas.** `assembleDebug` OK.
Comando: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:testDebugUnitTest :app:assembleDebug`

---

## Paridad Android ↔ iOS

El comportamiento troncal queda espejado con `avoqado-ios@65921fa`, mismos textos y misma
semántica. **Falta portar a iOS el parámetro `armedKey`** (que un desenlace rezagado no pise la
llave de un cobro posterior que sigue vivo). No se hizo en este trabajo: el worktree de iOS
(`avoqado-ios/.claude/worktrees/doble-cobro`) es de otra sesión y había un `xcodebuild` corriendo
sobre ese repo. Es un cambio de ~6 líneas en `CardChargeOutcome.swift` + su llamada en
`PaymentFlowViewModel.swift`, con los tres tests equivalentes.

# Cuenta compartida entre áreas, vales y báscula — diseño

**Fecha:** 2026-07-28 · **v3** (MVP online tras segunda auditoría)
**Repos:** `avoqado-server` · `avoqado-android` · `avoqado-web-dashboard` *(iOS: fase 2, §12)*
**Origen:** cliente en Culiacán — cafetería + panadería + cremería (granel) + conveniencia.
3 áreas emisoras + 1 caja, sobre Sunmi D3. Arranque objetivo: 1 semana.

> **Historia de este documento.** v1 se auditó con `/autoplan` (Codex + revisor independiente) y
> falló en 3 puntos estructurales. v2 reescribió la arquitectura a cuenta compartida y se auditó
> con Codex `gpt-5.6-sol` en `xhigh`: **volvió a fallar**, con dos defectos que la propia v2
> introdujo. v3 recorta a un **MVP estrictamente online** y cierra ambos. El registro está en §13.

---

## 1. El flujo del negocio

Confirmado con el cliente por WhatsApp el 2026-07-28.

```
  ÁREA 1 (la primera que atiende)
    1. Atiende: pesa el jamón / marca el pan / toma el pedido
    2. ABRE LA CUENTA, imprime el vale con su código
    3. SE QUEDA CON EL PRODUCTO
  ÁREA 2..N
    4. Escanea el vale que el cliente trae → AGREGA a la MISMA cuenta
    5. Imprime su vale (mismo código, sus renglones) y guarda su producto
  CAJA (una)
    6. Escanea cualquier vale → abre esa cuenta
    7. Suma papas y refrescos por GTIN normal
    8. Cobra UNA vez; el ticket pagado lleva el mismo código
  REGRESO AL ÁREA
    9. El cliente vuelve con el ticket pagado
   10. El área lo escanea → ve SOLO sus renglones → entrega y marca ENTREGADO
```

Cita del cliente (2:17 PM): *"El área le guarda el producto hasta que regresa con el ticket
pagado."* De ahí el requisito de entrega: hoy nada impide canjear el mismo vale dos veces, y es
donde está el producto de mayor valor por kilo.

**Escala:** 3 áreas · 1 caja · WiFi del local.

### 1.1 Por qué cuenta compartida y no vales fusionables (decidido en v2, se mantiene)

Los 3 tickets separados existen porque el sistema viejo del cliente **no sabe compartir una cuenta
entre dispositivos**. Avoqado sí. Además, la fusión no era construible: la orden de server nace al
pagar (`PaymentFlowViewModel.kt:655`) y `mergeFrom` exige sesión de mesa
(`TableOrderViewModel.kt:923`) — en la caja no hay ninguna de las dos.

**El cliente no ve el cambio:** sigue recibiendo su papelito en cada área. El papel es el token.

### 1.2 🔴 Decisión de alcance: el MVP es ONLINE

La segunda auditoría demostró que el offline multi-dispositivo **no es alcanzable en una semana**,
por razones que no dependen de cuánto se trabaje:

- **Todo el reducer offline está gateado por `TABLE_SERVICE`** — verificado: `applyOpenTable`,
  `applyAddItems`, `applyOrderMutation`, `applyClearTable`, `applySplitOrder`, `applySplitBySeat`
  y `applyMergeOrders` llaman `assertTableService`. No es que falte un gate: falta abrirlos todos.
- **El hub LAN no replica cuentas.** Solo arrienda leases de mesa (`LanHubService.kt`). Una cuenta
  abierta en el área 1 sin red **no existe** para la caja. El hub no es la pieza que falta; es una
  pieza distinta.
- **Sin red, el papel no prueba `PAID`.** El vale sin pagar y el ticket pagado llevan el mismo
  código. Entregar de todos modos abre fraude; esperar el ack impide entregar. No hay tercera
  opción sin criptografía o replicación.
- **Un `RETRY` corta el batch** (`SyncOutbox.kt:206`). Un fulfillment esperando pago bloquearía
  hasta 24 h todas las ventas posteriores de ese dispositivo.

**Qué significa en la práctica:** el local tiene WiFi. Si se cae, el POS avisa claro y el flujo de
vales se detiene — igual que hoy se detiene su sistema actual. Lo que **no** se detiene es la venta
directa de mostrador, que ya funciona offline y no se toca.

**Lo que NO se promete:** operación de vales entre dispositivos sin internet. No va en el material
de venta hasta que exista (§12).

---

## 2. Alcance y tiers

| Código | Cubre | Tier |
|---|---|---|
| `AREA_TICKETS` | Cuenta compartida, vales, entrega/canje | **PRO** — online-only, declarado |
| `SCALE_INTEGRATION` | Driver USB-serial de báscula | **PREMIUM** + kit certificado e instalación |
| `VARIABLE_WEIGHT_BARCODE` | Decodificar EAN-13 de peso variable | **PRO** — fase 2 |

**Cómo se declaran.** `basePlan.service.ts` **solo enumera** `PREMIUM_ONLY_CODES` y
`FREE_TIER_CODES`; **PRO es blanket**. Solo `SCALE_INTEGRATION` va ahí. Meter los códigos PRO los
volvería PREMIUM. Test de espejo en CI que falle si eso pasa.

**Honestidad de tier.** `AREA_TICKETS` en PRO es online-only y se dice así. La alternativa
(`AREA_TICKETS_LAN` en PREMIUM) **no existe hoy**: exigiría que el hub replique cuentas, renglones,
pagos y claims — trabajo mayor que este spec completo. Vender "offline" hoy sería vender algo que
no está construido.

**Consecuencias ya conocidas:** un plan vencido puede gatear **crear** vales, pero consultar,
cobrar y **entregar** los ya abiertos debe seguir funcionando — hay producto de un cliente en el
mostrador. Y `voidRecordsWaste` se oculta sin `INVENTORY_TRACKING` (PREMIUM).

---

## 3. Lo que ya existe y se reutiliza

| Pieza | Dónde |
|---|---|
| Venta por peso | `Product.soldByWeight` (`schema.prisma:1523`), `OrderItem.weightQuantity Decimal(12,3)` |
| Aritmética de peso | `pos/pos/data/model/Weight.kt` — half-up, paridad al centavo |
| Captura manual de peso | `pos/presentation/product/WeightCapturePanel.kt` |
| Ruteo de impresión | `Printer` · `PrintStation` · `PrintGateway` · `PrintJob` |
| Escáner | `BarcodeScannerView.kt` (ML Kit + CameraX), montado en `CheckoutScreen.kt:642` |
| Impresión QR nativa | `ESCPOSPrinter.printQr()` (`:153`) |
| Registro pasivo de terminal | `registerDevice.middleware.ts` + `deviceRegistry.service.ts` |

**Ya funciona sin escribir código:** escanear papas y refrescos por `sku`/`barcode`/`gtin`.

**No sirve:** `SavedCartsRepository` es `SharedPreferences` (local del dispositivo).

---

## 4. Hallazgos de campo (verificados, sin cambios desde v1)

**4.1** El código del cliente (`VPZ1617070`) es **referencia opaca** a su base de datos, no lleva
peso ni precio — y no podría: ese ticket trae 5 renglones y un solo código. → "El deli se queda con
su sistema" no existe sin integrar con su proveedor. La cremería entra a Avoqado.

**4.2** Su simbología es **CODE39** (los asteriscos son su firma). Su pistola **sí lee CODE128**,
probado con código real el 2026-07-28. → CODE128-C, con CODE39 configurable de respaldo.

**4.3** Su aritmética **coincide con la nuestra**: `0.224 × 164.00 = 36.736 → 36.74`,
`0.306 × 233.50 = 71.451 → 71.45`, total 273.98 ✓. Half-up a 2 decimales por renglón, peso a 3 en
kg. → Los importes cuadrarán al centavo el día de la migración. Va como test (§11).

**4.4** `orderNumber` es `` `ORD-${Date.now()}` `` = 17 caracteres. No cabe confiable en 58 mm.

---

## 5. Server

### 5.1 🔴 Código de vale — monótono y con espacio propio

**Los dos defectos que la v2 introdujo y esta versión corrige:**

1. **El contador se reiniciaba cada día.** El primer vale del dispositivo 47 hoy tenía el **mismo
   código** que el primero de ayer, y la ruta solo recibe `:code`. Un vale de ayer resolvía la
   cuenta de **otro cliente** hoy. No era caso borde: pasaba el primer vale de cada día.
2. **8 dígitos colisionan con EAN-8.** Y el escáner busca producto primero
   (`CheckoutScreen.kt:643`), así que un producto con ese código escondía el vale en silencio.

**Formato: `9 PP NNNNNN C`** — 10 dígitos.

| | |
|---|---|
| `9` | marcador de espacio de nombres. **10 dígitos ≠ EAN-8 (8) ≠ UPC-A (12) ≠ EAN-13 (13)** |
| `PP` | partición del dispositivo (10..99), asignada por el server (§5.2) |
| `NNNNNN` | contador **monótono**, nunca se reinicia. 1M vales por dispositivo |
| `C` | verificador mod-10 |

**Sin reinicio diario desaparecen de golpe:** `businessDay`, el cálculo de día operativo, el
conflicto de autoridad de reloj cliente/server, el rollover de medianoche, la venta iniciada a las
23:58, y el bug de arriba. Unicidad simple: `@@unique([venueId, areaTicketCode])`, para siempre.

**El verificador es para errores de dedo, no seguridad.** Mod-10 es público y se adivina en diez
intentos. Contra canje ajeno: **rate limiting** en el endpoint de resolución, y el vale ya entregado
responde "ya entregado", no la cuenta.

**Resolución del escáner — orden fijo:** si el código tiene 10 dígitos, empieza en `9` y el
verificador cuadra → resolver como **vale**. En cualquier otro caso → producto. Determinista, sin
ambigüedad, y arregla el defecto 2.

`GET /mobile/venues/:venueId/area-tickets/:code` → cuenta viva · `ALREADY_PAID` · `DELIVERED` ·
`NOT_FOUND`. Nunca un 404 mudo: el cajero lo re-teclea tres veces antes de entender.

### 5.2 🔴 Partición del dispositivo — hay que construir el punto de entrega

El registro que existe hoy es **pasivo**: `registerDevice.middleware.ts` graba una `Terminal`
*después* de responder, y **no devuelve nada al cliente**. `/devices/register` es para tokens push
(`push.mobile.controller.ts:27`), no identidad POS.

Falta: asignación **transaccional** de partición, `@@unique([venueId, partition])`, y devolverla al
cliente en el login. Sin eso dos dispositivos pueden acuñar la misma secuencia.

**Problemas de ciclo de vida, decididos explícitamente:**

- **Reinstalación (Android):** `allowBackup=false` (`AndroidManifest.xml:56`) borra `deviceId` y
  contador. Al re-loguearse pide partición nueva. **Las particiones NO se reciclan** mientras
  existan vales vivos de la vieja — 90 particiones alcanzan para años en un local de 4 terminales,
  y el agotamiento avisa en el dashboard antes de bloquear.
- **Restore (iOS):** `UserDefaults` viaja en la restauración, así que el dispositivo viejo y el
  restaurado podrían acuñar la misma secuencia. **Mitigación:** el server valida
  `(partition, counter)` contra el máximo visto; un contador que retrocede se rechaza con
  `AREA_CODE_REPLAY` y el cliente pide partición nueva. *(iOS es fase 2 de todos modos, §12.)*
- **El contador se persiste transaccionalmente**, no con read-modify-write de preferencias.

### 5.3 🔴 `FulfillmentArea` — y quién asigna el área

`OrderItem.printStationId` es **espejo derivado de impresión** (`schema.prisma:2908`), no autoridad.
El área es entidad propia:

```
FulfillmentArea
  id · venueId · name · fulfillmentMode · active · displayOrder · printStationId?
  @@unique([venueId, name])

Terminal.fulfillmentAreaId?    // ← el binding que faltaba en v2
OrderItem.fulfillmentAreaId    // autoridad de "de quién es este renglón"
```

**Quién lo pone:** el server, desde `Terminal.fulfillmentAreaId` de la terminal que envía el
renglón. **No se confía en el id que manda el cliente** — sería manipulable. Si la terminal no
tiene área, la línea queda como `null` (caso caja, abajo).

**Las líneas de la caja (papas, refrescos) llevan `fulfillmentAreaId = null`.** Semántica:
*"se entrega en la caja, al momento"*. No se inventa un área sintética "Caja" — sería una entidad
que nadie configura y que aparecería en las listas de pendientes de las 3 áreas reales. Un `null`
explícito, documentado, con la pantalla de entrega ignorando esas líneas.

### 5.4 🔴 Estados de la cuenta y cobro atómico

v2 no tenía máquina de estados. Sin ella no se sabe cuándo la cuenta deja de aceptar renglones.

```
OPEN ──(la caja escanea)──► CHECKOUT_CLAIMED ──(pago ok)──► PAID ──(todo entregado)──► CLOSED
  │                                │
  └──────(cancelar)────────────────┴──► CANCELLED
```

- En `CHECKOUT_CLAIMED` las áreas **no pueden agregar** renglones. Sin eso, el área suma jamón
  mientras la caja cobra y el total se mueve bajo los pies del cajero.
- El claim lleva `claimedByTerminalId` y caduca sola a los 5 minutos (una caja que se cuelga no
  puede secuestrar la cuenta).

🔴 **Cobro atómico — arregla también un bug ya desplegado.** `payCashOrder()` lee `paymentStatus`
**fuera** de la transacción (`order.mobile.service.ts:~1607`) y crea el `Payment` dentro
(`~1696`). El guard de idempotencia solo cubre dos requests con la **misma** llave — el índice es
`@@unique([venueId, idempotencyKey])`. Dos dispositivos generan llaves distintas, ambos leen
`PENDING`, ambos crean un pago, y `paidAmount > total` sin que nadie se entere hasta el corte.

Hoy es estrecho: en mostrador la orden nace al pagar en un solo dispositivo, y el reintento del
mismo dispositivo reusa la llave (`PaymentFlowViewModel.kt:115`). **Con cuenta compartida deja de
ser estrecho** — 4 dispositivos conocen el mismo `orderId` por diseño, y las órdenes sin mesa no
tienen guard de propiedad (`sync.mobile.service.ts:269`).

→ La lectura y validación de `paymentStatus` se mueven **dentro** de la transacción, con transición
condicional `CHECKOUT_CLAIMED → PAID` vía `updateMany` que devuelve 0 si alguien más ganó. El
camino idempotente actual se conserva.

### 5.5 Entrega — renglón completo, sin parcialidad

```
OrderFulfillment
  id · orderId · fulfillmentAreaId · deliveredAt · deliveredByStaffId · terminalId
  @@unique([orderId, fulfillmentAreaId])
OrderFulfillmentLine
  fulfillmentId · orderItemId
  @@unique([fulfillmentId, orderItemId])
```

🔴 **La entrega parcial sale del MVP.** v2 la prometía y el modelo no la soportaba: sin `PARTIAL`,
con un solo `deliveredAt`/actor, sin distinguir reintento de segunda entrega, sin decir si
`deliveredQty` es delta o acumulado, y sin definir qué significa "cantidad" en una línea pesada.
Hacerla bien pide `FulfillmentEvent` append-only con llave de idempotencia y agregado derivado —
fase 2 (§12). Prometerla a medias es peor que no tenerla.

`POST .../orders/:id/fulfill { fulfillmentAreaId }` — **idempotente**: segundo intento devuelve
*"ya entregado a las 14:31 por Rosa"*, no error. Exige `PAID`; fijo, no configurable.

**Reporte "pagado y no entregado"** y **pantalla "vales pendientes"** por área. Sin ellos el área
guarda producto perecedero a ciegas.

### 5.6 Modos de área

| Modo | El área… | ¿Guarda? | Ejemplo |
|---|---|---|---|
| `IMMEDIATE` | entrega al momento | no | pan que te llevas |
| `HOLD_UNTIL_PAID` | prepara ya, guarda hasta el ticket pagado | sí | **cremería** |
| `PREPARE_ON_PAID` | prepara cuando el cliente regresa pagado | n/a | cafetería |

Default `IMMEDIATE`. Los tres necesitan disparo pre-pago nuevo: en mostrador la comanda hoy sale
**después** de pagar (`autoPrintAfterPayment`, `PaymentFlowViewModel.kt:1299`) y el vale se emite
antes. En `PREPARE_ON_PAID` la comanda impresa probablemente sobra — quien dispara y quien recibe
es la misma persona; lo que necesita es ver el pedido en su pantalla.

### 5.7 Cancelación

Capacidad siempre; política por venue (`voidRequiresReason`, `voidRequiresManagerPin`,
`voidRecordsWaste`). **Después de cobrar no es cancelar, es reembolsar** — `cancelOrder` rechaza
órdenes pagadas (`order.mobile.service.ts:2119`); ese caso va por `issue_refund`.

### 5.8 MCP

Ruta correcta: **`src/mcp/tools/`** (45 archivos). **No** `scripts/mcp/`, que no existe — el
`CLAUDE.md` del repo también lo dice mal, corregir aparte. Herramientas: `area_ticket_status` y
`pending_fulfillment`. Fase 2 (§12).

---

## 6. Contrato de API — CONGELADO

> Fuente de verdad para el fan-out. Server y Android implementan **contra esto**.

**Sin intents nuevos en el MVP.** Al ser online, el flujo de vales usa endpoints REST directos. Los
2 intents de sincronización (`OPEN_AREA_TICKET`, `FULFILL_AREA`) son fase 2 y llegan junto con
abrir el reducer más allá de `TABLE_SERVICE` (§1.2).

```
POST   /mobile/venues/:venueId/area-tickets           → abre cuenta, devuelve código
GET    /mobile/venues/:venueId/area-tickets/:code     → resuelve (viva|ALREADY_PAID|DELIVERED|NOT_FOUND)
POST   /mobile/venues/:venueId/area-tickets/:code/items → agrega renglones (área o caja)
POST   /mobile/venues/:venueId/area-tickets/:code/claim → CHECKOUT_CLAIMED (caja)
POST   /mobile/orders/:id/fulfill                     → entrega, idempotente
GET    /mobile/venues/:venueId/fulfillment/pending    → vales pendientes por área
POST   /mobile/devices/partition                      → asigna/devuelve partición (login)
```

`PrintJobType.AREA_TICKET` en tres lugares: enum de Prisma (`schema.prisma:11482`), reducer, y el
tipo de Android — que hoy es un **`String` suelto** (`PrintJobModels.kt:20`) y debe volverse enum
antes de agregarle un valor.

### 6.1 🔴 Verificador — **GS1 mod-10**, no Luhn

"mod-10" **no identifica un algoritmo**: Luhn también se llama así. Dos repos que elijan distinto
se rechazan el **90 % de los vales** entre sí (medido sobre 71,910 códigos), y el síntoma no apunta
a nada: el cajero escanea, no pasa nada, y nadie sospecha que el dígito se calcula distinto de cada
lado.

**El algoritmo es GS1 mod-10** (el de EAN/UPC, pesos 3/1), sobre los **9 dígitos de datos**:

1. Numerar de **derecha a izquierda**.
2. Peso **3** a la posición 1 (la más a la derecha), **1** a la 2, 3 a la 3… alternando.
3. `C = (10 − (suma mod 10)) mod 10`.

**Ejemplo trabajado — obligatorio como test en los dos repos:**

```
datos  947000001
d1..d9 de derecha a izquierda: 1,0,0,0,0,0,7,4,9
suma = 1·3 + 0·1 + 0·3 + 0·1 + 0·3 + 0·1 + 7·3 + 4·1 + 9·3 = 55
C    = (10 − (55 mod 10)) mod 10 = 5
código completo → 9470000015
```

*(Verificado el 2026-07-28: `src/lib/areaTicketCode.ts` del server y `AreaTicketCode.kt` de Android
coinciden en los 71,910 códigos del espacio útil. Coincidieron por interpretación, no porque
estuviera escrito — por eso queda aquí.)*

### 6.2 🔴 Handshake de partición — se toma el MÁXIMO de los dos contadores

`POST /mobile/devices/partition` devuelve `{ partition, lastCounter, fulfillmentAreaId, … }`.
El cliente guarda **`max(contador local, lastCounter del server)`**, nunca el menor. Cada fuente
tapa el hueco de la otra:

- **El server cubre la reinstalación.** En Android `allowBackup=false` borra el contador; si el
  server devuelve la MISMA partición y el cliente arrancara en 0, **repetiría códigos ya impresos
  y en manos de clientes**. En iOS el agravante es el restore (§5.2).
- **El local cubre al server desactualizado.** Los vales se acuñan sin pedir permiso; si algunos
  no han llegado al server, su `lastCounter` va atrás y hacerle caso reacuñaría esos códigos.

Partición distinta → espacio de nombres nuevo: se ignora el contador local y se respeta el del
server. `lastCounter` fuera de rango se recorta a `0..999_999`, nunca revienta.

**El contador arranca en 1**, no en 0: `lastCounter = 0` en partición virgen → primer vale
`9-PP-000001`. La validación de replay del server acepta esa base. Son 999,999 vales usables por
dispositivo — a 300/día son ~9 años.

> **Hueco conocido, NO construido:** no existe la semántica de *"dame partición nueva, la mía se
> agotó"*. El cliente expone `remainingCodes` y `PartitionExhausted` para avisar antes de
> bloquear, pero el endpoint no sabe reasignar. A 9 años de distancia, se difiere a propósito.

*(Implementado y verificado 2026-07-28: `AreaTicketCodeStore.setPartition(partition,
serverLastCounter)` + 5 tests, incluidos reinstalación, server atrasado y valores absurdos.)*

### 6.3 🔴 El estado va en el cuerpo, nunca en el status HTTP

`GET /area-tickets/:code` devuelve **200 en los cuatro casos** —
`OK` · `ALREADY_PAID` · `DELIVERED` · `NOT_FOUND` — con `state` y un `message` en español.

Es desviación deliberada de REST puro: un 404 real le llega al cliente Android como error de red
genérico, y el cajero no aprende nada de un vale vencido o ya entregado (§5.1, "nunca un 404 mudo").

**Contrato para los clientes: leer `state` del cuerpo. Nunca ramificar por el status HTTP.**

---

## 7. Clientes

### 7.1 Android

| # | Pieza |
|---|---|
| 1 | `ESCPOSPrinter.printBarcode()` — `GS k` CODE128-C + CODE39 |
| 2 | Vale de área + disparo pre-pago (§5.6) |
| 3 | Partición en login + acuñado del código (§5.1, §5.2) |
| 4 | Escáner: 10 dígitos con `9` + verificador → vale; si no → producto |
| 5 | Caja: escanear vale → claim → agregar → cobrar |
| 6 | Pantalla "Entregar" + "Vales pendientes" |
| 7 | Driver de báscula (§8) — pista física paralela |

**Diseño obligatorio:** `designsystem/`. El diálogo actual del escáner es un `AlertDialog` crudo
(`TableOrderScreen.kt:670`) con "Producto no encontrado" — se migra a `AvoqadoDialog` y el mensaje
distingue producto / vale vencido / vale ya entregado.

### 7.2 Dashboard

Alta de `FulfillmentArea` con textos de operador (*"Lo guardamos hasta que pague"*, nunca el
enum), binding terminal→área, simbología, banderas de cancelación, y los códigos en
`plan-catalog.ts`. App-plane, no `/superadmin/`.

---

## 8. Báscula (`SCALE_INTEGRATION`)

Cremería: **Rhino BAR-8RS** (40 kg / 2 g, RS-232, **sin impresora**), hoy contra una PC. El driver
reemplaza la PC por la D3. CEDIS (JUSTA LP7516): fuera de alcance.

**El parser actual NO sirve:** `parseWeightKg()` filtra a dígitos y punto — es de captura manual.
No distingue estable/inestable, neto/bruto, signo, sobrecarga, ni una trama con peso+precio+total,
que es justo lo que emite una báscula de precio computado. Hace falta parser de trama real, con el
manual intacto detrás.

**El baud/trama es el riesgo principal**, no un detalle. → **Pista física desde el día 1**, en
paralelo, sin bloquear nada. **Una sola combinación certificada** (BAR-8RS + adaptador concreto +
D3) antes de prometer chipsets genéricos.

**Degradación:** cable flojo, báscula muda o permiso USB negado → cae a captura manual, **nunca
bloquea la venta**.

---

## 9. Semana 1 — el MVP online

1. `FulfillmentArea` + `Terminal.fulfillmentAreaId` + `OrderItem.fulfillmentAreaId` + migración
2. Partición de dispositivo: endpoint, unicidad, entrega en login (§5.2)
3. Código de vale monótono con espacio propio + resolución del escáner (§5.1)
4. **Cobro atómico** + máquina de estados de la cuenta (§5.4) — cierra el bug de doble cobro
5. `OrderFulfillment` por renglón completo, idempotente (§5.5)
6. Endpoints de §6
7. Android: CODE128, vale, disparo pre-pago, escáner, caja, Entregar, Vales pendientes
8. Config sembrada directo para este venue

**Pista física paralela desde el día 1:** cable, conexión, medición de trama de la BAR-8RS.

---

## 10. Pruebas

**Server**
- **Concurrencia de cobro:** dos `payCashOrder` simultáneos, misma orden, **llaves distintas** →
  un solo `Payment`. Es la prueba del bug de §5.4.
- Claim: área intenta agregar con la cuenta en `CHECKOUT_CLAIMED` → rechazo claro.
- Claim caducado a los 5 min libera la cuenta.
- `fulfill` dos veces → un registro, mismo id. Sobre orden no pagada → rechazo. Sobre `CANCELLED` → rechazo.
- Resolución de código: vivo · pagado · entregado · inexistente. Verificador inválido → rechazo sin tocar DB.
- Partición: dos dispositivos nunca reciben la misma; contador que retrocede → `AREA_CODE_REPLAY`.
- Venue PRO: `AREA_TICKETS` ✅ / `SCALE_INTEGRATION` ❌. Espejo `plan-catalog.ts` ↔ `PREMIUM_ONLY_CODES`.

**Android**
- `EscPosBarcodeTest.kt` — bytes de `GS k` CODE128-C con 10 dígitos; ancho < útil de 58 mm.
- `AreaTicketCodeTest.kt` — verificador mod-10, monotonía, formato `9PPNNNNNNC`.
- Resolución del escáner: `9…` de 10 dígitos → vale; EAN-8 de 8 → producto; **producto cuyo GTIN
  empieza en 9 y mide 10** → producto (el largo manda).
- `WeightMathTest.kt` (existe) — **agregar los dos renglones del ticket del cliente**:
  `0.224 × 164.00 → 36.74` y `0.306 × 233.50 → 71.45`. §4.3 los vuelve criterio de aceptación.
- `SyncOutboxTest.kt` — hoy **no existe ningún test que toque `SyncOutbox`**, pese a ser contrato
  crítico. Aunque el MVP sea online, la deuda se paga aquí.

---

## 11. Supuestos abiertos

1. **Baud rate y trama de la BAR-8RS** — pista física, no se le pregunta al cliente.
2. 🔴 **Ancho de papel por área — decide si el respaldo CODE39 existe.** *(Hallazgo de la
   implementación, 2026-07-28.)*
   - **CODE128-C con 10 dígitos: 90 módulos** (`11 arranque + 5×11 datos + 11 verificador +
     13 paro`), 110 con zonas mudas. A módulo 3 son 330 puntos → **entra holgado en 58 mm** (384).
   - **CODE39 con 10 dígitos: ~211 módulos.** Ni al ancho mínimo que acepta `GS w` (2) entra:
     422 puntos contra 384. **El respaldo CODE39 exige rollo de 80 mm.**
   - Matiz: ESC/POS **no fija** la razón ancho:angosto de CODE39; cada fabricante usa la suya
     (2:1, 2.5:1, 3:1). La implementación asume 3:1 (la peor) en `CODE39_WIDE_RATIO`. Con 2:1 sí
     cabría en 58 mm. **Se decide imprimiendo, no leyendo manuales** — primera prueba contra el
     hardware real.
3. **¿El ticket analizado es de su tienda?** RFC de CDMX vs contacto 667. No cambia decisiones.

**Cerrados:** mismo RFC ✅ · 3 áreas + 1 caja ✅ · pistola lee CODE128 ✅ · CFDI no afecta el diseño ✅

---

## 12. Fase 2 — escrito, con dueño, fuera de la semana 1

| Qué | Por qué no ahora |
|---|---|
| **Offline multi-dispositivo** (2 intents, abrir el reducer más allá de `TABLE_SERVICE`, replicación LAN de cuentas, prueba de `PAID` sin red) | §1.2 — no es una pieza, son cuatro, y el hub actual no transporta cuentas |
| **Entrega parcial** (`FulfillmentEvent` append-only) | §5.5 — el modelo simple no la soporta y a medias es peor |
| **Paridad iOS** | Sus 4 terminales son Android; el cable MFi probablemente no existe. Se anota explícito por la regla del repo |
| **`VARIABLE_WEIGHT_BARCODE`** | §4.1 — este cliente no lo usa |
| **Herramientas MCP** | §5.8 |
| **UI self-service del dashboard** | La config se siembra en la instalación |

---

## 13. Registro de auditorías

**v1 → v2** (`/autoplan`: Codex + revisor independiente, 15 cambios). Los tres estructurales:
fusión no construible en caja · `REJECTED` en vez de `RETRY` era el P1 documentado ·
`printStationId` no es autoridad de área.

**v2 → v3** (Codex `gpt-5.6-sol` `xhigh`, 5.2M tokens):

| # | v2 decía | Realidad | Sec. |
|---|---|---|---|
| 1 | Contador reiniciado por día operativo | El vale de ayer resolvía la cuenta de hoy. **Rompía el primer vale de cada día** | §5.1 |
| 2 | Código de 8 dígitos | Colisiona con EAN-8, y el escáner busca producto primero | §5.1 |
| 3 | Partición "asignada por el server al registrar" | El registro existente es **pasivo** y no devuelve nada al cliente | §5.2 |
| 4 | `FulfillmentArea` arregla la identidad del área | Faltaba **quién** asigna: no había binding terminal→área, ni decisión para las líneas de caja | §5.3 |
| 5 | `OrderFulfillmentLine` = entrega parcial | Sin `PARTIAL`, sin actor/tiempo por línea, sin delta vs acumulado. Modelo sin flujo | §5.5 |
| 6 | `ORDER_NOT_PAID → RETRY` resuelve el P1 | No hay FIFO **global**; un `RETRY` corta el batch y bloquearía 24 h el dispositivo | §1.2 |
| 7 | Faltaban 2 intents | Faltaba un tercero (`ADD_AREA_ITEMS`): `applyAddItems` también exige `TABLE_SERVICE` | §1.2 |
| 8 | Nada sobre estados de la cuenta | Sin máquina de estados el área suma renglones mientras la caja cobra | §5.4 |
| 9 | (no lo mencionaba) | **Bug ya desplegado:** `payCashOrder` permite doble cobro entre dispositivos con llaves distintas | §5.4 |
| 10 | Semana 1 con offline | No cabe. El hub no replica cuentas y el reducer está gateado completo | §1.2, §9 |

**Limitación de esa auditoría:** la instrucción de frontera le impidió leer rutas con `.claude/`,
donde vive `offline-first-y-hub-lan.md`. Codex lo señaló. Dedujo el contrato desde el código y
acertó, pero la próxima corrida debe pasarle ese archivo aparte.

**No cambió en ninguna auditoría:** todo §4 — el trabajo de campo se sostuvo entero.

# Vales independientes por área, cobro consolidado y básculas — spec canónico

**Fecha original:** 2026-07-28
**Última revisión:** 2026-07-29
**Versión:** v7
**Estado:** spec canónico revisado con `gstack plan-ceo-review`, `plan-eng-review` y
`plan-design-review`; v7 cierra pagos parciales, efectivo offline, snapshots, reservas,
materialización, impresión y la unidad monetaria canónica de Avoqado; reemplaza por completo el
modelo de “cuenta compartida” de v3
**Repos involucrados:** `avoqado-server`, `avoqado-android`, `avoqado-ios`,
`avoqado-web-dashboard`
**Modo de revisión de alcance:** `HOLD_SCOPE` — completar y blindar el flujo confirmado, sin
añadir food halls, offline multi-dispositivo ni un motor monetario paralelo

> Este documento es la fuente de verdad funcional y técnica. El handoff de auditoría conserva el
> historial de implementación y sus defectos, pero no define el comportamiento del producto.

---

## 1. Decisión ejecutiva

El negocio no necesita una cuenta que tres áreas editen simultáneamente. Necesita:

1. Un **vale independiente por área**.
2. Una caja que pueda escanear y consolidar varios vales en una sola venta.
3. Una sola venta, un solo total y un solo comprobante para el cliente.
4. Trazabilidad de qué área conserva y entrega cada producto.
5. Verificación visual del comprobante pagado **y** registro digital de la entrega.
6. Integración de básculas en CEDIS y cremería, sin hacer depender los vales del hardware.
7. Activación explícita por venue y terminal, sin modificar el flujo normal de restaurantes o
   tiendas que no usan esta capacidad.

El modelo de v3 —una sola orden compartida entre áreas— queda descartado porque contradice el flujo
confirmado por el cliente y crea concurrencia innecesaria.

El resultado a optimizar es concreto: **un cliente puede comprar en varias áreas y caja puede
cobrar una sola vez, sin que ningún producto se pierda, se cobre dos veces o se entregue dos
veces**.

Vales y básculas tienen decisiones de release independientes:

- `AREA_TICKETS` puede salir con captura manual de peso.
- `SCALE_INTEGRATION` puede certificarse por equipo y host sin bloquear el flujo de vales.
- La falla o desactivación de una báscula nunca desactiva caja, pagos ni entregas.

---

## 2. Evidencia del negocio

Confirmado por WhatsApp con el cliente el 2026-07-28:

- Áreas que emiten vale: **cremería, panadería y cafetería**.
- Cajas de cobro: **una**.
- Cada área genera un ticket por separado.
- Caja escanea esos tickets y forma una sola venta.
- Se cobra un solo monto.
- El área guarda el producto hasta que el cliente regresa con el ticket pagado.
- La tienda también vende artículos empacados con código normal, como papas o refrescos.
- La pistola de caja leyó correctamente el CODE 128 de prueba.
- Báscula de CEDIS: **Justa LP7516**.
- Báscula de cremería/granel: **Rhino**; el modelo y los puertos siguen pendientes de confirmación
  mediante una foto de la placa, porque la marca no identifica por sí sola el protocolo.
- Se desea conectar ambas básculas a Avoqado.

Citas textuales:

> “Si pides jamón, y un café y aparte pan se genera un ticket por separado... Esos tickets se
> escanean en caja y se forma un solo ticket con el total de productos que escogiste y se cobra un
> solo monto.”

> “El área le guarda el producto hasta que regresa con el ticket pagado.”

### 2.1 Interpretación de producto

El cliente describe un patrón conocido de POS:

- **Tickets abiertos que se consolidan:** Square y Oracle Simphony permiten combinar tickets o
  cuentas abiertas antes de cobrar.
- **Producto de peso variable:** GS1 y sistemas retail permiten capturar peso/precio en el área y
  cobrar después en el punto central.

Avoqado combinará ambos patrones sin convertir las áreas en comercios independientes. Las ventas,
impuestos, inventario y pago siguen perteneciendo al mismo venue.

### 2.2 Supuesto de alcance comercial

Las áreas pertenecen al mismo venue y al mismo comercio que recibe el pago. Este diseño no incluye
liquidación de dinero entre concesionarios o terceros. Si en el futuro un food hall tiene merchants
of record distintos, será otro módulo.

---

## 3. Objetivos y no objetivos

### 3.1 Objetivos

- Reproducir el flujo físico actual con menos captura manual y mejor trazabilidad.
- Permitir uno o varios productos por vale.
- Permitir múltiples vales del mismo cliente, incluso de la misma área.
- Mezclar vales y productos retail normales en una sola venta.
- Impedir doble escaneo, doble cobro y doble entrega.
- Conservar atribución por área después de consolidar.
- Permitir que el área confirme entrega desde una lista o escaneando el comprobante pagado.
- Capturar peso manualmente cuando la báscula no esté disponible.
- Integrar CEDIS y cremería con perfiles de báscula separados.
- Mantener intacto el POS normal cuando el módulo esté desactivado.

### 3.2 No objetivos del MVP

- Cuenta compartida editable por varias áreas.
- Operación de vales multi-dispositivo sin conexión.
- Entrega parcial de un mismo vale.
- Liquidación a comercios independientes.
- Codificar los productos, pesos o precios dentro del código del vale.
- Reemplazar todas las reglas existentes de pagos, propinas, reembolsos o facturación.
- Crear un segundo motor de precios, descuentos, impuestos o inventario para los vales.
- Permitir que caja edite peso, cantidad o precio unitario de un vale ya impreso.
- Entrega parcial de renglones dentro de un mismo vale.
- Interfaz especializada para reembolsos parciales por vale; el modelo sí conserva el origen.
- Integración física de báscula por USB en iOS; iOS conserva paridad funcional mediante captura
  manual y los mismos contratos de emisión, caja, pago y entrega.

El MVP de vales es **online**. Si no hay red, el POS normal conserva su comportamiento existente,
pero no se crean, cobran ni entregan vales sin poder consultar al servidor.

### 3.3 Medición de éxito y guardrails de piloto

Antes de activar producción, el piloto debe demostrar:

| Resultado | Umbral de salida |
|---|---|
| Integridad monetaria | 30 recorridos consecutivos con total de orden, pago y recibo idénticos al centavo |
| At-most-once | 0 dobles cobros, importaciones o entregas bajo reintentos y carreras ensayadas |
| Lectura física | Al menos 49 de 50 lecturas correctas por combinación impresora/pistola |
| Recuperación de hardware | 100% de ensayos de desconexión de báscula terminan por captura manual |
| Aislamiento | Suite dorada del POS normal sin cambios con el módulo apagado |
| Operación | Ninguna venta queda sin una acción visible de recuperación para operador o soporte |

Después del lanzamiento se observan tiempos emisión→pago y pago→entrega, abandono, uso manual de
peso y errores por dispositivo. Los objetivos de tiempo se fijan después de obtener una semana de
línea base real; no se inventa un SLA sin medición.

---

## 4. Glosario

- **Área de cumplimiento (`FulfillmentArea`):** cremería, panadería o cafetería.
- **Vale de área (`AreaTicket`):** documento independiente que representa productos retenidos por
  una sola área antes del pago.
- **Sesión de consolidación (`AreaTicketCheckoutSession`):** agrupación temporal de vales que una
  caja está preparando para cobrar.
- **Orden final (`Order`):** venta normal de Avoqado que contiene los renglones importados de los
  vales y cualquier producto agregado directamente en caja.
- **Código de vale:** referencia opaca al vale; no contiene peso ni precio.
- **Código de entrega:** referencia impresa en el comprobante final que permite a cada área
  consultar únicamente lo que le corresponde entregar.

---

## 5. Flujo funcional

### 5.1 Emisión en un área

1. El operador selecciona los productos.
2. Si un producto se vende por peso, captura un peso estable desde la báscula o manualmente.
3. El servidor obtiene catálogo, precio, impuestos y descuentos permitidos; nunca acepta como
   autoridad el total calculado por Android.
4. El servidor crea un vale independiente, asigna su código y congela el snapshot de sus renglones.
5. El área imprime el vale y conserva el producto.
6. Si el cliente pide algo adicional después de imprimir, se emite otro vale; no se modifica el
   anterior.

### 5.2 Consolidación y cobro en caja

1. Caja abre una sesión de consolidación.
2. Escanea uno o varios vales.
3. Cada vale queda reclamado por esa sesión mediante una operación atómica.
4. Repetir el mismo escaneo no duplica productos.
5. Caja puede retirar un vale antes de iniciar el pago.
6. Caja puede agregar productos normales por SKU, GTIN o código de barras.
7. El servidor materializa una sola `Order` con los snapshots de los vales y recalcula en servidor
   los productos normales.
8. Se ejecuta el flujo normal de pago de Avoqado.
9. Cuando la `Order` queda completamente pagada, todos los vales asociados quedan pagados en una
   sola transición idempotente.
10. El comprobante final imprime el área y código del vale de cada línea importada, además de un
    `areaDeliveryCode` en CODE 128 y texto legible. El recibo digital conserva los mismos datos.
11. Las líneas importadas ya fueron preparadas por su área: aparecen en la orden y recibo, pero
    quedan excluidas del alta KDS y de las comandas post-pago. Los productos normales agregados en
    caja conservan exactamente el flujo KDS/comanda existente.

### 5.3 Regreso y entrega

El cliente regresa con el comprobante pagado. Avoqado soporta las dos modalidades solicitadas:

**Modalidad visual**

1. El operador abre “Pendientes de entrega”.
2. Localiza el vale por hora, importe, código o comprobante.
3. Revisa visualmente el papel pagado.
4. Pulsa “Entregar”.

**Modalidad por escaneo**

1. El operador abre “Entregar”.
2. Escanea el código del comprobante pagado.
3. El servidor deriva el área desde la terminal autenticada.
4. La pantalla muestra únicamente los vales de esa área incluidos en la venta.
5. El operador confirma la entrega.

Ambas modalidades llaman a la misma operación idempotente. Un segundo intento muestra quién entregó
y a qué hora, sin crear otra entrega.

### 5.4 Arquitectura del flujo

```text
┌──────────────────┐       emitir        ┌───────────────────────────┐
│ Terminal de área │ ──────────────────► │ AreaTicket + líneas        │
│ + impresora      │ ◄──── código ────── │ snapshot inmutable         │
└──────────────────┘                     └─────────────┬─────────────┘
                                                     │ claim atómico
                                                     ▼
┌──────────────────┐   escanear/agrupar  ┌───────────────────────────┐
│ Caja / carrito   │ ◄─────────────────► │ CheckoutSession           │
│ normal Avoqado   │                     │ claim basket temporal      │
└────────┬─────────┘                     └─────────────┬─────────────┘
         │ materializar                               │
         ▼                                            │ enlaza
┌──────────────────┐       pagar         ┌────────────▼──────────────┐
│ Order normal     │ ──────────────────► │ Payment normal Avoqado    │
│ única autoridad  │ ◄──── resultado ─── │ idempotente/reconciliable │
└────────┬─────────┘                     └───────────────────────────┘
         │ PAID
         ▼
┌──────────────────┐       entregar      ┌───────────────────────────┐
│ Pendientes área  │ ──────────────────► │ Fulfillment idempotente   │
│ lista o escaneo  │                     │ auditado por vale         │
└──────────────────┘                     └───────────────────────────┘
```

`AreaTicketCheckoutSession` es únicamente una canasta de claims. No reemplaza el carrito, la
`Order`, el `Payment`, el cálculo fiscal ni el inventario existentes.

### 5.5 Autoridad monetaria

- El vale congela producto, nombre, peso/cantidad, precio unitario, descuento de línea permitido,
  impuestos y total calculados por el servidor al emitirlo.
- Un cambio posterior de catálogo no cambia el vale ni el papel que ya recibió el cliente.
- Caja no puede editar renglones importados de un vale. Puede retirar el vale completo antes del
  pago y emitir uno nuevo desde el área si había un error.
- Los productos agregados directamente en caja se valorizan con el contrato actual de creación de
  órdenes; el cliente no es autoridad del precio.
- Un descuento de orden autorizado en caja se aplica mediante las reglas existentes de Avoqado. La
  `Order` y el recibo final son la autoridad del importe efectivamente cobrado; el vale conserva su
  importe original como evidencia.
- Si el descuento debe atribuirse por renglón para reembolso o reporte, el servidor distribuye los
  centavos contables de forma determinista usando el mecanismo existente y asigna cualquier residuo
  al último renglón estable. Los importes se siguen almacenando y transportando en pesos `Decimal`
  1:1; nunca se convierten a minor units dentro del dominio ni se recalculan desde Android.
- Propina, cargos de servicio, impuestos incluidos y facturación siguen el flujo normal. No se
  agregan reglas especiales porque un renglón provenga de un vale.

### 5.6 Inventario y producto retenido

El producto queda físicamente apartado al emitir. Para no descontarlo dos veces:

1. Un vale `ISSUED` o `CLAIMED` cuenta como **reserva lógica** sólo para productos con inventario
   rastreado y cuando `inventoryReservationMode = HOLD_AVAILABLE_STOCK`.
2. La emisión resuelve el mismo plan de consumo que usaría la venta normal y persiste una
   `AreaTicketInventoryReservation` por cada componente de inventario. Una receta puede producir
   varias reservas para una sola línea; no se duplica la lógica que expande producto, modificadores
   e insumos.
3. La emisión ordena los `inventoryId`, bloquea sus filas dentro de una transacción PostgreSQL,
   calcula `onHand - reservas ACTIVE de otros vales`, valida disponibilidad y crea vale + líneas +
   reservas atómicamente. Dos emisiones concurrentes no pueden reservar las mismas existencias.
4. Una reserva no crea una venta ni un movimiento de salida. Su cantidad se guarda en la unidad
   base del inventario para evitar mezclar kg, litros y piezas.
5. Al confirmar el pago, el flujo normal de la `Order` descuenta inventario exactamente una vez y
   cambia las reservas `ACTIVE → CONSUMED` dentro de la misma finalización transaccional que enlaza
   `Payment`, `Order`, sesión y vales. Cada movimiento usa `reservationId` como llave idempotente.
6. Si un proveedor ya capturó dinero pero la finalización de base de datos falla, la sesión queda en
   `RECONCILIATION_REQUIRED`; se reintenta la misma finalización y no se cobra otra vez.
7. Cancelar o vencer un vale libera la reserva. Si el producto ya fue preparado y no puede volver a
   stock, la política de merma se ejecuta de forma explícita y auditada.
8. Un venue que no usa inventario conserva `inventoryReservationMode = NONE`; vales no activan
   inventario por sí solos.

La validación previa al pago considera `onHand - reservas ACTIVE de otros vales`, pero no resta de
nuevo las reservas de la propia sesión. No se usa el descuento asíncrono y tolerante a fallos de la
ruta móvil actual para órdenes con vales; esa ruta no puede garantizar la transición atómica.

### 5.7 Pago incierto y recuperación

```text
OPEN ──materializar──► MATERIALIZED ──intento──► PAYMENT_PENDING
                            ▲                         │
                            │ fallo definitivo        ├── éxito parcial ──► PARTIALLY_PAID
                            │ sin pagos previos        │                      │
                            └───────────────────────────┘                      └── siguiente intento
                                                      │
                                                      ├── orden liquidada ──► PAID
                                                      │
                                                      └── resultado incierto
                                                                  ▼
                                                     RECONCILIATION_REQUIRED
```

- Antes de invocar un proveedor externo, se crea un `AreaTicketPaymentAttempt` con llave estable,
  monto, método y secuencia; la sesión apunta a ese intento y entra a `PAYMENT_PENDING`.
- Mientras esté `PAYMENT_PENDING` o `RECONCILIATION_REQUIRED`, el TTL del claim queda suspendido.
  Nunca se liberan vales si el proveedor podría haber capturado dinero.
- Un timeout no habilita “cobrar otra vez” con otra llave. La interfaz muestra “Confirmando pago” y
  permite consultar de nuevo.
- Webhook, polling o reintento con la misma llave finalizan de forma idempotente el intento,
  `Payment`, `Order`, sesión y vales.
- Cada abono de split tender crea un intento distinto. Un abono exitoso con saldo restante mueve la
  sesión a `PARTIALLY_PAID`, conserva todos los claims y permite únicamente el siguiente pago; ya
  no permite retirar vales, editar renglones ni cancelar la venta como si no hubiera dinero.
- Un fallo definitivo vuelve a `MATERIALIZED` si no existe ningún pago exitoso, o a
  `PARTIALLY_PAID` si ya hay abonos. Nunca vuelve a `OPEN` después de materializar.
- Efectivo no necesita conciliación de proveedor, pero sí usa una llave por intento y una
  transacción server-side. Si Android pierde la respuesta, consulta o reintenta la misma llave.
- Android e iOS **no usan la cola offline de efectivo** para una orden que contiene
  `areaTicketLineId`. Sin confirmación server-side no registran venta de cajón, no muestran éxito,
  no imprimen comprobante pagado y no limpian la sesión.
- Un job de conciliación busca sesiones pendientes, consulta las fuentes disponibles y alerta a
  soporte cuando no puede resolverlas automáticamente.

---

## 6. Configurabilidad y aislamiento

### 6.1 Regla de activación

El acceso efectivo requiere:

```text
entitlement del plan
AND configuración explícita del venue
AND capacidad de la terminal autenticada
```

`AREA_TICKETS` no puede activarse únicamente por pertenecer a PRO. La configuración del servidor
es la autoridad.

Entitlements:

```text
AREA_TICKETS
SCALE_INTEGRATION
```

El tier comercial que concede cada entitlement vive en el servicio de planes y puede cambiar sin
publicar otra app. Android sólo consume capacidades efectivas; no codifica `PRO` o `PREMIUM` como
regla del flujo. Tener el entitlement permite configurar el módulo, pero no lo enciende
automáticamente en todos los venues.

### 6.2 Configuración por venue

```text
areaTickets.enabled
areaTickets.allowMixedCart
areaTickets.claimTtlSeconds
areaTickets.checkoutSessionMaxAgeMinutes
areaTickets.ticketExpiryPolicy
areaTickets.ticketExpiryMinutes?
areaTickets.deliveryVerificationMode
areaTickets.codeSymbology
areaTickets.requireManagerForCancel
areaTickets.recordWasteOnCancel
areaTickets.inventoryReservationMode

scaleIntegration.enabled
```

Valores permitidos para `deliveryVerificationMode`:

```text
PAPER_CONFIRMATION   // revisar el papel y confirmar manualmente en la app
RECEIPT_SCAN         // exigir escanear el comprobante pagado
PAPER_OR_SCAN        // permitir cualquiera de las dos rutas
```

Valores permitidos para `ticketExpiryPolicy`:

```text
BUSINESS_DAY_CLOSE   // default; usa el cierre operativo y timezone del venue
FIXED_DURATION       // exige ticketExpiryMinutes
```

Valores recomendados para este cliente:

```text
enabled = true
allowMixedCart = true
claimTtlSeconds = 300
checkoutSessionMaxAgeMinutes = 30
ticketExpiryPolicy = BUSINESS_DAY_CLOSE
deliveryVerificationMode = PAPER_OR_SCAN
codeSymbology = CODE128
inventoryReservationMode = HOLD_AVAILABLE_STOCK si el venue rastrea inventario; NONE en otro caso
scaleIntegration.enabled = false hasta certificar cada perfil físico
```

Desactivar el módulo impide emitir y reclamar nuevos vales, pero nunca oculta vales ya pagados o
producto pendiente de entregar.

### 6.3 Configuración por terminal

```text
terminal.fulfillmentAreaId?       // área emisora o área que entrega
terminal.canIssueAreaTickets
terminal.canCheckoutAreaTickets
terminal.canDeliverAreaTickets
terminal.scaleProfileId?
terminal.defaultWorkspace             STANDARD_POS | AREA_OPERATIONS
```

La caja no necesita `fulfillmentAreaId`. Una terminal de área sí lo necesita para emitir o
entregar. El servidor deriva el área desde la terminal; nunca acepta el área del cuerpo como
autoridad.

Una terminal con ambos espacios puede cambiar desde el selector de modo existente. Una terminal
dedicada al área abre `AREA_OPERATIONS` por defecto. El workspace estándar no cambia por inferencia
del plan ni porque el venue tenga otras terminales con vales.

### 6.4 Aislamiento en Android

- La configuración se guarda por `venueId`, nunca como booleano global.
- Cambiar de venue descarta la configuración activa anterior.
- Si falla la consulta, el módulo de vales falla cerrado, pero el POS normal sigue disponible.
- El escáner normal no cambia de comportamiento en venues sin el módulo.
- No se interceptan códigos de vale antes de comprobar la configuración del venue.
- `AREA_TICKETS` y `SCALE_INTEGRATION` se evalúan y liberan por separado.

---

## 7. Modelo de dominio

Los nombres son conceptuales; Prisma puede adaptarlos a convenciones existentes sin cambiar las
invariantes.

```text
AreaTicket
  id
  venueId
  fulfillmentAreaId
  code
  status                    ISSUED | CLAIMED | PAID | DELIVERED | CANCELLED | EXPIRED
  sourceTerminalId
  issuedByStaffId
  checkoutSessionId?
  orderId?
  currency
  subtotal                  Decimal pesos 1:1
  discountAmount            Decimal pesos 1:1
  taxAmount                 Decimal pesos 1:1
  total                     Decimal pesos 1:1
  pricingSnapshotHash
  printStatus               NOT_PRINTED | PRINTED | PRINT_FAILED
  version
  issuedAt
  printedAt?
  claimedAt?
  claimExpiresAt?
  paidAt?
  cancelledAt?
  expiresAt?

  unique(venueId, code)

AreaTicketLine
  id
  areaTicketId
  productId?
  productNameSnapshot
  skuSnapshot?
  categoryNameSnapshot?
  modifiersSnapshot?
  quantity?
  weightKg?
  unitPrice                 Decimal pesos 1:1
  discountAmount            Decimal pesos 1:1
  appliedDiscountId?
  taxAmount                 Decimal pesos 1:1
  total                     Decimal pesos 1:1
  notes?
  orderItemId?

  unique(orderItemId)
  index(areaTicketId)

AreaTicketInventoryReservation
  id
  venueId
  areaTicketLineId
  inventoryId
  quantityBaseUnits
  status                    ACTIVE | CONSUMED | RELEASED | WASTE
  inventoryMovementId?
  createdAt
  consumedAt?
  releasedAt?

  unique(areaTicketLineId, inventoryId)
  unique(inventoryMovementId)
  index(venueId, inventoryId, status)

AreaTicketCheckoutSession
  id
  venueId
  terminalId
  staffId
  status                    OPEN | MATERIALIZED | PARTIALLY_PAID |
                            PAYMENT_PENDING | RECONCILIATION_REQUIRED |
                            PAID | CANCELLED | EXPIRED
  orderId?
  activePaymentAttemptId?
  lastHeartbeatAt?
  version
  expiresAt
  createdAt

  unique(orderId)
  index(venueId, terminalId, status, createdAt)

AreaTicketPaymentAttempt
  id
  checkoutSessionId
  orderId
  sequence
  idempotencyKey
  amount                    Decimal pesos 1:1
  method
  status                    PREPARED | PENDING | SUCCEEDED | FAILED | UNKNOWN
  paymentId?
  providerReference?
  startedAt
  completedAt?
  lastCheckedAt?

  unique(checkoutSessionId, idempotencyKey)
  unique(paymentId)
  index(checkoutSessionId, sequence)

AreaTicketPrintAttempt
  id
  areaTicketId
  idempotencyKey
  terminalId
  staffId
  status                    PRINTED | FAILED
  kind                      ORIGINAL | REPRINT
  reason?
  errorCode?
  createdAt

  unique(areaTicketId, idempotencyKey)
  index(areaTicketId, createdAt)

AreaTicketFulfillment
  id
  areaTicketId
  orderId
  fulfillmentAreaId
  method                    PAPER_CONFIRMATION | RECEIPT_SCAN
  deliveredByStaffId
  terminalId
  deliveredAt

  unique(areaTicketId)
  index(fulfillmentAreaId, deliveredAt)
```

`OrderItem.fulfillmentAreaId` conserva el origen operativo. `OrderItem.areaTicketLineId` evita que
un mismo renglón se importe dos veces.

`pricingSnapshotHash` es SHA-256 de JSON canónico versión 1 con estos campos ordenados:
`currency`, `productId`, `productNameSnapshot`, `skuSnapshot`, `categoryNameSnapshot`,
`modifiersSnapshot`, `quantity`, `weightKg`, `unitPrice`, `discountAmount`,
`appliedDiscountId`, `taxAmount` y `total`. Los `Decimal` monetarios se serializan como
strings con exactamente dos decimales antes de calcular el hash. Sirve para detectar corrupción o una
importación incompleta; no reemplaza las columnas persistidas ni actúa como secreto.

La `Order` final añade `areaDeliveryCode?`, opaco y único por venue. Se asigna al materializar,
pero sólo se imprime como comprobante de entrega cuando la orden está totalmente pagada.
Reimpresiones conservan el mismo código.

No se reutiliza `Order.areaTicketCode` como cuenta compartida. Si esa columna ya llegó a un entorno
persistente, se retira con una migración aditiva posterior; nunca se edita una migración aplicada.

Índices mínimos adicionales:

```text
AreaTicket(venueId, status, fulfillmentAreaId, issuedAt)
AreaTicket(venueId, checkoutSessionId, status)
AreaTicket(venueId, orderId)
AreaTicketInventoryReservation(venueId, inventoryId, status)
AreaTicketPaymentAttempt(checkoutSessionId, sequence)
AreaTicketFulfillment(fulfillmentAreaId, deliveredAt)
Order unique(venueId, areaDeliveryCode)
```

Las listas operativas usan paginación por cursor estable (`issuedAt`, `id`), no `skip/take` sobre
una cola que cambia mientras el operador la consulta.

---

## 8. Estados e invariantes

### 8.1 Vale

```text
ISSUED ──claim──► CLAIMED ──pago confirmado──► PAID ──entrega──► DELIVERED
   │                 │
   ├──cancelar───────┴───────────────────────────────► CANCELLED
   └──vencer────────────────────────────────────────► EXPIRED

CLAIMED ──cancelar/expirar sesión antes de pago──► ISSUED
```

### 8.2 Reglas no negociables

1. Un vale pertenece exactamente a un venue y una sola área.
2. Un vale impreso no se edita.
3. Un vale puede pertenecer como máximo a una sesión de caja activa.
4. `claim`, liberación y cada transición incrementan `version`.
5. Sólo la sesión que reclamó el vale puede materializarlo en una orden.
6. Cada línea de vale se importa como máximo una vez.
7. Todos los vales se marcan pagados juntos cuando la `Order` alcanza `paymentStatus = PAID`.
8. No se entrega un vale cuya orden no tenga pago confirmado.
9. Un vale se entrega como máximo una vez.
10. El área de emisión y entrega se deriva de la terminal autenticada.
11. Todo endpoint de mutación tiene llave de idempotencia.
12. Dinero viaja en pesos 1:1 como decimal string de dos posiciones; peso viaja como decimal
    string en kg. Sólo el adaptador de un proveedor externo convierte a minor units.
13. La `Order` final conserva el snapshot del vale; nunca repricing silencioso.
14. Una sesión con pago potencialmente capturado no pierde sus claims por TTL.
15. Inventario se descuenta una vez, al completar la venta; la emisión sólo puede reservar.
16. Una sesión queda inmutable al materializar: no agrega ni retira vales o productos después de
    obtener `orderId`.
17. Cada intento de pago tiene su propia llave; split tender nunca reutiliza una llave entre
    abonos.
18. Una venta con vales jamás usa la cola offline de efectivo ni muestra pago exitoso sin
    confirmación del servidor.
19. Un reembolso total vuelve no entregables los vales pendientes aunque conserven su estado
    histórico `PAID`; la autorización de entrega siempre consulta el estado vigente de la orden.

### 8.3 Concurrencia

`claim` debe usar una actualización condicional que incluya `status`, `version`, venue y vigencia.
La actualización debe cambiar `status`, asignar la sesión e incrementar `version` en una sola
sentencia.

Al reclamar o liberar varios vales, el servicio ordena los IDs de forma determinista antes de
bloquear/actualizar. Si usa una actualización por lote, verifica que el número afectado coincida
con el esperado; ante cualquier diferencia revierte todo. Esto evita estados parciales y
deadlocks por orden de locks distinto entre cajas.

La finalización después del pago debe ejecutarse dentro de la misma transacción que confirma la
relación definitiva entre `Payment`, `Order`, sesión y vales. Para pagos con proveedor externo no
se mantiene una transacción abierta durante la llamada de red: se registra el intento, se llama al
proveedor y se finaliza de forma idempotente al recibir éxito. El claim no vence durante ese
intervalo.

La emisión con reservas bloquea inventarios en orden estable. La materialización bloquea vales en
orden estable. La finalización bloquea sesión, intento, orden y vales siempre en ese orden. Esta
jerarquía debe aparecer como comentario ASCII junto al servicio transaccional y en las pruebas de
concurrencia para evitar que futuras rutas introduzcan deadlocks.

Las pruebas unitarias con mocks no bastan. Debe existir una prueba de integración PostgreSQL con
dos clientes Prisma independientes.

---

## 9. Contrato de API

### 9.1 Convenciones

Respuesta exitosa:

```json
{
  "success": true,
  "data": {},
  "error": null
}
```

Respuesta fallida:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "AREA_TICKET_ALREADY_CLAIMED",
    "message": "Este vale está siendo cobrado en otra caja.",
    "retryable": false
  }
}
```

- Mutaciones usan semántica HTTP normal y el cliente interpreta `error.code`.
- Resolución de escaneo usa HTTP 200 con un resultado de dominio.
- Importes: decimal string en pesos 1:1, por ejemplo `"273.98"`.
- Pesos: string decimal, por ejemplo `"0.224"`.
- Fechas: ISO-8601 UTC; la interfaz formatea con el timezone del venue.

Errores de dominio mínimos:

| `error.code` | HTTP | Reintento | Comportamiento de interfaz |
|---|---:|---|---|
| `AREA_TICKETS_DISABLED` | 403 | No | Ocultar acciones de vales; conservar POS normal |
| `AREA_TICKET_NOT_FOUND` | 404 | No | Ofrecer reescanear o captura manual del código |
| `AREA_TICKET_ALREADY_CLAIMED` | 409 | Después | Mostrar caja, hora y vencimiento del claim |
| `AREA_TICKET_NOT_ISSUED` | 409 | No | Mostrar estado pagado/cancelado/vencido/entregado |
| `AREA_TICKET_SNAPSHOT_MISMATCH` | 409 | No | No cobrar datos incompletos; pedir reemisión desde el área |
| `CHECKOUT_SESSION_STALE` | 409 | Sí | Recargar sesión antes de continuar |
| `AREA_TICKET_NOT_PAID` | 409 | No | No entregar |
| `AREA_TICKET_ORDER_REFUNDED` | 409 | No | No entregar; mostrar que la venta fue reembolsada |
| `TERMINAL_AREA_MISMATCH` | 403 | No | Indicar que la terminal no pertenece al área |
| `SCALE_READING_UNSTABLE` | 422 | Sí | Esperar estabilidad o usar captura manual |

Un error no traducido se presenta con folio de soporte y una acción segura; la app nunca muestra
stack traces ni mensajes crudos del proveedor.

Estados operativos que no son errores usan `success: true`:

- Preparar o consultar un pago incierto responde HTTP 202 con
  `data.state = "RECONCILIATION_REQUIRED"` y la misma `paymentAttemptId`.
- Repetir una entrega ya registrada responde HTTP 200 con
  `data.alreadyFulfilled = true`, actor y hora originales.
- Emitir un vale responde HTTP 201 aunque todavía no se haya impreso. La falla física posterior se
  registra mediante `print-attempts`, no se convierte retroactivamente en error de emisión.

### 9.2 Configuración y bootstrap

```text
GET /mobile/venues/:venueId/area-ticket-settings
```

Devuelve configuración efectiva, área y capacidades de la terminal. Android no inventa ni conserva
una configuración de otro venue.

### 9.3 Emitir un vale

```text
POST /mobile/venues/:venueId/area-tickets
```

```json
{
  "idempotencyKey": "uuid",
  "lines": [
    {
      "clientLineId": "uuid",
      "productId": "uuid",
      "quantity": "1",
      "weightKg": "0.224"
    }
  ]
}
```

Para productos conocidos, el servidor obtiene nombre, impuestos y precio del catálogo. Un precio
manual requiere permiso específico y debe quedar auditado.

La respuesta contiene el vale completo bajo `data.ticket`, con `items`, `total`,
`fulfillmentArea`, `code` y timestamps.

### 9.4 Resolver un escaneo

```text
POST /mobile/venues/:venueId/scans/resolve
```

```json
{
  "code": "9470000015",
  "context": "CHECKOUT"
}
```

`context` es `CHECKOUT`, `AREA_DELIVERY` o `PRODUCT_LOOKUP`. Un código no cambia el flujo de otra
pantalla por inferencia global.

Resultados:

```text
PRODUCT
AREA_TICKET
PAID_AREA_TICKET
DELIVERY_RECEIPT
AMBIGUOUS
UNKNOWN
```

Reglas:

- Con el módulo desactivado sólo se resuelven productos normales.
- No se decide únicamente por largo o prefijo.
- Si el código coincide con producto y vale, se devuelve `AMBIGUOUS`; nunca se elige en silencio.
- Reescanear un vale ya agregado devuelve la sesión actual y no duplica líneas.

### 9.5 Sesión de consolidación

```text
POST   /mobile/venues/:venueId/area-ticket-checkouts
POST   /mobile/venues/:venueId/area-ticket-checkouts/:sessionId/tickets
DELETE /mobile/venues/:venueId/area-ticket-checkouts/:sessionId/tickets/:ticketId
POST   /mobile/venues/:venueId/area-ticket-checkouts/:sessionId/materialize-order
POST   /mobile/venues/:venueId/area-ticket-checkouts/:sessionId/heartbeat
POST   /mobile/venues/:venueId/area-ticket-checkouts/:sessionId/prepare-payment
GET    /mobile/venues/:venueId/area-ticket-checkouts/:sessionId/payment-attempts/:attemptId
POST   /mobile/venues/:venueId/area-ticket-checkouts/:sessionId/cancel
GET    /mobile/venues/:venueId/area-ticket-checkouts/:sessionId
```

`materialize-order` recibe los productos normales usando el contrato de creación de orden existente
y crea o devuelve idempotentemente una orden. El servidor vuelve a obtener catálogo y precios para
esas líneas. Las líneas importadas de vales usan sus snapshots e incluyen `areaTicketLineId` y
`fulfillmentAreaId`.

`materialize-order` es el punto de congelamiento. Sólo acepta una sesión `OPEN`; al terminar pasa a
`MATERIALIZED`. Desde ahí no se agregan ni retiran vales o productos. Android e iOS lo llaman al
confirmar el primer pago, después de que el operador terminó de editar el carrito. Si el operador
abandona antes de cualquier abono, `cancel` cancela la orden borrador y libera los vales en una
transacción. Después de un abono, `cancel` se rechaza y se usa reembolso.

El servidor usa `buildOrderItemsData` únicamente para valorar líneas nuevas al emitir vales y
productos normales al materializar. Para las líneas de vale, `materialize-order` usa un mapper de
snapshot persistido que valida `pricingSnapshotHash`, copia importes/peso sin consultar precio
vigente y no ejecuta un segundo motor monetario.

`prepare-payment` es llamado por el servicio de pago, no por una pantalla paralela. Crea o devuelve
un `AreaTicketPaymentAttempt`, congela la sesión y suspende el TTL. Un fallo definitivo vuelve a
`MATERIALIZED` o `PARTIALLY_PAID` según los pagos confirmados; un resultado desconocido la deja en
conciliación. El intento siguiente de split tender siempre usa una llave nueva.

`heartbeat` extiende el lease de una sesión `OPEN` mientras caja está activa, sin superar
`checkoutSessionMaxAgeMinutes`. Si la app desaparece, el lease vence y libera claims; si se
reinicia a tiempo, recupera la sesión y continúa con la misma identidad. En pago o conciliación no
hace falta heartbeat porque el lease está congelado.

El pago se ejecuta mediante el flujo normal de Avoqado. El servicio de pagos debe llamar
internamente a `finalizeAreaTicketCheckout` cuando la orden quede efectivamente pagada.

### 9.6 Registro de impresión

```text
POST /mobile/venues/:venueId/area-tickets/:ticketId/print-attempts
```

Android o iOS lo llama después del resultado físico local con `idempotencyKey`, `status`,
`kind`, `reason?` y `errorCode?`. `ORIGINAL + PRINTED` actualiza `printedAt`; cualquier reimpresión
exige motivo. Repetir la misma llave devuelve el intento original. La incapacidad temporal de
registrar el intento no crea otro vale; el cliente conserva el evento local y lo reintenta.

### 9.7 Entrega

```text
GET  /mobile/venues/:venueId/area-ticket-fulfillment/pending
POST /mobile/venues/:venueId/area-ticket-fulfillment/resolve
POST /mobile/venues/:venueId/area-tickets/:ticketId/fulfill
```

`pending` deriva el área desde la terminal. `resolve` recibe el código del comprobante pagado y
devuelve únicamente los vales de la terminal. `fulfill` no recibe un área autoritativa; sólo método
e idempotency key. `pending` exige cursor y límite acotado, pero no un horizonte temporal fijo.

---

## 10. Impresión y códigos

### 10.1 Vale del área

Debe incluir:

- Nombre del venue y área.
- Fecha y hora local.
- Código legible.
- Uno o varios renglones.
- Peso, precio unitario y total cuando aplique.
- Total del vale.
- Instrucción: “Paga este vale en caja y regresa con tu comprobante”.
- CODE 128.

El código por defecto tiene 10 dígitos numéricos para usar CODE 128-C: nueve dígitos opacos más
verificador GS1 mod-10. No codifica venue, terminal, fecha, producto, peso ni precio. Lo genera el
servidor; Android no acuña códigos durante el MVP online.

El código debe tener dígito verificador para detectar captura manual y unicidad por venue. La
generación debe evitar colisiones en base de datos y el resolutor debe manejar cualquier conflicto
con un producto como `AMBIGUOUS`.

### 10.2 Comprobante final

Debe incluir:

- Una sola venta y un solo total.
- Agrupación visual por cremería, panadería, cafetería y productos de caja.
- Códigos de los vales fuente en texto.
- Código de entrega de la orden en CODE 128.
- Estado pagado.

El comprobante no sustituye el registro del servidor; sólo es la prueba física presentada por el
cliente.

### 10.3 Reimpresión

Reimprimir conserva el mismo código y registra actor, terminal, fecha y motivo. Nunca crea un vale
nuevo ni habilita una segunda entrega.

La emisión y la impresión no son una sola transacción física. Si el servidor creó el vale pero la
impresora falló, Android conserva el `ticketId`, muestra “Vale creado, pendiente de impresión” y
ofrece reintentar con el mismo vale. Nunca vuelve a llamar a emisión para “arreglar” una impresión.
Después de cada resultado local registra un `AreaTicketPrintAttempt`. El estado server-side permite
que otra terminal o soporte sepa si el papel original salió, pero una caída del endpoint de
telemetría no bloquea la reimpresión segura del mismo `ticketId`.

---

## 11. Básculas

La integración de báscula es un módulo independiente de `AREA_TICKETS`.

### 11.1 Equipos en alcance inicial

| Ubicación | Equipo | Uso |
|---|---|---|
| CEDIS | Justa LP7516 | Captura de peso en recepción, despacho/remisión, conteo o ajuste de inventario |
| Cremería/granel | Rhino BAR-8RS candidato, placa por confirmar | Captura de peso para productos del vale |

Ambos están en alcance. Cada uno tendrá configuración y certificación independientes.

Equipos observados en otro cliente el 2026-07-29:

| Equipo | Evidencia | Estrategia inicial |
|---|---|---|
| Kretz Report | Modelo Report con impresor integrado; capacidad exacta NX/LT por confirmar | Integrar primero la etiqueta/código impreso. La gestión por red usa protocolo propietario Kretz y no se habilita sin documentación/certificación del fabricante |
| Torrey PCR con torreta | Familia PCR; falta confirmar en la placa si es PCR-20T o PCR-40T | USB serial, comando ASCII `P`, respuesta de peso terminada en `CR`; exigir lecturas consecutivas iguales porque el manual no expone un indicador de estabilidad |

Estos equipos amplían el catálogo de perfiles compatibles, pero no cambian el orden del piloto:
primero Justa LP7516 y Rhino BAR-8RS como candidato para el equipo del cliente original. La
carcasa observada y la interfaz de la familia coinciden, pero “Rhino” por sí solo no prueba el
modelo: se necesita confirmar la placa y una respuesta real antes de activar producción.

### 11.2 Perfil configurable

```text
ScaleProfile
  id
  venueId
  location
  model
  allowedContexts        AREA_TICKET_LINE | INVENTORY_RECEIPT |
                         INVENTORY_TRANSFER_DISPATCH | STOCK_COUNT | STOCK_ADJUSTMENT
  transport              ANDROID_USB_SERIAL | DESKTOP_BRIDGE | MANUAL
  vendorId?
  productId?
  baudRate?
  dataBits?
  parity?
  stopBits?
  frameParser
  stableIndicator?
  unit
  active
```

El host de CEDIS puede ser un bridge de escritorio si no usa una terminal Android. Esa decisión se
toma durante la prueba física y no altera el contrato de peso.

La Justa del CEDIS no crea vales ni ventas al cliente. Publica una lectura normalizada al flujo de
inventario que esté activo en esa estación. El contexto exacto se selecciona en instalación entre
los flujos de CEDIS ya existentes; no se crea una pantalla de “báscula” aislada sin destino.

Perfiles de protocolo conocidos:

```text
JUSTA_LP7516_ASCII
  transporte: ANDROID_USB_SERIAL o DESKTOP_BRIDGE mediante RS-232
  cable: RS-232 cruzado/null-modem, pines DB9 2 TXD, 3 RXD, 5 GND
  baud: configurable 1200/2400/4800/9600; instalación C19=3 para 9600, 8N1
  modo REQUEST: C18=3, Android envía R
  modo CONTINUOUS: C18=4, Android no hace polling
  trama: ST|US|OL, GS|NT, signo, peso, kg|lb, CR LF

RHINO_BAR8RS_ASCII
  transporte: ANDROID_USB_SERIAL; cable Rhino CAUS-1 o equivalente certificado
  baud: 9600, 8N1, sin control de flujo
  consulta: enviar P
  respuesta candidata: peso ASCII + kg, con o sin CR/LF
  estabilidad: dos lecturas consecutivas equivalentes dentro de 1 segundo

TORREY_PCR_ASCII
  transporte: ANDROID_USB_SERIAL
  baud: 115200 recomendado por Torrey
  consulta: enviar P
  respuesta: peso ASCII + unidad + CR
  estabilidad: dos lecturas consecutivas iguales dentro de 1 segundo
```

El simulador no forma parte de la interfaz operativa, tampoco en una APK de desarrollo instalada
en una terminal. Las simulaciones viven en pruebas automatizadas o dobles de transporte que no
pueden ser accionados por el cajero.

### 11.2.1 Primer flujo CEDIS conectado

La primera integración visible de Justa es **Inventario → Conteos**:

1. La terminal consulta su perfil con el permiso `scale:use`, independiente de vales por área.
2. La tarjeta de báscula sólo aparece al contar una unidad compatible con peso (`kg` o `g`).
3. Una lectura inestable se muestra, pero no se puede aplicar.
4. Una lectura estable requiere confirmación explícita con **Usar este peso**.
5. El campo y teclado manual permanecen disponibles si no hay perfil, red, permiso, USB o lectura.

Recepción de órdenes de compra no usa todavía la lectura automática porque su DTO vigente expresa
cantidades como enteros. Conectar kilogramos decimales a ese contrato produciría pérdida de
precisión; primero se migra el dominio de recepción y después se activa `INVENTORY_RECEIPT`.

### 11.3 Lectura normalizada

```json
{
  "deviceId": "uuid",
  "grossKg": "0.230",
  "tareKg": "0.006",
  "netKg": "0.224",
  "stable": true,
  "observedAt": "2026-07-29T20:00:00Z"
}
```

Sólo un peso neto positivo y estable puede confirmarse automáticamente. El operador siempre puede
volver a captura manual con permiso y registro de auditoría.

### 11.4 Fallos

- Cable desconectado, permiso USB rechazado o trama inválida: mostrar estado y usar captura manual.
- Peso inestable: no agregar automáticamente.
- Cambio de kg/lb: rechazar o convertir únicamente si el perfil lo declara.
- Lectura vieja: invalidar al retirar/cambiar producto.
- La báscula nunca decide producto ni precio; únicamente aporta la medición.

### 11.5 Código de peso variable

GS1 permite etiquetas que contienen producto y peso/precio cuando el cliente lleva el producto
físicamente a caja. No es el flujo actual porque las áreas conservan el producto. Se mantiene como
capacidad futura `VARIABLE_WEIGHT_BARCODE`, separada del código de vale.

---

## 12. Seguridad y permisos

Permisos mínimos:

```text
area-tickets:issue
area-tickets:checkout
area-tickets:cancel
area-tickets:deliver
area-tickets:configure
scale:use
scale:configure
```

Reglas:

- `venueId`, terminal, staff y área se resuelven desde autenticación y registro del dispositivo.
- Una terminal de panadería no puede emitir ni entregar como cremería.
- Una caja no puede reclamar vales de otro venue.
- Los códigos de vale y entrega tienen rate limit.
- Los mensajes de error no exponen productos de otro venue.
- Cancelación, reimpresión, captura manual de peso y entrega quedan auditadas.
- Un plan vencido impide crear nuevos vales, pero permite cerrar, cobrar o entregar compromisos ya
  existentes según política operativa segura.

---

## 13. Cancelaciones, pagos y reembolsos

- Antes de pagar, caja puede retirar un vale de su sesión.
- Cancelar la sesión libera todos los vales mediante CAS.
- Un vale puede cancelarse antes del pago con permiso y motivo.
- Un vale pagado no se cancela: se usa el flujo normal de reembolso.
- Un reembolso total antes de la entrega vuelve el vale no entregable.
- Un reembolso posterior a la entrega conserva el evento de entrega y registra la devolución.
- Reembolso parcial por renglón requiere conservar `areaTicketLineId`; la interfaz especializada
  puede quedar fuera del MVP, pero el modelo no debe perder el origen.

La corrección general contra doble cobro en `payCashOrder` es una mejora transversal de pagos, no
parte del gate de vales. Debe probarse por separado contra PostgreSQL real.

`fulfill` bloquea y relee la `Order` dentro de la misma transacción que crea el evento. Exige
`Order.paymentStatus = PAID` y saldo neto pagado positivo para las líneas del vale. Una orden
`REFUNDED` o un reembolso total asignado a esas líneas responde `AREA_TICKET_ORDER_REFUNDED`.
`pending` aplica la misma regla, por lo que un comprobante reimpreso antes del reembolso no autoriza
una entrega posterior.

---

## 14. Experiencia Android

La interfaz reutiliza `designsystem/`, los patrones obligatorios de iOS y la estructura del checkout
actual. No crea un lenguaje visual propio para vales.

### 14.1 Arquitectura de información

**Workspace de área**

```text
Header: área + estado de red + estado de báscula
Tabs: [Nuevo vale] [Entregas · N]

Nuevo vale:
  productos / búsqueda
  captura de peso
  carrito del vale
  total
  acción primaria: Imprimir vale

Entregas:
  búsqueda o escaneo
  pendientes ordenados por antigüedad
  detalle del vale
  acción primaria: Entregar
```

Una terminal dedicada abre este workspace por configuración. Una terminal mixta lo selecciona desde
el selector de modo; no se inserta una tab permanente en venues normales.

**Caja**

- El checkout y el botón “Cobrar” actuales conservan su jerarquía.
- Un escaneo puede resolver producto o vale.
- Los renglones normales siguen en “En tienda”.
- Cada vale aparece como un grupo compacto con área, código corto, renglones y subtotal.
- El grupo es removible antes del pago, pero sus renglones no son editables.
- El total, descuento, impuestos, propina y pago usan las superficies actuales.
- En pantalla secundaria del cliente se muestran nombres y montos del carrito; nunca claims,
  terminales, permisos ni mensajes internos.

### 14.2 Componentes y reglas visuales

- Full-screen: `AvoqadoFullscreenHeader` con cierre circular, título centrado y acción pill.
- Acción primaria: `PrimaryButton`; altura y espaciado provienen de tokens.
- Confirmaciones y PIN: `AvoqadoDialog`; nunca `AlertDialog` crudo.
- Búsqueda: `SearchPillField`.
- Carga inicial: `AvoqadoLoadingState`.
- Error visible: `AvoqadoErrorToast` o estado inline con acción de recuperación.
- Emisión, reimpresión y entrega exitosas: `AvoqadoSuccessToast`.
- Colores mediante `MaterialTheme.colorScheme`; el estado no depende únicamente del color.
- Área, folio y estado aparecen en texto. Un nombre largo puede truncarse visualmente, pero el
  detalle siempre muestra el valor completo.

### 14.3 Cobertura de estados

| Superficie | Carga | Vacío | Éxito | Error recuperable | Estado bloqueado |
|---|---|---|---|---|---|
| Nuevo vale | Catálogo/settings | Carrito sin productos + CTA contextual | Vale creado e impreso | Impresión falló: reimprimir mismo vale | Sin red: no emitir |
| Peso | Conectando | Sin lectura | Estable y confirmado | Desconectada/inestable: captura manual | Peso inválido: no agregar |
| Escaneo en caja | Resolviendo | Sesión sin vales | Grupo agregado una vez | Desconocido/ambiguo: reescanear o elegir | Claim ajeno/estado final |
| Pago | Preparando | No aplica | Orden y vales pagados | Fallo definitivo: volver a caja | Resultado incierto: conciliar, no recobrar |
| Pendientes | Primera página | Mensaje “No hay entregas pendientes” | Lista por antigüedad | Reintentar carga | Terminal sin área/permisos |
| Entrega | Resolviendo | Código sin productos del área | “¡Entregado!” | Papel visual o reescaneo | No pagado/reembolsado |

### 14.4 Mensajes operativos

| Situación | Mensaje principal | Acción |
|---|---|---|
| Claim en otra caja | “Este vale ya está en otra caja.” | “Actualizar” |
| Vale ya pagado | “Este vale ya fue cobrado.” | “Ver venta” |
| Pago incierto | “Estamos confirmando el cobro. No intentes cobrar otra vez.” | “Consultar estado” |
| Impresión fallida | “El vale se creó, pero no se imprimió.” | “Reimprimir” |
| Báscula desconectada | “Báscula desconectada.” | “Capturar manualmente” |
| Sin pendientes | “No hay productos pendientes de entrega en esta área.” | “Actualizar” |
| Ya entregado | “Entregado a las {hora} por {persona}.” | “Ver detalle” |

### 14.5 Accesibilidad y dispositivo

- Objetivos táctiles de al menos 48 dp.
- `contentDescription` en escaneo, reimpresión, estado de báscula y acciones de icono.
- Orden de foco y TalkBack: título → estado crítico → contenido → acción primaria.
- Contraste AA y soporte dark/light mediante tokens existentes.
- No usar animación como única confirmación; éxito y error quedan también en texto.
- La Sunmi D3 en landscape es el dispositivo piloto. Phone mantiene navegación full-screen y
  carrito existente; no se intenta comprimir el layout tablet 50/50.

### 14.6 Venue sin el módulo

- No aparecen accesos de vales.
- El escáner resuelve únicamente productos.
- No se inicializan repositorios, sesiones ni trabajos de vales.
- No se altera impresión, pagos, comandas, pantalla secundaria ni navegación existente.

---

## 15. Dashboard y operación

La configuración no puede quedar sembrada permanentemente a mano. El dashboard debe permitir:

- Activar/desactivar el módulo por venue.
- Crear y ordenar áreas.
- Seleccionar modo operativo del área.
- Asociar terminales con áreas y capacidades.
- Configurar expiración y política de cancelación.
- Configurar reserva lógica de inventario.
- Configurar verificación de entrega.
- Configurar perfiles de báscula.
- Consultar vales abiertos, reclamados, pagados, entregados, cancelados y vencidos.
- Consultar producto pagado pendiente de entrega por antigüedad.
- Consultar sesiones en `RECONCILIATION_REQUIRED`, pago asociado y última comprobación.
- Liberar claims sólo cuando soporte haya verificado que no existe un pago capturado.

Modos de área:

```text
HOLD_UNTIL_PAID     // prepara y conserva hasta comprobar pago
PREPARE_ON_PAID     // prepara después del pago
IMMEDIATE           // entrega inmediata; normalmente no requiere vale
```

Este cliente usa `HOLD_UNTIL_PAID` en las áreas que emiten vale, salvo que su configuración
operativa indique lo contrario.

---

## 16. Criterios de aceptación

### 16.1 Flujo principal

1. Cremería emite un vale con varios productos pesados.
2. Panadería emite un vale independiente.
3. Cafetería emite un vale independiente.
4. Caja escanea los tres y agrega papas por código normal.
5. La venta muestra cuatro grupos y un total correcto.
6. Se cobra una sola vez.
7. El comprobante final incluye las tres áreas y código de entrega.
8. Cada área ve únicamente sus productos.
9. La entrega puede registrarse desde pendientes o escaneando el comprobante.
10. Repetir la entrega no crea otro evento.

### 16.2 Dinero y concurrencia

- Dos emisiones con la misma idempotency key crean un vale.
- Escanear dos veces agrega una vez.
- Dos cajas reclamando el mismo vale: sólo una gana.
- Cancelar caja libera el vale.
- Heartbeat mantiene una sesión activa; ausencia de heartbeat libera claims `OPEN` vencidos.
- Pago fallido no marca vales como pagados.
- Reintento tras timeout no duplica renglones, orden ni pago.
- Dos intentos simultáneos por el mismo saldo no pueden cobrarlo dos veces.
- Si el venue permite split tender, los `Payment` parciales existentes son válidos, pero ningún
  vale queda `PAID` hasta liquidar la `Order` completa.
- Cada abono de split tender usa un `AreaTicketPaymentAttempt` distinto; fallar el segundo conserva
  el primero y deja la sesión `PARTIALLY_PAID`.
- Después de materializar no se pueden retirar vales ni modificar productos. Cancelar sin abonos
  anula la orden borrador y libera claims; con abonos exige reembolso.
- La transición `CLAIMED → PAID` no puede perder ni añadir renglones.
- Cambiar el precio del catálogo después de emitir no cambia el vale importado.
- Un descuento de orden distribuye hasta el centavo siempre de la misma forma, manteniendo
  almacenamiento `Decimal` en pesos.
- Durante `PAYMENT_PENDING` el claim no vence aunque transcurra su TTL normal.
- Un timeout del proveedor no habilita un segundo cobro; webhook/polling resuelven la misma sesión.
- Cortar red antes de crear la orden o registrar efectivo no encola ni muestra éxito cuando el
  carrito contiene vales. El POS normal conserva su cola offline cuando no contiene vales.

### 16.3 Aislamiento

- Venue desactivado: comportamiento idéntico al POS actual.
- Cambio de venue: no se conserva el gate anterior.
- Fallo al cargar settings: el POS normal funciona y vales permanece oculto.
- Terminal de otra área: acceso rechazado.
- Código que coincide con producto y vale: `AMBIGUOUS`, nunca resolución silenciosa.

### 16.4 Entrega

- Vale no pagado: no entregable.
- Comprobante pagado: resuelve sólo productos del área autenticada.
- Confirmación visual y escaneo producen el mismo resultado.
- Vale ya entregado muestra actor y hora local.
- Pendientes no desaparecen por un límite arbitrario de siete días.
- Dos operadores entregando a la vez crean un solo fulfillment.
- Reembolso total antes de entregar retira el vale de pendientes y bloquea un comprobante pagado
  reimpreso.

### 16.5 Peso

- `0.224 kg × $164.00/kg = $36.74`.
- `0.306 kg × $233.50/kg = $71.45`.
- Peso inestable no se confirma.
- Desconectar cada báscula activa captura manual sin bloquear venta.
- CEDIS y cremería usan perfiles diferentes sin compartir estado.
- Reiniciar o cambiar de venue no reutiliza la última lectura.
- Con reservas activas, otro vale no puede consumir el stock ya apartado.
- Pago descuenta inventario una vez y consume la reserva sin restarla dos veces.
- Cancelación libera reserva; merma configurada crea su movimiento una sola vez.

### 16.6 Hardware

Se prueba físicamente:

- Sunmi D3.
- Impresora real de cada área.
- Pistola de caja.
- CODE 128 impreso en el ancho real.
- Justa LP7516 con su host real de CEDIS.
- Rhino con terminal/host real de cremería.
- Pérdida y recuperación de conexión.

### 16.7 Recuperación y experiencia

- Vale creado + impresora desconectada: reimprimir conserva código y no duplica el vale.
- Cada impresión o reimpresión crea un intento idempotente; repetir su llave no duplica auditoría.
- App reiniciada con sesión `OPEN`: recupera grupos y claims desde servidor.
- App reiniciada con sesión `PAYMENT_PENDING`: abre “Confirmando pago”, no un carrito cobrable.
- App reiniciada con sesión `PARTIALLY_PAID`: muestra saldo y permite sólo el siguiente abono.
- Carga, vacío, error, éxito y bloqueo existen para cada superficie de §14.3.
- TalkBack anuncia área, estado, importe y acción primaria.
- Venue sin módulo mantiene la pantalla secundaria del cliente y checkout actuales.
- Android e iOS consumen los mismos fixtures contractuales y muestran estados equivalentes.

---

## 17. Lo que ya existe y se reutiliza

| Capacidad | Fuente existente | Regla |
|---|---|---|
| Carrito, descuentos, impuestos y total | `CartViewModel` + contrato actual de órdenes | Extender tipos de línea; no duplicar matemática |
| Venta por peso | `CartItem.weightKg`, `addProductByWeight`, `WeightCapturePanel` | Reusar captura y validación; server sigue siendo autoridad |
| Cálculo server de líneas | `buildOrderItemsData` | Valora al emitir y líneas normales; los vales materializados copian el snapshot persistido |
| Orden y pago | `Order`, `OrderItem`, `Payment`, `PaymentFlowViewModel` | Vales materializan una orden normal; no crean un pago paralelo |
| Idempotencia de orden/pago | `externalId` e `idempotencyKey` existentes | Añadir sesión/vale como contexto, no reemplazar garantías |
| Inventario al completar venta | validación y deducción de pago existentes | Adaptar para reservas; no descontar en emisión y pago |
| Scanner | `BarcodeScannerView` + resolución actual de SKU/barcode/GTIN | Añadir resolutor server por contexto; validar pistola HID física |
| Impresión | `PrinterService`, `ESCPOSPrinter`, CODE 128-C y QR | Añadir plantillas de vale/recibo; no reimplementar transporte |
| Pantalla de cliente | `CustomerDisplayState` | Reflejar el carrito final; nunca calcular ni exponer claims |
| Design system | `designsystem/` + patrones iOS | Obligatorio para toda superficie nueva |

La implementación v3 de cuenta compartida (`Order.areaTicketCode`, `addItems`, claims sobre la
orden y fulfill por área enviada por cliente) es evidencia histórica, no una base arquitectónica.
Puede reaprovechar utilidades puras de impresión o formato después de probarlas, pero no sus
invariantes.

Regla de dependencia:

```text
area-ticket service
  ├── llama cálculo existente al emitir y para productos nuevos de caja
  ├── importa snapshots persistidos sin consultar de nuevo el precio
  ├── puede registrar contexto para el servicio de pago existente
  └── NO puede calcular un total alterno, capturar dinero ni descontar inventario por su cuenta
```

---

## 18. Estrategia de implementación

### Fase A — corregir base y contrato

1. Sustituir la cuenta compartida por `AreaTicket` y `AreaTicketLine`.
2. Definir el contrato común de DTOs y fixtures para server/Android.
3. Añadir configuración efectiva por venue y bootstrap por terminal.
4. Eliminar autoridad del cliente sobre `fulfillmentAreaId`.
5. Retirar acuñado local de códigos para el MVP online.
6. Reusar `buildOrderItemsData` al emitir y crear un mapper sin repricing para materializar
   snapshots persistidos.
7. Añadir reservas persistentes con locks de inventario y consumo idempotente.
8. Añadir intentos de pago por abono, estados parcial/incierto y catálogo de errores.
9. Añadir eventos de impresión y bloquear la cola offline de efectivo para ventas con vales.
10. Añadir pruebas PostgreSQL reales de claims, idempotencia, pago e inventario.

### Fase B — flujo operativo

1. Emisión e impresión independiente por área.
2. Sesión de consolidación en caja.
3. Carrito mixto.
4. Materialización de orden y pago.
5. Comprobante final.
6. Pendientes, escaneo y confirmación de entrega.
7. Paridad funcional Android/iOS para emisión, caja, pago, impresión y entrega.
8. Estados de recuperación, reinicio de app y accesibilidad.

### Fase C — hardware en paralelo

1. Identificar cable, adaptador, host y trama de Justa LP7516.
2. Identificar modelo, cable, adaptador y trama exacta de Rhino.
3. Crear perfiles y parsers por equipo.
4. Certificar peso estable, desconexión y fallback manual.

### Fase D — configuración y rollout

1. Aplicar migraciones aditivas con flags apagados.
2. Desplegar server compatible con clientes anteriores.
3. Desplegar Android e iOS con rutas ocultas y POS normal intacto.
4. Dashboard y asignación explícita de terminales.
5. Activar `AREA_TICKETS` sólo en venue piloto, primero con peso manual.
6. Ejecutar los umbrales de §3.3 con personal y hardware reales.
7. Certificar y activar cada `ScaleProfile` de manera independiente.
8. Activación progresiva por venue y terminal.

### Rollback

- Apagar `AREA_TICKETS` detiene emisiones y nuevos claims, pero deja terminar pagos inciertos y
  entregas pendientes.
- Apagar `SCALE_INTEGRATION` cambia a captura manual sin tocar vales.
- No se hace rollback destructivo de schema ni se borran vales, sesiones, órdenes o eventos.
- Si una versión Android falla, el server mantiene el contrato anterior mientras el flag esté
  apagado.
- Una sesión en `PAYMENT_PENDING` se concilia antes de liberar claims o degradar versión.

---

## 19. Migración desde la implementación v3

El código existente fue construido contra una arquitectura diferente y no debe integrarse sin
adaptación.

Cambios obligatorios:

- `Order.areaTicketCode` deja de representar una cuenta compartida.
- `addItems` sobre un vale impreso desaparece.
- `claim` debe operar sobre un vale independiente y una sesión persistente.
- Los DTO Android e iOS que esperan `data`, importes en pesos `Decimal` y líneas deben coincidir
  exactamente con servidor.
- El servidor debe aceptar idempotency keys persistentes.
- El gate global de `SecureStorage` debe reemplazarse por configuración por venue.
- `fulfill` debe derivar el área desde la terminal.
- La lectura de `PAID` y la creación de fulfillment deben ser atómicas.
- El arreglo de timezone debe compilar y tener prueba dirigida.
- Los worktrees de checkout y entrega deben rebasarse contra este contrato; no se mezclan tal cual.

Si la migración v3 sólo existe en desarrollo local, puede reemplazarse antes del despliegue. Si
existe en cualquier entorno compartido o productivo, se corrige con una migración nueva y aditiva.

---

## 20. Observabilidad

Eventos mínimos:

```text
area_ticket.issued
area_ticket.printed
area_ticket.reprinted
area_ticket.claimed
area_ticket.claim_released
area_ticket.order_materialized
area_ticket.payment_pending
area_ticket.reconciliation_required
area_ticket.reconciled
area_ticket.paid
area_ticket.fulfilled
area_ticket.cancelled
area_ticket.expired
area_ticket.print_failed
area_ticket.inventory_reserved
area_ticket.inventory_reservation_released
scale.connected
scale.disconnected
scale.unstable
scale.manual_fallback
```

Métricas:

- Tiempo desde emisión hasta pago.
- Tiempo desde pago hasta entrega.
- Vales abandonados por área.
- Claims expirados.
- Escaneos duplicados o ambiguos.
- Intentos de doble pago y doble entrega bloqueados.
- Uso de peso manual frente a automático.
- Desconexiones por perfil de báscula.
- Sesiones en conciliación y su antigüedad.
- Impresiones fallidas después de emisión.
- Diferencias entre reserva y deducción de inventario.

Alertas operativas:

- Cualquier sesión `RECONCILIATION_REQUIRED` por encima del umbral configurado.
- Vales `PAID` sin entrega por encima de la línea base del área.
- Tasa de impresión o escaneo fallido por dispositivo.
- Intento bloqueado de doble pago o doble entrega.

Logs y eventos incluyen IDs, venue, terminal y estado, pero no datos de tarjeta ni payloads
completos del proveedor.

---

## 21. Riesgos abiertos

La conversación ya contiene las decisiones fundamentales del negocio. No hace falta volver a pedir
que expliquen el flujo completo. Lo pendiente es verificación de instalación y política operativa,
no una redefinición del producto:

1. Cable, adaptador, host y trama exactos de ambas básculas.
2. Ancho y protocolo real de cada impresora.
3. Método más cómodo de entrega para cada área: escaneo externo, cámara o lista.
4. Política de expiración al cierre y tratamiento de producto abandonado.
5. En qué pantalla existente de CEDIS se habilita primero la Justa: recepción, despacho, conteo o
   ajuste.

Se resuelven mediante instalación, prueba física y configuración. No cambian el modelo de dominio.

---

## 22. Referencias externas

- Square Orders API — la orden concentra artículos, totales, pago, inventario y ciclo de vida:
  https://developer.squareup.com/docs/orders-api/what-it-does
- Square Create Order — los reintentos seguros usan una idempotency key estable:
  https://developer.squareup.com/reference/square/orders/create-order
- Square Fulfillments — completar fulfillment exige que el pago de la orden esté completo:
  https://developer.squareup.com/docs/orders-api/fulfillments
- Square, “Split and merge open tickets”:
  https://squareup.com/help/us/en/article/8439-split-and-merge-open-tickets
- Oracle Simphony, “Combining Checks in the Same Revenue Center into One Order”:
  https://docs.oracle.com/en/industries/food-beverage/simphony/sipou/t_checks_combine_same_rvc.htm
- GS1, “2D Barcodes at Retail Point-of-Sale Implementation Guideline” — ejemplo deli con GTIN,
  peso y precio como patrón de producto de peso variable, separado del código opaco del vale:
  https://ref.gs1.org/guidelines/2d-in-retail/
- Oracle Retail Xstore, “Weight in Barcode” — el POS decodifica producto/peso y calcula precio
  cuando la etiqueta realmente lleva esos datos:
  https://docs.oracle.com/en/industries/retail/retail-xstore-point-of-service/21.0/rpxug/entering-items.htm

---

## 23. Decisiones cerradas

| Decisión | Resultado |
|---|---|
| ¿Cuenta compartida o vales independientes? | Vales independientes |
| ¿Un cobro o varios? | Una venta y un total; split tender existente puede crear varios `Payment` |
| ¿Se mezclan productos normales? | Sí |
| ¿Quién conserva producto? | El área hasta pago |
| ¿Entrega visual o digital? | Ambas; terminan en registro digital idempotente |
| ¿Cuántas áreas iniciales? | Cremería, panadería y cafetería |
| ¿Cuántas cajas iniciales? | Una |
| ¿Básculas iniciales? | Justa LP7516 en CEDIS y Rhino en cremería |
| ¿Offline multi-dispositivo? | No en MVP |
| ¿Afecta venues normales? | No; activación explícita por venue y terminal |
| ¿Quién genera el código? | Servidor |
| ¿El código contiene peso/precio? | No |
| ¿Se repricing un vale en caja? | No; conserva snapshot server-side |
| ¿Caja edita líneas del vale? | No; retira el vale completo o el área reemite |
| ¿Cuándo se descuenta inventario? | Una vez al completar la orden; el vale sólo puede reservar |
| ¿Qué ocurre si el pago queda incierto? | Claims congelados y conciliación; nunca recobro libre |
| ¿Cómo se representa split tender? | Un intento idempotente por abono; sesión parcial hasta liquidar |
| ¿Efectivo con vales puede ir a cola offline? | No; requiere confirmación server-side |
| ¿Cuándo se congela la venta? | Al materializar la orden, justo antes del primer intento |
| ¿Cómo se importa el precio del vale? | Copia del snapshot persistido; no consulta el catálogo vigente |
| ¿Cómo evita sobreventa la reserva? | Filas persistentes y locks de inventario en orden estable |
| ¿Cómo se registra impresión local? | `AreaTicketPrintAttempt` idempotente por resultado físico |
| ¿Vales y básculas salen juntos? | No; son gates y decisiones de release independientes |
| ¿Para qué usa peso CEDIS? | Flujos de inventario, no vales ni venta final |
| ¿Android e iOS mantienen paridad? | Sí en el flujo visible y contrato; USB puede ser específico de plataforma |

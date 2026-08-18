# Promociones en el POS — la mitad cliente (Android + iOS)

**Fecha:** 2026-08-15 · **Repos:** avoqado-android · avoqado-ios · avoqado-server (3 huecos de frontera)
**Tier:** PRO, código `PROMOTIONS` · **Origen:** plan 3 de 3 del proyecto Promociones.

> **Este spec NO reemplaza al diseño de producto.** El diseño canónico de promociones es
> `avoqado-server/docs/superpowers/specs/2026-08-12-promociones-en-el-pos-design.md` (v2, auditado
> con Codex xhigh). Aquel definió el modelo, la prorrata al centavo, la semántica fiscal, la
> autoridad offline y el panel. Este spec cubre **sólo lo que falta: el cliente** — y corrige tres
> afirmaciones del v2 que el código ya dejó obsoletas, más el hueco que el v2 nunca vio.

---

## Qué se construye, en una frase

Que el cajero vea las promociones publicadas en la pantalla de cobro, las toque, y los productos
entren al carrito con su precio de promoción — en **venta rápida y en mesas**, con y sin internet,
igual en Android y en iOS.

---

## Lo que el v2 daba por cierto y ya no lo es (verificado en el código, 2026-08-15)

| El v2 decía | La realidad hoy |
| --- | --- |
| "Android e iOS no tienen NINGÚN gate de tier. Hay que construir el mecanismo." | **Ya existe en ambas.** `PlanManager` + `PlanGate` (Android: `core/domain/PlanManager.kt`, `designsystem/components/PlanGate.kt`) leen el tier del payload de settings, con **fail-open** explícito. El gate de esta feature es wiring, no construcción. |
| El panel muestra tres tipos: bundle, combo **y descuento** | Lo construido tiene `Promotion.type = BUNDLE \| COMBO` únicamente. No existe tipo `DISCOUNT`. **Decisión del founder (abajo): el panel v1 no lista descuentos.** |
| Alcance del server: "endpoint móvil" (en singular, ya cubierto) | El endpoint de catálogo existe pero **no alcanza para pintar la tarjeta**, y el camino de **venta rápida no conoce promociones**. Son 3 huecos reales (abajo). |

**El hueco que el v2 nunca vio, y es el que define este plan:** hay **dos** caminos por los que entran
items al POS, y las promociones sólo viven en uno.

| Camino | Cómo entra hoy | ¿Promociones? |
| --- | --- | --- |
| **Mesa** (rondas) | outbox → `ADD_ITEMS` → reducer (`sync.mobile.service.ts`) | ✅ `promotionRef` implementado y probado |
| **Venta rápida** (carrito → cobrar) | `POST /mobile/venues/:venueId/orders` → `createOrderWithItems` | ❌ cero menciones de promoción |

El cliente que originó la feature hace **autoservicio girando la pantalla 180°** — o sea venta
rápida. Sin cerrar ese camino, la feature no le sirve a quien la pidió.

---

## Decisiones del founder (2026-08-15) — no re-litigar

1. **Los dos caminos en la misma entrega:** venta rápida **y** mesas.
2. **Pantalla del cajero en Android e iOS + segunda pantalla en Android.** El iPad no tiene el
   mecanismo de segunda pantalla de las Sunmi (Presentation API); esa mitad es **excepción real de
   plataforma**, declarada aquí y en memoria, no un port pendiente.
3. **El panel lista sólo combos, paquetes y 2x1.** Los descuentos se quedan donde ya viven hoy, en
   la pestaña de atajos: un lugar para cada cosa, cero duplicación, y no obliga a inventar un tipo
   `DISCOUNT` en el server.

Decisiones heredadas del v2 que siguen vigentes: tier PRO `PROMOTIONS` · defaults por pantalla
(**cajero `TAB`, cliente `SIDE_PANEL`**) con caída automática a pestaña bajo ~960dp · sin promos
vigentes se muestran las próximas (4h) apagadas · la venta nunca se rechaza offline · `HIDDEN` es
preferencia de layout, no un apagado silencioso.

---

## Parte 1 — Server: los tres huecos de frontera

### 1.1 Los ajustes de panel no llegan al POS

`VenueSettings.promotionsPanelCashier` / `promotionsPanelCustomer` (`HIDDEN|TAB|SIDE_PANEL`) existen y
el dashboard ya los escribe, pero `GET /api/v1/mobile/venues/:venueId/settings`
(`tpvSettings.mobile.controller.ts`) no los devuelve. Sin esto el POS no sabe dónde pintar.

**Se agrega ADITIVO** al payload existente — nunca se renombra ni se quita nada (regla cross-repo:
versiones viejas de las apps dependen de los campos actuales). Mismo patrón que `plan`, que ya se
agregó así:

```jsonc
{ "terminals": [...], "settings": {...}, "activeTerminalId": "...", "plan": {...},
  "promotions": { "panelCashier": "TAB", "panelCustomer": "SIDE_PANEL" } }   // ← nuevo, opcional
```

🔴 **Ojo con la fuente:** `settings` en ese payload es `TpvSettings` (por **terminal**); los ajustes
de panel son de `VenueSettings` (por **venue**). Van en su propio objeto `promotions`, no mezclados
dentro de `settings`, para que nadie los confunda con configuración de terminal.

**Cliente viejo:** campo ausente → el POS asume los defaults del v2 (`TAB` cajero, `SIDE_PANEL`
cliente). Nunca truena.

### 1.2 El catálogo POS no alcanza para pintar la tarjeta

Hoy `GET /api/v1/mobile/venues/:venueId/promotions` devuelve por opción sólo
`{ id, productId, priceDeltaCents }`. Con eso el POS **no puede** escribir "entran 2, pagas 1" ni
mostrar qué caerá al carrito antes de tocar.

**Se agrega, también aditivo** (`promotionCatalog.service.ts`):

```jsonc
"options": [{ "id": "...", "productId": "...", "priceDeltaCents": 0,
              "quantity": 2, "chargedQuantity": 1,          // ← para el gancho "2x1" y el preview
              "productName": "Cerveza Corona", "productPriceCents": 6500 }]  // ← para la tarjeta
```

`productName`/`productPriceCents` se denormalizan en la respuesta (el POS ya tiene su catálogo, pero
cruzarlo a mano deja tarjetas vacías cuando el producto no está en la página cacheada). El precio
del producto es **sólo para mostrar el estimado**, jamás para calcular lo que se cobra.

### 1.3 La venta rápida no conoce promociones

`createOrderWithItems` **ya recalcula el subtotal desde el catálogo** (`buildOrderItemsData`) e
ignora el `subtotal`/`total` que manda el cliente — o sea el server ya es la autoridad del precio
también aquí. Sólo falta enseñarle a resolver promociones, reusando **el mismo servicio ya probado**
que usa el reducer: `applyPromotionToOrder({ venueId, orderId, promotionId, instanceId, selections, soldAt })`.

**Contrato (espejo exacto del reducer, mismos nombres):**

```jsonc
// POST /api/v1/mobile/venues/:venueId/orders
"items": [
  { "productId": "...", "quantity": 1 },                      // línea normal, como hoy
  { "promotionRef": { "promotionId": "...", "promotionInstanceId": "<uuid local>",
                      "selections": [{ "groupId": "...", "optionId": "..." }] } }  // ← nuevo
]
```

Reglas, idénticas a las del reducer para que los dos caminos no diverjan:

- Los items con `promotionRef` **no pasan** por el alta normal de líneas: sus líneas y su precio los
  crea el motor de promociones.
- El intent **no lleva precios**. La aritmética es del server, siempre.
- La orden y sus promociones se crean en **una sola transacción**: nunca media promoción.
- `promotionInstanceId` es la llave de idempotencia (`@@unique(orderId, instanceId)`): un reintento
  —vivo o del outbox— actualiza, no duplica. Se compone con el `externalId` de orden que ya
  deduplica la creación.
- `soldAt` = el `createdAtLocal` del cliente, **acotado a `[sync − 24h, sync]`** (`clampSoldAt`, ya
  existe). Fuera de la ventana → líneas a precio de lista + marcada para revisión. **Nunca rechazo.**

---

## Parte 2 — El POS (Android e iOS, misma entrega)

### 2.1 Catálogo cacheado

Un `PromotionsRepository` por app, **modelado sobre `UpsellRepository`**, que ya resolvió este
problema exacto: cache-first, sobrevive sin red, y se guarda **por venueId**. Un refresh fallido nunca
borra el catálogo bueno (misma ley que `PrintConfigRepository`: el fail-safe no puede ser quedarse sin
poder vender). El único caso que sí limpia es un 403 con `featureCode` — el candado de plan real.

🔴 **NO copiar `UpsellRepository` tal cual: tiene un hueco, y es el mismo en las dos apps.** Su
`clearMemory()` existe pero **no lo llama nadie** (verificado por grep en ambos repos; en Android
además `PayloadCache.pruneOtherVenues()` también está muerto). El aislamiento al cambiar de venue
depende hoy de que Compose/SwiftUI recreen la pantalla, no de una limpieza explícita — y sin red eso
significa **ver las promociones del local anterior**. El patrón correcto a copiar es el de
`TpvSettingsRepository`, que sí está en la lista explícita de `AuthRepository.switchVenue()`
(Android: `AuthRepository.kt:176-188`; iOS: `AuthRepository.swift:156-192` + el `clearCache()` de
`CheckoutView.switchToTpvVenue`). **El repositorio de promociones va en esa lista, en las dos apps.**

### 2.2 El panel

- Colocación por el ajuste: `TAB` (pestaña junto a Teclado/Productos) · `SIDE_PANEL` (columna 1/4) ·
  `HIDDEN`.
- **Caída automática:** bajo **960** de ancho (dp en Android, pt en iOS) el lateral es inservible y
  el panel cae a pestaña, sin importar el ajuste.

  🔴 **De dónde sale el 960 — corregido el 2026-08-15 al construirlo** (el v2 de este spec decía
  37.5% y era falso): con el panel lateral, la columna de entrada **se queda en 50%** (Android:
  `.weight(0.5f)`), no baja. Una celda de producto necesita ~120 y son 3 columnas ⇒ 360 ⇒ el **piso
  estricto es 720**, no 960. Los 960 son ese piso **más un margen deliberado**, porque a 720 cada
  columna lateral cae a ~180 y una tarjeta con gancho + nombre + imagen ahí se ve apretada.

  En Android el piso se **deriva en código** desde la constante de celda y un test prohíbe que el
  umbral baje de él; **iOS espeja la misma estructura**: piso derivado, margen explícito, y el mismo
  número. Si el QA en device cambia la cifra, cambia en las dos apps el mismo día.
- **Tarjeta:** gancho grande (`2x1`, `$99`), nombre, qué trae, precio o condición. La vigencia se
  escribe **sólo si faltan menos de 60 min** ("hasta las 11:00 pm").
- **Orden:** vigentes primero (`displayOrder`, luego nombre); debajo las que abren dentro de 4h, en
  gris, no tocables ("empieza a las 6:00 pm"). Más allá de 4h no se muestran.

### 2.3 Al tocar

| Tipo | Qué pasa |
| --- | --- |
| Todos los grupos con 1 opción (paquete) | entra directo al carrito |
| Algún grupo con varias (combo) | hoja paso a paso, **sólo** los grupos con más de una opción |

El POS genera el `promotionInstanceId` (UUID local) al tocar, pinta las líneas con el **estimado**
local y manda la promo por el camino que corresponda:

| Contexto | Destino | Estado |
| --- | --- | --- |
| Venta rápida **con red** | queda en el carrito; viaja al cobrar dentro de `POST /orders` | ❌ §1.3 |
| Venta rápida **sin red** | ⚠️ **CORREGIDO tras explorar las dos apps (2026-08-15): no hay UN solo camino, y las apps NO son iguales.** Ver la tabla de abajo. | ⚠️ parcial |
| Mesa (con o sin red) | outbox → `ADD_ITEMS` con `promotionRef` | ✅ ya soportado |

🔴 **El camino sin red, con precisión (verificado en ambos repos el 2026-08-15).** Mi versión anterior
de esta sección decía que toda venta rápida sin red desemboca en `ADD_ITEMS`. **Es falso**: eso sólo
ocurre cuando hay una MESA abierta sin red. Lo que hay realmente:

| Situación | iOS | Android |
| --- | --- | --- |
| Mesa abierta sin red (sesión provisional) | `SyncOutbox` → `OPEN_TABLE`/`ADD_ITEMS`/`PAY_CASH` (`CashPaymentRepository.swift:86`, `TableSession.swift`) | igual (`PaymentFlowViewModel.kt:1194`, `TablesViewModel.kt:217`) |
| **Mostrador sin mesa, con productos, sin red** | **`PendingOrderStore`**: persiste el payload de crear orden tal cual (JSON) y lo reproduce (`PaymentFlowViewModel.swift:1127-1186`) | ✅ **SÍ existe** — corregido el 2026-08-16 al construir la Task 8: `queueCashPayment` → `PaymentSyncService` reenvía el MISMO `POST /orders`. Mi sospecha anterior ("no encontré equivalente") era falsa: miré sólo el camino de mesa provisional |
| Venta sin productos (sólo monto del teclado) | `PendingPaymentStore` + `POST /fast` | — |

**Consecuencias para este plan:**
1. En **iOS** basta con que `promotionRef` viaje dentro del payload que arma `OrderRepository.buildOrderPayload`
   (`Services/OrderRepository.swift:97-162`): ese JSON es exactamente lo que se persiste y se reproduce, así
   que el camino offline de mostrador sale **gratis**. Hoy ese payload filtra los items sin `productId`, así
   que una línea de promoción se caería — **ése es el punto exacto a tocar**.
2. En **Android** la primera tarea del plan es **verificar si una venta de mostrador sin red tiene cola**.
   Si no la tiene, es una diferencia que existe **desde antes de promociones** y que este plan NO resuelve:
   se documenta y se le dice al founder, no se inventa una cola nueva a media entrega.
3. **El mismo `promotionInstanceId`** debe reusarse si una venta empezó online y terminó encolada — es lo
   que evita cobrar el combo dos veces.

**Éxito:** `AvoqadoSuccessToast` ("¡Combo agregado!") — regla del design system, nunca un estado
silencioso.

### 2.4 Quitar

Quitar **cualquier** línea nacida de una promoción quita la promoción completa (con sus descuentos),
por `promotionInstanceId`. En el carrito local es borrado directo; en mesa ya existe el retiro por
instancia en el server. La UI lo dice antes: "Se quitará el combo completo."

### 2.5 Cuando no aplica: nunca un botón muerto

Siempre qué falta y cuánto — copy del v2, en español, espejado literal entre las dos apps:

- "Te faltan $45 para usar esta promoción. Llevas $155 de $200."
- "Agrega una cerveza a la cuenta para aplicar el 2x1."
- "Esta promoción es de 6:00 a 8:00 pm. Faltan 40 minutos."

### 2.6 🔴 Permiso: quién puede aplicar una promoción

**Hueco encontrado en la auditoría del 2026-08-15 — hay que cerrarlo antes de escribir código.**
Aplicar una promoción **regala mercancía**: es la misma clase de acto que aplicar un descuento o una
cortesía. Hoy nada lo gobierna:

- El catálogo (`mobile.routes.ts:1632`) exige `requireVenueMembership` + `checkFeatureAccess('PROMOTIONS')`
  — es decir, membresía y plan. **Ningún permiso de rol.**
- El caso análogo sí lo tiene: `discounts:apply` (`lib/permissions.ts:380`, comentado literal como
  "TPV can apply discounts to orders").

Sin decidirlo, **cualquier mesero puede regalar mercancía por la puerta de las promociones**. No es
hipotético: es exactamente el agujero que ya mordió una vez — una línea con `isCortesia` dentro de
`ADD_ITEMS` evadía `orders:comp` y se arregló en `0778d35d`.

**DECISIÓN DEL FOUNDER (2026-08-15): se reusa `discounts:apply`.** Mismo acto de negocio, mismo
riesgo, cero permisos nuevos que espejar en 4 repos y cero migración de roles — quien hoy puede
aplicar descuentos podrá aplicar promociones. Se exige en los **dos** caminos del server (el reducer
de `ADD_ITEMS` y `createOrderWithItems`), más el gate visual en las dos apps. **No** se crea
`promotions:apply`.

🔴 **El permiso se valida en el server, no sólo en la UI.** Un gate sólo visual lo brinca cualquiera
que arme el request a mano, y offline el intent llega igual.

### 2.7 Tier PRO

`PlanManager.hasFeature("PROMOTIONS")` — código espejado por nombre EXACTO con el backend. Local sin
PRO: el punto de entrada **se ve** con candado (`PlanGate`) y dice qué plan lo prende. Nunca
desaparece en silencio. Se respeta el fail-open que ya es ley en `PlanManager`: plan desconocido o
server viejo → se permite, jamás se brickea un POS a media venta.

Precedencia, en este orden: **tier → ajuste de pantalla → hay algo que mostrar.**

### 2.8 🔴 Devolver y partir una cuenta que trae promociones

**Segundo hueco de la auditoría.** El server ya defiende las dos cosas; lo que falta es que el POS
**no deje al usuario llegar al error**:

| Acción del cajero | Qué hace el server hoy | Qué debe hacer el POS |
| --- | --- | --- |
| Devolver 1 de las 2 cervezas de un 2x1 | **Rechaza**: `assertPromotionLineFullQuantity` lanza si `refundQty !== line.quantity` (`refund.dashboard.service.ts:176`) | Agrupar las líneas de una promoción y ofrecer **"Devolver el combo completo"**. Nunca dejar seleccionar media promoción y descubrirlo al enviar. |
| Partir un cheque dejando medio combo de cada lado | **Rechaza**: la promoción se resuelve todo-o-nada y la instancia sigue a sus líneas | Al dividir, tratar la promoción como **una unidad indivisible**: se va entera a un lado. Avisar en el momento, no al confirmar. |

Es la misma ley de §2.5: nunca un "no se pudo" seco. El POS sabe de antemano cuáles líneas traen
`orderPromotionId` — el server ya las devuelve marcadas.

### 2.9 Segunda pantalla (sólo Android)

Con `panelCustomer != HIDDEN`, la pantalla que mira el cliente pinta las promociones vigentes junto
al desglose del cobro (Presentation API + Compose, como el customer display que ya existe). Es
**vitrina, no control**: se ve, no se toca — la venta la opera el cajero.

🔴 **iOS no lleva esta parte** y no es un TODO pendiente: el iPad no tiene ese mecanismo. Queda
declarado aquí, en el reporte y en memoria.

---

## Parte 3 — Offline

Heredado del v2 y ya implementado del lado server; el cliente sólo tiene que respetarlo. **No hay
camino offline nuevo que inventar**: mesas y venta rápida sin red desembocan las dos en `ADD_ITEMS`
(§2.3), que ya lleva `promotionRef`.

| Situación al sincronizar | Qué pasa |
| --- | --- |
| Vigente en `createdAtLocal` (acotado) | se aplica normal |
| No vigente / reloj movido >24h | líneas a **precio de lista**, marcada para revisión |
| Archivada o cambió de precio mientras no había red | se aplica el **snapshot** y se marca |

En los tres casos la mercancía entra a la cuenta, la cocina la ve y el inventario se descuenta.
**La venta nunca se rechaza.** Y un éxito encolado **jamás** se pinta como pantalla de error (bug
real, `AngelPayPaymentViewModel`).

---

## Invariantes con test (TDD obligatorio: dinero y stock)

Los del v2 que aplican al cliente, más los que nacen de este spec:

1. Una promo tocada en **venta rápida** llega al server como `promotionRef` y **sin precios**.
2. El total cobrado en venta rápida lo calcula el server: si el estimado local difiere, **manda el
   server** (test con catálogo local desactualizado a propósito).
3. Un **2x1 descuenta 2** del inventario, no 1.
4. Un replay del mismo `promotionInstanceId` **no duplica** — incluido el caso cruzado que más duele:
   la venta arranca con red, el `POST /orders` se pierde en el camino y termina reproducida por el
   outbox. Mismo instanceId ⇒ **un solo combo cobrado**.
5. Quitar una línea de la promoción **quita la promoción completa**.
6. Sin red, la promo se encola y al sincronizar tarde **entra igual** (precio de lista + marcada).
7. Cambiar de venue **limpia** el catálogo de promociones cacheado (no se filtra entre negocios).
8. Sin plan PRO el panel **se ve con candado**; con plan desconocido **se permite** (fail-open).
9. Bajo ~960dp el lateral **cae a pestaña** aunque el ajuste diga `SIDE_PANEL`.
10. Campo `promotions` ausente en settings (server viejo) → defaults, sin crash.
11. **Un rol sin el permiso de aplicar no puede meter una promoción** — ni desde la UI, ni armando el
    request a mano, ni por el outbox (§2.6). Test primero: es permiso Y es dinero.
12. **Devolver media promoción no es posible desde el POS**: la UI ofrece el combo completo y el
    server sigue rechazando el parcial (§2.8).

---

## Registro de auditoría

**2026-08-15 — auditoría con Codex: NO SE PUDO CORRER.** `codex exec` (`gpt-5.6-sol`, xhigh) abortó
con `You've hit your usage limit … try again at Aug 19th`. El respaldo (Gemini CLI) también falló:
`IneligibleTierError — this client is no longer supported`. **Queda pendiente re-auditar con Codex a
partir del 19-ago**; este spec NO ha pasado por revisión de un segundo modelo.

**Auditoría propia (misma disciplina: cada afirmación contra el código, con archivo:línea).**
4 hallazgos, los 4 incorporados arriba:

| # | Hallazgo | Estado |
| --- | --- | --- |
| P1 | Nadie define **qué permiso** habilita aplicar una promoción; el análogo `discounts:apply` sí existe. Precedente real: `isCortesia` evadiendo `orders:comp` (`0778d35d`). | → §2.6, decisión al founder |
| P1 | **Reembolso parcial** de una línea de promoción: el server ya lo rechaza (`refund.dashboard.service.ts:176`), pero el POS dejaría al cajero llegar al error. | → §2.8 |
| P2 | El camino offline de venta rápida **vive en archivos distintos** en cada app; citar sólo la ruta de Android mandaría a iOS a buscar donde no está. | → §2.3 |
| P2 | **Partir un cheque** con un combo no estaba mencionado; el server lo resuelve todo-o-nada. | → §2.8 |

Verificado y **sostenido** (no eran falsas): `createOrderWithItems` recalcula el subtotal e ignora los
totales del cliente · la venta rápida sin red desemboca en `ADD_ITEMS` en **ambas** apps ·
`PlanManager`/`PlanGate` existen en ambas con fail-open · el catálogo POS no expone
`quantity`/`chargedQuantity` · `applyPromotionToOrder` valida tenant, orden pagada y cancelada.

---

## Alcance por repo

| Repo | Qué lleva |
| --- | --- |
| **avoqado-server** | los 3 huecos: `promotions` en settings mobile · `quantity`/`chargedQuantity`/nombre/precio en el catálogo POS · `promotionRef` en `createOrderWithItems` (reusando `applyPromotionToOrder`) |
| **avoqado-android** | `PromotionsRepository` · panel (pestaña/lateral/oculto + caída) · hoja de combo · aplicar/quitar en carrito y mesa · estados "no aplica" · gate PRO · **segunda pantalla** |
| **avoqado-ios** | espejo exacto **menos** la segunda pantalla (excepción de plataforma) |
| **MCP** | sin cambios: el ciclo de vida y la edición están excluidos a propósito (documentado en `mcp/tools/promotions.ts`). Se revisa al cerrar. |

**Obligaciones del workspace que van en el MISMO cambio:** presentación de ventas (deck + los dos
one-pagers + **regenerar los 3 PDFs**) al cerrar el plan 3, porque ahí sí hay capacidad nueva
vendible visible al cliente. Sin migraciones de Prisma en este plan → no aplica `schema:map`.

---

## Lo que NO va en la v1 (heredado del v2, sigue vigente)

BOGO mezclando SKUs distintos · elegir más de una opción por grupo · reembolso parcial de un
componente · productos por peso y serializados dentro de una promoción · apilar promociones entre
sí · límites de uso (`maxTotalUses`) · promos en el KDS (cocina ve componentes) · **descuentos en el
panel** (decisión 3 de esta sesión) · **promociones en la segunda pantalla de iOS** (no existe el
mecanismo).

---

## 🔑 Contrato real, tras construir el server (plan 3A ejecutado 2026-08-15) — LEER ANTES DE ESCRIBIR EL POS

Lo que sigue NO estaba en el diseño: salió de construir el server y de tres revisiones. Son las formas de
usar mal este contrato, y todas cuestan dinero o pantallas en blanco.

| Trampa | Qué hacer en el POS |
| --- | --- |
| **Un 400 de `POST /orders` NO significa "no pasó nada".** La orden se crea ANTES de aplicar la promoción; si la promo falla (no publicada, falta elegir una opción), el server anula la orden y libera la llave — pero hubo una orden. | Tratar el 4xx como "no se vendió" y **reintentar con un `externalId` NUEVO**, no reusar el de la venta fallida. |
| **`quantity` no multiplica una promoción.** El server rechaza `quantity ≠ 1` junto a `promotionRef` (400 online, `REJECTED` offline). | **3 combos = 3 `promotionInstanceId` distintos.** Nunca `quantity: 3`. |
| **No rellenar la línea de promoción con ceros.** `unitPrice: 0` junto a `promotionRef` se lee como "línea normal" y recibe 400. | Mandar la línea de promoción **sólo** con `promotionRef`. Nada de `productId`, `name` ni `unitPrice` al lado. |
| **Un item mixto producto+promoción se rechaza** en los dos caminos (era subcobro silencioso). | Un item es producto **o** promoción, nunca los dos. |
| **La respuesta NO trae forma de agrupar las líneas de un combo.** El POS recibe N líneas sueltas con precios prorrateados y sin `orderPromotionId`. | Hoy hay que agrupar por lo que el POS mandó (recuerda el `promotionInstanceId` local y sus líneas). 🔴 **Pedir `orderPromotionId` por línea en la respuesta es un cambio aditivo del server que conviene hacer ANTES de 3B** — sin él no se puede etiquetar "Combo X" en el carrito ni en el recibo. |
| **No existe "quitar promoción" en el POS.** `removePromotionFromOrder` existe en el server pero no está expuesta en ninguna ruta `/mobile` ni hay intent para ella. | En venta rápida se resuelve editando el carrito **antes** de crear la orden. Para mesas habría que exponerla. |
| **El estimado local puede coincidir al centavo** con lo que cobra el server: el catálogo ya expone exactamente los insumos que usa el motor (`pricingMode`, `priceCents`, y por opción `quantity`/`chargedQuantity`/`priceDeltaCents`/`productPriceCents`). | Calcular el estimado con ESOS campos, y aun así **mostrar siempre el total que devuelve el server** al cobrar. |
| **Nombres distintos para el mismo número:** el catálogo publica `productPriceCents`; el motor lo llama `listPriceCents` internamente. | Cosmético, pero no confundirlos al portar. |

⚠️ **Frágil sin cobertura:** el catálogo filtra el producto por `venueId`, pero ningún test lo protege —
si alguien borra ese `select`, los tests siguen verdes y **el panel de combos sale en blanco para todos los
venues** (nombre `''`, precio `0`). Si en 3B ves tarjetas vacías, empieza por ahí.

## Decisiones del founder aún abiertas (no bloquean este plan)

Ninguna de las cuatro impide construir; se resuelven cuando toquen su superficie:

- **(a)** Reembolso por MONTO sobre un pago que es sólo combo: ¿se bloquea o es válvula legítima?
- **(b)** Confirmar que el cargo de servicio % aplica sobre el neto de la promo (server y sesión b7
  coinciden en que sí: es cargo, no descuento).
- **(c)** Reporteo P&L de lo regalado (se lee de `OrderPromotion.discountCents`) — follow-up de
  reportes, no de promociones.
- **(e)** ¿Se permite `FIXED_TOTAL` en $0 (Square sí lo permite) o el editor exige > 0?

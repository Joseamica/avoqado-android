# Caja externa — Fase 1: núcleo de la ruta externa (server + dashboard)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que un área pueda cobrar en una caja ajena — emitir el vale en Avoqado, registrar o asumir
el cobro externo, y entregar — sin que Avoqado invente una `Order` ni un `Payment`, y sin tocar el
flujo nativo.

**Architecture:** Se añade un discriminador `settlementRoute` a `FulfillmentArea` y `AreaTicket`.
La ruta `EXTERNAL` reutiliza la emisión, el snapshot monetario, la impresión y la entrega de v7, y
sustituye el circuito `CheckoutSession → Order → Payment` por una sola entidad,
`AreaTicketExternalSettlement`, que registra un hecho declarado por una persona (o asumido por
política), nunca un cobro observado. El inventario se consume al emitir. Tres CHECK constraints
impiden que una ruta se cuele en el carril de la otra.

**Tech Stack:** TypeScript · Express · Prisma/PostgreSQL · Jest (unit + integration) · React 18 +
Vite + TanStack Query (dashboard).

## Alcance de ESTE plan

**Incluye:** `avoqado-server` y `avoqado-web-dashboard`.

**NO incluye, y son planes aparte:**

| Plan | Qué | Por qué después |
|---|---|---|
| Fase 2 | Android + iOS: emisión externa, cola "Cobros por confirmar", impresión del vale | Necesita la API de esta fase estable. Las dos apps van **juntas** (regla dura del workspace) |
| Fase 3 | Perfil de caja externa, mapeos, encoder de códigos variables, códigos externos en el papel | 🔴 **Depende de R2** — ver abajo |
| Fase 4 | Folios pre-reservados y emisión offline | Necesita §10.3 del spec resuelto |
| Fase 5 | Presentación de partners + rollout del piloto | Al final |

### 🔴 Por qué esta fase es la primera, y no la §A del spec

El spec ordena `A: configuración y códigos` primero. **Este plan lo invierte a propósito.**

El riesgo R2 (§25.1 del spec) pone en duda que exista un código estable por producto en el POS
externo. Si sale por la Lectura B, toda la Fase 3 se rediseña. Todo lo que hay en **este** plan
sobrevive a las dos lecturas: la ruta externa, el registro del cobro, la entrega y el inventario no
dependen de cómo se vea el código de barras.

Y al terminar esta fase el cliente **ya tiene un producto usable**: un vale con nombres, importes y
total de referencia que el cajero captura a mano en su POS. Es exactamente el fallback de la Lectura
B, y bajo la Lectura A es el paso previo natural a que se escanee.

**Antes de empezar la Fase 3, alguien tiene que mandar las dos preguntas de §25.1 al cliente.** No
bloquea este plan.

## Global Constraints

Valores exactos, copiados del spec y de las reglas de los repos. **Aplican a todas las tareas.**

- **Dinero en PESOS 1:1**, `Prisma.Decimal`, `@db.Decimal(12,2)`. Nunca centavos, nunca float. El
  `* 100` solo existe en el borde de un proveedor externo, y aquí no hay ninguno.
- **Fechas venue-local** vía `fromZonedTime(\`${d}T00:00:00.000\`, tz)` o `venueStartOfDay`. Nunca
  `new Date('YYYY-MM-DD')` pelón.
- **`authContext`, no `req.user`**: `const { venueId, userId } = (req as any).authContext`.
- **Todas las queries filtran por `venueId`.** Sin excepción.
- **Toda mutación lleva `idempotencyKey`** persistida, no en memoria.
- **Toda mutación audit-worthy escribe `ActivityLog`** (`action`, `entity`, `entityId`, `staffId`,
  `venueId`, `data`) con `void logAction(...)`, fuera de la transacción.
- **Mensajes de Zod en español.** El middleware los muestra crudos al usuario.
- **Migraciones:** `npx prisma migrate dev --name <desc>`. **Nunca** `prisma db push`. **Nunca**
  editar una migración ya aplicada.
- **Todo `schema.prisma` termina con `npm run schema:map`**, y `docs/SCHEMA_MAP.md` va en el MISMO
  commit. Modelo nuevo ⇒ primero su entrada en `scripts/generate-schema-map.ts` → `MODEL_TO_DOMAIN`.
- **Después de editar TS:** `npm run format && npm run lint:fix`.
- **Nunca `git add -A`.** Solo rutas explícitas (hay otras sesiones de IA en este workspace).
- **Nada de nombres de cliente en condicionales.** `if (profile.system === 'MYBUSINESS')` está
  prohibido.
- **La palabra "pagado" no aparece nunca en la ruta externa.** Es "cobro confirmado" o "cobro
  asumido".
- Correr un test suelto: `npx jest --selectProjects unit --testPathPattern "<nombre>"`. El argumento
  posicional **no filtra** en este repo.
- Los tests de integración exigen `TEST_DATABASE_URL` apuntando a `av-db-25-test`. Sin esa variable
  las ~29 suites de integración truenan — es un guardrail, no un bug.

## File Structure

### `avoqado-server`

| Archivo | Responsabilidad |
|---|---|
| `prisma/schema.prisma` | Enums, campos aditivos, `AreaTicketExternalSettlement`, `AreaTicketExternalIncident` |
| `prisma/migrations/<ts>_area_ticket_external_route/migration.sql` | Migración aditiva |
| `prisma/migrations/<ts>_area_ticket_external_constraints/migration.sql` | Los tres CHECK |
| `scripts/generate-schema-map.ts` | Clasificar los modelos nuevos |
| `src/services/mobile/areaTicketExternal.mobile.service.ts` | **NUEVO** — settlement: handoff, confirm, not-charged, cola. Vive aparte de `areaTicketV7` (que ya son 1,800 líneas) |
| `src/services/mobile/areaTicketV7.mobile.service.ts` | Rama externa en `issueAreaTicket`, predicado externo en `fulfill`/`pending`, reversa al cancelar |
| `src/controllers/mobile/areaTicketExternal.mobile.controller.ts` | **NUEVO** — controllers finos |
| `src/routes/mobile.routes.ts` | Montar las rutas nuevas |
| `src/schemas/mobile/areaTicketExternal.schema.ts` | **NUEVO** — Zod en español |
| `src/lib/permissions.ts` | `area-tickets:confirm-external` |
| `src/jobs/areaTicketExternalReconciliation.job.ts` | **NUEVO** — abre `UNCONFIRMED_CHARGE` |
| `src/mcp/tools/areaTickets.ts` | Exponer ruta, cobro externo e incidencias |

### `avoqado-web-dashboard`

| Archivo | Responsabilidad |
|---|---|
| `src/pages/Settings/AreaTickets.tsx` | Sección de ruta de cobro por área |
| `src/pages/Settings/components/ExternalRouteAreaCard.tsx` | **NUEVO** — el switch y sus políticas |
| `src/pages/AreaTickets/ExternalSettlements.tsx` | **NUEVO** — cobros por confirmar + incidencias |
| `src/services/areaTickets.service.ts` | Métodos nuevos de API |

---

## Task 1: Schema — ruta externa y settlement

**Files:**
- Modify: `avoqado-server/prisma/schema.prisma`
- Modify: `avoqado-server/scripts/generate-schema-map.ts`
- Create: `avoqado-server/prisma/migrations/<ts>_area_ticket_external_route/migration.sql` (generada)
- Test: `avoqado-server/tests/unit/schemas/areaTicketExternalSchema.test.ts`

**Interfaces:**
- Produces: enums `AreaSettlementRoute`, `ExternalConfirmationMode`, `ExternalOfflinePolicy`,
  `ExternalDeliveryTracking`, `AreaTicketExternalSettlementStatus`, `AreaTicketExternalHandoffState`,
  `AreaTicketExternalIncidentKind`, `AreaTicketExternalIncidentStatus`; modelos
  `AreaTicketExternalSettlement`, `AreaTicketExternalIncident`.

- [ ] **Step 1: Escribir el test que falla**

`tests/unit/schemas/areaTicketExternalSchema.test.ts`:

```typescript
import { AreaSettlementRoute, AreaTicketExternalSettlementStatus } from '@prisma/client'

describe('Schema — ruta externa', () => {
  it('AVOQADO es el default de la ruta, para que ningún venue existente cambie de comportamiento', () => {
    expect(AreaSettlementRoute.AVOQADO).toBe('AVOQADO')
    expect(AreaSettlementRoute.EXTERNAL).toBe('EXTERNAL')
  })

  it('el cobro externo distingue asumido de confirmado — nunca son lo mismo', () => {
    expect(Object.keys(AreaTicketExternalSettlementStatus).sort()).toEqual(
      ['ASSUMED', 'CONFIRMED', 'DISCREPANCY', 'NOT_CHARGED', 'PENDING'].sort(),
    )
  })
})
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd avoqado-server && npx jest --selectProjects unit --testPathPattern "areaTicketExternalSchema"`
Expected: FAIL — `AreaSettlementRoute` no existe en `@prisma/client`.

- [ ] **Step 3: Añadir enums y modelos al schema**

En `prisma/schema.prisma`, junto al bloque `VALES POR ÁREA v7` (~línea 13450):

```prisma
enum AreaSettlementRoute {
  /// Flujo v7: caja Avoqado, Order y Payment. Default de todo lo existente.
  AVOQADO
  /// Otro POS cobra en su caja. Avoqado NO crea Order ni Payment.
  EXTERNAL
}

enum ExternalConfirmationMode {
  /// Una persona con permiso confirma que la caja externa cobró.
  MANUAL
  /// Se da por ocurrido al imprimir. NO es "pagado": es "asumido".
  ASSUME_ON_PRINT
}

enum ExternalOfflinePolicy {
  ALLOW
  BLOCK
}

enum ExternalDeliveryTracking {
  /// Se registra la entrega y hay cola de pendientes.
  TRACKED
  /// El área entrega mirando el papel. Sin cola. Se pierde trazabilidad, nada más.
  UNTRACKED
}

enum AreaTicketExternalSettlementStatus {
  PENDING
  ASSUMED
  CONFIRMED
  DISCREPANCY
  NOT_CHARGED
}

enum AreaTicketExternalHandoffState {
  PENDING
  HANDED_OFF
  RETURNED
}

enum AreaTicketExternalIncidentKind {
  UNCONFIRMED_CHARGE
  AMOUNT_VARIANCE
  NEGATIVE_STOCK
  CODE_MISMATCH
  REPRINT_RISK
}

enum AreaTicketExternalIncidentStatus {
  OPEN
  RESOLVED
  DISMISSED
}

/// El cobro que ocurrió en la caja de OTRO sistema. No es un Payment y nunca debe
/// convertirse en uno: Avoqado no vio ese dinero. `referenceAmount` es lo que Avoqado
/// calculó; `externalAmount` es lo que alguien dice que la otra caja cobró.
model AreaTicketExternalSettlement {
  id      String @id @default(cuid())
  venueId String
  venue   Venue  @relation(fields: [venueId], references: [id], onDelete: Restrict)

  areaTicketId String     @unique
  areaTicket   AreaTicket @relation(fields: [areaTicketId], references: [id], onDelete: Cascade)

  status       AreaTicketExternalSettlementStatus @default(PENDING)
  handoffState AreaTicketExternalHandoffState     @default(PENDING)
  /// Qué política estaba vigente al emitir. Se congela: cambiarla después no reescribe
  /// la historia de un vale que ya se cobró bajo otra regla.
  confirmationMode ExternalConfirmationMode

  referenceAmount Decimal  @db.Decimal(12, 2)
  externalAmount  Decimal? @db.Decimal(12, 2)
  externalReference String?

  idempotencyKey     String    @db.VarChar(64)
  confirmedByStaffId String?
  confirmedByStaff   Staff?    @relation("AreaTicketExternalSettlementStaff", fields: [confirmedByStaffId], references: [id], onDelete: SetNull)
  confirmedAt        DateTime?
  terminalId         String?
  terminal           Terminal? @relation("AreaTicketExternalSettlementTerminal", fields: [terminalId], references: [id], onDelete: SetNull)
  notes              String?

  createdAt DateTime @default(now())
  updatedAt DateTime @updatedAt

  @@unique([areaTicketId, idempotencyKey])
  @@index([venueId, status, createdAt])
}

/// Cola de trabajo de oficina. Deliberadamente NO es un estado del vale: una incidencia
/// abierta jamás bloquea el piso.
model AreaTicketExternalIncident {
  id      String @id @default(cuid())
  venueId String
  venue   Venue  @relation(fields: [venueId], references: [id], onDelete: Cascade)

  areaTicketId String?
  areaTicket   AreaTicket? @relation(fields: [areaTicketId], references: [id], onDelete: Cascade)

  kind   AreaTicketExternalIncidentKind
  status AreaTicketExternalIncidentStatus @default(OPEN)
  detail Json

  openedAt          DateTime  @default(now())
  resolvedAt        DateTime?
  resolvedByStaffId String?
  resolvedByStaff   Staff?    @relation("AreaTicketExternalIncidentStaff", fields: [resolvedByStaffId], references: [id], onDelete: SetNull)
  resolution        String?

  /// Una incidencia VIVA por tipo y por vale. Sin esto el job de conciliación abre una
  /// fila nueva cada vez que corre y la cola se vuelve ilegible en un día.
  @@unique([areaTicketId, kind])
  @@index([venueId, status, kind, openedAt])
}
```

Y los campos aditivos:

```prisma
// En model FulfillmentArea:
  settlementRoute          AreaSettlementRoute      @default(AVOQADO)
  externalConfirmationMode ExternalConfirmationMode @default(MANUAL)
  externalOfflinePolicy    ExternalOfflinePolicy    @default(BLOCK)
  externalDeliveryTracking ExternalDeliveryTracking @default(TRACKED)

// En model AreaTicket:
  settlementRoute    AreaSettlementRoute            @default(AVOQADO)
  externalSettlement AreaTicketExternalSettlement?
  externalIncidents  AreaTicketExternalIncident[]

// En model AreaTicketInventoryReservation:
  /// Ancla de la REVERSA. Sin una segunda columna única, un reintento de cancelación
  /// devuelve el producto a stock dos veces.
  reversalMovementId String? @unique

// En model Venue: las relaciones inversas
  areaTicketExternalSettlements AreaTicketExternalSettlement[]
  areaTicketExternalIncidents   AreaTicketExternalIncident[]

// En model Staff:
  areaTicketExternalSettlements AreaTicketExternalSettlement[] @relation("AreaTicketExternalSettlementStaff")
  areaTicketExternalIncidents   AreaTicketExternalIncident[]   @relation("AreaTicketExternalIncidentStaff")

// En model Terminal:
  areaTicketExternalSettlements AreaTicketExternalSettlement[] @relation("AreaTicketExternalSettlementTerminal")
```

- [ ] **Step 4: Clasificar los modelos nuevos en el mapa de schema**

En `scripts/generate-schema-map.ts`, dentro de `MODEL_TO_DOMAIN`, junto a los `AreaTicket*` que ya
están:

```typescript
  AreaTicketExternalSettlement: 'Orders, KDS & Cash',
  AreaTicketExternalIncident: 'Orders, KDS & Cash',
```

Si los `AreaTicket*` existentes están en otro dominio, usa **el mismo** — agrúpalos con sus hermanos.
Verifícalo con: `grep -n "AreaTicket" scripts/generate-schema-map.ts`

- [ ] **Step 5: Generar la migración y el mapa**

```bash
cd avoqado-server
npx prisma migrate dev --name area_ticket_external_route
npm run schema:map
```

Abre la migración generada y **verifica que es puramente aditiva**: solo `CREATE TYPE`, `CREATE
TABLE`, y `ALTER TABLE … ADD COLUMN` con `DEFAULT`. Si aparece un `DROP` o un `ALTER COLUMN … SET NOT
NULL` sobre una tabla con datos, algo se hizo mal — para y revisa.

- [ ] **Step 6: Correr el test y verificar que pasa**

Run: `npx jest --selectProjects unit --testPathPattern "areaTicketExternalSchema"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
cd avoqado-server
git add prisma/schema.prisma prisma/migrations scripts/generate-schema-map.ts docs/SCHEMA_MAP.md \
        tests/unit/schemas/areaTicketExternalSchema.test.ts
git commit -m "feat(area-tickets): schema de ruta de cobro externa

Enums de ruta/confirmación/offline/entrega, AreaTicketExternalSettlement y
AreaTicketExternalIncident. Todo aditivo y con default AVOQADO: ningún venue
existente cambia de comportamiento.

reversalMovementId en la reserva de inventario es el ancla de la reversa —
sin ella un reintento de cancelación devuelve stock dos veces."
```

---

## Task 2: Los tres CHECK que sostienen las invariantes

**Files:**
- Create: `avoqado-server/prisma/migrations/<ts>_area_ticket_external_constraints/migration.sql` (a mano)
- Test: `avoqado-server/tests/integration/area-tickets/area-ticket-external-constraints.test.ts`

**Interfaces:**
- Consumes: los modelos de Task 1.
- Produces: garantías a nivel de base de datos. Ninguna interfaz de código.

**Por qué a mano:** Prisma no modela CHECK constraints. La migración se escribe con SQL directo, y
por eso mismo es la única defensa que sobrevive a un script de datos que no pase por el servicio.

- [ ] **Step 1: Escribir el test de integración que falla**

`tests/integration/area-tickets/area-ticket-external-constraints.test.ts` — sigue el patrón de
`area-ticket-v7-flow.test.ts` para el `beforeAll` (crear organization, venue, área, terminal, staff,
producto). Los tres casos:

```typescript
describe('CHECK constraints de la ruta externa', () => {
  it('rechaza un vale EXTERNAL que traiga checkoutSessionId — no puede entrar al carril de caja Avoqado', async () => {
    await expect(
      prisma.areaTicket.create({
        data: {
          ...baseTicketData(),
          settlementRoute: 'EXTERNAL',
          checkoutSessionId: sessionId,
        },
      }),
    ).rejects.toThrow(/area_ticket_external_no_avoqado_circuit/)
  })

  it('rechaza un vale EXTERNAL en estado CLAIMED — en la ruta externa no hay claim', async () => {
    await expect(
      prisma.areaTicket.create({
        data: { ...baseTicketData(), settlementRoute: 'EXTERNAL', status: 'CLAIMED' },
      }),
    ).rejects.toThrow(/area_ticket_external_no_avoqado_circuit/)
  })

  it('rechaza una entrega de ruta AVOQADO sin orderId — la invariante nativa NO se debilitó', async () => {
    await expect(
      prisma.areaTicketFulfillment.create({
        data: { ...baseFulfillmentData(), settlementRoute: 'AVOQADO', orderId: null },
      }),
    ).rejects.toThrow(/atf_order_required_for_avoqado_route/)
  })

  it('SÍ acepta una entrega de ruta EXTERNAL sin orderId', async () => {
    const created = await prisma.areaTicketFulfillment.create({
      data: { ...baseFulfillmentData(), settlementRoute: 'EXTERNAL', orderId: null },
    })
    expect(created.orderId).toBeNull()
  })
})
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `TEST_DATABASE_URL=postgresql://…/av-db-25-test npx jest --selectProjects integration --testPathPattern "area-ticket-external-constraints"`
Expected: FAIL — los `create` pasan sin error porque los CHECK todavía no existen.

- [ ] **Step 3: Hacer `orderId` nullable y añadir el discriminador a la entrega**

En `prisma/schema.prisma`, `model AreaTicketFulfillment`:

```prisma
  orderId String?
  order   Order?  @relation(fields: [orderId], references: [id], onDelete: Restrict)
  settlementRoute AreaSettlementRoute @default(AVOQADO)
```

```bash
npx prisma migrate dev --name area_ticket_fulfillment_external_route
npm run schema:map
```

- [ ] **Step 4: Escribir la migración de constraints a mano**

```bash
mkdir -p prisma/migrations/$(date +%Y%m%d%H%M%S)_area_ticket_external_constraints
```

`migration.sql`:

```sql
BEGIN;

-- 1. La invariante NATIVA no se debilita: pasa de estar sostenida por el tipo de la
--    columna a estar sostenida por una restricción que dice lo que quiere decir.
ALTER TABLE "AreaTicketFulfillment"
  ADD CONSTRAINT "atf_order_required_for_avoqado_route"
  CHECK ("settlementRoute" <> 'AVOQADO' OR "orderId" IS NOT NULL);

-- 2. Un vale externo NUNCA entra al circuito de caja Avoqado.
ALTER TABLE "AreaTicket"
  ADD CONSTRAINT "area_ticket_external_no_avoqado_circuit"
  CHECK (
    "settlementRoute" <> 'EXTERNAL'
    OR (
      "checkoutSessionId" IS NULL
      AND "orderId" IS NULL
      AND "status" IN ('ISSUED', 'CANCELLED', 'EXPIRED')
    )
  );

COMMIT;
```

**Sobre `lock_timeout`:** el repo ya enruta todo deploy por
`scripts/prisma-migrate-deploy-bounded.js`, que aplica `lock_timeout=5s` en la conexión (ver
`tests/unit/architecture/areaTicketMigrationLockSafety.test.ts`). No añadas un `SET` propio: el
wrapper ya lo hace y el test de arquitectura verifica que sea la única vía.

Un `ADD CONSTRAINT … CHECK` valida toda la tabla y toma `ACCESS EXCLUSIVE`. En `AreaTicket`, que hoy
es chica, es instantáneo. **Antes de aplicar en producción**, verifica el tamaño:

```sql
SELECT count(*) FROM "AreaTicket";
```

Si superara ~500k filas, se parte en `ADD CONSTRAINT … NOT VALID` + `VALIDATE CONSTRAINT`, que es el
patrón que ya usa `20260808121126_add_catalog_publication_outbox_hot_parent_fks_not_valid`.

- [ ] **Step 5: Aplicar y correr el test**

```bash
npx prisma migrate dev
TEST_DATABASE_URL=… npx jest --selectProjects integration --testPathPattern "area-ticket-external-constraints"
```
Expected: PASS los cuatro casos.

- [ ] **Step 6: Verificar que no rompiste la ruta nativa**

Run: `TEST_DATABASE_URL=… npx jest --selectProjects integration --testPathPattern "area-ticket-v7"`
Expected: PASS. Si el flujo v7 truena aquí, el CHECK está mal escrito — la ruta nativa no debe
notar ningún cambio.

- [ ] **Step 7: Commit**

```bash
git add prisma/schema.prisma prisma/migrations docs/SCHEMA_MAP.md \
        tests/integration/area-tickets/area-ticket-external-constraints.test.ts
git commit -m "feat(area-tickets): CHECK constraints de la ruta externa

AreaTicketFulfillment.orderId pasa a nullable, pero con un CHECK que lo sigue
exigiendo cuando la ruta es AVOQADO: la invariante nativa no se debilita, se
vuelve explícita en la base.

Segundo CHECK: un vale EXTERNAL no puede traer checkoutSession, orderId, ni
estar CLAIMED/PAID/DELIVERED. Es la garantía que sobrevive a un script de
datos que no pase por el servicio."
```

---

## Task 3: Permiso `area-tickets:confirm-external`

**Files:**
- Modify: `avoqado-server/src/lib/permissions.ts`
- Test: `avoqado-server/tests/unit/lib/areaTicketExternalPermissions.test.ts`

**Interfaces:**
- Produces: la cadena `'area-tickets:confirm-external'`, usable en `checkPermission()` y en
  `<PermissionGate>` del dashboard.

**Por qué es su propia tarea:** un permiso mal registrado no falla — queda *phantom*, y el endpoint
nace muerto para todos menos SUPERADMIN. El audit del repo lo detecta, y por eso se cierra antes de
que exista el endpoint que lo usa.

- [ ] **Step 1: Escribir el test que falla**

```typescript
import { DEFAULT_PERMISSIONS, INDIVIDUAL_PERMISSIONS_BY_RESOURCE } from '@/lib/permissions'

describe('Permiso de confirmación de cobro externo', () => {
  const PERM = 'area-tickets:confirm-external'

  it('está en el catálogo, para que se pueda asignar desde el editor de roles', () => {
    const all = Object.values(INDIVIDUAL_PERMISSIONS_BY_RESOURCE).flat()
    expect(all.map((p: any) => (typeof p === 'string' ? p : p.key))).toContain(PERM)
  })

  it('MANAGER lo tiene por default — confirmar un cobro es trabajo de gerencia, no de superadmin', () => {
    expect(DEFAULT_PERMISSIONS.MANAGER).toContain(PERM)
  })

  it('CASHIER NO lo tiene: es una afirmación sobre dinero', () => {
    expect(DEFAULT_PERMISSIONS.CASHIER ?? []).not.toContain(PERM)
  })
})
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `npx jest --selectProjects unit --testPathPattern "areaTicketExternalPermissions"`
Expected: FAIL en los tres.

- [ ] **Step 3: Registrar el permiso**

En `src/lib/permissions.ts`, junto a los `area-tickets:*` existentes — **usa la forma exacta que ya
tengan los vecinos** (string suelto u objeto `{ key, label }`; míralo con
`grep -n "area-tickets:" src/lib/permissions.ts`):

1. `INDIVIDUAL_PERMISSIONS_BY_RESOURCE`, recurso `area-tickets`: añadir
   `area-tickets:confirm-external` con la etiqueta en español **"Confirmar cobro en caja externa"**.
2. `DEFAULT_PERMISSIONS`: añadirlo a `MANAGER`, `ADMIN` y `OWNER`. **No** a `CASHIER` ni `WAITER`.
3. `PERMISSION_DEPENDENCIES`: `'area-tickets:confirm-external': ['area-tickets:confirm-external',
   'area-tickets:read']` si existe un `area-tickets:read`; si no existe, omite la entrada — no
   inventes un permiso que nadie gatea.

- [ ] **Step 4: Correr el test y el audit**

```bash
npx jest --selectProjects unit --testPathPattern "areaTicketExternalPermissions"   # PASS
npm run audit:permissions                                                          # exit 0
```

El audit lee los repos hermanos. Un `CATALOG_GAP` o `PHANTOM` aquí es un bug tuyo, no ruido.
`DASHBOARD_DEAD_GATE` es esperable hasta la Task 11 — anótalo y sigue.

- [ ] **Step 5: Commit**

```bash
git add src/lib/permissions.ts tests/unit/lib/areaTicketExternalPermissions.test.ts
git commit -m "feat(permissions): area-tickets:confirm-external

MANAGER+. Confirmar que otra caja cobró es una afirmación sobre dinero hecha
por una persona: no la hace un cajero, y no requiere ser superadmin de la
plataforma."
```

---

## Task 4: Emisión — la rama externa

**Files:**
- Modify: `avoqado-server/src/services/mobile/areaTicketV7.mobile.service.ts:714` (`issueAreaTicket`)
- Test: `avoqado-server/tests/integration/area-tickets/area-ticket-external-issue.test.ts`

**Interfaces:**
- Consumes: enums y modelos de Task 1; los CHECK de Task 2.
- Produces: `issueAreaTicket` acepta áreas `EXTERNAL` y devuelve el vale con
  `settlementRoute: 'EXTERNAL'` y `externalSettlement: { status, handoffState, referenceAmount }` en
  el mapeo. Task 5 y Task 6 dependen de que el settlement exista desde la emisión.

- [ ] **Step 1: Escribir el test de integración que falla**

```typescript
describe('Emisión en un área con ruta externa', () => {
  it('crea el vale con settlementRoute EXTERNAL y su settlement en PENDING', async () => {
    const ticket = await issueAreaTicket(venueId, {
      idempotencyKey: `ext-${suffix}-1`,
      deviceUid: externalIssueDeviceUid,
      lines: [{ clientLineId: 'l1', productId, quantity: '2' }],
    })

    expect(ticket.settlementRoute).toBe('EXTERNAL')
    expect(ticket.externalSettlement.status).toBe('PENDING')
    expect(ticket.externalSettlement.handoffState).toBe('PENDING')
    // El importe de referencia es el total del vale, al centavo.
    expect(ticket.externalSettlement.referenceAmount).toBe(ticket.total)
  })

  it('congela el modo de confirmación vigente — cambiarlo después no reescribe la historia', async () => {
    const ticket = await issueAreaTicket(venueId, {
      idempotencyKey: `ext-${suffix}-2`,
      deviceUid: externalIssueDeviceUid,
      lines: [{ clientLineId: 'l1', productId, quantity: '1' }],
    })
    await prisma.fulfillmentArea.update({
      where: { id: externalAreaId },
      data: { externalConfirmationMode: 'ASSUME_ON_PRINT' },
    })
    const row = await prisma.areaTicketExternalSettlement.findUnique({
      where: { areaTicketId: ticket.id },
    })
    expect(row!.confirmationMode).toBe('MANUAL')
  })

  it('consume el inventario AL EMITIR, no al cobrar — el producto ya salió del área', async () => {
    const before = await prisma.inventory.findUnique({ where: { id: externalInventoryId } })
    await issueAreaTicket(venueId, {
      idempotencyKey: `ext-${suffix}-3`,
      deviceUid: externalIssueDeviceUid,
      lines: [{ clientLineId: 'l1', productId, quantity: '3' }],
    })
    const after = await prisma.inventory.findUnique({ where: { id: externalInventoryId } })
    expect(Number(after!.currentStock)).toBe(Number(before!.currentStock) - 3)

    const reservation = await prisma.areaTicketInventoryReservation.findFirst({
      where: { inventoryId: externalInventoryId },
      orderBy: { createdAt: 'desc' },
    })
    expect(reservation!.status).toBe('CONSUMED')
    expect(reservation!.inventoryMovementId).not.toBeNull()
  })

  it('la misma idempotencyKey no crea un segundo settlement ni un segundo movimiento', async () => {
    const key = `ext-${suffix}-4`
    const input = {
      idempotencyKey: key,
      deviceUid: externalIssueDeviceUid,
      lines: [{ clientLineId: 'l1', productId, quantity: '1' }],
    }
    const a = await issueAreaTicket(venueId, input)
    const b = await issueAreaTicket(venueId, input)

    expect(b.id).toBe(a.id)
    expect(await prisma.areaTicketExternalSettlement.count({ where: { areaTicketId: a.id } })).toBe(1)
  })

  it('un área AVOQADO sigue sin settlement externo — la ruta nativa no cambió', async () => {
    const ticket = await issueAreaTicket(venueId, {
      idempotencyKey: `nat-${suffix}-1`,
      deviceUid: issueDeviceUid,
      lines: [{ clientLineId: 'l1', productId, quantity: '1' }],
    })
    expect(ticket.settlementRoute).toBe('AVOQADO')
    expect(await prisma.areaTicketExternalSettlement.count({ where: { areaTicketId: ticket.id } })).toBe(0)
  })
})
```

En el `beforeAll`, además de lo que ya crea `area-ticket-v7-flow.test.ts`, hace falta una segunda
área con `settlementRoute: 'EXTERNAL'`, su terminal (`canIssueAreaTickets: true`,
`fulfillmentAreaId` apuntando a ella) y `VenueAreaTicketSettings.inventoryReservationMode:
'HOLD_AVAILABLE_STOCK'`.

- [ ] **Step 2: Correr y verificar que falla**

Run: `TEST_DATABASE_URL=… npx jest --selectProjects integration --testPathPattern "area-ticket-external-issue"`
Expected: FAIL — `settlementRoute` no viene en el mapeo y no se crea settlement.

- [ ] **Step 3: Implementar la rama externa**

En `issueAreaTicket`, dentro de la transacción que ya crea el vale y sus líneas (~línea 756):

```typescript
const isExternal = fulfillmentArea.settlementRoute === AreaSettlementRoute.EXTERNAL
```

1. Al crear el `areaTicket`, añadir `settlementRoute: fulfillmentArea.settlementRoute`.
2. Después de crear las líneas, si `isExternal`:

```typescript
await tx.areaTicketExternalSettlement.create({
  data: {
    venueId,
    areaTicketId: ticket.id,
    status: AreaTicketExternalSettlementStatus.PENDING,
    handoffState: AreaTicketExternalHandoffState.PENDING,
    // Se congela: la política puede cambiar mañana, este vale se emitió bajo la de hoy.
    confirmationMode: fulfillmentArea.externalConfirmationMode,
    referenceAmount: total,
    idempotencyKey,
  },
})
```

3. **Inventario:** en la ruta externa las reservas se crean directamente `CONSUMED` con su
   movimiento, en la misma transacción. La ruta nativa las sigue creando `ACTIVE`. Reutiliza
   `reservationSpecs` y `lockAndValidateReservations` tal cual — solo cambia el estado final y que
   aquí sí se escribe el `InventoryMovement`. Extrae eso a un helper local
   `consumeReservationsAtIssue(tx, venueId, ticketId, specs)` para no inflar más una función que ya
   pasa de 150 líneas.
4. En `mapTicket`, añadir `settlementRoute: ticket.settlementRoute` y el bloque
   `externalSettlement` (null si no existe), con `referenceAmount` y `externalAmount` pasados por
   `money()` — decimal string de dos posiciones, como todo el resto del contrato.
5. Añadir `externalSettlement: true` a `ticketInclude`.

- [ ] **Step 4: Correr los tests**

```bash
TEST_DATABASE_URL=… npx jest --selectProjects integration --testPathPattern "area-ticket-external-issue"  # PASS
TEST_DATABASE_URL=… npx jest --selectProjects integration --testPathPattern "area-ticket-v7"              # PASS, sin cambios
npx jest --selectProjects unit --testPathPattern "areaTicketV7"                                            # PASS
```

- [ ] **Step 5: Typecheck y formato**

```bash
npm run build && npm run format && npm run lint:fix
```

`npx tsc --noEmit` pelón revienta por memoria en este repo — usa `npm run build`.

- [ ] **Step 6: Commit**

```bash
git add src/services/mobile/areaTicketV7.mobile.service.ts \
        tests/integration/area-tickets/area-ticket-external-issue.test.ts
git commit -m "feat(area-tickets): emisión en ruta externa

El vale nace con su settlement en PENDING y el modo de confirmación congelado.

El inventario se consume AL EMITIR, no al cobrar (enmienda E3 del spec): el
producto ya se rebanó y salió del área, y esperar un cobro que Avoqado nunca
observa dejaría el stock inflado para siempre."
```

---

## Task 5: Reversa de inventario al cancelar

**Files:**
- Modify: `avoqado-server/src/services/mobile/areaTicketV7.mobile.service.ts` (cancelación)
- Test: `avoqado-server/tests/integration/area-tickets/area-ticket-external-cancel.test.ts`

**Interfaces:**
- Consumes: `reversalMovementId` (Task 1), reservas `CONSUMED` (Task 4).
- Produces: nada nuevo hacia afuera.

- [ ] **Step 1: Escribir el test que falla**

```typescript
describe('Cancelación de un vale externo ya consumido', () => {
  it('devuelve el stock y marca la reserva RELEASED', async () => {
    const ticket = await issueExternalTicket({ quantity: '2' })
    const before = await stockOf(externalInventoryId)

    await cancelAreaTicket(venueId, ticket.id, {
      idempotencyKey: `cancel-${suffix}-1`,
      deviceUid: externalIssueDeviceUid,
      reason: 'El cliente se arrepintió',
    })

    // Comparar en Decimal, no en Number: `currentStock` es Decimal(x,3) y
    // colapsarlo a float invita a un falso verde el día que la cantidad no sea redonda.
    expect((await stockOf(externalInventoryId)).toFixed(3)).toBe(before.plus(2).toFixed(3))
    const r = await prisma.areaTicketInventoryReservation.findFirst({ where: { areaTicketId: ticket.id } })
    expect(r!.status).toBe('RELEASED')
    expect(r!.reversalMovementId).not.toBeNull()
  })

  it('cancelar dos veces devuelve el stock UNA sola vez', async () => {
    const ticket = await issueExternalTicket({ quantity: '2' })
    const before = await stockOf(externalInventoryId)
    const input = { idempotencyKey: `cancel-${suffix}-2`, deviceUid: externalIssueDeviceUid, reason: 'x' }

    await cancelAreaTicket(venueId, ticket.id, input)
    await cancelAreaTicket(venueId, ticket.id, input)

    // Comparar en Decimal, no en Number: `currentStock` es Decimal(x,3) y
    // colapsarlo a float invita a un falso verde el día que la cantidad no sea redonda.
    expect((await stockOf(externalInventoryId)).toFixed(3)).toBe(before.plus(2).toFixed(3))   // NO +4
  })

  it('con recordWasteOnCancel el stock NO vuelve: se registra merma', async () => {
    await prisma.venueAreaTicketSettings.update({ where: { venueId }, data: { recordWasteOnCancel: true } })
    const ticket = await issueExternalTicket({ quantity: '1' })
    const before = await stockOf(externalInventoryId)

    await cancelAreaTicket(venueId, ticket.id, {
      idempotencyKey: `cancel-${suffix}-3`, deviceUid: externalIssueDeviceUid, reason: 'Se echó a perder',
    })

    expect(await stockOf(externalInventoryId)).toBe(before)
    const r = await prisma.areaTicketInventoryReservation.findFirst({ where: { areaTicketId: ticket.id } })
    expect(r!.status).toBe('WASTE')
  })

})
```

> **El caso "no se puede cancelar un cobro ya CONFIRMED" NO va en esta tarea.** Necesita
> `confirmExternalSettlement`, que nace en Task 7, y un test en `skip` es un test que no prueba nada.
> Se escribe completo en Task 7, donde ya existe todo lo que necesita. El guard sí se implementa
> aquí (Step 3) — lo que se difiere es su test, no su código.

- [ ] **Step 2: Correr y verificar que falla**

Run: `TEST_DATABASE_URL=… npx jest --selectProjects integration --testPathPattern "area-ticket-external-cancel"`
Expected: FAIL — no hay reversa.

- [ ] **Step 3: Implementar**

En la cancelación, para vales `EXTERNAL` con reservas `CONSUMED`, dentro de una transacción:

```typescript
for (const reservation of consumedReservations) {
  if (reservation.reversalMovementId) continue   // ya revertida: idempotente

  const wastes = settings.recordWasteOnCancel
  // 🔴 `MovementType` NO tiene WASTE ni RETURN. El enum real es
  // PURCHASE | SALE | ADJUSTMENT | LOSS | TRANSFER | COUNT.
  // Sigue el precedente de `restockItem` (la restitución por reembolso), que ya
  // resolvió este mismo problema: ADJUSTMENT para devolver a stock, LOSS para merma.
  const movement = await tx.inventoryMovement.create({
    data: {
      inventoryId: reservation.inventoryId,
      type: wastes ? MovementType.LOSS : MovementType.ADJUSTMENT,
      // El signo es lo inverso del consumo. Verifica cuál usa el consumo ANTES de
      // escribir el tuyo — un signo invertido duplica el error en vez de corregirlo,
      // y no lo nota nadie hasta el conteo físico.
      quantity: wastes ? reservation.quantityBaseUnits.neg() : reservation.quantityBaseUnits,
      reference: `area-ticket-cancel:${ticket.id}`,
      // …resto según el contrato de InventoryMovement
    },
  })
  if (!wastes) await adjustInventoryStock(tx, reservation.inventoryId, reservation.quantityBaseUnits)

  await tx.areaTicketInventoryReservation.update({
    where: { id: reservation.id },
    data: {
      status: wastes ? 'WASTE' : 'RELEASED',
      releasedAt: new Date(),
      reversalMovementId: movement.id,
    },
  })
}
```

Y el guard, antes de todo:

```typescript
if (ticket.externalSettlement?.status === AreaTicketExternalSettlementStatus.CONFIRMED) {
  throw domainError(409, 'AREA_TICKET_EXTERNAL_ALREADY_CHARGED',
    'Este vale ya se cobró en la caja externa. La devolución se hace ahí.')
}
```

🔴 **Verifica el signo de `quantity` contra `deductStockFIFO` antes de escribirlo.** Hay
inconsistencia conocida de signos entre las rutas de recepción y las de venta en este repo.

- [ ] **Step 4: Correr los tests**

Run: `TEST_DATABASE_URL=… npx jest --selectProjects integration --testPathPattern "area-ticket-external-cancel"`
Expected: PASS los tres activos (el cuarto sigue en `skip`).

- [ ] **Step 5: Commit**

```bash
git add src/services/mobile/areaTicketV7.mobile.service.ts \
        tests/integration/area-tickets/area-ticket-external-cancel.test.ts
git commit -m "feat(area-tickets): reversa de inventario al cancelar un vale externo

Idempotente por reversalMovementId. Sin esa columna, un reintento de
cancelación devuelve el producto a stock dos veces."
```

---

## Task 5b: Guard — un vale externo no entra al circuito de caja Avoqado

**Files:**
- Modify: `avoqado-server/src/services/mobile/areaTicketV7.mobile.service.ts` (`addTicketToCheckout:941`, y el claim de `lockAreaTicketCheckoutForPayment:1018`)
- Test: `avoqado-server/tests/integration/area-tickets/area-ticket-external-not-claimable.test.ts`

**Interfaces:**
- Consumes: `settlementRoute` (Task 1), el CHECK `area_ticket_external_no_avoqado_circuit` (Task 2), vales externos emitibles (Task 4).
- Produces: error de dominio `AREA_TICKET_IS_EXTERNAL` (409).

**Por qué existe esta tarea:** la encontró la revisión de Task 4 y **no estaba en el plan original**. Task 4 es el primer productor de vales `EXTERNAL`; hoy, si una caja Avoqado escanea uno, el código pasa todos sus guards —ni siquiera selecciona `settlementRoute`— y llega al `updateMany` que pone `status: CLAIMED` + `checkoutSessionId`, donde el CHECK de la base dispara un `23514` que sale como **500 crudo en la pantalla del cajero**. El dinero está a salvo (el CHECK hace su trabajo), pero un error de Postgres no le dice a nadie qué hacer. El CHECK es la red; esto es la puerta.

- [ ] **Step 1: Escribir el test que falla**

```typescript
describe('Un vale externo no se puede cobrar en una caja Avoqado', () => {
  it('addTicketToCheckout lo rechaza con AREA_TICKET_IS_EXTERNAL, no con un 500 de Postgres', async () => {
    const ticket = await issueExternalTicket({ quantity: '1' })
    const session = await createAreaTicketCheckout(venueId, { idempotencyKey: `s-${suffix}`, deviceUid: checkoutDeviceUid })

    await expect(
      addTicketToCheckout(venueId, session.id, ticket.code, { idempotencyKey: `a-${suffix}`, deviceUid: checkoutDeviceUid }),
    ).rejects.toMatchObject({ code: 'AREA_TICKET_IS_EXTERNAL' })
  })

  it('el mensaje le dice al cajero qué hacer, no qué falló', async () => {
    const ticket = await issueExternalTicket({ quantity: '1' })
    const session = await createAreaTicketCheckout(venueId, { idempotencyKey: `s2-${suffix}`, deviceUid: checkoutDeviceUid })
    try {
      await addTicketToCheckout(venueId, session.id, ticket.code, { idempotencyKey: `a2-${suffix}`, deviceUid: checkoutDeviceUid })
      throw new Error('debió rechazar')
    } catch (e: any) {
      expect(e.code).toBe('AREA_TICKET_IS_EXTERNAL')
      expect(e.message).toMatch(/caja externa/i)
      expect(e.message).not.toMatch(/constraint|violat|23514/i)
    }
  })

  it('el vale externo queda intacto: sigue ISSUED, sin sesión y sin orden', async () => {
    const ticket = await issueExternalTicket({ quantity: '1' })
    const session = await createAreaTicketCheckout(venueId, { idempotencyKey: `s3-${suffix}`, deviceUid: checkoutDeviceUid })
    await expect(
      addTicketToCheckout(venueId, session.id, ticket.code, { idempotencyKey: `a3-${suffix}`, deviceUid: checkoutDeviceUid }),
    ).rejects.toThrow()

    const after = await prisma.areaTicket.findUnique({ where: { id: ticket.id } })
    expect(after!.status).toBe('ISSUED')
    expect(after!.checkoutSessionId).toBeNull()
    expect(after!.orderId).toBeNull()
  })

  it('un vale NATIVO se sigue agregando a la sesión igual que antes', async () => {
    const ticket = await issueNativeTicket({ quantity: '1' })
    const session = await createAreaTicketCheckout(venueId, { idempotencyKey: `s4-${suffix}`, deviceUid: checkoutDeviceUid })
    const result = await addTicketToCheckout(venueId, session.id, ticket.code, { idempotencyKey: `a4-${suffix}`, deviceUid: checkoutDeviceUid })
    expect(result.tickets.map((t: any) => t.id)).toContain(ticket.id)
  })
})
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `set -a; source .env.test.local; set +a; npx jest --selectProjects integration --testPathPattern "area-ticket-external-not-claimable"`
Expected: FAIL — los primeros casos truenan con un error de Postgres (`23514`, `check_violation`) en vez de con `AREA_TICKET_IS_EXTERNAL`. **Captura esa salida:** es la prueba de que el defecto existe.

- [ ] **Step 3: Implementar el guard**

En `addTicketToCheckout`, añade `settlementRoute` al `select` del vale (hoy no se selecciona) y, junto a los guards de estado que ya existen:

```typescript
if (ticket.settlementRoute === AreaSettlementRoute.EXTERNAL) {
  throw domainError(409, 'AREA_TICKET_IS_EXTERNAL',
    'Este vale se cobra en la caja externa, no en Avoqado.')
}
```

Aplica el mismo guard en el claim de `lockAreaTicketCheckoutForPayment`. Ponlo **antes** de cualquier escritura, para que el vale quede intacto.

- [ ] **Step 4: Correr los tests**

```bash
set -a; source .env.test.local; set +a
npx jest --selectProjects integration --testPathPattern "area-ticket-external-not-claimable"   # 4/4
npx jest --selectProjects integration --testPathPattern "area-ticket-v7"                       # regresión, sin cambios
```

- [ ] **Step 5: Commit**

```bash
git add src/services/mobile/areaTicketV7.mobile.service.ts \
        tests/integration/area-tickets/area-ticket-external-not-claimable.test.ts
git commit -m "fix(area-tickets): rechazar un vale externo en caja Avoqado con un error legible

El CHECK de la base ya impedía el daño (un vale EXTERNAL no puede entrar al
circuito de caja Avoqado), pero lo hacía disparando un 23514 que le llegaba al
cajero como un 500 crudo. El CHECK es la red; este guard es la puerta: rechaza
antes de escribir, con un mensaje que dice qué hacer.

Encontrado por la revisión de Task 4."
```

---

## Task 6: Servicio de settlement — handoff

**Files:**
- Create: `avoqado-server/src/services/mobile/areaTicketExternal.mobile.service.ts`
- Create: `avoqado-server/tests/unit/services/mobile/areaTicketExternal.handoff.test.ts`

**Interfaces:**
- Consumes: modelos de Task 1, settlement creado en Task 4.
- Produces:

```typescript
export interface ExternalSettlementInput {
  idempotencyKey: string
  deviceUid: string
  staffId?: string | null
}
export async function markExternalHandoff(
  venueId: string, ticketId: string, input: ExternalSettlementInput,
): Promise<{ areaTicketId: string; handoffState: 'HANDED_OFF'; alreadyHandedOff: boolean }>
```

**Por qué archivo nuevo:** `areaTicketV7.mobile.service.ts` ya pasa de 1,800 líneas. Todo lo del
cobro externo vive junto y aparte.

- [ ] **Step 1: Escribir el test que falla**

```typescript
describe('markExternalHandoff', () => {
  it('marca HANDED_OFF cuando el vale se imprimió', async () => {
    mockTicket({ printStatus: 'PRINTED', settlementRoute: 'EXTERNAL' })
    const r = await markExternalHandoff(venueId, ticketId, baseInput)
    expect(r.handoffState).toBe('HANDED_OFF')
    expect(r.alreadyHandedOff).toBe(false)
  })

  it('NO deja marcar el envío de un vale que nunca se imprimió — no hay papel que llevar', async () => {
    mockTicket({ printStatus: 'PRINT_FAILED', settlementRoute: 'EXTERNAL' })
    await expect(markExternalHandoff(venueId, ticketId, baseInput))
      .rejects.toMatchObject({ code: 'AREA_TICKET_NOT_PRINTED' })
  })

  it('repetir la misma llave devuelve alreadyHandedOff sin volver a escribir', async () => {
    mockTicket({ printStatus: 'PRINTED', handoffState: 'HANDED_OFF' })
    const r = await markExternalHandoff(venueId, ticketId, baseInput)
    expect(r.alreadyHandedOff).toBe(true)
  })

  it('rechaza un vale de ruta AVOQADO', async () => {
    mockTicket({ settlementRoute: 'AVOQADO' })
    await expect(markExternalHandoff(venueId, ticketId, baseInput))
      .rejects.toMatchObject({ code: 'AREA_TICKET_NOT_EXTERNAL' })
  })
})
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `npx jest --selectProjects unit --testPathPattern "areaTicketExternal.handoff"`
Expected: FAIL — el módulo no existe.

- [ ] **Step 3: Implementar**

Crea el servicio con el guard compartido que van a reutilizar las Tasks 7-9:

```typescript
async function loadExternalTicket(venueId: string, ticketId: string) {
  const ticket = await prisma.areaTicket.findFirst({
    where: { id: ticketId, venueId },
    include: { externalSettlement: true, fulfillmentArea: true },
  })
  if (!ticket) throw domainError(404, 'AREA_TICKET_NOT_FOUND', 'No encontramos ese vale en este local.')
  if (ticket.settlementRoute !== AreaSettlementRoute.EXTERNAL || !ticket.externalSettlement) {
    throw domainError(409, 'AREA_TICKET_NOT_EXTERNAL', 'Este vale se cobra en Avoqado, no en una caja externa.')
  }
  return ticket
}
```

`markExternalHandoff` exige `printStatus === 'PRINTED'`, y si ya está `HANDED_OFF` devuelve
`alreadyHandedOff: true` sin escribir.

- [ ] **Step 4: Correr el test**

Run: `npx jest --selectProjects unit --testPathPattern "areaTicketExternal.handoff"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/services/mobile/areaTicketExternal.mobile.service.ts \
        tests/unit/services/mobile/areaTicketExternal.handoff.test.ts
git commit -m "feat(area-tickets): servicio de cobro externo — handoff

Marcar que el papel salió del área es un hecho distinto de haberlo impreso, y
por eso es su propio eje. Un vale que no se imprimió no se puede entregar en
la caja."
```

---

## Task 7: Confirmar el cobro (y la discrepancia)

**Files:**
- Modify: `avoqado-server/src/services/mobile/areaTicketExternal.mobile.service.ts`
- Create: `avoqado-server/tests/unit/services/mobile/areaTicketExternal.confirm.test.ts`
- Modify: `avoqado-server/tests/integration/area-tickets/area-ticket-external-cancel.test.ts` (añadir el caso diferido de Task 5)

**Interfaces:**
- Produces:

```typescript
export interface ConfirmExternalSettlementInput extends ExternalSettlementInput {
  externalAmount?: string | null      // decimal string en pesos, p.ej. "168.00"
  externalReference?: string | null
  notes?: string | null
}
export async function confirmExternalSettlement(
  venueId: string, ticketId: string, input: ConfirmExternalSettlementInput,
): Promise<{
  areaTicketId: string
  status: 'CONFIRMED' | 'DISCREPANCY'
  referenceAmount: string
  externalAmount: string | null
  variance: string | null
  alreadyConfirmed: boolean
}>
```

- [ ] **Step 1: Escribir el test que falla**

```typescript
describe('confirmExternalSettlement', () => {
  it('sin importe capturado queda CONFIRMED', async () => {
    const r = await confirmExternalSettlement(venueId, ticketId, baseInput)
    expect(r.status).toBe('CONFIRMED')
    expect(r.variance).toBeNull()
  })

  it('con el mismo importe queda CONFIRMED y variación cero', async () => {
    mockSettlement({ referenceAmount: '168.00' })
    const r = await confirmExternalSettlement(venueId, ticketId, { ...baseInput, externalAmount: '168.00' })
    expect(r.status).toBe('CONFIRMED')
    expect(r.variance).toBe('0.00')
  })

  it('con importe distinto queda DISCREPANCY y guarda la variación con signo', async () => {
    mockSettlement({ referenceAmount: '168.00' })
    const r = await confirmExternalSettlement(venueId, ticketId, { ...baseInput, externalAmount: '165.50' })
    expect(r.status).toBe('DISCREPANCY')
    expect(r.variance).toBe('-2.50')
  })

  it('la variación se calcula en Decimal, no en float: 0.1 + 0.2 no puede dar 0.30000000000000004', async () => {
    mockSettlement({ referenceAmount: '0.30' })
    const r = await confirmExternalSettlement(venueId, ticketId, { ...baseInput, externalAmount: '0.10' })
    expect(r.variance).toBe('-0.20')
  })

  it('repetir la misma llave devuelve alreadyConfirmed y NO cambia el importe', async () => {
    mockSettlement({ status: 'CONFIRMED', externalAmount: '168.00', idempotencyKey: 'k1' })
    const r = await confirmExternalSettlement(venueId, ticketId, { ...baseInput, idempotencyKey: 'k1', externalAmount: '999.00' })
    expect(r.alreadyConfirmed).toBe(true)
    expect(r.externalAmount).toBe('168.00')
  })

  it('rechaza confirmar un vale CANCELLED', async () => {
    mockTicket({ status: 'CANCELLED' })
    await expect(confirmExternalSettlement(venueId, ticketId, baseInput))
      .rejects.toMatchObject({ code: 'AREA_TICKET_NOT_ISSUED' })
  })
})
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `npx jest --selectProjects unit --testPathPattern "areaTicketExternal.confirm"`
Expected: FAIL.

- [ ] **Step 3: Implementar**

```typescript
const reference = new Prisma.Decimal(settlement.referenceAmount)
const external = input.externalAmount == null ? null : new Prisma.Decimal(input.externalAmount)
const variance = external === null ? null : external.sub(reference)
const status = variance !== null && !variance.isZero()
  ? AreaTicketExternalSettlementStatus.DISCREPANCY
  : AreaTicketExternalSettlementStatus.CONFIRMED
```

`variance` se **deriva**, no se persiste: una columna calculada que se puede desincronizar de sus dos
insumos es una fuente de mentiras. Se expone como decimal string de dos posiciones.

Si el status queda `DISCREPANCY`, abre `AreaTicketExternalIncident` de tipo `AMOUNT_VARIANCE` con
`detail: { referenceAmount, externalAmount, variance }` — usa `upsert` sobre el
`@@unique([areaTicketId, kind])`.

Y el `ActivityLog`, fuera de la transacción:

```typescript
void logAction({
  action: 'AREA_TICKET_EXTERNAL_CHARGE_CONFIRMED',
  entity: 'AreaTicketExternalSettlement',
  entityId: settlement.id,
  staffId: input.staffId ?? null,
  venueId,
  data: { areaTicketId: ticketId, referenceAmount, externalAmount, variance, status },
})
```

- [ ] **Step 4: Escribir el caso que Task 5 dejó pendiente**

El guard `AREA_TICKET_EXTERNAL_ALREADY_CHARGED` se implementó en Task 5, pero no se pudo probar sin
`confirmExternalSettlement`. Ahora sí. Añade a
`tests/integration/area-tickets/area-ticket-external-cancel.test.ts`:

```typescript
it('no se puede cancelar un vale con el cobro CONFIRMED — eso es una devolución, y ocurre en la otra caja', async () => {
  const ticket = await issueExternalTicket({ quantity: '1' })
  await confirmExternalSettlement(venueId, ticket.id, {
    idempotencyKey: `conf-${suffix}`, deviceUid: externalIssueDeviceUid, staffId,
  })
  await expect(
    cancelAreaTicket(venueId, ticket.id, {
      idempotencyKey: `cx-${suffix}`, deviceUid: externalIssueDeviceUid, reason: 'ya no',
    }),
  ).rejects.toMatchObject({ code: 'AREA_TICKET_EXTERNAL_ALREADY_CHARGED' })
})
```

- [ ] **Step 5: Correr los tests**

```bash
npx jest --selectProjects unit --testPathPattern "areaTicketExternal.confirm"                              # PASS
TEST_DATABASE_URL=… npx jest --selectProjects integration --testPathPattern "area-ticket-external-cancel"  # PASS los 4
```

- [ ] **Step 6: Commit**

```bash
git add src/services/mobile/areaTicketExternal.mobile.service.ts \
        tests/unit/services/mobile/areaTicketExternal.confirm.test.ts \
        tests/integration/area-tickets/area-ticket-external-cancel.test.ts
git commit -m "feat(area-tickets): confirmar el cobro externo, con variación

La variación se DERIVA de referenceAmount y externalAmount en Decimal; no se
persiste, porque una columna calculada que se desincroniza de sus insumos
miente. Una diferencia abre incidencia pero NO bloquea la entrega: el producto
ya está pagado en la otra caja."
```

---

## Task 8: Marcar "no cobrado"

**Files:**
- Modify: `avoqado-server/src/services/mobile/areaTicketExternal.mobile.service.ts`
- Create: `avoqado-server/tests/unit/services/mobile/areaTicketExternal.notCharged.test.ts`

**Interfaces:**
- Produces:

```typescript
export async function markExternalNotCharged(
  venueId: string, ticketId: string, input: ExternalSettlementInput & { reason: string },
): Promise<{ areaTicketId: string; status: 'NOT_CHARGED' }>
```

- [ ] **Step 1: Escribir el test que falla**

```typescript
describe('markExternalNotCharged', () => {
  it('marca NOT_CHARGED y exige motivo', async () => {
    const r = await markExternalNotCharged(venueId, ticketId, { ...baseInput, reason: 'El cliente no pasó a caja' })
    expect(r.status).toBe('NOT_CHARGED')
  })

  it('sin motivo no procede — es una afirmación que alguien tendrá que auditar', async () => {
    await expect(markExternalNotCharged(venueId, ticketId, { ...baseInput, reason: '   ' }))
      .rejects.toMatchObject({ code: 'REASON_REQUIRED' })
  })

  it('no se puede marcar no cobrado algo ya CONFIRMED', async () => {
    mockSettlement({ status: 'CONFIRMED' })
    await expect(markExternalNotCharged(venueId, ticketId, { ...baseInput, reason: 'x' }))
      .rejects.toMatchObject({ code: 'AREA_TICKET_EXTERNAL_ALREADY_CHARGED' })
  })

  it('cierra la incidencia de cobro sin confirmar, si estaba abierta', async () => {
    await openIncident('UNCONFIRMED_CHARGE')
    await markExternalNotCharged(venueId, ticketId, { ...baseInput, reason: 'no pasó' })
    const inc = await prisma.areaTicketExternalIncident.findFirst({
      where: { areaTicketId: ticketId, kind: 'UNCONFIRMED_CHARGE' },
    })
    expect(inc!.status).toBe('RESOLVED')
  })
})
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `npx jest --selectProjects unit --testPathPattern "areaTicketExternal.notCharged"`
Expected: FAIL.

- [ ] **Step 3: Implementar**

`reason` obligatorio y no vacío tras `trim()`. Marca `NOT_CHARGED`, resuelve la incidencia
`UNCONFIRMED_CHARGE` si existe, y escribe `ActivityLog` con
`action: 'AREA_TICKET_EXTERNAL_MARKED_NOT_CHARGED'`.

**No cancela el vale ni revierte inventario**: son decisiones distintas y las toma una persona. El
vale queda `ISSUED` con cobro `NOT_CHARGED`, deja de ser entregable, y quien decida cancelarlo lo
hace explícitamente por la ruta de Task 5.

- [ ] **Step 4: Cerrar el hueco del guard de cancelación** (lo encontró la revisión de Task 7)

`cancelAreaTicket` bloquea la cancelación solo cuando el cobro externo está `CONFIRMED`
(`areaTicketV7.mobile.service.ts`, dos puntos: el chequeo previo a la transacción y el de
adentro). Cuando se escribió ese guard, `DISCREPANCY` y `ASSUMED` **no eran estados alcanzables
todavía** — Task 7 los volvió reales.

El hueco es concreto: cancelar dispara la reversa de inventario. Con el cobro en `DISCREPANCY`
—que significa que la otra caja **sí** cobró, solo que por otro importe— devolverías a stock un
producto que el cliente ya pagó y se llevó.

Amplía **los dos** puntos del guard para que bloqueen cuando el estado esté en:

```typescript
const YA_COBRADO_AFUERA = [
  AreaTicketExternalSettlementStatus.CONFIRMED,
  AreaTicketExternalSettlementStatus.DISCREPANCY,
  AreaTicketExternalSettlementStatus.ASSUMED,
]
```

- `CONFIRMED` y `DISCREPANCY`: alguien afirmó que la otra caja cobró. La devolución se hace ahí.
- `ASSUMED`: se dio por cobrado sin verificar. Cancelar aquí revierte inventario sobre una venta
  que probablemente ocurrió, **sin que nadie lo haya afirmado**. La salida correcta es declararlo
  primero con `markExternalNotCharged` —que es una afirmación humana, con motivo y auditada— y
  cancelar después. Por eso este paso vive en esta tarea y no antes: `markExternalNotCharged` es
  lo que lo hace posible.

Se sigue pudiendo cancelar con el cobro en `PENDING` o `NOT_CHARGED`.

Añade a `tests/integration/area-tickets/area-ticket-external-cancel.test.ts`:

```typescript
it('no se puede cancelar un vale con DISCREPANCY — la otra caja cobró, aunque por otro importe', async () => {
  const ticket = await issueExternalTicket({ quantity: '1' })
  await confirmExternalSettlement(venueId, ticket.id, {
    idempotencyKey: `d-${suffix}`, deviceUid: externalIssueDeviceUid, staffId, externalAmount: '999.00',
  })
  await expect(
    cancelAreaTicket(venueId, ticket.id, { idempotencyKey: `dx-${suffix}`, deviceUid: externalIssueDeviceUid, reason: 'x' }),
  ).rejects.toMatchObject({ code: 'AREA_TICKET_EXTERNAL_ALREADY_CHARGED' })
})

it('no se puede cancelar un vale ASSUMED sin declarar primero que no se cobró', async () => {
  const ticket = await issueAssumedExternalTicket()
  await expect(
    cancelAreaTicket(venueId, ticket.id, { idempotencyKey: `ax-${suffix}`, deviceUid: externalIssueDeviceUid, reason: 'x' }),
  ).rejects.toMatchObject({ code: 'AREA_TICKET_EXTERNAL_ALREADY_CHARGED' })
})

it('tras marcarlo NOT_CHARGED, el mismo vale SÍ se puede cancelar y el stock vuelve', async () => {
  const ticket = await issueAssumedExternalTicket()
  await markExternalNotCharged(venueId, ticket.id, {
    idempotencyKey: `nc-${suffix}`, deviceUid: externalIssueDeviceUid, staffId, reason: 'El cliente no pasó a caja',
  })
  const before = await stockOf(externalInventoryId)
  await cancelAreaTicket(venueId, ticket.id, { idempotencyKey: `ok-${suffix}`, deviceUid: externalIssueDeviceUid, reason: 'no pasó' })
  expect((await stockOf(externalInventoryId)).toFixed(3)).toBe(before.plus(1).toFixed(3))
})
```

El tercero es el que prueba que el flujo completo funciona: no es un callejón sin salida, es un
paso explícito de por medio.

- [ ] **Step 5: Correr los tests**

```bash
npx jest --selectProjects unit --testPathPattern "areaTicketExternal.notCharged"
set -a; source .env.test.local; set +a
npx jest --selectProjects integration --testPathPattern "area-ticket-external-cancel"
```
Expected: PASS ambos. El de integración incluye los casos previos de Task 5 y Task 7 — si alguno
truena, el guard ampliado rompió algo que antes funcionaba.

- [ ] **Step 6: Commit**

```bash
git add src/services/mobile/areaTicketExternal.mobile.service.ts \
        tests/unit/services/mobile/areaTicketExternal.notCharged.test.ts
git commit -m "feat(area-tickets): marcar un cobro externo como no cobrado

Exige motivo y cierra la incidencia. No cancela el vale ni revierte inventario:
son decisiones distintas que toma una persona, no un efecto colateral."
```

---

## Task 9: Cola "Cobros por confirmar"

**Files:**
- Modify: `avoqado-server/src/services/mobile/areaTicketExternal.mobile.service.ts`
- Create: `avoqado-server/tests/integration/area-tickets/area-ticket-external-queue.test.ts`

**Interfaces:**
- Produces:

```typescript
export async function listPendingExternalConfirmation(
  venueId: string, input: { deviceUid: string; cursor?: string | null; limit?: number },
): Promise<{ items: PendingExternalItem[]; nextCursor: string | null }>
```

- [ ] **Step 1: Escribir el test que falla**

```typescript
describe('listPendingExternalConfirmation', () => {
  it('devuelve solo los vales del área de la terminal autenticada', async () => {
    const r = await listPendingExternalConfirmation(venueId, { deviceUid: externalIssueDeviceUid })
    expect(r.items.every(i => i.fulfillmentAreaId === externalAreaId)).toBe(true)
  })

  it('excluye los ya confirmados, los asumidos y los cancelados', async () => { /* … */ })

  it('ordena por antigüedad y pagina por cursor estable', async () => {
    const first = await listPendingExternalConfirmation(venueId, { deviceUid: externalIssueDeviceUid, limit: 2 })
    expect(first.items).toHaveLength(2)
    const second = await listPendingExternalConfirmation(venueId, {
      deviceUid: externalIssueDeviceUid, limit: 2, cursor: first.nextCursor,
    })
    // Sin traslape: un cursor por offset se recorre solo cuando llegan vales nuevos mientras
    // el operador lee la lista.
    expect(second.items.map(i => i.id)).not.toEqual(expect.arrayContaining(first.items.map(i => i.id)))
  })

  it('una terminal de otra área no ve estos vales', async () => {
    const r = await listPendingExternalConfirmation(venueId, { deviceUid: issueDeviceUid })
    expect(r.items).toHaveLength(0)
  })
})
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `TEST_DATABASE_URL=… npx jest --selectProjects integration --testPathPattern "area-ticket-external-queue"`
Expected: FAIL.

- [ ] **Step 3: Implementar**

Deriva el área de la terminal (nunca del body). Filtra
`settlementRoute: 'EXTERNAL'`, `status: 'ISSUED'`, `externalSettlement.status: 'PENDING'`. Reutiliza
`encodePendingCursor` / `decodePendingCursor` que ya existen en `areaTicketV7.mobile.service.ts`
(línea 1682) — expórtalos si hace falta, no los dupliques. `limit` por default 25, tope 100.

- [ ] **Step 4: Correr el test**

Run: `TEST_DATABASE_URL=… npx jest --selectProjects integration --testPathPattern "area-ticket-external-queue"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/services/mobile/areaTicketExternal.mobile.service.ts \
        tests/integration/area-tickets/area-ticket-external-queue.test.ts
git commit -m "feat(area-tickets): cola de cobros por confirmar

Área derivada de la terminal autenticada, cursor estable (issuedAt, id). Un
cursor por offset se recorre solo cuando entran vales nuevos mientras el
operador lee la lista."
```

---

## Task 10: Entrega — el predicado externo

**Files:**
- Modify: `avoqado-server/src/services/mobile/areaTicketV7.mobile.service.ts` (`fulfillAreaTicket:1799`, `listPendingAreaTicketFulfillment:1706`)
- Create: `avoqado-server/tests/integration/area-tickets/area-ticket-external-fulfillment.test.ts`

**Interfaces:**
- Consumes: settlement (Tasks 4, 7, 8), CHECK de Task 2.
- Produces: `fulfillAreaTicket` acepta vales externos; `AreaTicketFulfillment` con
  `settlementRoute: 'EXTERNAL'` y `orderId: null`.

- [ ] **Step 1: Escribir el test que falla**

```typescript
describe('Entrega de un vale externo', () => {
  it('entrega con el cobro CONFIRMED, sin orderId', async () => {
    const t = await issueAndConfirmExternal()
    const r = await fulfillAreaTicket(venueId, t.id, { idempotencyKey: `f-${suffix}`, deviceUid: externalDeliveryDeviceUid, method: 'PAPER_CONFIRMATION' })
    expect(r.fulfillment.id).toBeTruthy()
    const row = await prisma.areaTicketFulfillment.findUnique({ where: { areaTicketId: t.id } })
    expect(row!.orderId).toBeNull()
    expect(row!.settlementRoute).toBe('EXTERNAL')
  })

  it('entrega con ASSUMED', async () => { /* … */ })

  it('entrega con DISCREPANCY — el producto ya está pagado en la otra caja', async () => { /* … */ })

  it('NO entrega con el cobro PENDING', async () => {
    const t = await issueExternalTicket({ quantity: '1' })
    await expect(fulfillAreaTicket(venueId, t.id, { idempotencyKey: `f2-${suffix}`, deviceUid: externalDeliveryDeviceUid, method: 'PAPER_CONFIRMATION' }))
      .rejects.toMatchObject({ code: 'AREA_TICKET_EXTERNAL_NOT_CONFIRMED' })
  })

  it('NO entrega con NOT_CHARGED', async () => { /* … */ })

  it('entregar dos veces crea UN solo evento y devuelve actor y hora del primero', async () => {
    const t = await issueAndConfirmExternal()
    const input = { idempotencyKey: `f3-${suffix}`, deviceUid: externalDeliveryDeviceUid, method: 'PAPER_CONFIRMATION' as const }
    const a = await fulfillAreaTicket(venueId, t.id, input)
    const b = await fulfillAreaTicket(venueId, t.id, input)
    expect(b.alreadyFulfilled).toBe(true)
    expect(await prisma.areaTicketFulfillment.count({ where: { areaTicketId: t.id } })).toBe(1)
  })

  it('un área UNTRACKED no aparece en pendientes', async () => {
    await prisma.fulfillmentArea.update({ where: { id: externalAreaId }, data: { externalDeliveryTracking: 'UNTRACKED' } })
    await issueAndConfirmExternal()
    const p = await listPendingAreaTicketFulfillment(venueId, { deviceUid: externalDeliveryDeviceUid })
    expect(p.items).toHaveLength(0)
  })

  it('la ruta nativa sigue exigiendo Order pagada', async () => {
    // Regresión: el camino v7 no cambió.
  })
})
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `TEST_DATABASE_URL=… npx jest --selectProjects integration --testPathPattern "area-ticket-external-fulfillment"`
Expected: FAIL — `fulfillAreaTicket` exige `order.paymentStatus === 'PAID'` (línea 1815).

- [ ] **Step 3: Implementar**

En `fulfillAreaTicket`, sustituir el guard de la línea 1815 por una bifurcación **explícita por
ruta** — no por "si hay orden, valídala":

```typescript
if (ticket.settlementRoute === AreaSettlementRoute.EXTERNAL) {
  const st = ticket.externalSettlement?.status
  const eligible = st === 'CONFIRMED' || st === 'ASSUMED' || st === 'DISCREPANCY'
  if (!eligible) {
    throw domainError(409, 'AREA_TICKET_EXTERNAL_NOT_CONFIRMED',
      'Confirma el cobro en la caja antes de entregar este vale.')
  }
} else {
  // Ruta nativa: idéntica a v7, sin tocar.
  if (!ticket.order || ticket.order.paymentStatus !== 'PAID' || ticket.status !== AreaTicketStatus.PAID) {
    throw domainError(409, 'AREA_TICKET_NOT_PAID', 'Este vale todavía no está completamente pagado.')
  }
  // …y la validación de reembolso existente
}
```

Una bifurcación implícita ("si no hay orden, sáltate la validación") convertiría cualquier bug que
deje `orderId` nulo en la ruta nativa en una entrega gratis.

Al crear el `AreaTicketFulfillment`: `orderId: ticket.orderId ?? null` y
`settlementRoute: ticket.settlementRoute`. En la ruta externa **no** se toca `AreaTicket.status`
(el CHECK de Task 2 lo prohíbe): la entrega se lee de la existencia del fulfillment.

En `listPendingAreaTicketFulfillment`, la lista es la **unión**: nativos elegibles como hoy, más
externos con `externalDeliveryTracking: 'TRACKED'` y settlement elegible.

- [ ] **Step 4: Arreglar el test de aislamiento por área de la Task 9** (lo encontró su revisión)

`tests/integration/area-tickets/area-ticket-external-queue.test.ts` tiene un test que dice probar
que una terminal de otra área no ve estos vales. **No prueba eso.** Usa una terminal de área
*nativa*, cuyos vales quedan fuera por **tres** condiciones independientes a la vez:
`fulfillmentAreaId`, `settlementRoute: EXTERNAL`, y la ausencia de relación `externalSettlement`.
El test pasaría igual **si se borrara la línea de `fulfillmentAreaId` de la query** — o sea que no
vigila el predicado que dice vigilar, y es justo la regla de seguridad central del módulo.

El código que hay hoy es correcto (es una igualdad plana, verificada en revisión); lo que falla es
la prueba. El test venía prescrito así en el plan: **es un error del plan, no del implementador de
la Task 9.**

Arréglalo creando una **segunda área también `EXTERNAL`**, con su propia terminal, y afirmando que
cada terminal ve solo los vales de su área:

```typescript
it('una terminal de OTRA área externa no ve estos vales — aísla el filtro de área, no otros', async () => {
  // Ambas áreas son EXTERNAL: si la query dejara de filtrar por fulfillmentAreaId,
  // nada más excluiría estos vales y el test fallaría. Ese es el punto.
  const mio = await issueExternalTicket({ quantity: '1' })
  const ajeno = await issueExternalTicketInSecondArea({ quantity: '1' })

  const propia = await listPendingExternalConfirmation(venueId, { deviceUid: externalIssueDeviceUid })
  expect(propia.items.map((i: any) => i.id)).toContain(mio.id)
  expect(propia.items.map((i: any) => i.id)).not.toContain(ajeno.id)

  const otra = await listPendingExternalConfirmation(venueId, { deviceUid: segundaAreaDeviceUid })
  expect(otra.items.map((i: any) => i.id)).toContain(ajeno.id)
  expect(otra.items.map((i: any) => i.id)).not.toContain(mio.id)
})
```

**Verifica que el test nuevo tenga poder de verdad:** borra temporalmente la línea
`fulfillmentAreaId: terminal.fulfillmentAreaId` de la query, confirma que este test **falla**, y
restáurala. Si no falla, el test sigue sin servir y hay que rehacerlo. Documenta esa comprobación
en tu reporte — es la única forma de saber que un test de aislamiento aísla algo.

Conserva el test viejo si quieres (no estorba), pero el que cuenta es este.

- [ ] **Step 5: Correr todo**

```bash
set -a; source .env.test.local; set +a
npx jest --selectProjects integration --testPathPattern "area-ticket"   # todas
npm run build
```

- [ ] **Step 5: Commit**

```bash
git add src/services/mobile/areaTicketV7.mobile.service.ts \
        tests/integration/area-tickets/area-ticket-external-fulfillment.test.ts
git commit -m "feat(area-tickets): entrega en la ruta externa

Misma entidad y misma idempotencia; lo único que cambia es el predicado de
elegibilidad. La bifurcación es explícita POR RUTA: 'si no hay orden, sáltate
la validación' habría convertido cualquier orderId nulo accidental de la ruta
nativa en una entrega gratis.

Se entrega con DISCREPANCY —el producto ya está pagado en la otra caja—, no
con el cobro pendiente."
```

---

## Task 11: Endpoints, rutas y validación

**Files:**
- Create: `avoqado-server/src/controllers/mobile/areaTicketExternal.mobile.controller.ts`
- Create: `avoqado-server/src/schemas/mobile/areaTicketExternal.schema.ts`
- Modify: `avoqado-server/src/routes/mobile.routes.ts`
- Create: `avoqado-server/tests/api-tests/areaTicketExternal.routes.test.ts`

**Interfaces:**
- Consumes: todo el servicio de Tasks 6-9.
- Produces: las rutas que consumirá la Fase 2 (Android/iOS).

- [ ] **Step 1: Escribir el test de API que falla**

```typescript
describe('POST /mobile/venues/:venueId/area-tickets/:ticketId/external-settlement/confirm', () => {
  it('403 sin el permiso area-tickets:confirm-external', async () => {
    const res = await request(app).post(url).set(authAs('CASHIER')).send({ idempotencyKey: 'k' })
    expect(res.status).toBe(403)
  })

  it('200 con MANAGER, y el envelope es { success, data, error }', async () => {
    const res = await request(app).post(url).set(authAs('MANAGER')).send({ idempotencyKey: 'k', externalAmount: '168.00' })
    expect(res.status).toBe(200)
    expect(res.body).toMatchObject({ success: true, error: null })
    expect(res.body.data.status).toBe('CONFIRMED')
  })

  it('400 en español si externalAmount no es un decimal de dos posiciones', async () => {
    const res = await request(app).post(url).set(authAs('MANAGER')).send({ idempotencyKey: 'k', externalAmount: '168.000' })
    expect(res.status).toBe(400)
    expect(res.body.error.message).toMatch(/importe/i)
  })

  it('400 sin idempotencyKey', async () => { /* … */ })
})
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `npx jest --selectProjects api --testPathPattern "areaTicketExternal.routes"`
Expected: FAIL — 404, las rutas no existen.

- [ ] **Step 3: Zod en español**

`src/schemas/mobile/areaTicketExternal.schema.ts`:

```typescript
const decimalPesos = z.string().regex(/^-?\d+\.\d{2}$/, 'El importe debe tener dos decimales, por ejemplo "168.00".')

export const confirmExternalSettlementSchema = z.object({
  body: z.object({
    idempotencyKey: z.string().min(1, 'La llave de idempotencia es requerida.').max(64),
    externalAmount: decimalPesos.optional().nullable(),
    externalReference: z.string().max(120).optional().nullable(),
    notes: z.string().max(500).optional().nullable(),
  }),
})
```

Shape y formato aquí; las reglas de negocio (¿ya está confirmado? ¿es externo?) viven en el servicio.

- [ ] **Step 4: Controllers y rutas**

Controllers finos: extraen `authContext`, llaman al servicio, responden. Cero lógica.

En `mobile.routes.ts`, junto a las rutas v7:

```typescript
router.post('/venues/:venueId/area-tickets/:ticketId/external-settlement/handoff',
  authenticateTokenMiddleware, checkPermission('area-tickets:issue'),
  validateRequest(handoffSchema), areaTicketExternalController.handoff)

router.post('/venues/:venueId/area-tickets/:ticketId/external-settlement/confirm',
  authenticateTokenMiddleware, checkPermission('area-tickets:confirm-external'),
  validateRequest(confirmExternalSettlementSchema), areaTicketExternalController.confirm)

router.post('/venues/:venueId/area-tickets/:ticketId/external-settlement/not-charged',
  authenticateTokenMiddleware, checkPermission('area-tickets:confirm-external'),
  validateRequest(notChargedSchema), areaTicketExternalController.notCharged)

router.get('/venues/:venueId/area-tickets/pending-confirmation',
  authenticateTokenMiddleware, checkPermission('area-tickets:issue'),
  areaTicketExternalController.listPendingConfirmation)
```

⚠️ `validateRequest` va **antes** de `checkPermission` solo cuando el permiso depende del body. Aquí
no depende, así que este orden es correcto.

- [ ] **Step 5: Correr los tests**

```bash
npx jest --selectProjects api --testPathPattern "areaTicketExternal.routes"   # PASS
npm run audit:permissions                                                     # exit 0
```

- [ ] **Step 6: Commit**

```bash
git add src/controllers/mobile/areaTicketExternal.mobile.controller.ts \
        src/schemas/mobile/areaTicketExternal.schema.ts src/routes/mobile.routes.ts \
        tests/api-tests/areaTicketExternal.routes.test.ts
git commit -m "feat(area-tickets): endpoints de cobro externo

handoff, confirm, not-charged y la cola de pendientes. Zod valida forma en
español; las reglas de negocio viven en el servicio."
```

---

## Task 12: Job de conciliación

**Files:**
- Create: `avoqado-server/src/jobs/areaTicketExternalReconciliation.job.ts`
- Create: `avoqado-server/tests/unit/jobs/areaTicketExternalReconciliation.test.ts`

**Interfaces:**
- Produces: abre incidencias `UNCONFIRMED_CHARGE`.

- [ ] **Step 1: Escribir el test que falla**

```typescript
describe('Job de conciliación de cobros externos', () => {
  it('abre UNCONFIRMED_CHARGE para vales PENDING de días operativos anteriores', async () => { /* … */ })

  it('NO abre incidencia para los de hoy — el día todavía no cierra', async () => { /* … */ })

  it('no duplica: correrlo dos veces deja UNA incidencia por vale', async () => { /* … */ })

  it('usa el día operativo del venue en su timezone, no UTC', async () => {
    // Un venue en America/Mexico_City a las 20:00 locales sigue en el mismo día
    // operativo aunque en UTC ya sea mañana.
  })

  it('ignora los vales en modo ASSUME_ON_PRINT: no hay nada que confirmar', async () => { /* … */ })
})
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `npx jest --selectProjects unit --testPathPattern "areaTicketExternalReconciliation"`
Expected: FAIL.

- [ ] **Step 3: Implementar**

🔴 **Registrar con `scheduleCron('area-ticket-external-reconciliation', …)` de
`src/observability/jobContext.ts`, NUNCA con `new CronJob(...)` directo.** Hay un test que falla si
alguien lo hace (`tests/unit/jobs/jobContextGuard.test.ts`).

🔴 **La lectura de entrada va envuelta en `retry(..., shouldRetryDbConnectionError)`** — regla de
`.claude/rules/cron-jobs.md`, previene la estampida de P1001 al tope de la hora.

Fecha de corte: día operativo del venue con `venueStartOfDay(tz, …)`, nunca `new Date('YYYY-MM-DD')`.

- [ ] **Step 4: Correr los tests**

```bash
npx jest --selectProjects unit --testPathPattern "areaTicketExternalReconciliation"   # PASS
npx jest --selectProjects unit --testPathPattern "jobContextGuard"                    # PASS
```

- [ ] **Step 5: Commit**

```bash
git add src/jobs/areaTicketExternalReconciliation.job.ts \
        tests/unit/jobs/areaTicketExternalReconciliation.test.ts
git commit -m "feat(area-tickets): job de conciliación de cobros externos

Abre UNCONFIRMED_CHARGE al cerrar el día operativo del venue, en su timezone.
Idempotente por el unique (areaTicketId, kind): sin eso la cola se vuelve
ilegible en un día."
```

---

## Task 13: MCP

**Files:**
- Modify: `avoqado-server/src/mcp/tools/areaTickets.ts`
- Modify: `avoqado-server/tests/unit/mcp-customer/area-tickets.test.ts`

**Interfaces:**
- Consumes: todo lo anterior.

**Por qué no es opcional:** regla dura del repo — una capacidad que el MCP no alcanza está
incompleta.

- [ ] **Step 1: Escribir el test que falla**

```typescript
describe('MCP — vales con ruta externa', () => {
  it('area_ticket_status expone la ruta y el estado del cobro externo', async () => {
    const r = await callTool('area_ticket_status', { venueId, code })
    expect(r.settlementRoute).toBe('EXTERNAL')
    expect(r.externalSettlement.status).toBe('PENDING')
  })

  it('los importes salen en PESOS, no en centavos', async () => {
    const r = await callTool('area_ticket_status', { venueId, code })
    expect(r.externalSettlement.referenceAmount).toBe(168.0)   // NO 16800
  })

  it('area_ticket_reconciliation_queue incluye las incidencias externas', async () => { /* … */ })

  it('pending_area_ticket_deliveries incluye los externos elegibles', async () => { /* … */ })
})
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `npx jest --selectProjects unit --testPathPattern "mcp-customer/area-tickets"`
Expected: FAIL.

- [ ] **Step 3: Implementar**

Solo **lectura** en esta fase. Un write de confirmación por MCP exigiría confirm-gate de dos pasos
con preview `current → new` (es una afirmación sobre dinero) — no se agrega hasta que alguien lo
pida.

- [ ] **Step 4: Correr los tests**

```bash
npx jest --selectProjects unit --testPathPattern "mcp-customer"
```

- [ ] **Step 5: Commit**

```bash
git add src/mcp/tools/areaTickets.ts tests/unit/mcp-customer/area-tickets.test.ts
git commit -m "feat(mcp): ruta externa y cobro externo en los tools de vales

Solo lectura. Importes en pesos 1:1."
```

---

## Task 14: Dashboard — configurar la ruta del área

**Files:**
- Create: `avoqado-web-dashboard/src/pages/Settings/components/ExternalRouteAreaCard.tsx`
- Modify: `avoqado-web-dashboard/src/pages/Settings/AreaTickets.tsx`
- Modify: `avoqado-web-dashboard/src/services/areaTickets.service.ts`
- Modify: `avoqado-server/src/services/dashboard/areaTicket.dashboard.service.ts` (+ controller, ruta, schema)
- Test: `avoqado-web-dashboard/src/services/__tests__/areaTickets.service.test.ts`

**Interfaces:**
- Produces: el switch canónico. **Las apps lo leen; no lo escriben.**

- [ ] **Step 1: Escribir el test del servicio del dashboard**

```typescript
describe('areaTickets.service — ruta de cobro', () => {
  it('updateAreaSettlementRoute manda las cuatro políticas juntas', async () => {
    await updateAreaSettlementRoute(venueId, areaId, {
      settlementRoute: 'EXTERNAL',
      externalConfirmationMode: 'MANUAL',
      externalOfflinePolicy: 'BLOCK',
      externalDeliveryTracking: 'TRACKED',
    })
    expect(api.patch).toHaveBeenCalledWith(
      `/dashboard/venues/${venueId}/fulfillment-areas/${areaId}/settlement-route`,
      expect.objectContaining({ settlementRoute: 'EXTERNAL' }),
    )
  })
})
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `cd avoqado-web-dashboard && npx vitest run src/services/__tests__/areaTickets.service.test.ts`
Expected: FAIL.

- [ ] **Step 3: Endpoint del dashboard**

`PATCH /dashboard/venues/:venueId/fulfillment-areas/:areaId/settlement-route`, con
`checkPermission('area-tickets:configure')`, Zod en español y `ActivityLog`
(`AREA_SETTLEMENT_ROUTE_CHANGED`, con `{ from, to }`).

- [ ] **Step 4: El componente**

`ExternalRouteAreaCard.tsx`, con los patrones que ya use `AreaTickets.tsx` (míralo antes de escribir;
sigue sus componentes, no inventes otros):

- Switch "Cobrar en caja externa", **apagado por default**.
- Al encenderlo, un diálogo de confirmación que dice en una línea qué cambia: *"Los vales de esta
  área se cobrarán en otra caja. Avoqado no registrará esas ventas ni emitirá factura por ellas."*
- Con el switch encendido, las tres políticas, cada una con su explicación de una línea.
- Estado apagado **visible y explicado**, nunca ausente.

- [ ] **Step 5: Correr los tests**

```bash
cd avoqado-web-dashboard && npx vitest run src/services/__tests__/areaTickets.service.test.ts && npm run build
cd ../avoqado-server && npm run build
```

- [ ] **Step 6: Commit**

```bash
cd avoqado-web-dashboard
git add src/pages/Settings/AreaTickets.tsx src/pages/Settings/components/ExternalRouteAreaCard.tsx \
        src/services/areaTickets.service.ts src/services/__tests__/areaTickets.service.test.ts
git commit -m "feat(area-tickets): configurar la ruta de cobro del área

Switch canónico en el dashboard, apagado por default, con diálogo que dice
qué cambia: Avoqado deja de registrar esas ventas."
```

---

## Task 15: Dashboard — cobros por confirmar e incidencias

**Files:**
- Create: `avoqado-web-dashboard/src/pages/AreaTickets/ExternalSettlements.tsx`
- Modify: `avoqado-web-dashboard/src/services/areaTickets.service.ts`
- Modify: `avoqado-server/src/services/dashboard/areaTicket.dashboard.service.ts` (+ controller/ruta)
- Test: `avoqado-web-dashboard/src/pages/AreaTickets/__tests__/ExternalSettlements.test.tsx`

- [ ] **Step 1: Escribir el test que falla**

```typescript
describe('ExternalSettlements', () => {
  it('muestra el importe como "Importe de referencia", nunca como "Total pagado"', async () => {
    render(<ExternalSettlements />)
    expect(await screen.findByText(/importe de referencia/i)).toBeInTheDocument()
    expect(screen.queryByText(/pagado/i)).not.toBeInTheDocument()
  })

  it('muestra la variación con signo cuando hay discrepancia', async () => { /* … */ })

  it('el estado vacío dice qué significa, no solo "sin datos"', async () => { /* … */ })
})
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `npx vitest run src/pages/AreaTickets/__tests__/ExternalSettlements.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Endpoints de lectura + página**

`GET …/external-settlements` y `GET …/external-incidents`, ambos con
`checkPermission('area-tickets:configure')`, filtros por área/fecha/tipo y paginación por cursor.

Dos pestañas: **Cobros por confirmar** e **Incidencias**. Los importes de referencia van en su propia
sección, **nunca sumados a las ventas de Avoqado**.

- [ ] **Step 4: Correr los tests**

```bash
npx vitest run src/pages/AreaTickets && npm run build
```

- [ ] **Step 5: Commit**

```bash
git add src/pages/AreaTickets/ src/services/areaTickets.service.ts
git commit -m "feat(area-tickets): colas de cobros por confirmar e incidencias

Los importes van etiquetados como referencia y en su propia sección: nunca
sumados a las ventas de Avoqado, que es dinero que sí entró."
```

---

## Task 15b: MCP — la cola de cobros por confirmar

**Files:**
- Modify: `avoqado-server/src/mcp/tools/areaTickets.ts`
- Modify: `avoqado-server/tests/unit/mcp-customer/area-tickets.test.ts`

**Interfaces:**
- Consumes: `listPendingExternalConfirmation` (Task 9), `AreaTicketExternalSettlement` (Task 1).
- Produces: un tool de lectura que lista los cobros externos pendientes de confirmar.

**Por qué existe:** la encontró la revisión de Task 15. La regla del repo es dura — *"una capacidad
que existe pero no es alcanzable por el MCP está incompleta"*. Task 9 construyó la cola de cobros
por confirmar, Task 15 le dio pantalla en el dashboard, y **el MCP nunca la expuso**. Verificado por
el controlador: `area_ticket_reconciliation_queue` sí trae `externalIncidents` (lo añadió Task 13),
y `area_ticket_status` devuelve el settlement de **un** vale por su código — pero no hay forma de
**listar** los pendientes. El operador puede preguntar "¿cómo va este vale?" y no "¿qué cobros me
faltan confirmar?", que es justo la pregunta que motiva la pantalla.

- [ ] **Step 1: Escribir el test que falla**

En `tests/unit/mcp-customer/area-tickets.test.ts`:

```typescript
describe('pending_external_confirmations', () => {
  it('lista los cobros externos en PENDING, con su importe de referencia en pesos', async () => {
    const r = await callTool('pending_external_confirmations', { venueId })
    expect(r.items[0]).toMatchObject({
      code: expect.any(String),
      referenceAmount: '168.00',        // decimal string, dos posiciones — nunca centavos
      confirmationMode: 'MANUAL',
    })
  })

  it('excluye los ASSUMED — el venue se excluyó de confirmar por diseño', async () => {
    // mismo criterio que listPendingExternalConfirmation (Task 9)
  })

  it('excluye confirmados, no-cobrados y cancelados', async () => { /* … */ })

  it('filtra por los venues del scope, no por todos', async () => { /* … */ })
})
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `npx jest --selectProjects unit --testPathPattern "mcp-customer/area-tickets"`
Expected: FAIL — el tool no existe.

- [ ] **Step 3: Implementar**

Añade el tool en `registerAreaTicketTools`, siguiendo la forma exacta de sus tres vecinos
(`area_ticket_status:14`, `pending_area_ticket_deliveries:147`,
`area_ticket_reconciliation_queue:248`). Requisitos:

- **Solo lectura.** Nada de confirmar desde el MCP: eso es una afirmación sobre dinero disparada
  por un modelo interpretando lenguaje natural, y exigiría confirm-gate de dos pasos.
- **Importes en pesos**, con el helper `decimal()` del archivo — nunca centavos.
- **Mismo criterio de elegibilidad que `listPendingExternalConfirmation`** (Task 9): ruta EXTERNAL,
  vale `ISSUED`, settlement `PENDING`. **Ábrela y compárala campo por campo** — la revisión de
  Task 13 encontró un espejo a medias con un comentario que prometía paridad completa, y ese fue
  el defecto, no el hueco.
- **Permiso**: el mismo que gobierna la cola en el dashboard (`area-tickets:configure`).
- Descripción del tool en inglés, como sus vecinos, explicando que Avoqado nunca vio ese dinero.

- [ ] **Step 4: Correr los tests**

```bash
npx jest --selectProjects unit --testPathPattern "mcp-customer"
npm run build
```

- [ ] **Step 5: Commit**

```bash
git add src/mcp/tools/areaTickets.ts tests/unit/mcp-customer/area-tickets.test.ts
git commit -m "feat(mcp): listar los cobros externos pendientes de confirmar

Task 9 construyó la cola y Task 15 le dio pantalla, pero el MCP solo permitía
consultar un vale por su código — no listar los pendientes. Un operador podía
preguntar '¿cómo va este vale?' pero no '¿qué cobros me faltan confirmar?',
que es la pregunta que motiva toda la pantalla.

La regla del repo: una capacidad que el MCP no alcanza está incompleta.

Encontrado por la revisión de Task 15."
```

---

## Task 16: Verificación de fase completa

**Files:** ninguno nuevo — es el gate de salida.

- [ ] **Step 1: Suite completa del server**

```bash
cd avoqado-server
npm run build
TEST_DATABASE_URL=… npm test
npm run pre-deploy
```

Los tres en verde. **La máquina está compartida con otras sesiones de IA: si va lenta, sube el
timeout y espera — no reportes "listo" con la suite sin correr.**

- [ ] **Step 2: Suite del dashboard**

```bash
cd avoqado-web-dashboard && npm run build && npx vitest run
```

- [ ] **Step 3: Aislamiento — la prueba que de verdad importa**

Con la ruta externa apagada en todas las áreas, el POS nativo tiene que comportarse **idéntico**:

```bash
cd avoqado-server
TEST_DATABASE_URL=… npx jest --selectProjects integration --testPathPattern "area-ticket-v7"
```

Cero diferencias. Si algo de v7 cambió, es un bug de este trabajo.

- [ ] **Step 4: Auditorías del repo**

```bash
npm run audit:permissions        # exit 0
npm run schema:map               # sin diff pendiente en docs/SCHEMA_MAP.md
git status --short               # solo TUS rutas; el WIP ajeno se deja en paz
```

- [ ] **Step 5: Revisar el log del backend**

Levanta el server, ejerce el flujo externo completo (emitir → handoff → confirmar → entregar) y lee:

```bash
LOG=$(ls -t logs/development*.log | head -1)
grep "entrypoint: 'POST /api/v1/mobile/venues/:id/area-tickets" "$LOG"
grep -i "error" "$LOG" | tail -30
```

Un 200 en la respuesta con un `error:` en el log es un bug escondiéndose. Detecta el archivo activo
por `mtime`, no por número — winston rota y el `tail` se queda mudo sin avisar.

- [ ] **Step 6: Reporte**

Qué quedó funcionando, qué NO se probó (impresión real, apps, códigos externos — todo eso es Fase 2
y 3), y el estado de R2.

---

## Self-Review

**Cobertura del spec por esta fase:**

| Sección del spec | Tarea | Estado |
|---|---|---|
| §5 dos rutas | T1, T2 | ✅ |
| §6.1 emisión externa | T4 | ✅ (sin códigos externos — Fase 3) |
| §6.3 confirmación | T7, T8 | ✅ |
| §6.4 entrega | T10 | ✅ |
| §7 seis ejes | T1, T4, T6, T7, T10 | ✅ |
| §8 perfil y mapeos | — | ⏭️ Fase 3 (bloqueado por R2) |
| §9 códigos | — | ⏭️ Fase 3 |
| §10 folios offline | — | ⏭️ Fase 4 |
| §11 inventario | T4, T5 | ✅ |
| §12 autoridad monetaria | T4, T7, T12 | ✅ |
| §13 entrega | T10 | ✅ |
| §14 modelo | T1, T2 | ✅ |
| §15 API | T11 | ✅ (los de perfil son Fase 3) |
| §16 impresión | — | ⏭️ Fase 2 (vive en las apps) |
| §17 tier/permisos/MCP | T3, T13 | ✅ |
| §18 errores | T4–T11 | ✅ los de esta fase |
| §19 dashboard | T14, T15 | ✅ (Android/iOS = Fase 2) |
| §20 observabilidad | T7, T8, T12 | ⚠️ ActivityLog sí; los eventos con nombre de §20 quedan como telemetría de Fase 2 |
| §21 pruebas | T16 | ✅ las que no piden hardware |

**Consistencia de tipos:** `ExternalSettlementInput` (T6) es la base que extienden
`ConfirmExternalSettlementInput` (T7) y el input de `markExternalNotCharged` (T8). `settlementRoute`
se llama igual en `FulfillmentArea`, `AreaTicket` y `AreaTicketFulfillment`. Los importes cruzan la
API siempre como decimal string de dos posiciones y salen del MCP como número en pesos.

**Dependencia hacia atrás detectada y resuelta:** el cuarto caso de T5 usa
`confirmExternalSettlement`, que nace en T7 — queda escrito en `it.skip` y se reactiva ahí.

---

## Execution Handoff

Plan guardado. Dos formas de ejecutarlo:

**1. Subagent-Driven (recomendado)** — un subagente fresco por tarea, con revisión entre tareas.
Encaja bien aquí: 16 tareas con fronteras nítidas y test propio, y ningún subagente necesita cargar
el contexto de los otros.

**2. Inline** — ejecución en esta sesión con checkpoints por lotes.

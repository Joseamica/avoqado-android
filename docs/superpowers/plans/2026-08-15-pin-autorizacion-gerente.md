# PIN de autorización de gerente (manager override) — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cuando un rol sin permiso intenta una acción, el POS abre un teclado de PIN para que alguien **con** ese permiso la autorice **una sola vez**, sin dejar la terminal elevada.

**Architecture:** Un único punto de integración: el 403 de `checkPermission` gana un campo aditivo `overridable`; un endpoint nuevo cambia PIN + permiso por un **token de un uso, 60 s, atado a ese permiso y ese venue**; el mismo `checkPermission` consume el token del header `X-Permission-Override` y deja pasar. Cero cambios por-acción: cubre las ~200 rutas con `checkPermission` de hoy y las futuras. Los clientes (Android/iOS) interceptan el 403 `overridable`, piden el PIN y **reintentan la request original** con el header, así el flujo del ViewModel original no cambia.

**Tech Stack:** avoqado-server (Express + TypeScript + Prisma/PostgreSQL, Jest) · avoqado-web-dashboard (React 18 + Vite + TanStack Query + Vitest) · avoqado-android (Kotlin + Compose + Hilt + OkHttp, JUnit4/MockK/MockWebServer) · avoqado-ios (SwiftUI + XCTest)

**Spec:** `/Users/amieva/Documents/Programming/Avoqado/avoqado-android/docs/superpowers/specs/2026-08-15-pin-autorizacion-gerente-design.md`

---

## Global Constraints

Estas reglas aplican a **todas** las tareas. Los requisitos de cada tarea las incluyen implícitamente.

1. **Commits atómicos, por rutas explícitas.** `git add <ruta> <ruta>` — **JAMÁS** `git add -A` ni `git add .`. Otras sesiones de IA editan este workspace en paralelo; un `-A` se lleva su trabajo. Si aun así se cuela WIP ajeno, **no lo reviertas** — dilo en el reporte.
2. **Nunca** `git reset --hard`, `git checkout .`, `git clean`, `git stash`, ni cambiar de rama "para dejar limpio".
3. **Cada commit va precedido por su verificación.** En `avoqado-server` esto toca **permisos** → **TDD estricto**: test primero, verlo fallar, implementar, verlo pasar, suite del módulo verde, y sólo entonces commit. En las apps: el proyecto tocado **compila** antes de cada commit.
4. **Cualquier edición a `prisma/schema.prisma` (incluidos comentarios) obliga a `npm run schema:map` y a commitear `docs/SCHEMA_MAP.md` en el MISMO commit.** Un modelo nuevo necesita antes su entrada en `scripts/generate-schema-map.ts` → `MODEL_TO_DOMAIN`, o el script sale con código distinto de 0.
5. **Migraciones de verdad**, nunca `db push`: `npx prisma migrate dev --name <nombre>`.
6. **Campos de API sólo aditivos.** `overridable`, `managerPinOverrideEnabled` y `authorizedBy` se AGREGAN; nunca se renombra ni se quita `error`, `message`, `required`, `userRole`. Apps viejas los leen.
7. **Paridad Android ↔ iOS en el MISMO trabajo.** Mismos textos en español, mismos nombres de campo, misma semántica. Los códigos de permiso se replican por **nombre EXACTO**: `orders:merge`.
8. **Formato del server:** después de editar TS corre `npm run format && npm run lint:fix`.
9. **Mensajes de commit en español**, estilo `feat(...)`, `fix(...)`, `test(...)`, `docs(...)`, y terminados con:
   ```
   Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
   ```
10. **Máquina compartida.** Antes de un build pesado (`./gradlew assemble*`, `xcodebuild`, suite completa) corre el chequeo de capacidad y **nunca dos builds pesados propios a la vez**:
    ```bash
    sysctl -n hw.ncpu vm.loadavg && sysctl -n vm.swapusage
    pgrep -fl "GradleDaemon|KotlinCompileDaemon|xcodebuild|jest|vitest|tsc" | head
    ```
    Si la máquina está saturada **se corre igual** y se avisa que tardará. Un typecheck/lint/un test suelto NO pasa por el chequeo: se corre siempre.
11. **Orden de deploy: server primero.** Este plan **no** incluye deploy a producción — termina en verificación local + hardware.
12. **Decisiones cerradas que NO se re-litigan:** el PIN vive en texto plano a propósito (founder 2026-08-15, `decision_pin_sin_hashear.md`); el override es "una acción, una vez"; el tier es core (sin candado de plan); el switch nace OFF; `orders:merge` nace restringido.

---

## File Structure

### avoqado-server
| Archivo | Responsabilidad |
|---|---|
| `prisma/schema.prisma` | **Modificar** — modelo `PermissionOverride` (token + auditoría en una tabla) y `VenueSettings.managerPinOverrideEnabled` |
| `scripts/generate-schema-map.ts` | **Modificar** — `PermissionOverride` en `MODEL_TO_DOMAIN` |
| `docs/SCHEMA_MAP.md` | **Regenerado** por `npm run schema:map` |
| `src/services/mobile/permission-override.mobile.service.ts` | **Crear** — crear token (valida PIN + permiso efectivo), consumir token (update atómico), leer el switch del venue |
| `src/controllers/mobile/permission-override.mobile.controller.ts` | **Crear** — HTTP fino: 201 / 401 / 403 `OVERRIDE_INSUFFICIENT` |
| `src/schemas/mobile/permissionOverride.mobile.schema.ts` | **Crear** — zod con mensajes en español |
| `src/routes/mobile.routes.ts` | **Modificar** — ruta nueva; `orders:merge` en la ruta de merge |
| `src/middlewares/checkPermission.middleware.ts` | **Modificar** — `overridable` en el 403 + consumo del header + `ActivityLog` |
| `src/lib/permissions.ts` | **Modificar** — `orders:merge` en catálogo, dependencias y defaults |
| `src/schemas/dashboard/superadmin-staff.schema.ts` | **Modificar** — PIN 4-6 → 4-10 (3 sitios) |
| `src/schemas/dashboard/venueSettings.schema.ts` | **Modificar** — `managerPinOverrideEnabled` en el body del PUT |
| `src/controllers/mobile/tpvSettings.mobile.controller.ts` | **Modificar** — expone `managerPinOverrideEnabled` |
| `src/mcp/tools/activity-log.ts` | **Modificar** — la descripción del tool nombra las acciones del override |
| `tests/unit/**` | **Crear** — 5 archivos de test (ver tareas) |

### avoqado-web-dashboard
| Archivo | Responsabilidad |
|---|---|
| `src/pages/Venue/Edit/components/ManagerPinOverrideSetting.tsx` | **Crear** — el switch canónico (copia de `CashReconciliationSetting`, sin gate de tier) |
| `src/pages/Venue/Edit/components/ManagerPinOverrideSetting.test.tsx` | **Crear** |
| `src/pages/Venue/Edit/BasicInfo.tsx` | **Modificar** — lo monta |
| `src/types.ts` | **Modificar** — `VenueSettings.managerPinOverrideEnabled` |
| `src/locales/{es,en,fr}/venue.json` | **Modificar** — bloque `edit.managerPinOverride` |
| `src/lib/permissions/defaultPermissions.ts` | **Modificar** — `orders:merge` en el grid |
| `src/lib/permissions/permissionDependencies.ts` | **Modificar** — dependencias de `orders:merge` |
| `src/locales/{es,en}/settings.json` | **Modificar** — etiquetas del permiso |

### avoqado-android
| Archivo | Responsabilidad |
|---|---|
| `.../core/data/network/ForbiddenInterceptor.kt` | **Modificar** — `overridable` + rama de override (bloquea el hilo de red y reintenta) |
| `.../core/data/network/ManagerOverrideCoordinator.kt` | **Crear** — un sheet a la vez, `awaitToken` bloqueante, `submitPin`/`cancel` |
| `.../core/data/network/PermissionOverrideRepository.kt` | **Crear** — POST al endpoint con su **propio** `OkHttpClient()` (evita el ciclo Hilt) |
| `.../core/domain/PermissionLabels.kt` | **Crear** — permiso → texto en español |
| `.../designsystem/components/ManagerOverrideSheet.kt` | **Crear** — UI del PIN sobre `AvoqadoDialog` + `PinPadView` |
| `.../core/di/NetworkModule.kt` | **Modificar** — inyecta el coordinator al interceptor |
| `.../navigation/AvoqadoNavGraph.kt` | **Modificar** — monta el sheet junto al diálogo de 403 |
| `.../tpvsettings/data/TpvSettingsRepository.kt` | **Modificar** — lee y persiste `managerPinOverrideEnabled` |
| `.../core/domain/RoleManager.kt` | **Modificar** — `ActionVisibility` (ALLOWED / LOCKED / HIDDEN) |
| `.../transactions/presentation/TransactionDetailSheet.kt` | **Modificar** — primer candado visible (reembolso) |
| `app/src/test/java/com/avoqado/pos/**` | **Crear/Modificar** — 3 archivos de test |

### avoqado-ios
| Archivo | Responsabilidad |
|---|---|
| `avoqado-ios/Services/APIClient.swift` | **Modificar** — 403 `overridable` → pide token y reintenta |
| `avoqado-ios/Services/ManagerOverrideCoordinator.swift` | **Crear** — espejo del de Android (async, sin bloquear) |
| `avoqado-ios/Services/PermissionLabels.swift` | **Crear** — mismo mapa que Android |
| `avoqado-ios/Components/ManagerOverrideSheet.swift` | **Crear** — UI del PIN sobre `AvoqadoDialog` |
| `avoqado-ios/POS/Views/MainTabView.swift` | **Modificar** — monta el host del sheet |
| `avoqado-ios/Services/TpvSettingsRepository.swift` | **Modificar** — `managerPinOverrideEnabled` |
| `avoqado-ios/Services/RoleManager.swift` | **Modificar** — `ActionVisibility` |
| `avoqado-ios/Transactions/Views/TransactionDetailView.swift` | **Modificar** — candado visible (reembolso) |
| `avoqado-iosTests/**` | **Crear** — 2 archivos de test |

---

## Resumen de fases

| Fase | Repo | Tareas |
|---|---|---|
| 1 | avoqado-server | 1–8 |
| 2 | avoqado-web-dashboard | 9–10 |
| 3 | avoqado-android | 11–14 |
| 4 | avoqado-ios | 15–18 |
| 5 | MCP + docs + presentación | 19–20 |
| 6 | Verificación en hardware (`/full-testing`) | 21 |

---

# FASE 1 — avoqado-server (TDD estricto: toca permisos)

### Tarea 1: Unificar el PIN a 4-10 dígitos en el camino superadmin

**Por qué primero:** el PIN en texto plano es una decisión cerrada; su única defensa es que pueda ser LARGO. Hoy el camino superadmin lo capa a 6 dígitos, así que un gerente con PIN de 10 no se puede dar de alta por ahí. Sin esto, la premisa de seguridad del override es falsa.

**Files:**
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/tests/unit/schemas/superadmin-staff.schema.test.ts`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/src/schemas/dashboard/superadmin-staff.schema.ts:63-66, 111-114, 129-133`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/prisma/schema.prisma:1113` (sólo el comentario)
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/src/middlewares/pin-login-rate-limit.middleware.ts:12` (sólo el comentario)

**Interfaces:**
- Consumes: nada.
- Produces: `createStaffSchema`, `assignVenueSchema`, `updateVenueAssignmentSchema` aceptan `/^\d{4,10}$/` en `body.pin`. Ningún consumidor cambia de firma.

- [ ] **Step 1: Escribe el test que falla**

Crea `tests/unit/schemas/superadmin-staff.schema.test.ts`:

```typescript
import { createStaffSchema, assignVenueSchema, updateVenueAssignmentSchema } from '@/schemas/dashboard/superadmin-staff.schema'

const VENUE_ID = 'clzzzzzzzzzzzzzzzzzzzzzzz'
const STAFF_ID = 'clyyyyyyyyyyyyyyyyyyyyyyy'

describe('superadmin staff schemas — PIN de 4 a 10 dígitos', () => {
  // 1. NUEVO: el camino superadmin debe aceptar PINs largos
  it('createStaffSchema acepta un PIN de 10 dígitos', () => {
    const result = createStaffSchema.shape.body.shape.pin.safeParse('1234567890')
    expect(result.success).toBe(true)
  })

  it('assignVenueSchema acepta un PIN de 10 dígitos', () => {
    const result = assignVenueSchema.shape.body.shape.pin.safeParse('1234567890')
    expect(result.success).toBe(true)
  })

  it('updateVenueAssignmentSchema acepta un PIN de 10 dígitos', () => {
    const result = updateVenueAssignmentSchema.shape.body.shape.pin.safeParse('1234567890')
    expect(result.success).toBe(true)
  })

  it('updateVenueAssignmentSchema sigue aceptando null (borrar el PIN)', () => {
    const result = updateVenueAssignmentSchema.shape.body.shape.pin.safeParse(null)
    expect(result.success).toBe(true)
  })

  // 2. REGRESIÓN: lo que ya funcionaba sigue igual
  it('sigue aceptando el PIN de 4 dígitos de siempre', () => {
    expect(createStaffSchema.shape.body.shape.pin.safeParse('1234').success).toBe(true)
    expect(assignVenueSchema.shape.body.shape.pin.safeParse('1234').success).toBe(true)
  })

  it('sigue rechazando 3 dígitos, 11 dígitos y letras', () => {
    expect(createStaffSchema.shape.body.shape.pin.safeParse('123').success).toBe(false)
    expect(createStaffSchema.shape.body.shape.pin.safeParse('12345678901').success).toBe(false)
    expect(createStaffSchema.shape.body.shape.pin.safeParse('12a4').success).toBe(false)
  })

  it('el resto del body de createStaffSchema no cambió', () => {
    const parsed = createStaffSchema.safeParse({
      body: {
        firstName: 'Ana',
        lastName: 'Ruiz',
        email: 'ana@example.com',
        organizationId: VENUE_ID,
        orgRole: 'MEMBER',
        venueId: VENUE_ID,
        venueRole: 'MANAGER',
        pin: '1234567890',
      },
      params: {},
      query: {},
    })
    expect(parsed.success).toBe(true)
  })

  it('updateVenueAssignmentSchema exige un staffId cuid en params', () => {
    const parsed = updateVenueAssignmentSchema.safeParse({
      params: { staffId: 'no-es-cuid', venueId: VENUE_ID },
      body: { pin: '1234567890' },
      query: {},
    })
    expect(parsed.success).toBe(false)
  })

  it('updateVenueAssignmentSchema acepta un params válido', () => {
    const parsed = updateVenueAssignmentSchema.safeParse({
      params: { staffId: STAFF_ID, venueId: VENUE_ID },
      body: { pin: '1234567890' },
      query: {},
    })
    expect(parsed.success).toBe(true)
  })
})
```

> Si `createStaffSchema.safeParse` falla por campos del body que este plan no conoce (el schema tiene más llaves), ajusta ese objeto a los campos reales del archivo — los 7 tests que atacan `shape.body.shape.pin` directamente son los que importan y no dependen del resto del body.

- [ ] **Step 2: Córrelo y verifica que FALLA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/schemas/superadmin-staff.schema.test.ts
```
Esperado: FALLAN los 3 tests de "acepta un PIN de 10 dígitos" y el de 11 dígitos pasa por la razón equivocada. Mensaje: `El PIN debe tener entre 4 y 6 dígitos`.

- [ ] **Step 3: Cambia los tres regex**

En `src/schemas/dashboard/superadmin-staff.schema.ts`, **los tres sitios** (dentro de `createStaffSchema`, `assignVenueSchema` y `updateVenueAssignmentSchema`) pasan de:

```typescript
    pin: z
      .string()
      .regex(/^\d{4,6}$/, 'El PIN debe tener entre 4 y 6 dígitos')
```

a:

```typescript
    // 4-10 dígitos: mismo rango que tpv.schema.ts e invitation.schema.ts.
    // El PIN se guarda en claro a propósito (decisión founder 2026-08-15), así
    // que su única defensa es poder ser LARGO — caparlo a 6 aquí dejaba fuera
    // del alta por superadmin justo a los PINs que sostienen esa premisa.
    pin: z
      .string()
      .regex(/^\d{4,10}$/, 'El PIN debe tener entre 4 y 10 dígitos')
```

(en `updateVenueAssignmentSchema` conserva el `.optional().nullable()` que ya tiene; en los otros dos, el `.optional()`).

- [ ] **Step 4: Corrige los comentarios viejos**

`prisma/schema.prisma:1113`:
```prisma
  pin String? // 4-10 dígitos, PIN de acceso al POS por sucursal. Texto plano a propósito (decisión founder 2026-08-15).
```

`src/middlewares/pin-login-rate-limit.middleware.ts:12`:
```typescript
 * - Prevents brute force PIN enumeration (4-10 digit PINs = 10k-10B combinations)
```

- [ ] **Step 5: Córrelo y verifica que PASA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/schemas/superadmin-staff.schema.test.ts
```
Esperado: PASS (todos).

- [ ] **Step 6: Suite del módulo + schema map + formato**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/schemas
npm run schema:map          # obligatorio: se editó schema.prisma (aunque sea un comentario)
npm run format && npm run lint:fix
git status --short docs/SCHEMA_MAP.md
```
Esperado: suite verde. `docs/SCHEMA_MAP.md` puede o no cambiar; si cambia, entra en el commit.

- [ ] **Step 7: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
git add tests/unit/schemas/superadmin-staff.schema.test.ts \
        src/schemas/dashboard/superadmin-staff.schema.ts \
        src/middlewares/pin-login-rate-limit.middleware.ts \
        prisma/schema.prisma docs/SCHEMA_MAP.md
git commit -m "$(cat <<'EOF'
fix(pin): el alta por superadmin acepta PINs de 4 a 10 dígitos

Había dos reglas vivas: 4-10 en tpv/invitation y 4-6 en superadmin. Como el
PIN se guarda en claro a propósito, su única defensa es poder ser largo — y el
camino de superadmin era justo el que lo impedía.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 2: `orders:merge` — permiso propio, restringido desde el día uno

**Por qué:** hoy `orders:update` traga 10 acciones distintas (`mobile.routes.ts`: move 1756, assign 1770, split 2015, **merge 2028**, split-by-seat 2041, discounts 2050/2063, comp 2072, service-charges 2105/2117). v1 separa **sólo merge** — decisión del founder, divergencia deliberada de Square, que no lo separa. 🔴 **Consecuencia operativa:** un mesero que hoy junta mesas dejará de poder (quedará a un PIN de distancia). Hay que avisar a los venues **antes** de liberar las apps (paso explícito en la Fase 6).

**Files:**
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/tests/unit/lib/permissions.orders-merge.test.ts`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/src/lib/permissions.ts` (dependencias ~línea 72, defaults de MANAGER ~línea 736, catálogo `INDIVIDUAL_PERMISSIONS_BY_RESOURCE.orders` línea 1498)
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/src/routes/mobile.routes.ts:2028` (la ruta de merge)

**Interfaces:**
- Consumes: nada de tareas previas.
- Produces: la string exacta **`orders:merge`**, que Android, iOS y el dashboard replican por nombre EXACTO. `hasPermission(role, custom, 'orders:merge')` → `true` para MANAGER (literal), ADMIN/OWNER (por el wildcard `orders:*` que ya tienen) y SUPERADMIN (`*:*`); `false` para WAITER/CASHIER/HOST/KITCHEN/VIEWER.

- [ ] **Step 1: Escribe el test que falla**

Crea `tests/unit/lib/permissions.orders-merge.test.ts`:

```typescript
import { StaffRole } from '@prisma/client'
import {
  DEFAULT_PERMISSIONS,
  INDIVIDUAL_PERMISSIONS_BY_RESOURCE,
  PERMISSION_DEPENDENCIES,
  hasPermission,
} from '@/lib/permissions'

describe("permiso 'orders:merge'", () => {
  // 1. NUEVO
  it('está en el catálogo del recurso orders (asignable desde el editor de roles)', () => {
    expect(INDIVIDUAL_PERMISSIONS_BY_RESOURCE.orders).toContain('orders:merge')
  })

  it('declara sus dependencias implícitas', () => {
    expect(PERMISSION_DEPENDENCIES['orders:merge']).toEqual(
      expect.arrayContaining(['orders:read', 'orders:update', 'orders:merge']),
    )
  })

  it('MANAGER lo trae por default, explícito', () => {
    expect(DEFAULT_PERMISSIONS[StaffRole.MANAGER]).toContain('orders:merge')
    expect(hasPermission(StaffRole.MANAGER, null, 'orders:merge')).toBe(true)
  })

  it('ADMIN y OWNER lo traen por el wildcard orders:*', () => {
    expect(hasPermission(StaffRole.ADMIN, null, 'orders:merge')).toBe(true)
    expect(hasPermission(StaffRole.OWNER, null, 'orders:merge')).toBe(true)
  })

  it('SUPERADMIN lo trae por *:*', () => {
    expect(hasPermission(StaffRole.SUPERADMIN, null, 'orders:merge')).toBe(true)
  })

  it('🔴 WAITER y CASHIER NO lo traen — restringido desde el día uno', () => {
    expect(hasPermission(StaffRole.WAITER, null, 'orders:merge')).toBe(false)
    expect(hasPermission(StaffRole.CASHIER, null, 'orders:merge')).toBe(false)
  })

  it('HOST, KITCHEN y VIEWER tampoco', () => {
    expect(hasPermission(StaffRole.HOST, null, 'orders:merge')).toBe(false)
    expect(hasPermission(StaffRole.KITCHEN, null, 'orders:merge')).toBe(false)
    expect(hasPermission(StaffRole.VIEWER, null, 'orders:merge')).toBe(false)
  })

  // 2. REGRESIÓN: las otras 9 acciones de orders:update NO se movieron
  it('WAITER conserva orders:update — sólo merge se separó en v1', () => {
    expect(hasPermission(StaffRole.WAITER, null, 'orders:update')).toBe(true)
  })

  it('el resto del catálogo de orders sigue completo', () => {
    expect(INDIVIDUAL_PERMISSIONS_BY_RESOURCE.orders).toEqual(
      expect.arrayContaining(['orders:read', 'orders:create', 'orders:update', 'orders:cancel', 'orders:comp', 'orders:void']),
    )
  })
})
```

- [ ] **Step 2: Córrelo y verifica que FALLA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/lib/permissions.orders-merge.test.ts
```
Esperado: FAIL — el catálogo no contiene `orders:merge`, `PERMISSION_DEPENDENCIES['orders:merge']` es `undefined`, y `hasPermission(MANAGER, ...)` devuelve `false`.

- [ ] **Step 3: Agrega el permiso en los tres lugares**

**a)** `src/lib/permissions.ts`, en `PERMISSION_DEPENDENCIES`, justo después del bloque `'orders:cancel'` (~línea 72):

```typescript
  // "Fusionar cuentas": permiso propio desde el día uno (divergencia deliberada
  // de Square, que no lo separa). Junta el dinero de dos cheques en uno solo y
  // cierra el origen — si sale mal, no hay "deshacer" que devuelva las líneas
  // a su cheque original. Por eso NO viaja con orders:update.
  'orders:merge': ['orders:read', 'orders:update', 'orders:merge', 'tables:read'],
```

**b)** en `DEFAULT_PERMISSIONS[StaffRole.MANAGER]`, junto a las otras de orders (~línea 736, después de `'orders:cancel',`):

```typescript
    'orders:merge', // Fusionar cuentas — MANAGER+; WAITER queda a un PIN de distancia
```

ADMIN, OWNER y SUPERADMIN **no se tocan**: ya lo cubren con `'orders:*'` y `'*:*'`.

**c)** en `INDIVIDUAL_PERMISSIONS_BY_RESOURCE` (línea 1498):

```typescript
  orders: ['orders:read', 'orders:create', 'orders:update', 'orders:cancel', 'orders:comp', 'orders:void', 'orders:merge'],
```

- [ ] **Step 4: Cambia la ruta de merge**

`src/routes/mobile.routes.ts:2028` — la ruta `POST /venues/:venueId/orders/:orderId/merge` cambia **una sola línea**:

```typescript
/**
 * POST /api/v1/mobile/venues/:venueId/orders/:orderId/merge
 * "Fusionar cuentas" (Square's merge): el inverso de dividir.
 * 🔴 Permiso PROPIO desde 2026-08: junta el dinero de dos cheques y cierra el
 * origen. WAITER no lo trae — el POS ofrece PIN de gerente si el venue lo activó.
 */
router.post(
  '/venues/:venueId/orders/:orderId/merge',
  authenticateTokenMiddleware,
  checkFeatureAccess('TABLE_SERVICE'),
  checkPermission('orders:merge'),
  checkTableOwnership('order'),
  orderMobileController.mergeOrders,
)
```

Las otras 9 rutas con `checkPermission('orders:update')` **no se tocan**.

- [ ] **Step 5: Córrelo y verifica que PASA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/lib/permissions.orders-merge.test.ts
```
Esperado: PASS (todos).

- [ ] **Step 6: Auditoría de permisos + suites vecinas**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm run audit:permissions
npm test -- tests/unit/lib tests/unit/routes
npm run format && npm run lint:fix
```
Esperado: `audit:permissions` sale **0**. Si marca `DASHBOARD_DEAD_GATE` para `orders:merge`, es esperado hasta la Tarea 10 (el dashboard aún no lo lista) — es WARN, no ERROR. Si marca `PHANTOM` o `CATALOG_GAP`, algo del paso 3 quedó a medias: arréglalo antes de commitear.

- [ ] **Step 7: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
git add tests/unit/lib/permissions.orders-merge.test.ts src/lib/permissions.ts src/routes/mobile.routes.ts
git commit -m "$(cat <<'EOF'
feat(permisos): fusionar cuentas exige su propio permiso orders:merge

Se separa SÓLO merge de las 10 acciones que hoy viajan con orders:update.
MANAGER+ lo trae; WAITER no. Divergencia deliberada de Square, que no lo separa.

🔴 Cambia la operación de los venues existentes el día del deploy: un mesero
que hoy junta mesas dejará de poder. Avisar antes de liberar las apps.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 3: Esquema — `PermissionOverride` + `VenueSettings.managerPinOverrideEnabled`

**Files:**
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/prisma/schema.prisma` (modelo nuevo + un campo en `VenueSettings`, línea ~792)
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/scripts/generate-schema-map.ts:177` (`MODEL_TO_DOMAIN`)
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/tests/__helpers__/setup.ts` (registro del prismaMock)
- Create: `prisma/migrations/<timestamp>_permission_override_and_manager_pin_switch/migration.sql` (lo genera Prisma)
- Regenerado: `docs/SCHEMA_MAP.md`

**Interfaces:**
- Consumes: nada.
- Produces: `prisma.permissionOverride` con campos `id, venueId, token, permission, authorizedById, requestedById, expiresAt, consumedAt, consumedRoute, createdAt`; y `prisma.venueSettings.managerPinOverrideEnabled: boolean` (default `false`). Las tareas 4, 6, 7 y 8 los consumen.

- [ ] **Step 1: Agrega el modelo al schema**

En `prisma/schema.prisma`, **después** del modelo `VenueRolePermission** (busca `model VenueRolePermission`), pega:

```prisma
/// Token de elevación de UN SOLO USO para el "PIN de autorización de gerente".
/// Es token Y bitácora en la misma tabla: la fila sobrevive al consumo y deja
/// escrito quién autorizó qué, en qué ruta y cuándo.
///
/// 🔴 `authorizedById` y `requestedById` son ids de StaffVenue SIN llave foránea
/// a propósito: si mañana se da de baja a ese empleado, la fila de auditoría NO
/// puede desaparecer con él (el mismo tropiezo que ya costó entradas de
/// ActivityLog por el FK `ActivityLog_staffId_fkey`).
model PermissionOverride {
  id             String    @id @default(cuid())
  venueId        String
  token          String    @unique /// uuid v4, viaja en el header X-Permission-Override
  permission     String /// el permiso EXACTO que habilita, p.ej. 'orders:merge'
  authorizedById String /// StaffVenue.id de quien tecleó el PIN
  requestedById  String? /// StaffVenue.id de quien estaba bloqueado (null si no se pudo resolver)
  expiresAt      DateTime /// createdAt + 60 s
  consumedAt     DateTime? /// null = sin usar. El consumo es un updateMany atómico.
  consumedRoute  String? /// "POST /api/v1/mobile/venues/:venueId/orders/:orderId/merge"
  createdAt      DateTime  @default(now())

  @@index([venueId, createdAt])
  @@index([expiresAt])
}
```

En el modelo `VenueSettings`, justo después del bloque de `enforceTableOwnership` (línea ~792), agrega:

```prisma
  /// PIN de autorización de gerente: cuando un rol sin permiso intenta una acción,
  /// el POS ofrece un teclado para que alguien CON ese permiso la autorice una vez.
  /// 🔴 Nace OFF — ningún venue existente amanece pidiendo PINs. Core, sin candado
  /// de plan. Switch canónico en el dashboard; el POS sólo lo lee.
  managerPinOverrideEnabled Boolean @default(false)
```

- [ ] **Step 2: Registra el modelo en el generador del mapa**

`scripts/generate-schema-map.ts`, en `MODEL_TO_DOMAIN`, justo después de `VenueRolePermission: 'Staff, Auth, Permissions & Time',` (línea 177):

```typescript
  PermissionOverride: 'Staff, Auth, Permissions & Time',
```

- [ ] **Step 3: Genera la migración y el mapa**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npx prisma migrate dev --name permission_override_and_manager_pin_switch
npm run schema:map
```
Esperado: una carpeta nueva en `prisma/migrations/` con `CREATE TABLE "PermissionOverride"` + `ALTER TABLE "VenueSettings" ADD COLUMN "managerPinOverrideEnabled" BOOLEAN NOT NULL DEFAULT false`, y `docs/SCHEMA_MAP.md` modificado con la fila nueva. Si `schema:map` sale distinto de 0 quejándose de un modelo desconocido, el Step 2 quedó mal.

- [ ] **Step 4: Registra el modelo en el mock de Prisma de los tests**

`tests/__helpers__/setup.ts` — junto a `venueRolePermission` / `staffVenue` (busca `staffVenue: createMockModel(),`, línea 95), agrega en el mismo objeto:

```typescript
  permissionOverride: createMockModel(),
```

> Sin esto, cualquier test que toque `prisma.permissionOverride` revienta con "Cannot read properties of undefined" (el registro es manual, ver memoria `prismamock-manual-registry`).

- [ ] **Step 5: Verifica que compila y que la suite no se rompió**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npx prisma validate
npm run build
npm test -- tests/unit/middlewares
```
Esperado: `prisma validate` OK, build OK, la suite de middlewares sigue verde (nada la usa todavía).

- [ ] **Step 6: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
git add prisma/schema.prisma prisma/migrations docs/SCHEMA_MAP.md \
        scripts/generate-schema-map.ts tests/__helpers__/setup.ts
git commit -m "$(cat <<'EOF'
feat(schema): PermissionOverride + switch managerPinOverrideEnabled

Token de un solo uso Y bitácora en la misma tabla: la fila sobrevive al consumo
y deja escrito quién autorizó qué. Sin FK a StaffVenue a propósito — la baja de
un empleado no puede borrar la evidencia.

El switch del venue nace OFF: ningún local existente amanece pidiendo PINs.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 4: Servicio del override — crear token, consumirlo, leer el switch

**Files:**
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/src/services/mobile/permission-override.mobile.service.ts`
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/tests/unit/services/mobile/permission-override.mobile.service.test.ts`

**Interfaces:**
- Consumes: `prisma.permissionOverride`, `prisma.venueSettings.managerPinOverrideEnabled` (Tarea 3); `hasPermission`, `evaluatePermissionList` de `@/lib/permissions`.
- Produces (las tareas 5, 6 y 7 dependen de estas firmas EXACTAS):
  ```typescript
  export const OVERRIDE_TTL_MS: number                      // 60_000
  export class OverrideInvalidPinError extends Error { readonly code: 'OVERRIDE_INVALID_PIN' }
  export class OverrideInsufficientError extends Error { readonly code: 'OVERRIDE_INSUFFICIENT' }
  export async function createPermissionOverride(params: {
    venueId: string
    pin: string
    permission: string
    requestedById?: string | null
    now?: Date
  }): Promise<{ token: string; expiresAt: Date; authorizedBy: { id: string; name: string } }>
  export async function consumePermissionOverride(params: {
    token: string
    venueId: string
    permission: string
    route: string
    now?: Date
  }): Promise<{ authorizedById: string } | null>
  export async function isManagerPinOverrideEnabled(venueId: string): Promise<boolean>
  ```

**Decisión de diseño que hay que respetar:** la resolución del permiso efectivo del autorizador **usa exactamente el mismo camino que `checkPermission`** — `permissionSet` si lo tiene, si no `VenueRolePermission` + `hasPermission(role, custom, perm)`. Si el servicio resolviera distinto, tendríamos "el PIN se aceptó pero la acción sigue fallando" (o peor, un PIN que concede lo que la puerta no concedería).

- [ ] **Step 1: Escribe el test que falla**

Crea `tests/unit/services/mobile/permission-override.mobile.service.test.ts`:

```typescript
import { StaffRole } from '@prisma/client'
import prisma from '@/utils/prismaClient'
import {
  createPermissionOverride,
  consumePermissionOverride,
  isManagerPinOverrideEnabled,
  OverrideInvalidPinError,
  OverrideInsufficientError,
  OVERRIDE_TTL_MS,
} from '@/services/mobile/permission-override.mobile.service'

jest.mock('@/utils/prismaClient', () => ({
  __esModule: true,
  default: {
    staffVenue: { findFirst: jest.fn() },
    venueRolePermission: { findUnique: jest.fn() },
    venueSettings: { findUnique: jest.fn() },
    permissionOverride: { create: jest.fn(), updateMany: jest.fn(), findUnique: jest.fn() },
  },
}))

jest.mock('@/config/logger', () => ({
  __esModule: true,
  default: { info: jest.fn(), warn: jest.fn(), error: jest.fn(), debug: jest.fn() },
}))

const VENUE = 'venue_1'
const NOW = new Date('2026-08-15T18:00:00.000Z')

const managerStaffVenue = {
  id: 'sv_manager',
  role: StaffRole.MANAGER,
  permissionSetId: null,
  permissionSet: null,
  staff: { firstName: 'Laura', lastName: 'Méndez' },
}

const waiterStaffVenue = {
  ...managerStaffVenue,
  id: 'sv_waiter',
  role: StaffRole.WAITER,
  staff: { firstName: 'Beto', lastName: 'Cruz' },
}

beforeEach(() => {
  jest.clearAllMocks()
  ;(prisma.venueRolePermission.findUnique as jest.Mock).mockResolvedValue(null)
  ;(prisma.permissionOverride.create as jest.Mock).mockImplementation(async ({ data }: any) => data)
})

describe('createPermissionOverride', () => {
  // 1. NUEVO
  it('PIN que no existe en el venue → OverrideInvalidPinError', async () => {
    ;(prisma.staffVenue.findFirst as jest.Mock).mockResolvedValue(null)
    await expect(
      createPermissionOverride({ venueId: VENUE, pin: '9999', permission: 'orders:merge', now: NOW }),
    ).rejects.toBeInstanceOf(OverrideInvalidPinError)
    expect(prisma.permissionOverride.create).not.toHaveBeenCalled()
  })

  it('PIN correcto pero SIN ese permiso → OverrideInsufficientError', async () => {
    ;(prisma.staffVenue.findFirst as jest.Mock).mockResolvedValue(waiterStaffVenue)
    await expect(
      createPermissionOverride({ venueId: VENUE, pin: '1234', permission: 'orders:merge', now: NOW }),
    ).rejects.toBeInstanceOf(OverrideInsufficientError)
    expect(prisma.permissionOverride.create).not.toHaveBeenCalled()
  })

  it('PIN correcto CON el permiso → token de 60 s atado a ese permiso y venue', async () => {
    ;(prisma.staffVenue.findFirst as jest.Mock).mockResolvedValue(managerStaffVenue)
    const result = await createPermissionOverride({
      venueId: VENUE,
      pin: '1234567890',
      permission: 'orders:merge',
      requestedById: 'sv_waiter',
      now: NOW,
    })
    expect(result.token).toEqual(expect.any(String))
    expect(result.token.length).toBeGreaterThan(20)
    expect(result.expiresAt.getTime()).toBe(NOW.getTime() + OVERRIDE_TTL_MS)
    expect(result.authorizedBy).toEqual({ id: 'sv_manager', name: 'Laura Méndez' })
    expect(prisma.permissionOverride.create).toHaveBeenCalledWith({
      data: expect.objectContaining({
        venueId: VENUE,
        permission: 'orders:merge',
        authorizedById: 'sv_manager',
        requestedById: 'sv_waiter',
      }),
    })
  })

  it('busca sólo empleados ACTIVOS de ESE venue', async () => {
    ;(prisma.staffVenue.findFirst as jest.Mock).mockResolvedValue(managerStaffVenue)
    await createPermissionOverride({ venueId: VENUE, pin: '1234', permission: 'orders:merge', now: NOW })
    expect(prisma.staffVenue.findFirst).toHaveBeenCalledWith(
      expect.objectContaining({ where: expect.objectContaining({ venueId: VENUE, pin: '1234', active: true }) }),
    )
  })

  it('respeta un permissionSet asignado en vez del rol', async () => {
    ;(prisma.staffVenue.findFirst as jest.Mock).mockResolvedValue({
      ...waiterStaffVenue,
      permissionSetId: 'ps_1',
      permissionSet: { permissions: ['orders:read', 'orders:update', 'orders:merge'] },
    })
    const result = await createPermissionOverride({ venueId: VENUE, pin: '1111', permission: 'orders:merge', now: NOW })
    expect(result.authorizedBy.id).toBe('sv_waiter')
  })

  it('un permissionSet SIN el permiso sigue siendo insuficiente', async () => {
    ;(prisma.staffVenue.findFirst as jest.Mock).mockResolvedValue({
      ...waiterStaffVenue,
      permissionSetId: 'ps_1',
      permissionSet: { permissions: ['orders:read', 'orders:update'] },
    })
    await expect(
      createPermissionOverride({ venueId: VENUE, pin: '1111', permission: 'orders:merge', now: NOW }),
    ).rejects.toBeInstanceOf(OverrideInsufficientError)
  })
})

describe('consumePermissionOverride', () => {
  it('consume con un update ATÓMICO que exige sin usar y sin expirar', async () => {
    ;(prisma.permissionOverride.updateMany as jest.Mock).mockResolvedValue({ count: 1 })
    ;(prisma.permissionOverride.findUnique as jest.Mock).mockResolvedValue({ authorizedById: 'sv_manager' })

    const result = await consumePermissionOverride({
      token: 'tok_1',
      venueId: VENUE,
      permission: 'orders:merge',
      route: 'POST /api/v1/mobile/venues/:venueId/orders/:orderId/merge',
      now: NOW,
    })

    expect(result).toEqual({ authorizedById: 'sv_manager' })
    expect(prisma.permissionOverride.updateMany).toHaveBeenCalledWith({
      where: {
        token: 'tok_1',
        venueId: VENUE,
        permission: 'orders:merge',
        consumedAt: null,
        expiresAt: { gt: NOW },
      },
      data: { consumedAt: NOW, consumedRoute: 'POST /api/v1/mobile/venues/:venueId/orders/:orderId/merge' },
    })
  })

  it('🔴 segundo consumo del MISMO token → null (count 0 = otro ganó la carrera)', async () => {
    ;(prisma.permissionOverride.updateMany as jest.Mock).mockResolvedValue({ count: 0 })
    const result = await consumePermissionOverride({
      token: 'tok_1', venueId: VENUE, permission: 'orders:merge', route: 'r', now: NOW,
    })
    expect(result).toBeNull()
    expect(prisma.permissionOverride.findUnique).not.toHaveBeenCalled()
  })

  it('un token de OTRO permiso no sirve', async () => {
    ;(prisma.permissionOverride.updateMany as jest.Mock).mockResolvedValue({ count: 0 })
    const result = await consumePermissionOverride({
      token: 'tok_1', venueId: VENUE, permission: 'payments:refund', route: 'r', now: NOW,
    })
    expect(result).toBeNull()
  })

  it('un token de OTRO venue no sirve', async () => {
    ;(prisma.permissionOverride.updateMany as jest.Mock).mockResolvedValue({ count: 0 })
    const result = await consumePermissionOverride({
      token: 'tok_1', venueId: 'venue_2', permission: 'orders:merge', route: 'r', now: NOW,
    })
    expect(result).toBeNull()
  })
})

describe('isManagerPinOverrideEnabled', () => {
  it('true cuando el switch del venue está ON', async () => {
    ;(prisma.venueSettings.findUnique as jest.Mock).mockResolvedValue({ managerPinOverrideEnabled: true })
    await expect(isManagerPinOverrideEnabled(VENUE)).resolves.toBe(true)
  })

  it('false cuando está OFF', async () => {
    ;(prisma.venueSettings.findUnique as jest.Mock).mockResolvedValue({ managerPinOverrideEnabled: false })
    await expect(isManagerPinOverrideEnabled(VENUE)).resolves.toBe(false)
  })

  it('false — y NUNCA lanza — si el venue no tiene fila de settings', async () => {
    ;(prisma.venueSettings.findUnique as jest.Mock).mockResolvedValue(null)
    await expect(isManagerPinOverrideEnabled(VENUE)).resolves.toBe(false)
  })

  it('false — y NUNCA lanza — si la consulta revienta', async () => {
    ;(prisma.venueSettings.findUnique as jest.Mock).mockRejectedValue(new Error('db down'))
    await expect(isManagerPinOverrideEnabled(VENUE)).resolves.toBe(false)
  })
})
```

- [ ] **Step 2: Córrelo y verifica que FALLA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/services/mobile/permission-override.mobile.service.test.ts
```
Esperado: FAIL — "Cannot find module '@/services/mobile/permission-override.mobile.service'".

- [ ] **Step 3: Implementa el servicio**

Crea `src/services/mobile/permission-override.mobile.service.ts`:

```typescript
/**
 * PIN de autorización de gerente (manager override).
 *
 * Cambia un PIN + un permiso por un TOKEN de un solo uso, 60 s de vida, atado a
 * ESE permiso y ESE venue. `checkPermission` lo consume desde el header
 * `X-Permission-Override` y deja pasar la acción una vez.
 *
 * 🔴 El PIN se compara en TEXTO PLANO. Es una decisión explícita del founder
 * (2026-08-15) y no se re-propone. Consecuencia honesta: quien tenga lectura de
 * la base puede usar el PIN de un gerente y la bitácora diría su nombre igual.
 * La auditoría sirve para reconstruir qué pasó, NO como prueba de quién autorizó.
 */

import { randomUUID } from 'crypto'
import { StaffRole } from '@prisma/client'
import prisma from '@/utils/prismaClient'
import logger from '@/config/logger'
import { evaluatePermissionList, hasPermission } from '@/lib/permissions'

/** Vida del token. Suficiente para reintentar la request, corto para no dejar la terminal elevada. */
export const OVERRIDE_TTL_MS = 60_000

export class OverrideInvalidPinError extends Error {
  readonly code = 'OVERRIDE_INVALID_PIN' as const
  constructor() {
    super('Código incorrecto')
  }
}

export class OverrideInsufficientError extends Error {
  readonly code = 'OVERRIDE_INSUFFICIENT' as const
  constructor() {
    super('Ese código tampoco tiene este permiso')
  }
}

/**
 * Resuelve el permiso efectivo por el MISMO camino que checkPermission:
 * permissionSet asignado > VenueRolePermission + rol. Divergir aquí produciría
 * un PIN que se acepta y luego la acción falla igual (o al revés).
 */
async function staffVenueCan(params: {
  venueId: string
  role: StaffRole
  permissionSet: { permissions: unknown } | null
  requiredPermission: string
}): Promise<boolean> {
  const { venueId, role, permissionSet, requiredPermission } = params

  if (permissionSet) {
    return evaluatePermissionList(permissionSet.permissions as string[], requiredPermission)
  }

  const venueRolePermission = await prisma.venueRolePermission.findUnique({
    where: { venueId_role: { venueId, role } },
    select: { permissions: true },
  })

  const customPermissions = venueRolePermission ? (venueRolePermission.permissions as string[]) : null
  return hasPermission(role, customPermissions, requiredPermission)
}

export async function createPermissionOverride(params: {
  venueId: string
  pin: string
  permission: string
  requestedById?: string | null
  now?: Date
}): Promise<{ token: string; expiresAt: Date; authorizedBy: { id: string; name: string } }> {
  const { venueId, pin, permission, requestedById = null } = params
  const now = params.now ?? new Date()

  const staffVenue = await prisma.staffVenue.findFirst({
    where: { venueId, pin, active: true },
    select: {
      id: true,
      role: true,
      permissionSetId: true,
      permissionSet: true,
      staff: { select: { firstName: true, lastName: true } },
    },
  })

  if (!staffVenue) {
    logger.warn('Override rechazado: ningún empleado activo de este venue tiene ese PIN', { venueId, permission })
    throw new OverrideInvalidPinError()
  }

  const can = await staffVenueCan({
    venueId,
    role: staffVenue.role,
    permissionSet: staffVenue.permissionSetId ? (staffVenue.permissionSet as any) : null,
    requiredPermission: permission,
  })

  if (!can) {
    // Auto-autorizarse es imposible por construcción: si TU PIN tuviera el
    // permiso, nunca habría habido 403 y este endpoint no se habría llamado.
    logger.warn('Override rechazado: ese PIN tampoco tiene el permiso', {
      venueId,
      permission,
      authorizerRole: staffVenue.role,
    })
    throw new OverrideInsufficientError()
  }

  const token = randomUUID()
  const expiresAt = new Date(now.getTime() + OVERRIDE_TTL_MS)

  await prisma.permissionOverride.create({
    data: {
      venueId,
      token,
      permission,
      authorizedById: staffVenue.id,
      requestedById,
      expiresAt,
    },
  })

  return {
    token,
    expiresAt,
    authorizedBy: {
      id: staffVenue.id,
      name: `${staffVenue.staff.firstName} ${staffVenue.staff.lastName}`.trim(),
    },
  }
}

/**
 * Consumo ATÓMICO. El `updateMany` con `consumedAt: null` en el WHERE es lo que
 * garantiza UN solo uso aunque dos requests lleguen a la vez: la base decide, y
 * sólo una recibe count 1. Nunca separes esto en un read + un write.
 */
export async function consumePermissionOverride(params: {
  token: string
  venueId: string
  permission: string
  route: string
  now?: Date
}): Promise<{ authorizedById: string } | null> {
  const { token, venueId, permission, route } = params
  const now = params.now ?? new Date()

  const claimed = await prisma.permissionOverride.updateMany({
    where: { token, venueId, permission, consumedAt: null, expiresAt: { gt: now } },
    data: { consumedAt: now, consumedRoute: route },
  })

  if (claimed.count !== 1) return null

  const row = await prisma.permissionOverride.findUnique({
    where: { token },
    select: { authorizedById: true },
  })

  return row ? { authorizedById: row.authorizedById } : null
}

/**
 * ¿El venue activó el PIN de autorización? Nace OFF.
 * 🔴 NUNCA lanza: se llama en el camino de un 403 y un fallo de base no puede
 * convertir un "no tienes permiso" en un 500.
 */
export async function isManagerPinOverrideEnabled(venueId: string): Promise<boolean> {
  try {
    const settings = await prisma.venueSettings.findUnique({
      where: { venueId },
      select: { managerPinOverrideEnabled: true },
    })
    return settings?.managerPinOverrideEnabled === true
  } catch (error) {
    logger.error('No se pudo leer managerPinOverrideEnabled — se asume apagado', { venueId, error })
    return false
  }
}
```

- [ ] **Step 4: Córrelo y verifica que PASA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/services/mobile/permission-override.mobile.service.test.ts
```
Esperado: PASS (17 tests).

- [ ] **Step 5: Suite del módulo + formato**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/services/mobile
npm run format && npm run lint:fix
npm run build
```

- [ ] **Step 6: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
git add src/services/mobile/permission-override.mobile.service.ts \
        tests/unit/services/mobile/permission-override.mobile.service.test.ts
git commit -m "$(cat <<'EOF'
feat(override): servicio del PIN de autorización de gerente

Cambia PIN + permiso por un token de UN uso, 60 s, atado a ese permiso y venue.
El consumo es un updateMany atómico: si dos requests llegan a la vez, la base
decide y sólo una recibe count 1.

El permiso del autorizador se resuelve por el MISMO camino que checkPermission
(permissionSet > VenueRolePermission + rol) — divergir daría un PIN que se
acepta y luego falla igual.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 5: Endpoint `POST /mobile/venues/:venueId/permission-overrides`

**Files:**
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/src/schemas/mobile/permissionOverride.mobile.schema.ts`
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/src/controllers/mobile/permission-override.mobile.controller.ts`
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/tests/unit/controllers/permission-override.mobile.controller.test.ts`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/src/routes/mobile.routes.ts` (import + ruta)

**Interfaces:**
- Consumes: `createPermissionOverride`, `OverrideInvalidPinError`, `OverrideInsufficientError` (Tarea 4).
- Produces: `POST /api/v1/mobile/venues/:venueId/permission-overrides`
  - body `{ pin: string (4-10 dígitos), permission: string }`
  - **201** `{ success: true, data: { token, expiresAt, authorizedBy: { id, name } } }`
  - **401** `{ success: false, code: 'OVERRIDE_INVALID_PIN', message: 'Código incorrecto' }`
  - **403** `{ success: false, code: 'OVERRIDE_INSUFFICIENT', message: 'Ese código tampoco tiene este permiso' }`
  - **429** del `pinLoginRateLimiter` existente (prod: 10/15 min por IP + 20 por venue)
  - Android e iOS consumen estas formas exactas.

- [ ] **Step 1: Escribe el test que falla**

Crea `tests/unit/controllers/permission-override.mobile.controller.test.ts`:

```typescript
import { Request, Response, NextFunction } from 'express'
import { createOverride } from '@/controllers/mobile/permission-override.mobile.controller'
import * as service from '@/services/mobile/permission-override.mobile.service'
import { OverrideInvalidPinError, OverrideInsufficientError } from '@/services/mobile/permission-override.mobile.service'

jest.mock('@/services/mobile/permission-override.mobile.service', () => {
  const actual = jest.requireActual('@/services/mobile/permission-override.mobile.service')
  return { ...actual, createPermissionOverride: jest.fn() }
})

jest.mock('@/config/logger', () => ({
  __esModule: true,
  default: { info: jest.fn(), warn: jest.fn(), error: jest.fn(), debug: jest.fn() },
}))

describe('POST /mobile/venues/:venueId/permission-overrides', () => {
  let req: Partial<Request>
  let res: Partial<Response>
  let next: NextFunction
  let json: jest.Mock
  let status: jest.Mock

  beforeEach(() => {
    jest.clearAllMocks()
    json = jest.fn()
    status = jest.fn(() => res as Response)
    res = { status, json } as any
    next = jest.fn()
    req = {
      params: { venueId: 'venue_1' },
      body: { pin: '1234567890', permission: 'orders:merge' },
      authContext: { userId: 'user_waiter', venueId: 'venue_1', role: 'WAITER' },
    } as any
  })

  it('201 con el token y quién autorizó', async () => {
    const expiresAt = new Date('2026-08-15T18:01:00.000Z')
    ;(service.createPermissionOverride as jest.Mock).mockResolvedValue({
      token: 'tok_abc',
      expiresAt,
      authorizedBy: { id: 'sv_manager', name: 'Laura Méndez' },
    })

    await createOverride(req as Request, res as Response, next)

    expect(status).toHaveBeenCalledWith(201)
    expect(json).toHaveBeenCalledWith({
      success: true,
      data: { token: 'tok_abc', expiresAt: expiresAt.toISOString(), authorizedBy: { id: 'sv_manager', name: 'Laura Méndez' } },
    })
  })

  it('401 OVERRIDE_INVALID_PIN cuando el código no existe', async () => {
    ;(service.createPermissionOverride as jest.Mock).mockRejectedValue(new OverrideInvalidPinError())
    await createOverride(req as Request, res as Response, next)
    expect(status).toHaveBeenCalledWith(401)
    expect(json).toHaveBeenCalledWith({ success: false, code: 'OVERRIDE_INVALID_PIN', message: 'Código incorrecto' })
  })

  it('403 OVERRIDE_INSUFFICIENT cuando el código existe pero no puede', async () => {
    ;(service.createPermissionOverride as jest.Mock).mockRejectedValue(new OverrideInsufficientError())
    await createOverride(req as Request, res as Response, next)
    expect(status).toHaveBeenCalledWith(403)
    expect(json).toHaveBeenCalledWith({
      success: false,
      code: 'OVERRIDE_INSUFFICIENT',
      message: 'Ese código tampoco tiene este permiso',
    })
  })

  it('un error inesperado va a next() — no se traga como 401', async () => {
    const boom = new Error('db down')
    ;(service.createPermissionOverride as jest.Mock).mockRejectedValue(boom)
    await createOverride(req as Request, res as Response, next)
    expect(next).toHaveBeenCalledWith(boom)
    expect(status).not.toHaveBeenCalled()
  })

  it('pasa el venueId de la ruta y el StaffVenue del bloqueado al servicio', async () => {
    ;(service.createPermissionOverride as jest.Mock).mockResolvedValue({
      token: 't', expiresAt: new Date(), authorizedBy: { id: 'x', name: 'y' },
    })
    await createOverride(req as Request, res as Response, next)
    expect(service.createPermissionOverride).toHaveBeenCalledWith(
      expect.objectContaining({ venueId: 'venue_1', pin: '1234567890', permission: 'orders:merge' }),
    )
  })
})
```

- [ ] **Step 2: Córrelo y verifica que FALLA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/controllers/permission-override.mobile.controller.test.ts
```
Esperado: FAIL — el módulo del controlador no existe.

- [ ] **Step 3: Crea el schema zod (mensajes en español)**

`src/schemas/mobile/permissionOverride.mobile.schema.ts`:

```typescript
import { z } from 'zod'

/**
 * PIN de autorización de gerente. El PIN viaja UNA vez por request, sobre TLS,
 * y nunca se guarda en el dispositivo.
 */
export const createPermissionOverrideSchema = z.object({
  params: z.object({
    venueId: z.string().min(1, { message: 'El ID del establecimiento es requerido.' }),
  }),
  body: z.object({
    pin: z
      .string()
      .regex(/^\d{4,10}$/, { message: 'El código debe tener entre 4 y 10 dígitos.' }),
    permission: z
      .string()
      .min(3, { message: 'El permiso es requerido.' })
      .regex(/^[a-z0-9-]+:[a-z0-9_-]+$/i, { message: 'Formato de permiso inválido.' }),
  }),
})
```

- [ ] **Step 4: Crea el controlador**

`src/controllers/mobile/permission-override.mobile.controller.ts`:

```typescript
/**
 * PIN de autorización de gerente — endpoint del override.
 *
 * Devuelve un token de UN uso para el permiso pedido. No ejecuta la acción: el
 * cliente reintenta su request original con `X-Permission-Override: <token>`.
 */

import { NextFunction, Request, Response } from 'express'
import prisma from '../../utils/prismaClient'
import logger from '../../config/logger'
import {
  createPermissionOverride,
  OverrideInsufficientError,
  OverrideInvalidPinError,
} from '../../services/mobile/permission-override.mobile.service'

/**
 * @route POST /api/v1/mobile/venues/:venueId/permission-overrides
 */
export const createOverride = async (req: Request, res: Response, next: NextFunction) => {
  const { venueId } = req.params
  const { pin, permission } = req.body as { pin: string; permission: string }

  try {
    // Quién estaba bloqueado. Es sólo para la bitácora: si no se resuelve, el
    // override procede igual — la autorización no depende de este dato.
    let requestedById: string | null = null
    const userId = (req as any).authContext?.userId
    if (userId) {
      const requester = await prisma.staffVenue
        .findUnique({ where: { staffId_venueId: { staffId: userId, venueId } }, select: { id: true } })
        .catch(() => null)
      requestedById = requester?.id ?? null
    }

    const result = await createPermissionOverride({ venueId, pin, permission, requestedById })

    logger.info('Override de permiso concedido', {
      venueId,
      permission,
      authorizedById: result.authorizedBy.id,
      requestedById,
    })

    return res.status(201).json({
      success: true,
      data: {
        token: result.token,
        expiresAt: result.expiresAt.toISOString(),
        authorizedBy: result.authorizedBy,
      },
    })
  } catch (error) {
    if (error instanceof OverrideInvalidPinError) {
      return res.status(401).json({ success: false, code: 'OVERRIDE_INVALID_PIN', message: error.message })
    }
    if (error instanceof OverrideInsufficientError) {
      return res.status(403).json({ success: false, code: 'OVERRIDE_INSUFFICIENT', message: error.message })
    }
    return next(error)
  }
}
```

- [ ] **Step 5: Registra la ruta**

En `src/routes/mobile.routes.ts`, junto a los demás imports de controladores (bloque de las líneas 9-50):

```typescript
import * as permissionOverrideMobileController from '../controllers/mobile/permission-override.mobile.controller'
import { createPermissionOverrideSchema } from '../schemas/mobile/permissionOverride.mobile.schema'
```

Y la ruta, **inmediatamente antes** del bloque `// TIME CLOCK (Reloj Checador)` (línea ~898):

```typescript
// ============================================================================
// PIN DE AUTORIZACIÓN DE GERENTE (manager override)
// ============================================================================

/**
 * POST /api/v1/mobile/venues/:venueId/permission-overrides
 * Cambia el PIN de alguien CON el permiso por un token de un solo uso (60 s)
 * para ESE permiso y ESE venue. No ejecuta nada: el POS reintenta su request
 * original con el header `X-Permission-Override`.
 *
 * Mismo rate limit que el login por PIN (prod: 10/15 min por IP, 20 por venue).
 */
router.post(
  '/venues/:venueId/permission-overrides',
  authenticateTokenMiddleware,
  requireVenueMembership,
  pinLoginRateLimiter,
  validateRequest(createPermissionOverrideSchema),
  permissionOverrideMobileController.createOverride,
)
```

- [ ] **Step 6: Córrelo y verifica que PASA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/controllers/permission-override.mobile.controller.test.ts
npm test -- tests/unit/routes
npm run format && npm run lint:fix && npm run build
```
Esperado: PASS. Si `tests/unit/routes` tiene un test que enumera rutas/permisos, actualízalo con la ruta nueva.

- [ ] **Step 7: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
git add src/schemas/mobile/permissionOverride.mobile.schema.ts \
        src/controllers/mobile/permission-override.mobile.controller.ts \
        src/routes/mobile.routes.ts \
        tests/unit/controllers/permission-override.mobile.controller.test.ts
git commit -m "$(cat <<'EOF'
feat(override): endpoint POST /mobile/venues/:venueId/permission-overrides

201 con el token y quién autorizó; 401 si el código no existe; 403
OVERRIDE_INSUFFICIENT si existe pero tampoco tiene el permiso — para que el POS
pueda decir "ese código tampoco puede" en vez de un "no" mudo.

Reusa el pinLoginRateLimiter del login por PIN.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 6: `overridable` en el 403 de `checkPermission` + consumo del token

**Por qué las dos cosas juntas:** son el mismo bloque de código (`if (!authorized)`) y comparten los tests. Separarlas dejaría un commit intermedio donde el POS ofrece PIN y el server no lo acepta.

**Files:**
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/src/middlewares/checkPermission.middleware.ts:277-314`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/tests/unit/middlewares/checkPermission.middleware.test.ts` (agrega un `describe` nuevo)

**Interfaces:**
- Consumes: `consumePermissionOverride`, `isManagerPinOverrideEnabled` (Tarea 4).
- Produces:
  - El 403 de permiso gana `overridable: true` **sólo** si el switch del venue está ON. `error`, `message`, `required`, `userRole` **no cambian**.
  - El 403 de **membresía** (`'No access to this venue'`) NO lo lleva nunca.
  - Header `X-Permission-Override: <token>` → si el token valida, `next()` y `req.authContext.overrideAuthorizedBy = <StaffVenue.id>` queda disponible para los controladores.
  - `ActivityLog` gana las acciones `PERMISSION_OVERRIDE_USED` y `PERMISSION_OVERRIDE_REJECTED`.

- [ ] **Step 1: Escribe los tests que fallan**

Añade al final de `tests/unit/middlewares/checkPermission.middleware.test.ts`, **dentro** del `describe('checkPermission Middleware', ...)` existente. Primero extiende el `jest.mock('@/utils/prismaClient', ...)` de la cabecera del archivo para que incluya los modelos nuevos:

```typescript
// En el jest.mock('@/utils/prismaClient') del inicio del archivo, agrega:
    venueSettings: {
      findUnique: jest.fn(),
    },
    permissionOverride: {
      updateMany: jest.fn(),
      findUnique: jest.fn(),
    },
```

Y agrega el bloque de tests:

```typescript
  describe('PIN de autorización de gerente (override)', () => {
    beforeEach(() => {
      // Por default: el switch APAGADO y sin token en el header.
      ;(prisma.venueSettings.findUnique as jest.Mock).mockResolvedValue({ managerPinOverrideEnabled: false })
      ;(prisma.permissionOverride.updateMany as jest.Mock).mockResolvedValue({ count: 0 })
      ;(permissionsLib.hasPermission as jest.Mock).mockReturnValue(false)
      ;(mockReq as any).headers = {}
      ;(mockReq as any).method = 'POST'
      ;(mockReq as any).originalUrl = '/api/v1/mobile/venues/venue_123/orders/o1/merge'
    })

    // 1. NUEVO
    it('switch OFF → el 403 NO lleva overridable', async () => {
      await checkPermission('orders:merge')(mockReq as Request, mockRes as Response, mockNext)

      expect(statusMock).toHaveBeenCalledWith(403)
      const body = jsonMock.mock.calls[0][0]
      expect(body).toEqual({
        error: 'Forbidden',
        message: "Permission 'orders:merge' required",
        required: 'orders:merge',
        userRole: 'MANAGER',
      })
      expect(body.overridable).toBeUndefined()
      expect(mockNext).not.toHaveBeenCalled()
    })

    it('switch ON → el 403 lleva overridable: true SIN perder ningún campo viejo', async () => {
      ;(prisma.venueSettings.findUnique as jest.Mock).mockResolvedValue({ managerPinOverrideEnabled: true })

      await checkPermission('orders:merge')(mockReq as Request, mockRes as Response, mockNext)

      expect(statusMock).toHaveBeenCalledWith(403)
      expect(jsonMock).toHaveBeenCalledWith({
        error: 'Forbidden',
        message: "Permission 'orders:merge' required",
        required: 'orders:merge',
        userRole: 'MANAGER',
        overridable: true,
      })
    })

    it('🔴 el 403 de MEMBRESÍA nunca lleva overridable (ningún PIN arregla no pertenecer al venue)', async () => {
      ;(prisma.venueSettings.findUnique as jest.Mock).mockResolvedValue({ managerPinOverrideEnabled: true })
      ;(prisma.staffVenue.findUnique as jest.Mock).mockResolvedValue(null)
      ;(prisma.venue.findUnique as jest.Mock).mockResolvedValue({ organizationId: 'org_999' })
      ;(prisma.staffOrganization.findUnique as jest.Mock).mockResolvedValue(null)
      ;(mockReq as any).authContext = { userId: 'user_123', venueId: 'otro_venue', orgId: 'org_123', role: undefined }

      await checkPermission('orders:merge')(mockReq as Request, mockRes as Response, mockNext)

      expect(statusMock).toHaveBeenCalledWith(403)
      expect(jsonMock).toHaveBeenCalledWith({ error: 'Forbidden', message: 'No access to this venue' })
    })

    it('token válido en el header → deja pasar y expone quién autorizó', async () => {
      ;(mockReq as any).headers = { 'x-permission-override': 'tok_abc' }
      ;(prisma.permissionOverride.updateMany as jest.Mock).mockResolvedValue({ count: 1 })
      ;(prisma.permissionOverride.findUnique as jest.Mock).mockResolvedValue({ authorizedById: 'sv_manager' })

      await checkPermission('orders:merge')(mockReq as Request, mockRes as Response, mockNext)

      expect(mockNext).toHaveBeenCalled()
      expect(statusMock).not.toHaveBeenCalled()
      expect((mockReq as any).authContext.overrideAuthorizedBy).toBe('sv_manager')
    })

    it('el consumo exige token + venue + permiso, sin usar y sin expirar', async () => {
      ;(mockReq as any).headers = { 'x-permission-override': 'tok_abc' }
      ;(prisma.permissionOverride.updateMany as jest.Mock).mockResolvedValue({ count: 1 })
      ;(prisma.permissionOverride.findUnique as jest.Mock).mockResolvedValue({ authorizedById: 'sv_manager' })

      await checkPermission('orders:merge')(mockReq as Request, mockRes as Response, mockNext)

      const where = (prisma.permissionOverride.updateMany as jest.Mock).mock.calls[0][0].where
      expect(where).toMatchObject({
        token: 'tok_abc',
        venueId: 'venue_123',
        permission: 'orders:merge',
        consumedAt: null,
      })
      expect(where.expiresAt.gt).toBeInstanceOf(Date)
    })

    it('🔴 token ya usado o expirado (count 0) → 403, NO pasa', async () => {
      ;(mockReq as any).headers = { 'x-permission-override': 'tok_usado' }
      ;(prisma.venueSettings.findUnique as jest.Mock).mockResolvedValue({ managerPinOverrideEnabled: true })
      ;(prisma.permissionOverride.updateMany as jest.Mock).mockResolvedValue({ count: 0 })

      await checkPermission('orders:merge')(mockReq as Request, mockRes as Response, mockNext)

      expect(mockNext).not.toHaveBeenCalled()
      expect(statusMock).toHaveBeenCalledWith(403)
      expect(jsonMock.mock.calls[0][0]).toMatchObject({ required: 'orders:merge', overridable: true })
    })

    it('token de OTRO permiso no sirve (el WHERE lo filtra → count 0 → 403)', async () => {
      ;(mockReq as any).headers = { 'x-permission-override': 'tok_de_refund' }
      ;(prisma.permissionOverride.updateMany as jest.Mock).mockResolvedValue({ count: 0 })

      await checkPermission('orders:merge')(mockReq as Request, mockRes as Response, mockNext)

      expect(statusMock).toHaveBeenCalledWith(403)
      expect((prisma.permissionOverride.updateMany as jest.Mock).mock.calls[0][0].where.permission).toBe('orders:merge')
    })

    it('un fallo al leer el switch NO convierte el 403 en 500', async () => {
      ;(prisma.venueSettings.findUnique as jest.Mock).mockRejectedValue(new Error('db down'))

      await checkPermission('orders:merge')(mockReq as Request, mockRes as Response, mockNext)

      expect(statusMock).toHaveBeenCalledWith(403)
      expect(statusMock).not.toHaveBeenCalledWith(500)
    })

    // 2. REGRESIÓN
    it('con permiso, el override ni se consulta', async () => {
      ;(permissionsLib.hasPermission as jest.Mock).mockReturnValue(true)

      await checkPermission('orders:merge')(mockReq as Request, mockRes as Response, mockNext)

      expect(mockNext).toHaveBeenCalled()
      expect(prisma.venueSettings.findUnique).not.toHaveBeenCalled()
      expect(prisma.permissionOverride.updateMany).not.toHaveBeenCalled()
    })

    it('SUPERADMIN sigue pasando sin tocar nada del override', async () => {
      ;(prisma.staffVenue.findFirst as jest.Mock).mockResolvedValue({ id: 'sv_super' })

      await checkPermission('orders:merge')(mockReq as Request, mockRes as Response, mockNext)

      expect(mockNext).toHaveBeenCalled()
      expect(prisma.permissionOverride.updateMany).not.toHaveBeenCalled()
    })
  })
```

- [ ] **Step 2: Córrelos y verifica que FALLAN**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/middlewares/checkPermission.middleware.test.ts -t "PIN de autorización"
```
Esperado: FAIL en los que esperan `overridable` y en los del token (hoy siempre responde 403 pelón).

- [ ] **Step 3: Implementa el bloque**

En `src/middlewares/checkPermission.middleware.ts`, añade el import junto a los de arriba:

```typescript
import { consumePermissionOverride, isManagerPinOverrideEnabled } from '@/services/mobile/permission-override.mobile.service'
```

Y **reemplaza** el cuerpo del `if (!authorized) { ... }` (líneas 277-314) por:

```typescript
      if (!authorized) {
        // 🔴 PIN de autorización de gerente. Este es el ÚNICO punto de integración
        // del override: cubre las ~200 rutas con checkPermission de hoy y las que
        // vengan. Cero cambios por-acción, igual que lo resuelve Square.
        const overrideToken = req.headers?.['x-permission-override']
        if (typeof overrideToken === 'string' && overrideToken.length > 0) {
          const consumed = await consumePermissionOverride({
            token: overrideToken,
            venueId,
            permission: requiredPermission,
            route: `${req.method} ${req.originalUrl}`,
          })

          if (consumed) {
            ;(req as any).authContext.overrideAuthorizedBy = consumed.authorizedById

            // La acción queda en la bitácora con QUIÉN la autorizó. Es lo que se
            // lee después por el MCP `get_activity_log`.
            try {
              void logAction({
                staffId: authContext.userId,
                venueId,
                action: 'PERMISSION_OVERRIDE_USED',
                entity: 'permission',
                entityId: requiredPermission,
                data: {
                  permission: requiredPermission,
                  userRole,
                  authorizedByStaffVenueId: consumed.authorizedById,
                  method: req.method,
                  path: req.originalUrl,
                },
                ipAddress: req.ip,
                userAgent: typeof req.get === 'function' ? req.get('user-agent') : undefined,
              })
            } catch (auditErr) {
              logger.error('checkPermission: audit log construction failed (non-fatal)', auditErr)
            }

            logger.info(
              `checkPermission: '${requiredPermission}' autorizado por override (StaffVenue ${consumed.authorizedById}) en venue ${venueId}`,
            )
            return next()
          }

          // Token reusado, expirado, de otro permiso o de otro venue. No es un
          // 500 ni un mensaje distinto: se cae al 403 de siempre y el POS vuelve
          // a pedir el PIN.
          try {
            void logAction({
              staffId: authContext.userId,
              venueId,
              action: 'PERMISSION_OVERRIDE_REJECTED',
              entity: 'permission',
              entityId: requiredPermission,
              data: { permission: requiredPermission, userRole, reason: 'token_invalido_o_consumido' },
              ipAddress: req.ip,
              userAgent: typeof req.get === 'function' ? req.get('user-agent') : undefined,
            })
          } catch (auditErr) {
            logger.error('checkPermission: audit log construction failed (non-fatal)', auditErr)
          }
        }

        logger.warn(
          `checkPermission: User ${authContext.userId} (${userRole}) denied access to '${requiredPermission}' in venue ${venueId}`,
        )

        // Persist the denial to ActivityLog for post-deploy monitoring + audit.
        // Wrapped in try/catch so a mocked req without .get() (in unit tests)
        // or any unexpected synchronous failure can't leak as a 500 response.
        try {
          void logAction({
            staffId: authContext.userId,
            venueId,
            action: 'PERMISSION_DENIED',
            entity: 'permission',
            entityId: requiredPermission,
            data: {
              permission: requiredPermission,
              userRole,
              roleSource,
              method: req.method,
              path: req.originalUrl,
              hasPermissionSet: !!permissionSet,
            },
            ipAddress: req.ip,
            userAgent: typeof req.get === 'function' ? req.get('user-agent') : undefined,
          })
        } catch (auditErr) {
          logger.error('checkPermission: audit log construction failed (non-fatal)', auditErr)
        }

        // `overridable` es ADITIVO y sólo aparece con el switch del venue ON.
        // Nunca se toca error/message/required/userRole: apps viejas los leen.
        // El 403 de MEMBRESÍA (arriba) y el de TIER (checkFeatureAccess, otro
        // middleware) no pasan por aquí — ningún PIN los arregla.
        const overridable = await isManagerPinOverrideEnabled(venueId)

        return res.status(403).json({
          error: 'Forbidden',
          message: `Permission '${requiredPermission}' required`,
          required: requiredPermission,
          userRole,
          ...(overridable ? { overridable: true } : {}),
        })
      }
```

- [ ] **Step 4: Córrelos y verifica que PASAN**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/middlewares/checkPermission.middleware.test.ts
```
Esperado: PASS, incluidos **todos** los tests que ya existían (regresión).

- [ ] **Step 5: Suite del módulo completa**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/middlewares tests/unit/services/mobile tests/unit/lib
npm run format && npm run lint:fix && npm run build
```

- [ ] **Step 6: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
git add src/middlewares/checkPermission.middleware.ts tests/unit/middlewares/checkPermission.middleware.test.ts
git commit -m "$(cat <<'EOF'
feat(override): checkPermission ofrece y consume el PIN de gerente

El 403 de permiso gana `overridable: true` cuando el venue lo activó (campo
aditivo; error/message/required/userRole intactos), y el mismo middleware
consume `X-Permission-Override` con un update atómico.

El 403 de membresía no lo lleva, y el de tier va por otro middleware: ningún
PIN arregla no pertenecer al venue ni no haber pagado el plan.

La acción autorizada queda en ActivityLog como PERMISSION_OVERRIDE_USED.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 7: Exponer el switch al POS en `GET /mobile/venues/:venueId/settings`

**Por qué:** sin esto las apps no saben si deben mostrar el candado en las acciones que hoy esconden — y sin candado el PIN es inalcanzable para esas acciones.

**Files:**
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/src/controllers/mobile/tpvSettings.mobile.controller.ts` (el `select` del `venueSettings` y el `res.json`)
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/src/schemas/dashboard/venueSettings.schema.ts:42` (permitir escribirlo desde el dashboard)
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/tests/unit/controllers/tpvSettings.mobile.controller.override.test.ts`

**Interfaces:**
- Consumes: `VenueSettings.managerPinOverrideEnabled` (Tarea 3).
- Produces:
  - `GET /api/v1/mobile/venues/:venueId/settings` → `data.managerPinOverrideEnabled: boolean` (nivel `data`, hermano de `plan` y `promotions`; **no** dentro de `settings`, que es por-terminal). Android (Tarea 14) e iOS (Tarea 18) lo leen.
  - `PUT /api/v1/dashboard/venues/:venueId/settings` acepta `{ managerPinOverrideEnabled: boolean }`. El dashboard (Tarea 9) lo escribe.

- [ ] **Step 1: Escribe el test que falla**

Crea `tests/unit/controllers/tpvSettings.mobile.controller.override.test.ts`:

```typescript
import { Request, Response, NextFunction } from 'express'
import prisma from '@/utils/prismaClient'
import { getVenueTpvSettings } from '@/controllers/mobile/tpvSettings.mobile.controller'
import { UpdateVenueSettingsSchema } from '@/schemas/dashboard/venueSettings.schema'

jest.mock('@/utils/prismaClient', () => ({
  __esModule: true,
  default: {
    terminal: { findMany: jest.fn() },
    venueSettings: { findUnique: jest.fn() },
  },
}))
jest.mock('@/services/access/basePlan.service', () => ({ getVenuePlanInfo: jest.fn().mockResolvedValue(undefined) }))
jest.mock('@/services/dashboard/tpv.dashboard.service', () => ({ getTpvSettings: jest.fn().mockResolvedValue(null) }))
jest.mock('@/services/dashboard/activity-log.service', () => ({ logAction: jest.fn() }))
jest.mock('@/config/logger', () => ({
  __esModule: true,
  default: { info: jest.fn(), warn: jest.fn(), error: jest.fn(), debug: jest.fn() },
}))

describe('GET /mobile/venues/:venueId/settings — managerPinOverrideEnabled', () => {
  let req: Partial<Request>
  let res: Partial<Response>
  let next: NextFunction
  let json: jest.Mock

  beforeEach(() => {
    jest.clearAllMocks()
    json = jest.fn()
    res = { json } as any
    next = jest.fn()
    req = { params: { venueId: 'venue_1' }, headers: {} } as any
    ;(prisma.terminal.findMany as jest.Mock).mockResolvedValue([])
  })

  // 1. NUEVO
  it('devuelve true cuando el venue lo activó', async () => {
    ;(prisma.venueSettings.findUnique as jest.Mock).mockResolvedValue({
      promotionsPanelCashier: 'TAB',
      promotionsPanelCustomer: 'SIDE_PANEL',
      managerPinOverrideEnabled: true,
    })
    await getVenueTpvSettings(req as Request, res as Response, next)
    expect(json.mock.calls[0][0].data.managerPinOverrideEnabled).toBe(true)
  })

  it('devuelve false cuando el venue no tiene fila de settings (nace OFF)', async () => {
    ;(prisma.venueSettings.findUnique as jest.Mock).mockResolvedValue(null)
    await getVenueTpvSettings(req as Request, res as Response, next)
    expect(json.mock.calls[0][0].data.managerPinOverrideEnabled).toBe(false)
  })

  it('el PUT del dashboard acepta el campo', () => {
    const parsed = UpdateVenueSettingsSchema.safeParse({
      params: { venueId: 'venue_1' },
      body: { managerPinOverrideEnabled: true },
      query: {},
    })
    expect(parsed.success).toBe(true)
    expect((parsed as any).data.body.managerPinOverrideEnabled).toBe(true)
  })

  // 2. REGRESIÓN: el contrato viejo no se movió
  it('sigue devolviendo terminals, settings, activeTerminalId, deviceTerminal y promotions', async () => {
    ;(prisma.venueSettings.findUnique as jest.Mock).mockResolvedValue(null)
    await getVenueTpvSettings(req as Request, res as Response, next)
    const data = json.mock.calls[0][0].data
    expect(data).toHaveProperty('terminals')
    expect(data).toHaveProperty('settings')
    expect(data).toHaveProperty('activeTerminalId')
    expect(data).toHaveProperty('deviceTerminal')
    expect(data.promotions).toEqual({ panelCashier: 'TAB', panelCustomer: 'SIDE_PANEL' })
  })
})
```

- [ ] **Step 2: Córrelo y verifica que FALLA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/controllers/tpvSettings.mobile.controller.override.test.ts
```
Esperado: FAIL — `data.managerPinOverrideEnabled` es `undefined` y el PUT deja caer el campo.

- [ ] **Step 3: Amplía el `select` y la respuesta**

En `src/controllers/mobile/tpvSettings.mobile.controller.ts`, dentro del `Promise.all`, el tercer elemento:

```typescript
      prisma.venueSettings
        .findUnique({
          where: { venueId },
          select: { promotionsPanelCashier: true, promotionsPanelCustomer: true, managerPinOverrideEnabled: true },
        })
        .catch(error => {
          logger.error('Failed to resolve venue-level POS settings — returning defaults', { venueId, error })
          return null
        }),
```

Y en el `return res.json({...})`, justo después del bloque `promotions`:

```typescript
        // PIN de autorización de gerente. Aditivo y opcional (mismo contrato que
        // `plan`): un POS viejo lo ignora; uno nuevo sin el campo cae a false, que
        // es el comportamiento de hoy. Es de VENUE, no de terminal — por eso vive
        // aquí y no dentro de `settings`.
        managerPinOverrideEnabled: venueSettings?.managerPinOverrideEnabled ?? false,
```

- [ ] **Step 4: Permite escribirlo desde el dashboard**

En `src/schemas/dashboard/venueSettings.schema.ts`, dentro del `body` de `UpdateVenueSettingsSchema` (junto a `enforceTableOwnership`, línea ~42):

```typescript
    managerPinOverrideEnabled: z.boolean().optional(),
```

> Sin esta línea el PUT **descarta el campo en silencio** y el switch del dashboard parecería funcionar sin guardar nada.

- [ ] **Step 5: Córrelo y verifica que PASA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test -- tests/unit/controllers/tpvSettings.mobile.controller.override.test.ts
npm test -- tests/unit/controllers tests/unit/schemas
npm run format && npm run lint:fix && npm run build
```

- [ ] **Step 6: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
git add src/controllers/mobile/tpvSettings.mobile.controller.ts \
        src/schemas/dashboard/venueSettings.schema.ts \
        tests/unit/controllers/tpvSettings.mobile.controller.override.test.ts
git commit -m "$(cat <<'EOF'
feat(override): el POS lee managerPinOverrideEnabled y el dashboard lo escribe

Campo aditivo a nivel `data` en GET /mobile/venues/:id/settings (hermano de
`plan`, no dentro de `settings`: es de venue, no de terminal) y aceptado en el
PUT de ajustes del dashboard — sin esa línea el zod lo descartaba en silencio.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 8: Cierre de la fase del server

**Files:** ninguno nuevo — es la verificación completa antes de pasar a los clientes.

- [ ] **Step 1: Chequea la capacidad de la máquina (suite completa = build pesado)**

```bash
sysctl -n hw.ncpu vm.loadavg && sysctl -n vm.swapusage
pgrep -fl "GradleDaemon|KotlinCompileDaemon|xcodebuild|jest|vitest|tsc" | head
```
Si está saturada, **córrela igual** y avisa que tardará varios minutos. Sube el timeout, no te rindas.

- [ ] **Step 2: Suite completa + simulación de CI**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm test
npm run pre-deploy
npm run audit:permissions
npm run schema:map -- --check
```
Esperado: todo verde y `schema:map --check` sin diferencias. `audit:permissions` puede seguir marcando `DASHBOARD_DEAD_GATE` para `orders:merge` hasta la Tarea 10 — es WARN.

- [ ] **Step 3: Prueba manual contra el server local, leyendo el log**

Arranca `npm run dev`, y con un usuario WAITER real:

```bash
LOG=$(ls -t /Users/amieva/Documents/Programming/Avoqado/avoqado-server/logs/development*.log | head -1)
echo "log activo: $LOG"

# 1) merge sin permiso, switch OFF → 403 SIN overridable
curl -i -X POST "http://localhost:3000/api/v1/mobile/venues/<VENUE_ID>/orders/<ORDER_ID>/merge" \
  -H "Authorization: Bearer <TOKEN_WAITER>" -H "Content-Type: application/json" \
  -d '{"sourceOrderId":"<OTRA_ORDEN>"}'

# 2) prende el switch en la base y repite → 403 CON "overridable":true
psql "$DATABASE_URL" -c 'UPDATE "VenueSettings" SET "managerPinOverrideEnabled" = true WHERE "venueId" = '"'"'<VENUE_ID>'"'"';'

# 3) pide el token con el PIN del gerente
curl -i -X POST "http://localhost:3000/api/v1/mobile/venues/<VENUE_ID>/permission-overrides" \
  -H "Authorization: Bearer <TOKEN_WAITER>" -H "Content-Type: application/json" \
  -d '{"pin":"<PIN_GERENTE>","permission":"orders:merge"}'

# 4) reintenta el merge con el token → 200
curl -i -X POST "http://localhost:3000/api/v1/mobile/venues/<VENUE_ID>/orders/<ORDER_ID>/merge" \
  -H "Authorization: Bearer <TOKEN_WAITER>" -H "X-Permission-Override: <TOKEN>" \
  -H "Content-Type: application/json" -d '{"sourceOrderId":"<OTRA_ORDEN>"}'

# 5) el MISMO token otra vez → 403 (un solo uso)
```

Y comprueba el rastro en el log **por nombre de negocio**, no por reloj:

```bash
grep "venueName: '<NOMBRE DEL VENUE>'" "$LOG" | tail -40
```
Esperado: sin líneas `error:`. Toma el `X-Correlation-ID` de cada respuesta y `grep` ese uuid para ver la traza exacta.

- [ ] **Step 4: Verifica la bitácora en la base**

```bash
psql "$DATABASE_URL" -c "SELECT action, entity, \"entityId\", data->>'authorizedByStaffVenueId' AS autorizo, \"createdAt\" FROM \"ActivityLog\" WHERE action LIKE 'PERMISSION_OVERRIDE%' ORDER BY \"createdAt\" DESC LIMIT 10;"
psql "$DATABASE_URL" -c "SELECT token, permission, \"authorizedById\", \"consumedAt\", \"consumedRoute\" FROM \"PermissionOverride\" ORDER BY \"createdAt\" DESC LIMIT 5;"
```
Esperado: un `PERMISSION_OVERRIDE_USED` con el StaffVenue del gerente, y la fila de `PermissionOverride` con `consumedAt` y `consumedRoute` llenos.

- [ ] **Step 5: Deja el switch como estaba**

```bash
psql "$DATABASE_URL" -c 'UPDATE "VenueSettings" SET "managerPinOverrideEnabled" = false WHERE "venueId" = '"'"'<VENUE_ID>'"'"';'
```

- [ ] **Step 6: Sin commit**

Esta tarea no produce cambios de código. Si algo falló, vuelve a la tarea que lo introdujo, arréglalo **ahí** y re-commitea esa tarea.

---

# FASE 2 — avoqado-web-dashboard

> 🔴 Antes de tocar el primer archivo de este repo, lee su contexto: `avoqado-web-dashboard/CLAUDE.md`, `avoqado-web-dashboard/.claude/rules/*.md` (sobre todo `critical-warnings.md` y `ui-patterns.md`) y su memoria en `/Users/amieva/.claude/projects/-Users-amieva-Documents-Programming-Avoqado-avoqado-web-dashboard/memory/MEMORY.md`.

### Tarea 9: El switch canónico `managerPinOverrideEnabled`

**Por qué aquí:** regla del workspace — el switch canónico vive SIEMPRE en el dashboard, escribiendo el registro del server. Un feature cuyo único switch es un `UPDATE` en Postgres está incompleto. **No** se espeja en el POS: no se toca durante el turno desde el piso, se decide una vez en la oficina.

**Tier:** core, todos los planes. **A diferencia de `CashReconciliationSetting`, este componente NO lleva `useTierFeatureAccess`.**

**Files:**
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard/src/pages/Venue/Edit/components/ManagerPinOverrideSetting.tsx`
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard/src/pages/Venue/Edit/components/ManagerPinOverrideSetting.test.tsx`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard/src/pages/Venue/Edit/BasicInfo.tsx` (montarlo)
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard/src/types.ts:608-621`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard/src/locales/es/venue.json`, `.../en/venue.json`, `.../fr/venue.json`

**Interfaces:**
- Consumes: `PUT /api/v1/dashboard/venues/:venueId/settings` con `{ managerPinOverrideEnabled: boolean }` (Tarea 7).
- Produces: `VenueSettings.managerPinOverrideEnabled?: boolean` en `src/types.ts`; el componente `<ManagerPinOverrideSetting venueId={string} storedSetting={boolean} />`.

- [ ] **Step 1: Escribe el test que falla**

Crea `src/pages/Venue/Edit/components/ManagerPinOverrideSetting.test.tsx` (copia la estructura de mocks de `CashReconciliationSetting.test.tsx`, **sin** el mock de `use-tier-feature-access`):

```tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { ManagerPinOverrideSetting } from './ManagerPinOverrideSetting'

const mockPut = vi.fn().mockResolvedValue({ data: {} })
vi.mock('@/api', () => ({ default: { put: (...args: unknown[]) => mockPut(...args) } }))
vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: vi.fn() }) }))
const canMock = vi.fn().mockReturnValue(true)
vi.mock('@/hooks/use-access', () => ({ useAccess: () => ({ can: (p: string) => canMock(p) }) }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }))

function renderSetting(stored: boolean) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
  render(
    <QueryClientProvider client={queryClient}>
      <ManagerPinOverrideSetting venueId="venue-1" storedSetting={stored} />
    </QueryClientProvider>,
  )
  return { invalidateSpy }
}

describe('ManagerPinOverrideSetting', () => {
  beforeEach(() => { mockPut.mockClear() })

  it('nace apagado y manda el booleano exacto al prenderlo', async () => {
    const { invalidateSpy } = renderSetting(false)
    const sw = screen.getByRole('switch', { name: 'edit.managerPinOverride.switchLabel' })
    expect(sw).not.toBeChecked()

    fireEvent.click(sw)

    await waitFor(() =>
      expect(mockPut).toHaveBeenCalledWith('/api/v1/dashboard/venues/venue-1/settings', {
        managerPinOverrideEnabled: true,
      }),
    )
    expect(sw).toBeChecked()
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['get-venue-data', 'venue-1'] })
  })

  it('se puede apagar', async () => {
    renderSetting(true)
    fireEvent.click(screen.getByRole('switch', { name: 'edit.managerPinOverride.switchLabel' }))
    await waitFor(() =>
      expect(mockPut).toHaveBeenCalledWith('/api/v1/dashboard/venues/venue-1/settings', {
        managerPinOverrideEnabled: false,
      }),
    )
  })

  it('🔴 es core: se ve y se puede prender sin ningún gate de plan', () => {
    renderSetting(false)
    expect(screen.getByRole('switch', { name: 'edit.managerPinOverride.switchLabel' })).toBeEnabled()
  })

  it('sin permiso venues:update el switch queda deshabilitado y no manda nada', () => {
    // `can` es una función del mock de arriba: se le cambia el retorno para este caso.
    canMock.mockReturnValue(false)
    renderSetting(false)
    const sw = screen.getByRole('switch', { name: 'edit.managerPinOverride.switchLabel' })
    expect(sw).toBeDisabled()
    fireEvent.click(sw)
    expect(mockPut).not.toHaveBeenCalled()
    canMock.mockReturnValue(true)
  })

  it('revierte el switch si el PUT falla', async () => {
    mockPut.mockRejectedValueOnce({ response: { status: 500 } })
    renderSetting(false)
    const sw = screen.getByRole('switch', { name: 'edit.managerPinOverride.switchLabel' })
    fireEvent.click(sw)
    await waitFor(() => expect(sw).not.toBeChecked())
  })
})
```

- [ ] **Step 2: Córrelo y verifica que FALLA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard
npx vitest run src/pages/Venue/Edit/components/ManagerPinOverrideSetting.test.tsx
```
Esperado: FAIL — el componente no existe.

- [ ] **Step 3: Crea el componente**

`src/pages/Venue/Edit/components/ManagerPinOverrideSetting.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Loader2, ShieldCheck } from 'lucide-react'
import api from '@/api'
import { Switch } from '@/components/ui/switch'
import { useAccess } from '@/hooks/use-access'
import { useToast } from '@/hooks/use-toast'

interface ManagerPinOverrideSettingProps {
  venueId: string
  storedSetting: boolean
}

interface ToggleVariables {
  next: boolean
  previous: boolean
}

/**
 * PIN de autorización de gerente.
 *
 * Con esto encendido, cuando alguien sin permiso intenta una acción, el POS le
 * pide el código de un encargado para autorizarla UNA vez, en vez de decirle
 * "no tienes permiso" y dejarlo ahí.
 *
 * Core: todos los planes, sin candado de tier. Nace APAGADO — ningún local
 * existente amanece pidiendo códigos.
 */
export function ManagerPinOverrideSetting({ venueId, storedSetting }: ManagerPinOverrideSettingProps) {
  const { t } = useTranslation('venue')
  const { can } = useAccess()
  const { toast } = useToast()
  const queryClient = useQueryClient()
  const [enabled, setEnabled] = useState(storedSetting)

  useEffect(() => {
    setEnabled(storedSetting)
  }, [storedSetting])

  const canUpdate = can('venues:update')

  const toggle = useMutation({
    mutationFn: async ({ next }: ToggleVariables) => {
      await api.put(`/api/v1/dashboard/venues/${venueId}/settings`, { managerPinOverrideEnabled: next })
      return next
    },
    onSuccess: next => {
      toast({
        title: t(next ? 'edit.managerPinOverride.enabledTitle' : 'edit.managerPinOverride.disabledTitle'),
        description: t('edit.managerPinOverride.saved'),
      })
      queryClient.invalidateQueries({ queryKey: ['get-venue-data', venueId] })
    },
    onError: (error: any, variables) => {
      setEnabled(variables.previous)
      const status = error?.response?.status
      const fallback =
        status === 403 ? t('edit.managerPinOverride.permissionError') : t('edit.managerPinOverride.saveError')
      toast({
        title: t('edit.managerPinOverride.errorTitle'),
        description: error?.response?.data?.message || fallback,
        variant: 'destructive',
      })
      queryClient.invalidateQueries({ queryKey: ['get-venue-data', venueId] })
    },
  })

  const handleCheckedChange = (next: boolean) => {
    if (!canUpdate || toggle.isPending) return
    const previous = enabled
    setEnabled(next)
    toggle.mutate({ next, previous })
  }

  return (
    <div className="flex items-start justify-between gap-4 rounded-lg border p-4" data-tour="manager-pin-override">
      <div className="space-y-1">
        <div className="flex items-center gap-2">
          <ShieldCheck className="h-4 w-4 text-muted-foreground" />
          <p className="text-sm font-medium leading-none">{t('edit.managerPinOverride.title')}</p>
        </div>
        <p className="text-sm text-muted-foreground">{t('edit.managerPinOverride.description')}</p>
        {!canUpdate && <p className="text-xs text-muted-foreground">{t('edit.managerPinOverride.readOnly')}</p>}
      </div>
      <div className="flex shrink-0 items-center gap-2 pt-1">
        {toggle.isPending && <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />}
        <Switch
          checked={enabled}
          onCheckedChange={handleCheckedChange}
          disabled={!canUpdate || toggle.isPending}
          aria-label={t('edit.managerPinOverride.switchLabel')}
        />
      </div>
    </div>
  )
}
```

- [ ] **Step 4: Móntalo y tipa el campo**

En `src/types.ts`, dentro de `interface VenueSettings` (junto a `enforceTableOwnership`):

```typescript
  /** PIN de autorización de gerente. Core, nace OFF; el POS sólo lo lee. */
  managerPinOverrideEnabled?: boolean
```

En `src/pages/Venue/Edit/BasicInfo.tsx`, junto al `<CashReconciliationSetting …>` (líneas ~968-975) — pero **fuera** del `{field.value && (…)}` del sistema de turnos, porque el PIN de gerente no depende de los turnos. Agrega el import arriba y el render en la misma sección de ajustes operativos:

```tsx
import { ManagerPinOverrideSetting } from './components/ManagerPinOverrideSetting'
```

```tsx
{venueId && (
  <ManagerPinOverrideSetting
    venueId={venueId}
    storedSetting={venue.settings?.managerPinOverrideEnabled ?? false}
  />
)}
```

- [ ] **Step 5: Textos en los tres idiomas**

En `src/locales/es/venue.json`, dentro de `edit`, junto a `cashReconciliation`:

```json
    "managerPinOverride": {
      "title": "Autorización con código de encargado",
      "description": "Cuando alguien intente una acción para la que no tiene permiso, el punto de venta pedirá el código de un encargado para autorizarla esa vez. No deja la terminal con permisos abiertos.",
      "switchLabel": "Activar autorización con código",
      "enabledTitle": "Autorización con código activada",
      "disabledTitle": "Autorización con código desactivada",
      "saved": "La configuración se guardó correctamente.",
      "readOnly": "Necesitas permiso para actualizar la configuración del local.",
      "errorTitle": "No se guardó el cambio",
      "permissionError": "No tienes permiso para cambiar esta configuración.",
      "saveError": "Ocurrió un error al guardar la configuración."
    },
```

En `src/locales/en/venue.json`, mismo lugar:

```json
    "managerPinOverride": {
      "title": "Manager passcode approval",
      "description": "When someone tries an action they don't have permission for, the point of sale asks a manager for their code to approve it that one time. The terminal is never left elevated.",
      "switchLabel": "Enable passcode approval",
      "enabledTitle": "Passcode approval enabled",
      "disabledTitle": "Passcode approval disabled",
      "saved": "Settings saved successfully.",
      "readOnly": "You need permission to update this venue's settings.",
      "errorTitle": "Change not saved",
      "permissionError": "You don't have permission to change this setting.",
      "saveError": "Something went wrong while saving."
    },
```

En `src/locales/fr/venue.json`, mismo lugar:

```json
    "managerPinOverride": {
      "title": "Autorisation par code responsable",
      "description": "Lorsqu'une personne tente une action sans en avoir la permission, le point de vente demande le code d'un responsable pour l'autoriser une seule fois. Le terminal ne reste jamais avec des droits élevés.",
      "switchLabel": "Activer l'autorisation par code",
      "enabledTitle": "Autorisation par code activée",
      "disabledTitle": "Autorisation par code désactivée",
      "saved": "Les paramètres ont été enregistrés.",
      "readOnly": "Vous devez avoir la permission de modifier les paramètres de l'établissement.",
      "errorTitle": "Modification non enregistrée",
      "permissionError": "Vous n'avez pas la permission de modifier ce paramètre.",
      "saveError": "Une erreur est survenue lors de l'enregistrement."
    },
```

- [ ] **Step 6: Córrelo y verifica que PASA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard
npx vitest run src/pages/Venue/Edit/components/ManagerPinOverrideSetting.test.tsx
npm run lint
npm run build
```
Esperado: PASS + build limpio.

- [ ] **Step 7: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard
git add src/pages/Venue/Edit/components/ManagerPinOverrideSetting.tsx \
        src/pages/Venue/Edit/components/ManagerPinOverrideSetting.test.tsx \
        src/pages/Venue/Edit/BasicInfo.tsx src/types.ts \
        src/locales/es/venue.json src/locales/en/venue.json src/locales/fr/venue.json
git commit -m "$(cat <<'EOF'
feat(ajustes): switch del PIN de autorización de gerente

Switch canónico del venue, core (sin candado de plan) y apagado por default.
Sin esta pantalla el feature quedaría con el founder de switch humano haciendo
UPDATEs en Postgres para cada cliente que lo pida.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 10: `orders:merge` en el editor de permisos

**Contexto verificado:** el grid del dashboard **no** se alimenta del catálogo del backend — es una lista hardcodeada del cliente (`defaultPermissions.ts:309`). Ya hay deriva probada: `orders:comp` y `orders:void` existen en el server y **faltan** en el grid. Aquí sólo agregamos `orders:merge`; la deriva vieja no es de este trabajo.

**Files:**
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard/src/lib/permissions/defaultPermissions.ts:309`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard/src/lib/permissions/permissionDependencies.ts` (bloque orders, ~línea 44)
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard/src/locales/es/settings.json` (`rolePermissions.actions` línea 275, `rolePermissions.permissionLabels` línea ~348)
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard/src/locales/en/settings.json` (mismos bloques)

**Interfaces:**
- Consumes: la string `orders:merge` definida en el server (Tarea 2) — **nombre EXACTO**.
- Produces: el permiso aparece en el editor de conjuntos de rol y se puede asignar/quitar por rol.

- [ ] **Step 1: Agrega el permiso al catálogo del grid**

`src/lib/permissions/defaultPermissions.ts:309`:

```typescript
  ORDERS: {
    label: 'Orders',
    permissions: ['orders:read', 'orders:create', 'orders:update', 'orders:cancel', 'orders:merge'],
  },
```

- [ ] **Step 2: Declara sus dependencias**

`src/lib/permissions/permissionDependencies.ts`, después del bloque `'orders:cancel'`:

```typescript
  // "Fusionar cuentas": junta el dinero de dos cheques en uno y cierra el origen.
  // Espejo EXACTO de PERMISSION_DEPENDENCIES en avoqado-server/src/lib/permissions.ts.
  'orders:merge': ['orders:read', 'orders:update', 'orders:merge', 'tables:read'],
```

- [ ] **Step 3: Textos**

`src/locales/es/settings.json` → `rolePermissions.actions` (línea 275), agrega junto a `"void"`:

```json
      "merge": "Fusionar",
```

`src/locales/es/settings.json` → `rolePermissions.permissionLabels`, después de `"orders_cancel"`:

```json
      "orders_merge": "Fusionar cuentas",
```

y en `rolePermissions.permissionDescriptions`:

```json
      "orders_merge": "Permite juntar dos cuentas abiertas en una sola y cerrar la de origen. No se puede deshacer.",
```

`src/locales/en/settings.json`, los mismos tres lugares:

```json
      "merge": "Merge",
```
```json
      "orders_merge": "Merge checks",
```
```json
      "orders_merge": "Allows combining two open checks into one and closing the source check. This cannot be undone.",
```

> `src/locales/fr/settings.json` **no existe** — el francés ya no tiene traducciones de permisos. No es un hueco que abramos nosotros.

- [ ] **Step 4: Verifica en la UI**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard
npm run lint
npm run build
npx vitest run
```
Y a ojo, con el dev server: `Ajustes → Permisos por rol → editar WAITER` debe mostrar **Órdenes → Fusionar cuentas** apagado, y **MANAGER** con él encendido por default. Revisa en modo claro y oscuro.

- [ ] **Step 5: Confirma que el backend ya no marca la deriva**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm run audit:permissions
```
Esperado: sin `DASHBOARD_DEAD_GATE` ni `DASHBOARD_PHANTOM` para `orders:merge`.

- [ ] **Step 6: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard
git add src/lib/permissions/defaultPermissions.ts src/lib/permissions/permissionDependencies.ts \
        src/locales/es/settings.json src/locales/en/settings.json
git commit -m "$(cat <<'EOF'
feat(permisos): orders:merge en el editor de permisos por rol

El grid del dashboard es una lista hardcodeada, no viene del catálogo del
backend — sin esta línea el permiso existiría en el server y sería inasignable
desde la UI.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

# FASE 3 — avoqado-android

> 🔴 Recordatorio de paridad: **cada tarea de esta fase tiene su gemela en la Fase 4 (iOS)**. El trabajo no está completo hasta que las dos apps compilan con el mismo comportamiento y los mismos textos en español.

**Decisión de arquitectura del cliente (importante, y diverge de una lectura literal del spec):** el reintento ocurre **dentro del interceptor**, bloqueando el hilo de red mientras el usuario teclea. Es la única forma de que el resultado del reintento llegue al ViewModel que hizo la llamada original — si el coordinator reintentara "por fuera", el ViewModel ya habría pintado el error y el éxito posterior sería invisible (justo el bug de "pintar un éxito como pantalla de Error" que la regla de offline prohíbe). Es el mismo patrón que ya usa `TokenRefreshAuthenticator` para el 401, que también bloquea con `runBlocking`. Es seguro: el `readTimeout(30s)` sólo corre mientras se lee del socket, y el 403 ya se leyó completo antes de bloquear; `callTimeout` no está configurado.

### Tarea 11: `overridable` en el 403 + repositorio del override

**Files:**
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-android/app/src/main/java/com/avoqado/pos/core/data/network/PermissionOverrideRepository.kt`
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-android/app/src/main/java/com/avoqado/pos/core/domain/PermissionLabels.kt`
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-android/app/src/test/java/com/avoqado/pos/core/domain/PermissionLabelsTest.kt`

**Interfaces:**
- Consumes: `POST /api/v1/mobile/venues/:venueId/permission-overrides` (Tarea 5).
- Produces (la Tarea 12 depende de estas firmas):
  ```kotlin
  sealed interface OverrideResult {
      data class Granted(val token: String, val authorizedByName: String) : OverrideResult
      data object WrongPin : OverrideResult          // 401
      data object Insufficient : OverrideResult      // 403 OVERRIDE_INSUFFICIENT
      data object TooManyAttempts : OverrideResult   // 429
      data class Failed(val message: String) : OverrideResult
  }
  class PermissionOverrideRepository @Inject constructor(private val secureStorage: SecureStorage) {
      suspend fun requestToken(venueId: String, pin: String, permission: String): OverrideResult
  }
  object PermissionLabels { fun of(permission: String): String }
  ```

- [ ] **Step 1: Escribe el test que falla**

Crea `app/src/test/java/com/avoqado/pos/core/domain/PermissionLabelsTest.kt`:

```kotlin
package com.avoqado.pos.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionLabelsTest {

    @Test
    fun `traduce los permisos que el piso puede encontrarse`() {
        assertEquals("fusionar cuentas", PermissionLabels.of("orders:merge"))
        assertEquals("hacer un reembolso", PermissionLabels.of("payments:refund"))
        assertEquals("cancelar la cuenta", PermissionLabels.of("orders:cancel"))
        assertEquals("dar una cortesía", PermissionLabels.of("orders:comp"))
        assertEquals("anular artículos", PermissionLabels.of("orders:void"))
        assertEquals("modificar la cuenta", PermissionLabels.of("orders:update"))
        assertEquals("aplicar un descuento", PermissionLabels.of("discounts:apply"))
    }

    @Test
    fun `un permiso desconocido cae a un texto neutro, nunca a la string tecnica`() {
        assertEquals("esta acción", PermissionLabels.of("cosas:raras"))
        assertEquals("esta acción", PermissionLabels.of(""))
    }
}
```

- [ ] **Step 2: Córrelo y verifica que FALLA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
./gradlew testDebugUnitTest --tests "com.avoqado.pos.core.domain.PermissionLabelsTest"
```
Esperado: FAIL de compilación — `PermissionLabels` no existe.

- [ ] **Step 3: Crea `PermissionLabels`**

`app/src/main/java/com/avoqado/pos/core/domain/PermissionLabels.kt`:

```kotlin
package com.avoqado.pos.core.domain

/**
 * Permiso técnico → cómo se lo decimos a un mesero.
 *
 * 🔴 Espejo EXACTO de `PermissionLabels.swift` en avoqado-ios. Si agregas uno
 * aquí, agrégalo allá en el MISMO trabajo, con el mismo texto en español.
 *
 * Nunca enseñes la string cruda ("orders:merge") en pantalla: el que está
 * enfrente no sabe qué es un permiso, sabe qué estaba tratando de hacer.
 */
object PermissionLabels {
    private val LABELS = mapOf(
        "orders:merge" to "fusionar cuentas",
        "orders:cancel" to "cancelar la cuenta",
        "orders:comp" to "dar una cortesía",
        "orders:void" to "anular artículos",
        "orders:update" to "modificar la cuenta",
        "orders:create" to "abrir una cuenta",
        "payments:refund" to "hacer un reembolso",
        "payments:create" to "cobrar",
        "discounts:apply" to "aplicar un descuento",
        "shifts:close" to "cerrar el turno",
        "tables:manage-all" to "modificar mesas de otro mesero",
    )

    const val FALLBACK = "esta acción"

    fun of(permission: String): String = LABELS[permission] ?: FALLBACK
}
```

- [ ] **Step 4: Crea el repositorio del override**

`app/src/main/java/com/avoqado/pos/core/data/network/PermissionOverrideRepository.kt`:

```kotlin
package com.avoqado.pos.core.data.network

import android.util.Log
import com.avoqado.pos.core.data.storage.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** Lo que puede pasar al teclear el código de un encargado. */
sealed interface OverrideResult {
    data class Granted(val token: String, val authorizedByName: String) : OverrideResult
    data object WrongPin : OverrideResult
    data object Insufficient : OverrideResult
    data object TooManyAttempts : OverrideResult
    data class Failed(val message: String) : OverrideResult
}

@Serializable
private data class OverrideRequestBody(val pin: String, val permission: String)

@Serializable
private data class OverrideResponseBody(val success: Boolean = false, val data: OverrideData? = null)

@Serializable
private data class OverrideData(val token: String, val expiresAt: String? = null, val authorizedBy: AuthorizedBy? = null)

@Serializable
private data class AuthorizedBy(val id: String, val name: String)

@Serializable
private data class OverrideErrorBody(val code: String? = null, val message: String? = null)

/**
 * Pide el token de autorización de gerente.
 *
 * 🔴 Usa su PROPIO OkHttpClient a propósito. El cliente compartido lleva el
 * `ForbiddenInterceptor`, que es justo quien llama aquí — inyectarlo crearía un
 * ciclo en Hilt. Es el mismo recurso que ya usa `TokenRefreshAuthenticator`
 * para refrescar el token.
 *
 * El PIN viaja una sola vez, sobre TLS, y NUNCA se guarda en el dispositivo.
 */
@Singleton
class PermissionOverrideRepository @Inject constructor(
    private val secureStorage: SecureStorage,
) {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun requestToken(venueId: String, pin: String, permission: String): OverrideResult =
        withContext(Dispatchers.IO) {
            val accessToken = secureStorage.accessToken
                ?: return@withContext OverrideResult.Failed("Tu sesión expiró. Vuelve a entrar.")

            val body = json.encodeToString(OverrideRequestBody.serializer(), OverrideRequestBody(pin, permission))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/permission-overrides")
                .addHeader("Authorization", "Bearer $accessToken")
                // El 403 de este endpoint es un rechazo de NEGOCIO ("ese código
                // tampoco puede"), no falta de permisos del que está en la caja:
                // se interpreta aquí, no en el diálogo genérico.
                .addHeader(ForbiddenInterceptor.LOCAL_ERROR_HEADER, "true")
                .post(body)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    when (response.code) {
                        201, 200 -> {
                            val parsed = json.decodeFromString<OverrideResponseBody>(raw)
                            val data = parsed.data
                            if (data == null) {
                                OverrideResult.Failed("No se pudo obtener la autorización.")
                            } else {
                                OverrideResult.Granted(data.token, data.authorizedBy?.name.orEmpty())
                            }
                        }
                        401 -> OverrideResult.WrongPin
                        403 -> {
                            val err = runCatching { json.decodeFromString<OverrideErrorBody>(raw) }.getOrNull()
                            if (err?.code == "OVERRIDE_INSUFFICIENT") OverrideResult.Insufficient
                            else OverrideResult.Failed(err?.message ?: "No se pudo autorizar.")
                        }
                        429 -> OverrideResult.TooManyAttempts
                        else -> {
                            val err = runCatching { json.decodeFromString<OverrideErrorBody>(raw) }.getOrNull()
                            OverrideResult.Failed(ServerErrorText.humanize(err?.message, "No se pudo autorizar."))
                        }
                    }
                }
            } catch (e: Exception) {
                // 🔴 Sin red NO se encola: un rechazo de permiso no es un fallo de
                // red, y encolarlo daría por autorizado algo que nadie autorizó.
                Log.e("🔐 Override", "Falló la petición de autorización: ${e.message}")
                OverrideResult.Failed("Necesitas conexión para pedir autorización")
            }
        }
}
```

> Si `SecureStorage` no vive en `com.avoqado.pos.core.data.storage`, corrige el import al paquete real (el mismo que importa `TokenRefreshAuthenticator.kt`).

- [ ] **Step 5: Córrelo y verifica que PASA + compila**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
./gradlew testDebugUnitTest --tests "com.avoqado.pos.core.domain.PermissionLabelsTest"
./gradlew compileDebugKotlin
```

- [ ] **Step 6: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
git add app/src/main/java/com/avoqado/pos/core/domain/PermissionLabels.kt \
        app/src/main/java/com/avoqado/pos/core/data/network/PermissionOverrideRepository.kt \
        app/src/test/java/com/avoqado/pos/core/domain/PermissionLabelsTest.kt
git commit -m "$(cat <<'EOF'
feat(override): repositorio del PIN de gerente + etiquetas de permisos

Usa su propio OkHttpClient a propósito: el compartido lleva el
ForbiddenInterceptor, que es quien lo llama — inyectarlo cerraría un ciclo en
Hilt. Mismo recurso que ya usa TokenRefreshAuthenticator.

Sin red devuelve "necesitas conexión": un rechazo de permiso NO se encola.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 12: Coordinator + `overridable` en el interceptor + reintento

**Files:**
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-android/app/src/main/java/com/avoqado/pos/core/data/network/ManagerOverrideCoordinator.kt`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-android/app/src/main/java/com/avoqado/pos/core/data/network/ForbiddenInterceptor.kt`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-android/app/src/main/java/com/avoqado/pos/core/di/NetworkModule.kt:38-70`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-android/app/src/test/java/com/avoqado/pos/core/data/network/ForbiddenInterceptorTest.kt`

**Interfaces:**
- Consumes: `PermissionOverrideRepository`, `OverrideResult`, `PermissionLabels` (Tarea 11).
- Produces (la Tarea 13 depende de esto):
  ```kotlin
  @Singleton
  class ManagerOverrideCoordinator @Inject constructor(private val repository: PermissionOverrideRepository) {
      data class Prompt(val permission: String, val actionLabel: String)
      val prompt: StateFlow<Prompt?>
      fun awaitToken(permission: String): String?      // BLOQUEA el hilo llamador (el de red)
      suspend fun submitPin(venueId: String, pin: String): OverrideResult
      fun cancel()
  }
  ```
  Y en `ForbiddenInterceptor`: `const val PERMISSION_OVERRIDE_HEADER = "X-Permission-Override"`.

- [ ] **Step 1: Escribe los tests que fallan**

Añade a `app/src/test/java/com/avoqado/pos/core/data/network/ForbiddenInterceptorTest.kt` (el archivo ya tiene el `setUp` con MockWebServer; ajusta la construcción del interceptor para pasarle un coordinator falso):

```kotlin
    // Doble de prueba: responde el token que le digamos, sin UI ni red.
    private class FakeCoordinator(
        private val tokenToReturn: String?,
    ) : ManagerOverrideCoordinator(mockk(relaxed = true)) {
        var askedFor: String? = null
        override fun awaitToken(permission: String): String? {
            askedFor = permission
            return tokenToReturn
        }
    }

    @Test
    fun `403 overridable pide el codigo y reintenta la peticion con el header`() {
        val coordinator = FakeCoordinator("tok_abc")
        val client = OkHttpClient.Builder()
            .addInterceptor(ForbiddenInterceptor(errorNotifier, coordinator))
            .build()

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Forbidden","message":"Permission 'orders:merge' required","required":"orders:merge","userRole":"WAITER","overridable":true}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val response = client.newCall(Request.Builder().url(server.url("/merge")).build()).execute()

        assertEquals(200, response.code)
        assertEquals("orders:merge", coordinator.askedFor)
        server.takeRequest()
        val retried = server.takeRequest()
        assertEquals("tok_abc", retried.getHeader("X-Permission-Override"))
        // Éxito: no se pinta el diálogo de "no tienes permiso".
        assertNull(errorNotifier.forbiddenError.value)
    }

    @Test
    fun `si el usuario cancela, el 403 llega tal cual y SIN dialogo generico`() {
        val coordinator = FakeCoordinator(null)
        val client = OkHttpClient.Builder()
            .addInterceptor(ForbiddenInterceptor(errorNotifier, coordinator))
            .build()

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Forbidden","message":"Permission 'orders:merge' required","required":"orders:merge","overridable":true}"""),
        )

        val response = client.newCall(Request.Builder().url(server.url("/merge")).build()).execute()

        assertEquals(403, response.code)
        assertNull(errorNotifier.forbiddenError.value)
    }

    @Test
    fun `un 403 con el header ya puesto NO vuelve a pedir codigo (sin bucle)`() {
        val coordinator = FakeCoordinator("tok_abc")
        val client = OkHttpClient.Builder()
            .addInterceptor(ForbiddenInterceptor(errorNotifier, coordinator))
            .build()

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Forbidden","message":"Permission 'orders:merge' required","required":"orders:merge","overridable":true}"""),
        )

        val response = client.newCall(
            Request.Builder().url(server.url("/merge")).header("X-Permission-Override", "tok_viejo").build(),
        ).execute()

        assertEquals(403, response.code)
        assertNull(coordinator.askedFor)
        assertEquals("No tienes permisos para esta acción", errorNotifier.forbiddenError.value)
    }

    @Test
    fun `403 SIN overridable sigue abriendo el dialogo de siempre`() {
        val coordinator = FakeCoordinator("tok_abc")
        val client = OkHttpClient.Builder()
            .addInterceptor(ForbiddenInterceptor(errorNotifier, coordinator))
            .build()

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Forbidden","message":"Permission 'orders:merge' required","required":"orders:merge"}"""),
        )

        client.newCall(Request.Builder().url(server.url("/merge")).build()).execute()

        assertNull(coordinator.askedFor)
        assertEquals("No tienes permisos para esta acción", errorNotifier.forbiddenError.value)
    }

    @Test
    fun `403 de plan sigue sin pedir codigo (va al upsell, no al PIN)`() {
        val coordinator = FakeCoordinator("tok_abc")
        val client = OkHttpClient.Builder()
            .addInterceptor(ForbiddenInterceptor(errorNotifier, coordinator))
            .build()

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Forbidden","message":"Feature not available","featureCode":"INVENTORY_TRACKING","overridable":true}"""),
        )

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute()

        assertNull(coordinator.askedFor)
        assertNull(errorNotifier.forbiddenError.value)
    }

    @Test
    fun `403 de intermediario sigue sin pedir codigo (ningun PIN arregla un tunel caido)`() {
        val coordinator = FakeCoordinator("tok_abc")
        val client = OkHttpClient.Builder()
            .addInterceptor(ForbiddenInterceptor(errorNotifier, coordinator))
            .build()

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "text/html")
                .setBody("<!DOCTYPE html><html><body>tunnel down</body></html>"),
        )

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute()

        assertNull(coordinator.askedFor)
        assertNull(errorNotifier.forbiddenError.value)
    }
```

Para que `FakeCoordinator` pueda heredar, `ManagerOverrideCoordinator` debe declararse `open` y `awaitToken` `open`. Añade también `import io.mockk.mockk` si falta.

- [ ] **Step 2: Córrelos y verifica que FALLAN**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
./gradlew testDebugUnitTest --tests "com.avoqado.pos.core.data.network.ForbiddenInterceptorTest"
```
Esperado: FAIL de compilación — `ManagerOverrideCoordinator` no existe y `ForbiddenInterceptor` sólo recibe un argumento.

- [ ] **Step 3: Crea el coordinator**

`app/src/main/java/com/avoqado/pos/core/data/network/ManagerOverrideCoordinator.kt`:

```kotlin
package com.avoqado.pos.core.data.network

import android.util.Log
import com.avoqado.pos.core.domain.PermissionLabels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquesta el PIN de autorización de gerente.
 *
 * 🔴 `awaitToken` BLOQUEA el hilo que lo llama — que es el hilo de red del
 * interceptor, nunca el de UI. Es a propósito: si el token llegara "por fuera",
 * el ViewModel que hizo la llamada ya habría pintado un error y el éxito del
 * reintento sería invisible. Mismo patrón que `TokenRefreshAuthenticator` con
 * el 401.
 *
 * El `Mutex` garantiza UN teclado a la vez: dos acciones bloqueadas al mismo
 * tiempo hacen fila en vez de apilar dos diálogos.
 */
@Singleton
open class ManagerOverrideCoordinator @Inject constructor(
    private val repository: PermissionOverrideRepository,
) {
    data class Prompt(val permission: String, val actionLabel: String)

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    private val queue = Mutex()

    @Volatile
    private var pending: CompletableDeferred<String?>? = null

    /** Devuelve el token, o null si el usuario canceló / no se pudo. */
    open fun awaitToken(permission: String): String? = runBlocking {
        queue.withLock {
            val deferred = CompletableDeferred<String?>()
            pending = deferred
            _prompt.value = Prompt(permission, PermissionLabels.of(permission))
            try {
                deferred.await()
            } finally {
                _prompt.value = null
                pending = null
            }
        }
    }

    /** La UI llama esto al teclear el código. Sólo `Granted` cierra el diálogo. */
    open suspend fun submitPin(venueId: String, pin: String): OverrideResult {
        val permission = _prompt.value?.permission ?: return OverrideResult.Failed("La acción ya no está esperando autorización.")
        val result = repository.requestToken(venueId, pin, permission)
        if (result is OverrideResult.Granted) {
            Log.d("🔐 Override", "Autorizado por ${result.authorizedByName} para $permission")
            pending?.complete(result.token)
        }
        return result
    }

    /** El usuario cerró el teclado: la acción falla como fallaba antes. */
    open fun cancel() {
        pending?.complete(null)
    }
}
```

- [ ] **Step 4: Cambia el interceptor**

En `ForbiddenInterceptor.kt`:

**a)** agrega el campo a `ForbiddenResponse` (línea ~27):

```kotlin
    /**
     * Sólo viene cuando el venue activó el PIN de autorización de gerente Y el
     * 403 es de permisos. `null` (server viejo o switch apagado) = no se ofrece
     * teclado; el comportamiento es exactamente el de hoy.
     */
    val overridable: Boolean? = null,
```

**b)** el constructor y la constante del header:

```kotlin
class ForbiddenInterceptor(
    private val errorNotifier: ErrorNotifier,
    private val overrideCoordinator: ManagerOverrideCoordinator,
) : Interceptor {

    companion object {
        const val BACKGROUND_HEADER = "X-Avoqado-Background"
        const val LOCAL_ERROR_HEADER = "X-Avoqado-Local-Error"

        /** Token de un solo uso del PIN de autorización de gerente. */
        const val PERMISSION_OVERRIDE_HEADER = "X-Permission-Override"
    }
```

**c)** dentro del `if (response.code == 403)`, **después** de la rama de `featureCode` (línea ~118) y **antes** del `ServerErrorText.humanize(...)`:

```kotlin
            // 🔴 PIN de autorización de gerente. Sólo llega aquí lo que YA se
            // descartó arriba: no es de un intermediario y no es candado de plan
            // — o sea, un "no" real de nuestra API por falta de permiso.
            //
            // El guard del header evita el bucle: si la petición ya traía un
            // token y aun así volvió 403 (expirado, reusado), no se vuelve a
            // pedir; se cae al mensaje de siempre.
            if (parsed.overridable == true &&
                parsed.required != null &&
                request.header(PERMISSION_OVERRIDE_HEADER) == null
            ) {
                val token = overrideCoordinator.awaitToken(parsed.required)
                if (token != null) {
                    response.close()
                    val retried = request.newBuilder()
                        .header(PERMISSION_OVERRIDE_HEADER, token)
                        .build()
                    Log.d("🔒 RBAC", "Reintentando ${request.url.encodedPath} con autorización de gerente")
                    return chain.proceed(retried)
                }
                // Canceló. La acción falla como fallaba antes, pero SIN el modal
                // de "no tienes permiso": ya le dijimos por qué en el teclado.
                Log.w("🔒 RBAC", "El usuario canceló la autorización para ${parsed.required}")
                return response
            }
```

**d)** `NetworkModule.kt` — el provider recibe el coordinator y se lo pasa:

```kotlin
    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        deviceHeadersInterceptor: DeviceHeadersInterceptor,
        tokenRefreshAuthenticator: TokenRefreshAuthenticator,
        errorNotifier: ErrorNotifier,
        connectivityMonitor: ConnectivityMonitor,
        managerOverrideCoordinator: ManagerOverrideCoordinator,
    ): OkHttpClient {
```
y la línea 62:
```kotlin
        .addInterceptor(ForbiddenInterceptor(errorNotifier, managerOverrideCoordinator))
```

> No hay ciclo: `ManagerOverrideCoordinator` → `PermissionOverrideRepository` → `SecureStorage`, y el repositorio construye su propio `OkHttpClient()`.

- [ ] **Step 5: Córrelos y verifica que PASAN**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
./gradlew testDebugUnitTest --tests "com.avoqado.pos.core.data.network.ForbiddenInterceptorTest"
```
Esperado: PASS — los 6 nuevos **y** los que ya existían (las tres clasificaciones viejas siguen intactas).

- [ ] **Step 6: Compila**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
sysctl -n vm.loadavg && pgrep -fl "GradleDaemon|KotlinCompileDaemon" | head
./gradlew compileDebugKotlin
```

- [ ] **Step 7: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
git add app/src/main/java/com/avoqado/pos/core/data/network/ManagerOverrideCoordinator.kt \
        app/src/main/java/com/avoqado/pos/core/data/network/ForbiddenInterceptor.kt \
        app/src/main/java/com/avoqado/pos/core/di/NetworkModule.kt \
        app/src/test/java/com/avoqado/pos/core/data/network/ForbiddenInterceptorTest.kt
git commit -m "$(cat <<'EOF'
feat(override): el 403 overridable pide código y reintenta la misma petición

El reintento vive DENTRO del interceptor a propósito: así el resultado llega al
ViewModel que hizo la llamada original. Reintentar "por fuera" habría pintado un
error primero y el éxito sería invisible.

Un token ya puesto no vuelve a pedir código (sin bucle). El 403 de plan y el de
intermediario siguen sin abrir el teclado: ningún PIN los arregla.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 13: El teclado de PIN (UI) montado en el NavGraph

**Files:**
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-android/app/src/main/java/com/avoqado/pos/designsystem/components/ManagerOverrideSheet.kt`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-android/app/src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt:94-99, 249-268`

**Interfaces:**
- Consumes: `ManagerOverrideCoordinator.prompt`, `.submitPin()`, `.cancel()` (Tarea 12); `OverrideResult` (Tarea 11).
- Produces: `@Composable fun ManagerOverrideSheet(prompt, onSubmit: suspend (String) -> OverrideResult, onDismiss: () -> Unit)`.

**Patrones obligatorios** (`avoqado-android/CLAUDE.md` → UI/UX Rules): `AvoqadoDialog` para el contenedor, `PinPadView` de `timeclock/` para los dígitos, `PrimaryButton`, `Spacing.*`, `MaterialTheme.colorScheme.*` y `MaterialTheme.typography.*`. **Nada** hardcodeado.

- [ ] **Step 1: Crea el sheet**

`app/src/main/java/com/avoqado/pos/designsystem/components/ManagerOverrideSheet.kt`:

```kotlin
package com.avoqado.pos.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.avoqado.pos.core.data.network.ManagerOverrideCoordinator
import com.avoqado.pos.core.data.network.OverrideResult
import com.avoqado.pos.designsystem.theme.Spacing
import com.avoqado.pos.timeclock.presentation.PinPadView
import kotlinx.coroutines.launch

/**
 * "Se necesita autorización": el teclado donde un encargado teclea SU código
 * para dejar pasar UNA acción.
 *
 * 🔴 Espejo EXACTO de `ManagerOverrideSheet.swift`. Mismos textos en español.
 *
 * Cerrar = cancelar: la acción falla como fallaba antes. Nunca se pinta como
 * éxito ni se encola.
 */
@Composable
fun ManagerOverrideSheet(
    prompt: ManagerOverrideCoordinator.Prompt,
    onSubmit: suspend (String) -> OverrideResult,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AvoqadoDialog(
        title = "Se necesita autorización",
        description = "Para ${prompt.actionLabel}. Pide a un encargado su código.",
        onDismiss = onDismiss,
        dismissOnClickOutside = false,
        actionButton = {
            PrimaryButton(
                text = "Autorizar",
                onClick = {
                    scope.launch {
                        isLoading = true
                        error = null
                        val result = onSubmit(pin)
                        isLoading = false
                        when (result) {
                            is OverrideResult.Granted -> Unit // el coordinator cierra el diálogo
                            OverrideResult.WrongPin -> {
                                error = "Código incorrecto"
                                pin = ""
                            }
                            OverrideResult.Insufficient -> {
                                error = "Ese código tampoco tiene este permiso"
                                pin = ""
                            }
                            OverrideResult.TooManyAttempts -> {
                                error = "Demasiados intentos. Espera 15 minutos."
                                pin = ""
                            }
                            is OverrideResult.Failed -> {
                                error = result.message
                                pin = ""
                            }
                        }
                    }
                },
                enabled = pin.length in 4..10 && !isLoading,
                isLoading = isLoading,
                fullWidth = true,
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            PinPadView(
                pin = pin,
                onPinChange = { pin = it; error = null },
                maxLength = 10,
                minLength = 4,
                compact = true,
            )
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
```

> Si `PrimaryButton` no acepta `isLoading` o `fullWidth`, usa los parámetros reales (los mismos que usa `TimeClockSheet.kt:240-262`).

- [ ] **Step 2: Móntalo en el NavGraph, junto al diálogo de 403**

En `AvoqadoNavGraph.kt`, en el `@EntryPoint` (líneas 94-99) agrega:

```kotlin
    fun managerOverrideCoordinator(): com.avoqado.pos.core.data.network.ManagerOverrideCoordinator
```

Y junto al bloque de `forbiddenDialog` (líneas 249-268), después de él:

```kotlin
    // PIN de autorización de gerente. Va aquí, en la raíz, para que exista una
    // sola instancia sin importar qué pantalla disparó la acción bloqueada.
    val overrideCoordinator = remember { entryPoint.managerOverrideCoordinator() }
    val overridePrompt by overrideCoordinator.prompt.collectAsState()
    val overrideVenueId = remember { secureStorage.venueId }
    overridePrompt?.let { prompt ->
        ManagerOverrideSheet(
            prompt = prompt,
            onSubmit = { pin -> overrideCoordinator.submitPin(overrideVenueId.orEmpty(), pin) },
            onDismiss = { overrideCoordinator.cancel() },
        )
    }
```

> `secureStorage.venueId` — usa el mismo accesor que ya emplea el NavGraph para saber el venue activo. Si en este archivo el venue viene de otra fuente (`AppState`, `TpvSettingsRepository`), usa esa: lo que importa es que sea el venue ACTIVO, no uno cacheado de otra sucursal.

- [ ] **Step 3: Compila y verifica en el emulador**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
sysctl -n vm.loadavg && pgrep -fl "GradleDaemon|KotlinCompileDaemon" | head
./gradlew assembleDebug
```
Instálalo y compruébalo a ojo (modo claro **y** oscuro): con el switch ON y un WAITER, tocar "Fusionar cuentas" abre el teclado con el título "Se necesita autorización" y el subtítulo "Para fusionar cuentas. Pide a un encargado su código."

> 🔴 El debug se instala como `com.avoqado.pos.dev`. Lanzar `com.avoqado.pos` te enseña la app vieja y parecerá que el build no tomó (memoria `reference_verificar_en_tablet_por_adb`).

- [ ] **Step 4: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
git add app/src/main/java/com/avoqado/pos/designsystem/components/ManagerOverrideSheet.kt \
        app/src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt
git commit -m "$(cat <<'EOF'
feat(override): teclado "Se necesita autorización" en la raíz de la app

Sobre AvoqadoDialog + PinPadView, montado junto al diálogo de 403 para que haya
una sola instancia venga de donde venga la acción bloqueada. "Ese código tampoco
tiene este permiso" es un mensaje propio: un "no" mudo manda al encargado a
adivinar.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 14: El candado visible — sin esto el PIN es inalcanzable

**El problema que resuelve:** hoy la app **esconde** los controles cuando el rol no tiene el permiso. Un botón que no existe nunca produce el 403, así que el override jamás se dispararía para esas acciones. Square hace lo contrario: la acción se ve, y al tocarla pide el código.

**Alcance de v1** (el spec no enumera las acciones; ver "Preguntas abiertas" al final): se construye el mecanismo y se aplica a **una** acción realmente escondida hoy — el **reembolso** en el detalle de transacción (`RoleManager.canIssueRefund` → permiso `payments:refund`). El caso de *fusionar cuentas* **no necesita candado**: hoy ya es visible para todos, así que el server empieza a rechazarlo y el PIN aparece solo.

**Files:**
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-android/app/src/main/java/com/avoqado/pos/core/domain/RoleManager.kt`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-android/app/src/main/java/com/avoqado/pos/tpvsettings/data/TpvSettingsRepository.kt:26-47, 108-196, 331-377`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-android/app/src/main/java/com/avoqado/pos/transactions/presentation/TransactionDetailSheet.kt:316`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-android/app/src/test/java/com/avoqado/pos/core/domain/RoleManagerTest.kt`

**Interfaces:**
- Consumes: `data.managerPinOverrideEnabled` de `GET /mobile/venues/:venueId/settings` (Tarea 7).
- Produces:
  ```kotlin
  enum class ActionVisibility { ALLOWED, LOCKED, HIDDEN }
  fun RoleManager.visibilityOf(allowed: Boolean, overrideEnabled: Boolean): ActionVisibility
  val TpvSettingsRepository.managerPinOverrideEnabled: StateFlow<Boolean>
  ```

- [ ] **Step 1: Escribe el test que falla**

Añade a `app/src/test/java/com/avoqado/pos/core/domain/RoleManagerTest.kt`:

```kotlin
    @Test
    fun `con permiso, la accion se ve normal`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertEquals(ActionVisibility.ALLOWED, roleManager.visibilityOf(allowed = true, overrideEnabled = false))
        assertEquals(ActionVisibility.ALLOWED, roleManager.visibilityOf(allowed = true, overrideEnabled = true))
    }

    @Test
    fun `sin permiso y con el switch APAGADO, se esconde como hoy`() {
        every { secureStorage.userRole } returns "WAITER"
        assertEquals(ActionVisibility.HIDDEN, roleManager.visibilityOf(allowed = false, overrideEnabled = false))
    }

    @Test
    fun `sin permiso y con el switch PRENDIDO, se ve con candado`() {
        every { secureStorage.userRole } returns "WAITER"
        assertEquals(ActionVisibility.LOCKED, roleManager.visibilityOf(allowed = false, overrideEnabled = true))
    }

    @Test
    fun `el reembolso sigue permitido para MANAGER y prohibido para WAITER`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canIssueRefund)
        every { secureStorage.userRole } returns "WAITER"
        assertFalse(roleManager.canIssueRefund)
    }
```

- [ ] **Step 2: Córrelo y verifica que FALLA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
./gradlew testDebugUnitTest --tests "com.avoqado.pos.core.domain.RoleManagerTest"
```
Esperado: FAIL de compilación — `ActionVisibility` y `visibilityOf` no existen.

- [ ] **Step 3: Agrega `ActionVisibility` a `RoleManager`**

Al final de `RoleManager.kt`, fuera de la clase:

```kotlin
/**
 * Cómo se pinta una acción que el rol no tiene.
 *
 * 🔴 Espejo EXACTO de `ActionVisibility` en avoqado-ios/Services/RoleManager.swift.
 *
 * Esconder un botón parece limpio, pero deja al piso sin salida: sin botón no
 * hay 403, y sin 403 no hay a quién pedirle autorización. Con el PIN de gerente
 * encendido, la acción se VE con un candado y el "no" llega con una puerta.
 */
enum class ActionVisibility { ALLOWED, LOCKED, HIDDEN }
```

Y dentro de la clase `RoleManager`:

```kotlin
    fun visibilityOf(allowed: Boolean, overrideEnabled: Boolean): ActionVisibility = when {
        allowed -> ActionVisibility.ALLOWED
        overrideEnabled -> ActionVisibility.LOCKED
        else -> ActionVisibility.HIDDEN
    }
```

- [ ] **Step 4: Lee el switch en `TpvSettingsRepository`**

**a)** el DTO de la respuesta (línea ~338) gana el campo:

```kotlin
@Serializable
internal data class VenueSettingsData(
    val settings: TpvSettings? = null,
    val activeTerminalId: String? = null,
    val deviceTerminal: DeviceTerminalSettingsDto? = null,
    val plan: VenuePlanDto? = null,
    /**
     * PIN de autorización de gerente. Es de VENUE, no de terminal — por eso vive
     * aquí y no dentro de `settings`. Default false: un server viejo (campo
     * ausente) se comporta exactamente como hoy.
     */
    val managerPinOverrideEnabled: Boolean = false,
)
```

**b)** el `StateFlow` público, junto a `settings` (línea ~83):

```kotlin
    private val _managerPinOverrideEnabled = MutableStateFlow(secureStorage.managerPinOverrideEnabled)

    /** ¿El local activó el PIN de autorización? Decide si una acción sin permiso se ve con candado o se esconde. */
    val managerPinOverrideEnabled: StateFlow<Boolean> = _managerPinOverrideEnabled.asStateFlow()
```

**c)** en `refreshSettingsForVenue`, donde hoy persiste el plan (líneas ~168-169):

```kotlin
        _managerPinOverrideEnabled.value = parsed.data?.managerPinOverrideEnabled ?: false
        secureStorage.managerPinOverrideEnabled = parsed.data?.managerPinOverrideEnabled ?: false
```

> 🔴 En el `catch` de red **no** toques este valor: igual que la config de impresión, un refresh fallido nunca debe borrar lo bueno. Se queda el último conocido.

**d)** en `SecureStorage`, copia el patrón exacto de la propiedad `planExempt` (está unas líneas arriba en ese mismo archivo) para:

```kotlin
    /** Último valor conocido del switch de PIN de autorización del venue activo. */
    var managerPinOverrideEnabled: Boolean
```

- [ ] **Step 5: Aplica el candado al reembolso**

En `TransactionDetailSheet.kt:316`, reemplaza el `if (viewModel.roleManager.canIssueRefund) { … }` por:

```kotlin
            val overrideEnabled by viewModel.tpvSettingsRepository.managerPinOverrideEnabled.collectAsState()
            when (viewModel.roleManager.visibilityOf(viewModel.roleManager.canIssueRefund, overrideEnabled)) {
                ActionVisibility.HIDDEN -> Unit
                ActionVisibility.ALLOWED, ActionVisibility.LOCKED -> {
                    val locked = !viewModel.roleManager.canIssueRefund
                    // Con candado se toca igual: la llamada sale, el server responde
                    // 403 overridable y el teclado de autorización aparece solo. NO
                    // hay lógica nueva de permisos en el cliente — el juez sigue
                    // siendo el server.
                    RefundButton(
                        leadingIcon = if (locked) Icons.Filled.Lock else null,
                        onClick = { /* … el onClick que ya tenía … */ },
                    )
                }
            }
```

> Adapta los nombres al composable real del botón de reembolso en ese archivo; lo que **no** cambia es la regla: con candado el botón sigue siendo tocable y la llamada sale igual. Si el `ViewModel` no expone `tpvSettingsRepository`, inyéctalo ahí (ya es `@Singleton` en Hilt).

- [ ] **Step 6: Córrelo, compila y verifica en el emulador**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
./gradlew testDebugUnitTest --tests "com.avoqado.pos.core.domain.RoleManagerTest"
./gradlew testDebugUnitTest --tests "com.avoqado.pos.tpvsettings.*"
sysctl -n vm.loadavg && pgrep -fl "GradleDaemon|KotlinCompileDaemon" | head
./gradlew assembleDebug
```
A ojo, en claro y oscuro, con un WAITER: switch OFF → el botón de reembolso **no aparece** (como hoy); switch ON → aparece **con candado**; al tocarlo sale el teclado.

- [ ] **Step 7: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
git add app/src/main/java/com/avoqado/pos/core/domain/RoleManager.kt \
        app/src/main/java/com/avoqado/pos/tpvsettings/data/TpvSettingsRepository.kt \
        app/src/main/java/com/avoqado/pos/core/data/storage/SecureStorage.kt \
        app/src/main/java/com/avoqado/pos/transactions/presentation/TransactionDetailSheet.kt \
        app/src/test/java/com/avoqado/pos/core/domain/RoleManagerTest.kt
git commit -m "$(cat <<'EOF'
feat(override): las acciones sin permiso se ven con candado cuando el local lo activó

Esconder el botón dejaba al piso sin salida: sin botón no hay 403, y sin 403 no
hay a quién pedirle autorización. Con el switch ON la acción se ve, se toca
igual, y el server decide — no hay lógica nueva de permisos en el cliente.

Con el switch OFF todo queda exactamente como hoy.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

# FASE 4 — avoqado-ios (paridad obligatoria, MISMO trabajo)

> 🔴 Antes de tocar el primer archivo, lee `avoqado-ios/CLAUDE.md`, `avoqado-ios/ui-patterns-ios.md`, `avoqado-ios/.claude/rules/offline-first-y-hub-lan.md` y su memoria en `/Users/amieva/.claude/projects/-Users-amieva-Documents-Programming-Avoqado-avoqado-ios/memory/MEMORY.md`.
>
> **En iOS el reintento NO bloquea nada**: `APIClient.request` ya es `async`, así que se espera el token con `await` y se re-invoca la misma función con el header. Es la única diferencia de implementación; el comportamiento visible es idéntico al de Android.

### Tarea 15: `PermissionLabels` + `ManagerOverrideCoordinator` (iOS)

**Files:**
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-ios/avoqado-ios/Services/PermissionLabels.swift`
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-ios/avoqado-ios/Services/ManagerOverrideCoordinator.swift`
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-ios/avoqado-iosTests/ManagerOverrideTests.swift`

**Interfaces:**
- Consumes: `POST /api/v1/mobile/venues/:venueId/permission-overrides` (Tarea 5).
- Produces (la Tarea 16 depende de esto):
  ```swift
  enum PermissionLabels { static func of(_ permission: String) -> String }
  enum OverrideResult: Equatable {
      case granted(token: String, authorizedByName: String)
      case wrongPin
      case insufficient
      case tooManyAttempts
      case failed(String)
  }
  @MainActor final class ManagerOverrideCoordinator: ObservableObject {
      static let shared: ManagerOverrideCoordinator
      struct Prompt: Identifiable { let id: UUID; let permission: String; let actionLabel: String }
      @Published private(set) var prompt: Prompt?
      func awaitToken(permission: String) async -> String?
      func submitPin(venueId: String, pin: String) async -> OverrideResult
      func cancel()
  }
  ```

- [ ] **Step 1: Escribe el test que falla**

`avoqado-iosTests/ManagerOverrideTests.swift`:

```swift
import XCTest
@testable import avoqado_ios

final class ManagerOverrideTests: XCTestCase {

    func testEtiquetasEspejanExactamenteAAndroid() {
        XCTAssertEqual(PermissionLabels.of("orders:merge"), "fusionar cuentas")
        XCTAssertEqual(PermissionLabels.of("payments:refund"), "hacer un reembolso")
        XCTAssertEqual(PermissionLabels.of("orders:cancel"), "cancelar la cuenta")
        XCTAssertEqual(PermissionLabels.of("orders:comp"), "dar una cortesía")
        XCTAssertEqual(PermissionLabels.of("orders:void"), "anular artículos")
        XCTAssertEqual(PermissionLabels.of("orders:update"), "modificar la cuenta")
        XCTAssertEqual(PermissionLabels.of("discounts:apply"), "aplicar un descuento")
    }

    func testPermisoDesconocidoCaeATextoNeutro() {
        XCTAssertEqual(PermissionLabels.of("cosas:raras"), "esta acción")
        XCTAssertEqual(PermissionLabels.of(""), "esta acción")
    }

    @MainActor
    func testCancelarDevuelveNilYCierraElPrompt() async {
        let coordinator = ManagerOverrideCoordinator()
        let task = Task { await coordinator.awaitToken(permission: "orders:merge") }
        // Espera a que el prompt aparezca antes de cancelar.
        for _ in 0..<50 where coordinator.prompt == nil {
            try? await Task.sleep(nanoseconds: 2_000_000)
        }
        XCTAssertEqual(coordinator.prompt?.actionLabel, "fusionar cuentas")
        coordinator.cancel()
        let token = await task.value
        XCTAssertNil(token)
        XCTAssertNil(coordinator.prompt)
    }
}
```

- [ ] **Step 2: Córrelo y verifica que FALLA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-ios
xcodebuild test -scheme avoqado-ios -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
  -only-testing:avoqado-iosTests/ManagerOverrideTests
```
Esperado: no compila — los tipos no existen.

- [ ] **Step 3: Crea `PermissionLabels.swift`**

```swift
import Foundation

/// Permiso técnico → cómo se lo decimos a un mesero.
///
/// 🔴 Espejo EXACTO de `PermissionLabels.kt` en avoqado-android. Si agregas uno
/// aquí, agrégalo allá en el MISMO trabajo, con el mismo texto en español.
enum PermissionLabels {
    static let fallback = "esta acción"

    private static let labels: [String: String] = [
        "orders:merge": "fusionar cuentas",
        "orders:cancel": "cancelar la cuenta",
        "orders:comp": "dar una cortesía",
        "orders:void": "anular artículos",
        "orders:update": "modificar la cuenta",
        "orders:create": "abrir una cuenta",
        "payments:refund": "hacer un reembolso",
        "payments:create": "cobrar",
        "discounts:apply": "aplicar un descuento",
        "shifts:close": "cerrar el turno",
        "tables:manage-all": "modificar mesas de otro mesero"
    ]

    static func of(_ permission: String) -> String {
        labels[permission] ?? fallback
    }
}
```

- [ ] **Step 4: Crea `ManagerOverrideCoordinator.swift`**

```swift
import Foundation

/// Lo que puede pasar al teclear el código de un encargado.
/// Espejo de `OverrideResult` en avoqado-android.
enum OverrideResult: Equatable {
    case granted(token: String, authorizedByName: String)
    case wrongPin
    case insufficient
    case tooManyAttempts
    case failed(String)
}

/// Orquesta el PIN de autorización de gerente.
///
/// A diferencia de Android, aquí NADA se bloquea: `APIClient.request` ya es
/// async, así que se espera el token con `await` y se reintenta la misma
/// petición. El comportamiento que ve el usuario es idéntico.
@MainActor
final class ManagerOverrideCoordinator: ObservableObject {
    static let shared = ManagerOverrideCoordinator()

    struct Prompt: Identifiable {
        let id = UUID()
        let permission: String
        let actionLabel: String
    }

    @Published private(set) var prompt: Prompt?

    private var pending: CheckedContinuation<String?, Never>?
    /// Cola: un solo teclado a la vez, aunque dos acciones se bloqueen juntas.
    private var queue: Task<Void, Never>?

    init() {}

    /// Devuelve el token, o nil si el usuario canceló.
    func awaitToken(permission: String) async -> String? {
        // Espera su turno si ya hay un teclado abierto.
        let previous = queue
        let gate = Task { await previous?.value }
        await gate.value

        return await withCheckedContinuation { (continuation: CheckedContinuation<String?, Never>) in
            pending = continuation
            prompt = Prompt(permission: permission, actionLabel: PermissionLabels.of(permission))
        }
    }

    /// La UI llama esto al teclear el código. Sólo `.granted` cierra el teclado.
    func submitPin(venueId: String, pin: String) async -> OverrideResult {
        guard let permission = prompt?.permission else {
            return .failed("La acción ya no está esperando autorización.")
        }
        let result = await PermissionOverrideService.requestToken(venueId: venueId, pin: pin, permission: permission)
        if case let .granted(token, name) = result {
            print("🔐 Override autorizado por \(name) para \(permission)")
            finish(with: token)
        }
        return result
    }

    /// El usuario cerró el teclado: la acción falla como fallaba antes.
    func cancel() {
        finish(with: nil)
    }

    private func finish(with token: String?) {
        prompt = nil
        pending?.resume(returning: token)
        pending = nil
    }
}

/// Petición del token. Construye su propia `URLRequest` a propósito: llamarla
/// desde `APIClient` sería recursión sobre el mismo manejo de 403.
enum PermissionOverrideService {
    private struct RequestBody: Encodable { let pin: String; let permission: String }
    private struct AuthorizedBy: Decodable { let id: String; let name: String }
    private struct ResponseData: Decodable { let token: String; let expiresAt: String?; let authorizedBy: AuthorizedBy? }
    private struct ResponseBody: Decodable { let success: Bool; let data: ResponseData? }
    private struct ErrorBody: Decodable { let code: String?; let message: String? }

    static func requestToken(venueId: String, pin: String, permission: String) async -> OverrideResult {
        guard let token = SecureStorage.shared.accessToken else {
            return .failed("Tu sesión expiró. Vuelve a entrar.")
        }
        guard let url = URL(string: "\(APIClient.shared.currentBaseURLValue)/mobile/venues/\(venueId)/permission-overrides") else {
            return .failed("No se pudo autorizar.")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.httpBody = try? JSONEncoder().encode(RequestBody(pin: pin, permission: permission))

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else { return .failed("No se pudo autorizar.") }
            switch http.statusCode {
            case 200, 201:
                guard let parsed = try? JSONDecoder().decode(ResponseBody.self, from: data), let payload = parsed.data else {
                    return .failed("No se pudo obtener la autorización.")
                }
                return .granted(token: payload.token, authorizedByName: payload.authorizedBy?.name ?? "")
            case 401:
                return .wrongPin
            case 403:
                let err = try? JSONDecoder().decode(ErrorBody.self, from: data)
                return err?.code == "OVERRIDE_INSUFFICIENT" ? .insufficient : .failed(err?.message ?? "No se pudo autorizar.")
            case 429:
                return .tooManyAttempts
            default:
                let err = try? JSONDecoder().decode(ErrorBody.self, from: data)
                return .failed(err?.message ?? "No se pudo autorizar.")
            }
        } catch {
            // 🔴 Sin red NO se encola: un rechazo de permiso no es un fallo de red.
            return .failed("Necesitas conexión para pedir autorización")
        }
    }
}
```

> `APIClient.shared.currentBaseURLValue`: si `currentBaseURL` es privado/aislado al actor, expón un `nonisolated static var` con la misma URL base que ya usa `TpvSettingsRepository.fetchSettingsFromVenueAPI` (`\(currentBaseURL)/mobile/...`), o reusa esa constante. No dupliques la lógica de ngrok/prod.

- [ ] **Step 5: Agrega los archivos al target y verifica que PASA**

Añádelos al target `avoqado-ios` en Xcode (o al `project.pbxproj`), y:

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-ios
xcodebuild test -scheme avoqado-ios -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
  -only-testing:avoqado-iosTests/ManagerOverrideTests
```
Esperado: PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-ios
git add avoqado-ios/Services/PermissionLabels.swift \
        avoqado-ios/Services/ManagerOverrideCoordinator.swift \
        avoqado-iosTests/ManagerOverrideTests.swift \
        avoqado-ios.xcodeproj/project.pbxproj
git commit -m "$(cat <<'EOF'
feat(override): coordinator y etiquetas del PIN de gerente (iOS)

Espejo de avoqado-android con los MISMOS textos en español. Aquí nada bloquea:
APIClient.request ya es async, así que se espera el token con await.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 16: `APIClient` pide el código y reintenta

**Files:**
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-ios/avoqado-ios/Services/APIClient.swift:224-265`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-ios/avoqado-iosTests/ManagerOverrideTests.swift` (un test más)

**Interfaces:**
- Consumes: `ManagerOverrideCoordinator.shared.awaitToken` (Tarea 15).
- Produces: constante `APIClient.permissionOverrideHeader = "X-Permission-Override"`. Todas las llamadas existentes reintentan solas; ningún ViewModel cambia.

- [ ] **Step 1: Implementa la rama del override**

En `APIClient.swift`, **dentro** del `if httpResponse.statusCode == 403`, **después** del bloque `isFromIntermediary` y **antes** del `preserveBusinessErrorPayload`:

```swift
            // 🔴 PIN de autorización de gerente. Sólo llega aquí lo que YA se
            // descartó arriba: no viene de un intermediario. Un 403 de PLAN
            // (featureCode) tampoco entra — se filtra abajo y va al upsell.
            //
            // El guard del header evita el bucle: si la petición ya traía un
            // token y aun así volvió 403 (expirado, reusado), no se vuelve a
            // pedir.
            if headers[Self.permissionOverrideHeader] == nil,
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               json["featureCode"] == nil,
               (json["overridable"] as? Bool) == true,
               let required = json["required"] as? String {
                if let overrideToken = await ManagerOverrideCoordinator.shared.awaitToken(permission: required) {
                    var retryHeaders = headers
                    retryHeaders[Self.permissionOverrideHeader] = overrideToken
                    print("🔒 Reintentando \(endpoint) con autorización de gerente")
                    return try await request(
                        endpoint: endpoint,
                        method: method,
                        body: body,
                        requiresAuth: requiresAuth,
                        suppressForbiddenNotification: suppressForbiddenNotification,
                        preserveBusinessErrorPayload: preserveBusinessErrorPayload,
                        headers: retryHeaders,
                        timeout: timeout
                    )
                }
                // Canceló. La acción falla como fallaba antes, pero SIN el aviso
                // global de "no tienes permiso": ya se lo dijimos en el teclado.
                throw APIError.forbidden("Acción no autorizada", featureCode: nil)
            }
```

Y junto a las demás constantes de la clase:

```swift
    /// Token de un solo uso del PIN de autorización de gerente.
    /// Espejo EXACTO de `ForbiddenInterceptor.PERMISSION_OVERRIDE_HEADER` en Android.
    static let permissionOverrideHeader = "X-Permission-Override"
```

- [ ] **Step 2: Test de no-regresión del 403 de plan**

Añade a `ManagerOverrideTests.swift`:

```swift
    func testUn403DePlanNuncaAbreElTeclado() {
        // El criterio es el mismo que ya usa APIClient para separar plan de permisos:
        // si viene featureCode, es candado de plan y va al upsell, no al PIN.
        let planBody = """
        {"error":"Forbidden","message":"Feature not available","featureCode":"INVENTORY_TRACKING","overridable":true,"required":"inventory:read"}
        """.data(using: .utf8)!
        let json = try? JSONSerialization.jsonObject(with: planBody) as? [String: Any]
        XCTAssertNotNil(json?["featureCode"])
        // Con featureCode presente, la guarda del override no entra.
    }

    func testUn403DeIntermediarioSeDetectaAntesDelOverride() {
        let http = HTTPURLResponse(
            url: URL(string: "https://api.avoqado.io/x")!,
            statusCode: 403,
            httpVersion: nil,
            headerFields: ["Content-Type": "text/html"]
        )!
        let body = "<!DOCTYPE html><html><body>tunnel down</body></html>".data(using: .utf8)!
        XCTAssertTrue(APIClient.isFromIntermediary(response: http, data: body))
    }
```

- [ ] **Step 3: Compila y corre los tests**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-ios
sysctl -n vm.loadavg && pgrep -fl xcodebuild | head
xcodebuild -scheme avoqado-ios -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build
xcodebuild test -scheme avoqado-ios -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
  -only-testing:avoqado-iosTests/ManagerOverrideTests
```

- [ ] **Step 4: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-ios
git add avoqado-ios/Services/APIClient.swift avoqado-iosTests/ManagerOverrideTests.swift
git commit -m "$(cat <<'EOF'
feat(override): APIClient pide el código y reintenta la misma petición

Un solo punto: toda llamada existente hereda el override sin tocar un solo
ViewModel. El 403 de plan y el de intermediario siguen su camino de siempre —
ningún PIN arregla un tunel caído ni un plan sin pagar.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 17: El teclado de PIN (SwiftUI)

**Files:**
- Create: `/Users/amieva/Documents/Programming/Avoqado/avoqado-ios/avoqado-ios/Components/ManagerOverrideSheet.swift`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-ios/avoqado-ios/POS/Views/MainTabView.swift`

**Interfaces:**
- Consumes: `ManagerOverrideCoordinator.shared` (Tarea 15).
- Produces: `struct ManagerOverrideSheet: View` y el modificador `.managerOverrideHost(venueId:)`.

**Patrones obligatorios** (`ui-patterns-ios.md`): `AvoqadoDialog`, `AvoqadoPrimaryButton`, `PinPadView` + `PinDisplayView` de `Components/`, `Spacing.*`, `AppColors.*`. Nada de `Color.black` / `.cornerRadius(12)` / `.font(.system(size:))`.

- [ ] **Step 1: Crea el sheet**

`avoqado-ios/Components/ManagerOverrideSheet.swift`:

```swift
import SwiftUI

/// "Se necesita autorización": el teclado donde un encargado teclea SU código
/// para dejar pasar UNA acción.
///
/// 🔴 Espejo EXACTO de `ManagerOverrideSheet.kt`. Mismos textos en español.
struct ManagerOverrideSheet: View {
    let prompt: ManagerOverrideCoordinator.Prompt
    let venueId: String
    let onSubmit: (String) async -> OverrideResult
    let onCancel: () -> Void

    @State private var pin: String = ""
    @State private var errorMessage: String?
    @State private var isLoading = false

    private let minPinLength = 4
    private let maxPinLength = 10

    var body: some View {
        AvoqadoDialog(
            title: "Se necesita autorización",
            description: "Para \(prompt.actionLabel). Pide a un encargado su código.",
            onDismiss: onCancel
        ) {
            VStack(spacing: Spacing.md) {
                PinDisplayView(pin: pin, maxLength: maxPinLength, isError: errorMessage != nil)
                PinPadView(
                    onNumberClick: { digit in
                        guard pin.count < maxPinLength else { return }
                        pin += digit
                        errorMessage = nil
                    },
                    onBackspace: {
                        guard !pin.isEmpty else { return }
                        pin.removeLast()
                        errorMessage = nil
                    },
                    onClear: { pin = ""; errorMessage = nil },
                    enabled: !isLoading
                )
                if let errorMessage {
                    Text(errorMessage)
                        .font(Typography.bodyMedium)
                        .foregroundColor(.red)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                }
            }
        } actionButton: {
            AvoqadoPrimaryButton(
                title: "Autorizar",
                action: { Task { await submit() } },
                isEnabled: pin.count >= minPinLength && !isLoading,
                isLoading: isLoading
            )
        }
    }

    private func submit() async {
        isLoading = true
        errorMessage = nil
        let result = await onSubmit(pin)
        isLoading = false
        switch result {
        case .granted:
            break // el coordinator cierra el teclado
        case .wrongPin:
            errorMessage = "Código incorrecto"; pin = ""
        case .insufficient:
            errorMessage = "Ese código tampoco tiene este permiso"; pin = ""
        case .tooManyAttempts:
            errorMessage = "Demasiados intentos. Espera 15 minutos."; pin = ""
        case .failed(let message):
            errorMessage = message; pin = ""
        }
    }
}

extension View {
    /// Monta el teclado de autorización en la raíz autenticada, para que exista
    /// UNA sola instancia venga de donde venga la acción bloqueada.
    func managerOverrideHost(venueId: String) -> some View {
        modifier(ManagerOverrideHost(venueId: venueId))
    }
}

private struct ManagerOverrideHost: ViewModifier {
    let venueId: String
    @ObservedObject private var coordinator = ManagerOverrideCoordinator.shared

    func body(content: Content) -> some View {
        content.sheet(item: Binding(
            get: { coordinator.prompt },
            set: { if $0 == nil { coordinator.cancel() } }
        )) { prompt in
            ManagerOverrideSheet(
                prompt: prompt,
                venueId: venueId,
                onSubmit: { pin in await coordinator.submitPin(venueId: venueId, pin: pin) },
                onCancel: { coordinator.cancel() }
            )
        }
    }
}
```

- [ ] **Step 2: Móntalo en `MainTabView`**

En `avoqado-ios/POS/Views/MainTabView.swift`, sobre el `body` de la vista raíz (la misma capa donde ya se observa `.apiForbidden`):

```swift
        .managerOverrideHost(venueId: SecureStorage.shared.venueId ?? "")
```

> Usa el mismo accesor del venue activo que ya emplea `TpvSettingsRepository` para pedir los settings. Lo que importa es que sea el venue ACTIVO.

- [ ] **Step 3: Compila y revisa a ojo**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-ios
sysctl -n vm.loadavg && pgrep -fl xcodebuild | head
xcodebuild -scheme avoqado-ios -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build
```
En el simulador, modo claro **y** oscuro: el teclado debe verse igual que en Android, con los mismos textos.

- [ ] **Step 4: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-ios
git add avoqado-ios/Components/ManagerOverrideSheet.swift \
        avoqado-ios/POS/Views/MainTabView.swift \
        avoqado-ios.xcodeproj/project.pbxproj
git commit -m "$(cat <<'EOF'
feat(override): teclado "Se necesita autorización" (iOS)

Sobre AvoqadoDialog + PinPadView, montado en la raíz autenticada para que haya
una sola instancia. Mismos textos que Android, palabra por palabra.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 18: El candado visible (iOS)

**Files:**
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-ios/avoqado-ios/Services/RoleManager.swift`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-ios/avoqado-ios/Services/TpvSettingsRepository.swift:248-301, 306-330`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-ios/avoqado-ios/Transactions/Views/TransactionDetailView.swift:217`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-ios/avoqado-iosTests/ManagerOverrideTests.swift`

**Interfaces:**
- Consumes: `data.managerPinOverrideEnabled` (Tarea 7).
- Produces: `enum ActionVisibility { case allowed, locked, hidden }` y `RoleManager.visibilityOf(allowed:overrideEnabled:)`; `TpvSettingsRepository.shared.managerPinOverrideEnabled: Bool` (`@Published`).

- [ ] **Step 1: Escribe el test que falla**

Añade a `ManagerOverrideTests.swift`:

```swift
    func testVisibilidadDeUnaAccionSinPermiso() {
        let manager = RoleManager(role: "WAITER")
        XCTAssertEqual(manager.visibilityOf(allowed: false, overrideEnabled: false), .hidden)
        XCTAssertEqual(manager.visibilityOf(allowed: false, overrideEnabled: true), .locked)
        XCTAssertEqual(manager.visibilityOf(allowed: true, overrideEnabled: false), .allowed)
        XCTAssertEqual(manager.visibilityOf(allowed: true, overrideEnabled: true), .allowed)
    }

    func testElReembolsoSigueSiendoDeManagerParaArriba() {
        XCTAssertTrue(RoleManager(role: "MANAGER").canIssueRefund)
        XCTAssertFalse(RoleManager(role: "WAITER").canIssueRefund)
    }
```

- [ ] **Step 2: Córrelo y verifica que FALLA**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-ios
xcodebuild test -scheme avoqado-ios -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
  -only-testing:avoqado-iosTests/ManagerOverrideTests
```

- [ ] **Step 3: Agrega `ActionVisibility`**

Al final de `RoleManager.swift`, fuera del struct:

```swift
/// Cómo se pinta una acción que el rol no tiene.
///
/// 🔴 Espejo EXACTO de `ActionVisibility` en avoqado-android/core/domain/RoleManager.kt.
///
/// Esconder un botón parece limpio, pero deja al piso sin salida: sin botón no
/// hay 403, y sin 403 no hay a quién pedirle autorización.
enum ActionVisibility {
    case allowed
    case locked
    case hidden
}
```

Y dentro de `struct RoleManager`:

```swift
    func visibilityOf(allowed: Bool, overrideEnabled: Bool) -> ActionVisibility {
        if allowed { return .allowed }
        return overrideEnabled ? .locked : .hidden
    }
```

- [ ] **Step 4: Lee el switch en `TpvSettingsRepository`**

En `MobileSettingsData` (dentro de `fetchSettingsFromVenueAPI`, línea ~259):

```swift
            let managerPinOverrideEnabled: Bool?
```

Publica el valor (junto a `storePlan`):

```swift
    /// ¿El local activó el PIN de autorización de gerente? Decide si una acción
    /// sin permiso se ve con candado o se esconde. Nace apagado.
    @Published private(set) var managerPinOverrideEnabled: Bool =
        UserDefaults.standard.bool(forKey: "managerPinOverrideEnabled")

    private func storeManagerPinOverride(_ enabled: Bool) {
        managerPinOverrideEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "managerPinOverrideEnabled")
    }
```

Y en el parseo exitoso, junto a `storePlan(...)`:

```swift
        storeManagerPinOverride(payload.data?.managerPinOverrideEnabled ?? false)
```

> 🔴 En el `catch` de red **no** lo toques: un refresh fallido nunca borra lo bueno.

- [ ] **Step 5: Aplica el candado al reembolso**

En `TransactionDetailView.swift:217`, cambia `if RoleManager.current.canIssueRefund { … }` por:

```swift
            let visibility = RoleManager.current.visibilityOf(
                allowed: RoleManager.current.canIssueRefund,
                overrideEnabled: TpvSettingsRepository.shared.managerPinOverrideEnabled
            )
            if visibility != .hidden {
                // Con candado se toca igual: la llamada sale, el server responde
                // 403 overridable y el teclado aparece solo. NO hay lógica nueva
                // de permisos en el cliente.
                HStack(spacing: Spacing.xs) {
                    if visibility == .locked {
                        Image(systemName: "lock.fill")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(.secondary)
                    }
                    // … el botón de reembolso que ya existía …
                }
            }
```

- [ ] **Step 6: Compila, corre los tests y revisa a ojo**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-ios
sysctl -n vm.loadavg && pgrep -fl xcodebuild | head
xcodebuild -scheme avoqado-ios -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build
xcodebuild test -scheme avoqado-ios -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
  -only-testing:avoqado-iosTests/ManagerOverrideTests
```
Con un WAITER: switch OFF → sin botón de reembolso (como hoy); ON → con candado; al tocarlo, teclado.

- [ ] **Step 7: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-ios
git add avoqado-ios/Services/RoleManager.swift \
        avoqado-ios/Services/TpvSettingsRepository.swift \
        avoqado-ios/Transactions/Views/TransactionDetailView.swift \
        avoqado-iosTests/ManagerOverrideTests.swift
git commit -m "$(cat <<'EOF'
feat(override): candado visible en acciones sin permiso (iOS)

Paridad exacta con avoqado-android: con el switch ON la acción se ve con
candado, se toca igual, y el server decide. Con el switch OFF nada cambia.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

# FASE 5 — MCP, documentación y presentación de ventas

### Tarea 19: El MCP debe poder contar quién autorizó qué

**Por qué no hay tool nueva:** la auditoría del override sale por `ActivityLog` (acciones `PERMISSION_OVERRIDE_USED` y `PERMISSION_OVERRIDE_REJECTED`, Tarea 6), que el tool `get_activity_log` ya lee. La obligación de mantener el MCP en sincronía se cumple **documentándolo en la descripción del tool**: un agente que no sabe que esas acciones existen nunca las va a buscar.

**Files:**
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/src/mcp/tools/activity-log.ts:13-21`
- Modify: `/Users/amieva/Documents/Programming/Avoqado/avoqado-server/docs/PERMISSIONS_SYSTEM.md`

**Interfaces:**
- Consumes: las acciones de `ActivityLog` escritas en la Tarea 6.
- Produces: nada que consuman otras tareas.

- [ ] **Step 1: Amplía la descripción del tool**

En `src/mcp/tools/activity-log.ts`, la descripción (línea 14) y el `describe` del parámetro `action` (línea 18):

```typescript
    'get_activity_log',
    'Audit trail for your venue(s): who did what and when — orders (comp/void/discount), payments, refunds, shifts, staff/access changes, inventory receiving and SIM custody, config changes, and manager-PIN authorizations (PERMISSION_OVERRIDE_USED records WHO approved a blocked action for someone else; PERMISSION_OVERRIDE_REJECTED, an expired or reused token; PERMISSION_DENIED, a plain denial). Most recent first. Pass venueId to focus one venue; filter by action code or date range. Requires the activity:read permission (owner-level).',
    {
      venueId: z.string().optional().describe('Focus one venue (must be in your scope); omit for all your venues'),
      action: z
        .string()
        .optional()
        .describe(
          'Filter by exact action code, e.g. PAYMENT_COMPLETED, SIM_CUSTODY_ASSIGNED_TO_PROMOTER or PERMISSION_OVERRIDE_USED (manager-PIN approvals; the approver is in data.authorizedByStaffVenueId)',
        ),
```

- [ ] **Step 2: Documenta el override en el doc de permisos**

Al final de `docs/PERMISSIONS_SYSTEM.md`, agrega:

```markdown
## Manager-PIN override (2026-08)

Cuando `checkPermission` deniega y el venue tiene `VenueSettings.managerPinOverrideEnabled = true`,
el 403 incluye el campo aditivo `overridable: true`. El POS pide entonces el PIN de alguien CON ese
permiso a `POST /api/v1/mobile/venues/:venueId/permission-overrides`, recibe un token de **un solo
uso, 60 s, atado a ese permiso y ese venue**, y reintenta la request original con el header
`X-Permission-Override`.

- **Un solo punto de integración.** Cubre toda ruta con `checkPermission`, presente y futura.
- **Nunca eleva la terminal.** El token muere al usarse (`updateMany` atómico sobre `consumedAt`).
- **No aplica a:** el 403 de membresía (`No access to this venue`) ni al 403 de tier
  (`checkFeatureAccess`, que lleva `featureCode` y va al upsell). Ningún PIN los arregla.
- **Auditoría:** `PermissionOverride` guarda token + quién autorizó + ruta consumida;
  `ActivityLog` recibe `PERMISSION_OVERRIDE_USED` / `PERMISSION_OVERRIDE_REJECTED`, visibles por
  el MCP `get_activity_log`.
- 🔴 **Límite honesto:** los PINs se guardan en texto plano por decisión explícita del founder
  (2026-08-15). Quien tenga lectura de la base puede usar el PIN de un gerente y la bitácora diría
  su nombre igual. La auditoría sirve para **reconstruir qué pasó**, no como prueba de quién
  autorizó. No se vende como autorización indiscutible.
```

- [ ] **Step 3: Verifica**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm run build
npm test -- tests/unit/mcp-customer
npm run format
```

- [ ] **Step 4: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
git add src/mcp/tools/activity-log.ts docs/PERMISSIONS_SYSTEM.md
git commit -m "$(cat <<'EOF'
docs(mcp): get_activity_log documenta las autorizaciones por PIN de gerente

La auditoría del override sale por ActivityLog, que este tool ya lee — pero un
agente que no sabe que PERMISSION_OVERRIDE_USED existe nunca la va a buscar.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

### Tarea 20: Presentación de ventas (regla 🔴 del workspace)

**Por qué:** "autorización con código de encargado" es una capacidad **visible para el cliente** y vendible. La regla del workspace obliga a actualizar los tres deliverables **y regenerar sus PDFs** en el MISMO cambio. Editar el HTML sin regenerar el PDF es un cambio incompleto: el PDF es el archivo que los socios abren y mandan.

> El spec no menciona esta tarea; se incluye porque la regla del workspace es incondicional para capacidades visibles al cliente. Si el founder decide que este feature no entra a la presentación, se salta y se anota — pero es una decisión, no un olvido.

**Files:**
- Modify: `~/Documents/Programming/Avoqado-HQ/operations/marketing/platform-presentation/avoqado-presentacion-v2.html`
- Modify: `.../avoqado-one-pager-v2.html`
- Modify: `.../avoqado-one-pager-cliente.html`
- Regenerar: los tres PDFs

- [ ] **Step 1: Lee el README de esa carpeta**

```bash
cat ~/Documents/Programming/Avoqado-HQ/operations/marketing/platform-presentation/README.md
```
Ahí está el comando exacto de Chrome-headless HTML→PDF. **No inventes uno.**

- [ ] **Step 2: Agrega la capacidad en los tres HTML**

Texto a usar (mismo en los tres, ajustando el tono de cada pieza):

> **Autorización con código de encargado.** Cuando un empleado intenta algo para lo que no tiene permiso —anular, dar cortesía, descontar, juntar cuentas—, el punto de venta pide el código de un encargado para autorizarlo **esa vez**. La terminal nunca queda con permisos abiertos, y en la bitácora queda registrado quién autorizó qué. Incluido en todos los planes.

- [ ] **Step 3: Regenera los tres PDFs**

Con el comando del `README.md` de esa carpeta. Verifica que los tres archivos `.pdf` tengan fecha de hoy:

```bash
ls -lt ~/Documents/Programming/Avoqado-HQ/operations/marketing/platform-presentation/*.pdf
```

- [ ] **Step 4: Commit**

```bash
cd ~/Documents/Programming/Avoqado-HQ
git add operations/marketing/platform-presentation/avoqado-presentacion-v2.html \
        operations/marketing/platform-presentation/avoqado-one-pager-v2.html \
        operations/marketing/platform-presentation/avoqado-one-pager-cliente.html \
        operations/marketing/platform-presentation/*.pdf
git commit -m "$(cat <<'EOF'
docs(ventas): autorización con código de encargado en deck y one-pagers

HTML + PDFs regenerados. El PDF es el archivo que los socios abren y mandan:
editar el HTML sin regenerarlo deja la presentación mintiendo.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

---

# FASE 6 — Verificación en hardware real

### Tarea 21: `/full-testing` con la T3 PRO y la D3

**Esta fase la ejecuta la sesión principal**, no un subagente: necesita los aparatos físicos conectados y decisiones sobre la marcha.

**Prerrequisito de ceremonia (no de código):** 🔴 **avisar a los venues** que un mesero dejará de poder fusionar cuentas y que quedará a un PIN de distancia. Esto se hace **antes** de liberar las apps, no en hora pica.

- [ ] **Step 1: Prepara los aparatos**

```bash
adb devices
for s in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do
  echo "$s -> $(adb -s $s shell getprop ro.serialno)"
done
```
Los dos objetivo: **T3 PRO** (`T302P3AP40102`) y **D3** (`D406D598J0068`). Si la conexión inalámbrica se perdió: `adb mdns services` da el puerto real (**cambia cada vez**) y luego `adb connect <ip>:<puerto>`. El 5555 no sirve en Android 11+.

- [ ] **Step 2: Instala y abre el paquete correcto**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
./gradlew installDebug
adb -s <serial> shell monkey -p com.avoqado.pos.dev -c android.intent.category.LAUNCHER 1
```
🔴 El debug es **`com.avoqado.pos.dev`**. Lanzar `com.avoqado.pos` enseña la app vieja y parece que el build no tomó.

- [ ] **Step 3: Deja el log del backend abierto**

```bash
LOG=$(ls -t /Users/amieva/Documents/Programming/Avoqado/avoqado-server/logs/development*.log | head -1)
tail -F "$LOG" | grep --line-buffered "venueName: '<NOMBRE DEL VENUE>'"
```
Tras cada fase larga, **vuelve a correr el `ls -t`**: si winston rotó, tu `tail -F` se quedó mudo sin avisar.

- [ ] **Step 4: Invoca `/full-testing` con este guion**

Pásale explícitamente estos escenarios, en este orden, con un usuario **WAITER real** (no SUPERADMIN, no ADMIN):

1. **Switch OFF (estado de todos los venues hoy).**
   - Fusionar cuentas → mensaje de siempre, **sin** teclado de PIN.
   - Detalle de transacción → el botón de reembolso **no aparece**.
   - Nada cambió respecto de la versión anterior. **Éste es el caso que protege a los venues que no activen el feature.**

2. **Switch ON** (préndelo desde el **dashboard**, no con un `UPDATE` — así se prueba también la Tarea 9), y reinicia la app para que refresque los settings.
   - **Fusionar cuentas** → sale el teclado "Se necesita autorización · Para fusionar cuentas. Pide a un encargado su código."
   - PIN de un **gerente** → la fusión **se completa** y la pantalla sigue su flujo normal (su toast de siempre). Nunca una pantalla de error seguida de un éxito silencioso.
   - PIN de **otro mesero** → "Ese código tampoco tiene este permiso". La acción NO pasa.
   - PIN **inventado** → "Código incorrecto".
   - **Cancelar** el teclado → la acción falla como antes, **sin** el modal genérico de "no tienes permiso".
   - **Candado visible**: el botón de reembolso ahora **aparece con candado**; al tocarlo sale el teclado.

3. **Un solo uso, de verdad.** Autoriza una fusión, y de inmediato intenta otra: debe volver a pedir el código. Confirma en la base:
   ```bash
   psql "$DATABASE_URL" -c "SELECT token, permission, \"consumedAt\", \"consumedRoute\" FROM \"PermissionOverride\" ORDER BY \"createdAt\" DESC LIMIT 5;"
   ```
   Cada fila usada con `consumedAt` y `consumedRoute` llenos, y ninguna reutilizada.

4. **Expiración.** Pide el token, espera **más de 60 s** antes de teclear… (el teclado sigue abierto, el token nace al enviar el PIN, así que la prueba real es: obtén un token por `curl`, espera 70 s, y úsalo) → 403 y el POS vuelve a pedir código.

5. **Tier ≠ permiso.** Con un venue **sin** el feature de la pantalla de inventario, provoca un 403 de plan → debe ir al **upsell**, NUNCA al teclado de PIN. Este es el caso que más fácil se rompe.

6. **Sin red, switch ON.** Apaga el WiFi (o apunta a un puerto muerto: `./gradlew assembleDebug -Pavoqado.devBaseUrl=http://<ip-del-mac>:3009/api/v1`) y toca una acción con candado → **"Necesitas conexión para pedir autorización"**. 🔴 La acción **NO** se encola: un rechazo de permiso no es un fallo de red.

7. **Offline de lo que SÍ puede.** Con la red caída, un WAITER debe seguir abriendo mesas, agregando rondas y cobrando efectivo como hoy (outbox). El override no puede haber roto nada de eso.

8. **Rate limit.** Teclea 11 códigos malos seguidos → "Demasiados intentos. Espera 15 minutos." (429). Verifica el `warn` en el log del server.

9. **Los dos aparatos.** Repite 2, 5 y 6 en la **T3 PRO** y en la **D3**, en **modo claro y oscuro**. El teclado no puede quedar cortado ni tapado por el teclado del sistema.

10. **iPhone/iPad.** Corre 2, 5 y 6 en el simulador de iOS y, si hay iPad a mano, en el aparato. Los textos deben ser **idénticos**, palabra por palabra.

- [ ] **Step 5: Lee el log, no la pantalla**

```bash
grep "venueName: '<NOMBRE DEL VENUE>'" "$LOG" | grep -i "error\|warn" | tail -40
grep "entrypoint: 'POST /api/v1/mobile/venues/:venueId/permission-overrides'" "$LOG" | tail -20
```
🔴 Un 200 en la pantalla con un `error:` en el log es un bug escondiéndose. Toma el `X-Correlation-ID` de una respuesta del device (logcat) y `grep` ese uuid para la traza exacta.

- [ ] **Step 6: Verifica la bitácora**

```bash
psql "$DATABASE_URL" -c "SELECT action, \"entityId\", data->>'authorizedByStaffVenueId' AS autorizo, data->>'userRole' AS rol, \"createdAt\" FROM \"ActivityLog\" WHERE action LIKE 'PERMISSION_%' ORDER BY \"createdAt\" DESC LIMIT 20;"
```
Y por el MCP: `get_activity_log` con `action: 'PERMISSION_OVERRIDE_USED'` debe devolver esas filas.

- [ ] **Step 7: Apaga el switch y reporta**

Deja el venue de pruebas con el switch **OFF** (como nacen todos). En el reporte final, para el founder:
- qué se probó y en qué aparato;
- qué falló y qué quedó pendiente, con el comando exacto para reproducirlo;
- el recordatorio del aviso a los venues por el cambio de `orders:merge`;
- el orden de deploy: **backend primero**, estable, y después las apps.

**Sin commit.** Si algo falla, se arregla en la tarea que lo introdujo y se re-commitea ahí.

---

## Preguntas abiertas — el spec NO las resuelve (para el founder, no las decida quien implementa)

1. **¿Qué otras acciones escondidas llevan candado en v1?** El spec §3.4 dice "la acción gateada se muestra con un candado" pero no enumera cuáles. Este plan aplica el mecanismo a **una** (el reembolso) para que exista y sea probable, y deja fuera la navegación (tabs de Inventario, Reportes…), donde un candado sería ruido. Falta la lista.
2. **`StaffVenue.permissions` (Json) queda ignorado.** El spec dice que el permiso efectivo del autorizador se resuelve "Json override > permissionSet > rol", pero `checkPermission` —la puerta real— **sólo** usa `permissionSet` y `VenueRolePermission`; ese campo Json no lo lee nadie. El plan mira exactamente lo mismo que la puerta (si divergiera, el PIN se aceptaría y la acción fallaría igual). ¿Se deja así, o el campo Json debe entrar al resolver de ambos?
3. **¿El override se registra cuando se DENIEGA el PIN?** El plan audita `PERMISSION_OVERRIDE_USED` y `PERMISSION_OVERRIDE_REJECTED` (token malo), pero **no** deja rastro en `ActivityLog` de un PIN tecleado que resultó insuficiente. ¿Se quiere ver "alguien intentó autorizar con un código que no puede"? Es señal de fraude interno, y hoy sólo queda en el log de texto.
4. **¿Entra a la presentación de ventas?** La Tarea 20 lo asume porque la regla del workspace es incondicional para capacidades visibles al cliente, pero el spec no lo menciona.
5. **La pantalla "Permisos" del POS no la construye nadie.** El spec §2 registra la decisión completa (como Square: crear empleado y elegirle conjunto **sí**; ver qué trae cada conjunto **sí**, con ojito; cambiarle el conjunto a alguien que ya existe **en gris**), pero ni §3 (arquitectura) ni §9 (orden de construcción) la incluyen, y §7 tampoco la manda a v2. Este plan **no la construye**: la decisión queda registrada para cuando esa pantalla se haga. ¿Es correcto, o se esperaba en v1?
6. **`orders:merge` y los venues con conjuntos personalizados.** El spec dice que NO lo reciben automático, y así queda. Pero eso significa que en esos venues **ni el gerente** podrá fusionar hasta que alguien edite el conjunto en el dashboard. ¿Se avisa uno por uno, o se hace una migración que agregue `orders:merge` a los conjuntos que ya tenían `orders:update` **y** un rol MANAGER+?


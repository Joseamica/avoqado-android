# Upsell — sugerencias que nacen muertas (A+B) — Plan de implementación

> **Para agentes:** SUB-SKILL REQUERIDO: usa `superpowers:subagent-driven-development`
> (recomendado) o `superpowers:executing-plans` para ejecutar tarea por tarea. Los pasos
> usan checkbox (`- [ ]`) para seguimiento.

**Objetivo:** Que el dashboard deje de crear reglas de upsell que el POS descarta en
silencio, y que un producto con opciones obligatorias pueda sugerirse resolviéndolas al
crear la regla.

**Arquitectura:** Un campo `suggestedModifiers` en `UpsellRule` guarda las opciones
obligatorias ya elegidas. El server valida al guardar (nunca se persiste una regla que el POS
va a ignorar) y las devuelve resueltas —con nombre y precio— al POS. El resolver de ambas
apps pasa de "tiene obligatorios → descarto" a "tiene obligatorios SIN resolver → descarto".

**Stack:** Express + Prisma + zod (server) · React + TanStack Query (dashboard) ·
Kotlin/Compose (Android) · SwiftUI (iOS).

**Spec:** `docs/superpowers/specs/2026-08-16-upsell-sugerencias-bloqueadas-design.md`
(léelo antes de la Tarea 1 — este plan argumenta desde ahí).

## Restricciones globales

- **Campos de API sólo ADITIVOS.** Jamás renombrar ni quitar: hay APKs viejos en la calle.
  `suggestedModifiers` es opcional y su ausencia = comportamiento de hoy.
- **Android e iOS se cambian JUNTOS**, en el mismo trabajo. Nombres de campo y textos en
  español idénticos entre las dos. (`avoqado-android/CLAUDE.md`)
- **Cualquier edición a `prisma/schema.prisma` regenera el mapa en el MISMO commit:**
  `cd avoqado-server && npm run schema:map`, y `docs/SCHEMA_MAP.md` va en ese commit.
- **Commits atómicos por rutas explícitas** (`git add <ruta>`), nunca `-A` ni `.`. Hay
  sesiones de IA en paralelo: si ves archivos modificados que no tocaste, ignóralos.
- **Verificación antes de cada commit:** server → los tests del módulo en verde;
  dashboard → `npx tsc -p tsconfig.app.json --noEmit` + `npx vitest run`;
  Android → `./gradlew testDebugUnitTest`; iOS → `xcodebuild ... build`.
- **Typecheck del server:** usa `NODE_OPTIONS=--max-old-space-size=8192 npx tsc --noEmit -p
  tsconfig.build.json`. El `tsc` pelón revienta por memoria en este repo.
- **Textos de UI en español.** Nada de cadenas en inglés visibles al usuario.
- **Máquina compartida:** antes de un build pesado, `sysctl -n vm.loadavg`. Si está saturada,
  corre igual y avisa que tardará. Nunca dos builds pesados propios a la vez.

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `avoqado-server/prisma/schema.prisma` | Campo `suggestedModifiers Json?` en `UpsellRule` |
| `avoqado-server/src/services/upsell/upsellModifiers.ts` | **NUEVO** — validar y resolver la selección. Toda la lógica nueva vive aquí, aislada y testeable sin HTTP |
| `avoqado-server/src/services/upsell/upsell.service.ts` | Llama al validador en create/update; incluye los modificadores resueltos en `PosUpsellRuleDTO` |
| `avoqado-web-dashboard/src/pages/Promotions/Upsell.tsx` | Selector que muestra todo con motivos + paso de opciones obligatorias |
| `avoqado-web-dashboard/src/lib/upsell/suggestability.ts` | **NUEVO** — espejo de los 4 filtros del POS, puro y testeable |
| `avoqado-android/.../pos/data/model/UpsellRule.kt` | Campo nuevo en el DTO |
| `avoqado-android/.../pos/domain/UpsellResolver.kt` | Filtro condicionado a "sin resolver" |
| `avoqado-ios/.../Services/UpsellRule.swift` + resolver | Espejo exacto |

---

## Tarea 1: El validador de selección (server, aislado)

**Archivos:**
- Crear: `avoqado-server/src/services/upsell/upsellModifiers.ts`
- Test: `avoqado-server/tests/unit/services/upsell/upsellModifiers.test.ts`

**Interfaces:**
- Consume: nada (función pura sobre datos que le pasan).
- Produce:
  ```ts
  export interface SuggestedModifierSelection { groupId: string; modifierId: string }
  export interface ResolvedModifier { groupId: string; modifierId: string; name: string; price: number }
  export interface ProductForValidation {
    id: string
    soldByWeight: boolean
    upsellEnabled: boolean | null
    modifierGroups: Array<{ group: { id: string; name: string; required: boolean; modifiers: Array<{ id: string; name: string; price: unknown; active?: boolean }> } }>
  }
  export class UpsellModifierError extends Error { readonly code: string }
  export function validateAndResolveModifiers(
    product: ProductForValidation,
    selection: SuggestedModifierSelection[] | null | undefined,
  ): ResolvedModifier[]
  ```

- [ ] **Paso 1: Escribir los tests que fallan**

```ts
// avoqado-server/tests/unit/services/upsell/upsellModifiers.test.ts
import { validateAndResolveModifiers, UpsellModifierError } from '@/services/upsell/upsellModifiers'

const grupo = (id: string, required: boolean, mods: Array<{ id: string; name: string; price: number; active?: boolean }>) => ({
  group: { id, name: `Grupo ${id}`, required, modifiers: mods.map(m => ({ active: true, ...m })) },
})

const producto = (groups: any[], extra: Partial<any> = {}) => ({
  id: 'prod_1',
  soldByWeight: false,
  upsellEnabled: true,
  modifierGroups: groups,
  ...extra,
})

describe('validateAndResolveModifiers', () => {
  it('producto sin grupos obligatorios y sin selección → lista vacía', () => {
    expect(validateAndResolveModifiers(producto([]), null)).toEqual([])
  })

  it('ignora los grupos OPCIONALES: no hay que resolverlos', () => {
    const p = producto([grupo('g_op', false, [{ id: 'm1', name: 'Extra', price: 10 }])])
    expect(validateAndResolveModifiers(p, null)).toEqual([])
  })

  // 🔴 El caso del founder: "Agua Mineral 1L" con el grupo "Tamaño" obligatorio.
  it('grupo OBLIGATORIO sin resolver → error MISSING_REQUIRED_MODIFIER con el nombre del grupo', () => {
    const p = producto([grupo('g_tam', true, [{ id: 'm_ch', name: 'Chico', price: 0 }])])
    expect(() => validateAndResolveModifiers(p, null)).toThrow(UpsellModifierError)
    try {
      validateAndResolveModifiers(p, null)
    } catch (e: any) {
      expect(e.code).toBe('MISSING_REQUIRED_MODIFIER')
      // El mensaje va a la UI: tiene que nombrar QUÉ falta, no un id.
      expect(e.message).toContain('Grupo g_tam')
    }
  })

  it('grupo obligatorio resuelto → devuelve nombre y precio para pintar la tarjeta', () => {
    const p = producto([grupo('g_tam', true, [
      { id: 'm_ch', name: 'Chico', price: 0 },
      { id: 'm_gr', name: 'Grande', price: 15 },
    ])])
    expect(validateAndResolveModifiers(p, [{ groupId: 'g_tam', modifierId: 'm_gr' }])).toEqual([
      { groupId: 'g_tam', modifierId: 'm_gr', name: 'Grande', price: 15 },
    ])
  })

  it('modificador que NO pertenece al grupo → MODIFIER_NOT_IN_GROUP', () => {
    const p = producto([
      grupo('g_tam', true, [{ id: 'm_ch', name: 'Chico', price: 0 }]),
      grupo('g_otro', false, [{ id: 'm_x', name: 'Ajeno', price: 5 }]),
    ])
    expect(() => validateAndResolveModifiers(p, [{ groupId: 'g_tam', modifierId: 'm_x' }])).toThrow(
      expect.objectContaining({ code: 'MODIFIER_NOT_IN_GROUP' }),
    )
  })

  it('modificador INACTIVO → MODIFIER_INACTIVE (la tarjeta ofrecería algo que ya no se vende)', () => {
    const p = producto([grupo('g_tam', true, [{ id: 'm_ch', name: 'Chico', price: 0, active: false }])])
    expect(() => validateAndResolveModifiers(p, [{ groupId: 'g_tam', modifierId: 'm_ch' }])).toThrow(
      expect.objectContaining({ code: 'MODIFIER_INACTIVE' }),
    )
  })

  it('resuelve TODOS los obligatorios, no sólo el primero', () => {
    const p = producto([
      grupo('g1', true, [{ id: 'a', name: 'A', price: 1 }]),
      grupo('g2', true, [{ id: 'b', name: 'B', price: 2 }]),
    ])
    expect(() => validateAndResolveModifiers(p, [{ groupId: 'g1', modifierId: 'a' }])).toThrow(
      expect.objectContaining({ code: 'MISSING_REQUIRED_MODIFIER' }),
    )
    expect(
      validateAndResolveModifiers(p, [
        { groupId: 'g1', modifierId: 'a' },
        { groupId: 'g2', modifierId: 'b' },
      ]),
    ).toHaveLength(2)
  })

  // Estos dos NO se pueden salvar eligiendo opciones: el POS los descarta igual.
  it('producto vetado por el dueño → PRODUCT_NOT_SUGGESTABLE', () => {
    const p = producto([], { upsellEnabled: false })
    expect(() => validateAndResolveModifiers(p, null)).toThrow(
      expect.objectContaining({ code: 'PRODUCT_NOT_SUGGESTABLE' }),
    )
  })

  it('producto que se vende por peso → PRODUCT_NOT_SUGGESTABLE', () => {
    const p = producto([], { soldByWeight: true })
    expect(() => validateAndResolveModifiers(p, null)).toThrow(
      expect.objectContaining({ code: 'PRODUCT_NOT_SUGGESTABLE' }),
    )
  })

  it('el precio de Prisma (Decimal) se normaliza a número', () => {
    const p = producto([grupo('g', true, [{ id: 'm', name: 'X', price: { toString: () => '12.50' } as any }])])
    expect(validateAndResolveModifiers(p, [{ groupId: 'g', modifierId: 'm' }])[0].price).toBe(12.5)
  })
})
```

- [ ] **Paso 2: Correr el test y verlo FALLAR**

Correr: `cd avoqado-server && npx jest tests/unit/services/upsell/upsellModifiers.test.ts`
Esperado: FAIL — `Cannot find module '@/services/upsell/upsellModifiers'`

- [ ] **Paso 3: Implementar**

```ts
// avoqado-server/src/services/upsell/upsellModifiers.ts

/**
 * Opciones obligatorias de una sugerencia de upsell (spec 2026-08-16, decisión B3).
 *
 * 🔴 La elección vive en la REGLA, no en el producto: el mismo Agua Mineral puede
 * sugerirse chica en una regla y grande en otra sin tocar el catálogo. Por eso NO
 * existe un `Modifier.isDefault` — y no debe agregarse para esto.
 *
 * El POS descarta una tarjeta que abriría un formulario (regla de Square: un
 * artículo con obligatorios SIEMPRE abre su pantalla de detalle). Resolver aquí las
 * opciones es lo que permite que la tarjeta entre de UN toque.
 */

export interface SuggestedModifierSelection {
  groupId: string
  modifierId: string
}

export interface ResolvedModifier {
  groupId: string
  modifierId: string
  name: string
  price: number
}

export interface ProductForValidation {
  id: string
  soldByWeight: boolean
  upsellEnabled: boolean | null
  modifierGroups: Array<{
    group: {
      id: string
      name: string
      required: boolean
      modifiers: Array<{ id: string; name: string; price: unknown; active?: boolean }>
    }
  }>
}

export class UpsellModifierError extends Error {
  constructor(
    readonly code:
      | 'PRODUCT_NOT_SUGGESTABLE'
      | 'MISSING_REQUIRED_MODIFIER'
      | 'MODIFIER_NOT_IN_GROUP'
      | 'MODIFIER_INACTIVE',
    message: string,
  ) {
    super(message)
    this.name = 'UpsellModifierError'
  }
}

/** Prisma devuelve Decimal; el DTO viaja como número. */
function toNumber(price: unknown): number {
  return typeof price === 'number' ? price : Number(String(price ?? 0))
}

/**
 * Valida la selección contra el producto y la devuelve resuelta (con nombre y
 * precio) para que el POS pinte la tarjeta sin recalcular nada.
 *
 * NO valida existencias: el stock es transitorio y cambia solo — bloquear la regla
 * por algo que mañana se resuelve sería absurdo. Ese filtro se queda en el POS.
 */
export function validateAndResolveModifiers(
  product: ProductForValidation,
  selection: SuggestedModifierSelection[] | null | undefined,
): ResolvedModifier[] {
  if (product.upsellEnabled !== true) {
    throw new UpsellModifierError('PRODUCT_NOT_SUGGESTABLE', 'Este producto está vetado para sugerencias en su ficha')
  }
  if (product.soldByWeight) {
    throw new UpsellModifierError('PRODUCT_NOT_SUGGESTABLE', 'Un producto que se vende por peso no puede sugerirse de un toque')
  }

  const picks = selection ?? []
  const resolved: ResolvedModifier[] = []

  for (const { group } of product.modifierGroups) {
    if (!group.required) continue

    const pick = picks.find(p => p.groupId === group.id)
    if (!pick) {
      throw new UpsellModifierError(
        'MISSING_REQUIRED_MODIFIER',
        `Falta elegir una opción de "${group.name}" para poder sugerir este producto`,
      )
    }

    const modifier = group.modifiers.find(m => m.id === pick.modifierId)
    if (!modifier) {
      throw new UpsellModifierError('MODIFIER_NOT_IN_GROUP', `La opción elegida no pertenece a "${group.name}"`)
    }
    if (modifier.active === false) {
      throw new UpsellModifierError('MODIFIER_INACTIVE', `La opción "${modifier.name}" está desactivada`)
    }

    resolved.push({ groupId: group.id, modifierId: modifier.id, name: modifier.name, price: toNumber(modifier.price) })
  }

  return resolved
}
```

- [ ] **Paso 4: Correr los tests y verlos PASAR**

Correr: `cd avoqado-server && npx jest tests/unit/services/upsell/upsellModifiers.test.ts`
Esperado: PASS — 10 tests.

- [ ] **Paso 5: Commit**

```bash
cd avoqado-server
git add src/services/upsell/upsellModifiers.ts tests/unit/services/upsell/upsellModifiers.test.ts
git commit -m "feat(upsell): validador de opciones obligatorias para una sugerencia

Aislado y sin HTTP: recibe el producto con sus grupos y la selección, y devuelve
los modificadores resueltos con nombre y precio, o falla con un código y un
mensaje que nombra el grupo que falta.

No valida existencias a propósito: el stock es transitorio y ese filtro se queda
en el POS.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Tarea 2: El campo en el schema

**Archivos:**
- Modificar: `avoqado-server/prisma/schema.prisma` (modelo `UpsellRule`, ~línea 6753)
- Modificar: `avoqado-server/docs/SCHEMA_MAP.md` (regenerado)
- Crear: migración en `avoqado-server/prisma/migrations/`

**Interfaces:**
- Consume: nada.
- Produce: la columna `suggestedModifiers Json?` en `UpsellRule`, que las Tareas 3 y 4 leen y escriben.

- [ ] **Paso 1: Agregar el campo al modelo**

En `prisma/schema.prisma`, dentro de `model UpsellRule`, justo después de `suggestedProductId`:

```prisma
  /// Opciones OBLIGATORIAS ya resueltas para esta sugerencia (spec 2026-08-16, B3).
  /// Forma: [{ groupId, modifierId }]. NULL/vacío = el producto no pide nada.
  ///
  /// 🔴 Vive en la REGLA y no en el producto a propósito: la misma bebida puede
  /// sugerirse chica en una regla y grande en otra sin tocar el catálogo. Por eso
  /// NO se agregó un `Modifier.isDefault`.
  ///
  /// Aditivo: las reglas existentes quedan en NULL y se comportan como hoy.
  suggestedModifiers Json?
```

- [ ] **Paso 2: Generar la migración**

```bash
cd avoqado-server
npx prisma migrate dev --name upsell_rule_suggested_modifiers --create-only
```

Verifica que el SQL generado sea sólo `ALTER TABLE "UpsellRule" ADD COLUMN "suggestedModifiers" JSONB;`
Si trae algo más, PARA y repórtalo: otra sesión pudo haber tocado el schema.

- [ ] **Paso 3: Aplicar y regenerar el mapa**

```bash
cd avoqado-server
npx prisma migrate deploy
npm run schema:map
```

- [ ] **Paso 4: Verificar que el cliente compila con el campo**

```bash
cd avoqado-server
NODE_OPTIONS=--max-old-space-size=8192 npx tsc --noEmit -p tsconfig.build.json
```
Esperado: exit 0, sin salida.

- [ ] **Paso 5: Commit**

```bash
cd avoqado-server
git add prisma/schema.prisma prisma/migrations docs/SCHEMA_MAP.md
git commit -m "feat(schema): UpsellRule.suggestedModifiers — opciones obligatorias resueltas

Aditivo y sin backfill: las reglas existentes quedan en NULL y se comportan
exactamente como hoy.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Tarea 3: El server valida al guardar y lo devuelve al POS

**Archivos:**
- Modificar: `avoqado-server/src/services/upsell/upsell.service.ts`
  (`PosUpsellRuleDTO` :70, `listActiveRulesForPos` :95, `CreateRuleInput` :129,
  `createRule` :191, `updateRule` :314)
- Test: `avoqado-server/tests/unit/services/upsell/upsell.service.test.ts` (o el que exista;
  si no existe, créalo con ese nombre)

**Interfaces:**
- Consume: `validateAndResolveModifiers`, `UpsellModifierError`, `SuggestedModifierSelection`,
  `ResolvedModifier` de la Tarea 1.
- Produce:
  - `CreateRuleInput` gana `suggestedModifiers?: SuggestedModifierSelection[] | null`
  - `UpdateRuleInput` gana el MISMO campo, con la misma forma. Sin él, editar una regla
    para cambiarle el producto la dejaría inválida en silencio — el bug por la puerta de atrás.
  - `PosUpsellRuleDTO` gana `suggestedModifiers: ResolvedModifier[]` (siempre array, nunca null)
  - `assertSameVenue` pasa de `Promise<void>` a `Promise<ProductForValidation>` (devuelve el
    producto sugerido para no volver a consultarlo)
  - Helpers internos nuevos: `PRODUCT_VALIDATION_SELECT`, `loadProductForValidation`, `resolveForDto`

- [ ] **Paso 1: Escribir los tests que fallan**

```ts
// Añadir a tests/unit/services/upsell/upsell.service.test.ts
// (mockea prisma igual que los demás tests del repo: jest.mock('@/utils/prismaClient'))

describe('createRule — opciones obligatorias (spec B3)', () => {
  it('rechaza guardar una regla cuyo producto pide opciones y no las trae', async () => {
    ;(prisma.product.findFirst as jest.Mock).mockResolvedValue({
      id: 'prod_agua',
      soldByWeight: false,
      upsellEnabled: true,
      modifierGroups: [
        { group: { id: 'g_tam', name: 'Tamaño', required: true, modifiers: [{ id: 'm_ch', name: 'Chico', price: 0, active: true }] } },
      ],
    })

    await expect(
      createRule({ venueId: 'v1', triggerType: 'ALWAYS', suggestedProductId: 'prod_agua' }, 'staff_1'),
    ).rejects.toThrow(/Tamaño/)

    // 🔴 Lo importante: NO se guardó. El bug original era justo que sí se guardaba.
    expect(prisma.upsellRule.create).not.toHaveBeenCalled()
  })

  it('guarda la selección cuando está completa', async () => {
    ;(prisma.product.findFirst as jest.Mock).mockResolvedValue({
      id: 'prod_agua',
      soldByWeight: false,
      upsellEnabled: true,
      modifierGroups: [
        { group: { id: 'g_tam', name: 'Tamaño', required: true, modifiers: [{ id: 'm_gr', name: 'Grande', price: 15, active: true }] } },
      ],
    })
    ;(prisma.upsellRule.create as jest.Mock).mockImplementation(async ({ data }: any) => ({ id: 'r1', ...data }))

    await createRule(
      {
        venueId: 'v1',
        triggerType: 'ALWAYS',
        suggestedProductId: 'prod_agua',
        suggestedModifiers: [{ groupId: 'g_tam', modifierId: 'm_gr' }],
      },
      'staff_1',
    )

    expect(prisma.upsellRule.create).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({ suggestedModifiers: [{ groupId: 'g_tam', modifierId: 'm_gr' }] }),
      }),
    )
  })

  it('un producto sin obligatorios sigue guardándose sin selección (no rompe lo de hoy)', async () => {
    ;(prisma.product.findFirst as jest.Mock).mockResolvedValue({
      id: 'prod_coca',
      soldByWeight: false,
      upsellEnabled: true,
      modifierGroups: [],
    })
    ;(prisma.upsellRule.create as jest.Mock).mockImplementation(async ({ data }: any) => ({ id: 'r2', ...data }))

    await createRule({ venueId: 'v1', triggerType: 'ALWAYS', suggestedProductId: 'prod_coca' }, 'staff_1')

    expect(prisma.upsellRule.create).toHaveBeenCalled()
  })
})

describe('listActiveRulesForPos — el POS recibe la selección RESUELTA', () => {
  it('devuelve nombre y precio de cada modificador, no sólo ids', async () => {
    ;(prisma.upsellRule.findMany as jest.Mock).mockResolvedValue([
      {
        id: 'r1',
        triggerType: 'ALWAYS',
        triggerProductIds: [],
        triggerCategoryIds: [],
        suggestedProductId: 'prod_agua',
        suggestedModifiers: [{ groupId: 'g_tam', modifierId: 'm_gr' }],
        headline: '¿Le agregamos un agua bien fría?',
        priority: 0,
        lift: null,
        daysOfWeek: [],
        timeFrom: null,
        timeUntil: null,
      },
    ])
    ;(prisma.product.findMany as jest.Mock).mockResolvedValue([
      {
        id: 'prod_agua',
        soldByWeight: false,
        upsellEnabled: true,
        modifierGroups: [
          { group: { id: 'g_tam', name: 'Tamaño', required: true, modifiers: [{ id: 'm_gr', name: 'Grande', price: 15, active: true }] } },
        ],
      },
    ])

    const dtos = await listActiveRulesForPos('v1')

    expect(dtos[0].suggestedModifiers).toEqual([
      { groupId: 'g_tam', modifierId: 'm_gr', name: 'Grande', price: 15 },
    ])
  })

  it('una regla sin selección devuelve array VACÍO, nunca null (el POS no debe checar nulos)', async () => {
    ;(prisma.upsellRule.findMany as jest.Mock).mockResolvedValue([
      {
        id: 'r2', triggerType: 'ALWAYS', triggerProductIds: [], triggerCategoryIds: [],
        suggestedProductId: 'prod_coca', suggestedModifiers: null, headline: null,
        priority: 0, lift: null, daysOfWeek: [], timeFrom: null, timeUntil: null,
      },
    ])
    ;(prisma.product.findMany as jest.Mock).mockResolvedValue([
      { id: 'prod_coca', soldByWeight: false, upsellEnabled: true, modifierGroups: [] },
    ])

    const dtos = await listActiveRulesForPos('v1')
    expect(dtos[0].suggestedModifiers).toEqual([])
  })
})
```

- [ ] **Paso 2: Correr y ver FALLAR**

Correr: `cd avoqado-server && npx jest tests/unit/services/upsell/upsell.service.test.ts`
Esperado: FAIL — `suggestedModifiers` no existe en el DTO / no se valida.

- [ ] **Paso 3: Implementar**

En `upsell.service.ts`:

```ts
// 1) Import arriba del archivo
import {
  validateAndResolveModifiers,
  type ResolvedModifier,
  type SuggestedModifierSelection,
} from './upsellModifiers'
```

```ts
// 2) En PosUpsellRuleDTO (después de suggestedProductId, línea ~75)
  /**
   * Opciones obligatorias ya resueltas, con nombre y precio, para que el POS
   * pinte la tarjeta y arme la línea sin recalcular. Vacío = el producto no pide
   * nada. NUNCA null: el POS no debe tener que checar nulos.
   */
  suggestedModifiers: ResolvedModifier[]
```

```ts
// 3) En CreateRuleInput (línea ~135)
  suggestedModifiers?: SuggestedModifierSelection[] | null
```

```ts
// 4) `assertSameVenue` (:157) YA consulta el producto sugerido para validar que
//    existe en el venue y que no está vetado. Se extiende ESE query — no se hace
//    uno nuevo — y devuelve el producto para no volver a buscarlo.

// 4a) La firma pasa de Promise<void> a devolver el producto sugerido:
async function assertSameVenue(venueId: string, input: CreateRuleInput): Promise<ProductForValidation> {
  const productIds = [...(input.triggerProductIds ?? []), input.suggestedProductId]
  const found = await prisma.product.findMany({
    where: { id: { in: productIds }, venueId },
    // 🔴 soldByWeight y los grupos hacen falta para validar las opciones
    // obligatorias. Sin esto el server sería tan ciego como lo era el dashboard.
    select: {
      id: true,
      upsellEnabled: true,
      soldByWeight: true,
      modifierGroups: {
        select: {
          group: {
            select: {
              id: true,
              name: true,
              required: true,
              modifiers: { select: { id: true, name: true, price: true, active: true } },
            },
          },
        },
      },
    },
  })
  const foundIds = new Set(found.map(p => p.id))
  const missing = productIds.filter(id => !foundIds.has(id))
  if (missing.length > 0) {
    throw new UpsellValidationError(`Estos productos no existen en este venue: ${missing.join(', ')}`, 'PRODUCT_NOT_IN_VENUE')
  }

  const suggested = found.find(p => p.id === input.suggestedProductId)
  if (!suggested?.upsellEnabled) {
    throw new UpsellValidationError(
      'El producto sugerido no está habilitado para promociones. Actívalo en su ficha antes de crear la regla.',
      'PRODUCT_NOT_UPSELL_ENABLED',
    )
  }

  // ... el resto de la función (triggerCategoryIds) queda IGUAL ...

  return suggested as ProductForValidation
}
```

```ts
// 4b) En createRule, la primera línea captura el producto y valida la selección
//     ANTES de tocar la base:
export async function createRule(input: CreateRuleInput, performedBy: string) {
  const suggestedProduct = await assertSameVenue(input.venueId, input)

  // 🔴 Antes de cualquier escritura: una regla que el POS va a descartar no se
  // guarda. Lanza UpsellModifierError con el nombre del grupo que falta.
  validateAndResolveModifiers(suggestedProduct, input.suggestedModifiers)

  // ... dedupeKey y la comprobación de duplicado quedan IGUAL ...

  const rule = await prisma.upsellRule.create({
    data: {
      venueId: input.venueId,
      triggerType: input.triggerType,
      triggerProductIds: input.triggerProductIds ?? [],
      triggerCategoryIds: input.triggerCategoryIds ?? [],
      // Se guarda la SELECCIÓN (ids), no lo resuelto: los nombres y precios se
      // resuelven al leer, así un cambio de precio en el catálogo se refleja solo.
      suggestedModifiers: (input.suggestedModifiers ?? []) as Prisma.InputJsonValue,
      // ... el resto de los campos queda IGUAL ...
    },
  })
  // ... el resto de la función queda IGUAL ...
}
```

```ts
// 4c) En el controlador (src/controllers/dashboard/upsell.dashboard.controller.ts),
//     donde ya se mapea UpsellValidationError a 400, agregar la hermana:
import { UpsellModifierError } from '@/services/upsell/upsellModifiers'
// ...
if (error instanceof UpsellModifierError) {
  return res.status(400).json({ success: false, code: error.code, message: error.message })
}
```

```ts
// 5) En updateRule (:314) — si cambia el producto O la selección, se revalida.
//    Cambiar el producto de una regla sin revalidar reintroduce el bug por la
//    puerta de atrás: la regla nació válida y se vuelve inválida en silencio.
export async function updateRule(venueId: string, ruleId: string, input: UpdateRuleInput, performedBy: string) {
  // ... la carga de la regla existente queda IGUAL ...

  if (input.suggestedProductId !== undefined || input.suggestedModifiers !== undefined) {
    const productId = input.suggestedProductId ?? existing.suggestedProductId
    const producto = await loadProductForValidation(venueId, productId)
    const seleccion = input.suggestedModifiers !== undefined ? input.suggestedModifiers : (existing.suggestedModifiers as any)
    validateAndResolveModifiers(producto, seleccion)
  }

  // ... y en el data del update, si vino: suggestedModifiers: input.suggestedModifiers
}
```

```ts
// 5b) Helper compartido, en upsell.service.ts (lo usan updateRule y
//     listActiveRulesForPos). Un solo `select` para los tres call sites.
const PRODUCT_VALIDATION_SELECT = {
  id: true,
  upsellEnabled: true,
  soldByWeight: true,
  modifierGroups: {
    select: {
      group: {
        select: {
          id: true, name: true, required: true,
          modifiers: { select: { id: true, name: true, price: true, active: true } },
        },
      },
    },
  },
} as const

async function loadProductForValidation(venueId: string, productId: string): Promise<ProductForValidation> {
  const p = await prisma.product.findFirst({ where: { id: productId, venueId }, select: PRODUCT_VALIDATION_SELECT })
  if (!p) throw new UpsellValidationError(`El producto ${productId} no existe en este venue`, 'PRODUCT_NOT_IN_VENUE')
  return p as ProductForValidation
}
```

```ts
// 6) En listActiveRulesForPos (:95) — los productos se cargan en UN findMany por
//    el lote, nunca uno por regla (N+1 en el arranque de cada POS del local).
export async function listActiveRulesForPos(venueId: string): Promise<PosUpsellRuleDTO[]> {
  const rules = await prisma.upsellRule.findMany(/* ... queda IGUAL ... */)

  const productos = await prisma.product.findMany({
    where: { id: { in: [...new Set(rules.map(r => r.suggestedProductId))] }, venueId },
    select: PRODUCT_VALIDATION_SELECT,
  })
  const porId = new Map(productos.map(p => [p.id, p as ProductForValidation]))

  return rules.map(rule => ({
    // ... todos los campos actuales quedan IGUAL ...
    suggestedModifiers: resolveForDto(porId.get(rule.suggestedProductId), rule.suggestedModifiers),
  }))
}

/**
 * 🔴 NUNCA lanza. Una regla que quedó inválida porque el catálogo cambió después
 * (le pusieron un obligatorio nuevo, desactivaron la opción elegida) no puede
 * tumbar la respuesta de TODAS las demás y dejar al local sin upsell. Devuelve []
 * y el POS la descarta sola, que es exactamente el comportamiento de hoy.
 */
function resolveForDto(product: ProductForValidation | undefined, selection: unknown): ResolvedModifier[] {
  if (!product) return []
  try {
    return validateAndResolveModifiers(product, selection as SuggestedModifierSelection[] | null)
  } catch {
    return []
  }
}
```

- [ ] **Paso 4: Correr los tests y verlos PASAR**

```bash
cd avoqado-server && npx jest tests/unit/services/upsell tests/unit/controllers 2>&1 | tail -6
```
Esperado: todos PASS, incluidos los previos del upsell.

- [ ] **Paso 5: Typecheck**

```bash
cd avoqado-server && NODE_OPTIONS=--max-old-space-size=8192 npx tsc --noEmit -p tsconfig.build.json
```
Esperado: exit 0.

- [ ] **Paso 6: Commit**

```bash
cd avoqado-server
git add src/services/upsell/upsell.service.ts src/controllers/dashboard/upsell.dashboard.controller.ts tests/unit/services/upsell/upsell.service.test.ts
git commit -m "feat(upsell): el server no guarda reglas que el POS va a ignorar

Al crear o editar una regla se validan las opciones obligatorias del producto:
si faltan, 400 con el nombre del grupo. Antes se guardaba tan campante y la
tarjeta simplemente nunca aparecía — el bug que el founder persiguió en la D3.

La respuesta al POS trae los modificadores RESUELTOS (nombre y precio) para que
pinte la tarjeta sin recalcular. Una regla que quedó inválida porque el catálogo
cambió devuelve [] y el POS la descarta, sin tumbar a las demás.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Tarea 4: El dashboard deja de mentir

**Archivos:**
- Crear: `avoqado-web-dashboard/src/lib/upsell/suggestability.ts`
- Test: `avoqado-web-dashboard/src/lib/upsell/suggestability.test.ts`
- Modificar: `avoqado-web-dashboard/src/pages/Promotions/Upsell.tsx` (:279 el query, :285 el
  filtro, :369 el `<Select>`, :458 el badge)

**Interfaces:**
- Consume: el endpoint de crear regla ahora acepta `suggestedModifiers`.
- Produce:
  ```ts
  export type SuggestabilityReason = 'VETADO' | 'POR_PESO' | 'SIN_EXISTENCIAS' | 'PIDE_OPCIONES' | null
  export function suggestabilityOf(product: {
    upsellEnabled?: boolean | null
    soldByWeight?: boolean | null
    isOutOfStock?: boolean | null
    modifierGroups?: Array<{ group?: { required?: boolean } }>
  }): { blocked: boolean; reason: SuggestabilityReason; label: string | null; resolvable: boolean }
  ```
  `resolvable: true` sólo para `PIDE_OPCIONES` — es el único que se arregla eligiendo.

- [ ] **Paso 1: Escribir el test que falla**

```ts
// avoqado-web-dashboard/src/lib/upsell/suggestability.test.ts
import { describe, it, expect } from 'vitest'
import { suggestabilityOf } from './suggestability'

const req = (required: boolean) => ({ group: { required } })

describe('suggestabilityOf — espejo EXACTO de los 4 filtros del POS', () => {
  it('producto normal → se puede sugerir', () => {
    expect(suggestabilityOf({ upsellEnabled: true, modifierGroups: [] })).toMatchObject({ blocked: false, reason: null })
  })

  it('sólo grupos OPCIONALES → se puede sugerir', () => {
    expect(suggestabilityOf({ upsellEnabled: true, modifierGroups: [req(false)] })).toMatchObject({ blocked: false })
  })

  it('vetado en su ficha → bloqueado y NO resoluble', () => {
    expect(suggestabilityOf({ upsellEnabled: false })).toMatchObject({
      blocked: true, reason: 'VETADO', resolvable: false,
    })
  })

  it('por peso → bloqueado y NO resoluble', () => {
    expect(suggestabilityOf({ upsellEnabled: true, soldByWeight: true })).toMatchObject({
      blocked: true, reason: 'POR_PESO', resolvable: false,
    })
  })

  it('sin existencias → bloqueado y NO resoluble', () => {
    expect(suggestabilityOf({ upsellEnabled: true, isOutOfStock: true })).toMatchObject({
      blocked: true, reason: 'SIN_EXISTENCIAS', resolvable: false,
    })
  })

  // 🔴 El caso del founder: éste SÍ se arregla eligiendo el tamaño.
  it('pide opciones obligatorias → bloqueado pero RESOLUBLE', () => {
    expect(suggestabilityOf({ upsellEnabled: true, modifierGroups: [req(true)] })).toMatchObject({
      blocked: true, reason: 'PIDE_OPCIONES', resolvable: true,
    })
  })

  it('el motivo se muestra en español, no un código', () => {
    expect(suggestabilityOf({ upsellEnabled: true, soldByWeight: true }).label).toBe('Se vende por peso')
    expect(suggestabilityOf({ upsellEnabled: false }).label).toBe('Vetado en su ficha')
  })

  it('el VETO gana sobre lo demás: es la decisión explícita del dueño', () => {
    expect(suggestabilityOf({ upsellEnabled: false, soldByWeight: true }).reason).toBe('VETADO')
  })
})
```

- [ ] **Paso 2: Correr y ver FALLAR**

Correr: `cd avoqado-web-dashboard && npx vitest run src/lib/upsell/suggestability.test.ts`
Esperado: FAIL — módulo no encontrado.

- [ ] **Paso 3: Implementar el helper**

```ts
// avoqado-web-dashboard/src/lib/upsell/suggestability.ts

/**
 * Espejo EXACTO de los cuatro filtros de `UpsellResolver` del POS
 * (avoqado-android/.../pos/domain/UpsellResolver.kt:50-77).
 *
 * 🔴 Existe porque el dashboard sólo conocía UNO de los cuatro y dejaba crear
 * reglas que el POS descartaba en silencio. Si el POS agrega o quita un filtro,
 * este archivo cambia en el MISMO trabajo.
 */

export type SuggestabilityReason = 'VETADO' | 'POR_PESO' | 'SIN_EXISTENCIAS' | 'PIDE_OPCIONES' | null

interface ProductLike {
  upsellEnabled?: boolean | null
  soldByWeight?: boolean | null
  isOutOfStock?: boolean | null
  modifierGroups?: Array<{ group?: { required?: boolean } }>
}

const LABELS: Record<Exclude<SuggestabilityReason, null>, string> = {
  VETADO: 'Vetado en su ficha',
  POR_PESO: 'Se vende por peso',
  SIN_EXISTENCIAS: 'Sin existencias',
  PIDE_OPCIONES: 'Pide elegir opciones',
}

export function suggestabilityOf(product: ProductLike): {
  blocked: boolean
  reason: SuggestabilityReason
  label: string | null
  /** Sólo PIDE_OPCIONES se arregla desde aquí, eligiendo las opciones. */
  resolvable: boolean
} {
  const ok = { blocked: false as const, reason: null, label: null, resolvable: false }

  // El veto del dueño gana sobre todo: es su decisión explícita en la ficha.
  if (product.upsellEnabled !== true) return { blocked: true, reason: 'VETADO', label: LABELS.VETADO, resolvable: false }
  if (product.soldByWeight) return { blocked: true, reason: 'POR_PESO', label: LABELS.POR_PESO, resolvable: false }
  if (product.isOutOfStock) return { blocked: true, reason: 'SIN_EXISTENCIAS', label: LABELS.SIN_EXISTENCIAS, resolvable: false }

  if ((product.modifierGroups ?? []).some(g => g.group?.required)) {
    return { blocked: true, reason: 'PIDE_OPCIONES', label: LABELS.PIDE_OPCIONES, resolvable: true }
  }

  return ok
}
```

- [ ] **Paso 4: Correr el test y verlo PASAR**

Correr: `cd avoqado-web-dashboard && npx vitest run src/lib/upsell/suggestability.test.ts`
Esperado: PASS — 8 tests.

- [ ] **Paso 5: Cablear la pantalla**

En `src/pages/Promotions/Upsell.tsx`:

```tsx
// 1) Línea 279 — SÍ se necesitan los modificadores. Sin esto el helper es ciego.
queryFn: () => getProducts(venueId, { orderBy: 'name', includeRecipe: false, includeModifiers: true }),

// 2) Línea 285 — reemplazar el filtro por una lista anotada. Se muestra TODO:
//    ver el motivo vale más que no ver el producto.
const anotados = useMemo(
  () => (products ?? []).map((p: any) => ({ ...p, suggestability: suggestabilityOf(p) })),
  [products],
)

// 3) Estado nuevo para el paso de opciones obligatorias (B3)
const [modifierPicks, setModifierPicks] = useState<Record<string, string>>({}) // groupId -> modifierId
const productoElegido = anotados.find(p => p.id === suggestedProductId)
const gruposObligatorios = (productoElegido?.modifierGroups ?? []).filter((g: any) => g.group?.required)

// 4) En el <Select> (:369) — cada opción deshabilitada SALVO las resolubles,
//    y siempre con el motivo a la vista:
//    <SelectItem value={p.id} disabled={p.suggestability.blocked && !p.suggestability.resolvable}>
//      {p.name}
//      {p.suggestability.label && <span className="ml-2 text-xs text-muted-foreground">{p.suggestability.label}</span>}
//    </SelectItem>

// 5) Debajo del Select, sólo si gruposObligatorios.length > 0 — el paso único de B3:
//    un <Select> por grupo obligatorio, con su nombre como etiqueta.
//    Texto de ayuda: "Esta sugerencia se agrega con un toque, así que hay que
//    dejar elegidas sus opciones desde ahora."

// 6) canSubmit (:312) — no se puede guardar sin resolver todos:
const obligatoriosResueltos = gruposObligatorios.every((g: any) => modifierPicks[g.group.id])
const canSubmit =
  !!suggestedProductId &&
  obligatoriosResueltos &&
  (triggerType !== 'PRODUCT' || triggerProductIds.length > 0)

// 7) En la mutación (:292) — mandar la selección:
suggestedModifiers: gruposObligatorios.map((g: any) => ({
  groupId: g.group.id,
  modifierId: modifierPicks[g.group.id],
})),

// 8) Al cerrar/resetear el formulario (:301), limpiar también: setModifierPicks({})

// 9) Badge de la lista (:458) — usar el motivo real, no sólo el veto:
//    const s = suggestabilityOf(rule.suggestedProduct ?? {})
//    {s.blocked && <Badge variant="destructive">{s.label}</Badge>}
//
//    🔴 Ojo: una regla YA guardada con sus opciones resueltas NO debe salir como
//    bloqueada por PIDE_OPCIONES — está resuelta. Si la regla trae
//    suggestedModifiers con elementos, ese motivo se ignora para el badge:
//      const bloqueadoDeVerdad = s.blocked && !(s.reason === 'PIDE_OPCIONES' && (rule.suggestedModifiers?.length ?? 0) > 0)

// 10) Vista previa (§4.2 del spec): si la pantalla ya tiene una tarjeta de
//     preview, debe mostrar el nombre del producto MÁS las opciones elegidas y el
//     precio sumado — lo mismo que verá el cliente. Si NO existe preview hoy, NO
//     la inventes: anótalo en el reporte y sigue. No es el objetivo de esta tarea.
```

- [ ] **Paso 6: Verificar**

```bash
cd avoqado-web-dashboard
npx tsc -p tsconfig.app.json --noEmit
npx vitest run
```
Esperado: typecheck exit 0; todos los tests PASS.

- [ ] **Paso 7: Commit**

```bash
cd avoqado-web-dashboard
git add src/lib/upsell/suggestability.ts src/lib/upsell/suggestability.test.ts src/pages/Promotions/Upsell.tsx
git commit -m "feat(upsell): el selector deja de ofrecer productos que el POS descarta

El POS descarta una sugerencia por cuatro motivos y esta pantalla conocía uno —
y pedía los productos con includeModifiers:false, así que ni siquiera tenía el
dato de los otros. Dejaba crear reglas que nunca se mostrarían, en silencio.

Ahora se muestra TODO el catálogo con el motivo escrito en cada producto
bloqueado, y los que sólo piden opciones obligatorias se pueden elegir:
resolverlas es un paso más en la misma pantalla.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Tarea 5: Android — el resolver acepta la selección resuelta

**Archivos:**
- Modificar: `avoqado-android/app/src/main/java/com/avoqado/pos/pos/data/model/UpsellRule.kt`
- Modificar: `avoqado-android/app/src/main/java/com/avoqado/pos/pos/domain/UpsellResolver.kt` (:70)
- Modificar: el punto donde se acepta la tarjeta (`UpsellViewModel` / `CheckoutScreen.resolveUpsell`)
- Test: `avoqado-android/app/src/test/java/com/avoqado/pos/pos/domain/UpsellResolverTest.kt`
  (ya existe; añadir casos)

**Interfaces:**
- Consume: `PosUpsellRuleDTO.suggestedModifiers` de la Tarea 3.
- Produce: `UpsellRule.suggestedModifiers: List<ResolvedModifier>` y una `UpsellCard` que
  lleva los modificadores para armar la línea del carrito.

- [ ] **Paso 1: Escribir los tests que fallan**

```kotlin
// Añadir a UpsellResolverTest.kt

/** El producto del founder: "Agua Mineral 1L" con el grupo "Tamaño" obligatorio. */
private fun productoConObligatorio() = producto(
    id = "prod_agua",
    modifierGroups = listOf(modifierGroup(required = true)),
)

@Test
fun `un producto con obligatorios SIN resolver se sigue descartando`() {
    val cards = resolveUpsellSuggestions(
        rules = listOf(regla(suggestedProductId = "prod_agua", suggestedModifiers = emptyList())),
        cartProductIds = emptySet(),
        cartCategoryIds = emptySet(),
        catalog = mapOf("prod_agua" to productoConObligatorio()),
        nowLocal = AHORA,
    )
    assertTrue("sin resolver, la tarjeta abriría un formulario", cards.isEmpty())
}

@Test
fun `con los obligatorios RESUELTOS la tarjeta si se muestra`() {
    val cards = resolveUpsellSuggestions(
        rules = listOf(
            regla(
                suggestedProductId = "prod_agua",
                suggestedModifiers = listOf(ResolvedModifier("g_tam", "m_gr", "Grande", 15.0)),
            ),
        ),
        cartProductIds = emptySet(),
        cartCategoryIds = emptySet(),
        catalog = mapOf("prod_agua" to productoConObligatorio()),
        nowLocal = AHORA,
    )
    assertEquals(1, cards.size)
}

@Test
fun `el precio de la tarjeta incluye los modificadores`() {
    val cards = resolveUpsellSuggestions(
        rules = listOf(
            regla(
                suggestedProductId = "prod_agua",
                suggestedModifiers = listOf(ResolvedModifier("g_tam", "m_gr", "Grande", 15.0)),
            ),
        ),
        cartProductIds = emptySet(),
        cartCategoryIds = emptySet(),
        catalog = mapOf("prod_agua" to productoConObligatorio(precio = 35.0)),
        nowLocal = AHORA,
    )
    // 🔴 Si esto falla, el cliente ve un precio y se le cobra otro.
    assertEquals(50.0, cards.first().priceWithModifiers, 0.001)
}

@Test
fun `los otros tres filtros NO se relajan aunque haya modificadores resueltos`() {
    val resueltos = listOf(ResolvedModifier("g_tam", "m_gr", "Grande", 15.0))
    val vetado = productoConObligatorio().copy(upsellEnabled = false)
    val porPeso = productoConObligatorio().copy(soldByWeight = true)
    val sinStock = productoConObligatorio().copy(isOutOfStock = true)

    listOf(vetado, porPeso, sinStock).forEach { p ->
        val cards = resolveUpsellSuggestions(
            rules = listOf(regla(suggestedProductId = "prod_agua", suggestedModifiers = resueltos)),
            cartProductIds = emptySet(),
            cartCategoryIds = emptySet(),
            catalog = mapOf("prod_agua" to p),
            nowLocal = AHORA,
        )
        assertTrue("el veto, el peso y el stock siguen mandando", cards.isEmpty())
    }
}
```

> Si los helpers `producto(...)`, `modifierGroup(...)`, `regla(...)` o `AHORA` no existen con
> esa forma en el archivo de test, adáptalos a los que ya tenga — NO reescribas el archivo.

- [ ] **Paso 2: Correr y ver FALLAR**

```bash
cd avoqado-android && ./gradlew testDebugUnitTest --tests "*UpsellResolverTest*"
```
Esperado: FAIL — `ResolvedModifier` no existe / `suggestedModifiers` no existe.

- [ ] **Paso 3: Implementar**

```kotlin
// 1) En UpsellRule.kt — DTO nuevo y campo
@Serializable
data class ResolvedModifier(
    val groupId: String,
    val modifierId: String,
    val name: String,
    val price: Double,
)

// dentro de UpsellRule:
//   /** Opciones obligatorias ya resueltas por la regla. Vacío = el producto no pide nada. */
//   val suggestedModifiers: List<ResolvedModifier> = emptyList(),
```

```kotlin
// 2) En UpsellResolver.kt, línea ~70 — el filtro pasa a ser condicional:
//
//    ANTES:  product.hasRequiredModifierGroup -> false
//    AHORA:
            // Tocarlo abriría el panel de modificadores. Misma razón que el peso.
            // Es la regla de Square: un artículo con obligatorios SIEMPRE abre su
            // pantalla de detalle.
            //
            // 🔴 SALVO que la regla ya los haya resuelto (spec 2026-08-16, B3): la
            // elección viajó desde el dashboard y la tarjeta entra de un toque.
            product.hasRequiredModifierGroup && rule.suggestedModifiers.isEmpty() -> false
```

```kotlin
// 3) La UpsellCard gana los modificadores y el precio con ellos:
//      val modifiers: List<ResolvedModifier>
//      val priceWithModifiers: Double  // product.price + modifiers.sumOf { it.price }
//    Úsalo para pintar el precio en la tarjeta (Composable de la tira y
//    CustomerDisplayScreen), NO el precio pelón del producto.
```

```kotlin
// 4) Al aceptar la tarjeta (resolveUpsell(accept = true) en CheckoutScreen:451):
//    la línea entra por la función que YA existe para esto — no se inventa un
//    camino nuevo para el upsell:
//
//      CartViewModel.addProductWithModifiers(
//          product: Product,
//          quantity: Int = 1,
//          modifiers: List<SelectedModifier>,
//          ...
//      )                                    // CartViewModel.kt:612
//
//    Hay que mapear ResolvedModifier -> SelectedModifier (mira la forma de
//    SelectedModifier en el modelo del carrito; los ids ya vienen resueltos, así
//    que es un map directo, sin buscar nada en el catálogo).
//
//    🔴 Donde HOY se llame addProduct(product) para el upsell, pasa a
//    addProductWithModifiers cuando card.modifiers no esté vacío. Si se queda en
//    addProduct, la línea entra SIN el tamaño y se cobra el precio pelón: el
//    cliente ve un precio en la tarjeta y se le cobra otro.
```

- [ ] **Paso 4: Correr los tests y verlos PASAR**

```bash
cd avoqado-android && ./gradlew testDebugUnitTest --tests "*Upsell*"
```
Esperado: BUILD SUCCESSFUL.

- [ ] **Paso 5: Suite completa y APK**

```bash
cd avoqado-android && ./gradlew testDebugUnitTest assembleDebug
```
Esperado: BUILD SUCCESSFUL, 0 failures.

- [ ] **Paso 6: Commit**

```bash
cd avoqado-android
git add app/src/main/java/com/avoqado/pos/pos/data/model/UpsellRule.kt \
        app/src/main/java/com/avoqado/pos/pos/domain/UpsellResolver.kt \
        app/src/main/java/com/avoqado/pos/pos/presentation/upsell/UpsellViewModel.kt \
        app/src/main/java/com/avoqado/pos/pos/presentation/checkout/CheckoutScreen.kt \
        app/src/test/java/com/avoqado/pos/pos/domain/UpsellResolverTest.kt
git commit -m "feat(upsell): un producto con opciones obligatorias ya puede sugerirse

El filtro pasa de 'tiene obligatorios' a 'tiene obligatorios SIN resolver': si la
regla trae la elección hecha desde el dashboard, la tarjeta entra de un toque.
La regla de Square se mantiene para todo lo demás.

El precio de la tarjeta incluye los modificadores — si no, el cliente ve uno y se
le cobra otro.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Tarea 6: iOS — espejo exacto de la Tarea 5

**Archivos:**
- Modificar: el modelo de `UpsellRule` en `avoqado-ios/avoqado-ios/` (búscalo con
  `grep -rn "struct UpsellRule" avoqado-ios/`)
- Modificar: el resolver equivalente (mismo grep con `UpsellResolver`)
- Test: el archivo de tests del resolver de iOS

**Interfaces:**
- Consume: lo mismo que Android — `suggestedModifiers` del DTO.
- Produce: `ResolvedModifier` en Swift con los MISMOS nombres de campo
  (`groupId`, `modifierId`, `name`, `price`) — el contrato se espeja por nombre exacto.

- [ ] **Paso 1: Leer primero la implementación de Android**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
git show HEAD --stat
git show HEAD
```
No adivines el diseño: cópialo. La regla del repo es que el comportamiento visible sea
equivalente.

- [ ] **Paso 2: Escribir los tests que fallan**

Los MISMOS cuatro casos de la Tarea 5, traducidos a XCTest y con los helpers que ya use el
archivo de tests de iOS:
1. producto con obligatorios SIN resolver → se descarta
2. con obligatorios RESUELTOS → la tarjeta se muestra
3. el precio incluye los modificadores
4. veto / peso / sin stock siguen bloqueando aunque haya resueltos

- [ ] **Paso 3: Correr y ver FALLAR**

```bash
cd avoqado-ios && xcodebuild test -project avoqado-ios.xcodeproj -scheme avoqado-ios \
  -destination 'platform=iOS Simulator,OS=18.5,name=iPhone 16 Pro' \
  -only-testing:avoqado-iosTests/UpsellResolverTests 2>&1 | grep -E "TEST FAILED|error:"
```
Esperado: FAIL.

- [ ] **Paso 4: Implementar el espejo**

Mismo cambio que Android: `ResolvedModifier` decodable, campo `suggestedModifiers` con
default `[]`, el filtro condicionado a que esté vacío, el precio con modificadores, y la línea
del carrito por el camino que ya existe en iOS para agregar un producto con modificadores.

- [ ] **Paso 5: Verificar**

```bash
cd avoqado-ios
xcodebuild test -project avoqado-ios.xcodeproj -scheme avoqado-ios \
  -destination 'platform=iOS Simulator,OS=18.5,name=iPhone 16 Pro' 2>&1 | grep -E "TEST SUCCEEDED|TEST FAILED"
```
Esperado: TEST SUCCEEDED.

> ⚠️ El destino DEBE llevar `OS=18.5`: `iPhone 16 Pro` ya no existe en `latest` (26.1).

- [ ] **Paso 6: Commit**

```bash
cd avoqado-ios
git add avoqado-ios/ avoqado-iosTests/
git commit -m "feat(upsell): un producto con opciones obligatorias ya puede sugerirse (iOS)

Espejo exacto del cambio de Android: mismos nombres de campo, mismo filtro
condicionado, mismo precio con modificadores.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Tarea 7: MCP y verificación en hardware

**Archivos:**
- Modificar: el tool del MCP que expone reglas de upsell (búscalo con
  `grep -rln upsell avoqado-server/src/mcp/`)

**Interfaces:**
- Consume: `PosUpsellRuleDTO.suggestedModifiers`.
- Produce: nada que otras tareas usen.

- [ ] **Paso 1: Poner el MCP en sincronía**

La regla del workspace es dura: *"Whenever you add or change a feature, Prisma model,
service, endpoint, permission, or any capability the MCP should expose, you MUST add or
update the matching MCP tool as part of the SAME change."*

Si existe un tool que lista o crea reglas de upsell, debe reflejar `suggestedModifiers` (y su
descripción debe explicar que un producto con opciones obligatorias necesita resolverlas).
Si NO existe ningún tool de upsell, anótalo en el reporte y no inventes uno.

- [ ] **Paso 2: Verificar el MCP**

```bash
cd avoqado-server && npx jest tests/unit/mcp tests/unit/mcp-customer 2>&1 | tail -4
```
Esperado: todos PASS.

- [ ] **Paso 3: Commit**

```bash
cd avoqado-server
git add src/mcp/
git commit -m "docs(mcp): las reglas de upsell exponen sus opciones obligatorias resueltas

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

- [ ] **Paso 4: Prueba en el D3 (la sesión principal, no un subagente)**

Con el backend local y el venue de prueba `cmpe64yq2001f9k92m0lbhmf4` ("Restaurante El
Atole"), que es donde el bug se reprodujo:

1. En el dashboard, abrir **Promociones → "¿Algo más?"**. El selector debe mostrar los 8
   productos bloqueados hoy con su motivo, y dejar elegir "Agua Mineral 1L" pidiendo el
   tamaño.
2. Editar la regla «¿Le agregamos un agua bien fría?» eligiendo un tamaño y guardar.
3. Compilar e instalar en el D3:
   ```bash
   cd avoqado-android
   ./gradlew assembleDebug -Pavoqado.devBaseUrl=http://<ip-del-mac>:3000/api/v1
   adb -s <serial-del-D3> install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. En modo **Retail**, agregar algo al carrito y tocar **Cobrar**.
5. **Debe aparecer la tarjeta** con el nombre resuelto y el precio del producto + el tamaño.
6. Aceptarla y confirmar que **el total sube exactamente lo que decía la tarjeta**.
7. Revisar el log del backend por `error:` en la ventana de la prueba:
   ```bash
   LOG=$(ls -t avoqado-server/logs/development*.log | head -1)
   ```

> El grupo de control es del 10%: si a la primera no sale, repite el cobro un par de veces
> antes de dudar del cambio.

---

## Notas de ejecución

- **Orden obligatorio:** 1 → 2 → 3 antes que 4, 5 y 6 (todas dependen del contrato del
  server). Las Tareas 5 y 6 pueden hacerse en paralelo entre sí, pero deben commitearse en el
  mismo trabajo por la regla de paridad.
- **No pushear.** El founder decide cuándo.
- **Presentación de ventas:** este cambio NO agrega una capacidad nueva de cara al cliente
  (el upsell ya se vende); es un arreglo. No toca el deck.

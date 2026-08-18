# Promociones en el POS — Plan 3B (Android + iOS, la mitad cliente)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el cajero vea las promociones publicadas en la pantalla de cobro, las toque, y los productos entren al carrito con su precio de promoción — en las dos apps, con y sin internet.

**Architecture:** El server ya hace toda la aritmética (plan 3A). El POS sólo: (1) cachea el catálogo que el server publica, (2) pinta el panel donde el ajuste del venue diga, (3) manda `promotionRef` —qué promoción y qué eligió la persona, **sin precios**— y (4) muestra el total que el server devuelve. Android e iOS se construyen **emparejadas**: cada task de Android tiene su espejo inmediato en iOS y la feature no está entregada hasta que el par esté completo.

**Tech Stack:** Kotlin + Jetpack Compose + Hilt + Retrofit/OkHttp + Room (Android) · SwiftUI + async/await + UserDefaults (iOS) · JUnit4/MockK/Turbine (Android) · XCTest (iOS).

**Spec:** `avoqado-android/docs/superpowers/specs/2026-08-15-promociones-pos-cliente-design.md` — su sección **"Contrato real"** lista las 8 formas de usar mal este contrato. Léela antes de la Task 2.

## Global Constraints

- 🔴 **Android e iOS se cambian JUNTOS.** Cada par (T2/T3, T4/T5, T6/T7, T8/T9) es UNA entrega. Mismos nombres de campo, mismos textos en español, misma semántica. Un par a medias es trabajo incompleto, no un TODO.
- 🔴 **El POS NUNCA manda precios de promoción.** `promotionRef` lleva `promotionId`, `promotionInstanceId` y `selections`. El precio que se cobra lo calcula el server; el estimado local es sólo para que el cajero vea algo al instante.
- **Nombres exactos del contrato** (idénticos a los del server, no inventar variantes):
  `promotionRef: { promotionId, promotionInstanceId, selections: [{ groupId, optionId }] }`.
- **3 combos = 3 `promotionInstanceId` distintos.** NUNCA `quantity: 3` — el server devuelve 400 con `quantity ≠ 1` junto a `promotionRef`.
- **Una línea de promoción viaja SOLA**: sin `productId`, sin `name`, sin `unitPrice` al lado (ni siquiera en 0 — el server lo lee como línea normal y responde 400).
- **Un 4xx de crear orden NO significa "no pasó nada"**: la orden pudo crearse y anularse. Reintentar SIEMPRE con un `externalId` nuevo.
- **Tier PRO, código `PROMOTIONS`**, espejado por nombre EXACTO. Ya existe en los dos `PlanManager`. **Fail-open es ley**: plan desconocido o server viejo ⇒ se permite. Un bug de gating jamás puede impedir cobrar.
- **Apagado se ve y se explica:** sin PRO el punto de entrada aparece con candado y dice qué plan lo prende. `HIDDEN` (preferencia del venue) sí oculta, y se revierte desde el dashboard.
- **Textos en español**, tomados literal del spec §2.5 para los estados de "no aplica".
- **Verificación (regla del workspace):** typecheck/compilación del proyecto tocado SIEMPRE, aunque la máquina esté cargada. `./gradlew assembleDebug` y `xcodebuild` son builds pesados: si `vm.swapusage` libre < 2 GB o hay otro build corriendo, se corre igual y se avisa que tardará. Nunca dos builds pesados propios a la vez.
- 🔴 **NO commitear hasta que el founder lo autorice.** Los pasos de commit están escritos y se ejecutan sólo cuando él diga "commitea". `git add <rutas explícitas>`, nunca `git add -A` (árbol compartido con otras sesiones de IA).
- Comandos: Android `./gradlew testDebugUnitTest` y `./gradlew assembleDebug` · iOS `xcodebuild -scheme avoqado-ios -destination 'platform=iOS Simulator,OS=18.5,name=iPhone 16 Pro' build` y `... test`.

---

## File Structure

| Archivo | Responsabilidad | Acción |
| --- | --- | --- |
| **SERVER** | | |
| `avoqado-server/src/services/mobile/order.mobile.service.ts` | Exponer `orderPromotionId` por línea + `promotions[]` en la respuesta | Modificar (`CreatedOrderResponse`, `toCreatedOrderResponse`, `createdOrderInclude`) |
| **ANDROID** | | |
| `pos/data/PromotionsRepository.kt` | Catálogo cacheado por venue | Crear |
| `pos/data/model/Promotion.kt` | Modelos del catálogo | Crear |
| `pos/data/model/CartItem.kt` | `promotionInstanceId` + `promotionName` en la línea | Modificar |
| `pos/presentation/cart/CartViewModel.kt` | Aplicar/quitar promoción completa | Modificar |
| `pos/presentation/promotions/PromotionsPanel.kt` | Tarjetas + estados vacío/candado | Crear |
| `pos/presentation/promotions/PromotionSheet.kt` | Hoja de elección (una pantalla) | Crear |
| `pos/presentation/checkout/CheckoutScreen.kt` | Pestaña nueva + panel lateral + caída por ancho | Modificar |
| `payment/data/model/PaymentModels.kt` | `promotionRef` en `OrderItemRequest` | Modificar |
| `payment/presentation/PaymentFlowViewModel.kt` + `cart/CartViewModel.kt` | Los DOS sitios que arman la orden | Modificar |
| `tpvsettings/data/TpvSettingsRepository.kt` | Leer `promotions` del settings | Modificar |
| `auth/data/AuthRepository.kt` | Limpiar el catálogo al cambiar de venue | Modificar |
| `customerdisplay/CustomerDisplayState.kt` + `CustomerDisplayScreen.kt` | Promos en la 2ª pantalla | Modificar |
| **iOS** | espejo exacto de lo anterior | |
| `POS/Services/PromotionsRepository.swift`, `POS/Models/PromotionModels.swift` | | Crear |
| `POS/Models/CartModels.swift`, `POS/ViewModels/CartViewModel.swift` | | Modificar |
| `POS/Components/PromotionsPanelView.swift`, `POS/Components/PromotionSheetView.swift` | | Crear |
| `POS/Views/CheckoutView.swift` | | Modificar |
| `Services/OrderRepository.swift` | `promotionRef` en `buildOrderPayload` | Modificar |
| `Payment/PaymentModels.swift` + `Services/TpvSettingsRepository.swift` | Ajuste del panel | Modificar |
| `Services/AuthRepository.swift` | Limpiar catálogo al cambiar de venue | Modificar |

---

### Task 1: El POS puede agrupar las líneas de un combo (server, aditivo)

Hoy la respuesta de crear orden devuelve las líneas del combo sueltas, sin nada que las ate. El POS no puede etiquetar "Combo del día" en el carrito ni en el recibo. `OrderItem.orderPromotionId` y `OrderPromotion` (con su snapshot) ya existen — sólo no se exponen.

**Files:**
- Modify: `avoqado-server/src/services/mobile/order.mobile.service.ts` (`CreatedOrderResponse` ~línea 74, `createdOrderInclude` ~línea 328, `toCreatedOrderResponse` ~línea 347)
- Test: `avoqado-server/tests/unit/services/mobile/createOrderPromotionResponse.test.ts` (nuevo)

**Interfaces:**
- Consumes: nada.
- Produces: cada item de la respuesta gana `orderPromotionId: string | null`; la respuesta gana
  `promotions: Array<{ id: string; instanceId: string; name: string; netCents: number; discountCents: number; needsReview: boolean }>`.
  Lo consumen las Tasks 8 y 9 para agrupar y etiquetar.

- [ ] **Step 1: Escribir el test que falla**

```typescript
// El POS recibe N líneas sueltas de un combo. Sin esto no puede agruparlas
// ni escribir "Combo del día" en el carrito ni en el recibo.
describe('createOrderWithItems — la respuesta permite agrupar las líneas de un combo', () => {
  it('devuelve orderPromotionId en cada línea nacida de una promoción', async () => {
    // prismaMock: la orden creada trae 2 items con orderPromotionId 'op-1' y 1 suelto con null
    const res = await createOrderWithItems('venue-1', { staffId: 's1', items: [/* … */] } as any)
    expect(res.items.filter(i => i.orderPromotionId === 'op-1')).toHaveLength(2)
    expect(res.items.find(i => i.orderPromotionId === null)).toBeDefined()
  })

  it('devuelve el nombre y el neto de cada promoción vendida, tomados del snapshot', async () => {
    // El snapshot es lo que se COBRÓ: si alguien edita la promo después, el
    // ticket histórico no cambia.
    const res = await createOrderWithItems('venue-1', { staffId: 's1', items: [/* … */] } as any)
    expect(res.promotions).toEqual([
      expect.objectContaining({ id: 'op-1', instanceId: 'uuid-1', name: 'Combo del día', netCents: 9900, needsReview: false }),
    ])
  })

  it('una orden sin promociones trae promotions: [] y todas las líneas con orderPromotionId null', async () => {
    const res = await createOrderWithItems('venue-1', { staffId: 's1', items: [{ productId: 'p1', quantity: 1 }] } as any)
    expect(res.promotions).toEqual([])
    expect(res.items.every(i => i.orderPromotionId === null)).toBe(true)
  })
})
```

- [ ] **Step 2: Correr el test y verificar que FALLA**

Run: `npx jest tests/unit/services/mobile/createOrderPromotionResponse.test.ts --runInBand`
Expected: FAIL — `orderPromotionId` y `promotions` no existen en la respuesta.

- [ ] **Step 3: Traer las promociones en el include**

En `createdOrderInclude`, agrega el hermano de `items` (los escalares de `OrderItem` ya vienen, incluido `orderPromotionId`, porque el include no usa `select`):

```typescript
const createdOrderInclude = {
  items: { include: { product: { select: { id: true, name: true, price: true } }, modifiers: { include: { modifier: true } } } },
  // Las promociones vendidas en esta orden. El POS las usa para agrupar sus
  // líneas y etiquetarlas; el nombre sale del SNAPSHOT (lo que se cobró), no
  // de la promoción viva, que pudo editarse después.
  promotions: { select: { id: true, instanceId: true, snapshotJson: true, netCents: true, discountCents: true, needsReview: true } },
} as const
```

- [ ] **Step 4: Exponerlas en la respuesta**

En `toCreatedOrderResponse`, agrega `orderPromotionId` al map de items y el arreglo nuevo:

```typescript
    items: flattenedOrder.items.map((item: any) => ({
      // …los campos que ya estaban, sin tocar…
      /** Ata la línea a la promoción que la creó. null = línea normal. */
      orderPromotionId: item.orderPromotionId ?? null,
    })),
    promotions: (flattenedOrder.promotions ?? []).map((p: any) => ({
      id: p.id,
      instanceId: p.instanceId,
      name: (p.snapshotJson as any)?.name ?? '',
      netCents: p.netCents,
      discountCents: p.discountCents,
      needsReview: p.needsReview,
    })),
```

Y declara ambos en la interface `CreatedOrderResponse` (el item gana `orderPromotionId: string | null`; la respuesta gana `promotions: Array<{…}>`).

- [ ] **Step 5: Correr el test y verificar que PASA**

Run: `npx jest tests/unit/services/mobile/createOrderPromotionResponse.test.ts --runInBand`
Expected: PASS (3/3).

- [ ] **Step 6: No romper lo que ya existía**

Run: `npx jest tests/unit/services/mobile/createOrderPromotion.test.ts tests/unit/services/mobile/order.mobile.service.test.ts --runInBand`
Expected: PASS. Es aditivo: ningún campo cambió de nombre ni de tipo.

- [ ] **Step 7: Typecheck**

Run: `npm run typecheck` (en `avoqado-server`)
Expected: sin errores.

- [ ] **Step 8: Commit** (sólo con autorización)

```bash
git add src/services/mobile/order.mobile.service.ts tests/unit/services/mobile/createOrderPromotionResponse.test.ts
git commit -m "feat(mobile): la orden creada dice qué líneas son de qué promoción"
```

---

### Task 2 (Android): catálogo de promociones cacheado

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/pos/data/model/Promotion.kt`
- Create: `app/src/main/java/com/avoqado/pos/pos/data/PromotionsRepository.kt`
- Modify: `app/src/main/java/com/avoqado/pos/auth/data/AuthRepository.kt` (la lista de `switchVenue()`, ~línea 176)
- Test: `app/src/test/java/com/avoqado/pos/pos/data/PromotionsRepositoryTest.kt`

**Interfaces:**
- Consumes: `GET mobile/venues/{venueId}/promotions` (plan 3A) → `{ success, data: { active: [...], upcoming: [...] } }`.
- Produces: `PromotionsRepository.promotions: StateFlow<PromotionsPayload>` y `suspend fun refresh(venueId: String)`. Lo consumen las Tasks 4 y 6.

- [ ] **Step 1: Modelos del catálogo**

Los nombres son los del server, verbatim. `quantity`/`chargedQuantity` son lo que permite escribir "Entran 2, pagas 1"; `productPriceCents` es SÓLO para el estimado en pantalla.

```kotlin
package com.avoqado.pos.pos.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PromotionOption(
    val id: String,
    val productId: String,
    val priceDeltaCents: Int = 0,
    val quantity: Int = 1,
    val chargedQuantity: Int = 1,
    val productName: String = "",
    /** Precio de lista, sólo para el estimado que se muestra. El precio real lo calcula el server. */
    val productPriceCents: Int = 0,
)

@Serializable
data class PromotionGroup(val id: String, val name: String, val options: List<PromotionOption> = emptyList())

@Serializable
data class Promotion(
    val id: String,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val type: String = "BUNDLE",
    val pricingMode: String = "FIXED_TOTAL",
    val priceCents: Int = 0,
    /** Sólo en las próximas: a qué hora abre. */
    val startsAt: String? = null,
    val groups: List<PromotionGroup> = emptyList(),
) {
    /** Un grupo con varias opciones obliga a preguntar; si ninguno la tiene, entra directo. */
    val requiereEleccion: Boolean get() = groups.any { it.options.size > 1 }
}

@Serializable
data class PromotionsPayload(val active: List<Promotion> = emptyList(), val upcoming: List<Promotion> = emptyList())
```

- [ ] **Step 2: Escribir los tests que fallan**

```kotlin
class PromotionsRepositoryTest {
    @Test fun `un fallo de red NO borra el catalogo bueno`() { /* 1º refresh OK, 2º refresh con 500 → sigue el payload viejo */ }
    @Test fun `un 403 con featureCode SI limpia el cache`() { /* candado de plan real */ }
    @Test fun `clearMemory deja el payload vacio`() { /* y se llama al cambiar de venue — ver Step 5 */ }
    @Test fun `el cache se guarda con la llave del venue`() { /* dos venues no se pisan */ }
}
```

- [ ] **Step 3: Correr y verificar que FALLAN**

Run: `./gradlew testDebugUnitTest --tests "*PromotionsRepositoryTest*"`
Expected: FAIL (la clase no existe).

- [ ] **Step 4: Implementar el repositorio**

**Copia `pos/data/UpsellRepository.kt` casi literal** — mismo `PayloadCache` (llave `"$type:$venueId"`), mismo cache-first, misma distinción de 403-con-`featureCode` (`isPlanLock`) como ÚNICO caso que limpia, mismo "un fallo de red conserva lo bueno". Cambia: el tipo del payload, la ruta (`mobile/venues/$venueId/promotions`) y la constante `TYPE = "promotions"`.

🔴 **Lo único que NO se copia es el hueco**: `UpsellRepository.clearMemory()` no lo llama nadie. El tuyo sí se llama (Step 5).

- [ ] **Step 5: Limpiar al cambiar de venue**

En `AuthRepository.switchVenue()` (~línea 176), agrega el repositorio a la lista que ya existe, junto a los demás:

```kotlin
        promotionsRepository.clearCache()   // junto a productsRepository.clearCache(), etc.
        // …y en el bloque de refetch de abajo:
        promotionsRepository.refresh(venueId)
```

Sin esto, cambiar de local **sin red** deja las promociones del local anterior en pantalla — el bug que ya nos señalaron en la auditoría del switch.

- [ ] **Step 6: Correr los tests y verificar que PASAN**

Run: `./gradlew testDebugUnitTest --tests "*PromotionsRepositoryTest*"`
Expected: PASS.

- [ ] **Step 7: Compilar**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. (Build pesado: si la máquina está cargada, se corre igual y se avisa que tardará.)

- [ ] **Step 8: Commit** (sólo con autorización)

```bash
git add app/src/main/java/com/avoqado/pos/pos/data/PromotionsRepository.kt app/src/main/java/com/avoqado/pos/pos/data/model/Promotion.kt app/src/main/java/com/avoqado/pos/auth/data/AuthRepository.kt app/src/test/java/com/avoqado/pos/pos/data/PromotionsRepositoryTest.kt
git commit -m "feat(promociones): catalogo cacheado por venue, se limpia al cambiar de local"
```

---

### Task 3 (iOS): espejo de la Task 2

**Files:**
- Create: `avoqado-ios/POS/Models/PromotionModels.swift`, `avoqado-ios/POS/Services/PromotionsRepository.swift`
- Modify: `avoqado-ios/Services/AuthRepository.swift` (`switchVenue`, ~línea 156) y `POS/Views/CheckoutView.swift` (`switchToTpvVenue`, ~línea 955, donde ya se limpian `ProductsRepository` y `DiscountsRepository`)
- Test: `avoqado-iosTests/PromotionsRepositoryTests.swift`

**Interfaces:**
- Produces: `PromotionsRepository.shared.payload: PromotionsPayload` (`@Published`) y `func fetchPromotions() async`. Mismos nombres de campo que Android.

- [ ] **Step 1: Modelos, espejo exacto de los de Android**

Con `decodeIfPresent` + default en cada campo, como `UpsellModels.swift` — para tolerar un server viejo sin tronar.

```swift
struct PromotionOption: Codable {
    let id: String
    let productId: String
    let priceDeltaCents: Int
    let quantity: Int
    let chargedQuantity: Int
    let productName: String
    /// Precio de lista, sólo para el estimado en pantalla. El real lo calcula el server.
    let productPriceCents: Int
}
struct PromotionGroup: Codable { let id: String; let name: String; let options: [PromotionOption] }
struct Promotion: Codable {
    let id: String; let name: String; let description: String?; let imageUrl: String?
    let type: String; let pricingMode: String; let priceCents: Int; let startsAt: String?
    let groups: [PromotionGroup]
    /// Un grupo con varias opciones obliga a preguntar.
    var requiereEleccion: Bool { groups.contains { $0.options.count > 1 } }
}
struct PromotionsPayload: Codable { var active: [Promotion] = []; var upcoming: [Promotion] = [] }
```

- [ ] **Step 2: Escribir los tests que fallan** (mismos 4 casos que Android, con XCTest — el patrón de `UpsellTests.swift`)

- [ ] **Step 3: Correr y verificar que FALLAN**

Run: `xcodebuild test -scheme avoqado-ios -destination 'platform=iOS Simulator,OS=18.5,name=iPhone 16 Pro' -only-testing:avoqado-iosTests/PromotionsRepositoryTests`
Expected: falla de compilación (la clase no existe) — es el rojo válido aquí.

- [ ] **Step 4: Implementar** copiando `POS/Services/UpsellRepository.swift`: singleton `.shared`, `UserDefaults` con llave `"promotions:\(venueId)"`, hidratar de disco si la memoria está vacía, red siempre, 403-con-`featureCode` como único caso que borra, cualquier otro fallo conserva.

- [ ] **Step 5: Llamar a `clearMemory()` de verdad**

En `AuthRepository.switchVenue` y en `CheckoutView.switchToTpvVenue` (junto a los `clearCache()` que ya están):

```swift
PromotionsRepository.shared.clearMemory()
```

🔴 En iOS la llave de disco de `TpvSettings` es GLOBAL (`"cached_tpv_settings"`, sin venueId). La del catálogo de promociones **sí** lleva `venueId` — no repitas ese patrón.

- [ ] **Step 6: Tests en verde** (mismo comando del Step 3)

- [ ] **Step 7: Compilar**

Run: `xcodebuild -scheme avoqado-ios -destination 'platform=iOS Simulator,OS=18.5,name=iPhone 16 Pro' build`
Expected: BUILD SUCCEEDED.

- [ ] **Step 8: Commit** (sólo con autorización)

```bash
git add avoqado-ios/POS/Models/PromotionModels.swift avoqado-ios/POS/Services/PromotionsRepository.swift avoqado-ios/Services/AuthRepository.swift avoqado-ios/POS/Views/CheckoutView.swift avoqado-iosTests/PromotionsRepositoryTests.swift
git commit -m "feat(promociones): catalogo cacheado por venue, espejo de Android"
```

---

### Task 4 (Android): el panel en la pantalla de cobro

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/pos/presentation/promotions/PromotionsPanel.kt`
- Modify: `app/src/main/java/com/avoqado/pos/pos/presentation/checkout/CheckoutScreen.kt` (`InputTab` líneas 92-97; `when` del bloque tablet 469-528; `when` del bloque teléfono 634-689; el `Row` tablet 426-596)
- Modify: `app/src/main/java/com/avoqado/pos/tpvsettings/data/TpvSettingsRepository.kt` (`TpvSettings` líneas 27-47)
- Test: `app/src/test/java/com/avoqado/pos/pos/presentation/promotions/PromotionsPanelLayoutTest.kt`

**Interfaces:**
- Consumes: `PromotionsRepository.promotions` (Task 2), `TpvSettings.promotions.panelCashier`.
- Produces: `PromotionsPanel(...)` composable y `fun resolverModoPanel(ajuste: PanelMode, anchoDp: Int): PanelMode` (la caída automática). La Task 6 lo usa para abrir la hoja.

- [ ] **Step 1: Leer el ajuste del server**

En `TpvSettings` (líneas 27-47), agrega el campo con default — `ignoreUnknownKeys = true` ya está, así que un server viejo no rompe nada:

```kotlin
@Serializable
enum class PanelMode { HIDDEN, TAB, SIDE_PANEL }

@Serializable
data class PromotionsPanelSettings(
    val panelCashier: PanelMode = PanelMode.TAB,
    val panelCustomer: PanelMode = PanelMode.SIDE_PANEL,
)
// dentro de TpvSettings:
val promotions: PromotionsPanelSettings = PromotionsPanelSettings(),
```

- [ ] **Step 2: Escribir el test de la caída automática (lógica pura, sin UI)**

```kotlin
class PromotionsPanelLayoutTest {
    // Con panel lateral, la columna de entrada se queda con el 37.5% del ancho.
    // Una celda de producto necesita ~120dp y son 3 columnas -> 360dp -> el
    // lateral sólo cabe a partir de ~960dp. Debajo de eso es ilegible.
    @Test fun `el panel lateral cae a pestana bajo el umbral`() {
        assertEquals(PanelMode.TAB, resolverModoPanel(PanelMode.SIDE_PANEL, anchoDp = 800))
        assertEquals(PanelMode.SIDE_PANEL, resolverModoPanel(PanelMode.SIDE_PANEL, anchoDp = 1370))
    }
    @Test fun `pestana se respeta en cualquier ancho`() {
        assertEquals(PanelMode.TAB, resolverModoPanel(PanelMode.TAB, anchoDp = 1370))
    }
    @Test fun `oculto NUNCA se convierte en visible`() {
        assertEquals(PanelMode.HIDDEN, resolverModoPanel(PanelMode.HIDDEN, anchoDp = 1370))
    }
}
```

- [ ] **Step 3: Correr y verificar que FALLAN**

Run: `./gradlew testDebugUnitTest --tests "*PromotionsPanelLayoutTest*"`

- [ ] **Step 4: Implementar la resolución de modo**

```kotlin
/** Ancho mínimo para que el panel lateral sea usable. Derivado, no inventado:
 *  con el lateral, la columna de entrada baja al 37.5%; 3 columnas de producto
 *  a ~120dp piden 360dp. Ajustable con un dispositivo real enfrente. */
const val ANCHO_MINIMO_PANEL_LATERAL_DP = 960
const val ANCHO_MINIMO_CELDA_PRODUCTO_DP = 120

fun resolverModoPanel(ajuste: PanelMode, anchoDp: Int): PanelMode =
    if (ajuste == PanelMode.SIDE_PANEL && anchoDp < ANCHO_MINIMO_PANEL_LATERAL_DP) PanelMode.TAB else ajuste
```

- [ ] **Step 5: La pestaña**

Agrega `PROMOS("Promociones")` a `InputTab` (líneas 92-97) — `TabSelectorView` ya la pinta sola porque itera `InputTab.entries`. Agrega su branch en los DOS `when` (tablet 469-528 y teléfono 634-689). 🔴 **La pestaña sólo aparece si el modo resuelto es `TAB`**: con `SIDE_PANEL` o `HIDDEN` no debe estar en la lista, o el cajero tendría dos entradas a lo mismo.

- [ ] **Step 6: El panel lateral**

En el `Row` tablet, hoy hay dos `Box` a `weight(0.5f)`. Con modo `SIDE_PANEL` pasa a tres: entrada `0.5f` · promociones `0.25f` · carrito `0.25f`, con el mismo divisor de `1.dp` entre columnas que ya existe (líneas 533-539). **No hay precedente de 3 columnas en este archivo**: es trabajo estructural, hazlo con el `BoxWithConstraints`/`maxWidth` que da el ancho real para alimentar `resolverModoPanel`.

- [ ] **Step 7: Las tarjetas**

`PromotionsPanel.kt` pinta, en este orden: vigentes primero (por `displayOrder`, luego nombre); debajo las próximas en gris y **no tocables**, con "empieza a las 6:00 pm" (`startsAt`). Gancho grande según `pricingMode`: `PER_UNIT` → "2x1" derivado de `quantity`/`chargedQuantity` de la primera opción; `FIXED_TOTAL` → el precio. Usa `AvoqadoSuccessToast` al agregar y `SearchPillField` si hay más de ~8 promociones.

**Estados que NO son un botón muerto** (textos literales del spec):
- Sin PRO → `PlanGate` con el candado. El precedente real de gate chico dentro del carrito es el bloque manual de `ReferralCaptureSection.kt:119-135`, no `PlanGateInlineNote` (que está definido pero sin uso).
- **Sin el permiso `discounts:apply`** → el panel se ve, pero las tarjetas no aplican: "Pídele a tu administrador el permiso para aplicar promociones." El server lo exige en los dos caminos (plan 3A), así que sin este aviso el cajero tocaría y recibiría un 403 seco. Léelo del mismo lugar donde la app ya lee permisos por rol.
- Con PRO y sin promociones publicadas → "Aún no hay promociones. Créalas desde el dashboard."
- Promoción que aún no abre → "Esta promoción es de 6:00 a 8:00 pm. Faltan 40 minutos."

- [ ] **Step 8: Tests en verde + compilar**

Run: `./gradlew testDebugUnitTest --tests "*PromotionsPanelLayoutTest*"` y luego `./gradlew assembleDebug`

- [ ] **Step 9: Commit** (sólo con autorización)

```bash
git add app/src/main/java/com/avoqado/pos/pos/presentation/promotions/ app/src/main/java/com/avoqado/pos/pos/presentation/checkout/CheckoutScreen.kt app/src/main/java/com/avoqado/pos/tpvsettings/data/TpvSettingsRepository.kt app/src/test/java/com/avoqado/pos/pos/presentation/promotions/
git commit -m "feat(promociones): panel en la pantalla de cobro, pestana o lateral segun el ancho"
```

---

### Task 5 (iOS): espejo de la Task 4

**Files:**
- Create: `avoqado-ios/POS/Components/PromotionsPanelView.swift`
- Modify: `avoqado-ios/POS/Views/CheckoutView.swift` (`InputTab` líneas 10-15; `tabContent` 1648-1691; el `GeometryReader` del iPad 979-1210)
- Modify: `avoqado-ios/Payment/PaymentModels.swift` (`TpvSettings` 33-121) + `avoqado-ios/Services/TpvSettingsRepository.swift` (`TpvSettingsDto` 430-467 y su `toDomain()` 448-466)
- Test: `avoqado-iosTests/PromotionsPanelLayoutTests.swift`

- [ ] 🔴 **Step 1: El ajuste — CORREGIDO (el texto original de este plan estaba MAL, y Android ya lo detectó).**

El server manda `promotions` como **HERMANO** de `settings` dentro de `data`, **no dentro de `TpvSettings`** — porque es ajuste de **venue**, no de **terminal** (`tpvSettings.mobile.controller.ts:147-150`). Si lo parseas dentro del DTO de settings, el campo **siempre vale el default**: compila, pasa los tests, y lo que el dueño configuró en el dashboard nunca llega al POS. Bug silencioso.

Haz lo que hizo Android (`task-4-report.md`): parsearlo del contenedor de la respuesta (el hermano de `settings`) y **copiarlo al `TpvSettings` resuelto**, para que el caché de disco lo conserve sin red. En iOS eso significa tocar el struct de la respuesta (`MobileSettingsResponse`/`data`) en `Services/TpvSettingsRepository.swift`, no sólo `TpvSettingsDto`.

- [ ] **Step 2-3: El test de caída, idéntico al de Android** (mismos tres casos, mismos umbrales) y verificarlo rojo.

- [ ] **Step 4: La función, espejo exacto — con la estructura que Android acabó teniendo, no sólo el número**

🔴 **El texto original de este plan traía una derivación FALSA (37.5%) que Android detectó y corrigió.** La verdad: la columna de entrada **se queda en 50%**, así que el piso estricto es `120 × 3 / 0.5 = 720`; **960 es ese piso más un margen deliberado**, porque a 720 cada columna lateral cae a ~180 y la tarjeta se ve apretada. Espeja las tres piezas, no sólo la cifra:

```swift
/// Ancho mínimo de una celda de producto. De aquí sale el piso, no es decorativo.
let anchoMinimoCeldaProducto: CGFloat = 120
/// Piso ESTRICTO derivado: con el panel lateral la entrada se queda en 50%,
/// así que 3 columnas de producto piden 720. Por debajo de esto el lateral es
/// inservible por aritmética, no por gusto.
let anchoEstrictoPanelLateral: CGFloat = (anchoMinimoCeldaProducto * 3) / 0.5
/// El umbral REAL = piso + margen deliberado (pendiente de ajustar con hardware).
/// Mismo número que Android: si cambia allá, cambia aquí el mismo día.
let anchoMinimoPanelLateral: CGFloat = 960

func resolverModoPanel(_ ajuste: PanelMode, anchoPt: CGFloat) -> PanelMode {
    (ajuste == .sidePanel && anchoPt < anchoMinimoPanelLateral) ? .tab : ajuste
}
```

Y agrega el **guardrail** que Android tiene: un test que falle si el umbral baja del piso derivado (`anchoMinimoPanelLateral >= anchoEstrictoPanelLateral`). Es el test que habría cazado el error original.

🔴 **Aquí está la diferencia técnica con Android:** iOS decide iPad/iPhone por **idiom** (`UIDevice.current.userInterfaceIdiom == .pad`, línea 92-94), no por ancho — un iPad en Slide Over angosto hoy sigue recibiendo el layout de iPad completo. Alimenta `resolverModoPanel` con `geometry.size.width` del `GeometryReader` que YA envuelve el layout de iPad (línea 979), no con `isIPad`.

- [ ] **Step 5: La pestaña** — un `case` en `InputTab` (líneas 10-15) y otro en el `switch` de `tabContent` (1650-1690). El selector ya itera `allCases`.

- [ ] **Step 6: El panel lateral** — el precedente visual son los tres overlays que ya existen (`ProductDetailPanelView` 1082-1119, etc.): borde `systemGray4` a la izquierda, sombra, `.transition(.move(edge: .trailing))`. **Pero con `.frame(width: geometry.size.width * 0.25)`, no los 400pt fijos de esos overlays.** Va encadenado después del overlay de `CartItemDetailPanelView` (línea 1187) y antes del `.bottomSheet` (1188).

- [ ] **Step 7: Las tarjetas** — mismos textos en español, mismo orden, mismo gancho. Candado con `PlanGateView`/`InlinePlanGateView` (`Components/PlanGateView.swift`). Toast: `AvoqadoSuccessToast`.

- [ ] **Step 8: Tests en verde + compilar**

- [ ] **Step 9: Commit** (sólo con autorización)

---

### Task 6 (Android): la hoja de elección y el carrito

**Decisión del founder (2026-08-15): una sola pantalla con todos los grupos**, que scrollea si hay muchos — igual que los modificadores de un producto hoy. NO paso a paso.

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/pos/presentation/promotions/PromotionSheet.kt`
- Modify: `app/src/main/java/com/avoqado/pos/pos/data/model/CartItem.kt` (agregar 2 campos)
- Modify: `app/src/main/java/com/avoqado/pos/pos/presentation/cart/CartViewModel.kt`
- Test: `app/src/test/java/com/avoqado/pos/pos/presentation/cart/CartPromotionTest.kt`

**Interfaces:**
- Consumes: `Promotion` (Task 2), `requiereEleccion`.
- Produces: `CartViewModel.aplicarPromocion(promotion: Promotion, selecciones: Map<String, String>)` y `quitarPromocion(instanceId: String)`. La Task 8 lee las líneas para armar el request.

- [ ] **Step 1: La línea del carrito sabe de qué promoción viene**

En `CartItem` (líneas 13-40), junto a `areaTicketId` — **ése es el precedente**: varias líneas planas atadas por un id común.

```kotlin
    /** Instancia de promoción a la que pertenece esta línea. null = línea normal.
     *  Varias líneas comparten el mismo valor: es lo que las agrupa y lo que
     *  hace que se quiten juntas. Mismo patrón que areaTicketId. */
    val promotionInstanceId: String? = null,
    /** Para etiquetar "Combo del día" en el carrito sin volver al catálogo. */
    val promotionName: String? = null,
```

- [ ] **Step 2: Escribir los tests que fallan**

```kotlin
class CartPromotionTest {
    @Test fun `aplicar un 2x1 agrega UNA linea con la cantidad que entra`() {
        // PER_UNIT quantity=2, chargedQuantity=1 -> una línea de cantidad 2.
        // (El 2x1 entra como UNA línea de cantidad 2 porque la deducción de
        //  inventario del server multiplica por quantity.)
    }
    @Test fun `aplicar un combo agrega una linea por opcion elegida, todas con el mismo instanceId`() {}
    @Test fun `quitar CUALQUIER linea de una promocion quita la promocion completa`() {}
    @Test fun `dos combos iguales son dos instanceId distintos`() {
        // 3 combos = 3 instanceId. NUNCA quantity: 3.
    }
    @Test fun `el estimado local usa productPriceCents y priceDeltaCents`() {
        // Combo $99 + opción con +$15 -> estimado $114. Es SÓLO para mostrar.
    }
}
```

- [ ] **Step 3: Correr y verificar que FALLAN**

Run: `./gradlew testDebugUnitTest --tests "*CartPromotionTest*"`

- [ ] **Step 4: Aplicar y quitar**

```kotlin
fun aplicarPromocion(promotion: Promotion, selecciones: Map<String, String>) {
    val instanceId = UUID.randomUUID().toString()   // la llave de idempotencia del server
    val nuevas = promotion.groups.mapNotNull { grupo ->
        val opcion = grupo.options.firstOrNull { it.id == selecciones[grupo.id] }
            ?: grupo.options.singleOrNull()          // grupo de una sola opción: no se preguntó
            ?: return@mapNotNull null
        CartItem(
            type = CartItemType.ProductItem(opcion.productId),
            name = opcion.productName,
            // Estimado local: el precio REAL lo pone el server al cobrar.
            unitPrice = opcion.productPriceCents + opcion.priceDeltaCents,
            quantity = opcion.quantity,              // 2 en un 2x1
            promotionInstanceId = instanceId,
            promotionName = promotion.name,
        )
    }
    _cartState.update { it.copy(items = it.items + nuevas) }
}

/** Una promoción se quita COMPLETA: quitar una línea quita todas sus hermanas. */
fun quitarPromocion(instanceId: String) {
    _cartState.update { it.copy(items = it.items.filterNot { i -> i.promotionInstanceId == instanceId }) }
}
```

Y en el borrado de línea que ya existe: si la línea trae `promotionInstanceId`, redirige a `quitarPromocion` y avisa antes ("Se quitará el combo completo").

- [ ] **Step 5: La hoja**

`PromotionSheet.kt` — `ModalBottomSheet` con **todos** los grupos de más de una opción en un `Column` scrolleable (los de una sola opción no se preguntan). Un grupo = título + opciones tipo radio, con el `+$15` del `priceDeltaCents` a la derecha. Abajo, `PrimaryButton` con el estimado ("Agregar al carrito · $114"), deshabilitado hasta que cada grupo tenga su elección. Cierre con `AvoqadoFullscreenHeader(navStyle = CLOSE)` si se hace pantalla completa.

Si `requiereEleccion == false` la hoja **no se abre**: la promoción entra directo al carrito.

- [ ] **Step 6: Tests en verde + compilar**

- [ ] **Step 7: Commit** (sólo con autorización)

---

### Task 7 (iOS): espejo de la Task 6

**Files:**
- Create: `avoqado-ios/POS/Components/PromotionSheetView.swift`
- Modify: `avoqado-ios/POS/Models/CartModels.swift` (`CartItem`, líneas 13-106), `avoqado-ios/POS/ViewModels/CartViewModel.swift`
- Test: `avoqado-iosTests/CartPromotionTests.swift` (patrón de `CartViewModelTests.swift`, XCTest)

- [ ] **Step 1: La línea del carrito**

En `CartItem`, junto a `areaTicketId` (líneas 35-37) — mismo precedente que en Android:

```swift
    /// Instancia de promoción a la que pertenece esta línea. nil = línea normal.
    /// Varias líneas comparten el valor: es lo que las agrupa y lo que hace que se quiten juntas.
    let promotionInstanceId: String?
    /// Para etiquetar "Combo del día" en el carrito sin volver al catálogo.
    let promotionName: String?
```

- [ ] **Step 2: Los mismos 5 tests que Android**, con estos nombres:
`testAplicarUn2x1AgregaUnaLineaConLaCantidadQueEntra`, `testAplicarUnComboAgregaUnaLineaPorOpcionElegidaConElMismoInstanceId`, `testQuitarCualquierLineaQuitaLaPromocionCompleta`, `testDosCombosIgualesSonDosInstanceIdDistintos`, `testElEstimadoLocalUsaProductPriceCentsYPriceDelta`.

- [ ] **Step 3: Correr y verificar que FALLAN**

Run: `xcodebuild test -scheme avoqado-ios -destination 'platform=iOS Simulator,OS=18.5,name=iPhone 16 Pro' -only-testing:avoqado-iosTests/CartPromotionTests`

- [ ] **Step 4: Aplicar y quitar — espejo exacto**

```swift
func aplicarPromocion(_ promotion: Promotion, selecciones: [String: String]) {
    let instanceId = UUID().uuidString          // la llave de idempotencia del server
    let nuevas: [CartItem] = promotion.groups.compactMap { grupo in
        guard let opcion = grupo.options.first(where: { $0.id == selecciones[grupo.id] })
            ?? (grupo.options.count == 1 ? grupo.options.first : nil) else { return nil }
        return CartItem(
            type: .product(productId: opcion.productId),
            name: opcion.productName,
            // Estimado local: el precio REAL lo pone el server al cobrar.
            unitPrice: opcion.productPriceCents + opcion.priceDeltaCents,
            quantity: opcion.quantity,           // 2 en un 2x1
            promotionInstanceId: instanceId,
            promotionName: promotion.name
        )
    }
    items.append(contentsOf: nuevas)
}

/// Una promoción se quita COMPLETA.
func quitarPromocion(instanceId: String) {
    items.removeAll { $0.promotionInstanceId == instanceId }
}
```

Y en el borrado de línea existente: si trae `promotionInstanceId`, redirige a `quitarPromocion` avisando "Se quitará el combo completo".

- [ ] **Step 5: La hoja** — `.bottomSheet(isPresented:)` (`Components/BottomSheetModal.swift`) con **todos** los grupos de más de una opción en un `ScrollView`, botón primario `.primaryButtonStyle()` con el estimado, deshabilitado hasta que cada grupo tenga elección, y cierre con el botón X circular estándar (nunca texto "Cerrar"). Si `requiereEleccion == false`, no se abre: entra directo.

- [ ] **Step 6: Tests en verde + compilar**

- [ ] **Step 7: Commit** (sólo con autorización)

---

### Task 8 (Android): mandar la promoción al server

**Files:**
- Modify: `app/src/main/java/com/avoqado/pos/payment/data/model/PaymentModels.kt` (`OrderItemRequest`, líneas 94-137)
- Modify: `app/src/main/java/com/avoqado/pos/payment/presentation/PaymentFlowViewModel.kt` (`buildOrderRequest`, 1837-1877) **y** `app/src/main/java/com/avoqado/pos/pos/presentation/cart/CartViewModel.kt` (`createPayLaterOrder`, 1075-1117) — **son DOS sitios que arman lo mismo; si sólo tocas uno, "pagar después" cobra el combo a precio de lista.**
- Test: `app/src/test/java/com/avoqado/pos/payment/OrderRequestPromotionTest.kt`

**Interfaces:**
- Consumes: las líneas con `promotionInstanceId` (Task 6).
- Produces: el request con `promotionRef`, que el server (plan 3A) ya sabe recibir.

- [ ] **Step 1: El contrato**

```kotlin
@Serializable
data class PromotionSelectionRequest(val groupId: String, val optionId: String)

@Serializable
data class PromotionRefRequest(
    val promotionId: String,
    val promotionInstanceId: String,
    val selections: List<PromotionSelectionRequest> = emptyList(),
)
// en OrderItemRequest, TODOS los demás campos siguen igual:
val promotionRef: PromotionRefRequest? = null,
```

- [ ] **Step 2: Escribir los tests que fallan**

```kotlin
class OrderRequestPromotionTest {
    @Test fun `una promocion viaja como UN item con promotionRef y sin precios`() {
        // Ni productId, ni name, ni unitPrice: el server los lee como línea
        // normal y responde 400.
    }
    @Test fun `las lineas normales viajan exactamente igual que antes`() {}
    @Test fun `3 combos viajan como 3 items con instanceId distintos, nunca quantity 3`() {}
    @Test fun `pagar despues arma el mismo request que cobrar`() {
        // El bug clásico: tocar sólo uno de los dos sitios.
    }
}
```

- [ ] **Step 3: Correr y verificar que FALLAN**

- [ ] **Step 4: Implementar en LOS DOS sitios**

Agrupa las líneas del carrito por `promotionInstanceId`: cada grupo produce **un** `OrderItemRequest` con `promotionRef` (y nada más), y las líneas sin instancia siguen el camino de siempre.

- [ ] **Step 5: Reintento tras 4xx**

Un 400/403 de crear orden **no** significa "no pasó nada": la orden pudo crearse y anularse. Al reintentar, genera un `externalId` NUEVO (`sessionIdempotencyKey()` se limpia por venta) y **conserva el mismo `promotionInstanceId`** si es la misma venta encolada.

- [ ] **Step 6: Verificar el camino offline de mostrador** 🔴

Averigua si una venta de mostrador **sin mesa y sin red** tiene cola en Android (en iOS la tiene: `PendingOrderStore`). En este repo `OPEN_TABLE` sólo se encola desde la pantalla de mesas. **Si no existe la cola, NO la construyas**: documenta el hallazgo en el reporte y sigue — es un hueco anterior a promociones, y decidirlo es del founder.

- [ ] **Step 7: Tests en verde + compilar**

- [ ] **Step 8: Commit** (sólo con autorización)

---

### Task 9 (iOS): espejo de la Task 8

**Files:**
- Modify: `avoqado-ios/Services/OrderRepository.swift` (`buildOrderPayload`, líneas 97-162) — 🔴 hoy **filtra fuera** los items sin `productId`, así que una línea de promoción se caería en silencio: ése es el punto exacto a tocar.
- Test: `avoqado-iosTests/OrderPayloadPromotionTests.swift`

- [ ] **Step 1: Los 4 tests que fallan** — los mismos casos que Android (`promocionViajaSinPrecios`, `lineasNormalesIgualQueAntes`, `tresCombosTresInstanceId`, `payloadSobreviveSerializacion`).

- [ ] **Step 2: Correr y verificar que FALLAN**

- [ ] **Step 3: Agrupar por instancia en `buildOrderPayload`**

Las líneas con `promotionInstanceId` NO entran por el camino de `productId` (que hoy las descartaría): se agrupan por instancia y cada grupo produce **un** dict con sólo `promotionRef`:

```swift
// Una promoción viaja SOLA: sin productId, sin name, sin unitPrice —
// el server los lee como línea normal y responde 400.
["promotionRef": [
    "promotionId": promotionId,
    "promotionInstanceId": instanceId,
    "selections": selections.map { ["groupId": $0.groupId, "optionId": $0.optionId] },
]]
```

- [ ] **Step 4: El camino offline sale gratis, pero pruébalo**

Ese mismo payload es el que `PaymentFlowViewModel.submitCartForPaymentOffline` (líneas 1127-1186) persiste tal cual en `PendingOrderStore` y reproduce después. El test `payloadSobreviveSerializacion` debe armar el payload, serializarlo a JSON, volverlo a leer y verificar que `promotionRef` sigue completo — es lo único que prueba que la venta sin red conserva el combo.

- [ ] **Step 5: Reintento tras 4xx** — `externalId` NUEVO, mismo `promotionInstanceId`. Igual que Android.

- [ ] **Step 6: Tests en verde + compilar**

- [ ] **Step 7: Commit** (sólo con autorización)

---

### Task 10 (Android + iOS): devolver y partir sin romper un combo

El server ya defiende las dos cosas; falta que el POS **no deje al cajero llegar al error**. Ambas apps, misma entrega.

**Files:**
- Modify (Android): `app/src/main/java/com/avoqado/pos/transactions/presentation/` (la hoja de reembolso) y la pantalla de dividir cuenta en `tables/presentation/`
- Modify (iOS): el equivalente de reembolso y `POS/Components/SplitPaymentSheet.swift`
- Test: uno por app, sobre la lógica de agrupación (no sobre la UI)

**Interfaces:**
- Consumes: `orderPromotionId` por línea (Task 1) y `promotions[]` de la respuesta.

- [ ] **Step 1: Los tests que fallan**

```
- devolver una línea de promoción ofrece el COMBO COMPLETO, no la línea suelta
- al dividir, las líneas de una misma promoción se mueven JUNTAS a un lado
```

- [ ] **Step 2: Correr y verificar que FALLAN**

- [ ] **Step 3: Reembolso**

El server **rechaza** devolver una parte (`assertPromotionLineFullQuantity`: `refundQty !== line.quantity` lanza). El POS agrupa por `orderPromotionId` y ofrece **"Devolver el combo completo"** — nunca deja seleccionar media promoción para descubrirlo al enviar.

- [ ] **Step 4: Dividir cuenta**

La promoción es **indivisible**: se va entera a un lado. Avisar en el momento ("El combo se va completo a esta cuenta"), no al confirmar. El POS ya sabe qué líneas son de qué promoción por `orderPromotionId`.

- [ ] **Step 5: Tests en verde + compilar ambas**

- [ ] **Step 6: Commit** (sólo con autorización)

---

### Task 11 (Android): promociones en la pantalla del cliente

**Sólo Android.** El iPad no tiene el mecanismo de segunda pantalla de las Sunmi; es excepción de plataforma declarada en el spec, no un port pendiente.

**Files:**
- Modify: `app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayState.kt` (sealed `CustomerContent`, líneas 20-89) y `CustomerDisplayScreen.kt` (el `when`, líneas 97-111)
- Modify: el ViewModel que gobierna el panel (Task 4), para empujar el estado

**El precedente casi exacto es `CustomerContent.Upsell`** (líneas 44-53): cards + selección, con la nota de que en pantallas no táctiles se pinta igual pero sin interacción.

- [ ] **Step 1: El estado nuevo**

En la sealed `CustomerContent` (líneas 20-89), calcado de `Upsell`:

```kotlin
    /** Vitrina de promociones en la pantalla del cliente. Se VE, no se toca:
     *  la venta la opera el cajero. En pantallas no táctiles (T3 Pro) se pinta
     *  igual — sirve para que el cajero la señale de viva voz. */
    data class Promotions(val cards: List<Promotion>, val totalCents: Int) : CustomerContent()
```

- [ ] **Step 2: El branch** — uno nuevo en el `when` de `CustomerDisplayScreen.kt:97-111`, con el mismo tamaño de tipografía y contraste que usa `CartMirror` (la pantalla del cliente se lee a un metro de distancia, no a treinta centímetros).

- [ ] **Step 3: El push** — desde el ViewModel del panel (Task 4), calcado de `UpsellViewModel.kt:147-150`. Se empuja cuando `panelCustomer != HIDDEN` y hay promociones vigentes; al vaciarse, vuelve al estado que había.

- [ ] **Step 4: Compilar** — `./gradlew assembleDebug`.

- [ ] **Step 5: Commit** (sólo con autorización)

---

### Task 12: paridad y cierre

- [ ] **Step 1: Diff de paridad**

Compara lado a lado los nombres de campo, los métodos públicos y los textos en español de las dos apps. Cualquier diferencia que no sea una excepción declarada de plataforma es un defecto.

- [ ] **Step 2: Las dos apps compilan**

Run: `./gradlew assembleDebug` y `xcodebuild -scheme avoqado-ios -destination 'platform=iOS Simulator,OS=18.5,name=iPhone 16 Pro' build`
🔴 **Nunca los dos a la vez** (dos builds pesados propios tumban la máquina). Uno, luego el otro.

- [ ] **Step 3: Suites de ambas**

Run: `./gradlew testDebugUnitTest` y `xcodebuild test -scheme avoqado-ios -destination 'platform=iOS Simulator,OS=18.5,name=iPhone 16 Pro'`

- [ ] **Step 4: Presentación de ventas** (regla del workspace, ahora sí aplica)

Con el POS ya vendiendo combos, la capacidad es visible para el cliente: actualiza el deck y los DOS one-pagers en `~/Documents/Programming/Avoqado-HQ/operations/marketing/platform-presentation/` **y regenera los 3 PDFs** con el comando de Chrome headless del `README.md` de esa carpeta. Editar el HTML sin regenerar el PDF es un cambio incompleto.

- [ ] **Step 5: Reporte al founder** — qué quedó, qué falta, y el QA en device (el founder pidió explícitamente un recorrido completo: crear promo en dashboard → venderla en el POS → verificar DB y log del backend).

---

## Lo que este plan NO hace

- No toca el motor de promociones ni la aritmética: eso es del server y ya está probado.
- No construye una cola de venta de mostrador offline para Android si resulta que no existe (Task 8 Step 6): se reporta.
- No expone "quitar promoción" de una orden YA creada: en venta rápida se edita el carrito antes de cobrar. Para mesas haría falta exponer `removePromotionFromOrder`, que existe en el server pero no tiene ruta `/mobile`.
- No mete promociones en el KDS (cocina ve los componentes) ni en la 2ª pantalla de iOS (no existe el mecanismo).

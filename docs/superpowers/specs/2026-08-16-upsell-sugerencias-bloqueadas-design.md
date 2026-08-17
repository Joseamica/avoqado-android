# Upsell "¿Algo más?" — sugerencias que hoy nacen muertas

**Fecha:** 2026-08-16 · **Estado:** listo para plan · **Decisor:** founder
**Alcance de este spec:** A + B. **C queda fuera a propósito** (ver §7).
**Repos:** avoqado-server · avoqado-web-dashboard · avoqado-android · avoqado-ios

## 1. El problema, medido

El founder configuró el upsell en un venue PRO, con 3 reglas activas y la superficie
"mostrador" encendida, y **en el POS no aparecía nada**. Diagnóstico sobre datos reales
(Restaurante El Atole, D3, 2026-08-16):

| Capa | Estado |
|---|---|
| Plan (UPSELL, tier PRO) | ✅ concedido |
| `Venue.upsellSurfaces` | ✅ `{counter:true, tableOrdering:true, tablePaying:true}` |
| Endpoint `/mobile/venues/:id/upsell-rules` | ✅ 200, devuelve 3 reglas + `holdoutPercent: 10` |
| Carrito | ✅ 2 artículos, modo Retail (no `isTablePaying`) |
| **Producto sugerido** | ❌ **"Agua Mineral 1L" tiene el grupo "Tamaño" como obligatorio** |

`UpsellResolver` descarta la tarjeta (`avoqado-android/.../pos/domain/UpsellResolver.kt:70`)
porque `hasRequiredModifierGroup`. La regla es correcta y coincide con el mercado: Square
documenta que *"Items without any variations or required modifiers will always add directly
to the cart"* — con obligatorios SIEMPRE abre la pantalla de detalle, nunca entra de un toque
([Square Support 8665](https://squareup.com/help/us/en/article/8665-troubleshoot-item-details-and-variation-selection-on-square-point-of-sale)).
Una tarjeta que abre un formulario deja de ser sugerencia.

**El daño real no es la regla: es que nadie lo dice.** Y el alcance no es un producto —
en ese menú **8 productos activos** tienen exactamente 1 grupo obligatorio + 2 opcionales:
Agua Mineral, Hamburguesa Doble, Hamburguesa de Pollo, Pastel de Chocolate, Pay de Limón,
Tacos de Pollo, Tarta de Queso, Té Helado. Es justo lo que más se vende en un upsell.

## 2. Los dos defectos

### A — el dashboard solo conoce 1 de los 4 filtros

`UpsellResolver` descarta por **cuatro** motivos (`UpsellResolver.kt:50-77`):

1. `upsellEnabled != true` — veto del dueño en la ficha del producto
2. `isOutOfStock` — sin existencias
3. `soldByWeight` — abriría la captura de peso
4. `hasRequiredModifierGroup` — abriría el panel de modificadores

`Upsell.tsx:285` filtra por **uno**: `products.filter(p => p.upsellEnabled)`. Y `:279` pide
la lista con `includeModifiers: false`, así que **ni siquiera tiene el dato** de los otros.
El server tampoco valida al crear la regla.

Resultado: el dashboard deja crear una regla que el POS descartará siempre, en silencio. El
único aviso que existe (`:458`, badge "No sugerible") cubre sólo el veto.

### B — no hay salida para los productos con obligatorios

Hoy la única manera de sugerirlos es quitarle lo obligatorio al grupo en el catálogo, lo que
cambia cómo se vende el producto en TODA la app para arreglar una tarjeta. Es el remedio
peor que la enfermedad.

## 3. Decisión: la elección vive en la REGLA, no en el catálogo

Tres caminos evaluados; el founder eligió el tercero por su costo de configuración:

| | Cómo | Trabajo del dueño | Riesgo |
|---|---|---|---|
| B1 | Campo `isDefault` en `Modifier` | Alto: producto por producto, y cada alta nueva | Se olvida |
| B2 | El resolver elige solo (primera opción) | Cero | 🔴 Mete "Grande" cuando querían "Chico": precio que nadie decidió |
| **B3 ✅** | **Al crear la regla se eligen las opciones obligatorias, ahí mismo** | **Un paso, dentro del flujo donde ya está** | **Ninguno: la tarjeta dice qué es y cuánto** |

B3 además **no toca el catálogo ni el modelo `Modifier`** (que hoy no tiene concepto de
default — verificado en `schema.prisma:3462-3485`). La elección es de ESA sugerencia, no del
producto: el mismo Agua Mineral puede sugerirse "Chica" en una regla y "Grande" en otra.

## 4. Diseño

### 4.1 Server

**Schema — un campo en `UpsellRule`:**

```prisma
/// Opciones obligatorias ya resueltas para esta sugerencia (B3).
/// [{ groupId, modifierId }]. Vacío/NULL = el producto no pide nada.
/// Vive en la REGLA y no en el producto a propósito: la misma bebida puede
/// sugerirse chica en una regla y grande en otra, sin tocar el catálogo.
suggestedModifiers Json?
```

Migración: aditiva, sin backfill (las reglas existentes quedan en NULL = comportamiento de hoy).
Mismo commit: `npm run schema:map` + `MODEL_TO_DOMAIN`.

**Validación al crear/editar la regla** (el server es el juez, no la UI):

- Si el producto tiene grupos obligatorios → `suggestedModifiers` DEBE cubrir **todos**, con
  un modificador activo y perteneciente a ese grupo. Si no: 400 con el motivo.
- Si el producto es `soldByWeight` o `upsellEnabled=false` → 400. Nunca se guarda una regla
  que el POS va a ignorar. (`isOutOfStock` NO se valida: es transitorio y cambia solo.)

**Respuesta de `/mobile/venues/:id/upsell-rules`:** cada regla incluye `suggestedModifiers`
resueltos (`{ groupId, modifierId, name, price }`) para que el POS pinte precio final sin
recalcular nada.

### 4.2 Dashboard — el selector deja de mentir

- `getProducts(..., includeModifiers: true)` — se necesita el dato. El include ya devuelve
  `modifierGroups.group.required` (`product.dashboard.service.ts:450-460`).
- El selector **muestra todo el catálogo**, no una lista recortada. Lo no sugerible sale
  deshabilitado **con el motivo escrito**: "se vende por peso", "vetado en su ficha",
  "sin existencias". Ver y entender por qué vale más que no ver.
- Los que piden opciones obligatorias **sí se pueden elegir**: al seleccionarlos aparecen
  los grupos obligatorios con sus opciones, en la misma pantalla. Es el paso único de B3.
- La tarjeta de vista previa muestra el nombre resuelto y el precio final.
- Las reglas ya existentes cuyo producto se volvió no sugerible siguen mostrando el badge
  actual, ahora con el motivo real.

### 4.3 POS (Android e iOS, mismo trabajo)

- `UpsellRule` gana `suggestedModifiers`.
- `UpsellResolver`: el filtro `hasRequiredModifierGroup` pasa a **"tiene obligatorios Y la
  regla no los resolvió"**. Con la regla resuelta, la tarjeta se muestra.
- Al aceptar, la línea entra al carrito **con esos modificadores aplicados** — el mismo
  camino que agregar el producto con modificadores a mano, no uno nuevo.
- El precio de la tarjeta = producto + modificadores. Ya resuelto por el server.
- Los otros tres filtros no se tocan.

## 5. Qué NO cambia (y por qué)

- **La regla de Square se mantiene.** Un producto con obligatorios SIN resolver sigue sin
  poder sugerirse. Lo que cambia es que ahora existe una forma de resolverlos.
- **El veto del dueño gana siempre** (`upsellEnabled`), incluso con modificadores resueltos.
- **`soldByWeight` sigue fuera**: no hay "peso por defecto" razonable.
- **El holdout del 10%** no se toca: es el grupo de control que hace medible el aumento.
- **`isTablePaying`** sigue bloqueado: es el candado de mesas, ajeno a esto.

## 6. Verificación

Server (TDD, toca dinero indirectamente — el precio de la tarjeta):
validación rechaza regla sin cubrir todos los obligatorios · rechaza modificador de otro grupo ·
rechaza modificador inactivo · acepta producto sin obligatorios con `suggestedModifiers` vacío ·
la respuesta trae nombre y precio de cada modificador.

Dashboard: el selector lista TODO el catálogo · lo no sugerible sale deshabilitado con motivo ·
elegir un producto con obligatorios revela sus grupos · no deja guardar sin resolverlos.

POS (ambas): resolver muestra la tarjeta con obligatorios resueltos · la sigue ocultando sin
resolver · aceptar mete la línea con los modificadores · el precio coincide con el cobrado.

Hardware: en el D3, con las reglas reales del venue, la tarjeta del Agua Mineral aparece y al
aceptarla el total sube exactamente lo que decía.

## 7. Fuera de este spec

**C — sugerir MODIFICADORES** ("¿le agregamos queso extra?", el patrón del Kiosk de Square).
Aprobado por el founder pero **para después**: es un tipo de sugerencia nuevo, toca el carrito
de las dos apps y necesita su propio spec. 🔴 Antes de planearlo hay que verificar si el POS
ya sabe agregarle un modificador a una línea que YA está en el carrito; si no, C crece
bastante.

**Aviso proactivo en el dashboard** de reglas que dejaron de ser sugeribles porque el producto
cambió después (se le puso un obligatorio, se vetó). El badge de §4.2 lo cubre al abrir la
pantalla; un aviso activo es otra cosa.

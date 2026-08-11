# Caja externa — vales de área cobrados en un POS ajeno

**Fecha:** 2026-08-10
**Versión:** v1
**Estado:** spec propuesto — NO implementado. Extiende (no reemplaza) el spec canónico de vales por
área v7.
**Spec base:** `2026-07-28-vales-por-area-y-bascula-design.md` (v7)
**Handoff previo:** `2026-07-29-AUDITORIA-handoff-vales-por-area.md`
**Repos involucrados:** `avoqado-server`, `avoqado-web-dashboard`, `avoqado-android`, `avoqado-ios`
**Modo de alcance:** `HOLD_SCOPE` — habilitar el cobro en una caja ajena sin construir una
plataforma de conectores ni tocar el flujo nativo.

> Este documento define **una segunda ruta de cobro** para los vales por área. Todo lo que no
> aparezca aquí sigue rigiéndose por el spec v7, que continúa siendo la fuente de verdad del flujo
> nativo.

---

## 1. Enmiendas explícitas al spec v7

El spec v7 asume que **Avoqado cobra**. Este documento introduce una ruta donde **otro POS cobra**,
y eso contradice varias reglas de v7. Cada contradicción se declara aquí, con su alcance exacto.
Ninguna se relaja de forma global: todas quedan acotadas a `settlementRoute = EXTERNAL`.

| # | Regla v7 | Enmienda | Alcance |
|---|---|---|---|
| E1 | §3.2 "El MVP de vales es **online**. Sin red no se crean vales." | Un área con ruta externa **sí** puede emitir sin red, con códigos pre-reservados (§10). | Solo `EXTERNAL`. La ruta nativa sigue siendo estrictamente online. |
| E2 | §8.2 inv. 7 y 8: los vales se marcan pagados cuando la `Order` llega a `PAID`; no se entrega sin pago confirmado. | En la ruta externa **no existe `Order` ni `Payment`**. La autorización de entrega se deriva de la confirmación de cobro externo (§13). | Solo `EXTERNAL`. |
| E3 | §8.2 inv. 15: "Inventario se descuenta una vez, al completar la venta; la emisión sólo puede reservar." | En la ruta externa el consumo de inventario se ancla en la **emisión**, no en un cobro que Avoqado no puede observar (§11). | Solo `EXTERNAL`. |
| E4 | §8.2 inv. 18: "Una venta con vales jamás usa la cola offline… ni muestra pago exitoso sin confirmación del servidor." | Se conserva **íntegra y reforzada**: la ruta externa **nunca** muestra "pagado". Muestra "cobro asumido" o "cobro confirmado", que son cosas distintas (§12). | Ambas rutas. |
| E5 | §7: `AreaTicketFulfillment.orderId` es obligatorio. | Pasa a nullable **con un CHECK que lo exige cuando la ruta es `AVOQADO`** (§14.6). La invariante nativa no se debilita: se vuelve explícita en la base de datos, donde antes solo la sostenía el tipo de columna. | Ambas rutas, sin pérdida de garantía. |
| E6 | §8.1: máquina de estados lineal `ISSUED → CLAIMED → PAID → DELIVERED`. | En la ruta externa `AreaTicket.status` solo toma `ISSUED \| CANCELLED \| EXPIRED`; impresión, envío, cobro, entrega y conciliación son **ejes independientes** (§7). | Solo `EXTERNAL`. El enum no cambia. |
| E7 | §10.1: "El código por defecto tiene 10 dígitos… no codifica producto, peso ni precio." | Sigue siendo cierto **para el código de vale de Avoqado**. Los códigos **externos por línea** son otra cosa: pertenecen al catálogo del POS ajeno y pueden llevar peso o importe embebido (§9). | Solo `EXTERNAL`, y solo para los códigos externos. |
| E8 | §11.5: código de peso variable declarado como capacidad futura `VARIABLE_WEIGHT_BARCODE`. | Deja de ser futura: la ruta externa la necesita para productos por peso, y hace falta un **encoder** además del decoder que ya existe (§9.3). | Solo `EXTERNAL`. |
| E9 | §5.5: "La `Order` y el recibo final son la autoridad del importe efectivamente cobrado." | En la ruta externa **no hay autoridad monetaria dentro de Avoqado**. El total del vale es un **importe de referencia** y se etiqueta así en pantalla, en papel y en reportes (§12). | Solo `EXTERNAL`. |

Todo lo demás de v7 —snapshot inmutable del vale, idempotencia en cada mutación, área derivada de la
terminal autenticada, aislamiento por venue, entrega idempotente, design system— aplica sin cambios.

---

## 2. Problema

**La Galeterie** instalará Avoqado en sus áreas productivas (cafetería, panadería, cremería) pero
conservará **MyBusiness** en su caja principal, al menos por ahora. No hay integración entre ambos
sistemas y no la habrá en el corto plazo.

El flujo que necesitan:

```text
1. El empleado captura en Avoqado productos de cafetería, panadería o cremería.
2. Avoqado imprime UN SOLO vale físico.
3. El vale lleva un código de barras EXTERNO por cada producto.
4. El cliente lleva el vale a la caja principal.
5. El cajero escanea cada código con la pistola de MyBusiness.
6. MyBusiness agrega esos productos a UNA SOLA venta.
7. MyBusiness cobra el total.
8. Avoqado registra o asume — configurablemente — que el cobro externo ocurrió.
9. El producto se entrega por papel, confirmación manual o el flujo de entregas configurado.
```

Ejemplo acordado: 1 americano + 1 capuchino + 1 baguette → **un papel con tres códigos de barras**.
El cajero escanea los tres y MyBusiness forma una sola venta. No son tres papeles.

### 2.1 Evidencia del POS externo

De las fotos y el video que mandó el cliente:

| Control MyBusiness | Código impreso | Lo que cargó al escanear |
|---|---|---|
| `1636226` | `*VPZ1636226*` | NATA CHOYS 250 ML — $49.00 |
| `1636227` | `*VPZ1636227*` | BOLILLO — $3.50 |

**`VPZ1636226` no es un SKU universal y no contiene al producto.** Es una llave interna que
MyBusiness resuelve contra su propia base de datos. Avoqado **no puede inventar** códigos `VPZ…` y
esperar que el otro sistema los reconozca.

De ahí la única alternativa viable, ya aprobada: **usar los códigos con los que los artículos ya
están dados de alta en el POS externo**. Eso obliga a mantener mapeos configurables entre el
catálogo de Avoqado y el catálogo ajeno.

### 2.2 Por qué no se llama "integración MyBusiness"

MyBusiness es el primer caso real, no el diseño. La capacidad se llama **caja externa** y sirve para
cualquier negocio donde Avoqado opera un área productiva y otro POS cobra en la caja principal.
Ningún nombre de sistema externo aparece en el modelo de dominio, en un enum ni en una condición de
código: el sistema externo es una **fila de configuración** con su etiqueta.

Prohibido, aquí igual que en el resto de la plataforma:

```typescript
if (profile.system === 'MYBUSINESS') { … }   // ❌ nunca
```

---

## 3. Objetivos y no objetivos

### 3.1 Objetivos

- Permitir que un área emita un vale cuyos productos se cobran en un POS ajeno.
- Imprimir **un solo papel** con un código externo por renglón, reconocible por la pistola del POS
  ajeno.
- Mantener el flujo nativo intacto para las áreas y venues que cobran con Avoqado.
- No fabricar dinero: ni `Order`, ni `Payment`, ni ingreso, ni CFDI por un cobro que ocurrió afuera.
- Permitir emisión sin internet, con identidad rastreable y sin reutilizar códigos.
- Registrar o asumir el cobro externo de forma **configurable y distinguible**.
- Mantener la trazabilidad de qué área conserva y entrega cada producto.
- Dejar una frontera arquitectónica para que en el futuro exista un conector real, sin rediseñar.

### 3.2 No objetivos

- **Conector con MyBusiness**: ni API, ni callbacks, ni lectura de su base de datos. Nada.
- Plataforma genérica de conectores, registry de adaptadores o SDK de terceros.
- Importar las ventas del POS externo a Avoqado para reportes o contabilidad.
- Emitir CFDI por una venta cobrada afuera.
- Conciliación automática contra el corte de la caja externa.
- Cambiar el flujo nativo de vales, órdenes, pagos, propinas o reembolsos.
- Entrega parcial de renglones de un mismo vale (sigue fuera de alcance, igual que en v7).
- Códigos **externos** en 2D (DataMatrix, GS1-128 con AIs) en v1 — el modelo los contempla, la
  implementación no. Esto no afecta al código **interno** de Avoqado, que sí se imprime en QR (§16.3).

### 3.3 Cómo se mide el éxito

| Resultado | Umbral de salida del piloto |
|---|---|
| Lectura física | ≥ 49 de 50 escaneos correctos por combinación impresora/pistola/formato, contra la pistola **real** de la caja externa |
| Consolidación | 30 vales consecutivos de 3+ renglones cargan **todos** sus renglones en una sola venta del POS externo |
| Importe | El total que cobra la caja externa coincide al centavo con el importe de referencia en ≥ 95% de los vales; toda diferencia queda registrada |
| Identidad | 0 códigos de vale duplicados o reutilizados, incluyendo ≥ 200 emisiones offline |
| Inventario | El consumo se aplica exactamente una vez por vale, con reintentos y reinicios de app forzados |
| Aislamiento | Suite dorada del POS nativo sin cambios con la ruta externa apagada |
| Operación | Ningún vale queda sin una acción visible de recuperación para el operador |

---

## 4. Glosario

- **Ruta de cobro (`settlementRoute`)** — dónde se cobra lo que emite un área: `AVOQADO` (flujo v7,
  default) o `EXTERNAL` (caja externa).
- **Perfil de caja externa (`ExternalCashierProfile`)** — la descripción del sistema ajeno: cómo son
  sus códigos, qué simbología usa, qué formatos de peso acepta, y el mapeo de cada producto de
  Avoqado a su código en ese sistema.
- **Versión publicada** — snapshot inmutable de un perfil. Es lo que descargan las terminales y lo
  que un vale conserva para siempre.
- **Código externo de línea** — el código de barras que Avoqado imprime por renglón para que lo lea
  la pistola del POS ajeno. Pertenece al catálogo ajeno.
- **Código de vale** — el código opaco de Avoqado (namespace 9, 10 dígitos + verificador). No cambia.
- **Importe de referencia** — lo que Avoqado calculó para el vale. **No** es lo que se cobró.
- **Cobro asumido** — Avoqado dio por ocurrido el cobro sin verlo (modo automático al imprimir).
- **Cobro confirmado** — una persona con permiso confirmó que la caja externa cobró.
- **Incidencia** — fila accionable de una cola de trabajo: cobro sin confirmar, inventario negativo,
  código impreso que no coincide con el recalculado, discrepancia de importe.

---

## 5. Modelo conceptual: dos rutas, un solo producto

```text
                        ┌───────────────────────────┐
                        │   FulfillmentArea         │
                        │   settlementRoute         │
                        └───────────┬───────────────┘
              AVOQADO (default)     │      EXTERNAL
        ┌───────────────────────────┴────────────────────────────┐
        ▼                                                        ▼
┌──────────────────┐                              ┌──────────────────────────┐
│ vale de área     │                              │ vale de área             │
│ snapshot v7      │                              │ snapshot v7              │
└────────┬─────────┘                              │ + códigos externos       │
         │ claim                                  │ + versión de perfil      │
         ▼                                        └────────────┬─────────────┘
┌──────────────────┐                                           │ papel
│ CheckoutSession  │                                           ▼
│ caja Avoqado     │                              ┌──────────────────────────┐
└────────┬─────────┘                              │ CAJA EXTERNA (otro POS)  │
         ▼                                        │ escanea, cobra, imprime  │
┌──────────────────┐                              └────────────┬─────────────┘
│ Order + Payment  │  ← autoridad monetaria                    │ (fuera de Avoqado)
└────────┬─────────┘                                           ▼
         │                                        ┌──────────────────────────┐
         │                                        │ confirmación o supuesto  │
         │                                        │ AreaTicketExternal-      │
         │                                        │ Settlement               │
         └──────────────┬─────────────────────────┴────────────┬─────────────┘
                        ▼                                      ▼
              ┌────────────────────────────────────────────────────┐
              │  AreaTicketFulfillment — MISMA entrega idempotente │
              └────────────────────────────────────────────────────┘
```

Lo que **comparten** las dos rutas: la emisión, el snapshot monetario del vale, la impresión y sus
intentos, la entrega y su idempotencia, el design system, la auditoría y los permisos.

Lo que **no comparten**: la sesión de caja, la `Order`, el `Payment`, el momento del consumo de
inventario y la autoridad del importe.

**Un venue puede tener las dos rutas a la vez.** La cafetería puede cobrar en la caja externa
mientras la barra cobra con Avoqado. La ruta se decide **por área**, nunca por venue ni por terminal.

---

## 6. Flujo funcional

### 6.1 Emisión en un área con ruta externa

1. El operador selecciona productos; si alguno se vende por peso, captura peso estable o manual.
2. El servidor —o la terminal, si está offline— resuelve catálogo, precio e impuestos con las mismas
   reglas de v7. **La matemática del dinero no cambia.**
3. Se resuelve el **código externo** de cada renglón contra la versión publicada del perfil. Si
   falta un mapeo, **la emisión se bloquea antes de imprimir** (§18.5).
4. Se crea el vale con `settlementRoute = EXTERNAL`, se congela el snapshot y se estampa
   `externalProfileVersionId`.
5. El cobro externo nace **siempre** en `PENDING`: al emitir todavía no se imprimió nada.
6. Se imprime **un papel** con un código externo por renglón (§16). Si el área usa
   `ASSUME_ON_PRINT`, el registro de impresión exitosa es lo que mueve el cobro a `ASSUMED`; si usa
   `MANUAL`, se queda en `PENDING` hasta que una persona lo confirme.
7. El área conserva el producto si su `fulfillmentMode` es `HOLD_UNTIL_PAID`.

### 6.2 Cobro en la caja externa

Ocurre **fuera de Avoqado**. El cajero escanea los códigos con su pistola, MyBusiness arma la venta
y cobra. Avoqado no participa, no observa y no registra ese pago.

### 6.3 Confirmación

**Modo manual** (`MANUAL`):

1. El operador del área abre "Cobros por confirmar".
2. Localiza el vale por hora, importe, código o escaneando el vale.
3. Confirma. Puede capturar el **importe realmente cobrado**.
4. Si el importe difiere del de referencia, la diferencia se muestra y se guarda; el vale se marca
   `DISCREPANCY` y entra a la cola de incidencias, pero **la entrega no se bloquea** — el producto
   ya está pagado en la otra caja y retenerlo castiga al cliente por un error de captura.

**Modo automático** (`ASSUME_ON_PRINT`):

1. Al registrar la impresión exitosa, el vale queda `ASSUMED`.
2. La UI, los reportes y la auditoría dicen **"cobro asumido"**, nunca "pagado".
3. Un vale `ASSUMED` que nunca se entrega no dispara incidencia por cobro; sí por entrega, si el área
   registra entregas.

### 6.4 Entrega

Idéntica a v7 en mecánica y superficie: lista de pendientes o escaneo, mismo evento idempotente,
mismo mensaje "Entregado a las {hora} por {persona}". Lo único que cambia es **el predicado de
elegibilidad** (§13).

Si el área entrega solo mirando el papel y nunca registra digitalmente (`deliveryTrackingMode =
UNTRACKED`), **no se genera cola de pendientes**. No se rompe inventario, ni ventas, ni contabilidad:
lo único que se pierde es la trazabilidad de entrega, y eso es una elección explícita del negocio,
no una falla.

---

## 7. Los seis ejes de estado

El error que la auditoría marcó y que este diseño evita: encadenar hechos independientes en una sola
máquina lineal. `EMITIDO → IMPRESO → EXPORTADO → CONFIRMADO → ENTREGADO` es falso — un vale puede
estar impreso y no cobrado, cobrado y no entregado, entregado con una discrepancia abierta.

| Eje | Dónde vive | Valores | Nota |
|---|---|---|---|
| **1. Vale** | `AreaTicket.status` | `ISSUED` · `CANCELLED` · `EXPIRED` | En ruta externa **nunca** toma `CLAIMED`, `PAID` ni `DELIVERED` |
| **2. Impresión** | `AreaTicket.printStatus` + `AreaTicketPrintAttempt` | `NOT_PRINTED` · `PRINTED` · `PRINT_FAILED` (+ intentos `ORIGINAL`/`REPRINT`) | Ya existe en v7, sin cambios |
| **3. Envío a caja** | `AreaTicketExternalSettlement.handoffState` | `PENDING` · `HANDED_OFF` · `RETURNED` | Se marca cuando el papel sale del área. Distinto de "se imprimió" |
| **4. Cobro externo** | `AreaTicketExternalSettlement.status` | `PENDING` · `ASSUMED` · `CONFIRMED` · `DISCREPANCY` · `NOT_CHARGED` | El eje monetario |
| **5. Entrega** | `AreaTicketFulfillment` (existencia) | entregado / no entregado / no rastreado | Misma entidad que la ruta nativa |
| **6. Conciliación** | `AreaTicketExternalIncident` | cola independiente, no un estado | Una bandera derivada, no un paso del ciclo de vida |

Reglas entre ejes:

1. No se puede marcar `HANDED_OFF` un vale cuya impresión no fue exitosa.
2. No se puede confirmar el cobro de un vale `CANCELLED` o `EXPIRED`.
3. Se puede entregar con el cobro en `DISCREPANCY`; **no** con el cobro en `PENDING` o `NOT_CHARGED`.
4. Cancelar un vale con cobro `CONFIRMED` está prohibido: eso es una devolución, y la devolución
   ocurre en la caja externa.
5. Una incidencia abierta **nunca** bloquea la operación del piso. Es trabajo de oficina.

**No se usa la palabra "exportado".** Entregar un papel no es exportar. `exportar` queda reservada
para cuando exista un conector real que transmita datos a otro sistema.

---

## 8. Configuración

### 8.1 Dos niveles, a propósito

Los mapeos de códigos son caros de mantener (cientos de filas) y pertenecen al **sistema externo**.
La política de confirmación, offline y entrega pertenece al **área**. Mezclarlos obligaría a duplicar
todo el catálogo cada vez que dos áreas quieren políticas distintas contra el mismo POS.

**Perfil de caja externa** — describe el sistema ajeno. Versionado e inmutable al publicar:

```text
ExternalCashierProfile
  name                  "Caja principal MyBusiness"
  externalSystemLabel   "MyBusiness"          // etiqueta libre, nunca un enum
  paperWidthMm          58 | 80
  symbologies           [CODE128, EAN13, CODE39, ITF14]
  internalCodeSymbology QR | CODE128          // cómo se imprime el código de Avoqado (§16.3)
  defaultWeightEncoding WEIGHT_EMBEDDED
  weightPrefix          "20".."29"
  pluDigits             5
  valueDigits           5
  checkDigitScheme      GS1_MOD10 | NONE
  reservedPrefixNote    (informativo, para el operador)
```

**Área** — política operativa:

```text
FulfillmentArea
  settlementRoute            AVOQADO | EXTERNAL          default AVOQADO
  externalCashierProfileId   (requerido si EXTERNAL)
  externalConfirmationMode   MANUAL | ASSUME_ON_PRINT    default MANUAL
  externalOfflineIssuePolicy ALLOW | BLOCK               default BLOCK
  externalDeliveryTracking   TRACKED | UNTRACKED         default TRACKED
```

### 8.2 Borrador → validación → publicación

```text
DRAFT ──validar──► (errores bloqueantes) ──corregir──► DRAFT
  │
  └──publicar──► ExternalCashierProfileVersion (INMUTABLE)
                        │
                        ├── las terminales la descargan y la cachean
                        └── cada vale guarda el id de la versión con la que se emitió
```

- Un perfil en `DRAFT` **no se puede asignar** a un área.
- Publicar crea una versión nueva; **nunca** modifica una publicada.
- Cambiar el perfil después no altera un vale ya emitido: el papel que tiene el cliente en la mano no
  se puede editar, así que el registro tampoco.
- Un área apunta a un **perfil**, y consume siempre su **última versión publicada**. Al publicar una
  versión nueva, las terminales la reciben en su siguiente sincronización.
- El snapshot de la versión incluye reglas **y** mapeos completos, serializados. Una terminal offline
  no necesita nada más para emitir.

### 8.3 Validaciones bloqueantes al publicar

Ninguna de estas es advertencia. Si falla, no se publica.

| # | Validación | Por qué |
|---|---|---|
| V1 | Todo producto activo y vendible en las áreas que usan el perfil tiene mapeo, o está marcado `EXCLUDED` con motivo | Un mapeo faltante descubierto en el piso deja al cliente esperando |
| V2 | Ningún código se repite entre dos targets distintos | Dos productos con el mismo código cobran mal en la caja externa |
| V3 | Longitud y charset compatibles con la simbología declarada | EAN-13 con 12 dígitos no imprime |
| V4 | Verificador correcto según `checkDigitScheme` | Un check malo lo rechaza la pistola |
| V5 | El código impreso **cabe** en `paperWidthMm` con el ancho de módulo mínimo legible | Un CODE128 de 20 caracteres en papel de 58 mm no se lee |
| V6 | Ningún código externo colisiona con el namespace de Avoqado: 10 dígitos numéricos que empiecen en **8** o **9** y con verificador GS1 mod-10 válido | Es exactamente la forma de un código de vale (9) o de entrega (8). Ver §9.4 |
| V7 | Todo producto `soldByWeight` tiene `weightEncoding ≠ NONE` | Un producto por peso con código fijo cobra la cantidad equivocada |
| V8 | Capacidad numérica suficiente: gramos y centavos caben en `valueDigits` | 5 dígitos = máx. 99.999 kg / $999.99. Un jamón de 120 kg o un renglón de $1,200 desbordan |
| V9 | El área que va a usar el perfil tiene al menos una impresora alcanzable configurada | Un vale externo que no se imprime no sirve para nada |

La pantalla de publicación muestra los errores **por producto y por regla**, con enlace directo al
renglón que hay que corregir. Nunca un "hay 14 errores".

### 8.4 Nada de SQL como operación normal

Todo lo que el comercio necesite tocar vive en `avoqado-web-dashboard`: crear el perfil, cargar los
mapeos (uno a uno y por CSV), validarlos, publicarlos, asignar el perfil al área y elegir la política.
Un feature cuyo único switch es un `UPDATE` en Postgres está incompleto.

---

## 9. Códigos

### 9.1 Dos clases de código en el mismo papel

| | Código externo de línea | Código de vale Avoqado |
|---|---|---|
| Para quién | la pistola del POS ajeno | la app de Avoqado |
| Quién lo define | el catálogo del sistema externo | Avoqado |
| Cuántos por vale | uno por renglón | uno |
| Contenido | identidad del producto, a veces peso o importe | opaco: nada |
| Simbología | la que declare el perfil | QR por defecto en ruta externa (§16.3) |

### 9.2 Modos de codificación por renglón

| Modo | Estructura | Quién calcula el importe |
|---|---|---|
| `WEIGHT_EMBEDDED` *(default)* | `PP + PLU(5) + gramos(5) + C` | El POS externo, desde su precio por kilo |
| `PRICE_EMBEDDED` | `PP + PLU(5) + centavos(5) + C` | Avoqado; el importe viaja dentro del código |
| `FIXED` | código fijo del catálogo externo | El POS externo, por su precio unitario |
| `FIXED_MANUAL_QTY` | código fijo + el cajero teclea cantidad | El POS externo. **Último recurso**: depende de que el cajero no se equivoque |

`WEIGHT_EMBEDDED` es el default recomendado: deja el precio en el sistema que va a cobrar, así que un
cambio de precio en MyBusiness no genera diferencias contra Avoqado.

`PRICE_EMBEDDED` invierte el riesgo: el importe lo fija Avoqado, y una desactualización del precio en
Avoqado se cobra literalmente. Se permite porque algunos POS solo aceptan eso, pero la UI lo advierte
al elegirlo.

### 9.3 Encoder — hay que construirlo

Hoy Android e iOS solo **decodifican**:

- `avoqado-android/app/src/main/java/com/avoqado/pos/pos/data/VariableWeightBarcode.kt`
- `avoqado-ios/avoqado-ios/AreaTickets/VariableWeightBarcode.swift`

Ambos leen `PP + PLU(5) + gramos(5) + verificador`. **No existe encoder.** Hace falta uno, con estas
propiedades:

1. **Función pura**, sin red ni estado, con tests de tabla en las tres implementaciones (server,
   Kotlin, Swift) sobre **los mismos vectores**.
2. Round-trip obligatorio: `decode(encode(x)) == x` para todo el rango válido.
3. **Desbordamiento = error, nunca truncamiento.** 100.001 kg en 5 dígitos de gramos no se recorta a
   00001: se rechaza y se bloquea la emisión de ese renglón.
4. Redondeo de gramos declarado y único: `round(kg × 1000)` con medio hacia arriba. El mismo en las
   tres implementaciones.
5. El **servidor es la autoridad**. Online calcula él. Offline calcula la terminal con el snapshot, y
   al sincronizar **el servidor recalcula y compara**: si difiere, abre incidencia y conserva el
   código que realmente se imprimió — el papel ya está en la calle y no se puede reescribir.

### 9.4 Colisiones con el namespace de Avoqado

Verificado en `avoqado-server/src/services/mobile/areaTicketV7.mobile.service.ts:251`: los códigos de
Avoqado son `generateOpaqueCode(9)` para vales y `generateOpaqueCode(8)` para entrega — 10 dígitos,
el primero fijo, verificador GS1 mod-10.

Un código externo con esa misma forma es ambiguo. Tres capas:

1. **Al publicar** (V6): se rechaza el mapeo. Es la defensa principal.
2. **Al resolver un escaneo**: el resolutor existente ya devuelve `AMBIGUOUS` cuando un código empata
   con producto y con vale (línea 1657). Se conserva tal cual.
3. **En el escáner del área**: si alguien escanea un **código externo de línea** en contexto
   `AREA_DELIVERY`, Avoqado hace un lookup contra los mapeos publicados **solo para explicar** —
   "Ese código es del catálogo de la caja externa. Escanea el código del vale, abajo del papel." —
   y nunca lo usa para resolver el flujo. Ignorar en silencio deja al operador tocando el mismo
   código tres veces sin entender.

---

## 10. Identidad offline: bloques de códigos pre-reservados

### 10.1 Por qué no sirve lo que hay

`generateOpaqueCode` es aleatorio y se valida contra la base de datos con hasta 5 reintentos, dentro
de la transacción de emisión. **Sin base de datos no hay código.** Una terminal offline que invente
uno puede chocar con otro que el servidor ya asignó.

Tampoco se resucita el esquema `9PPNNNNNNC` de la implementación v3: sus 90 particiones se agotan, se
rompen al reinstalar la app y obligan a reciclar códigos. Reciclar un código de vale es un doble
cobro esperando a ocurrir.

### 10.2 El diseño

```text
Servidor                                    Terminal
────────                                    ────────
pre-genera N códigos únicos    ──lease──►   guarda el bloque cifrado
(validados contra AreaTicket
 Y contra reservas vivas)                   emite offline:
                                              consume 1 código en una
                                              transacción local
                                              (marca consumedAt antes
                                               de imprimir)
                            ◄──sync──────    reporta los consumidos
marca CONSUMED, liga al vale
repone el bloque             ──lease──►
```

- Tamaño de bloque y umbral de reposición configurables por venue; default **200** y reposición al
  llegar a **50** restantes.
- **La reposición es oportunista**: cada vez que la terminal habla con el servidor por cualquier
  motivo, si el bloque bajó del umbral, se repone. No hay un job especial que pueda no correr.
- **Un código consumido jamás se reutiliza**, ni si el vale se cancela, ni si la impresión falla, ni
  si la app se reinstala. Un código quemado es basura barata; un código repetido es un doble cobro.
- **Bloque agotado sin red = emisión bloqueada**, con mensaje explícito: "Sin conexión y sin folios
  disponibles. Conéctate a internet para seguir emitiendo vales." Nunca se imprime un vale sin
  identidad rastreable.
- El bloque está ligado a `(venueId, terminalId)`. Al cambiar de venue se descarta; al desvincular la
  terminal, el servidor libera los no consumidos.

### 10.3 🔴 El generador online tiene que aprender de las reservas

Cambio obligatorio, y es el punto donde este diseño puede fallar en silencio: hoy la emisión online
solo comprueba unicidad contra `AreaTicket.code`. Con bloques pre-reservados existen códigos que
**todavía no son vales** pero ya están comprometidos con una terminal.

`generateOpaqueCode` y su bucle de colisión deben validar contra **las dos** tablas: `AreaTicket` y
`AreaTicketCodeReservation`. Sin eso, el servidor puede entregarle a la caja un código que una
terminal offline ya imprimió, y la colisión aparece días después, al sincronizar, sobre un papel que
el cliente ya usó.

---

## 11. Inventario en la ruta externa

### 11.1 El consumo se ancla en la emisión

En la ruta nativa el inventario se descuenta al pagar la orden. En la externa no hay orden, y esperar
a un cobro que Avoqado quizá nunca vea dejaría el stock inflado indefinidamente.

Físicamente, cuando el vale se imprime **el producto ya salió**: se rebanó, se empacó, se sirvió. Por
eso, en la ruta externa, **emitir = consumir**. Es la enmienda E3, y aplica solo aquí.

Se reutiliza el aparato de v7 sin inventarle uno nuevo: `AreaTicketInventoryReservation` se crea con
status `CONSUMED` y su `inventoryMovementId`, en la misma transacción que crea el vale.

"Emitir" significa cosas distintas según haya red, y conviene decirlo sin ambigüedad:

| | Cuándo se escribe el movimiento |
|---|---|
| **Online** | En la transacción de emisión, junto con el vale |
| **Offline** | La terminal registra el consumo **provisional local** al imprimir; el movimiento real se escribe en el servidor al sincronizar, con la misma llave (§11.2) |

En los dos casos el movimiento existe **una sola vez**, y en los dos el disparador conceptual es la
emisión — no un cobro que Avoqado no puede observar.

### 11.2 El ancla de idempotencia

Verificado: `InventoryMovement.reference` es `String?` **sin restricción de unicidad**
(`schema.prisma:1785`). No sirve como llave de idempotencia — una cadena libre no impide un segundo
movimiento idéntico.

El ancla real, y ya existe:

- `AreaTicketInventoryReservation @@unique([areaTicketLineId, inventoryKind, inventoryId])` — una sola
  fila posible por componente de una línea.
- `AreaTicketInventoryReservation.inventoryMovementId @unique` — un movimiento pertenece como máximo
  a una reserva.

Un reintento de sincronización encuentra la fila ya `CONSUMED` y no crea otro movimiento. Cubre por
igual producto base, producto por peso, receta, insumos y modificadores, porque reutiliza
`buildReservationSpecs`, que ya expande todo eso.

**La reversa necesita su propia ancla.** Cancelar un vale ya consumido tiene que escribir un
movimiento de signo contrario, y ese movimiento puede reintentarse igual que el primero. La reserva
pasa a `RELEASED` (devuelto a stock) o `WASTE` (merma), y guarda `reversalMovementId` con su propio
`@unique`. Sin esa segunda columna, un reintento de cancelación devuelve el producto a stock dos
veces — el mismo bug que la primera columna evita en el sentido de ida.

### 11.3 Offline no puede bloquear stock global

Sin red no hay forma de saber si otra terminal se llevó las existencias. La política, ya aprobada:

1. El vale **se emite e imprime** de todos modos si el área tiene `externalOfflineIssuePolicy = ALLOW`.
2. La terminal registra el consumo provisional local.
3. Al sincronizar, el movimiento se aplica **exactamente una vez**.
4. Si el stock ya se agotó, **se permite inventario negativo** y se abre una incidencia.
5. **Nunca** se invalida retroactivamente un producto ya preparado o entregado. El cliente ya se lo
   llevó; anular el registro no lo devuelve.
6. Un área que prefiera bloquear antes que permitir negativos usa `externalOfflineIssuePolicy = BLOCK`.
   Con esa política, sin red no se emite.

La incidencia de inventario muestra: producto, diferencia, terminal, empleado, hora, vale y la acción
sugerida (ajuste de inventario o conteo).

---

## 12. Autoridad monetaria y conciliación

### 12.1 Lo que Avoqado NO hace

- No crea `Order`.
- No crea `Payment`, ni con método "externo", ni con importe cero.
- No suma esos importes a ventas, ingresos, bancos, cortes de caja, propinas ni conciliación bancaria.
- No emite CFDI.
- No los envía al motor contable de doble partida.

Un `Payment` ficticio contaminaría reportes fiscales y bancarios con dinero que nunca entró a
Avoqado. El POS externo es la **única** autoridad de ese cobro.

### 12.2 Lo que sí hace

`AreaTicketExternalSettlement`, una fila por vale externo, creada al emitir:

| Campo | Qué es |
|---|---|
| `referenceAmount` | el total que calculó Avoqado. **Importe de referencia**, no cobro |
| `externalAmount` | lo que la caja externa cobró de verdad, si alguien lo capturó |
| `variance` | derivado: `externalAmount - referenceAmount`, visible cuando existe |
| `status` | `PENDING` · `ASSUMED` · `CONFIRMED` · `DISCREPANCY` · `NOT_CHARGED` |
| `handoffState` | `PENDING` · `HANDED_OFF` · `RETURNED` |
| `confirmationMode` | qué política estaba vigente: `MANUAL` o `ASSUME_ON_PRINT` |
| `externalReference` | folio/ticket de la caja externa, si el operador lo captura |
| `confirmedByStaffId`, `confirmedAt`, `terminalId`, `idempotencyKey` | quién, cuándo, desde dónde |

**Todo reporte que muestre estos importes los etiqueta como referencia y los presenta en una sección
separada de las ventas de Avoqado.** Nunca en el mismo total. Un operador que ve un solo número
grande asume que es su venta.

### 12.3 La cola de conciliación

`AreaTicketExternalIncident` — independiente del ciclo de vida del vale, con tipos:

| Tipo | Se abre cuando | Acciones |
|---|---|---|
| `UNCONFIRMED_CHARGE` | un vale `PENDING` supera el umbral del venue (default: cierre del día) | confirmar con importe · marcar no cobrado · cancelar con merma |
| `AMOUNT_VARIANCE` | `externalAmount ≠ referenceAmount` | aceptar la diferencia con nota · corregir el importe |
| `NEGATIVE_STOCK` | la sincronización dejó existencias negativas | ajustar inventario · programar conteo |
| `CODE_MISMATCH` | el código impreso offline ≠ el recalculado por el servidor | revisar el mapeo · republicar el perfil |
| `REPRINT_RISK` | un vale con más de una impresión sigue sin confirmar | verificar con la caja externa |

Las incidencias viven en el dashboard, con filtros por área, fecha y tipo. **Ninguna bloquea el
piso.**

---

## 13. Entrega

Misma entidad, misma idempotencia, mismo mensaje. Lo único que cambia es quién es elegible:

| Ruta | Predicado de elegibilidad |
|---|---|
| `AVOQADO` | `AreaTicket.status = PAID` **y** `Order.paymentStatus = PAID` **y** sin fulfillment — igual que v7 |
| `EXTERNAL` | `settlement.status ∈ {CONFIRMED, ASSUMED, DISCREPANCY}` **y** `AreaTicket.status = ISSUED` **y** área con `deliveryTracking = TRACKED` **y** sin fulfillment |

La pantalla "Pendientes de entrega" del área muestra la **unión** de ambos conjuntos, ordenada por
antigüedad, con una etiqueta discreta de ruta. El operador no debería tener que saber en qué ruta
está: el producto es el mismo y el cliente está esperando.

Un vale externo con cobro `PENDING` o `NOT_CHARGED` **no aparece** en pendientes de entrega: aparece
en "Cobros por confirmar", que es otra cosa y otra acción.

### 13.1 `AreaTicketFulfillment.orderId`

Verificado: hoy es `String` no nullable (`schema.prisma:13390`), y `fulfillAreaTicket` exige
`ticket.order.paymentStatus === 'PAID'` (línea 1815). Un vale externo no tiene `Order`.

La decisión —y la auditoría fue explícita en no relajar la regla globalmente— es **una sola tabla con
un discriminador y un CHECK**, no una tabla paralela:

```sql
ALTER TABLE "AreaTicketFulfillment"
  ADD CONSTRAINT "atf_order_required_for_avoqado_route"
  CHECK ("settlementRoute" <> 'AVOQADO' OR "orderId" IS NOT NULL);
```

La invariante nativa **no se debilita**: pasa de estar sostenida por el tipo de una columna a estar
sostenida por una restricción que dice exactamente lo que quiere decir. Y la ruta externa no puede
colarse a la nativa sin orden.

Dos tablas habrían duplicado la lógica de entrega, la cola de pendientes, el índice
`(fulfillmentAreaId, deliveredAt)` y la pantalla del área — con dos oportunidades de que una se
arregle y la otra no.

---

## 14. Modelo de dominio

Nombres conceptuales; Prisma puede ajustarlos a las convenciones del schema sin cambiar las
invariantes.

### 14.1 Cambios a modelos existentes (todos aditivos)

```text
FulfillmentArea
+ settlementRoute            AreaSettlementRoute  @default(AVOQADO)
+ externalCashierProfileId   String?
+ externalConfirmationMode   ExternalConfirmationMode  @default(MANUAL)
+ externalOfflineIssuePolicy ExternalOfflinePolicy     @default(BLOCK)
+ externalDeliveryTracking   ExternalDeliveryTracking  @default(TRACKED)

AreaTicket
+ settlementRoute            AreaSettlementRoute  @default(AVOQADO)
+ externalProfileVersionId   String?
+ issuedOffline              Boolean  @default(false)
+ clientIssuedAt             DateTime?              // reloj de la terminal, para auditoría
  // el vínculo con el folio pre-reservado vive SOLO en AreaTicketCodeReservation.areaTicketId
  // (§14.5). Dos punteros cruzados se desincronizan; uno no puede.

AreaTicketInventoryReservation
+ reversalMovementId         String?  @unique       // ancla de la reversa — §11.2

AreaTicketLine
+ externalCode               String?
+ externalCodeSymbology      String?
+ externalWeightEncoding     ExternalWeightEncoding?
+ externalCodeSource         SERVER | CLIENT_OFFLINE

AreaTicketFulfillment
~ orderId                    String?   (era String)  + CHECK de §13.1
+ settlementRoute            AreaSettlementRoute
```

### 14.2 Perfil y mapeos

```text
ExternalCashierProfile
  id, venueId, name, externalSystemLabel
  status                DRAFT | PUBLISHED | ARCHIVED
  paperWidthMm, symbologies[], internalCodeSymbology
  defaultWeightEncoding, weightPrefix, pluDigits, valueDigits, checkDigitScheme
  currentVersionId?
  createdAt, updatedAt
  @@unique([venueId, name])

ExternalCashierProfileVersion            // INMUTABLE
  id, profileId, version
  snapshot              Json             // reglas + TODOS los mapeos, listo para offline
  snapshotHash          String           // SHA-256 del JSON canónico
  publishedAt, publishedByStaffId
  @@unique([profileId, version])

ExternalCashierCodeMapping               // el borrador editable
  id, profileId
  targetKind            PRODUCT | MODIFIER
  targetId
  code, symbology
  weightEncoding        NONE | WEIGHT_EMBEDDED | PRICE_EMBEDDED | FIXED | FIXED_MANUAL_QTY
  plu?, prefix?
  excluded              Boolean @default(false)
  excludedReason?
  @@unique([profileId, targetKind, targetId])
  @@unique([profileId, code])            // V2 a nivel de base de datos, no solo de validación
```

`targetKind` cubre `PRODUCT` y `MODIFIER` porque **en Avoqado no existe `ProductVariant`**: lo que el
negocio llama "variante" son modificadores (`Modifier`, `schema.prisma:3269`), y `Modifier` no tiene
`sku` ni `gtin`, así que su código externo no puede colgarse de un campo existente.

### 14.3 Cobro externo

```text
AreaTicketExternalSettlement
  id, venueId
  areaTicketId          @unique
  status                PENDING | ASSUMED | CONFIRMED | DISCREPANCY | NOT_CHARGED
  handoffState          PENDING | HANDED_OFF | RETURNED
  confirmationMode      MANUAL | ASSUME_ON_PRINT
  referenceAmount       Decimal @db.Decimal(12,2)
  externalAmount        Decimal? @db.Decimal(12,2)
  externalReference     String?
  idempotencyKey        String  @db.VarChar(64)
  confirmedByStaffId?, confirmedAt?, terminalId?, notes?
  @@unique([areaTicketId, idempotencyKey])
  @@index([venueId, status, createdAt])
```

### 14.4 Incidencias

```text
AreaTicketExternalIncident
  id, venueId, areaTicketId?
  kind          UNCONFIRMED_CHARGE | AMOUNT_VARIANCE | NEGATIVE_STOCK | CODE_MISMATCH | REPRINT_RISK
  status        OPEN | RESOLVED | DISMISSED
  detail        Json
  openedAt, resolvedAt?, resolvedByStaffId?, resolution?
  @@index([venueId, status, kind, openedAt])
  @@unique([areaTicketId, kind])         // una incidencia viva por tipo y por vale
```

### 14.5 Folios pre-reservados

```text
AreaTicketCodeReservation
  id, venueId, terminalId
  code                  String
  status                AVAILABLE | LEASED | CONSUMED | VOIDED
  leasedAt?, consumedAt?, areaTicketId? @unique
  @@unique([venueId, code])              // y el generador online la consulta — §10.3
  @@index([venueId, terminalId, status])
```

### 14.6 Restricciones que van en la migración, no solo en el código

```sql
-- 1. La ruta nativa sigue exigiendo orden en la entrega
CHECK ("settlementRoute" <> 'AVOQADO' OR "orderId" IS NOT NULL)

-- 2. Un vale externo nunca entra al circuito de caja Avoqado
CHECK ("settlementRoute" <> 'EXTERNAL'
       OR ("checkoutSessionId" IS NULL AND "orderId" IS NULL
           AND status IN ('ISSUED','CANCELLED','EXPIRED')))

-- 3. Un vale externo siempre trae la versión de perfil con la que se emitió
CHECK ("settlementRoute" <> 'EXTERNAL' OR "externalProfileVersionId" IS NOT NULL)
```

Son las tres invariantes que, si solo viven en TypeScript, se rompen el día que alguien escriba un
script de datos.

### 14.7 Regla obligatoria del repo

Cualquier edición a `avoqado-server/prisma/schema.prisma` termina con `npm run schema:map`, y
`docs/SCHEMA_MAP.md` se commitea junto con el cambio. Los modelos nuevos primero necesitan su entrada
en `scripts/generate-schema-map.ts` → `MODEL_TO_DOMAIN`.

---

## 15. Contrato de API

Mismo envelope `{ success, data, error }` de v7, mismos decimales en pesos 1:1 como string, mismas
fechas ISO-8601 UTC, idempotency key en toda mutación.

### 15.1 Configuración (dashboard)

```text
GET    /dashboard/venues/:venueId/external-cashier-profiles
POST   /dashboard/venues/:venueId/external-cashier-profiles
PATCH  /dashboard/venues/:venueId/external-cashier-profiles/:id
POST   /dashboard/venues/:venueId/external-cashier-profiles/:id/mappings        // alta y CSV
DELETE /dashboard/venues/:venueId/external-cashier-profiles/:id/mappings/:mapId
POST   /dashboard/venues/:venueId/external-cashier-profiles/:id/validate        // §8.3, sin publicar
POST   /dashboard/venues/:venueId/external-cashier-profiles/:id/publish
GET    /dashboard/venues/:venueId/external-cashier-profiles/:id/versions
```

`validate` devuelve el listado completo de errores por regla y por producto. `publish` vuelve a
validar en servidor y rechaza si algo falla — la validación del cliente es cortesía, no autoridad.

### 15.2 Móvil

```text
GET  /mobile/venues/:venueId/area-ticket-settings
     → + settlementRoute del área, políticas externas, versión de perfil vigente y su hash

GET  /mobile/venues/:venueId/external-cashier-profile-version
     → snapshot completo para operar offline; responde 304 si el hash del cliente coincide

POST /mobile/venues/:venueId/area-ticket-code-blocks/lease
     → { size } → códigos pre-reservados para esta terminal

POST /mobile/venues/:venueId/area-tickets
     → mismo contrato de emisión. En ruta externa acepta:
       reservedCode?, issuedOffline, clientIssuedAt, y por línea el externalCode que se imprimió

POST /mobile/venues/:venueId/area-tickets/:ticketId/external-settlement/handoff
POST /mobile/venues/:venueId/area-tickets/:ticketId/external-settlement/confirm
     → { idempotencyKey, externalAmount?, externalReference?, notes? }
POST /mobile/venues/:venueId/area-tickets/:ticketId/external-settlement/not-charged
     → { idempotencyKey, reason }

GET  /mobile/venues/:venueId/area-tickets/pending-confirmation
     → cola del área, cursor estable (issuedAt, id)
```

La entrega (`/area-ticket-fulfillment/*`) **no cambia de forma**: mismos endpoints, mismo cuerpo,
mismo evento idempotente. Solo cambia a quién considera elegible.

### 15.3 Errores de dominio nuevos

| `error.code` | HTTP | Reintento | Interfaz |
|---|---:|---|---|
| `EXTERNAL_CASHIER_PROFILE_NOT_PUBLISHED` | 409 | No | "El perfil de caja externa aún no está publicado" + a quién pedírselo |
| `EXTERNAL_CODE_MAPPING_MISSING` | 409 | No | Lista exacta: producto, modificador y dónde configurarlo |
| `EXTERNAL_CODE_CAPACITY_EXCEEDED` | 422 | No | "El peso/importe no cabe en el código de este sistema" |
| `AREA_TICKET_CODE_BLOCK_EXHAUSTED` | 409 | Sí (con red) | "Sin folios disponibles. Conéctate para seguir emitiendo" |
| `EXTERNAL_SETTLEMENT_ALREADY_CONFIRMED` | 200 | — | `success: true`, `alreadyConfirmed: true`, actor y hora |
| `EXTERNAL_SETTLEMENT_NOT_CONFIRMED` | 409 | No | "Confirma el cobro antes de entregar" |
| `EXTERNAL_ROUTE_DISABLED` | 403 | No | Ocultar la ruta externa; el POS normal sigue |

---

## 16. Impresión

### 16.1 Un papel, N códigos

```text
        LA GALETERIE — CAFETERÍA
        10/08/2026  14:32

        ─────────────────────────────
        Americano                 $45.00
        ▌▌║▌║▌▌║║▌║▌▌║▌║▌▌║
        7501234500011

        Capuchino                 $55.00
        ▌▌║▌║▌▌║║▌║▌▌║▌║▌▌║
        7501234500028

        Baguette jamón            $68.00
        ▌▌║▌║▌▌║║▌║▌▌║▌║▌▌║
        7501234500035
        ─────────────────────────────
        Importe de referencia    $168.00

        Este vale NO es comprobante de pago.
        Pásalo en la caja principal.

              ▄▄▄▄▄  ▄ ▄▄
              █ ▄ █ ▀▄▀▄█      ← QR, uso interno Avoqado
              █▄▄▄█ ▄█▀▄
              9470000015
```

Requisitos:

- Un código externo **por renglón**, con el nombre del producto y su importe de referencia arriba, y
  el código legible en texto debajo — si la pistola falla, el cajero teclea.
- Los renglones por peso muestran el peso y el precio por kilo.
- El total dice **"Importe de referencia"**, nunca "Total a pagar".
- Leyenda obligatoria de que no es comprobante de pago.
- Si el área es `HOLD_UNTIL_PAID`, se agrega: "Regresa con tu vale pagado para recoger tu producto."

### 16.2 Ancho y legibilidad

El ancho de papel del perfil determina cuántos módulos caben. La validación V5 lo verifica al
publicar, pero la impresión vuelve a comprobarlo en el momento: si un código no cabe legible,
**se bloquea la impresión de ese vale** y se explica. Nunca se imprime un código comprimido ni se cae
en silencio a texto plano — un código ilegible manda al cajero a teclear sin avisarle que eso pasó.

### 16.3 El código de Avoqado va en QR

En la ruta externa, el código de vale de Avoqado se imprime como **QR por defecto**. Razón operativa:
la pistola 1D de la caja externa no lo levanta, y así el cajero no lo escanea por error creyendo que
es un producto más. Los POS de área leen QR con la cámara y con las pistolas 2D.

Configurable a `CODE128` en el perfil, para áreas que entregan con pistola 1D. Al elegirlo, la UI
advierte el riesgo de escaneo cruzado en la caja externa.

### 16.4 Reimpresión

Conserva el mismo vale, el mismo código de vale y **los mismos códigos externos**. Nunca se emite un
vale nuevo por una falla de impresión. La copia se imprime marcada:

```text
        *** COPIA ***
   Sustituye al vale 9470000015
```

Avoqado **no puede** impedir que la caja externa cobre dos veces si alguien escanea el original y la
copia: esos códigos pertenecen al catálogo ajeno y son reutilizables por diseño. Lo que sí hace: deja
la copia marcada, registra el `AreaTicketPrintAttempt` con actor, terminal, hora y motivo, y abre una
incidencia `REPRINT_RISK` si el vale acumula más de una impresión y sigue sin confirmar.

---

## 17. Seguridad, permisos, tier y activación

### 17.1 Tier

La caja externa **no es un entitlement nuevo**: forma parte de `AREA_TICKETS`, que ya existe y hoy se
resuelve con `venueHasFeatureAccess(venueId, 'AREA_TICKETS')` — es decir, vive en el sistema
**Feature** (tier con Stripe), no en el sistema **Module**. Cruzar los resolvers falla en silencio,
así que el gate de la ruta externa usa exactamente el mismo.

> **Confirmación pendiente del founder al revisar este spec:** ¿la caja externa se queda en el tier
> que ya tenga `AREA_TICKETS`, o justifica PREMIUM por ser interoperabilidad con un sistema de
> terceros? La recomendación de este diseño es **el mismo tier**: no le da al cliente una capacidad
> nueva, le permite adoptar Avoqado por partes, y ponerle precio a eso encarece justamente el
> escenario de entrada.

### 17.2 Activación

Tier y activación son ejes distintos, y hay que decidir los dos:

| Eje | Contesta | Valor |
|---|---|---|
| Tier | ¿lo pagó? | el de `AREA_TICKETS` |
| Ajuste del venue | ¿lo quiere prendido? | `FulfillmentArea.settlementRoute`, default `AVOQADO` |
| Permiso | ¿este usuario puede? | ver 17.3 |

**Default OFF**, y no es una elección tibia: encender la ruta externa cambia dónde entra el dinero.
El switch canónico vive en `avoqado-web-dashboard` (configuración del área) porque se decide una vez
en la instalación, no durante el turno. **No se espeja en Android/iOS**: las apps lo leen, no lo
escriben.

Apagado se ve y se explica. Un área con ruta externa cuyo perfil fue despublicado no desaparece: la
pantalla de emisión muestra el bloqueo, qué falta y a quién pedírselo.

### 17.3 Permisos

```text
area-tickets:configure          (existente)  crear/editar/publicar perfiles y asignarlos al área
area-tickets:confirm-external   (NUEVO)      confirmar o marcar no cobrado un vale externo
area-tickets:issue              (existente)  emitir
area-tickets:deliver            (existente)  entregar
area-tickets:cancel             (existente)  cancelar
```

`area-tickets:confirm-external` es un permiso nuevo y arrastra la lista completa de la política del
repo: catálogo (`INDIVIDUAL_PERMISSIONS_BY_RESOURCE`), defaults por rol (`DEFAULT_PERMISSIONS`),
dependencias, gate de backend, gate del dashboard, y `npm run audit:permissions` en verde. Un permiso
sin default es un endpoint muerto para todos menos SUPERADMIN.

Default propuesto: MANAGER y superiores. **No requiere ser superadmin de la plataforma** — lo hace el
gerente del comercio.

### 17.4 Auditoría

Cada confirmación, "no cobrado", cancelación, reimpresión, publicación de perfil y cambio de ruta del
área escribe su `ActivityLog` (`action`, `entity`, `entityId`, `staffId`, `venueId`, `data`) en el
mismo cambio. Confirmar un cobro es una afirmación de dinero hecha por una persona: sin actor y hora
no vale nada.

### 17.5 MCP

Regla dura del repo: una capacidad que no es alcanzable por el MCP está incompleta. En el mismo
cambio:

- `area_ticket_status` — devolver ruta, estado de cobro externo e importe de referencia.
- `area_ticket_reconciliation_queue` — incluir las incidencias externas de §12.3.
- `pending_area_ticket_deliveries` — incluir los vales externos elegibles.
- **Nuevo** `external_cashier_profiles` (lectura) — perfiles, versión vigente y cobertura de mapeos.
- Si se expone un write de confirmación, va con `requirePermission` +`venueFilter` + `auditMcpWrite`
  y **confirm-gate de dos pasos con preview `current → new`**: es una afirmación sobre dinero.

---

## 18. Errores y recuperación

| # | Situación | Comportamiento |
|---|---|---|
| 18.1 | **Bloque de folios agotado sin red** | Emisión bloqueada, mensaje explícito. Jamás un vale sin identidad rastreable |
| 18.2 | **Impresión parcial** | Mismo vale, mismos códigos. Reimprimir marcado como copia, con motivo y actor |
| 18.3 | **Doble tap en "Imprimir"** | Idempotente por `idempotencyKey`: un vale, un consumo de inventario, un folio |
| 18.4 | **Impresora desconectada** | El vale queda emitido y `NOT_PRINTED`. **No** se marca `HANDED_OFF`. Reintento sin duplicar inventario |
| 18.5 | **Mapeo externo faltante** | Bloquea **antes** de emitir. Muestra producto, modificador y la ruta exacta del dashboard donde configurarlo |
| 18.6 | **Código inválido o demasiado ancho** | Bloquea la publicación (V3/V5). En impresión, bloquea ese vale. Sin fallback silencioso a texto |
| 18.7 | **Producto por peso sin formato** | Bloquea la publicación (V7) y la emisión. Nunca se trata como producto normal en silencio |
| 18.8 | **Se cae internet después de imprimir** | Se conservan vale local, folio consumido, snapshot de perfil, consumo provisional, auditoría y estado de impresión. Todo sincroniza después |
| 18.9 | **Sincronización repetida** | Emisión, confirmación e inventario se aplican exactamente una vez, por llave persistida |
| 18.10 | **Stock insuficiente al sincronizar** | La operación se mantiene, se permite negativo, se abre `NEGATIVE_STOCK` |
| 18.11 | **El perfil cambió mientras la terminal estaba offline** | El vale conserva su `externalProfileVersionId`. El papel manda |
| 18.12 | **El código impreso offline no coincide con el recalculado** | Se conserva el impreso, se abre `CODE_MISMATCH`. No se reescribe la historia |
| 18.13 | **Cancelar un producto ya preparado** | Permiso + motivo + autorización de gerente si está configurada. Merma o devolución a stock según política |
| 18.14 | **Original y copia escaneados en la caja externa** | Avoqado no puede evitarlo. Copia marcada, reimpresión registrada, incidencia `REPRINT_RISK` |
| 18.15 | **Importe externo distinto** | Se captura el real, se muestra la variación, `DISCREPANCY` + incidencia. **No** bloquea la entrega |
| 18.16 | **Nunca se confirma el cobro** | Cola de conciliación al cierre del día. Sin pagos ficticios, sin cierre automático |
| 18.17 | **Entrega por papel sin registro digital** | Permitido con `UNTRACKED`. No hay cola de pendientes y nada más se rompe |
| 18.18 | **Perfil despublicado con vales vivos** | Los vales emitidos siguen siendo válidos y entregables. Solo se bloquean emisiones nuevas |

---

## 19. Experiencia

### 19.1 Dashboard (`avoqado-web-dashboard`)

Vive junto a lo que ya existe en `src/pages/Settings/AreaTickets.tsx`:

- **Perfiles de caja externa** — lista, alta, edición, estado borrador/publicado, historial de
  versiones.
- **Códigos externos por producto** — tabla con búsqueda y filtros, alta manual y **carga CSV**;
  columna de cobertura ("142 de 156 productos mapeados") con los faltantes en un clic.
- **Validar y publicar** — errores agrupados por regla, cada uno con enlace al renglón. Nunca "hay 14
  errores" a secas.
- **Configuración del área** — ruta de cobro, perfil, modo de confirmación, política offline y
  seguimiento de entrega, cada uno con su explicación en una línea.
- **Cobros por confirmar** e **Incidencias** — colas accionables con filtros por área, fecha y tipo.

### 19.2 Android e iOS — paridad obligatoria

La regla del workspace es dura: Android e iOS se cambian **juntos, en el mismo trabajo**. Aplica
entero a esta ruta.

Superficies:

- **Emisión** — igual que hoy; la única diferencia visible es el botón: "Imprimir vale para caja" en
  vez de "Imprimir vale", y un aviso de bloqueo si falta un mapeo.
- **Cobros por confirmar** — nueva pestaña en el workspace del área, solo si la ruta es externa y el
  modo es `MANUAL`. Lista por antigüedad, detalle, confirmar con importe opcional.
- **Pendientes de entrega** — la de hoy, con los vales externos elegibles dentro.
- **Estado de folios** — indicador discreto cuando el bloque baja del umbral: "Quedan 12 folios sin
  conexión".

Todo con `designsystem/` y los patrones obligatorios: `AvoqadoFullscreenHeader`, `PrimaryButton`,
`AvoqadoDialog`, `SearchPillField`, `AvoqadoSuccessToast` al confirmar y al entregar,
`AvoqadoErrorToast` con acción de recuperación. Ningún lenguaje visual nuevo.

### 19.3 Cobertura de estados

| Superficie | Carga | Vacío | Éxito | Error recuperable | Bloqueado |
|---|---|---|---|---|---|
| Emisión externa | Catálogo + perfil | Carrito vacío | "Vale impreso" | Impresión falló → reimprimir | Mapeo faltante · sin folios · perfil despublicado |
| Cobros por confirmar | Primera página | "No hay cobros por confirmar" | "¡Cobro confirmado!" | Reintentar | Sin permiso `confirm-external` |
| Pendientes de entrega | Primera página | "No hay entregas pendientes" | "¡Entregado!" | Reintentar | Cobro sin confirmar |
| Folios | — | — | Bloque repuesto | Sin red → cuenta regresiva visible | Agotado sin red |

### 19.4 Mensajes

| Situación | Mensaje | Acción |
|---|---|---|
| Mapeo faltante | "Falta el código de caja externa para {producto}." | "Ver cómo configurarlo" |
| Sin folios offline | "Sin conexión y sin folios disponibles." | "Reintentar conexión" |
| Pocos folios | "Quedan {n} folios para emitir sin conexión." | "Conectar ahora" |
| Cobro por confirmar | "Confirma en la caja que este vale se cobró." | "Confirmar cobro" |
| Ya confirmado | "Confirmado a las {hora} por {persona}." | "Ver detalle" |
| Diferencia de importe | "La caja cobró {externo}; la referencia era {referencia}." | "Guardar de todos modos" |
| Perfil despublicado | "La configuración de caja externa no está publicada." | "Pídeselo a tu administrador" |

Nunca aparece la palabra "pagado" en la ruta externa. El vale se cobró afuera; Avoqado lo sabe de
oídas.

---

## 20. Observabilidad

```text
area_ticket.external.issued
area_ticket.external.issued_offline
area_ticket.external.handed_off
area_ticket.external.settlement_assumed
area_ticket.external.settlement_confirmed
area_ticket.external.settlement_discrepancy
area_ticket.external.settlement_not_charged
area_ticket.external.code_mismatch
area_ticket.external.incident_opened
area_ticket.external.incident_resolved
area_ticket.code_block.leased
area_ticket.code_block.low
area_ticket.code_block.exhausted
external_cashier_profile.published
external_cashier_profile.validation_failed
```

Métricas: tiempo de emisión→confirmación · % de vales sin confirmar al cierre · variación de importe
por área · vales emitidos offline y su latencia de sincronización · inventarios negativos generados ·
`CODE_MISMATCH` por perfil · reimpresiones por vale · agotamientos de bloque por terminal.

Alertas: vales sin confirmar por encima del umbral · cualquier `CODE_MISMATCH` (indica un mapeo malo
en producción) · bloque agotado en una terminal · tasa de discrepancia de importe fuera de la línea
base del área.

Con el contexto de ejecución del server, todo esto ya sale con `venueId`, `userId`, `terminalSerial`,
`entrypoint` y `correlationId` sin pasar nada — no hay que instrumentar a mano.

---

## 21. Pruebas obligatorias

### 21.1 TDD, no negociable

Toca dinero, inventario y códigos que otro sistema va a cobrar. Test primero en:

- El **encoder/decoder** de códigos variables — tabla compartida de vectores en TypeScript, Kotlin y
  Swift, con round-trip y desbordamiento.
- El **verificador GS1 mod-10** y la detección de colisión con el namespace 8/9.
- La **elegibilidad de entrega** por ruta.
- El **consumo idempotente de inventario** al sincronizar una emisión offline.
- La **confirmación de cobro** y el cálculo de variación.

### 21.2 PostgreSQL real

- Defensa contra folio duplicado: dos emisiones que llegan con el mismo `code` (lo que solo puede
  pasar por corrupción o por un bug de lease) — una gana, la otra falla limpio y visible, nunca se
  crean dos vales con el mismo código.
- Reintento de sincronización ×5: un vale, un movimiento de inventario, un settlement.
- Cancelar dos veces un vale ya consumido: una sola reversa de inventario.
- Confirmación concurrente desde dos terminales: un solo evento, el segundo devuelve
  `alreadyConfirmed`.
- Publicar una versión nueva mientras hay vales vivos con la anterior: los vales no cambian.
- El generador online **no** entrega un código que está en `AreaTicketCodeReservation` (§10.3).
- Los tres CHECK de §14.6 rechazan los estados imposibles.
- Emisión offline contra stock agotado: negativo permitido + incidencia, exactamente una.

### 21.3 End-to-end

```text
área externa emite 3 renglones (1 por peso)
→ imprime UN papel con 3 códigos externos + QR interno
→ la pistola REAL de la caja externa lee los 3
→ el POS externo forma UNA venta y cobra
→ el área confirma con el importe real
→ el área entrega
→ segundo intento de entrega es idempotente
→ Avoqado NO creó Order, NO creó Payment, NO tocó ventas ni contabilidad
```

Y el mismo recorrido **con el WiFi apagado** desde el paso 1 hasta después de imprimir.

### 21.4 Aislamiento

- Venue sin la ruta externa: comportamiento idéntico, suite dorada sin cambios.
- Un venue con un área externa y otra nativa: cada una se comporta como debe, sin filtrarse.
- Fallo al cargar el perfil: se bloquea la emisión externa, el POS normal sigue vivo.
- Cambio de venue: no se conserva el perfil ni el bloque de folios del anterior.

### 21.5 Hardware

- La pistola real de la caja externa, contra los formatos reales del perfil, en el papel real.
- Impresora de cada área, en su ancho real, con el código más largo del catálogo.
- Báscula de cremería alimentando un renglón `WEIGHT_EMBEDDED` de punta a punta.
- El QR interno leído con la cámara del POS de área.

**Sin la pistola real de MyBusiness leyendo un papel real, esto no se activa en producción.** Es la
única prueba que no se puede simular: todo lo demás lo controlamos nosotros.

---

## 22. Implementación y rollout

### Fase A — configuración y códigos (server + dashboard)

1. Modelos, migraciones y los tres CHECK. `npm run schema:map` en el mismo commit.
2. Perfil, mapeos, validaciones V1–V9, publicación y versión inmutable.
3. Encoder/decoder de códigos variables con su tabla de vectores compartida.
4. Dashboard: perfiles, mapeos, CSV, validación, publicación, configuración del área.
5. Permiso `area-tickets:confirm-external` completo, con `audit:permissions` en verde.

### Fase B — emisión y cobro (server + Android + iOS)

6. Ruta externa en la emisión: resolución de códigos, snapshot de versión, bloqueo por mapeo faltante.
7. Consumo de inventario en emisión, con el ancla idempotente.
8. `AreaTicketExternalSettlement`: handoff, confirmación, no cobrado, discrepancia.
9. Plantilla de impresión: un papel, N códigos, QR interno, copia marcada.
10. Cola "Cobros por confirmar" en Android **y** iOS.

### Fase C — offline

11. Bloques de folios: lease, consumo local transaccional, reposición oportunista.
12. 🔴 El generador online consulta las reservas (§10.3).
13. Sincronización idempotente de emisión + inventario + settlement.
14. Incidencias y su cola en el dashboard.

### Fase D — MCP, presentación y rollout

15. Tools del MCP de §17.5.
16. Deck y one-pagers de partners + **regeneración de los tres PDF**. Es una capacidad visible al
    cliente: editar el HTML sin regenerar el PDF deja el cambio incompleto.
17. Migraciones aplicadas con todo apagado; server compatible con clientes viejos.
18. Piloto en **una** área del venue, en modo `MANUAL` y con `externalOfflineIssuePolicy = BLOCK`.
19. Certificar la lectura con la pistola real antes de ampliar.
20. Habilitar offline y, si el cliente lo pide, `ASSUME_ON_PRINT`.

### Rollback

- Poner el área en `settlementRoute = AVOQADO` detiene emisiones externas nuevas y **deja terminar**
  las confirmaciones y entregas pendientes.
- Despublicar un perfil bloquea emisiones sin invalidar vales vivos.
- No hay rollback destructivo de schema. No se borran vales, settlements, incidencias ni folios.
- Los folios ya entregados a una terminal se quedan quemados: perder unos cientos de números no
  cuesta nada.

---

## 23. Frontera para un conector futuro

No se construye ahora. Lo único que se hace hoy es no cerrarse la puerta:

1. `AreaTicketExternalSettlement.confirmationMode` es un enum, no un booleano. Un tercer valor
   (`CONNECTOR`) cabe sin migrar datos.
2. `externalReference` ya existe: el folio del sistema ajeno tiene dónde vivir el día que llegue solo.
3. La confirmación es un **servicio con llave de idempotencia**, no un handler de pantalla: un webhook
   futuro llama exactamente al mismo servicio.
4. `ExternalCashierProfile.externalSystemLabel` es texto libre; el día que haya adaptadores, se le
   agrega un `adapterKey` opcional al lado.

Lo que **no** se hace: registry de adaptadores, interfaz de plugin, cola de outbox hacia terceros,
ni un `ExternalPosConnector` vacío "para después". Un andamio sin usuario es deuda con buena
presentación.

---

## 24. Decisiones cerradas

| Decisión | Resultado |
|---|---|
| ¿Conector con MyBusiness? | No. Papel y códigos, nada más |
| ¿Quién cobra? | El POS externo, y es la única autoridad de ese cobro |
| ¿Avoqado crea Order/Payment? | No. Nunca |
| ¿Cómo se llama el total de Avoqado? | Importe de referencia |
| ¿Un papel o varios? | **Uno**, con un código por renglón |
| ¿De dónde salen los códigos? | Del catálogo del POS externo, mapeados y configurables |
| ¿Dónde se decide la ruta? | Por **área**, no por venue ni por terminal |
| ¿Default? | `AVOQADO`. La ruta externa se prende explícitamente |
| ¿Se puede emitir sin internet? | Sí, con folios pre-reservados; configurable por área, default `BLOCK` |
| ¿Se reutiliza un folio? | Jamás |
| ¿Cuándo se descuenta inventario? | Al **emitir**, en la ruta externa. Enmienda E3 |
| ¿Offline puede dejar stock negativo? | Sí, con incidencia. No se invalida producto ya entregado |
| ¿Confirmación del cobro? | Manual (default) o asumida al imprimir; siempre distinguibles |
| ¿Se entrega con discrepancia de importe? | Sí. No con el cobro sin confirmar |
| ¿Entrega solo por papel? | Permitida, sin cola de pendientes y sin romper nada más |
| ¿Estado del vale externo? | Solo `ISSUED`/`CANCELLED`/`EXPIRED`. Los demás ejes viven aparte |
| ¿`AreaTicketFulfillment.orderId`? | Nullable + CHECK que lo exige en la ruta nativa |
| ¿Tabla de entrega separada? | No. Una sola, con discriminador |
| ¿Tier? | El de `AREA_TICKETS` — pendiente de confirmación del founder (§17.1) |
| ¿Permiso nuevo? | `area-tickets:confirm-external`, MANAGER+ |
| ¿Se llama "exportar"? | No. Reservado para un conector real |
| ¿Android e iOS? | Juntos, en el mismo trabajo |

---

## 25. Riesgos y decisiones abiertas

Ninguna de estas cambia el modelo de dominio. Se resuelven con instalación, prueba física o una
respuesta del founder.

| # | Pendiente | Cómo se cierra |
|---|---|---|
| R1 | **Tier** (§17.1) | Respuesta del founder al revisar este spec |
| R2 🔴 | **¿`VPZ…` es un código por PRODUCTO o por ETIQUETA?** Ver §25.1 — es el riesgo que puede invalidar la §9 entera | Dos preguntas al cliente, antes de escribir código |
| R3 | **De dónde se exportan los códigos de MyBusiness** | Pedirle al cliente el catálogo. Si no hay export, el CSV del dashboard es la vía manual |
| R4 | **Ancho de papel y modelo de impresora por área** | Descubrimiento en instalación |
| R5 | **Umbral de "cobro sin confirmar"** | Configurable; default cierre del día. Ajustar tras una semana de línea base |
| R6 | **Tamaño de bloque de folios** | Default 200/50. Medir emisiones por día y por terminal en el piloto |
| R7 | **¿El cliente quiere `ASSUME_ON_PRINT` desde el día uno?** | Arrancar en `MANUAL`; cambiar solo si la confirmación resulta ser fricción real |
| R8 | **Productos del área que no existen en el catálogo del POS externo** | Decisión del cliente: darlos de alta allá, o marcarlos `EXCLUDED` y cobrarlos con Avoqado |
| R9 | **Mantener los mapeos es deuda perpetua** | Cada producto nuevo en cualquiera de los dos catálogos deja un hueco hasta que alguien republique el perfil. §18.5 bloquea la emisión en vez de fallar callado, pero el dashboard debe avisar activamente: al crear un producto en un área con ruta externa, banner de "falta su código de caja externa" |
| R10 | **La confirmación manual puede no hacerse nunca** | En un piso con fila nadie confirma vale por vale. Es probable que el cliente derive a `ASSUME_ON_PRINT`, y entonces Avoqado no sabe nada del cobro y la cola de conciliación se llena sin lector. No es un defecto del diseño: es que el valor de esta ruta es **emitir**, no **verificar**. Medirlo en el piloto (% confirmado a los 7 días) antes de prometer conciliación a nadie |

### 25.1 🔴 R2 en detalle — la premisa que hay que confirmar antes de codificar

La evidencia disponible es **ambigua**, y las dos lecturas llevan a diseños distintos:

| Control | Código | Producto |
|---|---|---|
| `1636226` | `VPZ1636226` | NATA CHOYS 250 ML |
| `1636227` | `VPZ1636227` | BOLILLO |

Son **consecutivos** y corresponden a productos sin relación entre sí.

- **Lectura A — código por PRODUCTO.** Es el "control" del artículo en el catálogo de MyBusiness, y
  quedaron consecutivos porque se dieron de alta uno tras otro. → Este spec funciona tal cual.
- **Lectura B — código por ETIQUETA.** Es un contador de etiquetas impresas: un folio por instancia
  física, no por producto. → **No existe un código estable por producto** y el modelo de mapeos de
  §9 no sirve. Habría que rediseñar hacia otra vía (que el POS externo también lea EAN/SKU de
  fábrica, o captura manual del cajero).

**Test discriminante, en este orden:**

1. Escanear **el mismo producto desde dos etiquetas distintas** (o una impresa otro día). ¿Mismo
   código o distinto? Distinto ⇒ lectura B.
2. Escanear un **producto empacado con código de fábrica** — las papas o refrescos que §2 de v7 dice
   que ya venden. ¿MyBusiness lo reconoce por su EAN? Si sí, existe una vía por código de producto
   aunque `VPZ…` sea por etiqueta.

No se resuelve razonando ni leyendo código. Cuesta dos mensajes al cliente y decide si la §9 se
implementa o se rediseña.

---

## 26. Referencias

- Spec canónico v7: `2026-07-28-vales-por-area-y-bascula-design.md`
- Handoff de auditoría: `2026-07-29-AUDITORIA-handoff-vales-por-area.md`
- Guía operativa del cliente: `avoqado-android/docs/operacion/GUIA-CLIENTE-VALES-POR-AREA.md`
- Servicio vigente: `avoqado-server/src/services/mobile/areaTicketV7.mobile.service.ts`
- Modelos vigentes: `avoqado-server/prisma/schema.prisma:13071-13470`
- Decoder existente: `avoqado-android/.../pos/data/VariableWeightBarcode.kt` ·
  `avoqado-ios/.../AreaTickets/VariableWeightBarcode.swift`
- GS1, "2D Barcodes at Retail Point-of-Sale Implementation Guideline" —
  https://ref.gs1.org/guidelines/2d-in-retail/
- Oracle Retail Xstore, "Weight in Barcode" —
  https://docs.oracle.com/en/industries/retail/retail-xstore-point-of-service/21.0/rpxug/entering-items.htm

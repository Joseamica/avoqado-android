# PIN de autorización de gerente (manager override) — Diseño

**Fecha:** 2026-08-15 · **Estado:** listo para implementar · **Decisor:** founder
**Repos:** avoqado-server · avoqado-android · avoqado-ios · avoqado-web-dashboard
**Memoria:** `project_pin_autorizacion_gerente.md` (android) · `decision_pin_sin_hashear.md` (server)

## 1. Qué es

Cuando un rol sin permiso intenta una acción (anular, cortesía, descuento, fusionar mesas…),
en vez de "no tienes permiso" el POS abre un teclado de PIN para que alguien **con** ese
permiso autorice **esa acción, una vez**. La terminal no queda elevada.

Modelo verificado en Square 7.20 sobre el aparato (iPad restaurante + D3 retail, capturas en
`square-ui-reference/2026-08-restaurante-vs-retail/`): **el override no se configura en
ningún lado** — no existe lista de "qué acciones piden PIN"; sale solo de los permisos.
Lo único configurable es la sesión (cuándo re-pedir el código), y eso es v2.

## 2. Decisiones cerradas (founder)

| Punto | Decisión |
|---|---|
| Disparador | Toda acción bloqueada por permiso, en el momento. **General, no una lista** (como Square) |
| Validación | Contra el **permiso concreto**, nunca contra jerarquía de roles |
| PIN sin ese permiso | Mensaje claro: "Ese código tampoco tiene este permiso" |
| Alcance | **Una acción, una vez.** El token muere al usarse |
| Tier | Core, todos los planes, sin candado de plan |
| Activación | Switch en dashboard (`VenueSettings.managerPinOverrideEnabled`), **nace OFF** — como el retail de Square. Ningún venue existente amanece pidiendo PINs |
| PIN en DB | **Texto plano, a propósito** (decisión founder 2026-08-15 — no re-proponer hash) |
| Permisos desde el POS | **Como Square**: crear empleado + elegir conjunto sí; ver conjuntos sí (ojito); **cambiar el conjunto de uno existente, en gris**. Conjuntos se crean/editan solo en dashboard |
| Fusionar mesas | Permiso propio `orders:merge`, **restringido desde el día uno** — divergencia deliberada de Square, que no lo separa |

Divergencias conscientes de Square: PIN de 4-10 dígitos (Square: 4 fijo) · switch canónico en
dashboard, no en Ajustes del POS (regla de workspace: el POS lo lee) · sin código compartido
de equipo en v1.

## 3. Arquitectura: token de elevación en el punto único del 403

Enfoque A del análisis previo, confirmado por cómo lo hace Square (un mecanismo general, no
por-endpoint). **Cero cambios por-acción**: cubre las ~200 rutas con `checkPermission` de hoy
y las futuras.

```
POS: acción → 403 { required, overridable:true }
   → sheet de PIN ("Pide a un encargado su código")
   → POST /venues/:venueId/permission-overrides { pin, permission }
   → 201 { token, authorizedBy }            (token: un uso, 60 s, ESE permiso)
   → reintenta la request ORIGINAL + header X-Permission-Override: <token>
   → checkPermission consume el token → la acción pasa → auditoría
```

### 3.1 Server

**El 403 de permiso gana un campo aditivo** (`checkPermission.middleware.ts:308` — hoy ya
devuelve `error, message, required, userRole`; nunca renombrar ni quitar, apps viejas lo leen):

```json
{ "error": "Forbidden", "message": "Permission 'orders:merge' required",
  "required": "orders:merge", "userRole": "WAITER", "overridable": true }
```

`overridable: true` solo si el switch del venue está ON. Los 403 de tier (`featureCode`) y de
membresía ("No access to this venue") **no** lo llevan — siguen su camino actual (upsell / error).

**Endpoint nuevo** `POST /api/v1/mobile/venues/:venueId/permission-overrides`
— body `{ pin, permission }`, protegido por el `pinLoginRateLimiter` existente
(prod: 10 intentos/15 min por IP + 20 por venue).

- Busca el `StaffVenue` del venue cuyo `pin` coincida.
- Sin coincidencia → 401 "Código incorrecto".
- Coincide pero SIN el permiso efectivo → 403 con código propio `OVERRIDE_INSUFFICIENT` →
  la UI dice "Ese código tampoco tiene este permiso". El permiso efectivo se evalúa
  **exactamente como lo evalúa `checkPermission`** (permissionSet + `VenueRolePermission`);
  el Json de `StaffVenue.permissions` NO participa en esa puerta hoy, así que tampoco aquí —
  divergir aceptaría PINs para acciones que igual fallarían.
- El intento con PIN válido pero insuficiente **se registra en `ActivityLog`** — es la señal
  clásica de fraude interno (alguien probando códigos ajenos) y el rate limiter solo lo
  frena, no lo deja escrito.
- Coincide y puede → crea el registro y devuelve `{ token, authorizedBy: { id, name } }`.

**Modelo nuevo = token Y auditoría en una sola tabla** (sin Redis; el consumo atómico es un
`updateMany where token && consumedAt IS NULL && expiresAt > now()` → count 1 garantiza un uso
aunque dos requests lleguen a la vez):

```prisma
model PermissionOverride {
  id            String    @id @default(cuid())
  venueId       String
  token         String    @unique            // uuid
  permission    String                       // 'orders:merge'
  authorizedById String                      // StaffVenue del que puso el PIN
  requestedById  String?                     // StaffVenue del que estaba bloqueado
  expiresAt     DateTime                     // +60 s
  consumedAt    DateTime?
  consumedRoute String?                      // entrypoint normalizado
  createdAt     DateTime  @default(now())
}
```

**Consumo en `checkPermission`**: si el permiso falla y viene `X-Permission-Override`, valida
token↔permiso↔venue, lo consume, adjunta `authContext.overrideAuthorizedBy` y deja pasar. El
`ActivityLog` de la acción registra "autorizado por Fulano" — visible ya por el MCP
`get_activity_log`, sin tool nueva (obligación MCP cubierta; se documenta en el tool).

Mismo cambio: **regenerar `docs/SCHEMA_MAP.md`** (`npm run schema:map`) por el modelo nuevo.

### 3.2 Android

- `ForbiddenInterceptor` ya clasifica el 403 (nuestro vs intermediario vs tier — commit
  `65807e9`). Se agrega `overridable: Boolean? = null` a `ForbiddenResponse` (default null:
  contra un server viejo simplemente no se ofrece PIN).
- El interceptor NO abre UI (no se bloquea el hilo de red): emite el evento tipado con
  `required` + la request original; un `ManagerOverrideCoordinator` (singleton Hilt) muestra
  el sheet, pide el token y **reintenta la request original** con el header.
- UI: sheet con `PinPadView` (existe en `timeclock/`), patrón `AvoqadoDialog`. Título "Se
  necesita autorización", subtítulo con la acción y "Pide a un encargado su código". Éxito →
  la acción original continúa su flujo normal (su propio toast); error → mensaje inline.
- Espejo del nombre exacto `orders:merge` y gating del diálogo de fusionar.

### 3.3 iOS — mismo trabajo, no "después"

Paridad obligatoria: `APIClient` (el 403 ya pasa por `isFromIntermediary`) + coordinator +
pin pad equivalente + `orders:merge`. Ambas apps compilando antes de dar por hecho.

### 3.4 Visibilidad de acciones bloqueadas (las dos apps) — sin esto el PIN es inalcanzable

Hoy las apps **esconden** los controles cuando el rol no tiene el permiso (gating local
espejado). Un botón que no existe nunca produce el 403, y el override jamás se dispararía
para esas acciones. Square hace lo contrario: la acción se ve, y al tocarla pide el código.

- Switch **OFF** → todo como hoy: se esconde.
- Switch **ON** → la acción gateada se **muestra con un candado** (`Lock` chico junto al
  label); al tocarla se dispara la llamada normal → el 403 `overridable` abre el PIN. No hay
  lógica nueva de permisos en el cliente: el server sigue siendo el juez.
- **Sin red con switch ON** → el sheet no puede validar: mensaje claro "Necesitas conexión
  para pedir autorización" (la acción NO se encola — un rechazo de negocio no es un fallo de
  red).
- Caso merge: hoy es visible para todos (viaja con `orders:update`), así que ahí no cambia
  nada visual — el server empieza a rechazar y el PIN aparece solo.
- **Alcance v1 del candado:** se aplica a **Reembolsar** (hoy escondida), que es donde el
  override más se necesita. Las demás acciones gateadas ya son visibles y el 403 las cubre
  sin cambio de UI. Más candados se agregan cuando el piso los pida — el mecanismo es
  genérico.

### 3.5 Dashboard

- Switch `managerPinOverrideEnabled` en ajustes del venue (canónico, escribe `VenueSettings`).
- La UI de permisos existente gana el permiso `orders:merge` en el grid.
- Apagado se VE: en el POS, el 403 sin override muestra el mensaje de siempre — no hay
  elemento que desaparezca, así que no aplica pantalla explicativa extra.

## 4. Granularidad: solo `orders:merge` en v1

Verificado ruta por ruta: `orders:update` hoy traga **10 acciones** (`mobile.routes.ts`):
move `1756`, assign `1770`, split `2015`, **merge `2028`**, split-by-seat `2041`, discounts
add/remove `2050/2063`, comp `2072`, service-charges add/remove `2105/2117`. Separadas ya:
`orders:cancel` (`794`) y `payments:create` (`748`).

v1 separa **únicamente merge** (lo decidió el founder). Roles ADMIN/OWNER/MANAGER lo traen;
WAITER no. 🔴 **Avisar a los venues antes de liberar**: un mesero que hoy junta mesas dejará
de poder (quedará a un PIN de distancia). Las otras 9 acciones se separan después si el piso
lo pide — el override ya las cubre a todas por diseño.

🔶 **ABIERTO (founder) — venues con conjuntos personalizados:** un conjunto custom reemplaza
los defaults del rol, así que sin migración **nadie** en esos venues (ni el gerente) podría
fusionar ni autorizar con PIN hasta editar el conjunto en el dashboard. Opciones: (A)
migración que otorga `orders:merge` a los conjuntos que ya tienen `orders:cancel` — preserva
la intención "gerentes sí, meseros no" sin bloquear a nadie; (B) no migrar y avisar venue por
venue. v1 implementa los defaults de rol; la migración espera la respuesta.

## 5. Seguridad

- **Unificar PIN a 4-10 dígitos** — hoy hay DOS reglas vivas: 4-10 en `tpv.schema.ts:15` y
  `invitation.schema.ts:25`, pero 4-6 en `superadmin-staff.schema.ts:65,113,131` (y comentarios
  viejos en `schema.prisma:1113` y el rate-limit middleware). Se corrigen los tres puntos de
  superadmin + comentarios. Sin esto, el camino superadmin impide los PINs largos que son la
  premisa de seguridad del texto plano.
- Token: un uso (update atómico), 60 s, atado a permiso+venue. Reuso o expiración → 403 y el
  cliente vuelve a pedir PIN.
- Rate limit: el `pinLoginRateLimiter` existente en el endpoint nuevo.
- El PIN viaja una vez por request de override, sobre TLS, nunca se guarda en el dispositivo.
- Auto-autorización: imposible por construcción — si tu PIN tiene el permiso, no hubo 403.

## 6. Offline — honesto: v1 NO tiene override sin red

El PIN se valida en el server y **no se guarda nada del PIN en el dispositivo**. Sin red:

- Las acciones que el rol SÍ tiene siguen funcionando offline como hoy (outbox).
- Una acción bloqueada por permiso queda bloqueada con el mensaje de siempre. No es regresión:
  hoy tampoco se puede.
- El replay del outbox no cambia: el reducer evalúa permisos con los mismos servicios.
- v2 (sin diseñar, NO prometer): hashes de PIN en Keystore/Enclave para override offline de
  acciones sin dinero. La auditoría externa de esa idea nunca corrió (límites de Codex) —
  no construir sin auditarla.

## 7. Fuera de v1 (v2 explícito)

- **Pantalla de equipo/permisos del POS estilo Square** (crear empleado + elegir conjunto,
  ver conjuntos con el ojito, editar el conjunto de uno existente en gris): la decisión está
  tomada en §2 pero se construye como proyecto propio — el override no la necesita.
- Pantalla `Seguridad` estilo Square: re-pedir código después de cada venta / al cancelar /
  timeout. Es un sistema distinto (sesión, no override).
- Código compartido de equipo.
- Separar las otras 9 acciones de `orders:update`.
- Override offline.

## 8. Verificación (TDD — toca permisos: test primero, no negociable)

Server (suite del módulo): token un-solo-uso bajo carrera (2 consumos concurrentes → 1 pasa) ·
expiración · PIN correcto sin permiso → `OVERRIDE_INSUFFICIENT` · token de un permiso no sirve
para otro ni para otro venue · 403 de tier y de membresía NO llevan `overridable` · switch OFF
→ sin `overridable` · rate limit responde 429 · superadmin acepta PIN de 10 dígitos.

Apps: unit del coordinator (403 overridable → evento; intermediario/tier → NO) + gating de
visibilidad (switch ON → candado visible; OFF → oculto como hoy; sin red → mensaje de
conexión) + compilación de ambas + prueba en hardware del flujo completo (T3/D3) con un rol
WAITER real.

## 9. Orden de construcción

1. Server: schema + endpoint + consumo en `checkPermission` + tests + `schema:map` + unificación 4-10.
2. Dashboard: switch + `orders:merge` en el grid.
3. Android e iOS en paralelo (mismo trabajo).
4. Hardware, deploy backend primero, avisar el cambio de merge antes de liberar apps.

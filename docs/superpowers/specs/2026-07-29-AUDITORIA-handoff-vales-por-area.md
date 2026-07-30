# Handoff de auditoría — Vales independientes por área + básculas

**Fecha:** 2026-07-29
**Estado:** corregido después de auditoría, validación adicional con el founder y tercera pasada
con `gstack plan-ceo-review`, `plan-eng-review`, `plan-design-review` e investigación contra el
código vigente. La spec canónica está en v7.
**Spec canónico:** `2026-07-28-vales-por-area-y-bascula-design.md`

> Este archivo explica qué cambió, qué código existente ya no representa el producto correcto y
> qué debe verificar la siguiente persona. No sustituye el spec canónico.

---

## 1. Corrección fundamental

La implementación inicial modeló una **cuenta compartida** que varias áreas podían modificar.
Después de revisar la conversación completa con el cliente y contrastar el flujo con patrones de
retail y POS, esa interpretación quedó descartada.

El flujo confirmado es:

```text
CREMERÍA      emite vale A y conserva sus productos
PANADERÍA     emite vale B y conserva sus productos
CAFETERÍA     emite vale C y conserva sus productos
CAJA          escanea A + B + C + productos normales
CAJA          forma una venta y cobra un solo monto
CLIENTE       regresa con el comprobante pagado
CADA ÁREA     verifica y registra la entrega de su propio vale
```

Cada vale es independiente. Caja los consolida sin borrar su identidad, área, productos ni estado
de entrega.

### Evidencia directa

El cliente dijo:

> “Si pides jamón, y un café y aparte pan se genera un ticket por separado... Esos tickets se
> escanean en caja y se forma un solo ticket con el total de productos que escogiste y se cobra un
> solo monto.”

> “El área le guarda el producto hasta que regresa con el ticket pagado.”

También confirmó:

- 3 áreas: cremería, panadería y cafetería.
- 1 caja.
- La pistola de caja lee el CODE 128 probado.
- La entrega debe admitir revisión del papel y registro/escaneo digital.
- Básculas en alcance: Justa LP7516 en CEDIS y Rhino en cremería.

---

## 2. Reglas que no deben reinterpretarse

1. Un vale pertenece a una sola área.
2. Un vale impreso no recibe nuevos renglones.
3. El cliente puede llevar varios vales.
4. Caja puede mezclar vales y productos normales.
5. Se crea una sola orden final, un solo total y un solo comprobante.
6. Los vales fuente siguen existiendo después de consolidar.
7. El área conserva producto hasta el pago.
8. La entrega puede iniciarse desde una lista o por escaneo.
9. Las dos modalidades de entrega crean el mismo evento idempotente.
10. Las áreas y permisos se derivan de la terminal autenticada.
11. El servidor genera los códigos durante el MVP online.
12. El módulo se activa explícitamente por venue y terminal.
13. Las básculas son una capacidad independiente; su fallo cae a captura manual.
14. CEDIS y cremería tienen perfiles de hardware separados.
15. El vale conserva el precio server-side de emisión; caja no repricing ni edita sus líneas.
16. La orden y el pago existentes siguen siendo la única autoridad monetaria.
17. Un pago incierto congela claims y entra a conciliación; nunca habilita recobro libre.
18. El vale puede reservar inventario, pero la venta lo descuenta exactamente una vez.
19. Vale creado con impresión fallida se reimprime; no se vuelve a emitir.
20. `AREA_TICKETS` y `SCALE_INTEGRATION` se liberan por separado.
21. Una venta con vales no usa la cola offline de efectivo.
22. Split tender conserva un intento idempotente por abono y una sesión parcialmente pagada.
23. Materializar congela vales y productos; cualquier edición ocurre antes.
24. `buildOrderItemsData` calcula al emitir, pero materialización copia el snapshot persistido.
25. Reservas de inventario son filas persistentes protegidas por locks en orden estable.
26. Impresión local registra intentos independientes; una falla nunca reemite el vale.
27. Android e iOS mantienen paridad funcional; USB puede ser específico de plataforma.
28. Todo importe del dominio usa pesos 1:1 en `Decimal`; sólo los adaptadores externos convierten
    a minor units.
29. El recibo pagado conserva el área y código del vale de cada línea, más un
    `areaDeliveryCode` escaneable y legible; el recibo normal no cambia.
30. Una línea importada desde un vale ya fue preparada: nunca vuelve a KDS ni genera otra comanda
    post-pago. Sólo los productos normales agregados en caja siguen ese flujo.

---

## 3. Estado del código auditado

### Server

Snapshot revisado: `avoqado-server`, `develop`, commit base `74db19e9`.

Verificado durante la auditoría:

- 631 suites unitarias pasaron.
- 7,679 tests pasaron y 13 quedaron omitidos.
- Prisma reportó 363 migraciones y schema al día.
- Los tests dirigidos de código, servicio y efectivo pasaron: 57 tests.
- `npm run typecheck` falló:

```text
src/services/mobile/areaTicket.mobile.service.ts(208,64):
Property 'venueTimezone' does not exist on type 'AreaTicketView'
```

El código server existente implementa la arquitectura anterior y no debe considerarse compatible
con v7 aunque sus pruebas actuales estén verdes.

### Android

Snapshot revisado: `avoqado-android`, `main`, HEAD `bcbf824`.

Verificado:

- 576 tests unitarios Android pasaron.
- Los worktrees de caja y entrega no estaban integrados.
- Los dos worktrees tenían contratos distintos entre sí y respecto al servidor.
- No existe un flujo end-to-end ejecutable contra hardware real.

La suite verde prueba piezas aisladas, no el flujo corregido. Estos hashes son el snapshot histórico
de la auditoría original; cualquier integración debe volver a comparar contra el HEAD vigente.

### Actualización posterior a la implementación

La observación anterior es histórica. La integración v7 ya existe en los worktrees principales de
server, Android, iOS y dashboard, sin crear otro worktree ni cambiar de rama. La validación del
2026-07-29 comprobó:

- 596 tests Android, build server y build dashboard verdes.
- Integración PostgreSQL dirigida del flujo de vales verde.
- Emisión en Samsung, consolidación/pago en Sunmi T3 PRO SUPER y dos tipos de entrega.
- Impresión y reimpresión reales en Epson TM-m30III Ethernet.
- Recibo pagado con área + vale por línea y `areaDeliveryCode` CODE 128.
- Ninguna redistribución de líneas preparadas a KDS/comanda post-pago.

La evidencia y los IDs exactos de prueba están en
`.gstack/qa-reports/qa-report-device-avoqado-2026-07-29.md`. Los datos QA se eliminaron y el producto
temporal regresó a su precio original.

---

## 4. Defectos encontrados en la implementación anterior

### 4.1 Contrato incompatible

El servidor devolvía `ticket` en el nivel superior con importes en pesos y campos como `items`.
Los clientes esperaban `data`, `lines`, nombres monetarios incompatibles y `weightKg`.

Consecuencias observadas:

- El escáner no podía reconstruir la cuenta.
- Un producto pesado podía fallar por nombres de campo distintos.
- Un precio personalizado podía convertirse en cero.
- La respuesta de entrega tampoco coincidía con su modelo Android.

v7 fija un envelope único, dinero `Decimal` en pesos 1:1 y peso como string decimal.

### 4.2 Carrera entre agregar y reclamar

En la arquitectura anterior `claim` no incrementaba `version`, mientras `addItems` usaba esa versión
para su CAS. Un área podía agregar producto después de que caja hubiera reclamado la cuenta.

v7 elimina `addItems` sobre un vale impreso. Cada vale es un snapshot inmutable.

### 4.3 Falta de idempotencia

Android enviaba una idempotency key al agregar, pero server la ignoraba. Un timeout después del
commit podía duplicar productos y cobro.

v7 exige idempotencia persistente en todas las mutaciones.

### 4.4 Área controlada por el cliente

`fulfill` y el endpoint de partición aceptaban `fulfillmentAreaId` enviado por el dispositivo como
si fuera autoridad.

v7 obliga a derivar el área desde la terminal autenticada y sus capacidades.

### 4.5 Gate global

`SecureStorage.areaTicketsEnabled` era global, no estaba ligado a `venueId` y no se limpiaba al
cambiar de venue. Además, server no entregaba de forma consistente `modules.areaTickets`.

v7 usa configuración efectiva server-side por venue y terminal. Un fallo de settings oculta
únicamente el módulo de vales; el POS normal sigue funcionando.

### 4.6 Entrega no atómica

La orden se verificaba como pagada antes de iniciar la transacción de fulfillment. Un reembolso o
cancelación concurrente podía ocurrir antes de crear la entrega.

v7 exige comprobar pago y crear el evento idempotente dentro de una operación consistente.

### 4.7 Códigos innecesariamente acuñados en Android

Particiones, contadores y manejo de reinstalación se diseñaron para un futuro offline que no forma
parte del MVP.

v7 genera el código en servidor. El escáner resuelve por contexto y devuelve `AMBIGUOUS` si un
código coincide simultáneamente con producto y vale.

### 4.8 No estaba cerrada la autoridad del precio

Sin una regla explícita, `materialize-order` podía volver a consultar el catálogo y cobrar un precio
distinto al papel ya impreso, o confiar en el total enviado por Android.

v7 congela el snapshot calculado por servidor al emitir. La caja puede retirar el vale completo y
aplicar reglas normales de descuento de orden, pero no editar ni repricing sus renglones.

### 4.9 El TTL podía vencer durante un pago capturado

Un claim de cinco minutos no puede liberarse mientras un proveedor externo quizá ya cobró. Eso
permitiría que otra caja reclamara los mismos vales y cobrara otra vez.

v7 agrega `PAYMENT_PENDING` y `RECONCILIATION_REQUIRED`, suspende el TTL y obliga a reintentar con
la misma llave hasta obtener un resultado definitivo.

### 4.10 Inventario e impresión no tenían recuperación completa

El área aparta producto antes del pago. Sin reserva lógica, otra venta puede consumir el stock;
sin una transición clara, emisión y pago pueden descontarlo dos veces. Además, repetir emisión
después de una falla de impresora duplica el vale.

v7 separa reserva de deducción y exige que el pago consuma la reserva una sola vez. Una impresión
fallida reusa el `ticketId` y el código ya creados.

---

## 5. Código existente que requiere sustitución o adaptación

### Server

- `Order.areaTicketCode` no debe seguir representando la cuenta compartida.
- Hace falta `AreaTicketLine` independiente.
- Hace falta `AreaTicketCheckoutSession`.
- `addItems` posterior a impresión desaparece.
- `claim` debe asociar un vale a una sesión e incrementar versión.
- Los claims múltiples deben adquirir locks en orden estable o verificar el conteo de un batch
  atómico.
- El pago debe finalizar todos los vales de la orden atómicamente.
- El TTL debe congelarse durante pago y conciliación.
- Cada `OrderItem` importado debe conservar `areaTicketLineId` y `fulfillmentAreaId`.
- La orden necesita un `areaDeliveryCode` opaco y estable para el comprobante pagado.
- `buildOrderItemsData` sigue siendo autoridad al emitir; materialización copia el snapshot sin
  repricing.
- Hace falta snapshot monetario inmutable para líneas de vale.
- Inventario debe persistir reservas por componente, bloquear filas al emitir y consumirlas
  idempotentemente al finalizar pago.
- Hace falta `AreaTicketPaymentAttempt`; una sola llave en la sesión no representa split tender.
- `materialize-order` debe congelar la sesión.
- Hace falta `AreaTicketPrintAttempt` para separar emisión server-side de impresión física.
- `fulfill` no debe aceptar un área autoritativa.
- No debe existir horizonte fijo de siete días para pendientes.
- Pendientes deben paginar por cursor e índices operativos.

### Android

- Sustituir los modelos divergentes de los dos worktrees por DTOs contractuales compartidos.
- Eliminar el booleano global del venue.
- La caja debe mantener una sesión persistente de consolidación.
- El carrito debe admitir grupos de vales y productos normales.
- Los renglones importados no deben permitir editar peso, cantidad o precio.
- El escáner no debe decidir sólo por largo/prefijo.
- Pendientes y escaneo de comprobante deben compartir el mismo caso de uso de entrega.
- La configuración de báscula debe estar ligada a venue, terminal y perfil físico.
- Reiniciar durante pago incierto debe abrir “Confirmando pago”, no un carrito cobrable.
- Reiniciar con pago parcial debe mostrar saldo y permitir sólo el siguiente abono.
- La cola offline de efectivo debe quedar prohibida cuando existan líneas de vale.
- Impresión fallida debe reimprimir el mismo vale.
- El recibo pagado debe imprimir `areaDeliveryCode` en barras y texto, y conservar área + vale de
  cada línea para la verificación visual.
- Las líneas bloqueadas de vale deben excluirse de KDS y comandas post-pago.
- Todo UI nuevo debe cubrir carga, vacío, error, éxito y bloqueo con el design system existente.

### iOS

- Consumir los mismos DTOs y fixtures que Android.
- Mantener equivalencia visible en emisión, consolidación, pago, entrega y recuperación.
- Captura manual de peso es obligatoria aunque el transporte físico se certifique primero en
  Android.

### Dashboard

- El módulo no puede depender indefinidamente de un seed manual.
- Debe configurar áreas, terminales, permisos, políticas de entrega y perfiles de báscula.
- Debe exponer sesiones que necesitan conciliación, reservas y claims activos.

---

## 6. Alcance de básculas corregido

La versión anterior declaraba CEDIS fuera de alcance. Eso ya no es válido.

| Ubicación | Báscula | Alcance |
|---|---|---|
| CEDIS | Justa LP7516 | Primera etapa; entrada para recepción/despacho/conteo/ajuste |
| Cremería | Rhino | Primera etapa; peso de renglón del vale |

Las dos requieren descubrimiento físico de:

- Cable y adaptador.
- Host real: Android USB serial o bridge de escritorio.
- Baud rate y formato de trama.
- Indicador de peso estable.
- Unidad, tara, signo y manejo de sobrecarga.

Ninguna debe bloquear vales. Captura manual es el fallback obligatorio.

Investigación posterior del 2026-07-29 añadió dos perfiles candidatos de otro cliente:

- Kretz Report con impresor: integrar primero por etiqueta/código; su protocolo de gestión por red
  es propietario y no se declara compatible sin documentación/certificación.
- Torrey familia PCR con torreta, PCR-20T/PCR-40T por confirmar en placa: USB serial, comando `P`
  y lectura ASCII. Como su manual no expone bit de estabilidad, Avoqado exige dos lecturas iguales.

El botón visible de simulación fue retirado del panel operativo. La simulación sólo se conserva
como prueba automatizada del contrato normalizado.

La Justa no crea vales: alimenta un flujo de inventario de CEDIS. `AREA_TICKETS` y
`SCALE_INTEGRATION` tienen flags, pruebas y rollback independientes.

---

## 7. Pruebas obligatorias antes de considerar integrado

### Contrato

- Fixtures JSON idénticos consumidos por server, Android e iOS.
- Dinero siempre en pesos 1:1 como decimal string de dos posiciones.
- Peso como decimal string.
- Estados y errores exhaustivos.

### PostgreSQL real

- Dos clientes reclaman el mismo vale.
- Timeout y reintento de emisión.
- Reescaneo en la misma sesión.
- Heartbeat renueva una sesión `OPEN`; su ausencia libera claims al vencer.
- Dos sesiones intentan materializar el mismo vale.
- Dos intentos simultáneos por el mismo saldo; sólo uno puede cobrarlo.
- Split tender secuencial conserva los claims hasta que la orden quede totalmente pagada.
- Fallar el segundo abono conserva el primero y deja la sesión parcialmente pagada.
- Intentar efectivo sin red en una venta con vales no encola, no imprime y no muestra éxito.
- Precio de catálogo cambia después de emitir; el snapshot no cambia.
- Descuento de orden asigna hasta el centavo de forma determinista sin salir de pesos `Decimal`.
- Cancelación de sesión libera claims.
- Claim no vence durante `PAYMENT_PENDING`.
- Timeout de proveedor entra a conciliación y el mismo webhook/reintento finaliza una vez.
- Reserva de inventario evita sobreventa y el pago descuenta una sola vez.
- Dos emisiones simultáneas contra la misma existencia: sólo las que caben crean reservas.
- Reembolso concurrente con entrega.

### End-to-end

```text
cremería emite
→ panadería emite
→ cafetería emite
→ caja escanea los tres
→ caja agrega producto normal
→ cobra una vez
→ imprime comprobante con resumen por área + código de entrega
→ sólo el producto normal entra a KDS/comanda post-pago
→ cada área confirma
→ segundo intento de entrega es idempotente
```

### Aislamiento

- Venue normal antes/después: mismo comportamiento.
- Cambio de venue no filtra configuración.
- Settings caídos no bloquean venta normal.
- Terminal no asociada no emite ni entrega.

### Hardware

- D3, impresoras y pistola reales.
- Justa LP7516 en CEDIS.
- Rhino en cremería.
- Fallback manual y reconexión.
- Vale creado con impresora caída; reimpresión conserva el código.
- Cada resultado físico crea un intento idempotente sin reemitir el vale.

### Experiencia

- Estados loading, empty, success, error y blocked en emisión, checkout, pago y entrega.
- Reinicio de app con sesión abierta y con pago incierto.
- Reinicio con pago parcial conserva abonos y saldo.
- TalkBack, targets de 48 dp, dark/light y nombres largos.
- Pantalla secundaria muestra sólo productos y dinero, no estados internos.

---

## 8. Orden recomendado de trabajo

1. Congelar v7 como contrato.
2. Crear modelos de vale independiente y sesión de consolidación.
3. Reusar el motor monetario existente y fijar snapshots/reservas.
4. Implementar configuración efectiva por venue/terminal.
5. Generar DTOs/fixtures compartidos y catálogo de errores.
6. Corregir seguridad, CAS, locks, idempotencia y conciliación en server.
7. Adaptar caja, emisión y entrega Android contra el contrato.
8. Ejecutar integración PostgreSQL y pruebas de recuperación.
9. Probar el flujo completo sin báscula.
10. Activar `AREA_TICKETS` sólo en el venue piloto.
11. Certificar y activar cada báscula de forma independiente.

---

## 9. Resultado de la tercera pasada gstack

| Lente | Resultado |
|---|---|
| CEO | `HOLD_SCOPE`: el problema y usuario están claros; se separaron los releases de vales y básculas sin ampliar a food halls/offline |
| Ingeniería | Se cerraron snapshots sin repricing, reservas persistentes, split tender, efectivo offline, congelamiento, impresión, locks, rollout y rollback |
| Diseño | La spec ahora define IA, jerarquía, estados, copy, accesibilidad y pantalla secundaria sobre patrones existentes |

El diseñador visual de gstack no estaba instalado (`DESIGN_NOT_AVAILABLE`), por lo que no se
generaron mockups. Esto no bloquea el contrato técnico, pero una revisión visual con la Sunmi D3
real sigue siendo requisito antes de producción.

No quedan preguntas fundamentales sobre el modelo comercial. Los pendientes con el cliente son
pruebas físicas o preferencias configurables, no decisiones que cambien el dominio.

---

## 10. Criterio de aprobación para la siguiente auditoría

No aprobar por conteo de tests aislados. Aprobar únicamente cuando:

- `typecheck` y suites estén verdes.
- El contrato coincida byte por byte entre repos.
- Las pruebas PostgreSQL demuestren claims, pago y fulfillment.
- Precio, descuentos e inventario cuadren con la orden existente al centavo.
- Pago incierto tenga conciliación demostrable y no permita segundo cobro.
- Impresión fallida y reinicio de app tengan recuperación ensayada.
- El venue normal no cambie.
- El flujo completo funcione en una rama integrada.
- Exista evidencia física de impresión, escaneo y ambas básculas, o una declaración explícita de
  fallback manual para el hardware aún no certificado.

Hasta entonces el estado es **NO LISTO PARA PRODUCCIÓN**.

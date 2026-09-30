# Offline-first + Hub LAN — lo que hay que saber antes de tocar esto

Aplica a **avoqado-android, avoqado-ios y avoqado-server a la vez**. Los tres
comparten un contrato; romper uno rompe los otros en silencio.


🔴 **Y el criterio de aceptación, que aplica a TODO el workspace y no sólo a este subsistema:**
`.claude/rules/todo-funciona-sin-red.md` en el root — las cuatro preguntas que se responden antes de
dar por terminado cualquier cosa que toque un aparato, y cómo se prueba apagando la red de verdad.

---

## 1. El modelo mental en 60 segundos

Sin internet, cada POS escribe cada mutación como un **intent append-only** en su
outbox local y la reproduce al reconectar (FIFO por dispositivo). El server tiene
un **reducer** que aplica esos intents usando **los mismos servicios que la ruta
online** — sincronizar no es una puerta trasera: el feature gating y la propiedad
de mesa se evalúan igual.

Encima de eso, el **hub LAN** (PREMIUM) hace que los POS se coordinen entre sí
por el WiFi del local para PREVENIR conflictos en vez de detectarlos al
reconectar.

```
POS sin red → outbox (intents) → replay al reconectar → reducer del server
                    ↑
            hub LAN (opcional): leases de mesa entre POS, sin internet
```

---

## 2. Reglas que NO se negocian

### 2.1 El contrato de intents se espeja por nombre EXACTO

`SyncIntentType` en `avoqado-server/src/services/mobile/sync.mobile.service.ts`
es la fuente de verdad. Los 15 tipos actuales:

```
OPEN_TABLE · ADD_ITEMS · PAY_CASH · APPLY_DISCOUNT · APPLY_SERVICE_CHARGE
COMP_ORDER · UPDATE_DETAILS · CANCEL_ORDER · MOVE_ORDER · ASSIGN_ORDER
CLEAR_TABLE · SPLIT_ORDER · SPLIT_BY_SEAT · MERGE_ORDERS · KDS_TICKET_MARK
```

`KDS_TICKET_MARK` (KDS etapa 3): marca pegajosa por folio de la comanda — `FALLBACK_PRINTED` lo produce la caja
cuando una estación «sólo pantalla» salió en papel de respaldo (3.4; desde la 3.5, cuando ninguna pantalla acusó), y
`BUMP` la pantalla de cocina al tocar LISTO sin red (3.5, cierra el folio entero). Siempre ACK: nunca cuarentena ni
bloquea intents de dinero.

Agregar uno = tocar server + Android + iOS + el MCP `pos_sync_status`, en el
MISMO cambio. Un tipo que el server no conoce se rechaza con
`UNKNOWN_INTENT_TYPE` (no se pierde, cae en cuarentena).

### 2.2 Tres estados de ack, y el del medio es el que la gente olvida

- `ACKED` — aplicado, terminal.
- `REJECTED` — rechazo de NEGOCIO permanente → **cuarentena visible**.
- `RETRY` — condición TRANSITORIA (hoy sólo `VERSION_CONFLICT`): el cliente lo
  deja PENDING y **corta el batch** para preservar el FIFO. NO se persiste.

Si conviertes un error transitorio en `REJECTED`, el intent se pierde para
siempre. Ese fue un P1 real.

### 2.3 "Offline es estado normal, no error"

Un fallo de RED se convierte en intent (`orQueueOffline` / `queueOfflineOrRethrow`).
Un rechazo de NEGOCIO (403/409/4xx) se propaga tal cual al usuario. Confundirlos
es el bug clásico: o tragas errores reales, o le dices "error" a algo que salió
bien.

Corolario que costó un bug real: **jamás pintes un éxito encolado como pantalla
de Error.** Ver `avoqado-tpv` (`AngelPayPaymentViewModel.handleRecordFailure`),
que sigue teniendo ese defecto.

### 2.4 Identidad local: `localOrderId` y `externalId`

Un dispositivo sin red no conoce ids del server. Genera UUIDs locales:

- **`localOrderId`** — la orden. `OPEN_TABLE` lo mapea al id real, y los intents
  posteriores lo resuelven vía el mapa del batch o `PosSyncIntent.localRef`.
- **`externalId`** — las LÍNEAS. `ADD_ITEMS` inyecta `sync:<intentId>:<idx>`, que
  es determinista y por tanto el cliente lo puede predecir. Es lo que permite
  separar un cheque que aún no sincroniza.

El `deviceId` se reusa del outbox. **No inventes otro**: si cambia al reiniciar,
el mismo POS se ve como dos peers y la elección de árbitro deja de ser estable.

### 2.5 Dinero: idempotencia y todo-o-nada

- `PAY_CASH` viaja con `idempotencyKey` (= id del intent). El server deduplica
  por `[venueId, idempotencyKey]`. Sin esto, un reintento cobra dos veces.
- `ADD_ITEMS` usa CAS real sobre `version` (no incremento ciego) + `externalId`.
- `SPLIT_ORDER` resuelve las referencias **todo-o-nada**: si UNA no resuelve,
  rechaza. Un cheque partido a medias cobra de menos a un cliente y de más a
  otro, y el mesero no tiene cómo notarlo.
- **Rendirse rápido ES el diseño en la ruta del dinero** (crear orden, cobrar
  efectivo, fast): timeouts cortos (5 s conectar / 15 s total) y a la cola. El
  default (30 s Android / 60 s iOS) congelaba al cajero con fila cuando el WiFi
  seguía "bien" pero sin internet. NO aplicar al resto: la terminal espera 310 s
  a propósito (alguien tiene que pasar la tarjeta) y el catálogo en red lenta sí
  tarda y no tiene cola que lo salve.
- La creación EN LÍNEA también viaja con `externalId` (Android
  `sessionIdempotencyKey()`, iOS `sessionOrderExternalId()`): si el intento
  lento SÍ aterrizó, el reintento — vivo o del outbox — deduplica en vez de
  duplicar la orden. La llave es POR VENTA: se limpia al crear, al encolar y en
  reset, porque reusarla entre dos ventas devolvería la orden de la anterior.

---

## 3. Hub LAN (PREMIUM `OFFLINE_LAN_HUB`)

Código: `core/data/lan/` (Android) · `Services/LAN/` (iOS). **UN transporte por aparato** (`TransporteLan`, KDS 3.5):
UN `ServerSocket`, UN anuncio `_avoqado-pos._tcp` y UN buscador. Rutea cada línea por `op` (`EnrutadorLan`):
`acquire|renew|release|list` al hub de mesas (sólo si está encendido en ESE aparato) y `comanda` al receptor de cocina
(sólo con un Tablero a la vista); lo demás, «Operación desconocida». TXT: `did`, `wired`, `boot`, `venue`, `kds=<estaciones
en pantalla>`, `hub=1|0` — un aparato con `hub=0` no entra a la elección del árbitro. Líneas de ≤ 64 KiB con plazo de 3 s
para la línea entera (`LineaAcotada`). Las capas del hub de mesas:

1. **Núcleo** (`TableLease`, `LeaseRegistry`, `ArbiterElection`) — lógica PURA,
   sin red y con el reloj por parámetro. Toda la corrección vive aquí.
2. **Protocolo** (`LeaseProtocol`) — JSON por línea sobre TCP crudo.
3. **Transporte + descubrimiento** (`TransporteLan` es dueño del socket y del anuncio;
   `LanDiscovery`, `LeaseClient`; `LeaseServer` sólo contesta líneas).
4. **Coordinador + wiring** (`LanHubCoordinator`, `LanHubService`): consume los
   peers del transporte y registra sus ops en él; no abre nada propio.

### 3.1 Lo que hay que entender antes de tocarlo

- **LEASE, no candado.** Un candado sin caducidad deja la mesa muerta si la
  tablet se apaga. TTL 30s, renovación a 1/3.
- **ÉPOCA (fencing token).** Sube SIEMPRE — ni al caducar ni al soltar la mesa se
  reinicia. Si la reinicias, un dispositivo zombi con la época vieja vuelve a
  parecer válido y pisa el trabajo de otro.
- **Elección determinista:** cableado > mayor uptime > `deviceId`. El desempate
  por deviceId NO es cosmético: sin él, dos equipos idénticos pueden elegirse
  distinto y habría DOS árbitros.
- **El árbitro NO es fuente de verdad.** Lo sigue siendo el server. Si el árbitro
  se equivoca, el server rechaza al reconectar y cae en cuarentena. Por eso NO
  hace falta consenso tipo Raft.
- 🔴 **DEGRADAR, NUNCA BLOQUEAR.** Sin hub, con el árbitro caído, con permiso de
  red denegado o con error de protocolo → `NoHub` y el POS sigue como isla. El
  hub PREVIENE conflictos, no autoriza ventas: **jamás puede impedir un cobro.**

### 3.2 Asimetría de cable Kotlin ↔ Swift (real, silenciosa)

- Kotlin serializa con `encodeDefaults = true` → manda `"lease":null` EXPLÍCITO.
- Swift (`JSONEncoder`) **omite** los opcionales nil.

Ambos lados tienen un test con el JSON literal que produce el otro. Si cambias
`decodeIfPresent` por `decode`, o quitas un default, truena en el test — no en un
salón con un iPad y una tablet.

### 3.3 Trampas de plataforma

- **Android — MulticastLock:** sin él, muchos equipos tiran el multicast al
  apagarse la pantalla. "Funciona en el escritorio y falla en el salón".
- **Android — resolves EN SERIE:** `resolveService` revienta con
  `FAILURE_ALREADY_ACTIVE` si hay otro en curso.
- **iOS — `NSBonjourServices`:** `_avoqado-pos._tcp` DEBE estar en el Info.plist.
  iOS no permite descubrir un servicio no declarado y **falla en silencio**.
- **iOS — permiso de red local:** en dispositivo real sale un diálogo que alguien
  tiene que aceptar. En simulador no aparece.
- **Filtro por venue en el TXT:** plazas y food courts comparten WiFi. Un peer de
  otro negocio se ignora; arbitrar mesas ajenas sería catastrófico y silencioso.
- El propio anuncio se filtra por `deviceId`, **no por nombre** (el SO renombra a
  "(2)" si hay colisión).

### 3.4 Comandas a la pantalla de cocina por el WiFi del local (KDS etapa 3, fase 3.5)

No depende del hub Premium: viaja por el MISMO transporte. Espejo en iOS con los mismos umbrales y textos.

- **La caja** (`ComandaDispatcher` + `printing/data/EntregaPorWifi`): si quien llama reparte por pantalla (mostrador y
  rondas; Uber, vales y reimpresiones no) empuja cada plan con pantalla a TODAS las pantallas que anuncian esa estación,
  en paralelo (conectar ≤ 1 s, total ≤ 1.5 s). 🔴 El `kds=` de una pantalla CAMBIA (sólo lo anuncia con el Tablero a
  la vista) y Android NSD **no avisa** un cambio de TXT de un servicio ya conocido (QA 29-sep: la caja se quedaba con
  `kds=[]` y todo salía en papel hasta reiniciarla). Por eso `LanDiscovery.refrescar()` re-resuelve los conocidos en cada
  revisión (60 s) y cuando una entrega no encuentra pantalla (espera ≤ 1 s dentro del presupuesto) — SÓLO en Android 14+:
  abajo no se puede cancelar un resolve colgado y un aparato ido trabaría el resolver del sistema. El anuncio propio no
  cuenta como conocido. Acuse estricto `{"v":1,"status":"ok","sourceKey":<el mismo>}`; cualquier
  otra cosa = sin acuse. El acuse decide el papel de las «sólo pantalla»: sin acuse ⇒ papel de respaldo + marca
  `FALLBACK_PRINTED`, con o sin internet. «Impresora + pantalla» imprime siempre.
- 🔴 **El papel de UN tiempo no marca la ronda (Codex 3.6 #1):** la marca esconde el folio ENTERO y los tiempos lo
  comparten. Si el folio va en más de un tiempo (se cuenta con las entregas guardadas de antemano), su papel sale SIN marca;
  sin guardado previo y con más de un tiempo, ninguno marca; el replay no marca filas `round:`. Costo aceptado: ese tiempo
  se ve en papel Y en la pantalla (duplicado, nunca pérdida). Mostrador (`sale:`) y ronda de un tiempo marcan como siempre.
- **Se guarda ANTES de tocar la red:** cada entrega «sólo pantalla» va a `entregas_kds_pendientes` (una fila por plan:
  `<folio>|<orderItemIds>`). Una fila se borra sólo cuando su papel se DECIDIÓ (acusó, salió, o la estación no tiene
  impresora). Si el despacho truena o se cancela —también a media entrega—, sus filas se SUELTAN (`soltadaEnMillis`, bajo
  `NonCancellable`).
- **Replay** (`ReplayDeEntregasKds`, al abrir y cada 60 s): toma filas de un proceso muerto o ya soltadas; una fila de
  este proceso sin soltar es de un despacho vivo y no se toca. < 10 min ⇒ se reempuja (espera ≤ 3 s a que aparezca la
  pantalla); el resto, o sin acuse ⇒ papel + marca. Revalida la sucursal por fila; > 8 h se descarta con log.
- **La pantalla** (`kds/data/ReceptorDeComandas` + `KdsTicketsLocalesStore`): el receptor vive SÓLO con el Tablero a la
  vista (se apaga en `ON_STOP`, al cambiar de estación y si la observación de lo guardado se cae). Guarda en
  `kds_tickets_locales` ANTES de acusar, y acusa sólo si el mismo receptor sigue enganchado con esa estación (la
  GENERACIÓN del transporte sube al soltar el receptor, al cambiar de estación y al reiniciar el transporte). El mismo
  folio UNE renglones por id; un curso con renglones NUEVOS sobre una fila ya LISTA no se guarda ni se acusa ⇒ papel.
- **La mesa y los tiempos (3.6):** una ronda manda a la pantalla `orderType` = «Mesa 8» (no el «Mesa 8 · Aperitivos» del
  papel, que sigue igual) y el tiempo en cada renglón (`course`); el servidor devuelve lo mismo (`tableNumber` y
  `items[].course`, leídos de la cuenta al consultar: si la mueven de mesa, la cocina ve la nueva). La tarjeta agrupa
  por tiempo: «Inmediato» (sin tiempo) primero y los demás en orden de aparición; sin ningún tiempo se ve como siempre.
- **Mezcla por folio** (`juntarPorFolio`): la copia del servidor gana; una local sin copia se ve como `lan:<folio>`; un
  folio LISTO local esconde la copia del servidor hasta 12 h (o hasta que el servidor deje de mandarlo).
- **Foto del servidor al reabrir sin red (Codex 3.6 #2):** cada lectura buena guarda la lista en el aparato (`KdsPrefs`, por
  sucursal + estación, con su hora). Si la lectura falla y este ViewModel aún no tiene nada de la estación (tablet
  reiniciada), el tablero arranca con esa foto si tiene < 12 h: lo que la copia local ya retiró no desaparece de la cocina.
  Lo terminado en línea sale de la foto; el retiro de la copia local no cambió.
- **LISTO sin red:** `KDS_TICKET_MARK BUMP` por la cola PRIMERO y DESPUÉS `listaEnMillis`, en UNA transacción del DAO
  (`marcarListaOCrear`: marca la fila pendiente o, si no hay ninguna, inserta la sombra LISTA; nunca reemplaza una fila).
  La sombra va con la estación del TABLERO donde se tocó; el BUMP, con la de la comanda.
- **Banda de la caja:** «Las comandas de Barra salen en papel si la pantalla no contesta»; tras 3 entregas seguidas sin
  acuse a la misma estación, fija «La pantalla de Barra no se alcanza por el WiFi» (la limpia el primer acuse). Nada de
  eso en la tablet que ES la pantalla. Pantalla sin internet con receptor vivo: «Sin internet: recibiendo por el WiFi del
  local».

🔴 **Invariante: la fila local de un folio ⊆ la copia del servidor de ese folio.** El folio (`round:<roundKey>:<estación>`
o `sale:<externalId>:<estación>`) lo arma el servidor DE UNA VEZ con todos sus renglones, y la caja empuja exactamente los
renglones que ruteó de esa misma ronda o venta. Por eso marcar LISTO el folio entero es correcto (esconde lo mismo que la
cocina tocó), y NO hay que comparar renglones entre la copia local y la del servidor (son dos espacios de ids). Sólo una
config de ruteo distinta en caja y servidor la rompe, y ese renglón aparece en la estación a la que el servidor lo mandó.

🔴 **Regla de los cursos:** las entregas de TODOS los cursos de una ronda quedan en disco ANTES de que el primero toque la
red (`despacharEnFondo` refresca la config una vez, rutea todos y los guarda; cada curso se despacha sin volver a
refrescar). Si el proceso muere entre dos cursos, el replay empuja o imprime el que faltaba. Sin esto el curso 2 no existía
en ningún lado, y un LISTO sin red sobre el curso 1 cerraba el folio entero en el servidor: pérdida, no duplicado.

---

## 4. Impresión offline (varios bugs reales)

`PrintConfigRepository` **debe ser cache-first y NUNCA borrar una config buena en
un refresh fallido.**

Antes lo hacía: al fallar, la pisaba con `PrintConfig()` vacío → cero estaciones
→ cero ruteo → **la comanda no se imprimía sin internet**, aunque las impresoras
estuvieran en la misma LAN. Y peor: un bache de WiFi a media comida dejaba al
local sin imprimir hasta reiniciar la app.

El razonamiento original ("fail-safe over stale") es equivocado en este dominio:
el "fail-safe" era **no imprimir nada**, o sea que la cocina nunca se entera del
pedido. Una IP de impresora ligeramente vieja es muchísimo menos dañina.

> **Consecuencia operativa:** un POS recién instalado que NUNCA se conectó con
> internet no tiene config de impresoras y no imprimirá offline. Hay que abrir la
> app con internet una vez. Está en `docs/INSTALACION-HUB-LAN.md`.

---

### 4.1 Tres trampas más, encontradas tocando el hardware (2026-07-28)

**a) Sin estaciones configuradas NO se imprimía NADA.** `printRoundComandas`
exigía al menos una estación activa antes de intentar. Un local sin estaciones,
o un POS recién instalado que nunca pudo bajarlas, se quedaba sin comandas
**aunque tuviera su impresora de cocina conectada y con rol**. Contradecía al
propio motor, que es fail-open ("SIN ESTACIÓN") y cae a la impresora KITCHEN
local. El guard impedía llegar ahí. Nunca vuelvas a poner un guard de
configuración delante de la impresión: en este dominio el fail-safe no puede ser
no imprimir.

**b) Los resolves de mDNS deben ir EN SERIE.** `NsdManager.resolveService` mata
todo lo que no sea el primero con `FAILURE_ALREADY_ACTIVE` (3), y el fallo es
SILENCIOSO: la impresora simplemente no aparece. Medido: de 4 anuncios, 3
fallaban y sólo 1 llegaba a la lista. Un local con cocina + barra + caja vería
UNA. Es el MISMO tropiezo que el hub LAN — el fix nunca se había portado al
descubrimiento de impresoras. Deduplica por DIRECCIÓN: la misma impresora se
anuncia en `_printer`, `_pdl-datastream` e `_ipp`.

**c) La impresora fantasma entra por DOS puertas.** Sunmi preinstala el servicio
AIDL en toda su gama Y anuncia la interna por Bluetooth como "InnerPrinter". En
una T3 Pro (sin cabezal) ambas mienten. Cerrar sólo la puerta AIDL no basta —
por BT ya estaba configurada como impresora de recibos diciendo "Conectada".
Detalle: `updatePrinterState()` devuelve **505** ("no printer detected") con
modal/serial vacíos; `printerPaper` devuelve 1 igual, o sea que MIENTE.

**Alta manual de impresora por IP: YA EXISTE en las dos** (ios `62dc231`,
android `c97ae22`). Está al final del buscador de impresoras, bajo "¿No aparece
tu impresora?". Es la salida cuando no se anuncia por mDNS —VLAN, aislamiento
de cliente en el WiFi, IP fija sin anuncio—, que antes dejaba al local sin
comandas con la impresora encendida y en la misma red.

🔴 **Prueba la conexión ANTES de guardar, y si falla no guarda nada.** Guardar
sin probar deja una entrada que dice "Conectada" y nunca imprime — el mismo
daño que la impresora fantasma de las Sunmi. No lo quites.

**El permiso de "dispositivos cercanos"** (Android 13+) es obligatorio para
descubrir impresoras: si alguien lo rechaza en la instalación, la lista sale
vacía y —sin alta manual— el local se queda sin imprimir.


## 5. Qué es online-only A PROPÓSITO

No "se nos olvidó": quitar descuento/cargo ya aplicado, cortesía de UN item ya
enviado, canje de lealtad, pago con TARJETA (Blumon necesita red), turnos,
login/logout.

---

## 6. Cómo probar esto de verdad

La lógica pura tiene tests; lo demás necesita hardware. Recetas que funcionan:

**Simular "sin internet" con la LAN viva** (lo que pasa en un apagón real):

```bash
./gradlew assembleDebug -Pavoqado.devBaseUrl=http://<ip-del-mac>:3009/api/v1
# nada escuchando en 3009 → la API falla, el WiFi/LAN sigue vivo
```

**Intermitencia** (la prueba de fuego de la idempotencia): `flaky-proxy.mjs` con
modo `DROP_RESPONSE` — reenvía el request (el efecto SÍ ocurre) pero mata la
respuesta, así que el cliente reintenta. Es el escenario del doble-cobro.

**Peer LAN falso** para probar el hub sin un segundo dispositivo:

```bash
dns-sd -R "Avoqado-POS-fake" "_avoqado-pos._tcp" local 9911 \
    did=fake-device wired=1 boot=1000 venue=<venueId>
```

**Impresora ESC/POS falsa:** escuchar en `:9100` y decodificar lo que llega.
Verifica todo el camino menos el papel.

**adb inalámbrico:** si el daemon se reinicia se pierde el dispositivo. Se
recupera con `adb mdns services` (da el puerto real, **cambia cada vez**) y
`adb connect <ip>:<puerto>`. El 5555 NO sirve en Android 11+.

⚠️ Al probar leases a mano: el TTL es 30s. Entre tomar la mesa desde un peer y
tocar en la tablet tienen que pasar MENOS de 30s, o el lease caduca y parece un
bug que no lo es.

---

## 7. Estado y límites honestos (2026-09-29)

**Funciona y está verificado en hardware:** abrir mesa, rondas, efectivo,
descuentos, cargos, cortesías, mover/asignar/anular, liberar, separar y fusionar
cheques, dividir por puesto — todo sin red. Hub LAN previniendo el doble-abre.
Comanda imprimiendo offline **verificada contra un receptor ESC/POS real**, sin
red y sin config de estaciones (2026-07-28). Cuarentena visible para rechazos.

**KDS por el WiFi del local (3.5, §3.4): construido y con pruebas unitarias (2026-09-29); falta el QA en aparato apagando
la red** (Home con comanda empujada, SIM + WiFi sin internet, pantalla en segundo plano, cambio de estación a
media entrega, WiFi que aísla aparatos). Límites declarados: con un WiFi que aísla aparatos todo lo «sólo pantalla» sale
en papel (sin perder nada, y la caja lo dice); los planes «Sin estación» no se empujan (el servidor los pone en todas las
pantallas); cambiar de sucursal sin red conserva la config anterior (`switchVenue` es una llamada al servidor); lo
marcado LISTO sin red no aparece en «Recientes» y «Deshacer» sigue sólo en línea.

**NO está hecho:**
- **Fencing del lado del server** (rechazar intents con época vieja): necesita
  persistir `lastLeaseEpoch` por mesa. Sin eso el sistema ya es seguro
  (ADD_ITEMS fusiona, PAY_CASH es idempotente, la propiedad de mesa rechaza
  cruces), pero es la capa que falta.
- Indicador visual de árbitro/isla en el plano.
- El fix de impresión offline de **iOS** se hizo por paridad y lectura de código:
  **no se ha probado con un iPad y una impresora físicos.**
- ~~Alta manual de impresora por IP~~ — HECHO 2026-08-04 en ambas.

**No vender:** "servicio completo offline multi-terminal" hasta que el QA en aparato
de la 3.5 pase.

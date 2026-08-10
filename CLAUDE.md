# CLAUDE.md - Avoqado Android

This file provides guidance to Claude Code when working with this repository.

> **Reglas de entorno** — sesiones de IA en paralelo, y cuándo verificar según la carga de la
> máquina — están en el `CLAUDE.md` del workspace (`../CLAUDE.md`), que auto-carga junto con este
> archivo. Léelas antes de correr builds/tests o de tocar git.

## 🔴 CRITICAL — Android e iOS se cambian JUNTOS (paridad obligatoria)

**Todo cambio de producto que hagas aquí debe portarse a `avoqado-ios` en el MISMO
trabajo — nunca "después".** Android e iOS son la misma app en dos plataformas: si una
queda atrás, el founder ve comportamientos distintos según el dispositivo y hay que
re-descubrir el diseño meses después.

- Aplica a: features nuevas, cambios de UX/flujo, fixes de bugs (sobre todo de dinero),
  estados de carga/guards, textos visibles, y contratos con el server.
- El port no es opcional ni un TODO: si el cambio no está en ambos, el trabajo está
  incompleto. Deja el iOS compilando (`xcodebuild ... build`) antes de darlo por hecho.
- Si por algo NO se puede portar en el momento (worktree bloqueado, falta hardware,
  decisión pendiente), **dilo explícitamente en el reporte y anótalo en memoria** con
  qué falta exactamente — no lo dejes silencioso.
- Excepción: cosas genuinamente específicas de plataforma (permisos de Android, MFi/
  ExternalAccessory de iOS, layouts propios de cada SO). Aun así, el COMPORTAMIENTO
  que ve el usuario debe ser equivalente.
- Espejo exacto: usa los mismos nombres de campos, textos en español y semántica que
  el otro repo. Los códigos de feature/permiso se replican por nombre EXACTO.

## 🔴 CRITICAL — Ask which payment tier (and how it gets turned on) BEFORE building or changing anything

Avoqado is a tier-gated SaaS (**FREE · PRO · PREMIUM · ENTERPRISE**). Whenever you add a new
feature, modify existing behavior, or expose a new capability, **STOP and ask the founder which
paid tier it falls under** — then wire the gating to match. A change shipped without a tier
decision is unfinished: it either leaks paid value into a lower tier or hides a free capability
behind a paywall.

- **Backend (authoritative):** `avoqado-server/src/services/access/basePlan.service.ts` +
  `avoqado-server/src/middlewares/checkFeatureAccess.middleware.ts`. Obligatory gating questions:
  `avoqado-server/.claude/rules/feature-gating.md`. PREMIUM-only codes today: `CFDI`, `INVENTORY_TRACKING`.
- **Dashboard display/CTA map:** `avoqado-web-dashboard/src/config/plan-catalog.ts`
  (`TierId`, `PLAN_TIERS`, `getTierForFeature()` → FeatureGate upsell).
- **Enforcement status:** ✅ only **avoqado-web-dashboard** enforces tiers today.
  ⚠️ **avoqado-android** (THIS repo) and **avoqado-ios** have NO tier gating yet — **start adding it
  now**, mirroring the backend feature codes by exact name. Treat tier codes like permissions:
  mirrored across backend + every client by exact name — a mismatch fails silently.
- **Activación (regla completa en `../CLAUDE.md`):** esta app **lee** el estado del switch; solo se
  construye aquí si se toca durante el turno desde el piso, y entonces va en Android **e** iOS en el mismo
  trabajo. Apagado se ve y se explica, nunca desaparece en silencio.

## UI/UX Rules (BLOCKING — read before ANY UI work)

**Before creating or modifying ANY Composable, you MUST:**

1. **Read the Design System** — `designsystem/` package contains theme, colors, typography, and reusable components. Use these instead of hardcoded values. This is the primary reference for ALL UI work.
2. **Check `../square-ui-reference/`** — 174 screenshots + notes from Square POS (v6.99sw) on iPad. Use as design baseline when implementing features that Square already has on iPad.
3. **Live Square comparison via physical Android device** — when Square has the feature on Android (reservations, appointments, etc.) and the iPad screenshots don't cover it, connect a physical device running the Square Android app: `adb devices` shows emulator + device; target the device with `adb -s <device-serial> shell screencap -p /sdcard/sq.png` and `adb -s <device-serial> shell uiautomator dump` to capture the live reference, then compare against the Avoqado Composable.
4. **Reference iOS ONLY if iOS has the feature** — `../avoqado-ios/ui-patterns-ios.md` has mandatory component patterns for shared features. As of v2.3.x, Android has modules iOS doesn't (reservations, etc.). Don't open iOS to look for something that isn't there — verify the feature exists in `../avoqado-ios/POS/` first.

**If you skip these steps and create inconsistent UI, it will need to be redone.**

### Quick reference — mandatory patterns:
| Need | Use | Never |
|------|-----|-------|
| Back/dismiss button | Circle with chevron (match iOS `CircleBackButton`) | Plain text "Back" or raw Icon |
| Primary button | `PrimaryButton` composable or `RoundedCornerShape(50)` | `RoundedCornerShape(12.dp)` |
| Acción primaria en pantallas chicas | En formularios/full-screen modal: `Guardar/Crear` en header (derecha) y cierre circular `X` a la izquierda | Botón fijo abajo que se recorta/tapa contenido |
| Header fullscreen modal | `AvoqadoFullscreenHeader` — botón circular izquierda (`X` para entrada / cerrar flujo, `←` para pasos 2-N de wizard multi-paso) + título centrado + acción pill o icon derecha; acción invertida por tema (`dark`: fondo blanco/texto negro, `light`: fondo negro/texto blanco) | Header sin simetría, título a un lado, o `TopAppBar` crudo de Material3 |
| Spacing | `Spacing.md`, `Spacing.lg` from design tokens | Hardcoded `12.dp`, `16.dp` |
| Colors | `MaterialTheme.colorScheme.*` | Hardcoded `Color.Black`, `Color.White` |
| Typography | `MaterialTheme.typography.*` | `fontSize = 14.sp` inline |
| Confirmation / input dialog | `AvoqadoDialog` (X top-right, pill input, full-width primary) | Raw Material3 `AlertDialog` |
| Form input inside dialog | `AvoqadoPillTextField` (48dp, rounded 50) | Raw `OutlinedTextField` |
| Phone input (international) | `AvoqadoPhoneInput` (flag + dial code selector + pill digits) | Plain text field asking for "+52 …" |
| Success feedback | `AvoqadoSuccessToast` (auto-dismiss green check overlay) | Silent state / native `Toast.makeText` / snackbar |
| Text search field | `SearchPillField` (44dp, leading search icon) | Raw `TextField` with border |

### `AvoqadoPhoneInput` — composing E.164
The component emits raw digits only — caller composes `"+${country.dialCode}${digits}"` before
hitting the API. Default country is MX; override via `Countries.byIso(venue.country)` when
venue data is available. Backend `normalizePhone` already strips the leading `+`, so sending the
fully-qualified E.164 string from the client avoids the backend's Mexico-fallback ambiguity.

### `AvoqadoSuccessToast` — when to fire it
Any user-initiated action that *succeeds silently today* should celebrate with this toast.
Message = what the user did, in Spanish (e.g. "¡Cliente guardado!", "¡Stock recibido!", "¡Caja cerrada!").
Use `subtitle` for a secondary detail only (channel, venue, amount).

Fire it after: save customer/product/discount/coupon/category/modifier/credit-pack, close cash drawer,
connect printer, change PIN, time clock in/out, send receipt (email/WhatsApp/print), change venue,
create purchase order, receive stock, transfer inventory, issue refund, sync offline queue.

Do **not** fire for: validation errors, network failures, or any non-success state — toast is
green-check celebration only. Errors stay inline in the originating dialog/sheet.

## 🔴 Offline-first y Hub LAN — LEE ESTO antes de tocar sync, mesas o impresión

Reglas completas: **`.claude/rules/offline-first-y-hub-lan.md`**
Guía de instalación en un local: **`docs/INSTALACION-HUB-LAN.md`**

Lo mínimo que tienes que saber:

- Los 14 tipos de intent se espejan por nombre EXACTO entre server, Android e
  iOS. Agregar uno = tocar los tres + el MCP `pos_sync_status`, en el MISMO cambio.
- Hay TRES estados de ack: `ACKED`, `REJECTED` (permanente → cuarentena) y
  `RETRY` (transitorio → el cliente reintenta). Convertir un transitorio en
  REJECTED pierde el intent para siempre.
- Un fallo de RED se convierte en intent; un rechazo de NEGOCIO se propaga tal
  cual. Y **nunca pintes un éxito encolado como pantalla de Error.**
- El hub LAN (PREMIUM `OFFLINE_LAN_HUB`) PREVIENE conflictos, **no autoriza
  ventas**: si no está disponible se degrada a modo isla y jamás bloquea un cobro.
- `PrintConfigRepository` es cache-first: un refresh fallido NUNCA debe borrar la
  config buena, o el local deja de imprimir comandas.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Clean and build
./gradlew clean assembleDebug

# Run on connected device/emulator
./gradlew installDebug
```

## Release / Production Build

When the user asks to build for production, release, or to upload to Google Play:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`
2. Run `./gradlew bundleRelease` (AAB) and `./gradlew assembleRelease` (APK)
3. Create a folder with the version name and copy both artifacts:
   ```
   /Users/amieva/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/Releases/avoqado-android/<versionName>/
   ```
4. Copy `app-release.aab` and `app-release.apk` into that folder
5. Create a `CAPTION.md` in that same folder with:
   - **Nombre de la versión**: `<versionCode> (<versionName>)`
   - **Notas de la versión**: wrapped in `<es-419>...</es-419>` tags, with a bullet list summarizing the changes in this release (in Spanish). Ready to copy-paste into Google Play Console.

### Deploy a Producción (Google Play) — todo por CLI

Cuando el usuario pida "deploy a producción" / "sube a Play Store": los pasos 1-5 de arriba
(bump, bundleRelease/assembleRelease con **JDK 17** vía `JAVA_HOME=$(/usr/libexec/java_home -v 17)`,
carpeta de iCloud + CAPTION.md) siguen aplicando como archivo/ceremonia local, y además:

6. **Subida y publicación por API**: 
   ```
   scripts/play_release.py app/build/outputs/bundle/release/app-release.aab <versionCode> --notes notas.txt
   ```
   Sube el AAB al track `production` con notas `es-419` y confirma el edit — Google lo pasa a su
   revisión (horas). Credenciales: la cuenta de servicio de Firebase
   (`avoqado-server/firebase-service-account.json`), invitada en Play Console con permisos de
   Versiones desde 2026-07. Si responde 403, revisar Play Console → Usuarios y permisos.
7. Igual que iOS: si la versión depende de endpoints nuevos del backend, ese deploy va primero.

## Architecture Overview

Avoqado Android is a **Point of Sale (POS) system** for Android tablets and phones, ported from the iOS app (`avoqado-ios`). Built with Kotlin + Jetpack Compose + Material3.

**Package:** `com.avoqado.pos`

### MVVM Architecture

```
UI Layer (Jetpack Compose @Composable)
         |
ViewModel Layer (@HiltViewModel with StateFlow)
         |
Repository/Service Layer (Retrofit, OkHttp, auth)
         |
Data Layer (Models, EncryptedSharedPreferences, BLE)
```

### Dependency Injection: Hilt

- `@HiltAndroidApp` on `AvoqadoApp`
- `@AndroidEntryPoint` on `MainActivity`
- `@HiltViewModel` on all ViewModels
- `@Singleton` on repositories
- Modules: `NetworkModule`, `StorageModule`

### Key Dependencies

| Library | Purpose |
|---------|---------|
| Compose BOM 2025.01.01 | UI framework |
| Material3 | Theming, components |
| Navigation Compose | Tab navigation |
| Hilt 2.54 | Dependency injection |
| Retrofit 2.11.0 | HTTP client |
| OkHttp 4.12.0 | Interceptors, auth |
| kotlinx-serialization | JSON parsing |
| security-crypto | EncryptedSharedPreferences |
| Coil 2.7.0 | Image loading |
| material-icons-extended | Full icon set |
| WindowSizeClass | Tablet detection |

## Module Structure (83 files)

| Directory | Purpose | Key Files |
|-----------|---------|-----------|
| `auth/` | Authentication, login, biometrics | AuthRepository, SignInFlowScreen, BiometricAuthManager |
| `core/` | Network, storage, DI, utilities | ApiService, SecureStorage, AuthInterceptor, NetworkModule |
| `designsystem/` | Theme, colors, typography, components | AvoqadoTheme, Color, PrimaryButton, AuthButton |
| `inventory/` | Stock management | InventoryScreen, InventoryViewModel |
| `navigation/` | Bottom tabs, NavHost | AvoqadoNavGraph, MainTab (5 tabs) |
| `notifications/` | In-app notifications | NotificationsScreen |
| `payment/` | Payment flow state machine | PaymentFlowViewModel, PaymentFlowScreen |
| `pos/` | **Core POS** - cart, products, checkout | CheckoutScreen, CartViewModel, ShortcutsGridView |
| `printing/` | Bluetooth receipt printing | PrinterService |
| `push/` | Firebase push notifications | PushNotificationManager |
| `settings/` | More menu, hardware settings | MoreMenuScreen |
| `timeclock/` | Employee clock in/out with PIN | TimeClockSheet, PinPadView |
| `tpvsettings/` | TPV terminal configuration | TpvSettingsRepository |
| `transactions/` | Transaction history | TransactionsScreen, TransactionDetailSheet |

## Checkout Screen Architecture

The checkout screen is the main POS screen with adaptive layout:

### Tablet (iPad-style): 50/50 split
```
[Left Panel - Input]        | [Right Panel - Cart]
  SearchBar                 |   CustomerHeader ("Agregar cliente")
  TabSelector               |   Cart Items ("En tienda" section)
  TabContent:               |   Discounts
    - Teclado (keypad)      |   "Guardar carrito" + "Cobrar" buttons
    - Shortcuts (grid)      |
    - Todos los productos   |
```

### Phone (iPhone-style): Full screen
```
SearchBar
TabSelector
TabContent (fills remaining space)
Bottom Cart Bar (black, when items in cart)
```

### Three Input Tabs
1. **Teclado** - `NumericKeypadView`: Amount display ($0.00), "+ Nota" button, 4x3 keypad grid
2. **Shortcuts** - `ShortcutsGridView`: 2-column colored tile grid with sub-views (discounts, coupons, void items, cortesia, pay later, saved carts)
3. **Todos los productos** - `ProductGridView`: Category filter chips + product grid with images/initials

### Tab Selector Implementation Note
Uses `Modifier.width(IntrinsicSize.Max)` on each tab Column to constrain width to text content. Without this, `fillMaxWidth()` on the underline Box causes the first tab to consume all horizontal space, pushing other tabs offscreen.

## API Configuration

- Production: `https://api.avoqado.io/api/v1`
- Debug: Configurable ngrok tunnel in `ApiConstants.kt`
- Auth: Bearer token via `AuthInterceptor` (OkHttp interceptor)
- Token refresh: `TokenRefreshAuthenticator` handles 401s

**IMPORTANT:** Use `/mobile/` routes, NOT `/dashboard/` routes.

## Design System

### Color Tokens (Color.kt)
- Brand: `AvoqadoPrimaryLight/Dark` (adaptive)
- Surfaces: Maps to iOS `systemBackground`, `secondarySystemBackground`
- Semantic: `Success`, `Warning`, `Error`, `Info`
- Action colors for shortcuts: `ActionGreen`, `ActionOrange`, `ActionPurple`, `ActionRed`, `ActionTeal`, `ActionPink`
- Discount: `DiscountBackground`, `DiscountText`

### Spacing (8pt grid)
`xxs=4, xs=6, sm=8, md=12, lg=16, xl=20, xxl=24, xxxl=32`

### Corner Radii
`xs=2, sm=4, md=8, lg=12, xl=16`

### Design Token Mapping (iOS -> Android)

| iOS (SwiftUI) | Android (Compose) |
|---------------|-------------------|
| `Color(.label)` | `MaterialTheme.colorScheme.onSurface` |
| `Color(.systemBackground)` | `MaterialTheme.colorScheme.surface` |
| `Color(.secondarySystemBackground)` | `MaterialTheme.colorScheme.surfaceVariant` |
| `Color(.systemGray4)` | `MaterialTheme.colorScheme.outlineVariant` |
| `.clipShape(Capsule())` | `RoundedCornerShape(50)` |
| `@Published var` | `MutableStateFlow<T>` |
| `@MainActor class` | `@HiltViewModel class : ViewModel()` |
| `@StateObject` | `hiltViewModel()` |
| `GeometryReader` | `BoxWithConstraints` or `WindowSizeClass` |
| `VStack(spacing:)` | `Column(verticalArrangement = Arrangement.spacedBy())` |
| `HStack(spacing:)` | `Row(horizontalArrangement = Arrangement.spacedBy())` |
| `Spacer()` | `Spacer(modifier = Modifier.weight(1f))` |
| `.sheet()` | `ModalBottomSheet` or custom overlay |

## Code Conventions

- **UI text**: Spanish language
- **Debug logs**: Use `Log.d("TAG", "message")` with emoji tags matching iOS
- **Sections**: Use `// MARK: -` comments for organization
- **State**: Use `StateFlow` + `collectAsState()` in Composables
- **Navigation**: Compose Navigation with `NavHost`
- **Async**: `viewModelScope.launch` for coroutines

## iOS Reference (conditional)

**As of v2.3.x, Android has surpassed iOS feature-wise.** Android-only modules include reservations (calendar/list/detail/wizard/waitlist) and may grow further. iOS is NOT the parity reference for these — the Android Design System and Square (where applicable) are.

iOS source: `/Users/amieva/Documents/Programming/Avoqado/avoqado-ios/`. Consult only when:
1. The feature exists on iOS (verify with a quick `find ../avoqado-ios -iname "*Feature*"` first), AND
2. You are porting it to Android or fixing a parity bug between the two.

When iOS has the feature, the canonical references are:
- `avoqado-ios/POS/Views/CheckoutView.swift` - Main checkout (864 lines)
- `avoqado-ios/POS/Components/ShortcutsGridView.swift` - Shortcuts grid (1575 lines)
- `avoqado-ios/POS/Components/CartPanelView.swift` - Cart panel (791 lines)
- `avoqado-ios/POS/Views/NumericKeypad.swift` - Numeric keypad
- `avoqado-ios/POS/Views/ProductGridView.swift` - Product grid
- `avoqado-ios/DesignSystem/DesignSystem.swift` - Design tokens

For Android-only features, the references in priority order are: (1) internal `designsystem/` package, (2) sibling Avoqado Composables in this app, (3) live Square Android app captured via `adb -s <device-serial>` (see UI/UX Rules section above).

## Known Issues & TODOs

### Checkout Screen
- [ ] Barcode scanner integration (CameraX + ML Kit)
- [ ] Create item sheet (from shortcuts)
- [ ] Cart item detail panel (tap item to edit)
- [ ] Save cart functionality
- [ ] iPhone cart sheet (full screen when tapping cart bar)
- [ ] Cortesia sub-view needs proper cart item update (currently placeholder logic)

### Payment
- [ ] BLE terminal connection (BluetoothService not yet implemented)
- [ ] Terminal payment relay via server
- [ ] Receipt printing via Bluetooth

### General
- [ ] Push notifications (needs google-services.json)
- [ ] Dark mode testing across all screens
- [ ] Edge-to-edge display verification on all screens

## 🔴 CRITICAL — Keep the Avoqado MCP in sync

The Avoqado MCP (`avoqado-server/scripts/mcp/`) is a **first-class interface**: it exposes
the platform's data and actions to AI agents (internal ops today, customer-facing tomorrow).
It must never fall behind the platform.

**Whenever you add or change a feature, Prisma model, service, endpoint, permission, or any
capability the MCP should expose, you MUST add or update the matching MCP tool in
`avoqado-server/scripts/mcp/` as part of the SAME change — never "later".** A capability that
exists but isn't reachable through the MCP is unfinished. Treat the MCP like permissions: kept
in lockstep, never an afterthought.

## 🔴 CRITICAL — Keep the sales presentation in sync

The partner sales presentation (`~/Documents/Programming/Avoqado-HQ/operations/marketing/platform-presentation/`)
is the canonical "what Avoqado does" document — third parties sell from it. It must never fall
behind the platform.

**Whenever you add, change, or remove a customer-visible capability (feature, module, product,
payment method, supported sector, tier packaging), you MUST update BOTH deliverables as part of
the SAME change — never "later":** the full deck (`avoqado-presentacion.html`) AND the one-pager
(`avoqado-one-pager.html`), then regenerate both PDFs following that folder's `README.md`.
Updating only one of the two is an incomplete change. Internal refactors and bugfixes with no
customer-visible impact are exempt.

---

## Fetching Asana task attachments / screenshots

When given an Asana task URL, you **can** see its screenshots and attachments — don't claim you can't.

- `mcp__asana__*` reads task text/comments but **not** files; the `mcp__claude_ai_Asana__` connector is often unauthorized. Don't stop there — use the Asana Personal Access Token directly (it's what powers the `asana` MCP server):
  1. Read the token (use it, **never print or commit the value**): key `ASANA_ACCESS_TOKEN` under `mcpServers.asana.env` in `~/.claude.json`. Example:
     `TOKEN=$(python3 -c "import json,os; print(json.load(open(os.path.expanduser('~/.claude.json')))['mcpServers']['asana']['env']['ASANA_ACCESS_TOKEN'])")`
  2. List attachments + signed URLs (task GID = the long number after `/task/` in the URL):
     `curl -s -H "Authorization: Bearer $TOKEN" "https://app.asana.com/api/1.0/tasks/<GID>/attachments?opt_fields=name,download_url,created_at"`
  3. `curl` each `download_url` (pre-signed, needs no auth) to a temp file in the scratchpad, then Read the image. Inline description images are attachments too, so this returns all of them — not just the ones embedded in the text.
- If slide/screenshot text is unreadable after Read downscales a large image, crop it into regions with PIL and upscale (LANCZOS) before re-reading.

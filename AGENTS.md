# AGENTS.md - Avoqado Android

This file provides guidance to Codex when working with this repository.

## Entorno: varias sesiones de IA trabajan en paralelo (contexto, no un bloqueo)

Casi siempre hay 2+ agentes editando este workspace al mismo tiempo. Es lo normal: **no es una
anomalía, no es motivo para detenerte, preguntar ni "arreglar" nada.** Solo cambia cómo interpretas
lo que ves:

- **Archivos modificados que tú no tocaste** en `git status` / `git diff` = WIP de otra sesión. Normal.
- 🔴 **Nunca** `git reset --hard`, `git checkout .`, `git clean`, `git stash` ni cambies de rama "para
  dejar limpio": el árbol de trabajo es compartido y eso sí destruye trabajo ajeno irrecuperable.
  Es la única regla dura de esta sección.
- **Commitea por rutas explícitas** (`git add <ruta>`), nunca `git add -A` / `git add .`. Si aun así
  se cuela WIP ajeno en tu commit, **no es grave**: no lo reviertas ni lo reescribas — dilo en el reporte.
- **Ruido que no viene de tu cambio**: el dev server hace hot-reload o se reinicia solo, un test/build
  truena en un archivo que no tocaste, un puerto ocupado. Verifica con `git diff <archivo>`: si ese
  cambio no es tuyo, **no lo debuggees ni lo corrijas** — reintenta una vez y, si sigue, anótalo en el
  reporte y continúa con lo tuyo.
- **No mates procesos, servidores, emuladores ni daemons de build que no arrancaste tú**, ni reinicies
  o borres bases de datos locales: otras sesiones están usándolas.
- Si un `Edit` falla porque el archivo cambió debajo de ti, relee y reaplica. Sin drama.
- ¿Quién más está adentro? MCP **Huella**: `quien_trabaja(repo)` y `actividad_reciente(repo)`.

**Asume concurrencia, no conflicto. Sigue programando.**

## Verificar sí; cuánto verificar lo decide la máquina

Esta Mac (10 núcleos / 32 GB) está compartida con las demás sesiones y vive cerca del límite.

**Pasan por el chequeo de capacidad, y SOLO estas:** `./gradlew assemble*` / `bundle*`, `xcodebuild`,
la suite de tests completa, el typecheck de todo el monorepo.
**No pasan nunca — se corren siempre, aunque la máquina esté saturada:** typecheck o build de UN
proyecto, UN archivo de test, lint. Cuestan segundos: la carga NO es excusa para saltárselos.

```bash
sysctl -n hw.ncpu vm.loadavg   # núcleos y { 1min 5min 15min }
sysctl -n vm.swapusage         # 'free' es la señal que más importa
pgrep -fl "GradleDaemon|KotlinCompileDaemon|xcodebuild|jest|vitest|tsc" | head
```

- **Si swap `free` < 2 GB, o load de 1 min > 2× núcleos, o ya hay un build ajeno corriendo: no arranques.**
  Adelanta lo que no dependa de eso y reintenta (cada ~2 min, tope ~10 min). Si sigue saturado, corre la
  verificación corta y reporta la larga como pendiente — no te quedes esperando indefinidamente.
- **Nunca dos builds pesados a la vez**: dos daemons de Kotlin a `-Xmx6g` tumban la máquina.
- Única excepción a "no mates procesos ajenos": si `pgrep` no muestra ningún build activo,
  `./gradlew --stop` libera daemons ociosos (4–6 GB cada uno, viven 2 h sin usarse) — dilo en el reporte.
  Los servidores de dev, emuladores y bases de datos NO se tocan.
- Si el typecheck pelón (`npx tsc --noEmit`) revienta por memoria, usa el script del repo (`npm run build`).

**La carga nunca compra "no lo verifiqué" — compra "lo verifiqué en corto".** Si cambiaste código, se
comprueba antes de decir que está listo. Lo que la máquina decide es el *tamaño*: typecheck solo del
proyecto tocado, el archivo de test en vez de la suite completa, `assembleDebug` en vez de
`assembleRelease`. **Lo que difieras va explícito en el reporte, con el comando exacto para correrlo.**
Un "listo" que esconde lo que no se corrió es un reporte falso.

| Qué tocaste | Mínimo obligatorio |
|---|---|
| Dinero, fechas/timezone, tiers, permisos, stock, pagos/reembolsos, migraciones de datos | **Test primero (TDD)** + suite del módulo. No negociable: esto no se difiere ni con la máquina en llamas. |
| Cualquier otro código | Que compile / typechee el proyecto tocado. Un cambio que no compila no es un cambio. |
| Cambio amplio, o antes de commitear/lanzar | Suite completa + build completo. Aquí sí se espera capacidad. |
| Markdown, docs, comentarios, copy sin lógica | Nada. |

"No era importante" es una conclusión que se justifica en el reporte, no un default. Si dudas, córrelo.

## UI/UX Rules (BLOCKING — read before ANY UI work)

**Before creating or modifying ANY Composable, you MUST:**

1. **Read the Design System** — `designsystem/` package contains theme, colors, typography, and reusable components. Use these instead of hardcoded values.
2. **Check `../square-ui-reference/`** — 174 screenshots + notes from Square POS (v6.99sw) on iPad. Use as design baseline when implementing features that Square already has.
3. **Reference iOS** — `../avoqado-ios/ui-patterns-ios.md` has mandatory component patterns. Match parity with iOS app.

**If you skip these steps and create inconsistent UI, it will need to be redone.**

### Quick reference — mandatory patterns:
| Need | Use | Never |
|------|-----|-------|
| Back/dismiss button | Circle with chevron (match iOS `CircleBackButton`) | Plain text "Back" or raw Icon |
| Primary button | `PrimaryButton` composable or `RoundedCornerShape(50)` | `RoundedCornerShape(12.dp)` |
| Acción primaria en pantallas chicas | En formularios/full-screen modal: `Guardar/Crear` en header (derecha) y cierre circular `X` a la izquierda | Botón fijo abajo que se recorta/tapa contenido |
| Header fullscreen modal | `X` circular izquierda + título centrado + acción pill derecha; acción invertida por tema (`dark`: fondo blanco/texto negro, `light`: fondo negro/texto blanco) | Header sin simetría o con título cargado a un lado |
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

## iOS Reference

The iOS source code is at: `/Users/amieva/Documents/Programming/Avoqado/avoqado-ios/`

When porting features or fixing parity issues, always reference the iOS implementation:
- `avoqado-ios/POS/Views/CheckoutView.swift` - Main checkout (864 lines)
- `avoqado-ios/POS/Components/ShortcutsGridView.swift` - Shortcuts grid (1575 lines)
- `avoqado-ios/POS/Components/CartPanelView.swift` - Cart panel (791 lines)
- `avoqado-ios/POS/Views/NumericKeypad.swift` - Numeric keypad
- `avoqado-ios/POS/Views/ProductGridView.swift` - Product grid
- `avoqado-ios/DesignSystem/DesignSystem.swift` - Design tokens

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

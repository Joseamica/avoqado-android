# CLAUDE.md - Avoqado Android

This file provides guidance to Claude Code when working with this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Clean and build
./gradlew clean assembleDebug

# Run on connected device/emulator
./gradlew installDebug
```

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

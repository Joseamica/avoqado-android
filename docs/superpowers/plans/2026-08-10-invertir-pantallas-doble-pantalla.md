# Invertir pantallas en POS de doble pantalla — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un interruptor por equipo que intercambia qué pantalla ve el cliente y en cuál trabaja el cajero, en POS de doble pantalla con segunda pantalla física (Sunmi D3 y equivalentes).

**Architecture:** Se introduce un único concepto —el *rol* de cada pantalla— resuelto por una función pura (`resolveDisplayRoles`). El modo normal no cambia: el cliente sigue en un `Presentation` sobre la pantalla secundaria. En modo invertido la caja (`MainActivity`) se relanza en la pantalla secundaria física con `ActivityOptions.setLaunchDisplayId`, y el cliente pasa a una `CustomerDisplayActivity` en la pantalla principal — obligatorio, porque `TYPE_PRESENTATION` está prohibido en la pantalla por defecto. El booleano vive en SharedPreferences (autoridad para aplicar) y se sincroniza con `Terminal.customerDisplayInverted` en el server para poder prenderlo desde el dashboard.

**Tech Stack:** Kotlin + Jetpack Compose + Hilt (Android) · Express + Prisma/PostgreSQL (server) · React 18 + Vite (dashboard) · JUnit puro para los tests de lógica.

**Spec:** `docs/superpowers/specs/2026-08-10-invertir-pantallas-doble-pantalla-design.md`

## Global Constraints

- **Tier: FREE.** Default del interruptor: **OFF**. No se agrega gating de tier (Android no lo tiene hoy).
- **Nombre del campo idéntico en los tres repos: `customerDisplayInverted`.** Un desajuste falla en silencio.
- **Nunca quitar ni renombrar un campo de una respuesta de API.** El campo nuevo es **opcional con default `false`** (apps viejas siguen funcionando).
- 🔴 **Un refresh fallido NUNCA borra el valor local.** El valor local aplica siempre; el del server solo se adopta en una respuesta exitosa y sin cambio local pendiente.
- 🔴 **Nunca bloquear una venta.** Si el equipo no permite mover la caja, se degrada y se explica; jamás se impide cobrar.
- **Textos de UI en español**, con acentos correctos.
- **Design system obligatorio:** `AvoqadoDialog` para confirmar, `AvoqadoSuccessToast` para el éxito, `AvoqadoTheme.spacing.*` y `MaterialTheme.typography.*`. Nunca `AlertDialog` crudo, `Toast.makeText`, dp ni sp hardcodeados.
- **Antes de editar el primer archivo de `avoqado-server` o `avoqado-web-dashboard`** hay que leer su `CLAUDE.md`, sus `.claude/rules/*.md` y su `MEMORY.md` (regla del workspace multirepo). Está como paso explícito en las tareas 6 y 8.
- **Cualquier edición de `prisma/schema.prisma` regenera el mapa en el MISMO cambio:** `cd avoqado-server && npm run schema:map`, y `docs/SCHEMA_MAP.md` se commitea junto.
- **Builds de Android con JDK 17:** `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew ...`
- **Otras sesiones de IA trabajan en paralelo en este workspace.** Commitear por rutas explícitas (`git add <ruta>`), nunca `git add -A`. Archivos modificados que no tocaste son WIP ajeno: no los revientes.
- **iOS: no aplica** (no existe pantalla de cliente en `avoqado-ios`). Es la excepción de hardware por plataforma y ya está declarada en el spec.

---

## Estructura de archivos

**Android (`avoqado-android`)**

| Archivo | Responsabilidad |
|---|---|
| `app/src/main/java/com/avoqado/pos/customerdisplay/DisplayRoles.kt` | **Nuevo.** Lógica PURA: `DisplayRoles`, `resolveDisplayRoles`, `shouldRelaunchCashier`, y las constantes/helpers compartidos (`REMOTE_CAPTURE_HINTS`, `displayOwnerPackage`). Cero Android salvo `Display` en el helper. |
| `app/src/main/java/com/avoqado/pos/customerdisplay/DisplayModePrefs.kt` | **Nuevo.** El booleano `inverted` + bandera `dirty` en SharedPreferences. Autoridad para aplicar. |
| `app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayActivity.kt` | **Nuevo.** Hospeda `CustomerDisplayScreen` en la pantalla principal en modo invertido. |
| `app/src/main/java/com/avoqado/pos/customerdisplay/CashierDisplayGuard.kt` | **Nuevo.** Relanza la caja en su pantalla, con anti-bucle. |
| `app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayManager.kt` | Modificar: monta al cliente según el rol (Presentation o Activity). |
| `app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayState.kt` | Modificar: agrega `invertible` e `invertUnsupported`. |
| `app/src/main/java/com/avoqado/pos/MainActivity.kt` | Modificar: llama al guard en `onCreate`. |
| `app/src/main/AndroidManifest.xml` | Modificar: declara `CustomerDisplayActivity`. |
| `app/src/main/java/com/avoqado/pos/settings/presentation/CustomerDisplaySheet.kt` | Modificar: el interruptor, la confirmación y las explicaciones. |
| `app/src/main/java/com/avoqado/pos/settings/MoreMenuViewModel.kt` | Modificar: expone `displayModePrefs`. |
| `app/src/main/java/com/avoqado/pos/tpvsettings/data/TpvSettingsRepository.kt` | Modificar: lee `customerDisplayInverted` del `deviceTerminal`. |
| `app/src/main/java/com/avoqado/pos/customerdisplay/DisplayModeSync.kt` | **Nuevo.** Reconcilia local ↔ server (adopta o empuja). |
| `app/src/test/java/com/avoqado/pos/customerdisplay/DisplayRolesTest.kt` | **Nuevo.** Tests puros del resolver y del anti-bucle. |
| `app/src/test/java/com/avoqado/pos/customerdisplay/DisplayModeSyncTest.kt` | **Nuevo.** Tests puros de la reconciliación. |

**Server (`avoqado-server`)** — `prisma/schema.prisma` (columna), `docs/SCHEMA_MAP.md` (regenerado), `src/controllers/mobile/tpvSettings.mobile.controller.ts` (lectura + PATCH), `src/schemas/mobile/` (zod del PATCH), `src/routes/mobile.routes.ts` (ruta), `src/schemas/dashboard/tpv.schema.ts` (`UpdateTpvBody`), `src/services/dashboard/tpv.dashboard.service.ts` (`updateTpv`), `src/mcp/tools/terminals.ts` (campo en el reporte).

**Dashboard (`avoqado-web-dashboard`)** — `src/pages/Tpv/TpvId.tsx` (el `Switch`), `src/services/tpv.service.ts` (mutación).

---

### Task 1: Lógica pura de roles de pantalla

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/customerdisplay/DisplayRoles.kt`
- Create: `app/src/test/java/com/avoqado/pos/customerdisplay/DisplayRolesTest.kt`
- Modify: `app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayManager.kt:64-67` (mueve `REMOTE_CAPTURE_HINTS`), `:177-179` (mueve `ownerPackage`)

**Interfaces:**
- Consumes: `CandidateDisplay(displayId: Int, ownerPackage: String?)` y `chooseCustomerDisplayId(candidates, remoteCaptureHints): Int?`, ambos ya existentes en `CustomerDisplayManager.kt` y ambos `internal`. **No se tocan** — sus 6 tests en `CustomerDisplaySelectionTest.kt` deben seguir pasando.
- Produces:
  - `internal data class DisplayRoles(val cashierDisplayId: Int, val customerDisplayId: Int?, val invertible: Boolean)`
  - `internal fun resolveDisplayRoles(defaultDisplayId: Int, candidates: List<CandidateDisplay>, remoteCaptureHints: List<String>, inverted: Boolean): DisplayRoles`
  - `internal fun shouldRelaunchCashier(currentDisplayId: Int, targetDisplayId: Int, attemptsForTarget: Int, maxAttempts: Int = 2): Boolean`
  - `internal val REMOTE_CAPTURE_HINTS: List<String>`
  - `internal fun displayOwnerPackage(display: Display): String?`

- [ ] **Step 1: Escribe los tests que fallan**

Crea `app/src/test/java/com/avoqado/pos/customerdisplay/DisplayRolesTest.kt`:

```kotlin
package com.avoqado.pos.customerdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que protegen estos tests: invertir las pantallas solo es válido cuando la
 * segunda es FÍSICA. En un Sunmi T3 Pro la pantalla del cliente es virtual y de
 * otra app (com.sunmi.usbscreen): Android NO le entrega toques, así que poner la
 * caja ahí dejaría al cajero con una pantalla muerta y el local sin cobrar.
 */
class DisplayRolesTest {

    private val hints = listOf("anydesk", "teamviewer", "scrcpy")

    // `val`, no `const val`: en Kotlin un const solo va top-level o en un object.
    private val DEFAULT = 0

    @Test
    fun `sin segunda pantalla la caja se queda en la principal y no es invertible`() {
        val roles = resolveDisplayRoles(DEFAULT, emptyList(), hints, inverted = false)
        assertEquals(DEFAULT, roles.cashierDisplayId)
        assertNull(roles.customerDisplayId)
        assertFalse(roles.invertible)
    }

    @Test
    fun `modo normal con segunda fisica - caja en la principal, cliente en la secundaria`() {
        val roles = resolveDisplayRoles(DEFAULT, listOf(CandidateDisplay(2, null)), hints, inverted = false)
        assertEquals(DEFAULT, roles.cashierDisplayId)
        assertEquals(2, roles.customerDisplayId)
        assertTrue(roles.invertible)
    }

    @Test
    fun `modo invertido con segunda fisica - caja en la secundaria, cliente en la principal`() {
        val roles = resolveDisplayRoles(DEFAULT, listOf(CandidateDisplay(2, null)), hints, inverted = true)
        assertEquals(2, roles.cashierDisplayId)
        assertEquals(DEFAULT, roles.customerDisplayId)
        assertTrue(roles.invertible)
    }

    @Test
    fun `T3 Pro - la virtual de vendor sirve como cliente pero NO es invertible`() {
        val roles = resolveDisplayRoles(
            DEFAULT,
            listOf(CandidateDisplay(3, "com.sunmi.usbscreen")),
            hints,
            inverted = true, // aunque lo pidan
        )
        assertEquals(DEFAULT, roles.cashierDisplayId) // la caja NO se mueve
        assertEquals(3, roles.customerDisplayId)
        assertFalse(roles.invertible)
    }

    @Test
    fun `la captura de escritorio remoto se descarta y no habilita invertir`() {
        val roles = resolveDisplayRoles(
            DEFAULT,
            listOf(CandidateDisplay(5, "com.anydesk.anydeskandroid")),
            hints,
            inverted = true,
        )
        assertEquals(DEFAULT, roles.cashierDisplayId)
        assertNull(roles.customerDisplayId)
        assertFalse(roles.invertible)
    }

    @Test
    fun `fisica y captura a la vez - gana la fisica y si es invertible`() {
        val roles = resolveDisplayRoles(
            DEFAULT,
            listOf(CandidateDisplay(5, "com.anydesk.anydeskandroid"), CandidateDisplay(2, null)),
            hints,
            inverted = true,
        )
        assertEquals(2, roles.cashierDisplayId)
        assertEquals(DEFAULT, roles.customerDisplayId)
    }

    @Test
    fun `invertido y desaparece la pantalla del cajero - vuelve a la principal`() {
        val roles = resolveDisplayRoles(DEFAULT, emptyList(), hints, inverted = true)
        assertEquals(DEFAULT, roles.cashierDisplayId)
        assertNull(roles.customerDisplayId)
    }

    @Test
    fun `anti-bucle - no relanza si ya esta en la pantalla correcta`() {
        assertFalse(shouldRelaunchCashier(currentDisplayId = 2, targetDisplayId = 2, attemptsForTarget = 0))
    }

    @Test
    fun `anti-bucle - relanza en el primer y segundo intento`() {
        assertTrue(shouldRelaunchCashier(currentDisplayId = 0, targetDisplayId = 2, attemptsForTarget = 0))
        assertTrue(shouldRelaunchCashier(currentDisplayId = 0, targetDisplayId = 2, attemptsForTarget = 1))
    }

    @Test
    fun `anti-bucle - al segundo intento fallido se rinde`() {
        // Sin esto, un equipo que ignore setLaunchDisplayId relanza la app para siempre.
        assertFalse(shouldRelaunchCashier(currentDisplayId = 0, targetDisplayId = 2, attemptsForTarget = 2))
    }
}
```

- [ ] **Step 2: Corre los tests y confirma que fallan**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.customerdisplay.DisplayRolesTest"
```

Esperado: FALLA en compilación — `resolveDisplayRoles`, `DisplayRoles` y `shouldRelaunchCashier` no existen.

- [ ] **Step 3: Escribe la implementación mínima**

Crea `app/src/main/java/com/avoqado/pos/customerdisplay/DisplayRoles.kt`:

```kotlin
package com.avoqado.pos.customerdisplay

import android.view.Display

/**
 * Apps cuya pantalla virtual es una CAPTURA de la caja, no un display de
 * cliente. Se comparan como substring del paquete en minúsculas, así que basta
 * la raíz de la marca para cubrir sus variantes.
 *
 * Vive aquí (y no en el manager) porque el guard de arranque necesita la MISMA
 * lista: dos listas que se separen darían dos decisiones distintas sobre la
 * misma pantalla.
 */
internal val REMOTE_CAPTURE_HINTS: List<String> = listOf(
    "anydesk", "teamviewer", "rustdesk", "vnc", "scrcpy",
    "airdroid", "splashtop", "screencap", "screenrecord",
)

/**
 * Paquete que creó una pantalla VIRTUAL; null en las físicas.
 * `getOwnerPackageName()` es @hide, por eso reflexión — envuelta para que si un
 * OEM la bloquea, caigamos al comportamiento previo en vez de crashear.
 */
internal fun displayOwnerPackage(display: Display): String? = runCatching {
    Display::class.java.getMethod("getOwnerPackageName").invoke(display) as? String
}.getOrNull()

/**
 * Qué pantalla le toca a quién.
 *
 * @param cashierDisplayId dónde debe vivir `MainActivity` (la caja).
 * @param customerDisplayId dónde se muestra al cliente; null si no hay segunda pantalla usable.
 * @param invertible si este equipo admite el modo invertido (ver [resolveDisplayRoles]).
 */
internal data class DisplayRoles(
    val cashierDisplayId: Int,
    val customerDisplayId: Int?,
    val invertible: Boolean,
)

/**
 * Decisión PURA de los roles. Sin Android, sin red, con todo por parámetro:
 * toda la corrección del feature vive aquí y por eso aquí están los tests.
 *
 * 🔴 `invertible` exige que la segunda pantalla sea FÍSICA (sin dueño). Una
 * virtual de vendor (la del Sunmi T3 Pro, creada por com.sunmi.usbscreen) sirve
 * perfectamente para MOSTRARLE al cliente, pero Android no le entrega toques a
 * la app: poner la caja ahí dejaría al cajero sin poder tocar nada. Ante la
 * duda, no se invierte.
 */
internal fun resolveDisplayRoles(
    defaultDisplayId: Int,
    candidates: List<CandidateDisplay>,
    remoteCaptureHints: List<String>,
    inverted: Boolean,
): DisplayRoles {
    val secondaryId = chooseCustomerDisplayId(candidates, remoteCaptureHints)
    val secondary = candidates.firstOrNull { it.displayId == secondaryId }
    val invertible = secondary != null &&
        secondary.ownerPackage == null &&
        secondary.displayId != defaultDisplayId

    return if (inverted && invertible && secondary != null) {
        DisplayRoles(
            cashierDisplayId = secondary.displayId,
            customerDisplayId = defaultDisplayId,
            invertible = true,
        )
    } else {
        DisplayRoles(
            cashierDisplayId = defaultDisplayId,
            customerDisplayId = secondaryId,
            invertible = invertible,
        )
    }
}

/**
 * ¿Hay que relanzar la caja en otra pantalla?
 *
 * 🔴 El `maxAttempts` no es paranoia: si un equipo ignora `setLaunchDisplayId`,
 * la Activity vuelve a nacer en la pantalla equivocada y volvería a relanzarse
 * — para siempre. El contador lo corta.
 */
internal fun shouldRelaunchCashier(
    currentDisplayId: Int,
    targetDisplayId: Int,
    attemptsForTarget: Int,
    maxAttempts: Int = 2,
): Boolean = currentDisplayId != targetDisplayId && attemptsForTarget < maxAttempts
```

Ahora borra los duplicados de `CustomerDisplayManager.kt`: elimina el bloque `private val REMOTE_CAPTURE_HINTS = listOf(...)` (líneas 64-67 más su comentario) y la función `private fun ownerPackage(display: Display): String?` (líneas 177-179 más su KDoc). Reemplaza sus dos usos internos por las versiones compartidas: `REMOTE_CAPTURE_HINTS` (misma referencia, ahora top-level) y `displayOwnerPackage(it)` / `displayOwnerPackage(target)`.

- [ ] **Step 4: Corre los tests nuevos Y los viejos**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.customerdisplay.*"
```

Esperado: PASS en `DisplayRolesTest` (10 tests) **y** en `CustomerDisplaySelectionTest` (6 tests). Si alguno de los viejos falla, rompiste `chooseCustomerDisplayId` o el movimiento de constantes — arréglalo antes de seguir.

- [ ] **Step 5: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
git add app/src/main/java/com/avoqado/pos/customerdisplay/DisplayRoles.kt \
        app/src/test/java/com/avoqado/pos/customerdisplay/DisplayRolesTest.kt \
        app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayManager.kt
git commit -m "feat(pantallas): rol de cada pantalla como decision pura

resolveDisplayRoles decide quien va en cual pantalla, y solo declara
invertible cuando la segunda es FISICA: en un T3 Pro la del cliente es
virtual y Android no le pasa toques, asi que la caja ahi seria una
pantalla muerta."
```

---

### Task 2: Preferencia local del modo invertido

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/customerdisplay/DisplayModePrefs.kt`
- Modify: `app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayState.kt` (agrega dos StateFlow)

**Interfaces:**
- Consumes: nada de tareas previas.
- Produces:
  - `@Singleton class DisplayModePrefs` con `val inverted: StateFlow<Boolean>`, `val dirty: StateFlow<Boolean>`, `fun setInverted(value: Boolean)` (marca `dirty = true`), `fun adoptFromServer(value: Boolean)` (solo si `!dirty`), `fun markSynced()`.
  - En `CustomerDisplayState`: `val invertible: StateFlow<Boolean>` + `fun setInvertible(Boolean)`, y `val invertUnsupported: StateFlow<Boolean>` + `fun setInvertUnsupported(Boolean)`.

- [ ] **Step 1: Crea la preferencia**

`app/src/main/java/com/avoqado/pos/customerdisplay/DisplayModePrefs.kt`:

```kotlin
package com.avoqado.pos.customerdisplay

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ¿Este mostrador está armado al revés — el cliente viendo la pantalla grande y
 * el cajero trabajando en la chica?
 *
 * Vive en SharedPreferences y NO en el cache de settings del server por una
 * razón dura: el refresh de settings borra su cache ante un 4xx
 * (`TpvSettingsRepository.kt:127-131`). Si el modo de pantallas viviera ahí, un
 * error de permisos movería la caja de pantalla a media venta. El valor local
 * es la autoridad para APLICAR; el server solo sincroniza.
 *
 * Apagado por defecto: prenderlo por nuestra cuenta movería la caja de alguien
 * sin avisar.
 */
@Singleton
class DisplayModePrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("avoqado_display_mode", Context.MODE_PRIVATE)

    private val _inverted = MutableStateFlow(prefs.getBoolean(KEY_INVERTED, false))
    val inverted: StateFlow<Boolean> = _inverted.asStateFlow()

    /** true = hay un cambio hecho en este equipo que el server todavía no confirmó. */
    private val _dirty = MutableStateFlow(prefs.getBoolean(KEY_DIRTY, false))
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    /** Cambio hecho DESDE este equipo: aplica ya y queda pendiente de empujar. */
    fun setInverted(value: Boolean) {
        prefs.edit().putBoolean(KEY_INVERTED, value).putBoolean(KEY_DIRTY, true).apply()
        _inverted.value = value
        _dirty.value = true
    }

    /**
     * Valor que llegó del server. Se ignora si hay un cambio local pendiente:
     * si no, un equipo sin internet que acaba de prender el modo lo vería
     * revertirse en el siguiente refresh.
     */
    fun adoptFromServer(value: Boolean) {
        if (_dirty.value) return
        if (_inverted.value == value) return
        prefs.edit().putBoolean(KEY_INVERTED, value).apply()
        _inverted.value = value
    }

    /** El server confirmó nuestro valor: a partir de aquí él manda. */
    fun markSynced() {
        prefs.edit().putBoolean(KEY_DIRTY, false).apply()
        _dirty.value = false
    }

    private companion object {
        const val KEY_INVERTED = "customer_display_inverted"
        const val KEY_DIRTY = "customer_display_inverted_dirty"
    }
}
```

- [ ] **Step 2: Agrega los dos estados a `CustomerDisplayState`**

En `CustomerDisplayState.kt`, junto a los otros StateFlow (después del bloque de `customerCapturesInput`, ~línea 147):

```kotlin
    /** ¿Este equipo admite invertir las pantallas? (segunda pantalla física). */
    private val _invertible = MutableStateFlow(false)
    val invertible: StateFlow<Boolean> = _invertible.asStateFlow()

    fun setInvertible(value: Boolean) {
        _invertible.value = value
    }

    /**
     * El equipo NO permitió mover la caja a la otra pantalla (dos intentos
     * fallidos). Solo en memoria: nunca se toca la preferencia del usuario.
     */
    private val _invertUnsupported = MutableStateFlow(false)
    val invertUnsupported: StateFlow<Boolean> = _invertUnsupported.asStateFlow()

    fun setInvertUnsupported(value: Boolean) {
        _invertUnsupported.value = value
    }
```

- [ ] **Step 3: Compila**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:compileDebugKotlin
```

Esperado: BUILD SUCCESSFUL. Todavía no cambia ningún comportamiento — nadie lee `inverted` aún.

- [ ] **Step 4: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
git add app/src/main/java/com/avoqado/pos/customerdisplay/DisplayModePrefs.kt \
        app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayState.kt
git commit -m "feat(pantallas): preferencia local del modo invertido

En SharedPreferences y no en el cache de settings: ese cache se borra
ante un 4xx, y un error de permisos no puede mover la caja de pantalla."
```

---

### Task 3: La pantalla del cliente como Activity (modo invertido)

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml:81` (después del cierre de la activity existente)
- Modify: `app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayManager.kt:103-143` (`refresh` + helpers) y `:50-58` (inyectar `DisplayModePrefs`)

**Interfaces:**
- Consumes: `resolveDisplayRoles`, `DisplayRoles`, `REMOTE_CAPTURE_HINTS`, `displayOwnerPackage` (Task 1); `DisplayModePrefs.inverted` y `CustomerDisplayState.setInvertible` (Task 2).
- Produces: `CustomerDisplayActivity` con `companion object { fun isShowingOn(displayId: Int): Boolean; fun finishIfShowing() }`.

- [ ] **Step 1: Crea la Activity del cliente**

`app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayActivity.kt`:

```kotlin
package com.avoqado.pos.customerdisplay

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * La pantalla del cliente cuando el mostrador está invertido (el cliente ve la
 * pantalla grande, que es la PRINCIPAL del equipo).
 *
 * 🔴 Por qué una Activity y no el `Presentation` de siempre: Android prohíbe las
 * ventanas `TYPE_PRESENTATION` en la pantalla por defecto — solo las acepta en
 * pantallas que califican como *public presentation display*. El `Presentation`
 * sigue siendo el camino en modo normal (y es el ÚNICO que funciona en equipos
 * cuya pantalla de cliente es virtual, como el T3 Pro).
 *
 * Es sin estado: solo pinta el singleton [CustomerDisplayState]. Si el sistema
 * la recrea, no se pierde nada.
 */
@AndroidEntryPoint
class CustomerDisplayActivity : ComponentActivity() {

    @Inject lateinit var state: CustomerDisplayState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔴 Un letrero de cara al público JAMÁS debe quitarle el foco ni el
        // teclado a la caja, que en este modo vive en la otra pantalla. Con
        // NOT_FOCUSABLE los toques DENTRO de esta ventana sí llegan — es como
        // funcionan propina y calificación hoy en el Presentation.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        // La pantalla de cara al cliente no se apaga a media venta.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        instance = this
        setContent {
            // Siempre en claro: es un letrero público, no sigue el tema del cajero.
            AvoqadoTheme(darkTheme = false) {
                CustomerDisplayScreen(
                    state = state,
                    onRating = { state.onRatingPicked?.invoke(it) },
                    onTip = { state.onTipPicked?.invoke(it) },
                    onWhatsApp = { state.onWhatsAppSubmit?.invoke(it) },
                    onEmail = { state.onEmailSubmit?.invoke(it) },
                )
            }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** Se limpia en onDestroy, así que no retiene la Activity muerta. */
        private var instance: CustomerDisplayActivity? = null

        fun isShowingOn(displayId: Int): Boolean =
            instance?.let { !it.isFinishing && it.display?.displayId == displayId } == true

        fun finishIfShowing() {
            instance?.finish()
            instance = null
        }
    }
}
```

- [ ] **Step 2: Declárala en el manifest**

En `app/src/main/AndroidManifest.xml`, inmediatamente después de `</activity>` (línea 81) y antes de `</application>`:

```xml
        <!-- Pantalla del cliente en modo invertido: vive en la pantalla
             PRINCIPAL, en su propia tarea porque está en otro display, y fuera
             de recientes (no es una pantalla que el cajero abra). -->
        <activity
            android:name=".customerdisplay.CustomerDisplayActivity"
            android:exported="false"
            android:launchMode="singleInstance"
            android:taskAffinity=".customerdisplay"
            android:excludeFromRecents="true"
            android:resizeableActivity="true"
            android:theme="@style/Theme.Avoqado" />
```

- [ ] **Step 3: Haz que el manager monte según el rol**

En `CustomerDisplayManager.kt`, agrega la dependencia al constructor (después de `prefs`):

```kotlin
    private val displayModePrefs: DisplayModePrefs,
```

Reemplaza el cuerpo completo de `refresh()` (líneas 103-143) por:

```kotlin
    private fun refresh() {
        val activity = hostActivity ?: return
        // PRESENTATION = pantallas pensadas para mostrar contenido a terceros;
        // es justo la categoría en la que caen los displays de cliente.
        val displays = displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.toList()
            .orEmpty()

        val roles = resolveDisplayRoles(
            defaultDisplayId = Display.DEFAULT_DISPLAY,
            candidates = displays.map { CandidateDisplay(it.displayId, displayOwnerPackage(it)) },
            remoteCaptureHints = REMOTE_CAPTURE_HINTS,
            inverted = displayModePrefs.inverted.value,
        )
        state.setInvertible(roles.invertible)

        val customerId = roles.customerDisplayId
        if (customerId == null) {
            if (presentation != null || CustomerDisplayActivity.isShowingOn(Display.DEFAULT_DISPLAY)) {
                Log.i(tag, "Segunda pantalla desconectada")
            }
            dismiss()
            return
        }

        if (customerId == Display.DEFAULT_DISPLAY) {
            // Modo invertido: el cliente va en la pantalla principal, y ahí
            // TYPE_PRESENTATION está prohibido → Activity.
            dismissPresentation()
            showCustomerActivity(activity, customerId)
            // La principal siempre es física y táctil: el cliente sí puede
            // elegir propina y calificación.
            state.setTouchCapable(true)
            return
        }

        CustomerDisplayActivity.finishIfShowing()
        val target = displays.firstOrNull { it.displayId == customerId } ?: return
        showPresentation(activity, target)
    }

    /** Modo invertido: el cliente en una Activity sobre la pantalla principal. */
    private fun showCustomerActivity(activity: Activity, displayId: Int) {
        if (CustomerDisplayActivity.isShowingOn(displayId)) return
        runCatching {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            activity.startActivity(
                Intent(activity, CustomerDisplayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                opts.toBundle(),
            )
            isActive = true
            state.setPresenting(true)
            Log.i(tag, "Pantalla del cliente (Activity) montada en display $displayId")
        }.onFailure {
            // Nunca tumbar la caja por culpa de la pantalla del cliente.
            Log.e(tag, "No se pudo abrir la pantalla del cliente: ${it.message}")
            isActive = false
            state.setPresenting(false)
        }
    }

    /** Modo normal: el cliente en un Presentation sobre la secundaria. */
    private fun showPresentation(activity: Activity, target: Display) {
        if (presentation?.display?.displayId == target.displayId && presentation?.isShowing == true) return
        dismissPresentation()
        runCatching {
            CustomerDisplayPresentation(activity, target, state).also {
                it.show()
                presentation = it
                isActive = true
                state.setPresenting(true)
                // Detección automática por hardware: una pantalla FÍSICA (sin
                // dueño) sí entrega toques; una virtual de Sunmi (NP511 del T3
                // Pro) NO. De esto depende delegar propina/calificación.
                val touchCapable = displayOwnerPackage(target) == null
                state.setTouchCapable(touchCapable)
                Log.i(tag, "Pantalla del cliente montada en display ${target.displayId} (${target.name}), táctil=$touchCapable")
            }
        }.onFailure {
            Log.e(tag, "No se pudo montar la pantalla del cliente: ${it.message}")
            isActive = false
            state.setPresenting(false)
        }
    }

    private fun dismissPresentation() {
        runCatching { presentation?.dismiss() }
        presentation = null
    }
```

Y reemplaza el `dismiss()` existente (líneas 181-186) por uno que cierre **ambas** ventanas:

```kotlin
    private fun dismiss() {
        dismissPresentation()
        // Si la caja se va a segundo plano, el cliente NO puede quedarse viendo
        // un total congelado.
        CustomerDisplayActivity.finishIfShowing()
        isActive = false
        state.setPresenting(false)
    }
```

Agrega los imports que faltan en el manager: `android.app.ActivityOptions`, `android.content.Intent`.

Borra la función `pickCustomerDisplay` (líneas 164-170) y su KDoc: `resolveDisplayRoles` ya hace ese trabajo y dejarla sería una segunda fuente de verdad.

- [ ] **Step 4: Compila y corre los tests**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.avoqado.pos.customerdisplay.*"
```

Esperado: BUILD SUCCESSFUL y 16 tests PASS. Con `inverted = false` (el default) el comportamiento es idéntico al de hoy: `Presentation` sobre la secundaria.

- [ ] **Step 5: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
git add app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayActivity.kt \
        app/src/main/java/com/avoqado/pos/customerdisplay/CustomerDisplayManager.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat(pantallas): pantalla del cliente como Activity en modo invertido

TYPE_PRESENTATION esta prohibido en la pantalla principal, asi que el
cliente va en una Activity cuando el mostrador esta invertido. El modo
normal sigue con Presentation: es el unico que funciona en equipos cuya
pantalla de cliente es virtual (T3 Pro)."
```

---

### Task 4: Mover la caja a su pantalla (con anti-bucle)

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/customerdisplay/CashierDisplayGuard.kt`
- Modify: `app/src/main/java/com/avoqado/pos/MainActivity.kt:38-46` (inyección) y `:72-75` (`onCreate`)

**Interfaces:**
- Consumes: `resolveDisplayRoles`, `shouldRelaunchCashier`, `REMOTE_CAPTURE_HINTS`, `displayOwnerPackage`, `CandidateDisplay` (Task 1); `DisplayModePrefs.inverted`, `CustomerDisplayState.setInvertUnsupported` (Task 2).
- Produces: `@Singleton class CashierDisplayGuard` con `fun enforce(activity: Activity)`.

- [ ] **Step 1: Crea el guard**

`app/src/main/java/com/avoqado/pos/customerdisplay/CashierDisplayGuard.kt`:

```kotlin
package com.avoqado.pos.customerdisplay

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deja la caja en la pantalla que le toca.
 *
 * El launcher SIEMPRE abre en la pantalla por defecto, así que en modo
 * invertido cada arranque en frío necesita un relanzamiento: se ve ~1 s de la
 * caja en la pantalla equivocada y luego aparece en la correcta. Es el precio
 * aceptado del feature.
 *
 * 🔴 Degradar, nunca bloquear: si el equipo no permite mover la Activity, la
 * caja se queda donde está, se marca el modo como no soportado EN MEMORIA (la
 * preferencia del usuario no se toca) y Ajustes lo explica. Jamás se impide
 * cobrar por esto.
 */
@Singleton
class CashierDisplayGuard @Inject constructor(
    private val displayModePrefs: DisplayModePrefs,
    private val state: CustomerDisplayState,
) {
    private val tag = "🖥️CashierDisplay"

    private var attemptsTarget: Int? = null
    private var attempts = 0
    private var lastDisplaySet: Set<Int> = emptySet()

    fun enforce(activity: Activity) {
        val dm = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList()

        // El contador se reinicia SOLO cuando cambia el hardware presente:
        // conectar o desconectar una pantalla es un escenario nuevo y merece
        // otro intento.
        val present = displays.map { it.displayId }.toSet()
        if (present != lastDisplaySet) {
            lastDisplaySet = present
            attempts = 0
            attemptsTarget = null
        }

        val roles = resolveDisplayRoles(
            defaultDisplayId = Display.DEFAULT_DISPLAY,
            candidates = displays.map { CandidateDisplay(it.displayId, displayOwnerPackage(it)) },
            remoteCaptureHints = REMOTE_CAPTURE_HINTS,
            inverted = displayModePrefs.inverted.value,
        )

        if (attemptsTarget != roles.cashierDisplayId) {
            attemptsTarget = roles.cashierDisplayId
            attempts = 0
        }

        val current = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        if (!shouldRelaunchCashier(current, roles.cashierDisplayId, attempts)) {
            // Se rindió tras dos intentos: el equipo no lo permite.
            state.setInvertUnsupported(current != roles.cashierDisplayId)
            return
        }

        attempts++
        runCatching {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(roles.cashierDisplayId)
            activity.startActivity(
                Intent(activity, activity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                opts.toBundle(),
            )
            Log.i(tag, "Moviendo la caja al display ${roles.cashierDisplayId} (intento $attempts)")
        }.onFailure {
            Log.e(tag, "No se pudo mover la caja: ${it.message}")
            state.setInvertUnsupported(true)
        }
    }

    /** El usuario cambió el ajuste: merece intentos frescos. */
    fun resetAttempts() {
        attempts = 0
        attemptsTarget = null
        state.setInvertUnsupported(false)
    }
}
```

- [ ] **Step 2: Llámalo desde `MainActivity.onCreate`**

En `MainActivity.kt`, agrega la inyección junto a las otras (después de `innerPrinter`, línea 46):

```kotlin
    /** Deja la caja en la pantalla que le toca cuando el mostrador está invertido. */
    @Inject lateinit var cashierGuard: com.avoqado.pos.customerdisplay.CashierDisplayGuard
```

Y en `onCreate`, justo después de `super.onCreate(savedInstanceState)` (línea 73):

```kotlin
        // Si el mostrador está invertido, la caja va en la otra pantalla. Se
        // llama ANTES de pintar, pero NO se hace finish(): el sistema mueve la
        // tarea y recrea la Activity, y si por lo que sea no se mueve, la caja
        // sigue siendo usable donde está.
        cashierGuard.enforce(this)
```

- [ ] **Step 3: Compila**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:compileDebugKotlin
```

Esperado: BUILD SUCCESSFUL. Con `inverted = false`, `shouldRelaunchCashier` devuelve `false` siempre (la caja ya está en la principal) → cero cambios de comportamiento.

- [ ] **Step 4: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
git add app/src/main/java/com/avoqado/pos/customerdisplay/CashierDisplayGuard.kt \
        app/src/main/java/com/avoqado/pos/MainActivity.kt
git commit -m "feat(pantallas): mover la caja a su pantalla con anti-bucle

Dos intentos por objetivo y el contador solo se reinicia al cambiar el
hardware presente: sin eso, un equipo que ignore setLaunchDisplayId
relanzaria la app para siempre."
```

---

### Task 5: El interruptor en Más → Hardware → Pantalla del cliente

**Files:**
- Modify: `app/src/main/java/com/avoqado/pos/settings/presentation/CustomerDisplaySheet.kt` (fila nueva + diálogo + toast)
- Modify: `app/src/main/java/com/avoqado/pos/settings/MoreMenuViewModel.kt:37-38` (expone `displayModePrefs` y `cashierGuard`)
- Modify: `app/src/main/java/com/avoqado/pos/settings/MoreMenuScreen.kt:895-900` (pasa los nuevos parámetros)

**Interfaces:**
- Consumes: `DisplayModePrefs` (Task 2), `CustomerDisplayState.invertible` / `.invertUnsupported` (Task 2), `CashierDisplayGuard.resetAttempts()` (Task 4).
- Produces: nada que consuman tareas posteriores.

- [ ] **Step 1: Expón las dependencias en el ViewModel**

En `MoreMenuViewModel.kt`, junto a `customerDisplayPrefs` y `customerDisplayState` (líneas 37-38), agrega al constructor:

```kotlin
    val displayModePrefs: com.avoqado.pos.customerdisplay.DisplayModePrefs,
    val cashierDisplayGuard: com.avoqado.pos.customerdisplay.CashierDisplayGuard,
```

- [ ] **Step 2: Agrega la fila, la confirmación y el toast al sheet**

En `CustomerDisplaySheet.kt`, cambia la firma para recibir lo nuevo:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerDisplaySheet(
    prefs: CustomerDisplayPrefs,
    displayState: CustomerDisplayState,
    displayModePrefs: com.avoqado.pos.customerdisplay.DisplayModePrefs,
    onInvertedChange: (Boolean) -> Unit,
    ventaEnCurso: Boolean,
    onDismiss: () -> Unit,
) {
```

Después del bloque del switch existente (línea 109) y antes del `Spacer` final, agrega:

```kotlin
            Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.lg))

            val invertible by displayState.invertible.collectAsState()
            val inverted by displayModePrefs.inverted.collectAsState()
            val unsupported by displayState.invertUnsupported.collectAsState()
            var confirmando by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.padding(end = AvoqadoTheme.spacing.md)) {
                    Text(
                        text = "Invertir pantallas",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        // Apagado se VE y se EXPLICA: nunca desaparece en silencio.
                        text = when {
                            unsupported ->
                                "Este equipo no permitió mover la caja a la otra pantalla."
                            !invertible ->
                                "La segunda pantalla de este equipo no es táctil; " +
                                    "el cajero no podría trabajar en ella."
                            ventaEnCurso ->
                                "Termina la venta en curso para poder cambiar de pantalla."
                            else ->
                                "El cliente ve la pantalla grande y el cajero trabaja en la chica."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = inverted,
                    enabled = invertible && !ventaEnCurso,
                    onCheckedChange = { confirmando = true },
                )
            }

            if (confirmando) {
                com.avoqado.pos.designsystem.components.AvoqadoDialog(
                    title = if (inverted) "¿Volver a la pantalla normal?" else "¿Invertir las pantallas?",
                    message = "La caja se va a reiniciar en la otra pantalla. " +
                        "Tarda unos segundos y no se pierde nada.",
                    confirmLabel = "Continuar",
                    onConfirm = {
                        confirmando = false
                        onInvertedChange(!inverted)
                    },
                    onDismiss = { confirmando = false },
                )
            }
```

Agrega los imports que faltan: `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.setValue`.

> **Verifica la firma real de `AvoqadoDialog`** antes de escribir la llamada: `grep -n "fun AvoqadoDialog" -A 12 app/src/main/java/com/avoqado/pos/designsystem/components/*.kt`. Si sus parámetros se llaman distinto, usa los reales — el design system manda, no este plan.

- [ ] **Step 3: Conéctalo en `MoreMenuScreen`**

En `MoreMenuScreen.kt`, reemplaza la llamada al sheet (líneas 895-900) por:

```kotlin
    if (showCustomerDisplay) {
        val carritoConItems by viewModel.hasActiveCart.collectAsState()
        CustomerDisplaySheet(
            prefs = viewModel.customerDisplayPrefs,
            displayState = viewModel.customerDisplayState,
            displayModePrefs = viewModel.displayModePrefs,
            ventaEnCurso = carritoConItems,
            onInvertedChange = { nuevo ->
                viewModel.cashierDisplayGuard.resetAttempts()
                viewModel.displayModePrefs.setInverted(nuevo)
                showCustomerDisplay = false
                showSuccessToast = "¡Pantallas invertidas!"
            },
            onDismiss = { showCustomerDisplay = false },
        )
    }
```

> **`hasActiveCart` y `showSuccessToast` pueden no existir con esos nombres.** Antes de escribir esto, busca lo que ya hay: `grep -n "AvoqadoSuccessToast\|successToast" app/src/main/java/com/avoqado/pos/settings/MoreMenuScreen.kt` y `grep -n "cartState\|hasItems\|itemCount" app/src/main/java/com/avoqado/pos/pos/presentation/cart/CartViewModel.kt`. Usa el mecanismo de toast que ya usa esa pantalla y la señal de carrito que ya exista; si `MoreMenuViewModel` no tiene acceso al carrito, expón un `StateFlow<Boolean>` derivado del `CartViewModel`/repositorio de carrito **en esta misma tarea** — el guard de "no a media venta" no es opcional, porque mover la caja recrea la Activity.

- [ ] **Step 4: Compila y verifica los tests**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.avoqado.pos.customerdisplay.*"
```

Esperado: BUILD SUCCESSFUL, 16 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
git add app/src/main/java/com/avoqado/pos/settings/presentation/CustomerDisplaySheet.kt \
        app/src/main/java/com/avoqado/pos/settings/MoreMenuViewModel.kt \
        app/src/main/java/com/avoqado/pos/settings/MoreMenuScreen.kt
git commit -m "feat(pantallas): interruptor de invertir pantallas en Mas > Hardware

Con confirmacion antes de reiniciar la caja, bloqueado a media venta, y
apagado VISIBLE con la razon cuando el equipo no lo admite."
```

---

### Task 6: Server — campo por dispositivo, lectura, escritura y MCP

**Files:**
- Modify: `avoqado-server/prisma/schema.prisma` (modelo `Terminal`, ~línea 3865)
- Modify: `avoqado-server/docs/SCHEMA_MAP.md` (regenerado, no a mano)
- Modify: `avoqado-server/src/controllers/mobile/tpvSettings.mobile.controller.ts` (select + `deviceTerminal` + nuevo handler)
- Modify: `avoqado-server/src/routes/mobile.routes.ts` (ruta PATCH, junto a la sección "TPV SETTINGS", ~línea 1622)
- Modify: `avoqado-server/src/schemas/dashboard/tpv.schema.ts:35-44` (`UpdateTpvBody`)
- Modify: `avoqado-server/src/services/dashboard/tpv.dashboard.service.ts:235` (`updateTpv`)
- Modify: `avoqado-server/src/mcp/tools/terminals.ts:24-45` (campo en el reporte)

**Interfaces:**
- Consumes: nada de Android; este trabajo es independiente y puede ir en paralelo a las tareas 1-5.
- Produces: campo `customerDisplayInverted: boolean` dentro de `data.deviceTerminal` de `GET /api/v1/mobile/venues/:venueId/settings`, y `PATCH /api/v1/mobile/venues/:venueId/terminals/:terminalId/display-mode` con body `{ customerDisplayInverted: boolean }`.

- [ ] **Step 1: Carga el contexto del repo (obligatorio antes de editar)**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
cat CLAUDE.md | head -80
ls .claude/rules/
cat /Users/amieva/.claude/projects/-Users-amieva-Documents-Programming-Avoqado-avoqado-server/memory/MEMORY.md
```

Es la regla del workspace multirepo: el `CLAUDE.md` y las reglas de este repo no se cargan solas, y sus memorias nunca. Lee `.claude/rules/feature-gating.md` y `.claude/rules/contexto-de-ejecucion.md`.

- [ ] **Step 2: Agrega la columna**

En `prisma/schema.prisma`, dentro de `model Terminal`, junto a los otros campos por dispositivo (cerca de `defaultWorkspace`):

```prisma
  /// Mostrador invertido: el cliente ve la pantalla grande y el cajero trabaja
  /// en la chica. Es por DISPOSITIVO (así está armado ESE mostrador), no por
  /// venue. El POS aplica su valor local y sincroniza con este; el dashboard lo
  /// puede cambiar en remoto.
  customerDisplayInverted Boolean @default(false)
```

- [ ] **Step 3: Migra y regenera el mapa del schema**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npx prisma migrate dev --name terminal_customer_display_inverted
npm run schema:map
```

Esperado: la migración se aplica y `docs/SCHEMA_MAP.md` queda modificado. **No commitees el schema sin el mapa regenerado** — es regla del workspace.

- [ ] **Step 4: Devuélvelo en el endpoint que ya lee el dispositivo**

En `src/controllers/mobile/tpvSettings.mobile.controller.ts`, agrega al `select` del `findMany` (después de `defaultWorkspace`, ~línea 65):

```ts
          customerDisplayInverted: true,
```

Y al objeto `deviceTerminal` de la respuesta (~línea 120):

```ts
              customerDisplayInverted: deviceTerminal.customerDisplayInverted,
```

Es **aditivo**: apps viejas que no lo conocen siguen funcionando igual.

- [ ] **Step 5: Agrega el handler de escritura**

Al final de `src/controllers/mobile/tpvSettings.mobile.controller.ts`:

```ts
/**
 * Cambia el modo de pantallas de UN dispositivo.
 * @route PATCH /api/v1/mobile/venues/:venueId/terminals/:terminalId/display-mode
 *
 * El POS es quien conoce su hardware, así que él decide y aquí solo se guarda.
 * Se verifica que la terminal pertenezca al venue del request: sin eso, un
 * token de un venue podría reconfigurar el mostrador de otro.
 */
export const updateTerminalDisplayMode = async (req: Request, res: Response, next: NextFunction) => {
  try {
    const { venueId, terminalId } = req.params
    const { customerDisplayInverted } = req.body as { customerDisplayInverted: boolean }

    const terminal = await prisma.terminal.findFirst({
      where: { id: terminalId, venueId },
      select: { id: true },
    })
    if (!terminal) {
      return res.status(404).json({ success: false, error: 'Terminal no encontrada en este venue' })
    }

    const updated = await prisma.terminal.update({
      where: { id: terminal.id },
      data: { customerDisplayInverted },
      select: { id: true, customerDisplayInverted: true },
    })

    logger.info('Modo de pantallas actualizado desde el POS', {
      terminalId: updated.id,
      customerDisplayInverted: updated.customerDisplayInverted,
    })

    return res.json({ success: true, data: updated })
  } catch (error) {
    next(error)
  }
}
```

- [ ] **Step 6: Registra la ruta**

En `src/routes/mobile.routes.ts`, en la sección "TPV SETTINGS" (después del `router.get` de settings, ~línea 1630), imitando el patrón de `/venues/:venueId/upsell-impressions/:impressionId` (líneas 1613-1619):

```ts
router.patch(
  '/venues/:venueId/terminals/:terminalId/display-mode',
  authenticateTokenMiddleware,
  requireVenueMembership,
  validateRequest(updateTerminalDisplayModeSchema),
  tpvSettingsMobileController.updateTerminalDisplayMode,
)
```

Crea el schema zod junto a los demás schemas mobile (busca dónde viven con `grep -rn "convertUpsellImpressionSchema" src/schemas/`) y añade:

```ts
export const updateTerminalDisplayModeSchema = z.object({
  body: z.object({
    customerDisplayInverted: z.boolean(),
  }),
})
```

Ajusta la forma del objeto (`{ body: ... }` vs plano) a la que use `validateRequest` en este repo — cópiala de `convertUpsellImpressionSchema`.

- [ ] **Step 7: Permite cambiarlo desde el dashboard**

En `src/schemas/dashboard/tpv.schema.ts`, dentro de `UpdateTpvBody` (líneas 35-44):

```ts
  /** Mostrador invertido: el cliente ve la pantalla grande. Por dispositivo. */
  customerDisplayInverted?: boolean
```

En `src/services/dashboard/tpv.dashboard.service.ts`, en `updateTpv` (línea 235), asegúrate de que el campo se propague al `prisma.terminal.update` — si la función arma el `data` campo por campo, agrégalo; si pasa el body completo, ya queda cubierto. **Léela antes de editar.**

- [ ] **Step 8: Refleja el campo en el MCP**

En `src/mcp/tools/terminals.ts`, agrega `customerDisplayInverted: boolean` a `TerminalInput` y a `TerminalConfigReport` (dentro de `settings`), y propágalo en `auditTerminalConfig`. También agrégalo al `select` de Prisma de la línea ~77. Una capacidad que no se ve por el MCP está incompleta — es regla del workspace.

- [ ] **Step 9: Verifica**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
npm run build
```

Esperado: compila sin errores de tipos. Luego, con el server local corriendo, comprueba el endpoint y **lee el log** (un 200 con un `error:` en el log es un bug escondiéndose):

```bash
LOG=$(ls -t logs/development*.log | head -1); grep "entrypoint: 'PATCH /api/v1/mobile/venues/:id/terminals/:id/display-mode'" "$LOG" | tail -5
```

- [ ] **Step 10: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-server
git add prisma/schema.prisma prisma/migrations docs/SCHEMA_MAP.md \
        src/controllers/mobile/tpvSettings.mobile.controller.ts \
        src/routes/mobile.routes.ts src/schemas src/services/dashboard/tpv.dashboard.service.ts \
        src/mcp/tools/terminals.ts
git commit -m "feat(terminal): modo de pantallas invertido por dispositivo

Columna dedicada y no configOverrides: ese campo es del dashboard y el
sync del TPV lo reemplaza completo, asi que un valor que el equipo
escribe ahi se borraria solo. Campo aditivo en deviceTerminal, PATCH
mobile para el POS, UpdateTpvBody para el dashboard, y reflejado en el
MCP de terminales."
```

---

### Task 7: Android — sincronizar el modo con el server

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/customerdisplay/DisplayModeSync.kt`
- Create: `app/src/test/java/com/avoqado/pos/customerdisplay/DisplayModeSyncTest.kt`
- Modify: `app/src/main/java/com/avoqado/pos/tpvsettings/data/TpvSettingsRepository.kt:53-58` (`TerminalNavigationSettings`), `:279-298` (DTO + mapeo)

**Interfaces:**
- Consumes: `DisplayModePrefs` (Task 2); el campo `customerDisplayInverted` del `deviceTerminal` (Task 6).
- Produces: `internal fun reconcileDisplayMode(local: Boolean, dirty: Boolean, server: Boolean?): DisplayModeAction` y la clase sellada `DisplayModeAction { Adopt(value), Push(value), Keep }`.

- [ ] **Step 1: Escribe los tests que fallan**

`app/src/test/java/com/avoqado/pos/customerdisplay/DisplayModeSyncTest.kt`:

```kotlin
package com.avoqado.pos.customerdisplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo que protege: si el server pudiera pisar un cambio local no confirmado, un
 * equipo sin internet que acaba de invertir sus pantallas las vería regresar
 * solas en el siguiente refresh — a media venta.
 */
class DisplayModeSyncTest {

    @Test
    fun `sin cambio local se adopta lo del server`() {
        assertEquals(
            DisplayModeAction.Adopt(true),
            reconcileDisplayMode(local = false, dirty = false, server = true),
        )
    }

    @Test
    fun `con cambio local pendiente se EMPUJA y no se adopta`() {
        assertEquals(
            DisplayModeAction.Push(true),
            reconcileDisplayMode(local = true, dirty = true, server = false),
        )
    }

    @Test
    fun `server ausente - servidor viejo sin el campo - no se toca nada`() {
        assertEquals(
            DisplayModeAction.Keep,
            reconcileDisplayMode(local = true, dirty = false, server = null),
        )
    }

    @Test
    fun `valores iguales - nada que hacer`() {
        assertEquals(
            DisplayModeAction.Keep,
            reconcileDisplayMode(local = true, dirty = false, server = true),
        )
    }

    @Test
    fun `cambio local que ya coincide con el server - solo marcar sincronizado`() {
        assertEquals(
            DisplayModeAction.Push(true),
            reconcileDisplayMode(local = true, dirty = true, server = true),
        )
    }
}
```

- [ ] **Step 2: Corre y confirma que falla**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.customerdisplay.DisplayModeSyncTest"
```

Esperado: FALLA en compilación — `reconcileDisplayMode` y `DisplayModeAction` no existen.

- [ ] **Step 3: Implementa la reconciliación**

`app/src/main/java/com/avoqado/pos/customerdisplay/DisplayModeSync.kt`:

```kotlin
package com.avoqado.pos.customerdisplay

/** Qué hacer tras comparar el valor local con el del server. */
internal sealed interface DisplayModeAction {
    data class Adopt(val value: Boolean) : DisplayModeAction
    data class Push(val value: Boolean) : DisplayModeAction
    /** No se llama `Nothing`: ese nombre ya es un tipo de Kotlin y confunde al leer. */
    data object Keep : DisplayModeAction
}

/**
 * Regla de conflicto, PURA.
 *
 * El valor local manda mientras haya un cambio sin confirmar (`dirty`): así un
 * equipo sin internet que acaba de invertir sus pantallas no las ve regresar
 * solas. Cuando no hay nada pendiente, el server manda. Un `server == null`
 * (servidor viejo que no conoce el campo) NO cambia nada.
 */
internal fun reconcileDisplayMode(
    local: Boolean,
    dirty: Boolean,
    server: Boolean?,
): DisplayModeAction = when {
    dirty -> DisplayModeAction.Push(local)
    server == null -> DisplayModeAction.Keep
    server == local -> DisplayModeAction.Keep
    else -> DisplayModeAction.Adopt(server)
}
```

- [ ] **Step 4: Corre los tests**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:testDebugUnitTest --tests "com.avoqado.pos.customerdisplay.*"
```

Esperado: 21 tests PASS.

- [ ] **Step 5: Lee el campo del server**

En `TpvSettingsRepository.kt`, agrega el campo en tres lugares:

`TerminalNavigationSettings` (línea 53-58):
```kotlin
    val customerDisplayInverted: Boolean = false,
```

`DeviceTerminalSettingsDto` (línea 279-286):
```kotlin
    val customerDisplayInverted: Boolean = false,
```

`toTerminalNavigationSettings()` (línea 290-297):
```kotlin
        customerDisplayInverted = terminal.customerDisplayInverted,
```

🔴 **No conectes `DisplayModePrefs` al camino del 4xx.** Las líneas 127-131 llaman a `clearPersistedSettings(venueId)` y resetean `_terminalNavigation` a `DEFAULT` cuando el server responde 4xx. `DisplayModePrefs` es un almacén aparte precisamente para que eso no mueva la caja de pantalla. Lo único que se agrega es, **tras una respuesta EXITOSA** (después de `persistTerminalNavigation`, línea 139):

```kotlin
            // Modo de pantallas: el local manda mientras haya un cambio sin
            // confirmar; si no, se adopta el del server. Solo en el camino
            // exitoso — un refresh fallido no puede mover la caja de pantalla.
            when (
                val action = reconcileDisplayMode(
                    local = displayModePrefs.inverted.value,
                    dirty = displayModePrefs.dirty.value,
                    server = result.data?.deviceTerminal?.customerDisplayInverted,
                )
            ) {
                is DisplayModeAction.Adopt -> displayModePrefs.adoptFromServer(action.value)
                is DisplayModeAction.Push -> pushDisplayMode(
                    venueId = venueId,
                    terminalId = terminalNavigation.terminalId,
                    value = action.value,
                )
                DisplayModeAction.Keep -> Unit
            }
```

Y agrega el push, siguiendo el mismo patrón de OkHttp del `refreshSettingsForVenue` (con `withContext(Dispatchers.IO)` — obligatorio, sin eso truena con `NetworkOnMainThreadException`):

```kotlin
    /**
     * Empuja el modo de pantallas de ESTE equipo. Si falla no pasa nada malo:
     * la bandera `dirty` sigue puesta y se reintenta en el próximo refresh.
     */
    private suspend fun pushDisplayMode(venueId: String, terminalId: String?, value: Boolean) {
        val id = terminalId ?: return
        val token = secureStorage.accessToken ?: return
        runCatching {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/terminals/$id/display-mode")
                .header("Authorization", "Bearer $token")
                .patch(
                    """{"customerDisplayInverted":$value}"""
                        .toRequestBody("application/json".toMediaType()),
                )
                .build()
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (response.isSuccessful) {
                displayModePrefs.markSynced()
                Log.d("📦", "✅ Modo de pantallas sincronizado ($value)")
            } else {
                Log.w("📦", "⚠️ El server rechazó el modo de pantallas: ${response.code}")
            }
        }.onFailure {
            Log.w("📦", "⚠️ Sin red para sincronizar el modo de pantallas — se reintenta después")
        }
    }
```

Inyecta `DisplayModePrefs` en el constructor de `TpvSettingsRepository` (líneas 68-72) y agrega los imports `okhttp3.MediaType.Companion.toMediaType` y `okhttp3.RequestBody.Companion.toRequestBody`.

- [ ] **Step 6: Compila y corre los tests**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.avoqado.pos.*"
```

Esperado: BUILD SUCCESSFUL y toda la suite de unit tests en verde.

- [ ] **Step 7: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
git add app/src/main/java/com/avoqado/pos/customerdisplay/DisplayModeSync.kt \
        app/src/test/java/com/avoqado/pos/customerdisplay/DisplayModeSyncTest.kt \
        app/src/main/java/com/avoqado/pos/tpvsettings/data/TpvSettingsRepository.kt
git commit -m "feat(pantallas): sincronizar el modo invertido con el server

El local manda mientras haya un cambio sin confirmar, y solo el camino
EXITOSO del refresh toca la preferencia: un 4xx no puede mover la caja
de pantalla a media venta."
```

---

### Task 8: Dashboard — el interruptor en la ficha del dispositivo

**Files:**
- Modify: `avoqado-web-dashboard/src/pages/Tpv/TpvId.tsx` (pestaña de información/configuración del dispositivo)
- Modify: `avoqado-web-dashboard/src/services/tpv.service.ts` (la mutación de actualización)

**Interfaces:**
- Consumes: `PUT /api/v1/dashboard/venues/:venueId/tpv/:tpvId` con `{ customerDisplayInverted: boolean }` (Task 6).
- Produces: nada.

- [ ] **Step 1: Carga el contexto del repo (obligatorio antes de editar)**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard
cat CLAUDE.md | head -60
ls .claude/rules/
cat /Users/amieva/.claude/projects/-Users-amieva-Documents-Programming-Avoqado-avoqado-web-dashboard/memory/MEMORY.md
```

- [ ] **Step 2: Localiza el patrón que ya existe**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard
grep -n "Switch" src/pages/Tpv/TpvId.tsx | head
grep -n "useMutation\|updateTpv" src/services/tpv.service.ts src/pages/Tpv/TpvId.tsx | head
```

`TpvId.tsx` ya importa `Switch` (línea 25) y tiene pestañas (`VALID_TABS`, línea 81). **Copia el patrón del switch que ya haya en ese archivo** en lugar de inventar uno: mismo componente, mismo hook de mutación, mismo manejo de estado optimista y de error.

- [ ] **Step 3: Agrega el interruptor**

En la pestaña de información/configuración del dispositivo, una fila con:

- Etiqueta: **Invertir pantallas**
- Descripción: *"El cliente ve la pantalla grande y el cajero trabaja en la chica. Solo aplica en equipos de doble pantalla con segunda pantalla táctil."*
- `checked={terminal.customerDisplayInverted}`
- `onCheckedChange` → mutación a `PUT /dashboard/venues/:venueId/tpv/:tpvId` con `{ customerDisplayInverted: valor }`, invalidando la query de la terminal al terminar.

Y una nota corta bajo el switch: *"El equipo aplica el cambio la próxima vez que sincroniza."* — porque es la verdad: el POS lo adopta en su siguiente refresh de settings, no al instante.

- [ ] **Step 4: Verifica**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard
npm run build
```

Esperado: compila. Prueba el switch contra el server local y **lee el log del backend** — un 200 en la UI con un `error:` en el log es un bug escondido:

```bash
LOG=$(ls -t /Users/amieva/Documents/Programming/Avoqado/avoqado-server/logs/development*.log | head -1)
grep "entrypoint: 'PUT /api/v1/dashboard/venues/:id/tpv/:id'" "$LOG" | tail -3
```

- [ ] **Step 5: Commit**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-web-dashboard
git add src/pages/Tpv/TpvId.tsx src/services/tpv.service.ts
git commit -m "feat(tpv): interruptor de invertir pantallas en la ficha del dispositivo"
```

---

### Task 9: Verificación en el D3 físico

**Files:** ninguno — es la tarea de comprobar que el feature funciona en el hardware al que va dirigido.

**Interfaces:**
- Consumes: todas las tareas anteriores.
- Produces: el reporte de verificación (va en el commit final o en el mensaje al founder).

- [ ] **Step 1: Instala en el D3**

```bash
cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android
adb devices -l   # el D3 aparece como model:D3, por wifi (ip:puerto)
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew installDebug
```

Si el D3 no aparece: `adb mdns services` da el puerto real (**cambia cada vez**, el 5555 no sirve en Android 11+), luego `adb connect <ip>:<puerto>`.

- [ ] **Step 2: Modo normal — que nada se haya roto**

Antes de tocar el interruptor, con el modo apagado:

```bash
D=<ip:puerto>
adb -s $D shell "dumpsys input | grep -oE \"name='[^']*avoqado[^']*', id=[0-9]+, displayId=[0-9]+\"" | sort -u
```

Esperado: `MainActivity` en `displayId=0` y una ventana de la app en `displayId=2` (la del cliente). Cobra en efectivo con propina y calificación e **imprime una comanda y un recibo**. Todo debe verse igual que antes de este trabajo.

- [ ] **Step 3: Préndelo y verifica los roles**

`Más → Hardware → Pantalla del cliente → Invertir pantallas → Continuar`. Luego:

```bash
adb -s $D shell "dumpsys activity activities | grep -E 'Display #|topResumedActivity'"
```

Esperado: `MainActivity` en Display #2 y `CustomerDisplayActivity` en Display #0.

- [ ] **Step 4: El teclado del cajero (el riesgo que había que confirmar)**

En la pantalla chica, entra al buscador de productos y escribe. Luego:

```bash
adb -s $D shell "dumpsys input_method | grep -E 'mCurTokenDisplayId|mInputShown'"
```

Esperado: `mCurTokenDisplayId=2` y `mInputShown=true`. Si sale `0`, el teclado le está apareciendo **al cliente en la cara** y el modo invertido no es usable en este equipo: para el trabajo y repórtalo.

- [ ] **Step 5: Impresión en modo invertido**

Cobra una venta completa e imprime **comanda y recibo**. Es la preocupación explícita del founder y la razón de esta tarea: el ruteo no depende del display, pero se comprueba, no se supone.

- [ ] **Step 6: Los caminos de degradación**

- **Reinicia la app** (`adb shell am force-stop com.avoqado.pos` y ábrela): debe arrancar en la pantalla chica tras el relanzamiento visible de ~1 s.
- **Reinicia el equipo**: igual.
- **Desconecta la pantalla del cliente** con el modo invertido puesto: la caja debe volver a la pantalla principal y seguir cobrando. Reconéctala y verifica que se recompongan los roles.
- **Apaga el interruptor**: todo debe volver exactamente al estado del Step 2.

- [ ] **Step 7: Reporta**

Documenta cada paso con su resultado. **Cualquier fila que no se pudo probar va explícita en el reporte con el comando exacto** — un "listo" que esconde lo que no se corrió es un reporte falso.

---

## Self-Review

**Cobertura del spec** — cada sección tiene tarea:

| Sección del spec | Tarea |
|---|---|
| §5.1 concepto de roles · §5.3 reglas del resolver | Task 1 |
| §5.2 componentes | Tasks 1-5 |
| §5.4 `CashierDisplayGuard` + anti-bucle | Task 4 |
| §5.5 `CustomerDisplayActivity` | Task 3 |
| §5.6 persistencia y autoridad | Tasks 2, 6, 7 |
| §5.7 UI Android | Task 5 |
| §5.8 UI dashboard | Task 8 |
| §5.9 MCP | Task 6, Step 8 |
| §6.1 tests unitarios (7 casos) | Task 1 (los 7 + 3 del anti-bucle) |
| §6.2 pruebas en hardware | Task 9 |

**Consistencia de tipos:** `DisplayRoles`/`resolveDisplayRoles`/`shouldRelaunchCashier` (Task 1) se consumen con la misma firma en Tasks 3 y 4. `DisplayModePrefs.inverted`/`.dirty`/`setInverted`/`adoptFromServer`/`markSynced` (Task 2) se usan igual en Tasks 3, 4, 5 y 7. `customerDisplayInverted` es el mismo nombre en Prisma, en el DTO de Android y en el dashboard.

**Dos puntos donde el plan manda leer antes de escribir, a propósito:** la firma real de `AvoqadoDialog` y el mecanismo de toast/carrito en `MoreMenuScreen` (Task 5, Steps 2-3), y el patrón de switch/mutación que ya exista en `TpvId.tsx` (Task 8, Step 2). No son placeholders: son puntos donde el código existente manda sobre este documento, y el plan dice exactamente qué comando correr para averiguarlo.

---
description: Exhaustive UI pattern audit of avoqado-android against the Avoqado design system (AGENTS.md/CLAUDE.md) and iOS parity (../avoqado-ios/ui-patterns-ios.md). Finds anti-patterns, missing components, and hardcoded values across the whole codebase.
argument-hint: "[optional module path — e.g. app/src/main/java/com/avoqado/pos/transactions]"
---

# /avoqado:check-android-ui

Audit the **entire avoqado-android codebase** (or the path passed as `$ARGUMENTS` when provided) against Avoqado's UI/UX rules. Produce a ranked report the user can act on. **Do not fix anything yet** — this command is READ-ONLY and reports findings. The user will tell you which findings to fix.

## Pre-flight (BLOCKING — do this first)

Read these three sources verbatim before grepping:

1. `CLAUDE.md` and `AGENTS.md` at repo root — component whitelist, mandatory tokens.
2. `../avoqado-ios/ui-patterns-ios.md` — iOS parity rules (heights, spacing, back buttons, typography). Target = match these on Android.
3. `app/src/main/java/com/avoqado/pos/designsystem/components/` — **this is the ground truth** of what exists. List every file; any anti-pattern that has a replacement here counts as a finding.

Skip this step and the audit is unreliable.

## Audit Scope

If `$ARGUMENTS` is provided, scope all greps to that path. Otherwise scope to `app/src/main/java/com/avoqado/pos/**/*.kt` and exclude `designsystem/`, `core/`, `*/data/*`, and `*/di/*` (those layers don't render UI).

## Categories to Check

Report findings grouped by severity: **🔴 Blocker** → must fix before merge. **🟡 Warning** → should fix soon. **⚪ Nit** → fix opportunistically.

### 🔴 Blocker — component replacements

For each row below, grep the pattern, print `file:line` for every hit outside `designsystem/`. If hits > 0, that's a blocker finding.

| Anti-pattern (grep) | Should use | Severity |
|---|---|---|
| `AlertDialog(` | `AvoqadoDialog` | 🔴 |
| `Toast.makeText(` | `AvoqadoSuccessToast` (for success) or inline error in dialog | 🔴 |
| `OutlinedTextField(` inside an `AvoqadoDialog` / custom dialog | `AvoqadoPillTextField` or `AvoqadoPhoneInput` | 🔴 |
| Success `.alert(` / "Recibo enviado" / "se envió correctamente" etc. as plain Text/Snackbar | `AvoqadoSuccessToast` | 🔴 |
| Hardcoded `+52` placeholder or `filter(isDigit \|\| '+')` phone inputs | `AvoqadoPhoneInput` with `Countries.byIso(...)` | 🔴 |
| `Icons.Filled.ArrowBack` / `Icons.AutoMirrored.Filled.ArrowBack` for back nav | `CircleBackButton` | 🔴 |
| `RoundedCornerShape(12.dp)` on a primary/CTA button | `PrimaryButton` (uses `RoundedCornerShape(50)`) | 🔴 |
| Full-screen form modal with bottom-fixed `Guardar/Crear` on compact/small tablets | Header action: `X` circular izquierda + título centrado + botón pill a la derecha | 🔴 |
| Full-screen modal header not using balanced pattern (`X` izquierda + título centrado + acción derecha) | `AvoqadoFullScreenModal` header pattern (invert action colors by theme) | 🔴 |

### 🟡 Warning — hardcoded tokens

Grep each, report counts + sample file:line for top 10:

- `fontSize = \d+\.sp` — should use `MaterialTheme.typography.*` or `metrics.*` from responsive system.
- `Color\.(Black|White|Red|Green|Blue|Gray)` — should use `MaterialTheme.colorScheme.*` or named tokens in `Color.kt`.
- `Color\(0x[0-9A-Fa-f]{8}\)` — same; use theme-aware colors.
- `\b(12|16|20|24|32)\.dp\b` **inside a `.padding(` / `Spacer.*height(` / similar** — should use `AvoqadoTheme.spacing.*`. (bare `4.dp`/`8.dp`/`1.dp` are often icon/border sizes — use judgment, don't flag those.)
- Literal English UI text in `Text(` (e.g. `"Cancel"`, `"Save"`, `"Back"`) — UI copy is Spanish per convention.

### 🟡 Warning — responsive gaps

- Files with `@Composable fun *DetailPanel`, `*DetailView`, `*DetailSheet` or `BoxWithConstraints`-less tablet split panels that hardcode `.font(.system(size: N))` or `fontSize = N.sp` ≥ 20sp. Flag each — they need responsive `DetailMetrics`-style scaling like `TransactionDetailSheet.kt`.
- Tablet split panels where the right panel has NO `BoxWithConstraints` + `CompositionLocal<Metrics>`. Grep: files with `TransactionDetailPanel`, `ProductDetailPanel`, `OrderDetailPanel` etc. — verify each has a metrics system. If not, warning.
- Lists that flash `No hay ...` before fetch completes (empty state shown while loading). Prefer explicit `isLoading && items.isEmpty()` branch with reusable loading state (`AvoqadoLoadingState`) before empty state.

### 🟡 Warning — edge-to-edge & insets

- Any `Scaffold` with `bottomBar = { … }` where the bar doesn't call `.navigationBarsPadding()` — bar content can clip on gesture-nav devices.
- Screens with `enableEdgeToEdge()` in activity but no `contentWindowInsets = WindowInsets.statusBars` on their root `Scaffold` — content can overlap status bar.

### ⚪ Nit

- `Spacer(modifier = Modifier.width(X.dp))` / `.height(X.dp)` where `X` matches a `Spacing.*` token — prefer `AvoqadoTheme.spacing.X`.
- Hardcoded `RoundedCornerShape(N.dp)` where `N` matches a `CornerRadius.*` token — prefer `AvoqadoTheme.cornerRadius.X`.
- `IconButton` without `contentDescription` on its `Icon` child — accessibility issue.
- `Icons.Filled.*` for RTL-sensitive glyphs (`ArrowBack`, `Sort`, `ReceiptLong`, `ListAlt`, `Send`) — use `Icons.AutoMirrored.Filled.*`.

## Output Format

Produce a single markdown report with this exact structure:

```
# UI Audit — <scope>

## Summary
- Files scanned: N
- 🔴 Blockers: N     (M unique files)
- 🟡 Warnings: N     (M unique files)
- ⚪ Nits: N         (M unique files)

## 🔴 Blockers

### <Category name>
- `file.kt:123` — <what's wrong>, <one-line fix hint>
- `file.kt:456` — ...

<repeat per category; empty categories omit>

## 🟡 Warnings
<same structure>

## ⚪ Nits
<same structure>

## Top 3 recommended fixes
Rank the 3 findings with the highest user-visible impact (not just count).
For each: file, one-line what/why, estimated scope (one file / few files / systemic).

## Not audited
List anything explicitly skipped and why (e.g. "data/ layer — no UI", "test files — no UI").
```

## Rules for the audit

- **READ-ONLY**. Do not Edit/Write any source file during the audit.
- Findings must cite `file:line`. Approximate ranges (`"around line 100"`) are not acceptable.
- Don't flag patterns inside `designsystem/components/` — that code *defines* the components.
- Don't flag patterns inside `*Test.kt` files unless the test explicitly validates UI rendering.
- If a file has > 10 hits for one pattern, summarize: `file.kt — 23 occurrences (samples: L12, L45, L98)`.
- Dedupe: if the same `file:line` violates multiple categories, pick the most severe and mention the rest in a sub-bullet.
- When a finding hinges on context ("is this dialog a success dialog?"), `Read` the file around the line before flagging. Don't guess.
- At the end, ask: *"¿Qué hallazgo empiezo a arreglar?"* — do not proceed to fixes without the user's pick.

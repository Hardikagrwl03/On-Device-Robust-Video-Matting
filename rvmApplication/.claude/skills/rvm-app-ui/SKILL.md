---
name: rvm-app-ui
description: Compose UI conventions for the RVM app -- the fixed brand theme, screen/navigation structure, and the reusable card, badge and toast patterns to copy when adding UI. Use when adding a screen or control, restyling anything, or wondering why dynamic colour and Material defaults are deliberately overridden.
---

# RVM app: UI conventions

All UI is Jetpack Compose under `ui/`. Design rationale lives in
`docs/ui-redesign-plan.md` ("Key decision 3" is the visual system).

## Theme -- fixed, not Material You

`ui/theme/Theme.kt` sets `USE_DYNAMIC_COLOR = false` **on purpose**:

- one look across devices is not achievable from a per-device wallpaper,
- the app's own content is achromatic (an alpha matte is black and white), so
  the chrome palette is the only colour there is and must be chosen,
- every contrast ratio in the design was measured against these exact values.

Do not reintroduce `dynamicLightColorScheme`/`dynamicDarkColorScheme`, and
**always use `MaterialTheme.colorScheme` roles, never literal colours** -- both
light and dark schemes define every `surfaceContainer*`, `primaryContainer`,
`secondaryContainer` and `errorContainer` role.

`RvmShapes` (10/16/20/28 dp) restyles cards/sheets/surfaces. Buttons stay pills
regardless: `ButtonDefaults.shape` resolves to `CornerFull` -> `CircleShape`
independently of `MaterialTheme.shapes`.

## Navigation

There is **no `androidx.navigation`**. `MainActivity` holds a local
`private enum class RvmDestination { HOME, MATTE, MODELS }` in `rememberSaveable`,
with `BackHandler(enabled = destination != HOME)`. Add a destination by
extending the enum and the `when` -- the back handler already covers it.

`MainActivity` collects `uiState` eagerly so `MatteViewModel` is constructed at
launch and its model load starts in the background. Do not "optimise" that away.

## Patterns to copy

**Tappable card** (`HomeScreen.HomeActionTile`, `ModelsScreen.ModelCard`):

```kotlin
Surface(
    onClick = ..., enabled = ...,
    shape = MaterialTheme.shapes.extraLarge,
    color = containerColor, contentColor = contentColor,
    modifier = Modifier.fillMaxWidth()
) {
    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) { ... }
}
```

Subtitles use `bodySmall` at `LocalContentColor.current.copy(alpha = 0.8f)`.

**Badge chip**: `Surface(shape = CircleShape, color = surfaceContainerHighest,
contentColor = onSurfaceVariant)` with `labelSmall` at 10 dp / 4 dp padding.

**State by colour**: an installed/selected item switches **both** container and
content colour (`primaryContainer`/`onPrimaryContainer`); failures use
`errorContainer`/`onErrorContainer`. Colour carries the state; icons reinforce it.

> Keep trailing elements the same width across sibling cards. A badge *plus* an
> icon on one card wrapped its subtitle to a second line and made that row taller
> than the rest -- which is why installed model rows show the check icon alone.

**Section header**: `labelLarge` / `onSurfaceVariant`, matching `ConfigSheet`'s
field labels.

**Messages**: transient feedback from the ViewModel goes through
`MatteUiState.transientMessage` -> Snackbar (`saved_to_gallery`). Direct
confirmations from a callback use `Toast` (`config_applied`,
`error_original_needs_cpu`). Both exist; match the surrounding code.

## Hard rules

- **No hardcoded UI strings.** Everything goes in `res/values/strings.xml`.
- **Use `ButtonDefaults` constants** (`IconSize`, `IconSpacing`,
  `TextButtonContentPadding`), never dp literals for button metrics.
- **`Material3 SegmentedButton` hardcodes `Modifier.weight(1f)`** on every
  segment and appends it *after* your modifier, so equal widths cannot be
  overridden. `MatteScreen.OutputKindRow` is a hand-rolled replacement using the
  public `SegmentedButtonDefaults.itemShape`/`.BorderWidth` plus the token
  colours; `SegmentedButtonColors`' resolution methods are `internal`.
- **`ConfigSheet` narrows source -> resolution -> backbone -> downsample**, each
  `LaunchedEffect` snapping a stale selection back in range. A new dimension must
  join that chain, and must handle the list going empty (nothing installed).

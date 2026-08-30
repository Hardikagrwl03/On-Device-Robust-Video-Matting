# UI/UX Redesign Plan — RVM

A phased plan for the home-screen + matting-screen redesign. Same convention as `docs/ui-plan.md`: each phase is a self-contained instruction that can be handed to a fresh session on its own, followed by a check you can run on a device before starting the next.

**Target requirements**
1. Make the app more aesthetically pleasing overall — *colourful yet simple and elegant, very readable, one common UI theme across the whole app.*
2. A home/landing screen: wordmark, logo, tagline, two action buttons.
3. The live-preview button shows a "Coming soon" toast.
4. The video button navigates to the matting screen.
5. The matting screen fits without scrolling; one-word import button, a matting-run button (not "Relight"), a reset button.
6. A well-placed configure **button** opening the existing sheet; Apply confirms with a toast.
7. Let the user pick matte vs. foreground output, and keep output playback in sync with the input's.
8. A Save button; outputs written to cache during a run, copied to the shared gallery only on Save.

> **Revision note (this version).** Two pieces of review feedback are folded in. (a) The configure affordance is no longer a bare toolbar glyph — see Phase 3 and *Key decision 3 §7*. (b) A whole-app visual system now exists: a fixed, deliberate colour scheme replaces wallpaper-derived dynamic colour, and one role/shape/size assignment is applied identically across every phase — see the new **Key decision 3**. Phases 1, 2, 3, 5, 6, 7 and 8 changed as a result; Phase 4 is unchanged. Two readability defects in the previous draft (the disabled "Coming soon" chip at 1.88:1, and a preview label chip whose legibility flipped with the theme) are fixed.

---

## Verified starting state (as of `9c21dd9`, the current `HEAD`)

Confirmed by reading the files — the plan below depends on these being true.

> The previous revision of this document said "as of `e5e158e`". That was a mislabel: `e5e158e` predates `MatteScreen.kt`, `ConfigSheet.kt`, `MatteViewModel.kt` and `Theme.kt`, all of which arrived in `9c21dd9`. Every fact in the table was and is true of `9c21dd9`; only the header was wrong. Corrected here.

| Fact | Status |
| --- | --- |
| `MainActivity` uses an explicit `viewModelFactory { initializer { MatteViewModel(application, createSavedStateHandle()) } }` | ✅ confirmed, lines 16–22. **Must not be reverted to a bare `by viewModels()`.** |
| `MatteScreen(viewModel: MatteViewModel)` — single param, `Scaffold` + `verticalScroll(rememberScrollState())` | ✅ confirmed (`MatteScreen.kt:100` top bar, `:108` scroll) |
| `VideoPlayer(uri: Uri?, contentDescription: String, modifier: Modifier = Modifier)` wraps `android.widget.VideoView` with `setZOrderOnTop(true)` and manual `layoutParams` aspect math in `setOnPreparedListener` | ✅ confirmed, `MatteScreen.kt:322` (file is 372 lines) |
| `ConfigCard` + `ConfigRow` render the ~200 dp config block; a full-width `OutlinedButton` opens the sheet | ✅ confirmed, `MatteScreen.kt:194`, `:196`, `:242` |
| `ConfigSheet(visible, config, isRunning, onApply, onDismiss)` — Apply calls `onApply(newConfig)` then `dismiss()`, no confirmation | ✅ confirmed, `ConfigSheet.kt:73` and `:206` |
| `Controller.matteVideo(onProgress)` writes `alphamatte.mp4` + `fgr.mp4` into `context.getExternalFilesDir(null)`, returns the matte `Uri`, sets `outputFgrVideoUri` | ✅ confirmed, `Controller.kt:91–139` |
| `outputFgrVideoUri` is never read by `MatteViewModel`/`MatteUiState`/`MatteScreen` | ✅ confirmed — `MatteUiState` has only `outputVideoUri` |
| `MatteUiState.Stage.CONFIGURING` is declared but never assigned anywhere | ✅ confirmed — dead |
| `RvmTheme` = dynamic colour only; no `Typography`, no `Shapes`, no `Type.kt`/`Shape.kt`/**`Color.kt`** | ✅ confirmed, `Theme.kt` is 28 lines: `if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)`, then `MaterialTheme(colorScheme, content)` |
| **No** `res/values-night/` directory exists | ✅ confirmed — `res/values/` only. Phase 1 creates `values-night/colors.xml`. |
| `res/values/colors.xml` holds only the stock `purple_200`/`purple_500`/`purple_700`/`teal_200`/`teal_700`/`black`/`white` entries | ✅ confirmed — and nothing references them (`activity_main.xml` was deleted in `9c21dd9`; `themes.xml` names none of them). Phase 1 replaces the file. |
| `themes.xml` → `Theme.RVM` parent `Theme.Material3.DayNight.NoActionBar`, no attributes set | ✅ confirmed |
| `androidx.compose.material3:material3` resolves to **1.4.0** via Compose BOM `2026.02.01` | ✅ confirmed in the Gradle cache |
| `material3` 1.4.0 exposes public `lightColorScheme(...)` / `darkColorScheme(...)` with **every** role as a named parameter — including `surfaceContainer`, `surfaceContainerLow/Lowest/High/Highest`, `surfaceBright`, `surfaceDim` and the `*Fixed` roles | ✅ confirmed by reading `commonMain/androidx/compose/material3/ColorScheme.kt:844` and `:946` in the sources jar |
| material3 1.4.0 has **no public seed-based palette generator**: `TonalPalette` is `internal` (`TonalPalette.kt:35`), `dynamicTonalPalette(context)` is `internal` (`DynamicTonalPalette.android.kt:33`), `expressiveLightColorScheme()` is `internal`, and there is **no** `dynamicLightColorScheme(seed: Color)` overload — only `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)` | ✅ confirmed. **Consequence: a fixed scheme must be written out as explicit hex per role. There is no supported in-app way to generate one from a seed at runtime.** |
| Role→tone mapping in this version is the classic `v0_210` token set: light `primary=P40, onPrimary=P100, primaryContainer=P90, onPrimaryContainer=P10, surface=N98, onSurface=N10, surfaceContainer=N94, …`; dark `primary=P80, onPrimary=P20, primaryContainer=P30, onPrimaryContainer=P90, surface=N6, onSurface=N90, surfaceContainer=N12, …` | ✅ confirmed by reading `tokens/ColorLightTokens.kt` and `tokens/ColorDarkTokens.kt` |
| `ButtonDefaults.shape` / `filledTonalShape` / `elevatedShape` resolve to `ShapeKeyTokens.CornerFull`, and `Shapes.fromToken` maps `CornerFull → CircleShape` **independently of `MaterialTheme.shapes`**; `CornerSmall/Large` *do* read `MaterialTheme.shapes.small/large` | ✅ confirmed, `Shapes.kt:334–349`, `Button.kt:550`. So overriding `Shapes` in Phase 1 restyles cards/sheets/surfaces but leaves buttons as pills — which is what we want. |
| `ButtonDefaults.IconSize = 18.dp`, `ButtonDefaults.IconSpacing`, `ButtonDefaults.ButtonWithIconContentPadding` all exist | ✅ confirmed, `Button.kt:488, 533–545` — use these, never literals |
| `FilledTonalIconButton`, `FilledIconButton`, `OutlinedIconButton`, `FilledTonalButton`, `OutlinedButton`, `AssistChip`, `SingleChoiceSegmentedButtonRow` all exist in 1.4.0; `IconButtonDefaults.filledShape` resolves to `SmallIconButtonTokens.ContainerShapeRound` (a full-round container) | ✅ confirmed in `IconButton.kt`, `IconButtonDefaults.kt:849–857`, `Button.kt`, `Chip.kt` |
| **No** `material-icons-core` / `material-icons-extended` in the build | ✅ confirmed — and `material3:1.4.0`'s Gradle module metadata does **not** pull it transitively. **`Icons.*` will not compile today.** |
| Compose BOM `2026.02.01` manages `material-icons-core`/`material-icons-extended` at `1.7.8` | ✅ confirmed by reading the BOM POM — so the icons artifact can be added **without a version literal** |
| **No** `androidx.navigation` | ✅ confirmed |
| **No** Media3 / ExoPlayer | ✅ confirmed in the build (media3 `1.2.1` happens to be in the machine's Gradle cache from an unrelated project — irrelevant, we pin our own version) |
| `minSdk = 35`, `targetSdk = 36`, `compileSdk = 37`, AGP `9.3.1`, Kotlin `2.2.10` | ✅ confirmed |
| Release build has `optimization { enable = false }` (R8 off) | ✅ confirmed — relevant to the icons-artifact size note |
| `strings.xml` has exactly the 27 entries listed in the brief, `output_video_label` = **"Processed Video"** (not "Output Video") | ✅ confirmed |
| No in-app logo drawable; only `ic_launcher_background.xml` / `ic_launcher_foreground.xml` (the stock Android green robot) | ✅ confirmed |
| `assets/` holds exactly 4 `.tflite` files, all `720x1280`, backbones `resnet50`/`mobilenetv3`, tags `100`/`auto` | ✅ confirmed |

### Known constraints this redesign does **not** fix (call out, don't touch)

- **Outputs are silent and fixed at the config resolution.** `matteEncoder.startVideoEncoder(matteFile, config.width, config.height, …)` passes `width = config.width (1280)`, `height = config.height (720)`, and the encoder writes no audio track. So both outputs are 1280×720, mute, `fps` = the input's `fps`, and `frames` = `durationMs * fps / 1000` (`VideoFrameDecoder:49`). Consequence for Phase 6: input and output durations agree to within a frame or two, which is what makes position-based sync tractable — but the *aspect ratios* can differ from the input's, so each preview must fit its own aspect independently.
- **The pipeline effectively assumes a 720×1280 input.** `VideoFrameDecoder.getNextFrame` loads a bitmap at the *source video's* dimensions into a buffer sized from `config.height * config.width`. A non-720×1280 input under- or over-fills that buffer. The redesign makes this more visible (input and output side by side), but fixing it is out of scope.
- `MatteConfig.buildModelFileName()` computes `dtypeTag` and never uses it (`MatteConfig.kt:40`). Cosmetic; leave it.

---

## Key decision 1: navigation — plain state switching, **not** `navigation-compose`

*(Unchanged from the previous revision.)*

**Recommendation: a `rememberSaveable` destination enum inside a new `RvmApp()` composable in `MainActivity.kt`, plus `BackHandler`.** Reasons specific to this project:

- **Two destinations, no arguments, no deep links.** `navigation-compose` earns its keep on argument passing, deep links, and nested graphs. None apply. Type-safe routes would additionally drag in the `kotlinx-serialization` Gradle plugin.
- **`MatteViewModel` must stay activity-scoped, and that's the fragile part.** It owns `Controller`, which owns `MatteModule` → a live TFLite interpreter with 100 MB+ of weights and per-frame recurrent hidden state. Under `NavHost`, `viewModel()` defaults to the **`NavBackStackEntry`** scope, so an accidental re-creation on navigation would reload the model — or worse, orphan an interpreter that only `onCleared()` closes (`MatteViewModel.kt:207`). With state switching the ViewModel is unambiguously created once, by the activity, using the existing explicit factory.
- **Back is 4 lines.** `BackHandler(enabled = destination != HOME) { destination = HOME }` — `androidx.activity.compose.BackHandler` is already on the classpath via `activity-compose`.
- **Zero new dependencies, zero new build config.**

Revisit when the live-camera screen actually lands (3 destinations, camera-permission flow, possibly arguments) — at that point `navigation-compose` starts paying for itself, and the migration is mechanical because each screen is already a plain composable taking callbacks.

## Key decision 2: playback — migrate to **Media3 ExoPlayer**, `media3-exoplayer` only

*(Unchanged from the previous revision.)*

This is a new dependency, so it needs to be justified. It buys **three** things, not one:

1. **Frame-accurate seeking.** `VideoView` → `MediaPlayer.seekTo(int)` snaps to the previous sync frame. `VideoFrameEncoder` sets `KEY_I_FRAME_INTERVAL = 1` (`VideoFrameEncoder.kt:60`), so the outputs have keyframes only once per second — a `VideoView`-based follower would quantise to ~1 s. ExoPlayer's `setSeekParameters(SeekParameters.EXACT)` decodes forward from the preceding keyframe and lands on the requested position.
2. **Soft sync without seeking.** `Player.setPlaybackSpeed(1.02f)` lets the follower *drift back into* alignment smoothly. `MediaPlayer` on a `VideoView` exposes no equivalent, so a `VideoView` follower can only correct by seeking — i.e. by visibly jumping.
3. **It removes the `setZOrderOnTop(true)` hack and unblocks the whole aesthetic brief.** `VideoView` is a `SurfaceView`; z-ordered on top it punches through the window and **ignores Compose clipping, alpha, and animation entirely**. Rounded-corner preview cards, cross-fades between matte and foreground, overlay label chips and scrim overlays are all impossible today. ExoPlayer can render into a plain `TextureView` via `player.setVideoTextureView(...)`, which is an ordinary View in the hierarchy — it clips, alphas and animates like anything else.

**Cost:** ~1.5 MB of code across `media3-exoplayer` and its transitive `media3-common`/`datasource`/`decoder`/`extractor`. Against 240 MB of `.tflite` assets already in `assets/`, this is noise. We do **not** need `media3-ui` — `PlayerView` brings a whole XML control surface we'd fight; a bare `TextureView` driven from Compose is smaller and gives full layout control.

**Residual risk:** `TextureView` costs one extra GPU copy per frame versus `SurfaceView`, and we now run two concurrent AVC decoders. Both are fine at 720p on any API-35 device, but they are real, so Phase 6 carries an explicit Plan B (single shared player, three-way Input/Matte/Foreground switcher) with a stated decision point.

---

## Key decision 3: visual design system — a **fixed brand colour scheme**, one role assignment, one shape/size scale

Requirement 1 is now specific: *colourful yet simple and elegant, very readable, one common theme across the app.* That is a set of constraints, not a mood, and it forces four decisions that the previous revision left implicit. This section owns all four. **Every later phase draws from the tables here and adds nothing of its own.**

### 1. Dynamic colour is **off**. A fixed, deliberate scheme is the app's identity.

The app today is pure Material You: `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)`, derived from the user's wallpaper. Recommendation: **replace it with a fixed scheme, and keep the dynamic branch alive only behind a `private const val USE_DYNAMIC_COLOR = false` in `Theme.kt`.**

Reasoning, in the order that matters:

- **"A common UI theme across the app" is not achievable with wallpaper-derived colour.** It is a common theme across *one device*. Two users get two different apps; the same user gets a different app after changing wallpaper. Nothing in this plan's design language — the tonal relationships below, the "warm accent means you have a result" rule, the medallion on the home screen — survives being re-hued at runtime by something outside the app.
- **This app's content is achromatic, and that makes the palette load-bearing.** An alpha matte is literally black-and-white; the foreground output is a subject on black. The UI chrome is the *only* colour on the matting screen. A wallpaper-derived scheme can land on a low-chroma grey (looks unfinished against grey content), on a green (collides with every green-screen/compositing association the app trades on), or on a bright warm hue that fights the near-black video plate. A fixed cool accent is guaranteed to read as *chrome* against *content* in every install.
- **Contrast can only be verified once.** Every ratio in §5 below is a checked number. Under dynamic colour they are whatever the wallpaper produced — Material's own generator keeps role pairs safe, but the *specific* combinations this design uses (a fixed dark video plate under a translucent chip, an accent used at small sizes on `surfaceContainerHigh`) are not ones the generator guarantees.
- **There is no supported way to have it both ways cheaply.** Material3 1.4.0 exposes no public seed→palette generator (verified above: `TonalPalette` and `dynamicTonalPalette` are `internal`, no seed overload exists). Harmonising the brand accent into a dynamic scheme would mean vendoring `material-color-utilities` — a real dependency and real code, for an outcome that is by definition undesignable.

**Why keep the flag rather than delete the branch:** it is two lines and one `if`, it documents the decision at the point of use, and it lets you A/B the wallpaper palette on a device in 30 seconds if you ever want to revisit. It is not user-facing and there is no settings toggle — a theme picker is scope this brief did not ask for.

### 2. The seed: a deep cyan-teal, hue 203.5°

**Primary key colour = `#00696E`** (the tone-40 step; the palette is generated around its hue, not around this literal).

Why this hue, tied to what the app does:

- **Cyan-teal is the compositing/broadcast accent.** Video tooling (Resolve, Premiere, scopes, waveform monitors) sits in cool teals and blues. It reads "signal, timeline, transport" rather than "productivity app".
- **It is maximally distinct from the content.** The matte is neutral grey, the foreground plate is black, the subject is usually skin-toned. Cyan is far from all three in hue *and* is the complement of skin tones, so chrome never gets confused with content — which is exactly the elegance requirement ("the video draws the eye, not the frame around it").
- **It is not the green screen.** Deliberately: a green UI would imply the app *is* the chroma-key workflow it replaces. Teal is adjacent enough to carry the association, far enough not to make the claim.
- **It is not the stock Material purple.** Currently the app inherits `Theme.Material3.DayNight` baseline purple at launch (window background) and a wallpaper palette after — neither says anything.

**Tertiary = warm amber/copper, hue 62°** (`#8C4F1B` at tone 40). Chosen as a true complement to the primary so the one warm accent in the app is unmistakably an accent. Its single job is defined in §6: *warm means you have a result.*

**Secondary** is the same hue as primary at low chroma — it is the "control" colour (see §6) and must never compete with primary. **Neutrals** carry a faint cyan cast (Lab chroma 2, neutral-variant 4) so greys sit in the same family as the accent instead of looking like a second, unrelated palette.

### 3. How the palette was produced, and why it is written out as literals

There is no runtime generator available (verified), so the ramps are computed once, offline, and pasted in. They were generated by holding the hue constant in CIELAB and stepping L\* to each Material tone — **Material's "tone" *is* CIE L\***, which was verified empirically: every baseline M3 tone-40 token (`#6750A4`, `#00696E`, `#B3261E`, `#7D5260`) measures L\* = 40.0 ± 0.1. Chroma per tone was clipped to the sRGB gamut and capped by the chroma profile measured off Material's own baseline primary ramp (constant ≈ 51 through tone 70, then 36.5 at tone 80, 18.3 at 90, 9.8 at 95) so the light containers land in the same perceptual place as stock Material containers rather than turning neon — cyan has a much wider gamut than purple at high tones, and an unclipped ramp produces a `#65F7FF` `primaryContainer`, which is the opposite of elegant.

This is close to but not bit-identical with Google's HCT solver (HCT uses CAM16 hue and viewing-condition-corrected chroma). That does not matter: the output is a hand-verified palette, not a claim of algorithmic equivalence, and §5 checks the only property that actually matters.

**Tonal ramps** (paste into `Color.kt`):

| Tone | Primary (h 203.5) | Secondary (h 203.5) | Tertiary (h 62) | Neutral (h 203.5) | NeutralVariant (h 203.5) |
| --- | --- | --- | --- | --- | --- |
| 0 | — | — | — | `#000000` | — |
| 4 | — | — | — | `#090F10` | — |
| 6 | — | — | — | `#0F1414` | — |
| 10 | `#002022` | `#002022` | `#2C1700` | `#181C1C` | — |
| 12 | — | — | — | `#1C2021` | — |
| 17 | — | — | — | `#262B2B` | — |
| 20 | `#003739` | `#153537` | `#4D2600` | `#2D3131` | — |
| 22 | — | — | — | `#313636` | — |
| 24 | — | — | — | `#363A3A` | — |
| 30 | `#004F53` | `#2C4C4E` | `#6F3802` | — | `#3F4849` |
| 40 | `#00696E` | `#446466` | `#8C4F1B` | — | — |
| 50 | — | — | — | — | `#6F7979` |
| 60 | — | — | — | — | `#889393` |
| 80 | `#61D7DF` | `#AACDCF` | `#F5BA8F` | — | `#BDC9C9` |
| 87 | — | — | — | `#D5DBDB` | — |
| 90 | `#B6ECEF` | `#C6E9EB` | `#FEDCC5` | `#DEE3E4` | `#D9E5E5` |
| 92 | — | — | — | `#E4E9E9` | — |
| 94 | — | — | — | `#E9EFEF` | — |
| 95 | — | — | — | `#ECF2F2` | — |
| 96 | — | — | — | `#EFF5F5` | — |
| 98 | — | — | — | `#F5FAFB` | — |
| 100 | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | — |

**Error roles are left at the Material baseline** (don't pass them to `lightColorScheme`/`darkColorScheme`). The baseline error red is well-tested, universally legible, and *should* look foreign to the brand palette — that is what makes an error read as an error. This is a deliberate exception to "one palette", stated so nobody "fixes" it later.

### 4. Role assignment (the tone mapping is Material's own `v0_210`, verified against this version's tokens)

**Light:**

| Role | Value | Role | Value |
| --- | --- | --- | --- |
| `primary` | `#00696E` | `surface` / `background` / `surfaceBright` | `#F5FAFB` |
| `onPrimary` | `#FFFFFF` | `onSurface` / `onBackground` | `#181C1C` |
| `primaryContainer` | `#B6ECEF` | `surfaceVariant` | `#D9E5E5` |
| `onPrimaryContainer` | `#002022` | `onSurfaceVariant` | `#3F4849` |
| `inversePrimary` | `#61D7DF` | `surfaceDim` | `#D5DBDB` |
| `secondary` | `#446466` | `surfaceContainerLowest` | `#FFFFFF` |
| `onSecondary` | `#FFFFFF` | `surfaceContainerLow` | `#EFF5F5` |
| `secondaryContainer` | `#C6E9EB` | `surfaceContainer` | `#E9EFEF` |
| `onSecondaryContainer` | `#002022` | `surfaceContainerHigh` | `#E4E9E9` |
| `tertiary` | `#8C4F1B` | `surfaceContainerHighest` | `#DEE3E4` |
| `onTertiary` | `#FFFFFF` | `outline` | `#6F7979` |
| `tertiaryContainer` | `#FEDCC5` | `outlineVariant` | `#BDC9C9` |
| `onTertiaryContainer` | `#2C1700` | `inverseSurface` | `#2D3131` |
| `scrim` | `#000000` | `inverseOnSurface` | `#ECF2F2` |

**Dark:**

| Role | Value | Role | Value |
| --- | --- | --- | --- |
| `primary` | `#61D7DF` | `surface` / `background` / `surfaceDim` | `#0F1414` |
| `onPrimary` | `#003739` | `onSurface` / `onBackground` | `#DEE3E4` |
| `primaryContainer` | `#004F53` | `surfaceVariant` | `#3F4849` |
| `onPrimaryContainer` | `#B6ECEF` | `onSurfaceVariant` | `#BDC9C9` |
| `inversePrimary` | `#00696E` | `surfaceBright` | `#363A3A` |
| `secondary` | `#AACDCF` | `surfaceContainerLowest` | `#090F10` |
| `onSecondary` | `#153537` | `surfaceContainerLow` | `#181C1C` |
| `secondaryContainer` | `#2C4C4E` | `surfaceContainer` | `#1C2021` |
| `onSecondaryContainer` | `#C6E9EB` | `surfaceContainerHigh` | `#262B2B` |
| `tertiary` | `#F5BA8F` | `surfaceContainerHighest` | `#313636` |
| `onTertiary` | `#4D2600` | `outline` | `#889393` |
| `tertiaryContainer` | `#6F3802` | `outlineVariant` | `#3F4849` |
| `onTertiaryContainer` | `#FEDCC5` | `inverseSurface` | `#DEE3E4` |
| `scrim` | `#000000` | `inverseOnSurface` | `#2D3131` |

**Two extended (non-Material) colours** — these are fixed in *both* themes and are the only colours in the app that do not come from `MaterialTheme.colorScheme`:

| Name | Value | Purpose |
| --- | --- | --- |
| `RvmVideoPlate` | `#090F10` (Neutral 4) | The background of every video preview, in light **and** dark mode. |
| `RvmOnVideoPlate` | `#DEE3E4` (Neutral 90) | Text/icons drawn on the plate or over video. |

**Why the video plate is theme-independent** — this is a design decision, not an oversight. The foreground output is *rendered on black*; a light-mode preview box would put a bright frame around a black image and make the letterbox bars a hard visible edge. Every serious media surface (Photos, YouTube, any NLE viewer) uses a dark plate regardless of theme, because the plate must not influence the perception of the content sitting on it. Keeping it fixed also means the overlay chip and transport control are designed against *one* known background instead of two, which is what makes the contrast numbers in §5 hold. The plate is one tone *darker* than dark-mode `surface` so it still reads as a recessed well in dark mode; a 1 dp `outlineVariant` border defines the rounded edge in both themes.

### 5. Readability — measured, not asserted

WCAG 2.1 contrast ratios, computed on the exact hex values above. AA body text needs 4.5:1; AAA needs 7:1.

| Pair | Light | Dark |
| --- | --- | --- |
| `onPrimary` on `primary` (the filled Matte button) | **6.47** | **7.66** |
| `onPrimaryContainer` on `primaryContainer` (home hero tile, medallion) | **13.20** | **7.24** |
| `onSecondaryContainer` on `secondaryContainer` (configure pill, selected segment, transport button) | **13.22** | **7.22** |
| `onTertiaryContainer` on `tertiaryContainer` (Save) | **13.22** | **7.25** |
| `onSurface` on `surface` (body, wordmark) | **16.33** | **14.34** |
| `onSurfaceVariant` on `surface` (tagline, status strip) | **8.93** | **10.94** |
| `onSurface` on `surfaceContainer` (bottom action bar) | **14.78** | **12.69** |
| `onSurfaceVariant` on `surfaceContainerHigh` (Live tile subtitle) | **7.67** | **8.45** |
| `onSurface` on `surfaceContainerHighest` (badge text) | **13.27** | **9.47** |
| `primary` on `surface` (progress indicator, logo tint) | **6.15** | **10.88** |
| `tertiary` on `surface` | **6.14** | **10.88** |
| `outline` on `surface` (borders only, not text) | 4.26 | 5.88 |

Every text/icon pair clears AA; all but the two 6.1–6.5 accent pairs clear AAA. `outline` at 4.26 is used only for 1 dp borders, never for text — that matches Material's own baseline (its `outline` measures ≈ 4.2 too).

**On the video plate** (`RvmVideoPlate #090F10`):

| Pair | Ratio |
| --- | --- |
| `RvmOnVideoPlate` on the plate (empty-state text, letterbox area) | **14.91** |
| Label chip = plate at **72 % alpha** + `#ECF2F2` text, over a **pure white** video frame (worst case) | **6.98** |
| …over a mid-grey frame | 12.00 |
| …over a black frame | 17.48 |

72 % is the chosen alpha because it is the lowest value that keeps the worst case (white video under the chip) above 4.5:1 with margin — 60 % drops it to 4.25 and fails.

**Two readability defects in the previous revision, now fixed:**

1. Phase 2 specified the "Coming soon" badge as `AssistChip(onClick = {}, enabled = false, …)`. A disabled M3 chip renders its label at 38 % alpha: `onSurfaceVariant` at 0.38 over `surfaceContainerHigh` measures **1.88:1**. That is unreadable and it is also semantically wrong — the badge is a *label*, not a disabled control. Replaced with a full-opacity `Surface` badge measuring **7.26:1** (light) / **7.22:1** (dark).
2. Phase 3 specified the preview label chip as `surface.copy(alpha = 0.75f)`. Because `surface` flips from near-white to near-black with the theme, the chip's legibility over video inverted between modes and was undefined over mid-grey content. Replaced with the fixed plate scrim above.

### 6. Colour role assignment — one rule per role, applied everywhere

This is the table that makes the app look like one product. **A phase may not introduce a colour usage that isn't on it.**

| Role | Means | Allowed uses (exhaustive) |
| --- | --- | --- |
| `primary` (filled) | *The* action on this screen. **At most one filled `primary` element visible at a time.** | The Matte run button; `LinearProgressIndicator`; the logo tint on the home screen |
| `primaryContainer` | The hero surface — the thing the screen exists for | The Video Matte tile; the home logo medallion |
| `secondaryContainer` | **Controls**: things you operate, and their selected state | The configure pill; the selected segment of the Matte/Foreground switcher (M3's own `SegmentedButton` default — the system and Material agree here); the play/pause transport button (`filledTonalIconButtonColors()` default) |
| `tertiaryContainer` | **"You have a result."** The only warm colour in the app; appears **only** after a successful run | The Save button. Nothing else. |
| `surfaceContainer` | Full-bleed chrome at a screen edge | The bottom action bar |
| `surfaceContainerHigh` | Inert blocks inside the padded content column | The Live Matte tile |
| `surfaceContainerHighest` | Small labels that must read against `surfaceContainerHigh` | The "Coming soon" badge |
| `onSurfaceVariant` | Secondary text | Tagline, tile subtitles, status strip, config summary text |
| `error` | Errors only | `StatusStrip` in `ERROR`, snackbar error text |
| `RvmVideoPlate` / `RvmOnVideoPlate` | Media | Preview backgrounds, the label chip, empty-state art |

Note what this rules out, and why each was tempting: the "Coming soon" badge is **not** tertiary (a warm accent on a dead end pulls the eye toward the one thing that doesn't work); the DONE status text is **not** tertiary (the Save button already carries that signal, and doubling it makes the strip shout); `OutlinedButton` for Reset uses `outline` + `primary` label per M3 default and gets no container (a destructive-ish action should be the quietest of the three).

### 7. The configure control (feedback item 1) — a labelled pill, not a toolbar glyph

The previous revision put configure in `TopAppBar.actions` as a bare `IconButton { Icon(Icons.Filled.Settings) }`. That is the most minimal possible treatment of a control the user explicitly wanted well-placed, it gives no hint of the current model/device/thread state, and — after this revision moves Save out of the top bar too — it would have left a 64 dp bar holding one 24 dp glyph.

**Decision: merge the configure affordance and the config-state readout into one full-width tonal pill directly under the top bar.**

```
┌──────────────────────────────────────────────────────────┐
│ ⚙  Model   resnet50 · 720p · ds 0.25 · CPU · 4 threads  › │   44 dp, secondaryContainer, pill
└──────────────────────────────────────────────────────────┘
```

Why this is the right answer rather than a `FilledTonalIconButton` in the toolbar or a floating pill near the previews:

- **It has real presence without costing layout height.** The previous plan already budgeted a 28 dp `ConfigSummaryRow` for exactly this text; giving it a container and a chevron costs 16 dp total and turns a passive line into an obvious button.
- **A button that shows its own state is better than a button plus a state line.** The old design had two elements (summary row *and* gear icon) doing one job in two places.
- **One entry point, not two.** The gear is removed from the top bar entirely; there is exactly one way into the sheet.
- **It reads as a control at a glance** — tonal container, leading icon, trailing chevron, pill shape — which is the standard "opens a settings surface" idiom, so it needs no learning.
- **A floating pill over the previews was rejected**: the previews are the one place §8 says must stay visually quiet, and an overlay there would also have to survive the light/dark + arbitrary-video-content problem the label chip already solves at higher cost.

### 8. Save (feedback item 1, consistency half) — a labelled tonal pill in the output row

The previous revision made Save a bare `IconButton` in the top bar, designed the same way as the configure glyph and therefore inheriting the same criticism. Reconsidered:

**Decision: Save becomes a labelled `FilledTonalButton` (`tertiaryContainer`) sitting at the end of the output switcher row, appearing with the row when a run completes.**

- **It is placed next to the thing it saves.** Save is meaningless until output exists and the row it lives in is itself conditional on output existing, so the control and its precondition appear and disappear together — no disabled-for-most-of-the-session button.
- **It costs zero layout height.** The switcher row is already 40 dp; Save takes horizontal space next to it (≈ 88 dp of a 379 dp row on a Pixel, ≈ 328 dp on a 360 dp phone — the two-segment switcher still gets ≈ 240 dp).
- **It keeps the bottom bar at exactly the three verbs requirement 5 asks for** (Import / Matte / Reset).
- **It is the one place `tertiary` appears**, which is what makes the warm accent legible as a signal rather than decoration.
- **Consequence: `TopAppBar.actions` is empty.** The bar holds the back arrow and the title, and nothing else. That is intended — see §9.

### 9. Where colour carries weight, and where restraint matters

The two halves of "colourful yet simple and elegant" fight each other, so the split is decided here rather than per phase.

**Colour carries weight on the home screen.** It is the app's one purely presentational surface: nothing on it needs to compete with content. It gets the logo medallion (a 112 dp `primaryContainer` circle behind the mark), a `primaryContainer` hero tile, and the wordmark at 64 sp. This is the screen a user screenshots.

**Colour carries weight on exactly three elements of the matting screen**, and never more than two at once before a run completes:
1. the filled `primary` Matte button (always),
2. the `secondaryContainer` configure pill and the selected switcher segment (always — they are the same pale tone, so they read as one family, not two accents),
3. the `tertiaryContainer` Save button (only after a run).

**Restraint everywhere the video lives.** The previews sit on a fixed near-black plate with a 1 dp `outlineVariant` border and one 72 %-scrim label chip. No coloured borders, no tinted overlays, no coloured empty-state art (the placeholder logo is `RvmOnVideoPlate` at 40 % alpha, not `primary`). The status strip is `onSurfaceVariant` in every state except `ERROR`. The top bar is bare. The rule to apply when in doubt: **if a video frame can appear behind or beside it, it is neutral.**

**Considered and rejected:** a `primaryContainer → surface` vertical gradient behind the home screen. It reads well in mockups, but it puts the tagline and wordmark on a background whose luminance varies down the column, which means the measured contrast in §5 stops being a single number — precisely the kind of "colourful at the cost of readable" trade the brief rules out. The medallion gets the same colour hit inside a shape whose contrast is fixed and checked.

### 10. Shape, size and spacing — one scale, applied identically

| Concern | Rule | Rationale / verification |
| --- | --- | --- |
| **Shape family** | **Anything ≤ 56 dp that is a control or a label → full pill** (M3 default `CornerFull`, which resolves to `CircleShape` independently of `MaterialTheme.shapes` — verified). **Anything larger that is a surface → the corner scale.** | One decision rule, no per-element judgement. Buttons, the configure pill, segmented buttons, icon buttons, the "Coming soon" badge and the preview label chip are all pills; tiles, preview frames and sheets use the scale. |
| **Corner scale** | `small = 10`, `medium = 16`, `large = 20`, `extraLarge = 28` (Phase 1). Home tiles `extraLarge`; preview frames `large`. | Unchanged from the previous revision. |
| **Containerised icon buttons** | Always `FilledTonalIconButton`, default `IconButtonDefaults.filledShape`, **40 dp container, 20 dp icon**, `secondaryContainer`. | Currently only the play/pause transport control. The rule exists so the next one matches. |
| **Icon in a labelled button** | `ButtonDefaults.IconSize` (18 dp) + `ButtonDefaults.IconSpacing` + `ButtonDefaults.ButtonWithIconContentPadding`. **Never a dp literal.** | The previous revision hardcoded `Modifier.size(18.dp)` + `Spacer(6.dp)`; 6 dp is not the M3 spacing token. |
| **Icon sizes elsewhere** | Top-bar nav 24 dp (M3 standard slot — the one deliberate exception); home tile leading icon 28 dp; home logo 56 dp inside a 112 dp medallion; preview empty-state logo 40 dp. | |
| **Button heights** | Bottom action bar 48 dp; every inline/secondary control 40 dp; the configure pill 44 dp. | Three values, each with a stated job. |
| **Spacing** | Multiples of 4 only, and in practice only 4 / 8 / 12 / 16 / 20 / 24. Matting screen: 16 dp horizontal, 12 dp between blocks. Home: 24 dp horizontal, 12 dp between tiles. | |
| **Elevation** | Tonal only (`surfaceContainer*` roles). No `shadowElevation` anywhere. | Shadows under a near-black video plate look like rendering artefacts. |

### 11. What changed in the already-written phases as a result

| Phase | Change |
| --- | --- |
| 1 | Now also creates `Color.kt`, builds `RvmLightColors`/`RvmDarkColors`, replaces `colors.xml`, adds `values-night/colors.xml`, and sets `windowBackground` in `themes.xml` (kills the purple launch flash). |
| 2 | Logo goes into a `primaryContainer` medallion; the badge stops being a disabled `AssistChip` (1.88:1 → 7.26:1); tile icon and container roles restated against §6. |
| 3 | Gear icon in `actions` → full-width configure pill; label chip uses the fixed plate scrim, not `surface.copy(alpha)`; preview background is `RvmVideoPlate`, not `surfaceContainerHighest`; button icons use `ButtonDefaults` tokens; layout budget recomputed. |
| 4 | **Unchanged.** |
| 5 | The switcher is built inside an `OutputBar` row with `Modifier.weight(1f)` **specifically so Phase 7 can drop Save in beside it** without restructuring. |
| 6 | Transport control pinned to the §10 icon-button spec; the empty state uses `RvmOnVideoPlate`, not `onSurfaceVariant`; the 1 dp border added. |
| 7 | Save moves from a bare top-bar `IconButton` to a labelled `tertiaryContainer` pill in the `OutputBar`. |
| 8 | Launcher icon colours are now concrete palette values; adds an explicit consistency audit pass. |

---

## Naming proposals

### Home screen buttons (requirement 2)

| | Primary recommendation | Rationale |
| --- | --- | --- |
| Live camera | **Live Matte** | Parallel with the other button, names the *product action* rather than the input device, and reads naturally with a "Coming soon" badge. |
| Video file | **Video Matte** | Same shape; the pair differs only in the source word, so the grid reads as one choice on one axis (live vs. recorded), which is exactly the choice being made. |

Alternates, if the primary reads too repetitive:
- **Camera** / **Library** — shortest, most conventional, but says nothing about what the app does.
- **Realtime** / **From a Clip** — most descriptive, least parallel.

Order them **Video Matte first** (`primaryContainer` tile), **Live Matte second** (`surfaceContainerHigh` tile with a "Coming soon" badge). Putting the working feature first and visually dominant is better than alphabetising a dead end.

### Tagline

Primary: **"Subject from background, entirely on-device."**
Alternates: *"On-device video matting. No cloud, no upload."* / *"Cut anyone out of any clip, offline."*

### Matting screen action labels (requirement 5)

| Old | New | Rationale |
| --- | --- | --- |
| `select_video` "Select Video" | **`action_import` = "Import"** | One word, as asked. "Import" pairs semantically with "Save" (in / out) in a way "Select" or "Pick" doesn't; "Open" implies a document. |
| `relight` "Relight" | **`action_matte` = "Matte"** | "Relight" is a leftover from an unrelated concept and describes the wrong operation. "Matte" is a real verb in compositing, is the app's own name, and is unambiguous next to "Import" and "Reset". |
| `reset` "Reset" | **unchanged** | Already one word and correct. |

Alternates for the run button if "Matte" reads too jargony: **"Run"**, **"Extract"**, **"Cut Out"**.

### The configure control

Leading label **"Model"** (`labelLarge`) followed by the live summary (`bodySmall`). "Model" beats "Configure"/"Settings" because every knob in the sheet is a model or runtime knob — backbone, resolution, downsample, device, threads — and none of them are app preferences. `configure_content_description` = "Model settings" carries the full phrase for TalkBack.

---

## Layout budget for the non-scrolling matting screen (requirement 5)

Reference window: **411 × 914 dp** (Pixel-class). Subtract a 24 dp status inset and a 24 dp gesture-nav inset → **~866 dp of content height**. `Scaffold` handles both insets for `topBar`/`bottomBar` automatically under the existing `enableEdgeToEdge()`.

| Element | Height | Notes |
| --- | --- | --- |
| `TopAppBar` (small) | 64 | back nav icon + title only; `actions` is empty by design |
| Column padding (12 top + 12 bottom) | 24 | |
| **Configure pill** | **44** | was a 28 dp passive text row; now a real button (§7) |
| `OutputBar` (switcher + Save) | 40 | **only rendered when output exists** |
| Status strip | 28 | **fixed slot**: progress while RUNNING, stats when DONE, hint when IDLE — never changes height, so nothing reflows mid-run |
| Inter-block spacing (4 × 12) | 48 | 5 children in the column → 4 gaps |
| Bottom action bar | 72 | 3 × 48 dp buttons + 12 dp padding each side |
| **Fixed subtotal (output present)** | **320** | |
| **Left for previews** | **546** → **273 dp each** | two weighted previews |

Idle state (no output yet): 3 children, 2 gaps → fixed subtotal **256**, leaving **~610 dp** for a single input preview. That is also the nicest possible idle state, and it is why the `OutputBar` and output preview are conditional rather than disabled.

What shrinks vs. today: the `ConfigCard` (7 rows ≈ 200 dp) collapses into the 44 dp pill; the full-width "Configure" `OutlinedButton` (48 dp + spacing) is absorbed by that same pill; the two `Text` section labels (each ~24 dp) become overlay chips drawn *inside* the preview boxes at zero layout cost; the fixed `.height(240.dp)` on each preview becomes `Modifier.weight(1f)`. Net: one control replaces three elements totalling ~270 dp.

Small-phone check (**360 × 780 dp** window, ~732 dp content): 732 − 320 = 412 → **206 dp per preview**. A 16:9 clip at 360 dp wide is 202 dp tall, so it fits; a portrait clip pillarboxes to ~116 dp wide but stays legible. In the `OutputBar` at 360 dp, Save takes ≈ 88 dp and the two-segment switcher gets ≈ 240 dp — comfortable. **Decision rule for the implementer:** if per-preview height would fall below ~140 dp, fall back to Plan B (one preview, three-way Input/Matte/Foreground switcher) rather than shipping two unreadable thumbnails.

---

## Phase 1 — Colour, type and shape foundation

**Goal:** requirement 1's baseline and all of Key decision 3 §1–§5. Give the app a deliberate palette, type scale and corner language so that every later phase draws from one source instead of inventing its own colours and `letterSpacing`. **Nothing in Phases 2–8 defines a colour.**

**Instruction to give:**

> Create three files under `app/src/main/java/dev/hamster/rvm/ui/theme/` — `Color.kt`, `Type.kt`, `Shape.kt` — rewrite `Theme.kt`, replace `res/values/colors.xml`, add `res/values-night/colors.xml`, and set a window background in `res/values/themes.xml`.
>
> **1. `Color.kt`** — the tonal ramps plus the two extended colours. All `internal`, all `androidx.compose.ui.graphics.Color`:
> ```kotlin
> package dev.hamster.rvm.ui.theme
>
> import androidx.compose.ui.graphics.Color
>
> // Brand tonal ramps. Hue 203.5 (deep cyan-teal) for primary/secondary/neutrals,
> // hue 62 (warm amber) for tertiary. Tone == CIE L*. Generated once and verified for
> // WCAG contrast; see docs/ui-redesign-plan.md "Key decision 3". Do not hand-edit
> // individual values — regenerate the whole ramp if the hue ever changes.
> internal val RvmPrimary10 = Color(0xFF002022)
> internal val RvmPrimary20 = Color(0xFF003739)
> internal val RvmPrimary30 = Color(0xFF004F53)
> internal val RvmPrimary40 = Color(0xFF00696E)
> internal val RvmPrimary80 = Color(0xFF61D7DF)
> internal val RvmPrimary90 = Color(0xFFB6ECEF)
> internal val RvmPrimary100 = Color(0xFFFFFFFF)
>
> internal val RvmSecondary10 = Color(0xFF002022)
> internal val RvmSecondary20 = Color(0xFF153537)
> internal val RvmSecondary30 = Color(0xFF2C4C4E)
> internal val RvmSecondary40 = Color(0xFF446466)
> internal val RvmSecondary80 = Color(0xFFAACDCF)
> internal val RvmSecondary90 = Color(0xFFC6E9EB)
> internal val RvmSecondary100 = Color(0xFFFFFFFF)
>
> internal val RvmTertiary10 = Color(0xFF2C1700)
> internal val RvmTertiary20 = Color(0xFF4D2600)
> internal val RvmTertiary30 = Color(0xFF6F3802)
> internal val RvmTertiary40 = Color(0xFF8C4F1B)
> internal val RvmTertiary80 = Color(0xFFF5BA8F)
> internal val RvmTertiary90 = Color(0xFFFEDCC5)
> internal val RvmTertiary100 = Color(0xFFFFFFFF)
>
> internal val RvmNeutral0 = Color(0xFF000000)
> internal val RvmNeutral4 = Color(0xFF090F10)
> internal val RvmNeutral6 = Color(0xFF0F1414)
> internal val RvmNeutral10 = Color(0xFF181C1C)
> internal val RvmNeutral12 = Color(0xFF1C2021)
> internal val RvmNeutral17 = Color(0xFF262B2B)
> internal val RvmNeutral20 = Color(0xFF2D3131)
> internal val RvmNeutral22 = Color(0xFF313636)
> internal val RvmNeutral24 = Color(0xFF363A3A)
> internal val RvmNeutral87 = Color(0xFFD5DBDB)
> internal val RvmNeutral90 = Color(0xFFDEE3E4)
> internal val RvmNeutral92 = Color(0xFFE4E9E9)
> internal val RvmNeutral94 = Color(0xFFE9EFEF)
> internal val RvmNeutral95 = Color(0xFFECF2F2)
> internal val RvmNeutral96 = Color(0xFFEFF5F5)
> internal val RvmNeutral98 = Color(0xFFF5FAFB)
> internal val RvmNeutral100 = Color(0xFFFFFFFF)
>
> internal val RvmNeutralVariant30 = Color(0xFF3F4849)
> internal val RvmNeutralVariant50 = Color(0xFF6F7979)
> internal val RvmNeutralVariant60 = Color(0xFF889393)
> internal val RvmNeutralVariant80 = Color(0xFFBDC9C9)
> internal val RvmNeutralVariant90 = Color(0xFFD9E5E5)
>
> /**
>  * Extended colours — deliberately identical in light and dark. Every video preview sits on
>  * [RvmVideoPlate] in both themes so the plate never influences how the content on it reads,
>  * and so the foreground output (a subject on black) has no bright frame around it.
>  */
> internal val RvmVideoPlate = RvmNeutral4
> internal val RvmOnVideoPlate = RvmNeutral90
> /** Scrim alpha for chips/controls drawn over live video. 0.72 keeps text ≥ 4.5:1 even over a white frame. */
> internal const val RvmVideoScrimAlpha = 0.72f
> ```
>
> **2. `Theme.kt`** — replace the whole file. Build both schemes with the public `lightColorScheme(...)`/`darkColorScheme(...)` builders (confirmed to accept every role as a named parameter in material3 1.4.0). **Do not pass the error roles** — the Material baseline error red is intentionally retained.
> ```kotlin
> package dev.hamster.rvm.ui.theme
>
> import androidx.compose.foundation.isSystemInDarkTheme
> import androidx.compose.material3.MaterialTheme
> import androidx.compose.material3.darkColorScheme
> import androidx.compose.material3.dynamicDarkColorScheme
> import androidx.compose.material3.dynamicLightColorScheme
> import androidx.compose.material3.lightColorScheme
> import androidx.compose.runtime.Composable
> import androidx.compose.ui.platform.LocalContext
>
> /**
>  * Material You dynamic colour is deliberately OFF. The app's palette is a fixed brand scheme
>  * because (a) "one common theme across the app" is not achievable from a per-device wallpaper,
>  * (b) the app's own content is achromatic — an alpha matte is black and white — so the chrome
>  * palette is the only colour there is and must be chosen, not inherited, and (c) every contrast
>  * ratio this design depends on was measured against these exact values.
>  * Flip this to true only to A/B the wallpaper-derived palette on a device; nothing in the app's
>  * visual language depends on it and there is no user-facing toggle.
>  */
> private const val USE_DYNAMIC_COLOR = false
>
> private val RvmLightColors = lightColorScheme(
>     primary = RvmPrimary40,
>     onPrimary = RvmPrimary100,
>     primaryContainer = RvmPrimary90,
>     onPrimaryContainer = RvmPrimary10,
>     inversePrimary = RvmPrimary80,
>     secondary = RvmSecondary40,
>     onSecondary = RvmSecondary100,
>     secondaryContainer = RvmSecondary90,
>     onSecondaryContainer = RvmSecondary10,
>     tertiary = RvmTertiary40,
>     onTertiary = RvmTertiary100,
>     tertiaryContainer = RvmTertiary90,
>     onTertiaryContainer = RvmTertiary10,
>     background = RvmNeutral98,
>     onBackground = RvmNeutral10,
>     surface = RvmNeutral98,
>     onSurface = RvmNeutral10,
>     surfaceVariant = RvmNeutralVariant90,
>     onSurfaceVariant = RvmNeutralVariant30,
>     inverseSurface = RvmNeutral20,
>     inverseOnSurface = RvmNeutral95,
>     outline = RvmNeutralVariant50,
>     outlineVariant = RvmNeutralVariant80,
>     scrim = RvmNeutral0,
>     surfaceBright = RvmNeutral98,
>     surfaceDim = RvmNeutral87,
>     surfaceContainerLowest = RvmNeutral100,
>     surfaceContainerLow = RvmNeutral96,
>     surfaceContainer = RvmNeutral94,
>     surfaceContainerHigh = RvmNeutral92,
>     surfaceContainerHighest = RvmNeutral90,
> )
>
> private val RvmDarkColors = darkColorScheme(
>     primary = RvmPrimary80,
>     onPrimary = RvmPrimary20,
>     primaryContainer = RvmPrimary30,
>     onPrimaryContainer = RvmPrimary90,
>     inversePrimary = RvmPrimary40,
>     secondary = RvmSecondary80,
>     onSecondary = RvmSecondary20,
>     secondaryContainer = RvmSecondary30,
>     onSecondaryContainer = RvmSecondary90,
>     tertiary = RvmTertiary80,
>     onTertiary = RvmTertiary20,
>     tertiaryContainer = RvmTertiary30,
>     onTertiaryContainer = RvmTertiary90,
>     background = RvmNeutral6,
>     onBackground = RvmNeutral90,
>     surface = RvmNeutral6,
>     onSurface = RvmNeutral90,
>     surfaceVariant = RvmNeutralVariant30,
>     onSurfaceVariant = RvmNeutralVariant80,
>     inverseSurface = RvmNeutral90,
>     inverseOnSurface = RvmNeutral20,
>     outline = RvmNeutralVariant60,
>     outlineVariant = RvmNeutralVariant30,
>     scrim = RvmNeutral0,
>     surfaceBright = RvmNeutral24,
>     surfaceDim = RvmNeutral6,
>     surfaceContainerLowest = RvmNeutral4,
>     surfaceContainerLow = RvmNeutral10,
>     surfaceContainer = RvmNeutral12,
>     surfaceContainerHigh = RvmNeutral17,
>     surfaceContainerHighest = RvmNeutral22,
> )
>
> @Composable
> fun RvmTheme(content: @Composable () -> Unit) {
>     val dark = isSystemInDarkTheme()
>     val context = LocalContext.current
>     val colorScheme = when {
>         USE_DYNAMIC_COLOR && dark -> dynamicDarkColorScheme(context)
>         USE_DYNAMIC_COLOR -> dynamicLightColorScheme(context)
>         dark -> RvmDarkColors
>         else -> RvmLightColors
>     }
>     MaterialTheme(
>         colorScheme = colorScheme,
>         typography = RvmTypography,
>         shapes = RvmShapes,
>         content = content
>     )
> }
> ```
> No `@RequiresApi` guard is needed on the dynamic branch — `minSdk` is 35, above the API 31 floor.
>
> **3. `Type.kt`** exposes `internal val RvmTypography: Typography`. Start from `Typography()` and use `.copy()` on the individual `TextStyle`s so every other style keeps its Material 3 default:
> - `displayLarge`: `fontFamily = FontFamily.SansSerif`, `fontWeight = FontWeight.Light`, `fontSize = 64.sp`, `letterSpacing = 10.sp`, `lineHeight = 72.sp` — the "RVM" wordmark style used by the home screen.
> - `titleLarge`: `fontWeight = FontWeight.SemiBold`, `letterSpacing = 0.sp`.
> - `labelLarge`: `fontWeight = FontWeight.Medium`, `letterSpacing = 0.4.sp` — button labels and the configure pill's "Model" label.
> - `bodySmall`: `lineHeight = 18.sp` — the status strip and the config summary.
>
> Do **not** add any font resources or the `ui-text-google-fonts` dependency; use only the built-in `FontFamily` values so this phase adds zero dependencies.
>
> **4. `Shape.kt`** exposes `internal val RvmShapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp))`. Note that this deliberately does **not** restyle buttons: `ButtonDefaults.shape` resolves to `ShapeKeyTokens.CornerFull`, which `Shapes.fromToken` maps to `CircleShape` regardless of this object. Buttons stay pills; cards, sheets and `Surface`s follow the scale. That split is the shape rule from Key decision 3 §10 and is intended.
>
> **5. Kill the purple launch flash.** `Theme.RVM` currently inherits `Theme.Material3.DayNight`'s baseline (purple-tinted) window background, which is visible for a frame at cold start and does not match anything else in the app any more. Replace `res/values/colors.xml` entirely — the seven stock `purple_*`/`teal_*`/`black`/`white` entries are unreferenced (verify with `grep -rn "@color/" app/src/main` first; `activity_main.xml` was deleted in `9c21dd9`):
> ```xml
> <?xml version="1.0" encoding="utf-8"?>
> <resources>
>     <!-- Neutral98 — must stay in sync with RvmLightColors.surface in Color.kt -->
>     <color name="rvm_window_background">#F5FAFB</color>
> </resources>
> ```
> and create `res/values-night/colors.xml`:
> ```xml
> <?xml version="1.0" encoding="utf-8"?>
> <resources>
>     <!-- Neutral6 — must stay in sync with RvmDarkColors.surface in Color.kt -->
>     <color name="rvm_window_background">#0F1414</color>
> </resources>
> ```
> In `res/values/themes.xml`, keep the parent and add one attribute:
> ```xml
> <style name="Theme.RVM" parent="Theme.Material3.DayNight.NoActionBar">
>     <item name="android:windowBackground">@color/rvm_window_background</item>
> </style>
> ```
> Do **not** set `android:statusBarColor` / `android:navigationBarColor` — they are no-ops under `enableEdgeToEdge()` on API 35.

**Acceptance:** The app builds and launches unchanged in behaviour. The whole UI is now teal-accented rather than wallpaper-coloured, and looks identical on two devices with different wallpapers. Cold start shows a near-white (light) / near-black (dark) window background with no purple flash. `TopAppBar` and button text render with the new weights. Toggle the system dark theme and confirm both schemes are legible with no washed-out text.

---

## Phase 2 — Logo, home screen, navigation, and the "Coming soon" toast

**Goal:** requirements 2, 3 and 4, and the "colour carries weight here" half of Key decision 3 §9. The app launches into a landing screen; the live button toasts; the video button navigates to the existing matting screen; system back returns home.

**New dependency:** `androidx.compose.material:material-icons-extended` (version managed by the Compose BOM at 1.7.8 — do **not** write a version literal). Needed from this phase on: `Icons.AutoMirrored.Filled.ArrowBack`, `Icons.AutoMirrored.Filled.KeyboardArrowRight`, `Icons.Filled.Tune`, `Icons.Filled.Refresh`, `Icons.Filled.PlayArrow`, `Icons.Filled.Pause`, `Icons.Filled.Close`, `Icons.Filled.Save`, `Icons.Filled.VideoLibrary`, `Icons.Filled.Videocam`, `Icons.Filled.AutoAwesome`. `material-icons-core` would cover only about half of these (no `Save`, `Pause`, `VideoLibrary`, `AutoAwesome`, `Tune`), so take `extended`. Note the release build currently has `optimization { enable = false }`, so the artifact ships un-shrunk — a few MB, against 240 MB of `.tflite` assets already in `assets/`.

**Instruction to give:**

> **1. Add the icons dependency.**
> In `gradle/libs.versions.toml`, under `[libraries]`, add (no version — the Compose BOM manages it):
> ```
> androidx-compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
> ```
> In `app/build.gradle.kts`, add to `dependencies` next to the other Compose lines:
> ```
> implementation(libs.androidx.compose.material.icons.extended)
> ```
>
> **2. Add the logo drawable.** Create `app/src/main/res/drawable/ic_rvm_logo.xml` with exactly this content — a rounded film frame containing a subject silhouette that is solid on the left half and ghosted on the right, which is literally what matting does:
> ```xml
> <vector xmlns:android="http://schemas.android.com/apk/res/android"
>     android:width="96dp"
>     android:height="96dp"
>     android:viewportWidth="96"
>     android:viewportHeight="96">
>     <path
>         android:pathData="M22,10 L74,10 A12,12 0 0 1 86,22 L86,74 A12,12 0 0 1 74,86 L22,86 A12,12 0 0 1 10,74 L10,22 A12,12 0 0 1 22,10 Z"
>         android:fillColor="#00000000"
>         android:strokeColor="#FFFFFFFF"
>         android:strokeWidth="5"
>         android:strokeLineJoin="round" />
>     <group>
>         <clip-path android:pathData="M10,10 L48,10 L48,86 L10,86 Z" />
>         <path
>             android:fillColor="#FFFFFFFF"
>             android:pathData="M48,30 m-10,0 a10,10 0 1,0 20,0 a10,10 0 1,0 -20,0 Z" />
>         <path
>             android:fillColor="#FFFFFFFF"
>             android:pathData="M28,76 C28,60 37,52 48,52 C59,52 68,60 68,76 Z" />
>     </group>
>     <group>
>         <clip-path android:pathData="M48,10 L86,10 L86,86 L48,86 Z" />
>         <path
>             android:fillColor="#FFFFFFFF"
>             android:fillAlpha="0.3"
>             android:pathData="M48,30 m-10,0 a10,10 0 1,0 20,0 a10,10 0 1,0 -20,0 Z" />
>         <path
>             android:fillColor="#FFFFFFFF"
>             android:fillAlpha="0.3"
>             android:pathData="M28,76 C28,60 37,52 48,52 C59,52 68,60 68,76 Z" />
>     </group>
> </vector>
> ```
> Always render it with `Icon(painter = painterResource(R.drawable.ic_rvm_logo), tint = <a colour role>, …)`. `Icon`'s tint uses `BlendMode.SrcIn`, which replaces the RGB but keeps per-path alpha, so the ghosted right half stays ghosted at 30 % of whatever tint you pass. Do not set `android:tint` inside the vector.
>
> **3. Add strings** to `app/src/main/res/values/strings.xml`:
> ```xml
> <string name="home_tagline">Subject from background, entirely on-device.</string>
> <string name="home_video_title">Video Matte</string>
> <string name="home_video_subtitle">Pick a clip and process it on-device</string>
> <string name="home_live_title">Live Matte</string>
> <string name="home_live_subtitle">Real-time camera preview</string>
> <string name="home_live_badge">Coming soon</string>
> <string name="coming_soon">Live matting is coming soon.</string>
> <string name="logo_content_description">RVM logo</string>
> <string name="back">Back</string>
> ```
>
> **4. Create `app/src/main/java/dev/hamster/rvm/ui/HomeScreen.kt`** with:
> ```kotlin
> @Composable fun HomeScreen(onOpenVideoMatte: () -> Unit, modifier: Modifier = Modifier)
> ```
> Layout — a `Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally)`:
> - `Spacer(Modifier.weight(1f))`
> - **the logo medallion** — this is the home screen's deliberate splash of colour (Key decision 3 §9):
>   ```kotlin
>   Surface(
>       shape = CircleShape,
>       color = MaterialTheme.colorScheme.primaryContainer,
>       contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
>       modifier = Modifier.size(112.dp)
>   ) {
>       Box(contentAlignment = Alignment.Center) {
>           Icon(
>               painter = painterResource(R.drawable.ic_rvm_logo),
>               contentDescription = stringResource(R.string.logo_content_description),
>               modifier = Modifier.size(56.dp)
>           )
>       }
>   }
>   ```
>   Do not pass an explicit `tint` — inheriting `onPrimaryContainer` from the `Surface` keeps the mark at a verified 13.20:1 (light) / 7.24:1 (dark) and means the medallion can never fall out of sync with the scheme.
> - `Spacer(Modifier.height(24.dp))`
> - `Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onSurface)` — the wordmark; `displayLarge` already carries the Phase 1 light weight and 10 sp tracking
> - `Spacer(Modifier.height(12.dp))`
> - `Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)`
> - `Spacer(Modifier.weight(1f))`
> - the two action tiles, `Arrangement.spacedBy(12.dp)`
> - `Spacer(Modifier.height(32.dp))`
>
> Do **not** add a background gradient behind this column — see Key decision 3 §9 for why it was considered and rejected.
>
> Add a private composable in the same file:
> ```kotlin
> @Composable
> private fun HomeActionTile(
>     icon: ImageVector,
>     title: String,
>     subtitle: String,
>     containerColor: Color,
>     contentColor: Color,
>     badge: String? = null,
>     onClick: () -> Unit
> )
> ```
> implemented as `Surface(onClick = onClick, shape = MaterialTheme.shapes.extraLarge, color = containerColor, contentColor = contentColor, modifier = Modifier.fillMaxWidth())` containing a `Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp))` of: the `Icon` at `Modifier.size(28.dp)`, then a `Column(Modifier.weight(1f))` with the title in `titleMedium` and the subtitle in `bodySmall` at `LocalContentColor.current.copy(alpha = 0.8f)`, then — if `badge != null` — the badge.
>
> Using `Surface(onClick = …)` keeps the correct `Role.Button` semantics and ripple while allowing a two-line label, which a plain `Button` cannot hold comfortably. `extraLarge` (28 dp), not a pill: at ~88 dp tall this is a surface, not a control (Key decision 3 §10).
>
> **The badge must NOT be `AssistChip(enabled = false)`.** A disabled M3 chip draws its label at 38 % alpha, which measures **1.88:1** against the tile — unreadable — and it is semantically a label, not a broken button. Use:
> ```kotlin
> Surface(
>     shape = CircleShape,
>     color = MaterialTheme.colorScheme.surfaceContainerHighest,
>     contentColor = MaterialTheme.colorScheme.onSurfaceVariant
> ) {
>     Text(
>         badge,
>         style = MaterialTheme.typography.labelSmall,
>         modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
>     )
> }
> ```
> That measures 7.26:1 (light) / 7.22:1 (dark). Note it reads `surfaceContainerHighest` from the theme, not from the tile's `contentColor`, which is why it only works on the neutral tile — and the badge is only ever used there.
>
> Wire the two tiles:
> - **Video Matte** (first, the hero): `Icons.Filled.VideoLibrary`, `containerColor = MaterialTheme.colorScheme.primaryContainer`, `contentColor = MaterialTheme.colorScheme.onPrimaryContainer`, no badge, `onClick = onOpenVideoMatte`.
> - **Live Matte** (second, inert): `Icons.Filled.Videocam`, `containerColor = MaterialTheme.colorScheme.surfaceContainerHigh`, `contentColor = MaterialTheme.colorScheme.onSurface`, `badge = stringResource(R.string.home_live_badge)`, and `onClick = { Toast.makeText(context, R.string.coming_soon, Toast.LENGTH_SHORT).show() }` where `context = LocalContext.current`. Fire the toast **inside `HomeScreen`**, not via a callback — it isn't a navigation concern, and it mirrors the existing `Toast.makeText(context, R.string.error_selection_cancelled, Toast.LENGTH_SHORT).show()` pattern in `MatteScreen.kt:77`.
>
> These two roles are the whole point of the screen's colour budget: the working feature is the only saturated container, the dead end is neutral.
>
> **5. Add a back affordance to the matting screen.** In `app/src/main/java/dev/hamster/rvm/ui/MatteScreen.kt`, change the signature to `fun MatteScreen(viewModel: MatteViewModel, onNavigateBack: () -> Unit)` and give the existing `TopAppBar` a `navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } }`. This is the one icon button in the app with no container and a 24 dp icon — the standard nav slot, a deliberate exception to Key decision 3 §10. Change nothing else in that file this phase.
>
> **6. Add navigation in `app/src/main/java/dev/hamster/rvm/MainActivity.kt`.** Keep the existing `by viewModels { viewModelFactory { initializer { MatteViewModel(application, createSavedStateHandle()) } } }` block **exactly as it is** — the explicit factory is required because `SavedStateViewModelFactory`'s constructor reflection does not resolve `(Application, SavedStateHandle)` in the AndroidX Lifecycle version this project resolves, and removing it crashes at launch. Add above the class:
> ```kotlin
> private enum class RvmDestination { HOME, MATTE }
> ```
> and replace the `setContent` body with:
> ```kotlin
> setContent {
>     RvmTheme {
>         var destination by rememberSaveable { mutableStateOf(RvmDestination.HOME) }
>         BackHandler(enabled = destination != RvmDestination.HOME) {
>             destination = RvmDestination.HOME
>         }
>         when (destination) {
>             RvmDestination.HOME -> HomeScreen(onOpenVideoMatte = { destination = RvmDestination.MATTE })
>             RvmDestination.MATTE -> MatteScreen(viewModel, onNavigateBack = { destination = RvmDestination.HOME })
>         }
>     }
> }
> ```
> `BackHandler` comes from `androidx.activity.compose.BackHandler`, already available via `activity-compose`. Use `rememberSaveable` so a rotation on the matting screen does not bounce back to home. Do **not** add `androidx.navigation:navigation-compose`. Note that because `viewModel` is activity-scoped, navigating home mid-run does **not** cancel the run and the state is intact on return — that is intended.

**Acceptance:** Launching the app shows the teal logo medallion, "RVM" wordmark, tagline, and two tiles — the Video Matte tile visibly coloured, the Live Matte tile neutral with a fully legible "Coming soon" badge (read it at arm's length in both light and dark). Tapping "Live Matte" shows a "Live matting is coming soon." toast and stays put. Tapping "Video Matte" opens today's matting screen; the back arrow and the system back gesture both return home; rotating on the matting screen keeps you there.

---

## Phase 3 — Rebuild the matting screen as a fixed, non-scrolling layout

**Goal:** requirements 5 and 1, plus the configure half of 6. The screen fits on one page with no scrolling, uses the new action names, and the full-width "Configure" button plus the 200 dp `ConfigCard` collapse into one well-placed configure pill (Key decision 3 §7).

**Instruction to give:**

> Rewrite the layout of `app/src/main/java/dev/hamster/rvm/ui/MatteScreen.kt`. Keep `VideoPlayer` (the `AndroidView`/`VideoView` composable, including its `setZOrderOnTop(true)` workaround and prepared-listener aspect math) **unchanged** — it is replaced in a later phase. Keep the `DisposableEffect` that toggles `FLAG_KEEP_SCREEN_ON`, the `LaunchedEffect` that surfaces `errorMessage` on the `SnackbarHost`, the `rememberLauncherForActivityResult(ActivityResultContracts.GetContent())` picker with its cancelled-selection toast, the reset-confirmation `AlertDialog`, and the `ConfigSheet` call site.
>
> **Strings.** In `app/src/main/res/values/strings.xml`, delete `<string name="select_video">` and `<string name="relight">`, and add:
> ```xml
> <string name="action_import">Import</string>
> <string name="action_matte">Matte</string>
> <string name="configure_label">Model</string>
> <string name="configure_content_description">Model settings</string>
> <string name="status_idle_hint">Import a video to begin.</string>
> <string name="status_ready_hint">Ready — tap Matte to run.</string>
> <string name="config_summary">%1$s · %2$dp · ds %3$s · %4$s · %5$d threads</string>
> ```
> Keep `reset`, `cancel`, `configure`, `input_video_label`, `output_video_label`, `no_video_selected`, `progress_caption`, `completion_stats`, `reset_confirm_title`, `reset_confirm_message`, and every config-sheet label as they are. Change `output_video_label`'s value from `Processed Video` to `Output` and `input_video_label`'s from `Input Video` to `Input` — they are now small overlay chips, not section headers.
>
> **Structure.** Replace the `Scaffold` body. The outer `Column` must **not** have `verticalScroll` — delete that modifier and the now-unused `rememberScrollState`/`verticalScroll` imports.
>
> ```
> Scaffold(
>   topBar = TopAppBar(
>     title = { Text(stringResource(R.string.app_name)) },
>     navigationIcon = back arrow -> onNavigateBack       // from Phase 2
>     // NOTE: no `actions`. Configure lives in the pill below; Save arrives in Phase 7
>     //       in the OutputBar. Leaving this bar bare is deliberate — see Key decision 3 §9.
>   ),
>   bottomBar = { MatteActionBar(...) },
>   snackbarHost = { SnackbarHost(snackbarHostState) }
> ) { innerPadding ->
>   Column(Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp, vertical = 12.dp),
>          verticalArrangement = Arrangement.spacedBy(12.dp)) {
>       ConfigButton(config, onClick = { showConfigSheet = true })          // 44 dp
>       PreviewFrame(label = input, uri = uiState.selectedVideoUri, Modifier.weight(1f))
>       if (uiState.outputVideoUri != null)
>           PreviewFrame(label = output, uri = uiState.outputVideoUri, Modifier.weight(1f))
>       StatusStrip(uiState, Modifier.height(28.dp))                       // fixed height, always present
>   }
> }
> ```
>
> **New private composables in the same file:**
>
> - **`ConfigButton(config: MatteConfig, onClick: () -> Unit)`** — the configure control. This **replaces both** `ConfigCard` (delete it *and* its `ConfigRow` helper) **and** the old full-width "Configure" `OutlinedButton` at `MatteScreen.kt:196` (delete that too). Keep the existing `downsampleDisplay` private function — it is reused here.
>   ```kotlin
>   Surface(
>       onClick = onClick,
>       shape = CircleShape,                                     // pill: a 44 dp control
>       color = MaterialTheme.colorScheme.secondaryContainer,
>       contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
>       modifier = Modifier.fillMaxWidth().height(44.dp)
>   ) {
>       Row(
>           Modifier.padding(horizontal = 16.dp),
>           verticalAlignment = Alignment.CenterVertically,
>           horizontalArrangement = Arrangement.spacedBy(12.dp)
>       ) {
>           Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.configure_content_description), Modifier.size(20.dp))
>           Text(stringResource(R.string.configure_label), style = MaterialTheme.typography.labelLarge)
>           Text(
>               summary,                                          // R.string.config_summary, formatted
>               style = MaterialTheme.typography.bodySmall,
>               color = LocalContentColor.current.copy(alpha = 0.8f),
>               maxLines = 1,
>               overflow = TextOverflow.Ellipsis,
>               modifier = Modifier.weight(1f)
>           )
>           Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, Modifier.size(20.dp))
>       }
>   }
>   ```
>   Format `summary` from `R.string.config_summary` with `config.variant.backbone`, `config.height`, `downsampleDisplay(...)`, `config.runtimeConfig.device.name`, `config.runtimeConfig.numThreads`. The full model filename moves out of the always-visible UI; it stays the config sheet's business. `Surface(onClick=…)` gives `Role.Button` semantics for free. If `Icons.AutoMirrored.Filled.KeyboardArrowRight` doesn't resolve, `Icons.Filled.ChevronRight` is the fallback.
>
> - **`PreviewFrame(label: String, uri: Uri?, modifier: Modifier)`**:
>   ```kotlin
>   Box(
>       modifier
>           .fillMaxWidth()
>           .clip(MaterialTheme.shapes.large)
>           .background(RvmVideoPlate)
>           .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
>   ) { … }
>   ```
>   containing the existing `VideoPlayer(uri, contentDescription = label, Modifier.fillMaxSize())` and, aligned `Alignment.TopStart` with 8 dp padding, the label chip:
>   ```kotlin
>   Surface(
>       shape = CircleShape,
>       color = RvmVideoPlate.copy(alpha = RvmVideoScrimAlpha),
>       contentColor = RvmOnVideoPlate
>   ) {
>       Text(label, style = MaterialTheme.typography.labelSmall,
>            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
>   }
>   ```
>   **Use the fixed plate colours, not `surface`/`onSurface`.** The previous draft used `surface.copy(alpha = 0.75f)`, which flips from near-white to near-black with the theme and left the chip's legibility over video undefined. The fixed scrim measures ≥ 6.98:1 even over a pure-white video frame, in both themes. Import `RvmVideoPlate`, `RvmOnVideoPlate`, `RvmVideoScrimAlpha` from `ui.theme`.
>   The empty state (`uri == null`) is the `ic_rvm_logo` painter at 40 dp tinted `RvmOnVideoPlate.copy(alpha = 0.4f)` above `Text(stringResource(R.string.no_video_selected), color = RvmOnVideoPlate.copy(alpha = 0.7f))` — neutral, never `primary`; the previews are the quiet zone.
>   **Note:** the chip and empty state will be painted *behind* the `VideoView` while a video is loaded, because of `setZOrderOnTop(true)`. That is expected and is fixed in Phase 6; render them anyway so the empty state reads correctly now.
>
> - **`StatusStrip(uiState: MatteUiState, modifier: Modifier)`** — one fixed-height `Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart)` that renders, by `uiState.stage`:
>   - `RUNNING` → a `Column` with a `LinearProgressIndicator(progress = { processed / total }, modifier = Modifier.fillMaxWidth())` over the existing `R.string.progress_caption` text (same maths as today: `processed.toFloat() / total`, `processed * 100 / total`, guarding `total > 0`). The indicator uses its default `primary` colour — this is the one moment during a run when colour is doing work.
>   - `DONE` → the existing `R.string.completion_stats` text in `onSurfaceVariant`. **Not** `tertiary` — the Save button carries the "you have a result" signal (Key decision 3 §6); doubling it makes the strip shout.
>   - `ERROR` → `uiState.errorMessage` in `MaterialTheme.colorScheme.error`.
>   - `IDLE` → `status_idle_hint` when `selectedVideoUri == null`, else `status_ready_hint`, in `onSurfaceVariant`.
>   Keeping this slot at a constant 28 dp means starting a run does not reflow the previews.
>
> - **`MatteActionBar(...)`** — a `Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp)` (no `shadowElevation` — tonal only) wrapping a `Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp))` of three `Modifier.weight(1f).height(48.dp)` buttons, all at their default M3 pill shape:
>   1. `FilledTonalButton` → `Icons.Filled.VideoLibrary` + `R.string.action_import`, `onClick = { pickVideo.launch("video/*") }`, `enabled = !isRunning`.
>   2. When `isRunning`, a `FilledTonalButton` → `Icons.Filled.Close` + `R.string.cancel` calling `viewModel.cancel()`; otherwise a filled `Button` → `Icons.Filled.AutoAwesome` + `R.string.action_matte` calling `viewModel.runMatting()`, `enabled = uiState.selectedVideoUri != null`. (Same enable/swap logic as today, new labels.) **This is the only filled `primary` element on the screen** — do not add another.
>   3. `OutlinedButton` → `Icons.Filled.Refresh` + `R.string.reset`, `onClick = { if (isRunning) showResetConfirm = true else viewModel.reset() }`. Default M3 outlined colours (`outline` border, `primary` label), no container — the quietest of the three, deliberately.
>
>   Inside every one of these buttons use exactly:
>   ```kotlin
>   contentPadding = ButtonDefaults.ButtonWithIconContentPadding
>   …
>   Icon(icon, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
>   Spacer(Modifier.width(ButtonDefaults.IconSpacing))
>   Text(label, maxLines = 1)
>   ```
>   **No dp literals for icon size or spacing** — `ButtonDefaults.IconSize` is 18 dp and `IconSpacing` is a token; the previous draft hardcoded 18 dp / 6 dp, and 6 dp is not the M3 value. `contentDescription = null` is correct here because the adjacent `Text` already names the action.
>
> Delete the old inline three-button `Row`, the inline progress `Column`, the inline completion-stats `Text`, the two section-label `Text`s, the `ConfigCard`/`ConfigRow` pair, and the full-width "Configure" `OutlinedButton`.

**Acceptance:** On a phone in portrait, the whole matting screen fits with no scrolling and nothing clipped, in both light and dark mode, before and after a run. The three bottom buttons read Import / Matte / Reset. The configure control is an obvious full-width tonal pill reading "⚙ Model  resnet50 · 720p · ds 0.25 · CPU · 4 threads ›", it opens the config sheet, and its summary text updates when the config changes. The top app bar has no action icons. Starting a run does not shift the previews. The preview boxes are near-black in *both* themes with a hairline border, and their label chips are legible.

---

## Phase 4 — Apply confirmation

*(Unchanged from the previous revision — nothing in the visual-system revision touches it.)*

**Goal:** the second half of requirement 6 — Apply both applies and confirms.

**Recommendation:** use a **Toast**, not a Snackbar. The `SnackbarHost` in `MatteScreen` is currently reserved for `stage == ERROR` messages (`MatteScreen.kt:91–96`), and mixing a routine success confirmation into it dilutes that; a toast also floats over the just-dismissed bottom sheet without contending with it for the bottom of the screen. It matches the existing `Toast.makeText(context, R.string.error_selection_cancelled, …)` precedent at `MatteScreen.kt:77`.

**Instruction to give:**

> Add to `app/src/main/res/values/strings.xml`:
> ```xml
> <string name="config_applied">Configuration applied</string>
> ```
> In `app/src/main/java/dev/hamster/rvm/ui/MatteScreen.kt`, change the `ConfigSheet(...)` call site's `onApply` lambda from `{ viewModel.updateConfig(it) }` to:
> ```kotlin
> onApply = {
>     viewModel.updateConfig(it)
>     Toast.makeText(context, R.string.config_applied, Toast.LENGTH_SHORT).show()
> }
> ```
> using the `context` already obtained from `LocalContext.current` at the top of `MatteScreen`. Do not move the toast into `ConfigSheet.kt` — the sheet stays a pure editor whose only side effect is invoking `onApply`, which is what makes Cancel/scrim-dismiss discard cleanly today.
>
> While in `ConfigSheet.kt`, make one small correctness fix at the same time: the sheet's `Cancel` `TextButton` calls the local `dismiss()` (which animates the sheet out then calls `onDismiss`), but `ModalBottomSheet`'s own `onDismissRequest` is wired directly to `onDismiss`. Leave that as is — it works — but change the `Apply` button so its `enabled` also accounts for `isRunning` in the label, i.e. when `isRunning` is true, render the Apply button disabled **and** add a one-line `Text` above the button row explaining why. If you'd rather not add a new string, simply leave the disabled button as is; do not block on this.

**Acceptance:** Opening the configure pill, changing the compute device, and tapping Apply dismisses the sheet, shows a "Configuration applied" toast, and the pill's summary text updates to the new device in the same frame.

---

## Phase 5 — Surface the foreground output and add the matte/foreground switcher

**Goal:** the first half of requirement 7. `Controller.matteVideo` already produces both videos in one pass (`Controller.kt:137–138`) and nothing in the UI has ever read `outputFgrVideoUri`. This phase closes that gap **without** touching playback mechanics, so it can be verified on its own.

**Instruction to give:**

> **1. `app/src/main/java/dev/hamster/rvm/ui/MatteViewModel.kt`.**
> - In `MatteUiState`, rename `outputVideoUri` to `outputMatteVideoUri`, add `val outputFgrVideoUri: Uri? = null`, and add `val outputSelection: OutputKind = OutputKind.MATTE`. Add alongside the existing `Stage` enum:
>   ```kotlin
>   enum class OutputKind { MATTE, FOREGROUND }
>   ```
>   and a convenience accessor on the data class:
>   ```kotlin
>   val activeOutputUri: Uri?
>       get() = when (outputSelection) {
>           OutputKind.MATTE -> outputMatteVideoUri
>           OutputKind.FOREGROUND -> outputFgrVideoUri
>       }
>   ```
> - In `runMatting()`, after `controller.matteVideo { … }` returns, set **both** URIs: `it.copy(stage = DONE, outputMatteVideoUri = outputUri, outputFgrVideoUri = controller.outputFgrVideoUri, elapsedMs = elapsed)`. `outputFgrVideoUri` is a public `var` on `Controller` (`Controller.kt:26`) assigned immediately before `matteVideo` returns, so it is safe to read there.
> - Clear `outputFgrVideoUri` (and reset `outputSelection` to `MATTE`) everywhere `outputVideoUri` is cleared today: `onVideoSelected`, the start of `runMatting`, and `reset()`.
> - Add:
>   ```kotlin
>   fun selectOutput(kind: MatteUiState.OutputKind) {
>       _uiState.update { it.copy(outputSelection = kind) }
>   }
>   ```
>
> **2. Strings** — add:
> ```xml
> <string name="output_matte">Matte</string>
> <string name="output_foreground">Foreground</string>
> ```
>
> **3. `MatteScreen.kt`.** Update the two `uiState.outputVideoUri` references to the new names, and insert a new private composable **`OutputBar`** between the input preview and the output preview, rendered only when `uiState.outputMatteVideoUri != null`.
>
> **Build it as a `Row` with the switcher weighted, even though the switcher is currently its only child** — Phase 7 drops the Save button into the trailing slot, and structuring it this way now means Phase 7 is an insertion rather than a rewrite:
> ```kotlin
> @Composable
> private fun OutputBar(uiState: MatteUiState, onSelect: (OutputKind) -> Unit, modifier: Modifier = Modifier) {
>     Row(
>         modifier.fillMaxWidth().height(40.dp),
>         horizontalArrangement = Arrangement.spacedBy(8.dp),
>         verticalAlignment = Alignment.CenterVertically
>     ) {
>         SingleChoiceSegmentedButtonRow(Modifier.weight(1f).fillMaxHeight()) {
>             MatteUiState.OutputKind.entries.forEachIndexed { index, kind ->
>                 SegmentedButton(
>                     selected = uiState.outputSelection == kind,
>                     onClick = { onSelect(kind) },
>                     shape = SegmentedButtonDefaults.itemShape(index, MatteUiState.OutputKind.entries.size)
>                 ) {
>                     Text(stringResource(if (kind == OutputKind.MATTE) R.string.output_matte else R.string.output_foreground))
>                 }
>             }
>         }
>         // Phase 7 inserts the Save button here.
>     }
> }
> ```
> `SingleChoiceSegmentedButtonRow`/`SegmentedButton`/`SegmentedButtonDefaults` are already used in `ConfigSheet.kt:143–152` — follow that pattern, and **leave the colours at their M3 defaults**: `SegmentedButton`'s selected state resolves to `secondaryContainer`/`onSecondaryContainer`, which is exactly the "controls" role assignment in Key decision 3 §6, so the selected segment and the configure pill are the same tone by construction. Do not override them.
>
> Drive the output `PreviewFrame` from `uiState.activeOutputUri`. Wrap `OutputBar` and the output `PreviewFrame` in `AnimatedVisibility(visible = uiState.outputMatteVideoUri != null)` so they fade in rather than snapping when a run finishes.

**Acceptance:** After a run completes, a Matte / Foreground segmented control fades in above the output preview; tapping Foreground plays the foreground video (the person cut out on black) and tapping Matte plays the alpha matte. The selected segment is the same pale teal as the configure pill. Neither control is visible before the first run, and both disappear after Reset.

---

## Phase 6 — Media3 playback with synchronized input/output

**Goal:** the second half of requirement 7, and a large chunk of requirement 1 (rounded, clippable, animatable video surfaces — and the first point at which the Phase 3 label chips actually draw *over* the video). **This is the highest-risk phase — read the risk notes and the Plan B before starting.**

**New dependency:** `androidx.media3:media3-exoplayer`. Not `media3-ui` — we drive a bare `TextureView`, so `PlayerView` and its XML control surface are not needed.

**Instruction to give:**

> **1. Add the dependency.** In `gradle/libs.versions.toml`:
> ```
> [versions]
> media3 = "1.8.0"
>
> [libraries]
> androidx-media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
> ```
> In `app/build.gradle.kts`: `implementation(libs.androidx.media3.exoplayer)`. (If Gradle cannot resolve `1.8.0`, take the newest stable 1.x — anything ≥ 1.7 has the APIs used here. Do **not** add `media3-ui` or `media3-ui-compose`.)
>
> **2. Create `app/src/main/java/dev/hamster/rvm/ui/player/VideoSurface.kt`:**
> ```kotlin
> @Composable
> fun VideoSurface(
>     player: ExoPlayer?,
>     aspectRatio: Float,          // width / height; 16f / 9f when unknown
>     contentDescription: String,
>     modifier: Modifier = Modifier
> )
> ```
> Implementation: `BoxWithConstraints(modifier, contentAlignment = Alignment.Center)`. If `player == null`, render the empty state — the `ic_rvm_logo` painter at 40 dp tinted `RvmOnVideoPlate.copy(alpha = 0.4f)` above `Text(stringResource(R.string.no_video_selected), color = RvmOnVideoPlate.copy(alpha = 0.7f))` — and return. Otherwise compute `val boxRatio = maxWidth.value / maxHeight.value` and render:
> ```kotlin
> AndroidView(
>     modifier = Modifier
>         .aspectRatio(aspectRatio, matchHeightConstraintsFirst = aspectRatio < boxRatio)
>         .semantics { this.contentDescription = contentDescription },
>     factory = { TextureView(it) },
>     update = { player.setVideoTextureView(it) }
> )
> ```
> The `matchHeightConstraintsFirst` term is what makes a portrait clip pillarbox instead of overflowing the box: when the video is narrower than the box, height is the binding constraint. The pillarbox bars are `RvmVideoPlate`, which is why the plate is near-black — the bars are invisible against the foreground output's own black background. There is **no** `setZOrderOnTop` here and no manual `layoutParams` math — a `TextureView` is an ordinary View, so Compose's `clip`, `alpha` and animation modifiers work on it, which is what lets the parent round its corners and cross-fade.
>
> **3. Create `app/src/main/java/dev/hamster/rvm/ui/player/DualVideoSync.kt`.** All ExoPlayer calls must happen on the main thread; every call site below is inside a composable/`LaunchedEffect`, which satisfies that. If the compiler flags any Media3 symbol as unstable, annotate the file with `@OptIn(androidx.media3.common.util.UnstableApi::class)`.
> ```kotlin
> class DualVideoSync(context: Context) {
>     val inputPlayer: ExoPlayer
>     val outputPlayer: ExoPlayer
>     var inputAspect by mutableFloatStateOf(16f / 9f); private set
>     var outputAspect by mutableFloatStateOf(16f / 9f); private set
>     var isPlaying by mutableStateOf(false); private set
>     var hasInput by mutableStateOf(false); private set
>     var hasOutput by mutableStateOf(false); private set
>
>     fun setInputUri(uri: Uri?)
>     fun setOutputUri(uri: Uri?)      // preserves position across matte<->fgr swaps
>     fun togglePlayPause()
>     fun correctDrift()
>     fun release()
> }
>
> @Composable fun rememberDualVideoSync(): DualVideoSync
> ```
> Construction of each player:
> ```kotlin
> ExoPlayer.Builder(context).build().apply {
>     repeatMode = Player.REPEAT_MODE_ONE
>     playWhenReady = false
>     volume = 0f                       // both muted: this is a visual comparison, and
>                                       // the outputs have no audio track anyway
> }
> ```
> plus `outputPlayer.setSeekParameters(SeekParameters.EXACT)` — the outputs have `KEY_I_FRAME_INTERVAL = 1` (`VideoFrameEncoder.kt:60`), so keyframe-snapped seeking would quantise the follower to ~1 second.
>
> Attach a `Player.Listener` to each that captures `onVideoSizeChanged(videoSize)` into `inputAspect`/`outputAspect` as `videoSize.width.toFloat() / videoSize.height` (guard against zero), and `onIsPlayingChanged` on the **input** player into `isPlaying`. `VideoSize` from ExoPlayer already has display rotation applied.
>
> `setInputUri(uri)`: if `uri == null`, `inputPlayer.clearMediaItems()`, `hasInput = false`; else `setMediaItem(MediaItem.fromUri(uri)); prepare()`, `hasInput = true`. Same for `setOutputUri`, except capture `val pos = outputPlayer.currentPosition` first and `seekTo(pos)` after `prepare()` so switching matte ↔ foreground does not jump back to the start.
>
> `togglePlayPause()`: drive **both** players together — `if (inputPlayer.isPlaying) { inputPlayer.pause(); outputPlayer.pause() } else { outputPlayer.seekTo(inputPlayer.currentPosition); inputPlayer.play(); outputPlayer.play() }`. Re-aligning on every resume is what stops long-term drift from accumulating across pauses.
>
> `correctDrift()` — the core of the mechanism. The **input is the leader**, the output the follower:
> ```kotlin
> fun correctDrift() {
>     if (!inputPlayer.isPlaying || !hasOutput) return
>     val followerDuration = outputPlayer.duration
>     if (followerDuration <= 0) return                      // C.TIME_UNSET or not prepared
>     val target = inputPlayer.currentPosition.coerceIn(0, followerDuration - 1)
>     val drift = target - outputPlayer.currentPosition      // >0 => follower is behind
>     when {
>         abs(drift) > HARD_SEEK_MS -> {                     // 400ms: loop wrap, or a scrub
>             outputPlayer.setPlaybackSpeed(1f)
>             outputPlayer.seekTo(target)
>         }
>         abs(drift) > SOFT_TOLERANCE_MS ->                  // 40ms ~= one frame at 25fps
>             outputPlayer.setPlaybackSpeed(
>                 1f + (drift / 1000f).coerceIn(-0.05f, 0.05f)
>             )
>         else -> outputPlayer.setPlaybackSpeed(1f)
>     }
> }
> ```
> Speed nudging is the primary correction and seeking is the exception, deliberately: an `EXACT` seek on a 1-second-GOP AVC stream re-decodes up to a second of frames, so doing it on a 150 ms cadence would stutter continuously. A ±5 % speed trim closes a 40–400 ms gap within a second or two and is imperceptible on muted video.
>
> `release()` releases both players. `rememberDualVideoSync()` is:
> ```kotlin
> @Composable
> fun rememberDualVideoSync(): DualVideoSync {
>     val context = LocalContext.current
>     val sync = remember { DualVideoSync(context) }
>     DisposableEffect(sync) { onDispose { sync.release() } }
>     return sync
> }
> ```
> The players deliberately live in the composition, **not** in `MatteViewModel` — they are lifecycle-bound display resources and must not outlive the screen, whereas the ViewModel outlives it by design (navigating home mid-run keeps the run alive).
>
> **4. Rewire `MatteScreen.kt`.**
> - **Delete the entire `VideoPlayer` composable** (`MatteScreen.kt:322–372`) and its now-unused imports: `android.widget.VideoView`, `androidx.compose.ui.viewinterop.AndroidView`, and any of `background`/`RoundedCornerShape`/`clip` that nothing else uses.
> - `val sync = rememberDualVideoSync()` at the top.
> - `LaunchedEffect(uiState.selectedVideoUri) { sync.setInputUri(uiState.selectedVideoUri) }` and `LaunchedEffect(uiState.activeOutputUri) { sync.setOutputUri(uiState.activeOutputUri) }`.
> - The drift loop:
>   ```kotlin
>   LaunchedEffect(sync.isPlaying) {
>       while (sync.isPlaying) {
>           sync.correctDrift()
>           delay(150)
>       }
>   }
>   ```
> - Pause on backgrounding: a `DisposableEffect(LocalLifecycleOwner.current)` registering a `LifecycleEventObserver` that pauses both players on `ON_STOP`.
> - `PreviewFrame` from Phase 3 now takes a `player: ExoPlayer?` and an `aspectRatio: Float` instead of a `uri`, and calls `VideoSurface(...)`. **Keep its `RvmVideoPlate` background, 1 dp `outlineVariant` border, `shapes.large` clip and scrim label chip exactly as Phase 3 defined them** — the only change is what fills the middle. Because the surface is now a `TextureView`, the `clip` genuinely rounds the video and the label chip finally appears **on top of** it rather than behind.
> - Add a transport control **overlaid** on the input `PreviewFrame` at `Alignment.BottomStart` with 8 dp padding, built to the Key decision 3 §10 icon-button spec:
>   ```kotlin
>   FilledTonalIconButton(
>       onClick = { sync.togglePlayPause() },
>       modifier = Modifier.size(40.dp)
>   ) {
>       Icon(
>           if (sync.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
>           contentDescription = stringResource(if (sync.isPlaying) R.string.pause else R.string.play),
>           modifier = Modifier.size(20.dp)
>       )
>   }
>   ```
>   Leave `IconButtonDefaults.filledTonalIconButtonColors()` at its default — it resolves to `secondaryContainer`/`onSecondaryContainer`, which is exactly the "controls" role, so the transport button, the configure pill and the selected switcher segment are all the same tone without any of them hardcoding it. Against the near-black plate this measures well above AA in both themes. Overlaying costs zero layout height, which the Phase 3 budget depends on. Add `<string name="play">Play</string>` and `<string name="pause">Pause</string>`.
> - Playback starts **paused**. Auto-looping two videos the instant a file is picked is both noisy and the worst case for drift; an explicit play gesture also gives `togglePlayPause` a clean point to align the two players.
>
> **Risks to watch, and the fallback.** Verify on a real device before declaring this done:
> - *Loop-boundary stutter.* Both players use `REPEAT_MODE_ONE` and their durations agree only to within a frame or two (the output's frame count is `durationMs * fps / 1000`, truncated — `VideoFrameDecoder.kt:49`). Whichever wraps first makes `drift` swing past `HARD_SEEK_MS`, forcing one hard seek per loop. One visible hitch per loop is acceptable; a continuous re-seek storm near the end is not. If you see the latter, raise `HARD_SEEK_MS` and clamp the leader to the follower's duration.
> - *Two concurrent AVC decoders.* Fine at 720p on API-35 hardware, but if `ExoPlaybackException` reports `MediaCodec` instantiation failure, that's the cause.
> - *`TextureView` cost.* One extra GPU copy per frame per surface versus `SurfaceView`. Immaterial at 720p; noted for completeness.
>
> **Plan B (fall back to this rather than shipping something jittery):** one `ExoPlayer`, one `VideoSurface`, and a **three-way** switcher — Input / Matte / Foreground — where changing the selection does `val pos = player.currentPosition; player.setMediaItem(newItem); player.prepare(); player.seekTo(pos)`. This gives frame-comparable A/B switching at a single shared position with zero sync machinery, satisfies "keep the output in sync with the input" in substance, and frees ~280 dp of vertical space. It is strictly more robust; the only thing it loses is seeing both at once. If you take it, the `OutputBar` becomes a three-segment row and the Save button (Phase 7) still sits in its trailing slot unchanged.

**Acceptance:** Pick a video, run matting, tap play: input and output play together and stay visually aligned for at least a full minute of looping (no persistent lead/lag). Both preview surfaces have visibly rounded corners over the near-black plate and their label chips are readable **over** the video, in both themes and over both bright and dark footage. The play/pause button is a 40 dp pale-teal circle matching the configure pill's tone. Switching Matte ↔ Foreground swaps the output without restarting from zero. Backgrounding the app pauses both; there is no `MediaCodec` leak warning in logcat after navigating home and back five times.

---

## Phase 7 — Cache-backed outputs and Save to the gallery

**Goal:** requirement 8. Runs write to `cacheDir`; nothing reaches the user's gallery until Save is tapped. The Save control is a labelled tonal button in the `OutputBar`, not a toolbar glyph (Key decision 3 §8).

**Instruction to give:**

> **1. `app/src/main/java/dev/hamster/rvm/Controller.kt`.** Replace the two fixed external-files paths in `matteVideo` (`Controller.kt:94–95`) with per-run files in the cache directory, and expose the `File` handles so the saver does not have to reverse a `Uri` back into a path:
> ```kotlin
> var outputMatteFile: File? = null
>     private set
> var outputFgrFile: File? = null
>     private set
>
> private fun prepareRunDir(): File {
>     val dir = File(context.cacheDir, "rvm_runs")
>     if (dir.exists()) dir.listFiles()?.forEach { it.delete() } else dir.mkdirs()
>     return dir
> }
> ```
> At the top of `matteVideo`, after `mattingModule.reset()`:
> ```kotlin
> val runDir = prepareRunDir()
> val stamp = System.currentTimeMillis()
> val matteFile = File(runDir, "matte_$stamp.mp4")
> val fgrFile = File(runDir, "fgr_$stamp.mp4")
> ```
> and after `matteEncoder.saveVideo()` / `fgrEncoder.saveVideo()`, set `outputMatteFile = matteFile; outputFgrFile = fgrFile` before returning. **The per-run timestamp in the filename is load-bearing**, not cosmetic: with the current fixed `alphamatte.mp4` name, a second run produces an identical `Uri`, so neither `LaunchedEffect(uri)` nor ExoPlayer's media-item comparison would notice the change and the preview would keep showing the previous run's video. Unique names make every completed run a distinct `Uri`.
> In `reset()`, also delete the run directory's contents and null both `File` fields alongside the existing `mattingModule.reset()`.
>
> **2. Create `app/src/main/java/dev/hamster/rvm/utils/MediaStoreSaver.kt`.** No manifest permission is required or available: `WRITE_EXTERNAL_STORAGE` has had no effect since API 29 and `minSdk` is 35 — an app inserting its own media into `MediaStore` needs nothing.
> ```kotlin
> package dev.hamster.rvm.utils
>
> object MediaStoreSaver {
>     suspend fun saveToMovies(context: Context, source: File, displayName: String): Uri =
>         withContext(Dispatchers.IO) {
>             val resolver = context.contentResolver
>             val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
>             val values = ContentValues().apply {
>                 put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
>                 put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
>                 put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/RVM")
>                 put(MediaStore.Video.Media.IS_PENDING, 1)
>             }
>             val uri = resolver.insert(collection, values)
>                 ?: error("MediaStore rejected the insert")
>             try {
>                 resolver.openOutputStream(uri)?.use { out ->
>                     source.inputStream().use { it.copyTo(out) }
>                 } ?: error("Could not open the output stream")
>                 values.clear()
>                 values.put(MediaStore.Video.Media.IS_PENDING, 0)
>                 resolver.update(uri, values, null, null)
>                 uri
>             } catch (t: Throwable) {
>                 resolver.delete(uri, null, null)   // don't leave a pending stub behind
>                 throw t
>             }
>         }
> }
> ```
> `IS_PENDING = 1` during the copy hides the half-written file from the gallery; flipping it to `0` publishes it atomically. `RELATIVE_PATH` is what places it in `Movies/RVM` under scoped storage.
>
> **3. `MatteViewModel.kt`.** The constructor currently takes `application: Application` without storing it — change it to `private val application: Application` (leave `MainActivity`'s explicit `viewModelFactory { initializer { MatteViewModel(application, createSavedStateHandle()) } }` untouched; it still matches). Add to `MatteUiState`: `val isSaving: Boolean = false` and `val transientMessage: String? = null`. Add:
> ```kotlin
> fun saveOutputs() {
>     val matte = controller.outputMatteFile ?: return
>     if (_uiState.value.isSaving) return
>     _uiState.update { it.copy(isSaving = true) }
>     viewModelScope.launch {
>         try {
>             val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
>             MediaStoreSaver.saveToMovies(application, matte, "RVM_matte_$stamp.mp4")
>             controller.outputFgrFile?.let {
>                 MediaStoreSaver.saveToMovies(application, it, "RVM_foreground_$stamp.mp4")
>             }
>             _uiState.update {
>                 it.copy(isSaving = false,
>                         transientMessage = application.getString(R.string.saved_to_gallery))
>             }
>         } catch (e: Exception) {
>             _uiState.update {
>                 it.copy(isSaving = false,
>                         transientMessage = application.getString(R.string.save_failed, e.message ?: ""))
>             }
>         }
>     }
> }
>
> fun consumeTransientMessage() {
>     _uiState.update { it.copy(transientMessage = null) }
> }
> ```
> Save **both** outputs on one tap — they are produced together from one pass and the user asked for "the two generated output videos" to go to the gallery.
>
> **4. Strings:**
> ```xml
> <string name="action_save">Save</string>
> <string name="save_content_description">Save output videos to gallery</string>
> <string name="saved_to_gallery">Saved to Movies/RVM</string>
> <string name="save_failed">Couldn\'t save: %1$s</string>
> ```
>
> **5. `MatteScreen.kt` — put Save in the `OutputBar`, not the top app bar.** Insert into the trailing slot of the Phase 5 `OutputBar` `Row` (the comment marking it is already there), leaving `TopAppBar.actions` empty:
> ```kotlin
> FilledTonalButton(
>     onClick = onSave,
>     enabled = !uiState.isSaving && !isRunning,
>     modifier = Modifier.fillMaxHeight(),
>     contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
>     colors = ButtonDefaults.filledTonalButtonColors(
>         containerColor = MaterialTheme.colorScheme.tertiaryContainer,
>         contentColor = MaterialTheme.colorScheme.onTertiaryContainer
>     )
> ) {
>     if (uiState.isSaving) {
>         CircularProgressIndicator(
>             Modifier.size(ButtonDefaults.IconSize),
>             strokeWidth = 2.dp,
>             color = LocalContentColor.current
>         )
>     } else {
>         Icon(Icons.Filled.Save, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
>     }
>     Spacer(Modifier.width(ButtonDefaults.IconSpacing))
>     Text(stringResource(R.string.action_save), maxLines = 1)
> }
> ```
> Notes on each choice, so nothing here is arbitrary:
> - **`tertiaryContainer` is Save's and only Save's.** It is the app's single warm accent and Key decision 3 §6 assigns it the meaning "you have a result". It appears exactly when a result exists.
> - **No `outputMatteVideoUri != null` in `enabled`** — the whole `OutputBar` is already conditional on that, so the button simply doesn't exist beforehand. This is why Save moved here: it is never a greyed-out control the user has to interpret.
> - **Same `ButtonDefaults` icon tokens as every other labelled button** in the app, and the progress spinner is sized to `ButtonDefaults.IconSize` so the button does not change width when it starts saving.
> - **`contentDescription = null` on the icon** because the adjacent `Text` names the action; keep `save_content_description` for the accessibility label on the whole button via `Modifier.semantics` if you prefer the longer phrasing.
>
> Add the message effect next to the existing error effect:
> ```kotlin
> LaunchedEffect(uiState.transientMessage) {
>     uiState.transientMessage?.let {
>         snackbarHostState.showSnackbar(it)
>         viewModel.consumeTransientMessage()
>     }
> }
> ```
> Use the **Snackbar** here rather than a toast: unlike the config confirmation, saving is an operation with a real failure mode and a destination worth naming, and the `SnackbarHost` is already wired.

**Acceptance:** Run matting, then confirm with a file browser that nothing new exists under `Movies/`; `adb shell run-as dev.hamster.rvm ls cache/rvm_runs` shows two timestamped `.mp4`s. The Save button appears — warm-toned, labelled, at the right-hand end of the Matte/Foreground row — only once the run completes, and does not exist before that. Tapping it shows a spinner in place of its icon without the button changing width, then a "Saved to Movies/RVM" snackbar, and the device's Photos/Gallery app shows two new videos in an `RVM` album. Running a second time replaces the cache files and the preview shows the new run, not the old one. The top app bar still has no action icons.

---

## Phase 8 — Polish, consistency audit, and cleanup

**Goal:** the remainder of requirement 1, a deliberate pass over the visual system now that every screen exists, plus cleanup of what the redesign made dead.

**Instruction to give:**

> - **Visual consistency audit.** With every phase landed, walk the app against the Key decision 3 tables and fix anything that drifted:
>   - `grep -rn "Color(0x" app/src/main/java --include=*.kt` must return hits **only** in `ui/theme/Color.kt`. Any literal colour elsewhere is a bug.
>   - `grep -rn "colorScheme\." app/src/main/java --include=*.kt` — every hit must be a use listed in the §6 role table. In particular: exactly one filled `primary` button per screen; `tertiaryContainer` used only by Save; `surfaceContainer` only by the bottom bar; `surfaceContainerHigh` only by the Live Matte tile.
>   - `grep -rn "\.size([0-9]" app/src/main/java --include=*.kt` — every icon inside a labelled button must use `ButtonDefaults.IconSize`, not a literal. Containerised icon buttons are 40 dp with a 20 dp icon; the top-bar nav icon is the only 24 dp one.
>   - Every `Modifier.padding`/`Spacer` value is a multiple of 4.
>   - Every rounded control ≤ 56 dp is a pill; every surface above that uses `MaterialTheme.shapes`. No `shadowElevation` anywhere.
> - **Dead code.** Delete `MatteUiState.Stage.CONFIGURING` — it has never been assigned anywhere in the codebase. Delete `Controller.testVideoHandler(count: Int)` and `Controller.testVideoHandler(): Uri` (`Controller.kt:58–82`); they write `test.mp4` into the external-files directory that Phase 7 stopped using and nothing calls them.
> - **Strings.** Remove any entry in `res/values/strings.xml` no longer referenced after Phases 3–7 (`grep -rn "R.string.<name>" app/src/main` for each). `configure` survives as the config sheet's own title (`ConfigSheet.kt:138`); `dtype_label`, `model_file_label` and `threads_value_label` are orphaned by the removal of `ConfigCard` — either delete them or, better, add a `dtype` / model-filename readout row to the bottom of `ConfigSheet` so the resolved `config.runtimeConfig.modelFileName` stays inspectable somewhere (the configure pill's summary deliberately drops it).
> - **Motion.** Wrap the status strip's stage-dependent content in `AnimatedContent` so progress → completion-stats cross-fades instead of snapping. Give the output `PreviewFrame` a `Crossfade` keyed on `uiState.outputSelection` — this is only possible now that Phase 6 replaced the z-ordered `SurfaceView` with a `TextureView`. Keep durations at Material defaults; do not add custom easing.
> - **Accessibility.** Every `IconButton`/`Icon` that is not accompanied by a text label must have a non-null `contentDescription`; every `Icon` that *is* accompanied by its own label must have `contentDescription = null` (no double announcement). The two `VideoSurface`s already take one. Check the configure pill and the home tiles expose `Role.Button` via `Surface(onClick=…)`. Run TalkBack once through home → import → run → save.
> - **Launcher icon.** Replace `res/drawable/ic_launcher_foreground.xml` with the Phase 2 logo geometry scaled into the 108 dp adaptive-icon viewport (the safe zone is the central 72 dp, so scale the 96-unit artwork by ~0.66 and centre it), drawn in **`#B6ECEF`** (Primary 90), and set `res/drawable/ic_launcher_background.xml` to flat **`#004F53`** (Primary 30) — a 7.24:1 pairing, and the same two tones as the dark-theme primary container, so the launcher icon, the home medallion and the app's accent are demonstrably one palette. `res/mipmap-anydpi/ic_launcher.xml` already declares `<monochrome android:drawable="@drawable/ic_launcher_foreground" />`, so the themed-icon variant comes along for free.
> - **Small-screen check.** Run the matting screen on a 360 × 640 dp emulator with a completed run. If either preview drops below ~140 dp of height, switch that configuration to the Phase 6 Plan B single-preview layout rather than shrinking further. Also confirm the configure pill's summary ellipsises gracefully rather than pushing the chevron off-screen, and that Save + a two-segment switcher still fit on one 40 dp row.
> - **Light/dark parity sweep.** Screenshot home and matting (idle, running, done) in both themes and compare against the §5 contrast table. The two places most likely to have drifted are the preview label chip over bright footage and the Save button's disabled state.
> - **README.** Update the "Simple `VideoView`-based UI" bullet and the `MainActivity.kt` line in the Project Structure tree — both are now wrong (`HomeScreen`, ExoPlayer, `MediaStoreSaver`, the `ui/theme` package). Add a one-line note that the app uses a fixed brand colour scheme rather than dynamic colour, pointing at `docs/ui-redesign-plan.md` Key decision 3.

**Acceptance:** No unused string resources, no dead enum constants, no colour literal outside `Color.kt`, no icon-size literal inside a labelled button. The app reads as one designed product from launcher icon through home screen to matting screen, in both themes, and the small-screen configuration is verified.

---

## Dependency ordering and requirement coverage

| Phase | Delivers requirement(s) | Hard prerequisite | Why here |
| --- | --- | --- | --- |
| 1 — Colour, type & shape foundation | 1 | — | Every later phase draws colours, corners and text styles from here. Doing it first is what prevents eight phases inventing eight palettes; it is also why Phases 2–8 contain no colour definitions at all. |
| 2 — Logo, home screen, navigation, toast | 2, 3, 4, 1 | Phase 1 (`displayLarge`, `primaryContainer`, `surfaceContainerHigh`) | Also the phase that adds `material-icons-extended`, which every later phase's icons depend on. Independently testable: the matting screen is untouched apart from gaining a back arrow. |
| 3 — Non-scrolling matting layout + renames + configure pill | 5, 1, 6 (control) | Phase 2 (`onNavigateBack` param; icons artifact) | Must precede 5 and 7, which insert new elements into the layout this phase defines. Establishes `PreviewFrame`, the plate colours and the button-token conventions that 6 and 7 reuse verbatim. |
| 4 — Apply confirmation toast | 6 (toast) | Phase 3 (the configure pill is the only way into the sheet) | Two-line change; kept separate so its acceptance check is unambiguous. Unchanged by this revision. |
| 5 — Foreground output + switcher | 7 (selection) | Phase 3 (the slot `OutputBar` occupies) | Deliberately *before* Phase 6: it changes only state plumbing, so if the Media3 migration is deferred or reverted, requirement 7's "pick which output to view" half still ships on the existing `VideoView`s. **Builds `OutputBar` as a weighted `Row` specifically so Phase 7's Save button is an insertion, not a rewrite.** |
| 6 — Media3 + synchronized playback | 7 (sync), 1 | Phase 5 (`activeOutputUri` is what the follower player consumes) | Highest risk, so it sits as late as possible while still preceding nothing that depends on it. Carries an explicit Plan B. Also the phase that makes Phase 3's label chips and Phase 8's cross-fades actually render. |
| 7 — Cache outputs + Save to gallery | 8 | Phase 5 (the `OutputBar` slot Save occupies), Phase 6 only loosely (the unique-filename change fixes a stale-preview bug that either player exhibits) | Touches `Controller`, so keeping it after the UI phases means UI churn never has to be re-tested against a moving backend. Note the prerequisite moved from Phase 3 (top bar) to Phase 5 (`OutputBar`) in this revision. |
| 8 — Polish, consistency audit, cleanup | 1 | all | The consistency grep-audit and the dead-code sweep can only be run once every phase has landed. |

**New dependencies, in the order they appear:**

| Phase | Catalog `[libraries]` entry | `app/build.gradle.kts` line |
| --- | --- | --- |
| 2 | `androidx-compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }` (no version — BOM-managed at 1.7.8) | `implementation(libs.androidx.compose.material.icons.extended)` |
| 6 | `media3 = "1.8.0"` under `[versions]`; `androidx-media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }` | `implementation(libs.androidx.media3.exoplayer)` |

No other dependency is added. **Phase 1 adds none** — the fixed colour scheme is built entirely from `material3`'s public `lightColorScheme`/`darkColorScheme` builders, which is the main reason it is written as explicit hex rather than generated from a seed at runtime (no public seed generator exists in 1.4.0, and vendoring `material-color-utilities` to get one would be a dependency in service of a capability this design deliberately doesn't want).

Explicitly **not** added: `androidx.navigation:navigation-compose` (Key decision 1), `androidx.media3:media3-ui` / `media3-ui-compose` (a bare `TextureView` is smaller and gives full Compose layout control), `androidx.compose.ui:ui-text-google-fonts` (built-in families are enough for the wordmark), `com.google.android.material:material-color-utilities` (Key decision 3 §1).

---

### Critical files for implementation

- `app/src/main/java/dev/hamster/rvm/ui/theme/Color.kt` **(new — the palette, and the only file in the app allowed to contain a colour literal)**
- `app/src/main/java/dev/hamster/rvm/ui/theme/Theme.kt` (rewritten in Phase 1; `Type.kt` and `Shape.kt` are new alongside it)
- `app/src/main/java/dev/hamster/rvm/ui/MatteScreen.kt`
- `app/src/main/java/dev/hamster/rvm/ui/MatteViewModel.kt`
- `app/src/main/java/dev/hamster/rvm/Controller.kt`
- `app/src/main/java/dev/hamster/rvm/MainActivity.kt`
- `app/src/main/res/values/strings.xml` (with `res/values/colors.xml` + the new `res/values-night/colors.xml` + `res/values/themes.xml` for Phase 1, and `gradle/libs.versions.toml` + `app/build.gradle.kts` for the two dependency additions)

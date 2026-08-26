# UI Implementation Plan

A phased plan for building the matting UI. Each phase is scoped to be handed to an agent as a single instruction, and ends with an acceptance check you can verify before moving on.

**Target features**
1. Select a video for matting
2. Initialise it with a configuration
3. Run matting
4. Reset the app
5. Reconfigure (compute device, downsample ratio, resolution)
6. Show the active configuration on screen
7. Show progress on screen
8. Simple, elegant, follows the device theme

---

## Key decision: Jetpack Compose, not XML

Build Compose, replacing `activity_main.xml` + `findViewById`. Reasons specific to this project:

- **The dependencies are already there and unused.** `app/build.gradle.kts` already sets `buildFeatures { compose = true }` and pulls in the Compose BOM, `material3`, `ui`, `ui-tooling-preview`, and `activity-compose`. The `kotlin-compose` plugin is applied. Nothing new is needed to start.
- **`minSdk = 35` makes Material You free.** Dynamic color (`dynamicLightColorScheme` / `dynamicDarkColorScheme`) needs API 31+; since the floor is 35, it is *always* available — no version guard, no fallback palette. Feature 8 ("follow theme of device") becomes a handful of lines.
- **The current theme cannot satisfy feature 8.** `Theme.RVM` is `android:Theme.Material.Light.NoActionBar` with no `values-night/` variant, so the app is permanently light regardless of device setting.
- **Features 5–7 are state-driven.** A config sheet, a live config summary, and a progress bar are all reactive state. Compose handles this with far less code than manual `findViewById` + `setText` + visibility toggling.

`VideoView` has no Compose equivalent in this dependency set — wrap it in `AndroidView` (see Phase 3).

---

## Phase 0 — Blockers in the non-UI code (do this first)

These are correctness problems in `Controller`/`MatteModule` that make features 3, 4, 5, and 7 impossible to build on top of. **The UI cannot work until these are fixed.** Do not skip ahead.

### 0a. Matting runs on the main thread → guaranteed ANR

`MainActivity.onRelightClicked()` calls `controller.matteVideo()` directly, which loops over every frame doing decode + inference + encode synchronously. On a real video this blocks the UI thread for many seconds and Android will kill the app.

**Instruction to give:**
> In `Controller.kt`, make `matteVideo()` and `fgrVideo()` `suspend` functions that run their frame loop on `Dispatchers.Default`. Add an `onProgress: (current: Int, total: Int) -> Unit` parameter, invoked once per frame inside the loop. Call `coroutineContext.ensureActive()` at the top of each iteration so the job is cancellable. Do not change the matting logic itself.

**Acceptance:** `matteVideo` is `suspend`, takes a progress callback, and calls `ensureActive()` per frame.

### 0b. The module is closed after a single run → second run crashes

Both `matteVideo()` and `fgrVideo()` call `mattingModule.close()` at the end of the loop. `close()` sets `interpreter = null`, so the *next* run hits `interpreter!!` in `TFLiteModelRunner.run` and throws an NPE. This breaks "run again", "reset", and "reconfigure" (features 3, 4, 5).

**Instruction to give:**
> In `Controller.kt`, remove the `mattingModule.close()` call from the end of both `matteVideo()` and `fgrVideo()`. Instead, call `mattingModule.reset()` at the *start* of each run so hidden states begin zeroed for a new video. Add a separate `Controller.close()` that closes `mattingModule`, to be called from the UI's lifecycle teardown only.

**Acceptance:** Running matting twice in a row without restarting the app succeeds.

### 0c. Config resolution can name a model file that does not exist

`MatteConfig.buildModelFileName()` derives a filename from backbone + resolution + downsample ratio, but `app/src/main/assets/` currently holds only four models:

```
rvm_mobilenetv3_720x1280_ds_100.tflite
rvm_mobilenetv3_720x1280_ds_auto.tflite
rvm_resnet50_720x1280_ds_100.tflite
rvm_resnet50_720x1280_ds_auto.tflite
```

Any other combination (a different resolution, an int8 dtype) resolves to a name `context.assets.openFd()` will throw `FileNotFoundException` on. Since feature 5 lets the user change resolution and downsample ratio, **the UI must only offer combinations that actually exist**, or every wrong pick is a crash.

**Instruction to give:**
> Add `dev/hamster/rvm/utils/ModelCatalog.kt`. It should list `context.assets.list("")`, keep entries matching `rvm_<backbone>_<h>x<w>_ds_<tag>.tflite`, parse each into a data class holding backbone, height, width, and downsample tag, and expose: the full parsed list, the distinct resolutions available, the backbones available for a given resolution, and the downsample tags available for a given backbone + resolution. This is a pure parser over the asset list — it must not load any model.

**Acceptance:** `ModelCatalog` returns 4 entries, 1 resolution (720x1280), 2 backbones, 2 downsample tags.

### 0d. Config resolution and buffer sizing disagree

`Controller.loadModels()` hardcodes `MatteConfig()` (defaults to 720x1280), but `matteVideo()` sizes its `SharedBuffer`s from the *video's* dimensions read off `VideoHandlerModule`. If the selected video is not 720x1280, the buffers do not match the model's tensors. Feature 5 explicitly wants the user to control "the height and width in which the bitmap is read and saved", so this needs one source of truth.

**Instruction to give:**
> In `Controller.kt`, replace `loadModels()` with `configure(config: MatteConfig)` which stores the config and passes it to `mattingModule.configure(...)`. In `matteVideo()`/`fgrVideo()`, size every `SharedBuffer` from the stored config's `height`/`width` rather than from `videoHandler.getHeight()/getWidth()`. Keep the video's own dimensions only for decode/encode setup.

**Acceptance:** Buffer allocations reference `config.height`/`config.width`; the config is the single source of truth for tensor sizing.

---

## Phase 1 — Theme foundation

**Goal:** feature 8. A Material 3 theme that follows the device's light/dark setting and uses Material You dynamic color.

**Instruction to give:**
> Create `dev/hamster/rvm/ui/theme/Theme.kt` with an `RvmTheme(content: @Composable () -> Unit)` composable. Use `isSystemInDarkTheme()` to choose between `dynamicDarkColorScheme(context)` and `dynamicLightColorScheme(context)` — no API-level guard is needed since `minSdk` is 35. Wrap in `MaterialTheme`. Also update `res/values/themes.xml` so `Theme.RVM` extends a `Theme.Material3.DayNight.NoActionBar`-style parent instead of `android:Theme.Material.Light.NoActionBar`, so the system bars follow the theme too.

**Acceptance:** Toggling the device's dark mode changes the app's colors, including the status bar.

---

## Phase 2 — UI state model and ViewModel

**Goal:** one observable state object the whole screen renders from. This is what makes features 6 and 7 trivial later.

**Note:** this needs a dependency that is not yet present — add `androidx.lifecycle:lifecycle-viewmodel-compose` to `app/build.gradle.kts`.

**Instruction to give:**
> Add `androidx.lifecycle:lifecycle-viewmodel-compose` to the app dependencies. Create `dev/hamster/rvm/ui/MatteViewModel.kt` holding a `StateFlow<MatteUiState>`, where `MatteUiState` is a data class with: `selectedVideoUri: Uri?`, `outputVideoUri: Uri?`, `config: MatteConfig`, `stage: Stage` (an enum of `IDLE`, `CONFIGURING`, `RUNNING`, `DONE`, `ERROR`), `processedFrames: Int`, `totalFrames: Int`, and `errorMessage: String?`. Give it methods `onVideoSelected(uri)`, `updateConfig(config)`, `runMatting()`, `reset()`, and `cancel()`. `runMatting()` launches into `viewModelScope`, calls the suspend `Controller.matteVideo` from Phase 0a, and pushes progress into state via the callback. Hold the running job so `cancel()` can stop it. Override `onCleared()` to call `Controller.close()`.

**Acceptance:** The ViewModel compiles and exposes state; no UI wired yet.

---

## Phase 3 — Main screen scaffold, picker, and previews

**Goal:** features 1 and 3/4's button placement (behaviour comes later).

**Instruction to give:**
> Create `dev/hamster/rvm/ui/MatteScreen.kt`. Use a `Scaffold` with a `TopAppBar` titled with the app name. In the body, lay out vertically in a scrollable column: an input video preview, an output video preview, and a button row. For the previews, write a small `VideoPlayer(uri: Uri?, modifier: Modifier)` composable that wraps `VideoView` in `AndroidView` — keep the existing `setZOrderOnTop(true)` workaround from `MainActivity` and the aspect-ratio fitting from its `playVideo`, and show a placeholder surface when `uri` is null. Wire "Select Video" to `rememberLauncherForActivityResult(ActivityResultContracts.GetContent())` with `"video/*"`, calling `viewModel.onVideoSelected`. Then rewrite `MainActivity` to `setContent { RvmTheme { MatteScreen(viewModel) } } ` and delete `activity_main.xml` and the `findViewById` code.

**Acceptance:** The app launches into the Compose screen, a video can be picked, and it plays in the input preview.

---

## Phase 4 — Configuration sheet

**Goal:** feature 5. **Depends on Phase 0c** — every option must come from `ModelCatalog`, never be free-form.

**Instruction to give:**
> Create `dev/hamster/rvm/ui/ConfigSheet.kt` as a Material 3 `ModalBottomSheet`, opened by a "Configure" button. It edits a *draft* copy of `MatteConfig` and only commits via `viewModel.updateConfig` when the user taps Apply (Cancel discards). Controls:
> - **Compute device** — a `SegmentedButton` row over `RuntimeConfig.ComputeDevice` (CPU / GPU / NPU / AUTO), writing to `config.runtimeConfig.device`.
> - **Resolution** — a dropdown populated from `ModelCatalog`'s available resolutions, writing `height`/`width`.
> - **Backbone** — a dropdown over the backbones the catalog reports for the chosen resolution, writing `variant`.
> - **Downsample ratio** — a dropdown over the tags the catalog reports for the chosen backbone + resolution; the `auto` tag maps to the `-1.0f` sentinel that `MatteConfig.init` resolves via `min(512 / max(h, w), 1.0)`.
> - **Threads** — a slider or stepper for `runtimeConfig.numThreads`.
>
> Each dropdown must narrow the ones below it so an unavailable combination cannot be selected. Disable Apply while matting is running.

**Acceptance:** Every selectable combination resolves to a `.tflite` that exists in assets; no picker state can produce a missing file.

---

## Phase 5 — Active configuration display

**Goal:** feature 6.

**Instruction to give:**
> Add a `ConfigCard` composable to `MatteScreen`, rendering the current `MatteConfig` as a Material 3 `Card` of label/value rows: compute device, resolution (`720 × 1280`), backbone, downsample ratio (showing "Auto (0.40)" when the sentinel resolved), dtype, and thread count. Include the resolved `config.runtimeConfig.modelFileName` in a monospace style as the last row — it is the most useful single line for confirming what will actually be loaded. Keep it visually quiet: `MaterialTheme.colorScheme.surfaceVariant`, labels in `onSurfaceVariant`, values in `onSurface`.

**Acceptance:** Changing anything in the config sheet updates this card immediately, including the model filename.

---

## Phase 6 — Run and progress

**Goal:** features 3 and 7.

**Instruction to give:**
> Wire the "Run Matting" button to `viewModel.runMatting()`, enabled only when a video is selected and `stage` is not `RUNNING`. While `RUNNING`, show a determinate `LinearProgressIndicator` driven by `processedFrames / totalFrames`, plus a caption reading `"Frame 42 / 300 · 68%"`, and swap the run button for a "Cancel" button calling `viewModel.cancel()`. On `DONE`, load the output URI into the output preview. On `ERROR`, show the message in a `Snackbar` or an error-colored card. Keep the screen awake during a run with `FLAG_KEEP_SCREEN_ON`.

**Acceptance:** Progress advances smoothly frame by frame, the UI stays responsive, and Cancel actually stops the run.

---

## Phase 7 — Reset

**Goal:** feature 4.

**Instruction to give:**
> Wire a "Reset" button to `viewModel.reset()`, which cancels any running job, clears `selectedVideoUri` and `outputVideoUri`, resets `stage` to `IDLE`, zeroes the progress counters, clears `errorMessage`, and calls `Controller`'s module `reset()` so hidden states are zeroed. Leave the user's chosen `MatteConfig` intact — resetting the run should not discard their configuration. Show a confirmation dialog if a run is in progress.

**Acceptance:** After Reset the app is back to its initial state with previews cleared, but the configured device/resolution/backbone are still selected.

---

## Phase 8 — Polish

**Instruction to give:**
> - Add `contentDescription` to icon buttons and previews for accessibility.
> - Handle process death: mark `MatteUiState` `@Parcelize` or persist the config via `SavedStateHandle`.
> - Show elapsed time and average ms/frame on completion — the data is already logged by `MatteModule` and `Controller`.
> - Replace any leftover hardcoded UI strings with `strings.xml` entries; delete the now-unused `error_*`/`status_*` strings that the Compose flow no longer references.
> - Remove the stray `GID_Debug` log line in the old `MainActivity` code path.

---

## Suggested ordering

Phase 0 is a hard prerequisite — 0a and 0b in particular, since without them the app ANRs on the first run and crashes on the second. Phases 1–3 give a working skeleton worth checking on a device. Phases 4–7 map one-to-one onto the remaining features and can be done in any order, though 4 before 5 is natural (the card renders what the sheet edits). Phase 8 is optional.

| Phase | Features covered |
| --- | --- |
| 0 | *(prerequisite — none directly)* |
| 1 | 8 |
| 2 | *(foundation for 6, 7)* |
| 3 | 1 |
| 4 | 5 |
| 5 | 6 |
| 6 | 3, 7 |
| 7 | 4 |
| 8 | polish |

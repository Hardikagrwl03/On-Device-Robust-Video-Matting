# On-Demand Model Download Plan — RVM

A phased plan for removing bundled `.tflite` assets and replacing them with a
download-on-demand model store backed by the
[`models-v1` GitHub release](https://github.com/Hardikagrwl03/On-Device-Robust-Video-Matting/releases/tag/models-v1).
Same convention as `docs/ui-redesign-plan.md` and `docs/threading-plan.md`: each phase is a
self-contained instruction that can be handed to a fresh session on its own, followed by a check
you can run on a device before starting the next. **Every phase ends with the app in a runnable
state** — no phase leaves the build broken waiting on the next one.

**Target requirements** (verbatim from the brief, numbered for reference below)

1. The app doesn't prestore the `.tflite` models in the assets folder.
2. On first install, the mobilenet and resnet models for **auto** downsample from the **gpu**
   source are downloaded from GitHub release `models-v1`.
3. The first page gets an option to download TFLite models, leading to a page with an entry per
   model showing the converter config it was created with; tapping one downloads it, and it stays
   downloaded forever.
4. That page marks already-downloaded models distinctly (font colour / entry appearance).
5. All new UI matches the existing theme and design language.

---

## Status: implemented and verified on a Galaxy S23 FE (SM-S711B)

All five phases are done. Each phase's acceptance check was run on-device before starting the
next; the per-phase notes below record what was actually observed and where the implementation
deviated from this document.

> **Phase 1 — verified.** A temporary trigger downloaded `rvm_gpu_mobilenetv3_720x1280_ds_auto.tflite`
> in 21.8 s (0.7 MB/s), landing at exactly `15219308` bytes with no `.part` orphan; relaunching
> re-reported `Installed` without re-downloading. The offline path failed with
> `SocketTimeoutException` after the 15 s connect timeout, mapped to "Connection timed out", and
> deleted its `.part`.
>
> *Deviation:* the five `error_*` strings listed under §2e were added in Phase 1 instead, since
> §1d's exception mapping consumes them and Phase 1 has to build and run on its own.
>
> *Deviation:* `onProgress` is throttled on **time alone** (250 ms), not "time or 1%, whichever is
> coarser". Requiring both would stall the UI for many seconds on a slow link — precisely when
> liveness matters most — while time alone already caps a 107 MB transfer at ~4 updates/sec.

> **Phase 2 — verified.** All 8 rows rendered with correct config lines and `Formatter`-derived
> sizes. A tapped model went `NotInstalled → Downloading → Installed`; the badge tracked `0/8 →
> 1/8`; state survived a force-stop and relaunch. Starting the 108 MB ResNet50 download, navigating
> to Home and back showed it **still running** with its `.part` growing — the check that proves Key
> decision 4. Tapping a second model while that ran showed `Queued`, and tapping an installed row
> did nothing.
>
> *Deviation:* the `Installed` row shows the check icon **without** the "Downloaded" badge. Badge +
> icon together are wide enough to wrap that card's subtitle onto a second line, making the
> installed row taller and raggeder than every other row; a uniform 28 dp trailing keeps all eight
> cards identical, and the accented container plus check already read unambiguously.

> **Phase 3 — verified.** The APK contains **zero** `.tflite` files and no `assets/` entry at all.
> The interpreter loaded `rvm_gpu_resnet50_720x1280_ds_auto.tflite` from `filesDir/models/`;
> switching backbone in the sheet rebuilt it as `rvm_gpu_mobilenetv3_720x1280_ds_auto.tflite`. A
> full matting run completed in 27.2 s (425 ms/frame) producing all three outputs. After
> downloading an `original` model, Source offered both and selecting it narrowed Backbone and
> Downsample to the single installed combination.
>
> *Deviation:* `source` is also persisted through `SavedStateHandle` (`KEY_SOURCE`). Without it a
> user's `original` selection silently reverted to `gpu` after process death — a correctness gap
> opened by §3a, not something this document called out.

> **Phase 4 — verified.** A genuinely fresh install auto-downloaded mobilenetv3 (6.9 s) then
> resnet50 serially, badge counting `0/8 → 1/8 → 2/8`. The ViewModel configured **mobilenetv3** 13 s
> after launch rather than waiting ~60 s for the resnet50 the default config names — the fallback
> working as intended. A matting run then completed (27.2 s) without ever visiting the Models page.
> A fresh install with Wi-Fi and mobile data genuinely off did not crash: the badge stayed `0/8`,
> the Video Matte tile read "Tap to download a model" and routed to Models, both bootstrap rows
> rendered in `errorContainer` with "No internet connection", and retry succeeded once the network
> returned.
>
> *Note:* airplane mode alone is **not** a valid offline test on this device — Samsung keeps Wi-Fi
> enabled across it, and the first attempt at this check downloaded normally. Use
> `adb shell svc wifi disable && adb shell svc data disable`.
>
> *Deviation:* §4b's `restoreConfig()` fallback is implemented inside `resolveAndConfigure()` in
> `init` instead of in `restoreConfig()` itself. One mechanism covers both cases this document
> treated separately — a restored config naming an uninstalled model, and a *default* config naming
> one that hasn't finished downloading — and the latter is the common case on first launch.

### Defect found during Phase 3 that this plan did not anticipate

Applying an `original`-source model with the default `GPU` compute device **crashed the app**. The
GPU delegate is mandatory once added: it refuses to prepare a graph containing ops it doesn't
implement — `GATHER_ND`, `RELU_0_TO_1`, `STABLEHLO_REDUCE_WINDOW`, exactly the ops the converter's
`model_gpu/` tree rewrites — and `Interpreter`'s constructor throws rather than silently running
them on CPU. That `IllegalArgumentException` propagated through `ConfinedRunner`, out of
`runConfigure`'s `viewModelScope`, and killed the process.

This document made all 8 models installable without considering that half of them are incompatible
with the default compute device, so two fixes were added beyond its scope:

1. **`TFLiteModelRunner.loadModel` catches the delegate rejection and rebuilds on CPU.** Slower but
   numerically identical, and the alternative is killing the app for a selection the UI offers.
   Guards any future model/delegate incompatibility, not just this one.
2. **`MatteViewModel.runConfigure` reports failures instead of throwing.** Configuration can fail
   for reasons outside the app's control (a model file removed from under it, a delegate refusing
   the graph); an uncaught throw there takes the process down.

**Follow-up (done).** The silent fallback was replaced as the *primary* path by an up-front block,
on request: applying `original` with `GPU` or `NPU` now coerces the device to CPU and reports it in
a toast, so the UI never claims an accelerator it isn't using. `AUTO` is deliberately exempt —
falling back through NNAPI → GPU → CPU is its whole purpose — and `TFLiteModelRunner`'s CPU
fallback stays as a backstop for any pairing the check doesn't anticipate. Verified on device:
`original` + GPU and `original` + NPU both land on `Device: CPU` with the toast shown, `gpu` + GPU
still applies normally, and no crash occurs.

> **Second defect, found while verifying that follow-up.** The coercion is a `MatteConfig.copy()`,
> and `MatteConfig.init` has *already* resolved the `-1.0F` "auto" sentinel to a concrete `0.4` by
> then — so the copy rebuilt the model filename as `_ds_040` (a file the release doesn't publish)
> instead of `_ds_auto`, and configuration failed with "Model not downloaded". This is the same
> trap `persistConfig` had a hand-rolled guard for, and it will catch *any* future `copy()` on a
> resolved config. Fixed properly by giving `MatteConfig` a `requestedDownsampleRatio` property
> that recovers the sentinel from the filename; the coercion and `persistConfig` both use it, and
> the duplicated `contains("_ds_auto")` string is gone. Worth noting that the Phase 3 error
> handling above is what turned this into a legible toast instead of a crash.

---

## Verified starting state (as of `612e228`, the current `HEAD` of `application`)

Confirmed by reading the files and querying the release API. The plan below depends on these
being true.

| Fact | Status |
| --- | --- |
| `app/src/main/assets/` holds exactly 4 `.tflite` files: `rvm_{mobilenetv3,resnet50}_720x1280_ds_{100,auto}.tflite` | ✅ confirmed |
| Those 4 files are **untracked** — `app/.gitignore` line 2 is `*.tflite`, and `git ls-tree -r HEAD` shows no `assets/` entries | ✅ confirmed. **The repo has never shipped the models; the APK only builds with models if you copy them in by hand.** Requirement 1 is therefore mostly about deleting the *code path*, and the local copies. |
| The 4 local assets are byte-for-byte the same sizes as the four **`gpu`**-source release assets (`15200516`, `15219308`, `107663768`, `107683140`) | ✅ confirmed — the app has been running gpu-source builds all along; its filename convention just omits the `source` component |
| `TFLiteModelRunner.loadModelFile(modelFileName)` is the **only** place a model is opened: `context.assets.openFd(name)` → `FileInputStream` → `fileChannel.map(READ_ONLY, startOffset, declaredLength)` → `MappedByteBuffer` | ✅ confirmed, `TFLiteModelRunner.kt:167–174` |
| `ModelCatalog` is the **only other** consumer of `assets`: `context.assets.list("")` filtered through `FILE_NAME_REGEX` | ✅ confirmed, `ModelCatalog.kt:31` |
| `ModelCatalog`'s regex is `rvm_([a-zA-Z0-9]+)_(\d+)x(\d+)_ds_([a-zA-Z0-9]+)(?:_(int8\|fp16))?\.tflite` — **no `source` group** | ✅ confirmed, `ModelCatalog.kt:26–27` |
| `MatteConfig.buildModelFileName()` produces `rvm_<backbone>_<h>x<w>_ds_<tag>.tflite` — **no `source` component**, and `dtypeTag` is computed then never used | ✅ confirmed, `MatteConfig.kt:39–42` |
| `MatteConfig.init` calls `buildModelFileName()` **before** resolving the `-1.0F` auto sentinel, so `_ds_auto` survives into the filename | ✅ confirmed, `MatteConfig.kt:32–36`. **This ordering must not change.** |
| `MatteConfig()` defaults are `720×1280`, `RESNET50`, `downsampleRatio = 1.0F` → resolves to `rvm_resnet50_720x1280_ds_100.tflite` | ✅ confirmed — **not** one of the two bootstrap models (requirement 2 names `ds auto`). See Key decision 5. |
| Release `models-v1` has exactly 8 assets, all `720x1280`, named `rvm_<source>_<variant>_720x1280_ds_<100\|auto>.tflite` for `source ∈ {gpu, original}`, `variant ∈ {mobilenetv3, resnet50}` | ✅ confirmed via the GitHub API; exact byte sizes in the Phase 1 manifest table |
| Those URLs need no auth and redirect once (`302` → `objects.githubusercontent.com`), https → https | ✅ per `converter/rvmConverter/README.md`; https→https redirects **are** followed by `HttpURLConnection`'s default `instanceFollowRedirects` (only cross-protocol hops are not) |
| `AndroidManifest.xml` declares **no** `<uses-permission>` at all — no `INTERNET` | ✅ confirmed. **Nothing can download today.** |
| `android:allowBackup="true"`, with `backup_rules.xml` (API < 31) and `data_extraction_rules.xml` (API ≥ 31) both effectively empty | ✅ confirmed. `minSdk = 35`, so only `data_extraction_rules.xml` applies in practice. |
| No networking dependency of any kind (no OkHttp, Ktor, Retrofit, `WorkManager`) | ✅ confirmed in `app/build.gradle.kts` |
| `androidx.compose.material:material-icons-extended` **is** a dependency | ✅ confirmed — `Icons.Filled.Download` / `CheckCircle` / `Refresh` / `CloudOff` are all available without a new dependency |
| `MainActivity` navigates with a local `private enum class RvmDestination { HOME, MATTE }` + `rememberSaveable` + `BackHandler(enabled = destination != HOME)`. No `androidx.navigation`. | ✅ confirmed, `MainActivity.kt:20, 38–56` |
| `HomeScreen(isModelReady, onOpenVideoMatte, modifier)` renders exactly two `HomeActionTile`s; the tile takes `icon/title/subtitle/containerColor/contentColor/badge/loading/onClick` | ✅ confirmed, `HomeScreen.kt:44, 100–125, 129–139` |
| `MatteViewModel.init { runConfigure(_uiState.value.config) }` runs unconditionally at construction, and `MainActivity` collects `uiState` eagerly precisely to force that to start at launch | ✅ confirmed, `MatteViewModel.kt:89` and `MainActivity.kt:41–44`. **With no bundled asset this throws `FileNotFoundException` on a fresh install.** |
| `MatteViewModel.runConfigure` raises `isConfiguring` **synchronously** before `viewModelScope.launch` and clears it in a `finally` | ✅ confirmed, `MatteViewModel.kt:107–118`. **Must not be reverted** — see the doc comment for the TOCTOU race it fixes. |
| `MatteModule`/`VideoFrameDecoder`/`VideoFrameEncoder` are each confined to a dedicated thread via `utils/ConfinedRunner.kt` | ✅ confirmed — see `docs/threading-plan.md`. Relevant here only as a constraint: **model loading still happens on `rvm-matte`**, so a download must never be performed from inside `MatteModule`. |
| `ConfigSheet` narrows resolution → backbone → downsample-tag from `ModelCatalog`, so no reachable selection can name a missing file | ✅ confirmed, `ConfigSheet.kt:129–147` |
| `RvmTheme` is a fixed (non-dynamic) scheme defining every `surfaceContainer*`, `primaryContainer`, `secondaryContainer`, `errorContainer` role, `RvmShapes` (10/16/20/28 dp), and `RvmTypography` | ✅ confirmed, `ui/theme/*.kt` |
| Outputs go to `context.cacheDir/rvm_runs/` | ✅ confirmed, `Controller.kt:185`. Models must **not** go there (see Key decision 2). |

---

## Key decisions

### 1. Model identity gains a `source` dimension

The release names files `rvm_<source>_<variant>_<h>x<w>_ds_<tag>.tflite`; the app names them
`rvm_<variant>_<h>x<w>_ds_<tag>.tflite`. Since the download page must offer all 8 (requirement 3),
and 4 of those differ from the other 4 *only* by source, `source` has to become part of the app's
model identity rather than something stripped at download time. Concretely:

- a new top-level `enum class ModelSource(val tag: String) { GPU("gpu"), ORIGINAL("original") }`,
- `MatteConfig` gains `var source: ModelSource = ModelSource.GPU`,
- `buildModelFileName()` emits the source component,
- **downloaded files keep their release filename verbatim on disk**, so the manifest, the URL, the
  on-disk name and `RuntimeConfig.modelFileName` are all the same string. There is no mapping
  layer to get out of sync.

While touching `buildModelFileName()`, fix the downsample tag to `%03d` (`0.5` currently yields
`50`, but the converter's convention is `050`). Latent only — no `050` model is published — but
it costs one format string. Leave the unused `dtypeTag` alone; the release publishes no `int8`/
`fp16` builds, and `docs/ui-redesign-plan.md` already ruled it cosmetic.

### 2. Models live in `filesDir/models/`, excluded from backup

- **Not `cacheDir`** — the OS evicts it under storage pressure, and requirement 3 says
  "stays downloaded in the app forever".
- **Not external storage** — no reason to expose ~500 MB of blobs to the gallery/file manager, and
  internal storage needs no permission and no scoped-storage handling.
- **Excluded from cloud backup and device transfer.** A full set is ~495 MB of freely
  re-downloadable data; letting Android back it up would be user-hostile. Add
  `<exclude domain="file" path="models/"/>` to `data_extraction_rules.xml` (the one that applies at
  `minSdk 35`) and mirror it in `backup_rules.xml` for consistency.

### 3. Transport is `HttpURLConnection`, not a new dependency

The app has no networking library and doesn't need one for eight static URLs. `HttpURLConnection`
on `Dispatchers.IO` follows the release's single https→https redirect by default and gives exact
byte-level progress, which is what the download page needs.

The integrity check is **`Content-Length` and final on-disk length must both equal the manifest's
recorded byte size**. The release publishes no checksums, so this is the strongest check available
without changing the converter side. Download to `<name>.tflite.part` and `renameTo` the real name
only after that check passes, so a truncated file can never be mistaken for an installed model.

> **Worth doing on the converter side later:** publish a `SHA256SUMS` asset on the release and add
> a `sha256` field to the manifest. Length-matching catches truncation but not corruption.

### 4. Download state lives in a process-wide `ModelRepository`, not a ViewModel

A 107 MB download must survive navigating off the models page, so its coroutine cannot live in a
`viewModelScope`. `ModelRepository` is a singleton (`companion object { fun get(context): … }`
holding a `@Volatile` instance built from `applicationContext`) owning:

- `CoroutineScope(SupervisorJob() + Dispatchers.IO)`,
- `StateFlow<Map<String, ModelDownloadState>>` keyed by filename, seeded from disk at construction,
- a `Mutex` so downloads run **one at a time** (two concurrent 107 MB streams just halve each
  other's bandwidth and double peak disk usage; queued entries show a distinct `Queued` state so
  the UI never has to lie about a stalled-looking 0%).

The screen collects that flow directly with `collectAsState()`. No `ModelsViewModel` is needed —
adding one would only duplicate state that already outlives the screen.

*Known limitation:* the download still dies with the process. Resumable, process-independent
downloads would mean `WorkManager` + HTTP `Range` requests; that's deliberately out of scope here
and called out under Risks.

### 5. The default `MatteConfig` must name a bootstrap model

`MatteConfig()` currently resolves to `rvm_resnet50_720x1280_ds_100.tflite`, but requirement 2
bootstraps the two **`ds auto`** models. Change the defaults to `source = GPU`,
`variant = RESNET50`, `downsampleRatio = -1.0F` → `rvm_gpu_resnet50_720x1280_ds_auto.tflite`.

This is a **behaviour change**: `ds auto` traces RVM's refiner branch at ~0.4 scale instead of
running the full-resolution graph, so matte quality and per-frame time both shift. It is forced by
the brief, and it keeps the default *variant* as-is.

> **Alternative worth considering:** default to `mobilenetv3` instead. It is 15 MB rather than
> 104 MB, so a fresh install becomes usable in seconds rather than minutes on a slow connection.
> One-line change if first-run time-to-usable matters more than matching today's default variant.

### 6. "Tabs" are list cards, not a `TabRow`

The brief says "tabs for all models". Material's `TabRow` is for switching between sibling views,
not for 8 downloadable items with per-item progress and state. The established language in this
app for a tappable, stateful, icon+title+subtitle+badge row is `HomeScreen`'s `HomeActionTile`
(`Surface(onClick=…, shape = MaterialTheme.shapes.extraLarge, color = …)`), so the models page
reuses exactly that shape — which is also what satisfies requirement 5.

---

## Phase 1 — The model store and the download engine (headless)

Nothing user-visible changes. The app still loads from `assets/`. This phase only adds the
machinery, so it can be verified in isolation before anything depends on it.

**New package `dev.hamster.rvm.models`.**

### 1a. `models/ModelManifest.kt`

```kotlin
enum class ModelSource(val tag: String) { GPU("gpu"), ORIGINAL("original") }

data class ModelSpec(
    val source: ModelSource,
    val backbone: String,        // "mobilenetv3" | "resnet50" -- a String, not MatteConfig.Variant,
                                 // so this package stays independent of `matte` (MatteConfig
                                 // already has to import ModelSource from here; a Variant import
                                 // back the other way would make that a cycle).
    val height: Int,
    val width: Int,
    val downsampleTag: String,   // "100" | "auto"
    val sizeBytes: Long
) {
    val fileName: String get() = "rvm_${source.tag}_${backbone}_${height}x${width}_ds_$downsampleTag.tflite"
    val url: String get() = "$RELEASE_BASE_URL/$fileName"
}
```

`ModelManifest.ALL` is this exact table, in this order (the order the models page renders):

| # | source | backbone | h×w | ds | `sizeBytes` |
|---|---|---|---|---|---|
| 1 | gpu | mobilenetv3 | 720×1280 | auto | `15219308` |
| 2 | gpu | mobilenetv3 | 720×1280 | 100 | `15200516` |
| 3 | gpu | resnet50 | 720×1280 | auto | `107683140` |
| 4 | gpu | resnet50 | 720×1280 | 100 | `107663768` |
| 5 | original | mobilenetv3 | 720×1280 | auto | `15419312` |
| 6 | original | mobilenetv3 | 720×1280 | 100 | `16368288` |
| 7 | original | resnet50 | 720×1280 | auto | `107874080` |
| 8 | original | resnet50 | 720×1280 | 100 | `108822888` |

`RELEASE_BASE_URL = "https://github.com/Hardikagrwl03/On-Device-Robust-Video-Matting/releases/download/models-v1"`.

Also expose `ModelManifest.BOOTSTRAP` = entries 1 and 3 (gpu + auto, both backbones — requirement 2)
and `ModelManifest.byFileName(name): ModelSpec?`.

> These byte sizes are the release's current asset sizes and are what the integrity check compares
> against. **If the `models-v1` assets are ever re-uploaded, this table must be updated in the same
> commit** — see Risks.

### 1b. `models/ModelStore.kt`

Pure filesystem, no coroutines:

```kotlin
class ModelStore(context: Context) {
    val dir: File = File(context.filesDir, "models").apply { mkdirs() }
    fun fileFor(fileName: String): File
    fun isInstalled(fileName: String): Boolean   // exists() && length() == manifest size
    fun installedSpecs(): List<ModelSpec>        // ModelManifest.ALL.filter { isInstalled(it.fileName) }
    fun hasFreeSpaceFor(spec: ModelSpec): Boolean // dir.usableSpace > spec.sizeBytes * 11 / 10
}
```

`isInstalled` checks length, not just existence, so a file left behind by some other failure mode
is treated as absent rather than handed to the interpreter.

### 1c. `models/ModelDownloader.kt`

```kotlin
suspend fun download(spec: ModelSpec, store: ModelStore, onProgress: (bytesRead: Long, total: Long) -> Unit)
```

- Fail fast with a typed error before opening a stream if `!store.hasFreeSpaceFor(spec)`.
- `URL(spec.url).openConnection() as HttpURLConnection`, `connectTimeout = 15_000`,
  `readTimeout = 30_000`. Leave `instanceFollowRedirects` at its default `true`.
- Require `responseCode == 200` and `contentLengthLong == spec.sizeBytes`; otherwise abort before
  writing ~100 MB.
- Stream into `File(dir, "${spec.fileName}.part")` with a 64 KB buffer.
- Call `coroutineContext.ensureActive()` each loop iteration so cancellation actually stops the
  transfer (same idiom `Controller.matteVideo` already uses).
- **Throttle `onProgress` to at most one emission per ~250 ms or per 1%**, whichever is coarser. At
  64 KB granularity a 107 MB download is ~1,700 `StateFlow` emissions; unthrottled that recomposes
  the models page into the ground.
- On success: assert `part.length() == spec.sizeBytes`, then `part.renameTo(store.fileFor(...))`.
- On **any** failure or cancellation: `part.delete()` in a `finally`, then rethrow.
- Log under `TAG = "ModelDownloader"` — one line at start (`url`, expected size), one on completion
  (elapsed ms, achieved MB/s), one on failure. Matches the codebase's existing logging density.

### 1d. `models/ModelRepository.kt`

```kotlin
sealed interface ModelDownloadState {
    data object NotInstalled : ModelDownloadState
    data object Queued : ModelDownloadState
    data class Downloading(val bytesRead: Long, val total: Long) : ModelDownloadState {
        val fraction: Float get() = if (total > 0) bytesRead.toFloat() / total else 0f
    }
    data object Installed : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}
```

```kotlin
class ModelRepository private constructor(context: Context) {
    companion object { fun get(context: Context): ModelRepository }  // @Volatile singleton on applicationContext
    val states: StateFlow<Map<String, ModelDownloadState>>
    fun download(spec: ModelSpec)          // no-op if already Installed/Queued/Downloading
    fun ensureBootstrapModels()            // idempotent; enqueues missing BOOTSTRAP entries
    fun retry(spec: ModelSpec)             // clears Failed, re-enqueues
}
```

- Seed `states` from `ModelStore.installedSpecs()` at construction.
- `download` sets `Queued`, launches on the repository scope, takes the `Mutex`, flips to
  `Downloading`, and lands on `Installed` or `Failed(message)`.
- Track in-flight jobs in a `Map<String, Job>` so a double-tap can't start a second transfer.
- Map exceptions to short user-facing strings (`UnknownHostException` → "No internet connection",
  `SocketTimeoutException` → "Connection timed out", the insufficient-space error → "Not enough
  storage", anything else → `e.message`). Log the full stack trace at `Log.e`; only the short form
  reaches the UI.
- `TAG = "ModelRepository"`.

### 1e. Manifest and backup rules

- `AndroidManifest.xml`: add `<uses-permission android:name="android.permission.INTERNET" />`
  above `<application>`. **No** `usesCleartextTraffic` — every URL is https.
- `data_extraction_rules.xml`: `<cloud-backup><exclude domain="file" path="models/" /></cloud-backup>`
  and the same `<exclude>` under a `<device-transfer>` block.
- `backup_rules.xml`: mirror with `<exclude domain="file" path="models/" />`.

### ✅ Check before Phase 2

Temporarily call `ModelRepository.get(this).download(ModelManifest.ALL[0])` from
`MainActivity.onCreate` (the 15 MB mobilenetv3 entry — remove this line before committing), then:

```bash
adb logcat -c && adb shell am start -n dev.hamster.rvm/.MainActivity
adb logcat -s ModelDownloader:D ModelRepository:D ModelRepository:E
adb shell run-as dev.hamster.rvm ls -l files/models
```

Expect: no `.part` file left behind, one `rvm_gpu_mobilenetv3_720x1280_ds_auto.tflite` of exactly
`15219308` bytes, and a completion log line. Then re-launch and confirm the repository reports
`Installed` without re-downloading. Then test the failure path — enable airplane mode, clear
`files/models`, relaunch — and confirm a `Failed("No internet connection")` state and **no**
leftover `.part`.

---

## Phase 2 — The models page, the home entry point, and navigation

Still no change to how matting loads models; this phase makes all 8 downloadable through the UI.

### 2a. Navigation

`MainActivity`: extend the enum to `private enum class RvmDestination { HOME, MATTE, MODELS }`.
The existing `BackHandler(enabled = destination != RvmDestination.HOME)` already covers the new
destination — no change needed there. Add the `MODELS ->` branch rendering
`ModelsScreen(onNavigateBack = { destination = RvmDestination.HOME })`.

### 2b. `ui/ModelsScreen.kt`

```kotlin
@Composable
fun ModelsScreen(onNavigateBack: () -> Unit)
```

- `Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.models_title)) },
  navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, …) } }) })`
  — identical to `MatteScreen`'s top bar.
- Body: `LazyColumn`, `contentPadding` 16 dp horizontal / 12 dp vertical,
  `verticalArrangement = Arrangement.spacedBy(12.dp)` (matches `MatteScreen`'s 12 dp column rhythm).
- Two groups, `gpu` first (it's what the app defaults to), each preceded by a header `Text` in
  `labelLarge` / `onSurfaceVariant` — the same treatment `ConfigSheet` gives its field labels.
- A one-line caption under the top group explaining what `source` means, in `bodySmall` /
  `onSurfaceVariant`: gpu builds are rewritten for full GPU-delegate compatibility, original builds
  are the unmodified upstream graph. Without this the page shows 8 near-identical rows with no way
  to tell why you'd pick one.

### 2c. `ModelCard` states — requirement 4

One private composable, driven by `ModelDownloadState`. All colours are existing `RvmTheme` roles;
none are new.

| State | Container | Content | Trailing | Enabled |
|---|---|---|---|---|
| `NotInstalled` | `surfaceContainerHigh` | `onSurface` | `Icons.Filled.Download` | yes → `download(spec)` |
| `Queued` | `surfaceContainerHigh` | `onSurface` at 0.6α | badge chip "Queued" | no |
| `Downloading` | `surfaceContainerHigh` | `onSurface` | `CircularProgressIndicator(progress = { fraction })` | no |
| `Installed` | **`primaryContainer`** | **`onPrimaryContainer`** | `Icons.Filled.CheckCircle` + "Downloaded" badge | no |
| `Failed` | `errorContainer` | `onErrorContainer` | `Icons.Filled.Refresh` | yes → `retry(spec)` |

The `Installed` row therefore changes *both* container and font colour, which is what requirement 4
asks for, and it reuses the exact accent `HomeScreen` already gives its primary tile. The
"Downloaded"/"Queued" badges reuse `HomeActionTile`'s badge recipe verbatim
(`Surface(shape = CircleShape, color = surfaceContainerHighest, contentColor = onSurfaceVariant)`
with `labelSmall` text at 10 dp / 4 dp padding).

Card content — requirement 3's "their respective configs":

- **Title** (`titleMedium`): pretty backbone name — `MobileNetV3` / `ResNet50`.
- **Line 2** (`bodySmall`, `LocalContentColor.current.copy(alpha = 0.8f)` — same as `HomeActionTile`'s
  subtitle): `720 × 1280 · downsample auto · gpu source`.
- **Line 3** (`labelSmall`, `onSurfaceVariant`): the size, via
  `android.text.format.Formatter.formatShortFileSize(context, spec.sizeBytes)` — no hand-rolled
  formatting. While `Downloading`, this line becomes `43 MB / 104 MB · 41%`.

Give each card a `contentDescription` reflecting its state so the row is intelligible to TalkBack
rather than reading out as an unlabelled tap target.

### 2d. Home entry point

Add a third `HomeActionTile` beneath the existing two:

- icon `Icons.Filled.Download`, title `Models`, subtitle `Download matting models`,
- `containerColor = surfaceContainerHigh` / `contentColor = onSurface` — the same non-primary
  treatment as the Live Matte tile, so Video Matte stays the visually dominant action,
- `badge` = `"$installedCount/8"`, driven by the repository flow,
- `onClick` → `onOpenModels()`.

`HomeScreen`'s signature becomes
`HomeScreen(isModelReady: Boolean, onOpenVideoMatte: () -> Unit, onOpenModels: () -> Unit, modifier: Modifier = Modifier)`.

### 2e. Strings

Add to `res/values/strings.xml` (no hardcoded UI text anywhere):
`models_title`, `home_models_title`, `home_models_subtitle`, `models_group_gpu`,
`models_group_original`, `models_source_explainer`, `model_spec_summary` (`%1$d × %2$d · downsample %3$s · %4$s source`),
`model_state_downloaded`, `model_state_queued`, `model_download_progress` (`%1$s / %2$s · %3$d%%`),
`model_installed_count` (`%1$d/%2$d`), `model_download_content_description`,
`model_retry_content_description`, plus the five error strings from 1d
(`error_no_internet`, `error_timeout`, `error_no_storage`, `error_size_mismatch`, `error_download_generic`).

### ✅ Check before Phase 3

```bash
adb shell run-as dev.hamster.rvm rm -rf files/models   # start clean
# relaunch, Home -> Models
adb shell uiautomator dump && adb shell cat /sdcard/window_dump.xml | grep -o 'text="[^"]*"'
adb shell screencap -p /sdcard/models.png && adb pull /sdcard/models.png
```

Verify: all 8 rows render with correct config lines and sizes; tapping a small one shows progress
then flips to the `primaryContainer` + `CheckCircle` appearance; navigating back to Home and
returning shows it still `Installed` and the badge now reads `1/8`; starting a large download,
navigating to Home, and returning shows the download **still running** (this is the check that
proves Key decision 4 works); tapping an already-installed row does nothing. Screenshot the page in
both light and dark mode and confirm the installed/not-installed contrast holds in both.

---

## Phase 3 — Load from the store, delete the assets

Now the inference path switches over. Do **not** start this phase until Phase 2 can actually put a
file in `files/models` — the app has no other source of models once the assets are gone.

### 3a. `MatteConfig` gains `source`

- Add `var source: ModelSource = ModelSource.GPU`.
- `buildModelFileName()` → `"rvm_${source.tag}_${variant.backbone}_${height}x${width}_ds_${dsTag}.tflite"`,
  with `dsTag = if (downsampleRatio == -1.0F) "auto" else "%03d".format((downsampleRatio * 100).toInt())`.
- **Do not** move the `runtimeConfig` assignment relative to the auto-sentinel resolution in `init`.
- Change the defaults per Key decision 5: `downsampleRatio = -1.0F`.

### 3b. `TFLiteModelRunner.loadModelFile` reads a real file

```kotlin
private fun loadModelFile(modelFileName: String): MappedByteBuffer {
    val file = ModelStore(context).fileFor(modelFileName)
    if (!file.exists()) throw FileNotFoundException("Model not downloaded: $modelFileName")
    return RandomAccessFile(file, "r").use { raf ->
        raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
    }
}
```

The `MappedByteBuffer` stays valid after the channel closes, so `use` is correct here. Everything
downstream (`Interpreter(model, options)`) is unchanged — it already takes a `MappedByteBuffer`.

Note this still runs on the `rvm-matte` confined thread via `MatteModule.configureImpl`, which is
fine: it's a memory-map, not a network call. **Nothing in `matte/` or `modelRunner/` may ever
trigger a download** — that would block the confined thread on I/O of unbounded duration.

### 3c. `ModelCatalog` becomes manifest+disk driven

Move to `dev.hamster.rvm.models`, drop the filename regex and the `assets` read entirely, and
back every query with `ModelStore.installedSpecs()`:

```kotlin
class ModelCatalog(context: Context) {
    fun availableSources(): List<ModelSource>
    fun availableResolutions(source: ModelSource): List<Pair<Int, Int>>
    fun availableBackbones(source: ModelSource, height: Int, width: Int): List<String>
    fun availableDownsampleTags(source: ModelSource, backbone: String, height: Int, width: Int): List<String>
}
```

Drop the `by lazy` on `entries` — it must now re-read after a download, and the sheet is
constructed fresh each time it opens. Update the class KDoc: it no longer describes `assets/`.

### 3d. `ConfigSheet` gains a Source dropdown

`ConfigDraft` gains `source: ModelSource`, and a fourth `LabeledDropdown` goes **above** Resolution
(source is the outermost narrowing key). The existing `LaunchedEffect(backbones)` /
`LaunchedEffect(downsampleTags)` narrowing pattern extends to resolutions, which can now go empty
when the source changes. Update the class KDoc's "present in `assets/`" wording to "downloaded".

This is a consequence of Key decision 1, not an added feature — with two sources installed, the
sheet is otherwise unable to address half the models.

### 3e. Delete the assets

```bash
rm -rf app/src/main/assets
```

Nothing is tracked there (see the starting-state table), so this is a local-disk change only.
Grep to confirm the code path is gone:

```bash
grep -rn "assets" app/src/main/java/     # expect: no hits
```

### ✅ Check before Phase 4

With `files/models` holding exactly the two gpu/auto models downloaded in Phase 2's check:

```bash
adb logcat -c
# Home -> Video Matte -> Configure
adb logcat -s MatteModule:D TFLiteModelRunner:D
```

Verify: the config sheet's Source dropdown offers only `gpu`; switching backbone between
mobilenetv3 and resnet50 works and Apply reloads the interpreter; the model-file line at the bottom
of the sheet reads `rvm_gpu_<backbone>_720x1280_ds_auto.tflite`; a full matting run still produces
all three outputs. Then download an `original` model from the Models page, reopen the sheet, and
confirm Source now offers both and selecting `original` narrows correctly.

---

## Phase 4 — First-launch bootstrap and the no-model states

The app currently assumes a model always exists. This phase makes a fresh install self-provision
(requirement 2) and makes every "no model yet" path explicit rather than a crash.

### 4a. Bootstrap trigger

Call `ModelRepository.get(this).ensureBootstrapModels()` once from `MainActivity.onCreate`, before
`setContent`. It's idempotent and returns immediately (the work is on the repository scope), so
this is not a launch-latency concern.

Serial ordering per Key decision 4 means mobilenetv3 (15 MB) lands first and resnet50 (104 MB)
second — **put mobilenetv3 first in `ModelManifest.BOOTSTRAP`** so the app becomes usable at all as
early as possible, even though the *default config* names resnet50.

### 4b. `MatteViewModel` stops assuming a model exists

- `MatteUiState` gains `val modelMissing: Boolean = false`.
- `init` no longer calls `runConfigure` unconditionally. Instead: if
  `ModelStore.isInstalled(config.runtimeConfig.modelFileName)`, configure as today; otherwise set
  `modelMissing = true` and **collect `ModelRepository.states` in `viewModelScope`**, configuring
  automatically the moment that model becomes `Installed`.
- `restoreConfig()`: if the persisted config names a model that isn't installed, fall back to the
  first installed spec (rebuilding a `MatteConfig` from it) rather than returning a config that
  can't load; if nothing is installed, keep the default and let `modelMissing` carry the state.
- **Do not** change `runConfigure`'s synchronous `isConfiguring` raise — see the starting-state
  table.

### 4c. Gating in the UI

- `MainActivity` passes `isModelReady = !uiState.isConfiguring && !uiState.modelMissing`.
- `HomeScreen`'s Video Matte tile: when `modelMissing` and a bootstrap download is in flight, its
  subtitle becomes the live progress (`Downloading model · 41%`) and it keeps the existing
  `loading` spinner treatment. When `modelMissing` with **no** download running (bootstrap failed,
  e.g. offline first launch), tapping it routes to the Models page instead of the matte screen.
- `MatteScreen`: if `modelMissing` is somehow reached, show the existing `StatusStrip` with a "No
  model installed" message and disable the Matte action. `ConfigSheet` with an empty catalog must
  render an explanatory line rather than four empty dropdowns.

### ✅ Check before Phase 5

The real test is a genuinely fresh install:

```bash
adb uninstall dev.hamster.rvm
./gradlew installDebug
adb logcat -s ModelRepository:D ModelDownloader:D MatteModule:D
```

Verify: on first launch the home screen shows the Models badge counting up `0/8 → 1/8 → 2/8`, the
Video Matte tile shows download progress and becomes tappable once mobilenetv3 lands, and a matting
run works end to end without ever visiting the Models page. Then repeat the fresh install **in
airplane mode**: the app must not crash, the Video Matte tile must route to the Models page, and
the Models page must show `Failed` rows that retry successfully once the network is back.

---

## Phase 5 — Documentation

- **`README.md`**: the Requirements, Getting Started and "Downloading models" sections all
  currently tell the reader to place `.tflite` files in `app/src/main/assets/`. Replace with the
  on-demand flow. Update Project Structure for the new `models/` package and `ui/ModelsScreen.kt`,
  Architecture for `ModelRepository`/`ModelStore`/`ModelDownloader`, Usage for the Models page, and
  the Logging table with the `ModelRepository` / `ModelDownloader` tags. **Do not change the title
  line.**
- **`converter/rvmConverter/README.md`**: add a line under *Pre-converted models* noting the app
  consumes `models-v1` directly, so re-uploading an asset changes its byte size and requires the
  app's `ModelManifest` table to be updated in the same change.
- This file: mark the phases done, as `docs/threading-plan.md` does.

---

## Risks and things deliberately not done

1. **~123 MB downloads unattended on first launch, possibly over metered data.** The brief asks for
   this explicitly, so it's implemented as specified — but it is the single most user-hostile part
   of the design. The cheapest mitigation, if it becomes a problem: bootstrap only mobilenetv3
   (15 MB) automatically and leave resnet50 to a tap on the Models page.
2. **Downloads die with the process and cannot resume.** A 104 MB transfer that gets 90% of the way
   and then loses to a low-memory kill starts over. Fixing this properly means `WorkManager` +
   HTTP `Range` requests against the `.part` file's current length. Out of scope; a natural
   follow-up phase.
3. **`Content-Length` is the only integrity check.** It catches truncation, not corruption. Publish
   `SHA256SUMS` on the release and add a `sha256` to `ModelSpec` to close this.
4. **The manifest hardcodes byte sizes.** Re-uploading a `models-v1` asset will make every install
   reject it as a size mismatch. This is a deliberate trade (it's what makes the integrity check
   possible at all), but it means the manifest table and the release are coupled — see the Phase 5
   converter-README note.
5. **No delete affordance.** Requirement 3 says models stay "forever", so none is offered; a user
   with all 8 installed holds ~495 MB. Android's Settings → Storage → Clear data remains the escape
   hatch, and `ModelStore.isInstalled` is length-checked, so a partially cleared store degrades to
   "not installed" rather than to a corrupt load.
6. **`ModelCatalog`'s old filename regex is deleted, not extended.** Any `.tflite` a developer
   side-loads into `files/models` that isn't in the manifest is invisible to the app. That is
   intentional — the manifest is now the single source of truth for what a valid model is — but it
   removes the "drop a file in and it appears" workflow that the assets folder allowed.

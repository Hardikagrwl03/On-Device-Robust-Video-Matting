---
name: rvm-app-models
description: How the RVM app downloads, stores and selects .tflite models at runtime, and how to add or update one. Use when changing ModelManifest/ModelStore/ModelDownloader/ModelRepository, publishing new models to the models-v1 release, or debugging "Model not downloaded", a size-mismatch failure, or an empty config sheet.
---

# RVM app: the on-demand model system

No model is bundled. `app/src/main/assets/` does not exist and must not be
recreated. Models are fetched from the
[`models-v1` GitHub release](https://github.com/Hardikagrwl03/On-Device-Robust-Video-Matting/releases/tag/models-v1)
into `filesDir/models/`. Design and verified behaviour: `docs/model-download-plan.md`.

## The four pieces (`dev.hamster.rvm.models`)

| File | Role |
|---|---|
| `ModelManifest.kt` | `ModelSource`, `ModelSpec`, and the static table of all 8 assets. **Single source of truth** -- a `.tflite` in the store the manifest does not list is invisible by design. |
| `ModelStore.kt` | `filesDir/models/`. `isInstalled` checks **length against the manifest**, not just existence, so a truncated file reads as absent. |
| `ModelDownloader.kt` | `HttpURLConnection` -> `<name>.part` -> size check -> rename. Deletes the `.part` on any failure *or cancellation*. |
| `ModelRepository.kt` | Process-wide singleton holding `StateFlow<Map<String, ModelDownloadState>>` and a serial download queue. |

`ModelCatalog.kt` narrows *installed* models into the config sheet's
source -> resolution -> backbone -> downsample chain.

## Why `ModelRepository` is a singleton, not a ViewModel

A 104 MB download must survive navigating off the Models page; a
`viewModelScope` would cancel it. Screens collect `states` directly with
`collectAsState()`. Downloads run **one at a time** behind a `Mutex` -- two
concurrent 104 MB streams only halve each other's bandwidth. Waiting entries
report `Queued` so the UI never renders a queued download as a stalled 0%.

Known limitation: a download dies with the process and **cannot resume**.
Resumability would mean `WorkManager` + HTTP `Range`.

## Filenames are the identity

```
rvm_<source>_<backbone>_<height>x<width>_ds_<downsampleTag>.tflite
```

The release asset name, the on-disk name, `MatteConfig`'s resolved name and
`RuntimeConfig.modelFileName` are all **the same string**. There is no mapping
layer, and adding one would be a regression.

## `source` is NOT the compute device

- `ModelSource` (`gpu`/`original`) = **which file**, a build-time property:
  which copy of the RVM PyTorch source was traced.
- `RuntimeConfig.ComputeDevice` (`CPU`/`GPU`/`NPU`/`AUTO`) = **which delegate**.

They are independent, and both appear in the config sheet. But an `original`
model still contains `GATHER_ND`, `RELU_0_TO_1` and `STABLEHLO_REDUCE_WINDOW`,
which the GPU/NNAPI delegate cannot run -- and a delegate is *mandatory* once
attached, so it refuses the whole graph and `Interpreter`'s constructor throws.
This crashed the app before it was handled.

Two layers now guard it, keep both:

1. `MatteViewModel.coerceUnsupportedDevice` blocks `original` + GPU/NPU up
   front, forces CPU, and toasts. `AUTO` is exempt -- falling back is its job.
2. `TFLiteModelRunner.loadModel` catches a delegate rejection and rebuilds on
   CPU, as a backstop for pairings the check does not anticipate.

Since the `gpu` rewrites are numerically exact, a `gpu` model is never worse
than an `original` one at any device.

## Adding or updating a model

1. Upload the asset to the release, following the filename convention.
2. Add a `ModelSpec` to `ModelManifest.ALL` with its **exact byte size**
   (`curl -sI <url>` or the GitHub API). Order in `ALL` is the Models page's
   render order.
3. If it should be fetched on first launch, add it to `BOOTSTRAP` -- smallest
   first, since downloads are serial.
4. If the backbone is new, `MatteConfig.Variant` needs an entry with matching
   `backbone` string and channel widths.

> **The manifest's byte sizes are coupled to the release.** The release
> publishes no checksums, so length is the only integrity check. **Re-uploading
> an asset changes its size and makes every installed app reject the new file as
> a size mismatch** -- a re-upload must be paired with a manifest update in the
> same change. Adding a *new* asset is always safe.

Publishing `SHA256SUMS` on the release and adding a `sha256` to `ModelSpec`
would close the corruption gap that length-checking cannot.

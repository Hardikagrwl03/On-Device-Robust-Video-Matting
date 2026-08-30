# Application for On Device Video Depth Anything

An Android application that runs [Robust Video Matting (RVM)](https://github.com/PeterL1n/RobustVideoMatting) fully on-device via TensorFlow Lite / LiteRT to produce an alpha matte, a foreground extraction, and their composite for a user-selected video, with GPU/NNAPI acceleration where available.

## Overview

The user picks a video from their device, the app decodes it frame-by-frame, runs each frame through an on-device RVM TFLite model (carrying the model's recurrent hidden states between frames for temporal consistency), and re-encodes the result into three output videos in a single decode/inference pass:

- the **alpha matte** (grayscale opacity mask),
- the **foreground** (the subject as RVM extracts it), and
- their **composite** — `foreground × alpha` per pixel, i.e. the subject matted onto black with soft, opacity-weighted edges rather than a hard cutout.

All three play back in-app, kept in sync with the input video, and can be saved to the device gallery in one tap.

## Features

- On-device video matting using RVM, no network calls or server-side inference.
- Selectable compute backend per run: GPU delegate, NNAPI (NPU), CPU, or automatic fallback (NNAPI → GPU → CPU).
- Two RVM backbones (ResNet50, MobileNetV3) and configurable resolution/downsample ratio, each resolving to a matching `.tflite` asset actually present in `assets/` (the config UI can't offer a combination that doesn't exist).
- Frame-accurate hidden-state passing between inference calls, matching RVM's recurrent architecture.
- One decode/inference pass produces all three outputs — alpha matte, foreground, and their premultiplied composite — switchable in-app and played back in sync with the input; all three (plus the input) can be saved to `Movies/RVM` in the gallery.
- Native Jetpack Compose UI: a home screen, a non-scrolling matting screen with synchronized Media3 input/output preview playback, and Save-to-gallery — see `docs/ui-redesign-plan.md` for the full design.
- Every stateful native resource — the TFLite interpreter/GPU delegate, each `MediaCodec`/`MediaMuxer` encoder, the `MediaMetadataRetriever` decoder — is confined to its own dedicated thread, since none of them are safe to drive from more than one thread (the GPU delegate in particular binds an EGL context to whichever thread builds it); see `docs/threading-plan.md`.

The app uses a fixed brand colour scheme (not Material You dynamic colour) so its look is consistent across devices; see `docs/ui-redesign-plan.md`, "Key decision 3", for why and for the full palette.

## Requirements

- Android Studio (Narwhal or newer recommended) with the Android SDK.
- JDK 11.
- An Android device or emulator running **API 35 (Android 15) or newer** (`minSdk = 35`, `targetSdk = 36`, `compileSdk = 37`).
- Gradle 9.5 (fetched automatically via the Gradle wrapper), AGP 9.3.1, Kotlin 2.2.10.

## Getting Started

1. **Clone the repository** and open the `application/rvmApplication` folder in Android Studio.
2. **Download the TFLite models** (see below) and place them in `app/src/main/assets/`.
3. **Sync and build** — Android Studio will resolve dependencies (TensorFlow Lite, LiteRT, Jetpack Compose, Media3, AndroidX) automatically via Gradle.
4. **Run** on a device/emulator meeting the API level requirement above.

### Downloading the TFLite models

The `.tflite` model files are **not committed to this repository** (they're large binary assets) and must be downloaded separately:

**Models:** https://drive.google.com/drive/folders/1VXIsAFNzCVJ-ylWkxmL_tJxKAAFb992K?usp=drive_link

Download the models you need and copy them into `app/src/main/assets/`. Model filenames follow the convention produced by `MatteConfig`, and parsed back by `ModelCatalog` to populate the in-app config UI:

```
rvm_<backbone>_<height>x<width>_ds_<downsampleTag>[_<dtype>].tflite
```

- `<backbone>`: `resnet50` or `mobilenetv3`
- `<height>x<width>`: input resolution, e.g. `720x1280`
- `<downsampleTag>`: a fixed downsample ratio as an integer percentage (e.g. `100` for a ratio of 1.0), or `auto` when the ratio is resolved automatically from resolution
- `<dtype>`: omitted for FP32 (the default); `int8` or `fp16` for those variants

For example, `MatteConfig()` (the default config) resolves to `rvm_resnet50_720x1280_ds_100.tflite`. Make sure the model file named by the config you use is present in `assets/`, or `TFLiteModelRunner` will fail to load it.

## Project Structure

```
app/src/main/java/dev/hamster/rvm/
├── MainActivity.kt                    # Entry point: hosts the home/matte screen switch, wires up MatteViewModel
├── Controller.kt                      # Orchestrates VideoFrameDecoder + 3x VideoFrameEncoder + MatteModule end-to-end
├── ui/                                # Jetpack Compose UI
│   ├── HomeScreen.kt                  #   Landing screen: logo, tagline, Video Matte / Live Matte (coming soon) tiles
│   ├── MatteScreen.kt                 #   The matting screen: configure pill, input/output previews, action bar
│   ├── MatteViewModel.kt              #   MatteUiState + all screen logic; owns the Controller
│   ├── ConfigSheet.kt                 #   Bottom sheet for editing MatteConfig (device/resolution/backbone/downsample/threads)
│   ├── theme/                         #   Fixed brand colour scheme, type scale, shape scale
│   │   ├── Theme.kt                   #     RvmTheme - builds the light/dark ColorScheme from Color.kt
│   │   ├── Color.kt                   #     The brand palette's tonal ramps
│   │   ├── Type.kt                    #     Typography overrides (wordmark display style, etc.)
│   │   └── Shape.kt                   #     Corner-radius scale for cards/sheets/surfaces
│   └── player/                        #   Media3 ExoPlayer wrapper for synchronized input/output playback
│       ├── DualVideoSync.kt           #     Leader/follower playback sync (drift correction via speed nudging)
│       └── VideoSurface.kt            #     TextureView-backed Compose video surface
├── interfaces/                        # Generic contracts every inference module implements
│   ├── ModuleInterface.kt             #   configure/run/reset/close, generic over a module's Config and IO types
│   ├── ConfigInterface.kt             #   minimal shape every module config resolves to (height, width, RuntimeConfig)
│   └── HiddenStatesInterface.kt       #   contract for a module's recurrent/hidden-state buffers
├── matte/                             # RVM matting module (implements the interfaces above)
│   ├── MatteModule.kt                 #   runs the RVM model; confined to its own thread (see utils/ConfinedRunner.kt)
│   ├── MatteIO.kt                     #   MatteModule's named input/output buffers
│   ├── MatteConfig.kt                 #   resolution/backbone/dtype/downsample settings; derives the model filename
│   └── MatteHiddenStates.kt           #   the 4 ConvGRU recurrent state buffers RVM passes between frames
├── modelRunner/
│   ├── TFLiteModelRunner.kt           #   module-agnostic TFLite Interpreter/delegate wrapper; own dedicated thread
│   ├── ModelRunnerInterface.kt        #   the contract TFLiteModelRunner implements
│   └── RuntimeConfig.kt               #   model filename + compute device + thread count
├── video/                             # Video decode/encode, one class per direction, each on its own thread
│   ├── VideoFrameDecoder.kt           #   MediaMetadataRetriever-based frame extraction
│   ├── VideoFrameDecoderInterface.kt
│   ├── VideoFrameEncoder.kt           #   MediaCodec/MediaMuxer-based frame re-encoding
│   └── VideoFrameEncoderInterface.kt
└── utils/
    ├── ConfinedRunner.kt              #   confines an object's work to one dedicated thread
    ├── ModelCatalog.kt                #   parses assets/*.tflite filenames into valid config combinations
    ├── MediaStoreSaver.kt             #   copies a cache-directory output video into the shared gallery
    └── SharedBuffer.kt                #   native shared-memory-backed ByteBuffer helper
```

## Architecture

Inference modules follow a small, generic pattern so new models (segmentation, style transfer, etc.) can be added without changing the surrounding app code:

- **`ModuleInterface<Config : ConfigInterface, IO>`** — every module implements `configure(config)`, `run(io, count)`, `reset()`, and `close()`. `Config` and `IO` are generic per module: each module defines its own config data class (e.g. `MatteConfig`) and its own named input/output data class (e.g. `MatteIO`), so callers get readable, typed fields instead of positional buffers, while the app can still drive any module polymorphically.
- **`ConfigInterface`** — the minimal shape a config must expose (`height`, `width`, a `RuntimeConfig`) so the module can resolve and load the right `.tflite` model.
- **`HiddenStatesInterface`** — the contract for a module's recurrent state buffers (reset/rewind/put/close), used by RVM's hidden-state passing but reusable by any future recurrent model.
- **`ModelRunnerInterface` / `TFLiteModelRunner`** — the shared, module-agnostic wrapper around a TFLite `Interpreter`: (re)builds the interpreter and delegate (GPU/NNAPI/CPU/AUTO) only when the resolved `RuntimeConfig` actually changes, and exposes `run`/`close` for single- and multi-tensor inference. Every call is confined to one dedicated thread via `ConfinedRunner`, so the interpreter is always built and invoked on the same thread — required for the GPU delegate, whose EGL context is bound to its creating thread.
- **`MatteModule`** — the concrete matting implementation: on `run`, combines the caller's image/foreground/alpha buffers (`MatteIO`) with its own internally-managed hidden-state buffers, invokes `TFLiteModelRunner`, and copies the freshly produced hidden states back in for the next call. Also confined to its own dedicated thread.
- **`VideoFrameDecoder` / `VideoFrameEncoder`** — a `MediaMetadataRetriever`-based frame extractor and a `MediaCodec`/`MediaMuxer`-based frame encoder, each confined to its own thread (`MediaCodec`/`MediaMuxer`/`MediaMetadataRetriever` aren't safe to drive from more than one thread). `Controller` owns one decoder and three encoders (matte, foreground, composite), so the three encoders can each write a separate output video from the same decode/inference pass.
- **`Controller`** — the app-level orchestrator: loads the matting model, decodes the input video frame-by-frame, runs each frame through `MatteModule`, computes the `foreground × alpha` composite, and writes all three outputs via their respective encoders — one decode/inference pass, three videos out.
- **`MatteViewModel`** — owns the `Controller` and exposes a single `MatteUiState` `StateFlow` for the matting screen. Runs `Controller.configure()` (which can rebuild the GPU delegate — anywhere from tens of milliseconds to a few seconds) off the main thread, so the UI can show a spinner instead of appearing to hang; the first navigation into the matting screen triggers this same path, since `MatteViewModel` is created lazily on first use.
- **`ui/theme`** — a fixed brand colour scheme (not Material You dynamic colour), so the app looks the same regardless of the device's wallpaper; see `docs/ui-redesign-plan.md`.
- **`ui/player`** — `DualVideoSync` drives a leader (input) and follower (output) Media3 `ExoPlayer` pair, keeping them aligned via small playback-speed nudges rather than continuous re-seeking; `VideoSurface` renders each into a plain `TextureView` so Compose can clip, round, and cross-fade it (a `SurfaceView`-backed `VideoView`, used earlier, can't be composited under other Compose content).

## Usage

1. Launch the app; on the home screen, tap **Video Matte** (or **Live Matte** for a "coming soon" toast — real-time camera matting isn't implemented yet).
2. Tap **Import** to pick a video from the device.
3. Tap **Matte** to run matting over every frame; a status strip shows progress.
4. Once done, switch between **Matte**, **Foreground**, and **Both** (the premultiplied composite) — all played back in sync with the input — and tap **Save** to copy all three to `Movies/RVM` in the gallery.
5. Tap **Reset** to clear the current selection and start over.

## Logging

Every component logs through `android.util.Log` under its own tag, so `adb logcat -s <TAG>` (or the equivalent filter in Android Studio's Logcat panel) can isolate just the component you're interested in:

| Tag | Source | Covers |
| --- | --- | --- |
| `Controller` | `Controller.kt` | End-to-end orchestration: per-frame timing for the matte/foreground/composite pass |
| `MatteModule` | `matte/MatteModule.kt` | Matting configuration, per-run RVM inference timing, hidden-state reset/close |
| `TFLiteModelRunner` | `modelRunner/TFLiteModelRunner.kt` | Interpreter/delegate setup (GPU/NNAPI/CPU/AUTO), per-call inference timing, interpreter close |
| `VideoFrameDecoder` | `video/VideoFrameDecoder.kt` | Per-frame decode timing |
| `VideoFrameEncoder` | `video/VideoFrameEncoder.kt` | Per-frame encode timing (one line per encoder - matte, foreground, composite - per frame) |
| `testDummyInputs` | `modelRunner/TFLiteModelRunner.kt` | `testDummyInputs()` diagnostics: input/output tensor shapes, dtypes, sizes |
| `logSignature` | `modelRunner/TFLiteModelRunner.kt` | `logSignature()` diagnostics: input/output tensor names, shapes, dtypes |

To follow just the matting pipeline end-to-end, filter on multiple tags at once:

```
adb logcat -s Controller:D MatteModule:D TFLiteModelRunner:D VideoFrameDecoder:D VideoFrameEncoder:D
```

Note: `TFLiteModelRunner.configure()`'s own summary line ("configure: TFLite Model Runner configured with: ...") logs under the `MatteModule` tag rather than `TFLiteModelRunner` (a leftover from how the code evolved) — if you're filtering strictly on `TFLiteModelRunner`, that specific line won't show up there; its device-selection and interpreter-lifecycle lines (`loadModel: Using GPU`, `Interpreter Created`, `close: ...`) do use the `TFLiteModelRunner` tag correctly.

To watch which thread each component is running on (all of `MatteModule`, `TFLiteModelRunner`, `VideoFrameDecoder`, and each `VideoFrameEncoder` instance are confined to one dedicated, named thread - see `docs/threading-plan.md`), look at the TID column in `adb logcat -v threadtime`, or:

```
adb shell ps -T -p $(adb shell pidof dev.hamster.rvm) | grep rvm-
```

## Adding a New Module

To add a new on-device model (e.g. segmentation):

1. Create a package under `dev/hamster/rvm/<yourmodule>/`.
2. Define `<YourModule>Config : ConfigInterface` with whatever settings your model needs, resolving to a `RuntimeConfig` (model filename, compute device, thread count).
3. Define `<YourModule>IO` — a data class with named `ByteBuffer` fields for your model's inputs/outputs.
4. Implement `<YourModule> : ModuleInterface<YourModuleConfig, YourModuleIO>`, using `TFLiteModelRunner` internally the same way `MatteModule` does. If your model is recurrent, implement `HiddenStatesInterface` for its state buffers. Confine the module's work to its own thread with `ConfinedRunner`, the same way `MatteModule` does, if it wraps any native/stateful resource.
5. Wire it into `Controller` (or a new controller) alongside `MatteModule`.

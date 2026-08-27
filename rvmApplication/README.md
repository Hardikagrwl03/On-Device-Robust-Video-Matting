# Application for On Device Video Depth Anything

An Android application that runs [Robust Video Matting (RVM)](https://github.com/PeterL1n/RobustVideoMatting) fully on-device via TensorFlow Lite / LiteRT to produce an alpha matte and foreground extraction for a user-selected video, with GPU/NNAPI acceleration where available.

## Overview

The user picks a video from their device, the app decodes it frame-by-frame, runs each frame through an on-device RVM TFLite model (carrying the model's recurrent hidden states between frames for temporal consistency), and re-encodes the result — the alpha matte and/or the foreground — into an output video that plays back in-app.

## Features

- On-device video matting using RVM, no network calls or server-side inference.
- Selectable compute backend per run: GPU delegate, NNAPI (NPU), CPU, or automatic fallback (NNAPI → GPU → CPU).
- Two RVM backbones (ResNet50, MobileNetV3) and configurable resolution/downsample ratio, each resolving to a matching `.tflite` asset.
- Frame-accurate hidden-state passing between inference calls, matching RVM's recurrent architecture.
- Native Jetpack Compose UI: a home screen, a non-scrolling matting screen with synchronized Media3 input/output preview playback, and Save-to-gallery — see `docs/ui-redesign-plan.md` for the full design.

The app uses a fixed brand colour scheme (not Material You dynamic colour) so its look is consistent across devices; see `docs/ui-redesign-plan.md`, "Key decision 3", for why and for the full palette.

## Requirements

- Android Studio (Narwhal or newer recommended) with the Android SDK.
- JDK 11.
- An Android device or emulator running **API 35 (Android 15) or newer** (`minSdk = 35`, `targetSdk = 36`, `compileSdk = 37`).
- Gradle 9.5 (fetched automatically via the Gradle wrapper), AGP 9.3.1, Kotlin 2.2.10.

## Getting Started

1. **Clone the repository** and open the `application/rvmApplication` folder in Android Studio.
2. **Download the TFLite models** (see below) and place them in `app/src/main/assets/`.
3. **Sync and build** — Android Studio will resolve dependencies (TensorFlow Lite, LiteRT, Jetpack Compose, AndroidX) automatically via Gradle.
4. **Run** on a device/emulator meeting the API level requirement above.

### Downloading the TFLite models

The `.tflite` model files are **not committed to this repository** (they're large binary assets) and must be downloaded separately:

**Models:** https://drive.google.com/drive/folders/1VXIsAFNzCVJ-ylWkxmL_tJxKAAFb992K?usp=drive_link

Download the models you need and copy them into `app/src/main/assets/`. Model filenames follow the convention produced by `MatteModuleConfig`:

```
rvm_<backbone>_<height>x<width>_ds_<downsampleTag>[_<dtype>].tflite
```

- `<backbone>`: `resnet50` or `mobilenetv3`
- `<height>x<width>`: input resolution, e.g. `720x1280`
- `<downsampleTag>`: a fixed downsample ratio as an integer percentage (e.g. `100` for a ratio of 1.0), or `auto` when the ratio is resolved automatically from resolution
- `<dtype>`: omitted for FP32 (the default); `int8` or `fp16` for those variants

For example, `MatteModuleConfig()` (the default config) resolves to `rvm_resnet50_720x1280_ds_100.tflite`. Make sure the model file named by the config you use is present in `assets/`, or `TFLiteModelRunner` will fail to load it.

## Project Structure

```
app/src/main/java/dev/hamster/rvm/
├── MainActivity.kt              # Entry point: hosts the home/matte screen switch, wires up MatteViewModel
├── ui/                          # Compose screens (HomeScreen, MatteScreen, ConfigSheet, MatteViewModel)
│   ├── theme/                   #   Fixed brand colour scheme, type scale, shape scale (RvmTheme)
│   └── player/                  #   Media3 ExoPlayer wrapper (DualVideoSync, VideoSurface)
├── Controller.kt                # Orchestrates VideoFrameDecoder/Encoder + MatteModule to process a video end-to-end
├── interfaces/                  # Generic contracts every inference module implements
│   ├── ModuleInterface.kt       #   configure/run/reset/close, generic over a module's Config and IO types
│   ├── ConfigInterface.kt #   minimal shape every module config resolves to (height, width, RuntimeConfig)
│   └── HiddenStatesInterface.kt #   contract for a module's recurrent/hidden-state buffers
├── matte/                       # RVM matting module (implements the interfaces above)
│   ├── MatteModule.kt           #   runs the RVM model; also defines MatteIO (its named input/output buffers)
│   ├── MatteConfig.kt     #   resolution/backbone/dtype/downsample settings; derives the model filename
│   └── MatteHiddenStates.kt     #   the 4 ConvGRU recurrent state buffers RVM passes between frames
├── video/                       # Video decode/encode module
│   ├── VideoHandlerModule.kt    #   MediaCodec-based frame extraction and video re-encoding
│   └── VideoHandlerModuleInterface.kt
└── utils/
    ├── TfliteModelRunner.kt     #   TFLiteModelRunner (interpreter/delegate lifecycle) + RuntimeConfig
    └── SharedBuffer.kt          #   native shared-memory-backed ByteBuffer helper
```

## Architecture

Inference modules follow a small, generic pattern so new models (segmentation, style transfer, etc.) can be added without changing the surrounding app code:

- **`ModuleInterface<Config : ModuleConfigInterface, IO>`** — every module implements `configure(config)`, `run(io, count)`, `reset()`, and `close()`. `Config` and `IO` are generic per module: each module defines its own config data class (e.g. `MatteModuleConfig`) and its own named input/output data class (e.g. `MatteIO`), so callers get readable, typed fields instead of positional buffers, while the app can still drive any module polymorphically.
- **`ModuleConfigInterface`** — the minimal shape a config must expose (`height`, `width`, a `RuntimeConfig`) so the module can resolve and load the right `.tflite` model.
- **`HiddenStatesInterface`** — the contract for a module's recurrent state buffers (reset/rewind/put/close), used by RVM's hidden-state passing but reusable by any future recurrent model.
- **`TFLiteModelRunner`** — the shared, module-agnostic wrapper around a TFLite `Interpreter`: (re)builds the interpreter and delegate (GPU/NNAPI/CPU/AUTO) only when the resolved `RuntimeConfig` actually changes, and exposes `run`/`close` for single- and multi-tensor inference.
- **`MatteModule`** — the concrete matting implementation: on `run`, combines the caller's image/foreground/alpha buffers (`MatteIO`) with its own internally-managed hidden-state buffers, invokes `TFLiteModelRunner`, and copies the freshly produced hidden states back in for the next call.
- **`Controller`** — the app-level orchestrator: loads the matting model, decodes the input video via `VideoHandlerModule`, runs each frame through `MatteModule`, and re-encodes the result.

## Usage

1. Launch the app, tap **Video Matte** on the home screen, then tap **Import** to pick a video from the device.
2. Tap **Matte** to run matting over every frame; a status strip shows progress.
3. Once done, switch between the **Matte** and **Foreground** outputs, played back in sync with the input; tap **Save** to copy both to `Movies/RVM` in the gallery.
4. Tap **Reset** to clear the current selection and start over.

## Logging

Every component logs through `android.util.Log` under its own tag, so `adb logcat -s <TAG>` (or the equivalent filter in Android Studio's Logcat panel) can isolate just the component you're interested in:

| Tag | Source | Covers |
| --- | --- | --- |
| `VideoRelighting` | `MainActivity.kt` | UI events: video selection, playback, button taps |
| `RelightController` | `Controller.kt` | End-to-end orchestration: model loading, per-frame timing for matte/foreground video generation |
| `MatteModule` | `matte/MatteModule.kt` | Matting configuration, per-run RVM inference timing, hidden-state reset/close |
| `TFLiteModelRunner` | `utils/TfliteModelRunner.kt` | Interpreter/delegate setup (GPU/NNAPI/CPU/AUTO), per-call inference timing, interpreter close |
| `TFLiteSignature` | `utils/TfliteModelRunner.kt` | `testDummyInputs()` diagnostics: input/output tensor shapes, dtypes, sizes |
| `logSignature` | `utils/TfliteModelRunner.kt` | `logSignature()` diagnostics: input/output tensor names, shapes, dtypes |
| `VideoHandlerModule` | `video/VideoHandlerModule.kt` | MediaCodec decode/encode timing and frame conversion |
| `GID_Debug` | `MainActivity.kt` | A single ad-hoc debug line in `relightVideo()` |

To follow just the matting pipeline end-to-end, filter on multiple tags at once:

```
adb logcat -s RelightController:D MatteModule:D TFLiteModelRunner:D
```

Note: a few `configure()` log lines inside `TFLiteModelRunner` log under the `MatteModule` tag rather than `TFLiteModelRunner` (a leftover from how the code evolved) — if you're filtering strictly on `TFLiteModelRunner`, those specific lines won't show up there.

## Adding a New Module

To add a new on-device model (e.g. segmentation):

1. Create a package under `dev/hamster/rvm/<yourmodule>/`.
2. Define `<YourModule>Config : ModuleConfigInterface` with whatever settings your model needs, resolving to a `RuntimeConfig` (model filename, compute device, thread count).
3. Define `<YourModule>IO` — a data class with named `ByteBuffer` fields for your model's inputs/outputs.
4. Implement `<YourModule> : ModuleInterface<YourModuleConfig, YourModuleIO>`, using `TFLiteModelRunner` internally the same way `MatteModule` does. If your model is recurrent, implement `HiddenStatesInterface` for its state buffers.
5. Wire it into `Controller` (or a new controller) alongside `MatteModule`.

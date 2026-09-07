---
name: rvm-app-setup
description: Get the RVM Android app building and running after a fresh clone -- SDK/JDK requirements, supported ABIs, and how models arrive at runtime. Use when someone has just cloned rvmApplication, is setting up a device or emulator, or hits errors like "Model not downloaded", an INSTALL_FAILED_NO_MATCHING_ABIS, or an empty Models page.
---

# RVM app: first-time setup

An Android app that runs Robust Video Matting on-device. A fresh clone builds
and runs with **no manual asset step** -- unlike most TFLite apps, there is
nothing to copy into `app/src/main/assets/`. That directory does not exist and
must not be recreated; models are downloaded at runtime instead (see
`rvm-app-models`).

## Requirements

- Android Studio (Narwhal or newer) with the Android SDK, and **JDK 11**.
- A device or emulator on **API 35+** (`minSdk = 35`, `targetSdk = 36`,
  `compileSdk = 37`).
- The device must be **`arm64-v8a`**, or the emulator **`x86_64`**. Those are
  the only ABIs packaged -- see "ABI" below.
- Gradle 9.5 (via the wrapper), AGP 9.3.1, Kotlin 2.2.10.

```bash
./gradlew installDebug     # build + install
adb shell am start -n dev.hamster.rvm/.MainActivity
```

## What happens on first launch

`MainActivity.onCreate` calls `ModelRepository.ensureBootstrapModels()`, which
downloads two models from the `models-v1` GitHub release into
`filesDir/models/`:

1. `rvm_gpu_mobilenetv3_720x1280_ds_auto.tflite` (~15 MB) -- **first, on
   purpose**: downloads run one at a time, and the app becomes usable as soon
   as this lands.
2. `rvm_gpu_resnet50_720x1280_ds_auto.tflite` (~104 MB).

So the first run needs **network** and pulls ~120 MB. Until something is
installed the home screen's Video Matte tile shows download progress; if the
download failed it reads "Tap to download a model" and routes to the Models
page. Nothing crashes offline.

To watch it:

```bash
adb logcat -s ModelRepository:D ModelDownloader:D
adb shell run-as dev.hamster.rvm ls -l files/models
```

## ABI

`app/build.gradle.kts` sets `ndk { abiFilters += listOf("arm64-v8a", "x86_64") }`.
TFLite's native libraries are ~73 MB **per ABI**, and at `minSdk 35` nothing
can reach `armeabi-v7a` or `x86` (no Android 15 device ships 32-bit-only ARM,
and x86 Android phones do not exist). Dropping those two took the APK from
597 MB to 227 MB.

Consequence: a 32-bit-only emulator image fails to install with
`INSTALL_FAILED_NO_MATCHING_ABIS`. Create an `x86_64` (or arm64) AVD instead.

## What is gitignored and absent from a clone

- `*.tflite` -- models are never committed and never bundled.
- `rvm-release.keystore`, `keystore.properties` -- release signing material.
  Release builds still work without them, just unsigned (see `rvm-app-release`).
- `dist/` -- staged APKs.
- `local.properties` -- your SDK path; Android Studio regenerates it.

## Where to read next

- `README.md` -- architecture, project structure, logging tags.
- `docs/model-download-plan.md` -- the model-delivery design and its verified
  behaviour, including two defects found during implementation.
- `docs/threading-plan.md` -- why each native resource has its own thread.
- `docs/ui-redesign-plan.md` -- the visual system and its key decisions.

---
name: rvm-app-architecture
description: How the RVM app is structured and the invariants you must not break when editing it -- the generic module pattern, per-object thread confinement, and several traps that look harmless but deadlock, crash, or silently corrupt config. Use before modifying MatteModule, TFLiteModelRunner, VideoFrameDecoder/Encoder, Controller, MatteConfig, or MatteViewModel.
---

# RVM app: architecture and invariants

Read this before editing anything under `matte/`, `modelRunner/`, `video/`, or
`Controller.kt`. Most of the rules below exist because the obvious version was
tried first and broke.

## The generic module pattern

New models plug in without touching surrounding code:

- `ModuleInterface<Config : ConfigInterface, IO>` -- `configure/run/reset/close`.
- Each module brings its own config data class (`MatteConfig`) and its own named
  buffer holder (`MatteIO`), so callers get typed fields, not positional buffers.
- `HiddenStatesInterface` -- recurrent state buffers (RVM passes 4 ConvGRU
  states between frames).
- `ModelRunnerInterface` / `TFLiteModelRunner` -- the shared TFLite wrapper.
  Rebuilds the interpreter only when model file, compute device, or thread count
  actually changed.

`Controller` orchestrates: one `VideoFrameDecoder`, one `MatteModule`, and
**three** `VideoFrameEncoder`s (matte / foreground / composite), so one
decode+inference pass writes three videos.

## Thread confinement -- the core constraint

Every stateful native resource is pinned to one dedicated thread via
`utils/ConfinedRunner.kt`, because none are safe to drive from more than one
thread. The TFLite **GPU delegate binds an EGL context to whichever thread
built the interpreter**, so building and invoking must happen on the same one.
See `docs/threading-plan.md`.

### Trap 1: re-entrancy deadlocks the confined thread

`ConfinedRunner` wraps a single-thread executor and **blocks** on the result. A
task already running on that thread must never call a public (wrapped) method
of the same object -- that queues a second task the executor cannot start until
the first returns. Hence the `Impl` split:

```kotlin
override fun configure(c: MatteConfig) = runner.run { configureImpl(c) }
private fun configureImpl(c: MatteConfig) { resetImpl() /* NOT reset() */ }
```

**When adding a method: public wrapper -> `runner.run { ...Impl() }`, and every
internal call goes to the `Impl`.** Same rule in `VideoFrameEncoder`
(`putNextFramesImpl` calls `putNextFrameImpl`).

### Trap 2: `saveVideo()` must not shut the runner down

`Controller` reuses its three encoders across runs. `saveVideo()` finalises a
file; `close()` releases the thread and is called only from `Controller.close()`.
Shutting down in `saveVideo()` throws `RejectedExecutionException` on the
second run.

### Trap 3: never download or do unbounded I/O on a confined thread

`TFLiteModelRunner.loadModelFile` memory-maps from `ModelStore` -- that is fine.
Nothing in `matte/` or `modelRunner/` may trigger a network fetch.

## `MatteConfig` traps

### Trap 4: `copy()` silently destroys the "auto" downsample sentinel

`MatteConfig.init` resolves the `-1.0F` "auto" sentinel to a concrete ratio
(0.4 at 720x1280) **and** builds the model filename from it. So a plain
`config.copy(...)` re-runs `init` with the already-resolved `0.4` and rebuilds
the filename as `_ds_040.tflite` -- a file the release does not publish.

**Always pass `downsampleRatio = config.requestedDownsampleRatio` when copying**
a config you did not construct yourself. That property recovers the sentinel
from the filename. This has caused a real bug already.

### Trap 5: do not reorder `MatteConfig.init`

`buildModelFileName()` is deliberately called *before* the auto sentinel is
resolved. Moving it produces `_ds_040` instead of `_ds_auto`.

## `MatteViewModel` traps

### Trap 6: `isConfiguring` must be raised synchronously

It is set **before** `viewModelScope.launch`, cleared in `finally`. Setting it
inside the coroutine leaves a window where `updateConfig`'s guard reads a stale
`false` and starts a second, overlapping GPU-delegate build; the loser leaks its
delegate.

### Trap 7: configuration failures must not throw

`runConfigure` catches and reports. An uncaught throw propagates out of
`viewModelScope` and kills the process -- a model file removed underneath the
app, or a delegate refusing a graph, would take the app down.

## Kotlin/perf notes

- **`override` methods cannot declare default parameter values.** Interfaces
  declare the defaults; concrete overrides omit them. This recurs constantly.
- **Never use per-element `FloatBuffer.get(i)`/`put(i, v)` on a `SharedMemory`
  buffer.** ~2.76M accesses measured ~300 ms/frame. Bulk-transfer into a
  `FloatArray`, loop over the primitive array, bulk-write back -- see
  `Controller.compositeForegroundWithAlpha` and
  `VideoFrameEncoder.floatBufferToNV21`.

---
name: rvm-benchmark
description: Benchmark a .tflite model's inference speed and delegate coverage on an Android device's CPU or GPU, via scripts/benchmark.sh / benchmark/benchmark_cpu.sh / benchmark_gpu.sh. Use when asked to benchmark, profile, or measure the on-device performance of a .tflite model, or to check whether a model fully runs on the GPU delegate.
---

# Benchmarking a .tflite model on-device

Uses the prebuilt TFLite `benchmark_model` tool over `adb`. `benchmark/binary/`
ships one binary per ABI (`android_aarch64_benchmark_model` for arm64-v8a,
`android_arm_benchmark_model` for armeabi-v7a/armeabi); both scripts query the
connected device's ABI via `adb shell getprop ro.product.cpu.abi` and push the
matching one automatically, so you don't need to pick it yourself. An
unrecognized ABI aborts with an error rather than pushing a binary that won't
exec. Needs a device connected (see `rvm-setup`). Prefer the wrapper script:

```bash
./scripts/benchmark.sh <cpu|gpu> <model.tflite> [device_name_or_id]
```

`device_name_or_id` is optional if exactly one device is attached to `adb`
(it's passed straight through as `adb -s <device>`; omit it and plain `adb`
applies its own default/error behavior for multiple/zero devices). Get
connected device names/ids from `adb devices -l`.

This dispatches to `benchmark/benchmark_cpu.sh` (`--num_threads=10
--enable_op_profiling=true --verbose=true`) or `benchmark/benchmark_gpu.sh`
(`--use_gpu=true`, same profiling flags) directly if you need to run one of
those without the `<cpu|gpu>` dispatch layer. Both push the binary + model to
`/data/local/tmp/rvm_benchmark/` on the device and merge the binary's stdout
*and* stderr (`2>&1`) into the saved log -- stderr is where the important
`ERROR:` lines live (unsupported-op lists, delegate failures), so don't strip
it if you're editing these scripts further.

## Where results go

Logs mirror the model's location under `tflite_models/`: a model at
`tflite_models/<source>/foo.tflite` produces
`benchmark/<source>/<cpu|gpu>/foo_<device>.log` (outer layer = source
subfolder, inner layer = cpu/gpu backend). A model outside `tflite_models/`
falls back to a flat `benchmark/<cpu|gpu>/foo_<device>.log`.

## Reading the output

- `ERROR: Following operations are not supported by GPU delegate: ...` --
  lists exactly which ops force a CPU fallback for those nodes; **this is a
  soft failure**, not a crash -- the model still runs (partially delegated)
  unless followed by an `Init`/`Prepare` failure like `TfLiteGpuDelegate
  Init: Tensor "..." has bad input dims size: 0` / `ERROR: Benchmarking
  failed.`, which *is* a hard failure. If you're chasing GPU-delegate
  compatibility, see the `rvm-gpu-delegate-fix` skill.
- `INFO: Explicitly applied GPU delegate, and the model graph will be
  completely executed by the delegate.` -- full delegation, no CPU fallback
  at all; the goal state for a `--source gpu` build.
- `N operations will run on the GPU, and the remaining M operations will run
  on the CPU.` -- partial delegation; M > 0 means some ops still fell back.
- `Timings (microseconds): count=N ... avg=...` at the very end -- the
  headline number; `--enable_op_profiling=true` also prints a full per-node
  breakdown above it (useful for finding which specific op instance in the
  graph is slow or unsupported, via the model/module-path breadcrumbs in
  each node's name on a CPU run without profiling's op-fusion, or the
  `winograd_*`/`convolution_*`/etc. op-type names on a GPU run).

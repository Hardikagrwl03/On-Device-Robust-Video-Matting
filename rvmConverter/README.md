# Converter for On-Device Robust Video Matting

Converts [Robust Video Matting](https://github.com/PeterL1n/RobustVideoMatting)
(RVM) PyTorch checkpoints into TFLite models for on-device inference, with
tooling to verify the export is numerically correct and to benchmark it on a
real Android device's CPU and GPU delegate.

This directory vendors the upstream RVM source under `RobustVideoMatting/`
and adds a conversion/verification/benchmarking toolkit around it, plus a
second copy of the model source (`RobustVideoMatting/model_gpu/`) rewritten
specifically to be fully compatible with the TFLite GPU delegate.

## Contents

- [Pre-converted models](#pre-converted-models)
- [Setup](#setup)
- [File structure](#file-structure)
- [Core concepts](#core-concepts)
- [Utilities](#utilities)
  - [`convert.py` — export to TFLite](#convertpy--export-to-tflite)
  - [`verify.py` — check a `.tflite` against PyTorch](#verifypy--check-a-tflite-against-pytorch)
  - [`compare.py` — check `model_gpu` against `model`](#comparepy--check-model_gpu-against-model)
  - [`benchmark/` — on-device CPU/GPU benchmarking](#benchmark--on-device-cpugpu-benchmarking)
  - [`analysis/visualize.py` — render a `.tflite`'s op graph](#analysisvisualizepy--render-a-tflites-op-graph)
  - [`scripts/` — user-friendly wrappers](#scripts--user-friendly-wrappers)
- [The `model` vs. `model_gpu` split](#the-model-vs-model_gpu-split)
- [`run.sh` — the whole pipeline in one command](#runsh--the-whole-pipeline-in-one-command)
- [Typical workflow](#typical-workflow)
- [Claude Code skills](#claude-code-skills)
- [Gotchas](#gotchas)

## Pre-converted models

If you just want ready-to-use `.tflite` files and don't need to build the
conversion toolkit yourself, all exported models (`resnet50` and
`mobilenetv3`, both `original` and `gpu` sources) are shared here:

**[Pre-converted TFLite models (Google Drive)](https://drive.google.com/drive/folders/1VXIsAFNzCVJ-ylWkxmL_tJxKAAFb992K?usp=sharing)**

Filenames follow the convention described in [Core concepts](#core-concepts)
— `rvm_<variant>_<height>x<width>_ds_<ds>.tflite`. If you plan to run on a
GPU delegate, use a `gpu`-source build (see
[The `model` vs. `model_gpu` split](#the-model-vs-model_gpu-split)); if
you're only running on CPU, either source works.

## Setup

### 1. Conda environment

`environment.yaml` is a portable export of the exact environment this
toolkit was built and tested against (Python 3.11, `torch==2.12.0+cpu`,
`litert-torch==0.9.3`, `ai-edge-litert==2.1.6`, `torchvision==0.27.0+cpu`,
etc.):

```bash
conda env create -n rvm-convert -f environment.yaml
conda activate rvm-convert
```

`requirements.txt` documents the same pins via `pip`, with comments
explaining *why* each is pinned — most importantly, `torch`/`torchvision`
have to be new enough for `torch.export` (torch ≥ 2.4), which is
incompatible with upstream RVM's own inference pins
(`RobustVideoMatting/requirements_inference.txt`, `torch==1.9.0`). Use it if
you'd rather not use conda:

```bash
pip install -r requirements.txt
```

Every script under `scripts/` activates the `rvm-convert` env for you (via
`conda activate rvm-convert`), so once it exists under that exact name you
don't need to activate it by hand.

`netron`/`selenium` (also in both files) are only used by
[`analysis/visualize.py`](#analysisvisualizepy--render-a-tflites-op-graph);
everything else (convert/verify/compare/benchmark) works without them. That
script also needs a real Chrome/Chromium binary on the machine (`google-chrome`,
`google-chrome-stable`, `chromium`, or `chromium-browser` on `PATH`) — Selenium
downloads a matching driver for it automatically.

### 2. Checkpoints

`RobustVideoMatting/checkpoints/` is gitignored and empty on a fresh clone.
Download the two official RVM checkpoints (same URLs
`RobustVideoMatting/hubconf.py` uses):

```bash
mkdir -p RobustVideoMatting/checkpoints
curl -L -o RobustVideoMatting/checkpoints/rvm_mobilenetv3.pth \
  https://github.com/PeterL1n/RobustVideoMatting/releases/download/v1.0.0/rvm_mobilenetv3.pth
curl -L -o RobustVideoMatting/checkpoints/rvm_resnet50.pth \
  https://github.com/PeterL1n/RobustVideoMatting/releases/download/v1.0.0/rvm_resnet50.pth
```

### 3. Android device (optional, for benchmarking only)

`benchmark/binary/` ships prebuilt TFLite `benchmark_model` binaries for two
ABIs (`android_aarch64_benchmark_model` for `arm64-v8a`,
`android_arm_benchmark_model` for `armeabi-v7a`/`armeabi`) — built per the
official LiteRT guide on [implementing/building a delegate and its benchmark
tooling](https://developers.google.com/edge/litert/performance/implementing_delegate).
`benchmark_cpu.sh`/`benchmark_gpu.sh` detect the connected device's ABI and
push the matching one automatically. You'll need `adb` on `PATH` and at
least one Android device reachable:

```bash
adb devices -l
```

A device with neither ABI (rare — some x86 emulators) can't run either
binary; the scripts detect this via `adb shell getprop ro.product.cpu.abi`
and abort with an explicit "Unsupported device architecture" error rather
than failing unhelpfully at push/exec time.

## File structure

```
rvmConverter/
├── RobustVideoMatting/           # vendored upstream RVM source (inference.py, train.py, etc.)
│   ├── model/                    #   UNMODIFIED upstream model source -- never edit
│   ├── model_gpu/                #   parallel copy, edited for TFLite GPU-delegate compatibility
│   └── checkpoints/               #   gitignored; put rvm_resnet50.pth / rvm_mobilenetv3.pth here
├── wrapper.py                    # RVMWrapper: the nn.Module actually traced/exported
├── run.sh                        # chains convert -> compare -> verify -> benchmark -> visualize
├── convert.py                    # PyTorch checkpoint -> .tflite
├── verify.py                     # exported .tflite vs. PyTorch, numerical check
├── compare.py                    # model vs. model_gpu, numerical check (pure PyTorch)
├── scripts/                      # user-friendly wrappers (env activation + cwd handled)
│   ├── convert.sh
│   ├── verify.sh
│   ├── compare.sh
│   ├── benchmark.sh
│   └── visualize.sh
├── benchmark/                    # on-device CPU/GPU benchmarking via adb
│   ├── benchmark_cpu.sh
│   ├── benchmark_gpu.sh
│   ├── binary/                   # prebuilt per-ABI TFLite benchmark tools (see Setup step 3)
│   └── <original|gpu>/<cpu|gpu>/*.log   # generated logs -- tracked, not gitignored (see Gotchas)
├── tflite_models/                # convert.py's output, gitignored
│   └── <original|gpu>/*.tflite
├── analysis/
│   ├── visualize.py              # .tflite -> op-graph SVG/PNG (via headless-Chrome + netron)
│   └── <original|gpu>/*.svg      # generated visualizations -- tracked, not gitignored (see Gotchas)
├── .claude/skills/                # Claude Code skills documenting this toolkit's workflows
├── environment.yaml               # conda env export
├── requirements.txt               # pip equivalent, with pinning rationale
└── .gitignore
```

## Core concepts

**`RVMWrapper` (`wrapper.py`)** is what actually gets traced and exported —
not `MattingNetwork` directly. It wraps `MattingNetwork` and translates
between the model's native format and an on-device-friendly one:

- **Input** `src`: `[B, H, W, 3]`, float, **0–255** range (NHWC, no time
  dimension). `MattingNetwork`'s backbone/decoder branch on `ndim==4` vs
  `5` and take the simpler single-frame path for 4D input, matching
  single-frame on-device inference — no need to fake a time axis.
- **Output** `fgr`: `[B, H, W, 3]`, **0–255** range, same convention as the
  input. **Output** `pha`: `[B, H, W, 1]`, **0–1** range — unlike `fgr`, the
  alpha channel is left unscaled (matting alpha is naturally a [0,1]
  coverage value, and on-device consumers more often want it as a
  ready-to-multiply mask than as an 8-bit range).
- Internally it permutes NHWC↔NCHW and scales the input 0–255↔[0,1] and
  `fgr` back 0–1↔0–255 around a call into the real `MattingNetwork.forward`
  (which works entirely in NCHW, [0,1]), so that conversion lives inside the
  exported graph rather than needing to be reimplemented on-device.
- Recurrent states `r1`–`r4` (RVM's temporal consistency state) are
  correctly shaped for a given resolution/backbone via
  `RVMWrapper.init_recurrent_state()`, which runs the model once with
  `r1..r4=None` and captures the shapes it comes back with — **not** by
  hand-deriving them from stride arithmetic. (That was tried and quietly
  broke at some resolutions: stride arithmetic doesn't collapse to plain
  integer division of height/width at every size — e.g. resnet50's last
  stage rounds differently than a straight `// 16`.)

**`variant`**: `resnet50` or `mobilenetv3` — RVM's two backbone options.

**`source`**: `original` or `gpu` — which copy of the RVM model source to
trace from (`RobustVideoMatting.model` vs. `.model_gpu`; see
[below](#the-model-vs-model_gpu-split)). Also picks the default output
directory, `tflite_models/<source>/`.

**`downsample_ratio`**: RVM internally can run its backbone/decoder on a
downsampled frame and refine the result back up (`DeepGuidedFilterRefiner`)
for speed. TFLite export needs **static shapes**, so this toolkit bakes in a
single fixed ratio at trace time rather than exposing it as a runtime input.
`1.0` (the default) means no downsampling at all — the refiner branch is
never even traced. A ratio `< 1` is also supported and does trace the
refiner branch. Pass `<= 0` to auto-compute one from height/width via RVM's
own `min(512 / max(h, w), 1)` heuristic instead of a fixed value.

**Output naming convention** (`convert.py`'s `default_output()`), relied on
by `verify.py`'s filename inference and `benchmark/*.sh`'s log-folder
mirroring:

```
tflite_models/<source>/rvm_<variant>_<height>x<width>_ds_<ds>.tflite
```

`<ds>` is a whole-number percentage with no literal `.` (dots are awkward in
filenames): `100` = ratio 1.0 (no shape change), `050` = ratio 0.5, `auto`
when `--downsample-ratio` was `<= 0`.

## Utilities

### `convert.py` — export to TFLite

```bash
python convert.py [options]        # or: ./scripts/convert.sh [options]
```

| Flag | Default | Meaning |
|---|---|---|
| `--variant` | `all` | `resnet50`, `mobilenetv3`, or `all` (converts both) |
| `--source` | `original` | `original` or `gpu` (see [below](#the-model-vs-model_gpu-split)) |
| `--height` / `--width` | `720` / `1280` | traced (static) input resolution |
| `--downsample-ratio` | `1.0` | baked into the traced graph; `<= 0` = auto-compute |
| `--batch-size` | `1` | traced batch size |
| `--checkpoint` / `--output` | derived | only valid with a single `--variant`, not `all` |
| `--output-dir` | `tflite_models/<source>` | where derived output filenames go |
| `--skip-verify` | off | skip the built-in PyTorch-vs-exported-model numerical check |

Run `python convert.py --help` for the full, current, authoritative list.

It traces `RVMWrapper` with `litert_torch.convert`, prints the resolved
shapes, runs a quick in-memory PyTorch-vs-edge-model sanity check (unless
`--skip-verify`), and writes the `.tflite` file.

### `verify.py` — check a `.tflite` against PyTorch

```bash
python verify.py --tflite <path/to/model.tflite> [options]   # or: ./scripts/verify.sh ...
```

Loads the `.tflite` via `ai_edge_litert.interpreter.Interpreter`, runs both
it and the equivalent `RVMWrapper` in PyTorch on identical random inputs,
and prints a per-output PASS/FAIL table (`fgr`, `pha`, `r1`–`r4`) with max
absolute difference and means.

`--variant`, `--source`, `--height`, `--width`, and `--downsample-ratio` are
all optional — they're inferred from `--tflite`'s path, following the naming
convention above. It prints which flags it inferred vs. which you passed. If
a value can't be inferred and wasn't passed, `--variant`/`--height`/`--width`
error out asking for it explicitly; `--source`/`--downsample-ratio` instead
fall back to sane defaults (`original` / `1.0`).

`--atol` (default `1e-2`) sets the pass/fail threshold, applied to every
output at its own native scale. Normal PyTorch-vs-TFLite-XNNPACK
floating-point drift, not a bug: `fgr` (0–255 range) typically differs by
`~0.005`; `pha` (0–1 range, unscaled) typically differs by `~0.00005` —
about 255x smaller in absolute terms, matching the 255x smaller range. A
failure on a recurrent state, a `NaN`, or a diff far above these is worth
investigating.

### `compare.py` — check `model_gpu` against `model`

```bash
python compare.py --variant {resnet50,mobilenetv3} [options]
```

Pure PyTorch, no TFLite involved: loads the **same checkpoint** into
`RVMWrapper(source="original")` and `RVMWrapper(source="gpu")`, runs both on
identical inputs, and prints the same PASS/FAIL table as `verify.py`. Used
to confirm that a GPU-delegate-compatibility rewrite in `model_gpu/`
(see below) is *exactly* numerically equivalent to the unmodified original —
every fix made so far reports `max_diff=0.000000`.

### `benchmark/` — on-device CPU/GPU benchmarking

```bash
./benchmark/benchmark_cpu.sh <model.tflite> [device_name_or_id]
./benchmark/benchmark_gpu.sh <model.tflite> [device_name_or_id]
```

Queries the device's ABI (`adb shell getprop ro.product.cpu.abi`) to pick the
matching binary from `benchmark/binary/` (see [Setup step
3](#3-android-device-optional-for-benchmarking-only) for where these come
from), then pushes it and the given `.tflite` to
`/data/local/tmp/rvm_benchmark/` on the device via `adb` (`-s <device>` only
if a device is given) and runs it — `benchmark_gpu.sh` adds
`--use_gpu=true`, both use `--num_runs=10 --enable_op_profiling=true
--verbose=true` (the shared `--num_runs=10` keeps CPU and GPU timings
directly comparable — same iteration count on both), merging stdout **and**
stderr into the saved log (stderr is where the important `ERROR:`
diagnostics live).

Logs mirror the model's location under `tflite_models/`: a model at
`tflite_models/<source>/foo.tflite` produces
`benchmark/<source>/<cpu|gpu>/foo_<device>.log` (outer layer = source,
inner layer = backend). A model outside `tflite_models/` falls back to a
flat `benchmark/<cpu|gpu>/foo_<device>.log`.

**Reading the output:**
- `ERROR: Following operations are not supported by GPU delegate: ...` — a
  *soft* failure; those specific ops fall back to CPU, the rest of the model
  still runs on GPU.
- `TfLiteGpuDelegate Init: ... / ERROR: Benchmarking failed.` — a *hard*
  failure; the delegate couldn't prepare the graph at all.
- `INFO: Explicitly applied GPU delegate, and the model graph will be
  completely executed by the delegate.` — full delegation, zero CPU
  fallback; the goal state for a `--source gpu` build.
- `Timings (microseconds): count=N ... avg=...` — the headline inference
  time, at the end of the log.

### `analysis/visualize.py` — render a `.tflite`'s op graph

```bash
python analysis/visualize.py --tflite <path/to/model.tflite> [options]   # or: ./scripts/visualize.sh ...
```

Renders a `.tflite`'s op graph to a self-contained SVG (or PNG, via
`--format png`) — the same view [netron](https://netron.app/) shows in a
browser, but produced headlessly for scripting/CI use: it starts netron's
own local-webserver viewer, drives it in headless Chrome via Selenium, waits
for the graph to finish laying out, then calls the exact in-page function
netron's own "Export as SVG"/"Export as PNG" menu action calls (rather than
screenshotting the canvas) so the file is properly cropped and
self-contained. See the module docstring for the full mechanics and why a
screenshot wouldn't do.

`--output` defaults to `analysis/<source>/<model-basename>.<format>`, where
`<source>` (`original`/`gpu`) is inferred from `--tflite`'s path — same
convention as `convert.py`/`verify.py`. Useful for visually spotting which
node a GPU-delegate-unsupported op comes from (see
[`rvm-gpu-delegate-fix`](#claude-code-skills)) or just for inspecting a
model's structure.

### `scripts/` — user-friendly wrappers

Thin wrappers that activate the `rvm-convert` conda env and `cd` to the
project root (so relative paths work regardless of your current directory),
then forward everything else through:

```bash
./scripts/convert.sh [convert.py options]
./scripts/verify.sh --tflite <path> [verify.py options]
./scripts/compare.sh --variant {resnet50,mobilenetv3} [compare.py options]
./scripts/benchmark.sh <cpu|gpu> <model.tflite> [device_name_or_id]
./scripts/visualize.sh --tflite <path> [visualize.py options]
```

`convert.sh`/`verify.sh`/`compare.sh`/`visualize.sh` pass `--help` straight
through to the underlying Python CLI. `benchmark.sh` has its own
`-h`/`--help` (it has one more required argument — the backend — that the
others don't) and dispatches to `benchmark/benchmark_cpu.sh` or
`benchmark_gpu.sh`.

## The `model` vs. `model_gpu` split

`RobustVideoMatting/model/` is the **unmodified** upstream RVM source — it
must stay byte-identical to upstream and should never be edited.
`RobustVideoMatting/model_gpu/` is a parallel copy that exists specifically
to be rewritten for TFLite GPU-delegate compatibility. `wrapper.py`'s
`RVMWrapper` picks between them at construction time based on `source`, via
`importlib.import_module`.

Some idiomatic PyTorch ops decompose, under `torch.export`'s TFLite
lowering, into fused/generic ops the TFLite **GPU delegate** doesn't support
— even though they run fine on CPU (XNNPACK). Three have been found and
fixed so far, each as an exact algebraic rewrite (verified with `compare.py`
to have `max_diff=0.000000` against the original):

| File | Original | Unsupported op | Fix |
|---|---|---|---|
| `lraspp.py` | `nn.AdaptiveAvgPool2d(1)` in `LRASPP.aspp2` | `GATHER_ND` / `STABLEHLO_REDUCE_WINDOW` | `GlobalAvgPool2d`: `x.mean(dim=(-2,-1), keepdim=True)` — exact for any output-size-1 pool |
| `decoder.py` | `nn.AvgPool2d(2, 2, ceil_mode=True, count_include_pad=False)` in `AvgPool` | `STABLEHLO_REDUCE_WINDOW` | plain `nn.AvgPool2d(2, 2)` — exact whenever every pooled dimension stays even at each of the 3 stages (true for the resolutions this export targets) |
| `mobilenetv3.py` | `torchvision.ops.misc.SqueezeExcitation.avgpool` (`nn.AdaptiveAvgPool2d(1)`, inside SE-enabled `InvertedResidual` blocks) | `GATHER_ND` | same `GlobalAvgPool2d`, swapped in via post-construction module surgery (`isinstance(module, SqueezeExcitation)`) since it's torchvision library code, not something in this repo to edit directly |
| `model.py` | `x.clamp(0., 1.)` on final `fgr`/`pha` | `RELU_0_TO_1` | `_clamp01(x) = F.relu(x) - F.relu(x - 1)` — exact for all real `x` |

With all three, both `resnet50` and `mobilenetv3` fully GPU-delegate (`the
model graph will be completely executed by the delegate`, zero unsupported
ops in the benchmark log) as of this writing.

`wrapper.py` also has one fix unrelated to GPU-delegate support, applied to
**either** tree: `torchvision.transforms.functional.normalize` (used by
`mobilenetv3.py`'s backbone) has an internal `if (std == 0).any(): raise
...` input-validation check that `torch.export`'s tracer can't statically
resolve (`GuardOnDataDependentSymNode`), even though the ImageNet `std` it's
called with is a hardcoded, always-nonzero constant. `RVMWrapper` monkeypatches
a check-free replacement onto whichever `mobilenetv3` module gets imported.

See `.claude/skills/rvm-gpu-delegate-fix/SKILL.md` for the step-by-step
recipe used to find and fix these (useful if a future op turns out to be
unsupported too).

## `run.sh` — the whole pipeline in one command

```bash
./run.sh [options]   # convert -> compare -> verify -> benchmark -> visualize, for one variant
```

Chains all five tools above for a single variant/source/resolution
combination, so a full round-trip from checkpoint to on-device numbers is
one command instead of five. Defaults to `--variant mobilenetv3 --source gpu
--backend gpu` (the GPU-delegate-compatible build, benchmarked on GPU); see
`./run.sh --help` for the full option list (`--checkpoint`,
`--height`/`--width`/`--downsample-ratio`, `--output-dir`, `--device`,
`--format`). It reads the `.tflite` path back out of `convert.sh`'s own
"conversion successful: ..." output rather than recomputing the naming
convention itself, so it can't drift out of sync with `convert.py`.

`--source`/`--output` are passed explicitly to `verify.sh`/`visualize.sh`
rather than left to their own path-based inference, since that inference
only works when `--output-dir` happens to keep `gpu`/`original` as a path
component — `run.sh` already knows the real answer regardless of
`--output-dir`, so it doesn't guess.

Each step's own script (`./scripts/convert.sh --help`, etc.) exposes more
options than `run.sh` forwards — drop to running them individually (see
[Typical workflow](#typical-workflow)) for anything not covered here, e.g.
converting `--variant all` in one call.

## Typical workflow

```bash
# 1. Convert (both variants, GPU-delegate-compatible source, no downsampling)
./scripts/convert.sh --source gpu --downsample-ratio 1

# 2. Verify each export is numerically correct
./scripts/verify.sh --tflite tflite_models/gpu/rvm_mobilenetv3_720x1280_ds_100.tflite
./scripts/verify.sh --tflite tflite_models/gpu/rvm_resnet50_720x1280_ds_100.tflite

# 3. Benchmark on-device
./scripts/benchmark.sh gpu tflite_models/gpu/rvm_mobilenetv3_720x1280_ds_100.tflite <device>
./scripts/benchmark.sh cpu tflite_models/gpu/rvm_mobilenetv3_720x1280_ds_100.tflite <device>
```

## Claude Code skills

`.claude/skills/` has five project-scoped skills (auto-discovered by Claude
Code from this repo, no extra setup) that document these workflows in more
operational detail than this README:

- `rvm-setup` — this repo's first-time setup
- `rvm-convert` — `convert.py`/`scripts/convert.sh` in depth
- `rvm-verify` — `verify.py`/`scripts/verify.sh` in depth
- `rvm-benchmark` — on-device benchmarking in depth
- `rvm-gpu-delegate-fix` — the recipe for diagnosing and fixing a new
  GPU-delegate-unsupported op, with the three fixes above as worked examples
  (covers `compare.py`/`compare.sh` and `analysis/visualize.py`/`visualize.sh`
  as part of that workflow)

## Gotchas

- Never edit `RobustVideoMatting/model/*.py` — only `model_gpu/*.py`.
- `benchmark/binary/` holds one prebuilt `benchmark_model` per ABI
  (`arm64-v8a`, `armeabi-v7a`/`armeabi`) — see [Setup step
  3](#3-android-device-optional-for-benchmarking-only) for how they were
  built and the [LiteRT delegate-implementation
  guide](https://developers.google.com/edge/litert/performance/implementing_delegate)
  they follow. `benchmark_cpu.sh`/`benchmark_gpu.sh` auto-detect the device's
  ABI and push the right one; a device on neither ABI gets an explicit
  "Unsupported device architecture" error instead of a confusing push/exec
  failure.
- `--downsample-ratio 1.0` (the default) skips RVM's refiner branch
  entirely — the traced graph runs the full backbone/decoder at full
  resolution. A ratio `< 1` traces the refiner branch too, which is a
  larger/different graph.
- `fgr` is 0–255-ranged, `pha` is unscaled 0–1 — don't assume they share a
  scale when comparing raw numbers by hand. A `fgr` max-diff around `0.005`
  and a `pha` max-diff around `0.00005` in `verify.py` are both expected
  PyTorch-vs-TFLite floating-point drift, not a conversion bug.
- `verify.py` reads a `.tflite`'s outputs via its named `serving_default`
  signature (`output_0`, `output_1`, ...), not by sorting raw tensor
  `index` values. The two can diverge: a tensor's buffer index reflects
  internal flatbuffer layout, not necessarily `RVMWrapper.forward()`'s
  return-value order — this bit us for real once `fgr`'s and `pha`'s branches
  stopped sharing an identical trailing op sequence (see `pha`'s scaling
  above), silently swapping which output got compared against which. If
  you're editing `run_tflite()`/`run_pytorch()` in `verify.py`, keep going
  through the signature, not `get_input_details()`/`get_output_details()`
  sorted by `index`.
- Only `tflite_models/` and `RobustVideoMatting/checkpoints/` are gitignored
  (plus the blanket `*.pth`/`*.tflite` globs). `benchmark/*.log` and
  `analysis/*.svg`/`*.png` are generated too, but are deliberately **not**
  gitignored — this repo commits its own benchmark/visualization history
  instead of treating it as disposable. Don't assume a clean `git status`
  after running `benchmark_*.sh`/`visualize.sh`; `git add` the new/changed
  logs and images if you want them kept.
- `analysis/visualize.py` needs a real Chrome/Chromium binary installed on
  the machine (not just the `selenium`/`netron` pip packages) — see
  [Setup step 1](#1-conda-environment).

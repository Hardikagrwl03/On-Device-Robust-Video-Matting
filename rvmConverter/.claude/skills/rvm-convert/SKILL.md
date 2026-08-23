---
name: rvm-convert
description: Convert an RVM PyTorch checkpoint (resnet50/mobilenetv3) to a TFLite model via convert.py / scripts/convert.sh. Use when asked to export, convert, or trace an RVM model to TFLite, to change its traced resolution/downsample-ratio, or to produce a GPU-delegate-targeted (--source gpu) build.
---

# Converting RVM checkpoints to TFLite

`convert.py` traces `wrapper.RVMWrapper` (which wraps `MattingNetwork`) with
`litert_torch.convert` and exports a `.tflite` file. Prefer running it via
the wrapper script, which activates the `rvm-convert` conda env and `cd`s to
the repo root for you (see `rvm-setup` if the env doesn't exist yet):

```bash
./scripts/convert.sh [options]
```

(equivalent to `conda activate rvm-convert && python convert.py [options]`
from the repo root.)

## Key options

| Flag | Default | Meaning |
|---|---|---|
| `--variant` | `all` | `resnet50`, `mobilenetv3`, or `all` (converts both) |
| `--source` | `original` | `original` traces `RobustVideoMatting.model` (unmodified upstream); `gpu` traces `RobustVideoMatting.model_gpu` (same architecture, edited for TFLite-GPU-delegate compatibility -- see the `rvm-gpu-delegate-fix` skill) |
| `--height` / `--width` | `720` / `1280` | traced (static) input resolution |
| `--downsample-ratio` | `1.0` | baked into the traced graph; `1.0` = no downsampling (full-res, no refiner branch). Pass `<= 0` to auto-compute one from height/width instead (RVM's `min(512/max(h,w), 1)` heuristic) |
| `--batch-size` | `1` | traced batch size |
| `--checkpoint` / `--output` | derived | only valid with a single `--variant` (not `all`); otherwise both are derived automatically (see naming below) |
| `--output-dir` | `tflite_models/<source>` | where derived output filenames go |
| `--skip-verify` | off | skip the built-in PyTorch-vs-exported-edge-model numerical check that runs right after tracing |

Run `./scripts/convert.sh --help` for the exact, current flag list --
`convert.py`'s CLI is the source of truth, this table can drift.

## Output naming convention

Unless `--output`/`--checkpoint` are given explicitly, files are named and
located by convention -- other tools in this repo (`verify.py`'s filename
inference, `benchmark/*.sh`'s folder mirroring) rely on this:

```
tflite_models/<source>/rvm_<variant>_<height>x<width>_ds_<ds>.tflite
```

where `<ds>` is `format_downsample_ratio()`'s encoding (no literal `.`, since
dots are awkward in filenames): `100` = ratio 1.0 (no shape change), `050` =
ratio 0.5, etc.; `auto` when `--downsample-ratio` was `<= 0`. E.g.
`tflite_models/gpu/rvm_mobilenetv3_720x1280_ds_100.tflite`.

## Input traced by the wrapper

`RVMWrapper` (in `wrapper.py`) takes `src` as `[B, H, W, 3]`, `0-255`-ranged
float (NHWC, no time dimension -- the underlying `MattingNetwork` branches
on `ndim==4` vs `5` and takes the simpler single-frame path for 4D) and
returns `fgr` as `[B, H, W, 3]`, `0-255`-ranged (same convention as `src`)
and `pha` as `[B, H, W, 1]`, **unscaled `0-1`** -- unlike `fgr`, alpha is
left as a raw coverage value, not rescaled to 8-bit range. It internally
converts to/from the model's native NCHW, `[0,1]`-normalized format.
Recurrent states `r1`-`r4` are auto-sized per resolution/backbone via
`RVMWrapper.init_recurrent_state()` -- never hand-derive their shapes from
stride arithmetic, it silently drifts off by one at some resolutions (that
was a real, fixed bug here).

## After converting

Verify the export is numerically correct (`rvm-verify` skill) and, if you
care about on-device performance, benchmark it (`rvm-benchmark` skill). If
converting with `--source gpu` for a model still showing GPU-delegate errors,
see the `rvm-gpu-delegate-fix` skill.

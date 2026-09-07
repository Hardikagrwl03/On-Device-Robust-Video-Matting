---
name: rvm-setup
description: Set up this repo after a fresh clone -- conda env, PyTorch checkpoints, and adb device check. Use when someone has just cloned the repo and wants to get convert/verify/benchmark working, or hits errors like "No module named litert_torch", a missing checkpoint file, or "no devices/emulators found".
---

# RVM TFLite converter: first-time setup

This repo converts Robust Video Matting (RVM) PyTorch checkpoints to TFLite,
optionally rewritten for TFLite GPU-delegate compatibility, and can verify
and benchmark the result. Three things are gitignored and not in a fresh
clone: the conda env, the `.pth` checkpoints, and (obviously) any exported
`.tflite` files. Set those up first.

## 1. Conda environment

`environment.yaml` at the repo root is an exported, portable spec (no
machine-specific `prefix` line) of the exact env this project was built
against -- torch 2.12 (cpu), litert-torch, ai-edge-litert, torchvision, etc.
Recreate it:

```bash
conda env create -n rvm-convert -f environment.yaml
conda activate rvm-convert
```

If that fails or drifts, `requirements.txt` documents the same pins with
comments explaining *why* each one is pinned (e.g. torch/torchvision have to
be new enough for `torch.export`, which is incompatible with upstream RVM's
own `requirements_inference.txt` pins) -- use it with `pip install -r
requirements.txt` inside a matching Python 3.11 env if you'd rather not use
conda.

All the `scripts/*.sh` wrappers (see the `rvm-convert`, `rvm-verify` skills)
activate `rvm-convert` for you via
`source "$(conda info --base)/etc/profile.d/conda.sh" && conda activate rvm-convert`,
so once the env exists by that exact name, you don't need to activate it
by hand to use them.

## 2. RVM checkpoints

`RobustVideoMatting/checkpoints/` is gitignored and empty in a fresh clone.
`convert.py`/`compare.py` default to expecting
`RobustVideoMatting/checkpoints/rvm_<variant>.pth` for `<variant>` in
`resnet50`, `mobilenetv3`. Download the originals from the upstream RVM
release (same URLs `RobustVideoMatting/hubconf.py` uses):

```bash
mkdir -p RobustVideoMatting/checkpoints
curl -L -o RobustVideoMatting/checkpoints/rvm_mobilenetv3.pth \
  https://github.com/PeterL1n/RobustVideoMatting/releases/download/v1.0.0/rvm_mobilenetv3.pth
curl -L -o RobustVideoMatting/checkpoints/rvm_resnet50.pth \
  https://github.com/PeterL1n/RobustVideoMatting/releases/download/v1.0.0/rvm_resnet50.pth
```

(Confirm with the user before downloading anything if they haven't asked for
it explicitly -- these are ~15MB and ~108MB files from a third-party GitHub
release.)

## 3. On-device benchmarking (optional)

Only needed for the `rvm-benchmark` skill. Requires `adb` on `PATH` and at
least one Android device reachable:

```bash
adb devices -l
```

`benchmark/binary/` ships one prebuilt TFLite `benchmark_model` binary per
ABI (`android_aarch64_benchmark_model` for `arm64-v8a`,
`android_arm_benchmark_model` for `armeabi-v7a`/`armeabi`) -- checked into
the repo, not gitignored. `benchmark_cpu.sh`/`benchmark_gpu.sh` detect the
connected device's ABI (`adb shell getprop ro.product.cpu.abi`) and push the
matching one automatically; a device on neither ABI gets an explicit
"Unsupported device architecture" error rather than a confusing push/exec
failure.

## 4. Op-graph visualization (optional)

Only needed for `analysis/visualize.py`/`scripts/visualize.sh` (used most in
the `rvm-gpu-delegate-fix` workflow). It needs `netron`/`selenium` (already
pinned in `environment.yaml`/`requirements.txt`) *and* a real Chrome or
Chromium binary on the machine (`google-chrome`, `google-chrome-stable`,
`chromium`, or `chromium-browser` on `PATH`) -- it drives that browser
headlessly to trigger netron's own SVG/PNG export rather than screenshotting
it. Not needed for convert/verify/compare/benchmark.

## Repo map

- `convert.py` / `wrapper.py` -- PyTorch -> TFLite export (see `rvm-convert`)
- `verify.py` -- exported `.tflite` vs PyTorch numerical check (see `rvm-verify`)
- `compare.py` -- `RobustVideoMatting.model` vs `.model_gpu` numerical check (see `rvm-gpu-delegate-fix`)
- `benchmark/` -- on-device CPU/GPU benchmarking via `adb` (see `rvm-benchmark`)
- `analysis/visualize.py` -- renders a `.tflite`'s op graph to SVG/PNG (see `rvm-gpu-delegate-fix`)
- `scripts/` -- user-friendly wrappers around the above (env activation + cwd handled for you)
- `run.sh` -- chains convert -> compare -> verify -> benchmark -> visualize for one variant/source/resolution in a single command (see the README's `run.sh` section for its flags)
- `RobustVideoMatting/model/` -- **unmodified** upstream RVM source; never edit
- `RobustVideoMatting/model_gpu/` -- a parallel, editable copy for GPU-delegate-compatibility fixes (see `rvm-gpu-delegate-fix`)

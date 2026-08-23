---
name: rvm-verify
description: Numerically verify an exported .tflite model against the PyTorch model it was traced from, via verify.py / scripts/verify.sh. Use after converting a model, or when asked to check/validate/sanity-check a .tflite file's correctness, or to debug why an exported model's outputs look wrong.
---

# Verifying an exported .tflite against PyTorch

`verify.py` runs the same PyTorch `RVMWrapper` and a `.tflite` model
(via `ai_edge_litert.interpreter.Interpreter`) on identical random inputs,
then prints a per-output PASS/FAIL table. Prefer the wrapper script (see
`rvm-setup` if the `rvm-convert` conda env doesn't exist yet):

```bash
./scripts/verify.sh --tflite <path/to/model.tflite> [options]
```

## It infers most flags from the file for you

`--variant`, `--source`, `--height`, `--width`, and `--downsample-ratio` are
all optional and get inferred from `--tflite`'s path/filename, following
`convert.py`'s naming convention
(`tflite_models/<source>/rvm_<variant>_<height>x<width>_ds_<ds>.tflite` --
see the `rvm-convert` skill). So for a normally-named file, this is usually
enough:

```bash
./scripts/verify.sh --tflite tflite_models/gpu/rvm_mobilenetv3_720x1280_ds_100.tflite
```

It prints which flags it inferred vs. which you passed explicitly. If a flag
can't be inferred (custom/renamed file) and wasn't passed, it errors out
asking for it explicitly rather than guessing -- pass `--variant`,
`--height`, and `--width` by hand in that case (`--source` and
`--downsample-ratio` fall back to sane defaults, `original` and `1.0`,
instead of erroring).

`--atol` (default `1e-2`) controls the max allowed per-output absolute
difference; `--checkpoint` overrides the default
`RobustVideoMatting/checkpoints/rvm_<variant>.pth`.

## Reading the output

It compares `fgr`, `pha`, `r1`, `r2`, `r3`, `r4` (the foreground, alpha, and
four recurrent states) -- but `fgr` and `pha` are NOT on the same scale:
`fgr` is `0-255`-ranged, `pha` is unscaled `0-1`. At the default
`atol=1e-2`, a `fgr` diff of `~0.005` and a `pha` diff of `~0.00005` (about
255x smaller, matching the 255x smaller range) are both **normal
floating-point drift** between PyTorch and the TFLite XNNPACK CPU backend,
not a bug -- it shows up consistently across otherwise-correct conversions
in this repo. A `FAIL` on a recurrent state (`r1`-`r4`), a `NaN`, a diff far
above those, or an output-count/shape mismatch is a real problem worth
investigating.

`verify.py` reads a `.tflite`'s outputs via its named `serving_default`
signature (`output_0`, `output_1`, ...), not by sorting raw tensor `index`
values -- the two can diverge (a tensor's buffer index reflects internal
flatbuffer layout, not necessarily `RVMWrapper.forward()`'s return order),
and doing it the naive way silently swapped `fgr`↔`pha` once their branches
stopped sharing an identical trailing op. Keep going through the signature
if you touch `run_tflite()`/`run_pytorch()`.

If verifying a `--source gpu` model whose op-graph you just changed (see
`rvm-gpu-delegate-fix`), also run `compare.py` first -- it checks
`RobustVideoMatting.model` against `.model_gpu` in pure PyTorch (no TFLite
involved), which isolates whether a divergence is from your PyTorch-level
edit or from the TFLite export/runtime itself.

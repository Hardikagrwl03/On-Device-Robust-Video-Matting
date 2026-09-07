---
name: rvm-gpu-delegate-fix
description: Diagnose and fix a TFLite GPU-delegate-unsupported op (GATHER_ND, STABLEHLO_REDUCE_WINDOW, RELU_0_TO_1, etc.) surfaced when benchmarking a --source gpu model. Use when a GPU benchmark reports "Following operations are not supported by GPU delegate", a delegate Init/Prepare failure, or when asked to make an RVM variant fully GPU-delegate-compatible.
---

# Fixing TFLite GPU-delegate-unsupported ops in model_gpu/

`RobustVideoMatting/model_gpu/` is a parallel copy of `RobustVideoMatting/model/`
(the unmodified upstream RVM source, which must **never** be edited) that
exists specifically to be rewritten for TFLite GPU-delegate compatibility.
`convert.py --source {original,gpu}` picks which tree gets traced (see the
`rvm-convert` skill); `wrapper.py`'s `RVMWrapper` dynamically imports
whichever one is requested and applies a `torchvision.transforms.functional
.normalize` monkeypatch to either tree's `mobilenetv3.py` (needed because
that function's own data-dependent input-validation check breaks
`torch.export` tracing -- unrelated to GPU-delegate support, don't confuse
the two issues).

## The recipe (repeat until the model fully delegates)

1. **Convert + benchmark** to find the current unsupported-op list:
   ```bash
   ./scripts/convert.sh --variant <variant> --source gpu --downsample-ratio 1
   ./scripts/benchmark.sh gpu tflite_models/gpu/rvm_<variant>_<res>_ds_100.tflite <device>
   ```
   Look for `ERROR: Following operations are not supported by GPU delegate:`
   followed by op names (`GATHER_ND`, `STABLEHLO_REDUCE_WINDOW`,
   `RELU_0_TO_1`, ...). See `rvm-benchmark` for how to tell a soft
   (partial-CPU-fallback) failure from a hard (delegate Init/Prepare crash)
   one -- both are worth fixing, but a hard failure means the model doesn't
   run on GPU at all yet.

2. **Find the PyTorch source of the op.** These unsupported ops are almost
   always TFLite's *fused/decomposed* form of a specific PyTorch idiom, not
   something obviously named the same in the source. Known mappings found
   in this codebase so far:
   - `nn.AdaptiveAvgPool2d(1)` (any global/output-size-1 pool) -> `GATHER_ND`
     or `STABLEHLO_REDUCE_WINDOW` depending on where it's decomposed.
     Occurrences: `lraspp.py`'s `aspp2`, and every
     `torchvision.ops.misc.SqueezeExcitation.avgpool` inside
     `mobilenetv3.py`'s SE-enabled `InvertedResidual` blocks (torchvision
     library code, not this repo's source -- can't edit it directly, see
     step 3).
   - `nn.AvgPool2d(..., ceil_mode=True, count_include_pad=False)` ->
     `STABLEHLO_REDUCE_WINDOW`. Occurrence: `decoder.py`'s `AvgPool`.
   - `x.clamp(0., 1.)` (however spelled -- one call or two chained) ->
     `RELU_0_TO_1`. Occurrence: `model.py`'s final `fgr`/`pha` clamp.

   To confirm *which* instance in the graph an unsupported op comes from,
   run the **CPU** benchmark with profiling
   (`./scripts/benchmark.sh cpu ...`, or `benchmark_cpu.sh` directly) on the
   *same* export and grep its log for the op name -- CPU/XNNPACK profiling
   node names carry the full Python module-path breadcrumb (e.g.
   `.../SqueezeExcitation_2/torch.nn.modules.pooling.AdaptiveAvgPool2d_avgpool`),
   which the GPU log's fused op names don't. `./scripts/visualize.sh --tflite
   <export>.tflite` (see `analysis/visualize.py`) renders the same graph as
   an SVG if you'd rather browse it visually than grep a log.

3. **Rewrite with an exactly-equivalent decomposition** using only ops the
   GPU delegate already handles elsewhere in this graph (`MEAN`, `RELU`,
   `SUB`/`ADD`, `AVERAGE_POOL_2D`, ...) -- **only inside `model_gpu/`**,
   never `model/`. Prove the rewrite is exact algebraically before coding it,
   the same way the existing fixes were derived:
   - Global pool: `nn.AdaptiveAvgPool2d(1)` == `x.mean(dim=(-2,-1),
     keepdim=True)` when output size is 1 -- exact, always. Reuse
     `GlobalAvgPool2d` from `lraspp.py` (`from .lraspp import
     GlobalAvgPool2d`) rather than redefining it. For a module you can't
     edit directly (like torchvision's `SqueezeExcitation`), do the swap as
     post-construction module surgery: iterate `self.modules()` after
     `super().__init__(...)` and reassign the `.avgpool` attribute on every
     instance you find (see `mobilenetv3.py`).
   - `nn.AvgPool2d(2, 2, ceil_mode=True, count_include_pad=False)` ==
     plain `nn.AvgPool2d(2, 2)` **only when every pooled dimension stays
     even at each stage** (traced height/width divisible by 8 for 3 stages)
     -- true for this pipeline's target resolutions, but document the
     assumption in a comment since it's not universally exact.
   - `x.clamp(0., 1.)` == `F.relu(x) - F.relu(x - 1)` -- exact for all real
     x (check the three cases: x<=0, 0<x<=1, x>1). Simpler than the
     `1 - relu(1 - relu(x))` form used in an earlier iteration of this fix;
     both work, prefer fewer ops.

4. **Verify the rewrite changed nothing numerically**, in pure PyTorch
   first (fast, no TFLite/adb round-trip):
   ```bash
   ./scripts/verify.sh --tflite <old-export>.tflite  # optional sanity baseline
   ./scripts/compare.sh --variant <variant>
   ```
   `compare.py` runs `RVMWrapper(source="original")` and
   `RVMWrapper(source="gpu")` on identical inputs and prints a PASS/FAIL
   table; expect **exact** (`max_diff=0.000000`) agreement for these kinds
   of algebraic rewrites -- anything else means the rewrite isn't actually
   equivalent.

5. **Re-convert and re-benchmark** to confirm the specific op is gone from
   the GPU error list (repeat step 1). Iterate until you see `INFO:
   Explicitly applied GPU delegate, and the model graph will be completely
   executed by the delegate.` with zero `ERROR:` lines in the log.

## Gotchas

- Never edit `RobustVideoMatting/model/*.py` -- it must stay byte-identical
  to upstream. All fixes go in `RobustVideoMatting/model_gpu/*.py`.
- A module with no learnable parameters (pooling, activations, the clamp
  helper) is always a safe drop-in replacement for `state_dict` loading --
  `Sequential`/attribute indices are unaffected. A rewrite that *adds*
  parameters would break checkpoint loading and needs different handling
  (not needed for any fix so far).
- `benchmark_gpu.sh`'s log only has the full `ERROR:` diagnostics because
  its `adb shell ... 2>&1 | tee` merges stderr in -- if you're piping its
  output through something else ad hoc, keep that redirect or you'll lose
  exactly the lines you need.

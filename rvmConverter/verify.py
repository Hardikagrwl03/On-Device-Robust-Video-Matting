import argparse
import os
import re

# Heavy imports (torch, ai_edge_litert, the model wrapper) are deferred to
# main() so that `--help` works without the conversion deps installed.

VARIANTS = ("resnet50", "mobilenetv3")
SOURCES = ("original", "gpu")
DEFAULT_CHECKPOINT_DIR = "RobustVideoMatting/checkpoints"

# Matches convert.py's default_output() naming convention:
# rvm_<variant>_<height>x<width>_ds_<auto|NNN>.tflite
FILENAME_PATTERN = re.compile(r"rvm_(?P<variant>[a-zA-Z0-9]+)_(?P<height>\d+)x(?P<width>\d+)_ds_(?P<ds>auto|\d+)")


def default_checkpoint(variant: str) -> str:
    return os.path.join(DEFAULT_CHECKPOINT_DIR, f"rvm_{variant}.pth")


def infer_from_filename(tflite_path: str) -> dict:
    """
    Picks variant/height/width/downsample_ratio out of the .tflite filename
    (see FILENAME_PATTERN). Returns {} if the name doesn't match that
    convention -- callers then require the corresponding flag(s) to be passed
    explicitly rather than guessing.
    """
    match = FILENAME_PATTERN.search(os.path.basename(tflite_path))
    if not match:
        return {}

    parsed = {"height": int(match.group("height")), "width": int(match.group("width"))}
    if match.group("variant") in VARIANTS:
        parsed["variant"] = match.group("variant")

    ds = match.group("ds")
    parsed["downsample_ratio"] = None if ds == "auto" else int(ds) / 100.0

    return parsed


def infer_source(tflite_path: str) -> str:
    """
    Picks 'original' or 'gpu' from a directory component of --tflite's path,
    matching convert.py's default --output-dir convention
    (tflite_models/<source>/...). Falls back to 'original' -- RVMWrapper's
    own default -- if the path doesn't clearly say.
    """
    parts = os.path.normpath(tflite_path).split(os.sep)
    matches = [s for s in SOURCES if s in parts]
    return matches[0] if len(matches) == 1 else "original"


def run_pytorch(wrapped_model, tracing_inputs):
    import torch

    with torch.no_grad():
        outputs = wrapped_model(*tracing_inputs)
    return [o.numpy() for o in outputs]


def run_tflite(tflite_path, tracing_inputs):
    from ai_edge_litert.interpreter import Interpreter

    interpreter = Interpreter(model_path=tflite_path)
    interpreter.allocate_tensors()

    # Route through the named "serving_default" signature rather than raw
    # tensor `index` order. A tensor's buffer index reflects internal
    # flatbuffer layout, not necessarily RVMWrapper.forward()'s return-value
    # order: once fgr's branch and pha's branch stopped sharing an identical
    # trailing op sequence, pha's output tensor got allocated a *lower*
    # index than fgr's despite being returned second, silently swapping
    # which output got compared against which. The signature's input/output
    # name lists (args_0.., output_0..) preserve the traced argument/return
    # order instead, regardless of internal buffer layout.
    runner = interpreter.get_signature_runner("serving_default")
    signature = interpreter.get_signature_list()["serving_default"]

    inputs = {name: tensor.numpy() for name, tensor in zip(signature["inputs"], tracing_inputs)}
    outputs = runner(**inputs)

    return [outputs[name] for name in signature["outputs"]]


def compare(name, torch_out, tflite_out, atol):
    import numpy as np

    max_diff = float(np.max(np.abs(torch_out - tflite_out)))
    ok = max_diff <= atol
    status = "PASS" if ok else "FAIL"
    print(
        f"    [{status}] {name}: shape={tflite_out.shape} max_diff={max_diff:.6f} "
        f"mean_torch={np.mean(torch_out):.4f} mean_tflite={np.mean(tflite_out):.4f}"
    )
    return ok


def parse_args():
    parser = argparse.ArgumentParser(
        description="Verify an exported RVM .tflite model against the PyTorch model it was traced from."
    )
    parser.add_argument("--tflite", required=True, help="Path to the exported .tflite model.")
    parser.add_argument(
        "--variant",
        choices=VARIANTS,
        default=None,
        help="Defaults to whichever of {resnet50, mobilenetv3} appears in the --tflite filename.",
    )
    parser.add_argument(
        "--source",
        choices=SOURCES,
        default=None,
        help="Which RobustVideoMatting model tree to build the PyTorch reference from. "
        "Defaults to whichever of {original, gpu} appears in the --tflite path "
        "(falling back to 'original' if neither does).",
    )
    parser.add_argument(
        "--checkpoint",
        default=None,
        help=f"Path to a .pth checkpoint. Defaults to {DEFAULT_CHECKPOINT_DIR}/rvm_<variant>.pth.",
    )
    parser.add_argument(
        "--height", type=int, default=None, help="Input height. Defaults to the value encoded in the --tflite filename."
    )
    parser.add_argument(
        "--width", type=int, default=None, help="Input width. Defaults to the value encoded in the --tflite filename."
    )
    parser.add_argument("--batch-size", type=int, default=1, help="Input batch size (default: 1).")
    parser.add_argument(
        "--downsample-ratio",
        type=float,
        default=None,
        help="Must match the ratio the .tflite was traced with. Defaults to the value encoded in the "
        "--tflite filename (falling back to 1.0 if that can't be determined). Pass <= 0 for auto.",
    )
    parser.add_argument(
        "--atol", type=float, default=1e-2, help="Max allowed per-tensor absolute difference (default: 1e-2)."
    )
    return parser.parse_args()


def main():
    args = parse_args()
    inferred = infer_from_filename(args.tflite)

    variant = args.variant or inferred.get("variant")
    source = args.source or infer_source(args.tflite)
    height = args.height if args.height is not None else inferred.get("height")
    width = args.width if args.width is not None else inferred.get("width")

    if args.downsample_ratio is not None:
        downsample_ratio = args.downsample_ratio if args.downsample_ratio > 0 else None
    elif "downsample_ratio" in inferred:
        downsample_ratio = inferred["downsample_ratio"]
    else:
        downsample_ratio = 1.0

    missing = [
        flag
        for flag, value in [("--variant", variant), ("--height", height), ("--width", width)]
        if value is None
    ]
    if missing:
        raise SystemExit(
            f"Could not infer {', '.join(missing)} from filename {os.path.basename(args.tflite)!r}; "
            f"pass {'them' if len(missing) > 1 else 'it'} explicitly."
        )

    passed_explicitly = {
        "--variant": args.variant is not None,
        "--source": args.source is not None,
        "--height": args.height is not None,
        "--width": args.width is not None,
        "--downsample-ratio": args.downsample_ratio is not None,
    }
    inferred_flags = [flag for flag, was_explicit in passed_explicitly.items() if not was_explicit]
    if inferred_flags:
        print(f"inferred {', '.join(inferred_flags)} from {os.path.basename(args.tflite)!r}")

    import torch

    from wrapper import RVMWrapper

    checkpoint = args.checkpoint or default_checkpoint(variant)

    wrapped_model = RVMWrapper(
        variant=variant,
        checkpoint=checkpoint,
        downsample_ratio=downsample_ratio,
        height=height,
        width=width,
        source=source,
    ).eval()
    print(f"resolved downsample_ratio: {wrapped_model.downsample_ratio}")

    input_frame = torch.rand(args.batch_size, height, width, 3, dtype=torch.float32) * 255
    r1, r2, r3, r4 = wrapped_model.init_recurrent_state(input_frame)
    tracing_inputs = (input_frame, r1, r2, r3, r4)

    torch_outputs = run_pytorch(wrapped_model, tracing_inputs)
    tflite_outputs = run_tflite(args.tflite, tracing_inputs)

    if len(torch_outputs) != len(tflite_outputs):
        raise SystemExit(
            f"Output count mismatch: PyTorch returned {len(torch_outputs)}, "
            f"tflite returned {len(tflite_outputs)}."
        )

    names = ["fgr", "pha", "r1", "r2", "r3", "r4"]
    print(f"\nComparing {args.tflite} against PyTorch ({variant}, source={source}, atol={args.atol}):")
    results = [compare(name, t, e, args.atol) for name, t, e in zip(names, torch_outputs, tflite_outputs)]

    if all(results):
        print("\nAll outputs match within tolerance.")
    else:
        raise SystemExit("\nSome outputs exceed tolerance.")


if __name__ == "__main__":
    main()

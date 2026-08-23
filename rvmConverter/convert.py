import argparse
import os

# Heavy imports (torch, litert_torch, the model wrapper) are deferred to
# convert_variant() so that `--help` works without the conversion deps
# installed.

VARIANTS = ("resnet50", "mobilenetv3")
SOURCES = ("original", "gpu")

DEFAULT_CHECKPOINT_DIR = "RobustVideoMatting/checkpoints"


def default_checkpoint(variant: str) -> str:
    return os.path.join(DEFAULT_CHECKPOINT_DIR, f"rvm_{variant}.pth")


def default_output_dir(source: str) -> str:
    return os.path.join("tflite_models", source)


def format_downsample_ratio(downsample_ratio: float) -> str:
    """
    Render downsample_ratio for a filename with no literal '.': None (auto,
    resolved per-input at trace time) becomes 'auto'; otherwise the ratio is
    shown as a whole-number percentage of the traced resolution, e.g. 1.0
    (no shape change) -> '100', 0.5 (half resolution) -> '050'.
    """
    if downsample_ratio is None:
        return "auto"
    return f"{round(downsample_ratio * 100):03d}"


def default_output(variant: str, output_dir: str, height: int, width: int, downsample_ratio: float) -> str:
    return os.path.join(
        output_dir,
        f"rvm_{variant}_{height}x{width}_ds_{format_downsample_ratio(downsample_ratio)}.tflite",
    )


def convert(
    variant: str,
    source: str,
    checkpoint: str,
    output: str,
    batch_size: int,
    height: int,
    width: int,
    downsample_ratio: float,
    verify: bool,
):
    import litert_torch
    import numpy as np
    import torch

    from wrapper import RVMWrapper

    print(f"\n -- Processing Variant: {variant} (source={source}) --")
    print(f"    checkpoint: {checkpoint}")
    print(f"    output:     {output}")
    print(f"    resolution: {height}x{width}, downsample_ratio={downsample_ratio}")

    os.makedirs(os.path.dirname(output) or ".", exist_ok=True)

    # downsample_ratio=1 keeps the traced graph at a single, fixed
    # resolution: TFLite export needs static shapes, so the model's dynamic
    # downsample-then-refine path is only exercised if the caller opts in.
    wrapped_model = RVMWrapper(
        variant=variant,
        checkpoint=checkpoint,
        downsample_ratio=downsample_ratio,
        height=height,
        width=width,
        source=source,
    ).eval()

    print(f"    resolved downsample_ratio: {wrapped_model.downsample_ratio}")

    input_frame = torch.rand(batch_size, height, width, 3, dtype=torch.float32) * 255
    r1_init, r2_init, r3_init, r4_init = wrapped_model.init_recurrent_state(input_frame)
    tracing_inputs = (input_frame, r1_init, r2_init, r3_init, r4_init)

    fgr, alpha, h1, h2, h3, h4 = wrapped_model(*tracing_inputs)
    print("    fgr/alpha/state shapes:", fgr.shape, alpha.shape, h1.shape, h2.shape, h3.shape, h4.shape)

    print("    tracing architecture graph into flatbuffer")
    edge_model = litert_torch.convert(wrapped_model, tracing_inputs)

    if verify:
        edge_fgr, edge_alpha, *_ = edge_model(*tracing_inputs)
        val = fgr.detach().numpy()
        print("    torch mean:", np.mean(val), "| edge mean:", np.mean(edge_fgr))
        print("    edge has NaN:", np.any(np.isnan(edge_fgr)))
        print("    max diff:", np.max(np.abs(val - edge_fgr)))
        print("    allclose(atol=1e-4):", np.allclose(val, edge_fgr, atol=1e-4))

    edge_model.export(output)
    print(f"    conversion successful: {output}")


def parse_args():
    parser = argparse.ArgumentParser(description="Convert Robust Video Matting checkpoints to TFLite.")
    parser.add_argument(
        "--variant",
        choices=[*VARIANTS, "all"],
        default="all",
        help="Backbone variant to convert, or 'all' to convert every known variant (default: all).",
    )
    parser.add_argument(
        "--source",
        choices=SOURCES,
        default="original",
        help="Which RobustVideoMatting model tree to trace from: 'original' imports "
        "RobustVideoMatting.model (unmodified upstream), 'gpu' imports "
        "RobustVideoMatting.model_gpu (same architecture, edited to avoid ops the "
        "TFLite GPU delegate doesn't support). Also picks the default --output-dir "
        "(tflite_models/<source>) (default: original).",
    )
    parser.add_argument(
        "--checkpoint",
        type=str,
        default=None,
        help=f"Path to a .pth checkpoint. Only valid with a single --variant; "
        f"defaults to {DEFAULT_CHECKPOINT_DIR}/rvm_<variant>.pth.",
    )
    parser.add_argument(
        "--output",
        type=str,
        default=None,
        help="Path to write the .tflite file. Only valid with a single --variant; "
        "defaults to a name derived from --output-dir and the variant.",
    )
    parser.add_argument(
        "--output-dir",
        type=str,
        default=None,
        help="Directory for default output filenames (default: tflite_models/<source>).",
    )
    parser.add_argument("--height", type=int, default=720, help="Traced input height (default: 720).")
    parser.add_argument("--width", type=int, default=1280, help="Traced input width (default: 1280).")
    parser.add_argument("--batch-size", type=int, default=1, help="Traced input batch size (default: 1).")
    parser.add_argument(
        "--downsample-ratio",
        type=float,
        default=1.0,
        help="Model downsample_ratio to bake into the traced graph. Pass <= 0 to "
        "auto-compute one from --height/--width instead (default: 1.0, i.e. no downsampling).",
    )
    parser.add_argument(
        "--skip-verify",
        action="store_true",
        help="Skip running the exported edge model to numerically compare it against PyTorch.",
    )
    args = parser.parse_args()

    if args.variant == "all" and (args.checkpoint or args.output):
        parser.error("--checkpoint/--output require a single --variant, not 'all'.")

    return args


def main():
    args = parse_args()
    variants = list(VARIANTS) if args.variant == "all" else [args.variant]
    downsample_ratio = args.downsample_ratio if args.downsample_ratio > 0 else None
    output_dir = args.output_dir or default_output_dir(args.source)

    for variant in variants:
        convert(
            variant=variant,
            source=args.source,
            checkpoint=args.checkpoint or default_checkpoint(variant),
            output=args.output or default_output(variant, output_dir, args.height, args.width, downsample_ratio),
            batch_size=args.batch_size,
            height=args.height,
            width=args.width,
            downsample_ratio=downsample_ratio,
            verify=not args.skip_verify,
        )


if __name__ == "__main__":
    main()
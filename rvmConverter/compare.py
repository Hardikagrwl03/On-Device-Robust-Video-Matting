import argparse
import os

# Heavy imports (torch, the model wrapper) are deferred to main() so that
# `--help` works without the conversion deps installed.

VARIANTS = ("resnet50", "mobilenetv3")
DEFAULT_CHECKPOINT_DIR = "RobustVideoMatting/checkpoints"


def default_checkpoint(variant: str) -> str:
    return os.path.join(DEFAULT_CHECKPOINT_DIR, f"rvm_{variant}.pth")


def compare(name, original_out, gpu_out, atol):
    import numpy as np

    max_diff = float(np.max(np.abs(original_out - gpu_out)))
    ok = max_diff <= atol
    status = "PASS" if ok else "FAIL"
    print(
        f"    [{status}] {name}: shape={original_out.shape} max_diff={max_diff:.6f} "
        f"mean_original={np.mean(original_out):.4f} mean_gpu={np.mean(gpu_out):.4f}"
    )
    return ok


def parse_args():
    parser = argparse.ArgumentParser(
        description="Compare RVMWrapper(source='original') against RVMWrapper(source='gpu') on identical inputs."
    )
    parser.add_argument("--variant", required=True, choices=VARIANTS)
    parser.add_argument(
        "--checkpoint", default=None, help=f"Path to a .pth checkpoint. Defaults to {DEFAULT_CHECKPOINT_DIR}/rvm_<variant>.pth."
    )
    parser.add_argument("--height", type=int, default=720, help="Input height (default: 720).")
    parser.add_argument("--width", type=int, default=1280, help="Input width (default: 1280).")
    parser.add_argument("--batch-size", type=int, default=1, help="Input batch size (default: 1).")
    parser.add_argument(
        "--downsample-ratio", type=float, default=1.0, help="Pass <= 0 for auto (default: 1.0)."
    )
    parser.add_argument(
        "--atol", type=float, default=1e-5, help="Max allowed per-tensor absolute difference (default: 1e-5)."
    )
    return parser.parse_args()


def main():
    args = parse_args()

    import torch

    from wrapper import RVMWrapper

    checkpoint = args.checkpoint or default_checkpoint(args.variant)
    downsample_ratio = args.downsample_ratio if args.downsample_ratio > 0 else None

    torch.manual_seed(0)
    input_frame = torch.rand(args.batch_size, args.height, args.width, 3, dtype=torch.float32) * 255

    original = RVMWrapper(
        variant=args.variant,
        checkpoint=checkpoint,
        downsample_ratio=downsample_ratio,
        height=args.height,
        width=args.width,
        source="original",
    ).eval()
    gpu = RVMWrapper(
        variant=args.variant,
        checkpoint=checkpoint,
        downsample_ratio=downsample_ratio,
        height=args.height,
        width=args.width,
        source="gpu",
    ).eval()

    r1, r2, r3, r4 = original.init_recurrent_state(input_frame)
    tracing_inputs = (input_frame, r1, r2, r3, r4)

    with torch.no_grad():
        original_outputs = [o.numpy() for o in original(*tracing_inputs)]
        gpu_outputs = [o.numpy() for o in gpu(*tracing_inputs)]

    names = ["fgr", "pha", "r1", "r2", "r3", "r4"]
    print(f"\nComparing model_gpu against model ({args.variant}, atol={args.atol}):")
    results = [compare(name, a, b, args.atol) for name, a, b in zip(names, original_outputs, gpu_outputs)]

    if all(results):
        print("\nAll outputs match within tolerance.")
    else:
        raise SystemExit("\nSome outputs exceed tolerance.")


if __name__ == "__main__":
    main()

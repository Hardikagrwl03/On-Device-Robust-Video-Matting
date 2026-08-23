#!/usr/bin/env bash
# Verify an exported .tflite model against the PyTorch model it was traced
# from. Thin wrapper around verify.py: activates the rvm-convert conda env
# and runs from the project root, so --tflite/--checkpoint paths can be given
# relative to the project root regardless of where you call this from.
#
# Usage:   ./scripts/verify.sh --tflite <path/to/model.tflite> [verify.py options]
# Example: ./scripts/verify.sh --tflite tflite_models/gpu/rvm_mobilenetv3_720x1280_ds_100.tflite
# --variant/--height/--width/--downsample-ratio can usually be left out --
# they're inferred from the filename. Full option list:
#          ./scripts/verify.sh --help
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

source "$(conda info --base)/etc/profile.d/conda.sh"
conda activate rvm-convert

python verify.py "$@"

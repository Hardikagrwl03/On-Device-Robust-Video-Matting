#!/usr/bin/env bash
# Convert an RVM checkpoint to TFLite. Thin wrapper around convert.py:
# activates the rvm-convert conda env and runs from the project root, so
# paths work regardless of where you call this from.
#
# Usage:   ./scripts/convert.sh [convert.py options]
# Example: ./scripts/convert.sh --variant mobilenetv3 --source gpu --downsample-ratio -1
# Full option list (variant, source, resolution, output paths, ...):
#          ./scripts/convert.sh --help
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

source "$(conda info --base)/etc/profile.d/conda.sh"
conda activate rvm-convert

python convert.py "$@"

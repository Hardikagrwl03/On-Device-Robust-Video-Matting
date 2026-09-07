#!/usr/bin/env bash
# Compare RVMWrapper(source="original") against RVMWrapper(source="gpu") on
# identical inputs -- pure PyTorch, no TFLite, no device. Thin wrapper around
# compare.py: activates the rvm-convert conda env and runs from the project
# root, so --checkpoint paths can be given relative to the project root
# regardless of where you call this from.
#
# Usage:   ./scripts/compare.sh --variant {resnet50,mobilenetv3} [compare.py options]
# Example: ./scripts/compare.sh --variant mobilenetv3
#
# This is the tool to run while editing RobustVideoMatting/model_gpu/ -- it
# is what proves a GPU-delegate-compatibility rewrite didn't change the
# model's behaviour. Both wrappers are built from the same checkpoint and run
# on the same random input/recurrent-state; every fix made so far reports
# max_diff=0.000000 (the default --atol is 1e-5, tight enough to catch a real
# regression but loose enough for floating-point op-order noise).
#
# Passing here is a precondition, not a completion: it says the edit is
# behaviour-preserving, not that it helped the GPU delegate. Confirm that by
# re-converting (--source gpu) and running ./scripts/benchmark.sh gpu ...
#
# Full option list:
#          ./scripts/compare.sh --help
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

source "$(conda info --base)/etc/profile.d/conda.sh"
conda activate rvm-convert

python compare.py "$@"

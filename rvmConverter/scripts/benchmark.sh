#!/usr/bin/env bash
# Benchmark a .tflite model on an Android device's CPU or GPU. Thin wrapper
# around benchmark/benchmark_cpu.sh and benchmark/benchmark_gpu.sh: picks the
# right one for you and runs from the project root, so the model path can be
# given relative to the project root regardless of where you call this from.
#
# Usage:   ./scripts/benchmark.sh <cpu|gpu> <model.tflite> [device_name_or_id]
# Example: ./scripts/benchmark.sh gpu tflite_models/gpu/rvm_mobilenetv3_720x1280_ds_100.tflite 192.168.1.59:45893
#
# device_name_or_id is optional if exactly one device is connected via adb.
# Results are logged under benchmark/<tflite_models subfolder>/<cpu|gpu>/.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

BACKEND="${1:-}"

if [ "$BACKEND" = "-h" ] || [ "$BACKEND" = "--help" ]; then
    echo "Usage: $0 <cpu|gpu> <model.tflite> [device_name_or_id]"
    echo "  Benchmarks a .tflite model on an Android device via adb + the TFLite benchmark_model binary."
    echo "  device_name_or_id is optional if exactly one device is connected."
    exit 0
fi

if [ "$BACKEND" != "cpu" ] && [ "$BACKEND" != "gpu" ]; then
    echo "Usage: $0 <cpu|gpu> <model.tflite> [device_name_or_id]" >&2
    exit 1
fi
shift

cd "$PROJECT_ROOT"
exec "./benchmark/benchmark_${BACKEND}.sh" "$@"

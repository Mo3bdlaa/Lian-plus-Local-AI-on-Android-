#!/usr/bin/env bash
# Initialises (or repairs) the vendored engine sources.
#
# Equivalent to cloning with --recurse-submodules; useful when a clone was made
# without it, or when a submodule checkout has been left in a bad state.
set -euo pipefail

cd "$(dirname "$0")/.."

echo "Fetching native engine sources…"
git submodule update --init --recursive --depth 1

for dir in native/llama.cpp native/stable-diffusion.cpp; do
    if [ ! -f "$dir/CMakeLists.txt" ]; then
        echo "error: $dir is still empty" >&2
        exit 1
    fi
    printf '%-32s %s\n' "$dir" "$(git -C "$dir" rev-parse --short HEAD)"
done

echo "Done. Build with: ./gradlew assembleRelease"

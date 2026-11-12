#!/bin/bash
# vm.c to vm.wasm, as rexx.wasm is built (build.sh at the root): wasm32-wasip1-threads,
# the memory imported and shared, at most 2 GB (so rexxj's memories take their relaxed
# check, as for rexx.wasm), -O2. And vm-native, the same C natively, for reference.
#   prototypes/wasm-bench/build.sh [WORK]     WORK: where build.sh put wasi-sdk
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
W=${1:-/home/claude/work}/wasi-sdk-34.0-x86_64-linux
"$W/bin/clang" --target=wasm32-wasip1-threads -pthread -O2 \
  -Wl,--import-memory -Wl,--export-memory -Wl,--max-memory=2147483648 \
  -o "$HERE/vm.wasm" "$HERE/vm.c"
cc -O2 -o "$HERE/vm-native" "$HERE/vm.c"
echo "built: $HERE/vm.wasm, $HERE/vm-native"

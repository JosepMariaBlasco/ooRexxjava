#!/bin/sh
# Prepares the directory (argument 1) that is preopened as "/" for symlink.wasm.
set -e
mkdir -p "$1/sub" && echo hello > "$1/sub/target.txt"
ln -sfn ../sub/target.txt "$1/sub/deeplink"

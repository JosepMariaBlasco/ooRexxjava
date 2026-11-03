#!/bin/sh
# MemBench, the kinds alternated in separate JVMs, ROUNDS times (README.md):
#   ./run.sh [JAVA] [ROUNDS] [SECONDS]
J=${1:-java}; R=${2:-3}; S=${3:-10}
cd "$(dirname "$0")"
for w in loop vm; do
  for i in $(seq "$R"); do
    for k in unsafe mapped arena; do
      "$J" --sun-misc-unsafe-memory-access=allow MemBench.java "$k" "$w" "$S" 2>/dev/null | tail -1
    done
  done
done

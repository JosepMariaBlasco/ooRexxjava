#!/bin/bash
# Builds the reproductions with wasi-sdk and runs them on Chicory (pom.xml's version, or
# CHICORY_VERSION, e.g. 999-SNAPSHOT for a local build of main).
#   WASI_SDK=/path/to/wasi-sdk host/chicory-reports/run.sh
# Needs Java 11+, Maven, Python 3. A patched compiler jar can be given as PATCHED_COMPILER.
set -e
cd "$(dirname "$0")"
SDK=${WASI_SDK:?set WASI_SDK to a wasi-sdk directory}
CC="$SDK/bin/clang --target=wasm32-wasip1 --sysroot=$SDK/share/wasi-sysroot -O2 -Wl,--strip-all"
OUT=${OUT:-$(mktemp -d)}
python3 c/gen_indirect.py 2000      # writes c/indirect.c
for p in rename append symlink indirect; do $CC -o "$OUT/$p.wasm" c/$p.c; done
mvn -q -B package dependency:build-classpath -Dmdep.outputFile="$OUT/cp.txt" ${CHICORY_VERSION:+-Dchicory.version=$CHICORY_VERSION}
echo "Chicory: $(tr ':' '\n' < "$OUT/cp.txt" | sed -n 's:.*/wasi/\([^/]*\)/.*:\1:p')"
CP=target/chicory-repro-1.jar:$(cat "$OUT/cp.txt")
run() { env -u JAVA_TOOL_OPTIONS java "$@"; }
for p in rename append; do d=$(mktemp -d); echo "== $p"; run -cp "$CP" repro.Run "$OUT/$p.wasm" "$d" || true; done
d=$(mktemp -d); sh c/symlink-setup.sh "$d"; echo "== symlink"; run -cp "$CP" repro.Run "$OUT/symlink.wasm" "$d" || true
echo "  on the host, sub/wasilink -> $(readlink "$d/sub/wasilink")"
d=$(mktemp -d)
echo "== indirect, compiler, default JVM";               run -cp "$CP" repro.Run --compile "$OUT/indirect.wasm" "$d" 5000000
echo "== indirect, compiler, -XX:-DontCompileHugeMethods"; run -XX:-DontCompileHugeMethods -cp "$CP" repro.Run --compile "$OUT/indirect.wasm" "$d" 5000000
if [ -n "$PATCHED_COMPILER" ]; then
  CP2=target/chicory-repro-1.jar:$PATCHED_COMPILER:$(tr ':' '\n' < "$OUT/cp.txt" | grep -v '/compiler/' | paste -sd:)
  echo "== indirect, patched compiler, default JVM"; run -cp "$CP2" repro.Run --compile "$OUT/indirect.wasm" "$d" 5000000
fi

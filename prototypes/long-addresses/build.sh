#!/bin/bash
# Two experimental copies of a rexxj dist whose translated code computes WebAssembly's
# effective address in long (README.md):
#   prototypes/long-addresses/build.sh DIST OUT
#     OUT/dist-long    the 12 plain helpers call LongAddr (rexx.wasm's module and the
#                      compiler's template, so also modules translated at run time)
#     OUT/dist-inline  rexx.wasm's module only, the effective address computed in the
#                      helper itself, FlatMemory's long accessor called directly
# Needs the repository's checkout (host/ sources), JDK 22+ for MappedMemory (javac
# from /usr/lib/jvm/java-25-openjdk-amd64, or JAVAC22), and ASM in ~/.m2.
set -e
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
DIST=$(cd "${1:?DIST}" && pwd); OUT=${2:?OUT}; mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
JAVAC22=${JAVAC22:-/usr/lib/jvm/java-25-openjdk-amd64/bin/javac}
ASM=$(ls ~/.m2/repository/org/ow2/asm/asm/*/asm-*.jar | grep -v sources | sort -V | tail -1)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
# the sources with FlatMemory's long accessors
mkdir -p "$T/src" && cp -r "$ROOT/host/src/main/java" "$ROOT/host/src/main/java22" "$T/src/"
(cd "$T/src" && patch -s -p4 < "$HERE/flatmemory-long.patch")
cp "$HERE/LongAddr.java" "$T/src/java/rexxjvm/"
J=$DIST/rexxj.jar
mkdir -p "$T/cls" "$T/cls22" "$T/tool"
javac -nowarn -XDignore.symbol.file --release 11 -cp "$J" -d "$T/cls" \
  "$T/src/java/rexxjvm/FlatMemory.java" "$T/src/java/rexxjvm/UnsafeMemory.java" "$T/src/java/rexxjvm/LongAddr.java" 2>/dev/null
"$JAVAC22" -nowarn --release 22 -cp "$T/cls:$J" -d "$T/cls22" "$T/src/java22/rexxjvm/MappedMemory.java"
javac -cp "$ASM" -d "$T/tool" "$HERE/PatchLongAddr.java" "$HERE/PatchLongAddrInline.java"
for v in long inline; do
  rm -rf "$OUT/dist-$v"; cp -a "$DIST" "$OUT/dist-$v"
  W=$T/jar-$v; mkdir -p "$W/rexxjvm" "$W/META-INF/versions/22/rexxjvm"
  (cd "$W" && unzip -q "$J" rexxjvm/RexxModuleMachineShaded.class run/endive/compiler/internal/Shaded.class)
  if [ $v = long ]; then
    java -cp "$ASM:$T/tool" PatchLongAddr "$W/rexxjvm/RexxModuleMachineShaded.class" "$W/rexxjvm/RexxModuleMachineShaded.class"
    java -cp "$ASM:$T/tool" PatchLongAddr "$W/run/endive/compiler/internal/Shaded.class" "$W/run/endive/compiler/internal/Shaded.class"
  else
    java -cp "$ASM:$T/tool" PatchLongAddrInline "$W/rexxjvm/RexxModuleMachineShaded.class" "$W/rexxjvm/RexxModuleMachineShaded.class"
    rm "$W/run/endive/compiler/internal/Shaded.class"
  fi
  cp "$T/cls/rexxjvm/"*.class "$W/rexxjvm/"
  cp "$T/cls22/rexxjvm/MappedMemory"*.class "$W/META-INF/versions/22/rexxjvm/"
  (cd "$W" && zip -q -r "$OUT/dist-$v/rexxj.jar" .)
done
echo "built $OUT/dist-long and $OUT/dist-inline"

#!/bin/bash
# Installs com.dylibso.chicory:compiler:<chicory>-orx1 in the local Maven repository:
# Chicory's compiler with its call_indirect dispatch in smaller methods, so that HotSpot
# JIT-compiles them (host/chicory-patch/README.md). host/pom.xml uses it, both to translate
# rexx.wasm at build time and in rexxj.jar. build.sh and build-test.sh run this first;
# it does nothing if that version is already installed.
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
V=$(sed -n 's:.*<chicory.version>\(.*\)</chicory.version>.*:\1:p' "$HERE/host/pom.xml")
P=$(sed -n 's:.*<chicory.compiler.version>\(.*\)</chicory.compiler.version>.*:\1:p' "$HERE/host/pom.xml")
REPO=${MAVEN_REPO:-$HOME/.m2/repository}
[ -f "$REPO/com/dylibso/chicory/compiler/$P/compiler-$P.jar" ] && exit 0
mvn -q -B dependency:get -Dartifact=com.dylibso.chicory:compiler:$V
ORIG=$REPO/com/dylibso/chicory/compiler/$V/compiler-$V.jar
ASMV=$(sed -n 's:.*<asm.version>\(.*\)</asm.version>.*:\1:p' "$REPO/com/dylibso/chicory/chicory/$V/chicory-$V.pom")
mvn -q -B dependency:get -Dartifact=org.ow2.asm:asm:$ASMV
ASM=$REPO/org/ow2/asm/asm/$ASMV/asm-$ASMV.jar
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
javac -d "$T" -cp "$ASM" "$HERE/host/chicory-patch/PatchCompiler.java"
C=com/dylibso/chicory/compiler/internal/Compiler.class
(cd "$T" && unzip -q "$ORIG" "$C" && java -cp "$ASM:$T" PatchCompiler "$C" "$C" 128 512 \
   && cp "$ORIG" compiler.jar && zip -q compiler.jar "$C")
sed "s:^\(  <artifactId>compiler</artifactId>\)$:\1\n  <version>$P</version>:" \
    "$REPO/com/dylibso/chicory/compiler/$V/compiler-$V.pom" > "$T/compiler.pom"
grep -q "<version>$P</version>" "$T/compiler.pom"
mvn -q -B install:install-file -Dfile="$T/compiler.jar" -DpomFile="$T/compiler.pom"
echo "installed com.dylibso.chicory:compiler:$P"

#!/bin/bash
# Installs run.endive:compiler:<endive>-orx1 in the local Maven repository: Endive's
# compiler (Endive: Chicory's successor) with its call_indirect dispatch in smaller methods, so that HotSpot
# JIT-compiles them (host/chicory-patch/README.md). host/pom.xml uses it, both to translate
# rexx.wasm at build time and in rexxj.jar. build.sh and build-test.sh run this first;
# it does nothing if that version is already installed.
set -e
HERE=$(cd "$(dirname "$0")/.." && pwd)
V=$(sed -n 's:.*<endive.version>\(.*\)</endive.version>.*:\1:p' "$HERE/host/pom.xml")
P=$(sed -n 's:.*<endive.compiler.version>\(.*\)</endive.compiler.version>.*:\1:p' "$HERE/host/pom.xml")
REPO=${MAVEN_REPO:-$HOME/.m2/repository}
[ -f "$REPO/run/endive/compiler/$P/compiler-$P.jar" ] && exit 0
mvn -q -B dependency:get -Dartifact=run.endive:compiler:$V
ORIG=$REPO/run/endive/compiler/$V/compiler-$V.jar
ASMV=$(sed -n 's:.*<asm.version>\(.*\)</asm.version>.*:\1:p' "$REPO/run/endive/endive/$V/endive-$V.pom")
mvn -q -B dependency:get -Dartifact=org.ow2.asm:asm:$ASMV
ASM=$REPO/org/ow2/asm/asm/$ASMV/asm-$ASMV.jar
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
javac -d "$T" -cp "$ASM" "$HERE/host/chicory-patch/PatchCompiler.java"
C=run/endive/compiler/internal/Compiler.class
(cd "$T" && unzip -q "$ORIG" "$C" && java -cp "$ASM:$T" PatchCompiler "$C" "$C" 128 512 \
   && cp "$ORIG" compiler.jar && zip -q compiler.jar "$C")
sed "s:^\(  <artifactId>compiler</artifactId>\)$:\1\n  <version>$P</version>:" \
    "$REPO/run/endive/compiler/$V/compiler-$V.pom" > "$T/compiler.pom"
grep -q "<version>$P</version>" "$T/compiler.pom"
mvn -q -B install:install-file -Dfile="$T/compiler.jar" -DpomFile="$T/compiler.pom"
echo "installed run.endive:compiler:$P"

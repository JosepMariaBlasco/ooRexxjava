#!/bin/bash
# Rebuild ooRexx for WASI (wasm32-wasip1-threads) and the Chicory launcher.
# Needs: svn, cmake, make, Java 11+ and Maven, network for wasi-sdk 34 and Maven Central.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${1:-$HOME/oorexx-jvm}
mkdir -p "$WORK" && cd "$WORK"
[ -d wasi-sdk-34.0-x86_64-linux ] || { curl -sSLf -o wasi-sdk.tar.gz https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-34/wasi-sdk-34.0-x86_64-linux.tar.gz; tar xzf wasi-sdk.tar.gz; }
W=$WORK/wasi-sdk-34.0-x86_64-linux
[ -d oorexx ] || svn export -q -r 13289 https://svn.code.sf.net/p/oorexx/code-0/main/trunk oorexx
[ -d oorexxwasm ] || git clone -q --depth 1 https://github.com/JosepMariaBlasco/oorexxwasm
cd oorexx
patch -p1 -N < ../oorexxwasm/patches/0001-wasm-build-and-base-runtime.patch || true
patch -p1 -N < "$HERE/0001-wasi-on-top-of-oorexxwasm-0001.patch" || true
cd ..
# the Java launcher (Chicory 1.7.5)
(cd "$HERE/host" && mvn -q -B package -DskipTests)
JAR=$HERE/host/target/rexxjvm-0.1.jar
printf '#!/bin/sh\nexec java -Xss16m -jar %s "$@"\n' "$JAR" > runwasm; chmod +x runwasm
C=$HERE/compat
"$W/bin/clang" --target=wasm32-wasip1-threads -pthread -O2 -c "$C/orx_wasi_stubs.c" -o stubs.o
EMU="-D_WASI_EMULATED_SIGNAL -D_WASI_EMULATED_GETPID -D_WASI_EMULATED_PROCESS_CLOCKS -D_WASI_EMULATED_MMAN"
CF="-isystem $C -include $C/orx_wasi_compat.h -fwasm-exceptions -mllvm -wasm-use-legacy-eh=false $EMU"
LF="$WORK/stubs.o -fwasm-exceptions -lunwind -lwasi-emulated-signal -lwasi-emulated-getpid -lwasi-emulated-process-clocks -lwasi-emulated-mman -Wl,--max-memory=2147483648 -Wl,-z,stack-size=8388608"
sed "s|^\tset(WASI_SDK_PREFIX .*|\tset(WASI_SDK_PREFIX $W)|" "$HERE/wasi-p1-threads.cmake" > toolchain.cmake
# ORX_ENABLE_IPO=OFF: with LTO the final code generation loses -fwasm-exceptions and every catch
cmake -S oorexx -B build -DCMAKE_TOOLCHAIN_FILE="$WORK/toolchain.cmake" -DCMAKE_BUILD_TYPE=Release \
  -DORX_ENABLE_IPO=OFF -DCMAKE_C_FLAGS="$CF" -DCMAKE_CXX_FLAGS="$CF" -DCMAKE_EXE_LINKER_FLAGS="$LF" \
  -DCMAKE_CROSSCOMPILING_EMULATOR="$WORK/runwasm"
cmake --build build --target rexx_exe rexx_img -j"$(nproc)"
mkdir -p dist && cp build/bin/rexx dist/rexx.wasm && cp build/bin/rexx.img dist/
printf '#!/bin/sh\nD=$(dirname "$(readlink -f "$0")")\nPATH="$D:$PATH" exec java -Xss16m -jar %s "$D/rexx.wasm" "$@"\n' "$JAR" > dist/rexxj
chmod +x dist/rexxj
echo "done: $WORK/dist/rexxj program.rex"

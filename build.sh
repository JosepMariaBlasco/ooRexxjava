#!/bin/bash
# Rebuild ooRexx for WASI (wasm32-wasip1-threads) and the Chicory launcher.
# Needs: svn, cmake, make, Java 11+ and Maven, network for wasi-sdk 34 and Maven Central.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${1:-$HOME/oorexx-jvm}
mkdir -p "$WORK" && cd "$WORK"
[ -d wasi-sdk-34.0-x86_64-linux ] || { curl -sSLf -o wasi-sdk.tar.gz https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-34/wasi-sdk-34.0-x86_64-linux.tar.gz; tar xzf wasi-sdk.tar.gz; }
W=$WORK/wasi-sdk-34.0-x86_64-linux
# oorexxwasm's generic WebAssembly layer (its 0001, the only patch a WASI build applies),
# from a fixed commit (its HEAD moves with the family's revision)
OOREXXWASM_REV=62f59308e98f105116b7935e6678a245d86e8eb0
if [ "$(git -C oorexxwasm rev-parse HEAD 2>/dev/null)" != "$OOREXXWASM_REV" ]; then
  rm -rf oorexxwasm && git init -q oorexxwasm
  git -C oorexxwasm fetch -q --depth 1 https://github.com/JosepMariaBlasco/oorexxwasm "$OOREXXWASM_REV"
  git -C oorexxwasm checkout -q FETCH_HEAD
fi
# the patches only on a fresh export: applying them again to a patched tree breaks it
if [ ! -d oorexx ]; then
  svn export -q -r 13346 https://svn.code.sf.net/p/oorexx/code-0/main/trunk oorexx.tmp
  (cd oorexx.tmp && patch -p1 -s < ../oorexxwasm/patches/0001-wasm-generic-layer.patch \
                 && patch -p1 -s < "$HERE/0001-wasi-on-top-of-oorexxwasm-0001.patch")
  mv oorexx.tmp oorexx
fi
# the Java launcher (Chicory 1.7.5)
(cd "$HERE/host" && mvn -q -B clean package -DskipTests)
JAR=$HERE/host/target/rexxjvm-0.1.jar
printf '#!/bin/sh\nexec java -jar %s "$@"\n' "$JAR" > runwasm; chmod +x runwasm
C=$HERE/compat
"$W/bin/clang" --target=wasm32-wasip1-threads -pthread -O2 -isystem "$C" -c "$C/orx_wasi_stubs.c" -o stubs.o
# the host functions of SysWasmHost.hpp over the launcher's imports (commands, paths)
"$W/bin/clang" --target=wasm32-wasip1-threads -pthread -O2 -c "$C/orx_wasi_host.c" -o host.o
# file system calls done by the launcher instead of WASI (stat, access, chmod, rename, utimes)
"$W/bin/clang" --target=wasm32-wasip1-threads -pthread -O2 -D_GNU_SOURCE -c "$C/orx_wasi_fs.c" -o fs.o
# IPv4 sockets for rxsock through the launcher (orx_wasi_net.h redirects rxsock's calls)
"$W/bin/clang" --target=wasm32-wasip1-threads -pthread -O2 -D_GNU_SOURCE -isystem "$C" -c "$C/orx_wasi_net.c" -o net.o
EMU="-D_WASI_EMULATED_SIGNAL -D_WASI_EMULATED_GETPID -D_WASI_EMULATED_PROCESS_CLOCKS -D_WASI_EMULATED_MMAN"
CF="-isystem $C -include $C/orx_wasi_compat.h -fwasm-exceptions -mllvm -wasm-use-legacy-eh=false $EMU"
# the embedded mode (rexx --orx-wasm-embedded): the interpreter hosted by Java (rexxjvm.Rexx)
"$W/bin/clang++" --target=wasm32-wasip1-threads -pthread -O2 $CF -I oorexx/api -I oorexx/api/platform/unix \
  -c "$C/orx_wasi_embed.cpp" -o embed.o
LF="$WORK/stubs.o $WORK/host.o $WORK/fs.o $WORK/net.o $WORK/embed.o -fwasm-exceptions -lunwind -lwasi-emulated-signal -lwasi-emulated-getpid -lwasi-emulated-process-clocks -lwasi-emulated-mman -Wl,--max-memory=2147483648 -Wl,-z,stack-size=8388608"
sed "s|^\tset(WASI_SDK_PREFIX .*|\tset(WASI_SDK_PREFIX $W)|" "$HERE/wasi-p1-threads.cmake" > toolchain.cmake
cmake -S oorexx -B build -DCMAKE_TOOLCHAIN_FILE="$WORK/toolchain.cmake" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="$CF" -DCMAKE_CXX_FLAGS="$CF" -DCMAKE_EXE_LINKER_FLAGS="$LF" \
  -DCMAKE_CROSSCOMPILING_EMULATOR="$WORK/runwasm"
cmake --build build --target rexx_exe rexx_img -j"$(nproc)"
mkdir -p dist && cp build/bin/rexx dist/rexx.wasm && cp build/bin/rexx.img dist/
# the Rexx side of the embedded native packages (::requires "rxregexp.cls"),
# and the extensions written in Rexx alone
for f in rxregexp/rxregexp.cls rxsock/socket.cls rxsock/streamsocket.cls rxsock/mime.cls rxsock/smtp.cls json/json.cls yaml/yaml.cls logicalwrapper/logicalwrapper.cls \
         dateparser/dateparser.cls csvStream/csvStream.cls; do
  cp "oorexx/extensions/$f" dist/
done
# the samples, where the test suite looks for them (.RexxInfo~executable/../samples)
mkdir -p dist/samples && find oorexx/samples -maxdepth 1 -type f ! -name CMakeLists.txt -exec cp {} dist/samples/ \;
# rexxj.jar: the launcher with rexx.wasm translated to JVM bytecode at build time
# (Chicory's build-time compiler), so that start-up does not translate it again
(cd "$HERE/host" && mvn -q -B clean package -DskipTests -Pembedded -Dwasm.file="$WORK/dist/rexx.wasm")
cp "$HERE/host/target/rexxj.jar" dist/
# -Xss: Java threads the module did not create (Swing's, a pool's) may enter it, and
# Chicory's frames live on their Java stack (prototypes/threads/README.md)
printf '#!/bin/sh\nD=$(dirname "$(readlink -f "$0")")\nPATH="$D:$PATH" exec java -Xss16m -Drexxjvm.executable="$D/rexx" -Drexxjvm.home="$D" -jar "$D/rexxj.jar" "$@"\n' > dist/rexxj
chmod +x dist/rexxj
# the usual names, as commands (the test suite runs "rexx ..."): rexx is rexxj; the
# utilities are in the interpreter's binary (multi-call, rexx --orx-wasm-TOOL)
printf '#!/bin/sh\nexec "$(dirname "$(readlink -f "$0")")/rexxj" "$@"\n' > dist/rexx
for t in rexxc rxqueue rxsubcom; do
  printf '#!/bin/sh\nexec "$(dirname "$(readlink -f "$0")")/rexxj" --orx-wasm-%s "$@"\n' "$t" > "dist/$t"
done
chmod +x dist/rexx dist/rexxc dist/rxqueue dist/rxsubcom
echo "done: $WORK/dist/rexxj program.rex"

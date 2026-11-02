#!/bin/bash
# Rebuild ooRexx for WASI (wasm32-wasip1-threads) and the Chicory launcher.
# Needs: svn, git, cmake, make, Java 11+ and Maven, network for wasi-sdk 34, GitHub and Maven Central.
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
# BSF4ooRexx (Rony G. Flatscher's ooRexx-Java bridge, Apache 2.0), layer 1 of Java from
# Rexx: its native library compiled into rexx.wasm, its Java classes and BSF.CLS as they
# are, from a fixed commit of the Git import of its SVN branch 850
BSF_REV=eafe4e6335b70e58fbb181265f10bceca3f161f5
if [ "$(git -C BSF4ooRexx rev-parse HEAD 2>/dev/null)" != "$BSF_REV" ]; then
  rm -rf BSF4ooRexx && git init -q BSF4ooRexx
  git -C BSF4ooRexx fetch -q --depth 1 https://github.com/JosepMariaBlasco/BSF4ooRexx "$BSF_REV"
  git -C BSF4ooRexx checkout -q FETCH_HEAD
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
# the mini-JNI (layer 0 of Java from Rexx): a JNIEnv over the launcher's object table,
# with the JDK's own jni.h (the JDK that builds the launcher) and compat/jni's jni_md.h
JNI_INCLUDE=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")/include
"$W/bin/clang" --target=wasm32-wasip1-threads -pthread -O2 -I "$C/jni" -I "$JNI_INCLUDE" -c "$C/orx_jni.c" -o jni.o
# BSF4ooRexx.cc unchanged, against the mini-JNI: it loads the JVM with dlopen/dlsym, which
# are orx_bsf.c's (the JVM is the one running the module); its Java_... functions, the
# native methods of its Java side, are exported (host/.../Natives.java calls them)
BSF_CC=BSF4ooRexx/bsf4oorexx.dev/source_cc/BSF4ooRexx.cc
"$W/bin/clang++" --target=wasm32-wasip1-threads -pthread -O2 -std=gnu++11 $CF -DHAVE_CONFIG_H -DOPSYS_WASI -DORX_WASM -DUNIX -DLINUX \
  -Ddlopen=orx_bsf_dlopen -Ddlsym=orx_bsf_dlsym -Ddlerror=orx_bsf_dlerror -w \
  -I oorexx/api -I oorexx/api/platform/unix -I "$C/jni" -I "$JNI_INCLUDE" -c "$BSF_CC" -o bsf.o
"$W/bin/clang" --target=wasm32-wasip1-threads -pthread -O2 -I "$C/jni" -I "$JNI_INCLUDE" -c "$C/orx_bsf.c" -o bsf-lib.o
BSF_EXPORTS=$("$W/bin/llvm-nm" --defined-only --extern-only bsf.o | awk '$2 == "T" && $3 ~ /^Java_/ {printf " -Wl,--export=%s", $3}')
LF="$WORK/stubs.o $WORK/host.o $WORK/fs.o $WORK/net.o $WORK/embed.o $WORK/jni.o $WORK/bsf.o $WORK/bsf-lib.o$BSF_EXPORTS -fwasm-exceptions -lunwind -lwasi-emulated-signal -lwasi-emulated-getpid -lwasi-emulated-process-clocks -lwasi-emulated-mman -Wl,--max-memory=2147483648 -Wl,-z,stack-size=8388608"
sed "s|^\tset(WASI_SDK_PREFIX .*|\tset(WASI_SDK_PREFIX $W)|" "$HERE/wasi-p1-threads.cmake" > toolchain.cmake
cmake -S oorexx -B build -DCMAKE_TOOLCHAIN_FILE="$WORK/toolchain.cmake" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="$CF" -DCMAKE_CXX_FLAGS="$CF" -DCMAKE_EXE_LINKER_FLAGS="$LF" \
  -DCMAKE_CROSSCOMPILING_EMULATOR="$WORK/runwasm"
# the objects above reach the link through the linker flags, so CMake does not know
# rexx depends on them: link it again every time (it takes seconds)
rm -f build/bin/rexx
cmake --build build --target rexx_exe rexx_img -j"$(nproc)"
mkdir -p dist && cp build/bin/rexx dist/rexx.wasm && cp build/bin/rexx.img dist/
# the Rexx side of the embedded native packages (::requires "rxregexp.cls"),
# and the extensions written in Rexx alone
for f in rxregexp/rxregexp.cls rxsock/socket.cls rxsock/streamsocket.cls rxsock/mime.cls rxsock/smtp.cls json/json.cls yaml/yaml.cls logicalwrapper/logicalwrapper.cls \
         dateparser/dateparser.cls csvStream/csvStream.cls; do
  cp "oorexx/extensions/$f" dist/
done
# BSF4ooRexx: BSF.CLS (and the Rexx packages that go with it), and its Java side in one
# jar: bsf4oorexx.dev/source_java compiled for Java 8 (the classes compiled at the
# repository's root are older: RexxRedirectingCommandHandler is missing), over the
# repository's Apache BSF and Janino classes and their resources
for f in BSF.CLS BSF_OnTheFly.cls rgf_util2.rex; do cp "BSF4ooRexx/bsf4oorexx.dev/bin/$f" dist/; done
rm -rf bsf-stage && mkdir bsf-stage
(cd BSF4ooRexx && find org META-INF/services -type f \( -name '*.class' -o -name '*.properties' -o -path 'META-INF/services/*' \) \
   -exec cp --parents {} ../bsf-stage/ \;)
(cd BSF4ooRexx && javac -nowarn -encoding ISO-8859-1 --release 8 -cp . -d ../bsf-stage bsf4oorexx.dev/source_java/*.java 2>&1 | grep -v '^Note:\|^warning:\|^1 warning' || true)
rm -f dist/bsf4oorexx.jar && jar cf dist/bsf4oorexx.jar -C bsf-stage .
# the samples, where the test suite looks for them (.RexxInfo~executable/../samples)
mkdir -p dist/samples && find oorexx/samples -maxdepth 1 -type f ! -name CMakeLists.txt -exec cp {} dist/samples/ \;
# rexxj.jar: the launcher with rexx.wasm translated to JVM bytecode at build time
# (Chicory's build-time compiler), so that start-up does not translate it again
(cd "$HERE/host" && mvn -q -B clean package -DskipTests -Pembedded -Dwasm.file="$WORK/dist/rexx.wasm")
cp "$HERE/host/target/rexxj.jar" dist/
# -Xss: Java threads the module did not create (Swing's, a pool's) may enter it, and
# Chicory's frames live on their Java stack (prototypes/threads/README.md)
# -cp, not -jar: Java code that Rexx uses is found through CLASSPATH, as with BSF4ooRexx;
# the jar is also the agent that gives BSF4ooRexx's native methods to the module (Natives)
# -XX:-DontCompileHugeMethods: HotSpot never JIT-compiles a method over 8,000 bytes of
# bytecode, and some of the interpreter's functions become such methods; rexxcps
# 560 k -> 997 k clauses/s with it (02/11/2026)
printf '#!/bin/sh\nD=$(dirname "$(readlink -f "$0")")\nPATH="$D:$PATH" exec java -Xss16m -XX:-DontCompileHugeMethods -Drexxjvm.executable="$D/rexx" -Drexxjvm.home="$D" -javaagent:"$D/rexxj.jar" -cp "$D/rexxj.jar:$D/bsf4oorexx.jar${CLASSPATH:+:$CLASSPATH}" rexxjvm.RunWasm "$@"\n' > dist/rexxj
chmod +x dist/rexxj
# the usual names, as commands (the test suite runs "rexx ..."): rexx is rexxj; the
# utilities are in the interpreter's binary (multi-call, rexx --orx-wasm-TOOL)
printf '#!/bin/sh\nexec "$(dirname "$(readlink -f "$0")")/rexxj" "$@"\n' > dist/rexx
for t in rexxc rxqueue rxsubcom; do
  printf '#!/bin/sh\nexec "$(dirname "$(readlink -f "$0")")/rexxj" --orx-wasm-%s "$@"\n' "$t" > "dist/$t"
done
chmod +x dist/rexx dist/rexxc dist/rxqueue dist/rxsubcom
echo "done: $WORK/dist/rexxj program.rex"

#!/bin/bash
# The variant with the test suite's native libraries linked in, to run the API
# test groups (API/classic, API/oo) of the ooRexx test suite, which load them
# with ::METHOD ... EXTERNAL "LIBRARY orxmethod ...", RxFuncAdd(...,
# 'orxclassic1', ...) and RexxRegister*Dll(..., "orxexits", ...).  For testing
# only: dist-test/ is not meant to be installed.
#
# oorexxwasm's wasm/build-test.sh (its patch 0007), for WASI: there is no
# dlopen in WebAssembly, so the libraries of testbinaries/ are compiled into
# rexx.wasm and described by the table orx_wasm_static_libraries() that
# SysLibrary looks up (oorexxwasm's 0001).  The names more than one library
# defines get a prefix per library, the API macros that put a name in a method
# or routine table are redefined to keep the name as written, and the table
# maps the prefixed names back.  testbinaries/rexxinstance (run as a command by
# ProcessInvocation and ProcessRexxStart) is one more tool in the same
# rexx.wasm, through rexx.cpp's weak hook orxWasmExtraTool():
# rexxj --orx-wasm-rexxinstance, and a dist-test/rexxinstance script.
#
# Usage: ./build-test.sh [WORK]   (WORK: where ./build.sh built; it must be done)
# Output: WORK/dist-test, dist/ with this rexx.wasm and its rexxj.jar.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(cd "${1:-$HOME/oorexx-jvm}" && pwd)
W=$WORK/wasi-sdk-34.0-x86_64-linux
SRC=$WORK/oorexx
BUILD=$WORK/build
T=$BUILD/testbinaries-wasi; rm -rf "$T"; mkdir -p "$T"
C=$HERE/compat
[ -x "$WORK/dist/rexxj" ] || { echo "build first: ./build.sh $WORK" >&2; exit 1; }
FLAGS=(--target=wasm32-wasip1-threads -pthread -O2 -isystem "$C" -include "$C/orx_wasi_compat.h"
       -fwasm-exceptions -mllvm -wasm-use-legacy-eh=false
       -D_WASI_EMULATED_SIGNAL -D_WASI_EMULATED_GETPID -D_WASI_EMULATED_PROCESS_CLOCKS -D_WASI_EMULATED_MMAN
       -DHAVE_CONFIG_H -DOPSYS_WASI -DORX_WASM -DWASI -D_GNU_SOURCE
       -I"$SRC/api" -I"$SRC/api/platform/unix" -I"$BUILD" -I"$SRC/testbinaries")
# library:source, as in testbinaries/CMakeLists.txt (orxexits has two)
UNITS="orxfunction:orxfunction.cpp orxmethod:orxmethod.cpp orxclassic:orxclassic.cpp
       orxclassic1:orxclassic1.c orxexits:orxclassicexits.cpp orxexits:orxinstance.cpp
       orxinvocation:orxinvocation.cpp"
NM=$W/bin/llvm-nm
FILT=$W/bin/llvm-cxxfilt

compile() {   # compile SOURCE OBJECT [flags]
  local src=$1 obj=$2; shift 2
  case $src in
    *.c) "$W/bin/clang" "${FLAGS[@]}" "$@" -c "$src" -o "$obj" ;;
    *)   "$W/bin/clang++" -std=gnu++11 "${FLAGS[@]}" "$@" -c "$src" -o "$obj" ;;
  esac
}

# pass 1: the names each library defines; those defined by more than one
# library (as identifiers: C++ names demangled) get a prefix in pass 2
n=0
for unit in $UNITS; do
  lib=${unit%%:*} src=${unit#*:} n=$((n + 1))
  compile "$SRC/testbinaries/$src" "$T/probe-$n.o"
  "$NM" --defined-only --extern-only "$T/probe-$n.o" |
    awk -v lib="$lib" '$2 ~ /^[TDBR]$/ {print lib, $3}'
done | sort -u > "$T/defined.txt"
CLASHES=$(awk '{print $2}' "$T/defined.txt" | sort | uniq -d | "$FILT" |
          sed -E 's/\(.*//; s/.*:://' | sort -u)

# pass 2: each source and its symbol table in one translation unit, so that
# every address is taken through the function's own declaration
objects=() tables=()
n=0
for unit in $UNITS; do
  lib=${unit%%:*} src=${unit#*:} n=$((n + 1))
  case $src in *.c) ext=c ;; *) ext=cpp ;; esac
  defs=()
  for c in $CLASHES; do defs+=("-D$c=${lib}__$c"); done
  table=orx_wasm_static_$n
  wrapper=$T/$n-$lib.$ext
  {
    echo "#ifdef __cplusplus"
    echo "#include <oorexxapi.h>"
    echo "#undef REXX_METHOD"
    echo "#define REXX_METHOD(n, e) { METHOD_TYPED_STYLE, 0, #n, (void *)e, 0, 0 }"
    echo "#undef REXX_TYPED_ROUTINE"
    echo "#define REXX_TYPED_ROUTINE(n, e) { ROUTINE_TYPED_STYLE, 0, #n, (void *)e, 0, 0 }"
    echo "#undef REXX_CLASSIC_ROUTINE"
    echo "#define REXX_CLASSIC_ROUTINE(n, e) { ROUTINE_CLASSIC_STYLE, 0, #n, (void *)e, 0, 0 }"
    echo "#endif"
    echo "#include \"$SRC/testbinaries/$src\""
  } > "$wrapper"
  compile "$wrapper" "$T/probe.o" "${defs[@]}"
  syms=$("$NM" --defined-only --extern-only "$T/probe.o" |
         awk '$2 == "T" && $3 !~ /^(_Z|__)/ {print $3}' | sort -u)
  {
    echo "#ifdef __cplusplus"
    echo "extern \"C\" {"
    echo "#endif"
    echo "typedef struct { const char *name; void *address; } OrxWasmStaticSymbol;"
    echo "extern const OrxWasmStaticSymbol $table[];"
    echo "const OrxWasmStaticSymbol $table[] = {"
    for s in $syms; do echo "    { \"${s#"${lib}__"}\", (void *)&$s },"; done
    echo "    { 0, 0 }"
    echo "};"
    echo "#ifdef __cplusplus"
    echo "}"
    echo "#endif"
  } >> "$wrapper"
  compile "$wrapper" "$T/$n-$lib.o" "${defs[@]}"
  objects+=("$T/$n-$lib.o")
  tables+=("$lib:$table")
done

# the libraries a build adds to the table SysLibrary looks up (compat/orx_bsf.c's
# orx_wasm_static_libraries puts BSF4ooRexx first)
{
  echo 'extern "C" {'
  echo "typedef struct { const char *name; void *address; } OrxWasmStaticSymbol;"
  echo "typedef struct { const char *name; const OrxWasmStaticSymbol *symbols; } OrxWasmStaticLibrary;"
  for t in "${tables[@]}"; do echo "extern const OrxWasmStaticSymbol ${t#*:}[];"; done
  echo "const OrxWasmStaticLibrary *orx_wasi_extra_libraries(void)"
  echo "{"
  echo "    static const OrxWasmStaticLibrary libraries[] = {"
  for t in "${tables[@]}"; do echo "        { \"${t%%:*}\", ${t#*:} },"; done
  echo "        { 0, 0 }"
  echo "    };"
  echo "    return libraries;"
  echo "}"
  echo "}"
} > "$T/static-libraries.cpp"
compile "$T/static-libraries.cpp" "$T/static-libraries.o"
objects+=("$T/static-libraries.o")

# rexxinstance, compiled with the names of orxexits (the library it links)
defs=()
for c in $CLASHES; do defs+=("-D$c=orxexits__$c"); done
compile "$SRC/testbinaries/rexxinstance.cpp" "$T/rexxinstance.o" "${defs[@]}" -Dmain=orxWasmRexxinstanceMain
# the mini-JNI's test (tests/jni/), one more tool: rexxj --orx-wasm-jnitest
JNI_INCLUDE=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")/include
"$W/bin/clang" --target=wasm32-wasip1-threads -pthread -O2 -I "$C/jni" -I "$JNI_INCLUDE" \
  -Dmain=orxWasmJnitestMain -c "$HERE/tests/jni/jnitest.c" -o "$T/jnitest.o"
cat > "$T/extra-tool.cpp" <<'CPP'
#include <string.h>
int orxWasmRexxinstanceMain(int argc, char **argv);
extern "C" int orxWasmJnitestMain(int argc, char **argv);
// rexx.cpp: the tools a build adds (rexxj --orx-wasm-TOOL)
extern "C" bool orxWasmExtraTool(const char *tool, int argc, char **argv, int *rc)
{
    if (strcmp(tool, "rexxinstance") == 0)
    {
        *rc = orxWasmRexxinstanceMain(argc, argv);
        return true;
    }
    if (strcmp(tool, "jnitest") == 0)
    {
        *rc = orxWasmJnitestMain(argc, argv);
        return true;
    }
    return false;
}
CPP
compile "$T/extra-tool.cpp" "$T/extra-tool.o"
objects+=("$T/rexxinstance.o" "$T/jnitest.o" "$T/extra-tool.o")

# relink rexx_exe's own command with the objects, into bin-test/rexx
mkdir -p "$BUILD/bin-test"
LINK=$(sed "s| -o bin/rexx | ${objects[*]} -o bin-test/rexx |" "$BUILD/CMakeFiles/rexx_exe.dir/link.txt")
(cd "$BUILD" && eval "$LINK")

# dist-test: dist/ with this rexx.wasm, its rexxj.jar, and rexxinstance
rm -rf "$WORK/dist-test" && cp -a "$WORK/dist" "$WORK/dist-test"
cp "$BUILD/bin-test/rexx" "$WORK/dist-test/rexx.wasm"
(cd "$HERE/host" && mvn -q -B clean package -DskipTests -Pembedded -Dwasm.file="$WORK/dist-test/rexx.wasm")
cp "$HERE/host/target/rexxj.jar" "$WORK/dist-test/"
printf '#!/bin/sh\nexec "$(dirname "$(readlink -f "$0")")/rexxj" --orx-wasm-rexxinstance "$@"\n' > "$WORK/dist-test/rexxinstance"
chmod +x "$WORK/dist-test/rexxinstance"
# the mini-JNI's test, with the Java class it calls
mkdir -p "$WORK/dist-test/jnitest-classes"
javac -d "$WORK/dist-test/jnitest-classes" "$HERE/tests/jni/jnitest/JniTarget.java"
printf '#!/bin/sh\nD=$(dirname "$(readlink -f "$0")")\nCLASSPATH="$D/jnitest-classes${CLASSPATH:+:$CLASSPATH}" exec "$D/rexxj" --orx-wasm-jnitest "$@"\n' > "$WORK/dist-test/jnitest"
chmod +x "$WORK/dist-test/jnitest"
echo "done: $WORK/dist-test/rexxj program.rex"

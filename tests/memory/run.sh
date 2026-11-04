#!/bin/sh
# The memory's bounds checks (tests/memory/README.md): MemCheckTest against a
# rexxj.jar, strict and relaxed, with sun.misc.Unsafe and, on Java 22 and later,
# with the mapped block too. Usage: tests/memory/run.sh DIST (e.g. ~/oorexx-jvm/dist)
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
JAR=${1:?usage: run.sh DIST}/rexxj.jar
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
javac -nowarn -d "$T" -cp "$JAR" "$HERE/rexxjvm/MemCheckTest.java"
V=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
fail=0
for check in relaxed strict; do
  for memory in unsafe mapped; do
    [ "$memory" = mapped ] && [ "${V:-0}" -lt 22 ] && continue
    echo "== $memory, $check"
    java -Drexxjvm.memcheck=$check -Drexxjvm.memory=$memory -cp "$JAR:$T" rexxjvm.MemCheckTest 2>&1 \
      | grep -v -E '^ok |^WARNING' || true
    java -Drexxjvm.memcheck=$check -Drexxjvm.memory=$memory -cp "$JAR:$T" rexxjvm.MemCheckTest >/dev/null 2>&1 || fail=1
  done
done
exit $fail

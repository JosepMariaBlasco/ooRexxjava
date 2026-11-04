#!/bin/sh
# call_indirect's dispatch resolved at build time against Endive's own (README.md):
# builds rexxj.jar from table.wasm twice, with and without host/static-table, runs
# TableTest with each, and checks that both give expected.txt. Needs what build.sh
# needs for the jar (Maven on JDK 22 or later: scripts/mvn22.sh). Leaves host/target
# with the table.wasm jar: build.sh or build-test.sh build it again.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
for skip in false true; do
  (cd "$ROOT/host" && "$ROOT/scripts/mvn22.sh" -q -B clean package -DskipTests -Pembedded \
     -Dwasm.file="$HERE/table.wasm" -Dstatic.table.skip=$skip) > "$T/build-$skip.log" 2>&1 \
    || { cat "$T/build-$skip.log"; exit 1; }
  cp "$ROOT/host/target/rexxj.jar" "$T/$skip.jar"
  mkdir -p "$T/classes-$skip"
  javac -nowarn -d "$T/classes-$skip" -cp "$T/$skip.jar" "$HERE/rexxjvm/TableTest.java"
  java -cp "$T/$skip.jar:$T/classes-$skip" rexxjvm.TableTest > "$T/out-$skip.txt"
done
grep -q 'resolved at build time: true' "$T/out-false.txt" || { echo "FAIL: static-table not applied"; exit 1; }
grep -q 'resolved at build time: false' "$T/out-true.txt" || { echo "FAIL: skip did not skip"; exit 1; }
fail=0
for skip in false true; do
  grep -v '^#' "$T/out-$skip.txt" | diff -u "$HERE/expected.txt" - || fail=1
done
[ $fail = 0 ] && echo "ok: both dispatches give expected.txt ($(wc -l < "$HERE/expected.txt") calls)"
exit $fail

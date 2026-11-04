#!/bin/sh
# The test suite's tools as Windows launchers in DIST-TEST (build-test.sh's dist-test,
# the windows-suite workflow): rexxinstance.cmd and detach_root_terminate.cmd, rexxj.cmd
# with the tool's option (rexxj --orx-wasm-TOOL, as dist-test's sh scripts), on its java
# line and on its rexxjs.exe one.
#   scripts/test-tool-launchers.sh DIST-TEST
D=${1:?usage: test-tool-launchers.sh DIST-TEST}
for t in rexxinstance detach_root_terminate; do
  sed "s/rexxjvm\.RunWasm %\*/rexxjvm.RunWasm --orx-wasm-$t %*/; s/rexxjs\.exe\" %\*/rexxjs.exe\" --orx-wasm-$t %*/; s/^rem rexxj\.cmd on Windows/rem $t.cmd on Windows/" \
    "$D/rexxj.cmd" > "$D/$t.cmd"
  [ "$(grep -c -- "--orx-wasm-$t %\*" "$D/$t.cmd")" = 2 ] || { echo "test-tool-launchers.sh: $D/rexxj.cmd not as expected" >&2; exit 1; }
done

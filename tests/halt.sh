#!/bin/bash
# SIGINT, SIGTERM and SIGHUP raise HALT in the running program, as natively
# (Host.installSignals, compat/orx_wasi_host.c's signal thread):
#   ./tests/halt.sh [DIST]   (DIST: a built dist/, default ../oorexx-jvm/dist)
# Expected: "HALT result=4 quick=1" three times, then Error 4.1 and status 252; then
# a program that never sees its HALT: alive after one SIGTERM (as natively), ended by a
# second one, "hung: after one SIGTERM alive, after two status=143" (Host.signalWait: a
# signal while the previous HALT is
# still unseen ends the process as its default action would).
D=$(cd "${1:-$HOME/oorexx-jvm/dist}" && pwd); T=$(cd "$(dirname "$0")" && pwd)
W=$(mktemp -d)
sig() {  # sig SIGNAL PROGRAM: runs it, sends it SIGNAL once it runs, waits
  # a job in the background of a script starts with SIGINT ignored, and then,
  # as natively, rexx leaves it ignored: give it the default action
  rm -f "$W/ready"; env --default-signal=INT "$D/rexx" "$T/$2" "$W/ready" & local p=$!
  while [[ ! -s $W/ready ]]; do sleep 0.1; done
  kill -$1 $p; wait $p; echo "status=$?"
}
for s in INT TERM HUP; do printf '%s: ' $s; sig $s halt-trapped.rex | head -1; done
sig INT halt-untrapped.rex
rm -f "$W/ready"; "$D/rexx" "$T/halt-hung.rex" "$W/ready" & p=$!
while [[ ! -s $W/ready ]]; do sleep 0.1; done
sleep 1; kill -TERM $p; sleep 2
kill -0 $p 2>/dev/null && alive=alive || alive=gone
kill -TERM $p; wait $p; echo "hung: after one SIGTERM $alive, after two status=$?"
rm -rf "$W"

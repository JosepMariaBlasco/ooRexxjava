#!/bin/bash
# The local time zone under rexxj (compat/orx_wasi_stubs.c, host/.../TimeZones.java):
# tz.rex under native rexx and under rexxj, with several TZ, must print the same.
#   tests/timezone/run.sh DIST [NATIVE_REXX]
# NATIVE_REXX: native ooRexx's rexx (default /usr/local/bin/rexx, where its .deb puts it); without it, nothing to compare.
DIST=${1:?usage: run.sh DIST [NATIVE_REXX]}
NATIVE=${2:-/usr/local/bin/rexx}
HERE=$(cd "$(dirname "$0")" && pwd)
[ -x "$NATIVE" ] || { echo "skip: no native rexx at $NATIVE"; exit 0; }
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fails=0
# -: TZ unset, the host's zone (the JVM's default); the rest as glibc reads them
for tz in - "" Europe/Madrid :America/New_York UTC UTC0 EST5 GMT+3 '<+0530>-5:30' Asia/Kolkata Australia/Lord_Howe NoSuch/Zone; do
  if [ "$tz" = - ]; then unset TZ; else export TZ="$tz"; fi
  "$NATIVE" "$HERE/tz.rex" "$T/f" > "$T/native" 2>&1
  env -u JAVA_TOOL_OPTIONS "$DIST/rexxj" "$HERE/tz.rex" "$T/f" > "$T/rexxj" 2>&1
  if diff "$T/native" "$T/rexxj" > "$T/diff"; then
    echo "ok   TZ=[$tz]"
  else
    echo "FAIL TZ=[$tz]"; sed 's/^/     /' "$T/diff"; fails=$((fails + 1))
  fi
done
echo "$fails failed"
exit $((fails > 0))

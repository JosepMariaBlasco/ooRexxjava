#!/bin/sh
# A copy of the ooRexx test suite whose framework knows the OS "WASI".
#
# ooTest.frm's knownOSes lists WINDOWS, LINUX, DARWIN, AIX, SUNOS, MACOSX,
# CYGNUS, FREEBSD, NETBSD and OPENBSD; restrictOS("UNIX") means all of them but
# WINDOWS. Under WASI, parse source names the OS "WASI", which is not in the
# list, so the groups restricted to Unix (SysUnix, tilde, SysGetMessage,
# ncurses, utilities/rxapi) report no tests and count as passed. Run on this
# copy, they run. The project publishes both counts: the official suite's and
# this one's (Josep Maria, 2/11/2026; to be proposed to ooRexx after alpha).
#
# Usage: scripts/suite-with-wasi.sh SUITE COPY
#   SUITE: test/trunk exported; COPY: where the copy goes (replaced)
set -e
SRC=${1:?suite}; DST=${2:?copy}
rm -rf "$DST"
cp -r "$SRC" "$DST"
F=$DST/ooTest.frm
grep -q "'OPENBSD')" "$F" || { echo "ooTest.frm: knownOSes not as expected" >&2; exit 1; }
sed -i "s/'OPENBSD')/'OPENBSD', 'WASI')/" "$F"
grep -c "'WASI'" "$F" >/dev/null
echo "$DST: ooTest.frm knows WASI"

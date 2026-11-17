#!/bin/bash
# What a program sees through symbolic links: relative and absolute targets, a file and
# a directory, "..", dangling links, a program reached through a link.
# Usage: tests/probe/links.sh [REXXJ=dist/rexxj]     (prints; compare two builds' output)
# Written for Endive's name-by-name path resolution (endive#304, after 1.2.0): a link
# whose target is absolute gives EPERM there, even when the target is inside the
# preopened directory (ours is "/").
REXXJ=${1:-$(dirname "$0")/../../dist/rexxj}
[[ $REXXJ == /* ]] || REXXJ=$PWD/$REXXJ
D=$(mktemp -d "${TMPDIR:-/tmp}/links.XXXXXX") || exit 1
B=$(basename "$D"); P=$(basename "$(dirname "$D")")
mkdir "$D/realdir"; echo contents > "$D/realdir/f.txt"; echo 'say "the program ran"' > "$D/realdir/prog.rex"
ln -s "$D/realdir" "$D/dabs";           ln -s realdir "$D/drel"
ln -s "$D/realdir/f.txt" "$D/fabs";     ln -s realdir/f.txt "$D/frel"
ln -s "$D/nothing" "$D/dangabs";        ln -s nothing "$D/dangrel"
cat > "$D/probe.rex" <<REX
call t "fabs"; call t "frel"; call t "dabs/f.txt"; call t "drel/f.txt"
call t "DIR/fabs"; call t "DIR/dabs/f.txt"; call t "../$B/realdir/f.txt"
say "SysIsFileLink dangabs" SysIsFileLink("dangabs") "dangrel" SysIsFileLink("dangrel") "SysFileExists dangabs" SysFileExists("dangabs")
call SysFileTree "dabs/*", "f.", "FO"; say "SysFileTree dabs/*:" f.0
call SysFileTree "drel/*", "f.", "FO"; say "SysFileTree drel/*:" f.0
old = directory(); say "directory(dabs):" (directory("dabs") == old"/realdir"); call directory old
say "directory(drel):" (directory("drel") == old"/realdir"); call directory old
exit
t: parse arg p
  if p~startsWith("DIR/") then p = directory()substr(p, 4)
  shown = p~changeStr(directory(), "DIR")~changeStr("$B", "TMP")
  s = .stream~new(p); r = s~open("read")
  if r = "READY:" then do; l = s~linein; s~close; say left(shown, 28) "ok  " l; end
  else say left(shown, 28) "FAIL" r "(SysFileExists" SysFileExists(p)", SysIsFile" SysIsFile(p)")"
  return
REX
cd "$D" || exit 1
"$REXXJ" probe.rex 2>&1
echo "-- a program through an absolute link, then a relative one:"
"$REXXJ" dabs/prog.rex 2>&1 | sed "s|$D|DIR|g" | tail -1
"$REXXJ" "$D/drel/prog.rex" 2>&1 | tail -1

#!/bin/bash
# Checks of the warm JVM's runs (README.md, "A warm JVM: rexxjs"):
#   tests/server/run.sh [DIST]      DIST: /home/claude/work/dist by default
# - each run has a class loader of its own: the client's CLASSPATH, and statics that
#   start afresh (classpath.rex, with a/Greeter and b/Greeter), also two runs at once;
# - Java threads with no run of their own enter the run whose classes they call
#   (tests/bsf/threads.rex, twice at once);
# - Java's System.out and System.err reach the client (sysout.rex);
# - a command reads the client's standard input, a regular file, from where the
#   program's reading reached, as with rexxj (stdin-cmd.rex);
# - commands with captured input, output and error, large (capture.rex); a HALT
#   while a command runs (halt-cmd.rex);
# - rexxjs --orx-server-stop ends the server and its programs (143); the next run
#   starts a new one.
# REXXJ_SERVER_RELAY=1 tests/server/run.sh runs it all with the streams relayed through
# the socket (the mode for systems with no /proc); the expected results are the same.
set -u
D=${1:-/home/claude/work/dist}
cd "$(dirname "$0")"
unset JAVA_TOOL_OPTIONS
export REXXJ_SERVER=1
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fails=0
check() {   # NAME EXPECTED ACTUAL
  if [ "$2" == "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected [$2], got [$3]"; fails=$((fails + 1)); fi
}
[ -f a/Greeter.class ] || javac -d a a/Greeter.java
[ -f b/Greeter.class ] || javac -d b b/Greeter.java
"$D/rexxjs" --orx-server-stop 2>/dev/null

check "CLASSPATH a" "a1 a2" "$(CLASSPATH=a "$D/rexx" classpath.rex)"
check "statics afresh" "a1 a2" "$(CLASSPATH=a "$D/rexx" classpath.rex)"
check "CLASSPATH b, absolute" "b1 b2" "$(CLASSPATH=$PWD/b "$D/rexx" classpath.rex)"
check "CLASSPATH dir/*" "a1 a2" "$(mkdir -p "$T/jars" && (cd a && jar cf "$T/jars/a.jar" Greeter.class) && CLASSPATH="$T/jars/*" "$D/rexx" classpath.rex)"
CLASSPATH=a "$D/rexx" classpath.rex > "$T/a.txt" & CLASSPATH=b "$D/rexx" classpath.rex > "$T/b.txt"; wait
check "two at once" "a1 a2 b1 b2" "$(cat "$T/a.txt") $(cat "$T/b.txt")"

"$D/rexx" ../bsf/threads.rex > "$T/t1.txt" & "$D/rexx" ../bsf/threads.rex > "$T/t2.txt"; wait
check "foreign threads, two runs at once" "threads: ok (java-1 java-2 java-3 java-4) threads: ok (java-1 java-2 java-3 java-4)" "$(cat "$T/t1.txt") $(cat "$T/t2.txt")"

"$D/rexx" sysout.rex > "$T/o.txt" 2> "$T/e.txt"
check "System.out" "rexx: before|java: System.out|rexx: after" "$(tr '\n' '|' < "$T/o.txt" | sed 's/|$//')"
check "System.err" "java: System.err" "$(cat "$T/e.txt")"

seq 1 100000 > "$T/in.txt"
REXXJ_SERVER= "$D/rexx" stdin-cmd.rex < "$T/in.txt" > "$T/direct.txt"
"$D/rexx" stdin-cmd.rex < "$T/in.txt" > "$T/server.txt"
# rexxj's commands share the JVM's descriptor, after its 8 KB buffer; the server's
# share the program's, after the interpreter's 4 KB read: lines differ, the shape not
check "a command's standard input (direct)" "rexx: 1||1861|rexx: 2|rexx: 3" "$(tr '\n' '|' < "$T/direct.txt" | sed 's/|$//')"
check "a command's standard input (server)" "rexx: 1|1|1042|rexx: 2|rexx: 3" "$(tr '\n' '|' < "$T/server.txt" | sed 's/|$//')"

check "captured streams" "50000 line 50000 | 300000 300000 | 3 1 err" "$("$D/rexx" capture.rex)"
"$D/rexx" halt-cmd.rex > "$T/h.txt" & sleep 3; kill -TERM $!; wait $!; rc=$?
check "a HALT while a command runs" "start|HALT 9" "$(tr '\n' '|' < "$T/h.txt" | sed 's/|$//') $rc"

printf 'say "start"; call SysSleep 30; say "never"\n' > "$T/long.rex"
"$D/rexx" "$T/long.rex" > "$T/long.txt" & sleep 3
"$D/rexxjs" --orx-server-stop 2>/dev/null; wait $!; rc=$?
check "stop ends a running program" "start 143" "$(cat "$T/long.txt") $rc"
check "a new server after a stop" "1/7 = 0.1428571428571428571428571428571428571429" "$("$D/rexx" ../hello.rex | tail -1)"

echo "$fails failed"
exit $((fails > 0))

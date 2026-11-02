#!/bin/sh
# Compiles and runs Jsr223.java: a Java program hosting Rexx through JSR-223.
# Usage: tests/jsr223/run.sh [DIST]   (DIST: the dist/ directory; default /home/claude/work/dist)
# Note what the host needs: rexxj.jar as a Java agent and on the class path,
# bsf4oorexx.jar on the class path, and a large Java stack (-Xss). No PATH.
set -e
HERE=$(dirname "$(readlink -f "$0")")
D=${1:-/home/claude/work/dist}
OUT=$(mktemp -d)
javac -d "$OUT" -cp "$D/bsf4oorexx.jar" "$HERE/Jsr223.java"
exec java -Xss16m -XX:-DontCompileHugeMethods -javaagent:"$D/rexxj.jar" -cp "$D/rexxj.jar:$D/bsf4oorexx.jar:$OUT" Jsr223

#!/bin/bash
# mvn ARGS... with a JDK 22 or later, so that the jar gets its Java 22 part
# (META-INF/versions/22: rexxjvm.Blocks falling back to a mapped block when the JVM
# refuses sun.misc.Unsafe). Maven's own JDK if it is 22 or later; otherwise the newest
# 22+ JDK in /usr/lib/jvm (or $REXXJ_BUILD_JDK); otherwise Maven's, with a note: the jar
# then works on every Java, but not on a JVM that denies Unsafe's memory access.
major() { "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }
J=${REXXJ_BUILD_JDK:-}
if [ -z "$J" ]; then
  CUR=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}
  if [ "$(major "$CUR")" -ge 22 ] 2>/dev/null; then J=$CUR
  else
    best=0
    for d in /usr/lib/jvm/*/; do
      d=${d%/}; [ -x "$d/bin/javac" ] || continue
      m=$(major "$d"); [ -n "$m" ] && [ "$m" -ge 22 ] && [ "$m" -gt "$best" ] && { best=$m; J=$d; }
    done
  fi
fi
if [ -n "$J" ]; then JAVA_HOME=$J exec mvn "$@"; fi
echo "note: no JDK 22 or later for Maven: the jar has no fallback for a JVM that denies sun.misc.Unsafe" >&2
exec mvn "$@"

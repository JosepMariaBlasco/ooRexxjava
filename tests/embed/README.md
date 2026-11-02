# Tests of the embedded mode

`EmbedTest.java` hosts the interpreter through `rexxjvm.Rexx` (see the
repository's README, *Rexx from Java*) and checks: results, omitted
arguments, UTF-8, `SAY`; SYNTAX errors in code and at run time (code,
line, program) and a missing program, as `Rexx.RexxException`, with the
interpreter still usable after them; a recursion 2000 deep on a thread with
a large Java stack; `REPLY`; `.local` kept apart by two instances; many
Java threads (default stack size) calling at once; instances created and
closed again and again, and one left open for `Rexx.close()`. The Rexx
programs it calls are `args.rex`, `fails.rex` and `deep.rex`.

```bash
D=/home/claude/work/dist                    # a build (build.sh)
env -u JAVA_TOOL_OPTIONS javac -cp $D/rexxj.jar -d out tests/embed/EmbedTest.java
env -u JAVA_TOOL_OPTIONS java -cp $D/rexxj.jar:out EmbedTest [threads] [calls]
# from the repository's root (or -Dtest.dir=<this directory>)
```

Result (02/11/2026, compiled machine, Java 21, 2 cores): `ALL OK (41
checks)`; start-up and first instance 0.6 s; 8 threads × 100 calls at once,
1.9 ms per call; 500 calls in a row from one thread, 0.9 ms per call; 20
instances created, used and closed, 4 ms each.

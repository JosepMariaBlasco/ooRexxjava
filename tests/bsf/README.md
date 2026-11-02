# BSF4ooRexx on the JVM

BSF4ooRexx is part of `dist/` (`build.sh`): its native library
(`BSF4ooRexx.cc`, unchanged) is compiled into `rexx.wasm` over the mini-JNI,
its Java side is `dist/bsf4oorexx.jar`, and `BSF.CLS` is beside them. Rexx
programs use it as with any BSF4ooRexx installation (`::requires "BSF.CLS"`);
Java classes of your own go on the `CLASSPATH`.

- `basics.rex`: objects, a static field, a collection, a UTF-8 string, a
  Java array, a Java exception as a SYNTAX condition, and a callback (Java's
  `Collections.sort` calling a comparator written in Rexx). 7 checks.
- `threads.rex`: four Java threads (`java.lang.Thread`, not started by Rexx)
  calling a Rexx `Runnable`: each one runs the Rexx code on its own Java
  thread (a foreign entry through the dispatcher).

```bash
env -u JAVA_TOOL_OPTIONS /home/claude/work/dist/rexxj tests/bsf/basics.rex
env -u JAVA_TOOL_OPTIONS /home/claude/work/dist/rexxj tests/bsf/threads.rex
```

## BSF4ooRexx's own tests

Its `testUnits/` (in `WORK/BSF4ooRexx/bsf4oorexx.dev/testUnits`), run with
the test framework of ooRexx's suite (test/trunk r13347): the framework that
`testUnits/ooRexxUnit` carries is older and lacks `assertSameList`, which
some groups use. From a copy of `testUnits`:

```bash
export PATH=/home/claude/work/dist:$PATH CLASSPATH=$PWD/bsf4rexx
cd /path/to/oorexx-test      # ooRexx's test/trunk
env -u JAVA_TOOL_OPTIONS rexx testOORexx.rex -R /path/to/testUnits/bsf4rexx/rgf -s
```

Results (02/11/2026, Java 21, 2 cores, threads' C stack 256 KB):

| Group | Tests | Assertions | Failures | Errors |
|---|---|---|---|---|
| `rgf` | 172 | 268,038 | 0 | 0 |
| `lex` | 771 | 886 | 0 | 0 |
| `java8` | 39 | 1,500 | 0 | 0 |
| `java6` | 25 | 1,265 | 0 | 0 |
| `rgf_multithreading` | 6 | 1,557 | 0 | 0 |

`java9` has no test groups. `rgf_multithreading`'s group 80 runs 712
threads; its source has two heavier cases commented out, 2,601 and 10,201
threads (`nrJavaThreads`, `nrRexxThreads` = 50 or 100 in a copy of the
group). 2,601 passes (5,101 assertions, 7 s). Before the threads' C stack
went down from 8 MB to 256 KB (`README.md`, "Threads and their stacks") it
ran out of Java heap and hung; with 512 KB it still ran out (2,601 threads
alive at once, 1.5 GB of default heap here). 10,201 passed three runs of
four (20,201 assertions, 13 s); in the fourth more threads were alive at
once, the heap ran out (each thread also has its `Instance` of the module)
and the run hung.

Since the linear memory is off-heap (`FlatMemory`, 02/11/2026) the heap no
longer runs out, and 10,201 meets the module's own limit instead: with 256 KB
stacks, when more than about 8,000 threads are alive at once their stacks do
not fit in the 2 GB of linear memory, and `pthread_create` fails as Rexx's
`Error 48.1: Failure in system service: ERROR CREATING THREAD` (or a thread
silently missing: 8,113 of 10,201 in one run); with `REXXJVM_THREADSTACK=128K`
(1.3 GB for all of them) that no longer happens. Two things seen on the way,
neither ours:

- The test's own handler (`syntax:` in `80_test_Multithreading_via_Java_threads.rex`)
  calls `pp`, whose `id2x` (`rgf_util2.rex`, also in `BSF.CLS`) fails on a 32-bit
  interpreter for an object above 1,000 MB: `numeric digits 9` and a 10-digit
  `identityHash` (`Error 93.928: D2X value must be a valid whole number`), which
  hides the condition being reported. A copy with one more digit, found first
  through `REXX_PATH`, shows it.
- Sometimes nearly all of the 100 controllers wait out their 201 s and fail
  their "active thread counter" assertion (raised as SYNTAX 93.964 in the Java
  thread, so not counted as a failure; the run ends with fewer assertions,
  about 250 s instead of 20). Natively too (native BSF4ooRexx, 1 run of 3:
  217 s, 99 controllers), and with `ByteArrayMemory` (2 of 3): the test under
  this load on 2 cores, not the memory.

With `testUnits/ooRexxUnit`'s own framework the counts are the same apart from
16 errors (`assertSameList`) and 30 in `lex` (its procedural groups, run first,
unregister beans that `BSF.CLS` keeps cached; not seen with ooRexx's
framework, nor with `lex/oo` alone).

**Compared with a native BSF4ooRexx** (02/11/2026): ooRexx r13346's `.deb`,
`BSF4ooRexx.cc` (same commit) compiled with g++ as its `Makefile-64-linux`
does, the same `bsf4oorexx.jar` and `BSF.CLS`, Java 21. With ooRexx's
framework every group gives exactly the counts of the table above, no
failures, no errors. With `testUnits/ooRexxUnit`'s framework, natively too: 16
errors in `rgf` (4), `java8` (6), `java6` (6), and 30 in `lex` (`java.lang.Class`
objects that no longer understand `GETCLASS`, SYNTAX 97.1). So none of those is
ours: they are that old framework with these groups.

```bash
g++ -fPIC -O2 -DUNIX -m64 -DBSF4REXX_64_BIT -DUSE_OREXX -I. -I/usr/local/include \
    -I$JAVA_HOME/include -I$JAVA_HOME/include/linux -shared -rdynamic \
    -o libBSF4ooRexx850.so BSF4ooRexx.cc -L/usr/local/lib -lrexx -lrexxapi -lpthread -ldl
# run with LD_LIBRARY_PATH=<that dir>:$JAVA_HOME/lib/server, BSF.CLS on PATH,
# bsf4oorexx.jar on CLASSPATH
```

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

With `testUnits/ooRexxUnit`'s own framework the counts are the same apart from
16 errors (`assertSameList`) and 30 in `lex` (its procedural groups, run first,
unregister beans that `BSF.CLS` keeps cached; not seen with ooRexx's
framework, nor with `lex/oo` alone).

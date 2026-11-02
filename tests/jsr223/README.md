# JSR-223: a Java program hosting Rexx

`Jsr223.java` is a plain Java program that runs Rexx through `javax.script`,
with BSF4ooRexx's script engine (`RexxScriptEngine`): no class of ours is named
in it. The module starts by itself, in the embedded mode, the first time
BSF4ooRexx calls one of its `native` methods (`Rexx.startIfNeeded`, called from
`Natives.call`).

```bash
env -u JAVA_TOOL_OPTIONS tests/jsr223/run.sh [DIST]    # DIST: /home/claude/work/dist by default
```

What a host needs (`run.sh`): `rexxj.jar` as a Java agent and on the class
path, `bsf4oorexx.jar` on the class path, and a large Java stack (`-Xss16m`).
Nothing else: `BSF.CLS` and the other `.cls` are found beside `rexx.img`
(`-Drexxjvm.home`, by default the directory of `rexxj.jar`), which the embedded
mode puts first in the module's `PATH`, as the `rexxj` script does.

21 checks: the engine by name and by extension; `eval`; `say` to the context's
writer; `Compilable` with arguments in `ScriptEngine.ARGV`; a binding read from
Rexx (`arg(arg())~scriptContext`); `Invocable` (`invokeMethod`,
`invokeFunction`, `getInterface` on a Rexx object and on the public routines);
Rexx filling a Java `List` it is given; syntax and run-time errors as
`ScriptException`; 200 calls in a row and 8 Java threads calling at once (each
runs the Rexx code on its own thread); a second engine with its own `.local`.

Results (02/11/2026, Java 21, 2 cores): all pass; 2.2 s to the first result
(the module starts and BSF4ooRexx initializes); about 20 ms per
`invokeFunction`, from one thread or from eight.

Why 20 ms: each `invokeFunction` makes about 5 calls to
`jniRexxSendMessageToRexxObject` and 1.7 to `jniUnregisterRexxObject` (the
cleanup of Java references to Rexx objects), all from a Java thread that is not
running wasm code, so each one is a foreign entry (a pthread and an `Instance`
of the module). A profile (JFR, 400 calls) puts creating those `Instance`s at
about 16% of the time; nearly all the rest is wasm code running (the
interpreter, running the Rexx side of each call), so most of it is the
interpreter's raw speed (`rexxcps`: 15–16 times slower than native). Not
compared with a native BSF4ooRexx yet.

Three things that are BSF4ooRexx's behaviour, not ours (its Java code runs
unchanged):

- `SAY` goes to the context's writer only if the writer is set **before the
  engine's first `eval`**: `RexxScriptEngine` does not replace `.output` once it
  is a `.bsf.outputStream` (its comment on `rexxRedirectStandardFilesCode`), so a
  writer set later is ignored. Lines carry the prefix `REXXout>`.
- `invokeFunction` looks for the public routines of the **latest** script
  evaluated.
- BSF4ooRexx's cleanup thread (`RexxCleanupRef.runnableCleaner_1`) is not a
  daemon thread: the JVM does not end when `main` returns, so the test calls
  `System.exit`.

BSF4ooRexx's own JSR-223 tests (`testUnits/bsf4rexx/rgf/G_misc/07_rexx_JSR_223.testGroup`,
part of the `rgf` group: `tests/bsf/README.md`) use the engine from a Rexx
program, where the module is already running; this test is the other case.

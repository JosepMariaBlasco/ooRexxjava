# ooRexx for Java

Running ooRexx under Java: the ooRexx interpreter itself (the C++
sources, unchanged but for a small patch) compiled to WebAssembly with
WASI, and run on any Java virtual machine by
[Endive](https://github.com/bytecodealliance/endive), a WebAssembly runtime
written in pure Java (no JNI, no native code): the Bytecode Alliance's
continuation of [Chicory](https://github.com/dylibso/chicory), on which this
project ran until 03/11/2026.

**Status:** proof of concept (01/11/2026). ooRexx trunk r13358 (the
ooRexx family's revision since 03/11/2026; r13346 before, which the figures
below were measured on unless they say otherwise), built
for `wasm32-wasip1-threads` with wasi-sdk 34, runs on OpenJDK 21 with
Endive 1.2.0 (Chicory 1.7.5 until 03/11/2026): multithreading (`START`, `GUARD ON WHEN`, `REPLY`),
conditions, streams, the queue, `SysFileTree`, `INTERPRET` and
`rxregexp` behave as in native ooRexx, and the Rexx Parser parsing its
own sources produces an element dump byte-for-byte identical to
native, about 3.4 times slower. External commands (`ADDRESS SYSTEM`,
`ADDRESS PATH`, `ADDRESS ... WITH` redirection) run as processes of the
host through Java's `ProcessBuilder`. The Rexx API server (external
data queues, registrations, the macrospace) is shared between processes,
as natively, through a daemon that is the launcher itself (see *The shared
API server* below). rxunixsys (`SysStat`, `SysSymlink`...) is embedded; what WASI has no notion of
(users, sessions, signals, owners) fails as unsupported. rxsock's IPv4 sockets (TCP and UDP) work through the launcher's java.nio.
SIGINT (Ctrl-C), SIGTERM and SIGHUP raise HALT in the running program, as
natively; a second one while the program has not yet seen the first HALT (a
program hung where it never looks, waiting on a semaphore, say) ends the
process, 128 + the signal's number, where natively only SIGKILL would
(`tests/halt.sh`). When no more threads can be created (a process limit, memory),
`START` and the like fail with Rexx's Error 48.1, as natively; an
`OutOfMemoryError` or another `VirtualMachineError` that kills any thread ends
the process at once with 70, instead of leaving the other threads waiting for it. A Java program can host the interpreter (see *Rexx from Java* below, and JSR-223), and Rexx can use Java through BSF4ooRexx, unchanged. ooRexx's official test suite (test/trunk
r13347), run with `dist-test/` (`build-test.sh`, the variant with the
suite's native libraries) by a user other than root: **401 of 405** test
groups pass (`results/suite-jvm-r13346-sockets.csv`). The four failures depend
on timing and pass when run again: `RexxContext`, `TRACE`,
`TRACE_TraceObject`, and `MutexSemaphore`, whose `test_exclusion` can leave a
thread waiting for ever when the main thread wins the last acquisition (the
test's race; it shows here because threads are scheduled more slowly).
After the embedded mode (`suite-jvm-r13346-embedded.csv`): 404 of 405, only
`TRACE` failing. After the mini-JNI (`suite-jvm-r13346-jni.csv`): 404 of
405, only `BEEP` failing (timing); `MutexSemaphore` passed its tests but
hung at exit (the race above) and had to be killed. With BSF4ooRexx linked
in (`suite-jvm-r13346-bsf.csv`): 404 of 405, only `GUARD` failing (timing).
With the threads' C stack at 256 KB (`suite-jvm-r13346-threadstack.csv`):
402 of 405; `TRACE_TraceObject` and `scclient` failed and pass when run
again, and `ProcessInvocation` went over the 300 s limit (253 s on the run
before), on a machine slower than usual at the time (rexxcps 10-15% below
its own earlier figure); the slow groups, run alone, take the same with
8 MB and with 256 KB. On Endive 1.2.0, through the warm JVM (`REXXJ_SERVER`,
`suite-jvm-r13346-endive-server.csv`, 03/11/2026): 404 of 405, only
`rxsubcom` failing, which fails through the warm JVM with Chicory too
(`rxsubcom` not found as a command) and passes without it.
On ooRexx r13358 with test/trunk r13359 (03/11/2026, Endive, through the warm
JVM, `suite-jvm-r13358-server.csv`): 404 of 405 in 3 min 20 s, 24,440 tests,
again only `rxsubcom`; it and `ProcessInvocation` (60 tests, with the six new
`Terminate()` cases of #2107, `testbinaries/detach_root_terminate`, one more
tool of `dist-test`'s `rexx.wasm`) pass without the warm JVM
(`suite-jvm-r13358-alone-rxsubcom-processinvocation.csv`). With BSF4ooRexx
r1131 and commands looked up in their own `PATH` (the warm JVM had looked them
up in its own, that of whoever started it: hence `rxsubcom`), through the warm
JVM (`suite-jvm-r13358-bsf1131-server.csv`, 03/11/2026): **405 of 405** in
4 min 4 s, 24,440 tests, 0 failures, 0 errors.

That count includes, as passed with no tests, the groups the suite restricts
to Unix (`SysUnix`, `tilde`, `SysGetMessage`, `ncurses`, `utilities/rxapi`):
its framework does not know the OS "WASI" and skips them.
`scripts/suite-with-wasi.sh` makes a copy of the suite whose framework knows
it; there **396 of 405** pass (`suite-jvm-r13346-sockets-wasi-os.csv`):
`tilde` passes; `SysUnix` fails `crypt`'s `$3$` and six `SysStat` of the
native rxapi's socket (ours is named differently); there are no message
catalogs (`SysGetMessage`), no ncurses, and `utilities/rxapi` tests the
native daemon; the rest of the failures are the timing ones (`BEEP`, `GUARD`
among them on that run). Earlier: 398 before sockets
(`suite-jvm-r13346-rxunixsys.csv`), 396 before rxunixsys
(`suite-jvm-r13346-fs.csv`).
Run as root, 395 (`suite-jvm-r13346-fs-root.csv`): `File`, `Stream` and
`SysFileXXX` then fail as they would natively, because root may write to
read-only files. Before the file system calls went through the launcher
(permissions, rename, symbolic links, times, `chdir`): 388, run as root
(`suite-jvm-r13346-rxapi.csv`); 384 with the native libraries but without
the shared API server (`suite-jvm-r13346-api.csv`), 379 with commands
(`suite-jvm-r13346-cmd.csv`), 353 before commands (`suite-jvm-r13346.csv`).
The full report is in [`docs/poc-20261101.md`](docs/poc-20261101.md)
(written on r13289).

## Building

```bash
./build.sh [work-directory]      # default: ~/oorexx-jvm
```

Needs `svn`, `git`, `cmake`, `make`, Java 11 or later (22 or later for the jar's fallback without `Unsafe`) and Maven, and network
access (wasi-sdk 34, ooRexx trunk r13358 from SourceForge,
[`oorexxwasm`](https://github.com/JosepMariaBlasco/oorexxwasm)'s generic
WebAssembly layer, its `patches/0001-wasm-generic-layer.patch`, from the
commit `OOREXXWASM_REV` in `build.sh`, BSF4ooRexx's SVN `branches/850` at
the revision `BSF_REV` from SourceForge, Maven Central).
The result is `dist/`, self-contained: `dist/rexxj program.rex` runs a
Rexx program.

| In `dist/` | What |
|---|---|
| `rexxj` | The launcher script. Java classes that Rexx code uses are found through `CLASSPATH`. |
| `bsf4oorexx.jar`, `BSF.CLS`, `BSF_OnTheFly.cls`, `rgf_util2.rex` | BSF4ooRexx (Apache 2.0): its Java side and its Rexx packages. Its native library is in `rexx.wasm`. |
| `rexx`, `rexxc`, `rxqueue`, `rxsubcom` | The usual names, as commands: `rexx` is `rexxj`; the utilities are in the interpreter's binary (`rexxj --orx-wasm-rexxc` ...). |
| `rexxj.jar` | The Java launcher with `rexx.wasm` translated to JVM bytecode at build time (Endive's build-time compiler): about 0.6 s to run `hello.rex`, against about 3 s when the wasm is translated at start-up. |
| `rexx.wasm` | The interpreter as WebAssembly. Not needed by `rexxj`; `java -jar rexxj.jar rexx.wasm program.rex` translates it at start-up instead. |
| `rexx.img` | The interpreter's image. |
| `rxregexp.cls` | The Rexx side of `rxregexp` (the native side, like `rxmath` and `hostemu`, is embedded in the interpreter). |
| `json.cls`, `yaml.cls`, `logicalwrapper.cls`, `dateparser.cls`, `csvStream.cls` | The extensions written in Rexx alone. |
| `samples/` | ooRexx's samples (the test suite looks for them at `.RexxInfo~executable/../samples`). |

Running `build.sh` again over the same work directory reuses the
downloads and the patched sources.

### The variant for the test suite

```bash
./build-test.sh [work-directory]
```

after `build.sh`, builds `dist-test/`: `dist/` with the test suite's native
libraries (`testbinaries/`: `orxfunction`, `orxmethod`, `orxclassic`,
`orxclassic1`, `orxexits`, `orxinvocation`) linked into `rexx.wasm`, since
WebAssembly has no `dlopen`, and `testbinaries/rexxinstance` as one more tool
(`dist-test/rexxinstance`, that is, `rexxj --orx-wasm-rexxinstance`). It is
`oorexxwasm`'s `wasm/build-test.sh` (its patch 0007) for WASI, and it is for
the API test groups only: run the suite with `dist-test/rexxj`. It also
carries the mini-JNI's test (`dist-test/jnitest`, see `tests/jni/`).

### The saved build

The branch `saved-build` holds the last build (binaries, not sources: one
orphan commit, replaced each time), so that a fresh checkout need not
compile:

```bash
scripts/saved-build.sh fetch [work-directory]   # default: /home/claude/work
```

restores `dist/`, `dist-test/` and the build tree when its `MANIFEST` (the hashes of
`build.sh`, `build-test.sh`, the patch, the toolchain file, `compat/` and `host/`) matches
the checkout, and fails otherwise; `build.sh` over the same directory is
then incremental. The build tree holds absolute paths: it is only reusable
at the directory it was built in. After a build, `scripts/saved-build.sh
save` and `publish` replace it.

## The shared API server

As natively, the first interpreter that needs the Rexx API server (an
external queue, a registration, the macrospace) starts it, and it outlives
the session: it is the launcher run as `rexx --orx-wasm-rxapi` (the same
Java, the same jar), detached, in a session of its own if `setsid` is
there. Interpreters reach it over a Unix domain socket in `java.io.tmpdir`,
`.ooRexx-jvm-<user>-rxapi-<version>`, that only the user can open, with a
`.lock` beside it that keeps a second daemon from starting. The C side is
in the WASI patch (`rexxapi/common/platform/unix/SysHostConnection.cpp`),
the Java side in `host/.../RxApi.java`; the server keys sessions by
process id, so `getpid()` and `getppid()` give the real ones.

If the JVM is older than 16 (no Unix domain sockets), the daemon cannot
be started or reached, or `ORX_WASM_RXAPI=inprocess` is set, each
interpreter uses a server of its own, inside the process, as `oorexxwasm`
does in the browser: queues are then not shared with other processes.
To stop the daemon, kill the process (`pkill -f orx-wasm-rxapi`); after a
rebuild, do so, or it goes on running the old jar.

## Rexx from Java (the embedded mode)

A Java program can host the interpreter with `rexxj.jar` on its class path
(class `rexxjvm.Rexx`; the interpreter's files are looked for beside the
jar, or in `-Drexxjvm.home`):

```java
try (Rexx rexx = Rexx.start();
     Rexx.Interpreter in = rexx.newInterpreter()) {
    String r = in.callProgram("prog.rex", "a", "b");   // one Rexx argument each
    String s = in.eval("return 6*7");                    // code run as a routine
}
```

Each call runs on the calling Java thread, from any number of threads at
once; a result is a string (`null`: none), and a condition, normally a
SYNTAX error, is thrown as `Rexx.RexxException` (code, error text, program,
line). Several interpreter instances can be open at once, each with its own
`.local`. The module runs as `rexx --orx-wasm-embedded`, which waits for
calls; how calls enter it (a pthread per call, handed by `thread-spawn` to
the calling Java thread, attached to the instance) is in
[`docs/java-from-rexx-design.md`](docs/java-from-rexx-design.md). Endive's
frames live on the calling thread's Java stack: give the JVM `-Xss16m` or
more (`dist/rexxj` does), or deep Rexx recursion fails. Strings only: Java objects cross through BSF4ooRexx (below).
Each `Rexx.start()` is a module of its own, with its own memory, in the same
JVM (see "Several runs in one JVM"). Tests: `tests/embed/`.

## Java from Rexx: BSF4ooRexx

[BSF4ooRexx](https://sourceforge.net/projects/bsf4oorexx/), Rony G.
Flatscher's bridge between ooRexx and Java, is part of `dist/`, unchanged:
its native library compiled into `rexx.wasm`, its Java side in
`bsf4oorexx.jar`, `BSF.CLS` beside them. No native library, no `libjvm`: the
JVM is the one running the interpreter.

```rexx
sb = .bsf~new("java.lang.StringBuilder")
say sb~append("x")~append(42)~toString     -- x42
::requires "BSF.CLS"
```

Java classes of your own go on the `CLASSPATH`. Java code can call Rexx
objects back (listeners, comparators), from any Java thread: the Rexx code
then runs on that thread. Underneath there is a JNI inside the module (the
mini-JNI: `jobject`, `jclass`, `jmethodID` are handles of an object table
kept by the launcher; each `JNIEnv` function is a call to the launcher,
about 0.5 µs), and `rexxj.jar` doubles as a Java agent that sends
BSF4ooRexx's `native` methods to the module. BSF4ooRexx's own tests pass
(`rgf`, `lex`, `java6`, `java8`, and `rgf_multithreading`, up to 10,201
threads).
Design and limits in [`docs/java-from-rexx-design.md`](docs/java-from-rexx-design.md);
tests in `tests/bsf/` and `tests/jni/`. A Java program that hosts the
interpreter (`rexxjvm.Rexx`) and uses BSF4ooRexx needs `-javaagent:rexxj.jar`
too.

The other direction works as well: a Java program can run Rexx through
JSR-223 (`javax.script`), with BSF4ooRexx's own script engine, without naming
any class of ours. It needs `rexxj.jar` as a Java agent and on the class path,
`bsf4oorexx.jar` on the class path, and `-Xss16m`; the module starts by itself
the first time BSF4ooRexx needs it, and finds `BSF.CLS` beside `rexx.img`.

```java
ScriptEngine rexx = new ScriptEngineManager().getEngineByName("rexx");
Object r = rexx.eval("return reverse('hi!')");    // "!ih"
```

```bash
java -Xss16m -javaagent:dist/rexxj.jar -cp dist/rexxj.jar:dist/bsf4oorexx.jar:. MyHost
```

Test and its notes in `tests/jsr223/` (about 20 ms per `invokeFunction` for
now, 1 ms natively: mostly the interpreter's speed).

## Several runs in one JVM

The launcher keeps everything a program needs in a run (`host/.../Run.java`):
its arguments, environment, current directory, standard streams, linear
memory, threads, signals, and what it opens; the module translated to JVM
bytecode is shared, so the code the JIT compiled for one run serves the next.
`rexxj` is one run that owns the JVM, as before. A Java host can run many
programs in one JVM, one after another or at once, and end any of them; run
again in a warm JVM, `say "hi"` takes 40–90 ms instead of about a second, and
the Rexx Parser checking one of its files 4.4 s instead of 11.2. The design,
and what a server for `rexx` still needs, are in
[`docs/java-from-rexx-design.md`](docs/java-from-rexx-design.md) ("Runs:
several in one JVM"); the test is `tests/multirun/`. `Run` is not public yet.

## A warm JVM: `rexxjs`

With `REXXJ_SERVER` set, `rexxj` (and so `rexx`, `rexxc`, `rxqueue`,
`rxsubcom`, which run it) hands the program to `rexxjs`, a small native client
(`client/rexxjs.c`, Linux, built by `build.sh` when there is a C compiler;
on Windows `rexxjs.exe`, below).
`rexxjs` sends its arguments, environment, current directory and pid to
`rexxjvm.Server`, a JVM that keeps the module warm and runs each program as a
run of its own (above); the server opens the client's standard streams itself
(`/proc/PID/fd/0`, `1`, `2`), the client sends its signals on (a HALT, as
natively) and exits with the program's exit code. Where there is no `/proc`
(macOS, Windows), or on Linux with `REXXJ_SERVER_RELAY` set, the streams are
relayed through the socket instead, and the client itself runs the commands
the program issues, with its own standard streams, so they get its terminal,
pipes and files as natively (`host/.../Relay.java`). The first `rexxjs` starts the
server, detached (`$TMPDIR/.ooRexx-jvm-USER-server-KEY`, one per user and
build, its output in `….log`); it ends after 15 minutes with nothing running
(`-Drexxjvm.server.idle`, seconds, in `REXXJ_JAVA_OPTIONS`). When it cannot do
that (no server can start, a standard stream that is closed, or a socket
unless relayed), `rexxjs` runs `rexxj` as usual. The server needs Java 16 or
later (Unix domain sockets); on Java 11 to 15 it ends at once, and `rexxjs`,
which watches the server it started (a pipe on Unix, its process on Windows),
runs `rexxj` without waiting for a socket that will not come. The suite on Java 11 (one JVM per
group, 25 min): 398/405; the seven that fail share the API server between
processes (`ProcessInvocation`, `ProcessRexxStart`, `RexxQueue`, `rxQueue`,
`rxsubcom`, `Macrospace`), and `TRACE_TraceObject`, timing
(`results/suite-jvm-r13358-java11.csv`).

```bash
export REXXJ_SERVER=1
rexx program.rex        # the first time ~2 s (the server starts); then ~0.1 s
```

For now it is meant for running many short programs, ooRexx's test suite above
all: through it the suite takes 4 minutes and a half instead of about 21
(405/405 groups pass; natively 2 min 24 s: about twice as fast), and the Rexx Parser checking one of its files about
4 s instead of 9 (natively 2.4); a program with BSF4ooRexx, 0.2–0.3 s instead
of 2.3. Each program has a class loader of its own for BSF4ooRexx and the
client's `CLASSPATH`, so its Java classes and their statics start afresh;
Java's `System.out`, `System.err` and `System.in` are the client's. What
differs from `rexxj`: programs share one JVM, so a crash, an
`OutOfMemoryError` or Java code that calls `System.exit` affects all of them;
and Java code sees the server's environment and current directory (`/`), not
the client's (`docs/java-from-rexx-design.md`, "The warm JVM and its client").

```bash
rexxjs --orx-server-stop    # ends this build's server and the programs it runs
```

## Windows

`dist/` runs on Windows as it is, with Java 11 or later: `rexx.cmd`, `rexxj.cmd`
and the tools' `.cmd` (`launchers/windows/`, which `build.sh` copies into
`dist/`). Build on Linux; the jar and `rexx.wasm` are the same everywhere.
`.github/workflows/windows.yml` runs `tests/windows/run.ps1` on a Windows runner
with Java 21 and 25, over the saved build's `rexx.wasm`.

The interpreter is a Unix one (WASI), so on Windows each drive is a directory of
its file system, as in MSYS and Git Bash: `C:\Users\x` is `/c/Users/x`
(`host/.../WinPath.java`). The launcher translates where Windows paths come in or
go out: the current directory, `PATH` (`;` and `:`), the program's name on the
command line (`rexx C:\x\p.rex`, `rexx sub\p.rex`), `.RexxInfo~executable` and
`~libraryPath`, `HOME` and `TMPDIR` (from `USERPROFILE` and `TEMP` when they are
not set: `qualify('~')`, `cd ~` and temporary files, which would otherwise go to
`/tmp`), and a command's name, directory, `PATH`, `HOME` and `TMPDIR`. In a
command line for `cmd.exe`, every word that is a path on a drive (`/c`,
`/d/a/x`) goes to Windows' form (`"/d/a/rexx.cmd" p < /d/in` runs as
`"D:\a\rexx.cmd" p < D:\in`), and so do `ADDRESS PATH`'s arguments that are one;
other environment variables stay as Windows has them (`TEMP` is `C:\...`).

Commands: `ADDRESS SYSTEM`, `COMMAND`, `""` and `SH` run through `%ComSpec%`
(`cmd.exe /s /c`), as natively on Windows; `ADDRESS BASH` and the other shells are
looked up in `PATH` (Git Bash's `bash`, say); `ADDRESS PATH` looks the command up
with `PATHEXT`. There is no `ADDRESS CMD`: the Unix interpreter does not have it.

ooRexx's test suite (test/trunk r13359) on Windows, Java 25, one JVM per group
(`.github/workflows/windows-suite.yml`, on demand; `dist-test/` with its tools'
`.cmd`, run from Git Bash with `oorexxwasm`'s `tests/run-suite.sh`,
04/11/2026, `results/suite-jvm-r13358-windows-java25-final.csv`): **394 of
405** in about 19 minutes, 24,440 tests, 23 failures, 1 error; through the warm JVM
(`rexxjs.exe`, the workflow's default since 04/11/2026,
`results/suite-jvm-r13358-windows-java25-server.csv`) the same 394 in 6 minutes. The interpreter
under Node on Windows (`oorexxwasm`) has 393. What fails is a Unix-flavoured
interpreter on a Windows host, by design: the suite takes WASI for a Unix and
expects `sh` (`ADDRESS`, `OPTIONS`, `SysSearchPath`, `File~searchPath`), `id -u`
for its root check (`STREAM`, `Stream`), `rexx` as the executable's name
(`RexxInfo`, here `rexx.cmd`) and case-sensitive file names (`CALL`); read-only
directories stay writable on Windows (`File`), and `SysFileTree` of `TEMP` meets
a file whose attributes it does not expect. `TRACE` and `SysSleep` are timing
(Windows' timer). The first run had 377 (`suite-jvm-r13358-windows-java25.csv`),
before commands got drives' paths in Windows' form, `HOME` and `TMPDIR`, and the
test tools' `.cmd`.

Endive's WASI reads files' attributes through a view Windows does not have
(`"unix:*"`), and every `stat` failed there; `host/.../WinWasi.java` replaces its
three functions that need it on Windows (reported: bytecodealliance/endive#280;
the fix, bytecodealliance/endive#282, is checked on Windows by
`.github/workflows/windows-endive.yml`, with WinWasi off: `-Drexxjvm.winwasi=false`).
The warm JVM works on Windows too: `rexxjs.exe` (`client/rexxjs_win.c`, built
with MinGW-w64: `x86_64-w64-mingw32-gcc -O2 -municode -o rexxjs.exe
client/rexxjs_win.c -lws2_32 -static`; not built by `build.sh` yet, the
workflows build it), next to the `.cmd` launchers, which run it when
`REXXJ_SERVER` is set. It needs Windows 10 1803 or later (`AF_UNIX` sockets);
the streams are always relayed (no `/proc`), the server's socket is in `%TEMP%`,
Ctrl+C and Ctrl+Break are a HALT (with `rexxj.cmd`, Ctrl+Break is Java's thread
dump), and when it cannot run the program it runs `rexxj.cmd`. `say 'hi'` takes
60–80 ms instead of 0.5–0.9 s on the runner; `tests/windows` passes both ways,
and the suite takes 6 minutes instead of 19 (below). `REXXJS_DEBUG` set: why it
runs `rexxj.cmd`, or cannot start a command, to standard error.
The server frees each program's memory as it ends: on Windows `malloc` commits
the whole 2 GB reservation, and blocks left to the garbage collector kept other
JVMs from starting. `Unsafe`:
on Java 23 and later the launchers pass `--sun-misc-unsafe-memory-access=allow`,
so that JDK 24 and later do not warn on every run (the version is read from the
`release` file of the `java` in `PATH`; without one, as behind Oracle's
`javapath` shim, the option is not passed and the warning shows). On Windows
there is no fallback without `Unsafe` (a mapped file there cannot be deleted
while open).

## Speed

`rexxcps`, 2 cores, Java 21: about 2.6 M clauses/s as it is (4 s, with the
JVM's warm-up in it), 4.1–4.2 M over 26 s (`averaging=800`; Java 25: about 10%
more); natively, ooRexx r13346 runs it at 9.7–10.7 M on the same machine.
Four things got it there from 0.56 M (02–04/11/2026):

- **The compiler, patched** (`host/chicory-patch/`, written for Chicory 1.7.5;
  Endive 1.2.0's compiler has the same two constants). The runtime translates
  the module to JVM bytecode, and HotSpot never JIT-compiles a method over
  8,000 bytes of bytecode. Chicory 1.7.5 and Endive 1.2.0 dispatch indirect calls (every C++
  virtual call) through one `switch` per function signature with up to 1,024
  cases, over that limit: those methods ran interpreted for ever. The patch
  lowers two constants of the compiler (1,024 to 128, 4,096 to 512);
  `scripts/endive-compiler.sh` (run by `build.sh`) installs it in the local
  Maven repository as `run.endive:compiler:1.2.0-orx1`, from the
  official jar, which is not touched. 0.63 to 1.88 M (with Chicory). Reported to
  Endive (bytecodealliance/endive#273), which means to fix it.
- **`FlatMemory`**: the linear memory as one flat block outside the heap (see
  "Threads and their stacks"), with `sun.misc.Unsafe`. JDK 24 and later warn
  about it on its first use (JEP 498), unless the JVM is started with
  `--sun-misc-unsafe-memory-access=allow`, which the launchers pass on Java 23
  and later (the version read from the `release` file of the `java` in `PATH`). A JVM that refuses it (`=deny`, a later JDK's default) gets,
  on Java 22 and later, the same memory through `java.lang.foreign` instead,
  by itself, about a third slower (`-Drexxjvm.memory=mapped` asks for it;
  `docs/java-from-rexx-design.md`). Build with Maven on JDK 22 or later for
  that: `scripts/mvn22.sh`, which `build.sh` uses, finds one if there is.
- **The relaxed bounds check** (04/11/2026): for a module whose memory may
  reach the runtime's 2 GB (`rexx.wasm`), a plain access checks only that its
  address is below 2 GB, a test of the sign, with a guard reserved past the end
  of the block; the check against the current size, a field the JIT must read
  again after every call, was about a fifth of the time (26 s run: 3.1 to 3.9 M;
  no check at all, 4.0 M). An access between the current size and 2 GB, which
  only a wild pointer makes, reads or writes the reserve instead of trapping;
  blocks and atomics are checked as before. `-Drexxjvm.memcheck=strict` checks
  every access against the current size, as the specification says (`FlatMemory`,
  `tests/memory/`). ooRexx's suite with it: 405/405 (r13358, test/trunk r13359,
  through the warm JVM, `results/suite-jvm-r13358-bsf1131-server-relaxed.csv`).
- **`call_indirect` resolved at build time** (04/11/2026, `host/static-table/`):
  `rexx.wasm`'s table never changes, so the function behind each table index is
  known when the module is translated; a step after Endive's compiler switches
  on the index straight to the function, instead of looking the table up on
  every C++ virtual call (half of the dispatch's 18%). Anything else (an empty
  entry, a wrong signature, another table) goes to Endive's own code, unchanged
  (`tests/static-table/`). 26 s run: 3.80 to 4.17 M (+10%, medians of five);
  the 4 s run hardly changes (more to compile while warming up). The suite:
  405/405 (`results/suite-jvm-r13358-bsf1131-server-static-table.csv`).

Profile of the 26 s run before the last two (async-profiler, 04/11/2026): 49% of the
time in memory accesses, much of it the interpreter's
own loads; 18% in `call_indirect`'s dispatch (the table looked up on every C++
virtual call); 6% in `Thread.isInterrupted`, checked on every call; 4% in
globals (the C stack pointer, through Endive's `Instance.global`). JDK 21's JFR
walks the module's stacks badly (a few hundred samples in 27 s); JDK 25's
samples at safepoints, which charges `call_indirect` with twice its share.
Binaryen's `wasm-opt -O3` (the C++ is already `-O3`): 3.4 times slower, as its
inlining takes `RexxActivation::run` over HotSpot's limit; without inlining,
about 10% faster, not worth Node and Binaryen in the build.

`-XX:-DontCompileHugeMethods`, which lifts HotSpot's limit for every method,
no longer helps (1.75 M) and makes start-up slower; `REXXJ_JAVA_OPTIONS`
passes options like it to the JVM if wanted:

```bash
REXXJ_JAVA_OPTIONS=-Xmx4g rexxj program.rex
```

## Threads and their stacks

Every Rexx thread (`START`, `REPLY`, a Java thread calling Rexx) is a wasm
thread: a Java thread of its own, with a C stack taken from the module's
linear memory. The main thread has 8 MB; the others **256 KB** by default,
enough for about 2,600 levels of Rexx recursion (natively, with 8 MB, about
22,500) and for about 8,000 live threads: the linear memory is 2 GB at most,
and the stacks of the threads alive at once must fit in it.
The linear memory is not Java heap: it is one block outside it
(`FlatMemory`), reserved at its maximum size, whose pages the operating
system provides only when they are first touched; so a stack costs what it
uses, not its size (200 live threads with 8 MB stacks: 245 MB of resident
memory in all).
Recursion deeper than the stack ends in error 11, as natively. To change it,
`REXXJVM_THREADSTACK` in the environment, or `-Drexxjvm.threadstack` for a
Java host (bytes, or with `K` or `M`: `2M` gives about 25,000 levels;
from 128K to 256M):

```bash
REXXJVM_THREADSTACK=2M rexxj deep.rex
```

Why, and the measurements, in
[`docs/java-from-rexx-design.md`](docs/java-from-rexx-design.md) ("Memory
per thread").

## Contents

| Path | What |
|---|---|
| `build.sh` | Rebuilds everything from scratch. |
| `build-test.sh` | Builds `dist-test/`, the variant with the test suite's native libraries. |
| `scripts/saved-build.sh` | Saves the build on the branch `saved-build` and restores it. |
| `scripts/endive-compiler.sh` | Installs Endive's compiler with the patch of `host/chicory-patch/` in the local Maven repository (`build.sh` runs it). |
| `0001-wasi-on-top-of-oorexxwasm-0001.patch` | The WASI patch (three files, what is WASI's alone), applied on top of `oorexxwasm`'s `patches/0001-wasm-generic-layer.patch`, the layer common to every WebAssembly build. |
| `compat/` | Headers and stubs for what wasi-libc lacks; `orx_wasi_host.c`, the host functions of `oorexxwasm`'s `SysWasmHost.hpp` (commands, `.RexxInfo~executable`, `~libraryPath`) over functions the launcher gives the module (import module `rexxjvm`, `host/.../Host.java`); `orx_wasi_embed.cpp`, the embedded mode's side in the module; `orx_jni.c` and `jni/jni_md.h`, the mini-JNI (import module `jni`, `host/.../Jni.java`); `orx_bsf.c`, what BSF4ooRexx's library needs to be linked in (`dlopen`, the table of linked libraries). |
| `wasi-p1-threads.cmake` | wasi-sdk toolchain file for `wasm32-wasip1-threads`. |
| `launchers/` | `unix/rexxj`, the launcher (`build.sh` copies it into `dist/`); `windows/`, `rexx.cmd`, `rexxj.cmd` and the tools' launchers for Windows ("Windows"). |
| `client/` | `rexxjs.c`, the native client of the warm JVM ("A warm JVM: `rexxjs`"). |
| `host/` | The Java launcher (Maven; Endive's WASI, shared memory, and `thread-spawn`; the `embedded` profile builds `rexxj.jar` with the interpreter translated at build time; `FlatMemory`, the linear memory; `chicory-patch/`, the patch to Endive's (formerly Chicory's) compiler, README "Speed"). |
| `tests/` | Small test programs (`hello.rex`, `conc.rex`, `err.rex`, `sys.rex`, `cmd.rex`, `rxapi.rex`, `t.cpp`); `embed/`, the embedded mode's tests; `jni/`, the mini-JNI's; `bsf/`, BSF4ooRexx's; `jsr223/`, a Java program hosting Rexx through JSR-223; `windows/`, the checks the Windows CI job runs. |
| `docs/` | Reports and designs (`java-from-rexx-design.md`: Java from Rexx and Rexx from Java). |
| `prototypes/` | Experiments that are not part of the build (`threads/`: Java threads entering the module; `memory-bench/`: the linear memory with `Unsafe` and with `java.lang.foreign`, standalone). |
| `results/` | Runs of ooRexx's official test suite (one CSV per run, from `oorexxwasm`'s `tests/run-suite.sh`). Run it with `JAVA_TOOL_OPTIONS` unset: Java reports it on standard error ("Picked up ..."), and tests that expect no error output from `rexx` fail. |

## Trademarks and Legal Notices

* **ooRexx for Java** is a project by Rony Flatscher and Josep Maria Blasco.
* **Java**: Java is a registered trademark of Oracle and/or its affiliates.
  This project is an independent implementation and is not affiliated
  with, sponsored, authorized, or otherwise endorsed by Oracle America, Inc.
* **ooRexx**: Open Object Rexx and ooRexx are trademarks of the Rexx
  Language Association (RexxLA).
* **WASI / Endive / Chicory**: All other product names, trademarks, and registered
  trademarks are the property of their respective owners.

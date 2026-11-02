# ooRexx for Java

Running ooRexx under Java: the ooRexx interpreter itself (the C++
sources, unchanged but for a small patch) compiled to WebAssembly with
WASI, and run on any Java virtual machine by
[Chicory](https://github.com/dylibso/chicory), a WebAssembly runtime
written in pure Java (no JNI, no native code).

**Status:** proof of concept (01/11/2026). ooRexx trunk r13346 (the
ooRexx family's revision), built
for `wasm32-wasip1-threads` with wasi-sdk 34, runs on OpenJDK 21 with
Chicory 1.7.5: multithreading (`START`, `GUARD ON WHEN`, `REPLY`),
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
natively (`tests/halt.sh`). A Java program can host the interpreter (see *Rexx from Java* below); not available yet: Java from Rexx. ooRexx's official test suite (test/trunk
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
8 MB and with 256 KB.

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

Needs `svn`, `git`, `cmake`, `make`, Java 11 or later and Maven, and network
access (wasi-sdk 34, ooRexx trunk r13346 from SourceForge,
[`oorexxwasm`](https://github.com/JosepMariaBlasco/oorexxwasm)'s generic
WebAssembly layer, its `patches/0001-wasm-generic-layer.patch`, from the
commit `OOREXXWASM_REV` in `build.sh`, BSF4ooRexx from the commit `BSF_REV`
of its Git import, `JosepMariaBlasco/BSF4ooRexx`, Maven Central).
The result is `dist/`, self-contained: `dist/rexxj program.rex` runs a
Rexx program.

| In `dist/` | What |
|---|---|
| `rexxj` | The launcher script. Java classes that Rexx code uses are found through `CLASSPATH`. |
| `bsf4oorexx.jar`, `BSF.CLS`, `BSF_OnTheFly.cls`, `rgf_util2.rex` | BSF4ooRexx (Apache 2.0): its Java side and its Rexx packages. Its native library is in `rexx.wasm`. |
| `rexx`, `rexxc`, `rxqueue`, `rxsubcom` | The usual names, as commands: `rexx` is `rexxj`; the utilities are in the interpreter's binary (`rexxj --orx-wasm-rexxc` ...). |
| `rexxj.jar` | The Java launcher with `rexx.wasm` translated to JVM bytecode at build time (Chicory's build-time compiler): about 0.6 s to run `hello.rex`, against about 3 s when the wasm is translated at start-up. |
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
[`docs/java-from-rexx-design.md`](docs/java-from-rexx-design.md). Chicory's
frames live on the calling thread's Java stack: give the JVM `-Xss16m` or
more (`dist/rexxj` does), or deep Rexx recursion fails. Strings only: Java
objects cross through BSF4ooRexx (below).
One module per JVM for now. Tests: `tests/embed/`.

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

## Threads and their stacks

Every Rexx thread (`START`, `REPLY`, a Java thread calling Rexx) is a wasm
thread: a Java thread of its own, with a C stack taken from the module's
linear memory, which is all Java heap. The main thread has 8 MB; the others
**256 KB** by default, enough for about 2,600 levels of Rexx recursion
(natively, with 8 MB, about 22,500) and for about 8,000 live threads (512 KB
would exhaust Java's default heap with the 2,601 threads of BSF4ooRexx's
stress test).
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
| `0001-wasi-on-top-of-oorexxwasm-0001.patch` | The WASI patch (three files, what is WASI's alone), applied on top of `oorexxwasm`'s `patches/0001-wasm-generic-layer.patch`, the layer common to every WebAssembly build. |
| `compat/` | Headers and stubs for what wasi-libc lacks; `orx_wasi_host.c`, the host functions of `oorexxwasm`'s `SysWasmHost.hpp` (commands, `.RexxInfo~executable`, `~libraryPath`) over functions the launcher gives the module (import module `rexxjvm`, `host/.../Host.java`); `orx_wasi_embed.cpp`, the embedded mode's side in the module; `orx_jni.c` and `jni/jni_md.h`, the mini-JNI (import module `jni`, `host/.../Jni.java`); `orx_bsf.c`, what BSF4ooRexx's library needs to be linked in (`dlopen`, the table of linked libraries). |
| `wasi-p1-threads.cmake` | wasi-sdk toolchain file for `wasm32-wasip1-threads`. |
| `host/` | The Java launcher (Maven; Chicory's WASI, shared memory, and `thread-spawn`; the `embedded` profile builds `rexxj.jar` with the interpreter translated at build time). |
| `tests/` | Small test programs (`hello.rex`, `conc.rex`, `err.rex`, `sys.rex`, `cmd.rex`, `rxapi.rex`, `t.cpp`); `embed/`, the embedded mode's tests; `jni/`, the mini-JNI's; `bsf/`, BSF4ooRexx's. |
| `docs/` | Reports and designs (`java-from-rexx-design.md`: Java from Rexx and Rexx from Java). |
| `prototypes/` | Experiments that are not part of the build (`threads/`: Java threads entering the module). |
| `results/` | Runs of ooRexx's official test suite (one CSV per run, from `oorexxwasm`'s `tests/run-suite.sh`). Run it with `JAVA_TOOL_OPTIONS` unset: Java reports it on standard error ("Picked up ..."), and tests that expect no error output from `rexx` fail. |

## Trademarks and Legal Notices

* **ooRexx for Java** is a project by Rony Flatscher and Josep Maria Blasco.
* **Java**: Java is a registered trademark of Oracle and/or its affiliates.
  This project is an independent implementation and is not affiliated
  with, sponsored, authorized, or otherwise endorsed by Oracle America, Inc.
* **ooRexx**: Open Object Rexx and ooRexx are trademarks of the Rexx
  Language Association (RexxLA).
* **WASI / Chicory**: All other product names, trademarks, and registered
  trademarks are the property of their respective owners.

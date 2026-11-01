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
API server* below). Not available yet: sockets, Java from Rexx, HALT
(Ctrl-C). ooRexx's official test suite (test/trunk
r13347), run with `dist-test/` (`build-test.sh`, the variant with the
suite's native libraries) by a user other than root: 396 of 405 test groups
pass (`results/suite-jvm-r13346-fs.csv`; of the failures, `TRACE` depends on
timing, and `MutexSemaphore` hung once at exit and passes when run again).
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

Needs `svn`, `cmake`, `make`, Java 11 or later and Maven, and network
access (wasi-sdk 34, ooRexx trunk r13346 from SourceForge,
[`oorexxwasm`](https://github.com/JosepMariaBlasco/oorexxwasm)'s generic
WebAssembly layer, its `patches/0001-wasm-generic-layer.patch`, from the
commit `OOREXXWASM_REV` in `build.sh`, Maven Central).
The result is `dist/`, self-contained: `dist/rexxj program.rex` runs a
Rexx program.

| In `dist/` | What |
|---|---|
| `rexxj` | The launcher script. |
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
the API test groups only: run the suite with `dist-test/rexxj`.

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

## Contents

| Path | What |
|---|---|
| `build.sh` | Rebuilds everything from scratch. |
| `build-test.sh` | Builds `dist-test/`, the variant with the test suite's native libraries. |
| `scripts/saved-build.sh` | Saves the build on the branch `saved-build` and restores it. |
| `0001-wasi-on-top-of-oorexxwasm-0001.patch` | The WASI patch (three files, what is WASI's alone), applied on top of `oorexxwasm`'s `patches/0001-wasm-generic-layer.patch`, the layer common to every WebAssembly build. |
| `compat/` | Headers and stubs for what wasi-libc lacks; `orx_wasi_host.c`, the host functions of `oorexxwasm`'s `SysWasmHost.hpp` (commands, `.RexxInfo~executable`, `~libraryPath`) over functions the launcher gives the module (import module `rexxjvm`, `host/.../Host.java`). |
| `wasi-p1-threads.cmake` | wasi-sdk toolchain file for `wasm32-wasip1-threads`. |
| `host/` | The Java launcher (Maven; Chicory's WASI, shared memory, and `thread-spawn`; the `embedded` profile builds `rexxj.jar` with the interpreter translated at build time). |
| `tests/` | Small test programs (`hello.rex`, `conc.rex`, `err.rex`, `sys.rex`, `cmd.rex`, `rxapi.rex`, `t.cpp`). |
| `docs/` | Reports. |
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

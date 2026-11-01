# ooRexx for Java

Running ooRexx under Java: the ooRexx interpreter itself (the C++
sources, unchanged but for a small patch) compiled to WebAssembly with
WASI, and run on any Java virtual machine by
[Chicory](https://github.com/dylibso/chicory), a WebAssembly runtime
written in pure Java (no JNI, no native code).

**Status:** proof of concept (01/11/2026). ooRexx trunk r13289, built
for `wasm32-wasip1-threads` with wasi-sdk 34, runs on OpenJDK 21 with
Chicory 1.7.5: multithreading (`START`, `GUARD ON WHEN`, `REPLY`),
conditions, streams, the queue, `SysFileTree`, `INTERPRET` and
`rxregexp` behave as in native ooRexx, and the Rexx Parser parsing its
own sources produces an element dump byte-for-byte identical to
native, about 3.4 times slower. Not available yet: commands, sockets,
Java from Rexx, HALT (Ctrl-C). The full report, in Spanish, is in
[`docs/poc-20261101.md`](docs/poc-20261101.md).

## Building

```bash
./build.sh [work-directory]      # default: ~/oorexx-jvm
```

Needs `svn`, `cmake`, `make`, Java 11 or later and Maven, and network
access (wasi-sdk 34, ooRexx trunk r13289 from SourceForge,
[`oorexxwasm`](https://github.com/JosepMariaBlasco/oorexxwasm)'s base
patch, Maven Central). The result is `dist/rexxj`, which runs a Rexx
program: `dist/rexxj program.rex`.

## Contents

| Path | What |
|---|---|
| `build.sh` | Rebuilds everything from scratch. |
| `0001-wasi-on-top-of-oorexxwasm-0001.patch` | The WASI patch, applied on top of `oorexxwasm`'s `patches/0001-wasm-build-and-base-runtime.patch`. |
| `compat/` | Headers and stubs for what wasi-libc lacks. |
| `wasi-p1-threads.cmake` | wasi-sdk toolchain file for `wasm32-wasip1-threads`. |
| `host/` | The Java launcher (Maven; Chicory's WASI, shared memory, and `thread-spawn`). |
| `tests/` | Small test programs (`hello.rex`, `conc.rex`, `err.rex`, `sys.rex`, `t.cpp`). |
| `docs/` | Reports. |

## Trademarks and Legal Notices

* **ooRexx for Java** is a project by Rony Flatscher and Josep Maria Blasco.
* **Java**: Java is a registered trademark of Oracle and/or its affiliates.
  This project is an independent implementation and is not affiliated
  with, sponsored, authorized, or otherwise endorsed by Oracle America, Inc.
* **ooRexx**: Open Object Rexx and ooRexx are trademarks of the Rexx
  Language Association (RexxLA).
* **WASI / Chicory**: All other product names, trademarks, and registered
  trademarks are the property of their respective owners.

# Reproductions for reports to Chicory (03/11/2026)

Small WASI programs (C, built with wasi-sdk 34) and a synthetic module that
show what this project works around elsewhere (`compat/orx_wasi_fs.c` for the
WASI points, `host/chicory-patch/` for the compiler). Run on Chicory 1.7.5,
the latest release, and on a build of Chicory's `main` at `e2e2e40`
(27/05/2026): same results on both. `run.sh` builds and runs them all:

```bash
WASI_SDK=/path/to/wasi-sdk-34.0-x86_64-linux host/chicory-reports/run.sh
CHICORY_VERSION=999-SNAPSHOT WASI_SDK=... host/chicory-reports/run.sh   # a local build of main
```

`src/main/java/repro/Run.java` runs a WASI command with the directory given
preopened as `/` (`Run [--compile] module.wasm hostDir [args...]`;
`--compile` uses `MachineFactoryCompiler`). Errno numbers are WASI's
(8 EBADF, 44 ENOENT, 58 ENOTSUP).

| Program | Shows | On Chicory (1.7.5 and `main`) | Natively (Linux, same C) |
|---|---|---|---|
| `c/rename.c` | `path_rename` asks `Files.move` for `COPY_ATTRIBUTES`, which the JDK refuses for a move | `rename: Not supported (errno 58)` | `rename: ok` |
| `c/append.c` | `path_open` with `O_APPEND` drops `READ`; `O_TRUNC` with `O_APPEND` refused (Endive #275, fixed by PR #278) | `open O_TRUNC\|O_APPEND: Not supported`, `read: Bad file descriptor (errno 8)` | `read: 4 bytes` (PR #278, 03/11/2026; wasmtime 50.0.1 the same). `open("")` succeeds on both: wasi-libc makes it `"."` before `path_open`, so it is not a difference (our report had it wrong) |
| `c/symlink.c` | `path_readlink` returns the target's last component only; `path_symlink` stores the target resolved against the preopened directory, as an absolute host path | `readlink(/sub/deeplink): "target.txt"`; `stat(/sub/wasilink)`: ENOENT, the link on the host points to `<preopened>/target.txt` | `"../sub/target.txt"`; `stat`: ok |
| `c/gen_indirect.py` | `call_indirect` dispatch over HotSpot's 8,000-byte limit (2,000 functions of one type) | see below | — |

## The `call_indirect` dispatch

Median of 5 runs (each the mean of rounds 2–4), ns per indirect call, OpenJDK
21, 2 cores:

| Chicory | Default JVM | `-XX:-DontCompileHugeMethods` |
|---|---|---|
| 1.7.5 | 146 | 41 |
| 1.7.5, sizes 128 / 512 (`host/chicory-patch/`) | 45 | |
| `main` `e2e2e40` | 148 | 42 |
| `main`, `MAX_CALL_INDIRECT_METHODS = 128` (blocks of 512) | 44 | |

Bytecode length of the generated dispatch methods (the module compiled with
Chicory's `build-time-compiler-cli`, `code_length` read from the class files):

| | `main` | `main` with 128 / 512 |
|---|---|---|
| `Indirect_0_*.apply` | 26,203 (one block of 4,096 ids) | 6,300–6,677 (four blocks of 512) |
| `call_dispatch_*` (sized by #1288) | 4,097–4,121 | 4,097–4,121 |

With 128, Chicory's `compiler-tests` (the spec test suite at
`88e97b0f`) pass: 34,003 tests, 0 failures, 63 skipped.

`PATCHED_COMPILER=path/to/compiler-1.7.5-orx1.jar` (made by
`scripts/chicory-compiler.sh`) adds a run with our patched 1.7.5 compiler.

## Endive

Chicory's development continues as Endive (`bytecodealliance/endive`, Maven
`run.endive`), a fork hosted by the Bytecode Alliance since May 2026.
`endive/` is the same runner for it (`run.endive` packages, version 1.2.0):

```bash
cd host/chicory-reports/endive && mvn -q package dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp target/endive-repro-1.jar:$(cat cp.txt) repro.Run [--compile] module.wasm hostDir [args...]
```

On Endive 1.2.0 (`main` at `03bf3d8` differs from it only in `filestat`'s
attribute list) every result above is the same; `call_indirect`: 155 ns per
call, 40 with `-XX:-DontCompileHugeMethods` (medians of 5).

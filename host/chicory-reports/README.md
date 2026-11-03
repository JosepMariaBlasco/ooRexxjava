# Reproductions for reports to Chicory (03/11/2026)

Small WASI programs (C, built with wasi-sdk 34) and a synthetic module that
show, on Chicory 1.7.5 (the latest release; `main` at `e2e2e40` has the same
code in every place involved), what this project works around elsewhere:
`compat/orx_wasi_fs.c` for the WASI points, `host/chicory-patch/` for the
compiler. `run.sh` builds and runs them all:

```bash
WASI_SDK=/path/to/wasi-sdk-34.0-x86_64-linux host/chicory-reports/run.sh
```

`src/main/java/repro/Run.java` runs a WASI command with the directory given
preopened as `/` (`Run [--compile] module.wasm hostDir [args...]`;
`--compile` uses `MachineFactoryCompiler`). Errno numbers are WASI's
(8 EBADF, 44 ENOENT, 58 ENOTSUP).

| Program | Shows | On Chicory 1.7.5 | Natively (Linux, same C) |
|---|---|---|---|
| `c/rename.c` | `path_rename` asks `Files.move` for `COPY_ATTRIBUTES`, which the JDK refuses for a move | `rename: Not supported (errno 58)` | `rename: ok` |
| `c/append.c` | `path_open` with `O_APPEND` drops `READ`; `O_TRUNC` with `O_APPEND` refused; `open("")` | `open O_TRUNC\|O_APPEND: Not supported`, `read: Bad file descriptor (errno 8)`, `open(""): succeeded` | `read: 4 bytes`, `open(""): No such file or directory` |
| `c/symlink.c` | `path_readlink` returns the target's last component only; `path_symlink` stores the target resolved against the preopened directory, as an absolute host path | `readlink(/sub/deeplink): "target.txt"`; `stat(/sub/wasilink)`: ENOENT, the link on the host points to `<preopened>/target.txt` | `"../sub/target.txt"`; `stat`: ok |
| `c/gen_indirect.py` | `call_indirect` dispatch over HotSpot's 8,000-byte limit (2,000 functions of one type) | ~140 ns per indirect call; ~37 with `-XX:-DontCompileHugeMethods`; ~45 with `host/chicory-patch/` (128/512) | — |

Measured on 2 cores, OpenJDK 21. `PATCHED_COMPILER=path/to/compiler-1.7.5-orx1.jar`
(made by `scripts/chicory-compiler.sh`) adds the last run.

# MemBench: a WebAssembly linear memory with `Unsafe` and with `java.lang.foreign`

A standalone reproducer (one file, JDK 22+, nothing else) of how `rexxj` keeps the
linear memory of the interpreter's WebAssembly module (`host/.../FlatMemory.java`,
`UnsafeMemory.java`, `java22/.../MappedMemory.java`) and of what the
`java.lang.foreign` version costs. Written to be shown to Project Panama
(panama-dev@openjdk.org).

    java --sun-misc-unsafe-memory-access=allow MemBench.java unsafe|mapped|arena loop|vm [SECONDS]
    ./run.sh [JAVA] [ROUNDS] [SECONDS]        # all of them, alternated, in separate JVMs

`MemBench.java`'s javadoc says what each kind and workload is. In short: the
translated module calls an interface for every load and store (one
implementation loaded: monomorphic, inlined); the memory checks the address
against its current size, then loads at `base + addr` (`Unsafe`) or
`segment.get(JAVA_INT_UNALIGNED, addr)` (a final `MemorySegment` field).
`loop` is a plain loop; `vm` is an interpreter for a small register machine
whose code, registers and data are all in the linear memory — the shape of
ooRexx's interpreter compiled to WebAssembly and translated to bytecode.

## Results (3/11/2026)

2 cores (x86-64, Linux), OpenJDK 25 (Ubuntu), 5 one-second runs after 3 s of
warm-up, median; M operations per second:

| workload | `unsafe` | `mapped` | `arena` | FFM vs `Unsafe` |
|---|---|---|---|---|
| `loop` | 4,210 | 3,665 | 3,697 | −13% |
| `vm` | 170 | 108 | 105 | **−36%** |
| `vm`, `-Dnocheck=true` | 197 | 126 | | −36% |

- `vm` loses what `rexxj` loses: `rexxcps` (ooRexx's own benchmark) on JDK 25,
  1.73 M clauses/s with `Unsafe` against 1.10 M with the mapped segment, −36%
  (`docs/java-from-rexx-design.md`).
- `mapped` and `arena` are the same: the kind of segment does not matter.
- Without our own bounds check (`-Dnocheck=true`) the gap stays: it is the
  segment's access itself (its checks: bounds, liveness, read-only, and the
  `long` offset arithmetic), not a duplicated check.
- On JDK 21 (`--enable-preview --source 21`): 161 against 102, the same.

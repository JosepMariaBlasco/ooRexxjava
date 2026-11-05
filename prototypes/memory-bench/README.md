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

## Results (5/11/2026, after the fix)

Until 5/11/2026 (`4ecf01b`) the `vm` program never walked the array: two of its
operands, 256, were stored in a byte as 0 (N and DATA both 0), so each run was 19
instructions over address 0. Per pointed it out on panama-dev. The numbers of
3/11 below are from that program; now `program()` refuses an operand that is not
a byte and `main` checks the instruction count of one run.

2 cores (x86-64, Linux), 5 one-second runs after 3 s of warm-up, median of 3
alternated rounds in separate JVMs; M operations per second. OpenJDK 25 (Ubuntu)
and OpenJDK 27.0.1 (jdk.java.net); `delay` is
`-XX:+UnlockDiagnosticVMOptions -XX:+DelayAfterInliningCutoff` (JDK 27, a
diagnostic flag), suggested by Jorn Vernee on panama-dev (4/11/2026): the `vm`
method reaches `NodeCountInliningCutoff`, its accessors are not inlined and their
checks are redone on every access.

| `vm` | `unsafe` | `mapped` | `arena` |
|---|---|---|---|
| JDK 25 | 112 | 59 | 59 |
| JDK 25, `-Dnocheck=true` | 305 | 76 | |
| JDK 27 | 121 | 57 | 58 |
| JDK 27, `delay` | 127 | 83 | 80 |
| JDK 27, `delay`, `-Dnocheck=true` | 298 | 119 | |

`loop`, JDK 25: 2,205 `unsafe`, 2,324 `mapped`, 2,274 `arena` (the machine was
slower than on 3/11: compare within a day only).

- With `delay`, each kind with one set of checks (`unsafe` with ours, a segment
  without ours) is about even, 127 and 119, as Jorn found.
- Without it, a segment loses about half against `Unsafe`.
- Our own bounds check costs `Unsafe` a lot here (112 against 305): `rexxj`
  already checks only the sign of the address, with a guard region behind the
  memory (`UnsafeMemory$Relaxed`, `-Drexxjvm.memcheck=strict` for the full check).

The real thing, `rexxcps` (`averaging=800`, about 26 s, `rexxj` of 5/11/2026,
r13381; 2 cores, 3 alternated rounds, median; M clauses/s):

| | `Unsafe` | mapped segment (`-Drexxjvm.memory=mapped`) |
|---|---|---|
| JDK 25 | 3.26 | 1.74 |
| JDK 27 | 3.29 | 1.76 |
| JDK 27, `delay` | 3.39 | 2.04 |

`delay` gives the segment 16% and `Unsafe` 3%; the segment stays about 40% behind.

## Results (3/11/2026, before the fix: `vm` ran 19 instructions per run)


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

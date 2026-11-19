# Effective addresses in `long` in the translated module (19/11/2026)

An experiment on Rony G. Flatscher's suggestion (19/11/2026): let the translated code
do the address arithmetic in `long` (JDK-8397094: C2 and `int` address arithmetic).
In `prototypes/memory-bench` (section "Addresses in `long`") it gave `vm` +24% with
`Unsafe` and +12% with the mapped segment. This is whether that carries over to
Endive's translated code, `rexxj` itself.

Nothing here is in `rexxj`'s build: `build.sh` makes two patched copies of a dist.

## What changes

Endive's translated code does not access the memory itself: each load and store
calls a static helper of the module's `...MachineShaded` class (a copy of the
compiler's template, `run.endive.compiler.internal.Shaded`), such as
`memoryReadInt(int base, int offset, Memory)`, which computes
`getAddr(base, offset)` in `int` (the sum, or the base itself if negative, to trap)
and calls `Memory.readInt(int)`; `FlatMemory` turns that `int` into a `long` inside.
The twelve plain helpers (byte, short, int, long, float and double; read and write)
are rewritten with ASM:

- **`long`** (`PatchLongAddr`): each helper calls the method of the same name and
  descriptor in `LongAddr`, which computes WebAssembly's effective address in `long`
  (the `i32` zero-extended plus the offset zero-extended, no wrap-around) and calls
  `FlatMemory`'s new `long` accessors (`flatmemory-long.patch`: `readIntL`... with
  `atL`, `atRelaxedL`, `atStrictL`; past 2 GB they trap, as `getAddr`'s negative
  address does). Both the module's copy (`rexx.wasm`) and the compiler's template
  (modules translated at run time, `vm.wasm`), so one more call per access.
- **`inline`** (`PatchLongAddrInline`): `rexx.wasm`'s module only (its class is in
  package `rexxjvm`, where `FlatMemory`'s package-private accessors can be reached):
  the helper computes the effective address itself and calls `FlatMemory`'s `long`
  accessor directly, no call more than today.

Both checked: with `-Drexxjvm.memory=bytearray` (Chicory's memory, not a `FlatMemory`)
the patched helpers fail with a `ClassCastException`, so they are the ones running.
`throw.rex` and the like run unchanged.

    prototypes/long-addresses/build.sh /home/claude/work/dist /home/claude/exp

## Results

2 cores (x86-64, Linux), ooRexx r13545's dist (saved build `e326f2b`). `vm.wasm`
(`prototypes/wasm-bench`, M ops/s, median of 8 one-second runs) and `rexxcps`
(`averaging=400`, M clauses/s); medians of rounds alternated in separate JVMs. On
JDK 21 the mapped segment is not used (Java 22+): both columns are `Unsafe`.

`long` (`results-20261119-long.txt`, 3 rounds):

| | `vm.wasm` base | `vm.wasm` `long` | `rexxcps` base | `rexxcps` `long` |
|---|---|---|---|---|
| JDK 21, `Unsafe` | 23.8 | 23.0 | 5.18 | 4.93 (−5%) |
| JDK 25, `Unsafe` | 23.6 | 21.8 | 5.44 | 4.91 (−10%) |
| JDK 25, mapped | 14.0 | 14.2 | 2.81 | 2.87 (+2%) |

`inline` (`results-20261119-inline.txt`, JDK 25, 4 rounds, `rexxcps` only):

| | base | `inline` | |
|---|---|---|---|
| `Unsafe` | 5.36 | 5.43 | +1% (noise: 5.09–5.68) |
| mapped | 2.80 | 3.04 | **+9%** (every round above every base round) |

- The benchmark's gain does not carry over to `Unsafe`: with one call more per access
  (`long`) it loses, with none (`inline`) it stays where it was. In the translated
  code the accesses are mostly left out of line by C2's inlining budget
  (`prototypes/wasm-bench`, "Where the rest goes"), and the arithmetic around an
  access is still the module's own `i32` arithmetic; only `base + offset` changes.
- The segment gains a little with `inline`: an offset known to be non-negative and
  computed in `long` may spare part of its checks. The gap to `Unsafe` stays about
  −44%.
- Not measured: JDK 27 (`DelayAfterInliningCutoff`, which the launchers pass there),
  and the `inline` form for modules translated at run time (it would need
  `FlatMemory`'s accessors public, or the template's package).

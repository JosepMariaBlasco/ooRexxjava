# wasm-bench: a middle-sized benchmark, through Endive's real code generator

Between `prototypes/memory-bench` (MemBench's `vm`: a register machine written in
Java, its accesses through our memory interface) and `rexxcps` (the whole ooRexx
interpreter): asked for by Maurizio Cimadamore on panama-dev (11/11/2026), because
with `-XX:+DelayAfterInliningCutoff` the small benchmark showed the segment even with
`Unsafe` while `rexxcps` still lost about 40%.

`vm.c` is a small interpreter in C with the shape of ooRexx's: a bytecode loop with a
`switch`; values are heap objects (`malloc`/`free`, reference counts) whose methods are
reached through a table of function pointers per class (`call_indirect`); variables in
a hash table keyed by their names, hashed and compared as strings; some string
building. It is compiled to WebAssembly as `rexx.wasm` is (`build.sh`:
`wasm32-wasip1-threads`, the memory imported and shared, at most 2 GB) and run by
`rexxj`, which translates it with Endive's compiler at start-up and gives it its
linear memory, so the code is what Endive generates and the memory is rexxj's own:

    rexxj vm.wasm [SECONDS] [ROUNDS]                          # Unsafe
    REXXJ_JAVA_OPTIONS=-Drexxjvm.memory=mapped rexxj vm.wasm  # a MemorySegment

`vm.wasm` is in the repository, so that it runs without wasi-sdk; `build.sh` makes it
again (and `vm-native`, the same C natively). Each round prints M bytecodes per
second; the checksum must be the same for every memory (10317838540).

## Results (12/11/2026)

M ops/s, median of 8 one-second rounds after 3 s of warm-up.

GitHub's `ubuntu-latest` (4 cores), `wasm-bench.yml` run 43187455082, Temurin, two
runs each:

| | `Unsafe` | mapped segment | segment vs `Unsafe` |
|---|---|---|---|
| JDK 25.0.5 | 19.8 | 11.6 | −41% |
| JDK 27.0.1 | 20.5 | 11.7 | −43% |
| JDK 27.0.1, `DelayAfterInliningCutoff` | 19.5 | 16.0 | −18% |

This machine (2 cores, x86-64), JDK 25.0.4: `Unsafe` ~25.5, mapped ~13.5 (−47%);
`vm-native` ~100. The segment over the OS's own reservation
(`-Drexxjvm.memory=native`, `OsReservation`: an anonymous `mmap`), two alternated
rounds of 5: 14.5 and 15.8, against 13.9 and 14.1 mapped and 25.0 and 24.1 `Unsafe`:
the same as the mapped one, as it should be (the same segment accesses).

- It reproduces `rexxcps`'s pattern: about 40% lost without the flag; with it the
  segment gains 37% but stays about 20% behind, where MemBench's `vm` closed the gap.
- `Unsafe` does not move with the flag, as in `rexxcps` (+3%).

## Where the rest goes (12/11/2026)

**The accesses are left out of line by C2's inlining budget, and a segment access
spends five times more of it.** `-XX:+PrintInlining` on `vm.wasm` (JDK 25): the
generated functions call Endive's `CompiledMachineShaded.memoryReadInt` /
`memoryWriteInt` for each access; many are not inlined, for `total inlining greater
than DesiredMethodLimit` (both memories) and, with the segment only,
`NodeCountInliningCutoff` (what `DelayAfterInliningCutoff` lifts). One access,
inlined whole, is at most 134–149 bytes of bytecode with `Unsafe`, 752–811 with the
segment (`SegmentVarHandle.checkSegment`, `checkEnclosingLayout`, `checkAccess`,
`checkBounds` and the `VarHandleGuards` around them: `@ForceInline`, but counted
against `DesiredMethodLimit`, 8,000 bytes per compiled method), so a function fits
about 10 inlined segment accesses where it fits about 55 with `Unsafe`. The rest are
calls to the accessor, compiled on its own, where the segment's checks cannot be
merged or hoisted. JFR (JDK 25, `vm.wasm`, mapped): 33% of the samples in
`AbstractMemorySegmentImpl.checkBounds` (inlined into the accessors); rexxcps, mapped:
28% in the segment's code, half of it in the out-of-line accessors, and only 5% in
interpreted code (with `Unsafe` too).

`-XX:-ClipInlining` (a product flag) lifts `DesiredMethodLimit`. M ops/s, medians,
`wasm-bench.yml` runs 43191274730 and 43191984211 (GitHub, 4 cores):

| | `Unsafe` | mapped | segment vs `Unsafe` |
|---|---|---|---|
| `vm.wasm`, JDK 27 (`Delay`, the launchers' default) | 20.4 / 22.6 | 16.1 / 18.0 | −21% |
| `vm.wasm`, JDK 27, `-ClipInlining` | 29.3 / 31.9 | 18.4 / 21.2 | −35% |
| `vm.wasm`, JDK 25 | 19.6 / 19.4 | 11.8 / 11.9 | −39% |
| `vm.wasm`, JDK 25, `-ClipInlining` | 22.0 / 22.7 | 12.4 / 13.4 | −43% |
| `rexxcps` (M clauses/s), JDK 27 | 5.35 | 3.20 | −40% |
| `rexxcps`, JDK 27, `-ClipInlining` | 5.84 | 3.30 | −43% |
| `rexxcps`, JDK 25 | 5.05 | 2.72 | −46% |
| `rexxcps`, JDK 25, `-ClipInlining` | 5.32 | 2.72 | −49% |

- The budget held `Unsafe` back too: lifted, it gains far more than the segment
  (`vm.wasm` +44% against +15% on JDK 27; `rexxcps` +9% against +3%). So the
  segment's gap is not only the budget: in `rexxcps` it stays at about 40% whatever
  the inlining limits.
- `vm.wasm`'s interpreter loop (`func_81`) is never compiled (over HotSpot's 8,000
  bytes: `-XX:-DontCompileHugeMethods` gives `Unsafe` +20%, the segment nothing unless
  with `-ClipInlining`, +25%: a method that big is over `DesiredMethodLimit` by
  itself, so none of its accesses are inlined). Endive's method splitting (#270, and
  #292 for the build-time compiler) is the fix for that one.

## `-XX:-ClipInlining` for rexxj: its cost (12/11/2026)

This machine (2 cores), `rexxcps` with `averaging=400`, `Unsafe`, two runs each:
M clauses/s, code cache used at exit (`-XX:+PrintCodeCache`, the heaps' `used`
added), total compilation time (`-XX:+CITime`); `say "hi"`, median of 7 starts.

| | `rexxcps` | code cache | compilation | `say "hi"` |
|---|---|---|---|---|
| JDK 21 | 5.11 / 5.22 | 50.7 / 51.0 MB | 3.64 / 3.67 s | 667 ms |
| JDK 21, `-ClipInlining` | 5.66 / 5.46 | 49.0 / 49.4 MB | 3.36 / 3.58 s | 635 ms |
| JDK 25 | 5.53 / 5.41 | 37.1 / 39.6 MB | 3.48 / 3.83 s | 667 ms |
| JDK 25, `-ClipInlining` | 6.12 / 5.90 | 38.2 / 37.8 MB | 3.51 / 3.59 s | 684 ms |

With JDK 27 on GitHub's runners (above): `rexxcps` +9%. So 7–11% faster on 21, 25
and 27, at no cost in code cache, compilation time or start-up.

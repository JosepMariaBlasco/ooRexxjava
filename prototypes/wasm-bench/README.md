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

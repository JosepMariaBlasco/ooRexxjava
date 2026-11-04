# The memory's bounds checks

`rexxjvm/MemCheckTest.java` checks `FlatMemory`'s two ways of checking an
access (`host/src/main/java/rexxjvm/FlatMemory.java`, "Two ways to check";
README.md, "Speed"):

- a small module (1 page, at most 4) is always strict: an access straddling or
  past the current size traps, and past the maximum;
- a module whose maximum is the runtime's 2 GB (`rexx.wasm`'s) is relaxed
  (`UnsafeMemory$Relaxed`, `MappedMemory$Relaxed`) unless
  `-Drexxjvm.memcheck=strict`: an access past the current size reads the
  reserve, also an 8-byte access starting at 2^31 - 1 (the guard); an address
  past 2 GB traps, and blocks and atomics past the size trap in both modes;
- growing the memory: the new pages work, in both.

```bash
tests/memory/run.sh ~/oorexx-jvm/dist
```

runs it relaxed and strict, with `sun.misc.Unsafe` and, on Java 22 and later,
with the mapped block (`-Drexxjvm.memory=mapped`); it fails if any check does.

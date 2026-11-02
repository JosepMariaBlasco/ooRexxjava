# Prototype: Java threads and the module — 02/11/2026

Checks the two thread hypotheses of
[`docs/java-from-rexx-design.md`](../../docs/java-from-rexx-design.md) on a
small module, before anything is built on them. Not part of the build.

- `proto.c` — the module (`wasm32-wasip1-threads`): an export `reenter`
  that uses the stack, the heap and TLS and calls back into Java
  (`java_call`) until a depth is reached; a dispatcher thread that waits
  for requests from Java (`dispatch_wait`) and calls `pthread_create` for
  each one, with an explicit 8 MB stack; the worker calls `entry_run`.
- `Proto.java` — the harness, on Chicory as `RunWasm` uses it (shared
  memory, `thread-spawn`). A foreign Java thread queues a request and waits;
  when the dispatcher's `pthread_create` reaches `thread-spawn`, the harness
  does not start a Java thread but hands `(tid, startArg)` to the foreign
  thread, which creates an `Instance` and runs `wasi_thread_start` itself.
  Inside, it checks that `entry_run` runs on the foreign thread, the
  re-entry chain, TLS and `pthread_self`.

```bash
W=/home/claude/work/wasi-sdk-34.0-x86_64-linux
$W/bin/clang --target=wasm32-wasip1-threads -O2 -pthread \
  -Wl,--import-memory,--export-memory,--shared-memory,--max-memory=1073741824 \
  -Wl,--export=wasi_thread_start -o proto.wasm proto.c
J=/home/claude/work/dist/rexxj.jar     # has Chicory, compiler included
env -u JAVA_TOOL_OPTIONS javac -cp $J -d out Proto.java
env -u JAVA_TOOL_OPTIONS java -cp $J:out Proto proto.wasm [threads] [entries] [depth]
# -Dinterp=true: Chicory's interpreter instead of the compiled machine
```

## Results (Chicory 1.7.5, Java 21, 2 cores)

**H1, re-entry: works.** An export of the same `Instance` called from inside
a host function, nested ten deep (wasm → Java → wasm...), on the main
thread and on foreign threads; compiled machine and interpreter.

**H2, a foreign Java thread running wasm code: works.** Every entry ran on
the calling Java thread (checked in every `entry_run`), with its own TLS
and `pthread_self`; a thread entering repeatedly gets a new pthread each
time, on the same Java thread.

| Run | Entries | Time per entry |
|---|---|---|
| compiled, 1 entry, cold, depth 5 | 1 | 9–16 ms (first `Instance`, JIT) |
| compiled, 4 threads × 200 | 800 ok | 0.45 ms |
| compiled, 8 threads × 2000 | 16,000 ok | 0.17 ms |
| interpreter, 4 threads × 200 | 800 ok | 2.6 ms |

The cost per entry is a `pthread_create` through the dispatcher and an
`Instance`; pre-created threads parked in `thread-spawn` would remove most
of it.

**Stacks: two lessons.**

- *The wasm stack.* With wasi-libc's default pthread stack, a re-entry
  chain 500 deep wrote below the stack (`out of bounds memory access:
  address -208`) and then corrupted memory silently and hung: linear memory
  has no guard page. Threads created for foreign entries must get an
  explicit stack, as ooRexx's own threads do; with 8 MB the chain is fine.
- *The Java stack.* Chicory's frames live on the Java thread's stack. A
  foreign thread has whatever stack its creator gave it: Java's default is
  1 MB (`ThreadStackSize` 1024), against the 256 MB `RunWasm` gives wasm
  threads. With 1 MB, re-entry chains from 800 deep fail with
  `ChicoryException: call stack exhausted` (a clean exception: Chicory turns
  the `StackOverflowError` into it, and the module kept working in these
  runs; some entries at 2000 succeeded once the JIT had shrunk the frames).
  With `-Xss16m`, 2000 deep passes. `-Xss` also applies to threads the JVM
  creates without a size, Swing's event thread included, so `dist/rexxj`
  can set it; an application embedding Rexx has to set it itself. A stack
  overflow in the middle of C code holding a lock (`malloc`'s, the
  interpreter's) would still leave the lock held: true today of wasm
  threads too, only less likely with 256 MB.

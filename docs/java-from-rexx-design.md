# Java from Rexx (and Rexx from Java): design — 02/11/2026

*Status: proposal for discussion. The direction (option 3: a small core of
our own, BSF4ooRexx compatibility on top of it, our own API too) was agreed
with Josep Maria on 02/11/2026; everything below it is a proposal. The two
hypotheses in "Threads" were checked by a prototype the same day: both
hold. The embedded mode was built the same day too (see "The embedded mode,
as built"), then the mini-JNI (see "The mini-JNI, as built") and BSF4ooRexx
on top of it (see "BSF4ooRexx on the mini-JNI, as built").*

## The question

How should Rexx code running on this interpreter (ooRexx compiled to
WebAssembly, run by Chicory) use Java, and Java code use Rexx? Three options
were on the table:

1. A BSF4ooRexx-compatible API (`.BSF`, `bsf.import`, ...): existing code
   runs, but the API is large and its semantics must be respected.
2. A minimal API of our own.
3. A minimal core, with BSF4ooRexx compatibility on top of it.

The project was inspired by Rony G. Flatscher, the author of BSF4ooRexx, so
BSF4ooRexx compatibility is required. Running inside the JVM also makes an
API of our own interesting in itself. Option 3 was chosen; this document
says what "core" should mean.

## What BSF4ooRexx is made of

Read in `JosepMariaBlasco/BSF4ooRexx`, branch `main` (the SVN `branches/850`),
commit `eafe4e6` (r1125), directory `bsf4oorexx.dev/`; the build takes SVN
r1131 from SourceForge since 3/11/2026 (r1126–r1131 change no C++). License: Apache 2.0
(`Apache_LICENSE_2.0.txt`).

| Part | Size | What it is |
|---|---|---|
| `bin/BSF.CLS` | 6,980 lines of Rexx | Almost all of what Rexx users see: `.BSF`, `bsf.import`, forwarding of unknown messages, boxing, arrays... |
| `source_cc/BSF4ooRexx.cc` | 14,628 lines of C++ | The JNI library: the native Rexx routines, and the native methods of the Java side |
| `source_java/*.java` | ~30,000 lines | `RexxAndJava` (6,349), the reflection code (`RexxReflectJava7`, ...), `RexxProxy`, `RexxEngine`, the JSR-223 engine (`RexxScriptEngine`), command and exit handlers, `ClassAdapter` (extending Java classes, with Janino) |

The boundaries are narrow:

- **Rexx → native.** `BSF.CLS` calls only seven native routines: `BSF()`
  (20 call sites), `BsfRawBytes` (12), `BsfContextVariables` (4),
  `BsfGetTID` (2), `BsfInvokedBy`, `BsfRexxProxy`, `BsfUninit4JavaBean`
  (1 each). The library registers 25 routines in all
  (`BSF4ooRexx.cc`, lines 9006–9042), most of them stubs kept for
  backward compatibility.
- **`BSF()` is strings in, string out** (`BSF4ooRexx.cc`, line 7242). Each
  argument becomes a Java `String`: a Rexx string as itself, a `.BSF`
  object as its registry name (its `~string`), `.nil` as `null`, and any
  other Rexx object is wrapped in a `RexxProxy`, registered for the
  duration of the call. Everything reaches one Java method,
  `RexxAndJava.javaCallBSF(String[])` (`RexxAndJava.java`, line 2649), a
  `switch` over ~40 subfunctions (`invoke`, `invokeStrict`, fields,
  properties, arrays, `loadClass`, `registerBean`, events...). Overloads are
  resolved by reflection from the strings (`RexxReflectUtil`,
  `RexxReflectJava7`). The result comes back as a string: the value of a
  string, boxed primitive or boolean, or else the name under which the
  object was registered (`makeString4Rexx`, line 5802).
- **Java → native.** About 50 `native` methods, almost all in
  `RexxAndJava` (`jniRexxRunProgram`, `jniRexxSendMessageToRexxObject`,
  `jniRaiseCondition`, context variables, command handler I/O,
  `jniRexxCreateInterpreterInstance`...).
- **The C++ side uses 40 distinct JNI functions**, dominated by
  `DeleteLocalRef`, `ExceptionCheck`, object arrays, `CallObjectMethod`,
  `NewStringUTF`, `GetMethodID`, `FindClass`, global references, string and
  primitive array access. The rest of the file is ooRexx's native API
  (396 `context->` calls), which `rexx.wasm` already contains.
- **Threads.** A Java thread that calls Rexx is attached to the interpreter
  instance with `AttachThread` (line 1551 and others;
  `jniRexxSendMessageToRexxObject` at line 10073).

## Proposal: three layers

### Layer 0 — the plumbing (ours)

What lets Rexx inside the module and Java outside it reach each other. Not a
Rexx API.

- **A table of Java objects** referenced from the module: a handle (an
  `i32`) per reference, global and local references as in JNI.
- **A mini-JNI.** The 40 JNI functions `BSF4ooRexx.cc` uses, implemented in
  C inside the module as imports from the launcher (a new import module next
  to `rexxjvm`, `file_*`, `net_*`): `jobject`, `jclass`, `jmethodID` are
  handles; methods resolved once and cached as `MethodHandle`s; strings
  copied through linear memory (UTF-8 / modified UTF-8). `JavaVM`'s
  `AttachCurrentThread` and friends are trivial: the JVM is always there.
- **Java threads entering the module** (see "Threads").
- **An embedded mode.** Today the module is a WASI *command*: `_start` runs
  `main`, which runs one program and exits (`RunWasm.java`, lines 112–120).
  For Java to host Rexx (`RexxEngine`, JSR-223) the interpreter has to stay
  alive and serve requests: a `main` that creates the interpreter instance
  and waits on a dispatcher thread, or a *reactor* build. In the usual
  `rexx program.rex` mode the program's thread is the main thread and the
  dispatcher runs beside it.

### Layer 1 — BSF4ooRexx, as unchanged as possible

- `BSF4ooRexx.cc` compiled into `rexx.wasm` against the mini-JNI, with the
  smallest possible changes (the JVM-loading code goes; locks and threads
  are pthreads, which wasi-threads has).
- The Java side as it is, with its ~50 `native` methods redirected to the
  module (calls to its exports through the launcher). Two ways: a minimal
  fork of those declarations, or rewriting the `native` methods' bytecode at
  build time, so that the original jar is used untouched. The second keeps
  us in step with upstream for free; to be tried first.
- `BSF.CLS` unchanged.

What this buys: real compatibility rather than an imitation of it; the JSR-223
engine and Java-hosted Rexx included; and BSF4ooRexx's own `testUnits/` and
`samples/` as the acceptance suite, as ooRexx's test suite is for the
interpreter.

### Layer 2 — an API of our own (later)

On layer 0, without the string round trip: handles instead of registry
names, real types in both directions, an explicit signature when wanted,
`MethodHandle`s cached per call site, Rexx collections and streams mapped to
Java's (`Array` ↔ `List`, `Directory` ↔ `Map`, `.Stream` over
`InputStream`/`OutputStream`), Java exceptions as Rexx conditions carrying
the `Throwable`. Designed after layer 1 works, so that it is not designed in
a vacuum: layer 1 shows what is actually needed.

Other things the JVM makes possible, for both layers: no native library at
all (one jar); several fully independent interpreters in one JVM (one module
instance group, one linear memory each); a sandbox (Rexx reaches only the
Java the host exposes, so a policy of accessible classes is possible).

## Threads

The critical point. Two cases.

**Re-entry on the same thread** (Rexx calls Java, which calls back into
Rexx before returning). The Java code runs on the wasm thread's own Java
thread, inside a host function; the callback calls an export of the same
`Instance` from inside that host function, as a nested native call would.
*Hypothesis 1: Chicory allows this, with the compiled machine. To check.*

**A Java thread that is not a wasm thread** (Swing's event dispatch thread,
a pool thread) calling Rexx. It cannot just call an export: it has no wasm
stack and no TLS (the same reason the HALT signals go through a thread of
the module, 389ebe7). But `thread-spawn` is ours (`RunWasm.java`, lines
78–97). So:

1. The foreign thread T asks the dispatcher (a wasm thread waiting in a
   host function, like the signal thread) for a thread.
2. The dispatcher calls `pthread_create` normally: wasi-libc allocates the
   stack, TLS and `pthread` structure, and calls `thread-spawn`.
3. Our `thread-spawn` does not start a new Java thread for this request: it
   hands `(tid, startArg)` to T, and T itself creates an `Instance` and runs
   `wasi_thread_start`.
4. The start routine attaches to the interpreter instance, runs the
   request, and returns; T goes back to its Java caller.

Seen from Java, Rexx runs on T, so thread-affine Java code (Swing,
`ThreadLocal`s, locks T holds) behaves; seen from C, it is an ordinary
pthread. Cost: a `pthread_create` and an `Instance` per entry from a foreign
thread (BSF4ooRexx also attaches and detaches per call); pre-created threads
parked in `thread-spawn` can remove most of it.
*Hypothesis 2: this works, with Chicory and wasi-libc. To check.* If it does
not, the fallback is delegation: a wasm thread runs the Rexx code, and its
calls into Java are shipped back to T, which waits serving them; uglier and
slower, but correct for thread identity.

**Result (02/11/2026): both hypotheses hold**, on a small module, compiled
machine and interpreter: see
[`prototypes/threads/`](../prototypes/threads/README.md). 0.17–0.45 ms per
foreign entry with the compiled machine. Two requirements came out of it:
threads created for foreign entries need an explicit wasm stack (linear
memory has no guard page: wasi-libc's default stack overflowed silently),
and foreign Java threads need a large Java stack (`-Xss`; with Java's
default 1 MB, deep re-entry fails with Chicory's "call stack exhausted").

## The embedded mode, as built (02/11/2026)

- **One binary, a command.** `rexx --orx-wasm-embedded` (`rexx.cpp`, in the
  WASI patch, next to `rxapi`) runs `orxWasiEmbeddedMain`
  (`compat/orx_wasi_embed.cpp`): it starts the dispatcher thread and waits
  in the launcher (`embed_main_wait`) until Java closes the module. A
  reactor build would not have saved anything: a foreign Java thread needs
  a wasm stack and TLS anyway, so it goes through the dispatcher either
  way.
- **A home thread per interpreter instance.** The thread that creates an
  instance becomes its root activity, bound to that thread for the
  instance's whole life (`InterpreterInstance::initialize`,
  `ActivityManager::getRootActivity`); a short-lived pthread would not do,
  and its `pthread_t` could be reused by a later one. So each instance gets
  a pthread of its own, on a Java thread of its own, which runs
  `RexxCreateInterpreter`, waits (`embed_home`) and, when the instance is
  closed, calls `Terminate`.
- **Calls.** Each call is a foreign entry, as in the prototype: the
  dispatcher's `pthread_create` (the threads' wasm stack, 256 KB by default:
  see "Memory per thread" below), handed by `thread-spawn`
  to the calling Java thread, whose worker calls `embed_run`; from there
  the Java side calls the export `orx_embed_run` on that thread's
  `Instance`, which does `AttachThread`, `CallProgram` or `NewRoutine` +
  `CallRoutine`, and `DetachThread`. The C side is only the dispatcher, a
  generic worker and four exports; what a request does is decided in Java.
- **Strings** cross as lists in linear memory (an `int32` count, then a
  length and the bytes of each; −1: no string), UTF-8 on the Java side. A
  result is `R` and the string, `N` (none), or `C` and the condition's
  fields (`DecodeConditionInfo`).
- **Java API** (`host/.../Rexx.java`): `Rexx.start()`,
  `rexx.newInterpreter()`, `callProgram(file, args...)`,
  `eval(code, args...)`, `close()`; conditions as `Rexx.RexxException`.
  Since 03/11/2026 each `Rexx.start()` is a module of its own (a `Run`, see
  "Runs: several in one JVM"); before, one per JVM. No
  `System.exit` and no signals in this mode.
- **Results** (`tests/embed/`, compiled machine, 2 cores): start-up 0.6 s
  (the translated module's first `Instance` and the image), then ~1 ms per
  call from one thread (an `eval`: a routine translated each time), 1.9 ms
  per call with 8 threads calling at once (one interpreter lock), 4 ms to
  create, use and close an instance. Pre-created entry threads and cached
  routines can come later, if they matter.
- **Beside an ordinary program** too, since BSF4ooRexx: the dispatcher is
  started when C code asks for the JVM (see "BSF4ooRexx on the mini-JNI, as
  built").

## The mini-JNI, as built (02/11/2026)

- **A real `JNIEnv`.** `compat/orx_jni.c` fills the JDK's own `jni.h` tables
  (`JNINativeInterface_`, `JNIInvokeInterface_`), taken at build time from the
  JDK that builds the launcher, with `compat/jni/jni_md.h` for wasm32 (`jlong`
  is `long long`). C code written against JNI compiles unchanged. Each
  function calls one import of the module `jni` (`host/.../Jni.java`, 38
  functions). `orx_jni_env()` and `orx_jni_vm()` give the `JNIEnv` and the
  `JavaVM`; `JNI_GetCreatedJavaVMs` returns the one JVM, and
  `JNI_CreateJavaVM` gives it too, with the thread attached, as if it had just
  been created (what BSF4ooRexx's `BsfLoadJava` needs).
- **Handles.** A `jobject` is an index into the launcher's object table, cast
  to a pointer; `NULL` is null. Local, global and weak global references as in
  JNI. Local references belong to the thread's current frame: a frame is
  pushed by `AttachCurrentThread` (popped by `DetachCurrentThread`), by
  `PushLocalFrame`, and (layer 1) around each call from Java into the module.
  A thread that uses the `JNIEnv` without attaching has a base frame, never
  popped. `jmethodID` and `jfieldID` index tables of methods and fields,
  cached per class and never freed.
- **One `JNIEnv` for every thread.** Every wasm thread is a Java thread, so the
  launcher knows the caller: local references and the pending exception are
  per Java thread. `GetEnv` says `JNI_EDETACHED` until the thread attaches,
  which is what BSF4ooRexx tests to decide whether to detach afterwards.
- **Calls.** The arguments go to the launcher as they come: a `jvalue[]`, or
  the C `va_list` itself, which in wasm32 is a pointer to the arguments,
  promoted (`int`, `double`) and aligned to their size; the method's
  descriptor says how to read them. So the variadic functions need no
  signature cache in C. Methods are called by reflection; a public method of a
  class Java does not let us reach (a JDK implementation class) is called
  through the same method of a public supertype; nonvirtual calls use
  `MethodHandles` (`unreflectSpecial`).
- **Copies.** `Get...Chars` and `Get...ArrayElements` (and the `Critical`
  ones) always copy (`isCopy` is `JNI_TRUE`); `Release...` writes back unless
  `JNI_ABORT` and frees unless `JNI_COMMIT`. Strings are modified UTF-8 out;
  in, standard UTF-8's four-byte forms are accepted too.
- **Left out** (`NULL` entries): `DefineClass`, `AllocObject`,
  `RegisterNatives`, `UnregisterNatives`, `FromReflected...` /
  `ToReflected...`, direct buffers, `GetModule`, `IsVirtualThread`. Monitors
  are a reentrant lock per object, which does not exclude Java code
  `synchronized` on the same object (nothing in `BSF4ooRexx.cc` uses them).
- **Test** (`tests/jni/`, a tool of `dist-test`): 100 checks, all pass; 0.5 µs
  per `CallIntMethod`; 8 threads at once.
- **`rexxj` honours `CLASSPATH`** (`-cp` instead of `-jar`): `FindClass` uses
  the thread's context class loader, the application's.

**The boundary between layers 0 and 1 is not where it was drawn.** The real
`BSF()` (`BSF4ooRexx.cc`, line 7242) needs the `RexxAndJava` object (`rajo`)
of an initialized BSF4ooRexx; that object is made by Java (`Java4Rexx`,
`RexxEngine`), which calls `native` methods back in C on the way
(`jniSetupNativeJVMPointer`, `jniInitialize4Rexx`...). So `BSF()` as the
first client already needs part of layer 1: `BSF4ooRexx.cc` compiled into the
module, and the start-up `native` methods redirected to it.

## BSF4ooRexx on the mini-JNI, as built (02/11/2026)

BSF4ooRexx runs unchanged: its C++, its Java and its Rexx.

- **The native library, untouched.** `BSF4ooRexx.cc` (branch 850, r1125
  to r1131, identical) compiles for wasm32 against the mini-JNI
  without a single change. The only thing it lacked was `dlopen` / `dlsym`,
  with which `BsfLoadJava` loads `libjvm.so` and finds `JNI_CreateJavaVM`: it
  is compiled with `-Ddlopen=orx_bsf_dlopen ...`, and `compat/orx_bsf.c`'s
  versions give the mini-JNI's functions for any library whose name contains
  "jvm". The library is in oorexxwasm's table of linked libraries
  (`orx_wasm_static_libraries`, now in `orx_bsf.c`: BSF4ooRexx under both
  names `BSF.CLS` uses, `BSF4ooRexx850` and `BSF4ooRexx`, then whatever a
  build adds, `orx_wasi_extra_libraries`: `build-test.sh`'s test libraries).
  Its 50 `Java_...` functions are exported from the module.
- **The Java side, unchanged, with its `native` methods redirected.**
  `rexxj.jar` is also a Java agent (`-javaagent:rexxj.jar`, `rexxjvm.Natives`):
  as the classes of `org/rexxla/bsf/` (`-Drexxjvm.natives`) are loaded, ASM
  (which Chicory's compiler already brings) gives each `native` method a body
  that calls the module's `Java_...` function, and makes their
  `System.loadLibrary` do nothing. The jar on disk is not changed. A call
  pushes a frame of local references and counts as attached, and an exception
  the C code leaves pending is thrown when it returns, as in JNI.
  `dist/bsf4oorexx.jar` is `bsf4oorexx.dev/source_java` compiled for Java 8
  over the repository's Apache BSF and Janino classes (the classes compiled at
  the repository's root are older: `RexxRedirectingCommandHandler` is
  missing).
- **Threads.** A native method called on a thread that is running wasm code
  (Rexx calls Java, which calls back: a `Comparator` written in Rexx) runs on
  that thread's `Instance` (`Run.INSTANCE`; `RunWasm.current` before 03/11/2026). Called on any other Java
  thread (a `java.lang.Thread`, Swing's), it is a foreign entry, as in the
  embedded mode: the dispatcher, which `JNI_CreateJavaVM` now starts beside
  an ordinary program (`orxWasiStartDispatcher`), creates a pthread and
  `thread-spawn` hands it to that Java thread, which runs the call itself. So
  Rexx code called from a Java thread runs on that very thread.
- **Results.** `tests/bsf/` (objects, static fields, collections, arrays,
  exceptions as SYNTAX conditions, a Rexx comparator called by Java, four
  Java threads calling Rexx): all pass. BSF4ooRexx's own `testUnits`, with
  ooRexx's test framework: `rgf` 172 tests (268,038 assertions), `lex` 771,
  `java8` 39, `java6` 25, no failures and no errors.
- **Memory per thread (found with `rgf_multithreading`, solved 02/11/2026).**
  That test's stress cases create 712, 2,601 and 10,201 threads; at first
  2,601 ran out of Java heap and hung. The cost was not the `Instance` per
  thread (a few hundred KB) but each thread's C stack. The module is
  linked with `-z stack-size=8388608`, and natively a new activity gets the
  stack size of the thread that creates it, so every Rexx thread took 8 MB.
  Under WASI `pthread_create` takes that stack with `malloc` from the linear
  memory, and Chicory's `ByteArrayMemory` allocates a `byte[65536]` of Java
  heap for every page as soon as the memory grows, touched or not. So 8 MB of
  heap per live thread, and at most about 240 threads in the module's 2 GB.
  A Rexx call level takes only about 80 bytes of that stack under WASI
  (about 370 natively), because Chicory's frames are on the Java stack.
  Now the activities' threads and the dispatcher's workers get
  `orxWasiThreadStack()` (`compat/orx_wasi_host.c`, the WASI patch's
  `SysActivity::create`): 256 KB by default, `REXXJVM_THREADSTACK` or
  `-Drexxjvm.threadstack` (bytes, or `1M`, `2M`; 128K to 256M). The main
  thread keeps 8 MB. Measured: with 200 live threads, 78 MB of wasm memory
  instead of about 1.6 GB (28 MB of it fixed; 128 MB with 512K); in a
  thread, 2,661 levels of Rexx recursion (5,938 with 512K, 104,242 with
  8 MB; natively 22,525), then error 11 as usual; at most about 8,000 live
  threads. 512K was tried as the default first: the 2,601 threads of
  `rgf_multithreading`, nearly all alive at once, then exhaust Java's
  default heap (1.5 GB here). A memory that allocates its pages when first
  touched (a `Memory` of our own for Chicory) would make large stacks cost
  only what they use: done, see "The linear memory, outside the heap". All of
  `rgf_multithreading`'s cases pass (`tests/bsf/README.md`).

## The linear memory, outside the heap (02/11/2026)

Not a matter of Java and Rexx only, but found here. Chicory 1.7.5's
`ByteArrayMemory` keeps the linear memory as 64 KB pages (`byte[][]`): every
access finds its page, checks whether it crosses into the next one, and
indexes two arrays (a JFR profile of `rexxcps`: about 39% of the time in
memory accesses); and it allocates every page in the Java heap as soon as the
memory grows, so each thread's C stack costs its whole size ("Memory per
thread" above).

`host/.../FlatMemory.java` implements Chicory's `Memory` interface, which is
all the translated module uses (`invokeinterface` on it; one implementation
loaded, so the JIT inlines it). It is one block outside the heap, allocated
with `sun.misc.Unsafe` at the module's maximum size (32,767 pages, Chicory's
limit) when the memory is created. A block that large comes from `mmap`:
zero-filled pages that the operating system provides when first touched (a
few spots are checked; if they were not zero, the block is cleared, correct
but no longer lazy). An access is one bounds check, against a plain field
that a stale value can only make smaller, confirmed against the `volatile`
page count before trapping, and one load. Atomics as Chicory's: sequentially
consistent (`Unsafe`'s volatile accesses, CAS, get-and-add/set), byte and
short ones as a CAS of the aligned word that holds them, unaligned atomics
trap; `wait`/`notify` with the same bookkeeping as `ByteArrayMemory` (waiters
and the wake-ups owed to them, per address). Wasm is little-endian: values
are byte-swapped on a big-endian machine. `-Drexxjvm.memory=bytearray` goes
back to `ByteArrayMemory`.

`sun.misc.Unsafe` and later JDKs (3/11/2026). JDK 24 and later print a warning
on `Unsafe`'s first memory access (JEP 498), and a later JDK will refuse it
by default. `FlatMemory` is abstract, with all the logic (bounds, growth,
atomics, `wait`/`notify`), and one subclass per kind of block, so that an
access is still one bounds check and one load, the block's address a field of
the same object: `UnsafeMemory` (as before), and, in the jar's
`META-INF/versions/22` (a multi-release jar; `src/main/java22`, compiled only
when Maven runs on JDK 22 or later, `scripts/mvn22.sh`), `MappedMemory`, with
`java.lang.foreign` and no restricted method: a private (copy-on-write)
mapping of a temp file, extended to the size and deleted at once, lazy like
`malloc` (`Arena.allocate` zero-fills eagerly, every page resident: 4 GB, 3–4 s and 4.3 GB
resident, JDK 25.0.4, 11/11/2026; the 35 s measured on 3/11 does not reproduce; `FileChannel.map`
refuses `/dev/zero`). `Blocks` picks: `UnsafeMemory` while the JVM allows it,
the fastest; `MappedMemory` when the JVM denies it
(`--sun-misc-unsafe-memory-access=deny`) or with `-Drexxjvm.memory=mapped`
(`=unsafe` insists on `Unsafe`; Windows always `Unsafe`: a mapped file there
cannot be deleted while open). The warning goes away with
`--sun-misc-unsafe-memory-access=allow` in `REXXJ_JAVA_OPTIONS`. The price of
`MappedMemory`, `rexxcps` on JDK 25 (2 cores, alternated runs, a slow day):
1.10 M (1.07–1.13) against 1.73 M (1.65–1.81) with `UnsafeMemory`, about a
third less: a `MemorySegment` checks every access (length, session, read-only),
and the JIT cannot take those checks out of the module's huge methods; a
segment in a `static final` field did not help (2.0–2.3 M against 2.8–3.0 M,
another day). On JDK 21 the subclasses cost nothing (1.82 M against 1.79 M
before them, alternated). The project's own tests (rexx programs, BSF4ooRexx,
embedded mode, JSR-223, several runs in one JVM) pass on JDK 21, and on JDK 25
with `deny`.

Measured (2 cores, Java 21, with `-XX:-DontCompileHugeMethods`, README
"Speed"): `rexxcps` 1.19–1.23 M clauses/s, against 0.99–1.05 M with
`ByteArrayMemory` (runs alternated). 200 live threads with 8 MB stacks: 245 MB of resident memory in
all, where `ByteArrayMemory` reached 1.6 GB and failed; with 256 KB stacks,
250 MB against 313 MB.

## JSR-223, as built (02/11/2026)

BSF4ooRexx's script engine (`RexxScriptEngine`) already worked from a Rexx
program (`testUnits/.../07_rexx_JSR_223.testGroup`, in `rgf`): the module was
running. From a plain Java program nothing had started it, and the first
`native` method BSF4ooRexx calls (`jniSetupNativeJVMPointer`, from
`RexxAndJava`'s constructor) waited forever for a dispatcher that did not
exist. Two changes, in Java only:

- **The module starts by itself.** `Natives.call`, on a Java thread that is not
  running wasm code, calls `Rexx.startIfNeeded()` before the foreign entry: if
  nothing has started a run (no `Run.primary()`; before 03/11/2026, `RunWasm.module` null), it
  is started in the embedded mode, as `Rexx.start()` does. Beside an ordinary
  program, or once started, it does nothing. The host names no class of ours.
- **`BSF.CLS` is found without `PATH`.** In the embedded mode the module's
  `PATH` starts with the interpreter's directory (`-Drexxjvm.home`, by default
  the directory of `rexxj.jar`), as the `rexxj` script does for a program; so
  `::requires "BSF.CLS"` finds it beside `rexx.img`.

Results in `tests/jsr223/` (21 checks: `eval`, `Compilable`, `Invocable`
with `getInterface`, bindings, the context's writer, errors as
`ScriptException`, 8 Java threads, two engines): all pass. About 20 ms per
`invokeFunction`: some 6.7 foreign entries each (`jniRexxSendMessageToRexxObject`,
`jniUnregisterRexxObject`); creating their `Instance`s is about 16% of the time
(JFR), the rest is the interpreter running. A native BSF4ooRexx takes 1.0–1.1
ms (about 19 times less; about 16 without the `Instance`s, the ratio of
`rexxcps`; since Chicory's compiler is patched, README "Speed", 11.9 ms). So pre-created entry threads would save little here; the
interpreter's raw speed is what matters. Natively, BSF4ooRexx's `testUnits`
give exactly our counts, and the errors seen with their old framework too
(`tests/bsf/README.md`).

## Runs: several in one JVM, as built (03/11/2026)

Why: a JVM that runs one Rexx program and exits pays, every time, about a
second of start-up and several more of JIT warm-up (README, "Speed"; ooRexx's
suite is mostly that: median 4 s per group, and the groups that start `rexx`
as a process are 40% of its time). Run again in a JVM that is already warm,
with a new memory and new `Instance`s, `say "hi"` takes 40–90 ms instead of
about 950, and the Rexx Parser's `elident` on `Scanner.cls` 4.4 s instead of
11.2 (natively 2.3–2.6). A JVM that serves many runs (a server for `rexx`, the
suite) needs the launcher's state per run first; this is that step.

- **Shared by the JVM:** the module and its machine factory
  (`Run.Module`, translated once: the code the JIT compiles serves every run),
  the mini-JNI's object table (handles are unique) and its method and field
  ids, `FileSys`'s probe of the file system.
- **A run's own (`host/.../Run.java`):** its arguments, environment, current
  directory and standard streams (its WASI); its linear memory; its imports,
  which find their run as `Run.current()` (a thread-local set wherever wasm
  code runs: `Run.runOn`); its threads and thread ids; its signals; its
  foreign entries (`Entries.java`: the dispatcher's queue, `embed_next`,
  `embed_run`, the hand-over in thread-spawn, which were static in
  `Rexx`); the module's `JNIEnv`; and what it opened, closed when it ends:
  sockets (`Net`), connections to the API server (`RxApi`), JNI global and
  weak references (`Jni.releaseRun`). `getpid`/`getppid` can be given
  (`Run.Options.pid`, `ppid`).
- **Two kinds.** A *process* run owns the JVM: `rexxj` (`RunWasm`, now only
  that), unchanged: `exit` from any thread is `System.exit`, the process's
  signals are its (`Host.installSignals(run)`), commands inherit the process's
  standard streams. A *hosted* run does not: `exit` ends the run
  (`Run.end`), its signals come from its host (`Run.signal`), and commands
  write to the run's streams (copied; they read no input). The embedded mode
  is hosted, but an `exit` there is only reported, as before.
- **Ending a hosted run** (`Run.end`, also from outside, as a server will need
  when its client dies): the run's threads are interrupted (Chicory checks the
  interruption on every call and loop; the launcher's waits are
  interruptible), the dispatcher gets -1, what it opened is closed, WASI is
  closed (the host's streams are not: they are wrapped), and the memory is
  left to the GC (`FlatMemory`'s cleaner frees the block). `start()` then
  returns the run's exit code; `awaitThreads` waits for its threads.
- **Foreign entries without a run.** A `native` method of BSF4ooRexx called on
  a Java thread that is not running wasm code enters the *primary* run (the
  first one started, `Run.primary()`), starting the embedded mode if there is
  none, as before. With several runs, nothing says which one such a thread
  means; a run per class loader would (later, for the server).
- **Fixed on the way.** `Natives` cached each `Instance`'s exports in a
  `WeakHashMap` keyed by the `Instance`, whose values refer to it: no entry was
  ever freed, so every foreign entry (about 6.7 per JSR-223 `invokeFunction`)
  kept its `Instance` alive. Now a per-thread cache.
- **Results** (`tests/multirun/`, 2 cores, Java 21): 9 checks (10 since the server, below), all pass: 12
  runs one after another (744 ms the first, 60–119 ms the others), each its
  own output; the exit code; environment, current directory and a command's
  output; standard input and error; 20 runs on 4 threads at once; a run with
  two threads looping, ended from outside in 2 ms with no threads left; SIGINT
  as a HALT; no wasm threads left and every run's memory collected. `rexxj`
  unchanged: the repository's tests, `tests/halt.sh`, `tests/embed` (41),
  `tests/jsr223` (21), `tests/jni` (100), `tests/bsf`, BSF4ooRexx's
  `testUnits` (same counts) and ooRexx's suite: 400/405 (`results/suite-jvm-r13346-runs.csv`, two groups at a time; v9 398/405): left only the timing ones, `ProcessInvocation` over 300 s, `TRACE`, `TRACE_TraceObject`, `scclient`, `sfclient`.

### The warm JVM and its client, as built (03/11/2026)

The first use of runs: a minimal server and client, meant for the suite first.

- **`rexxjvm.Server`** (`host/.../Server.java`): a Unix socket per user and
  build, one server per socket (`SOCKET.lock`), a thread and a hosted `Run`
  per connection; idle for 15 minutes with nothing running, it exits. The
  protocol is in its javadoc: the client's arguments, environment, directory,
  pid and ppid, and the kind of its three standard streams (terminal, regular
  file and its offset, other); then signals; then the exit code.
- **The standard streams** are the client's own: the server opens
  `/proc/PID/fd/0`, `1`, `2` (Linux; the same user may), so pipes, terminals
  and files behave as they would natively, with no copying. Output to a
  regular file is appended, and the client moves its offset to the end when
  the program ends, so `( echo a; rexx p; echo b ) > f` comes out in order;
  input from a regular file starts at the client's offset and the client's
  offset moves to where the program left it. Commands the program runs get the
  same files (`Run.Options.cmdIn/Out/Err`, `ProcessBuilder.Redirect`): their
  output interleaves with the program's as natively. A command's input from a
  regular file (since 3/11/2026): `/proc/PID/fd/0` opens a new open file
  description, at offset 0, where natively the command shares the client's; so
  the command runs through `rexxjs --orx-stdin-at OFFSET REPORT`, which moves it
  to where the program's reading is, runs the command on a child, and writes the
  offset it leaves to `REPORT`, from where the program goes on (`Host.commandRun`).
  Not exact: `stdout`/`stderr` to
  the same file interleave unbuffered (natively stdout is buffered: the order
  differs from native, as `rexxj`'s does too, `tests/err.rex`).
- **`rexxjs`** (`client/rexxjs.c`, ~330 lines of C): finds or starts the
  server (detached, `setsid`, its output to `SOCKET.log`), sends the request,
  forwards SIGINT, SIGTERM and SIGHUP (unless ignored, as natively) as `'S'`,
  and exits with the program's code. It falls back to `rexxj` for what it
  cannot do: a standard stream closed or a socket, the API server's daemon,
  no server. `dist/rexxj` execs it when `REXXJ_SERVER` is set, so `rexx`,
  `rexxc`, `rxqueue`, `rxsubcom` and every `rexx` a program starts go through
  the server too.
- **Found by it:** `Jni.functions()` filled a shared list unsynchronized; two
  runs built at once could get half of the imports ("unknown import
  jni.attach"). Now synchronized, and `tests/multirun` starts with three runs
  built at once (10 checks).
- **Results** (2 cores, Java 21, the suite as `orxt`, two groups at a time,
  300 s limit): **404/405 in 4 min 24 s** (`results/suite-jvm-r13346-server.csv`;
  the groups' times add up to 430 s), against 400/405 and about 21 minutes
  with `rexxj` (2,495 s). `ProcessInvocation`, over 300 s every time until now,
  passes in 22 s; `scclient`, `sfclient` and `TRACE_TraceObject` pass too.
  Natively (the r13346 `.deb`, same machine and settings,
  `results/suite-native-r13346.csv`): 2 min 24 s, 394/405 (the `.deb` lacks the
  suite's test libraries: nine API groups; and `CHAROUT`, `LINEOUT`). On the 390
  groups that pass everywhere: native 182 s, server 358 s (x2.0), `rexxj` 1,993 s
  (x11); wall time x1.8. Groups that wait (`samples`, `Alarm`, sockets) take the
  same; groups that compute show the raw speed: `CALL` 2 s vs 41, `DateTime` 10
  vs 45, `Array` 2 vs 10. Left:
  `TRACE`'s `TEST_TRACE_LABEL_WITH_FORWARD`, as with `rexxj`. The Rexx
  Parser 0.7's `elident` on `Scanner.cls` and `Clauses.cls`: 3.9–4.8 s
  through the server once warm (the first 4.8–8.1 s), 8.2–9.8 s with `rexxj`,
  2.2–2.6 s natively. `say "hi"`: ~1.2–2 s the first time (the server
  starts), then 100–140 ms. BSF4ooRexx works through it (`tests/bsf/basics.rex`).
- **Towards a product version** (Josep Maria, 3/11/2026; in this order: Linux,
  then the streams relayed through the socket for macOS and Windows, then
  `rexxj` on Windows, then a Win32 client). Done on Linux:
  - *A class loader per run* (`Server.loader`): `bsf4oorexx.jar`, then the
    client's `CLASSPATH` as `java -cp` takes it (relative to the client's
    directory, `dir/*`, an empty entry the directory); its parent has
    `rexxj.jar` alone, the server's class path (`rexxjs` starts it so). So
    BSF4ooRexx's statics and the client's classes start afresh in every run,
    and two runs at once see different classes of the same name. It is the
    context class loader of the run's threads, which `Jni.findClass` uses and
    the threads they start inherit. A Java thread with no run of its own that
    calls a native method enters the run whose loader loaded the class
    (`Run.forLoader`, from `Natives.call`), no longer the first run. The agent
    rewrites the classes in every loader; a method keeps its id across loaders
    (`Natives.ids`), so nothing grows with the runs. When a run ends its
    loader must go too: `Jni.releaseLoader` drops its classes' method and field
    ids (their `Method`s kept the loader alive), and `Server.endJavaThreads`
    interrupts the Java threads the run started and left (their context loader
    is the run's): BSF4ooRexx's `RexxCleanupRef` cleaner, one per run, waits on
    a reference queue for ever otherwise (it says so in the server's log when
    interrupted). 21 runs with BSF4ooRexx leave one `RexxEngine` class loaded.
    A run with BSF4ooRexx through the server: 0.2–0.3 s (2.3 s with `rexxj`).
  - *System.out, System.err and System.in* of Java code (`Server.route`): the
    calling thread's run's streams, the client's, as with `rexxj`; the server's
    for a thread of no run. Before, Java's output went to the server's log.
  - *A command's input from a regular file* from the right offset (above).
  - *`rexxjs --orx-server-stop`*: the runs in progress end (143), the socket
    goes, the JVM ends; a server started meanwhile waits for the old one's lock.
  - Tests: `tests/server/run.sh` (12 checks; 14 since step 2).

  Step 2, *the streams relayed through the socket* (03/11/2026): the mode for
  systems with no `/proc/PID/fd` (macOS, Windows), on Linux with
  `REXXJ_SERVER_RELAY` set. The request ends with the mode, `'P'` or `'R'`
  (`"RXJ2"`); relayed, the socket then carries frames both ways
  (`host/.../Relay.java` has them):
  - *Output* as `'O'`/`'E'` frames that the client writes to its own fd 1 and 2:
    the order, appending and the offsets come out as natively, with nothing to
    fix at the end. The run's threads queue them, one thread of the relay's own
    writes them, many at once: a thread interrupted while it writes to a
    channel closes the channel, and a run's threads are interrupted as it ends,
    while they write its last output (the `'X'` of `--orx-server-stop` was lost
    so). The queue is bounded: a client that does not keep up holds the run back.
  - *Input on demand*: each read of the program sends `'R' n`, and the client
    does one `read(0, n)` and answers `'I'` with what it got. So the client never
    takes from its input more than the program asked for, and what comes after
    (a command, the next program in a pipeline) finds the rest, as natively.
  - *Commands run by the client* (`'C'`: argv, environment, directory, what is
    captured, the input given): it forks and execs them with its own standard
    streams but for those the program captures (`ADDRESS … WITH`), which go
    through pipes and back as `'o'`/`'e'` frames, then `'D'` and the status. A
    command gets the client's terminal, pipes and files themselves: no
    `--orx-stdin-at`, and `vi` or `less` see the terminal. A run that ends while a
    command runs sends `'K'` (SIGTERM) for it. SIGPIPE: the client ignores it
    (its commands get the disposition it found), and a broken pipe on its own
    output raises it again, so `rexx p | head -1` ends as a native program
    would (141); through `/proc` the server gets EPIPE instead and the client
    exits 0.
  - The client's other differences: a socket as a standard stream is fine
    relayed (only closed ones fall back to `rexxj`), and it no longer needs
    `pipe2`, `SOCK_CLOEXEC` or `/proc/self/exe` (macOS: `_NSGetExecutablePath`,
    `SO_NOSIGPIPE`; not tried there, nor built by `build.sh` but on Linux).
  - Results (Linux, forced): the suite relayed, **405/405** in about 4 min 45 s
    (`results/suite-jvm-r13358-bsf1131-server-relay.csv`; the groups' times add
    up to 463 s); `tests/server/run.sh` 14/14 in both modes (two checks added:
    large captured streams, a HALT while a command runs). Output is faster
    relayed once warm: 200,000 `say` to a file, 3.0–3.4 s against 4.6–4.9
    through `/proc` (a write per line there; here many frames per write).

  Step 3, *`rexxj` on Windows* (03/11/2026): `launchers/windows/*.cmd` (java
  run directly, not through `call`, which would expand `%` and `^` again); each
  drive preopened as `/letter` and the host translating paths at the borders
  (`WinPath`: PWD, PATH, the program's name, `.RexxInfo`, `FileSys`, commands);
  `/bin/sh -c` as `%ComSpec% /s /c "…"`; Endive's three WASI functions that read
  `"unix:*"` replaced on Windows (`WinWasi`: every `stat` failed, so `rexx.img`
  could not be loaded). CI: `.github/workflows/windows.yml`, a Linux job builds
  the jar over the saved build's `rexx.wasm`, a Windows job runs
  `tests/windows/run.ps1` on Java 21 and 25. Not yet: the warm JVM on Windows
  (step 4), the suite on Windows.

  What runs still share, and will: the JVM's heap (a run that exhausts it
  affects all) and `System.exit` (Java code that calls it ends the server and
  every run: BSF4ooRexx calls Java by reflection, so the call cannot be
  rewritten); `System.getenv` and `user.dir` (Java code sees the server's
  environment and directory, `/`, not the client's: a relative `java.io.File`
  is resolved against `/`); the default `Locale`, `TimeZone` and charset;
  system properties.

## Other implications

- **Speed.** Chicory translates the module to JVM bytecode. Each emulated
  JNI call is a host call plus a copy of strings: probably the same order
  as BSF4ooRexx, perhaps somewhat slower; layer 2 can do better.
- **Memory.** The module is 32-bit: 4 GB of linear memory at most.
- **Garbage collection.** Cycles across the two heaps (a Java listener
  referencing a Rexx object that references the listener) are not
  collected, as in BSF4ooRexx, which has its own reference counting and
  cleanup (`RexxCleanupRef`).
- **License.** This project has none yet (`todo`). Reusing Apache 2.0 code
  is a reason to choose Apache 2.0, which is compatible with ooRexx's CPL.
- **Upstream.** A possible suggestion one day, only if Josep Maria decides
  so: BSF4ooRexx's `native` methods behind an interface, JNI being one
  implementation. Nothing is proposed from here.

## Order of work

1. Prototype the two thread hypotheses (re-entry; a foreign thread through
   `thread-spawn`). Done.
2. The embedded mode. Done (strings only).
3. The mini-JNI and the object table. Done.
4. BSF4ooRexx, measured with its `testUnits/`. Done, unchanged; all of
   them pass since the threads' smaller C stack (memory per thread). JSR-223
   from a plain Java program: done (see "JSR-223, as built").
5. Layer 2.

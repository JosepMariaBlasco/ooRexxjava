# Java from Rexx (and Rexx from Java): design — 02/11/2026

*Status: proposal for discussion. The direction (option 3: a small core of
our own, BSF4ooRexx compatibility on top of it, our own API too) was agreed
with Josep Maria on 02/11/2026; everything below it is a proposal, and the
two hypotheses in "Threads" must be checked by a prototype before anything
is built on them.*

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
commit `eafe4e6`, directory `bsf4oorexx.dev/`. License: Apache 2.0
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
   `thread-spawn`).
2. The embedded mode.
3. The mini-JNI and the object table, with `BSF()` as the first client.
4. The rest of BSF4ooRexx, measured with its `testUnits/`.
5. Layer 2.

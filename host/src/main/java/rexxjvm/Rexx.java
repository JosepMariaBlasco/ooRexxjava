package rexxjvm;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasm.types.ValType;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ooRexx hosted by a Java program: the embedded mode.
 *
 * <pre>{@code
 * try (Rexx rexx = Rexx.start();
 *      Rexx.Interpreter in = rexx.newInterpreter()) {
 *     String r = in.callProgram("prog.rex", "a", "b");
 *     String s = in.eval("return 6*7");
 * }
 * }</pre>
 *
 * <p>{@link #start()} runs the module as {@code rexx --orx-wasm-embedded}
 * ({@code compat/orx_wasi_embed.cpp}): its main thread starts a dispatcher thread
 * and waits until {@link #close()}. Every call enters the module on the calling Java
 * thread: the dispatcher creates a pthread, and thread-spawn hands it to the caller,
 * which runs it on its own Java thread ({@link #enter}); the pthread attaches to the
 * interpreter instance, runs the code and detaches. Each interpreter instance lives
 * on a home thread of its own, which creates it and, when it is closed, terminates it.
 *
 * <p>The calling threads need a large Java stack: Chicory's frames live on it (a
 * thread's stack size, or {@code -Xss} for every thread created without one; 16 MB
 * is a reasonable minimum, deep Rexx recursion needs more). One module per JVM for
 * now. Strings cross as UTF-8. Thread-safe.
 */
public final class Rexx implements AutoCloseable {

    /** A condition (normally a SYNTAX error) that ended a call into Rexx. */
    public static final class RexxException extends RuntimeException {
        private final String condition, errorText, program;
        private final long code, rc, line;

        RexxException(String condition, long code, long rc, String errorText, String message,
                      String program, long line) {
            super(describe(condition, code, errorText, message, program, line));
            this.condition = condition; this.code = code; this.rc = rc;
            this.errorText = errorText; this.program = program; this.line = line;
        }

        private static String describe(String condition, long code, String errorText, String message,
                                       String program, long line) {
            StringBuilder b = new StringBuilder();
            if (code != 0) b.append("Error ").append(code / 1000).append('.').append(code % 1000).append(": ");
            else if (condition != null) b.append(condition).append(": ");
            b.append(message != null ? message : errorText != null ? errorText : "");
            if (program != null) b.append(" (").append(program).append(line > 0 ? ", line " + line : "").append(')');
            return b.toString();
        }

        /** The condition's name ({@code SYNTAX}). */
        public String condition() { return condition; }
        /** The full error code, major * 1000 + minor (40.1 is 40001); 0 if none. */
        public long code() { return code; }
        /** The return code (the major error number for SYNTAX). */
        public long rc() { return rc; }
        /** The major error's text. */
        public String errorText() { return errorText; }
        /** The program where the condition was raised. */
        public String program() { return program; }
        /** The line where it was raised; 0 if unknown. */
        public long line() { return line; }
    }

    /** An ooRexx interpreter instance. Closing it terminates it. */
    public final class Interpreter implements AutoCloseable {
        final Request home;
        volatile int handle;
        volatile boolean closed;

        Interpreter(Request home) { this.home = home; }

        /**
         * Runs a program file, as {@code CALL} would, on the calling thread; each
         * argument is one Rexx argument ({@code null}: omitted). Returns the program's
         * result, or {@code null} if it returns none.
         */
        public String callProgram(String file, String... args) { return run(0, file, args); }

        /** Runs a piece of Rexx code as a routine; otherwise as {@link #callProgram}. */
        public String eval(String code, String... args) { return run(1, code, args); }

        private String run(int mode, String what, String[] args) {
            if (closed) throw new IllegalStateException("interpreter closed");
            List<String> list = new ArrayList<>(args.length + 1);
            list.add(Objects.requireNonNull(what));
            list.addAll(Arrays.asList(args));
            List<String> r = enter(inst -> {
                int in = writeList(inst, list);
                long out = inst.export("orx_embed_run").apply(handle, mode, in)[0];
                free(inst, in);
                List<String> l = readList(inst.memory(), (int) out);
                free(inst, (int) out);
                return l;
            });
            switch (r.get(0)) {
                case "R": return r.get(1);
                case "N": return null;
                case "C": throw new RexxException(r.get(1), num(r.get(2)), num(r.get(3)),
                                                  r.get(4), r.get(5), r.get(6), num(r.get(7)));
                default: throw new IllegalStateException(r.size() > 1 ? r.get(1) : r.toString());
            }
        }

        /** Terminates the instance (after any activity still running in it ends). */
        @Override public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            interpreters.remove(this);
            home.release.countDown();
            await(home.done);
        }
    }

    // ---- the module (one per JVM) ----

    private static Rexx current;
    private static final CountDownLatch mainWaiting = new CountDownLatch(1);
    private static final CountDownLatch released = new CountDownLatch(1);
    private static volatile Throwable mainFailure;
    private static volatile int mainExit = -1;

    private final Thread mainThread;
    private final Set<Interpreter> interpreters = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private Rexx(Thread mainThread) { this.mainThread = mainThread; }

    /**
     * Starts the module: the one translated at build time ({@code rexxj.jar}), or the
     * file given by {@code -Drexxjvm.wasm=<file>}. The interpreter's files
     * ({@code rexx.img}, the {@code .cls} of the extensions) are looked for in
     * {@code -Drexxjvm.home}, else beside the jar.
     */
    public static Rexx start() {
        String wasm = System.getProperty("rexxjvm.wasm");
        return start(wasm == null ? null : new File(wasm));
    }

    /** Starts the module from a {@code .wasm} file ({@code null}: the embedded one). */
    public static synchronized Rexx start(File wasm) {
        if (current != null) throw new IllegalStateException("the module is already started (one per JVM)");
        String[] args = wasm == null ? new String[] { "--orx-wasm-embedded" }
                                     : new String[] { wasm.getPath(), "--orx-wasm-embedded" };
        if (System.getProperty("rexxjvm.executable") == null) {
            String home = Host.libraryDirectory();
            if (home != null) System.setProperty("rexxjvm.executable", home + "rexx");
        }
        RunWasm.embedded = true;
        Thread t = new Thread(null, () -> {
            try {
                List<String> argv = RunWasm.load(args);
                RunWasm.link(argv);
                mainExit = RunWasm.start();
            } catch (Throwable x) {
                mainFailure = x;
            } finally {
                mainWaiting.countDown();      // if it never got there
            }
        }, "rexx-main", RunWasm.STACK);
        t.setDaemon(true);
        t.start();
        await(mainWaiting);
        if (mainFailure != null) throw new IllegalStateException("the module failed to start", mainFailure);
        if (!t.isAlive() && mainExit >= 0 && released.getCount() > 0)
            throw new IllegalStateException("the module ended at start-up, exit code " + mainExit);
        return current = new Rexx(t);
    }

    /**
     * Starts the module in the embedded mode if nothing has loaded it yet: a Java
     * program that calls BSF4ooRexx's {@code native} methods (JSR-223, {@code RexxEngine})
     * without having started the module itself. Beside an ordinary program, or once
     * started, it does nothing.
     */
    static synchronized void startIfNeeded() {
        if (current == null && RunWasm.module == null) start();
    }

    /** Creates an interpreter instance (on a home thread of its own). */
    public Interpreter newInterpreter() {
        if (closed) throw new IllegalStateException("closed");
        Request home = new Request(null, inst -> {
            inst.export("orx_embed_home").apply(Request.current().id);
            return null;
        });
        Interpreter in = new Interpreter(home);
        home.interpreter = in;
        submit(home);
        await(home.ready);
        if (home.failure != null) throw new IllegalStateException("no interpreter instance", home.failure);
        if (in.handle == 0) {
            home.release.countDown();
            throw new IllegalStateException("RexxCreateInterpreter failed");
        }
        interpreters.add(in);
        return in;
    }

    /** Closes every interpreter instance still open, and ends the module. */
    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        for (Interpreter in : new ArrayList<>(interpreters)) in.close();
        requests.add(-1);                     // the dispatcher ends
        released.countDown();                 // and main returns
        try { mainThread.join(); } catch (InterruptedException x) { Thread.currentThread().interrupt(); }
    }

    // ---- requests: what a thread does inside the module ----

    interface Work<T> { T run(Instance inst) throws Exception; }

    static final class Request {
        static final ThreadLocal<Request> running = new ThreadLocal<>();
        static Request current() { return running.get(); }

        final int id = nextId.getAndIncrement();
        /** The Java thread that runs it; null: a new one (a home thread). */
        final Thread caller;
        final Work<?> work;
        final SynchronousQueue<int[]> handover = new SynchronousQueue<>();
        final CountDownLatch ready = new CountDownLatch(1), release = new CountDownLatch(1),
                             done = new CountDownLatch(1);
        Interpreter interpreter;
        Object result;
        volatile Throwable failure;

        Request(Thread caller, Work<?> work) { this.caller = caller; this.work = work; }
    }

    private static final AtomicInteger nextId = new AtomicInteger(1);
    private static final BlockingQueue<Integer> requests = new LinkedBlockingQueue<>();
    private static final Map<Integer, Request> pending = new ConcurrentHashMap<>();
    /** Set on the dispatcher's thread between embed_next and the thread-spawn it causes. */
    private static final ThreadLocal<Request> spawning = new ThreadLocal<>();

    private static void submit(Request r) {
        pending.put(r.id, r);
        requests.add(r.id);
    }

    /** Runs work inside the module, on the calling Java thread, in a pthread of its own. */
    <T> T enter(Work<T> work) {
        if (closed) throw new IllegalStateException("closed");
        return foreign(work);
    }

    /**
     * Runs work inside the module on the calling Java thread, which is not a wasm
     * thread: a foreign entry through the dispatcher, which must be running (the
     * embedded mode, or orxWasiStartDispatcher beside an ordinary program).
     */
    @SuppressWarnings("unchecked")
    static <T> T foreign(Work<T> work) {
        Request r = new Request(Thread.currentThread(), work);
        submit(r);
        int[] ts;
        try {
            ts = r.handover.take();           // (tid, startArg), from thread-spawn
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while entering the module", x);
        }
        try {
            if (ts[0] >= 0) RunWasm.runOn("wasi_thread_start", ts[0], ts[1]);
        } finally {
            pending.remove(r.id);
        }
        if (r.failure instanceof RuntimeException) throw (RuntimeException) r.failure;
        if (r.failure instanceof Error) throw (Error) r.failure;
        if (r.failure != null) throw new IllegalStateException(r.failure);
        return (T) r.result;
    }

    /** thread-spawn: is this pthread for a foreign entry? Then hand it to its caller. */
    static boolean handover(int tid, int startArg) {
        Request r = spawning.get();
        if (r == null) return false;
        spawning.remove();
        if (r.caller == null) return false;   // a home thread: a Java thread of its own
        try {
            r.handover.put(new int[] { tid, startArg });
        } catch (InterruptedException x) {
            throw new IllegalStateException(x);
        }
        return true;
    }

    /** A module thread called exit(): the embedded module cannot go on. */
    static void exited(int code) {
        System.err.println("rexx embedded: the module called exit(" + code + ")");
    }

    // ---- the imports (module "rexxjvm") ----

    static List<HostFunction> functions() {
        List<ValType> none = List.of(), i32 = List.of(ValType.I32);
        return List.of(
            new HostFunction("rexxjvm", "embed_main_wait", none, none, (inst, a) -> {
                mainWaiting.countDown();
                await(released);
                return null;
            }),
            new HostFunction("rexxjvm", "embed_next", none, i32, (inst, a) -> {
                int id;
                try { id = requests.take(); }
                catch (InterruptedException x) { return new long[] { -1 }; }
                if (id > 0) spawning.set(pending.get(id));
                return new long[] { id };
            }),
            new HostFunction("rexxjvm", "embed_run", i32, none, (inst, a) -> {
                Request r = pending.get((int) a[0]);
                if (r == null) return null;
                Request outer = Request.running.get();
                Request.running.set(r);
                try {
                    if (r.caller != null && r.caller != Thread.currentThread()) {
                        // the dispatcher could not create a pthread: tell the caller
                        spawning.remove();
                        r.failure = new IllegalStateException("the module could not create a thread");
                        r.handover.offer(new int[] { -1, -1 }, 10, TimeUnit.SECONDS);
                        return null;
                    }
                    r.result = r.work.run(inst);
                } catch (Throwable x) {
                    r.failure = x;
                } finally {
                    Request.running.set(outer);
                    if (r.caller == null) {       // a home thread ends
                        pending.remove(r.id);
                        r.ready.countDown();
                        r.done.countDown();
                    }
                }
                return null;
            }),
            new HostFunction("rexxjvm", "embed_home", List.of(ValType.I32, ValType.I32), none, (inst, a) -> {
                Request r = pending.get((int) a[0]);
                r.interpreter.handle = (int) a[1];
                r.ready.countDown();
                if (a[1] != 0) await(r.release);
                return null;
            }));
    }

    // ---- string lists in linear memory (see orx_wasi_embed.cpp) ----

    static int writeList(Instance inst, List<String> l) {
        byte[][] bs = new byte[l.size()][];
        int size = 4;
        for (int i = 0; i < bs.length; i++) {
            String s = l.get(i);
            bs[i] = s == null ? null : s.getBytes(StandardCharsets.UTF_8);
            size += 4 + (s == null ? 0 : bs[i].length);
        }
        int p = (int) inst.export("orx_embed_alloc").apply(size)[0];
        Memory m = inst.memory();
        m.writeI32(p, bs.length);
        int q = p + 4;
        for (byte[] b : bs) {
            m.writeI32(q, b == null ? -1 : b.length);
            q += 4;
            if (b != null) { m.write(q, b); q += b.length; }
        }
        return p;
    }

    static List<String> readList(Memory m, int p) {
        int n = m.readInt(p);
        List<String> l = new ArrayList<>(n);
        int q = p + 4;
        for (int i = 0; i < n; i++) {
            int len = m.readInt(q);
            q += 4;
            if (len < 0) { l.add(null); continue; }
            l.add(new String(m.readBytes(q, len), StandardCharsets.UTF_8));
            q += len;
        }
        return l;
    }

    static void free(Instance inst, int p) { inst.export("orx_embed_free").apply(p); }

    private static long num(String s) {
        try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (NumberFormatException x) { return 0; }
    }

    private static void await(CountDownLatch l) {
        boolean interrupted = false;
        for (;;) {
            try { l.await(); break; }
            catch (InterruptedException x) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}

package rexxjvm;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.types.ValType;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

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
 * is a reasonable minimum, deep Rexx recursion needs more). Each {@link #start()} is a
 * module of its own ({@link Run}); the code the JIT compiled is shared. Strings cross as
 * UTF-8. Thread-safe.
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
        final Entries.Request home;
        volatile int handle;
        volatile boolean closed;

        Interpreter(Entries.Request home) { this.home = home; }

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

    // ---- the module: a run of its own per Rexx ----

    private final CountDownLatch mainWaiting = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile Throwable mainFailure;
    private volatile int mainExit = -1;
    private volatile Run run;

    private Thread mainThread;
    private final Set<Interpreter> interpreters = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private Rexx() {}

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

    /**
     * Starts the module from a {@code .wasm} file ({@code null}: the embedded one). Each
     * call starts a module of its own, with its own memory, in the same JVM: the code the
     * JIT compiled is shared.
     */
    public static Rexx start(File wasm) {
        Run.Options o = new Run.Options();
        o.args = wasm == null ? List.of("--orx-wasm-embedded") : List.of(wasm.getPath(), "--orx-wasm-embedded");
        o.embedded = true;
        synchronized (Rexx.class) {
            if (System.getProperty("rexxjvm.executable") == null) {
                String home = Host.libraryDirectory();
                if (home != null) System.setProperty("rexxjvm.executable", home + "rexx");
            }
        }
        Rexx rexx = new Rexx();
        Thread t = new Thread(null, () -> {
            try {
                rexx.run = new Run(o, rexx);
                rexx.mainExit = rexx.run.start();
            } catch (Throwable x) {
                rexx.mainFailure = x;
            } finally {
                rexx.mainWaiting.countDown();      // if it never got there
                Run r = rexx.run;
                if (r != null) r.end(rexx.mainExit);
            }
        }, "rexx-main", Run.STACK);
        t.setDaemon(true);
        rexx.mainThread = t;
        t.start();
        await(rexx.mainWaiting);
        if (rexx.mainFailure != null) throw new IllegalStateException("the module failed to start", rexx.mainFailure);
        if (!t.isAlive() && rexx.mainExit >= 0 && rexx.released.getCount() > 0)
            throw new IllegalStateException("the module ended at start-up, exit code " + rexx.mainExit);
        return rexx;
    }

    /**
     * The run that serves a Java thread with no run of its own (a Java program that calls
     * BSF4ooRexx's {@code native} methods: JSR-223, {@code RexxEngine}): the first one
     * started; if there is none, the module is started now in the embedded mode, as
     * {@link #start()} does.
     */
    static synchronized Run primaryOrStart() {
        Run r = Run.primary();
        if (r == null) r = start().run;
        return r;
    }

    /** Creates an interpreter instance (on a home thread of its own). */
    public Interpreter newInterpreter() {
        if (closed) throw new IllegalStateException("closed");
        Entries.Request home = new Entries.Request(null, inst -> {
            inst.export("orx_embed_home").apply(Entries.Request.current().id);
            return null;
        });
        Interpreter in = new Interpreter(home);
        home.interpreter = in;
        run.entries.submit(home);
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
        run.entries.close();                  // the dispatcher ends
        released.countDown();                 // and main returns
        try { mainThread.join(); } catch (InterruptedException x) { Thread.currentThread().interrupt(); }
    }

    /** Runs work inside the module, on the calling Java thread, in a pthread of its own. */
    <T> T enter(Entries.Work<T> work) {
        if (closed) throw new IllegalStateException("closed");
        return run.entries.foreign(work);
    }

    /** A module thread called exit(): the embedded module cannot go on. */
    static void exited(int code) {
        System.err.println("rexx embedded: the module called exit(" + code + ")");
    }

    // ---- the imports (module "rexxjvm") ----

    /** The embedded mode's imports, and the foreign entries' (every run has them). */
    static List<HostFunction> functions(Run run) {
        List<ValType> none = List.of();
        Rexx rexx = run.rexx;
        List<HostFunction> l = new ArrayList<>(run.entries.functions());
        l.add(new HostFunction("rexxjvm", "embed_main_wait", none, none, (inst, a) -> {
            if (rexx == null) return null;
            rexx.mainWaiting.countDown();
            await(rexx.released);
            return null;
        }));
        l.add(new HostFunction("rexxjvm", "embed_home", List.of(ValType.I32, ValType.I32), none, (inst, a) -> {
            Entries.Request r = run.entries.pending((int) a[0]);
            r.interpreter.handle = (int) a[1];
            r.ready.countDown();
            if (a[1] != 0) await(r.release);
            return null;
        }));
        return l;
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

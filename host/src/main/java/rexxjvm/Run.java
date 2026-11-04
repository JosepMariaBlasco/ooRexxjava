package rexxjvm;

import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.*;
import run.endive.wasi.WasiExitException;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.MemoryImport;
import run.endive.wasm.types.ValType;
import java.io.File;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * One execution of the module: a program run by {@code rexxj}, the module of an embedded
 * {@link Rexx}, or a program run by a Java host that runs many of them in one JVM.
 *
 * <p>What every run shares is the module, translated to JVM bytecode once per JVM
 * ({@link Module}): so the code the JIT compiled for one run serves the next. What is
 * a run's own: its arguments, environment, current directory and standard streams
 * (its WASI), its linear memory, its imports (the launcher's functions, which find
 * their run as {@link #current()}), its threads and thread ids, its signals, its
 * foreign entries ({@link Entries}), the module's {@code JNIEnv}, and the resources it
 * opens (sockets, connections to the API server, JNI global references), released when
 * it ends.
 *
 * <p>Two kinds. A <em>process</em> run owns the JVM, as {@code rexxj} does: an
 * {@code exit} from any thread ends the JVM, the process's signals are its, and commands
 * inherit the process's standard streams. A <em>hosted</em> run does not: an
 * {@code exit} ends the run ({@link #end}), whose threads are interrupted, and commands
 * write to the run's streams. The embedded mode is hosted, except that an {@code exit}
 * there is only reported (the Java host goes on using the module).
 */
final class Run {

    // ---- the module, translated once per JVM ------------------------------------------

    /** A module and its machine factory, shared by every run of the JVM. */
    static final class Module {
        final WasmModule wasm;
        final Function<Instance, Machine> machine;    // null: Chicory's interpreter
        /** The module file given on the command line; null: the one translated at build time. */
        final String file;
        final MemoryImport memoryImport;

        Module(WasmModule wasm, Function<Instance, Machine> machine, String file) {
            this.wasm = wasm;
            this.machine = machine;
            this.file = file;
            // the shared memory the module imports (wasi-threads modules import "env" "memory")
            memoryImport = wasm.importSection().stream()
                .filter(i -> i instanceof MemoryImport).map(i -> (MemoryImport) i).findFirst().orElseThrow();
        }
    }

    private static final Map<String, Module> MODULES = new HashMap<>();

    /** The module in a .wasm file (translated at start-up), or (null) the one translated at build time. */
    static synchronized Module module(String file) {
        String key = file == null ? "" : file;
        Module m = MODULES.get(key);
        if (m == null) {
            if (file == null) {
                CompiledModule c = embeddedModule();
                if (c == null) throw new IllegalArgumentException("no module: give a .wasm file");
                m = new Module(c.wasmModule(), c.machineFactory(), null);
            } else {
                WasmModule w = Parser.parse(new File(file));
                m = new Module(w, Boolean.getBoolean("rexxjvm.interp") ? null : MachineFactoryCompiler.compile(w), file);
            }
            MODULES.put(key, m);
        }
        return m;
    }

    private static Boolean translated;

    /** Does this jar carry the module translated at build time (and -Drexxjvm.interp is not set)? */
    static synchronized boolean hasTranslated() {
        if (translated == null) {
            translated = !Boolean.getBoolean("rexxjvm.interp") && Run.class.getClassLoader() != null
                && Run.class.getClassLoader().getResource("rexxjvm/RexxModule.class") != null;
        }
        return translated;
    }

    /** The module translated at build time, if this jar carries one (and -Drexxjvm.interp is not set). */
    static CompiledModule embeddedModule() {
        if (Boolean.getBoolean("rexxjvm.interp")) return null;
        try {
            return (CompiledModule) Class.forName("rexxjvm.RexxModule").getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException x) {
            return null;
        } catch (ReflectiveOperationException x) {
            throw new IllegalStateException(x);
        }
    }

    // ---- what a run is given ------------------------------------------------------------

    /** How to run: everything left null or -1 is the JVM's own. */
    static final class Options {
        /**
         * As on {@code rexxj}'s command line: a {@code .wasm} file first runs that module,
         * else the one translated at build time; the rest goes to the module.
         */
        List<String> args = List.of();
        Map<String, String> env;              // null: the JVM's environment
        String cwd;                           // null: user.dir
        InputStream in;                       // null: the process's standard streams
        OutputStream out, err;
        boolean inTty, outTty, errTty;
        boolean process;                      // the run owns the JVM
        boolean embedded;                     // the module of a Rexx (embedded mode)
        long pid = -1, ppid = -1;             // getpid() and getppid(); -1: the JVM's
        /**
         * Where the commands the program runs read and write, if not the run's streams
         * (a server gives its client's: /proc/PID/fd/0, 1, 2); null: the run's streams
         * (the process's are inherited; a hosted run's own are copied, and no input).
         */
        ProcessBuilder.Redirect cmdIn, cmdOut, cmdErr;
        /**
         * The class loader of the run's Java side (a server's run: BSF4ooRexx and the
         * client's CLASSPATH), the context class loader of its threads; null: the
         * thread's. Java threads with no run of their own that call a native method of a
         * class it loaded enter this run ({@link #forLoader}).
         */
        ClassLoader loader;
        /**
         * The client's standard input when it is a regular file (a server's run), the
         * channel the run reads it through: commands read it from where the run's
         * reading has reached, and the run goes on from where they left it (Host).
         */
        java.nio.channels.FileChannel inFile;
        /**
         * A server's client whose standard streams are relayed through the socket: the
         * commands the program runs are started by the client (Host, Relay).
         */
        Relay relay;
    }

    // ---- the run ------------------------------------------------------------------------

    /**
     * Java stack for every thread that runs wasm code. Chicory's frames live on it, so
     * deep Rexx recursion needs far more than Java's default (the suite's CALL group
     * overflows 64 MB and passes with 256 MB). Reserved, not committed: unused stack
     * costs no memory. {@code -Drexxjvm.stack=<bytes>} overrides it.
     */
    static final long STACK = Long.getLong("rexxjvm.stack", 256L << 20);

    private static final AtomicInteger IDS = new AtomicInteger(1);
    private static final ThreadLocal<Run> CURRENT = new ThreadLocal<>();
    /**
     * The Instance through which the current Java thread runs wasm code, if it does: a
     * native method called on it (layer 1, {@link Natives}) re-enters the module there.
     */
    static final ThreadLocal<Instance> INSTANCE = new ThreadLocal<>();
    /** The run that serves Java threads with no run of their own: the first one started. */
    private static Run primary;
    /** The runs with a class loader of their own (Options.loader), by loader. */
    private static final Map<ClassLoader, Run> BY_LOADER = new ConcurrentHashMap<>();

    final int id = IDS.getAndIncrement();
    final Module module;
    /** The module's argv. */
    final List<String> argv;
    final boolean process, embedded;
    final long pid, ppid;
    /** The run's standard streams; null: the process's. */
    final InputStream in;
    final OutputStream out, err;
    /** Where commands read and write instead (Options.cmdIn, cmdOut, cmdErr); null: as above. */
    final ProcessBuilder.Redirect cmdIn, cmdOut, cmdErr;
    /** The class loader of the run's Java side (Options.loader); null: none of its own. */
    final ClassLoader loader;
    /** The client's standard input as a regular file (Options.inFile); null if it is not one. */
    final java.nio.channels.FileChannel inFile;
    /** The relay of a server's client (Options.relay); null if none. */
    final Relay relay;
    final Memory memory;
    final ImportValues imports;
    private final WasiPreview1 wasi;
    final AtomicInteger nextTid = new AtomicInteger(1);
    /** The run's wasm threads but the main one (thread-spawn's), while they live. */
    final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    private volatile Thread mainThread;
    /** Foreign entries: Java threads that run code of the module (the dispatcher's requests). */
    final Entries entries = new Entries(this);
    /** The embedded mode's Rexx, if this is its module. */
    final Rexx rexx;

    // the signals the run gets: a HALT in the program once its signal thread waits (Host)
    final LinkedBlockingQueue<Integer> signals = new LinkedBlockingQueue<>();
    volatile boolean signalsWanted, signalWaiting;

    /** The module's JNIEnv *, once known (Natives). */
    volatile int jniEnv;

    /** What the run opened and must close when it ends: sockets (Net), connections (RxApi). */
    final Set<Integer> sockets = ConcurrentHashMap.newKeySet(), apiHandles = ConcurrentHashMap.newKeySet();

    private volatile boolean ended;
    private volatile int exitCode = -1;

    Run(Options o) { this(o, null); }

    Run(Options o, Rexx rexx) {
        this.rexx = rexx;
        process = o.process;
        embedded = o.embedded;
        pid = o.pid;
        ppid = o.ppid;
        in = o.in;
        out = o.out;
        err = o.err;
        cmdIn = o.cmdIn;
        cmdOut = o.cmdOut;
        cmdErr = o.cmdErr;
        loader = o.loader;
        inFile = o.inFile;
        relay = o.relay;

        // the module, and its argv: a .wasm file first, else the one translated at build time
        List<String> args = o.args;
        List<String> av = new ArrayList<>(args);
        if (hasTranslated() && !(!args.isEmpty() && args.get(0).endsWith(".wasm"))) {
            module = module(null);
            av.add(0, "rexx");
        } else {
            if (args.isEmpty()) throw new IllegalArgumentException("no module: give a .wasm file");
            module = module(new File(args.get(0)).getAbsolutePath());
            av.set(0, new File(args.get(0)).getName());
        }
        argv = Collections.unmodifiableList(WinPath.WINDOWS ? guestArgs(av) : av);

        // WASI: the arguments, the environment, the whole file system, the standard streams
        // (on Windows every drive, as /c, /d...: WinPath)
        WasiOptions.Builder ob = WasiOptions.builder().inheritSystem().withArguments(argv);
        if (WinPath.WINDOWS) {
            for (File r : WinPath.roots()) {
                String s = r.getPath();
                if (s.length() >= 2 && s.charAt(1) == ':' && r.isDirectory()) ob.withDirectory(WinPath.drive(s.charAt(0)), r.toPath());
            }
        } else {
            ob.withDirectory("/", Path.of("/"));
        }
        if (in != null) ob.withStdin(new KeepOpenIn(in), o.inTty);
        if (out != null) ob.withStdout(new KeepOpenOut(out), o.outTty);
        if (err != null) ob.withStderr(new KeepOpenOut(err), o.errTty);
        // A Java host (the embedded mode, JSR-223): the interpreter's directory goes first
        // in PATH, as the rexxj script puts it, so that ::requires finds BSF.CLS and the
        // other .cls beside rexx.img without the host having to set PATH.
        // On Windows, PATH (Path, whatever its case there) and PWD in the module's form.
        Map<String, String> env = o.env != null ? o.env : System.getenv();
        String path = null;
        for (Map.Entry<String, String> e : env.entrySet())
            if (WinPath.WINDOWS ? e.getKey().equalsIgnoreCase("PATH") : e.getKey().equals("PATH"))
                path = WinPath.listToGuest(e.getValue());
        String home = embedded ? WinPath.toGuest(Host.libraryDirectory()) : null;
        if (home != null) {
            home = home.endsWith("/") && home.length() > 1 ? home.substring(0, home.length() - 1) : home;
            path = path == null || path.isEmpty() ? home : home + ":" + path;
        }
        // On Windows, HOME and TMPDIR too, in the module's form: the interpreter takes "~"
        // from HOME and its temporary files' directory from TMPDIR (else /tmp, which
        // Windows does not have); when they are not set, from USERPROFILE and TEMP (TMP)
        String winHome = null, winTmp = null;
        if (WinPath.WINDOWS) {
            String profile = null, temp = null, tmp = null;
            for (Map.Entry<String, String> e : env.entrySet()) {
                String k = e.getKey();
                if (k.equalsIgnoreCase("HOME")) winHome = e.getValue();
                else if (k.equalsIgnoreCase("TMPDIR")) winTmp = e.getValue();
                else if (k.equalsIgnoreCase("USERPROFILE")) profile = e.getValue();
                else if (k.equalsIgnoreCase("TEMP")) temp = e.getValue();
                else if (k.equalsIgnoreCase("TMP")) tmp = e.getValue();
            }
            if (winHome == null || winHome.isEmpty()) winHome = profile;
            if (winTmp == null || winTmp.isEmpty()) winTmp = temp != null ? temp : tmp;
            winHome = WinPath.toGuest(winHome);
            winTmp = WinPath.toGuest(winTmp);
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            String k = e.getKey();
            boolean special = WinPath.WINDOWS
                ? k.equalsIgnoreCase("PWD") || k.equalsIgnoreCase("PATH") || k.equalsIgnoreCase("HOME") || k.equalsIgnoreCase("TMPDIR")
                : k.equals("PWD") || k.equals("PATH");
            if (!special) ob.withEnvironment(k, e.getValue());
        }
        if (winHome != null) ob.withEnvironment("HOME", winHome);
        if (winTmp != null) ob.withEnvironment("TMPDIR", winTmp);
        if (path != null) ob.withEnvironment("PATH", path);
        ob.withEnvironment("PWD", WinPath.toGuest(o.cwd != null ? o.cwd : System.getProperty("user.dir")));
        // the C stack of the threads the interpreter creates (compat/orx_wasi_host.c,
        // orxWasiThreadStack): -Drexxjvm.threadstack=1M, else REXXJVM_THREADSTACK
        String threadStack = System.getProperty("rexxjvm.threadstack");
        if (threadStack != null) ob.withEnvironment("REXXJVM_THREADSTACK", threadStack);
        // -Drexxjvm.wasi.log=true: Endive's log of every WASI call, to standard error
        WasiPreview1.Builder wb = WasiPreview1.builder().withOptions(ob.build());
        if (Boolean.getBoolean("rexxjvm.wasi.log")) wb.withLogger(new run.endive.log.Logger() {
            @Override public void log(Level level, String msg, Throwable t) {
                System.err.println("wasi " + level + " " + msg + (t != null ? " " + t : ""));
            }
            @Override public boolean isLoggable(Level level) { return true; }
        });
        wasi = wb.build();

        memory = FlatMemory.create(module.memoryImport.limits());   // FlatMemory, unless -Drexxjvm.memory=bytearray

        HostFunction spawn = new HostFunction("wasi", "thread-spawn",
            List.of(ValType.I32), List.of(ValType.I32), (inst, a) -> new long[] { spawn((int) a[0]) });
        MemoryImport mi = module.memoryImport;
        imports = ImportValues.builder()
            .addFunction(WinWasi.functions(wasi))
            .addFunction(spawn)
            .addFunction(Host.functions().toArray(new HostFunction[0]))
            .addFunction(RxApi.functions().toArray(new HostFunction[0]))
            .addFunction(FileSys.functions().toArray(new HostFunction[0]))
            .addFunction(Net.functions().toArray(new HostFunction[0]))
            .addFunction(Rexx.functions(this).toArray(new HostFunction[0]))
            .addFunction(Jni.functions().toArray(new HostFunction[0]))
            .addMemory(new ImportMemory(mi.module(), mi.name(), memory))
            .build();

        // a hosted run gets its signals from its host (signal); the embedded mode, none
        signalsWanted = !process && !embedded;

        synchronized (Run.class) {
            if (primary == null && loader == null) primary = this;
        }
        if (loader != null) BY_LOADER.put(loader, this);
    }

    /**
     * Windows: the program's name on rexx's command line in the module's form (WinPath),
     * the first argument that is not an option or an option's value (-o and -od take
     * one: rexx.cpp; none after -e, whose program is text); for rexxc, its file names;
     * for the test suite's rexxinstance, its program, after the call type and the
     * options (each with a value: testbinaries/rexxinstance.cpp). Other arguments go to
     * the program as they are.
     */
    static List<String> guestArgs(List<String> argv) {
        List<String> l = new ArrayList<>(argv);
        String tool = l.size() > 1 && l.get(1).startsWith("--orx-wasm-") ? l.get(1).substring(11) : null;
        if (tool != null && !tool.equals("rexxc") && !tool.equals("rexxinstance")) return l;
        boolean rexxc = "rexxc".equals(tool);
        boolean instance = "rexxinstance".equals(tool);
        for (int i = tool == null ? 1 : instance ? 3 : 2; i < l.size(); i++) {
            String a = l.get(i);
            if (a.startsWith("-")) {
                if (tool == null && a.equals("-e")) break;
                if (instance || (tool == null && (a.equalsIgnoreCase("-o") || a.equalsIgnoreCase("-od")))) i++;
                continue;
            }
            l.set(i, WinPath.toGuest(a));
            if (!rexxc) break;
        }
        return l;
    }

    /**
     * The run whose Java side a class loader belongs to (it or one of its ancestors is a
     * run's Options.loader); null if none.
     */
    static Run forLoader(ClassLoader l) {
        if (BY_LOADER.isEmpty()) return null;
        for (; l != null; l = l.getParent()) {
            Run r = BY_LOADER.get(l);
            if (r != null) return r;
        }
        return null;
    }

    /** The run of the current thread, if it runs wasm code (or a foreign entry); else null. */
    static Run current() { return CURRENT.get(); }

    /** The run that serves Java threads with no run of their own; null if none. */
    static synchronized Run primary() { return primary; }

    /** thread-spawn: a pthread on a Java thread of its own, or handed to a foreign entry's caller. */
    private int spawn(int startArg) {
        int tid = nextTid.getAndIncrement();
        if (entries.handover(tid, startArg)) return tid;
        Thread t = new Thread(null, () -> {
            try {
                runOn("wasi_thread_start", tid, startArg);
            } catch (WasiExitException x) {
                exit(x.exitCode());
            } catch (Throwable x) {
                if (!ended) {
                    System.err.println("thread " + tid + ": " + x);
                    x.printStackTrace();
                    if (!embedded) exit(70);
                }
            } finally {
                threads.remove(Thread.currentThread());
            }
        }, "wasm-" + tid, STACK);
        t.setDaemon(true);
        threads.add(t);
        t.start();
        return tid;
    }

    /** Runs an export on a new Instance for the current thread (a wasm thread's entry point). */
    long[] runOn(String export, long... args) {
        Instance prevInstance = INSTANCE.get();
        Run prevRun = CURRENT.get();
        Instance inst = newInstance();
        INSTANCE.set(inst);
        CURRENT.set(this);
        try {
            return inst.export(export).apply(args);
        } finally {
            if (prevInstance == null) INSTANCE.remove(); else INSTANCE.set(prevInstance);
            if (prevRun == null) CURRENT.remove(); else CURRENT.set(prevRun);
        }
    }

    Instance newInstance() {
        Instance.Builder b = Instance.builder(module.wasm).withImportValues(imports).withStart(false);
        if (module.machine != null) b = b.withMachineFactory(module.machine);
        return b.build();
    }

    /**
     * Runs {@code _start} on the current thread; returns the exit code. A hosted run
     * (not the embedded mode's) then ends: see {@link #end}.
     */
    int start() {
        mainThread = Thread.currentThread();
        int rc;
        try {
            runOn("_start");
            rc = 0;
        } catch (WasiExitException x) {
            rc = x.exitCode();
        } catch (RuntimeException | Error x) {
            if (!ended) {
                if (!process && !embedded) end(70);
                throw x;
            }
            rc = exitCode;                    // another thread ended the run: its exit code
        }
        if (!process && !embedded) end(rc);
        return ended ? exitCode : rc;
    }

    /** A thread of the module called exit() (the main thread's ends start() instead). */
    void exit(int code) {
        if (embedded) {
            Rexx.exited(code);
            return;
        }
        if (process) {
            System.out.flush();
            System.exit(code);
        }
        end(code);
    }

    /** A signal for the run (SIGINT, SIGTERM, SIGHUP): a HALT once the program waits for them, else the end. */
    void signal(int number) {
        if (signalWaiting) {
            signals.offer(number);
        } else if (process) {
            System.out.flush();
            Runtime.getRuntime().halt(128 + number);
        } else {
            end(128 + number);
        }
    }

    boolean ended() { return ended; }

    int exitCode() { return exitCode; }

    /** Flushes the run's output, before a command writes to the same place. */
    void flush() {
        try {
            if (out != null) out.flush(); else System.out.flush();
            if (err != null) err.flush(); else System.err.flush();
        } catch (IOException x) {
            // nowhere to report it
        }
    }

    /**
     * Ends a hosted run, as the end of a process would: its threads are interrupted
     * (Chicory checks the interruption on every call and loop; waits in the launcher are
     * interruptible), what it opened is closed, and its memory is left to be collected.
     * Returns at once; {@link #awaitThreads} waits for the threads.
     */
    void end(int code) {
        synchronized (this) {
            if (ended) return;
            ended = true;
            exitCode = code;
        }
        flush();
        Thread self = Thread.currentThread();
        for (Thread t : threads) if (t != self) t.interrupt();
        Thread main = mainThread;
        if (main != null && main != self) main.interrupt();
        signalWaiting = false;
        entries.close();
        for (Integer h : new ArrayList<>(sockets)) Net.close(h);
        for (Integer h : new ArrayList<>(apiHandles)) RxApi.close(h);
        Jni.releaseRun(id);
        try {
            wasi.close();
        } catch (RuntimeException x) {
            // closing anyway
        }
        synchronized (Run.class) {
            if (primary == this) primary = null;
        }
        if (loader != null) {
            BY_LOADER.remove(loader, this);
            Jni.releaseLoader(loader);
        }
    }

    /** Waits up to the timeout for the run's threads to end; returns how many are left. */
    int awaitThreads(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (Thread t : new ArrayList<>(threads)) {
            long left = deadline - System.nanoTime();
            if (left <= 0) break;
            try {
                t.join(Math.max(1, left / 1_000_000L));
            } catch (InterruptedException x) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int n = 0;
        for (Thread t : threads) if (t.isAlive()) n++;
        return n;
    }

    /** Streams WASI closes when the run ends: the caller's stay open. */
    private static final class KeepOpenIn extends FilterInputStream {
        KeepOpenIn(InputStream in) { super(in); }
        @Override public void close() {}
    }

    private static final class KeepOpenOut extends FilterOutputStream {
        KeepOpenOut(OutputStream out) { super(out); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
        @Override public void close() throws IOException { out.flush(); }
    }
}

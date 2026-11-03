package rexxjvm;

import run.endive.runtime.HostFunction;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.wasm.types.ValType;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The functions the launcher gives the module (import module {@code "rexxjvm"}), behind
 * the host functions of oorexxwasm's {@code SysWasmHost.hpp} ({@code compat/orx_wasi_host.c}):
 * external commands as processes of the JVM's host, through {@link ProcessBuilder}, and
 * the paths behind {@code .RexxInfo~executable} and {@code ~libraryPath}.
 */
final class Host {
    // SysWasmHost.hpp's flags
    static final int INPUT = 1, OUTPUT = 2, ERROR = 4, SAME = 8;
    static final int UNKNOWN_COMMAND = 127;

    /** What the last command of this thread collected: [0] output, [1] error output. */
    static final ThreadLocal<byte[][]> results = ThreadLocal.withInitial(() -> new byte[2][]);

    private Host() {}

    static List<HostFunction> functions() {
        List<ValType> i32 = List.of(ValType.I32);
        return List.of(
            new HostFunction("rexxjvm", "command_run",
                List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32), i32,
                (inst, a) -> new long[] { commandRun(inst.memory(),
                    (int) a[0], (int) a[1], (int) a[2], (int) a[3], (int) a[4], (int) a[5]) }),
            new HostFunction("rexxjvm", "command_result_length", i32, i32, (inst, a) -> {
                byte[] r = results.get()[(int) a[0] & 1];
                return new long[] { r == null ? 0 : r.length };
            }),
            new HostFunction("rexxjvm", "command_result_copy", List.of(ValType.I32, ValType.I32), List.of(),
                (inst, a) -> {
                    byte[][] rs = results.get();
                    int which = (int) a[0] & 1;
                    if (rs[which] != null) inst.memory().write((int) a[1], rs[which]);
                    rs[which] = null;
                    return null;
                }),
            new HostFunction("rexxjvm", "executable_path", List.of(ValType.I32, ValType.I32), i32,
                (inst, a) -> new long[] { put(inst.memory(), (int) a[0], (int) a[1],
                                              System.getProperty("rexxjvm.executable")) }),
            new HostFunction("rexxjvm", "library_directory", List.of(ValType.I32, ValType.I32), i32,
                (inst, a) -> new long[] { put(inst.memory(), (int) a[0], (int) a[1], libraryDirectory()) }),
            new HostFunction("rexxjvm", "signal_wait", List.of(), i32, (inst, a) -> new long[] { signalWait() }));
    }

    /*
     * The host's signals, for a process run (a hosted run gets them from its host,
     * Run.signal). SIGINT (Ctrl-C), SIGTERM and SIGHUP become a HALT in the
     * running program, as natively: the module's signal thread (compat/orx_wasi_host.c)
     * waits here for them and calls the interpreter's orxWasmSignal(). Until that thread
     * waits, which is once the interpreter has started, a signal ends the process as
     * the default action would (128 + its number). Only for rexx itself: the tools
     * (rexx --orx-wasm-TOOL, the API server's daemon among them) keep the JVM's
     * default. Through sun.misc.Signal (jdk.unsupported), by reflection; without it,
     * there are no signals for the program, and the JVM's default stays.
     */
    static void installSignals(Run run) {
        List<String> argv = run.argv;
        if (argv.size() > 1 && argv.get(1).startsWith("--orx-wasm-")) return;
        try {
            Class<?> signal = Class.forName("sun.misc.Signal");
            Class<?> handlerType = Class.forName("sun.misc.SignalHandler");
            Object handler = java.lang.reflect.Proxy.newProxyInstance(Host.class.getClassLoader(),
                new Class<?>[] { handlerType }, (proxy, method, args) -> {
                    if (!method.getName().equals("handle")) return null;
                    run.signal((Integer) signal.getMethod("getNumber").invoke(args[0]));
                    return null;
                });
            for (String name : new String[] { "INT", "TERM", "HUP" }) {
                try {
                    Object s = signal.getConstructor(String.class).newInstance(name);
                    signal.getMethod("handle", signal, handlerType).invoke(null, s, handler);
                } catch (ReflectiveOperationException | RuntimeException x) {
                    // not this one here (the JVM may use it, or the system lack it)
                }
            }
            run.signalsWanted = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError x) {
            // no sun.misc.Signal: no signals for the program
        }
    }

    /** The next signal for the module's signal thread; -1: there are none to wait for. */
    static int signalWait() {
        Run run = Run.current();
        if (run == null || !run.signalsWanted || run.ended()) return -1;
        run.signalWaiting = true;
        try {
            return run.signals.take();
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** A C string into buf; 0 if there is none or it does not fit. */
    static int put(Memory m, int buf, int size, String s) {
        if (s == null || s.isEmpty()) return 0;
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length + 1 > size) return 0;
        m.write(buf, b);
        m.writeByte(buf + b.length, (byte) 0);
        return 1;
    }

    /** {@code -Drexxjvm.home}, else the directory of the jar; with a trailing slash. */
    static String libraryDirectory() {
        String home = System.getProperty("rexxjvm.home");
        if (home == null) {
            try {
                File jar = new File(Host.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                home = jar.getParent();
            } catch (Exception x) {
                return null;
            }
        }
        return home == null ? null : home.endsWith("/") ? home : home + "/";
    }

    /** A NULL-terminated array of C strings in the module's memory. */
    static List<String> strings(Memory m, int p) {
        List<String> l = new ArrayList<>();
        if (p == 0) return l;
        for (int q; (q = m.readInt(p)) != 0; p += 4) l.add(m.readCString(q, StandardCharsets.UTF_8));
        return l;
    }

    static int commandRun(Memory m, int argvP, int envpP, int cwdP, int inputP, int inputLength, int flags) {
        byte[][] rs = results.get();
        rs[0] = rs[1] = null;
        List<String> argv = strings(m, argvP);
        if (argv.isEmpty() || argv.get(0).isEmpty()) return UNKNOWN_COMMAND;
        ProcessBuilder pb = new ProcessBuilder(argv);
        Map<String, String> env = pb.environment();
        env.clear();
        for (String e : strings(m, envpP)) {
            int eq = e.indexOf('=');
            if (eq > 0) env.put(e.substring(0, eq), e.substring(eq + 1));
        }
        if (cwdP != 0) pb.directory(new File(m.readCString(cwdP, StandardCharsets.UTF_8)));
        boolean out = (flags & OUTPUT) != 0, err = (flags & ERROR) != 0, same = (flags & SAME) != 0;
        // A run with standard streams of its own (a hosted run's) cannot give them to the
        // process: the command's output is copied to them, and it reads no input.
        // A run can say where commands read and write (a server: its client's streams).
        Run run = Run.current();
        ProcessBuilder.Redirect toIn = run == null ? null : run.cmdIn;
        ProcessBuilder.Redirect toOut = run == null ? null : run.cmdOut, toErr = run == null ? null : run.cmdErr;
        OutputStream runOut = run == null || toOut != null ? null : run.out;
        OutputStream runErr = run == null || toErr != null ? null : run.err;
        boolean ownIn = run != null && run.in != null;
        ProcessBuilder.Redirect noInput = ProcessBuilder.Redirect.from(new File("/dev/null"));
        pb.redirectInput((flags & INPUT) != 0 ? ProcessBuilder.Redirect.PIPE
                         : toIn != null ? toIn : ownIn ? noInput : ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(out || runOut != null ? ProcessBuilder.Redirect.PIPE
                          : toOut != null ? toOut : ProcessBuilder.Redirect.INHERIT);
        if (out && same) pb.redirectErrorStream(true);
        else pb.redirectError(err || runErr != null ? ProcessBuilder.Redirect.PIPE
                              : toErr != null ? toErr : ProcessBuilder.Redirect.INHERIT);
        byte[] input = (flags & INPUT) != 0 && inputLength > 0 ? m.readBytes(inputP, inputLength) : new byte[0];

        // what the interpreter wrote so far must come before the command's output
        if (run != null) run.flush();
        else { System.out.flush(); System.err.flush(); }
        Process p;
        try {
            p = pb.start();
        } catch (IOException x) {
            return UNKNOWN_COMMAND;
        }
        Thread feeder = null, outReader = null, errReader = null;
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream(), errBytes = new ByteArrayOutputStream();
        if ((flags & INPUT) != 0) {
            feeder = pump(null, p.getOutputStream(), input);
        }
        if (out) outReader = pump(p.getInputStream(), outBytes, null);
        else if (runOut != null) outReader = pump(p.getInputStream(), runOut, null);
        if (err && !(out && same)) errReader = pump(p.getErrorStream(), errBytes, null);
        else if (!(out && same) && runErr != null) errReader = pump(p.getErrorStream(), runErr, null);
        int rc;
        try {
            rc = p.waitFor();
            for (Thread t : new Thread[] { feeder, outReader, errReader }) if (t != null) t.join();
        } catch (InterruptedException x) {
            p.destroy();
            Thread.currentThread().interrupt();
            return UNKNOWN_COMMAND;
        }
        if (out) rs[0] = outBytes.toByteArray();
        if (err && !(out && same)) rs[1] = errBytes.toByteArray();
        // A process a signal n ended: Java gives 128+n, which cannot be told from an exit
        // status of 128+n, so it goes back as such (SysWasmHost.hpp would take -n). Under
        // ADDRESS SYSTEM the shell gives 128+n anyway, as natively.
        return rc;
    }

    /** Copy in to out (or bytes to out, then close it) on a thread of its own. */
    static Thread pump(InputStream in, OutputStream out, byte[] bytes) {
        Thread t = new Thread(() -> {
            try {
                if (bytes != null) {
                    try (OutputStream o = out) { o.write(bytes); } catch (IOException ignored) { /* closed early */ }
                } else {
                    in.transferTo(out);
                }
            } catch (IOException ignored) {
                // the process went away
            }
        }, "rexxjvm-command-io");
        t.setDaemon(true);
        t.start();
        return t;
    }
}

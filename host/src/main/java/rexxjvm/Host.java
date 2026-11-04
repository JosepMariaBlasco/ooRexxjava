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
import java.util.LinkedHashMap;
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
                                              WinPath.toGuest(System.getProperty("rexxjvm.executable"))) }),
            new HostFunction("rexxjvm", "library_directory", List.of(ValType.I32, ValType.I32), i32,
                (inst, a) -> new long[] { put(inst.memory(), (int) a[0], (int) a[1],
                                              WinPath.toGuest(libraryDirectory())) }),
            new HostFunction("rexxjvm", "signal_wait", i32, i32, (inst, a) -> new long[] { signalWait((int) a[0]) }));
    }

    /*
     * The host's signals, for a process run (a hosted run gets them from its host,
     * Run.signal). SIGINT (Ctrl-C), SIGTERM and SIGHUP become a HALT in the
     * running program, as natively (a second one while the first HALT is still unseen
     * ends the process: signalWait): the module's signal thread (compat/orx_wasi_host.c)
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

    /**
     * The next signal for the module's signal thread; -1: there are none to wait for.
     * last: what the last one did (orxWasmSignal: 1 a HALT posted, 0 the previous HALT
     * still unseen; -1 none yet). With 0 the program is not seeing its HALTs: the signal
     * ends the run as its default action would (Run.unanswered).
     */
    static int signalWait(int last) {
        Run run = Run.current();
        if (run == null || !run.signalsWanted || run.ended()) return -1;
        if (last == 0) {
            run.unanswered(run.lastSignal);
            return -1;
        }
        run.signalWaiting = true;
        try {
            int sig = run.signals.take();
            run.lastSignal = sig;
            return sig;
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
        if (home == null) return null;
        return home.endsWith("/") || home.endsWith(File.separator) ? home : home + File.separator;
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
        Map<String, String> env = new LinkedHashMap<>();
        for (String e : strings(m, envpP)) {
            int eq = e.indexOf('=');
            if (eq > 0) env.put(e.substring(0, eq), e.substring(eq + 1));
        }
        String cwd = cwdP == 0 ? null : m.readCString(cwdP, StandardCharsets.UTF_8);
        if (WinPath.WINDOWS) cwd = WinPath.toHost(cwd);
        if (WinPath.WINDOWS && !windows(argv, env, cwd)) return UNKNOWN_COMMAND;
        // Java looks a bare name up in the JVM's own PATH, not in the one the command
        // gets: a server's runs would find commands in the PATH of whoever started the
        // server. Look it up here in the command's PATH, as execvp does natively.
        String exe = argv.get(0);
        if (!WinPath.WINDOWS && exe.indexOf('/') < 0) {
            exe = search(exe, env.containsKey("PATH") ? env.get("PATH") : "/bin:/usr/bin", cwd);
            if (exe == null) return UNKNOWN_COMMAND;
            argv.set(0, exe);
        }
        // A server's client whose streams are relayed (Relay): the client runs the
        // command, with its own standard streams but for those captured here.
        Run run = Run.current();
        if (run != null && run.relay != null) {
            boolean out = (flags & OUTPUT) != 0, err = (flags & ERROR) != 0, same = (flags & SAME) != 0;
            byte[] input = (flags & INPUT) == 0 ? null
                : inputLength > 0 ? m.readBytes(inputP, inputLength) : new byte[0];
            run.flush();                          // what the interpreter wrote comes first
            try {
                int rc = run.relay.command(argv, env, cwd, input, out, err && !(out && same), out && same, rs);
                return rc == Relay.NOT_STARTED ? UNKNOWN_COMMAND : rc;
            } catch (IOException x) {
                return UNKNOWN_COMMAND;           // the client went away, or the run is ending
            }
        }
        // A server's client with a regular file as standard input: the command gets the
        // file through a new open file description (cmdIn, /proc/PID/fd/0), at offset 0,
        // where natively it shares the client's. rexxjs --orx-stdin-at moves it to where
        // the run's reading is, and reports where the command left it (client/rexxjs.c).
        File report = null;
        if (run != null && run.inFile != null && run.cmdIn != null && (flags & INPUT) == 0) {
            String home = libraryDirectory();
            File rexxjs = home == null ? null : new File(home, "rexxjs");
            if (rexxjs != null && rexxjs.canExecute()) {
                try {
                    report = File.createTempFile("rexxj-stdin", ".at");
                    argv.addAll(0, List.of(rexxjs.getPath(), "--orx-stdin-at",
                        Long.toString(run.inFile.position()), report.getPath()));
                } catch (IOException x) {
                    if (report != null) report.delete();
                    report = null;                    // as before: from the start of the file
                }
            }
        }
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().clear();
        pb.environment().putAll(env);
        if (cwd != null) pb.directory(new File(cwd));
        boolean out = (flags & OUTPUT) != 0, err = (flags & ERROR) != 0, same = (flags & SAME) != 0;
        // A run with standard streams of its own (a hosted run's) cannot give them to the
        // process: the command's output is copied to them, and it reads no input.
        // A run can say where commands read and write (a server: its client's streams).
        ProcessBuilder.Redirect toIn = run == null ? null : run.cmdIn;
        ProcessBuilder.Redirect toOut = run == null ? null : run.cmdOut, toErr = run == null ? null : run.cmdErr;
        OutputStream runOut = run == null || toOut != null ? null : run.out;
        OutputStream runErr = run == null || toErr != null ? null : run.err;
        boolean ownIn = run != null && run.in != null;
        ProcessBuilder.Redirect noInput = ProcessBuilder.Redirect.from(new File(WinPath.WINDOWS ? "NUL" : "/dev/null"));
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
            if (report != null) report.delete();
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
            if (report != null) report.delete();
            Thread.currentThread().interrupt();
            return UNKNOWN_COMMAND;
        }
        if (report != null) {
            try {
                String at = new String(java.nio.file.Files.readAllBytes(report.toPath()), StandardCharsets.US_ASCII).trim();
                if (!at.isEmpty()) run.inFile.position(Long.parseLong(at));
            } catch (IOException | NumberFormatException x) {
                // the command left no offset: the run's stays where it was
            } finally {
                report.delete();
            }
        }
        if (out) rs[0] = outBytes.toByteArray();
        if (err && !(out && same)) rs[1] = errBytes.toByteArray();
        // A process a signal n ended: Java gives 128+n, which cannot be told from an exit
        // status of 128+n, so it goes back as such (SysWasmHost.hpp would take -n). Under
        // ADDRESS SYSTEM the shell gives 128+n anyway, as natively.
        return rc;
    }

    /**
     * Windows: a command as the module gives it, in the host's form; false if there is
     * no such command. The shells' environments come as "/bin/NAME -c command"
     * (SystemCommands.cpp): "" ADDRESS SYSTEM, COMMAND and SH as /bin/sh, which here is
     * cmd.exe (%ComSpec%), as natively on Windows (and /bin/cmd, should the interpreter
     * ever have an ADDRESS CMD: the Unix one has none, it is Error 3); any other shell
     * (bash, pwsh, powershell) is looked up in PATH and given "-c" as it is. ADDRESS
     * PATH's command name is looked up in PATH with PATHEXT. The command's PATH, HOME,
     * TMPDIR and name go back to the host's form, and so do the drives' paths in a
     * shell's command line (WinPath.commandToHost) and ADDRESS PATH's arguments that
     * are one (WinPath.argToHost).
     */
    static boolean windows(List<String> argv, Map<String, String> env, String cwd) {
        String path = null;
        for (Map.Entry<String, String> e : env.entrySet())
            if (e.getKey().equalsIgnoreCase("PATH")) path = e.getValue();
        env.keySet().removeIf(k -> k.equalsIgnoreCase("PATH"));
        if (path != null) {
            path = WinPath.listToHost(path);
            env.put("PATH", path);
        }
        // HOME and TMPDIR: the module has them in its form (Run), the command in the host's
        for (String k : new String[] { "HOME", "TMPDIR" }) {
            String v = env.get(k);
            if (v != null) env.put(k, WinPath.toHost(v));
        }
        String a0 = argv.get(0);
        if (a0.startsWith("/bin/") && argv.size() == 3 && argv.get(1).equals("-c")) {
            String shell = a0.substring(5).toLowerCase(java.util.Locale.ROOT);
            if (shell.equals("sh") || shell.equals("cmd")) {
                String comspec = null;
                for (Map.Entry<String, String> e : env.entrySet())
                    if (e.getKey().equalsIgnoreCase("ComSpec")) comspec = e.getValue();
                if (comspec == null) comspec = System.getenv("ComSpec");
                if (comspec == null) comspec = "cmd.exe";
                // /s /c "command": cmd.exe takes away the first and the last quote, and
                // runs the rest as typed; Java passes a quoted argument as it is
                String command = WinPath.commandToHost(argv.get(2));
                argv.clear();
                argv.addAll(List.of(comspec, "/s", "/c", "\"" + command + "\""));
                return true;
            }
            String exe = searchWindows(shell, path, cwd);
            if (exe == null) return false;
            argv.set(0, exe);
            return true;
        }
        a0 = WinPath.toHost(a0);
        if (a0.indexOf('\\') < 0 && a0.indexOf(':') < 0) {
            String exe = searchWindows(a0, path, cwd);
            if (exe == null) return false;
            a0 = exe;
        } else if (!new File(a0).isAbsolute() && cwd != null) {
            a0 = new File(cwd, a0).getPath();
        }
        argv.set(0, a0);
        for (int i = 1; i < argv.size(); i++) argv.set(i, WinPath.argToHost(argv.get(i)));
        return true;
    }

    /**
     * Windows: the first file called name, or name with one of PATHEXT's extensions,
     * in the current directory or PATH's (';'), as cmd.exe looks; null if none.
     */
    static String searchWindows(String name, String path, String cwd) {
        String pathext = System.getenv("PATHEXT");
        List<String> exts = new ArrayList<>();
        exts.add("");
        for (String x : (pathext == null ? ".COM;.EXE;.BAT;.CMD" : pathext).split(";"))
            if (!x.isEmpty()) exts.add(x);
        List<String> dirs = new ArrayList<>();
        dirs.add(cwd != null ? cwd : ".");
        if (path != null) for (String d : path.split(";")) if (!d.isEmpty()) dirs.add(d);
        boolean hasExt = name.lastIndexOf('.') > Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/'));
        for (String d : dirs) {
            for (String x : exts) {
                if (x.isEmpty() && !hasExt) continue;
                File f = new File(d, name + x);
                if (!f.isAbsolute() && cwd != null) f = new File(cwd, f.getPath());
                if (f.isFile()) return f.getPath();
            }
        }
        return null;
    }

    /**
     * The first executable regular file called name in the directories of path (an
     * empty entry is the current directory, relative ones go from cwd), as execvp
     * looks; null if there is none.
     */
    static String search(String name, String path, String cwd) {
        for (String d : path.split(":", -1)) {
            File f = new File(d.isEmpty() ? "." : d, name);
            if (!f.isAbsolute() && cwd != null) f = new File(cwd, f.getPath());
            if (f.isFile() && f.canExecute()) return f.getPath();
        }
        return null;
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

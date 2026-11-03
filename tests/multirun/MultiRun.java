package rexxjvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Many runs of the module in one JVM (Run, hosted): each with its own arguments,
 * environment, current directory and standard streams, one after another and at once;
 * ended from outside, and halted by a signal; and nothing left behind (threads, memory).
 *
 * <pre>
 * D=/home/claude/work/dist
 * env -u JAVA_TOOL_OPTIONS javac -cp $D/rexxj.jar -d out tests/multirun/MultiRun.java
 * env -u JAVA_TOOL_OPTIONS java -Xss16m -Drexxjvm.executable=$D/rexx -Drexxjvm.home=$D \
 *     -cp out:$D/rexxj.jar rexxjvm.MultiRun
 * </pre>
 *
 * In package rexxjvm: Run is not public (yet).
 */
public class MultiRun {
    static int checks, failures;
    static Path dir;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
    }

    static final class Result {
        int rc;
        String out, err;
        long ms;
        Run run;
    }

    static Path program(String name, String code) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, code);
        return p;
    }

    static Run.Options options(Path program, String... args) {
        Run.Options o = new Run.Options();
        List<String> a = new ArrayList<>();
        a.add(program.toString());
        a.addAll(Arrays.asList(args));
        o.args = a;
        return o;
    }

    /** One hosted run, its output captured. */
    static Result run(Run.Options o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        o.out = out;
        o.err = err;
        if (o.in == null) o.in = new ByteArrayInputStream(new byte[0]);
        long t = System.nanoTime();
        Result r = new Result();
        r.run = new Run(o);
        r.rc = r.run.start();
        r.ms = (System.nanoTime() - t) / 1_000_000;
        r.out = out.toString(StandardCharsets.UTF_8);
        r.err = err.toString(StandardCharsets.UTF_8);
        return r;
    }

    static int wasmThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().startsWith("wasm-") && t.isAlive()) n++;
        return n;
    }

    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("multirun");
        List<WeakReference<Object>> memories = new ArrayList<>();
        int threadsBefore = wasmThreads();

        // one after another: the first pays for the JIT, the others do not
        Path hi = program("hi.rex", "say 'hi' arg(1)\n");
        List<Long> times = new ArrayList<>();
        boolean allOk = true;
        for (int i = 0; i < 12; i++) {
            Result r = run(options(hi, "n" + i));
            times.add(r.ms);
            memories.add(new WeakReference<>(r.run.memory));
            allOk &= r.rc == 0 && r.out.equals("hi n" + i + "\n") && r.err.isEmpty();
        }
        check(allOk, "12 runs one after another, each its own output: ms " + times);

        Path exit7 = program("exit7.rex", "exit 7\n");
        check(run(options(exit7)).rc == 7, "the exit code");

        // the environment and the current directory
        Path envcwd = program("envcwd.rex",
            "say value('MULTIRUN_X',,'ENVIRONMENT')\nsay directory()\n'pwd'\n");
        Path d1 = Files.createDirectories(dir.resolve("one")).toRealPath();
        Run.Options o = options(envcwd);
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("MULTIRUN_X", "first");
        o.env = env;
        o.cwd = d1.toString();
        Result r = run(o);
        check(r.out.equals("first\n" + d1 + "\n" + d1 + "\n"),
              "its environment, current directory, and a command's output: " + r.out.replace("\n", "|"));

        // standard error and standard input
        Path errin = program("errin.rex",
            "call lineout 'STDERR', 'to stderr'\nparse pull line\nsay 'got' line\n");
        o = options(errin);
        o.in = new ByteArrayInputStream("abc\n".getBytes(StandardCharsets.UTF_8));
        r = run(o);
        check(r.out.equals("got abc\n") && r.err.equals("to stderr\n"),
              "its standard input and error: " + r.out.replace("\n", "|") + " / " + r.err.replace("\n", "|"));

        // at once: 4 threads, 5 runs each
        Path busy = program("busy.rex",
            "say 'start' arg(1)\nx = 0\ndo i = 1 to 20000; x = x + i; end\nsay 'end' arg(1) x\n");
        ExecutorService pool = Executors.newFixedThreadPool(4, task -> {
            Thread t = new Thread(null, task, "multirun", Run.STACK);
            t.setDaemon(true);
            return t;
        });
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String tag = "r" + i;
            fs.add(pool.submit(() -> {
                Result x = run(options(busy, tag));
                return x.rc == 0 && x.out.equals("start " + tag + "\nend " + tag + " 200010000\n");
            }));
        }
        boolean concurrentOk = true;
        for (Future<Boolean> f : fs) concurrentOk &= f.get(300, TimeUnit.SECONDS);
        check(concurrentOk, "20 runs on 4 threads at once, none mixed with another");

        // ended from outside, with a second thread running too
        Path forever = program("forever.rex",
            ".t~new~go\nsay 'ready'\ndo forever; nop; end\n::class t\n::method go\nreply\ndo forever; nop; end\n");
        o = options(forever);
        ByteArrayOutputStream fout = new ByteArrayOutputStream();
        o.out = fout;
        o.err = new ByteArrayOutputStream();
        o.in = new ByteArrayInputStream(new byte[0]);
        Run endless = new Run(o);
        Future<Integer> rc = pool.submit(endless::start);
        long deadline = System.currentTimeMillis() + 60_000;
        while (!fout.toString(StandardCharsets.UTF_8).contains("ready") && System.currentTimeMillis() < deadline)
            Thread.sleep(20);
        Thread.sleep(200);
        long t0 = System.nanoTime();
        endless.end(9);
        int code = rc.get(60, TimeUnit.SECONDS);
        int left = endless.awaitThreads(10, TimeUnit.SECONDS);
        check(code == 9 && left == 0, "ended from outside: exit code " + code + ", threads left " + left
              + ", " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        memories.add(new WeakReference<>(endless.memory));
        endless = null;

        // a signal: a HALT in the program
        Path halt = program("halt.rex",
            "signal on halt\nsay 'ready'\ndo forever; call syssleep 0.05; end\nhalt:\nsay 'halted'\nexit 4\n");
        o = options(halt);
        ByteArrayOutputStream hout = new ByteArrayOutputStream();
        o.out = hout;
        o.err = new ByteArrayOutputStream();
        o.in = new ByteArrayInputStream(new byte[0]);
        Run halted = new Run(o);
        Future<Integer> hrc = pool.submit(halted::start);
        deadline = System.currentTimeMillis() + 60_000;
        while (!hout.toString(StandardCharsets.UTF_8).contains("ready") && System.currentTimeMillis() < deadline)
            Thread.sleep(20);
        Thread.sleep(300);                    // the signal thread waits by now
        halted.signal(2);
        code = hrc.get(60, TimeUnit.SECONDS);
        check(code == 4 && hout.toString(StandardCharsets.UTF_8).equals("ready\nhalted\n"),
              "SIGINT as a HALT: exit code " + code + ", " + hout.toString(StandardCharsets.UTF_8).replace("\n", "|"));
        halted = null;

        // nothing left behind
        Thread.sleep(500);
        int threadsAfter = wasmThreads();
        check(threadsAfter <= threadsBefore, "no wasm threads left: " + threadsAfter + " (before: " + threadsBefore + ")");
        r = null;
        for (int i = 0; i < 10; i++) {
            System.gc();
            Thread.sleep(100);
        }
        int alive = 0;
        for (WeakReference<Object> w : memories) if (w.get() != null) alive++;
        check(alive == 0, "every run's memory collected: " + alive + " of " + memories.size() + " still reachable");

        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}

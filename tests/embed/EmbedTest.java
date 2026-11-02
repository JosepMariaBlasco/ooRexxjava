import java.util.*;
import java.util.concurrent.atomic.*;
import rexxjvm.Rexx;

/**
 * Tests of the embedded mode (rexxjvm.Rexx). See README.md beside it.
 * java -Xss16m -cp dist/rexxj.jar:out EmbedTest [threads] [calls]
 */
public final class EmbedTest {
    static final AtomicInteger failures = new AtomicInteger(), checks = new AtomicInteger();

    static void check(String what, Object got, Object expected) {
        checks.incrementAndGet();
        if (!Objects.equals(got, expected)) {
            failures.incrementAndGet();
            System.out.println("FAIL " + what + ": got " + got + ", expected " + expected);
        }
    }

    public static void main(String[] args) throws Exception {
        int nThreads = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        int nCalls = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        String dir = System.getProperty("test.dir", "tests/embed");

        long t0 = System.nanoTime();
        try (Rexx rexx = Rexx.start(); Rexx.Interpreter in = rexx.newInterpreter()) {
            System.out.printf("start + interpreter: %.0f ms%n", (System.nanoTime() - t0) / 1e6);

            t0 = System.nanoTime();
            check("eval", in.eval("return 6*7"), "42");
            System.out.printf("first eval: %.0f ms%n", (System.nanoTime() - t0) / 1e6);
            check("eval with arguments", in.eval("use arg a, b; return a || '+' || b", "x", "y"), "x+y");
            check("omitted argument", in.eval("return arg(1, 'e') arg(2, 'o') arg()", "x", null, "z"), "1 1 3");
            check("no result", in.eval("nop"), null);
            check("not ASCII", in.eval("return arg(1)~length", "día"), "4");   // UTF-8 bytes
            check("say", in.eval("say 'hello from Rexx'; return 1"), "1");
            check("program", in.callProgram(dir + "/args.rex", "uno", "dos"), "2:uno,dos");
            check("program's name", in.callProgram(dir + "/args.rex").startsWith("0:"), true);

            try {
                in.eval("x = 1 +");
                check("syntax error raised", false, true);
            } catch (Rexx.RexxException x) {
                check("syntax error code", x.code(), 35001L);
                check("syntax error rc", x.rc(), 35L);
                System.out.println("  (" + x.getMessage() + ")");
            }
            try {
                in.callProgram(dir + "/fails.rex");
                check("runtime error raised", false, true);
            } catch (Rexx.RexxException x) {
                check("runtime error code", x.code() / 1000, 41L);
                check("runtime error line", x.line(), 3L);
                check("runtime error program", x.program() != null && x.program().endsWith("fails.rex"), true);
                System.out.println("  (" + x.getMessage() + ")");
            }
            try {
                in.callProgram(dir + "/no-such-program.rex");
                check("missing program raised", false, true);
            } catch (Rexx.RexxException x) {
                check("missing program code", x.code() / 1000, 3L);
            }
            check("works after errors", in.eval("return 'ok'"), "ok");

            // a deep recursion, on a thread with a large Java stack
            String[] deep = new String[1];
            Thread d = new Thread(null, () -> deep[0] = in.callProgram(dir + "/deep.rex", "2000"), "deep", 512L << 20);
            d.start(); d.join();
            check("deep recursion", deep[0], "2000");

            // a Rexx thread (REPLY) still running when the call returns
            check("reply", in.eval("return .t~new~go\n::class t\n::method go\nreply 'early'\ncall SysSleep 0.2\nsay 'after reply'"), "early");

            // .local is per instance
            try (Rexx.Interpreter other = rexx.newInterpreter()) {
                in.eval(".local~embedTest = 'first'");
                other.eval(".local~embedTest = 'second'");
                check(".local, first instance", in.eval("return .embedTest"), "first");
                check(".local, second instance", other.eval("return .embedTest"), "second");
            }

            // many foreign threads, Java's default stack, at once
            AtomicLong ok = new AtomicLong();
            List<Thread> ts = new ArrayList<>();
            t0 = System.nanoTime();
            for (int i = 0; i < nThreads; i++) {
                int ti = i;
                Thread t = new Thread(() -> {
                    for (int j = 0; j < nCalls; j++) {
                        String r = in.eval("use arg a, b; return a * 1000 + b", String.valueOf(ti), String.valueOf(j));
                        if (String.valueOf(ti * 1000 + j).equals(r)) ok.incrementAndGet();
                        else check("thread " + ti + " call " + j, r, String.valueOf(ti * 1000 + j));
                    }
                }, "foreign-" + i);
                ts.add(t); t.start();
            }
            for (Thread t : ts) t.join();
            double ms = (System.nanoTime() - t0) / 1e6;
            check("foreign calls", ok.get(), (long) nThreads * nCalls);
            System.out.printf("%d threads x %d calls: %.0f ms (%.2f ms per call)%n",
                nThreads, nCalls, ms, ms / (nThreads * nCalls));

            // one thread, calls in a row
            t0 = System.nanoTime();
            for (int j = 0; j < 500; j++) in.eval("return 1");
            System.out.printf("500 calls in a row: %.2f ms per call%n", (System.nanoTime() - t0) / 1e6 / 500);

            // instances created and closed again and again
            t0 = System.nanoTime();
            for (int j = 0; j < 20; j++) {
                try (Rexx.Interpreter x = rexx.newInterpreter()) {
                    check("instance " + j, x.eval("return arg(1) + 1", String.valueOf(j)), String.valueOf(j + 1));
                }
            }
            System.out.printf("20 instances created, used, closed: %.0f ms each%n", (System.nanoTime() - t0) / 1e6 / 20);

            Rexx.Interpreter left = rexx.newInterpreter();   // closed by rexx.close()
            check("left open", left.eval("return 'open'"), "open");
        }
        System.out.println(failures.get() == 0 ? "ALL OK (" + checks.get() + " checks)"
                                               : "FAILURES: " + failures.get() + " of " + checks.get());
        System.exit(failures.get() == 0 ? 0 : 1);
    }
}

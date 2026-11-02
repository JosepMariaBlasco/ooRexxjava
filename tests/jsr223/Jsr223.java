import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

/**
 * A Java program hosting Rexx through JSR-223 (BSF4ooRexx's RexxScriptEngine),
 * with nothing of ours in sight: no rexxjvm class is named, the module is started
 * when BSF4ooRexx first calls a native method. Run with tests/jsr223/run.sh.
 */
public class Jsr223 {
    public interface Calc {
        Object multiply(Object a, Object b);
        String getString();
    }

    static int checks, failures;

    static void check(String what, Object expected, Object actual) {
        checks++;
        boolean ok = expected == null ? actual == null : actual != null && expected.toString().equals(actual.toString());
        if (!ok) failures++;
        System.out.println((ok ? "ok    " : "FAIL  ") + what + (ok ? "" : ": expected <" + expected + ">, got <" + actual + ">"));
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine e = manager.getEngineByName("rexx");
        check("engine by name", "Open Object Rexx (ooRexx)", e == null ? null : e.getFactory().getEngineName());
        check("engine by extension", true, manager.getEngineByExtension("rex") != null);

        // eval, and the time to the first result (the module starts here)
        check("eval", 2, e.eval("return 1+1"));
        System.out.printf("      (first eval after %.1f s)%n", (System.nanoTime() - t0) / 1e9);
        check("eval, a string", "!ih", e.eval("return reverse('hi!')"));

        // SAY goes to the context's writer. Set before the engine's first eval:
        // RexxScriptEngine does not replace .output once it is a .bsf.outputStream
        // (its comment on rexxRedirectStandardFilesCode), so a writer set later is ignored.
        ScriptEngine ew = manager.getEngineByName("rexx");
        StringWriter out = new StringWriter();
        ew.getContext().setWriter(out);
        ew.eval("say 'hello from Rexx'");
        check("say to the context's writer", "REXXout>hello from Rexx", out.toString().trim());

        // Compilable
        CompiledScript cs = ((Compilable) e).compile("parse arg a, b; return a * b");
        Bindings b = e.createBindings();
        b.put(ScriptEngine.ARGV, new Object[] { 6, 7 });
        check("compiled script, arguments in ARGV", 42, cs.eval(b));
        b.put(ScriptEngine.ARGV, new Object[] { 3, 5 });
        check("compiled script, again", 15, cs.eval(b));

        // bindings: an attribute of the context, read from Rexx
        e.put("greeting", "hola");
        check("binding read from Rexx", "HOLA",
              e.eval("return upper(arg(arg())~scriptContext~getAttribute('greeting'))"));

        // Invocable: public routines, methods of a Rexx object, Java interfaces
        String code = "return .test~new\n"
            + "::requires BSF.CLS\n"
            + "::class test\n"
            + "::method adder\n  parse arg a1, a2\n  return a1 + a2\n"
            + "::method getString\n  return 'getString'\n"
            + "::method multiply\n  parse arg a1, a2\n  return a1 * a2\n"
            + "::routine getString public\n  return 'routine: getString'\n"
            + "::routine multiply public\n  return arg(1) * arg(2) / 3\n";
        Object obj = e.eval(code);
        Invocable inv = (Invocable) e;
        check("invokeMethod", 3, inv.invokeMethod(obj, "adder", 1, 2));
        check("invokeFunction", 15, inv.invokeFunction("multiply", 9, 5));
        check("invokeFunction, no arguments", "routine: getString", inv.invokeFunction("getString"));
        Calc viaObject = inv.getInterface(obj, Calc.class);
        check("getInterface on a Rexx object", 12, viaObject.multiply(3, 4));
        check("getInterface on a Rexx object, again", "getString", viaObject.getString());
        Calc viaRoutines = inv.getInterface(Calc.class);
        check("getInterface on the public routines", 10, viaRoutines.multiply(6, 5));

        // Rexx using a Java object the host hands it
        List<String> list = new ArrayList<>();
        b = e.createBindings();
        b.put(ScriptEngine.ARGV, new Object[] { list });
        e.eval("use arg l; do i = 1 to 3; l~add('item' i); end; ::requires BSF.CLS", b);
        check("Rexx filling a Java list", "[item 1, item 2, item 3]", list);

        // errors are ScriptExceptions
        String message = null;
        try {
            e.eval("x = 1 +");
        } catch (ScriptException x) {
            message = x.getMessage();
        }
        check("a syntax error is a ScriptException", true, message != null && message.contains("Error 35"));
        message = null;
        try {
            e.eval("return 1 / 0");
        } catch (ScriptException x) {
            message = x.getMessage();
        }
        check("a run-time error is a ScriptException", true, message != null && message.contains("Error 42"));

        // several Java threads at once, each calling Rexx on its own thread
        // (invokeFunction looks in the latest script evaluated: evaluate the routines again)
        e.eval(code);
        int single = 200, good = 0;
        long ts0 = System.nanoTime();
        for (int i = 0; i < single; i++)
            if (String.valueOf(i).equals(String.valueOf(inv.invokeFunction("multiply", 3, i)))) good++;
        check(single + " calls in a row", single, good);
        System.out.printf("      (%.2f ms per call)%n", (System.nanoTime() - ts0) / 1e6 / single);
        int threads = 8, calls = 25;
        AtomicInteger right = new AtomicInteger();
        List<Thread> ts = new ArrayList<>();
        long t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int n = t;
            Thread th = new Thread(() -> {
                for (int i = 0; i < calls; i++) {
                    try {
                        Object r = inv.invokeFunction("multiply", n, 3 * i);
                        if (String.valueOf(n * i).equals(String.valueOf(r))) right.incrementAndGet();
                    } catch (Exception x) {
                        System.out.println("      thread " + n + ": " + x);
                    }
                }
            });
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) th.join();
        check(threads + " threads, " + calls + " calls each", threads * calls, right.get());
        System.out.printf("      (%.2f ms per call)%n", (System.nanoTime() - t1) / 1e6 / (threads * calls));

        // a second engine: a separate interpreter instance
        ScriptEngine e2 = manager.getEngineByName("ooRexx");
        e2.eval(".local~marker = 'second'");
        check("second engine, its own .local", "second", e2.eval("return .local~marker"));
        check("first engine, untouched", ".MARKER", e.eval("return .marker"));

        System.out.println(checks + " checks, " + failures + " failures");
        // BSF4ooRexx's RexxCleanupRef thread is not a daemon: exit explicitly
        System.exit(failures == 0 ? 0 : 1);
    }
}

package rexxjvm;

import java.util.Arrays;

/**
 * Runs a wasm32-wasip1-threads command module on Chicory, with wasi-threads (thread-spawn).
 *
 * <p>{@code RunWasm module.wasm args...} parses the module and translates it to JVM
 * bytecode at start-up. A jar built with the {@code embedded} Maven profile carries
 * {@code rexx.wasm} already translated ({@code rexxjvm.RexxModule}); there,
 * {@code RunWasm args...} runs it, and every argument goes to the module.
 *
 * <p>The program is a process run ({@link Run}): it owns the JVM, which ends with it.
 */
public final class RunWasm {
    private RunWasm() {}

    public static void main(String[] args) throws Exception {
        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try { run(args); } catch (Throwable x) { failure[0] = x; }
        }, "wasm-main", Run.STACK);
        t.start();
        t.join();
        if (failure[0] instanceof Exception) throw (Exception) failure[0];
        if (failure[0] != null) throw (Error) failure[0];
    }

    static void run(String[] args) throws Exception {
        Run.Options o = new Run.Options();
        o.args = Arrays.asList(args);
        o.process = true;
        Run run = new Run(o);
        Host.installSignals(run);
        int rc = run.start();
        System.out.flush();
        System.exit(rc);
    }
}

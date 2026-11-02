import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.*;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryImport;
import com.dylibso.chicory.wasm.types.ValType;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

/**
 * Prototype harness for proto.wasm (see proto.c).
 * java -cp rexxj.jar:. Proto proto.wasm [threads] [entries] [depth]; -Dinterp=true: Chicory's interpreter.
 */
public final class Proto {
    static WasmModule module;
    static ImportValues imports;
    static Function<Instance, Machine> machine;
    static final AtomicInteger nextTid = new AtomicInteger(1);

    /** A foreign thread's request to enter the module. */
    static final class Entry {
        final Thread caller = Thread.currentThread();
        final Consumer2 work;
        final SynchronousQueue<int[]> handover = new SynchronousQueue<>();
        Entry(Consumer2 work) { this.work = work; }
    }
    interface Consumer2 { void accept(Instance inst) throws Exception; }

    static final BlockingQueue<Integer> requests = new LinkedBlockingQueue<>();
    static final Map<Integer, Entry> entries = new ConcurrentHashMap<>();
    static final AtomicInteger nextReq = new AtomicInteger(1);
    /** Set on the dispatcher thread between dispatch_wait and the thread-spawn it causes. */
    static final ThreadLocal<Entry> pendingHandover = new ThreadLocal<>();
    static final AtomicInteger failures = new AtomicInteger();

    static void fail(String s) { failures.incrementAndGet(); System.err.println("FAIL: " + s); }

    /** Called on a foreign Java thread: runs work inside the module, on this very thread. */
    static void enter(Consumer2 work) throws Exception {
        Entry e = new Entry(work);
        int req = nextReq.getAndIncrement();
        entries.put(req, e);
        requests.put(req);
        int[] ts = e.handover.take();               // (tid, startArg) from thread-spawn
        Instance ti = newInstance();
        ti.export("wasi_thread_start").apply(ts[0], ts[1]);
        entries.remove(req);
    }

    public static void main(String[] args) throws Exception {
        module = Parser.parse(new File(args[0]));
        int nThreads = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        int nEntries = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        int depth = args.length > 3 ? Integer.parseInt(args[3]) : 5;
        boolean interp = Boolean.getBoolean("interp");

        WasiPreview1 wasi = WasiPreview1.builder()
            .withOptions(WasiOptions.builder().inheritSystem().withArguments(List.of("proto")).build()).build();
        MemoryImport mi = module.importSection().stream()
            .filter(i -> i instanceof MemoryImport).map(i -> (MemoryImport) i).findFirst().orElseThrow();
        Memory memory = new ByteArrayMemory(mi.limits());

        HostFunction spawn = new HostFunction("wasi", "thread-spawn",
            List.of(ValType.I32), List.of(ValType.I32), (inst, a) -> {
                int tid = nextTid.getAndIncrement();
                int startArg = (int) a[0];
                Entry e = pendingHandover.get();
                if (e != null) {                     // hand the new pthread to the foreign thread
                    pendingHandover.remove();
                    try { e.handover.put(new int[] { tid, startArg }); }
                    catch (InterruptedException x) { throw new RuntimeException(x); }
                    return new long[] { tid };
                }
                Thread t = new Thread(null, () -> {
                    try { newInstance().export("wasi_thread_start").apply(tid, startArg); }
                    catch (Throwable x) { x.printStackTrace(); System.exit(70); }
                }, "wasm-" + tid, 256L << 20);
                t.setDaemon(true);
                t.start();
                return new long[] { tid };
            });

        HostFunction javaCall = new HostFunction("proto", "java_call",
            List.of(ValType.I32, ValType.I32), List.of(ValType.I32), (inst, a) -> {
                // H1: back into the module, same instance, same thread
                long[] r = inst.export("reenter").apply(a[0], a[1]);
                return new long[] { r[0] };
            });
        HostFunction dispatchWait = new HostFunction("proto", "dispatch_wait",
            List.of(), List.of(ValType.I32), (inst, a) -> {
                try {
                    int req = requests.take();
                    if (req > 0) pendingHandover.set(entries.get(req));
                    return new long[] { req };
                } catch (InterruptedException x) { throw new RuntimeException(x); }
            });
        HostFunction entryRun = new HostFunction("proto", "entry_run",
            List.of(ValType.I32), List.of(), (inst, a) -> {
                Entry e = entries.get((int) a[0]);
                if (Thread.currentThread() != e.caller)
                    fail("entry " + a[0] + " runs on " + Thread.currentThread() + ", not " + e.caller);
                try { e.work.accept(inst); }
                catch (Exception x) { fail("work: " + x); }
                return null;
            });
        HostFunction mainWait = new HostFunction("proto", "main_wait",
            List.of(), List.of(), (inst, a) -> {
                try { foreignTests(nThreads, nEntries, depth); }
                catch (Exception x) { fail("foreign tests: " + x); x.printStackTrace(); }
                try { requests.put(-1); } catch (InterruptedException x) { throw new RuntimeException(x); }
                return null;
            });

        imports = ImportValues.builder()
            .addFunction(wasi.toHostFunctions())
            .addFunction(spawn, javaCall, dispatchWait, entryRun, mainWait)
            .addMemory(new ImportMemory(mi.module(), mi.name(), memory))
            .build();
        long t0 = System.nanoTime();
        machine = interp ? null : MachineFactoryCompiler.compile(module);
        System.out.printf("machine: %s (%d ms)%n", interp ? "interpreter" : "compiled", (System.nanoTime() - t0) / 1_000_000);

        Throwable[] failure = new Throwable[1];
        Thread mainT = new Thread(null, () -> {
            try { newInstance().export("_start").apply(); }
            catch (WasiExitException x) { if (x.exitCode() != 0) fail("exit " + x.exitCode()); }
            catch (Throwable x) { failure[0] = x; }
        }, "wasm-main", 256L << 20);
        mainT.start();
        mainT.join();
        if (failure[0] != null) { failure[0].printStackTrace(); failures.incrementAndGet(); }
        System.out.println(failures.get() == 0 ? "ALL OK" : "FAILURES: " + failures.get());
        System.exit(failures.get() == 0 ? 0 : 1);
    }

    /** H2: plain Java threads (default stack size, like Swing's or a pool's) enter the module. */
    static void foreignTests(int nThreads, int nEntries, int depth) throws Exception {
        // one entry, timed alone
        long t0 = System.nanoTime();
        Thread one = new Thread(() -> {
            try { enter(inst -> check(inst, depth)); } catch (Exception x) { fail("single: " + x); }
        }, "foreign-single");
        one.start(); one.join();
        System.out.printf("H2 single foreign entry: %.2f ms%n", (System.nanoTime() - t0) / 1e6);

        AtomicLong ok = new AtomicLong();
        List<Thread> ts = new ArrayList<>();
        t0 = System.nanoTime();
        for (int i = 0; i < nThreads; i++) {
            Thread t = new Thread(() -> {
                for (int j = 0; j < nEntries; j++) {
                    try { enter(inst -> { check(inst, depth); ok.incrementAndGet(); }); }
                    catch (Exception x) { fail("entry: " + x); }
                }
            }, "foreign-" + i);
            ts.add(t); t.start();
        }
        for (Thread t : ts) t.join();
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf("H2 %d foreign threads x %d entries: %d ok, %.1f ms (%.3f ms per entry)%n",
            nThreads, nEntries, ok.get(), ms, ms / (nThreads * nEntries));

        // the same thread entering again and again: identity kept every time
        Thread again = new Thread(() -> {
            try { for (int j = 0; j < 50; j++) enter(inst -> check(inst, depth)); }
            catch (Exception x) { fail("again: " + x); }
        }, "foreign-again");
        again.start(); again.join();
        System.out.println("H2 one thread entering 50 times: done");
    }

    /** Inside the module on a foreign thread: H1 re-entry chain, TLS, pthread identity. */
    static void check(Instance inst, int depth) {
        int self = (int) inst.export("self_id").apply()[0];
        long r = inst.export("reenter").apply(depth, 0)[0];
        if (r != depth) fail("reenter returned " + r + ", expected " + depth);
        int tls = (int) inst.export("tls_get").apply()[0];
        if (tls != depth + 1) fail("tls " + tls + ", expected " + (depth + 1));
        int self2 = (int) inst.export("self_id").apply()[0];
        if (self != self2 || self == 0) fail("pthread_self " + self + " / " + self2);
    }

    static Instance newInstance() {
        Instance.Builder b = Instance.builder(module).withImportValues(imports).withStart(false);
        if (machine != null) b = b.withMachineFactory(machine);
        return b.build();
    }
}
